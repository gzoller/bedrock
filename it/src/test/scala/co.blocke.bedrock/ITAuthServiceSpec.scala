package co.blocke.bedrock
package services
package auth

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.http.*
import zio.json.*

import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import software.amazon.awssdk.services.secretsmanager.model._

/**
  * Test all the Auth behavior, token rotation etc.
  */
object ITAuthServiceSpec extends ZIOSpecDefault {

  def realTimeDelay(duration: Duration): ZIO[Any, Nothing, Unit] =
    ZIO.attemptBlocking(Thread.sleep(duration.toMillis)).orDie

  def loopUntilVersionChanges(
      authToken: String,
      version: Int,
      retriesLeft: Int
  ): ZIO[Client, Throwable, Int] =
    if (retriesLeft <= 0) {
      ZIO.fail(new RuntimeException("Max retries reached without a version change"))
    } else {
      (for {
        verResp    <- Client.batched(Request.get("https://localhost:8073/key_bundle_version")
                        .addHeader(Header.Authorization.Bearer(authToken)))
                        .timeout(5.seconds) // Add timeout
                        .flatMap {
                          case Some(response) => ZIO.succeed(response)
                          case None           => ZIO.fail(new RuntimeException("Request timed out"))
                        }
        verStr     <- verResp.body.asString
        currentVer =  verStr.toInt
        result     <- if (currentVer != version) then
                        ZIO.succeed( currentVer ) // Return when the version changes
                      else
                        realTimeDelay(1.second) *>
                        loopUntilVersionChanges(authToken, version, retriesLeft - 1)
      } yield result
      ).provideLayer(Client.default)
    }

