package co.blocke.bedrock
package services
package auth

import zio.*
import java.net.URLDecoder

/*

Vendor Differences:

Query Parameter  	Google     GitHub   Azure AD  Facebook   Auth0                    Okta
code	            ✅ Yes	  ✅ Yes	  ✅ Yes    ✅ Yes     ✅ Yes                   ✅ Yes
state	            ✅ Yes	  ✅ Yes   ✅ Yes	   ✅ Yes     ✅ Yes                   ✅ Yes 
scope	            ✅ Yes	  ✅ Yes   ✅ Yes	   ❌ No      ✅ Yes (usually)         ✅ Yes
authuser	        ✅ Yes	  ❌ No	  ❌ No	   ❌ No      ❌ No                    ❌ No
prompt	            ✅ Yes     ❌ No	  ❌ No	   ❌ No      ❌ No                    ❌ No
error	            ✅ Yes*    ✅ Yes   ✅ Yes	   ✅ Yes     ✅ Yes                   ✅ Yes
error_description	✅ Yes*    ✅ Yes   ✅ Yes    ✅ Yes     ✅ Yes                   ✅ Yes
error_uri	        ✅ Yes(*)  ❌ No    ✅ Yes    ❌ No      ❌ No                    ❌ No
id_token            ❌ No      ❌ No    ❌ No     ❌ No      ✅ Yes (OpenID Connect)  ✅ Yes (OpenID Connect)
session_state       ❌ No      ❌ No    ❌ No     ❌ No      ✅ Yes (opt)             ❌ No
interaction_code    ❌ No      ❌ No    ❌ No     ❌ No      ❌ No                    ✅ Yes (opt)

 */
trait Validator:
  val successfulFields: List[String]

  def validateParams(params: Map[String, String], scope: List[String]): ZIO[Any, Throwable, State] =
    for {
    state <- stateIsOk(params)
    _     <- if (isError(params)) ZIO.fail(new RuntimeException("Error in OAuth2 flow")) else ZIO.unit
    _     <- ZIO.fail(new RuntimeException("Missing required fields")) unless fieldsExist(params, scope)
    } yield state

  private def fieldsExist(params: Map[String, String], fields: List[String]): Boolean =
    fields.forall(params.contains)
  private def isError(params: Map[String, String]): Boolean =
    params.contains("error")
  private def stateIsOk(params: Map[String, String]): ZIO[Any,Throwable,State] =
    params.get("state").map{ urlEncodedState => 
      for {
        encodedState   <- ZIO.attempt(URLDecoder.decode(urlEncodedState, "UTF-8"))
        paramState     <- State.decode(encodedState)
        retrievedState <- ZIO.fromOption(FakeSessionCache.get(paramState.sesionId))
                            .orElseFail(new RuntimeException(s"Session not found"))
        cachedState    <- State.decode(retrievedState)
        validState     <- ZIO.fromOption(
                            Option.when(
                                paramState.nonce == cachedState.nonce &&
                                paramState.redirectToUrl == cachedState.redirectToUrl &&
                                paramState.tenantPrefix == cachedState.tenantPrefix
                            )(cachedState)
                          ).orElseFail(new RuntimeException("State does not match"))
      } yield validState
    }.getOrElse(throw new RuntimeException(s"Session not found"))


object Validator:
  def apply(provider: String, scope: List[String]): Validator =
    provider match
      case "google" => GoogleValidator()
      case _ => throw new RuntimeException(s"Unknown provider: $provider")


case class GoogleValidator() extends Validator:
  val successfulFields: List[String] = List()
