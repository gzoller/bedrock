package co.blocke.bedrock
package services
package auth

import aws.AwsRedis
import model.*
import util.*

import zio.*
import zio.json.*
import zio.http.*
import zio.http.codec.*
import zio.http.endpoint.AuthType
import zio.http.endpoint.Endpoint
import java.time.Duration


// Localstack Google: Authorized redirect URIs:
// https://localhost:8073/api/oauth2/callback

// AWS Google: Authorized redirect URIs:
// https://premium-mammoth-abnormally.ngrok-free.app/api/oauth2/callback

trait OAuth2:
  def routes: Routes[Any, Response]  // callback for tokens from provider

/* 
Endpoints: 
    /api/oauth2/login_proxy  (OpenID--redirect for Web)
    /api/oauth2/login_proxy  (OpenID--GET for Mobile)
    /api/oauth2/callback     (OpenID)
 */
  
final case class LiveOAuth2(
  authConfig: AuthConfig,
  auth: Authentication,
  client: Client,
  redis: AwsRedis,
  clientId: String, 
  clientSecret: String, 
  validator: Validator
  ) extends OAuth2:


  // [==== /login Endpoint (OpenID Auth) ====]

  /**
    * This endpoint is redirected to by the Web UI to initiate the login process when using an OAuth provider.
    * It redirects to the OAuth2 provider. The purpose of this proxy is to hide knowledge of tokens, etc., from
    * the client.
    */
  private val loginProxyEndpoint: Endpoint[Unit, (String,String), String, String, AuthType.None] = Endpoint(RoutePattern.GET / "api" / "oauth2" / "login_proxy")
    .query(HttpCodec.query[String]("redirect_location"))
    .query(HttpCodec.query[String]("state"))
    .out[String](MediaType.text.plain)
    .outError[String](Status.InternalServerError)

  private val loginProxyHandler: Handler[Any, String, (String,String), String] =
    Handler.fromFunctionZIO { (redirectLocation: String, nonce: String) =>
      val stateId = UUIDutil.base64Id
      val jsEncodedState = State(stateId, authConfig.tenantPrefix, nonce, redirectLocation).toEncodedJson
      for {
        // Set the state in Redis for only 5 min--just long enough to get the callback from the provider
        _ <- redis.set(stateId, jsEncodedState, Some(5.minutes))
          .catchAll{ err =>
            ZIO.logError("Error while attempting to set value in Redis: "+err.getMessage) *>
              ZIO.fail("Unable to create session")
          }
      } yield s"${authConfig.oauthConfig.authUrl}?" +
              s"client_id=${encode(clientId)}&" + 
              s"redirect_uri=${encode(authConfig.callbackBaseUrl + "/api/oauth2/callback")}&" +
              s"""scope=${encode(authConfig.oauthConfig.scopes.mkString(" "))}&""" +
              s"state=${encode(jsEncodedState)}&" +
              "response_type=code&"
//              "access_type=offline"  // Google-specific parameter, ignored by others
              // NOTE: For Auth0/Okta, add offline_access to scope to trigger return of refresh token
      }

  private val loginProxyRoute = Routes(loginProxyEndpoint.implementHandler(loginProxyHandler)) @@ HTTPutil.redirectAspect


  private def exchangeCodeForTokens(req: Request, isCallback: Boolean): ZIO[Any, Nothing, Response] =
    // Extract query parameters to a Map[String,String]
    val qparams = req.queryParameters.map.map { case (k, v) => k -> v.mkString(",") }

    (for {
      state <- if isCallback then validator.validateParams(qparams, authConfig.oauthConfig.scopes, redis)
        .map(Some(_)) else ZIO.succeed(None)
      code <- ZIO.fromOption(qparams.get("code"))
        .orElseFail(new IllegalArgumentException("No code in callback"))
      body = Body.fromString(
        s"code=${encode(code)}" +
          "&grant_type=authorization_code" +
          s"&client_id=${encode(clientId)}" +
          s"&client_secret=${encode(clientSecret)}" +
          s"&redirect_uri=${encode(authConfig.callbackBaseUrl + "/api/oauth2/callback")}" +
          validator.tokenQueryExtraFields
      )
      // Call to exchange code for token set. This is a blocking call, in case it takes "a while"
      response <- client.batched(
        Request.post(authConfig.oauthConfig.tokenUrl, body)
          .addHeader(Header.ContentType(MediaType.application.`x-www-form-urlencoded`))
      )
      oauthTokensJS <- response.body.asString
      providerTokens <- ZIO.fromEither(oauthTokensJS.fromJson[ProviderOAuthTokens])
      userProfile <- validator.verifyIdToken(providerTokens.id_token)
      clock <- ZIO.clock
      now <- clock.instant

      // TODO: Query some DB to get user's roles for the tokens

      brAccessToken <- auth.issueAccessToken(userProfile.userId, List("user"))
      brRefreshToken <- auth.issueSessionToken(userProfile.userId, List("user"))
      sessionId <- auth.issueSessionToken(userProfile.userId, List.empty) // different from brRefreshToken for security

      // For each session we cache 2 things:
      // 1. userId -> Session, lifespan: max session lifespan
      // 2. sessionId -> brAccessToken, lifespan: inactivity period
      // If sessionId is gone (timed-out), delete the userId mapping
      // This dual-caching saves having to parse/re-encode JSON on each and every API request!
      // The sessionId encodes the userId, so even if expired, we can use it to get the Session
      // and refresh the sessionId->brAccessToken in cache
      _ <- redis.set(
        userProfile.userId,
        Session(
          userProfile,
          OAuthTokens(
            Some(now),
            Some(providerTokens.access_token),
            providerTokens.refresh_token,
            brRefreshToken
          )
        ).toJson,
        Some(Duration.ofSeconds(authConfig.sessionLifespanSec)))
      _ <- redis.set(
        sessionId,
        brAccessToken,
        Some(Duration.ofSeconds(authConfig.sessionInactivitySec)))
      redirectUrl <- if isCallback then ZIO.fromEither(URL.decode(state.get.redirectToUrl + s"?sessionId=$sessionId"))
        .map(Some(_)) else ZIO.succeed(None)
      resp = redirectUrl.map( r => Response.redirect(r) ).getOrElse(Response.text(sessionId))
    } yield resp)
      .catchAllCause { cause =>
        ZIO.succeed(handleFailure(cause))
      }


  /**
   * This endpoint is called by mobile apps after they've retrieved a code from the OAuth provider. It is a "straight call" (not a redirect)
   */
  private val loginProxyHandler_Code: Handler[Any, Nothing, Request, Response] =
    Handler.fromFunctionZIO { (req: Request) =>
      exchangeCodeForTokens(req, false)
    }

  private val loginProxyRoute_Code: Routes[Any, Response] =
    Routes(
      Method.POST / "api" / "oauth2" / "login_proxy" -> loginProxyHandler_Code
    )


  /**
   * This endpoint is called for machine-to-machine login (eg service calling a service). In this model, the caller service is
   * responsible for obtaining OAuth tokens (access + id), then calling this endpoint to exchange them for a session id.
   * Whenever the session expires the caller must re-do this "login".
   */
  private val machineLoginProxyEndpoint: Endpoint[Unit, (String,String), String, SessionDesc, AuthType.None] =
    Endpoint(RoutePattern.GET / "api" / "oauth2" / "login_proxy")
      .query(HttpCodec.query[String]("access-token"))
      .query(HttpCodec.query[String]("id-token"))
      .out[SessionDesc]
      .outError[String](Status.InternalServerError)


  private val machineLoginProxyHandler: Handler[Any, String, (String,String), SessionDesc] =
    Handler.fromFunctionZIO { case (accessToken: String, idToken: String) =>
      (for {
        _ <- validator.verifyAccessToken(accessToken)
        userProfile <- validator.verifyIdToken(idToken)
        clock <- ZIO.clock
        now <- clock.instant
        brAccessToken <- auth.issueAccessToken(userProfile.userId, List("user"))
        brRefreshToken <- auth.issueSessionToken(userProfile.userId, List("user"))
        sessionId <- auth.issueSessionToken(userProfile.userId, List.empty) // different from brRefreshToken for security
        _ <- redis.set(
          userProfile.userId,
          Session(
            userProfile,
            OAuthTokens(
              Some(now),
              Some(accessToken),
              None,
              brRefreshToken
            )
          ).toJson,
          Some(Duration.ofSeconds(authConfig.sessionLifespanSec)))
        _ <- redis.set(
          sessionId,
          brAccessToken,
          Some(Duration.ofSeconds(authConfig.sessionInactivitySec)))
      } yield SessionDesc(sessionId, authConfig.sessionLifespanSec, authConfig.sessionInactivitySec))
        .catchAll { err =>
          ZIO.logError(s"Error while attempting to login machine-to-machine: ${err.getMessage}") *>
            ZIO.fail("Unable to login machine-to-machine client")
        }      
    }


