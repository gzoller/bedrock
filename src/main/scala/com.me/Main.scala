package com.me

import zio.*
import zio.Console.*
import zio.http.*

class EmptyListException(message: String) extends Exception(message)

case class Config(prefix: String)

object Main extends ZIOAppDefault:

  def fn(strings: List[String]): ZIO[Config, EmptyListException, Int] = 
    ZIO.serviceWithZIO[Config] { config =>
      if (strings.isEmpty) 
        ZIO.fail(new EmptyListException("List is empty"))
      else 
        ZIO.foreach(strings)(str => printLine(s"${config.prefix} $str")) // print using ZIO.Console
          .mapError(_ => new EmptyListException("IO Error")) // Console may toss an IO Exception so need to map that to an EmptyListExcpetion, which is what is expected
          .as(strings.length) // resulting Int

    }

  val program: ZIO[Config & Scope & BookRepo, EmptyListException, Unit] = 
    // Set up SSL certs, config, and port for use when we start the server.
    val sslConfig = SSLConfig.fromResource("server.crt", "server.key")
    val serverConfig = (config: Server.Config) => config.port(8073).ssl(sslConfig)
    val serverLayer = Server.defaultWith(serverConfig)
    for {
      count <- fn(List("Hello", "world", "from", "ZIO"))
      _ <- ZIO.succeed(println(s"Number of strings printed: $count"))

      // Get the injected BookRepo dependency
      bookRepo <- ZIO.service[BookRepo]
      bookService = BookService(bookRepo)

      shutdownPromise <- Promise.make[Nothing, Unit]
      server <- Server.serve(bookService.routes).provide(serverLayer).fork
      _ <- shutdownPromise.await.onInterrupt(server.interrupt)
    } yield ()

  val configs = List(Config(prefix = "Prefix:"), Config(prefix = "Blah:"))

  override def run: ZIO[Any & ZIOAppArgs & Scope, Any, Any] = {
    val config = configs(scala.util.Random.nextInt(configs.length))
    program
      .provideSomeLayer[Scope](
        ZLayer.succeed(BookRepoStd) ++ 
        ZLayer.succeed(config)
        )
      .exitCode
  }