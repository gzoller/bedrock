import sbtassembly.AssemblyPlugin.autoImport._
enablePlugins(BuildInfoPlugin)

val scala3Version = "3.5.2"

lazy val root = project
  .in(file("."))
  .settings(
    name := "bedrock",
    version := "0.1.0-SNAPSHOT",

    scalaVersion := scala3Version,

    Compile / mainClass := Some("com.me.Main"),

    buildInfoKeys := Seq("isProd" -> sys.props.getOrElse("prod","false").toBoolean),
    buildInfoPackage := "com.me",
    buildInfoObject := "MyBuildInfo",
    // Global / excludeLintKeys += buildInfoKeys, // Suppress annoying lint message about unused keys
    // Global / excludeLintKeys += buildInfoPackage, 

    // TODO: Use a better assembler-packager like sbt-native-packager
    assembly / assemblyMergeStrategy := {
      {
        case PathList("META-INF", xs @ _*) => MergeStrategy.discard
        case PathList("reference.conf")    => MergeStrategy.concat
        case x =>
          val oldStrategy = (ThisBuild / assemblyMergeStrategy).value
          oldStrategy(x)
      }
    },
    
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % "2.1.12",
      "dev.zio" %% "zio-http" % "0.0.0+1826-1f8ef1ff+20241212-0015-SNAPSHOT", //"3.0.1",
      // "dev.zio" %% "zio-http-cli" % "3.0.1", // If you want a runnable CLI
      "software.amazon.awssdk" % "secretsmanager" % "2.20.0", // Secrets Manager SDK
      "software.amazon.awssdk" % "core" % "2.20.0",            // AWS SDK Core
      "ch.qos.logback" % "logback-classic" % "1.4.6",
      "com.github.jwt-scala" %% "jwt-core" % "10.0.1",
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % "2.20.3",
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "2.20.3" % "provided",
      "org.scalameta" %% "munit" % "1.0.0" % Test,
      "dev.zio" %% "zio-test" % "2.1.12" % Test,
      "dev.zio" %% "zio-test-sbt" % "2.1.12" % Test,
      "dev.zio" %% "zio-http-testkit" % "3.0.1" % Test,
      "dev.zio" %% "zio-logging" % "2.4.0"
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),

    scalacOptions ++= Seq(
      "-deprecation" // Enable warnings for deprecated APIs
    )
  )
