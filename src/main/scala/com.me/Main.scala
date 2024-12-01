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

  val program: ZIO[Config & Scope & BookRepo, EmptyListException, Unit] = for {
    count <- fn(List("Hello", "world", "from", "ZIO"))
    _ <- ZIO.succeed(println(s"Number of strings printed: $count"))
    shutdownPromise <- Promise.make[Nothing, Unit]
    bookRepo <- ZIO.service[BookRepo]
    bookService = BookService(bookRepo)
    server <- Server.serve(bookService.routes).provide(Server.default).fork
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
