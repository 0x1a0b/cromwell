package cromwell

import cromwell.util.FileUtil
import cromwell.util.SampleWdl.ThreeStep
import org.scalatest.{FlatSpec, Matchers}

class MainSpec extends FlatSpec with Matchers {
  val wdlFilePathAndWriter = FileUtil.tempFileAndWriter("wdl")
  val inputsJsonPathAndWriter = FileUtil.tempFileAndWriter("inputs")

  wdlFilePathAndWriter match {
    case (path, writer) =>
      writer.write(ThreeStep.WdlSource)
      writer.close()
  }

  inputsJsonPathAndWriter match {
    case (path, writer) =>
      writer.write("""{"three_step.cgrep.pattern": "..."}""")
      writer.close()
  }

  "Main" should "validate" in {
    val stream = new java.io.ByteArrayOutputStream()
    Console.withOut(stream) {
      Main.validate(Array(wdlFilePathAndWriter._1.toFile.getAbsolutePath))
    }
    stream.toString.length shouldEqual 0
  }

  it should "parse" in {
    val stream = new java.io.ByteArrayOutputStream()
    Console.withOut(stream) {
      Main.parse(Array(wdlFilePathAndWriter._1.toAbsolutePath.toString))
    }
    assert(stream.toString.contains("(Document:"))
  }

  it should "highlight" in {
    val stream = new java.io.ByteArrayOutputStream()
    Console.withOut(stream) {
      Main.highlight(Array(wdlFilePathAndWriter._1.toAbsolutePath.toString))
    }
    val expected =
      s"""\u001b[38;5;214mtask\u001b[0m \u001b[38;5;253mps\u001b[0m {
         |  command {
         |    ps
         |  }
         |  output {
         |    \u001b[38;5;33mFile\u001b[0m \u001b[38;5;112mprocs\u001b[0m = \033[38;5;13mstdout\033[0m()
         |  }
         |}
         |
         |\u001b[38;5;214mtask\u001b[0m \u001b[38;5;253mcgrep\u001b[0m {
         |  command {
         |    grep '$${\u001b[38;5;33mString\u001b[0m \u001b[38;5;112mpattern\u001b[0m}' $${\u001b[38;5;33mFile\u001b[0m \u001b[38;5;112min_file\u001b[0m} | wc -l
         |  }
         |  output {
         |    \u001b[38;5;33mInt\u001b[0m \u001b[38;5;112mcount\u001b[0m = \u001b[38;5;13mread_int\u001b[0m(\033[38;5;13mstdout\033[0m())
         |  }
         |}
         |
         |\u001b[38;5;214mtask\u001b[0m \u001b[38;5;253mwc\u001b[0m {
         |  command {
         |    cat $${\u001b[38;5;33mFile\u001b[0m \u001b[38;5;112min_file\u001b[0m} | wc -l
         |  }
         |  output {
         |    \u001b[38;5;33mInt\u001b[0m \u001b[38;5;112mcount\u001b[0m = \u001b[38;5;13mread_int\u001b[0m(\033[38;5;13mstdout\033[0m())
         |  }
         |}
         |
         |\u001b[38;5;214mworkflow\u001b[0m \u001b[38;5;253mthree_step\u001b[0m {
         |  \u001b[38;5;214mcall\u001b[0m \u001b[38;5;253mps\u001b[0m
         |  \u001b[38;5;214mcall\u001b[0m \u001b[38;5;253mcgrep\u001b[0m {
         |    input: in_file=ps.procs
         |  }
         |  \u001b[38;5;214mcall\u001b[0m \u001b[38;5;253mwc\u001b[0m {
         |    input: in_file=ps.procs
         |  }
         |}""".stripMargin
    stream.toString.stripLineEnd shouldEqual expected
  }

  it should "return inputs" in {
    val stream = new java.io.ByteArrayOutputStream()
    Console.withOut(stream) {
      Main.inputs(Array(wdlFilePathAndWriter._1.toAbsolutePath.toString))
    }
    assert(stream.toString.contains("\"three_step.cgrep.pattern\""))
  }

  it should "print usage" in {
    val stream = new java.io.ByteArrayOutputStream()
    Console.withOut(stream) {
      Main.usageAndExit(false)
    }
    assert(stream.toString.contains("java -jar cromwell.jar <action> <parameters>"))
  }
}
