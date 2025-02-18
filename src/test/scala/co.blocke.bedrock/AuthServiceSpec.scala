package co.blocke.bedrock
package services
package auth

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.http.*
import zio.json.*
import java.time.{Instant, Duration}

import aws.*
import model.*

import izumi.reflect.Tag

/**
  * Test all the Auth behavior, token rotation etc.
  */
object AuthServiceSpec extends ZIOSpecDefault {

  //
  // Mock Infrastructure
  //-------------------------------------------------

//  val bookRepoMock: BookRepo = new BookRepo {
//    override def find(query: String): List[Book] =
//      if query == "zio" then
//        List(Book("ZIO in Action", List("John Doe"), 2021))
//      else List.empty
//  }

  // This mock AwsSecretsManager rotates the keys upon every request
  case class MockAwsSecretsManager(clock: zio.Clock) extends AwsSecretsManager {
    private var version: Int = 1
    private var sessVersion: Int = 1
    private var current: Key = Key(s"v$version", s"secret_$version", Instant.EPOCH) // Placeholder
    private var previous: Option[Key] = None
    private var session: Key = Key(s"v$sessVersion", s"secret_$sessVersion", Instant.EPOCH)
    private var prevSession: Option[Key] = None
    private var sessionToggle: Boolean = false

    def toggleSessionRotation(): Unit = sessionToggle = true

    override def getSecretKeys: ZIO[Any, Throwable, KeyBundle] =
      for {
        now <- clock.instant
        result <- ZIO.succeed {
          if version == 1 then
            current = Key(s"v$version", s"secret_$version", now)
            session = Key(s"v$sessVersion", s"secret_$sessVersion", now)
          val output = (current, previous, session, prevSession)
          previous = Some(current)
          version += 1
          current = Key(s"v$version", s"secret_$version", now)
          if sessionToggle then
            sessionToggle = false
            prevSession = Some(session)
            sessVersion += 1
            session = Key(s"v$sessVersion", s"secret_$sessVersion", now)
          output
        }
      } yield KeyBundle(result._1, result._2, result._3, result._4)
  }

  val AwsSecretsManagerLayer: ZLayer[Any, Nothing, AwsSecretsManager] =
    ZLayer.fromZIO {
      for {
        clock <- ZIO.clock // Only depends on Clock
      } yield new MockAwsSecretsManager(clock)
    }

  val redis: AwsRedis = FakeAwsRedis()

  val authenticationLayer: ZLayer[AuthConfig & AwsSecretsManager, Throwable, Authentication] =
    ZLayer.fromZIO {
      for {
        authConfig <- ZIO.service[AuthConfig] // Depends on Config
        manager <- ZIO.service[AwsSecretsManager] // Depends on AwsSecretsManager
        clock <- ZIO.clock // Depends on Clock
        keyBundle <- manager.getSecretKeys
      } yield new LiveAuthentication(authConfig, clock, manager, redis, keyBundle)
    }

  // Compose the final layer
  val finalLayer: ZLayer[Any, Throwable, Authentication & AwsSecretsManager & AWSConfig & AuthConfig] =
    AppConfig.live ++ AwsSecretsManagerLayer >>> authenticationLayer ++ AwsSecretsManagerLayer ++ AppConfig.live

  // This simulates OAuth. It sets up the expected state (or wrong state for negative tests) in Redis
  // 1. userId -> Session, lifespan: max session lifespan
  // 2. sessionId -> brAccessToken, lifespan: inactivity period
  def setRedis(
                userId: String,
                session: Session,
                sessionId: String,
                accessToken: String,
                sessionTimeout: Int,
                sessionInactivityTime: Int
              ): ZIO[Any, Throwable, Unit] =
    for {
      _ <- redis.set(
        userId,
        session.toJson,
        Some(Duration.ofSeconds(sessionTimeout))
      )
      _ <- redis.set(
        sessionId,
        accessToken,
        Some(Duration.ofSeconds(sessionInactivityTime))
      )
    } yield ()

