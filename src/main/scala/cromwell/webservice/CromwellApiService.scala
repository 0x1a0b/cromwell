package cromwell.webservice

import akka.actor.{Props, Actor, ActorRef}
import com.typesafe.config.Config
import cromwell.engine.workflow.{ValidateActor, WorkflowOptions}
import cromwell.engine.{WorkflowId, WorkflowSourceFiles}
import lenthall.spray.SwaggerUiResourceHttpService
import lenthall.spray.WrappedRoute._
import lenthall.config.ScalaConfig._
import spray.http.StatusCodes
import spray.json._
import spray.routing.Directive.pimpApply
import spray.routing._

import scala.util.{Failure, Success, Try}

trait SwaggerService extends SwaggerUiResourceHttpService {
  override def swaggerServiceName = "cromwell"

  override def swaggerUiVersion = "2.1.1"
}

object CromwellApiServiceActor {
  def props(workflowManagerActorRef: ActorRef, config: Config): Props = {
    Props(classOf[CromwellApiServiceActor], workflowManagerActorRef, config)
  }
}

class CromwellApiServiceActor(val workflowManager: ActorRef, config: Config)
  extends Actor with CromwellApiService with SwaggerService {
  implicit def executionContext = actorRefFactory.dispatcher
  def actorRefFactory = context

  def possibleRoutes = workflowRoutes.wrapped("api", config.getBooleanOr("api.routeUnwrapped")) ~ swaggerUiResourceRoute

  def receive = runRoute(possibleRoutes)
}

trait CromwellApiService extends HttpService with PerRequestCreator {
  val workflowManager: ActorRef

  val workflowRoutes = queryRoute ~ workflowOutputsRoute ~ submitRoute ~ workflowStdoutStderrRoute ~ abortRoute ~
    callOutputsRoute ~ callStdoutStderrRoute ~ validateRoute ~ metadataRoute

  def queryRoute =
    path("workflows" / Segment / Segment / "status") { (version, id) =>
      get {
        Try(WorkflowId.fromString(id)) match {
          case Success(workflowId) =>
            requestContext => perRequest(requestContext, CromwellApiHandler.props(workflowManager), CromwellApiHandler.WorkflowStatus(workflowId))
          case Failure(ex) =>
            complete(StatusCodes.BadRequest)
        }
      }
    }

  def abortRoute =
    path("workflows" / Segment / Segment / "abort") { (version, id) =>
      post {
        Try(WorkflowId.fromString(id)) match {
          case Success(workflowId) =>
            requestContext => perRequest(requestContext, CromwellApiHandler.props(workflowManager), CromwellApiHandler.WorkflowAbort(workflowId))
          case Failure(ex) =>
            complete(StatusCodes.BadRequest)
        }
      }
    }

  def submitRoute =
    path("workflows" / Segment) { version =>
      post {
        formFields("wdlSource", "workflowInputs".?, "workflowOptions".?) { (wdlSource, workflowInputs, workflowOptions) =>
          val tryInputsMap = Try(workflowInputs.getOrElse("{}").parseJson)
          val tryOptionsMap = Try(workflowOptions.getOrElse("{}").parseJson)
          (tryInputsMap, tryOptionsMap) match {
            case (Success(JsObject(_)), Success(options: JsObject)) =>
              if (!options.fields.values.forall(_.isInstanceOf[JsString])) {
                complete(StatusCodes.BadRequest, "Workflow options must be a string -> string map")
              }
              else {
                WorkflowOptions.fromJsonObject(options) match {
                  case Success(wfOptions) =>
                    requestContext => perRequest(
                      requestContext,
                      CromwellApiHandler.props(workflowManager),
                      CromwellApiHandler.WorkflowSubmit(
                        WorkflowSourceFiles(
                          wdlSource, workflowInputs.getOrElse("{}"), wfOptions.asPrettyJson
                        )
                      )
                    )
                  case Failure(ex) => complete(StatusCodes.PreconditionFailed, s"Could not encrypt workflow options: ${ex.getMessage}")
                }
              }
            case (Success(_), _) | (_, Success(_)) =>
              complete(StatusCodes.BadRequest, "Expecting JSON object for workflowInputs and workflowOptions fields")
            case (Failure(ex), _) =>
              complete(StatusCodes.BadRequest, "workflowInput JSON was malformed")
            case (_, Failure(ex)) =>
              complete(StatusCodes.BadRequest, "workflowOptions JSON was malformed")
          }
        }
      }
    }

  def validateRoute =
    path("workflows" / Segment / "validate") { version =>
      post {
        formFields("wdlSource", "workflowInputs") { (wdlSource, workflowInputs) =>
          requestContext =>
            perRequest(
              requestContext,
              ValidateActor.props(wdlSource, workflowInputs),
              ValidateActor.ValidateWorkflow)
        }
      }
    }

  def workflowOutputsRoute =
    path("workflows" / Segment / Segment / "outputs") { (version, id) =>
      get {
        Try(WorkflowId.fromString(id)) match {
          case Success(workflowId) =>
            requestContext => perRequest(requestContext, CromwellApiHandler.props(workflowManager), CromwellApiHandler.WorkflowOutputs(workflowId))
          case Failure(ex) =>
            complete(StatusCodes.BadRequest)
        }
      }
    }

  def callOutputsRoute =
    path("workflows" / Segment / Segment / "outputs" / Segment) { (version, workflowId, callFqn) =>
      Try(WorkflowId.fromString(workflowId)) match {
        case Success(w) =>
          // This currently does not attempt to parse the call name for conformation to any pattern.
          requestContext => perRequest(requestContext, CromwellApiHandler.props(workflowManager), CromwellApiHandler.CallOutputs(w, callFqn))
        case Failure(_) =>
          complete(StatusCodes.BadRequest, s"Invalid workflow ID: '$workflowId'.")
      }
    }

  def callStdoutStderrRoute =
    path("workflows" / Segment / Segment / "logs" / Segment) { (version, workflowId, callFqn) =>
      Try(WorkflowId.fromString(workflowId)) match {
        case Success(w) =>
          // This currently does not attempt to parse the call name for conformation to any pattern.
          requestContext => perRequest(requestContext, CromwellApiHandler.props(workflowManager), CromwellApiHandler.CallStdoutStderr(w, callFqn))
        case Failure(_) =>
          complete(StatusCodes.BadRequest, s"Invalid workflow ID: '$workflowId'.")
      }
    }

  def workflowStdoutStderrRoute =
    path("workflows" / Segment / Segment / "logs") { (version, workflowId) =>
      Try(WorkflowId.fromString(workflowId)) match {
        case Success(w) =>
          requestContext => perRequest(requestContext, CromwellApiHandler.props(workflowManager), CromwellApiHandler.WorkflowStdoutStderr(w))
        case Failure(_) =>
          complete(StatusCodes.BadRequest, s"Invalid workflow ID: '$workflowId'.")
      }
    }

  def metadataRoute =
    path("workflows" / Segment / Segment / "metadata") { (version, workflowId) =>
      Try(WorkflowId.fromString(workflowId)) match {
        case Success(w) =>
          requestContext => perRequest(requestContext, CromwellApiHandler.props(workflowManager), CromwellApiHandler.WorkflowMetadata(w))
        case Failure(_) =>
          complete(StatusCodes.BadRequest, s"Invalid workflow ID: '$workflowId'.")
      }
    }
}
