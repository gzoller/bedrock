package com.me

import zio.*

import zio.cli.*
import zio.cli.HelpDoc.Span.text
import zio.http.endpoint.cli.HttpCliApp


object TestCliApp extends zio.cli.ZIOCliDefault {
  val cliApp =
    HttpCliApp
      .fromEndpoints(
        name = "books-search",
        version = "0.0.1",
        summary = HelpDoc.Span.text("Books search CLI"),
        footer = HelpDoc.p("Copyright 2024"),
        host = "localhost",
        port = 8080,
        endpoints = Chunk(MyRestService.endpoint, MyRestService.hello_endpoint),
        cliStyle = true,
      )
      .cliApp
}