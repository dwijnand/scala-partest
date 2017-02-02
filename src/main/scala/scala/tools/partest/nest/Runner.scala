/* NEST (New Scala Test)
 * Copyright 2007-2013 LAMP/EPFL
 * @author Paul Phillips
 */
package scala.tools.partest
package nest

import java.io.{ Console => _, _ }
import java.net.URL
import java.nio.charset.{ Charset, CharsetDecoder, CharsetEncoder, CharacterCodingException, CodingErrorAction => Action }
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.NANOSECONDS
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.Duration
import scala.io.Codec
import scala.reflect.internal.FatalError
import scala.reflect.internal.util.ScalaClassLoader
import scala.sys.process.{ Process, ProcessLogger }
import scala.tools.nsc.Properties.{ envOrNone, isWin, jdkHome, javaHome, propOrEmpty, setProp, versionMsg, javaVmName, javaVmVersion, javaVmInfo }
import scala.tools.nsc.{ Settings, CompilerCommand, Global }
import scala.tools.nsc.reporters.ConsoleReporter
import scala.tools.nsc.util.{ Exceptional, stackTraceString }
import scala.util.{ Try, Success, Failure }
import scala.util.control.NoStackTrace
import ClassPath.{ join, split }
import TestState.{ Pass, Fail, Crash, Uninitialized, Updated }

import FileManager.{ compareFiles, compareContents, joinPaths, withTempFile }

trait TestInfo {
  /** pos/t1234 */
  def testIdent: String

  /** pos */
  def kind: String

  // inputs

  /** pos/t1234.scala or pos/t1234 if dir */
  def testFile: File

  /** pos/t1234.check */
  def checkFile: File

  /** pos/t1234.flags */
  def flagsFile: File

  // outputs

  /** pos/t1234-pos.obj */
  def outFile: File

  /** pos/t1234-pos.log */
  def logFile: File
}

/** Run a single test. Rubber meets road. */
class Runner(val testFile: File, val suiteRunner: SuiteRunner, val nestUI: NestUI) extends TestInfo {

  import suiteRunner.{fileManager => fm, _}
  val fileManager = fm

  import fileManager._

  // Override to true to have the outcome of this test displayed
  // whether it passes or not; in general only failures are reported,
  // except for a . per passing test to show progress.
  def isEnumeratedTest = false

  private var _lastState: TestState = null
  private val _transcript = new TestTranscript

  def lastState                   = if (_lastState == null) Uninitialized(testFile) else _lastState
  def setLastState(s: TestState)  = _lastState = s
  def transcript: List[String]    = _transcript.fail ++ logFile.fileLines
  def pushTranscript(msg: String) = _transcript add msg

  val parentFile = testFile.getParentFile
  val kind       = parentFile.getName
  val fileBase   = basename(testFile.getName)
  val logFile    = new File(parentFile, s"$fileBase-$kind.log")
  val outFile    = logFile changeExtension "obj"
  val checkFile  = testFile changeExtension "check"
  val flagsFile  = testFile changeExtension "flags"
  val argsFile   = testFile changeExtension "javaopts"
  val testIdent  = testFile.testIdent // e.g. pos/t1234

  lazy val outDir = { outFile.mkdirs() ; outFile }

  // oh boy...
  private lazy val antLauncher = SFile(Path(envOrNone("ANT_HOME") getOrElse "/opt/ant/") / "lib/ant-launcher.jar")

  type RanOneTest = (Boolean, LogContext)

  def showCrashInfo(t: Throwable) {
    System.err.println(s"Crashed running test $testIdent: " + t)
    if (!nestUI.terse)
      System.err.println(stackTraceString(t))
  }
  protected def crashHandler: PartialFunction[Throwable, TestState] = {
    case t: InterruptedException =>
      genTimeout()
    case t: Throwable =>
      showCrashInfo(t)
      logFile.appendAll(stackTraceString(t))
      genCrash(t)
  }

  def genPass()                   = Pass(testFile)
  def genFail(reason: String)     = Fail(testFile, reason, _transcript.fail.toArray)
  def genTimeout()                = Fail(testFile, "timed out", _transcript.fail.toArray)
  def genCrash(caught: Throwable) = Crash(testFile, caught, _transcript.fail.toArray)
  def genUpdated()                = Updated(testFile)

  private def workerError(msg: String): Unit = System.err.println("Error: " + msg)

  def javac(files: List[File]): TestState = {
    // compile using command-line javac compiler
    val args = Seq(
      javacCmdPath,
      "-d",
      outDir.getAbsolutePath,
      "-classpath",
      joinPaths(outDir :: testClassPath),
      "-J-Duser.language=en",
      "-J-Duser.country=US"
    ) ++ (toolArgsFor(files)("javac")
    ) ++ (files.map(_.getAbsolutePath)
    )

    pushTranscript(args mkString " ")
    val captured = StreamCapture(runCommand(args, logFile))
    if (captured.result) genPass() else {
      logFile appendAll captured.stderr
      genFail("java compilation failed")
    }
  }