// [==== /callback Endpoint ====] (used for non-Bedrock OAuth providers)

  private val callbackHandler: Handler[Any, Nothing, Request, Response] =
    Handler.fromFunctionZIO { (req: Request) =>
      exchangeCodeForTokens(req, true)
    }

  private val callbackRoute: Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "oauth2" / "callback" -> callbackHandler
    )

  private def handleFailure(cause: Cause[Throwable | String]): Response = cause match {
    case Cause.Fail(error: Throwable, _) => 
      Response.text(s"Bad Request: ${error.getMessage}").status(Status.BadRequest)

    case Cause.Die(error: Throwable, _) =>
      Response.text(s"Internal Server Error: ${error.getMessage}").status(Status.InternalServerError)

    case Cause.Interrupt(_,_) =>
      Response.text("Request Interrupted").status(Status.RequestTimeout)

    case _ =>
      Response.text("Unexpected Error").status(Status.InternalServerError)
  }

  val routes: Routes[Any, Response] = loginProxyRoute ++ loginProxyRoute_Code ++ callbackRoute


object OAuth2:
  def live: ZLayer[AuthConfig & Client & AwsRedis & Authentication, Throwable, OAuth2] =
    val clientId = sys.env.getOrElse("OAUTH_CLIENT_ID","")
    val clientSecret = sys.env.getOrElse("OAUTH_CLIENT_SECRET","")
    ZLayer.fromZIO {
      for {
        _          <- ZIO.logInfo("OAuth2: Getting config")
        authConfig <- ZIO.service[AuthConfig]
        _          <- ZIO.logInfo("OAuth2: Getting Client")
        client     <- ZIO.service[Client]
        _          <- ZIO.logInfo("OAuth2: Getting Redis")
        redis      <- ZIO.service[AwsRedis]
        _          <- ZIO.logInfo("OAuth2: Getting Authentication")
        auth       <- ZIO.service[Authentication]
        _          <- ZIO.logInfo("OAuth2: Setting Validator")
        validator  <- Validator.apply(
                        authConfig.oauthConfig.provider, 
                        authConfig.oauthConfig.providerCertsUrl
                        )
        _          <- ZIO.logInfo("OAuth2: Creating LiveOAuth2")
      } yield LiveOAuth2(
        authConfig,
        auth,
        client,
        redis,
        clientId, 
        clientSecret, 
        validator,
        )
    }