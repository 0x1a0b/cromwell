package cromwell.engine.workflow.lifecycle.execution

import akka.actor.{ActorRef, LoggingFSM, Props}
import cromwell.backend.BackendCacheHitCopyingActor.CopyOutputsCommand
import cromwell.backend.BackendJobExecutionActor._
import cromwell.backend.{BackendInitializationData, BackendJobDescriptor, BackendJobDescriptorKey, BackendLifecycleActorFactory}
import cromwell.core.Dispatcher.EngineDispatcher
import cromwell.core.ExecutionIndex.IndexEnhancedIndex
import cromwell.core._
import cromwell.core.callcaching._
import cromwell.core.logging.WorkflowLogging
import cromwell.engine.workflow.lifecycle.execution.EngineJobExecutionActor._
import cromwell.engine.workflow.lifecycle.execution.JobPreparationActor.{BackendJobPreparationFailed, BackendJobPreparationSucceeded}
import cromwell.engine.workflow.lifecycle.execution.callcaching.EngineJobHashingActor.{CacheHit, CacheMiss, CallCacheHashes, HashError}
import cromwell.engine.workflow.lifecycle.execution.callcaching.FetchCachedResultsActor.{CachedOutputLookupFailed, CachedOutputLookupSucceeded}
import cromwell.engine.workflow.lifecycle.execution.callcaching._
import cromwell.jobstore.JobStoreActor._
import cromwell.jobstore.{Pending => _, _}
import cromwell.services.SingletonServicesStore
import wdl4s.TaskOutput

import scala.util.{Success, Try}