  def testPrompt = kind match {
    case "res"  => "nsc> "
    case _      => "% "
  }

  /** Evaluate an action body and update the test state.
   *  @param failFn optionally map a result to a test state.
   */
  def nextTestAction[T](body: => T)(failFn: PartialFunction[T, TestState]): T = {
    val result = body
    setLastState( if (failFn isDefinedAt result) failFn(result) else genPass() )
    result
  }
  def nextTestActionExpectTrue(reason: String, body: => Boolean): Boolean = (
    nextTestAction(body) { case false => genFail(reason) }
  )
  def nextTestActionFailing(reason: String): Boolean = nextTestActionExpectTrue(reason, false)

  val testFullPath = testFile.getAbsolutePath

  private def fullClasspath = {
    val classpath = joinPaths(extraClasspath ++ testClassPath)
    join(outDir.toString, classpath)
  }

  private def assembleTestProperties(outDir: File, logFile: File): Map[String, String] = {
    val extras =
      if (nestUI.debug) Map("partest.debug" -> "true")
      else Map()
    Map(
      "file.encoding" -> "UTF-8",
      "java.library.path" -> logFile.getParentFile.getAbsolutePath,
      "java.class.path" -> fullClasspath, // BytecodeTest's loadClassNode depends on this
      // TODO: Should this only be added when exec'ing in process?
      // TODO: Should instead BytecodeTEst use "partest.output"?
      "partest.output" -> outDir.getAbsolutePath,
      "partest.lib" -> libraryUnderTest.getAbsolutePath,
      "partest.reflect" -> reflectUnderTest.getAbsolutePath,
      "partest.comp" -> compilerUnderTest.getAbsolutePath,
      "partest.cwd" -> outDir.getParent,
      "partest.test-path" -> testFullPath,
      "partest.testname" -> fileBase,
      "javacmd" -> javaCmdPath,
      "javaccmd" -> javacCmdPath,
      "user.language" -> "en",
      "user.country" -> "US"
    ) ++ extras
  }

  private def assembleTestCommand(outDir: File, logFile: File): List[String] = {
    val argString = file2String(argsFile)
    if (argString != "")
      nestUI.verbose("Found javaopts file '%s', using options: '%s'".format(argsFile, argString))

    // Note! As this currently functions, suiteRunner.javaOpts must precede argString
    // because when an option is repeated to java only the last one wins.
    // That means until now all the .javaopts files were being ignored because
    // they all attempt to change options which are also defined in
    // partest.java_opts, leading to debug output like:
    //
    // debug: Found javaopts file 'files/shootout/message.scala-2.javaopts', using options: '-Xss32k'
    // debug: java -Xss32k -Xss2m -Xms256M -Xmx1024M -classpath [...]

    javaCmdPath +: (
      (suiteRunner.javaOpts.split(' ') ++ extraJavaOptions ++ argString.split(' ')).map(_.trim).filter(_ != "").toList ++ Seq(
        "-classpath",
        fullClasspath
      ) ++ assembleTestProperties(outDir, logFile).map{case (prop, value) => s"-D$prop=$value"}
        ++ Seq(
        "scala.tools.nsc.MainGenericRunner",
        "-usejavacp",
        "Test",
        "jvm"  // what is this doing here? Scala.NET remnant?
      )
    )
  }

  /** Runs command redirecting standard out and
   *  error out to output file.
   */
  protected def runCommand(args: Seq[String], outFile: File): Boolean = {
    //(Process(args) #> outFile !) == 0 or (Process(args) ! pl) == 0
    val pl = ProcessLogger(outFile)
    val nonzero = 17     // rounding down from 17.3
    def run: Int = {
      val p = Process(args) run pl
      try p.exitValue
      catch {
        case e: InterruptedException =>
          nestUI.verbose(s"Interrupted waiting for command to finish (${args mkString " "})")
          p.destroy
          nonzero
        case t: Throwable =>
          nestUI.verbose(s"Exception waiting for command to finish: $t (${args mkString " "})")
          p.destroy
          throw t
      }
      finally pl.close()
    }
    (pl buffer run) == 0
  }

  type WithThunk[T] = (=> T) => T

  private def withX[T, X](x: X)(get: => X, set: X => Unit): WithThunk[T] =
    t => { val x0 = get; set(x); try t finally set(x0) }

  private def foldWiths[T](t: => T)(withs: Seq[WithThunk[T]]): T =
    withs.fold((t => t): WithThunk[T])((acc, with1) => x => acc(with1(x)))(t)

//  private def withSysProp[T](key: String, value: String): WithThunk[T] =
//    withX(value)(sys props key, v => if (v == null) sys.props -= key else sys.props(key) = v)

//  private def withSysProps[T](sysProps: Map[String, String]): WithThunk[T] =
//    t => foldWiths(t)(sysProps.toSeq.map(kv => withSysProp[T](kv._1, kv._2)))

