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
* LocalStack support for running locally and integration testing

IN-PROGRESS:
* Figure out how to do integration testing in sbt
* Separate unit and integration tests with mocks for unit
* Figure out config w/env vars that override, eg IS_LIVE to know if we're running live or locally

DEPLOYMENT/AWS:
* Configure logback to send logs to CloudWatch. May need templated config files to do this.

TODO:
* Auto-generate Swagger config (fixed with my own mods to ZIO HTTP...PR pending)
* Hide server.crt/server.key files but ensure they're published & packaged correctly
* Figure out OAuth
* Figure out packaging (Docker, versioning, deployment, local/AWS)
* Figure out Kuberneties
* Figure out Teraform
* Figure out monitoring
* Figure out advanced logging (eg with queries like splunk)
* Investingate queryable event queue
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