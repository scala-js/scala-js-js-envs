import com.typesafe.tools.mima.core.{Problem, ProblemFilters}

val previousVersion: Option[String] = Some("1.5.0")
val newScalaBinaryVersionsInThisRelease: Set[String] = Set.empty

inThisBuild(Def.settings(
  organization := "org.scala-js",
  scalaVersion := "2.12.11",
  crossScalaVersions := Seq("2.11.12", "2.12.11", "2.13.2", "3.3.7"),
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-Xfatal-warnings",
    "-encoding", "utf-8",
  ),

  versionScheme := Some("semver-spec"),

  // Licensing
  homepage := Some(url("https://github.com/scala-js/scala-js-js-envs")),
  startYear := Some(2013),
  licenses += (("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0"))),
  scmInfo := Some(ScmInfo(
      url("https://github.com/scala-js/scala-js-js-envs"),
      "scm:git:git@github.com:scala-js/scala-js-js-envs.git",
      Some("scm:git:git@github.com:scala-js/scala-js-js-envs.git"))),

  // Publishing
  pomExtra := (
    <developers>
      <developer>
        <id>sjrd</id>
        <name>Sébastien Doeraene</name>
        <url>https://github.com/sjrd/</url>
      </developer>
      <developer>
        <id>gzm0</id>
        <name>Tobias Schlatter</name>
        <url>https://github.com/gzm0/</url>
      </developer>
      <developer>
        <id>nicolasstucki</id>
        <name>Nicolas Stucki</name>
        <url>https://github.com/nicolasstucki/</url>
      </developer>
    </developers>
  ),
  pomIncludeRepository := { _ => false },
))

val commonSettings = Def.settings(
  // Links to the JavaDoc do not work
  Compile / doc / scalacOptions -= "-Xfatal-warnings",

  // Scaladoc linking
  apiURL := {
    val name = moduleName.value
    val scalaBinVer = scalaBinaryVersion.value
    val ver = version.value
    Some(url(s"https://javadoc.io/doc/org.scala-js/${name}_$scalaBinVer/$ver/"))
  },
  autoAPIMappings := true,

  // sbt-header configuration
  headerLicense := Some(HeaderLicense.Custom(
    s"""Scala.js JS Envs (${homepage.value.get})
       |
       |Copyright EPFL.
       |
       |Licensed under Apache License 2.0
       |(https://www.apache.org/licenses/LICENSE-2.0).
       |
       |See the NOTICE file distributed with this work for
       |additional information regarding copyright ownership.
       |""".stripMargin
  )),

  // MiMa auto-configuration
  mimaPreviousArtifacts ++= {
    val scalaV = scalaVersion.value
    val scalaBinaryV = scalaBinaryVersion.value
    val thisProjectID = projectID.value
    previousVersion match {
      case None =>
        Set.empty
      case _ if newScalaBinaryVersionsInThisRelease.contains(scalaBinaryV) =>
        // New in this release, no binary compatibility to comply to
        Set.empty
      case Some(prevVersion) =>
        Set(thisProjectID.organization %% thisProjectID.name % prevVersion)
    }
  },
  mimaFailOnNoPrevious := !newScalaBinaryVersionsInThisRelease.contains(scalaBinaryVersion.value),
)

lazy val root = project
  .in(file("."))
  .aggregate(
    `scalajs-js-envs`,
    `scalajs-js-envs-test-kit`,
    `scalajs-env-nodejs`,
  )
  .settings(
    publish / skip := true,
  )

lazy val `scalajs-js-envs` = project
  .in(file("js-envs"))
  .settings(
    commonSettings,
    libraryDependencies ++= Seq(
      "org.scala-js" %% "scalajs-logging" % "1.2.0",
      "com.novocode" % "junit-interface" % "0.11" % "test",
    ),
  )

lazy val `scalajs-js-envs-test-kit` = project
  .in(file("js-envs-test-kit"))
  .settings(
    commonSettings,
    libraryDependencies ++= Seq(
        "com.google.jimfs" % "jimfs" % "1.1",
        "junit" % "junit" % "4.12",
        "com.novocode" % "junit-interface" % "0.11" % "test"
    ),
  )
  .dependsOn(`scalajs-js-envs`)

lazy val `scalajs-env-nodejs` = project
  .in(file("nodejs-env"))
  .settings(
    commonSettings,
    libraryDependencies ++= Seq(
        "com.google.jimfs" % "jimfs" % "1.1",
        "com.novocode" % "junit-interface" % "0.11" % "test"
    ),
  )
  .dependsOn(`scalajs-js-envs`, `scalajs-js-envs-test-kit` % "test")
