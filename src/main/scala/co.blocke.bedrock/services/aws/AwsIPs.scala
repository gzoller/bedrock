package co.blocke.bedrock
package services
package aws

import zio.*
import zio.http.*
import zio.json.*
import org.apache.commons.net.util.SubnetUtils

import scala.compiletime.uninitialized

/* 

  This trait and related classes is currently dormant. The intention was to check IPs of incoming requests against a whitelist of AWS IPs,
  in this case IP ranges for AWS services. This is a security measure to ensure that only requests from AWS services are allowed.
  However... there are a number of cases where the IPs are not present in the headers and lack of an IP address is not necessarily
  an indication of a non-AWS request.

  At the present time, given the complexity of the AWS IP ranges and the fact that the IPs are not always present in the headers, this
  feature is not being used. It is left here for future reference.

  Some incoming requests (eg SNS) have certs and in those cases the certs are verified, as a primary security mechanism.

 */


/**
  * This is a structure containing valid IPs for AWS services--a whitelist--for protecting endpoints.
  * AWS also signs their requests, so we'll check that too.
  */
case class AwsIPs(
    syncToken: String,
    createDate: String,
    prefixes: List[Prefix]
)

object AwsIPs:
  implicit val decoder: JsonDecoder[AwsIPs] = DeriveJsonDecoder.gen[AwsIPs]

case class Prefix(
    ip_prefix: Option[String],
    ipv6_prefix: Option[String],
    region: String,
    service: String,
    network_border_group: String
)

object Prefix:
  implicit val decoder: JsonDecoder[Prefix] = DeriveJsonDecoder.gen[Prefix]


case class ValidAwsIpRanges(
  ipv4: List[String],
  ipv6: List[String]
)


// Functions to read and validate AWS IP ranges
trait IPValidation {

  private var validAwsIpRanges: ValidAwsIpRanges = uninitialized  // a set-once variable

  // Run this at start-up to load the AWS IP ranges
  def getAwsIPs(ipRangesURL: String): ZIO[Scope & Client, Throwable, Unit] =
    for {
      client   <- ZIO.service[Client]
      response <- client.request(Request.get(ipRangesURL))
      json     <- response.body.asString
      awsIPs <- ZIO
        .fromEither(json.fromJson[AwsIPs])
        .mapError(ex => new RuntimeException(s"Failed to parse JSON: $json with error $ex"))
    } yield {
      validAwsIpRanges = calcRanges(awsIPs)
      ()
    }

  // X-Forwarded-For used by proxies and load blanacers, otherwise use host address in request
  def extractClientIp(request: Request): ZIO[Any, Nothing, Option[String]] = {
    ZIO.succeed {
      request.headers.get("X-Forwarded-For").map(_.split(",").head.trim)
        .orElse(request.remoteAddress.map(_.getHostAddress))
    }
  }

  // Does the given ip belong to any of the given ranges (either IP4 or IP6 as appropriate)
  def isIpAllowed(ip: String): ZIO[Any, Throwable, Boolean] = 
    ZIO.attempt{
      val isIpv4 = ip.contains(".") // Simple check for IPv4
      val validRanges = if (isIpv4) validAwsIpRanges.ipv4 else validAwsIpRanges.ipv6

      validRanges.exists { range =>
        val subnet = new SubnetUtils(range)
        subnet.getInfo.isInRange(ip)
      }
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
}

// val localhostRange = "127.0.0.0/8"
