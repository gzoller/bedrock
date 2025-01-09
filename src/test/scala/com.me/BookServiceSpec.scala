package com.me

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.http.*
import services.endpoint.*
import services.db.*
import services.auth.*
import com.typesafe.config.ConfigFactory
import java.time.Instant
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim}
import java.time.Clock

/**
  * This lovely thing runs the services' routes directly, without the drama
  * of an actual running server.
  */
object BookServiceSpec extends ZIOSpecDefault {

  implicit val clock: Clock = Clock.systemUTC

  val bookRepoMock: BookRepo = new BookRepo {
    override def find(query: String): List[Book] = 
      if query == "zio" then        
        List(Book("ZIO in Action", List("John Doe"), 2021))
      else List.empty
  }

  val mockSecretKeyManager = new SecretKeyManager {
    override def getSecretKey: ZIO[Any, Throwable, (Key, Option[Key])] = 
      ZIO.succeed((Key("bogus_version","secretKey",Instant.now()), None))
  }

  val appConfig = ConfigFactory.load()

  val liveAuth = LiveAuthentication(
    appConfig, 
    mockSecretKeyManager,
    Key("bogus_version","secretKey",Instant.now()), 
    None)

  val bookService = LiveBookEndpoint(liveAuth, bookRepoMock)

  var authToken: String = ""

  def spec = suite("BookServiceSpec")(
    test("Unauthorized access should fail (no token)") {
      val request = Request.get(URL.root / "hello")
      val session = Session("bogus_user") // Create a bogus session

      for {
        response <- bookService.helloRoute
                      .provideEnvironment(ZEnvironment(session)) // Inject the session
                      .runZIO(request)                           // Run the route with ZIO environment
        body <- response.body.asString
      } yield assert(response.status)(equalTo(Status.Unauthorized))
    },
    test("Unauthorized access should fail (bad token)") {
      val bogus_token = Jwt.encode(JwtClaim(subject = Some("bogus_user")).issuedNow.expiresIn( 300 ), "bogus_key", JwtAlgorithm.HS512)
      val request = Request.get(URL.root / "hello").addHeader(Header.Authorization.Bearer(bogus_token))
      val session = Session("bogus_user") // Create a bogus session

      for {
        response <- bookService.helloRoute
                      .provideEnvironment(ZEnvironment(session)) // Inject the session
                      .runZIO(request)                           // Run the route with ZIO environment
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
    test("authenticated request should return a list of books for a valid query") {
      val request = Request.get((URL.root / "books").addQueryParams("q=zio&num=2")).addHeader(Header.Authorization.Bearer(authToken))
      val session = Session("bogus_user") // Create a bogus session
      val expectedResponse = """[{"title":"ZIO in Action","authors":["John Doe"],"year":2021}]"""

      for {
        response <- bookService.bookSearchRoute
                      .provideEnvironment(ZEnvironment(session)) // Inject the session
                      .runZIO(request)                           // Run the route with ZIO environment
        body <- response.body.asString
      } yield assert(response.status)(equalTo(Status.Ok)) &&
        assert(body)(equalTo(expectedResponse))
    },
    test("authenticated request should return a hello message") {
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