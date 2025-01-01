package com.me

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.http.*
import zio.http.endpoint.*

/**
  * This lovely thing runs the services' routes directly, without the drama
  * of an actual running server.
  */
object BookServiceSpec extends ZIOSpecDefault {

  val bookRepoMock: BookRepo = new BookRepo {
    override def find(query: String): List[Book] = 
      if query == "zio" then        
        List(Book("ZIO in Action", List("John Doe"), 2021))
      else List.empty
  }

  val bookService = BookService(bookRepoMock, "secretKey")

  var authToken: String = ""

  def spec = suite("BookServiceSpec")(
    test("Unauthorized access should fail") {
      val request = Request.get(URL.root / "hello")
      for {
        response <- bookService.helloRoute.run(request)
        body <- response.body.asString
      } yield assert(response.status)(equalTo(Status.Unauthorized))
    },
    test("Login should work and return a token") {
      val request = Request.get(URL.root / "login")
      val result = for {
        response <- bookService.loginRoute.run(request)
        body <- response.body.asString
      } yield (body, assert(response.status)(equalTo(Status.Ok)) && assert(body.length)(isGreaterThan(0)))
      result.flatMap{ (a,b) => 
        authToken = a
        b
        }
    },
    test("should return a list of books for a valid query") {
      val request = Request.get((URL.root / "books").addQueryParams("q=zio&num=2")).addHeader(Header.Authorization.Bearer(authToken))
      val expectedResponse = """[{"title":"ZIO in Action","authors":["John Doe"],"year":2021}]"""

      for {
        response <- bookService.booksRoute.run(request)
        body <- response.body.asString
      } yield assert(response.status)(equalTo(Status.Ok)) &&
        assert(body)(equalTo(expectedResponse))
    },
    test("should return a hello message") {
      val request = Request.get(URL.root / "hello").addHeader(Header.Authorization.Bearer(authToken))
      val expectedResponse = "Hello, World, bogus_user!"

      for {
        response <- bookService.helloRoute.run(request)
        body <- response.body.asString
      } yield assert(response.status)(equalTo(Status.Ok)) &&
        assert(body)(equalTo(expectedResponse))
    }
  ) @@ TestAspect.sequential
}