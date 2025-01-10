package co.blocke.bedrock
package services.auth

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.http.*
import services.db.*
import services.auth.*
import com.typesafe.config.{Config, ConfigFactory}
import java.time.Instant
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim}
import java.time.Clock
import services.auth.{Session, SecretKeyManager, LiveAuthentication, Key}
import services.endpoint.LiveBookEndpoint
import services.db.BookRepo

import izumi.reflect.Tag

/**
  * Test all the Auth behavior, token rotation etc.
  */
object AuthServiceSpec extends ZIOSpecDefault {

  implicit val clock: Clock = Clock.systemUTC

  val bookRepoMock: BookRepo = new BookRepo {
    override def find(query: String): List[Book] = 
      if query == "zio" then        
        List(Book("ZIO in Action", List("John Doe"), 2021))
      else List.empty
  }

  // TODO: Make this respect the TestClock, not Instant.now()

  // This mock SecretKeyManager rotates the keys upon every request
  case class MockSecretKeyManager(clock: zio.Clock) extends SecretKeyManager {
    private var version: Int = 1
    private var current: Key = Key(s"v$version", s"secret_$version", Instant.EPOCH) // Placeholder
    private var previous: Option[Key] = None

    override def getSecretKey: ZIO[Any, Throwable, (Key, Option[Key])] =
      for {
        now <- clock.instant.map(_.toEpochMilli) // Fetch time dynamically
        nowInstant = Instant.ofEpochMilli(now)
        result <- ZIO.succeed {
          if version == 1 then
            current = Key(s"v$version", s"secret_$version", nowInstant)
          val output = (current, previous)
          previous = Some(current)
          version += 1
          current = Key(s"v$version", s"secret_$version", nowInstant)
          output
        }
      } yield result
  }


  /* 
  I have a ZIO layer problem that's kicking me in the head.  I have this composition:
    ```scala
    // These compile file
    val configLayer: ULayer[Config] = ...
    val secretKeyLayer: ZLayer[zio.Clock, Nothing, SecretKeyManager] = ...
    val authLayer: ZLayer[Config & SecretKeyManager & zio.Clock, Throwable, Authentication] = ...

    val testLayer = TestEnvironment.live ++ (configLayer ++ secretKeyLayer ++ authLayer)

    def spec = suite("AuthServiceSpec")(
       // tests here
    ).provideLayer(testLayer)
    ```

    Seems simple, right?  TestEnironment should provide Clock, etc.  But I get this error on provideLayer(testLayer):
    ```
    Found:    (co.blocke.bedrock.services.auth.AuthServiceSpec.testLayer :
      zio.ZLayer[
        zio.Console & (zio.System & zio.Random) & (com.typesafe.config.Config &
          co.blocke.bedrock.services.auth.SecretKeyManager & zio.Clock),
      Throwable, zio.test.TestEnvironment &
        co.blocke.bedrock.services.auth.Authentication & (com.typesafe.config.Config
        & co.blocke.bedrock.services.auth.SecretKeyManager)]
    )
    Required: zio.ZLayer[zio.test.TestEnvironment & zio.Scope, Throwable | zio.http.Response,
      ?1.OutEnvironment]
    ```
    Any ideas how to sort this out?
  
   */

  val clockLayer: ZLayer[Any, Nothing, zio.Clock] =
    zio.test.TestClock.default.map { env =>
      ZEnvironment(env.get[zio.Clock])
    }.asInstanceOf[ZLayer[Any, Nothing, zio.Clock]]

  val configLayer: ULayer[Config] =
    ZLayer.succeed(ConfigFactory.load()) // Provide Config independently

  val secretKeyManagerLayer: ZLayer[zio.Clock, Nothing, SecretKeyManager] =
    ZLayer.fromZIO {
      for {
        clock <- ZIO.service[zio.Clock] // Only depends on Clock
      } yield new MockSecretKeyManager(clock)
    }

  val authenticationLayer: ZLayer[Config & SecretKeyManager & zio.Clock, Throwable, Authentication] =
    ZLayer.fromZIO {
      for {
        config <- ZIO.service[Config] // Depends on Config
        manager <- ZIO.service[SecretKeyManager] // Depends on SecretKeyManager
        clock <- ZIO.service[zio.Clock] // Depends on Clock
        (currentKey, previousKey) <- manager.getSecretKey
      } yield new LiveAuthentication(config, clock, manager, currentKey, previousKey)
    }

