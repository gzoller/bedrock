# bedrock
Starting point for REST service

## Features

* ZIO HTTP REST endpoints
* Injectable data store (allows for unit testing w/o real database)
* Switchable Swagger support for prod/non-prod
    Compile with: ```sbt -Dprod=true "clean;compile;package;assembly"``` to disable Swagger for production deployment
* Test examples -- how to test a REST service
* [HTTPS Support](docs/https.md)
* Auth using tokens (protect certain endpoints with an auth token)
* Get access to the encoded user id from jtw token in handler

TODO:
* Auto-generate Swagger config (fixed with my own mods to ZIO HTTP...PR pending)
* Fix tests
* Hide server.crt/server.key files but ensure they're published & packaged correctly

## Running

We need to run some docker images to simulate services in AWS locally
```
docker-compose up -d
```

Stopping:

```
docker-compose down
```