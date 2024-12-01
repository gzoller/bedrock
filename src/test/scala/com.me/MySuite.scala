package com.me

import zio._
import zio.test._
import zio.test.Assertion._
import zio.test.{test, _}
import com.me.{Config => MConfig}

object MainSpec extends ZIOSpecDefault {
  def spec = suite("MainSpec")(
    test("fn should return the length of the list and print each element with prefix") {
      val config = MConfig(prefix = "TestPrefix:")
      val strings = List("Hello", "world", "from", "ZIO")
      val expectedOutput = strings.map(str => s"${config.prefix} $str").mkString("\n") + "\n"

      for {
        result <- Main.fn(strings)
          .provideSomeLayer[Scope](
                  ZLayer.succeed(BookRepoStd) ++ 
                  ZLayer.succeed(config)
                  )
        output <- TestConsole.output
      } yield assert(result)(equalTo(strings.length)) &&
        assert(output.mkString)(equalTo(expectedOutput))
    }.provideSomeLayer[Scope](Scope.default)
  )
}