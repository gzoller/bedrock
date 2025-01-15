package co.blocke.bedrock

import zio.schema.DeriveSchema
import zio.schema.Schema

case class Book(title: String, authors: List[String], year: Int)

object Book:
  implicit val schema: Schema[Book] = DeriveSchema.gen[Book]
