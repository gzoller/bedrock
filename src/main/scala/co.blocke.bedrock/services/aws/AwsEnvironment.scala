package co.blocke.bedrock
package services
package aws

import zio.*
import zio.http.*
import software.amazon.awssdk.regions.Region
import com.typesafe.config.Config

trait AwsEnvironment:
  def getRegion: ZIO[ZClient[Any, Scope, Body, Throwable, Response], Throwable, Option[Region]]


// This is the live/real implementation. Could produce a mock implementation to inject for testing
final case class LiveAwsEnvironment(appConfig: Config) extends AwsEnvironment:

  def getRegion: ZIO[ZClient[Any, Scope, Body, Throwable, Response], Nothing, Option[Region]] =
    val metadataUrl = URL.decode( appConfig.getString("app.aws.region_url")).toOption.get
    val request = Request.get(metadataUrl)

    (for {
      _ <- ZIO.logInfo("Checking for AWS")
      responseOpt <- Client.batched(request).timeout(1.second) // Perform the HTTP request with timeout
      _ <- ZIO.logInfo(s"AWS said: $responseOpt")
      result <- responseOpt match {
        case Some(response) if response.status.isSuccess =>
          response.body.asString.map(r => Some(Region.of(r))) // Extract the body as String
        case _ =>
          ZIO.succeed(None) // Return None for non-successful responses or timeouts
      }
    } yield (result)).catchAll { error =>
    ZIO.logError(s"Failed to fetch AWS region (normal if running locally): ${error.getMessage}") *> ZIO.succeed(None)
  }


object AwsEnvironment:
  // Creates a live instance for dependency injection in main program
  val live: ZLayer[Config, Nothing, AwsEnvironment] =
    ZLayer.fromZIO {
      for {
        appConfig <- ZIO.service[Config]
      } yield LiveAwsEnvironment(appConfig)
    }
