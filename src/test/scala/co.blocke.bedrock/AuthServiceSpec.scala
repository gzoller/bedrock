package co.blocke.bedrock
package services
package auth

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.http.*
import java.time.Instant

import db.*
import aws.*

import izumi.reflect.Tag

/**
  * Test all the Auth behavior, token rotation etc.
  */
object AuthServiceSpec extends ZIOSpecDefault {

  val bookRepoMock: BookRepo = new BookRepo {
    override def find(query: String): List[Book] = 
      if query == "zio" then        
        List(Book("ZIO in Action", List("John Doe"), 2021))
      else List.empty
  }

  // This mock AwsSecretsManager rotates the keys upon every request
  case class MockAwsSecretsManager(clock: zio.Clock) extends AwsSecretsManager {
    private var version: Int = 1
    private var current: Key = Key(s"v$version", s"secret_$version", Instant.EPOCH) // Placeholder
    private var previous: Option[Key] = None

    override def getSecretKeys: ZIO[Any, Throwable, KeyBundle] =
      for {
        now <- clock.instant //.map(_.toEpochMilli) // Fetch time dynamically
        result <- ZIO.succeed {
          if version == 1 then
            current = Key(s"v$version", s"secret_$version", now)
          val output = (current, previous)
          previous = Some(current)
          version += 1
          current = Key(s"v$version", s"secret_$version", now)
          output
        }
      } yield KeyBundle(current, previous, Key("sess_ver", "theWayIsShut", now))
  }

  val AwsSecretsManagerLayer: ZLayer[Any, Nothing, AwsSecretsManager] =
    ZLayer.fromZIO {
      for {
        clock <- ZIO.clock // Only depends on Clock
      } yield new MockAwsSecretsManager(clock)
    }

  val authenticationLayer: ZLayer[AuthConfig & AwsSecretsManager, Throwable, Authentication] =
    ZLayer.fromZIO {
      for {
        authConfig <- ZIO.service[AuthConfig] // Depends on Config
        manager <- ZIO.service[AwsSecretsManager] // Depends on AwsSecretsManager
        clock <- ZIO.clock // Depends on Clock
        keyBundle <- manager.getSecretKeys
      } yield new LiveAuthentication(authConfig, clock, manager, keyBundle)
    }

  // Compose the final layer
  val finalLayer = 
    AppConfig.live ++ AwsSecretsManagerLayer >>> authenticationLayer ++ AwsSecretsManagerLayer

  def spec = suite("AuthServiceSpec")(
    test("Simple token encoding and decoding should work (w/o rotation)") {
      for {
        auth     <- ZIO.service[Authentication]
        curKeyBundle =  auth.asInstanceOf[LiveAuthentication].getKeyBundle
        loginTokens <- auth.login("TestUser", "blah")
        result   <- auth.asInstanceOf[LiveAuthentication].decodeToken(loginTokens.authToken,None)
      } yield assert(result._2)(equalTo(Session("TestUser"))) &&
        assert(result._1)(isNone)
    },
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
        keyMgr   <- ZIO.service[AwsSecretsManager]
        keys     <- keyMgr.getSecretKeys  // rotate the keys but don't tell Authentication with auth.updateKeys
        token    <- JwtToken.jwtEncode("TestUser", keys.currentTokenKey.value, 3600)(clock)
        result   <- auth.asInstanceOf[LiveAuthentication].decodeToken(token,None).either
      } yield result match {
        case Left(response) =>
          assert(response.status)(equalTo(Status.Unauthorized)) // Assert the error Response
        case Right(_) =>
          assert(false)(equalTo(true)) // Fail the test if no error occurs
      }
    },
  ).provide(finalLayer ++ Runtime.removeDefaultLoggers)
  
}