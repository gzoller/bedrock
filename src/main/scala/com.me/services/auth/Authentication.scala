package com.me
package services
package auth

import java.time.Clock
import scala.util.Try
import zio.*
import zio.http.*
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim}
import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._
import java.time.Instant
import com.typesafe.config.Config


trait Authentication:
  def login(username: String, password: String): ZIO[Any, Either[GeneralFailure, BadCredentialError], String] // returns a token
  def jwtEncode(username: String, key: String): String
  def updateKeys: ZIO[Any, Throwable, Unit]
  def bearerAuthWithContext: HandlerAspect[Any, Session]


final case class LiveAuthentication(
  appConfig: Config,
  secretKeyManager: SecretKeyManager,
  @volatile private var currentSecretKey: Key,
  @volatile private var previousSecretKey: Option[Key]
) extends Authentication:

  implicit val clock: Clock = Clock.systemUTC

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
    ZIO.succeed(jwtEncode(username, currentSecretKey.value))

  // Define a case class to parse the payload
  case class JwtPayload(sub: Option[String])
  implicit val payloadCodec: JsonValueCodec[JwtPayload] = JsonCodecMaker.make

  def jwtEncode(username: String, key: String): String =
    Jwt.encode(JwtClaim(subject = Some(username)).issuedNow.expiresIn( appConfig.getInt("app.auth.token_expiration_sec") ), key, JwtAlgorithm.HS512)

  private def jwtDecode(token: String, key: String): Try[JwtClaim] =
    Jwt.decode(token, key, Seq(JwtAlgorithm.HS512))

  // Upon decoding, the subject (containing user id) is oddly stuffed into the payload as JSON
  // We must pull this out
  private def extractSubjectFromPayload(claim: JwtClaim): Option[String] = {
    try {
      // Deserialize the content field into JwtPayload
      val payload = readFromString[JwtPayload](claim.content)
      payload.sub
    } catch {
      case _: Throwable => None
    }
  }

  // Logic to attempt to decode given token with current key, and failing that try
  // using the previous key if current key was recently created (within last 5 min).
  // If we successfully used the previous key, regenerate a new token using the 
  // current key and return that token.
  private def decodeToken(rawToken: String): ZIO[Any, Response, (AuthToken, Session)] = {
    val now = Instant.now()

    // Decode using the current key
    val decodeWithCurrentKey: ZIO[Any, Response, (AuthToken, Session)] = 
      ZIO
        .fromTry(jwtDecode(rawToken, currentSecretKey.value))
        .mapError(_ => Response.unauthorized("Invalid or expired token!")) // Map Throwable to Response
        .flatMap { claim =>
          extractSubjectFromPayload(claim) match {
            case Some(subject) =>
              // Before we say we're ok, let's check the time-to-live of the token. If its inside
              // the window we'll go ahead and generate a new token and return it. (token rotation)
              claim.expiration match {
                case Some(expirationTime) =>
                  val now = Instant.now().getEpochSecond // Current time in seconds
                  if (expirationTime - now <= appConfig.getInt("app.auth.token_rotation_sec")) {
                    // Soon... rotate token with current key
                    ZIO.succeed( (Some(jwtEncode(subject, currentSecretKey.value)), Session(subject)) )
                  } else
                    // Not so soon... no need to re-gen token
                    ZIO.succeed( (None, Session(subject)) )
                case None =>
                  // Sticky... we've successfully decoded the token but it has no expiration time, which is
                  // invalid, so go ahead and re-gen a new one.
                  ZIO.succeed( (Some(jwtEncode(subject, currentSecretKey.value)), Session(subject)) )
              }
            case None =>
              ZIO.fail(Response.badRequest("Missing subject claim!"))
          }
        }

    // Decode using the previous key
    lazy val decodeWithPreviousKey: ZIO[Any, Response, (AuthToken, Session)] =
      previousSecretKey match {
        case Some(key) if currentSecretKey.instantCreated.plusSeconds( appConfig.getInt("app.auth.old_token_grandfather_period_sec") ).isAfter(now) =>
          ZIO
            .fromTry(jwtDecode(rawToken, key.value))
            .mapError(_ => Response.unauthorized("Invalid or expired token!")) // Convert Throwable to Response
            .flatMap { claim =>
              extractSubjectFromPayload(claim) match {
                case Some(subject) =>
                  val newToken = jwtEncode(subject, currentSecretKey.value) // Regenerate token
                  ZIO.succeed( (Some(newToken), Session(subject)) )
                case None =>
                  ZIO.fail(Response.badRequest("Missing subject claim!"))
              }
            }
        case _ =>
          ZIO.fail(Response.unauthorized("Invalid or expired token!"))
      }

    // Try decoding with current key first, then fallback to previous key
    decodeWithCurrentKey.orElse(decodeWithPreviousKey)
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
  def live: ZLayer[Config & SecretKeyManager, Throwable, Authentication] =
    ZLayer.fromZIO {
      for {
        manager         <- ZIO.service[SecretKeyManager]
        appConfig       <- ZIO.service[Config]
        (currentKey, previousKey) <- manager.getSecretKey
      } yield LiveAuthentication(appConfig, manager, currentKey, previousKey)
    }
