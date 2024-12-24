package com.me
package auth

import java.time.Clock
import scala.util.Try
import zio._
import zio.http._
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim}
import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._

object Authentication:
  implicit val clock: Clock = Clock.systemUTC

  // Define a case class to parse the payload
  case class JwtPayload(sub: Option[String])
  implicit val payloadCodec: JsonValueCodec[JwtPayload] = JsonCodecMaker.make

  // Secret Authentication key. In real life this would be a secret in Github or similar
  val SECRET_KEY = "secretKey"
  // val SECRET_KEY = SecretKeyManager.getSecretKey()
  val USER_ID = "bogus_user" // In real life this would be obtained on a login web form or similar

  def jwtEncode(username: String, key: String): String =
    Jwt.encode(JwtClaim(subject = Some(username)).issuedNow.expiresIn(300), key, JwtAlgorithm.HS512)

  def jwtDecode(token: String, key: String): Try[JwtClaim] =
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

  val bearerAuthWithContext: HandlerAspect[Any, String] =
    HandlerAspect.interceptIncomingHandler(Handler.fromFunctionZIO[Request] { request =>
      request.header(Header.Authorization) match {
        case Some(Header.Authorization.Bearer(token)) =>
          ZIO
            .fromTry(jwtDecode(token.value.asString, SECRET_KEY))
            .orElseFail(Response.badRequest("Invalid or expired token!"))
            .flatMap(claim => ZIO.fromOption(extractSubjectFromPayload(claim)).orElseFail(Response.badRequest("Missing subject claim!")))
            .map(u => (request, u))

        case _ => ZIO.fail(Response.unauthorized.addHeaders(Headers(Header.WWWAuthenticate.Bearer(realm = "Access"))))
      }
    })

