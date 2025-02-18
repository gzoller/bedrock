package co.blocke.bedrock
package services
package endpoint

import zio.*
import zio.http.*
import zio.http.codec.*
import zio.http.codec.PathCodec.*
import zio.http.endpoint.Endpoint
import zio.schema.*

import auth.Authentication
import aws.{AwsEnvironment,AwsSnsEndpoint}


trait HealthEndpoint:
  def routes: Routes[Any, Response]

final case class LiveHealthEndpoint(auth: Authentication, awsSnsEndpoint: AwsSnsEndpoint, isRunningLocally: Boolean) extends HealthEndpoint:

  private val health_endpoint: Endpoint[Unit, Unit, Unit, String, endpoint.AuthType.None] = Endpoint(RoutePattern.GET / "health")
    .out[String](MediaType.text.plain)
    .outError[Unit](Status.NotImplemented)

  private val health_handler: Handler[Any, Unit, Unit, String] =
    Handler.fromZIO { 
      for {
        isSubscribedToSns <- awsSnsEndpoint.isSubscribedToSNS
        result            <- ZIO.ifZIO(ZIO.succeed(isSubscribedToSns))(
                               ZIO.succeed("Service is healthy"),
                               ZIO.fail("Service is unhealthy")
                               ).orElseFail(()) // Convert the error type to Unit
      } yield result
    }

  private val health_routes: Routes[Any, Nothing] = Routes(health_endpoint.implementHandler(health_handler))

  //-------------------------------------------------------------------------------
  // The following endpoints are only used for unit testing. If not running locally, they are not provided to the server.
  //-------------------------------------------------------------------------------

  private val expire_token_endpoint: Endpoint[Unit, (Long,Boolean), Unit, String, zio.http.endpoint.AuthType.None] = Endpoint(RoutePattern.POST / "expire_token")
    .query(HttpCodec.query[Long]("seconds"))
    .query(HttpCodec.query[Boolean]("isSession"))
    .out[String](MediaType.text.plain)
    .outError[Unit](Status.InternalServerError)

//  private val expire_token_handler: Handler[Session, Unit, (Long,Boolean), String] =
//    Handler.fromFunctionZIO { case (expired_by_sec: Long, isSession: Boolean) =>
//      ZIO.environmentWithZIO[Session] { env =>
//        val session = env.get[Session] // Extract the Session from ZEnvironment
//        auth.issueExpiredToken(expired_by_sec, session.profile.userId, isSession)
//          .mapError(_ => ()) // Convert Throwable to Unit to match the handler's error type
//          .provideEnvironment(env) // Re-provide the Session context
//      }
//    }

//  private val expire_token_routes: Routes[Any, Nothing] = Routes(expire_token_endpoint.implementHandler(expire_token_handler)) @@ auth.bedrockProtected(List("test"))

  private val key_bundle_endpoint: Endpoint[Unit, Unit, Unit, Int, endpoint.AuthType.None] = Endpoint(RoutePattern.GET / "key_bundle_version")
    .out[Int](MediaType.text.plain)
    .outError[Unit](Status.InternalServerError)

  private val key_bundle_handler: Handler[String, Unit, Unit, Int] =
    Handler.fromZIO( auth.getKeyBundleVersion )

  private val key_bundle_routes: Routes[Any, Nothing] = Routes(key_bundle_endpoint.implementHandler(key_bundle_handler)) @@ auth.bedrockProtected(List("test"))


  val routes: Routes[Any, Response] =
    if isRunningLocally then
      health_routes //++ expire_token_routes ++ key_bundle_routes
    else
      health_routes


object HealthEndpoint:
  def live: ZLayer[AwsSnsEndpoint & AwsEnvironment & Authentication, Nothing, HealthEndpoint] =
    ZLayer.fromZIO {
      for {
        awsSnsEndpoint <- ZIO.service[AwsSnsEndpoint]
        awsEnvironment <- ZIO.service[AwsEnvironment]
        auth           <- ZIO.service[Authentication]
      } yield LiveHealthEndpoint(auth, awsSnsEndpoint, awsEnvironment.isRunningLocally)
    }
