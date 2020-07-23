package cromwell.engine.workflow.lifecycle.execution.stores

import cromwell.backend.BackendJobDescriptorKey
import cromwell.core.ExecutionStatus.{ExecutionStatus, _}
import cromwell.core.JobKey
import cromwell.engine.workflow.lifecycle.execution.stores.ExecutionStoreSpec._
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}
import wom.graph._

import scala.util.Random

class ExecutionStoreSpec extends FlatSpec with Matchers with BeforeAndAfter {


  it should "allow 10000 unconnected call keys to be enqueued and started in small batches" in {

    def jobKeys: Map[JobKey, ExecutionStatus] = (0.until(10000).toList map {
      i => BackendJobDescriptorKey(noConnectionsGraphNode, Option(i), 1) -> NotStarted })
      .toMap

    var store: ExecutionStore = ActiveExecutionStore(jobKeys, needsUpdate = true)

    var iterationNumber = 0
    while (store.needsUpdate) {
      // Assert that we're increasing the queue size by 1000 each time
      store.store.getOrElse(NotStarted, List.empty).size should be(10000 - iterationNumber * ExecutionStore.MaxJobsToStartPerTick)
      store.store.getOrElse(QueuedInCromwell, List.empty).size should be(iterationNumber * ExecutionStore.MaxJobsToStartPerTick)
      val update = store.update
      store = update.updatedStore.updateKeys(update.runnableKeys.map(_ -> QueuedInCromwell).toMap)
      iterationNumber = iterationNumber + 1
    }

    store.store.getOrElse(QueuedInCromwell, List.empty).size should be(10000)
    var currentlyRunning = store.store.getOrElse(Running, List.empty).size
    currentlyRunning should be(0)

    while(store.store.getOrElse(Running, List.empty).size < 10000) {
      val toStartRunning = store.store(QueuedInCromwell).take(Random.nextInt(1000))
      store = store.updateKeys(toStartRunning.map(j => j -> Running).toMap)
      val newlyRunning = store.store.getOrElse(Running, List.empty).size

      if (currentlyRunning + toStartRunning.size < 10000)
        newlyRunning should be(currentlyRunning + toStartRunning.size)
      else
        newlyRunning should be(10000)

      currentlyRunning = newlyRunning
      store.store.getOrElse(QueuedInCromwell, List.empty).size should be(10000 - currentlyRunning)
    }

    store.store.getOrElse(QueuedInCromwell, List.empty).size should be(0)
    store.store.getOrElse(Running, List.empty).size should be(10000)
  }
}

object ExecutionStoreSpec {

  val noConnectionsGraphNode: CommandCallNode = CommandCallNode(
    identifier = WomIdentifier("mock_task", "mock_wf.mock_task"),
    callable = null,
    inputPorts =  Set.empty[GraphNodePort.InputPort],
    inputDefinitionMappings = List.empty,
    nonInputBasedPrerequisites = Set.empty[GraphNode],
    outputIdentifierCompoundingFunction = (wi, _) => wi,
    sourceLocation = None
  )
}