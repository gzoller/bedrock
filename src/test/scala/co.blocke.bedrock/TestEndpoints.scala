package co.blocke.bedrock
package services

import zio.*
import zio.http.*
import zio.http.codec.*
import zio.http.codec.PathCodec.*
import zio.http.endpoint.AuthType
import zio.http.endpoint.Endpoint
import zio.http.endpoint.openapi.OpenAPI.SecurityScheme.*
import zio.http.endpoint.AuthType.Bearer
import zio.schema.*

import auth.*

case class TestEndpoints(auth: Authentication):

  // --------- Basic Hello message (no roles)
  //===============================================================
  private val hello_endpoint: Endpoint[Unit, Unit, ZNothing, String, Bearer.type] =
    Endpoint(RoutePattern.GET / "api" / "hello")
      .out[String](MediaType.text.plain) // force plaintext response, not JSON
      .auth[AuthType.Bearer](AuthType.Bearer)

  private val hello_handler: Handler[String, Nothing, Unit, String] = handler { (_: Unit) =>
    ZIO.serviceWithZIO[String] { userId =>
      auth.getSession(userId)
        .flatMap { session =>
          if (session.profile.given_name.nonEmpty)
            ZIO.succeed(s"""Hello, World, ${session.profile.given_name.getOrElse("Unknown")}!""")
          else
            ZIO.succeed("No session, you pirate!")
        }
        .catchAll(_ => ZIO.succeed("Something failed badly..."))
    }
  }

  val helloRoute: Routes[Any, Nothing] = Routes(hello_endpoint.implementHandler(hello_handler))
    @@ auth.bedrockProtected()

  // --------- Basic Hello message (no roles)
  //===============================================================
  private val bye_endpoint: Endpoint[Unit, Unit, ZNothing, String, Bearer.type] =
    Endpoint(RoutePattern.GET / "api" / "bye")
      .out[String](MediaType.text.plain) // force plaintext response, not JSON
      .auth[AuthType.Bearer](AuthType.Bearer)

  private val bye_handler: Handler[String, Nothing, Unit, String] = handler { (_: Unit) =>
    ZIO.serviceWithZIO[String] { userId =>
      auth.getSession(userId)
        .flatMap { session =>
          if (session.profile.given_name.nonEmpty)
            ZIO.succeed(s"""Goodbye, World, ${session.profile.given_name.getOrElse("Unknown")}!""")
          else
            ZIO.succeed("No session, you pirate!")
        }
        .catchAll(_ => ZIO.succeed("Something failed badly..."))
    }
  }

  val byeRoute: Routes[Any, Nothing] = Routes(bye_endpoint.implementHandler(bye_handler))
    @@ auth.bedrockProtected(List("admin"))