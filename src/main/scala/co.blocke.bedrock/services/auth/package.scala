package co.blocke.bedrock
package services
package auth

import java.time.*

import zio.*
import zio.json.*
import zio.schema.DeriveSchema
import zio.schema.Schema

//-------------------
// Types

opaque type AccessToken = String
opaque type RefreshToken = String
opaque type IDToken = String


//-------------------
// Auth Errors

case class BadCredentialError(message: String)
case class GeneralFailure(message: String)

object BadCredentialError:
  implicit val schema: Schema[BadCredentialError] = DeriveSchema.gen
object GeneralFailure:
  implicit val schema: Schema[GeneralFailure] = DeriveSchema.gen


//-------------------
// Tokens (JWT Tokens)

type AuthToken = Option[String]
case class TokenHeader(sub: String, iat: Long)
object TokenHeader:
  implicit val codec: JsonCodec[TokenHeader] = DeriveJsonCodec.gen[TokenHeader]

case class TokenBundle(sessionToken: String, authToken: String)
object TokenBundle:
  implicit val schema: Schema[TokenBundle] = DeriveSchema.gen[TokenBundle]
  implicit val codec: JsonCodec[TokenBundle] = DeriveJsonCodec.gen[TokenBundle]

//-------------------
// Session (placeholder--real session would be reified from JWT token subject, eg id, from a db or cache

case class Session(userId: String, roles: List[String])  // This is the payload of the JWT token and can contain other things like roles, etc.

// For testability, everything runs on ZIO Clock, so it can be manipulated in tests
// by TestClock.  However, the JWT library uses Java Clock.  This is a conversion utility
// to convert ZIO Clock to Java Clock for the JWT library.
object ClockConverter {
  def dynamicJavaClock(clock: zio.Clock): java.time.Clock = new java.time.Clock {
    override def getZone: ZoneId = ZoneId.systemDefault()
    override def withZone(zone: ZoneId): java.time.Clock = this
    override def instant(): Instant = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(clock.instant).getOrThrowFiberFailure()
    }
  }
}