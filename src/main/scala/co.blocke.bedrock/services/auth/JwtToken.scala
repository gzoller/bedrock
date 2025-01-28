package co.blocke.bedrock
package services
package auth

import pdi.jwt.*
import zio.*
import zio.json.*
import zio.json.ast.Json


case class JwtPayload(
  sub: Option[String],
  
  // OAuth providers often include roles in the JWT payload. However... the name and format is not
  // standard. So we'll support a few of the better knwon ones here and you can add/change as needed.
  // The default Bedrock Auth system uses "roles" as the field name.

  // To handle this flexibility we're using dyamic fields
  dynamicFields: Map[String, Json] = Map.empty // Store other fields dynamically
  )

object JwtPayload {
  implicit val decoder: JsonDecoder[JwtPayload] = JsonDecoder[Map[String, Json]].mapOrFail { fields =>
    val sub = fields.get("sub").flatMap(_.as[String].toOption)
    Right(JwtPayload(sub, fields - "sub")) // Remove `sub` from dynamicFields
  }

  implicit val encoder: JsonEncoder[JwtPayload] = JsonEncoder[Map[String, Json]].contramap { payload =>
    // Combine `sub` and `dynamicFields` into a single map
    payload.dynamicFields ++ payload.sub.map("sub" -> Json.Str(_))
  }
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

  private[auth] def jwtEncode(subject: String, key: String, expireSec: Long, roles: List[String])(implicit clock: zio.Clock): ZIO[Any, Throwable, String] =
    for {
      now <- clock.instant.map(_.getEpochSecond) // Dynamically fetch the current time
      claim = JwtClaim(subject = Some(subject))
        .issuedAt(now)
        .expiresIn(expireSec)(
          using ClockConverter.dynamicJavaClock(clock) // Use dynamic Clock here
        )
        .withContent("""{"roles":[""" + roles.map(r => "\"" + r + "\"").mkString(",") + "]}" )
      token <- ZIO.attempt(Jwt.encode(claim, key, JwtAlgorithm.HS512))
    } yield token 

  // Another version of jwtEncode using Claim, for use when refreshing a token. This one maintains original claim's payload fields, roles, etc.
  private[auth] def jwtEncode(claim: JwtClaim, key: String, expireSec: Long)(implicit clock: zio.Clock): ZIO[Any, Throwable, String] =
    for {
      now <- clock.instant.map(_.getEpochSecond) // Dynamically fetch the current time
      newClaim = claim
        .issuedAt(now)
        .expiresIn(expireSec)(
          using ClockConverter.dynamicJavaClock(clock) // Use dynamic Clock here
        )
      token <- ZIO.attempt(Jwt.encode(newClaim, key, JwtAlgorithm.HS512))
    } yield token 

  private[auth] def jwtDecode(
    token: String, 
    key: String, 
    leewaySec: Long = 0L
  )(implicit clock: zio.Clock): ZIO[Any, TokenError, JwtClaim] = 
    (for {
      claim <- ZIO.fromTry(
            Jwt(ClockConverter.dynamicJavaClock(clock))
            .decode(token, key, Seq(JwtAlgorithm.HS512), JwtOptions(leeway = leewaySec, expiration = true)))
            .mapError( _ match {
              case _: exceptions.JwtExpirationException => TokenError.Expired
              case e: exceptions.JwtValidationException => TokenError.BadSignature
              case x                                    => TokenError.OtherProblem
            })
      fixedClaim <- fixClaim(claim)
    } yield (fixedClaim))
    
  private[auth] def refreshToken(
    token: String,
    decodeWith: String,
    encodeNewKey: String,
    leewaySec: Long,
    expirationSec: Long
  )(implicit clock: zio.Clock): ZIO[Any, TokenError, (JwtClaim, String)] = 
    for {
      now <- clock.instant
      // Decode the token allowing for extra time (refresh_window_sec from config) to see if it is still in the
      // refresh window
      sloppyClaim <- jwtDecode(token, decodeWith, leewaySec)
      newToken <- sloppyClaim.subject match {
        case None => ZIO.fail(TokenError.NoSubject)
        case _ => 
          // Re-encode the token with a new expiration time
          jwtEncode(sloppyClaim, encodeNewKey, expirationSec)
            .mapError(_ => TokenError.OtherProblem)
      }
    } yield (sloppyClaim, newToken)

  private[auth] def getRoles(claim: JwtClaim, roleFieldName: Option[String] = None): ZIO[Any, TokenError, List[String]] = {
    // Define the possible keys that can represent "roles"
    val roleKeys = Set(
      "roles",           // Bedrock default, Okta
      "scope"            // OAuth 2.0/OpenID Connect (OIDC)
      ) ++ roleFieldName.toSet  // custom, sometimes a url

    for {
      payload <- ZIO
        .fromEither(claim.content.fromJson[JwtPayload])
        .mapError(_ => TokenError.BadPayload)
      roles <- ZIO.succeed {
        // Find the first matching key and parse it as either List[String] or String
        roleKeys
          .flatMap { key =>
            payload.dynamicFields.get(key).toList.flatMap { jsonValue =>
              jsonValue.as[List[String]].toOption.orElse(
                jsonValue.as[String].toOption.map(_.split(' ').toList) // space-sep list of roles
              )
            }
          }
          .headOption
          .getOrElse(List.empty[String]) // Default to empty list if no match is found
      }
    } yield roles
  }