  // Compose the final layer
  val finalLayer = //: ZLayer[Any, Throwable, Config & SecretKeyManager & Authentication] =
    clockLayer ++ configLayer ++ (clockLayer >>> secretKeyManagerLayer) ++ (clockLayer ++ configLayer ++ (clockLayer >>> secretKeyManagerLayer) >>> authenticationLayer)

  def spec = suite("AuthServiceSpec")(
    test("Simple token encoding and decoding should work (w/o rotation)") {
      for {
        auth     <- ZIO.service[Authentication]
        curKey   =  auth.asInstanceOf[LiveAuthentication].getCurrentSecretKey
        oldToken <- auth.jwtEncode("TestUser", curKey.value)
        result   <- auth.asInstanceOf[LiveAuthentication].decodeToken(oldToken)
      } yield assert(result._2)(equalTo(Session("TestUser"))) &&
        assert(result._1)(isNone)
    },
    test("Token decoding should fail upon token expiry (w/o rotation)") {
      for {
        auth     <- ZIO.service[Authentication]
        curKey   =  auth.asInstanceOf[LiveAuthentication].getCurrentSecretKey
        oldToken <- auth.jwtEncode("TestUser", curKey.value)
        _        <- TestClock.adjust(421.seconds)
        result   <- auth.asInstanceOf[LiveAuthentication].decodeToken(oldToken).either
      } yield result match {
        case Left(response) =>
          assert(response.status)(equalTo(Status.Unauthorized)) // Assert the error Response
        case Right(_) =>
          assert(false)(equalTo(true)) // Fail the test if no error occurs
      }
    },
      /*
    test("Secret Key rotation works") {
      for {
        auth     <- ZIO.service[Authentication]
        curKey   =  auth.asInstanceOf[LiveAuthentication].getCurrentSecretKey
        prevKey  =  auth.asInstanceOf[LiveAuthentication].getPreviousSecretKey
        _        <- auth.updateKeys
        curKey2  =  auth.asInstanceOf[LiveAuthentication].getCurrentSecretKey
        prevKey2 =  auth.asInstanceOf[LiveAuthentication].getPreviousSecretKey
        _ <- ZIO.succeed(println(curKey.toString+" :: "+prevKey + " >> "+curKey2+ " :: "+prevKey2))
      } yield assert(prevKey2.get)(equalTo(curKey)) &&
        assert(curKey2)(not(equalTo(curKey)))
    },
    */
    /*
    test("After Secret Key rotation, old tokens should work within old_token_grandfather_period_sec window (new token generated)") {
      for {
        auth     <- ZIO.service[Authentication]
        curKey   =  auth.asInstanceOf[LiveAuthentication].getCurrentSecretKey
        oldToken <- auth.jwtEncode("TestUser", curKey.value)
        _        <- auth.updateKeys
        newToken <- auth.asInstanceOf[LiveAuthentication].decodeToken(oldToken)
      } yield assert(newToken._2)(equalTo(Session("TestUser"))) &&
        assert(newToken._1.get)(not(equalTo(oldToken)))
    },
    */
    /*
    test("After Secret Key rotation, old tokens should fail outside old_token_grandfather_period_sec window") {
      for {
        auth     <- ZIO.service[Authentication]
        curKey   =  auth.asInstanceOf[LiveAuthentication].getCurrentSecretKey
        oldToken <- auth.jwtEncode("TestUser", curKey.value)
        _        <- auth.updateKeys
        _        <- TestClock.adjust(185.seconds)
        newToken <- auth.asInstanceOf[LiveAuthentication].decodeToken(oldToken)
      } yield assert(newToken._2)(equalTo(Session("TestUser"))) &&
        assert(newToken._1.get)(not(equalTo(oldToken)))
    }
        */
    /*
    test("Tokens should be rotated upon activity within the token_rotation_sec window") {
    test("Tokens should fail after they expire with no activity") {
     */
  ).provide(finalLayer)
  
}