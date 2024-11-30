import sbtassembly.AssemblyPlugin.autoImport._

val scala3Version = "3.5.2"

lazy val root = project
  .in(file("."))
  .settings(
    name := "bedrock",
    version := "0.1.0-SNAPSHOT",

    scalaVersion := scala3Version,

    Compile / mainClass := Some("com.me.Main"),

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
      "org.scalameta" %% "munit" % "1.0.0" % Test,
      "dev.zio" %% "zio" % "2.1.12",
      "dev.zio" %% "zio-http" % "3.0.1",
      "dev.zio" %% "zio-http-cli" % "3.0.1" // If you want a runnable CLI
    )
  )
