# bedrock
![Bedrock](docs/Bedrock_Logo.png)
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
* LocalStack support for running locally and integration testing
* Bedrock Auth (OAuth-lite) including:
  - Token rotation/expiration
  - Session (refresh) tokens
  - Role-based endpoint protection
* KMS encryption of secrets (required for HIPAA)



IN-PROGRESS:


DEPLOYMENT/AWS:
* Configure logback to send logs to CloudWatch. May need templated config files to do this.


TODO:
* Integrate with Cognito + AWS API Gateway
* Auto-generate Swagger config (fixed with my own mods to ZIO HTTP...PR pending)
* Figure out packaging (Docker, versioning, deployment, local/AWS)
* Figure out Kuberneties
   * Self-help script: after key rotation, wait 5 minutes and fire a lambda that does:
        * Get count of all running instance in Kube
        * Get count of all SNS topic subscriptions -- they must be the same!
        * Kill subscriptions for unknown servers, kill servers that aren't subscribed (retest)
        * For ea server get its key bundle version. Should be the same
        * Kill/restart any servers with older key bundle version (they missed a message)
* Configure github workflows manually trigger deployments to different envs
* Figure out AWS permissioning so a low-level dev can't deploy to prod but a sr-dev could
* Figure out monitoring
* Figure out advanced logging (eg with queries like splunk)
* Investigate queryable event queue
* Integrate w/RDS

## Running

Be sure you've got Docker running on your local host and that you've run ```sbt docker:publishLocal```
to create a Docker container for Bedrock.  Then you can run the following to launch LocalStack (AWD simulator)
and Bedrock:
```
docker-compose up
```

Stopping:
```
docker-compose down
```