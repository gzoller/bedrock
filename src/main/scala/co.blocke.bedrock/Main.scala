package co.blocke.bedrock

import zio.*
import zio.http.*
import zio.http.netty.*
import zio.http.netty.client.NettyClientDriver

import services.*
import aws.{AwsSnsEndpoint, AwsSecretsManager, AwsEnvironment}
import auth.Authentication
import db.BookRepo
import services.endpoint.{BookEndpoint, HealthEndpoint}

object Main extends ZIOAppDefault {

  val sslConfig: SSLConfig = SSLConfig.fromResource(
    behaviour = SSLConfig.HttpBehaviour.Fail,
    certPath = "server.crt",
    keyPath = "server.key"
  )
  val secureServerConfig: Server.Config => zio.http.Server.Config = 
    (config: Server.Config) => config.port(8073).ssl(sslConfig)

  val serverLayer: ZLayer[Any, Nothing, Server] = Server.defaultWith(secureServerConfig).orDie

  val clientLayer: ZLayer[Any, Throwable, Client] = ZLayer.make[Client](
    ZLayer.succeed(ZClient.Config.default.connectionTimeout(5.seconds)),
    Client.customized,
    NettyClientDriver.live,
    DnsResolver.default,
    ZLayer.succeed(NettyConfig.defaultWithFastShutdown)
  )

  val program =
    for {
      _                <- ZIO.logInfo("Loading BookEndpoint service")
      bookEndpoint     <- ZIO.service[BookEndpoint]
      healthEndpoint   <- ZIO.service[HealthEndpoint]
      _                <- ZIO.logInfo("Loading AwsEventEndpoint service")
      awsSnsEndpoint   <- ZIO.service[AwsSnsEndpoint]

      // Start the server first
      _                <- ZIO.logInfo("Setting routes")
      routes           =  bookEndpoint.routes ++ awsSnsEndpoint.routes ++ healthEndpoint.routes
      shutdownPromise  <- Promise.make[Nothing, Unit] // Promises for graceful shutdown
      _                <- ZIO.logInfo("Starting server...")
      serverFiber      <- Server.serve(routes).fork        // Start the secure server
      _                <- ZIO.logInfo("Secure server started on port 8443.")

      // SNS subscription after servers are running
      _                <- awsSnsEndpoint.subscribeToTopic()
      _                <- ZIO.addFinalizer(
                            ZIO.logInfo("Unsubscribing from SNS topic...") *> 
                            awsSnsEndpoint.unsubscribeOnShutdown
                          )

      _                <- ZIO.logInfo("Application is running. Press Ctrl+C to exit.")
      _                <- ZIO.interruptible {
                          shutdownPromise.await
                            .onInterrupt(ZIO.logInfo("Shutdown signal received.") *> serverFiber.interrupt)
                        }
    } yield ()

  override def run: URIO[Any, ExitCode] = {

    ZIO.scoped {
      // Share this one or else it is read multiple times due to being used in multiple layers
      val sharedAppConfig = AppConfig.live.tap(_ => ZIO.logInfo("Configuration loaded"))

      // make here magically sews together all the dependencies for the program.
      // MUCH easier than doing it manually!
      val appLayer = ZLayer.make[BookEndpoint & AwsSnsEndpoint & HealthEndpoint & Client](
        clientLayer,
        sharedAppConfig,
        Authentication.live,
        AwsSecretsManager.live,
        AwsEnvironment.live,
        BookRepo.mock,
        BookEndpoint.live,
        AwsSnsEndpoint.live,
        HealthEndpoint.live
      )

      program.provideSomeLayer[Scope](appLayer ++ serverLayer ++ clientLayer)

    }.exitCode
  }
}
