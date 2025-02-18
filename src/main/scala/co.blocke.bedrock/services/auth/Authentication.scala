package co.blocke.bedrock
package services
package auth

import aws.{AwsRedis, AwsSecretsManager, KeyBundle}
import model.*
import zio.*
import zio.json.*
import zio.http.*

import java.time.Duration

/** 
 * Meat 'n potatoes of the authentication service. These operations are outside OAuth. For example managing the momentary
 * instability when secret keys rotate, the mechanics of issuing tokens, etc., and providing the HandlerAspect use to
 * protect endpoints with JWT tokens
 */

trait Authentication:
//  def login(username: String, password: String): ZIO[Any, Either[GeneralFailure, BadCredentialError], TokenBundle] // returns a token
  def bedrockProtected(roles: List[String] = List.empty[String]): HandlerAspect[Any, String]
  def updateKeys(): ZIO[Any, Throwable, Unit]
  def getKeyBundleVersion: UIO[Int]
  def getSession(userId: String): ZIO[Any, AuthError, Session]

  def issueExpiredToken(expiredBySec: Long, subject: String, isSession: Boolean): ZIO[Any, Throwable, String]  // used only for integration testing
  def issueAccessToken(userId: String, roles: List[String]): ZIO[Any, Throwable, String]
  def issueSessionToken(userId: String, roles: List[String]): ZIO[Any, Throwable, String]


