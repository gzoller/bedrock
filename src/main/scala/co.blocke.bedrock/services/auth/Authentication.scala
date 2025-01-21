package co.blocke.bedrock
package services
package auth

import co.blocke.bedrock.services.auth.JwtToken.TokenError
import zio.*
import zio.http.*


trait Authentication:
  def login(username: String, password: String): ZIO[Any, Either[GeneralFailure, BadCredentialError], TokenBundle] // returns a token
  def updateKeys: ZIO[Any, Throwable, Unit]
  def bearerAuthWithContext: HandlerAspect[Any, Session]


final case class LiveAuthentication(
  authConfig: AuthConfig,
  clock: zio.Clock,
  secretKeyManager: SecretKeyManager,
  @volatile private var keyBundle: KeyBundle
) extends Authentication:

  // Protected accessors for testing
  private[auth] def getKeyBundle: KeyBundle = keyBundle

  implicit val theClock: zio.Clock = clock

  /**
    * Update the current and previous keys with the latest keys from the secret key manager.
    */
  def updateKeys: ZIO[Any, Throwable, Unit] =
    for {
      keyBundleLive <- secretKeyManager.getSecretKey
    } yield {
      keyBundle = keyBundleLive
    }

  /**
    * Login function to authenticate a user and return a a TokenBundle containing:
    *  - current auth token
    *  - a session token (used to refresh auth tokens, which expire frequently)
    *
    * The idea here is we hit a database or something to validate the credentials. Only 3 possible outcomes are allowed:
    *  1. successful -- credentials are good, in which case we return a TokenBundle
    *  2. bad creds  -- Return a Right[BadCredentialError]
    *  3. anything else at all -- Return a Left[GeneralFailure] (log the real error of course, but just return GeneralFailure)
    */
  def login(username: String, password: String): ZIO[Any, Either[GeneralFailure, BadCredentialError], TokenBundle] =
    for {
      sessionToken <- JwtToken.jwtEncode(username, keyBundle.sessionKey.value, authConfig.sessionDurationSec)
        .mapError { throwable =>
          val errorMessage = s"Failed to generate session JWT for user $username: ${throwable.getMessage}"
          GeneralFailure(errorMessage)
        }
        .tapError(error => ZIO.logError(error.message))
        .mapError(Left(_)) // Wrap the error as a `Left` to match the return type

      authToken <- JwtToken.jwtEncode(username, keyBundle.currentTokenKey.value, authConfig.tokenExpirationSec)
        .mapError { throwable =>
          val errorMessage = s"Failed to generate auth JWT for user $username: ${throwable.getMessage}"
          GeneralFailure(errorMessage)
        }
        .tapError(error => ZIO.logError(error.message))
        .mapError(Left(_)) // Wrap the error as a `Left` to match the return type
    } yield TokenBundle(sessionToken, authToken)

  private[auth] def decodeToken(authToken: String, sessionToken: Option[String]): ZIO[Any, Response, (AuthToken, Session)] = {
    for {
      now <- clock.instant
      outcome <- JwtToken
        .jwtDecode(authToken, keyBundle.currentTokenKey.value)
        .map( claim => (None, Session(claim.subject.get)) )
        .catchSome {
          case TokenError.Expired =>
            // Complexity here:  
            // If authToken is expired AND we have a session token AND the session token is not expired
            // AND the authToken's expiration is within the refresh window, we'll allow a token refresh
            sessionToken match {
              case Some(sessToken) =>
                JwtToken.jwtDecode(sessToken, keyBundle.sessionKey.value).flatMap { _ =>
                  JwtToken.refreshToken(authToken, keyBundle.currentTokenKey.value, keyBundle.currentTokenKey.value, authConfig.refreshWindowSec, authConfig.tokenExpirationSec)
                    .flatMap{ newToken => 
                      JwtToken.jwtDecode(newToken, keyBundle.currentTokenKey.value)
                      .map( claim => (Some(newToken), Session(claim.subject.get)) )
                    }
                }.orElse(ZIO.fail(TokenError.Expired)) // Fallback if session token decoding fails
              case None =>
                ZIO.fail(TokenError.Expired) // No session token, propagate the error
            }

          case TokenError.BadSignature => // might be hacking or a Secret Key rotation--try decoding with previoius key
            keyBundle.previousTokenKey.map( prevKey =>
              JwtToken.jwtDecode(authToken, prevKey.value)
                .flatMap{ claim => 
                  // Successful/validation decode with previous key, but we need to re-gen the token with the current key
                  JwtToken.refreshToken(authToken, prevKey.value, keyBundle.currentTokenKey.value, authConfig.refreshWindowSec, authConfig.tokenExpirationSec)
                    .map( newToken => (Some(newToken), Session(claim.subject.get)) )
                }
                .orElse{
                  ZIO.logWarning("Attempt to decode token with unknown or expired secret key.") *>
                  ZIO.fail(TokenError.BadSignature)
                } // Fallback if previous key decode fails
            ).getOrElse(ZIO.fail(TokenError.BadSignature)) // No previous key, propagate the error
          }.mapError(_ => Response.unauthorized("Invalid or expired token!")) // Map errors to Response
    } yield outcome
  }

  val requestInterceptH: Handler[Any, Response, Request, (AuthToken, (Request, Session))] =
    Handler.fromFunctionZIO[Request] { request =>
      request.header(Header.Authorization) match {
        case Some(Header.Authorization.Bearer(token)) =>
          decodeToken(token.value.asString, request.headers.get("X-Session-Token")).flatMap { decoded =>
            val (authToken, session) = decoded
            ZIO.succeed((authToken, (request, session)))
          }
        case _ =>
          ZIO.fail(
            Response.unauthorized.addHeaders(
                Headers(Header.WWWAuthenticate.Bearer(realm = "Access"))
            )
          )
        }
    }

  /**
    * If our logic has determined that a new bearer token is needed, it will be generated and passed
    * to this intercepte handler. If set, the handler will populate the Authorization header in
    * the Response. The caller is responsible for monitoring this header and resetting their token
    * for subsequent token-protected API calls.
    */
  val responseInterceptH: Handler[Any, Nothing, (AuthToken, Response), Response] =
    Handler.fromFunctionZIO { case (authToken, response) =>
      ZIO.succeed(
        authToken
          .map( newToken => response.addHeader(Header.Authorization.Bearer(newToken)))
          .getOrElse(response)
      )
    }

  def bearerAuthWithContext: HandlerAspect[Any, Session] = 
    HandlerAspect.interceptHandlerStateful[Any, AuthToken, Session](requestInterceptH)(responseInterceptH)


object Authentication:
  def live: ZLayer[AuthConfig & SecretKeyManager, Throwable, Authentication] =
    ZLayer.fromZIO {
      for {
        manager         <- ZIO.service[SecretKeyManager]
        authConfig      <- ZIO.service[AuthConfig]
        clock           <- ZIO.clock
        keyBundle       <- manager.getSecretKey
      } yield LiveAuthentication(authConfig, clock, manager, keyBundle)
    }