  private def withSysProps[T](sysProps: Map[String, String]): WithThunk[T] = t => {
    import scala.collection.JavaConverters._
    val saved = new java.util.Properties() // make our own local copy (don't just use System.getProperties)
    for ((key, value) <- System.getProperties.asScala) saved.setProperty(key, value)
    for ((key, value) <- sysProps) sys.props(key) = value
    try t finally System setProperties saved
  }

  private def execTestInProcess(outDir: File, logFile: File): Boolean = {
    val logWriter = new PrintStream(new FileOutputStream(logFile, true), true)

    // TODO: Switch from this.getClass.getClassLoader to testClassPath
    val loader = ScalaClassLoader.fromURLs(List(outDir.toURI.toURL), this.getClass.getClassLoader)
    val clazz = Class.forName("Test", true, loader)
    val main = clazz.getDeclaredMethod("main", classOf[Array[String]])

    import scala.tools.nsc.util.Exceptional
    def withPrintThrowableStackAndFail(t: => Boolean): Boolean =
      try t catch {
        case t: Throwable => (Exceptional unwrap t) printStackTrace logWriter; false
      }

    def withSysExitFails(t: => Boolean): Boolean = {
      val saved = System.getSecurityManager
      System setSecurityManager TrapExitSecurityManager(saved)
      try t catch {
        case t: Throwable => Exceptional unwrap t match {
          case TrapExitSecurityException(status) => if (status == 0) true else false
          case t                                 => throw t
        }
      } finally
        System setSecurityManager saved
    }

    def invoke = foldWiths {
      main.invoke(null, Array("jvm"))
      true
    }(Seq(
      Output withRedirected logWriter,
      Console withOut logWriter,
      Console withErr logWriter,
      withSysProps(assembleTestProperties(outDir, logFile)),
      withSysExitFails,
      withPrintThrowableStackAndFail
    ))

    pushTranscript(s"Running Test in $outDir, writing to $logFile")
    nextTestAction(execInProcessLock.synchronized(invoke)) {
      case false =>
        _transcript append EOL + logFile.fileContents
        genFail("non-zero exit code")
    }
  }

  private def execTestForked(outDir: File, logFile: File): Boolean = {
    val cmd = assembleTestCommand(outDir, logFile)

    pushTranscript((cmd mkString s" \\$EOL  ") + " > " + logFile.getName)
    nextTestAction(runCommand(cmd, logFile)) {
      case false =>
        _transcript append EOL + logFile.fileContents
        genFail("non-zero exit code")
    }
  }

  // "partest.forkall" is so we can measure the speedup we're getting
  // from adding in-process support. but also, it might be useful
  // from time to time for troubleshooting
  private def execTest(outDir: File, logFile: File): Boolean =
    if (argsFile.exists() || sys.BooleanProp.valueIsTrue("partest.forkall").value)
      execTestForked(outDir, logFile)
    else
      execTestInProcess(outDir, logFile)

  override def toString = s"""Test($testIdent, lastState = $lastState)"""

  // result is unused
  def newTestWriters() = {
    val swr = new StringWriter
    val wr  = new PrintWriter(swr, true)
    // diff    = ""

    ((swr, wr))
  }

  def fail(what: Any) = {
    nestUI.verbose("scalac: compilation of "+what+" failed\n")
    false
  }

  /** Filter the check file for conditional blocks.
   *  The check file can contain lines of the form:
   *  `#partest java7`
   *  where the line contains a conventional flag name.
   *  If the flag tests true, succeeding lines are retained
   *  (removed on false) until the next #partest flag.
   *  A missing flag evaluates the same as true.
   */
  def filteredCheck: Seq[String] = {
    import scala.util.Properties.{javaVersion, isAvian}
    // use lines in block so labeled? Default to sorry, Charlie.
    def retainOn(expr: String) = {
      val f = expr.trim
      val allArgs = suiteRunner.scalacExtraArgs ++ suiteRunner.scalacOpts.split(' ')
      def flagWasSet(f: String) = allArgs contains f
      val (invert, token) =
        if (f startsWith "!") (true, f drop 1) else (false, f)
      val cond = token.trim match {
        case "java8"  => javaVersion startsWith "1.8"
        case "java7"  => javaVersion startsWith "1.7"
        case "java6"  => javaVersion startsWith "1.6"
        case "avian"  => isAvian
        case "true"   => true
        case "-optimise" | "-optimize"
                      => flagWasSet("-optimise") || flagWasSet("-optimize")
        case flag if flag startsWith "-"
                      => flagWasSet(flag)
        case rest     => rest.isEmpty
      }
      if (invert) !cond else cond
    }
    val prefix = "#partest"
    val b = new ListBuffer[String]()
    var on = true
    for (line <- file2String(checkFile).linesIfNonEmpty) {
      if (line startsWith prefix) {
        on = retainOn(line stripPrefix prefix)
      } else if (on) {
        b += line
      }
    }
    b.toList
  }

