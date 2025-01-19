package co.blocke.bedrock

import co.blocke.bedrock.services.endpoint.AwsEventEndpoint
import java.lang.System
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
  val secureServerConfig: Server.Config => zio.http.Server.Config = 
    (config: Server.Config) => config.port(8443).ssl(sslConfig)
  val unsecureServerConfig: Server.Config => zio.http.Server.Config = 
    (config: Server.Config) => config.binding("0.0.0.0",8080)

  val secureServerLayer: ZLayer[Any, Nothing, Server] = Server.defaultWith(secureServerConfig).orDie
  val unsecureServerLayer: ZLayer[Any, Nothing, Server] = Server.defaultWith(unsecureServerConfig).orDie

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
        _                <- awsEnv.getAwsIPs  // retrieve valid AWS IP ranges0

        // Start the server first
        sslRoutes        = bookEndpoint.routes
        nonsslRoutes     = awsEventEndpoint.routes

        // Promises for graceful shutdown
        secureShutdownPromise    <- Promise.make[Nothing, Unit]
        unsecureShutdownPromise  <- Promise.make[Nothing, Unit]

        // Start the secure server
        secureServerFiber <- Server.serve(sslRoutes)
                              .provideLayer(secureServerLayer)
                              .fork
        _ <- ZIO.logInfo("Secure server started on port 8443.")

        // Start the non-secure server
        unsecureServerFiber <- Server.serve(nonsslRoutes)
                                .provideLayer(unsecureServerLayer ++ Client.default ++ Scope.default)
                                .fork
        _ <- ZIO.logInfo("Non-secure server started on port 8080.")

        // Ensure servers are running
        // _ <- ZIO.logInfo("Let servers settle...")
        // _ <- ZIO.sleep(3.seconds) // Allow time for servers to bind to their ports
        // _ <- ZIO.logInfo("Servers settled.")

        // Perform the SNS subscription after servers are running
        _ <- awsEventEndpoint.subscribeToTopic()
        _ <- ZIO.addFinalizer(
              ZIO.logInfo("Unsubscribing from SNS topic...") *> 
              awsEventEndpoint.unsubscribeOnShutdown
            )

        _ <- ZIO.logInfo("Application is running. Press Ctrl+C to exit.")
        _ <- secureShutdownPromise.await
              .zipPar(unsecureShutdownPromise.await)
              .onInterrupt(secureServerFiber.interrupt *> unsecureServerFiber.interrupt)        
      } yield ()

    // Set system properties to prefer IPv4 over IPv6 for compatibility
    System.setProperty("java.net.preferIPv4Stack", "true")
    System.setProperty("java.net.preferIPv6Addresses", "false")

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

      program.provideSomeLayer(appLayer ++ secureServerLayer ++ unsecureServerLayer)
    }.exitCode
  }
}

// TODO: 2 problems
// 1. All the layers are being executed twice
// 2. Not working!
