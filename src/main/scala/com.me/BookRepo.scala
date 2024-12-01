package com.me

import zio.*

trait BookRepo:
  def find(q: String): List[Book] 

object BookRepoStd extends BookRepo:
  val book1 = Book("Programming in Scala", List("Martin Odersky", "Lex Spoon", "Bill Venners", "Frank Sommers"), 2018)
  val book2 = Book("Zionomicon", List("John A. De Goes", "Adam Fraser"), 2023)
  val book3 = Book("Effect-Oriented Programming", List("Bill Frasure", "Bruce Eckel", "James Ward"), 2009)
  def find(q: String): List[Book] = 
    if (q.toLowerCase == "scala") List(book1, book2, book3)
    else if (q.toLowerCase == "zio") List(book2, book3)
    else List.empty

  val live: ZLayer[Any, Nothing, BookRepo] = ZLayer.succeed(BookRepoStd)


object BookRepoAlt extends BookRepo:
  val book1 = Book("Lord of the Rings", List("J. R. Tolkien"), 2018)
  val book2 = Book("Harry Potter", List("J. K. Rowling"), 2023)
  val book3 = Book("Holy Bible", List("God"), 2009)
  def find(q: String): List[Book] = 
    if (q.toLowerCase == "good") List(book1, book2, book3)
    else if (q.toLowerCase == "fiction") List(book1, book2)
    else List.empty

  val live: ZLayer[Any, Nothing, BookRepo] = ZLayer.succeed(BookRepoAlt)
