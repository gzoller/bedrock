package co.blocke.bedrock
package services
package endpoint

import scala.collection.immutable.ListMap

import zio.*
import zio.http.*
import zio.http.codec.*
import zio.http.codec.PathCodec.*
import zio.http.endpoint.AuthType
import zio.http.endpoint.Endpoint
import zio.http.endpoint.openapi.*
import zio.http.endpoint.openapi.OpenAPI.Components
import zio.http.endpoint.openapi.OpenAPI.Key
import zio.http.endpoint.openapi.OpenAPI.ReferenceOr
import zio.http.endpoint.openapi.OpenAPI.SecurityScheme.*
import zio.http.endpoint.AuthType.Bearer
import zio.schema.*

import auth.*
import db.BookRepo

trait BookEndpoint:
  def routes: Routes[Any, Response]


final case class LiveBookEndpoint( auth: Authentication, bookRepo: BookRepo ) extends BookEndpoint:

  private val authHeaderDoc: Doc = Doc.p("Requires an `Authorization: Bearer <token>` header to access this endpoint.")
  private val bearerAuthScheme: Http = OpenAPI.SecurityScheme.Http(
    scheme = "bearer",
    bearerFormat = Some("JWT"), // Optional: specify the token format
    description = Some(Doc.p("Use a Bearer token for authentication."))
  )

  // --------- Search for books 
  //===============================================================
  private val book_endpoint: Endpoint[Unit, (String, Int), ZNothing, List[Book], Bearer.type] = Endpoint((RoutePattern.GET / "api" / "books") ?? (Doc.p("Route for querying books" + authHeaderDoc)))
    .query(HttpCodec.query[String]("q").examples (("example1", "scala"), ("example2", "zio")) ?? Doc.p(
          "Query parameter for searching books"
        ))
    .query(HttpCodec.query[Int]("num").examples (("example1", 1), ("example2", 2)) ?? Doc.p(
          "Bogus second parameter--does nothing"
        ))
    // .in[Book] << Example showing inclusion of a request body (json), which would be passed to handler with query params
    .out[List[Book]](Doc.p("List of books matching the query")) 
    .auth(AuthType.Bearer)
    ?? Doc.p(
      "Endpoint to query books based on a search query"
    )

  // The String here in the Handler R type is the user id, pulled from the decrypted token, ie. the "subject".
  // In a production server this could be a more complex session object, or a session id, etc.
  private val book_handler: Handler[String, Nothing, (String,Int), List[Book]] = handler { (query: String, num: Int) =>
    withContext((userId: String) => bookRepo.find(query) )
  }
  private val bookSearchRoute: Routes[Any, Nothing] = Routes(book_endpoint.implementHandler(book_handler)) @@ auth.bedrockProtected()


  // --------- Hello message
  //===============================================================
  private val hello_endpoint: Endpoint[Unit, Unit, ZNothing, String, Bearer.type] =
    Endpoint(RoutePattern.GET / "api" / "hello" ??
      (Doc.p("Say hello to the people") + authHeaderDoc))
      .out[String](MediaType.text.plain, Doc.p("Just a hello message")) // force plaintext response, not JSON
      .auth[AuthType.Bearer](AuthType.Bearer)

  private val hello_handler: Handler[String, Nothing, Unit, String] = handler { (_: Unit) =>
    ZIO.serviceWithZIO[String] { userId =>
      auth.getSession(userId)
        .flatMap { session =>
          if (session.profile.given_name.nonEmpty)
            ZIO.succeed(s"""Hello, World, ${session.profile.given_name.getOrElse("Unknown")}!""")
          else
            ZIO.succeed("No session, you pirate!")
        }
        .catchAll(_ => ZIO.succeed("Something failed badly..."))
    }
  }

  private val helloRoute: Routes[Any, Nothing] = Routes(hello_endpoint.implementHandler(hello_handler))
    @@ auth.bedrockProtected()

  
  // --------- Swagger, if non-prod
  //===============================================================
  val swaggerRoutes: Routes[Any, Response] = 
    if co.blocke.bedrock.MyBuildInfo.isProd then
      Routes.empty // disable Swagger for prod deployment
    else
      val openAPIFirst = OpenAPIGen.fromEndpoints(title = "Library API", version = "1.0", book_endpoint, hello_endpoint) //, login_endpoint)
      val openAPI = openAPIFirst
          .copy(
            components = Some(Components(
              securitySchemes = ListMap(Key.fromString("BearerAuth").get -> ReferenceOr.Or(bearerAuthScheme)),
              schemas = openAPIFirst.components.get.schemas
              ))
          )
      SwaggerUI.routes("docs" / "openapi", openAPI)

  // --------- Bundle up all routes
  def routes: Routes[Any, Response] = {
    // Provide a default SessionContext for unsecured routes
    val unsecuredRoutes = swaggerRoutes
//    val unsecuredRoutes = loginRoute ++ swaggerRoutes

    // Secured routes with bearerAuthWithContext
    val securedRoutes = bookSearchRoute ++ helloRoute 

    // Combine both
    unsecuredRoutes ++ securedRoutes
  }


object BookEndpoint:
  def live: ZLayer[Authentication & BookRepo, Nothing, BookEndpoint] =
    ZLayer.fromZIO {
      for {
        auth <- ZIO.service[Authentication]
        bookRepo <- ZIO.service[BookRepo]
      } yield LiveBookEndpoint(auth, bookRepo)
    }

/* 
NOTE: As of this writing, ZIO HTTP does not yet support the latest OpenAPI and more specifically will not
correctly generate the "security" block of JSON needed for HTTPS.  The JSON below is a working sample.
I've modified a build of ZIO HTTPS with a fix and issued a PR into the main project, if the maintainers choose
to accept it. Otherwise JSON will need to be manually generated/manipulated to add the security block.

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
       */