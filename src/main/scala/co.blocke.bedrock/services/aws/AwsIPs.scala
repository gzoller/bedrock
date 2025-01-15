package co.blocke.bedrock
package services
package aws

import zio.*
import zio.json.*


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

// val localhostRange = "127.0.0.0/8"
