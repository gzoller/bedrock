package com.me

import zio.schema.{DeriveSchema, Schema}

case class Book(title: String, authors: List[String], year: Int)

object Book:
  implicit val schema: Schema[Book] = DeriveSchema.gen[Book]
