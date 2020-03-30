package cromwell.backend.google.pipelines.v2alpha1

import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.refineV
import mouse.all._
import org.slf4j.Logger
import wdl4s.parser.MemoryUnit
import wom.format.MemorySize

object MachineConstraints {
  implicit class EnhancedInformation(val information: MemorySize) extends AnyVal {
    def asMultipleOf(factor: MemorySize): MemorySize = MemorySize(factor.amount * (information.bytes / factor.bytes).ceil, factor.unit)
    def toMBString = information.to(MemoryUnit.MB).toString
  }

  // https://cloud.google.com/compute/docs/instances/creating-instance-with-custom-machine-type
  // https://cloud.google.com/compute/docs/instances/creating-instance-with-custom-machine-type#specifications
  private val minMemoryPerCpu = MemorySize(0.5, MemoryUnit.GB)
  private val maxMemoryPerCpu = MemorySize(8.0, MemoryUnit.GB)
  private val memoryFactor = MemorySize(256, MemoryUnit.MB)

  private def validateCpu(cpu: Int Refined Positive) = cpu.value match {
    case numLessOrEquals2 if numLessOrEquals2 <= 2 => 2
    // You can create VMs with vCPUs in multiples of 4 with up to 8 vCPUs. For VMs with more than 16 vCPUs, you can
    // create custom VMs with a vCPU count in multiples of 16.
    case numLessOrEquals4 if numLessOrEquals4 <= 4 => 4
    case numLessOrEquals8 if numLessOrEquals8 <= 8 => 8
    case numGreaterThan8 =>
      numGreaterThan8 match {
        case divisibleBy16 if divisibleBy16 % 16 == 0 => divisibleBy16
        case notDivisibleBy16 => notDivisibleBy16 + (16 - (notDivisibleBy16 % 16))
      }
  }

  private def validateMemory(memory: MemorySize) = memory.asMultipleOf(memoryFactor)

  // Assumes memory and cpu have been validated individually
  private def balanceMemoryAndCpu(memory: MemorySize, cpu: Int) = {
    val memoryPerCpuRatio = memory.bytes / cpu.toDouble

    lazy val adjustedMemory = MemorySize(minMemoryPerCpu.amount * cpu.toDouble, minMemoryPerCpu.unit) |> validateMemory

    lazy val adjustedCpu = refineV[Positive]((memory.bytes / maxMemoryPerCpu.bytes).ceil.toInt) match {
      // If for some reason the above yields 0, keep the cpu value unchanged 
      case Left(_) => cpu
      case Right(adjusted) => validateCpu(adjusted)
    }

    // If we're under the ratio, top up the memory. Because validMemory will only increase memory (if needed),
    // there's no risk that the call to validMemory will make the ratio invalid
    if (memoryPerCpuRatio < minMemoryPerCpu.bytes) {
      cpu -> adjustedMemory
    } else
    // If we're over the ratio, top up the CPU. Because validCpu will only increase CPU (if needed), there's no risk
    // that the call to validCpu will make the ratio invalid
    if (memoryPerCpuRatio > maxMemoryPerCpu.bytes) {
      adjustedCpu -> memory
    } else cpu -> memory
  }
  
  private def logAdjustment(originalCpu: Int, adjustedCpu: Int, originalMemory: MemorySize, adjustedMemory: MemorySize, logger: Logger) = {
    def memoryAdjustmentLog = s"memory was adjusted from ${originalMemory.toMBString} to ${adjustedMemory.toMBString}"
    def cpuAdjustmentLog = s"cpu was adjusted from $originalCpu to $adjustedCpu"
    
    val message = (originalCpu == adjustedCpu, originalMemory.to(MemoryUnit.MB).amount == adjustedMemory.to(MemoryUnit.MB).amount) match {
      case (true, false) => Option(memoryAdjustmentLog)
      case (false, true) => Option(cpuAdjustmentLog)
      case (false, false) => Option(memoryAdjustmentLog + " and " + cpuAdjustmentLog)
      case _ => None
    }

    message foreach { m => logger.info("To comply with GCE custom machine requirements, " + m) }
  }

  def machineType(memory: MemorySize, cpu: Int Refined Positive, jobLogger: Logger) = {
    val (validCpu, validMemory) = balanceMemoryAndCpu(memory |> validateMemory, cpu |> validateCpu)
    logAdjustment(cpu.value, validCpu, memory, validMemory, jobLogger)
    s"n2d-custom-$validCpu-${validMemory.to(MemoryUnit.MB).amount.intValue()}"
  }
}
