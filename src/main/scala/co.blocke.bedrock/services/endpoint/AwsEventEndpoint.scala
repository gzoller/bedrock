package co.blocke.bedrock
package services
package endpoint

import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sns.model.*
import zio.*
import zio.http.*
import zio.http.endpoint.Endpoint

import auth.Authentication
import aws.AwsEnvironment

import scala.xml.XML
import scala.jdk.CollectionConverters.*
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.Signature
import java.security.cert.CertificateFactory
import java.util.Base64


trait AwsEventEndpoint:
  def routes: Routes[Client & Scope, Nothing]
  def subscribeToTopic(): ZIO[Any, Throwable, Unit]
  def unsubscribeOnShutdown: URIO[Any, Any]


final case class LiveAwsEventEndpoint(auth: Authentication, awsConfig: AWSConfig, awsEnv: AwsEnvironment) extends AwsEventEndpoint:

  private var snsSubscriptionArn: String = ""

  def subscribeToTopic(): ZIO[Any, Throwable, Unit] = 
    ZIO.attempt {
      // Create the SNS client
      val (endpointOverride, credentialsProvider) = awsEnv.getCreds  
      val snsClientBuilder = SnsClient.builder()
          .region(awsEnv.getRegion)
          .credentialsProvider(credentialsProvider)
      endpointOverride.foreach(snsClientBuilder.endpointOverride)
      val snsClient = snsClientBuilder.build()        

      try {
        // Build the SubscribeRequest
        val subscribeRequest = SubscribeRequest.builder()
          .topicArn(awsConfig.snsTopicArn)
          .attributes(Map("RawMessageDelivery" -> "true").asJava)
          .protocol("https")
          .endpoint("https://host.docker.internal:8073/sns-handler") // Update if necessary
          .build()

        // Subscribe to the topic
        val subscribeResponse = snsClient.subscribe(subscribeRequest)
        val subscriptionArn = subscribeResponse.subscriptionArn()

        ()
      } catch {
        case e => 
          ZIO.logError(s"Failed to subscribe to SNS topic: ${e.getMessage}")
          throw e
      } finally {
        snsClient.close() // Ensure the client is closed
      }
    }

  def unsubscribeOnShutdown: URIO[Any, Any] = {
    ZIO.attempt {
      val (endpointOverride, credentialsProvider) = awsEnv.getCreds
      val snsClientBuilder = SnsClient.builder()
          .region( awsEnv.getRegion )
          .credentialsProvider(credentialsProvider)
      endpointOverride.foreach(snsClientBuilder.endpointOverride)
      val snsClient = snsClientBuilder.build()
      try {
        snsClient.unsubscribe(UnsubscribeRequest.builder()
          .subscriptionArn(snsSubscriptionArn)
          .build())
      } finally {
        snsClient.close() // Ensure the client is closed
      }
    }.tapError { error =>
      ZIO.logError(s"Failed to subscribe to SNS topic: ${error.getMessage}")
    }.tap(s => ZIO.logInfo("Unsubscribed to SNS topic"))
    .ignore
  }    

  def verifySnsMessage(message: Map[String, String]): ZIO[Any, Nothing, Boolean] = {
    // Step 1: Download the signing certificate
    ZIO.attempt {
      // Step 1: Get the certificate and public key
      val certUrl = URI.create(message("SigningCertURL")).toURL
      if (!certUrl.getHost.endsWith("amazonaws.com") && !awsEnv.isRunningLocally) {
        throw new SecurityException("Untrusted certificate URL: " + certUrl)
      }
      val certStream = certUrl.openStream()
      val certificateFactory = CertificateFactory.getInstance("X.509")
      val certificate = certificateFactory.generateCertificate(certStream)
      certStream.close()
      val publicKey = certificate.getPublicKey

      // Step 2: Construct the string to sign
      val stringToSign = List(
        "Message", message.getOrElse("Message", ""),
        "MessageId", message.getOrElse("MessageId", ""),
        "SubscribeURL", message.getOrElse("SubscribeURL", ""),
        "Timestamp", message.getOrElse("Timestamp", ""),
        "Token", message.getOrElse("Token", ""),
        "TopicArn", message.getOrElse("TopicArn", ""),
        "Type", message.getOrElse("Type", "")
      ).mkString("\n") + "\n"

      // Step 3: Decode the signature
      val signatureBytes = Base64.getDecoder.decode(message("Signature"))

      // Step 4: Verify the signature with SHA1 (for LocalStack)
      val signatureAlgorithm = if message("SignatureVersion") == "1" then "SHA1withRSA" else "SHA256withRSA"
      val sig = Signature.getInstance(signatureAlgorithm)
      sig.initVerify(publicKey)
      sig.update(stringToSign.getBytes(StandardCharsets.UTF_8))
      sig.verify(signatureBytes)
    }.catchAll { throwable =>
      ZIO.logError("SNS message verification failed: "+throwable.getMessage) *>
      ZIO.succeed(false)
    }
  }

  // Sends a SNS subscription confirmation message
  def confirmSubscription(snsMsg: SnsMessage): ZIO[Client & Scope, Nothing, Unit] =
    (for {
      verified <- verifySnsMessage(snsMsg.validationMap)
      _ <- if (verified) {
            ZIO.logInfo("SNS message verified successfully.") 
          } else {
            ZIO.logFatal("SNS message verification failed.") *> ZIO.fail(new Exception("Invalid SNS message signature"))
          }

      response <- Client.batched(Request.get(snsMsg.SubscribeURL.get)).catchAll { throwable =>
                    ZIO.logFatal(s"Failed to send request to ${snsMsg.SubscribeURL.get}: ${throwable.getMessage}") *> ZIO.succeed(
                      Response.status(Status.InternalServerError)
                    )
                  }
      body     <- response.body.asString      // Get the response body as a string
      xml      = XML.loadString(body)         // Parse the XML response
      arn      <- ZIO
                    .fromOption((xml \\ "SubscriptionArn").headOption.map(_.text))
                    .orElseFail(new RuntimeException("SubscriptionArn not found in response"))
      snsSubscriptionArn = arn

      _ <- if response.status.isSuccess then
            ZIO.logInfo(s"Confirmed subscription: ${response}")
          else
            ZIO.logFatal(s"Failed to confirm subscription: ${response}")
    } yield ()).orDie

  // The Request coming in from SNS has some non-JSON contentType in the body, so this little intercept
  // converts the body to JSON for us.
  private val fixContentTypeMiddleware: HandlerAspect[Any, Unit] =
    HandlerAspect.interceptIncomingHandler {
      Handler.fromFunctionZIO[Request] { request =>
        for {
          bodyBytes <- request.body.asChunk // Read the request body
          bodyString = new String(bodyBytes.toArray, java.nio.charset.StandardCharsets.UTF_8)
        } yield (request.copy(body = Body.fromString(bodyString).contentType(MediaType.application.json)),())
      }.mapError { error =>
        Response.text("Internal Server Error msg: "+error.getMessage).status(Status.InternalServerError)
      }
    }

  val sns_endpoint: zio.http.endpoint.Endpoint[Unit, SnsMessage, ZNothing, Int, zio.http.endpoint.AuthType.None.type] = 
    Endpoint(RoutePattern.POST / "sns-handler")     // Define POST endpoint
      .in[SnsMessage]
      .out[Int]                                     // Output: HTTP 200 status code (default)

  val sns_handler: Handler[Client & Scope, Nothing, SnsMessage, Int] =
    Handler.fromFunctionZIO { snsMessage =>
      ZIO.logInfo(s"Processing SNS message: $snsMessage") *>
        (snsMessage.Type match {
          case "SubscriptionConfirmation" =>
            snsMessage.SubscribeURL match {
              case Some(subscribeUrl) =>
                confirmSubscription(snsMessage).as(200)
              case None =>
                ZIO.logFatal("Missing SubscribeURL in SubscriptionConfirmation") *> ZIO.succeed(500)
            }

          case "Notification" =>
            snsMessage.Message match {
              case Some(message) =>
                auth.updateKeys
                  .as(200) // Return HTTP 200 if `updateKeys` succeeds
                  .catchAll { err =>
                    // Log the error and return HTTP 500 as the response status
                    ZIO.logError(s"Error rotating keys: ${err.getMessage}") *> ZIO.succeed(500)
                  }
              case None =>
                ZIO.logError("Missing Message in Notification") *> ZIO.succeed(500)
            }

          case _ =>
            ZIO.logError(s"Unknown SNS message type: ${snsMessage.Type}") *> ZIO.succeed(400)
        })
    }

  val routes: Routes[Client & Scope, Nothing] = Routes(sns_endpoint.implementHandler[Client & Scope](sns_handler)) @@ fixContentTypeMiddleware


