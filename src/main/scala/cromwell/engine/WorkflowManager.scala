package cromwell.engine

import java.util.UUID

import akka.actor.{Actor, ActorRef, Props}
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import cromwell.binding
import cromwell.binding.{WdlBinding, WdlSource}
import cromwell.engine.WorkflowActor._
import cromwell.engine.WorkflowManagerActor._
import cromwell.engine.backend.Backend
import cromwell.engine.backend.local.LocalBackend
import cromwell.util.WriteOnceStore

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Try

/**
 * Abstract notion of a workflow manager. Mainly exists to allow for actor-system-free unit testing of basic concepts
 */
trait WorkflowManager {
  type Workflow

  case class ManagedWorkflow(id: WorkflowId, workflow: Workflow)

  val backend: Backend

  def generateWorkflow(id: WorkflowId, wdl: WdlSource, inputs: binding.WorkflowRawInputs): Try[Workflow]

  def submitWorkflow(wdl: WdlSource, inputs: binding.WorkflowRawInputs): Future[WorkflowId]
  def notifyCompletion(obj: Option[AnyRef]): Unit
  def workflowStatus(id: WorkflowId): Option[WorkflowState] = workflowStates.get(id)
  def workflowOutputs(id: WorkflowId): Future[Option[binding.WorkflowOutputs]]
  def updateWorkflowState(workflow: Workflow, state: WorkflowState): Unit

  def idByWorkflow(workflow: Workflow): Option[WorkflowId] = {
    workflowStore.toMap collectFirst {case (k, v) if v == workflow => k}
  }

  private val workflowStore = new WriteOnceStore[WorkflowId, Workflow] // This probably doesn't need to be persisted
  protected val workflowStates = TrieMap.empty[WorkflowId, WorkflowState] // This *should* be persisted

  /**
   * Generates a workflow with an ID, inserts it into the store and returns the pair.
   */
  protected def addWorkflow(wdl: WdlSource, inputs: binding.WorkflowRawInputs): Future[Try[ManagedWorkflow]] = Future {
      val workflowId = UUID.randomUUID()
      val managedWorkflow = for {
        wf <- generateWorkflow(workflowId, wdl, inputs)
        _ <- workflowStore.insert(workflowId, wf) // Come for the side effect, stay for the Try
      } yield ManagedWorkflow(workflowId, wf)
      managedWorkflow foreach {m => workflowStates.put(m.id, WorkflowSubmitted)}
      managedWorkflow
  }

  def workflowById(id: WorkflowId): Option[Workflow] = workflowStore.toMap.get(id)
}

object WorkflowManagerActor {
  sealed trait WorkflowManagerMessage
  case class SubmitWorkflow(wdl: WdlSource, inputs: binding.WorkflowRawInputs) extends WorkflowManagerMessage
  case class NotifyCompletion(caller: AnyRef) extends WorkflowManagerMessage
  case class WorkflowStatus(id: WorkflowId) extends WorkflowManagerMessage
  case class WorkflowOutputs(id: WorkflowId) extends WorkflowManagerMessage
  case class Shutdown() extends WorkflowManagerMessage
}

/**
 * Responses to messages:
 * SubmitWorkflow: Returns a Future[Try[WorkflowId]]
 * WorkflowStatus: Returns a Future[Option[WorkflowState]]
 * WorkflowOutputs: Returns a Future[Option[binding.WorkflowOutputs]] aka Future[Option[Map[String, WdlValue]]]
 *
 */
trait WorkflowManagerActor extends Actor with WorkflowManager {
  override type Workflow = ActorRef // TODO: In a world where Akka Typed is no longer experimental switch to that
  val actorSystem = context.system
  private var caller: Option[AnyRef] = None

  def receive = {
    case SubmitWorkflow(wdl, inputs) =>
      val origSender = sender() // I don't think I have to do this, but better safe than sorry
      submitWorkflow(wdl, inputs) pipeTo origSender
    case WorkflowStatus(id) =>
      val origSender = sender()
      origSender ! workflowStatus(id)
    case NotifyCompletion(obj) => caller = Some(obj)
    case WorkflowOutputs(id) => workflowOutputs(id) pipeTo sender() // FIXME: What if the workflow isn't done? How best to handle?
    case Shutdown() => context.system.shutdown
    case WorkflowActor.Started => updateWorkflowState(sender(), WorkflowRunning)
    case WorkflowActor.Done(symbolStore) =>
      notifyCompletion(caller)
      updateWorkflowState(sender(), WorkflowSucceeded)
    case WorkflowActor.Failed(failures) =>
      notifyCompletion(caller)
      updateWorkflowState(sender(), WorkflowFailed)
  }
}

/**
 * A WorkflowManagerActor using WorkflowActors as the workflow handler
 */

object ActorWorkflowManager {
  def props: Props = Props(classOf[ActorWorkflowManager])
}

class ActorWorkflowManager extends WorkflowManagerActor {
  override val backend = new LocalBackend
  implicit val timeout = Timeout(30.seconds)

  override def generateWorkflow(id: WorkflowId, wdl: WdlSource, rawInputs: binding.WorkflowRawInputs): Try[Workflow] = {
    val binding = WdlBinding.process(wdl)
    for {
      coercedInputs <- binding.coerceRawInputs(rawInputs)
    } yield context.actorOf(WorkflowActor.buildWorkflowActorProps(id, binding, coercedInputs))
  }

  override def notifyCompletion(obj: Option[AnyRef]): Unit = {
    obj match {
      case Some(o) => o.synchronized{o.notify()}
      case _ =>
    }
  }
  override def workflowOutputs(id: WorkflowId): Future[Option[binding.WorkflowOutputs]] = {
    workflowById(id) map workflowToOutputs getOrElse Future{None}
  }

  private def workflowToOutputs(workflow: Workflow): Future[Option[binding.WorkflowOutputs]] = {
    val eventualSymbolStoreEntries = {workflow ? GetOutputs}.mapTo[Set[SymbolStoreEntry]]
    symbolsToWorkflowOutputs(eventualSymbolStoreEntries) map {Option(_)}
  }

  override def submitWorkflow(wdl: WdlSource, inputs: binding.WorkflowRawInputs): Future[WorkflowId] = {
    def startAndExtractId(workflow: ManagedWorkflow): WorkflowId = {
      // This needs to be an ask and not a tell as this isn't an Actor.
      // The Future result is deliberately ignored.
      workflow.workflow ? Start(backend)
      workflow.id
    }

    for {
      // Flatten what would otherwise be a Future[Try[]] into a Future[].
      tryWorkflow <- addWorkflow(wdl, inputs)
      workflow <- Future.fromTry(tryWorkflow)
    } yield startAndExtractId(workflow)
  }

  override def updateWorkflowState(workflow: Workflow, state: WorkflowState): Unit = {
    idByWorkflow(workflow) map {w => workflowStates.put(w, state)}
  }

  private def symbolsToWorkflowOutputs(eventualSymbolStoreEntries: Future[Set[SymbolStoreEntry]]): Future[binding.WorkflowOutputs] = {
    /*
     * Given a Future of Set[SymbolStoreEntry], reach in to the Future and then for each SymbolStoreEntry convert it to a
     * mapping from FullyQualifiedName to WdlValue and then convert the collection of those mappings to a Map
     */
    eventualSymbolStoreEntries.map(_.map(s => s"${s.key.scope}.${s.key.name}" -> s.wdlValue.get).toMap)
  }
}
