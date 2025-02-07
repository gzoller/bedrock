package co.blocke.bedrock
package services
package auth

import model.*

import pdi.jwt._
import zio.*
import zio.json.*
import zio.http.{Client, Request}
import java.security.spec.RSAPublicKeySpec
import java.security.{KeyFactory, PublicKey}


/*

Vendor Differences:

Query Parameter  	Google     GitHub   Azure AD  Facebook   Auth0                    Okta
code	            ✅ Yes	    ✅ Yes	  ✅ Yes    ✅ Yes     ✅ Yes                   ✅ Yes
state	            ✅ Yes	    ✅ Yes   ✅ Yes	   ✅ Yes     ✅ Yes                   ✅ Yes 
scope	            ✅ Yes	    ✅ Yes   ✅ Yes	   ❌ No      ✅ Yes (usually)         ✅ Yes
authuser	        ✅ Yes	    ❌ No	  ❌ No	   ❌ No      ❌ No                    ❌ No
prompt	          ✅ Yes     ❌ No    ❌ No	   ❌ No      ❌ No                    ❌ No
error	            ✅ Yes*    ✅ Yes   ✅ Yes	   ✅ Yes     ✅ Yes                   ✅ Yes
error_description	✅ Yes*    ✅ Yes   ✅ Yes    ✅ Yes     ✅ Yes                   ✅ Yes
error_uri	        ✅ Yes(*)  ❌ No    ✅ Yes    ❌ No      ❌ No                    ❌ No
id_token          ❌ No      ❌ No    ❌ No     ❌ No      ✅ Yes (OpenID Connect)  ✅ Yes (OpenID Connect)
session_state     ❌ No      ❌ No    ❌ No     ❌ No      ✅ Yes (opt)             ❌ No
interaction_code  ❌ No      ❌ No    ❌ No     ❌ No      ❌ No                    ✅ Yes (opt)
 */

final case class GooglePublicKey(kid: String, n: String, e: String, alg: String)
object GooglePublicKey {
  implicit val decoder: JsonDecoder[GooglePublicKey] = DeriveJsonDecoder.gen[GooglePublicKey]
}

final case class GoogleJWKS(keys: List[GooglePublicKey])
object GoogleJWKS {
  implicit val decoder: JsonDecoder[GoogleJWKS] = DeriveJsonDecoder.gen[GoogleJWKS]
}


final case class GoogleValidator(rsaKeys : List[GooglePublicKey]) extends Validator:

  private def createPublicKey(n: String, e: String): PublicKey = {
    val modulus = new java.math.BigInteger(1, base64UrlDecode(n))
    val exponent = new java.math.BigInteger(1, base64UrlDecode(e))
    val spec = new RSAPublicKeySpec(modulus, exponent)
    KeyFactory.getInstance("RSA").generatePublic(spec)
  }

  def verifyIdToken(idToken: String): ZIO[Any, Throwable, UserProfile] =
    for {
      decoded <- ZIO.fromTry(Jwt.decodeAll(idToken, JwtOptions(signature = false)))
      kidOpt = decoded._1.keyId

      // 🔹 Select the correct public key (or try all if `kid` is missing)
      selectedKeys = kidOpt match {
        case Some(kid) => rsaKeys.filter(_.kid.contains(kid)) // Use the key with matching `kid`
        case None => rsaKeys // If `kid` is missing, try all available keys
      }

      // 🔹 Validate JWT against available keys
      verified <- ZIO.succeed {
        selectedKeys.exists { key =>
          val publicKey = createPublicKey(key.n, key.e)
          Jwt.isValid(idToken, publicKey, Seq(JwtAlgorithm.RS256))
        }
      }.filterOrFail(identity)(new RuntimeException("JWT verification failed"))

      // 🔹 Parse the UserProfile from the JWT claims
      rawProfile <- ZIO.fromEither(decoded._2.content.fromJson[UserProfile].left.map(err =>
        new RuntimeException(s"JSON Parsing Error: $err")
      ))

      claimSubject <- ZIO.fromOption(decoded._2.subject).orElseFail(new RuntimeException("No subject in token"))

    } yield rawProfile.copy(userId = claimSubject)


object GoogleValidator:
  
  def create(providerCertsUrl: String): ZIO[Client, Throwable, GoogleValidator] =
    for {
      response <- Client.batched(Request.get(providerCertsUrl))
      body     <- response.body.asString
      jwks     <- ZIO.fromEither(body.fromJson[GoogleJWKS].left.map(err => new RuntimeException(s"JSON decoding error: $err")))
      rsaKeys   = jwks.keys.filter(_.alg == "RS256")
    } yield GoogleValidator(rsaKeys)


  /* Id Token Example:
  {
  "iss":"https://accounts.google.com",
  "azp":"593880049906-71491lin36bpaasbm6qu61v4cmn0fvrk.apps.googleusercontent.com",
  "aud":"593880049906-71491lin36bpaasbm6qu61v4cmn0fvrk.apps.googleusercontent.com",
  "email":"gzoller@gmail.com",
  "email_verified":true,
  "at_hash":"5SXfwH1hwd7Wys5XP_K7Rw",
  "name":"Greg Zoller",
  "picture":"https://lh3.googleusercontent.com/a/ACg8ocIHVWYukE6oTCQxCpOBkRneF1Pbjp-ppEyONWMc5yRTgy_QK6C2=s96-c",
  "given_name":"Greg",
  "family_name":"Zoller"
  }

  */