object AwsEventEndpoint:
  def live: ZLayer[Authentication & AWSConfig & AwsEnvironment, Nothing, AwsEventEndpoint] = {
    ZLayer.fromZIO {
      for {
        auth      <- ZIO.service[Authentication]
        awsConfig <- ZIO.service[AWSConfig]
        awsEnv    <- ZIO.service[AwsEnvironment]
        promise   <- Promise.make[Throwable, String]
      } yield LiveAwsEventEndpoint(auth, awsConfig, awsEnv)
    }
  }


  /* 
  
aws secretsmanager rotate-secret \
    --region us-east-1 \
    --endpoint-url http://localhost:4566 \
    --secret-id MySecretKey \
    --rotation-lambda-arn arn:aws:lambda:us-east-1:000000000000:function:RotateSecretFunction \
    --rotation-rules AutomaticallyAfterDays=30 

aws secretsmanager update-secret \
    --endpoint-url=http://localhost:4566 \
    --secret-id MySecretKey \
    --secret-string "newSecretString" \
    --region us-east-1

aws secretsmanager describe-secret \
    --secret-id MySecretKey \
    --endpoint-url=http://localhost:4566 \
    --region us-east-1


aws events put-events \
    --endpoint-url=$AWS_ENDPOINT_URL \
    --entries '[
        {
            "Source": "aws.secretsmanager",
            "DetailType": "AWS API Call via CloudTrail",
            "Detail": "{\"eventName\":\"RotateSecret\",\"requestParameters\":{\"secretId\":\"MySecretKey\"}}",
            "EventBusName": "default"
        }
    ]'    
   */