class EngineJobExecutionActor(replyTo: ActorRef,
                              jobKey: BackendJobDescriptorKey,
                              executionData: WorkflowExecutionActorData,
                              factory: BackendLifecycleActorFactory,
                              initializationData: Option[BackendInitializationData],
                              restarting: Boolean,
                              serviceRegistryActor: ActorRef,
                              jobStoreActor: ActorRef,
                              callCacheReadActor: ActorRef,
                              backendName: String,
                              callCachingMode: CallCachingMode) extends LoggingFSM[EngineJobExecutionActorState, EJEAData] with WorkflowLogging {

  override val workflowId = executionData.workflowDescriptor.id

  val jobTag = s"${workflowId.shortString}:${jobKey.call.fullyQualifiedName}:${jobKey.index.fromIndex}:${jobKey.attempt}"
  val tag = s"EJEA_$jobTag"

  // The code that deals with the call cache copying actor only runs if call cache reading is turned on, which it won't be
  // if there isn't a call cache copying actor available. i.e. no code should try to read this variable if this variable
  // isn't set, so while this could be wrapped in an `Option` that would only mask a violation of that invariant.
  private var callCachingActorProps: Props = _

  // There's no need to check for a cache hit again if we got preempted
  // NB: this can also change (e.g. if we have a HashError we just force this to CallCachingOff)
  private var effectiveCallCachingMode = if (jobKey.attempt > 1) callCachingMode.withoutRead else callCachingMode

  private val effectiveCallCachingKey = "Effective call caching mode"

  log.info(s"$tag: $effectiveCallCachingKey: $effectiveCallCachingMode")
  writeCallCachingModeToMetadata()

  startWith(Pending, NoData)

  // When Pending, the FSM always has NoData
  when(Pending) {
    case Event(Execute, NoData) =>
      if (restarting) {
        val jobStoreKey = jobKey.toJobStoreKey(workflowId)
        jobStoreActor ! QueryJobCompletion(jobStoreKey, jobKey.call.task.outputs)
        goto(CheckingJobStore)
      } else {
        prepareJob()
      }
  }

  // When CheckingJobStore, the FSM always has NoData
  when(CheckingJobStore) {
    case Event(JobNotComplete, NoData) =>
      prepareJob()
    case Event(JobComplete(jobResult), NoData) =>
      jobResult match {
        case JobResultSuccess(returnCode, jobOutputs) =>
          replyTo ! SucceededResponse(jobKey, returnCode, jobOutputs)
          context stop self
          stay()
        case JobResultFailure(returnCode, reason, false) =>
          replyTo ! FailedNonRetryableResponse(jobKey, reason, returnCode)
          context stop self
          stay()
        case JobResultFailure(returnCode, reason, true) =>
          replyTo ! FailedRetryableResponse(jobKey, reason, returnCode)
          context stop self
          stay()
      }
    case Event(f: JobStoreReadFailure, NoData) =>
      log.error(f.reason, "{}: Error reading from JobStore", tag)
      // Escalate
      throw new RuntimeException(f.reason)
  }

  // When PreparingJob, the FSM always has NoData
  when(PreparingJob) {
    case Event(BackendJobPreparationSucceeded(jobDescriptor, bjeaProps), NoData) =>
      effectiveCallCachingMode = checkCallCachingAvailable(jobDescriptor, bjeaProps)
      writeCallCachingModeToMetadata()
      val updatedData = ResponsePendingData(jobDescriptor, bjeaProps)
      effectiveCallCachingMode match {
        case activity: CallCachingActivity if activity.readFromCache =>
          initializeJobHashing(jobDescriptor, activity)
          goto(CheckingCallCache) using updatedData
        case activity: CallCachingActivity =>
          initializeJobHashing(jobDescriptor, activity)
          runJob(updatedData)
        case CallCachingOff => runJob(updatedData)
      }
    case Event(response: BackendJobPreparationFailed, NoData) =>
      replyTo forward response
      context stop self
      stay()
  }

  private val callCachingReadResultMetadataKey = "Call caching read result"
  when(CheckingCallCache) {
    case Event(CacheMiss, data: ResponsePendingData) =>
      writeToMetadata(Map(callCachingReadResultMetadataKey -> "Cache Miss"))
      log.debug("Cache miss for job {}", jobTag)
      runJob(data)
    case Event(hit: CacheHit, data: ResponsePendingData) =>
      writeToMetadata(Map(callCachingReadResultMetadataKey -> s"Cache Hit (from result ID ${hit.cacheResultId})"))
      log.debug("Cache hit for {}! Fetching cached result {}", jobTag, hit.cacheResultId)
      fetchCachedResults(data, jobKey.call.task.outputs, hit)
    case Event(HashError(t), data: ResponsePendingData) =>
      writeToMetadata(Map(callCachingReadResultMetadataKey -> s"Hashing Error: ${t.getMessage}"))
      disableCallCaching(t)
      runJob(data)
  }

  when(FetchingCachedOutputsFromDatabase) {
    case Event(CachedOutputLookupSucceeded(cachedJobOutputs, cacheHit), data: ResponsePendingData) =>
      makeBackendCopyCacheHit(cacheHit, cachedJobOutputs, data)
    case Event(CachedOutputLookupFailed(metaInfoId, error), data: ResponsePendingData) =>
      log.warning("Can't make a copy of the cached job outputs for {} due to {}. Running job.", jobTag, error)
      runJob(data)
    case Event(hashes: CallCacheHashes, data: ResponsePendingData) =>
      addHashesAndStay(data, hashes)
    case Event(HashError(t), data: ResponsePendingData) =>
      disableCacheWrite(t)
      // Can't write hashes for this job, but continue to wait for the lookup response.
      stay
  }

  when(BackendIsCopyingCachedOutputs) {
    // It's possible that cache writing was turned off due to a `HashError`, hence the `writeToCache` guard.
    case Event(response: SucceededResponse, data @ ResponsePendingData(_, _, Some(Success(hashes)))) if effectiveCallCachingMode.writeToCache =>
      saveCacheResults(hashes, response.updatedData(data))
    case Event(response: SucceededResponse, data @ ResponsePendingData(_, _, None)) if effectiveCallCachingMode.writeToCache =>
      // Wait for the CallCacheHashes
      stay using response.updatedData(data)
    case Event(response: SucceededResponse, data: ResponsePendingData) => // bad hashes or cache write off
      saveJobCompletionToJobStore(response.updatedData(data))
    case Event(response: BackendJobExecutionResponse, data: ResponsePendingData) =>
      // This matches all response types other than `SucceededResponse`.
      log.error("{}: Failed copying cache results, falling back to running job.", jobKey)
      runJob(data)
    case Event(hashes: CallCacheHashes, data: SucceededResponseData) =>
      saveCacheResults(hashes, data)
    case Event(hashes: CallCacheHashes, data: ResponsePendingData) =>
      addHashesAndStay(data, hashes)
    case Event(HashError(t), data: SucceededResponseData) =>
      disableCallCaching(t)
      saveJobCompletionToJobStore(data)
    case Event(HashError(t), data: ResponsePendingData) =>
      disableCacheWrite(t)
      // Can't write hashes for this job, but continue to wait for the copy response.
      stay
  }

  when(RunningJob) {
    case Event(hashes: CallCacheHashes, data: SucceededResponseData) =>
      saveCacheResults(hashes, data)
    case Event(hashes: CallCacheHashes, data: ResponsePendingData) =>
      addHashesAndStay(data, hashes)

    case Event(HashError(t), data: SucceededResponseData) =>
      disableCallCaching(t)
      saveJobCompletionToJobStore(data)
    case Event(HashError(t), data: ResponsePendingData) =>
      disableCallCaching(t)
      stay

    case Event(response: SucceededResponse, data @ ResponsePendingData(_, _, Some(Success(hashes)))) if effectiveCallCachingMode.writeToCache =>
      saveCacheResults(hashes, response.updatedData(data))
    case Event(response: SucceededResponse, data: ResponsePendingData) if effectiveCallCachingMode.writeToCache =>
      log.debug(s"Got job result for {}, awaiting hashes", jobTag)
      stay using response.updatedData(data)
    case Event(response: BackendJobExecutionResponse, data: ResponsePendingData) =>
      saveJobCompletionToJobStore(response.updatedData(data))
  }

  // When UpdatingCallCache, the FSM always has SucceededResponseData.
  when(UpdatingCallCache) {
    case Event(CallCacheWriteSuccess, data: SucceededResponseData) =>
      saveJobCompletionToJobStore(data)
    case Event(CallCacheWriteFailure(reason), data: SucceededResponseData) =>
      log.error(reason, "{}: Failure writing to call cache: {}", jobTag, reason.getMessage)
      saveJobCompletionToJobStore(data)
  }

  // When UpdatingJobStore, the FSM always has ResponseData.
  when(UpdatingJobStore) {
    case Event(JobStoreWriteSuccess(_), data: ResponseData) =>
      replyTo forward data.response
      context stop self
      stay()
    case Event(JobStoreWriteFailure(t), data: ResponseData) =>
      context.parent ! FailedNonRetryableResponse(jobKey, new Exception(s"JobStore write failure: ${t.getMessage}", t), None)
      context.stop(self)
      stay()
  }

  onTransition {
    case fromState -> toState =>
      log.debug("Transitioning from {}({}) to {}({})", fromState, stateData, toState, nextStateData)
  }

  whenUnhandled {
    case Event(msg, _) =>
      log.error(s"Bad message to EngineJobExecutionActor in state $stateName(with data $stateData): $msg")
      stay
  }

  private def disableCallCaching(reason: Throwable) = {
    log.error(reason, "{}: Hash error, disabling call caching for this job.", jobTag)
    effectiveCallCachingMode = CallCachingOff
    writeCallCachingModeToMetadata()
  }

  private def disableCacheWrite(reason: Throwable) = {
    log.error("{}: Disabling cache writing for this job.", jobTag)
    if (effectiveCallCachingMode.writeToCache) {
      effectiveCallCachingMode = effectiveCallCachingMode.withoutWrite
      writeCallCachingModeToMetadata()
    }
  }

  def writeCallCachingModeToMetadata(): Unit = {
    writeToMetadata(Map(effectiveCallCachingKey -> effectiveCallCachingMode.toString))
  }

  def prepareJob() = {
    val jobPreparationActorName = s"BackendPreparationActor_for_$jobTag"
    val jobPrepProps = JobPreparationActor.props(executionData, jobKey, factory, initializationData, serviceRegistryActor)
    val jobPreparationActor = context.actorOf(jobPrepProps, jobPreparationActorName)
    jobPreparationActor ! JobPreparationActor.Start
    goto(PreparingJob)
  }

  /**
    * If this backend doesn't offer a cache hit copying actor, remove 'read' from the effective cache options.
    * i.e. there's no way to copy a cache hit so don't bother looking for cache hits.  If this backend does
    * offer a cache hit copying actor, squirrel away its props so we can create this actor later if there's a hit.
    */
  private def checkCallCachingAvailable(jobDescriptor: BackendJobDescriptor, bjeaProps: Props): CallCachingMode = {
    if (effectiveCallCachingMode.readFromCache) {
      factory.cacheHitCopyingActorProps(jobDescriptor, initializationData, serviceRegistryActor) match {
        case Some(props) =>
          callCachingActorProps = props
          effectiveCallCachingMode
        case None =>
          log.warning("{}: Turning off cache reading for this job as no cache copying actor is available for this backend.", jobTag)
          effectiveCallCachingMode.withoutRead
      }
    } else effectiveCallCachingMode
  }

  def initializeJobHashing(jobDescriptor: BackendJobDescriptor, activity: CallCachingActivity) = {
    val props = EngineJobHashingActor.props(
      self,
      jobDescriptor,
      initializationData,
      factory.fileContentsHasherActor,
      callCacheReadActor,
      factory.runtimeAttributeDefinitions(initializationData), backendName, activity)
    context.actorOf(props, s"ejha_for_$jobDescriptor")
  }

  def fetchCachedResults(data: ResponsePendingData, taskOutputs: Seq[TaskOutput], cacheHit: CacheHit) = {
    context.actorOf(FetchCachedResultsActor.props(cacheHit, taskOutputs, self, new CallCache(SingletonServicesStore.databaseInterface)))
    goto(FetchingCachedOutputsFromDatabase)
  }

  def makeBackendCopyCacheHit(cacheHit: CacheHit, cachedJobOutputs: JobOutputs, data: ResponsePendingData) = {
    val cacheHitCopyActor = context.actorOf(callCachingActorProps, buildCacheHitCopyingActorName(data.jobDescriptor))
    cacheHitCopyActor ! CopyOutputsCommand(cachedJobOutputs)
    goto(BackendIsCopyingCachedOutputs)
  }

  def runJob(data: ResponsePendingData) = {
    val backendJobExecutionActor = context.actorOf(data.bjeaProps, buildJobExecutionActorName(data.jobDescriptor))
    val message = if (restarting) RecoverJobCommand else ExecuteJobCommand
    backendJobExecutionActor ! message
    replyTo ! JobRunning(data.jobDescriptor, backendJobExecutionActor)
    goto(RunningJob) using data
  }

  private def buildJobExecutionActorName(jobDescriptor: BackendJobDescriptor) = {
    s"$workflowId-BackendJobExecutionActor-$jobTag"
  }

  private def buildCacheHitCopyingActorName(jobDescriptor: BackendJobDescriptor) = {
    s"$workflowId-BackendCacheHitCopyingActor-$jobTag"
  }

  private def saveCacheResults(hashes: CallCacheHashes, data: SucceededResponseData) = {
    val callCache = new CallCache(SingletonServicesStore.databaseInterface)
    context.actorOf(CallCacheWriteActor.props(callCache, workflowId, hashes, data.successResponse), s"CallCacheWriteActor-$tag")
    val updatedData = data.copy(hashes = Option(Success(hashes)))
    goto(UpdatingCallCache) using updatedData
  }

  private def saveJobCompletionToJobStore(updatedData: ResponseData) = {
    updatedData.response match {
      case SucceededResponse(jobKey: BackendJobDescriptorKey, returnCode: Option[Int], jobOutputs: JobOutputs) => saveSuccessfulJobResults(jobKey, returnCode, jobOutputs)
      case AbortedResponse(jobKey: BackendJobDescriptorKey) => log.debug("Won't save 'aborted' job response to JobStore")
      case FailedNonRetryableResponse(jobKey: BackendJobDescriptorKey, throwable: Throwable, returnCode: Option[Int]) => saveUnsuccessfulJobResults(jobKey, returnCode, throwable, retryable = false)
      case FailedRetryableResponse(jobKey: BackendJobDescriptorKey, throwable: Throwable, returnCode: Option[Int]) => saveUnsuccessfulJobResults(jobKey, returnCode, throwable, retryable = true)
    }
    goto(UpdatingJobStore) using updatedData
  }

  private def saveSuccessfulJobResults(jobKey: JobKey, returnCode: Option[Int], outputs: JobOutputs) = {
    val jobStoreKey = jobKey.toJobStoreKey(workflowId)
    val jobStoreResult = JobResultSuccess(returnCode, outputs)
    jobStoreActor ! RegisterJobCompleted(jobStoreKey, jobStoreResult)
  }

  private def saveUnsuccessfulJobResults(jobKey: JobKey, returnCode: Option[Int], reason: Throwable, retryable: Boolean) = {
    val jobStoreKey = jobKey.toJobStoreKey(workflowId)
    val jobStoreResult = JobResultFailure(returnCode, reason, retryable)
    jobStoreActor ! RegisterJobCompleted(jobStoreKey, jobStoreResult)
  }

  private def writeToMetadata(keyValues: Map[String, String]) = {
    import cromwell.services.metadata.MetadataService.implicits.MetadataAutoputter
    serviceRegistryActor.putMetadata(workflowId, Option(jobKey), keyValues)
  }

  private def addHashesAndStay(data: ResponsePendingData, hashes: CallCacheHashes): State = {
    val updatedData = data.copy(hashes = Option(Success(hashes)))
    stay using updatedData
  }
}

