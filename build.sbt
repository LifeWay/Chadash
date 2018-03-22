name := "chadash"
version := scala.util.Properties.envOrElse("BUILD_VERSION", "DEV")
scalaVersion := "2.11.12"
scalacOptions ++= Seq("-feature", "-target:jvm-1.8")

lazy val root = (project in file(".")).enablePlugins(PlayScala).settings(
  javaOptions in Test += "-Dconfig.file=conf/application.test.conf"
  //FOR DEBUGGING TESTS:
  //,javaOptions in Test += "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=9999"
).settings(libraryDependencies ~= (_.map(excludeSpecs2)))
val AwsVersion   = "1.11.298"

libraryDependencies ++= Seq(
  ws,
  filters,
  "com.amazonaws" % "aws-java-sdk-cloudformation" % AwsVersion,
  "com.amazonaws" % "aws-java-sdk-s3" % AwsVersion,
  "com.amazonaws" % "aws-java-sdk-autoscaling" % AwsVersion,
  "com.amazonaws" % "aws-java-sdk-elasticloadbalancing" % AwsVersion,
  "com.google.code.findbugs" % "jsr305" % "3.0.0",
  "commons-io" % "commons-io" % "2.4",
  "com.typesafe.akka" %% "akka-actor" % "2.3.9",
  "com.typesafe.akka" %% "akka-slf4j" % "2.3.9",
  "com.typesafe.akka" %% "akka-testkit" % "2.3.9" % "test",
  "org.scalatestplus" %% "play" % "1.2.0" % "test",
  "org.mockito" % "mockito-core" % "2.16.0" % "test",
  "com.google.inject" % "guice" % "3.0"
)

def excludeSpecs2(module: ModuleID): ModuleID =
  module.excludeAll(ExclusionRule(organization =
    "org.specs2")).exclude("com.novocode", "junit-interface")

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

ScoverageSbtPlugin.ScoverageKeys.coverageMinimum := 60
ScoverageSbtPlugin.ScoverageKeys.coverageFailOnMinimum := false
ScoverageSbtPlugin.ScoverageKeys.coverageHighlighting := true

ScoverageSbtPlugin.ScoverageKeys.coverageExcludedPackages := "<empty>;appversion.BuildInfo;"
