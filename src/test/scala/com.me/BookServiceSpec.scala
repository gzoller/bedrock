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

  val bookService = BookService(bookRepoMock)

  def spec = suite("BookServiceSpec")(
    test("should return a list of books for a valid query") {
      val request = Request.get((URL.root / "books").addQueryParams("q=zio"))
      val expectedResponse = """[{"title":"ZIO in Action","authors":["John Doe"],"year":2021}]"""

      for {
        response <- bookService.booksRoute.run(request)
        body <- response.body.asString
      } yield assert(response.status)(equalTo(Status.Ok)) &&
        assert(body)(equalTo(expectedResponse))
    },
    test("should return a hello message") {
      val request = Request.get(URL.root / "hello")
      val expectedResponse = "\"Hello!\""

      for {
        response <- bookService.helloRoute.run(request)
        body <- response.body.asString
      } yield assert(response.status)(equalTo(Status.Ok)) &&
        assert(body)(equalTo(expectedResponse))
    }
  )
}