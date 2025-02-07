package co.blocke.bedrock
package services
package auth
package model

import zio.*
import zio.json.* 
import java.util.Base64
import java.net.URLDecoder


/* 
 * State is a case class that holds the state of the OAuth2 flow.  It is encoded into a string and passed to the OAuth2 provider.
 * We want to leave hooks to support multitenancy, so we prepend a tenant prefix to the state and cache keys.
 * The nonce is a random string to prevent replay attacks.
 * The redirectToUrl is the final front-end URL to redirect to after login done.
 * 
 * Encoding is URL-encoded, Base-64 encoded JSON.
 */
case class State(
  stateId:       String,
  tenantPrefix:  String,
  nonce:         String,
  redirectToUrl: String
  ):
  def toEncodedJson: String = encode(Base64.getEncoder.encodeToString(this.toJson.getBytes))

  
object State:
  implicit val codec: JsonCodec[State] = DeriveJsonCodec.gen[State]

  def fromEncodedJson(encoded: String): ZIO[Any,Throwable,State] = 
    ZIO.attempt{
      val bytes = Base64.getDecoder.decode(URLDecoder.decode(encoded, "UTF-8"))
      val json = new String(bytes)
      json.fromJson[State].getOrElse(throw new RuntimeException("Failed to decode State"))
    }.tapError{ e => 
        ZIO.logError(s"Failed to decode State: ${encoded}") *>
        ZIO.fail(e)
    }
