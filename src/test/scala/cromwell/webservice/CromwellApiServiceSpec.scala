package cromwell.webservice

import java.util.UUID

import akka.actor.{Actor, Props}
import cromwell.binding._
import cromwell.binding.values.{WdlFile, WdlInteger}
import cromwell.engine._
import cromwell.engine.workflow.WorkflowManagerActor.{SubmitWorkflow, WorkflowAbort, WorkflowOutputs, WorkflowStatus}
import cromwell.util.SampleWdl.HelloWorld
import org.scalatest.{FlatSpec, Matchers}
import spray.http._
import spray.json.DefaultJsonProtocol._
import spray.json._
import spray.testkit.ScalatestRouteTest

object MockWorkflowManagerActor {
  sealed trait WorkflowManagerMessage
  case class SubmitWorkflow(wdl: WdlSource, inputs: WorkflowRawInputs) extends WorkflowManagerMessage
  case class WorkflowStatus(id: WorkflowId) extends WorkflowManagerMessage
  case class WorkflowOutputs(id: WorkflowId) extends WorkflowManagerMessage

  val createdWorkflowId = UUID.randomUUID()
  val runningWorkflowId = UUID.randomUUID()
  val unknownId = UUID.randomUUID()
  val submittedWorkflowId = UUID.randomUUID()
  val abortedWorkflowId = UUID.randomUUID()

  def props: Props = Props(classOf[MockWorkflowManagerActor])
}

class MockWorkflowManagerActor extends Actor  {

  def receive = {
    case SubmitWorkflow(wdlSource, wdlJson, rawInputs) =>
      sender ! MockWorkflowManagerActor.submittedWorkflowId

    case WorkflowStatus(id) =>
      val msg = id match {
        case MockWorkflowManagerActor.runningWorkflowId =>
          Some(WorkflowRunning)
        case MockWorkflowManagerActor.abortedWorkflowId =>
          Some(WorkflowAborted)
        case _ =>
          None
      }
      sender ! msg

    case WorkflowAbort(id) =>
      val msg = id match {
        case MockWorkflowManagerActor.runningWorkflowId =>
          Some(WorkflowRunning)
        case _ =>
          None
      }
      sender ! msg

    case WorkflowOutputs(id) =>
      val msg = Map(
        "three_step.cgrep.count" -> WdlInteger(8),
        "three_step.ps.procs" -> WdlFile("/tmp/ps.stdout.tmp"),
        "three_step.wc.count" -> WdlInteger(8)
      )
      sender ! msg
  }
}

object CromwellApiServiceSpec {
  val MalformedInputsJson : String = "foobar bad json!"
  val MalformedWdl : String = "foobar bad wdl!"
}

class CromwellApiServiceSpec extends FlatSpec with CromwellApiService with ScalatestRouteTest with Matchers {
  def actorRefFactory = system
  val workflowManager = system.actorOf(Props(new MockWorkflowManagerActor()))
  val version = "v1"

  s"CromwellApiService $version" should "return 404 for get of unknown workflow" in {
    Get(s"/workflows/$version/${MockWorkflowManagerActor.unknownId}") ~>
      sealRoute(queryRoute) ~>
      check {
        assertResult(StatusCodes.NotFound) {
          status
        }
      }
  }

  it should "return 400 for get of a malformed workflow id" in {
    Get(s"/workflows/$version/foobar/status") ~>
      queryRoute ~>
      check {
        assertResult(StatusCodes.BadRequest) {
          status
        }
      }
  }

  it should "return 200 for get of a known workflow id" in {
    Get(s"/workflows/$version/${MockWorkflowManagerActor.runningWorkflowId}/status") ~>
      queryRoute ~>
      check {
        assertResult(StatusCodes.OK) {
          status
        }

        assertResult(
          s"""{
             |  "id": "${MockWorkflowManagerActor.runningWorkflowId.toString}",
             |  "status": "Running"
             |}""".stripMargin) {
          responseAs[String]
        }
      }
  }

  "CromwellApiService" should "return 404 for abort of unknown workflow" in {
    Post(s"/workflows/$version/${MockWorkflowManagerActor.unknownId}/abort") ~>
      abortRoute ~>
      check {
        assertResult(StatusCodes.NotFound) {
          status
        }
      }
  }

  it should "return 400 for abort of a malformed workflow id" in {
    Post(s"/workflows/$version/foobar/abort") ~>
      abortRoute ~>
      check {
        assertResult(StatusCodes.BadRequest) {
          status
        }
      }
  }

  it should "return 403 for abort of a workflow in a terminal state" in {
    Post(s"/workflows/$version/${MockWorkflowManagerActor.abortedWorkflowId}/abort") ~>
    abortRoute ~>
    check {
      assertResult(StatusCodes.Forbidden) {
        status
      }
    }
  }

  it should "return 200 for abort of a known workflow id" in {
    Post(s"/workflows/$version/${MockWorkflowManagerActor.runningWorkflowId}/abort") ~>
      abortRoute ~>
      check {
        assertResult(StatusCodes.OK) {
          status
        }

        assertResult(
          s"""{
             |  "id": "${MockWorkflowManagerActor.runningWorkflowId.toString}",
             |  "status": "Aborted"
             |}"""
            .stripMargin) {
          responseAs[String]
        }
      }
  }

  s"Cromwell submit workflow API $version" should "return 201 for a successful workflow submission " in {
    Post("/workflows/$version", FormData(Seq("wdlSource" -> HelloWorld.wdlSource(), "workflowInputs" -> HelloWorld.rawInputs.toJson.toString()))) ~>
      submitRoute ~>
      check {
        assertResult(StatusCodes.Created) {
          status
        }
        assertResult(
          s"""{
             |  "id": "${MockWorkflowManagerActor.submittedWorkflowId.toString}",
             |  "status": "Submitted"
             |}""".stripMargin) {
          responseAs[String]
        }
      }
  }

  it should "return 400 for a malformed JSON " in {
    Post("/workflows/$version", FormData(Seq("wdlSource" -> HelloWorld.wdlSource(), "workflowInputs" -> CromwellApiServiceSpec.MalformedInputsJson))) ~>
      submitRoute ~>
      check {
        assertResult(StatusCodes.BadRequest) {
          status
        }
        assertResult("workflowInput JSON was malformed") {
          responseAs[String]
        }
      }
  }

  s"Cromwell workflow outputs API $version" should "return 200 with GET of outputs on successful execution of workflow" in {
    Get(s"/workflows/$version/${MockWorkflowManagerActor.submittedWorkflowId.toString}/outputs") ~>
      outputsRoute ~>
      check {
        assertResult(StatusCodes.OK) {
          status
        }
        assertResult(
          s"""{
             |  "id": "${MockWorkflowManagerActor.submittedWorkflowId.toString}",
             |  "outputs": {
             |    "three_step.cgrep.count": 8,
             |    "three_step.ps.procs": "/tmp/ps.stdout.tmp",
             |    "three_step.wc.count": 8
             |  }
             |}""".stripMargin) {
            responseAs[String]
          }
      }
  }

  it should "return 405 with POST of outputs on successful execution of workflow" in {
    Post(s"/workflows/$version/${MockWorkflowManagerActor.submittedWorkflowId.toString}/outputs") ~>
      sealRoute(outputsRoute) ~>
      check {
        assertResult(StatusCodes.MethodNotAllowed) {
          status
        }
      }
  }
}
