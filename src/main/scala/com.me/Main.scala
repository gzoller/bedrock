package com.me

import zio.*
import zio.http.*
import zio.http.Header.{AccessControlAllowOrigin, AccessControlAllowMethods, AccessControlAllowHeaders}

class EmptyListException(message: String) extends Exception(message)

case class Config(prefix: String)

object Main extends ZIOAppDefault:

  def fn(strings: List[String]): ZIO[Config, EmptyListException, Int] = 
    ZIO.serviceWithZIO[Config] { config =>
      if (strings.isEmpty) 
        ZIO.fail(new EmptyListException("List is empty"))
      else 
        ZIO.succeed {
          strings.foreach(str => println(s"${config.prefix} $str"))
          strings.length
        }
    }

  val program: ZIO[Config & Scope, EmptyListException, Unit] = for {
    count <- fn(List("Hello", "world", "from", "ZIO"))
    _ <- ZIO.succeed(println(s"Number of strings printed: $count"))
    shutdownPromise <- Promise.make[Nothing, Unit]
    server <- Server.serve(MyRestService.routes).provide(Server.default).fork
    _ <- shutdownPromise.await.onInterrupt(server.interrupt)
  } yield ()

  val configs = List(Config(prefix = "Prefix:"), Config(prefix = "Blah:"))

  override def run: ZIO[Any & ZIOAppArgs & Scope, Any, Any] = {
    val config = configs(scala.util.Random.nextInt(configs.length))
    program.provideSomeLayer[Scope](ZLayer.succeed(config)).exitCode
  }