  def currentDiff = {
    val logged = file2String(logFile).linesIfNonEmpty.toList
    val (other, othername) = if (checkFile.canRead) (filteredCheck, checkFile.getName) else (Nil, "empty")
    compareContents(original = other, revised = logged, originalName = othername, revisedName = logFile.getName)
  }

  val gitRunner = List("/usr/local/bin/git", "/usr/bin/git") map (f => new java.io.File(f)) find (_.canRead)
  val gitDiffOptions = "--ignore-space-at-eol --no-index " + propOrEmpty("partest.git_diff_options")
    // --color=always --word-diff

  def gitDiff(f1: File, f2: File): Option[String] = {
    try gitRunner map { git =>
      val cmd  = s"$git diff $gitDiffOptions $f1 $f2"
      val diff = Process(cmd).lineStream_!.drop(4).map(_ + "\n").mkString

      "\n" + diff
    }
    catch { case t: Exception => None }
  }

  /** Normalize the log output by applying test-specific filters
   *  and fixing filesystem-specific paths.
   *
   *  Line filters are picked up from `filter: pattern` at the top of sources.
   *  The filtered line is detected with a simple "contains" test,
   *  and yes, "filter" means "filter out" in this context.
   *
   *  File paths are detected using the absolute path of the test root.
   *  A string that looks like a file path is normalized by replacing
   *  the leading segments (the root) with "\$ROOT" and by replacing
   *  any Windows backslashes with the one true file separator char.
   */
  def normalizeLog() {
    import scala.util.matching.Regex

    // Apply judiciously; there are line comments in the "stub implementations" error output.
    val slashes    = """[/\\]+""".r
    def squashSlashes(s: String) = slashes replaceAllIn (s, "/")

    // this string identifies a path and is also snipped from log output.
    val elided     = parentFile.getAbsolutePath

    // something to mark the elision in the log file (disabled)
    val ellipsis   = "" //".../"    // using * looks like a comment

    // no spaces in test file paths below root, because otherwise how to detect end of path string?
    val pathFinder = raw"""(?i)\Q${elided}${File.separator}\E([\${File.separator}\S]*)""".r
    def canonicalize(s: String): String = (
      pathFinder replaceAllIn (s, m =>
        Regex.quoteReplacement(ellipsis + squashSlashes(m group 1)))
    )

    def masters    = {
      val files = List(new File(parentFile, "filters"), new File(PathSettings.srcDir.path, "filters"))
      files filter (_.exists) flatMap (_.fileLines) map (_.trim) filter (s => !(s startsWith "#"))
    }
    val filters    = toolArgs("filter", split = false) ++ masters
    val elisions   = ListBuffer[String]()
    //def lineFilter(s: String): Boolean  = !(filters exists (s contains _))
    def lineFilter(s: String): Boolean  = (
      filters map (_.r) forall { r =>
        val res = (r findFirstIn s).isEmpty
        if (!res) elisions += s
        res
      }
    )

    logFile.mapInPlace(canonicalize)(lineFilter)
    if (nestUI.verbose && elisions.nonEmpty) {
      import nestUI.color._
      val emdash = bold(yellow("--"))
      pushTranscript(s"filtering ${logFile.getName}$EOL${elisions mkString (emdash, EOL + emdash, EOL)}")
    }
  }

  def diffIsOk: Boolean = {
    // always normalize the log first
    normalizeLog()
    val diff = currentDiff
    // if diff is not empty, is update needed?
    val updating: Option[Boolean] = (
      if (diff == "") None
      else Some(updateCheck)
    )
    pushTranscript(s"diff $logFile $checkFile")
    nextTestAction(updating) {
      case Some(true)  =>
        nestUI.verbose("Updating checkfile " + checkFile)
        checkFile writeAll file2String(logFile)
        genUpdated()
      case Some(false) =>
        // Get a word-highlighted diff from git if we can find it
        val bestDiff =
          if (updating.isEmpty) ""
          else if (checkFile.canRead)
            withTempFile(outFile, fileBase, filteredCheck) { f =>
              gitDiff(logFile, f) getOrElse f"diff $logFile $checkFile%n$diff"
            }
          else diff
        _transcript append bestDiff
        genFail("output differs")
        // TestState.fail("output differs", "output differs",
        // genFail("output differs")
        // TestState.Fail("output differs", bestDiff)
      case None        => genPass()  // redundant default case
    } getOrElse true
  }

  /** 1. Creates log file and output directory.
   *  2. Runs script function, providing log file and output directory as arguments.
   *     2b. or, just run the script without context and return a new context
   */
  def runInContext(body: => Boolean): (Boolean, LogContext) = {
    val (swr, wr) = newTestWriters()
    val succeeded = body
    (succeeded, LogContext(logFile, swr, wr))
  }

