package cromwell.binding

import java.io.File

import cromwell.binding.command.ParameterCommandPart
import cromwell.binding.types._
import cromwell.binding.values.{WdlArray, WdlValue}
import cromwell.parser.{AstTools, WdlParser}
import cromwell.parser.WdlParser._
import cromwell.util.FileUtil
import cromwell.parser.AstTools.{AstNodeName, EnhancedAstNode, EnhancedAstSeq}
import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}
import scala.language.postfixOps

/**
 * Define WdlNamespace as a sum type w/ two states - one containing a local workflow and one without.
 * The latter is a valid state for a WDL file, however only the former can be requested to be run, so
 * any constructs (e.g. WorkflowManagerActor) expecting to run a workflow should only take the `NamespaceWithWorkflow`
 */
sealed trait WdlNamespace extends WdlValue {
  final val wdlType = WdlNamespaceType

  def ast: Ast // FIXME: I think this is only used by the syntax highlighting, can it go away once we're built?
  def importedAs: Option[String] // Used when imported with `as` 
  def imports: Seq[Import] // FIXME: Change to Set?
  def namespaces: Seq[WdlNamespace] // FIXME: Change to Set? FIXME: Rename to importedNamespaces?
  def tasks: Seq[Task] // FIXME: Change to Set?
  def terminalMap: Map[Terminal, WdlSource]

  // Convenience method for findTask in the context of this namespace
  def findTask(name: String): Option[Task] = WdlNamespace.findTask(name, namespaces, tasks)

  override def toRawString = ???
  override def toWdlString = ???
}

/**
 * A valid Namespace which doesn't have a locally defined Workflow. This should pass any validity checking but is not
 * directly runnable by `WorkflowManagerActor`
 */
case class NamespaceWithoutWorkflow(importedAs: Option[String],
                                    imports: Seq[Import],
                                    namespaces: Seq[WdlNamespace],
                                    tasks: Seq[Task],
                                    terminalMap: Map[Terminal, WdlSource],
                                    ast: Ast) extends WdlNamespace
/**
 * Represents a WdlNamespace which has a local workflow, i.e. a directly runnable namespace
 *
 * FIXME: getCallFromMemberAccessAst was being doubly used as validator *and* runtime fetcher, thus needs that formatter. We shouldn't be doing syntax checks at runtime
 */
case class NamespaceWithWorkflow(importedAs: Option[String],
                                 workflow: Workflow,
                                 imports: Seq[Import],
                                 namespaces: Seq[WdlNamespace],
                                 tasks: Seq[Task],
                                 terminalMap: Map[Terminal, WdlSource],
                                 wdlSyntaxErrorFormatter: WdlSyntaxErrorFormatter,
                                 ast: Ast) extends WdlNamespace {
  /**
   * Confirm all required inputs are present and attempt to coerce raw inputs to `WdlValue`s.
   * This can fail if required raw inputs are missing or if the values for a specified raw input
   * cannot be coerced to the target type of the input as specified in the namespace.
   */
  def coerceRawInputs(rawInputs: WorkflowRawInputs): Try[WorkflowCoercedInputs] = {
    def coerceRawInput(input: WorkflowInput): Try[Option[WdlValue]] = input.fqn match {
      case _ if rawInputs.contains(input.fqn) =>
        val rawValue = rawInputs.get(input.fqn).get
        val coercionFailure = Failure(new UnsatisfiedInputsException(s"Could not coerce value for '${input.fqn}' into: ${input.wdlType}"))
        input.wdlType.coerceRawValue(rawValue) match {
          case Success(value) => Success(Some(value))
          case _ if input.postfixQuantifier.isDefined && input.wdlType.isInstanceOf[WdlArrayType] && ParameterCommandPart.PostfixQuantifiersThatAcceptArrays.contains(input.postfixQuantifier.get) =>
            val memberType = input.wdlType.asInstanceOf[WdlArrayType].memberType
            memberType.coerceRawValue(rawValue) match {
              case Success(value) => Success(Some(WdlArray(WdlArrayType(memberType), Seq(value))))
              case _ => coercionFailure
            }
          case _ => coercionFailure
        }
      /* TODO: if coercion fails above, it might be because you tried passing a single value to a parameter that can
       * take multiple values (e.g. `${sep=" " String var+}`).  If this is the case, coerce to
       */
      case _ =>
        input.optional match {
          case true => Success(None)
          case _ => Failure(new UnsatisfiedInputsException(s"Required workflow input '${input.fqn}' not specified."))
        }
    }

    val tryCoercedValues = workflow.inputs.map {input =>
      input.fqn -> coerceRawInput(input)
    }.toMap

    val (successes, failures) = tryCoercedValues.partition { case (_, tryValue) => tryValue.isSuccess }
    if (failures.isEmpty) {
      Try(for {
        (key, tryValue) <- successes
        optionValue = tryValue.get if tryValue.get.isDefined
      } yield (key -> optionValue.get))
    } else {
      val message = failures.values.collect { case f: Failure[_] => f.exception.getMessage }.mkString("\n")
      Failure(new UnsatisfiedInputsException(s"The following errors occurred while processing your inputs:\n\n$message"))
    }
  }

  /*
    FIXME: Originally this was called 2x - in validation and in WorkflowManagerActor. In the first case, the scaladoc
    comment makes sense. In the latter case it doesn't seem to be a check. Is the validating circumstance checking via
    side effect? Or the other way around?

    TODO/FIXME: Is this really the right thing to be running here anyways? WTF should syntax be checked at runtime? We could get rid of the formatter
   */
  /** Partially evaluate MemberAccess ASTs to make sure they make sense at compile time */
  def getCallFromMemberAccessAst(ast: Ast): Try[Call] = {
    NamespaceWithWorkflow.getCallFromMemberAccessAst(ast, workflow, wdlSyntaxErrorFormatter)
  }
}

