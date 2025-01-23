import sbtassembly.AssemblyPlugin.autoImport.*
import scala.sys.process.*

enablePlugins(BuildInfoPlugin)
enablePlugins(DockerPlugin)
enablePlugins(JavaAppPackaging)

val scala3Version = "3.5.2"

lazy val root = (project in file("."))
  .settings(
    name := "bedrock",
    version := "0.1.0-SNAPSHOT",

    scalaVersion := scala3Version,

    Compile / mainClass := Some("co.blocke.bedrock.Main"),

    buildInfoKeys := Seq("isProd" -> sys.props.getOrElse("prod","false").toBoolean),
    buildInfoPackage := "co.blocke.bedrock",
    buildInfoObject := "MyBuildInfo",
    // Global / excludeLintKeys += buildInfoKeys, // Suppress annoying lint message about unused keys
    // Global / excludeLintKeys += buildInfoPackage, 

    // TODO: Use a better assembler-packager like sbt-native-packager
    assembly / assemblyMergeStrategy := {
      {
        case "module-info.class" => MergeStrategy.discard
        case PathList("META-INF", xs @ _*) => MergeStrategy.discard
        case PathList("reference.conf")    => MergeStrategy.concat
        case x =>
          val oldStrategy = (ThisBuild / assemblyMergeStrategy).value
          oldStrategy(x)
      }
    },

    // assembly / assemblyMergeStrategy := {
    //   case PathList("META-INF", "services", xs @ _*) => MergeStrategy.concat
    //   case PathList("META-INF", xs @ _*)             => MergeStrategy.discard
    //   case "logback.xml"                             => MergeStrategy.first
    //   case x                                         => (assembly / assemblyMergeStrategy).value(x)
    // },

    ThisBuild / scalacOptions ++= Seq(
      "-Wunused:imports", // Warn on unused imports
      "-explain-cyclic"
    ),
    
    libraryDependencies ++= Seq(
      // ---- ZIO
      "dev.zio" %% "zio" % "2.1.12",
      "dev.zio" %% "zio-http" % "0.0.0+1826-1f8ef1ff+20241212-0015-SNAPSHOT", //"3.0.1",
      // "dev.zio" %% "zio-http-cli" % "3.0.1", // If you want a runnable CLI
      "dev.zio" %% "zio-logging" % "2.4.0",
      "dev.zio" %% "zio-config" % "4.0.3",
      "dev.zio" %% "zio-config-magnolia" % "4.0.3",  // For automatic derivation of ConfigDescriptor
      "dev.zio" %% "zio-config-typesafe" % "4.0.3",  // For HOCON (Typesafe Config) support

      // ---- AWS
      "software.amazon.awssdk" % "core" % "2.20.0",            // AWS SDK Core
      "software.amazon.awssdk" % "secretsmanager" % "2.20.0",  // Secrets Manager SDK
      "software.amazon.awssdk" % "sns" % "2.20.0",  // Secrets Manager SDK

      // ---- Misc
      "org.scala-lang.modules" %% "scala-xml" % "2.3.0",
      "ch.qos.logback" % "logback-classic" % "1.4.6",
      "com.github.jwt-scala" %% "jwt-core" % "10.0.1",
      "com.github.jwt-scala" %% "jwt-core" % "10.0.1",
      "commons-net" % "commons-net" % "3.9.0",
      "ch.qos.logback" % "logback-classic" % "1.4.6",
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % "2.20.3",
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "2.20.3" % "provided",

      // ---- Testing
      // "org.scalameta" %% "munit" % "1.0.0" % Test,
      "dev.zio" %% "zio-test" % "2.1.12" % Test,
      "dev.zio" %% "zio-test-sbt" % "2.1.12" % Test,
      "dev.zio" %% "zio-http-testkit" % "3.0.1" % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),

    scalacOptions ++= Seq(
      "-deprecation" // Enable warnings for deprecated APIs
    ),

    // Docker packaging settings
    dockerExposedPorts += 8073,
    dockerBaseImage := "openjdk:22",
  )
  
// Integration test subproject
// > sbt integrationTests/compile
// > sbt integrationTests/test
lazy val integrationTests = (project in file("it"))
  .settings(
    name := "IntegrationTests",
    scalaVersion := "3.5.2",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % "2.1.12",
      "dev.zio" %% "zio-test" % "2.1.12",
      "dev.zio" %% "zio-test-sbt" % "2.1.12"
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),

    // Proper lazy evaluation for test execution
    Test / test := Def.taskDyn {
      val startLocalStack = Process("./scripts/aws_local_start.sh").!(ProcessLogger(println, System.err.println))
      if (startLocalStack != 0) {
        sys.error("Failed to start LocalStack.")
      }

      // Create a new task to run the tests after LocalStack setup
      Def.task {
        try {
          (Test / executeTests).value // Run tests lazily after setup
        } finally {
          val stopLocalStack = Process("docker-compose down").!(ProcessLogger(println, System.err.println))
          if (stopLocalStack != 0) {
            sys.error("Failed to stop LocalStack.")
          }
        }
      }
    }.value
  )
  .dependsOn(root)
