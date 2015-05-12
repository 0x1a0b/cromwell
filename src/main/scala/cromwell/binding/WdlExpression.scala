package cromwell.binding

import java.util

import cromwell.binding.types.{WdlFloatType, WdlStringType, WdlIntegerType, WdlObjectType}
import cromwell.binding.values.{WdlString, WdlFloat, WdlInteger, WdlValue}
import cromwell.parser.WdlParser
import cromwell.parser.WdlParser.{AstList, Ast, AstNode, Terminal}
import scala.collection.JavaConverters._
import scala.util.{Success, Failure, Try}

class WdlExpressionException(message: String = null, cause: Throwable = null) extends RuntimeException(message, cause)

object WdlExpression {
  val parser = new WdlParser()

  def binaryOperators = Set(
    "Add", "Subtract", "Multiply", "Divide", "Remainder",
    "GreaterThan", "LessThan", "GreaterThanOrEqual", "LessThanOrEqual",
    "Equals", "NotEquals", "LogicalAnd", "LogicalOr"
  )

  def evaluate(ast: AstNode, lookup: String => WdlValue, functions: WdlFunctions): WdlValue = {
    ast match {
      case t: Terminal if t.getTerminalStr == "identifier" => lookup(t.getSourceString)
      case t: Terminal if t.getTerminalStr == "integer" => WdlInteger(t.getSourceString.toInt)
      case t: Terminal if t.getTerminalStr == "float" => WdlFloat(t.getSourceString.toFloat)
      case t: Terminal if t.getTerminalStr == "string" => WdlString(t.getSourceString)
      case a: Ast if binaryOperators.contains(a.getName) =>
        val lhs = evaluate(a.getAttribute("lhs"), lookup, functions)
        val rhs = evaluate(a.getAttribute("rhs"), lookup, functions)
        a.getName match {
          case "Add" => lhs.add(rhs)
          case _ => ???
        }
      case a: Ast if a.getName == "MemberAccess" =>
        val lhs = evaluate(a.getAttribute("lhs"), lookup, functions) match {
          case x:WdlObject => x
          case _ => throw new WdlExpressionException("Left-hand side of expression must be a WdlObject")
        }

        val rhs = a.getAttribute("rhs") match {
          case x:Terminal if x.getTerminalStr == "identifier" => x.getSourceString
          case _ => throw new WdlExpressionException("Right-hand side of expression must be identifier")
        }
        lhs.getOrElse(rhs, throw new WdlExpressionException(s"Could not find key $rhs"))
      case a: Ast if a.getName == "FunctionCall" =>
        val name = a.getAttribute("name").asInstanceOf[Terminal].getSourceString
        val params = a.getAttribute("params").asInstanceOf[AstList].asScala.toVector map {
          evaluate(_, lookup, functions)
        }
        functions.getFunction(name)(params)
    }
  }

  def fromString(expression: String): WdlExpression = {
    val tokens = parser.lex(expression, "string")
    new WdlExpression(parser.parse_e(tokens, new WdlSyntaxErrorFormatter(expression)).toAst)
  }
}

case class WdlExpression(ast: AstNode) {
  def evaluate(lookup: String => WdlValue, functions: WdlFunctions): WdlValue =
    WdlExpression.evaluate(ast, lookup, functions)
}

trait WdlFunctions {
  type WdlFunction = Seq[WdlValue] => WdlValue

  def getFunction(name: String): WdlFunction
}