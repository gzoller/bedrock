package co.blocke.bedrock
package services
package auth

import zio.*
import zio.http.*
import zio.http.codec.*
import zio.http.endpoint.AuthType
import zio.http.endpoint.Endpoint

import java.net.URLEncoder
import java.nio.charset.StandardCharsets


// This class is a (hopefully) generic OAuth 2.0 client for Bedrock. It is using Google's implementation.

/* 
Flow:  
    
    0. We want to protect as much info as possible, so front end redirects to a /login endpoint on the server.
       The front end will pass the final redirect URL (where to go after login) as a query parameter

    1. Server will get clientId, redirect_uri, scope, and state, then redirect to Provider (eg Google) for login.  
      For Google (and most OpenID) the call will look like this:

    https://accounts.google.com/o/oauth2/v2/auth?
        client_id=YOUR_CLIENT_ID&
        redirect_uri=https://your-backend.com/oauth2/callback&
        response_type=code&
        scope=email profile openid offline_access&
        state=xyz123

    https://accounts.google.com/o/oauth2/v2/auth?
        client_id=593880049906-71491lin36bpaasbm6qu61v4cmn0fvrk.apps.googleusercontent.com&
        redirect_uri=https://localhost:8073/callback&
        response_type=code&
        scope=email profile openid offline_access&
        state=eyJ0ZW5hbnRQcmVmaXgiOiJiZWRyb2NrIiwibm9uY2UiOiJVRlVDR1FBTlpEV0JYWlFJIiwicmVkaXJlY3RUb1VybCI6ImZpbGU6Ly8vVXNlcnMvZ3pvbGxlci9tZS9naXQvYmVkcm9jay93ZWIvbWFpbi5odG1sIn0=       

    (state is a random string to prevent CSRF)
    (offline_access ensures a refresh token is returned--this should be configurable... certain apps may not need/want refresh if they're short-term)

    2. Provider will redirect back to your backend with a code: https://your-backend.com/oauth2/callback?code=4/0AX4Xf...&state=xyz123

    if user denies consent or other problem redirect looks like: https://your-backend.com/oauth2/callback?error=access_denied&state=xyz123

    3. Now we exchange the code for an access token. Call this url: https://oauth2.googleapis.com/token
       and provide the following as application/x-www-form-urlencoded in the body:

    Parameter	    Description
    code   	        The authorization code received in the redirect from Google.
    client_id	    Your app’s Client ID from the Google Cloud Console.
    client_secret	Your app’s Client Secret from the Google Cloud Console.
    redirect_uri    The same redirect URI you specified when you initiated the authorization request.
    grant_type      Must be authorization_code for this step.

    For example: (plaintext)
    code=4/0AX4XfWhExampleCode&
    client_id=your-client-id.apps.googleusercontent.com&
    client_secret=your-client-secret&
    redirect_uri=https://your-backend.com/oauth2/callback&
    grant_type=authorization_code

    Google sends back: 
        {
          "access_token": "ya29.a0AfH6SMD....",
          "expires_in": 3599,
          "refresh_token": "1//04iGaa6O...",
          "scope": "https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/userinfo.profile",
          "token_type": "Bearer",
          "id_token": "eyJhbGciOiJSUzI1NiIsInR5c..."
        }
    
    4. Verify the tokens:
        - Check signature of the tokens, Google's certs here: https://www.googleapis.com/oauth2/v3/certs
        - Check issuer (must be https://accounts.google.com for Google)
        - Check audience (must be your client ID)
        - Check Expiration (ensure its not expired)
        - Check scopes (ensure they are what you expect/need)

        Sample code in ZIO:
            def validateAccessToken(token: String): ZIO[Any, Throwable, JwtClaim] = {
                for {
                    keys <- ZIO.attempt {
                    val jwkSet = JwkSet.loadFrom("https://www.googleapis.com/oauth2/v3/certs")
                    jwkSet.keys
                    }
                    claim <- JwtToken.jwtDecode(token, keys)
                    _ <- ZIO.fail(new RuntimeException("Token expired")).when(claim.isExpired)
                    _ <- ZIO.fail(new RuntimeException("Invalid issuer")).when(claim.issuer != Some("https://accounts.google.com"))
                    _ <- ZIO.fail(new RuntimeException("Invalid audience")).when(claim.audience != Some("your-client-id.apps.googleusercontent.com"))
                } yield claim
            }

        If access token is expired, return 401 Unauthorized. Client then can use refresh token to get a new access token.

    5. To refresh an expired access token, call: https://oauth2.googleapis.com/token with the following as application/x-www-form-urlencoded in the body:

            Parameter       Description
            client_id       Your app’s Client ID from the Google Cloud Console.
            client_secret   Your app’s Client Secret from the Google Cloud Console.
            refresh_token   The refresh token obtained during the initial authorization flow.
            grant_type      Must be refresh_token for this exchange.

            Response: 
                {
                "access_token": "ya29.a0AfH6SM...",
                "expires_in": 3599,
                "scope": "openid email profile",
                "token_type": "Bearer",
                "id_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9..."
                }             

            or error:
                {
                "error": "invalid_grant",
                "error_description": "Token has been expired or revoked."
                }   

                possible error values: invalid_grant (bad refresh token), invalid_client (bad client ID or secret), unsupported_grant_type (bad grant type)

    6. To get the user's ID info: it's all encoded in the JWT id token
 */

