package com.me

import zio.*
import zio.http.*
import zio.http.codec.*
import zio.http.codec.PathCodec.*
import zio.http.endpoint.Endpoint
import zio.http.endpoint.openapi.*

object MyRestService:

  val endpoint = Endpoint((RoutePattern.GET / "books") ?? Doc.p("Route for querying books"))
    .query(HttpCodec.query[String]("q").examples (("example1", "scala"), ("example2", "zio")) ?? Doc.p(
          "Query parameter for searching books"
        ))
    .out[List[Book]](Doc.p("List of books matching the query")) ?? Doc.p(
      "Endpoint to query books based on a search query"
    )
  val hello_endpoint = Endpoint((RoutePattern.GET / "hello") ?? Doc.p("Say hello to the people"))
    .out[String](Doc.p("Just a hello message"))

  val booksRoute = endpoint.implementHandler(handler((query: String) => BookRepo.find(query)))
  val helloRoute = hello_endpoint.implementHandler(handler{(_:Unit) => "Hello!"})

  val openAPI       = OpenAPIGen.fromEndpoints(title = "Library API", version = "1.0", endpoint, hello_endpoint)
  val swaggerRoutes = SwaggerUI.routes("docs" / "openapi", openAPI)
  val routes        = Routes(booksRoute, helloRoute) ++ swaggerRoutes