  def spec: Spec[Any, Any] = suite("AuthServiceSpec")(
    test("Simple token encoding and decoding should work (w/o rotation)") {
      for {
        authConfig <- ZIO.service[AuthConfig]
        userId = "u_test_1"
        auth <- ZIO.service[Authentication]
        accessToken <- auth.issueAccessToken(userId, List.empty[String])
        refreshToken <- auth.issueSessionToken(userId, List.empty[String])
        sessionId <- auth.issueSessionToken(userId, List.empty[String])
        session = Session(
          UserProfile("jdoe@gmail.com", None, "John Doe", None, Some("John"), Some("Doe"), userId),
          OAuthTokens(None, Some("bogus_ext_access"), Some("bogus_ext_refresh"), refreshToken)
          )
        _ <- setRedis(userId, session, sessionId, accessToken, authConfig.sessionLifespanSec, authConfig.sessionInactivitySec)
        endpoints = TestEndpoints(auth)
        request = Request.get(URL.root / "api" / "hello").addHeader(Header.Authorization.Bearer(sessionId))
        response <- endpoints.helloRoute
          .provideEnvironment(ZEnvironment(session))         // Inject the session
          .runZIO(request)   // Run the route with ZIO environment
        body <- response.body.asString
      } yield assert(response.status)(equalTo(Status.Ok)) &&
        assert(body)(equalTo("Hello, World, John!"))
    },
    test("Token decoding should fail upon session inactivity expiry") {
      for {
        authConfig <- ZIO.service[AuthConfig]
        userId = "u_test_2"
        auth <- ZIO.service[Authentication]
        accessToken <- auth.issueAccessToken(userId, List.empty[String])
        refreshToken <- auth.issueSessionToken(userId, List.empty[String])
        sessionId <- auth.issueSessionToken(userId, List.empty[String])
        session = Session(
          UserProfile("jdoe@gmail.com", None, "John Doe", None, Some("John"), Some("Doe"), userId),
          OAuthTokens(None, Some("bogus_ext_access"), Some("bogus_ext_refresh"), refreshToken)
          )
        _ <- setRedis(userId, session, sessionId, accessToken, authConfig.sessionLifespanSec, authConfig.sessionInactivitySec)
        endpoints = TestEndpoints(auth)
        _        <- TestClock.adjust( (authConfig.sessionInactivitySec+1).seconds)
        request = Request.get(URL.root / "api" / "hello").addHeader(Header.Authorization.Bearer(sessionId))
        response <- endpoints.helloRoute
          .provideEnvironment(ZEnvironment(session))         // Inject the session
          .runZIO(request)   // Run the route with ZIO environment
      } yield assert(response.status)(equalTo(Status.Unauthorized))
    },
    test("Token refresh should succeed upon token expiry and the presence of a valid session token") {
      for {
        authConfig <- ZIO.service[AuthConfig]
        userId = "u_test_3"
        auth <- ZIO.service[Authentication]
        accessToken <- auth.issueAccessToken(userId, List.empty[String])
        refreshToken <- auth.issueSessionToken(userId, List.empty[String])
        sessionId <- auth.issueSessionToken(userId, List.empty[String])
        session = Session(
          UserProfile("jdoe@gmail.com", None, "John Doe", None, Some("John"), Some("Doe"), userId),
          OAuthTokens(None, Some("bogus_ext_access"), Some("bogus_ext_refresh"), refreshToken)
          )
        _ <- setRedis(userId, session, sessionId, accessToken, authConfig.sessionLifespanSec, authConfig.sessionInactivitySec)
        endpoints = TestEndpoints(auth)

        _        <- TestClock.adjust( (authConfig.sessionInactivitySec-2).seconds)
        // Activity to keep session alive
        request1 = Request.get(URL.root / "api" / "hello").addHeader(Header.Authorization.Bearer(sessionId))
        _ <- endpoints.helloRoute
          .provideEnvironment(ZEnvironment(session))         // Inject the session
          .runZIO(request1)   // Run the route with ZIO environment
        midAccessToken <- redis.get(sessionId).someOrFail(new RuntimeException("unexpected None"))

        // Now the real request after the token has expired -- should be ok because session is still valid
        // This should force an access token refresh
        _        <- TestClock.adjust( (authConfig.sessionInactivitySec-2).seconds) // activity to keep session alive
        request2 = Request.get(URL.root / "api" / "hello").addHeader(Header.Authorization.Bearer(sessionId))
        response <- endpoints.helloRoute
          .provideEnvironment(ZEnvironment(session))         // Inject the session
          .runZIO(request2)   // Run the route with ZIO environment
        body <- response.body.asString
        newAccessToken <- redis.get(sessionId).someOrFail(new RuntimeException("unexpected None"))
      } yield assert(response.status)(equalTo(Status.Ok)) &&
        assert(body)(equalTo("Hello, World, John!")) &&
        assert(midAccessToken)(equalTo(accessToken)) &&  // first req didn't change access token
        assert(newAccessToken)(not(equalTo(accessToken))) // second request refreshed access token
    },
    test("Token refresh should fail upon token expiry and the presence of an expired session token") {
      for {
        authConfig <- ZIO.service[AuthConfig]
        userId = "u_test_4"
        auth <- ZIO.service[Authentication]
        accessToken <- auth.issueAccessToken(userId, List.empty[String])
        refreshToken <- auth.issueSessionToken(userId, List.empty[String])
        sessionId <- auth.issueSessionToken(userId, List.empty[String])
        session = Session(
          UserProfile("jdoe@gmail.com", None, "John Doe", None, Some("John"), Some("Doe"), userId),
          OAuthTokens(None, Some("bogus_ext_access"), Some("bogus_ext_refresh"), refreshToken)
          )
        _ <- setRedis(userId, session, sessionId, accessToken, authConfig.sessionLifespanSec, authConfig.sessionInactivitySec)
        endpoints = TestEndpoints(auth)

        // Burn up time by making requests so session doesn't time out due to inactivity--then one more delay to ensure
        // session ultimately times out...
        numTimes = (authConfig.sessionLifespanSec / authConfig.sessionInactivitySec)
        _ <- ZIO.replicateZIODiscard(numTimes)(
          for {
            _   <- TestClock.adjust((authConfig.sessionInactivitySec - 2).seconds)
            res <- endpoints.helloRoute
              .provideEnvironment(ZEnvironment(session))
              .runZIO(Request.get(URL.root / "api" / "hello")
                .addHeader(Header.Authorization.Bearer(sessionId)))
          } yield ()
        )
        _        <- TestClock.adjust( (authConfig.sessionInactivitySec-2).seconds)
        request = Request.get(URL.root / "api" / "hello").addHeader(Header.Authorization.Bearer(sessionId))
        response <- endpoints.helloRoute
          .provideEnvironment(ZEnvironment(session))         // Inject the session
          .runZIO(request)   // Run the route with ZIO environment
        body <- response.body.asString
      } yield assert(response.status)(equalTo(Status.Unauthorized)) &&
        assert(body)(equalTo("Session expired"))
    },
    test("Secret Key rotation works") {
      for {
        auth <- ZIO.service[Authentication]
        ver1 <- auth.getKeyBundleVersion
        _ <- auth.updateKeys()
        ver2 <- auth.getKeyBundleVersion
      } yield assert(ver2)(equalTo(ver1+1))
    },
    test("After Secret Key rotation, old tokens should work with the previous key if they are not otherwise expired (new token generated)") {
      for {
        authConfig <- ZIO.service[AuthConfig]
        userId = "u_test_5"
        auth <- ZIO.service[Authentication]
        accessToken <- auth.issueAccessToken(userId, List.empty[String])
        refreshToken <- auth.issueSessionToken(userId, List.empty[String])
        sessionId <- auth.issueSessionToken(userId, List.empty[String])
        session = Session(
          UserProfile("jdoe@gmail.com", None, "John Doe", None, Some("John"), Some("Doe"), userId),
          OAuthTokens(None, Some("bogus_ext_access"), Some("bogus_ext_refresh"), refreshToken)
          )
        _ <- setRedis(userId, session, sessionId, accessToken, authConfig.sessionLifespanSec, authConfig.sessionInactivitySec)

        // rotate secrets
        _ <- auth.updateKeys()

        endpoints = TestEndpoints(auth)
        request = Request.get(URL.root / "api" / "hello").addHeader(Header.Authorization.Bearer(sessionId))
        response <- endpoints.helloRoute
          .provideEnvironment(ZEnvironment(session))         // Inject the session
          .runZIO(request)   // Run the route with ZIO environment
        body <- response.body.asString
        newAccessToken <- redis.get(sessionId).someOrFail(new RuntimeException("unexpected None"))
      } yield assert(response.status)(equalTo(Status.Ok)) &&
        assert(body)(equalTo("Hello, World, John!")) &&
        assert(newAccessToken)(not(equalTo(accessToken)))  // ensure access token was rotated (using new key) even tho old one wasn't expired
    },
    test("Decoding roles from session id should work") {
      for {
        auth <- ZIO.service[Authentication]
        userId = "u_test_6"
        sessionId <- auth.issueSessionToken(userId, List("admin","user"))
        clock    <- ZIO.clock
        claim <- JwtToken.jwtDecode(sessionId, auth.asInstanceOf[LiveAuthentication].getKeyBundle.sessionKey.value)(clock)
        roles <- JwtToken.getRoles(claim)
      } yield assert(roles.toSet)(equalTo(Set("admin","user")))
    },
    test("Decoding a token with no defined endpoint roles should work regardless of user roles") {
      for {
        authConfig <- ZIO.service[AuthConfig]
        userId = "u_test_7"
        auth <- ZIO.service[Authentication]
        accessToken <- auth.issueAccessToken(userId, List.empty[String])
        refreshToken <- auth.issueSessionToken(userId, List.empty[String])
        sessionId <- auth.issueSessionToken(userId, List("admin"))
        session = Session(
          UserProfile("jdoe@gmail.com", None, "John Doe", None, Some("John"), Some("Doe"), userId),
          OAuthTokens(None, Some("bogus_ext_access"), Some("bogus_ext_refresh"), refreshToken)
        )
        _ <- setRedis(userId, session, sessionId, accessToken, authConfig.sessionLifespanSec, authConfig.sessionInactivitySec)
        endpoints = TestEndpoints(auth)
        request = Request.get(URL.root / "api" / "hello").addHeader(Header.Authorization.Bearer(sessionId))
        response <- endpoints.helloRoute
          .provideEnvironment(ZEnvironment(session))         // Inject the session
          .runZIO(request)   // Run the route with ZIO environment
        body <- response.body.asString
      } yield assert(response.status)(equalTo(Status.Ok)) &&
        assert(body)(equalTo("Hello, World, John!"))
    },
    test("Accessing a role-protected endpoint should succeed if user has one of those roles") {
      for {
        authConfig <- ZIO.service[AuthConfig]
        userId = "u_test_8"
        auth <- ZIO.service[Authentication]
        accessToken <- auth.issueAccessToken(userId, List.empty[String])
        refreshToken <- auth.issueSessionToken(userId, List.empty[String])
        sessionId <- auth.issueSessionToken(userId, List("admin", "foo"))
        session = Session(
          UserProfile("jdoe@gmail.com", None, "John Doe", None, Some("John"), Some("Doe"), userId),
          OAuthTokens(None, Some("bogus_ext_access"), Some("bogus_ext_refresh"), refreshToken)
        )
        _ <- setRedis(userId, session, sessionId, accessToken, authConfig.sessionLifespanSec, authConfig.sessionInactivitySec)
        endpoints = TestEndpoints(auth)
        request = Request.get(URL.root / "api" / "bye").addHeader(Header.Authorization.Bearer(sessionId))
        response <- endpoints.byeRoute
          .provideEnvironment(ZEnvironment(session))         // Inject the session
          .runZIO(request)   // Run the route with ZIO environment
        body <- response.body.asString
      } yield assert(response.status)(equalTo(Status.Ok)) &&
        assert(body)(equalTo("Goodbye, World, John!"))
    },
    test("Accessing a role-protected endpoint should fail if user doesn't have one of those roles") {
      for {
        authConfig <- ZIO.service[AuthConfig]
        userId = "u_test_9"
        auth <- ZIO.service[Authentication]
        accessToken <- auth.issueAccessToken(userId, List.empty[String])
        refreshToken <- auth.issueSessionToken(userId, List.empty[String])
        sessionId <- auth.issueSessionToken(userId, List("user"))
        session = Session(
          UserProfile("jdoe@gmail.com", None, "John Doe", None, Some("John"), Some("Doe"), userId),
          OAuthTokens(None, Some("bogus_ext_access"), Some("bogus_ext_refresh"), refreshToken)
        )
        _ <- setRedis(userId, session, sessionId, accessToken, authConfig.sessionLifespanSec, authConfig.sessionInactivitySec)
        endpoints = TestEndpoints(auth)
        request = Request.get(URL.root / "api" / "bye").addHeader(Header.Authorization.Bearer(sessionId))
        response <- endpoints.byeRoute
          .provideEnvironment(ZEnvironment(session))         // Inject the session
          .runZIO(request)   // Run the route with ZIO environment
        body <- response.body.asString
      } yield assert(response.status)(equalTo(Status.Unauthorized))
    },
    test("Accessing a role-protected endpoint should fail if user has no roles") {
      for {
        authConfig <- ZIO.service[AuthConfig]
        userId = "u_test_10"
        auth <- ZIO.service[Authentication]
        accessToken <- auth.issueAccessToken(userId, List.empty[String])
        refreshToken <- auth.issueSessionToken(userId, List.empty[String])
        sessionId <- auth.issueSessionToken(userId, List.empty[String])
        session = Session(
          UserProfile("jdoe@gmail.com", None, "John Doe", None, Some("John"), Some("Doe"), userId),
          OAuthTokens(None, Some("bogus_ext_access"), Some("bogus_ext_refresh"), refreshToken)
        )
        _ <- setRedis(userId, session, sessionId, accessToken, authConfig.sessionLifespanSec, authConfig.sessionInactivitySec)
        endpoints = TestEndpoints(auth)
        request = Request.get(URL.root / "api" / "bye").addHeader(Header.Authorization.Bearer(sessionId))
        response <- endpoints.byeRoute
          .provideEnvironment(ZEnvironment(session))         // Inject the session
          .runZIO(request)   // Run the route with ZIO environment
        body <- response.body.asString
      } yield assert(response.status)(equalTo(Status.Unauthorized))
    }
  ).provide(finalLayer ++ Runtime.removeDefaultLoggers)
}