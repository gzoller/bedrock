package co.blocke.bedrock
package services
package aws

import zio.*
import zio.http.*
import zio.json.*
import software.amazon.awssdk.regions.Region


trait AwsEnvironment:
  def getRegion: ZIO[Client, Nothing, Option[Region]]
  def getAwsIPs: ZIO[Client & Scope, Throwable, AwsIPs]


// This is the live/real implementation. Could produce a mock implementation to inject for testing
final case class LiveAwsEnvironment(awsConfig: AWSConfig) extends AwsEnvironment:

  def getAwsIPs: ZIO[Scope & Client, Throwable, AwsIPs] =
    for {
      client   <- ZIO.service[Client]
      response <- client.request(Request.get(awsConfig.ipRangesUrl))
      json     <- response.body.asString
      awsIPs <- ZIO
        .fromEither(json.fromJson[AwsIPs])
        .mapError(ex => new RuntimeException(s"Failed to parse JSON: $json with error $ex"))
    } yield awsIPs

  def getRegion: ZIO[Client, Nothing, Option[Region]] =
    (for {
      _ <- ZIO.logInfo("Checking for AWS")
      responseOpt <- Client.batched(Request.get(awsConfig.regionUrl)).timeout(1.second) // Perform the HTTP request with timeout
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
  val live: ZLayer[AWSConfig, Nothing, AwsEnvironment] =
    ZLayer.fromZIO {
      for {
        awsConfig <- ZIO.service[AWSConfig]
      } yield LiveAwsEnvironment(awsConfig)
    }
