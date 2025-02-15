package co.blocke.bedrock
package services
package aws

import software.amazon.awssdk.auth.credentials.*
import software.amazon.awssdk.regions.Region
import zio.*
import zio.http.*


trait AwsEnvironment:
  def isRunningLocally: Boolean
  def getRegion: Region
  def getCreds: (Option[java.net.URI], AwsCredentialsProvider)

// This is the live/real implementation. Could produce a mock implementation to inject for testing
final case class LiveAwsEnvironment(awsConfig: AWSConfig, region: Option[Region]) extends AwsEnvironment:

  def isRunningLocally: Boolean = !awsConfig.liveAws
  def getRegion: Region = region.getOrElse(Region.US_EAST_1)

  // This bit o'magic is needed to differentiate running locally on Localstack vs
  // on real AWS. These values are used to build various AWS clients.
  def getCreds: (Option[java.net.URI], AwsCredentialsProvider) = 
    if isRunningLocally then 
      // LocalStack configuration if no actual AWS is found
      (
        awsConfig.localstackUrl.map(java.net.URI(_)), // LocalStack endpoint
        StaticCredentialsProvider.create(AwsBasicCredentials.create("foo", "bar")) // Mock credentials
      )
    else
      // Non-local configuration, ie running on real AWS (uses default AWS credentials)
      (None, DefaultCredentialsProvider.create())


object AwsEnvironment:

  def getRegion(awsConfig: AWSConfig): ZIO[Client, Nothing, Option[Region]] =
    (for {
      _ <- ZIO.logInfo("Checking for AWS")
      responseFiber <- Client.batched(Request.get(awsConfig.regionUrl))
                        .timeout(2.seconds)
                        .forkDaemon  // Ensures it outlives parent scope
      responseOpt <- responseFiber.join  // Waits for completion
      // TODO: Remove the forkDaemon when debugging the exit 0 problem is complete
      // responseOpt <- Client.batched(Request.get(awsConfig.regionUrl)).timeout(2.seconds) // Perform the HTTP request with timeout
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

  // Creates a live instance for dependency injection in main program
  val live: ZLayer[AWSConfig & Client, Nothing, AwsEnvironment] =
    ZLayer.fromZIO {
      for {
        awsConfig <- ZIO.service[AWSConfig]
        region <- getRegion(awsConfig)
      } yield LiveAwsEnvironment(awsConfig, region)
    }