/**
 * Main interface into the `cromwell.binding` package.
 *
 * Example usage:
 *
 * {{{
 * val namespace = WdlNamespace.process(new File("/path/to/file.wdl"))
 * binding.workflow.calls foreach { call =>
 *      println(call)
 * }
 * }}}
 */
object WdlNamespace {
  /**
   * Given a pointer to a WDL file, parse the text and build Workflow and Task
   * objects.
   *
   * @param wdlFile The file to parse/process
   * @return WdlBinding object with the parsed results
   * @throws WdlParser.SyntaxError if there was a problem parsing the source code
   * @throws UnsupportedOperationException if an error occurred constructing the
   *                                       Workflow and Task objects
   *
   */
  def load(wdlFile: File): WdlNamespace = {
    load(readFile(wdlFile), wdlFile.toString, localImportResolver, None)
  }

  def load(wdlFile: File, importResolver: ImportResolver): WdlNamespace = {
    load(readFile(wdlFile), wdlFile.toString, importResolver, None)
  }

  def load(wdlSource: WdlSource): WdlNamespace = load(wdlSource, "string", localImportResolver, None)

  def load(wdlSource: WdlSource, importResolver: ImportResolver): WdlNamespace = {
    load(wdlSource, "string", importResolver, None)
  }

  def load(wdlSource: WdlSource, resource: String): WdlNamespace = load(wdlSource, resource, localImportResolver, None)

  def load(wdlSource: WdlSource, resource: String, importResolver: ImportResolver): WdlNamespace = {
    load(wdlSource, resource, importResolver, None)
  }

  private def load(wdlSource: WdlSource, resource: String, importResolver: ImportResolver,
                   importedAs: Option[String]): WdlNamespace = {
    WdlNamespace(AstTools.getAst(wdlSource, resource), wdlSource, importResolver, importedAs)
  }

