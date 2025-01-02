package com.me
package services
package db

import zio.*

trait BookRepo:
  def find(q: String): List[Book] 


final case class MockBookRepo() extends BookRepo:
  /**
    * Stupid hard-wired implementation. In reality this would be a DBMS with CRUD calls.
    */
  val book1 = Book("Programming in Scala", List("Martin Odersky", "Lex Spoon", "Bill Venners", "Frank Sommers"), 2018)
  val book2 = Book("Zionomicon", List("John A. De Goes", "Adam Fraser"), 2023)
  val book3 = Book("Effect-Oriented Programming", List("Bill Frasure", "Bruce Eckel", "James Ward"), 2009)
  def find(q: String): List[Book] = 
    if (q.toLowerCase == "scala") List(book1, book2, book3)
    else if (q.toLowerCase == "zio") List(book2, book3)
    else List.empty


object BookRepo:
  // Creates a live instance for dependency injection in main program
  val mock: ULayer[BookRepo] =
    ZLayer.succeed(MockBookRepo())
