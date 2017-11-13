package wom

import cats.data.Validated.{Invalid, Valid}
import org.scalatest.{FlatSpec, Matchers}
import wdl.{WdlNamespace, WdlNamespaceWithWorkflow}
import WdlNestedConditionalWomSpec._
import org.scalatest.prop.TableDrivenPropertyChecks

class WdlNestedConditionalWomSpec extends FlatSpec with Matchers {

  behavior of "WDL to WOM conversion of nested scopes"

  import TableDrivenPropertyChecks._
  val table = Table[String, String](
    ("test name", "WDL"),
    ("nested lookups", nestedLookups),
    ("nested WF inputs", nestedWorkflowInputLookups)
//    ("nested lookups with call inteference", nestedLookupsWithCallInterference),
//    ("nested lookups with double call inteference", nestedLookupsWithDoubleCallInterference),
//    ("nested lookups with declaration inteference", nestedLookupsWithDeclarationInterference),
//    ("nested lookups with double declaration inteference", nestedLookupsWithDoubleDeclarationInterference)
  )

  forAll(table) { (testName, wdl) =>


    it should s"link values outside and across nested scopes in the '$testName' WDL" in {
      val namespace = WdlNamespace.loadUsingSource(wdl, None, None).get.asInstanceOf[WdlNamespaceWithWorkflow]
      val conditionalTestGraph = namespace.workflow.womDefinition.map(_.graph)

      conditionalTestGraph match {
        case Valid(_) => () // Great!
        case Invalid(errors) => fail(s"Unable to build wom version of nested_lookups from WDL: ${errors.toList.mkString("\n", "\n", "\n")}")
      }
    }
  }
}

object WdlNestedConditionalWomSpec {

  val taskMirror =
    """
      |task mirror {
      |  Int i
      |
      |  command {
      |    echo ${i}
      |  }
      |  output {
      |    Int out = read_int(stdout())
      |  }
      |  runtime {
      |    docker: "ubuntu:latest"
      |  }
      |}""".stripMargin

  val nestedLookups =
    """workflow nested_lookups {
      |  Int i = 27
      |  Int pp = 22
      |  Int a = 82
      |  if(true) {
      |    if(true) {
      |      if(true) {
      |        call mirror as m1 { input: i = i}
      |        Int b = a
      |
      |        Int f = 100000
      |      }
      |    }
      |    if(true) {
      |      if(false) {
      |        Int? f1 = f
      |      }
      |    }
      |  }
      |
      |  Int c = select_first([b, i])
      |
      |  if(true) {
      |    if(true) {
      |      if(true) {
      |        call mirror as m2 { input: i = select_first([m1.out, 5]) + 1 }
      |        Int d = c
      |        Int? e = b
      |        Int? f2 = f1
      |      }
      |    }
      |  }
      |
      |  output {
      |    Int? m1_out = m1.out
      |    Int? m2_out = m2.out
      |
      |    Int? b_out = b
      |    Int c_out = c
      |    Int? d_out = d
      |    Int? e_out = e
      |
      |    Int? f1_out = f1
      |    Int? f2_out = f2
      |  }
      |}""".stripMargin ++ taskMirror

  val nestedWorkflowInputLookups =
    """workflow nested_lookups {
      |  if(true) {
      |    if(true) {
      |      if(true) {
      |        call mirror as needs_wf_input
      |      }
      |    }
      |  }
      |
      |  output {
      |    Int? needs_wf_input_out = needs_wf_input.out
      |  }
      |}""".stripMargin ++ taskMirror

  val nestedLookupsWithCallInterference =
    """workflow nested_lookups {
      |  Int i = 27
      |  if(true) {
      |    call mirror as throwaway { input: i = i } # Make sure this 'i' OGIN doesn't duplicate the nested m1's 'i' OGIN
      |    if(true) {
      |      if(true) {
      |        call mirror as m1 { input: i = i}
      |      }
      |    }
      |  }
      |
      |  output {
      |    Int? m1_out = m1.out
      |  }
      |}""".stripMargin ++ taskMirror

  val nestedLookupsWithDoubleCallInterference =
    """workflow nested_lookups {
      |  Int i = 27
      |  if(true) {
      |    call mirror as throwaway { input: i = i } # Make sure this 'i' OGIN doesn't duplicate the nested m1's 'i' OGIN
      |    call mirror as throwaway2 { input: i = i } # Make sure this 'i' OGIN doesn't duplicate the nested m1's 'i' OGIN
      |    if(true) {
      |      if(true) {
      |        call mirror as m1 { input: i = i}
      |      }
      |    }
      |  }
      |
      |  output {
      |    Int? m1_out = m1.out
      |  }
      |}""".stripMargin ++ taskMirror

  val nestedLookupsWithDeclarationInterference =
    """workflow nested_lookups {
      |  Int i = 27
      |  if(true) {
      |    Int? throwaway = i # Make sure this 'm1.out' OGIN doesn't duplicate the nested m2's 'm1.out' OGIN
      |    if(true) {
      |      if(true) {
      |        call mirror as m1 { input: i = i}
      |      }
      |    }
      |  }
      |
      |  output {
      |    Int? m1_out = m1.out
      |  }
      |}""".stripMargin ++ taskMirror

  val nestedLookupsWithDoubleDeclarationInterference =
    """workflow nested_lookups {
      |  Int i = 27
      |  if(true) {
      |    Int? throwaway = i # Make sure this 'm1.out' OGIN doesn't duplicate the nested m2's 'm1.out' OGIN
      |    Int? throwaway2 = i # Make sure this 'm1.out' OGIN doesn't duplicate the nested m2's 'm1.out' OGIN
      |    if(true) {
      |      if(true) {
      |        call mirror as m1 { input: i = i}
      |      }
      |    }
      |  }
      |
      |  output {
      |    Int? m1_out = m1.out
      |  }
      |}""".stripMargin ++ taskMirror
}