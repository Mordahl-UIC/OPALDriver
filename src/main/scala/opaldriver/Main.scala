package opaldriver

import java.io.{BufferedWriter, File, FileWriter}

import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}
import org.rogach.scallop.*

import org.opalj.log.GlobalLogContext
import org.opalj.br.DeclaredMethod
import org.opalj.br.analyses.{DeclaredMethodsKey, Project, ProjectInformationKey}
import org.opalj.br.fpcf.analyses.ContextProvider
import org.opalj.br.fpcf.properties.{CallStringContextsKey, Contexts}
import org.opalj.fpcf.PropertyStoreKey
import org.opalj.tac.cg.{CHACallGraphKey, CallGraph, CallGraphKey, PointsToCallGraphKey, RTACallGraphKey, TypeIteratorKey}
import org.opalj.tac.fpcf.analyses.cg.{CFA_k_l_TypeIterator, TypeIterator}

/** A k-l-CFA call graph key with configurable call-string length `k` and heap
  * (allocation-site) context depth `l`. OPAL only ships pre-built keys for
  * k = 1 (CFA_1_0/CFA_1_1); this lets us request arbitrary k. It mirrors the
  * shipped CFA keys: allocation-site based points-to with call-string contexts.
  */
final class CFAKLCallGraphKey(val k: Int, val l: Int) extends PointsToCallGraphKey {
  override val pointsToType: String = "AllocationSiteBased"

  override val contextKey: ProjectInformationKey[? <: Contexts[?], Nothing] =
    CallStringContextsKey

  override def getTypeIterator(project: Project[?]): TypeIterator =
    new CFA_k_l_TypeIterator(project, k, l)
}

private final class Conf(arguments: Seq[String]) extends ScallopConf(arguments) {
  banner(
    """OPALDriver -- compute a call graph for a JAR using OPAL.
      |
      |Usage: opaldriver --input <jar> --output <cg.json> --algorithm <cha|rta|cfa> [options]
      |
      |Options:""".stripMargin
  )

  val input: ScallopOption[String] =
    opt[String](required = true, descr = "JAR file (or directory of .class files) to analyze")
  val output: ScallopOption[String] =
    opt[String](required = true, descr = "Path to write the call graph (JSON) to")
  val algorithm: ScallopOption[String] =
    opt[String](
      required = true,
      descr = "Call graph algorithm: cha, rta, or cfa (k-CFA)"
    )
  val k: ScallopOption[Int] =
    opt[Int](default = Some(1), descr = "cfa only: call-string length k (default 1)")
  val l: ScallopOption[Int] =
    opt[Int](descr = "cfa only: heap/allocation context depth l (default = k)")
  val entrypoints: ScallopOption[String] =
    opt[String](
      default = Some("application"),
      descr =
        "Entry points: application, application-without-jre, library, all, configured (default application)"
    )
  val noJdk: ScallopOption[Boolean] =
    opt[Boolean](
      default = Some(false),
      descr = "Do not load the JRE as a library (faster, but less sound)"
    )

  verify()
}

