package com.me

import zio.*
import zio.http.*
import zio.http.codec.*
import zio.http.codec.PathCodec.*
import zio.http.endpoint.{AuthType,Endpoint}
import zio.http.endpoint.openapi.*
import zio.http.endpoint.openapi.OpenAPI.SecurityScheme
import Authentication.*

case class BookService( bookRepo: BookRepo ):

  val authHeaderDoc = Doc.p("Requires an `Authorization: Bearer <token>` header to access this endpoint.")
  val bearerAuthScheme = OpenAPI.SecurityScheme.Http(
    scheme = "bearer",
    bearerFormat = Some("JWT"), // Optional: specify the token format
    description = Some(Doc.p("Use a Bearer token for authentication."))
  )

  // --------- Search for books 
  val book_endpoint = Endpoint((RoutePattern.GET / "books") ?? (Doc.p("Route for querying books" + authHeaderDoc)))
    .query(HttpCodec.query[String]("q").examples (("example1", "scala"), ("example2", "zio")) ?? Doc.p(
          "Query parameter for searching books"
        ))
    .out[List[Book]](Doc.p("List of books matching the query")) 
    .auth[AuthType.Bearer](AuthType.Bearer)
    ?? Doc.p(
      "Endpoint to query books based on a search query"
    )
  val book_handler: Handler[String, Nothing, String, List[Book]] = handler { (query: String) =>
    withContext((user: String) => bookRepo.find(query) )
  }
  val booksRoute = Routes(book_endpoint.implementHandler(book_handler)) @@ bearerAuthWithContext

  // --------- Hello message
  // val helloRoute = hello_endpoint.implementHandler(handler((_:Unit) => ZIO.succeed("Hello, World!")))
  val hello_endpoint = Endpoint(RoutePattern.GET / "hello" ?? (Doc.p("Say hello to the people") + authHeaderDoc))
    .out[String](MediaType.text.plain, Doc.p("Just a hello message")) // force plaintext response, not JSON
    .auth[AuthType.Bearer](AuthType.Bearer)
  val hello_handler: Handler[String, Nothing, Unit, String] = handler { (_: Unit) =>
    withContext((user: String) => s"Hello, World, $user!")
  }
  val helloRoute = Routes(hello_endpoint.implementHandler(hello_handler)) @@ bearerAuthWithContext

  // --------- Login message
  val login_endpoint = Endpoint((RoutePattern.GET / "login") ?? Doc.p("Mock of a user login form to obtain auth token"))
    .out[String](MediaType.text.plain, Doc.p("Got me a token!")) // force plaintext response, not JSON
  val loginRoute = login_endpoint.implementHandler(handler{(_:Unit) => ZIO.succeed(jwtEncode(USER_ID, SECRET_KEY))})
    //FOO: Success(JwtClaim({"sub":"bogus_user"}, None, None, None, Some(1733862597), None, Some(1733862297), None))
  

  // --------- Swagger, if non-prod
  /* 
val swaggerRoute = SwaggerUI
  .fromEndpoints(List(hello_endpoint), "Hello API", "1.0.0")
  .withSecurityScheme("BearerAuth", bearerAuthScheme)            // Register the BearerAuth scheme
  .withSecurityRequirement("BearerAuth", hello_endpoint) 
   */
  val swaggerRoutes = 
    if com.me.MyBuildInfo.isProd then
      Routes.empty // disable Swagger for prod deployment
    else
      // val openAPI = OpenAPIGen.fromEndpoints(title = "Library API", version = "1.0", book_endpoint, hello_endpoint, login_endpoint)
      val openAPIRaw = OpenAPI.fromJson("""
{
  "openapi" : "3.1.0",
  "info" : {
    "title" : "Library API",
    "version" : "1.0"
  },
  "paths" : {
    "/books" : {
      "description" : "Endpoint to query books based on a search query\n\n",
      "get" : {
        "description" : "Endpoint to query books based on a search query\n\nRoute for querying books\n\n",
        "security": [
          {
            "BearerAuth": []
          }
        ],
        "parameters" : [
            {
            "name" : "q",
            "in" : "query",
            "description" : "Query parameter for searching books\n\n",
            "required" : true,
            "schema" :
              {
              "type" :
                "string"
            },
            "examples" : {
              "example1" :
                {
                "value" : "scala"
              },
              "example2" :
                {
                "value" : "zio"
              }
            },
            "allowReserved" : false,
            "style" : "form"
          }
        ],
        "responses" : {
          "200" :
            {
            "description" : "List of books matching the query\n\n",
            "content" : {
              "application/json" : {
                "schema" :
                  {
                  "type" :
                    "array",
                  "items" : {
                    "$ref" : "#/components/schemas/Book"
                  }
                }
              }
            }
          }
        }
      }
    },
    "/hello" : {
      "get" : {
        "description" : "Say hello to the people\n\nRequires an `Authorization: Bearer <token>` header to access this endpoint.\n\n",
        "responses" : {
          "200" :
            {
            "description" : "Just a hello message\n\n",
            "security": [
              {
                "BearerAuth": []
              }
            ],
            "responses": {
              "200": {
                "description": "Successful response"
              },
              "401": {
                "description": "Unauthorized"
              }
            },
            "content" : {
              "text/plain" : {
                "schema" :
                  {
                  "type" :
                    "string"
                }
              }
            }
          }
        }
      }
    },
    "/login" : {
      "get" : {
        "description" : "Mock of a user login form to obtain auth token\n\n",
        "responses" : {
          "200" :
            {
            "description" : "Got me a token!\n\n",
            "content" : {
              "text/plain" : {
                "schema" :
                  {
                  "type" :
                    "string"
                }
              }
            }
          }
        }
      }
    }
  },
  "components" : {
    "securitySchemes": {
      "BearerAuth": {
        "type": "http",
        "scheme": "bearer",
        "bearerFormat": "JWT",
        "description": "Provide a Bearer token for authorization."
      }
    },
    "schemas" : {
      "Book" :
        {
        "type" :
          "object",
        "properties" : {
          "title" : {
            "type" :
              "string"
          },
          "authors" : {
            "type" :
              "array",
            "items" : {
              "type" :
                "string"
            }
          },
          "year" : {
            "type" :
              "integer",
            "format" : "int32"
          }
        },
        "required" : [
          "title",
          "authors",
          "year"
        ]
      }
    }
  }
}      """)
      val openAPI = openAPIRaw.getOrElse(null)
      println(openAPI.toJsonPretty)
      SwaggerUI.routes("docs" / "openapi", openAPI)

  // --------- Bundle up all routes
  val routes        = Routes(loginRoute) ++ booksRoute ++ helloRoute ++ swaggerRoutes