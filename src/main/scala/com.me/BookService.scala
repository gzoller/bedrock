package com.me

import zio.*
import zio.http.*
import zio.http.codec.*
import zio.http.codec.PathCodec.*
import zio.http.endpoint.Endpoint
import zio.http.endpoint.openapi.*

case class BookService( bookRepo: BookRepo ):

  val endpoint = Endpoint((RoutePattern.GET / "books") ?? Doc.p("Route for querying books"))
    .query(HttpCodec.query[String]("q").examples (("example1", "scala"), ("example2", "zio")) ?? Doc.p(
          "Query parameter for searching books"
        ))
    .out[List[Book]](Doc.p("List of books matching the query")) ?? Doc.p(
      "Endpoint to query books based on a search query"
    )
  val booksRoute = endpoint.implementHandler(handler((query: String) => bookRepo.find(query)))

  val hello_endpoint = Endpoint((RoutePattern.GET / "hello") ?? Doc.p("Say hello to the people"))
    .out[String](Doc.p("Just a hello message"))
  val helloRoute = hello_endpoint.implementHandler(handler{(_:Unit) => "Hello!"})

  val swaggerRoutes = 
    if com.me.MyBuildInfo.isProd then
      Routes.empty // disable Swagger for prod deployment
    else
      val openAPI       = OpenAPIGen.fromEndpoints(title = "Library API", version = "1.0", endpoint, hello_endpoint)
      SwaggerUI.routes("docs" / "openapi", openAPI)
  val routes        = Routes(booksRoute, helloRoute) ++ swaggerRoutes