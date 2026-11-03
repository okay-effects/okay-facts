/** okay-facts: a fact resolver for LLMs — the model refers by handles, okay-facts resolves (specs/design.md) */
import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

ThisBuild / organization := "io.github.okay-effects"
ThisBuild / homepage := Some(url("https://github.com/okay-effects/okay-facts"))
ThisBuild / version := "0.1.0"
ThisBuild / scalaVersion := "3.3.8"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:imports,privates,locals")
ThisBuild / licenses := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.txt"))
ThisBuild / developers := List(Developer("sergey-scherbina", "Sergiy Shcherbyna", "sergey.scherbina@gmail.com",
  url("https://github.com/sergey-scherbina")))
ThisBuild / scmInfo := Some(ScmInfo(url("https://github.com/okay-effects/okay-facts"),
  "scm:git@github.com:okay-effects/okay-facts.git"))
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / resolvers ++= Seq("okay-scala" at "https://okay-effects.github.io/okay-scala/maven",
  "okay-simple" at "https://okay-effects.github.io/okay-simple/maven",
  "okay-llm" at "https://okay-effects.github.io/okay-llm/maven",
  "okay-http" at "https://okay-effects.github.io/okay-http/maven",
  "okay-serverless" at "https://okay-effects.github.io/okay-serverless/maven")
/** RELEASES, ON GITHUB PAGES: `sbt publish` writes every module into `target/pages-maven/`, which goes to the
 * `gh-pages` branch under `maven/` (RELEASING.md) */
val pagesRepository = settingKey[File]("the Maven repository a release is written to, for GitHub Pages")
ThisBuild / pagesRepository := (ThisBuild / baseDirectory).value / "target" / "pages-maven"
ThisBuild / publishTo := Some(MavenCache("pages", (ThisBuild / pagesRepository).value))
ThisBuild / Compile / packageDoc / publishArtifact := false

val munit = Def.setting("org.scalameta" %%% "munit" % "1.2.3" % Test)

/** THE RESOLVER AND THE BINDING: the metamodel, handles, expressions, `{params, template}`, ICU rendering, the
 * validator, the conversation loop over a model's port, the TCK */
lazy val core = crossProject(JVMPlatform, JSPlatform).crossType(CrossType.Pure).in(file("core"))
  .settings(name := "okay-facts",
    libraryDependencies ++= Seq("io.github.okay-effects" %%% "okay-scala" % "0.1.1",
      "io.github.okay-effects" %%% "okay-effects-json-value" % "0.1.1",
      // the model's port: okay-llm's `Llm` (its fake replays fixed outputs; `WorkersAi` answers it in a Worker)
      "io.github.okay-effects" %%% "okay-llm" % "0.1.0", munit.value))

/** IN THE CLOUD: the Workers AI binding as okay-llm's `Llm` port (okay-serverless' `Ai`) */
lazy val cloud = crossProject(JVMPlatform, JSPlatform).crossType(CrossType.Pure).in(file("cloud")).dependsOn(core)
  .settings(name := "okay-facts-cloud",
    libraryDependencies ++= Seq("io.github.okay-effects" %%% "okay-serverless" % "0.1.0", munit.value))

lazy val root = (project in file(".")).aggregate(core.jvm, core.js, cloud.jvm, cloud.js).settings(publish / skip := true)