trait OAuth2:
  def routes: Routes[Any, Response]  // callback for tokens from provider
  // def refreshAccessToken(refreshToken: RefreshToken): ZIO[Any, Throwable, AccessToken] // refresh token
  // def getTokens(code: String, scopes: List[String]): ZIO[Any, Throwable, (AccessToken, IDToken, Option[RefreshToken])] // get auth token from provider
  // def getIdData(idToken: IDToken): ZIO[Any, Throwable, Map[String,String]] // get user data from ID token

/* 
Endpoints: 
    /login      (Bedrock Auth)
    /callback   (OpenID)
    /userinfo   (OpenID)  (use server-generated session token to retrieve user info)
 */
  
final case class LiveOAuth2(
  clientId: String, 
  clientSecret: String, 
  tenantPrefix: String, 
  callbackBaseUrl: String,
  oauthConfig: OAuthConfig) extends OAuth2: 

  val validator = Validator(oauthConfig.provider, oauthConfig.scopes)

  // [==== Redirect HandlerAspect ====]

  val redirectAspect: HandlerAspect[Any, Unit] =
    HandlerAspect.interceptOutgoingHandler(
      Handler.fromFunctionZIO { (response: Response) =>
        response.body.asString.either.flatMap { // Convert failure to Either
          case Left(error) =>
            ZIO.succeed(Response.text(s"Failed to read response body: ${error.getMessage}").status(Status.InternalServerError))
          
          case Right(rawUrl) =>
            URL.decode(rawUrl) match {
              case Right(url) => ZIO.succeed(Response.redirect(url))
              case Left(e)    => ZIO.succeed(Response.text(s"Invalid redirect URL: $rawUrl").status(Status.BadRequest))
            }
        }
      }
    )

  // [==== /login Endpoint ====]

  val loginProxyEndpoint: Endpoint[Unit, (String,String), ZNothing, String, AuthType.None] = Endpoint(RoutePattern.GET / "login_proxy")
    .query(HttpCodec.query[String]("redirect_location"))
    .query(HttpCodec.query[String]("state"))
    .out[String](MediaType.text.plain)

  val loginProxyHandler: Handler[Any, Nothing, (String,String), String] = handler { (redirectLocation: String, initialState: String) =>
    val sessionId = "session:state:"+UUIDUtil.base64Id
    val rawEncodedState = State(sessionId, tenantPrefix, initialState, redirectLocation).encode
    FakeSessionCache.put(sessionId, rawEncodedState)

    val redirectUri = URLEncoder.encode(callbackBaseUrl + "/oauth2/callback", StandardCharsets.UTF_8.toString)
    val scope = URLEncoder.encode(oauthConfig.scopes.mkString(" "), StandardCharsets.UTF_8.toString)
    val encodedState = URLEncoder.encode(rawEncodedState, StandardCharsets.UTF_8.toString)
    val encodedClientId = URLEncoder.encode(clientId, StandardCharsets.UTF_8.toString)

    s"${oauthConfig.authUrl}?client_id=$encodedClientId&access_type=offline&redirect_uri=$redirectUri&response_type=code&scope=$scope&state=$encodedState"
  }

  val loginProxyRoute: Routes[Any, Nothing] = Routes(loginProxyEndpoint.implementHandler(loginProxyHandler)) @@ redirectAspect


  // [==== /callback Endpoint ====]

  val callbackEndpoint: Endpoint[Unit, Map[String, String], ZNothing, String, AuthType.None] = 
    Endpoint(RoutePattern.GET / "oauth2" / "callback")
      .query(HttpCodec.queryAll[Map[String,String]]) // Captures all query parameters dynamically
      .out[String](MediaType.text.plain)

  val callbackHandler: Handler[Any, Nothing, Map[String,String], String] = handler { (qparams: Map[String,String]) =>
    Handler.fromFunctionZIO { _ =>
      for {
        _         <- validator.validateParams(qparams, oauthConfig.scopes)
        code      <- ZIO.fromOption(qparams.get("code")).orElseFail(new RuntimeException("No code in callback"))
        body =    Body.fromString(
                    s"code=$code&client_id=$clientId&client_secret=$clientSecret&redirect_uri=$callbackBaseUrl/oauth2/callback&grant_type=authorization_code"
                  )
        response  <- Client.batched(Request.post(oauthConfig.tokenUrl, body)
                       .addHeader(Header.ContentType(MediaType.application.`x-www-form-urlencoded`)))
        respBody  <- response.body.asString
        _ <- ZIO.succeed(println("GOOGLE RESPONSE (tokens): "+body))
      } yield code
    }

    /* 
    After receiving this request:

Verify the state parameter (to prevent CSRF attacks).
Extract the code parameter.
Exchange the code for an access token by making a POST request to Google.

POST https://oauth2.googleapis.com/token
Content-Type: application/x-www-form-urlencoded

client_id=YOUR_CLIENT_ID
&client_secret=YOUR_CLIENT_SECRET
&code=4/0ASVgi3JZ64yiwnCckEHIEUXOTK0D5z8S63CqT6Ag-6zI00Lxwf_8pPK4bB_jRfo6d026Kw
&redirect_uri=https://localhost:8073/oauth2/callback
&grant_type=authorization_code

Response:
{
  "access_token": "ya29.a0AfH6SM...",
  "expires_in": 3600,
  "refresh_token": "1//0g...",
  "scope": "email profile openid",
  "token_type": "Bearer",
  "id_token": "eyJhbGciOiJIUzI1NiIs..."
}
     */
    "Hello!"
  }

  val callbackRoute: Routes[Any, Nothing] = Routes(callbackEndpoint.implementHandler(callbackHandler))// @@ redirectAspect

  val routes: Routes[Any, Nothing] = loginProxyRoute ++ callbackRoute


object OAuth2:
  def live: ZLayer[AuthConfig, Throwable, OAuth2] =
    val clientId = sys.env.getOrElse("OAUTH_CLIENT_ID","")
    val clientSecret = sys.env.getOrElse("OAUTH_CLIENT_SECRET","")
    ZLayer.fromZIO {
      for {
        authConfig <- ZIO.service[AuthConfig]
      } yield LiveOAuth2(
        clientId, 
        clientSecret, 
        authConfig.tenantPrefix,
        authConfig.callbackBaseUrl,
        authConfig.oauthConfig
        )
    }


    /* 
    
    So we have different needs/use cases here.

    1. Highly secure: needs very short (a few min) access token TTL, and a refresh token
    2. "Commerce-grade": (eg single-page app) 1 hr Google access token and no refresh token
    3. Mobile-ready (incl long-runnign web apps): 1 day Google access token with refresh token to re-auth w/o user login
     */