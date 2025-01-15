package co.blocke.bedrock
package services
package aws

import zio.*
import zio.http.*
import zio.json.*
import software.amazon.awssdk.regions.Region
import org.apache.commons.net.util.SubnetUtils
import scala.compiletime.uninitialized


trait AwsEnvironment:
  def getRegion: ZIO[Client, Nothing, Option[Region]]
  def getAwsIPs: ZIO[Client & Scope, Throwable, Unit]
  def isIpAllowed(ip: String): ZIO[Any, Throwable, Boolean]


// This is the live/real implementation. Could produce a mock implementation to inject for testing
final case class LiveAwsEnvironment(awsConfig: AWSConfig) extends AwsEnvironment:

  private var validAwsIpRanges: ValidAwsIpRanges = uninitialized  // a set-once variable

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

  def getRegion: ZIO[Client, Nothing, Option[Region]] =
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


object AwsEnvironment:
  // Creates a live instance for dependency injection in main program
  val live: ZLayer[AWSConfig, Nothing, AwsEnvironment] =
    ZLayer.fromZIO {
      for {
        awsConfig <- ZIO.service[AWSConfig]
      } yield LiveAwsEnvironment(awsConfig)
    }
