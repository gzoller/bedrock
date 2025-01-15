package co.blocke.bedrock
package services
package auth

import pdi.jwt.*
import zio.*
import zio.json.*


case class JwtPayload(sub: Option[String])
object JwtPayload {
  implicit val decoder: JsonDecoder[JwtPayload] = DeriveJsonDecoder.gen[JwtPayload]
  implicit val encoder: JsonEncoder[JwtPayload] = DeriveJsonEncoder.gen[JwtPayload]
}

object JwtToken:

  enum TokenError:
    case BadSignature, Expired, BadPayload, NoSubject, OtherProblem

  // Ok, so the JWT library we're using is a bit of a mess.  It encodes subjects fine/consistently, but
  // upon decode it sometimes puts the subject in the "sub" field and sometimes in the "content" field.
  // No clear reason why--so we have to accomodate either and force standardization.
  private def fixClaim(claim: JwtClaim): ZIO[Any, TokenError, JwtClaim] = {
    ZIO.fromOption(claim.subject)
        .orElse {
        ZIO
            .fromEither(claim.content.fromJson[JwtPayload])
            .mapError(_ => TokenError.BadPayload)
            .flatMap(payload =>
            payload.sub match {
                case Some(subject) => ZIO.succeed(subject)
                case None          => ZIO.fail(TokenError.NoSubject)
            }
            )
        }
        .map(subject =>
        JwtClaim(
            content = claim.content,
            issuer = claim.issuer,
            subject = Some(subject), // Now there will always be a subject here
            audience = claim.audience,
            expiration = claim.expiration,
            notBefore = claim.notBefore,
            issuedAt = claim.issuedAt,
            jwtId = claim.jwtId
        )
      )
    }

  private[auth] def jwtEncode(subject: String, key: String, expireSec: Long)(implicit clock: zio.Clock): ZIO[Any, Throwable, String] =
    for {
      now <- clock.instant.map(_.getEpochSecond) // Dynamically fetch the current time
      claim = JwtClaim(subject = Some(subject))
        .issuedAt(now)
        .expiresIn(expireSec)(
          using ClockConverter.dynamicJavaClock(clock) // Use dynamic Clock here
        )
      token <- ZIO.attempt(Jwt.encode(claim, key, JwtAlgorithm.HS512))
    } yield token 

  private[auth] def jwtDecode(
    token: String, 
    key: String, 
    leewaySec: Long = 0L, 
    withExpiration: Boolean = true
  )(implicit clock: zio.Clock): ZIO[Any, TokenError, JwtClaim] = 
    for {
      claim <- ZIO.fromTry(
            Jwt(ClockConverter.dynamicJavaClock(clock))
            .decode(token, key, Seq(JwtAlgorithm.HS512), JwtOptions(leeway = leewaySec, expiration = withExpiration)))
            .mapError( _ match {
              case _: exceptions.JwtExpirationException => TokenError.Expired
              case e: exceptions.JwtValidationException => TokenError.BadSignature
              case _                                    => TokenError.OtherProblem
            })
      fixedClaim <- fixClaim(claim)
    } yield (fixedClaim)
    
  private[auth] def refreshToken(
    token: String,
    decodeWith: String,
    encodeNewWith: String,
    leewaySec: Long,
    expirationSec: Long
  )(implicit clock: zio.Clock): ZIO[Any, TokenError, String] = 
    for {
      now <- clock.instant
      // Decode the token without verifying expiration and allowing for extra time (refresh_window_sec from config)
      sloppy <- jwtDecode(token, decodeWith, leewaySec, true)
      newToken <- sloppy.subject match {
        case None => ZIO.fail(TokenError.NoSubject)
        case Some(subject) => 
          // Re-encode the token with a new expiration time
          jwtEncode(subject, encodeNewWith, expirationSec)
            .mapError(_ => TokenError.OtherProblem)
      }
    } yield newToken