@main
def main(args: String*): Unit = {
  val conf = new Conf(args)

  val inputFile = new File(conf.input())
  if !inputFile.exists then {
    Console.err.println(s"error: input does not exist: ${inputFile.getPath}")
    sys.exit(2)
  }

  val cgKey: CallGraphKey =
    conf.algorithm().trim.toLowerCase match {
      case "cha"                  => CHACallGraphKey
      case "rta"                  => RTACallGraphKey
      case "cfa" | "kcfa" | "k-cfa" =>
        val k = conf.k()
        val l = conf.l.getOrElse(k)
        if k < 0 || l < 0 then {
          Console.err.println("error: k and l must be >= 0")
          sys.exit(2)
        }
        new CFAKLCallGraphKey(k, l)
      case other =>
        Console.err.println(
          s"error: unknown algorithm '$other' (supported: cha, rta, cfa). " +
            "VTA and k-object-sensitivity are not available in OPAL 7.0.0."
        )
        sys.exit(2)
    }

  val finderClass = entryPointsFinderClass(conf.entrypoints()) match {
    case Some(fqn) => fqn
    case None =>
      Console.err.println(s"error: unknown entrypoints mode '${conf.entrypoints()}'")
      sys.exit(2)
  }

  val baseConfig: Config = ConfigFactory.load(classOf[CFAKLCallGraphKey].getClassLoader)
  var config: Config = baseConfig.withValue(
    "org.opalj.br.analyses.cg.InitialEntryPointsKey.analysis",
    ConfigValueFactory.fromAnyRef(finderClass)
  )
  // AllEntryPointsFinder requires this extra key; restrict to the project's own
  // methods so we don't treat every JRE method as an entry point.
  if conf.entrypoints().trim.toLowerCase == "all" then {
    config = config.withValue(
      "org.opalj.br.analyses.cg.InitialEntryPointsKey.AllEntryPointsFinder.projectMethodsOnly",
      ConfigValueFactory.fromAnyRef(true)
    )
  }

  val libraryFiles: Array[File] =
    if conf.noJdk() then Array.empty[File] else Array(org.opalj.bytecode.JRELibraryFolder)

  Console.err.println(
    s"Loading project: ${inputFile.getPath}" +
      (if libraryFiles.isEmpty then "" else s" (+ JRE from ${libraryFiles.head.getPath})")
  )
  val project: Project[java.net.URL] =
    Project(Array(inputFile), libraryFiles, GlobalLogContext, config)

  Console.err.println(s"Computing call graph (${conf.algorithm()}) ...")
  val callGraph: CallGraph = project.get(cgKey)

  val ps = project.get(PropertyStoreKey)
  val contextProvider: ContextProvider = project.get(TypeIteratorKey)
  // DeclaredMethods is required by the call graph; force it so the project caches it.
  project.get(DeclaredMethodsKey): Unit

  val outFile = new File(conf.output())
  Option(outFile.getParentFile).foreach(_.mkdirs())

  val bw = new BufferedWriter(new FileWriter(outFile))
  var reachableCount = 0
  var edgeCount = 0
  try {
    bw.write("{\n")
    bw.write(s"""  "algorithm": ${jsonStr(conf.algorithm())},""" + "\n")
    cgKey match {
      case cfa: CFAKLCallGraphKey =>
        bw.write(s"""  "k": ${cfa.k},""" + "\n")
        bw.write(s"""  "l": ${cfa.l},""" + "\n")
      case _ =>
    }
    bw.write(s"""  "entrypoints": ${jsonStr(conf.entrypoints())},""" + "\n")
    bw.write("""  "reachableMethods": [""" + "\n")

    var firstMethod = true
    val reachable = callGraph.reachableMethods()
    while reachable.hasNext do {
      val callerContext = reachable.next()
      reachableCount += 1
      val method = callerContext.method

      if !firstMethod then bw.write(",\n")
      firstMethod = false

      bw.write("    {\n")
      bw.write(s"""      "context": ${jsonStr(callerContext.toString)},""" + "\n")
      bw.write(s"""      "method": ${jsonStr(methodSig(method))},""" + "\n")
      bw.write("""      "callSites": [""" + "\n")

      val callees = callGraph.calleesPropertyOf(method)
      val callSites = callees.callSites(callerContext)(using ps, contextProvider)
      var firstSite = true
      for (pcObj, calleeContexts) <- callSites do {
        val pc = pcObj.asInstanceOf[Int]
        if !firstSite then bw.write(",\n")
        firstSite = false
        bw.write("        {\n")
        bw.write(s"""          "pc": $pc,""" + "\n")
        bw.write("""          "callees": [""" + "\n")
        var firstCallee = true
        while calleeContexts.hasNext do {
          val calleeContext = calleeContexts.next()
          edgeCount += 1
          if !firstCallee then bw.write(",\n")
          firstCallee = false
          bw.write("            {\n")
          bw.write(s"""              "context": ${jsonStr(calleeContext.toString)},""" + "\n")
          bw.write(s"""              "method": ${jsonStr(methodSig(calleeContext.method))}""" + "\n")
          bw.write("            }")
        }
        bw.write("\n          ]\n")
        bw.write("        }")
      }
      bw.write("\n      ]\n")
      bw.write("    }")
    }

    bw.write("\n  ]\n")
    bw.write("}\n")
  } finally {
    bw.close()
  }

  Console.err.println(
    s"Done: $reachableCount reachable method contexts, $edgeCount call edges -> ${outFile.getPath}"
  )
}

/** Renders a [[DeclaredMethod]] as `declaringClass.name(descriptor)`. */
private def methodSig(m: DeclaredMethod): String =
  s"${m.declaringClassType.toJava}.${m.name}${m.descriptor.toJVMDescriptor}"

/** Maps a user-facing entry-points mode to OPAL's EntryPointFinder class. */
private def entryPointsFinderClass(mode: String): Option[String] =
  mode.trim.toLowerCase match {
    case "application" =>
      Some("org.opalj.br.analyses.cg.ApplicationEntryPointsFinder")
    case "application-without-jre" | "app-without-jre" =>
      Some("org.opalj.br.analyses.cg.ApplicationWithoutJREEntryPointsFinder")
    case "library" =>
      Some("org.opalj.br.analyses.cg.LibraryEntryPointsFinder")
    case "all" =>
      Some("org.opalj.br.analyses.cg.AllEntryPointsFinder")
    case "configured" | "configuration" =>
      Some("org.opalj.br.analyses.cg.ConfigurationEntryPointsFinder")
    case _ => None
  }

/** Minimal JSON string escaping. */
private def jsonStr(s: String): String = {
  val sb = new StringBuilder(s.length + 2)
  sb.append('"')
  s.foreach {
    case '"'  => sb.append("\\\"")
    case '\\' => sb.append("\\\\")
    case '\n' => sb.append("\\n")
    case '\r' => sb.append("\\r")
    case '\t' => sb.append("\\t")
    case c if c < 0x20 => sb.append("\\u%04x".format(c.toInt))
    case c    => sb.append(c)
  }
  sb.append('"')
  sb.toString
}
