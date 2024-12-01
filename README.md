# bedrock
Starting point for REST service

Compile with: ```sbt -Dprod=true "clean;compile;package;assembly"``` to disable Swagger for production deployment

## Features

* ZIO HTTP REST endpoints
* Injectable data store (allows for unit testing w/o real database)
* Switchable Swagger support for prod/non-prod

TODO:
* Auth using tokens (OAuth?)
* HTTPS support
