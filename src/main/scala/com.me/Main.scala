package com.me

import services.* 
import auth.*
import db.BookRepo

import zio.*
import zio.http.*

import aws.AwsEnvironment
import services.endpoint.BookEndpoint

import zio.http.netty.*
import zio.http.netty.client.NettyClientDriver
import com.me.services.endpoint.AwsEventEndpoint

object Main extends ZIOAppDefault {

  val sslConfig = SSLConfig.fromResource(
    behaviour = SSLConfig.HttpBehaviour.Fail,
    certPath = "server.crt",
    keyPath = "server.key"
  )
  val serverConfig = (config: Server.Config) => config.port(8073).ssl(sslConfig)
  val serverLayer = Server.defaultWith(serverConfig)

  val clientLayer = ZLayer.make[Client](
    ZLayer.succeed(ZClient.Config.default.connectionTimeout(5.seconds)),
    Client.customized,
    NettyClientDriver.live,
    DnsResolver.default,
    ZLayer.succeed(NettyConfig.defaultWithFastShutdown)
  )

  // val program: ZIO[Server & BookEndpoint & AwsEventEndpoint, Throwable, Unit] =
  val program =
    ZIO.scoped {
      for {
        bookEndpoint <- ZIO.service[BookEndpoint]
        awsEventEndpoint <- ZIO.service[AwsEventEndpoint]
        routes <- ZIO.succeed(bookEndpoint.routes ++ awsEventEndpoint.routes)
        shutdownPromise <- Promise.make[Nothing, Unit]
        server <- Server.serve(routes).fork //.provideLayer(serverLayer).fork
        _ <- shutdownPromise.await.onInterrupt(server.interrupt)
      } yield ()
    }

  override def run: URIO[Any, ExitCode] = {
    // Bunch of composition here to help untangle dependencies...
    //
    val secretKeyManagerLayer: ZLayer[Any, Throwable, SecretKeyManager] =
      AwsEnvironment.live ++ clientLayer >>> SecretKeyManager.live      

    val authenticationLayer: ZLayer[Any, Throwable, Authentication] =
      secretKeyManagerLayer >>> Authentication.live

    val bookEndpointLayer: ZLayer[Any, Nothing, BookEndpoint] =
      authenticationLayer.orDie ++ BookRepo.mock >>> BookEndpoint.live

    val awsEndpointLayer: ZLayer[Any, Throwable, AwsEventEndpoint] =
      authenticationLayer >>> AwsEventEndpoint.live

    program.provide(
      serverLayer ++                     // Provide server
      clientLayer ++                     // Provide ZClient
      secretKeyManagerLayer ++           // Provide SecretKeyManager
      authenticationLayer ++             // Provide Authentication
      bookEndpointLayer ++               // Provide BookEndpoint
      awsEndpointLayer                   // Provide AwsEventEndpoints
    ).exitCode      
  }
}

/*
object Main extends ZIOAppDefault:

  private val sslConfig = SSLConfig.fromResource(
    behaviour = SSLConfig.HttpBehaviour.Fail, // Restrict everything to HTTPS--appropriate for enterprise server
    certPath = "server.crt", 
    keyPath = "server.key"
  )
  private val serverConfig = (config: Server.Config) => config.port(8073).ssl(sslConfig)
  private val serverLayer = Server.defaultWith(serverConfig)

  private val clientLayer = ZLayer.make[Client](
    ZLayer.succeed(ZClient.Config.default.connectionTimeout(5.seconds)),
    Client.customized,
    NettyClientDriver.live,
    DnsResolver.default,
    ZLayer.succeed(NettyConfig.defaultWithFastShutdown),
  )

  private val program: ZIO[Scope & Client, Throwable, Unit] = 
    ZIO.scoped {
      for {
        bookEndpoint <- ZIO.service[BookEndpoint]
        routes <- bookEndpoint.routes

        shutdownPromise <- Promise.make[Nothing, Unit]
        server <- Server.serve(routes).provide(serverLayer).fork
        _ <- shutdownPromise.await.onInterrupt(server.interrupt)
      } yield ()
    }

  override def run: ZIO[ZIOAppArgs, Any, Any] = {
    program
      .provideSomeLayer(
        AwsEnvironment.live ++            // Provide AwsEnvironment
        clientLayer ++                    // Provide ZClient (Client)
        SecretKeyManager.live ++          // Provide SecretKeyManager (depends on AwsEnvironment & ZClient)
        Authentication.live ++            // Provide Authentication (depends on SecretKeyManager)
        BookRepo.mock ++                  // Provide mock BookRepo
        BookEndpoint.live                 // Provide BookEndpoint
      ).exitCode
  }
      */