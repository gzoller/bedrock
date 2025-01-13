package co.blocke.bedrock

import services.* 
import auth.*
import db.BookRepo

import zio.*
import zio.http.*

import aws.AwsEnvironment
import services.endpoint.BookEndpoint

import zio.http.netty.*
import zio.http.netty.client.NettyClientDriver
import co.blocke.bedrock.services.endpoint.AwsEventEndpoint

object Main extends ZIOAppDefault {

  val sslConfig = SSLConfig.fromResource(
    behaviour = SSLConfig.HttpBehaviour.Fail,
    certPath = "server.crt",
    keyPath = "server.key"
  )
  val serverConfig = (config: Server.Config) => config.port(8073).ssl(sslConfig)
  val serverLayer = Server.defaultWith(serverConfig).orDie

  val clientLayer = ZLayer.make[Client](
    ZLayer.succeed(ZClient.Config.default.connectionTimeout(5.seconds)),
    Client.customized,
    NettyClientDriver.live,
    DnsResolver.default,
    ZLayer.succeed(NettyConfig.defaultWithFastShutdown)
  )

  override def run: URIO[Any, ExitCode] = {

    val program =
      for {
        bookEndpoint <- ZIO.service[BookEndpoint]
        awsEventEndpoint <- ZIO.service[AwsEventEndpoint]
        awsEnv <- ZIO.service[AwsEnvironment]
        validIps <- awsEnv.getAwsIPs
        routes = bookEndpoint.routes ++ awsEventEndpoint.routes
        shutdownPromise <- Promise.make[Nothing, Unit]
        serverFiber <- Server.serve(routes).fork
        _ <- shutdownPromise.await.onInterrupt(serverFiber.interrupt)
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
