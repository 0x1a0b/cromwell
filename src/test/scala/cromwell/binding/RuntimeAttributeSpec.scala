package cromwell.binding

import com.google.api.services.genomics.model.Disk
import cromwell.parser.BackendType
import org.scalatest.{EitherValues, Matchers, FlatSpec}
import RuntimeAttributeSpec._
import RuntimeAttributes.EnhancedAst

object RuntimeAttributeSpec {
  val WorkflowWithRuntime =
    """
      |task ps {
      |  command {
      |    ps
      |  }
      |  output {
      |    File procs = stdout()
      |  }
      |  runtime {
      |    docker: "ubuntu:latest"
      |  }
      |}
      |
      |task cgrep {
      |  String pattern
      |  File in_file
      |  command {
      |    grep '${pattern}' ${in_file} | wc -l
      |  }
      |  output {
      |    Int count = read_int(stdout())
      |  }
      |  runtime {
      |    docker: "ubuntu:latest"
      |  }
      |}
      |
      |task wc {
      |  File in_file
      |  command {
      |    cat ${in_file} | wc -l
      |  }
      |  output {
      |    Int count = read_int(stdout())
      |  }
      |  runtime {
      |     docker: "ubuntu:latest"
      |  }
      |}
      |
      |workflow three_step {
      |  call ps
      |  call cgrep {
      |    input: in_file=ps.procs
      |  }
      |  call wc {
      |    input: in_file=ps.procs
      |  }
      |}
      |
    """.stripMargin

  val WorkflowWithoutRuntime =
    """
      |task hello {
      |  String addressee
      |  command {
      |    echo "Hello ${addressee}!"
      |  }
      |  output {
      |    String salutation = read_string(stdout())
      |  }
      |}
      |
      |workflow hello {
      |  call hello
      |}
    """.stripMargin

  val WorkflowWithFailOnStderr =
    """
      |task echoWithFailOnStderr {
      |  command {
      |    echo 66555 >&2
      |  }
      |  runtime {
      |    failOnStderr: "true"
      |  }
      |}
      |task echoWithoutFailOnStderr {
      |  command {
      |    echo 66555 >&2
      |  }
      |  runtime {
      |    failOnStderr: "false"
      |  }
      |}
      |
      |workflow echo_wf {
      |  call echoWithFailOnStderr
      |  call echoWithoutFailOnStderr
      |}
    """.stripMargin

  val WorkflowWithContinueOnReturnCode =
    """
      |task echoWithSingleContinueOnReturnCode {
      |  command {
      |    cat non_existent_file
      |  }
      |  runtime {
      |    continueOnReturnCode: 123
      |  }
      |}
      |task echoWithExpressionContinueOnReturnCode {
      |  command {
      |    cat non_existent_file
      |  }
      |  runtime {
      |    continueOnReturnCode: 123 + 321
      |  }
      |}
      |task echoWithListContinueOnReturnCode {
      |  command {
      |    cat non_existent_file
      |  }
      |  runtime {
      |    continueOnReturnCode: [0, 1, 2, 3]
      |  }
      |}
      |task echoWithTrueContinueOnReturnCode {
      |  command {
      |    cat non_existent_file
      |  }
      |  runtime {
      |    continueOnReturnCode: true
      |  }
      |}
      |task echoWithFalseContinueOnReturnCode {
      |  command {
      |    cat non_existent_file
      |  }
      |  runtime {
      |    continueOnReturnCode: false
      |  }
      |}
      |task echoWithTrueStringContinueOnReturnCode {
      |  command {
      |    cat non_existent_file
      |  }
      |  runtime {
      |    continueOnReturnCode: "true"
      |  }
      |}
      |task echoWithFalseStringContinueOnReturnCode {
      |  command {
      |    cat non_existent_file
      |  }
      |  runtime {
      |    continueOnReturnCode: "false"
      |  }
      |}
      |
      |workflow echo_wf {
      |  call echoWithSingleContinueOnReturnCode
      |  call echoWithExpressionContinueOnReturnCode
      |  call echoWithListContinueOnReturnCode
      |  call echoWithTrueContinueOnReturnCode
      |  call echoWithFalseContinueOnReturnCode
      |  call echoWithTrueStringContinueOnReturnCode
      |  call echoWithFalseStringContinueOnReturnCode
      |}
    """.stripMargin

  val WorkflowWithFullGooglyConfig =
    """
      |task googly_task {
      |  command {
      |    echo "Hello JES!"
      |  }
      |  runtime {
      |    docker: "ubuntu:latest"
      |    memory: "4G"
      |    cpu: "3"
      |    defaultZones: "US_Metro US_Backwater"
      |    defaultDisks: "Disk1 3 SSD, Disk2 500 OldSpinnyKind"
      |  }
      |}
      |
      |workflow googly_workflow {
      |  call googly_task
      |}
    """.stripMargin

