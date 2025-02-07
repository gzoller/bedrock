package co.blocke.bedrock
package util

import zio.*
import zio.http.*

object HTTPutil:


  /**
   *  HandlerAspect to intercept a Response and redirect to the URL in the response body.
   */
  val redirectAspect: HandlerAspect[Any, Unit] =
    HandlerAspect.interceptOutgoingHandler(
      Handler.fromFunctionZIO { (response: Response) =>
        response.body.asString.either.flatMap { // Convert failure to Either
          case Left(error) =>
            ZIO.succeed(Response.text(s"Failed to read response body: ${error.getMessage}").status(Status.InternalServerError))
          
          case Right(rawUrl) =>
            URL.decode(rawUrl) match {
              case Right(url) => ZIO.succeed(Response.redirect(url))
              case Left(e)    => ZIO.succeed(Response.text(s"Invalid redirect URL: $rawUrl").status(Status.BadRequest))
            }
        }
      }
    )


  val logRequests: HandlerAspect[Any, Unit] =
    HandlerAspect.interceptIncomingHandler {
      Handler.fromFunctionZIO[Request] { request =>
        val headers = request.headers.map(h => s"${h.headerName}: ${h.renderedValue}").mkString("\n")
        val logMsg =
          s"""
             |🔍 Received Request:
             |  - Method: ${request.method}
             |  - URL: ${request.url}
             |  - Headers:
             |  ${headers}
             |""".stripMargin

        Console.printLine(logMsg).orDie *> ZIO.succeed((request,()))
      }
    }