  override def spec = suite("Integration Test - Authentication")(
    test("Login should succeed") {
      for {
        response <- Client.batched(Request.get("https://localhost:8073/login"))
        body     <- response.body.asString
        tokens   <- ZIO.fromEither(body.fromJson[TokenBundle]) // Decode the JSON into a TokenBundle
                      .mapError(error => new RuntimeException(s"Failed to decode JSON: $error"))
      } yield assert(response.status)(equalTo(Status.Ok)) // implicitly tokens are fine if they parsed successfully
    },
    test("Valid auth token should work") {
      for {
        response  <- Client.batched(Request.get("https://localhost:8073/login"))
        body      <- response.body.asString
        tokens    <- ZIO.fromEither(body.fromJson[TokenBundle]) // Decode the JSON into a TokenBundle
                      .mapError(error => new RuntimeException(s"Failed to decode JSON: $error"))
        response2 <- Client.batched(Request.get("https://localhost:8073/hello").addHeader(Header.Authorization.Bearer(tokens.authToken)))
        msg      <- response2.body.asString
      } yield assert(response2.status)(equalTo(Status.Ok)) &&
        assert(msg)(equalTo("Hello, World, bogus_user!"))
    },
    test("Token decoding should fail upon token expiry") {
      for {
        response   <- Client.batched(Request.get("https://localhost:8073/login"))
        body       <- response.body.asString
        tokens     <- ZIO.fromEither(body.fromJson[TokenBundle]) // Decode the JSON into a TokenBundle
                      .mapError(error => new RuntimeException(s"Failed to decode JSON: $error"))
        
        expResp    <- Client.batched(Request.post("https://localhost:8073/expire_token?seconds=421", Body.empty)
                        .addHeader(Header.Authorization.Bearer(tokens.authToken)))
        expToken   <- response.body.asString

        response2 <- Client.batched(Request.get("https://localhost:8073/hello").addHeader(Header.Authorization.Bearer(expToken)))
      } yield assert(response2.status)(equalTo(Status.Unauthorized))
    },
    test("Token refresh should succeed upon token expiry and the presence of a valid session token (refresh must work)") {
      for {
        // Log in
        response   <- Client.batched(Request.get("https://localhost:8073/login"))
        body       <- response.body.asString
        tokens     <- ZIO.fromEither(body.fromJson[TokenBundle]) // Decode the JSON into a TokenBundle
                      .mapError(error => new RuntimeException(s"Failed to decode JSON: $error"))
        
        // Get an intentionally expired token
        expResp    <- Client.batched(Request.post("https://localhost:8073/expire_token?seconds=410&isSession=false", Body.empty)
                        .addHeader(Header.Authorization.Bearer(tokens.authToken)))
        expToken   <- expResp.body.asString

        // Confirm it's expired by retrying the hello endpoint with it
        unauth     <- Client.batched(Request.get("https://localhost:8073/hello").addHeader(Header.Authorization.Bearer(expToken)))

        // Retry with the expired token but also include the session token to trigger a token refresh
        retry      <- Client.batched(Request.get("https://localhost:8073/hello")
                        .addHeader("X-Session-Token", tokens.sessionToken)
                        .addHeader(Header.Authorization.Bearer(expToken)))
        // Get the new auth token out of the header of the (successful) retry response
        freshToken <- 
          retry.headers.header(Header.Authorization) match {
            case Some(Header.Authorization.Bearer(token)) => ZIO.succeed(token.value.asString)
            case _ => ZIO.fail(new RuntimeException("Authorization header is missing or incorrect."))
          }
        
        // Verify the new token works
        verify     <- Client.batched(Request.get("https://localhost:8073/hello")
                        .addHeader(Header.Authorization.Bearer(freshToken)))
        msg        <- verify.body.asString
      } yield assert(unauth.status)(equalTo(Status.Unauthorized)) &&
          assert(retry.status)(equalTo(Status.Ok)) &&
          assert(verify.status)(equalTo(Status.Ok)) &&
          assert(msg)(equalTo("Hello, World, bogus_user!"))
    },
    test("Token refresh should fail upon token expiry and the presence of an valid session token outside refresh window") {
      for {
        // Log in
        response   <- Client.batched(Request.get("https://localhost:8073/login"))
        body       <- response.body.asString
        tokens     <- ZIO.fromEither(body.fromJson[TokenBundle]) // Decode the JSON into a TokenBundle
                      .mapError(error => new RuntimeException(s"Failed to decode JSON: $error"))
        
        // Get an intentionally expired token
        expResp    <- Client.batched(Request.post("https://localhost:8073/expire_token?seconds=422&isSession=false", Body.empty) // expired auth token too old
                        .addHeader(Header.Authorization.Bearer(tokens.authToken)))
        expToken   <- expResp.body.asString

        // Confirm it's expired by retrying the hello endpoint with it
        unauth     <- Client.batched(Request.get("https://localhost:8073/hello").addHeader(Header.Authorization.Bearer(expToken)))

        // Retry with the expired token but also include the session token, but this time refresh will fail--too long since expiry
        retry      <- Client.batched(Request.get("https://localhost:8073/hello")
                        .addHeader("X-Session-Token", tokens.sessionToken)
                        .addHeader(Header.Authorization.Bearer(expToken)))
      } yield assert(unauth.status)(equalTo(Status.Unauthorized)) &&
          assert(retry.status)(equalTo(Status.Unauthorized))
    },
    test("Token refresh should fail upon token expiry and the presence of an expired session token") {
      for {
        // Log in
        response   <- Client.batched(Request.get("https://localhost:8073/login"))
        body       <- response.body.asString
        tokens     <- ZIO.fromEither(body.fromJson[TokenBundle]) // Decode the JSON into a TokenBundle
                      .mapError(error => new RuntimeException(s"Failed to decode JSON: $error"))
        
        // Get an intentionally expired auth token (inside refresh window)
        expResp    <- Client.batched(Request.post("https://localhost:8073/expire_token?seconds=410&isSession=false", Body.empty) // expired auth token too old
                        .addHeader(Header.Authorization.Bearer(tokens.authToken)))
        expToken   <- expResp.body.asString

        // Get an intentionally expired session token
        sessResp   <- Client.batched(Request.post("https://localhost:8073/expire_token?seconds=10&isSession=true", Body.empty) // expired auth token too old
                        .addHeader(Header.Authorization.Bearer(tokens.authToken)))
        sessToken  <- expResp.body.asString

        // Retry with the expired token but also include the session token, but this time refresh will fail--too long since expiry
        retry      <- Client.batched(Request.get("https://localhost:8073/hello")
                        .addHeader("X-Session-Token", sessToken)
                        .addHeader(Header.Authorization.Bearer(expToken)))
      } yield assert(retry.status)(equalTo(Status.Unauthorized))
    },
    test("Unexpired tokens work immediately after secret key rotation (using previous key), and refreshed token issued in response") {
      for {
        // Log in
        response   <- Client.batched(Request.get("https://localhost:8073/login"))
        body       <- response.body.asString
        tokens     <- ZIO.fromEither(body.fromJson[TokenBundle]) // Decode the JSON into a TokenBundle
                      .mapError(error => new RuntimeException(s"Failed to decode JSON: $error"))

        // Get the current key bundle version
        verResp    <- Client.batched(Request.get("https://localhost:8073/key_bundle_version")
                        .addHeader(Header.Authorization.Bearer(tokens.authToken)))
        verStr     <- verResp.body.asString
        initVer    =  verStr.toInt

        // Call aws to rotate secret keys here then wait for the key bundle version to change
        _          <- ZIO.attempt{
                        val client = SecretsManagerClient.builder()
                          .region(software.amazon.awssdk.regions.Region.US_EAST_1) // Set region
                          .endpointOverride(new java.net.URI("http://localhost:4566")) // LocalStack endpoint
                          .build()

                        // Define the request to rotate the secret
                        val request = RotateSecretRequest.builder()
                          .secretId("MySecretKey") // Secret ID
                          .rotationLambdaARN("arn:aws:lambda:us-east-1:000000000000:function:RotateSecretFunction") // Lambda ARN
                          .rotationRules(RotationRulesType.builder()
                            .automaticallyAfterDays(30) // Rotation frequency in days
                            .build())
                          .build()

                        client.rotateSecret(request)
          }
        _          <- loopUntilVersionChanges(tokens.authToken, initVer, 10)  // don't care what the new version is--just that it changes

        // Now test the old token with the previous key -- should work and return a new token
        retry      <- Client.batched(Request.get("https://localhost:8073/hello")
                        .addHeader(Header.Authorization.Bearer(tokens.authToken)))

        // Get the new auth token out of the header of the (successful) retry response
        freshToken <- 
          retry.headers.header(Header.Authorization) match {
            case Some(Header.Authorization.Bearer(token)) => ZIO.succeed(token.value.asString)
            case _ => ZIO.fail(new RuntimeException("Authorization header is missing or incorrect."))
          }
        
        // Verify the new token works
        verify     <- Client.batched(Request.get("https://localhost:8073/hello")
                        .addHeader(Header.Authorization.Bearer(freshToken)))
        msg        <- verify.body.asString
      } yield assert(retry.status)(equalTo(Status.Ok)) &&
          assert(verify.status)(equalTo(Status.Ok)) &&
          assert(freshToken)(not(equalTo(tokens.authToken))) &&
          assert(msg)(equalTo("Hello, World, bogus_user!"))
    },
  ).provideLayer(Client.default ++ ZLayer.succeed(Clock)) @@ TestAspect.sequential
 
}
