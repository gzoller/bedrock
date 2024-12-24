package com.me
package aws

import zio.*
import zio.http.*
import software.amazon.awssdk.regions.Region

object AwsEnvironment {

  lazy val getAwsRegion = {
    val metadataUrl = URL.decode("http://169.254.169.254/latest/meta-data/placement/region").toOption.get
    val request = Request.get(metadataUrl)

    for {
      _ <- ZIO.logInfo("Checking for AWS")
      responseOpt <- Client.batched(request).timeout(1.second) // Perform the HTTP request with timeout
      _ <- ZIO.logInfo(s"AWS said: $responseOpt")
      result <- responseOpt match {
        case Some(response) if response.status.isSuccess =>
          response.body.asString.map(r => Some(Region.of(r))) // Extract the body as String
        case _ =>
          ZIO.succeed(None) // Return None for non-successful responses or timeouts
      }
    } yield result
  }

}