  /** Grouped files in group order, and lex order within each group. */
  def groupedFiles(sources: List[File]): List[List[File]] = (
    if (sources.tail.nonEmpty) {
      val grouped = sources groupBy (_.group)
      grouped.keys.toList.sorted map (k => grouped(k) sortBy (_.getName))
    }
    else List(sources)
  )

  /** Source files for the given test file. */
  def sources(file: File): List[File] = (
    if (file.isDirectory)
      file.listFiles.toList filter (_.isJavaOrScala)
    else
      List(file)
  )

  def newCompiler = new DirectCompiler(this)

  def attemptCompile(sources: List[File]): TestState = {
    val state = newCompiler.compile(flagsForCompilation(sources), sources)
    if (!state.isOk)
      _transcript append ("\n" + file2String(logFile))

    state
  }

  // snort or scarf all the contributing flags files
  def flagsForCompilation(sources: List[File]): List[String] = {
    def argsplitter(s: String) = words(s) filter (_.nonEmpty)
    val perTest  = argsplitter(flagsFile.fileContents)
    val perGroup = if (testFile.isDirectory) {
      sources flatMap { f => SFile(Path(f) changeExtension "flags").safeSlurp map argsplitter getOrElse Nil }
    } else Nil
    perTest ++ perGroup
  }

  // inspect sources for tool args
  def toolArgs(tool: String, split: Boolean = true): List[String] =
    toolArgsFor(sources(testFile))(tool, split)

  // inspect given files for tool args
  def toolArgsFor(files: List[File])(tool: String, split: Boolean = true): List[String] = {
    def argsplitter(s: String) = if (split) words(s) filter (_.nonEmpty) else List(s)
    def argsFor(f: File): List[String] = {
      import scala.util.matching.Regex
      val p    = new Regex(s"(?:.*\\s)?${tool}:(?:\\s*)(.*)?", "args")
      val max  = 10
      val src  = Path(f).toFile.chars(codec)
      val args = try {
        src.getLines take max collectFirst {
          case s if (p findFirstIn s).nonEmpty => for (m <- p findFirstMatchIn s) yield m group "args"
        }
      } finally src.close()
      args.flatten map argsplitter getOrElse Nil
    }
    files flatMap argsFor
  }

  abstract class CompileRound {
    def fs: List[File]
    def result: TestState
    def description: String

    def fsString = fs map (_.toString stripPrefix parentFile.toString + "/") mkString " "
    def isOk = result.isOk
    def mkScalacString(): String = s"""scalac $fsString"""
    override def toString = description + ( if (result.isOk) "" else "\n" + result.status )
  }
  case class OnlyJava(fs: List[File]) extends CompileRound {
    def description = s"""javac $fsString"""
    lazy val result = { pushTranscript(description) ; javac(fs) }
  }
  case class OnlyScala(fs: List[File]) extends CompileRound {
    def description = mkScalacString()
    lazy val result = { pushTranscript(description) ; attemptCompile(fs) }
  }
  case class ScalaAndJava(fs: List[File]) extends CompileRound {
    def description = mkScalacString()
    lazy val result = { pushTranscript(description) ; attemptCompile(fs) }
  }

  def compilationRounds(file: File): List[CompileRound] = (
    (groupedFiles(sources(file)) map mixedCompileGroup).flatten
  )
  def mixedCompileGroup(allFiles: List[File]): List[CompileRound] = {
    val (scalaFiles, javaFiles) = allFiles partition (_.isScala)
    val round1                  = if (scalaFiles.isEmpty) None else Some(ScalaAndJava(allFiles))
    val round2                  = if (javaFiles.isEmpty) None else Some(OnlyJava(javaFiles))

    List(round1, round2).flatten
  }

  def runNegTest() = runInContext {
    val rounds = compilationRounds(testFile)

    // failing means Does Not Compile
    val failing = rounds find (x => nextTestActionExpectTrue("compilation failed", x.isOk) == false)

    // which means passing if it checks and didn't crash the compiler
    // or, OK, we'll let you crash the compiler with a FatalError if you supply a check file
    def checked(r: CompileRound) = r.result match {
      case Crash(_, t, _) if !checkFile.canRead || !t.isInstanceOf[FatalError] => false
      case _ => diffIsOk
    }

    failing map (checked) getOrElse nextTestActionFailing("expected compilation failure")
  }

  def runTestCommon(andAlso: => Boolean): (Boolean, LogContext) = runInContext {
    compilationRounds(testFile).forall(x => nextTestActionExpectTrue("compilation failed", x.isOk)) && andAlso
  }