  /**
   * Validates the following things about the AST:
   *
   * 1) Tasks do not have duplicate inputs
   * 2) Tasks in this namespace have unique names
   * 3) Tasks and namespaces don't have overlapping names (FIXME: Likely has to do w/ DSDEEPB-726)
   */
  def apply(ast: Ast, source: WdlSource, importResolver: ImportResolver, namespace: Option[String]): WdlNamespace = {
    /**
     * All `import` statement strings at the top of the document
     */
    val imports = ast.getAttribute("imports").asInstanceOf[AstList].asScala map {x => Import(x)}

    /* WdlBinding objects for each import statement */
    val namespaces: Seq[WdlNamespace] = {for {
      i <- imports
      source = importResolver(i.uri) if source.length > 0
    } yield WdlNamespace.load(source, i.uri, importResolver, i.namespace)}.toSeq

    /* Create a map of Terminal -> WdlBinding */
    val terminalMap = AstTools.terminalMap(ast, source)
    val combinedTerminalMap = ((namespaces map {x => x.terminalMap}) ++ Seq(terminalMap)) reduce (_ ++ _)
    val wdlSyntaxErrorFormatter = new WdlSyntaxErrorFormatter(combinedTerminalMap)

    /**
     * All imported `task` definitions for `import` statements without a namespace (e.g. no `as` clause)
     * These tasks are considered to be in this current workspace
     */
    val importedTasks: Seq[Task] = namespaces flatMap { b =>
      b.importedAs match {
        case None => b.tasks
        case _ => Seq.empty[Task]
      }
    }

    /**
     * All `task` definitions defined in the WDL file (i.e. not imported)
     */
    val localTasks: Seq[Task] = ast.findAsts(AstNodeName.Task) map {Task(_, wdlSyntaxErrorFormatter)}

    /**
     * All `task` definitions, including local and imported ones
     */
    val tasks: Seq[Task] = localTasks ++ importedTasks

    /* 
     * Ensure that no namespaces collide with task names. 
     * 
     * It'd be simpler to get this via the `namespaces` themselves but don't have access to the correct AST, which is
     * required by the error syntax highlighter :/ (FIXME: Or do I?)
     */
    for {
      i <- imports
      namespaceAst <- i.namespaceAst
      task <- findTask(namespaceAst.sourceString(), namespaces, tasks)
    } yield {throw new SyntaxError(wdlSyntaxErrorFormatter.taskAndNamespaceHaveSameName(task.ast, namespaceAst.asInstanceOf[Terminal]))}

    // Detect duplicated task names
    val dupeTaskAstsByName = tasks.map(_.ast).duplicatesByName
    if (dupeTaskAstsByName.nonEmpty) {
      throw new SyntaxError(wdlSyntaxErrorFormatter.duplicateTask(dupeTaskAstsByName))
    }

    // FIXME: Here's where I'd toSet stuff after duplications are detected
    ast.findAsts(AstNodeName.Workflow) match {
      case Nil => NamespaceWithoutWorkflow(namespace, imports, namespaces, tasks, terminalMap, ast)
      case Seq(x) => NamespaceWithWorkflow(ast, x, namespace, imports, namespaces, tasks, terminalMap, wdlSyntaxErrorFormatter)
      case doh => throw new SyntaxError(wdlSyntaxErrorFormatter.tooManyWorkflows(doh.asJava))
    }
  }


  /**
   * Given a name, a collection of WdlNamespaces and a collection of Tasks will attempt to find a Task
   * with that name within the WdlNamespaces
   */
  def findTask(name: String, namespaces: Seq[WdlNamespace], tasks: Seq[Task]): Option[Task] = {
    if (name.contains(".")) {
      val parts = name.split("\\.", 2)
      /*
        FIXME: From Scott, re more explanation:

        I think this is supposed to resolve a dot-notation name by traversing into the namespaces until it finds the task... for example
          a.b.c would first find namespace a and then findTasks(b.c) in the a namespace context
          b.c would find namespace b inside of the a namespace context, then findtasks(c)
          c would find task c in the b namespace context
       */
      namespaces find {_.importedAs == Some(parts(0))} flatMap {x => findTask(parts(1), x.namespaces, x.tasks)}
    } else tasks.find(_.name == name)
  }

  private def localImportResolver(path: String): WdlSource = readFile(new File(path))
  private def readFile(wdlFile: File): WdlSource = FileUtil.slurp(wdlFile)
}

object NamespaceWithWorkflow {
  def load(wdlSource: WdlSource): NamespaceWithWorkflow = from(WdlNamespace.load(wdlSource))
  def load(wdlSource: WdlSource, importResolver: ImportResolver): NamespaceWithWorkflow = NamespaceWithWorkflow.from(WdlNamespace.load(wdlSource, importResolver))
  /**
   * Used to safely cast a WdlNamespace to a NamespaceWithWorkflow. Throws an IllegalArgumentException if another
   * form of WdlNamespace is passed to it
   */
  private def from(namespace: WdlNamespace): NamespaceWithWorkflow = {
    namespace match {
      case n: NamespaceWithWorkflow => n
      case _ => throw new IllegalArgumentException("Namespace does not have a local workflow to run")
    }
  }

