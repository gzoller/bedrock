package com.me
package services
package aws

import zio.*
import zio.http.*
import software.amazon.awssdk.regions.Region

trait AwsEnvironment:
  def getRegion: ZIO[ZClient[Any, Scope, Body, Throwable, Response], Throwable, Option[Region]]


// This is the live/real implementation. Could produce a mock implementation to inject for testing
final case class LiveAwsEnvironment() extends AwsEnvironment:

  def getRegion: ZIO[ZClient[Any, Scope, Body, Throwable, Response], Throwable, Option[Region]] =
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
    } yield (result)


object AwsEnvironment:
  // Creates a live instance for dependency injection in main program
  val live: ULayer[AwsEnvironment] =
    ZLayer.succeed(LiveAwsEnvironment())
