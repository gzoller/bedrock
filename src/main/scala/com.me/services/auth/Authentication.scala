package com.me
package services
package auth

import java.time.Clock
import scala.util.Try
import zio.*
import zio.schema.{DeriveSchema, Schema}
import zio.http.*
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim}
import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._

case class BadCredentialError(message: String)
case class GeneralFailure(message: String)

object BadCredentialError:
  implicit val schema: Schema[BadCredentialError] = DeriveSchema.gen
object GeneralFailure:
  implicit val schema: Schema[GeneralFailure] = DeriveSchema.gen

trait Authentication:
  def login(username: String, password: String): ZIO[Any, Either[GeneralFailure, BadCredentialError], String] // returns a token
  def jwtEncode(username: String, key: String): String
  def updateKeys: ZIO[Any, Throwable, Unit]
  // def readSecretKeys: ZIO[Any, Throwable, Unit]
  // def rotateToken: ZIO[Any, Throwable, String]
  def bearerAuthWithContext: HandlerAspect[Any, String]


final case class LiveAuthentication(
  secretKeyManager: SecretKeyManager,
  @volatile private var currentSecretKey: Key,
  @volatile private var previousSecretKey: Option[Key]
)extends Authentication:

  implicit val clock: Clock = Clock.systemUTC

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
    Jwt.encode(JwtClaim(subject = Some(username)).issuedNow.expiresIn(300), key, JwtAlgorithm.HS512)

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

  def bearerAuthWithContext: HandlerAspect[Any, String] =
    HandlerAspect.interceptIncomingHandler(Handler.fromFunctionZIO[Request] { request =>
      request.header(Header.Authorization) match {
        case Some(Header.Authorization.Bearer(token)) =>
          ZIO
            .fromTry(jwtDecode(token.value.asString, currentSecretKey.value))
            .orElseFail(Response.badRequest("Invalid or expired token!"))
            .flatMap(claim => ZIO.fromOption(extractSubjectFromPayload(claim)).orElseFail(Response.badRequest("Missing subject claim!")))
            .map(u => (request, u))

        case _ => ZIO.fail(Response.unauthorized.addHeaders(Headers(Header.WWWAuthenticate.Bearer(realm = "Access"))))
      }
    })


object Authentication:
  def live: ZLayer[SecretKeyManager, Throwable, Authentication] =
    ZLayer.fromZIO {
      for {
        manager         <- ZIO.service[SecretKeyManager]
        (currentKey, previousKey) <- manager.getSecretKey
      } yield LiveAuthentication(manager, currentKey, previousKey)
    }