  /**
   * Validates:
   * 1) All `Call` blocks reference tasks that exist
   * 2) All `Call` inputs reference actual variables on the corresponding task
   * 3) Calls do not reference the same task input more than once
   * 4) `Call` input expressions (right-hand side) should only use the MemberAccess
   * syntax (e.g: x.y) on WdlObjects (which include other `Call` invocations)
   * 5) `Call` input expressions (right-hand side) should only reference identifiers
   * that will resolve when evaluated
   */
  def apply(ast: Ast, workflowAst: Ast, namespace: Option[String], imports: Seq[Import],
            namespaces: Seq[WdlNamespace], tasks: Seq[Task], terminalMap: Map[Terminal, WdlSource],
            wdlSyntaxErrorFormatter: WdlSyntaxErrorFormatter): NamespaceWithWorkflow = {
    /*
     * Ensure that no namespaces collide with workflow names.
     *
     * It'd be simpler to get this via the `namespaces` themselves but don't have access to the correct AST, which is
     * required by the error syntax highlighter :/ (FIXME: Or do I?)
     */
    for {
      i <- imports
      namespaceAst <- i.namespaceAst
      if namespaceAst.sourceString() == workflowAst.getAttribute("name").sourceString()
    } yield {throw new SyntaxError(wdlSyntaxErrorFormatter.workflowAndNamespaceHaveSameName(workflowAst, namespaceAst.asInstanceOf[Terminal]))}

    val calls = workflowAst.findAsts(AstNodeName.Call) map {Call(_, namespaces, tasks, wdlSyntaxErrorFormatter)}
    val workflow: Workflow = Workflow(workflowAst, calls)

    // FIXME: This block is run for its side effect of blowing up on the .get (I believe!) - Should there be a real syntax error?
    // FIXME: It took me a while to understand the logic of the original code & I'm not sure this comment is correct?
    // FIXME: Also, it'd be nice to move this validity check into Workflow if possible
    // All MemberAccess ASTs that are not contained in other MemberAccess ASTs
   for {
      call <- workflow.calls
      (name, expression) <- call.inputMappings
      memberAccess <- expression.ast.findTopLevelMemberAccesses()
    } yield {
      getCallFromMemberAccessAst(memberAccess, workflow, wdlSyntaxErrorFormatter).get
    }

    new NamespaceWithWorkflow(namespace, workflow, imports, namespaces, tasks, terminalMap, wdlSyntaxErrorFormatter, ast)
  }

  // FIXME/TODO: Depending on how things work w/ the related FIXME's in the actual case class this might change. This is also being used as a validity check. I had another FIXME about this up above
  /*
   * Partially evaluate MemberAccess ASTs to make sure they're not nonsense at compile time
   *
   * MemberAccess ASTs are of the form lhs.rhs
   */
  def getCallFromMemberAccessAst(ast: Ast, workflow: Workflow, wdlSyntaxErrorFormatter: WdlSyntaxErrorFormatter): Try[Call] = {
    def callFromName(name: String): Try[Call] = {
      workflow.calls.find(_.name == name) match {
        case Some(c:Call) => Success(c)
        case _ => Failure(new SyntaxError(wdlSyntaxErrorFormatter.undefinedMemberAccess(ast)))
      }
    }
    val rhs = ast.getAttribute("rhs").sourceString()

    /**
     * The right-hand side of a member-access AST should always be interpreted as a String
     * Sometimes, the left-hand side is itself a MemberAccess AST, like in the expression
     * for `call t1` below.  In that example, callFromName("ns.ns2.task_name") would be
     * called.  In the `call t2` example, callFromName("alias") is called
     *
     * import "test.wdl" as ns
     * workflow w {
     *  call ns.ns2.task_name
     *  call t1 {
     *    input: x=ns.ns2.task_name.output
     *  }
     *
     *  call ns.ns2.task_name as alias
     *  call t2 {
     *    input: y=alias.output
     *  }
     *}
     */
    val lhs = callFromName(ast.getAttribute("lhs") match {
      case a: Ast => WdlExpression.toString(a)
      case terminal: Terminal => terminal.sourceString()
    })

    lhs match {
      case Success(c:Call) =>
        c.task.outputs.find {_.name == rhs}.getOrElse {
          throw new SyntaxError(wdlSyntaxErrorFormatter.memberAccessReferencesBadTaskInput(ast))
        }
        Success(c)
      case f => f
    }
  }
}
