import scala.sys.process.*

enablePlugins(BuildInfoPlugin)
enablePlugins(DockerPlugin)
enablePlugins(JavaAppPackaging)

ThisBuild / scalaVersion := "3.5.2"

lazy val root = (project in file("."))
  .settings(
    name := "bedrock",
    version := "0.1.0-SNAPSHOT",

    Compile / mainClass := Some("co.blocke.bedrock.Main"),

    buildInfoKeys := Seq("isProd" -> sys.props.getOrElse("prod","false").toBoolean),
    buildInfoPackage := "co.blocke.bedrock",
    buildInfoObject := "MyBuildInfo",
    // Global / excludeLintKeys += buildInfoKeys, // Suppress annoying lint message about unused keys
    // Global / excludeLintKeys += buildInfoPackage, 


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
      "com.fasterxml.uuid" % "java-uuid-generator" % "5.1.0",  // Generate UUIDv7 (time-sensitive UUIDs)
      "org.scala-lang.modules" %% "scala-xml" % "2.3.0",
      "ch.qos.logback" % "logback-classic" % "1.4.6",
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

    javacOptions ++= Seq("-source", "21", "-target", "21"),

    // Docker packaging settings
    dockerExposedPorts += 8073,
    dockerBaseImage := "openjdk:21",
    dockerBuildOptions += "--no-cache"
  )
  
// Integration test subproject
// > sbt integrationTests/compile
// > sbt integrationTests/test
lazy val integrationTests = (project in file("it"))
  .settings(
    name := "IntegrationTests",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % "2.1.12",
      "dev.zio" %% "zio-test" % "2.1.12",
      "dev.zio" %% "zio-test-sbt" % "2.1.12",
      "software.amazon.awssdk" % "secretsmanager" % "2.20.0"
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),

    // Proper lazy evaluation for test execution
    // NOTE: All this drama below is actually very useful. Its starts "docker-compose up" before
    // running the integration tests, and stops it after the tests.
    Test / test := Def.taskDyn {
      import scala.sys.process._
      import scala.util.control.Breaks._

      def startAndWatchDockerCompose(requiredService: String, successMessage: String, timeout: Int = 60): Unit = {
        val startCommand = "docker-compose up --build -d"
        val logsCommand = "docker-compose logs -f"

        // Start docker-compose in detached mode
        val startResult = Process(startCommand).!(ProcessLogger(println, System.err.println))
        if (startResult != 0) {
          sys.error("Failed to start docker-compose.")
        }

        // Stream logs and wait for the success message
        val endTime = System.currentTimeMillis() + (timeout * 1000)

        breakable {
          Process(logsCommand).lineStream.foreach { line =>
            println(line) // Print log lines to the console for debugging
            if (line.contains(requiredService) && line.contains(successMessage)) {
              break // Exit the loop and start the test
            }
            if (System.currentTimeMillis() > endTime) {
              sys.error(s"Timeout waiting for $requiredService to be ready.")
            }
          }
        }
      }

      startAndWatchDockerCompose("bedrock", "Application is running. Press Ctrl+C to exit.", timeout = 60)
      println("....  OK, ready for testing  ....")

      // Create a new task to run the tests after LocalStack setup
      Def.task {
        try {
          (Test / executeTests).value // Run tests lazily after setup
        } finally {
          // NOTE: change this to `docker-compose stop` if you want to stop LocalStack after the tests and have logs remain
          // 'down' will remove the containers and logs
          val stopLocalStack = Process("docker-compose down").!(ProcessLogger(println, System.err.println))
          if (stopLocalStack != 0) {
            sys.error("Failed to stop LocalStack.")
          }
        }
      }
    }.value
  )
  .dependsOn(root)