object EngineJobExecutionActor {
  /** States */
  sealed trait EngineJobExecutionActorState
  case object Pending extends EngineJobExecutionActorState
  case object CheckingJobStore extends EngineJobExecutionActorState
  case object CheckingCallCache extends EngineJobExecutionActorState
  case object FetchingCachedOutputsFromDatabase extends EngineJobExecutionActorState
  case object BackendIsCopyingCachedOutputs extends EngineJobExecutionActorState
  case object PreparingJob extends EngineJobExecutionActorState
  case object RunningJob extends EngineJobExecutionActorState
  case object UpdatingCallCache extends EngineJobExecutionActorState
  case object UpdatingJobStore extends EngineJobExecutionActorState

  /** Commands */
  sealed trait EngineJobExecutionActorCommand
  case object Execute extends EngineJobExecutionActorCommand

  final case class JobRunning(jobDescriptor: BackendJobDescriptor, backendJobExecutionActor: ActorRef)

  def props(replyTo: ActorRef,
            jobDescriptorKey: BackendJobDescriptorKey,
            executionData: WorkflowExecutionActorData,
            factory: BackendLifecycleActorFactory,
            initializationData: Option[BackendInitializationData],
            restarting: Boolean,
            serviceRegistryActor: ActorRef,
            jobStoreActor: ActorRef,
            callCacheReadActor: ActorRef,
            backendName: String,
            callCachingMode: CallCachingMode) = {
    Props(new EngineJobExecutionActor(
      replyTo = replyTo,
      jobKey = jobDescriptorKey,
      executionData = executionData,
      factory = factory,
      initializationData = initializationData,
      restarting = restarting,
      serviceRegistryActor = serviceRegistryActor,
      jobStoreActor = jobStoreActor,
      callCacheReadActor = callCacheReadActor,
      backendName = backendName: String,
      callCachingMode = callCachingMode)).withDispatcher(EngineDispatcher)
  }

