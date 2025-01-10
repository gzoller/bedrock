package co.blocke.bedrock
package services
package auth

import scala.util.Try
import zio.*
import zio.http.*
import zio.json.*
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim}
import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._
import java.time.Instant
import com.typesafe.config.Config
import java.util.Base64


trait Authentication:
  def login(username: String, password: String): ZIO[Any, Either[GeneralFailure, BadCredentialError], String] // returns a token
  def jwtEncode(username: String, key: String): UIO[String]
  def updateKeys: ZIO[Any, Throwable, Unit]
  def bearerAuthWithContext: HandlerAspect[Any, Session]


final case class LiveAuthentication(
  appConfig: Config,
  clock: zio.Clock,
  secretKeyManager: SecretKeyManager,
  @volatile private var currentSecretKey: Key,
  @volatile private var previousSecretKey: Option[Key]
) extends Authentication:

  // implicit val javaClock: java.time.Clock = ClockConverter.fromZIOClock(clock)

  // Protected accessors for testing
  private[auth] def getCurrentSecretKey: Key = currentSecretKey
  private[auth] def getPreviousSecretKey: Option[Key] = previousSecretKey

  /**
    * Update the current and previous keys with the latest keys from the secret key manager.
    */
  def updateKeys: ZIO[Any, Throwable, Unit] =
    for {
      (newCurrent, newPrevious) <- secretKeyManager.getSecretKey
      _ <- ZIO.logInfo(s"New keys loaded: ($newCurrent, ${newPrevious.getOrElse("None")})")
    } yield {
      currentSecretKey = newCurrent
      previousSecretKey = newPrevious
    }

  // The idea here is we hit a database or something to validate the credentials. Only 3 possible outcomes are allowed:
  //  1. successful -- credentials are good, in which case we return a generated token
  //  2. bad creds  -- Return a Right[BadCredentialError]
  //  3. anything else at all -- Return a Left[GeneralFailure] (log the real error of course, but just return GeneralFailure)
  def login(username: String, password: String): ZIO[Any, Either[GeneralFailure, BadCredentialError], String] =
    // ZIO.fail(Left(GeneralFailure("boom")))
    jwtEncode(username, currentSecretKey.value)

  // Define a case class to parse the payload
  case class JwtPayload(sub: Option[String])
  implicit val payloadCodec: JsonValueCodec[JwtPayload] = JsonCodecMaker.make

  def jwtEncode(username: String, key: String): UIO[String] =
    for {
      _ <- ZIO.succeed(println(">>> Encoding token with key "+key))
      now <- clock.instant.map(_.getEpochSecond) // Dynamically fetch the current time
      _ <- ZIO.succeed( println(">>> Encoded at "+now))
      claim = JwtClaim(subject = Some(username))
        .issuedAt(now)
        .expiresIn(appConfig.getInt("app.auth.token_expiration_sec"))(
          using ClockConverter.dynamicJavaClock(clock) // Use dynamic Clock here
        )
      _ <- ZIO.succeed(println(">>> Claim: "+claim + " (expires at "+Instant.ofEpochSecond(claim.expiration.get)+")"))
    } yield Jwt.encode(claim, key, JwtAlgorithm.HS512)

  private def jwtDecode(token: String, key: String): Try[JwtClaim] =
    println("<<< Attempt to decode with key "+key)    
    Jwt(ClockConverter.dynamicJavaClock(clock)).decode(token, key, Seq(JwtAlgorithm.HS512))

  //---------
  private def decodeHeader(t:String) =
    val parts = t.split("\\.") 
    if (parts.length == 3) 
      val payloadBase64 = parts(1) // Decode the payload (second part)
      val decodedPayload = new String(Base64.getUrlDecoder.decode(payloadBase64), "UTF-8")
      decodedPayload.fromJson[TokenHeader] match {
        case Right(tokenHeader) => println("=== Header: "+tokenHeader + " (created at "+Instant.ofEpochSecond(tokenHeader.iat)+")")
        case _ => println("Can't decode header")
      }


  // Logic to attempt to decode given token with current key, and failing that try
  // using the previous key if current key was recently created (within last 5 min).
  // If we successfully used the previous key, regenerate a new token using the 
  // current key and return that token.
  private[auth] def decodeToken(rawToken: String): ZIO[Any, Response, (AuthToken, Session)] = {
    // Decode using the current key
    val decodeWithCurrentKey: ZIO[Any, Response, (AuthToken, Session)] = {
      println("Trying current key "+currentSecretKey.value)
      for {
        now <- clock.instant.map(_.getEpochSecond())
        claim <- ZIO.fromTry(jwtDecode(rawToken, currentSecretKey.value))
                  .mapError{e => 
                    println("Greg Zoller")
                    println("+++Now: "+Instant.ofEpochSecond(now))
                    decodeHeader(rawToken)
                    println("Oops! Decode failed: "+e)
                    Response.unauthorized("Invalid or expired token!")
                  } // Map Throwable to Response
        result <- claim.subject match {
            // extractSubjectFromPayload(claim) match {
              case Some(subject) =>
                println("Ok... we have subject: "+subject)
                // Before we say we're ok, let's check the time-to-live of the token. If its inside
                // the window we'll go ahead and generate a new token and return it. (token rotation)
                claim.expiration match {
                  case Some(expirationTime) =>
                    for {
                      result <- {
                        if (expirationTime - now <= appConfig.getInt("app.auth.token_rotation_sec")) {
                          // Soon... rotate token with current key
                          jwtEncode(subject, currentSecretKey.value).map(token => (Some(token), Session(subject)))
                        } else
                          // Not so soon... no need to re-gen token
                          ZIO.succeed( (None, Session(subject)) )
                      }
                    } yield result
                  case None =>
                    // Sticky... we've successfully decoded the token but it has no expiration time, which is
                    // invalid, so go ahead and re-gen a new one.
                    jwtEncode(subject, currentSecretKey.value).map(token => (Some(token), Session(subject)))
                }
              case None =>
                ZIO.fail(Response.badRequest("Missing subject claim!"))
            }
      } yield result
    }

    // Decode using the previous key
    def decodeWithPreviousKey: ZIO[Any, Response, (AuthToken, Session)] =
      println("Trying previous key")
      println("Created: "+currentSecretKey.instantCreated)
      for {
        now <- clock.instant
        _ <- ZIO.succeed( println(">>> Decode w/Previous at "+now))
        result <- previousSecretKey match {
          case Some(key) if currentSecretKey.instantCreated.plusSeconds( appConfig.getInt("app.auth.old_token_grandfather_period_sec") ).isAfter(now) =>
            ZIO
              .fromTry(jwtDecode(rawToken, key.value))
              .mapError(_ => Response.unauthorized("Invalid or expired token!")) // Convert Throwable to Response
              .flatMap { _.subject match {
                  case Some(subject) =>
                    val newToken = jwtEncode(subject, currentSecretKey.value) // Regenerate token
                    newToken.map(token => (Some(token), Session(subject)))
                  case None =>
                    ZIO.fail(Response.badRequest("Missing subject claim!"))
                }
              }
          case _ =>
            ZIO.fail(Response.unauthorized("Invalid or expired token!"))
        }
      } yield result

    // Try decoding with the current key first, then fallback to previous key with retry logic
    decodeWithCurrentKey.orElse(decodeWithPreviousKey).tapError{ error =>
      // If decoding token was unsuccessful, test for the improbable (but possible) case where a server failed to get the 
      // secret key rotation message and therefore does not have a key to decode the token. We want to log these events
      // for visibility. If they are too frequent, we'll need to add logic here to re-acquire a fresh key in some window
      // when we can't decode a token.

      Try{
        val parts = rawToken.split("\\.") 
        if (parts.length == 3) 
          val payloadBase64 = parts(1) // Decode the payload (second part)
          val decodedPayload = new String(Base64.getUrlDecoder.decode(payloadBase64), "UTF-8")
          decodedPayload.fromJson[TokenHeader] match {
            case Right(tokenHeader) =>
              for {
                now <- clock.instant
                result <- {
                  val duration = java.time.Duration.between(Instant.ofEpochSecond(tokenHeader.iat), now)
                  val isWithinExpiry: Boolean = Math.abs(duration.getSeconds) <= appConfig.getInt("app.auth.token_expiration_sec")
                  if isWithinExpiry then
                    ZIO.logWarning("Attempted to decode token with current and previous keys, but both failed. Token is within token expiry window. Possible server didn't get rotate_key message.")
                  else
                    ZIO.none
                }
               } yield result
            case Left(_) => 
              ZIO.logWarning("Bad token (can't parse header, iat), significant error or hacking attempt")
          }
        else 
          ZIO.none // else we don't care--invalid token, no need log anything        
      }.getOrElse(ZIO.none) // Do nothing further
    }
  }

  val requestInterceptH: Handler[Any, Response, Request, (AuthToken, (Request, Session))] =
    Handler.fromFunctionZIO[Request] { request =>
      request.header(Header.Authorization) match {
        case Some(Header.Authorization.Bearer(token)) =>
          decodeToken(token.value.asString).flatMap { decoded =>
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
  def live: ZLayer[Config & Clock & SecretKeyManager, Throwable, Authentication] =
    ZLayer.fromZIO {
      for {
        manager         <- ZIO.service[SecretKeyManager]
        appConfig       <- ZIO.service[Config]
        clock           <- ZIO.service[zio.Clock]
        (currentKey, previousKey) <- manager.getSecretKey
      } yield LiveAuthentication(appConfig, clock, manager, currentKey, previousKey)
    }
