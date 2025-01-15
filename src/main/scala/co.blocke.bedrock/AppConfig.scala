package co.blocke.bedrock

import zio.*
import zio.config.*
import zio.config.magnolia.*
import zio.config.typesafe.*
import zio.http.URL


case class AuthConfig(
    tokenExpirationSec: Int,
    refreshWindowSec: Int,
    sessionDurationSec: Int,
    secretName: String,
    sessionSecretName: String
)

case class AWSConfig(
    snsTopicArn: String,
    regionUrl: URL,
    ipRangesUrl: URL,
    localstackUrl: Option[String]  // this is a string b/c ultimately its parsed into a URI
)

object AppConfig:
  private def readAppConfig: ZIO[Any, Throwable, String] = for {
    stream <- ZIO.attempt(Option(getClass.getClassLoader.getResourceAsStream("application.conf")))
    content <- stream match {
      case Some(inputStream) =>
        val content = new String(inputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
        inputStream.close()
        ZIO.succeed(content)
      case None => ZIO.fail(new RuntimeException("application.conf not found in classpath"))
    }
  } yield content

  def live: ZLayer[Any, Throwable, AWSConfig & AuthConfig] = {
    implicit val urlConfig: DeriveConfig[URL] =
      DeriveConfig[String].map(string => URL.decode(string) match {
          case Right(u) => u
          case Left(e) => throw e
      })
    val theZio = for {
        // Read the configuration string once
        configString <- readAppConfig
        hoconSource = TypesafeConfigProvider.fromHoconString(configString)

        // Load AWSConfig
        awsConfig <- hoconSource
          .load(deriveConfig[AWSConfig].mapKey(toSnakeCase).nested("aws").nested("bedrock"))
          .tapError(err => ZIO.logError(s"Failed to load AWSConfig: $err"))

        // Load AuthConfig
        authConfig <- hoconSource
          .load(deriveConfig[AuthConfig].mapKey(toSnakeCase).nested("auth").nested("bedrock"))
          .tapError(err => ZIO.logError(s"Failed to load AuthConfig: $err"))
      } yield (awsConfig, authConfig)
    val awsLayer = ZLayer.fromZIO( theZio.map(_._1 ) )
    val authLayer = ZLayer.fromZIO( theZio.map(_._2 ) )
    awsLayer ++ authLayer
  }
