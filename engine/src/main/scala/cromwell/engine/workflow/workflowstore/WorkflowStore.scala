package cromwell.engine.workflow.workflowstore

import java.time.OffsetDateTime

import cats.data.NonEmptyList
import cromwell.core.{WorkflowId, WorkflowSourceFilesCollection}
import cromwell.engine.workflow.workflowstore.SqlWorkflowStore.WorkflowStoreAbortResponse.WorkflowStoreAbortResponse
import cromwell.engine.workflow.workflowstore.SqlWorkflowStore.WorkflowStoreState.WorkflowStoreState
import cromwell.engine.workflow.workflowstore.SqlWorkflowStore.{WorkflowSubmissionResponse, WorkflowsBySubmissionId}
import cromwell.engine.workflow.workflowstore.WorkflowStoreActor.WorkflowStoreWorkflowStatus

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

trait WorkflowStore {

  def initialize(implicit ec: ExecutionContext): Future[Unit]

  def abortAllRunning()(implicit ec: ExecutionContext): Future[Unit]

  /**
    * Mark a workflow as aborting, unless the row is OnHold or Submitted, in which case the row is deleted.
    */
  def aborting(id: WorkflowId)(implicit ec: ExecutionContext): Future[WorkflowStoreAbortResponse]

  def findWorkflows(cromwellId: String)(implicit ec: ExecutionContext): Future[Iterable[WorkflowId]]

  def findWorkflowsWithAbortRequested(cromwellId: String)(implicit ec: ExecutionContext): Future[Iterable[WorkflowId]]

  def stats(implicit ec: ExecutionContext): Future[Map[WorkflowStoreState, Int]]

  /**
    * Adds the requested WorkflowSourceFiles to the store and returns a WorkflowId for each one (in order)
    * for tracking purposes.
    */
  def add(sources: NonEmptyList[WorkflowSourceFilesCollection])(implicit ec: ExecutionContext): Future[NonEmptyList[WorkflowSubmissionResponse]]

  /**
    * Retrieves up to n workflows which have not already been pulled into the engine and sets their pickedUp
    * flag to true
    */
  def fetchStartableWorkflows(n: Int, cromwellId: String, heartbeatTtl: FiniteDuration)(implicit ec: ExecutionContext): Future[List[WorkflowToStart]]

  def writeWorkflowHeartbeats(workflowIds: Set[(WorkflowId, OffsetDateTime)],
                              heartbeatDateTime: OffsetDateTime)
                             (implicit ec: ExecutionContext): Future[Int]

  def switchOnHoldToSubmitted(id: WorkflowId)(implicit ec: ExecutionContext): Future[Unit]

  def listSubmissions(implicit ec: ExecutionContext): Future[List[WorkflowsBySubmissionId]]

  def updateWorkflowStates(submissionId: Option[String], fromWorkflowState: Option[String], toWorkflowState: String, maxChanges: Option[Long])
                          (implicit ec: ExecutionContext): Future[Int]

  def fetchWorkflowStatus(workflowId: WorkflowId)(implicit ec: ExecutionContext): Future[Option[WorkflowStoreWorkflowStatus]]
}
