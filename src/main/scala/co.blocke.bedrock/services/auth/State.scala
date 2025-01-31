package co.blocke.bedrock
package services
package auth

import zio.*
import zio.json.* 
import java.util.Base64


/* 
 * State is a case class that holds the state of the OAuth2 flow.  It is encoded into a string and passed to the OAuth2 provider.
 * We want to leave hooks to support multitenancy, so we prepend a tenant prefix to the state and cache keys.
 * The nonce is a random string to prevent replay attacks.
 * The redirectToUrl is the URL to redirect to after login.
 */
case class State(
  sesionId:      String,
  tenantPrefix:  String,
  nonce:         String,
  redirectToUrl: String
  ):
  def encode: String = Base64.getEncoder.encodeToString(this.toJson.getBytes)

  
object State:
  implicit val codec: JsonCodec[State] = DeriveJsonCodec.gen[State]

  def decode(encoded: String): ZIO[Any,Throwable,State] = 
    ZIO.attempt{
      val bytes = Base64.getDecoder.decode(encoded)
      val json = new String(bytes)
      json.fromJson[State].getOrElse(throw new RuntimeException("Failed to decode State"))
    }.tapError{ e => 
        ZIO.logError(s"Failed to decode State: ${encoded}") *>
        ZIO.fail(e)
    }
