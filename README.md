# bedrock
Starting point for REST service

## Features

* ZIO HTTP REST endpoints
* Injectable data store (allows for unit testing w/o real database)
* Switchable Swagger support for prod/non-prod. 
    Compile with: ```sbt -Dprod=true "clean;compile;package;assembly"``` to disable Swagger for production deployment
* Test examples -- how to test a REST service
* [HTTPS Support](docs/https.md)
* [Auth using tokens (endpoint protection) incl rotating keys and tokens)](docs/security.md)
* Get access to the encoded user id from jtw token in handler

IN-PROGRESS:
* Figure out secret key rotation:
   - retrieve versioned keys (current/previous) DONE
   - figure out how to handle transition period where 2 keys are valid at the same time for a short defined window
   - figure out how to subscribe to EventBridge and listen for events trigering a re-read of keys from Secrets Manager
   - figure out how to injet secret keys into Authentication code

TODO:
* Auto-generate Swagger config (fixed with my own mods to ZIO HTTP...PR pending)
* Hide server.crt/server.key files but ensure they're published & packaged correctly
* Figure out OAuth
* Figure out packaging (Docker)
* Figure out Kuberneties
* Figure out Teraform
* Figure out monitoring
* Figure out advanced logging (eg with queries like splunk)
* Investingate queryable event queue
* Integrate w/RDS
* Figure out token rotation:
  - How to rotate secret in Secrets Manager with Lambda function
    https://docs.aws.amazon.com/secretsmanager/latest/userguide/rotate-secrets_lambda.html
  - How to notify upon rotation
    B. Push Notifications Using AWS EventBridge and Lambda
	•	Configure AWS Secrets Manager to emit an event to EventBridge when a secret is rotated.
	•	Use a Lambda function to notify your servers or update a central configuration service.
	•	Your servers can then subscribe to these notifications via WebSocket, API calls, or a configuration polling mechanism.
  - How to handle tokens generated with old/new secrets

    This situation can occur during the secret rotation window. To address it, you can implement a rolling secret validation strategy where both the old and new secrets are accepted for a limited time.

    Solution: Rolling Key Strategy

    Steps:
        1.	Use a Key ID or Metadata
        •	When rotating the secret, AWS Secrets Manager assigns a new version or identifier to the secret.
        •	Include this key ID or version in the JWT header as a claim (kid).
        2.	Maintain Both Old and New Secrets
        •	When the secret rotates, keep both the old and new secrets cached during the transition period (e.g., 1–5 minutes).
        •	Use the kid claim in the token to determine which key to use for validation.
        3.	Update Verification Logic
        •	Verify tokens using the secret associated with the kid. If no kid is provided, try both secrets.

//Generate Tokens with a kid:
val newSecret = "new-secret-key"
val oldSecret = "old-secret-key"

val claim = JwtClaim(subject = Some(username)).issuedNow.expiresIn(300)
val token = Jwt.encode(claim, newSecret, JwtAlgorithm.HS512)
  .withHeader(JwtHeader(JwtAlgorithm.HS512, Map("kid" -> "new-secret-key")))

//Verify Tokens with Rolling Keys:
val keys = Map(
  "old-secret-key" -> oldSecret,
  "new-secret-key" -> newSecret
)

def verifyToken(token: String): Boolean = {
  Jwt.decode(token, keys.values.toSeq, Seq(JwtAlgorithm.HS512)) match {
    case Success(decoded) =>
      val kid = decoded.header.get("kid").map(_.as[String])
      kid match {
        case Some(keyId) if keys.contains(keyId) =>
          Jwt.decode(token, keys(keyId), Seq(JwtAlgorithm.HS512)).isSuccess
        case _ =>
          false // Invalid key
      }
    case Failure(_) => false
  }
}

Alternative: Fallback Validation Without kid
If kid is not included in the token:
	•	Attempt validation with both secrets sequentially:

def verifyWithFallback(token: String, oldSecret: String, newSecret: String): Boolean = {
  Jwt.decode(token, oldSecret, Seq(JwtAlgorithm.HS512)).isSuccess ||
  Jwt.decode(token, newSecret, Seq(JwtAlgorithm.HS512)).isSuccess
}

Set a Transition Period
	•	Maintain both the old and new secrets for a configurable transition period (e.g., 5 minutes).
	•	After the transition period, discard the old secret.

Example Workflow for Rotation and Validation
	1.	Pre-Rotation:
	•	All servers use oldSecret for both signing and verification.
	2.	During Rotation:
	•	AWS Secrets Manager updates the secret.
	•	Servers fetch and cache both oldSecret and newSecret.
	3.	Post-Rotation:
	•	Servers use newSecret for signing.
	•	Servers verify tokens with both oldSecret and newSecret.
	4.	End of Transition Period:
	•	Servers discard oldSecret and use newSecret exclusively.


* Figure out token expiry

## Running

We need to run some docker images to simulate services in AWS locally
```
scripts/aws_local_start.sh
```

Stopping:

```
docker-compose down
```