  val WorkflowWithoutGooglyConfig =
    """
      |task googly_task {
      |  command {
      |    echo "Hello JES!"
      |  }
      |  runtime {
      |    docker: "ubuntu:latest"
      |  }
      |}
      |
      |workflow googly_workflow {
      |  call googly_task
      |}
    """.stripMargin

  val WorkflowWithMessedUpMemory =
  """
    |task messed_up_memory {
    |  command {
    |      echo "YO"
    |  }
    |  runtime {
    |    memory: "HI TY"
    |  }
    |}
    |
    |workflow great_googly_moogly {
    |  call messed_up_memory
    |}
  """.stripMargin

  val WorkflowWithMessedUpMemoryUnit =
    """
      |task messed_up_memory {
      |  command {
      |      echo "YO"
      |  }
      |  runtime {
      |    memory: "5 TY"
      |  }
      |}
      |
      |workflow great_googly_moogly {
      |  call messed_up_memory
      |}
    """.stripMargin
}

class RuntimeAttributeSpec extends FlatSpec with Matchers with EitherValues {
  val NamespaceWithRuntime = NamespaceWithWorkflow.load(WorkflowWithRuntime, BackendType.LOCAL)

  it should "have docker information" in {
    assert(NamespaceWithRuntime.workflow.calls forall {
      _.task.runtimeAttributes.docker.get == "ubuntu:latest"
    })
  }

  "WDL file with failOnStderr runtime" should "identify failOnStderr for (and only for) appropriate tasks" in {
    val namespaceWithFailOnStderr = NamespaceWithWorkflow.load(WorkflowWithFailOnStderr, BackendType.LOCAL)
    val echoWithFailOnStderrIndex = namespaceWithFailOnStderr.workflow.calls.indexWhere(call => call.name == "echoWithFailOnStderr")
    assert(echoWithFailOnStderrIndex >= 0)
    assert(namespaceWithFailOnStderr.workflow.calls(echoWithFailOnStderrIndex).failOnStderr)

    val echoWithoutFailOnStderrIndex = namespaceWithFailOnStderr.workflow.calls.indexWhere(call => call.name == "echoWithoutFailOnStderr")
    assert(echoWithoutFailOnStderrIndex >= 0)
    assert(!namespaceWithFailOnStderr.workflow.calls(echoWithoutFailOnStderrIndex).failOnStderr)
  }

  "WDL file with continueOnReturnCode runtime" should "identify continueOnReturnCode for (and only for) appropriate tasks" in {
    val namespaceWithContinueOnReturnCode = NamespaceWithWorkflow.load(WorkflowWithContinueOnReturnCode, BackendType.LOCAL)

    val echoWithSingleContinueOnReturnCodeIndex =
      namespaceWithContinueOnReturnCode.workflow.calls indexWhere { call =>
        call.name == "echoWithSingleContinueOnReturnCode"
      }
    echoWithSingleContinueOnReturnCodeIndex should be >= 0
    namespaceWithContinueOnReturnCode.workflow.calls(echoWithSingleContinueOnReturnCodeIndex)
      .continueOnReturnCode should be(ContinueOnReturnCodeSet(Set(123)))

    val echoWithExpressionContinueOnReturnCodeIndex =
      namespaceWithContinueOnReturnCode.workflow.calls indexWhere { call =>
        call.name == "echoWithExpressionContinueOnReturnCode"
      }
    echoWithExpressionContinueOnReturnCodeIndex should be >= 0
    namespaceWithContinueOnReturnCode.workflow.calls(echoWithExpressionContinueOnReturnCodeIndex)
      .continueOnReturnCode should be(ContinueOnReturnCodeSet(Set(444)))

    val echoWithListContinueOnReturnCodeIndex =
      namespaceWithContinueOnReturnCode.workflow.calls indexWhere { call =>
        call.name == "echoWithListContinueOnReturnCode"
      }
    echoWithListContinueOnReturnCodeIndex should be >= 0
    namespaceWithContinueOnReturnCode.workflow.calls(echoWithListContinueOnReturnCodeIndex)
      .continueOnReturnCode should be(ContinueOnReturnCodeSet(Set(0, 1, 2, 3)))

    val echoWithTrueContinueOnReturnCodeIndex =
      namespaceWithContinueOnReturnCode.workflow.calls indexWhere { call =>
        call.name == "echoWithTrueContinueOnReturnCode"
      }
    echoWithTrueContinueOnReturnCodeIndex should be >= 0
    namespaceWithContinueOnReturnCode.workflow.calls(echoWithTrueContinueOnReturnCodeIndex)
      .continueOnReturnCode should be(ContinueOnReturnCodeFlag(true))

    val echoWithFalseContinueOnReturnCodeIndex =
      namespaceWithContinueOnReturnCode.workflow.calls indexWhere { call =>
        call.name == "echoWithFalseContinueOnReturnCode"
      }
    echoWithFalseContinueOnReturnCodeIndex should be >= 0
    namespaceWithContinueOnReturnCode.workflow.calls(echoWithFalseContinueOnReturnCodeIndex)
      .continueOnReturnCode should be(ContinueOnReturnCodeFlag(false))

    val echoWithTrueStringContinueOnReturnCodeIndex =
      namespaceWithContinueOnReturnCode.workflow.calls indexWhere { call =>
        call.name == "echoWithTrueStringContinueOnReturnCode"
      }
    echoWithTrueStringContinueOnReturnCodeIndex should be >= 0
    namespaceWithContinueOnReturnCode.workflow.calls(echoWithTrueStringContinueOnReturnCodeIndex)
      .continueOnReturnCode should be(ContinueOnReturnCodeFlag(true))

    val echoWithFalseStringContinueOnReturnCodeIndex =
      namespaceWithContinueOnReturnCode.workflow.calls indexWhere { call =>
        call.name == "echoWithFalseStringContinueOnReturnCode"
      }
    echoWithFalseStringContinueOnReturnCodeIndex should be >= 0
    namespaceWithContinueOnReturnCode.workflow.calls(echoWithFalseStringContinueOnReturnCodeIndex)
      .continueOnReturnCode should be(ContinueOnReturnCodeFlag(false))
  }

