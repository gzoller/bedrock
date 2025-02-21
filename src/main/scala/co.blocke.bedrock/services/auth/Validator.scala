package co.blocke.bedrock
package services
package auth

import aws.AwsRedis
import model.*

import zio.*
import zio.http.Client

/*

General-purpose OAuth2 Validator.  Each OAuth2 provider has its own quirks, so this class is a bit of a mess.  The general idea is to:
  1. Validate the state parameter (nonce, redirect URL, tenant prefix)
  2. Ensure no error in the query params
  3. Ensure all required fields are present
  4. Ensure the scope is correct
  5. Validate the ID token 


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

trait Validator:
  // Fields we expect provider to return as query params on initial redirect--can be overridden by provider
  val successfulFields = List("code", "state", "scope")

  // Provider-specific parameters for the code->tokens exchange
  val tokenQueryExtraFields: String = ""

  def verifyAccessToken(accessToken: String): ZIO[Any, Throwable, Unit]
  def verifyIdToken(idToken: String): ZIO[Any, Throwable, UserProfile]

  def validateParams(params: Map[String, String], requiredScope: List[String], redis: AwsRedis): ZIO[Any, Throwable, State] =
    for {
    _     <- if (isError(params)) 
               ZIO.logError(s"Error in OAuth2 flow: ${params("error_description")}").as(()) *>
               ZIO.fail(new RuntimeException("Error in OAuth2 flow")) else ZIO.unit
    _     <- (ZIO.logError("Missing required fields") *> 
               ZIO.fail(new RuntimeException("Missing required fields"))) unless fieldsExist(params)
    _     <- (ZIO.logError(s"""OAuth Scope is not correct: ${params.get("scope")}""") *>
               ZIO.fail(new RuntimeException("Scope is not correct"))) unless scopeIsOk(params.get("scope"), requiredScope)
    state <- stateIsOk(params.get("state"), redis)
    } yield state


  private def isError(params: Map[String, String]): Boolean = params.contains("error")

  private def fieldsExist(params: Map[String, String]): Boolean = successfulFields.forall(params.contains)

  private def scopeIsOk(paramScope: Option[String], requiredScope: List[String]): Boolean =
    paramScope.exists { scopeString =>
      val scopeSet = scopeString.split(" ").toSet
      requiredScope.forall(scopeSet.contains)
    }

  private def stateIsOk(paramState: Option[String], redis: AwsRedis): ZIO[Any,Throwable,State] =
    paramState.map{ urlEncodedState => 
      for {
        paramState       <- State.fromEncodedJson(urlEncodedState)
        retrievedStateJS <- redis.getDel(paramState.stateId)
                              .someOrFail(new RuntimeException("State no longer in cache"))
        cachedState      <- State.fromEncodedJson(retrievedStateJS)
        validState       <- ZIO.fromOption(
                              Option.when(
                                  paramState.nonce == cachedState.nonce &&
                                  paramState.redirectToUrl == cachedState.redirectToUrl &&
                                  paramState.tenantPrefix == cachedState.tenantPrefix
                              )(cachedState)
                            ).orElseFail(new RuntimeException("State does not match"))
      } yield validState
    }.getOrElse(throw new RuntimeException(s"Session not found"))


object Validator:
  def apply(provider: String, providerCertsUrl: String): ZIO[Client, Throwable, Validator]  =
    provider match
      case "google" => GoogleValidator.create(providerCertsUrl)
      case _        => ZIO.fail(throw new RuntimeException(s"Unknown provider: $provider"))