  // Apache Ant 1.6 or newer
  def ant(args: Seq[String], output: File): Boolean = {
    val antOptions =
      if (nestUI.verbose) List("-verbose", "-noinput")
      else List("-noinput")
    val cmd = javaCmdPath +: (
      suiteRunner.javaOpts.split(' ').map(_.trim).filter(_ != "") ++ Seq(
        "-classpath",
        antLauncher.path,
        "org.apache.tools.ant.launch.Launcher"
      ) ++ antOptions ++ args
    )

    runCommand(cmd, output)
  }

  def runAntTest(): (Boolean, LogContext) = {
    val (swr, wr) = newTestWriters()

    val succeeded = try {
      val binary = "-Dbinary="+ fileManager.distKind
      val args = Array(binary, "-logfile", logFile.getPath, "-file", testFile.getPath)
      nestUI.verbose("ant "+args.mkString(" "))

      pushTranscript(s"ant ${args.mkString(" ")}")
      nextTestActionExpectTrue("ant failed", ant(args, logFile)) && diffIsOk
    }
    catch { // *catch-all*
      case e: Exception =>
        nestUI.warning("caught "+e)
        false
    }

    (succeeded, LogContext(logFile, swr, wr))
  }

  def extraClasspath = kind match {
    case "specialized"  => List(PathSettings.srcSpecLib.fold(sys.error, identity))
    case _              => Nil
  }
  def extraJavaOptions = kind match {
    case "instrumented" => ("-javaagent:"+agentLib).split(' ')
    case _              => Array.empty[String]
  }

  def runResidentTest() = {
    // simulate resident compiler loop
    val prompt = "\nnsc> "
    val (swr, wr) = newTestWriters()

    nestUI.verbose(s"$this running test $fileBase")
    val dir = parentFile
    val resFile = new File(dir, fileBase + ".res")

    // run compiler in resident mode
    // $SCALAC -d "$os_dstbase".obj -Xresident -sourcepath . "$@"
    val sourcedir  = logFile.getParentFile.getAbsoluteFile
    val sourcepath = sourcedir.getAbsolutePath+File.separator
    nestUI.verbose("sourcepath: "+sourcepath)

    val argList = List(
      "-d", outDir.getAbsoluteFile.getPath,
      "-Xresident",
      "-sourcepath", sourcepath)

    // configure input/output files
    val logOut    = new FileOutputStream(logFile)
    val logWriter = new PrintStream(logOut, true)
    val resReader = new BufferedReader(new FileReader(resFile))
    val logConsoleWriter = new PrintWriter(new OutputStreamWriter(logOut), true)

    // create compiler
    val settings = new Settings(workerError)
    settings.sourcepath.value = sourcepath
    settings.classpath.value = joinPaths(fileManager.testClassPath)
    val reporter = new ConsoleReporter(settings, scala.Console.in, logConsoleWriter)
    val command = new CompilerCommand(argList, settings)
    object compiler extends Global(command.settings, reporter)

    def resCompile(line: String): Boolean = {
      // NestUI.verbose("compiling "+line)
      val cmdArgs = (line split ' ').toList map (fs => new File(dir, fs).getAbsolutePath)
      // NestUI.verbose("cmdArgs: "+cmdArgs)
      val sett = new Settings(workerError)
      sett.sourcepath.value = sourcepath
      val command = new CompilerCommand(cmdArgs, sett)
      // "scalac " + command.files.mkString(" ")
      pushTranscript("scalac " + command.files.mkString(" "))
      nextTestActionExpectTrue(
        "compilation failed",
        command.ok && {
          (new compiler.Run) compile command.files
          !reporter.hasErrors
        }
      )
    }
    def loop(): Boolean = {
      logWriter.print(prompt)
      resReader.readLine() match {
        case null | ""  => logWriter.close() ; true
        case line       => resCompile(line) && loop()
      }
    }
    // res/t687.res depends on ignoring its compilation failure
    // and just looking at the diff, so I made them all do that
    // because this is long enough.
    if (!Output.withRedirected(logWriter)(try loop() finally resReader.close()))
      setLastState(genPass())

    (diffIsOk, LogContext(logFile, swr, wr))
  }

  def run(): TestState = {
    // javac runner, for one, would merely append to an existing log file, so just delete it before we start
    logFile.delete()

    if (kind == "neg" || (kind endsWith "-neg")) runNegTest()
    else kind match {
      case "pos"          => runTestCommon(true)
      case "ant"          => runAntTest()
      case "res"          => runResidentTest()
      case "scalap"       => runScalapTest()
      case "script"       => runScriptTest()
      case _              => runTestCommon(execTest(outDir, logFile) && diffIsOk)
    }

    lastState
  }

