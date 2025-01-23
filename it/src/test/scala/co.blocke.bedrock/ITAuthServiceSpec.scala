package co.blocke.bedrock
package services
package auth

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.http.*
import java.time.Instant
import services.endpoint.* 
import services.db.*
import services.aws.*

// import izumi.reflect.Tag

/**
  * Test all the Auth behavior, token rotation etc.
  */
object ITAuthServiceSpec extends ZIOSpecDefault {

   // Layer for the ZIO HTTP client
  val clientLayer: ZLayer[Any, Nothing, Client] = Client.default.orDie

  val testProgram: ZIO[Any, Throwable, Unit] = ZIO.scoped {
    val testAppLayer = ZLayer.make[BookEndpoint & AwsEventEndpoint & Client](
      Main.clientLayer,
      AppConfig.live.tap(_ => ZIO.logInfo("Configuration loaded for test")),
      Authentication.live,
      SecretKeyManager.live,
      AwsEnvironment.live,
      BookRepo.mock, // Use mock repositories for tests
      BookEndpoint.live,
      AwsEventEndpoint.live
    )

    Main.program.provideSomeLayer[Scope](testAppLayer ++ Main.serverLayer ++ Main.clientLayer)
  }

  /*
  val testProgram2: ZIO[Any, Throwable, Unit] = ZIO.scoped {
    val testAppLayer = ZLayer.make[BookEndpoint & AwsEventEndpoint & Client](
      Main.clientLayer,
      AppConfig.live.tap(_ => ZIO.logInfo("Configuration loaded for test")),
      Authentication.live,
      SecretKeyManager.live,
      AwsEnvironment.live,
      BookRepo.mock,
      BookEndpoint.live,
      AwsEventEndpoint.live
    )

    for {
      // Start the server in a fiber
      bookEndpoint     <- ZIO.service[BookEndpoint]
      awsEventEndpoint <- ZIO.service[AwsEventEndpoint]
      routes            = bookEndpoint.routes ++ awsEventEndpoint.routes
      _ <- ZIO.logInfo("Starting test server...")
      serverFiber <- Server
                      .serve(routes)
                      .provide(Main.serverLayer)
                      .fork
      _ <- ZIO.addFinalizer(serverFiber.interrupt) // Stop the server when tests complete

      // Wait for the server to become ready
      client <- ZIO.service[Client]
      _ <- client
            .request(Request.get("https://localhost:8073/login"))
            .retry(Schedule.fixed(100.millis) && Schedule.recurs(30))
            .tapError(_ => ZIO.logError("Readiness check failed"))
            .orElseFail(new RuntimeException("Server failed to start within the timeout."))
      _ <- ZIO.logInfo("Test server is ready!")
    } yield ()
  }.provideSomeLayer[Scope](Main.serverLayer ++ Main.clientLayer)
  */

  override def spec = suite("Integration Test - AuthServiceSpec")(
    test("Login should succeed") {
      for {
        // response <- Client.batched(Request.get("http://localhost:8073/test"))
        response <- Client.batched(Request.get("https://localhost:8073/login"))
        body <- response.body.asString
      } yield assert(response.status)(equalTo(Status.Ok)) &&
        assert(body)(equalTo("Server is running!"))
    },
  ).provideLayer(Main.clientLayer) @@ TestAspect.beforeAll(testProgram.orDie) @@ TestAspect.sequential
  // ).provideLayer(Main.clientLayer) @@ TestAspect.beforeAll(testProgram.orDie) @@ TestAspect.sequential


      // } yield assert(response.status)(equalTo(Status.Ok)) &&
      //   assert(body)(equalTo("Expceted"))
  // ).provideLayer(testLayer) @@ TestAspect.sequential

