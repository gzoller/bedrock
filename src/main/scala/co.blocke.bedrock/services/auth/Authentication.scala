package co.blocke.bedrock
package services
package auth

import co.blocke.bedrock.services.auth.JwtToken.TokenError
import zio.*
import zio.http.*
import pdi.jwt.JwtClaim

import aws.{AwsSecretsManager, KeyBundle}


trait Authentication:
  def login(username: String, password: String): ZIO[Any, Either[GeneralFailure, BadCredentialError], TokenBundle] // returns a token
  def bearerAuthWithContext(roles: List[String] = List.empty[String]): HandlerAspect[Any, Session]
  def updateKeys: ZIO[Any, Throwable, Unit]
  def issueExpiredToken(expiredBySec: Long, subject: String, isSession: Boolean): ZIO[Any, Throwable, String]  // used only for integration testing
  def getKeyBundleVersion: UIO[Int]

final case class LiveAuthentication(
  authConfig: AuthConfig,
  clock: zio.Clock,
  AwsSecretsManager: AwsSecretsManager,
  @volatile private var keyBundle: KeyBundle
) extends Authentication:

  // Protected accessors for testing
  private[auth] def getKeyBundle: KeyBundle = keyBundle

  implicit val theClock: zio.Clock = clock
  private var keyBundleVersion = 0  // Used to track key bundle changes in integration testing

  def getKeyBundleVersion: UIO[Int] = ZIO.succeed(keyBundleVersion)

  def issueExpiredToken(expiredBySec: Long, subject: String, isSession: Boolean): ZIO[Any, Throwable, String] =  // used only for integration testing
    val key = if isSession then keyBundle.sessionKey.value else keyBundle.currentTokenKey.value
    JwtToken.jwtEncode(subject, key, expiredBySec * -1, List.empty[String])  // note: any roles are lost, but this is for testing only, so...

  /**
    * Update the current and previous keys with the latest keys from the secret key manager.
    */
  def updateKeys: ZIO[Any, Throwable, Unit] =
    for {
      keyBundleLive <- AwsSecretsManager.getSecretKeys
    } yield {
      keyBundleVersion += 1
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
      sessionToken <- JwtToken.jwtEncode(username, keyBundle.sessionKey.value, authConfig.sessionDurationSec, List.empty[String])
        .mapError { throwable =>
          val errorMessage = s"Failed to generate session JWT for user $username: ${throwable.getMessage}"
          GeneralFailure(errorMessage)
        }
        .tapError(error => ZIO.logError(error.message))
        .mapError(Left(_)) // Wrap the error as a `Left` to match the return type

      // Hardwired here--in real live pull thse out of a db, IAM, or something
      roles = username match {
        case "AdminUser" => List("admin") 
        case "TestUser" => List("test") 
        case _ => List.empty[String]
      }
      authToken <- JwtToken.jwtEncode(username, keyBundle.currentTokenKey.value, authConfig.tokenExpirationSec, roles)
        .mapError { throwable =>
          val errorMessage = s"Failed to generate auth JWT for user $username: ${throwable.getMessage}"
          GeneralFailure(errorMessage)
        }
        .tapError(error => ZIO.logError(error.message))
        .mapError(Left(_)) // Wrap the error as a `Left` to match the return type
    } yield TokenBundle(sessionToken, authToken)

  // See if any of the user's roles (tokenRoles) exist in the endpointRoles list. If so, return the user's roles from the token.
  private def checkPermissions(claim: JwtClaim, endpointRoles: List[String]): ZIO[Any, TokenError, List[String]] = {
    for {
      tokenRoles <- JwtToken.getRoles(claim)
      isOk       =  endpointRoles.isEmpty || tokenRoles.exists(endpointRoles.contains)
      _          <- ZIO.ifZIO(ZIO.succeed(isOk))(
                      ZIO.unit, // Pass through if true
                      ZIO.fail(TokenError.OtherProblem) // Fail if false
                    )
    } yield tokenRoles
  }

  private[auth] def decodeToken(authToken: String, sessionToken: Option[String], endpointRoles: List[String]): ZIO[Any, Response, (AuthToken, Session)] = 
    (for {
      now         <- clock.instant
      claim       <- JwtToken.jwtDecode(authToken, keyBundle.currentTokenKey.value)
      userRoles   <- checkPermissions(claim, endpointRoles)
    } yield (None, Session(claim.subject.get, userRoles)))
      .catchSome {
        case TokenError.Expired =>
          // Complexity here:  
          // If authToken is expired AND we have a session token AND the session token is not expired
          // AND the authToken's expiration is within the refresh window, we'll allow a token refresh
          sessionToken match {
            case Some(sessToken) =>
              (for {
                _                 <- JwtToken.jwtDecode(sessToken, keyBundle.sessionKey.value) // ensure session token is valid
                (claim, newToken) <- JwtToken.refreshToken(
                                        authToken, 
                                        keyBundle.currentTokenKey.value, 
                                        keyBundle.currentTokenKey.value, 
                                        authConfig.refreshWindowSec, 
                                        authConfig.tokenExpirationSec)
                userRoles         <- checkPermissions(claim, endpointRoles)
              } yield (Some(newToken), Session(claim.subject.get, userRoles)))
                .mapError(_ => ZIO.fail(TokenError.Expired)) // Fallback if session token decoding fails or any other problem
            case None =>
              ZIO.fail(TokenError.Expired) // No session token, propagate the error
          }

        case TokenError.BadSignature => // might be hacking or a Secret Key rotation--try decoding with previoius key
          keyBundle.previousTokenKey.map( prevKey =>
            (for {
              oldClaim          <- JwtToken.jwtDecode(authToken, prevKey.value)
              // Successful/validation decode with previous key, but we need to re-gen the token with the current key
              (claim, newToken) <- JwtToken.refreshToken(
                                     authToken, 
                                     prevKey.value,
                                     keyBundle.currentTokenKey.value, 
                                     authConfig.refreshWindowSec, 
                                     authConfig.tokenExpirationSec)
              userRoles         <- checkPermissions(claim, endpointRoles)
                  //.map( newToken => (Some(newToken), Session(claim.subject.get)) )
            } yield (Some(newToken), Session(claim.subject.get, userRoles)))
            .mapError{ e => 
              ZIO.logWarning("Attempt to decode token with unknown or expired secret key. "+e) *>
              ZIO.fail(TokenError.BadSignature) 
            } // Fallback if previous key decode fails or any other problem
          ).getOrElse(ZIO.fail(TokenError.BadSignature)) // No previous key, propagate the error
      }.mapError{_ =>  // Map errors to Response
        Response.unauthorized("Invalid or expired token, or lacking role permissions")
      }

  def requestInterceptH(endpointRoles: List[String]): Handler[Any, Response, Request, (AuthToken, (Request, Session))] =
    Handler.fromFunctionZIO[Request] { request =>
      request.header(Header.Authorization) match {
        case Some(Header.Authorization.Bearer(token)) =>
          decodeToken(token.value.asString, request.headers.get("X-Session-Token"), endpointRoles).flatMap { decoded =>
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

  def bearerAuthWithContext(roles: List[String]): HandlerAspect[Any, Session] = 
    HandlerAspect.interceptHandlerStateful[Any, AuthToken, Session](requestInterceptH(roles))(responseInterceptH)


object Authentication:
  def live: ZLayer[AuthConfig & AwsSecretsManager, Throwable, Authentication] =
    ZLayer.fromZIO {
      for {
        _               <- ZIO.logInfo("Authentication: Loading AwsSecretsManager")
        manager         <- ZIO.service[AwsSecretsManager]
        _               <- ZIO.logInfo("Authentication: Loading AuthConfig")
        authConfig      <- ZIO.service[AuthConfig]
        _               <- ZIO.logInfo("Authentication: Loading Clock")
        clock           <- ZIO.clock
        _               <- ZIO.logInfo("Authentication: Getting secret keys")
        keyBundle       <- manager.getSecretKeys.tapError(e => ZIO.logError("Authentication ERROR: "+e.getMessage))
        _               <- ZIO.logInfo("Authentication: Creating LiveAuthentication")
      } yield LiveAuthentication(authConfig, clock, manager, keyBundle)
    }