  private def decompileClass(clazz: Class[_], isPackageObject: Boolean): String = {
    import scala.tools.scalap

    // TODO: remove use of reflection once Scala 2.11.0-RC1 is out
    // have to use reflection to work on both 2.11.0-M8 and 2.11.0-RC1.
    // Once we require only 2.11.0-RC1, replace the following block by:
    // import scalap.scalax.rules.scalasig.ByteCode
    // ByteCode forClass clazz bytes
    val bytes = {
      import scala.language.{reflectiveCalls, existentials}
      type ByteCode       = { def bytes: Array[Byte] }
      type ByteCodeModule = { def forClass(clazz: Class[_]): ByteCode }
      val ByteCode        = {
        val ByteCodeModuleCls =
          // RC1 package structure -- see: scala/scala#3588 and https://issues.scala-lang.org/browse/SI-8345
          (util.Try { Class.forName("scala.tools.scalap.scalax.rules.scalasig.ByteCode$") }
          // M8 package structure
           getOrElse  Class.forName("scala.tools.scalap.scalasig.ByteCode$"))
        ByteCodeModuleCls.getDeclaredFields()(0).get(null).asInstanceOf[ByteCodeModule]
      }
      ByteCode forClass clazz bytes
    }

    scalap.Main.decompileScala(bytes, isPackageObject)
  }

  def runScalapTest() = runTestCommon {
    val isPackageObject = testFile.getName startsWith "package"
    val className       = testFile.getName.stripSuffix(".scala").capitalize + (if (!isPackageObject) "" else ".package")
    val loader          = ScalaClassLoader.fromURLs(List(outDir.toURI.toURL), this.getClass.getClassLoader)
    logFile writeAll decompileClass(loader loadClass className, isPackageObject)
    diffIsOk
  }

