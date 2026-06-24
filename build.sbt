// Pinned to 3.7.3 to match OPAL 7.0.0's build: that Scala version uses the
// scala-library 2.13.16 that OPAL's runtime analysis resolution (scala-reflect
// 2.13.16) requires. Newer Scala 3 ships an incompatible unified scala-library.
val scala3Version = "3.7.3"

lazy val root = project
  .in(file("."))
  .settings(
    name                   := "OPALDriver",
    version                := "0.1.0-SNAPSHOT",
    scalaVersion           := scala3Version,
    semanticdbEnabled      := true,
    semanticdbVersion      := scalafixSemanticdb.revision,
    Compile / doc / target := file("docs"),
    scalacOptions ++= Seq(
      "-Wunused:all",
      "-Wnonunit-statement",
      "-Wvalue-discard",
      "-deprecation",
      "-rewrite",
      "-no-indent",
      "-source:future",
      "-feature"
    ),

    // Test Dependencies
    libraryDependencies += "org.scalameta" %% "munit" % "1.2.1" % Test,
    libraryDependencies += "org.scalameta" %% "munit-scalacheck" % "1.2.0" % Test,
    libraryDependencies += "org.scalamock" %% "scalamock" % "7.5.0" % Test,
    libraryDependencies += "de.opal-project" % "bytecode-representation_3" % "7.0.0",
    libraryDependencies += "de.opal-project" % "three-address-code_3" % "7.0.0",
    libraryDependencies += "org.rogach" %% "scallop" % "5.2.0"
  )
