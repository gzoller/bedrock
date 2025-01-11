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
import com.typesafe.config.{Config, ConfigFactory}

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

    type MyClient = ZClient[Any, Scope, Body, Throwable, Response]

    val clockLayer: ZLayer[Any, Nothing, Clock] = ZLayer.succeed(Clock.ClockLive)

    val configLayer: ULayer[Config] = ZLayer.succeed(ConfigFactory.load())

    val secretKeyManagerLayer: ZLayer[Config & MyClient, Throwable, SecretKeyManager] =
      (AwsEnvironment.live ++ configLayer ++ clientLayer >>> SecretKeyManager.live)

    val authenticationLayer: ZLayer[Config & MyClient & SecretKeyManager, Throwable, Authentication] =
      (configLayer ++ clientLayer ++ secretKeyManagerLayer >>> Authentication.live)

    val awsEndpointLayer: ZLayer[Config & MyClient & SecretKeyManager, Throwable, AwsEventEndpoint] =
      authenticationLayer >>> AwsEventEndpoint.live

    val bookEndpointLayer: ZLayer[Config & MyClient & SecretKeyManager, Throwable, BookEndpoint] =
      (authenticationLayer ++ BookRepo.mock >>> BookEndpoint.live)

    val appLayer: ZLayer[Any, Throwable, BookEndpoint & AwsEventEndpoint] =
      configLayer ++ clientLayer >>> (
        secretKeyManagerLayer >+> authenticationLayer >+> (bookEndpointLayer ++ awsEndpointLayer)
      )

    program.provide(appLayer ++ serverLayer).exitCode

  }
}