  implicit class EnhancedSuccess(val response: SucceededResponse) extends AnyVal {
    def updatedData(data: ResponsePendingData): SucceededResponseData = {
      SucceededResponseData(response, data.hashes)
    }
  }

  implicit class EnhancedNotSuccess(val response: BackendJobExecutionResponse) extends AnyVal {
    def updatedData(data: ResponsePendingData): NotSucceededResponseData = {
      NotSucceededResponseData(response, data.hashes)
    }
  }

  private[execution] sealed trait EJEAData {
    override def toString = getClass.getSimpleName
  }

  private[execution] case object NoData extends EJEAData

  private[execution] case class ResponsePendingData(jobDescriptor: BackendJobDescriptor,
                                                    bjeaProps: Props,
                                                    hashes: Option[Try[CallCacheHashes]] = None) extends EJEAData

  private[execution] trait ResponseData extends EJEAData {
    def response: BackendJobExecutionResponse
    def hashes: Option[Try[CallCacheHashes]]
  }

  private[execution] case class SucceededResponseData(successResponse: SucceededResponse,
                                                      hashes: Option[Try[CallCacheHashes]] = None) extends ResponseData {
    override def response = successResponse
  }

  private[execution] case class NotSucceededResponseData(response: BackendJobExecutionResponse,
                                                         hashes: Option[Try[CallCacheHashes]] = None) extends ResponseData
}