final case class LiveAuthentication(
  authConfig: AuthConfig,
  clock: zio.Clock,
  AwsSecretsManager: AwsSecretsManager,
  redis: AwsRedis,
  @volatile private var keyBundle: KeyBundle
) extends Authentication:

  // Protected accessors for testing
  private[auth] def getKeyBundle: KeyBundle = keyBundle

  implicit val theClock: zio.Clock = clock
  private var keyBundleVersion = 0  // Used to track key bundle changes in integration testing

  def getKeyBundleVersion: UIO[Int] = ZIO.succeed(keyBundleVersion)

  // (Used only for integration testing)
  def issueExpiredToken(expiredBySec: Long, subject: String, isSession: Boolean): ZIO[Any, Throwable, String] =  
    val key = if isSession then keyBundle.sessionKey.value else keyBundle.currentTokenKey.value
    JwtToken.jwtEncode(subject, key, expiredBySec * -1, List.empty[String])  // note: any roles are lost, but this is for testing only, so...

  def issueAccessToken(userId: String, roles: List[String]): ZIO[Any, Throwable, String] =
    JwtToken.jwtEncode(
      userId, 
      keyBundle.currentTokenKey.value, 
      authConfig.tokenExpirationSec, 
      roles
      )

  def issueSessionToken(userId: String, roles: List[String]): ZIO[Any, Throwable, String] =
    JwtToken.jwtEncode(
      userId, 
      keyBundle.sessionKey.value, 
      authConfig.sessionLifespanSec,
      roles
      )

  /**
    * Update the current and previous keys with the latest keys from the secret key manager.
    */
  def updateKeys(): ZIO[Any, Throwable, Unit] =
    for {
      keyBundleLive <- AwsSecretsManager.getSecretKeys
    } yield {
      keyBundleVersion += 1
      keyBundle = keyBundleLive
    }


  def getSession(userId: String): ZIO[Any, AuthError, Session] =
    for {
      // Marshal Session object from cache
      sessionJS <- redis.get(userId)
        .mapError(e => GeneralFailure(s"Failed to retrieve session from Redis: ${e.getMessage}"))
        .someOrFail(SessionExpired("Session expired"))
      session <- ZIO.fromEither(sessionJS.fromJson[Session])
        .mapError(e => GeneralFailure(s"Cannot parse session JSON: $e"))
    } yield session


  private def resolveToken(sessionId: String): ZIO[Any,AuthError, (String, String, List[String])] =
    for {
      // Ensure session has not expired
      sessionClaim <- JwtToken.jwtDecode(sessionId, keyBundle.sessionKey.value)
        .mapError {
          case JwtToken.TokenError.Expired => SessionExpired("Session expired")
          case _ => GeneralFailure("Non-expiration session error (couldn't decode session token)")
        }
      userId <- ZIO.fromOption(sessionClaim.subject)
        .orElseFail(GeneralFailure("Bad session id token"))
      roles <- JwtToken.getRoles(sessionClaim)
        .mapError {
          case JwtToken.TokenError.Expired => SessionExpired("Session expired")
          case _ => GeneralFailure("Non-expiration session error (couldn't decode session token)")
        }

      // Ensure sessionId->access_token mapping is still in cache (if not, period of inactivity has invalidated session)
      accessToken <- redis.getDel(sessionId)
        .mapError(e => GeneralFailure(s"Failed to get value from redis: ${e.getMessage}"))
        .someOrFail(SessionExpired("Session inactive and expired"))

      // Determine if accessToken has expired, and if so, refresh it
      refreshedToken <- JwtToken.jwtDecode(accessToken, keyBundle.currentTokenKey.value)
        .map( _ => accessToken ) // decode worked--keep original token
        .catchAll{ _ =>
          for {
            // Marshal Session object from cache
            session <- getSession(userId)

            // Attempt to refresh token using session data
            newToken <- JwtToken.refreshAccessToken(
              accessToken,
              session.oauthTokens.bedrockRefreshToken,
              keyBundle,
              authConfig.sessionLifespanSec,
              authConfig.tokenExpirationSec
            ).mapError(_ => GeneralFailure("Unable to refresh access token using refresh token"))
          } yield newToken
        }
//      _ <- ZIO.succeed(println(s">>> Orig token: $accessToken"))
//      _ <- ZIO.succeed(println(s">>>  New token: $refreshedToken"))

      // Restore sessionId->access_token mapping
      _ <- redis.set(sessionId, refreshedToken, Some(Duration.ofSeconds(authConfig.sessionInactivitySec)))
             .mapError(e => GeneralFailure(s"Failed to set value in redis: ${e.getMessage}"))
    } yield (refreshedToken, userId, roles)

  /**
   * Mix this into your endpoint (@@) to protect it with JWT tokens.  The endpoint will be protected by the roles. 
   * This aspect looks up the session and adds the Bedrock access token into the request context.
   */
  def bedrockProtected(endpointRoles: List[String]): HandlerAspect[Any, String] =
    HandlerAspect.interceptIncomingHandler {
      Handler.fromFunctionZIO[Request] { request =>
        request.header(Header.Authorization) match {
          case Some(Header.Authorization.Bearer(sessionId)) =>
            (for {
              (resolvedToken, userId, roles) <- resolveToken(sessionId.value.asString)
              // check role permissions
              _ <- if endpointRoles.isEmpty || roles.exists(endpointRoles.contains) then
                ZIO.succeed(()) else ZIO.fail(BadCredentialError("role mismatch"))
              newRequest = if (resolvedToken != sessionId.value.asString) request.addHeader(Header.Authorization.Bearer(resolvedToken)) else request
            } yield (newRequest, userId))
              .tapError {
                case e: GeneralFailure => ZIO.logError(e.message)
                case _ => ZIO.unit
              }
              .mapError{
                case e: SessionExpired => Response.text(e.message).status(Status.Unauthorized)
                case e: BadCredentialError => Response.text(e.message).status(Status.Unauthorized)
                case e: GeneralFailure => Response.text(e.message).status(Status.InternalServerError)
              }
          case _ =>
            ZIO.fail(
              Response.unauthorized.addHeaders(
                  Headers(Header.WWWAuthenticate.Bearer(realm = "Access"))
              )
            )
          }
        }
    }


object Authentication:
  def live: ZLayer[AuthConfig & AwsSecretsManager & AwsRedis, Throwable, Authentication] =
    ZLayer.fromZIO {
      for {
        _               <- ZIO.logInfo("Authentication: Loading AwsSecretsManager")
        manager         <- ZIO.service[AwsSecretsManager]
        _               <- ZIO.logInfo("Authentication: Loading AuthConfig")
        authConfig      <- ZIO.service[AuthConfig]
        _               <- ZIO.logInfo("Authentication: Loading Redis")
        redis           <- ZIO.service[AwsRedis]
        _               <- ZIO.logInfo("Authentication: Loading Clock")
        clock           <- ZIO.clock
        _               <- ZIO.logInfo("Authentication: Getting secret keys")
        keyBundle       <- manager.getSecretKeys.tapError(e => ZIO.logError("Authentication ERROR: "+e.getMessage))
        _               <- ZIO.logInfo("Authentication: Creating LiveAuthentication")
      } yield LiveAuthentication(authConfig, clock, manager, redis, keyBundle)
    }
