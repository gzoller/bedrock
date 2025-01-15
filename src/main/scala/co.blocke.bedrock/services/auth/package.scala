package co.blocke.bedrock
package services
package auth

import java.time.*

import zio.*
import zio.json.*
import zio.schema.DeriveSchema
import zio.schema.Schema

case class BadCredentialError(message: String)
case class GeneralFailure(message: String)

object BadCredentialError:
  implicit val schema: Schema[BadCredentialError] = DeriveSchema.gen
object GeneralFailure:
  implicit val schema: Schema[GeneralFailure] = DeriveSchema.gen

type AuthToken = Option[String]

case class Session(userId: String)  // This is the payload of the JWT token and can contain other things like roles, etc.

case class Key(version: String, value: String, instantCreated: Instant)

case class KeyBundle( currentTokenKey: Key, previousTokenKey: Option[Key], sessionKey: Key )

case class TokenHeader(sub: String, iat: Long)
object TokenHeader:
  implicit val codec: JsonCodec[TokenHeader] = DeriveJsonCodec.gen[TokenHeader]

case class TokenBundle(sessionToken: String, authToken: String)
object TokenBundle:
  implicit val schema: Schema[TokenBundle] = DeriveSchema.gen[TokenBundle]
  implicit val codec: JsonCodec[TokenBundle] = DeriveJsonCodec.gen[TokenBundle]

// Convert between ZIO Clock (for testability) to Java Clock (for JWT library)
object ClockConverter {
  def dynamicJavaClock(clock: zio.Clock): java.time.Clock = new java.time.Clock {
    override def getZone: ZoneId = ZoneId.systemDefault()
    override def withZone(zone: ZoneId): java.time.Clock = this
    override def instant(): Instant = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(clock.instant).getOrThrowFiberFailure()
    }
  }
}