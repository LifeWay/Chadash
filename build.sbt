import play.PlayImport._
import play.PlayScala
import sbtbuildinfo.Plugin._

name := "chadash"
version := scala.util.Properties.envOrElse("BUILD_VERSION", "DEV")
scalaVersion := "2.11.6"
scalacOptions ++= Seq("-feature", "-target:jvm-1.8")

lazy val root = (project in file(".")).enablePlugins(PlayScala).settings(
  javaOptions in Test += "-Dconfig.file=conf/application.test.conf"
)

libraryDependencies ++= Seq(
  ws,
  filters,
  "com.amazonaws" % "aws-java-sdk" % "1.9.19",
  "com.google.code.findbugs" % "jsr305" % "3.0.0",
  "commons-io" % "commons-io" % "2.4",
  "com.typesafe.akka" %% "akka-actor" % "2.3.9",
  "com.typesafe.akka" %% "akka-slf4j" % "2.3.9",
  "com.typesafe.akka" %% "akka-testkit" % "2.3.9" % "test",
  "org.scalatestplus" %% "play" % "1.1.0" % "test",
  "com.google.inject" % "guice" % "3.0"
)

//----
// Create the buildInfo object at compile time
//----
buildInfoSettings
sourceGenerators in Compile <+= buildInfo
buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion)
buildInfoKeys ++= Seq[BuildInfoKey](
  BuildInfoKey.action("gitCommit") {
    scala.util.Properties.envOrElse("GIT_COMMIT", "")
  },
  BuildInfoKey.action("buildTime") {
    if (version.value != "DEV") System.currentTimeMillis else ""
  }
)
buildInfoPackage := "com.lifeway.chadash.appversion"