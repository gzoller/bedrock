package com.me

import zio.*
import zio.Console.*
import zio.http.*
import auth.SecretKeyManager
import aws.AwsEnvironment

import zio.http.netty.*
import zio.http.netty.client.NettyClientDriver

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

  val program: ZIO[Scope & Client & Config & BookRepo, EmptyListException, Unit] = 
    // Set up SSL certs, config, and port for use when we start the server.
    val sslConfig = SSLConfig.fromResource(
      behaviour = SSLConfig.HttpBehaviour.Fail, // Restrict everything to HTTPS--appropriate for entrprise server
      certPath = "server.crt", 
      keyPath = "server.key"
      )
    val serverConfig = (config: Server.Config) => config.port(8073).ssl(sslConfig)
    val serverLayer = Server.defaultWith(serverConfig)
    for {
      count <- fn(List("Hello", "world", "from", "ZIO"))
      _ <- ZIO.succeed(println(s"Number of strings printed: $count"))

      // Ping AWS to see if we're actually running on AWS. If no response, assume running locally
      awsRegion   <- AwsEnvironment.getAwsRegion.mapError {
        case _: Throwable => new EmptyListException("AWS Region retrieval failed")
      }
      _        <- ZIO.logInfo(s"Region: ${awsRegion.getOrElse("Unknown")}")      

      secretKey <- SecretKeyManager.getSecretKey(awsRegion).tapError(e => ZIO.logError(s"Failed to retrieve secret: ${e.getMessage}"))
            .orElse(ZIO.succeed("foo"))
      _ <- ZIO.succeed(println(s"Secret key: $secretKey"))
      
      // Get the injected BookRepo dependency
      bookRepo <- ZIO.service[BookRepo]
      bookService = BookService(bookRepo)

      shutdownPromise <- Promise.make[Nothing, Unit]
      server <- Server.serve(bookService.routes).provide(serverLayer).fork
      _ <- shutdownPromise.await.onInterrupt(server.interrupt)
    } yield ()

  val configs = List(Config(prefix = "Prefix:"), Config(prefix = "Blah:"))

  private val partialClientLayer = ZLayer.makeSome[ZClient.Config, Client](
    Client.customized,
    NettyClientDriver.live,
    DnsResolver.default,
    ZLayer.succeed(NettyConfig.defaultWithFastShutdown),
  )

  override def run: ZIO[Any & ZIOAppArgs, Any, Any] = {
    val config = configs(scala.util.Random.nextInt(configs.length))

    ZIO.scoped {
      program
        .provideSomeLayer(
          ZLayer.succeed(ZClient.Config.default.connectionTimeout(5.second)) >>>
          partialClientLayer ++

          ZLayer.succeed(BookRepoStd) ++ 
          ZLayer.succeed(config)
          )
    }.exitCode
  }