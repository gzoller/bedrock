package co.blocke.bedrock
package services
package endpoint

import auth.Authentication

import zio.*
import zio.http.*
import zio.http.endpoint.Endpoint

trait AwsEventEndpoint:
  def routes: Routes[Any, zio.http.Response]

final case class LiveAwsEventEndpoint(auth: Authentication) extends AwsEventEndpoint:

  // --------- RotateSecret message
  //===============================================================
  val rotate_secret_endpoint = Endpoint(RoutePattern.GET / "rotate-secret")
    .out[Unit]
    // We need to protect this endpoint!  Rather than an auth token, we should use some devops
    // method, IAM, IP whitelist, or similar, to keep non-AWS people from calling this...

  val rotate_secret_handler: Handler[Any, Nothing, Unit, Unit] = handler { (_: Unit) => 
    auth.updateKeys.catchAll{ err =>
      // Log the error, but don't let it bubble up to the user 
      ZIO.logError(s"Error rotating keys: $err") *> ZIO.unit
      }
  }

  val rotateSecretRoute = Routes(rotate_secret_endpoint.implementHandler(rotate_secret_handler))

  def routes = rotateSecretRoute


object AwsEventEndpoint:
  def live: ZLayer[Authentication, Nothing, AwsEventEndpoint] = {
    ZLayer.fromZIO {
      for {
        auth <- ZIO.service[Authentication]
      } yield LiveAwsEventEndpoint(auth)
    }
  }
  