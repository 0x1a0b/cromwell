package cromwell

import java.util.UUID

import akka.actor.ActorSystem
import akka.actor.SupervisorStrategy.Stop
import akka.testkit._
import com.typesafe.config.ConfigFactory
import cromwell.binding.values.WdlValue
import cromwell.binding.{FullyQualifiedName, WdlBinding}
import cromwell.engine.WorkflowActor._
import cromwell.engine.{UnsatisfiedInputsException, WorkflowActor}

import scala.concurrent.duration._
import scala.language.postfixOps

object WorkflowActorSpec {
  val config =
    """
      |akka {
      |  loggers = ["akka.testkit.TestEventListener"]
      |  loglevel = "DEBUG"
      |  actor.debug.receive = on
      |}
    """.stripMargin

  val HelloWdl =
    """
      |task hello {
      |  command {
      |    echo "Hello ${addressee}!"
      |  }
      |  output {
      |    String salutation = read_string("stdout")
      |  }
      |}
      |
      |workflow hello {
      |  call hello
      |}
    """.stripMargin
}

// Copying from http://doc.akka.io/docs/akka/snapshot/scala/testkit-example.html#testkit-example
class WorkflowActorSpec extends CromwellSpec(ActorSystem("WorkflowActorSpec", ConfigFactory.parseString(WorkflowActorSpec.config))) {
  import cromwell.binding.WdlImplicits._

  val helloBinding = WdlBinding.process(WorkflowActorSpec.HelloWdl)

  def buildWorkflowActor(name: String = UUID.randomUUID().toString,
                         inputs: Map[FullyQualifiedName, WdlValue] = Map(Addressee -> "world".toWdlValue)): TestActorRef[WorkflowActor] = {
    val binding = WdlBinding.process(WorkflowActorSpec.HelloWdl)
    val props = WorkflowActor.buildWorkflowActorProps(binding, inputs)
    TestActorRef(props, "Workflow-" + name)
  }

  override def afterAll() {
    shutdown()
  }

  val Addressee = "hello.hello.addressee"
  val TestExecutionTimeout = 500 milliseconds

  "A WorkflowActor" should {

    "start" in {
      within(TestExecutionTimeout) {
        val workflowActor = buildWorkflowActor("started")
        workflowActor ! Start
        expectMsgPF() {
          case Started => ()
        }
        // TODO this is not examining the result, nor message flow between workflow and task actors.
        expectMsgPF() {
          case Failed(t) => fail(t)
          case Done(outputs) => ()
        }
      }
    }

    "fail to construct with missing inputs" in {
      intercept[UnsatisfiedInputsException] {
        buildWorkflowActor(inputs = Map.empty)
      }
    }

    "fail to construct with inputs of the wrong type" in {
      intercept[UnsatisfiedInputsException] {
        buildWorkflowActor(inputs = Map(Addressee -> 3.toWdlValue))
      }
    }

    "fail to stop when not first started" in {
      within(TestExecutionTimeout) {
        val probe = TestProbe()
        val workflowActor = buildWorkflowActor("fail to stop")
        probe watch workflowActor
        workflowActor ! Stop
        expectMsgPF() {
          case InvalidOperation(_) => ()
        }
      }
    }

    "stop" in {
      within(TestExecutionTimeout) {
        val probe = TestProbe()
        val workflowActor = buildWorkflowActor("stop")
        probe watch workflowActor

        ignoreMsg {
          case Done(_) => true
        }
        workflowActor ! Start
        workflowActor ! Stop
        expectMsgPF() {
          case Started => ()
        }
        expectMsgPF() {
          case Stopped => ()
        }

        probe expectTerminated workflowActor
      }
    }
  }
}
