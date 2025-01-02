package com.me
package services
package auth

import aws.AwsEnvironment
import db.BookRepo

import java.time.Clock
import scala.util.Try
import zio.*
import zio.http.*
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim}
import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._
import software.amazon.awssdk.regions.Region

trait Authentication:
  def login(username: String, password: String): ZIO[Any, Throwable, String] // returns a token
  def jwtEncode(username: String, key: String): String
  // def readSecretKeys: ZIO[Any, Throwable, Unit]
  // def rotateToken: ZIO[Any, Throwable, String]
  def bearerAuthWithContext: HandlerAspect[Any, String]


final case class LiveAuthentication(
  secretKeyManager: SecretKeyManager,
  @volatile private var currentSecretKey: Key,
  @volatile private var previousSecretKey: Option[Key]
)extends Authentication:

  implicit val clock: Clock = Clock.systemUTC

  def login(username: String, password: String): ZIO[Any, Throwable, String] =
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
