package co.blocke.bedrock
package services
package aws

import scala.compiletime.uninitialized

import org.apache.commons.net.util.SubnetUtils
import software.amazon.awssdk.auth.credentials.*
import software.amazon.awssdk.regions.Region
import zio.*
import zio.http.*
import zio.json.*


trait AwsEnvironment:
  def isRunningLocally: Boolean
  def getRegion: Region
  def getCreds: (Option[java.net.URI], AwsCredentialsProvider)
  def getAwsIPs: ZIO[Client & Scope, Throwable, Unit]
  def isIpAllowed(ip: String): ZIO[Any, Throwable, Boolean]


// This is the live/real implementation. Could produce a mock implementation to inject for testing
final case class LiveAwsEnvironment(awsConfig: AWSConfig, region: Option[Region]) extends AwsEnvironment:

  private var validAwsIpRanges: ValidAwsIpRanges = uninitialized  // a set-once variable

  def isRunningLocally: Boolean = region.isEmpty
  def getRegion: Region = region.getOrElse(Region.US_EAST_1)

  // This bit o'magic is needed to differentiate runinning locally on Localstack vs
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

  def getAwsIPs: ZIO[Scope & Client, Throwable, Unit] =
    for {
      client   <- ZIO.service[Client]
      response <- client.request(Request.get(awsConfig.ipRangesUrl))
      json     <- response.body.asString
      awsIPs <- ZIO
        .fromEither(json.fromJson[AwsIPs])
        .mapError(ex => new RuntimeException(s"Failed to parse JSON: $json with error $ex"))
    } yield {
      validAwsIpRanges = calcRanges(awsIPs)
      ()
    }

  private def calcRanges(awsIPs: AwsIPs): ValidAwsIpRanges = {
    val amazonPrefixes = awsIPs.prefixes.filter(_.service == "AMAZON")

    val ipv4Ranges = amazonPrefixes.flatMap(_.ip_prefix) // Extract IPv4 prefixes
    val ipv6Ranges = amazonPrefixes.flatMap(_.ipv6_prefix) // Extract IPv6 prefixes

    ValidAwsIpRanges(
      ipv4 = ipv4Ranges,
      ipv6 = ipv6Ranges
    )
  }

  // Does the given ip belong to any of the given ranges (either IP4 or IP6 as appropriate)
  def isIpAllowed(ip: String): ZIO[Any, Throwable, Boolean] = {
    ZIO.attempt{
      val isIpv4 = ip.contains(".") // Simple check for IPv4
      val validRanges = if (isIpv4) validAwsIpRanges.ipv4 else validAwsIpRanges.ipv6

      validRanges.exists { range =>
        val subnet = new SubnetUtils(range)
        subnet.getInfo.isInRange(ip)
      }
    }
  }


object AwsEnvironment:

  def getRegion(awsConfig: AWSConfig): ZIO[Client, Nothing, Option[Region]] =
    (for {
      _ <- ZIO.logInfo("Checking for AWS")
      responseOpt <- Client.batched(Request.get(awsConfig.regionUrl)).timeout(2.seconds) // Perform the HTTP request with timeout
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
