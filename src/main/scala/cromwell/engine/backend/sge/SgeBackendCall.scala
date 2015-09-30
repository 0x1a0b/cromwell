package cromwell.engine.backend.sge

import cromwell.binding.CallInputs
import cromwell.binding.values.WdlValue
import cromwell.engine.backend.local.LocalBackend
import cromwell.engine.backend.{BackendCall, ExecutionResult, LocalFileSystemBackendCall}
import cromwell.engine.workflow.CallKey
import cromwell.engine.{AbortRegistrationFunction, WorkflowDescriptor}

case class SgeBackendCall(backend: SgeBackend,
                          workflowDescriptor: WorkflowDescriptor,
                          key: CallKey,
                          locallyQualifiedInputs: CallInputs,
                          callAbortRegistrationFunction: AbortRegistrationFunction) extends BackendCall with LocalFileSystemBackendCall {
  val workflowRootPath = LocalBackend.hostExecutionPath(workflowDescriptor)
  val callRootPath = LocalBackend.hostCallPath(workflowDescriptor, call.name, key.index)
  val stdout = callRootPath.resolve("stdout")
  val stderr = callRootPath.resolve("stderr")
  val script = callRootPath.resolve("script.sh")
  val returnCode = callRootPath.resolve("rc")
  val engineFunctions: SgeEngineFunctions = new SgeEngineFunctions(callRootPath, stdout, stderr)
  val lookupFunction: String => WdlValue = inputName => locallyQualifiedInputs.get(inputName).get
  callRootPath.toFile.mkdirs
  def execute: ExecutionResult = backend.execute(this)
}