  def runScriptTest() = {
    import scala.sys.process._
    val (swr, wr) = newTestWriters()

    val args = file2String(testFile changeExtension "args")
    val cmdFile = if (isWin) testFile changeExtension "bat" else testFile
    val succeeded = (((cmdFile + " " + args) #> logFile !) == 0) && diffIsOk

    (succeeded, LogContext(logFile, swr, wr))
  }

  def cleanup() {
    if (lastState.isOk)
      logFile.delete()
    if (!nestUI.debug)
      Directory(outDir).deleteRecursively()
  }

}

/** Loads `library.properties` from the jar. */
object Properties extends scala.util.PropertiesTrait {
  protected def propCategory    = "partest"
  protected def pickJarBasedOn  = classOf[SuiteRunner]
}

/** Extended by Ant- and ConsoleRunner for running a set of tests. */
class SuiteRunner(
  val testSourcePath: String, // relative path, like "files", or "pending"
  val fileManager: FileManager,
  val updateCheck: Boolean,
  val failed: Boolean,
  val nestUI: NestUI,
  val javaCmdPath: String = PartestDefaults.javaCmd,
  val javacCmdPath: String = PartestDefaults.javacCmd,
  val scalacExtraArgs: Seq[String] = Seq.empty,
  val javaOpts: String = PartestDefaults.javaOpts,
  val scalacOpts: String = PartestDefaults.scalacOpts) {

  import PartestDefaults.{ numThreads, waitTime }

  // a running test needs exclusive access to stdout/stderr and exclusive
  // access to customized system properties
  val execInProcessLock = new AnyRef

  setUncaughtHandler

  // TODO: make this immutable
  PathSettings.testSourcePath = testSourcePath

  def banner = {
    val baseDir = fileManager.compilerUnderTest.parent.toString
    def relativize(path: String) = path.replace(baseDir, s"$$baseDir").replace(PathSettings.srcDir.toString, "$sourceDir")
    val vmBin  = javaHome + fileSeparator + "bin"
    val vmName = "%s (build %s, %s)".format(javaVmName, javaVmVersion, javaVmInfo)

  s"""|Partest version:     ${Properties.versionNumberString}
      |Compiler under test: ${relativize(fileManager.compilerUnderTest.getAbsolutePath)}
      |Scala version is:    $versionMsg
      |Scalac options are:  ${(scalacExtraArgs ++ scalacOpts.split(' ')).mkString(" ")}
      |Compilation Path:    ${relativize(joinPaths(fileManager.testClassPath))}
      |Java binaries in:    $vmBin
      |Java runtime is:     $vmName
      |Java options are:    $javaOpts
      |baseDir:             $baseDir
      |sourceDir:           ${PathSettings.srcDir}
    """.stripMargin
    // |Available processors:       ${Runtime.getRuntime().availableProcessors()}
    // |Java Classpath:             ${sys.props("java.class.path")}
  }

  def onFinishTest(testFile: File, result: TestState): TestState = result

  def runTest(testFile: File): TestState = {
    val runner = new Runner(testFile, this, nestUI)

    // when option "--failed" is provided execute test only if log
    // is present (which means it failed before)
    val state =
      if (failed && !runner.logFile.canRead)
        runner.genPass()
      else {
        val (state, _) =
          try timed(runner.run())
          catch {
            case t: Throwable => throw new RuntimeException(s"Error running $testFile", t)
          }
        nestUI.reportTest(state, runner)
        runner.cleanup()
        state
      }
    onFinishTest(testFile, state)
  }

  def runTestsForFiles(kindFiles: Array[File], kind: String): Array[TestState] = {
    nestUI.resetTestNumber(kindFiles.size)

    val pool              = Executors newFixedThreadPool numThreads
    val futures           = kindFiles map (f => pool submit callable(runTest(f.getAbsoluteFile)))

    pool.shutdown()
    Try (pool.awaitTermination(waitTime) {
      throw TimeoutException(waitTime)
    }) match {
      case Success(_) => futures map (_.get)
      case Failure(e) =>
        e match {
          case TimeoutException(d)      =>
            nestUI.warning("Thread pool timeout elapsed before all tests were complete!")
          case ie: InterruptedException =>
            nestUI.warning("Thread pool was interrupted")
            ie.printStackTrace()
        }
        pool.shutdownNow()     // little point in continuing
        // try to get as many completions as possible, in case someone cares
        val results = for (f <- futures) yield {
          try {
            Some(f.get(0, NANOSECONDS))
          } catch {
            case _: Throwable => None
          }
        }
        results.flatten
    }
  }

  class TestTranscript {
    private val buf = ListBuffer[String]()

    def add(action: String): this.type = { buf += action ; this }
    def append(text: String) { val s = buf.last ; buf.trimEnd(1) ; buf += (s + text) }

    // Colorize prompts according to pass/fail
    def fail: List[String] = {
      import nestUI.color._
      def pass(s: String) = bold(green("% ")) + s
      def fail(s: String) = bold(red("% ")) + s
      buf.toList match {
        case Nil  => Nil
        case xs   => (xs.init map pass) :+ fail(xs.last)
      }
    }
  }
}

case class TimeoutException(duration: Duration) extends RuntimeException

class LogContext(val file: File, val writers: Option[(StringWriter, PrintWriter)])

object LogContext {
  def apply(file: File, swr: StringWriter, wr: PrintWriter): LogContext = {
    require (file != null)
    new LogContext(file, Some((swr, wr)))
  }
  def apply(file: File): LogContext = new LogContext(file, None)
}

object Output {
  object outRedirect extends Redirecter(out)
  object errRedirect extends Redirecter(err)

  System.setOut(outRedirect)
  System.setErr(errRedirect)

  import scala.util.DynamicVariable
  private def out = java.lang.System.out
  private def err = java.lang.System.err
  private val redirVar = new DynamicVariable[Option[PrintStream]](None)

  class Redirecter(stream: PrintStream) extends PrintStream(new OutputStream {
    def write(b: Int) = withStream(_ write b)

    private def withStream(f: PrintStream => Unit) = f(redirVar.value getOrElse stream)

    override def write(b: Array[Byte]) = withStream(_ write b)
    override def write(b: Array[Byte], off: Int, len: Int) = withStream(_.write(b, off, len))
    override def flush = withStream(_.flush)
    override def close = withStream(_.close)
  })

  // this supports thread-safe nested output redirects
  def withRedirected[T](newstream: PrintStream)(func: => T): T = {
    // note down old redirect destination
    // this may be None in which case outRedirect and errRedirect print to stdout and stderr
    val saved = redirVar.value
    // set new redirecter
    // this one will redirect both out and err to newstream
    redirVar.value = Some(newstream)

    try func
    finally {
      newstream.flush()
      redirVar.value = saved
    }
  }
}

// Heavily borrowed from sbt
final case class TrapExitSecurityException(status: Int) extends SecurityException with NoStackTrace
final case class TrapExitSecurityManager(delegate: SecurityManager) extends SecurityManager {
  import java.security._

  /** SecurityManager hook to trap calls to `System.exit` to avoid shutting down the whole JVM. */
  override def checkExit(status: Int): Unit = {
    val stack = Thread.currentThread.getStackTrace
    if (stack == null || (stack exists isRealExit)) throw TrapExitSecurityException(status)
  }

  /** This ensures that only actual calls to exit are trapped and not just calls to check if exit is allowed. */
  private def isRealExit(element: StackTraceElement): Boolean =
    element.getClassName == "java.lang.Runtime" && element.getMethodName == "exit"

  // These are overridden to do nothing because there is a substantial filesystem performance penalty
  // when there is a SecurityManager defined.  The default implementations of these construct a
  // FilePermission, and its initialization involves canonicalization, which is expensive.
  override def checkRead(file: String): Unit                  = ()
  override def checkRead(file: String, context: AnyRef): Unit = ()
  override def checkWrite(file: String): Unit                 = ()
  override def checkDelete(file: String): Unit                = ()
  override def checkExec(cmd: String): Unit                   = ()

  override def checkPermission(perm: Permission): Unit = if (delegate ne null) delegate checkPermission perm
  override def checkPermission(perm: Permission, context: AnyRef): Unit =
    if (delegate ne null) delegate.checkPermission(perm, context)
}
