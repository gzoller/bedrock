# bedrock
Starting point for REST service

## Features

* ZIO HTTP REST endpoints
* Injectable data store (allows for unit testing w/o real database)
* Switchable Swagger support for prod/non-prod
    Compile with: ```sbt -Dprod=true "clean;compile;package;assembly"``` to disable Swagger for production deployment
* Test examples -- how to test a REST service

TODO:
* Auth using tokens (OAuth?)
* HTTPS support