  "WDL file with Googly config" should "parse up properly" in {
    val namespaceWithGooglyConfig = NamespaceWithWorkflow.load(WorkflowWithFullGooglyConfig, BackendType.JES)
    val calls = namespaceWithGooglyConfig.workflow.calls
    val callIndex = calls.indexWhere(call => call.name == "googly_task")
    callIndex should be >= 0

    val googlyCall = calls(callIndex)
    val attributes = googlyCall.task.runtimeAttributes
    attributes.cpu shouldBe 3
    val firstDisk = new Disk().setName("Disk1").setSizeGb(3L).setType("SSD")
    val secondDisk = new Disk().setName("Disk2").setSizeGb(500L).setType("OldSpinnyKind")

    val expectedDisks = Vector(firstDisk, secondDisk, RuntimeAttributes.LocalizationDisk)
    attributes.defaultDisks foreach { d => expectedDisks should contain (d) }

    val expectedZones = Vector("US_Metro", "US_Backwater")
    attributes.defaultZones foreach { z => expectedZones should contain (z) }

    attributes.memoryGB shouldBe 4
  }

  "WDL file with no Googly config" should "also parse up properly to defaults" in {
    val NamespaceWithoutGooglyConfig = NamespaceWithWorkflow.load(WorkflowWithoutGooglyConfig, BackendType.LOCAL)
    val calls = NamespaceWithoutGooglyConfig.workflow.calls
    val callIndex = calls.indexWhere(call => call.name == "googly_task")
    callIndex should be >= 0

    val googlyCall = calls(callIndex)
    val attributes = googlyCall.task.runtimeAttributes
    attributes.cpu shouldBe RuntimeAttributes.Defaults.Cpu
    attributes.defaultDisks foreach { d => RuntimeAttributes.Defaults.Disk should contain (d) }
    attributes.defaultZones foreach { z => RuntimeAttributes.Defaults.Zones should contain (z) }
    attributes.memoryGB shouldBe RuntimeAttributes.Defaults.Memory
  }

  "WDL file with Googly config" should "issue warnings on the local backend" in {
    val workflow = NamespaceWithWorkflow.load(WorkflowWithFullGooglyConfig, BackendType.LOCAL)
    val attributeMap = workflow.ast.toAttributes
    val expectedString = "Found unsupported keys for backend 'LOCAL': cpu, defaultDisks, defaultZones, memory"
    attributeMap.unsupportedKeys(BackendType.LOCAL).head shouldBe expectedString
  }

  "WDL file without runtime section" should "not be accepted on JES backend as it has no docker" in {
    val ex = intercept[IllegalArgumentException] {
      val workflow = NamespaceWithWorkflow.load(WorkflowWithoutRuntime, BackendType.JES)
    }
    ex.getMessage should include ("Missing required keys in runtime configuration for backend 'JES': docker")
  }

  "WDL file with runtime section but no docker" should "not be accepted on JES backend" in {
    val ex = intercept[IllegalArgumentException] {
      val workflow = NamespaceWithWorkflow.load(WorkflowWithFailOnStderr, BackendType.JES)
    }
    ex.getMessage should include ("Missing required keys in runtime configuration for backend 'JES': docker")
  }


  "WDL file with a seriously screwed up memory runtime" should "not parse" in {
    val ex = intercept[IllegalArgumentException] {
      val namespaceWithBorkedMemory = NamespaceWithWorkflow.load(WorkflowWithMessedUpMemory, BackendType.LOCAL)
    }

    ex.getMessage should include ("should be of the form X Unit")
  }

  "WDL file with an invalid memory unit" should "say so" in {
    val ex = intercept[IllegalArgumentException] {
      val namespaceWithBorkedMemory = NamespaceWithWorkflow.load(WorkflowWithMessedUpMemoryUnit, BackendType.LOCAL)
    }

    ex.getMessage should include ("is an invalid memory unit")
  }
}
