ThisBuild / scalaVersion := "3.5.2"
ThisBuild / javacOptions ++= Seq("--release", "21")

enablePlugins(JavaAppPackaging)  // Requires sbt-native-packager in plugins.sbt

lazy val root = (project in file("."))
  .settings(
    name := "scala3-zio-http",
    version := "0.1.0",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % "2.1.12",
      "dev.zio" %% "zio-http" % "3.0.1"
    )
  )
