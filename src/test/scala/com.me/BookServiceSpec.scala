package co.blocke.bedrock

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.http.*
import services.db.*
import services.auth.*
import com.typesafe.config.{Config, ConfigFactory}
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim}
import services.auth.{Session, SecretKeyManager, LiveAuthentication, Key}
import services.endpoint.LiveBookEndpoint
import services.db.BookRepo

/**
  * This lovely thing runs the services' routes directly, without the drama
  * of an actual running server.
  */
object BookServiceSpec extends ZIOSpecDefault {

  // implicit val clock: Clock = Clock.systemUTC

  val configLayer: ULayer[Config] =
    ZLayer.succeed(ConfigFactory.load()) // Provide Config independently

  val secretKeyManagerLayer: ZLayer[Any, Nothing, SecretKeyManager] =
    ZLayer.fromZIO {
      for {
        clock <- ZIO.clock // Only depends on Clock
        now <- clock.instant
      } yield new SecretKeyManager {
        override def getSecretKey: ZIO[Any, Throwable, (Key, Option[Key])] = 
          ZIO.succeed((Key("bogus_version","secretKey",now), None))
      }
    }

  val authenticationLayer: ZLayer[Config & SecretKeyManager, Throwable, Authentication] =
    ZLayer.fromZIO {
      for {
        config <- ZIO.service[Config] // Depends on Config
        manager <- ZIO.service[SecretKeyManager] // Depends on SecretKeyManager
        clock <- ZIO.clock // Depends on Clock
        now <- clock.instant
      } yield new LiveAuthentication(config, clock, manager, Key("bogus_version","secretKey",now), None)
    }

  val bookServiceLayer = ZLayer.succeed(
    new BookRepo {
      override def find(query: String): List[Book] = 
        if query == "zio" then        
          List(Book("ZIO in Action", List("John Doe"), 2021))
        else List.empty
    }
  )

  val bookLayer =
    ZLayer.fromZIO {
      for {
        auth <- ZIO.service[Authentication]
        bookRepoMock <- ZIO.service[BookRepo]
      } yield LiveBookEndpoint(auth, bookRepoMock)
    }

  // Compose only the layers needed for Authentication
  val authDependencies =
    configLayer ++ secretKeyManagerLayer

  val authenticationAndRepo =
    authDependencies >>> authenticationLayer ++ bookServiceLayer

  val allLayers =
    authenticationAndRepo >>> bookLayer

  var authToken: String = ""

  def spec = suite("BookServiceSpec")(
    test("Unauthorized access should fail (no token)") {
      val request = Request.get(URL.root / "hello")
      val session = Session("bogus_user") // Create a bogus session
      for {
        bookService <- ZIO.service[LiveBookEndpoint]
        response    <- bookService.helloRoute
                      .provideEnvironment(ZEnvironment(session)) // Inject the session
                      .runZIO(request)                           // Run the route with ZIO environment
        body <- response.body.asString
      } yield assert(response.status)(equalTo(Status.Unauthorized))
    },
    test("Unauthorized access should fail (bad token)") {
      val session = Session("bogus_user") // Create a bogus session
      for {
        clock       <- ZIO.clock
        bogus_token =  Jwt.encode(JwtClaim(subject = Some("bogus_user"))
                         .issuedNow(ClockConverter.dynamicJavaClock(clock))
                         .expiresIn( 300 )(ClockConverter.dynamicJavaClock(clock)), "bogus_key", JwtAlgorithm.HS512)
        request     =  Request.get(URL.root / "hello").addHeader(Header.Authorization.Bearer(bogus_token))
        bookService <- ZIO.service[LiveBookEndpoint]
        response    <- bookService.helloRoute
                      .provideEnvironment(ZEnvironment(session)) // Inject the session
                      .runZIO(request)                           // Run the route with ZIO environment
        body <- response.body.asString
      } yield assert(response.status)(equalTo(Status.Unauthorized))
    },
    test("Login should work and return a token") {
      val request = Request.get(URL.root / "login")
      val result = for {
        bookService <- ZIO.service[LiveBookEndpoint]
        response    <- bookService.loginRoute.run(request)
        body        <- response.body.asString
      } yield (body, assert(response.status)(equalTo(Status.Ok)) && assert(body.length)(isGreaterThan(0)))
      result.flatMap{ (a,b) => 
        authToken = a  // save the token for later tests
        b
        }
    },
    test("authenticated request should return a hello message") {
      val request = Request.get(URL.root / "hello").addHeader(Header.Authorization.Bearer(authToken))
      val expectedResponse = "Hello, World, bogus_user!"
      for {
        bookService <- ZIO.service[LiveBookEndpoint]
        response    <- bookService.helloRoute.run(request)
        body        <- response.body.asString
      } yield assert(response.status)(equalTo(Status.Ok)) &&
        assert(body)(equalTo(expectedResponse))
    },
    test("authenticated request should return a list of books for a valid query") {
      val request = Request.get((URL.root / "books").addQueryParams("q=zio&num=2")).addHeader(Header.Authorization.Bearer(authToken))
      val session = Session("bogus_user") // Create a bogus session
      val expectedResponse = """[{"title":"ZIO in Action","authors":["John Doe"],"year":2021}]"""
      for {
        bookService <- ZIO.service[LiveBookEndpoint]
        response    <- bookService.bookSearchRoute
                      .provideEnvironment(ZEnvironment(session)) // Inject the session
                      .runZIO(request)                           // Run the route with ZIO environment
        body <- response.body.asString
      } yield assert(response.status)(equalTo(Status.Ok)) &&
        assert(body)(equalTo(expectedResponse))
    }
  ).provide(allLayers ++ Runtime.removeDefaultLoggers) @@ TestAspect.sequential
}