val scala3Version = "3.8.3"

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
    libraryDependencies += "org.scalameta" %% "munit"            % "1.2.1" % Test,
    libraryDependencies += "org.scalameta" %% "munit-scalacheck" % "1.2.0" % Test,
    libraryDependencies += "org.scalamock" %% "scalamock"        % "7.5.0" % Test
  )
