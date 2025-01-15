package co.blocke.bedrock

import co.blocke.bedrock.services.endpoint.AwsEventEndpoint
import zio.*
import zio.http.*
import zio.http.netty.*
import zio.http.netty.client.NettyClientDriver

import services.*
import auth.*
import db.BookRepo
import aws.AwsEnvironment
import services.endpoint.BookEndpoint

object Main extends ZIOAppDefault {

  val sslConfig: SSLConfig = SSLConfig.fromResource(
    behaviour = SSLConfig.HttpBehaviour.Fail,
    certPath = "server.crt",
    keyPath = "server.key"
  )
  val serverConfig: Server.Config => zio.http.Server.Config = (config: Server.Config) => config.port(8073).ssl(sslConfig)
  val serverLayer: ZLayer[Any, Nothing, Server] = Server.defaultWith(serverConfig).orDie

  val clientLayer: ZLayer[Any, Throwable, Client] = ZLayer.make[Client](
    ZLayer.succeed(ZClient.Config.default.connectionTimeout(5.seconds)),
    Client.customized,
    NettyClientDriver.live,
    DnsResolver.default,
    ZLayer.succeed(NettyConfig.defaultWithFastShutdown)
  )

  override def run: URIO[Any, ExitCode] = {

    val program =
      for {
        bookEndpoint     <- ZIO.service[BookEndpoint]
        awsEventEndpoint <- ZIO.service[AwsEventEndpoint]
        awsEnv           <- ZIO.service[AwsEnvironment]
        _                <- awsEnv.getAwsIPs  // retrieve valid AWS IP ranges
        routes           =  bookEndpoint.routes ++ awsEventEndpoint.routes

        // Manage SNS topic for key rotation: subscribe on startup, unsubscribe on shutdown
        // (we keep the snsArn b/c this is the full arn of the subscription, which is needed to unsubscribe)
        snsArn           <- awsEventEndpoint.subscribeToTopic()
        _                <- ZIO.addFinalizer(
                              ZIO.logInfo("Unsubscribing from SNS topic...") *> 
                              awsEventEndpoint.unsubscribeOnShutdown(snsArn))

        // Start the server with graceful interrupt handling
        shutdownPromise  <- Promise.make[Nothing, Unit]
        serverFiber      <- Server.serve(routes).fork
        _                <- shutdownPromise.await.onInterrupt(serverFiber.interrupt)
      } yield ()

    ZIO.scoped {
      // Share this one or else it is read multiple times due to being used in multiple layers
      val sharedAppConfig = AppConfig.live.tap(_ => ZIO.logInfo("Configuration loaded"))

      // make here magically sews together all the dependencies for the program.
      // MUCH easier than doing it manually!
      val appLayer = ZLayer.make[BookEndpoint & AwsEventEndpoint & AwsEnvironment & Client](
        clientLayer,
        sharedAppConfig,
        Authentication.live,
        SecretKeyManager.live,
        AwsEnvironment.live,
        BookRepo.mock,
        BookEndpoint.live,
        AwsEventEndpoint.live
      )

      program.provideSomeLayer(appLayer ++ serverLayer)
    }.exitCode
  }
}

// TODO: 2 problems
// 1. All the layers are being executed twice
// 2. Not working!
