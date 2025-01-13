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
import services.db.BookRepo

import izumi.reflect.Tag

/**
  * Test all the Auth behavior, token rotation etc.
  */
object AuthServiceSpec extends ZIOSpecDefault {

  // implicit val clock: Clock = Clock.systemUTC

  val bookRepoMock: BookRepo = new BookRepo {
    override def find(query: String): List[Book] = 
      if query == "zio" then        
        List(Book("ZIO in Action", List("John Doe"), 2021))
      else List.empty
  }

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

  val configLayer: ULayer[Config] =
    ZLayer.succeed(ConfigFactory.load()) // Provide Config independently

  val secretKeyManagerLayer: ZLayer[Any, Nothing, SecretKeyManager] =
    ZLayer.fromZIO {
      for {
        clock <- ZIO.clock // Only depends on Clock
      } yield new MockSecretKeyManager(clock)
    }

  val authenticationLayer: ZLayer[Config & SecretKeyManager, Throwable, Authentication] =
    ZLayer.fromZIO {
      for {
        config <- ZIO.service[Config] // Depends on Config
        manager <- ZIO.service[SecretKeyManager] // Depends on SecretKeyManager
        clock <- ZIO.clock // Depends on Clock
        (currentKey, previousKey) <- manager.getSecretKey
      } yield new LiveAuthentication(config, clock, manager, currentKey, previousKey)
    }

  // Compose the final layer
  val finalLayer = 
    configLayer ++ secretKeyManagerLayer ++ configLayer >>> authenticationLayer ++ secretKeyManagerLayer

  def spec = suite("AuthServiceSpec")(
    test("Simple token encoding and decoding should work (w/o rotation)") {
      for {
        auth     <- ZIO.service[Authentication]
        curKey   =  auth.asInstanceOf[LiveAuthentication].getCurrentSecretKey
        // oldToken <- auth.jwtEncode("TestUser", curKey.value)
        oldToken <- auth.login("TestUser", "blah")
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
    test("Secret Key rotation works") {
      for {
        auth     <- ZIO.service[Authentication]
        curKey   =  auth.asInstanceOf[LiveAuthentication].getCurrentSecretKey
        prevKey  =  auth.asInstanceOf[LiveAuthentication].getPreviousSecretKey
        _        <- auth.updateKeys
        curKey2  =  auth.asInstanceOf[LiveAuthentication].getCurrentSecretKey
        prevKey2 =  auth.asInstanceOf[LiveAuthentication].getPreviousSecretKey
      } yield assert(prevKey2.get)(equalTo(curKey)) &&
        assert(curKey2)(not(equalTo(curKey)))
    },
    test("After Secret Key rotation, old tokens should work within old_token_grandfather_period_sec window (new token generated)") {
      for {
        auth     <- ZIO.service[Authentication]
        curKey   =  auth.asInstanceOf[LiveAuthentication].getCurrentSecretKey
        oldToken <- auth.jwtEncode("TestUser", curKey.value)
        _        <- auth.updateKeys
        result   <- auth.asInstanceOf[LiveAuthentication].decodeToken(oldToken)
      } yield assert(result._2)(equalTo(Session("TestUser"))) &&
        assert(result._1)((not(isNone))) &&
        assert(result._1.get)(not(equalTo(oldToken)))
    },
    test("After Secret Key rotation, old tokens should fail outside old_token_grandfather_period_sec window") {
      for {
        auth     <- ZIO.service[Authentication]
        curKey   =  auth.asInstanceOf[LiveAuthentication].getCurrentSecretKey
        oldToken <- auth.jwtEncode("TestUser", curKey.value)
        _        <- auth.updateKeys
        _        <- TestClock.adjust(185.seconds)
        result   <- auth.asInstanceOf[LiveAuthentication].decodeToken(oldToken).either
      } yield result match {
        case Left(response) =>
          assert(response.status)(equalTo(Status.Unauthorized)) // Assert the error Response
        case Right(_) =>
          assert(false)(equalTo(true)) // Fail the test if no error occurs
      }
    },
    test("After Secret Key rotation, a server may have missed the rotate-secret message and not have any current token") {
      for {
        auth     <- ZIO.service[Authentication]
        keyMgr   <- ZIO.service[SecretKeyManager]
        keys     <- keyMgr.getSecretKey  // rotate the keys but don't tell Authentication with auth.updateKeys
        newToken <- auth.jwtEncode("TestUser", keys._1.value)
        result   <- auth.asInstanceOf[LiveAuthentication].decodeToken(newToken).either
      } yield result match {
        case Left(response) =>
          assert(response.status)(equalTo(Status.Unauthorized)) // Assert the error Response
        case Right(_) =>
          assert(false)(equalTo(true)) // Fail the test if no error occurs
      }
    },
    // Token rotation window:
    // token_expiration_sec - token_rotation_sec -> Don't expire tokens within this window
    test("Tokens should not be rotated upon activity outside the token_rotation_sec window") {
      for {
        auth     <- ZIO.service[Authentication]
        curKey   =  auth.asInstanceOf[LiveAuthentication].getCurrentSecretKey
        oldToken <- auth.jwtEncode("TestUser", curKey.value)
        _        <- TestClock.adjust(275.seconds)
        result   <- auth.asInstanceOf[LiveAuthentication].decodeToken(oldToken)
      } yield assert(result._2)(equalTo(Session("TestUser"))) &&
        assert(result._1)(isNone)
    },
    test("Tokens should not be rotated upon activity outside the token_rotation_sec window") {
      for {
        auth     <- ZIO.service[Authentication]
        curKey   =  auth.asInstanceOf[LiveAuthentication].getCurrentSecretKey
        oldToken <- auth.jwtEncode("TestUser", curKey.value)
        _        <- TestClock.adjust(350.seconds)
        result   <- auth.asInstanceOf[LiveAuthentication].decodeToken(oldToken)
      } yield assert(result._2)(equalTo(Session("TestUser"))) &&
        assert(result._1)(not(isNone)) &&
        assert(result._1.get)(not(equalTo(oldToken)))
    }
  ).provide(finalLayer ++ Runtime.removeDefaultLoggers)
  
}