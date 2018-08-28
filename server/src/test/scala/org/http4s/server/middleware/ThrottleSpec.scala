package org.http4s.server.middleware

import cats.effect.{IO, Timer}
import cats.effect.laws.util.TestContext
import org.http4s.{Http4sSpec, HttpApp, Request, Status}
import scala.concurrent.duration._
import cats.implicits._
import org.http4s.dsl.io._
import cats.effect.IO.ioConcurrentEffect

//FIXME look at testcontext in cats-effect
class ThrottleSpec extends Http4sSpec {
  "LocalTokenBucket" should {

    val ctx = TestContext()
    implicit val testTimer: Timer[IO] = ctx.timer[IO]

    "thing" in {
      1 must_== 1
    }

    "contain initial number of tokens equal to specified capacity" in {
      println("har")
      val someRefillTime = 1234.milliseconds
      val capacity = 5
      val createBucket = TokenBucket.local[IO](capacity, someRefillTime)(ioConcurrentEffect(testTimer), testTimer)
      println("here")

      IO { ctx.tick() } *> createBucket.evalMap(testee => {
        println("her")
        val takeFiveTokens: IO[List[TokenAvailability]] = (1 to 5).toList.traverse(_ => testee.takeToken)
        val checkTokensUpToCapacity = takeFiveTokens.map(tokens => tokens must not contain TokenUnavailable)
        checkTokensUpToCapacity >> testee.takeToken
      }).compile.toList.map(_.head) must returnValue(TokenUnavailable)
    }

//    "add another token at specified interval when not at capacity" in {
//      val capacity = 1
//      val createBucket =  TokenBucket.local[IO](capacity, 100.milliseconds)(ioConcurrentEffect(testTimer), testTimer)
//
//      val takeTokenAfterRefill = createBucket.evalMap(testee => {
//        testee.takeToken >> IO { ctx.tick(100.milliseconds) } >> testee.takeToken
//      })
//
//      takeTokenAfterRefill.compile.toList.map(_.head) must returnValue(TokenAvailable)
//    }

//    //FIXME if refill happens to occur in middle of taking tokens test will fail
//    "not add another token at specified interval when at capacity" in {
//      val capacity = 5
//      val createBucket = TokenBucket.local[IO](capacity, 100.milliseconds)(ioConcurrentEffect(testTimer), testTimer)
//
//      val takeExtraToken = createBucket.evalMap(testee => {
//        val takeFiveTokens: IO[List[TokenAvailability]] = (1 to 5).toList.traverse(_ => testee.takeToken)
//        IO { Thread.sleep(500) } >> takeFiveTokens >> testee.takeToken
//      })
//
//      takeExtraToken.compile.toList.map(_.head) must returnValue(TokenUnavailable)
//    }
  }

  "Throttle" should {
    val alwaysOkApp = HttpApp[IO] { _ =>
      Ok()
    }

    "allow a request to proceed when the rate limit has not been reached" in {
      val limitNotReachedBucket = new TokenBucket[IO] {
        override def takeToken
          : IO[TokenAvailability] = TokenAvailable.pure[IO]
      }

      val testee = Throttle(limitNotReachedBucket)(alwaysOkApp)
      val req = Request[IO](uri = uri("/"))

      testee(req) must returnStatus(Status.Ok)
    }

    "deny a request when the rate limit had been reached" in {
      val limitReachedBucket = new TokenBucket[IO] {
        override def takeToken
        : IO[TokenAvailability] = TokenUnavailable.pure[IO]
      }

      val testee = Throttle(limitReachedBucket)(alwaysOkApp)
      val req = Request[IO](uri = uri("/"))

      testee(req) must returnStatus(Status.TooManyRequests)
    }
  }
}
