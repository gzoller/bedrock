package com.example

import zio.*
import zio.http.*

object HelloApp extends ZIOAppDefault:

  val secureServerConfig: Server.Config => Server.Config =
    _.port(8000)

  val serverLayer: ZLayer[Any, Nothing, Server] =
    Server.defaultWith(secureServerConfig).orDie

  // Define routes
  val routes = Routes(
    Method.GET / "say" / "hello" -> handler {
      ZIO.succeed(Response.text("Hello!"))
    }
  )

  override def run: ZIO[Any, Throwable, Nothing] =
    Server.serve(routes).provideLayer(serverLayer)
