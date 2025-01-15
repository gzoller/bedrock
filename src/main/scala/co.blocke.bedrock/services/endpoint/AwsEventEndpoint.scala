package co.blocke.bedrock
package services
package endpoint

import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sns.model.*
import zio.*
import zio.http.*
import zio.http.codec.*
import zio.http.endpoint.Endpoint
import zio.schema.*

import auth.Authentication
import aws.AwsEnvironment


case class SnsMessage(Type: String, SubscribeURL: Option[String], Message: Option[String])
object SnsMessage:
  implicit val schema: Schema[SnsMessage] = DeriveSchema.gen[SnsMessage]
  implicit val snsCodec: ContentCodec[SnsMessage] = HttpCodec.content[SnsMessage]


trait AwsEventEndpoint:
  def routes: Routes[Any, zio.http.Response]
  def subscribeToTopic(): ZIO[Any, Throwable, String]
  def unsubscribeOnShutdown(snsArn: String): URIO[Any, Any]


final case class LiveAwsEventEndpoint(auth: Authentication, awsConfig: AWSConfig, awsEnv: AwsEnvironment) extends AwsEventEndpoint:

  def subscribeToTopic(): ZIO[Any, Throwable, String] = 
    ZIO.attempt {
      // Create the SNS client
      val (endpointOverride, credentialsProvider) = awsEnv.getCreds  
      val snsClientBuilder = SnsClient.builder()
          .region( awsEnv.getRegion )
          .credentialsProvider(credentialsProvider)
      endpointOverride.foreach(snsClientBuilder.endpointOverride)
      val snsClient = snsClientBuilder.build()        

      try {
        // Build the SubscribeRequest
        val request = SubscribeRequest.builder()
          .topicArn(awsConfig.snsTopicArn)
          .protocol("https")
          .endpoint("https://localhost:8073/sns-handler") // TODO -- localhost
          .build()

        val response = snsClient.subscribe(request)
        response.subscriptionArn()
      } finally {       
        snsClient.close() // Ensure the client is closed
      }
    }.tapError { error =>
      ZIO.logError(s"Failed to subscribe to SNS topic: ${error.getMessage}")
    }.flatMapError { error =>
      ZIO.die(error) // Rethrow as a fatal error to terminate the server
    }.tap(s => ZIO.logInfo("Subscribed to SNS topic"))

  def unsubscribeOnShutdown(snsArn: String): URIO[Any, Any] = {
    ZIO.attempt {
      val (endpointOverride, credentialsProvider) = awsEnv.getCreds
      val snsClientBuilder = SnsClient.builder()
          .region( awsEnv.getRegion )
          .credentialsProvider(credentialsProvider)
      endpointOverride.foreach(snsClientBuilder.endpointOverride)
      val snsClient = snsClientBuilder.build()
      try {
        snsClient.unsubscribe(UnsubscribeRequest.builder()
          .subscriptionArn(snsArn)
          .build())
      } finally {
        snsClient.close() // Ensure the client is closed
      }
    }.tapError { error =>
      ZIO.logError(s"Failed to subscribe to SNS topic: ${error.getMessage}")
    }.tap(s => ZIO.logInfo("Unsubscribed to SNS topic"))
    .ignore
  }    

  // Sends a SNS subscription confirmation message
  def confirmSubscription(subscribeUrl: String): ZIO[Client & Scope, Nothing, Unit] =
    for {
      response <- Client.batched(Request.get(subscribeUrl)).catchAll { throwable =>
                    ZIO.logError(s"Failed to send request to $subscribeUrl: ${throwable.getMessage}") *> ZIO.succeed(
                      Response.status(Status.InternalServerError)
                    )
                  }
      _ <- if response.status.isSuccess then
            ZIO.logInfo(s"Confirmed subscription: ${response.status}")
          else
            ZIO.logError(s"Failed to confirm subscription: ${response.status}")
    } yield ()

  val sns_endpoint: zio.http.endpoint.Endpoint[Unit, SnsMessage, ZNothing, Int, zio.http.endpoint.AuthType.None.type] = 
    Endpoint(RoutePattern.POST / "sns-handler")     // Define POST endpoint
      .in[SnsMessage]
      .out[Int]                                     // Output: HTTP 200 status code (default)

  val sns_handler: Handler[Client & Scope, Nothing, SnsMessage, Int] =
    Handler.fromFunctionZIO { snsMessage =>
      snsMessage.Type match {
        case "SubscriptionConfirmation" =>
          snsMessage.SubscribeURL match {
            case Some(subscribeUrl) => 
              confirmSubscription(subscribeUrl).as(200) // No `catchAll` since Nothing can't fail
            case None => 
              ZIO.logError("Missing SubscribeURL in SubscriptionConfirmation") *> ZIO.succeed(500)
          }

        case "Notification" =>
          snsMessage.Message match {
            case Some(message) =>
              ZIO.logInfo(s"Received SNS notification: $message").as(200) // No failure possible
            case None =>
              ZIO.logError("Missing Message in Notification") *> ZIO.succeed(500)
          }

        case _ =>
          ZIO.logError(s"Unknown SNS message type: ${snsMessage.Type}") *> ZIO.succeed(400)
      }
    }

  val snsRoute: Routes[ZClient[Any, Scope, Body, Throwable, Response] & Scope, Nothing] = Routes(sns_endpoint.implementHandler[Client & Scope](sns_handler))

  // --------- RotateSecret message
  // This is the endpoint we want SNS to call when secrets change
  //===============================================================
  val rotate_secret_endpoint: zio.http.endpoint.Endpoint[Unit, Unit, ZNothing, Unit, zio.http.endpoint.AuthType.None.type] = Endpoint(RoutePattern.GET / "rotate-secret")
    .out[Unit]
    // We need to protect this endpoint!  Rather than an auth token, we should use some devops
    // method, IAM, IP whitelist, or similar, to keep non-AWS people from calling this...

  val rotate_secret_handler: Handler[Any, Nothing, Unit, Unit] = handler { (_: Unit) => 
    auth.updateKeys.catchAll{ err =>
      // Log the error, but don't let it bubble up to the user 
      ZIO.logError("Error rotating keys: "+err.getMessage) *> ZIO.unit
      }
  }

  val rotateSecretRoute: Routes[Any, Nothing] = Routes(rotate_secret_endpoint.implementHandler(rotate_secret_handler))

  // --------- RotateSecret message
  // This is the endpoint we want SNS to call when secrets change
  //===============================================================


  def routes = rotateSecretRoute


object AwsEventEndpoint:
  def live: ZLayer[Authentication & AWSConfig & AwsEnvironment, Nothing, AwsEventEndpoint] = {
    ZLayer.fromZIO {
      for {
        auth      <- ZIO.service[Authentication]
        awsConfig <- ZIO.service[AWSConfig]
        awsEnv    <- ZIO.service[AwsEnvironment]
      } yield LiveAwsEventEndpoint(auth, awsConfig, awsEnv)
    }
  }
  