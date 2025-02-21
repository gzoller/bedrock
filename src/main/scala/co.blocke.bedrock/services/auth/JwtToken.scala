package co.blocke.bedrock
package services
package auth

import pdi.jwt.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import aws.KeyBundle


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

  // Ok, so the JWT library we're using is a bit of a mess.  It encodes subjects consistently, but
  // upon decode it sometimes puts the subject in the "sub" field and sometimes in the "content" field.
  // No clear reason why--so we have to accomodate either and force standardization.
  private def fixClaim(claim: JwtClaim): ZIO[Any, TokenError, JwtClaim] =
    ZIO.fromOption(claim.subject)
      .orElse {
        ZIO.fromEither(claim.content.fromJson[JwtPayload])
          .mapError(_ => TokenError.BadPayload)
          .flatMap(payload =>
            payload.sub match {
              case Some(subject) => ZIO.succeed(subject)
              case None          => ZIO.fail(TokenError.NoSubject)
            }
          )
      }
      .map { subject =>
        // Parse existing content to remove the duplicate "sub" field
        val updatedContent = claim.content.fromJson[JwtPayload] match {
          case Right(payload) =>
            payload.copy(sub = Some(subject)).toJson // Ensure the new "sub" is properly set
          case Left(_) => claim.content // Keep original if parsing fails
        }

        JwtClaim(
          content = updatedContent,  // Use the cleaned-up content
          issuer = claim.issuer,
          subject = Some(subject),    // Ensure "sub" is set in JwtClaim
          audience = claim.audience,
          expiration = claim.expiration,
          notBefore = claim.notBefore,
          issuedAt = claim.issuedAt,
          jwtId = claim.jwtId
        )
      }

  private[auth] def jwtEncode(
    subject: String, 
    key: String, 
    expireSec: Long, 
    roles: List[String] = List.empty[String]
    )(implicit clock: zio.Clock): ZIO[Any, Throwable, String] =
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
        .mapError {
          case _: exceptions.JwtExpirationException => TokenError.Expired
          case e: exceptions.JwtValidationException => TokenError.BadSignature
          case x => TokenError.OtherProblem
        }
      fixedClaim <- fixClaim(claim)
    } yield (fixedClaim))

  /**
   * This is a wee mess in order to accomodate the fact that the secret keys can rotate, and when the do, we don't
   * want to inconvenience the user if we can avoid it. So long as the previous key works, simply re-encodde the
   * access token with the new key. We don't worry about the session key--it gets the same "previous" treatment
   * but will gracefully age out and does not need to be refreshed.
   */
  private[auth] def refreshAccessToken(
     oldAccessToken: String,
     refreshToken: String,
     keyBundle: KeyBundle,
     accessExpirationSec: Long,
     leewaySec: Long,  // should be session TTL to be safe (if older than session we're dead anyway)
   )(implicit clock: zio.Clock): ZIO[Any, TokenError, String] =
    for {
      currentTime <- Clock.currentTime(java.util.concurrent.TimeUnit.SECONDS)

      // Ensure refresh token is good
      _ <- jwtDecode(refreshToken, keyBundle.sessionKey.value)
        .catchSome{
          // If session key has rotated, try previous one. If that fails--it's bad
          case _ if keyBundle.previousSessionKey.isDefined => jwtDecode(refreshToken, keyBundle.previousSessionKey.get.value)
        }

      // Get the access token's claim, accounting for possible secret key rotation
      accessClaim <- jwtDecode(oldAccessToken, keyBundle.currentTokenKey.value, leewaySec).catchSome{
        case TokenError.BadSignature if keyBundle.previousTokenKey.isDefined => jwtDecode(oldAccessToken, keyBundle.previousTokenKey.get.value) // try with previous key or fail
      }

      // Encode a new access token, maintaining other info, eg roles
      newAccessToken <- jwtEncode(accessClaim, keyBundle.currentTokenKey.value, accessExpirationSec)
        .mapError(_ => TokenError.OtherProblem)
    } yield newAccessToken


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