  // Use this in case of SSL errors
  // val testClientLayer: ZLayer[Any, Throwable, Client] = ZLayer.make[Client](
  //   ZLayer.succeed(ZClient.Config.default.ssl(SslConfig.unsafeDisableCertificateValidation)),
  //     Client.customized,
  //     NettyClientDriver.live,
  //     DnsResolver.default,
  //     ZLayer.succeed(NettyConfig.defaultWithFastShutdown)
  //   )
  
}


    /*
    test("Token decoding should fail upon token expiry") {
      for {
        auth     <- ZIO.service[Authentication]
        curKeyBundle = auth.asInstanceOf[LiveAuthentication].getKeyBundle
        loginTokens <- auth.login("TestUser", "blah")
        _        <- TestClock.adjust(421.seconds)
        result   <- auth.asInstanceOf[LiveAuthentication].decodeToken(loginTokens.authToken,None).either
      } yield result match {
        case Left(response) =>
          assert(response.status)(equalTo(Status.Unauthorized)) // Assert the error Response
        case Right(_) =>
          assert(false)(equalTo(true)) // Fail the test if no error occurs
      }
    },
    test("Token refresh should succeed upon token expiry and the presence of a valid session token") {
      for {
        auth     <- ZIO.service[Authentication]
        curKeyBundle = auth.asInstanceOf[LiveAuthentication].getKeyBundle
        loginTokens <- auth.login("TestUser", "blah")
        _        <- TestClock.adjust(421.seconds)
        result1  <- auth.asInstanceOf[LiveAuthentication].decodeToken(loginTokens.authToken,None).either // expired
        result2  <- auth.asInstanceOf[LiveAuthentication].decodeToken(loginTokens.authToken,Some(loginTokens.sessionToken)).either
      } yield result1 match {
        case Left(response) =>
          assert(response.status)(equalTo(Status.Unauthorized)) // Assert the error Response
          result2 match {
            case Left(response) =>
              assert(false)(equalTo(true)) // Fail the test if error occurs
            case Right((newAuthToken, session)) =>
              assert(newAuthToken)(not(isNone)) && assert(session)(equalTo(Session("TestUser")))
          }
        case Right(_) =>
          assert(false)(equalTo(true)) // Fail the test if no error occurs
      }
    },
    test("Token refresh should fail upon token expiry and the presence of an valid session token outside refresh window") {
      for {
        auth     <- ZIO.service[Authentication]
        curKeyBundle = auth.asInstanceOf[LiveAuthentication].getKeyBundle
        loginTokens <- auth.login("TestUser", "blah")
        _        <- TestClock.adjust(841.seconds)
        result1  <- auth.asInstanceOf[LiveAuthentication].decodeToken(loginTokens.authToken,None).either // expired
        result2  <- auth.asInstanceOf[LiveAuthentication].decodeToken(loginTokens.authToken,Some(loginTokens.sessionToken)).either
      } yield result1 match {
        case Left(response) =>
          assert(response.status)(equalTo(Status.Unauthorized)) // Assert the error Response
          result2 match {
            case Left(response) =>
              assert(response.status)(equalTo(Status.Unauthorized)) // Assert the error Response
            case Right((newAuthToken, session)) =>
              assert(false)(equalTo(true)) // Fail the test if error occurs
          }
        case Right(_) =>
          assert(false)(equalTo(true)) // Fail the test if no error occurs
      }
    },
    test("Token refresh should fail upon token expiry and the presence of an expired session token") {
      for {
        auth         <- ZIO.service[Authentication]
        curKeyBundle = auth.asInstanceOf[LiveAuthentication].getKeyBundle
        loginTokens  <- auth.login("TestUser", "blah")
        _            <- TestClock.adjust(7201.seconds)
        result1      <- auth.asInstanceOf[LiveAuthentication].decodeToken(loginTokens.authToken,None).either // expired
        result2      <- auth.asInstanceOf[LiveAuthentication].decodeToken(loginTokens.authToken,Some(loginTokens.sessionToken)).either
      } yield result1 match {
        case Left(response) =>
          assert(response.status)(equalTo(Status.Unauthorized)) // Assert the error Response
          result2 match {
            case Left(response) =>
              assert(response.status)(equalTo(Status.Unauthorized)) // Assert the error Response
            case Right((newAuthToken, session)) =>
              assert(false)(equalTo(true)) // Fail the test if error occurs
          }
        case Right(_) =>
          assert(false)(equalTo(true)) // Fail the test if no error occurs
      }
    },
    test("Secret Key rotation works") {
      for {
        auth           <- ZIO.service[Authentication]
        curKeyBundle   =  auth.asInstanceOf[LiveAuthentication].getKeyBundle
        _              <- auth.updateKeys
        curKeyBundle2  =  auth.asInstanceOf[LiveAuthentication].getKeyBundle
      } yield assert(curKeyBundle2.previousTokenKey.get)(equalTo(curKeyBundle.currentTokenKey)) &&
        assert(curKeyBundle2.currentTokenKey)(not(equalTo(curKeyBundle.currentTokenKey)))
    },
    test("After Secret Key rotation, old tokens should work with the previous key if they are not otherwise expired (new token generated)") {
      for {
        auth     <- ZIO.service[Authentication]
        curKeyBundle = auth.asInstanceOf[LiveAuthentication].getKeyBundle
        loginTokens <- auth.login("TestUser", "blah")
        _        <- auth.updateKeys
        result   <- auth.asInstanceOf[LiveAuthentication].decodeToken(loginTokens.authToken, None)
      } yield assert(result._2)(equalTo(Session("TestUser"))) &&
        assert(result._1)((not(isNone))) &&
        assert(result._1.get)(not(equalTo(loginTokens.authToken)))
    },
    test("After Secret Key rotation, a server may have missed the rotate-secret message and not have any current token") {
      for {
        clock    <- ZIO.clock
        auth     <- ZIO.service[Authentication]
        keyMgr   <- ZIO.service[SecretKeyManager]
        keys     <- keyMgr.getSecretKey  // rotate the keys but don't tell Authentication with auth.updateKeys
        token    <- JwtToken.jwtEncode("TestUser", keys.currentTokenKey.value, 3600)(clock)
        result   <- auth.asInstanceOf[LiveAuthentication].decodeToken(token,None).either
      } yield result match {
        case Left(response) =>
          assert(response.status)(equalTo(Status.Unauthorized)) // Assert the error Response
        case Right(_) =>
          assert(false)(equalTo(true)) // Fail the test if no error occurs
      }
    },
    */
 

    //-----------


    /* 
      /*
  val testLayer: ZLayer[Any, Throwable, Server & Client] = {
    val readinessCheck: ZIO[Client, Throwable, Unit] = for {
      _ <- ZIO.logInfo("Starting readiness check...")
      client <- ZIO.service[Client]
      _ <- client
            .request(Request.get("http://localhost:8073/login"))
            .retry(Schedule.fixed(100.millis) && Schedule.recurs(30))
            .tapError(_ => ZIO.logError("Readiness check failed"))
            .orElseFail(new RuntimeException("Server failed to start within the timeout."))
      _ <- ZIO.logInfo("Server is ready!")
    } yield ()

    ZLayer.scoped {
      for {
        scope <- ZIO.scope
        env   <- (Main.serverLayer ++ Main.clientLayer).build.provideEnvironment(ZEnvironment(scope)) // Build the environment
        _     <- readinessCheck.provideEnvironment(env) // Run readiness check within the built environment
      } yield env
    }
  }
  */

  val app = Routes(
    Method.GET / "test" -> handler { Response.text("Server is running!") }
  )

  val secureServerConfig: Server.Config => zio.http.Server.Config = 
    (config: Server.Config) => config.port(8073)

  // Server layer with configuration
  val serverLayer: ZLayer[Any, Throwable, Unit] = ZLayer.scoped {
    for {
      _ <- Server
             .serve(app)
             .provide(
               Server.defaultWith(secureServerConfig) // Set host and port
             ).fork
      _ <- ZIO.logInfo("Server started on http://localhost:8073")
    } yield ()
  }

    
     */