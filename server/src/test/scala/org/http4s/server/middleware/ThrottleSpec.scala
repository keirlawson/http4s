package org.http4s.server.middleware

import cats.effect.{IO, Timer}
import cats.effect.laws.util.TestContext
import org.http4s.{Http4sSpec, HttpApp, Request, Status}
import cats.implicits._
import org.http4s.dsl.io._
import cats.effect.IO.ioEffect
import org.specs2.matcher.FutureMatchers
import scala.concurrent.duration._
import org.specs2.concurrent.ExecutionEnv
import Throttle._

class ThrottleSpec(implicit ee: ExecutionEnv) extends Http4sSpec with FutureMatchers {
  "local TokenBucket" should {

    //FIXME add tests for RequestLimiters

    //FIXME add test for taking multiple tokens
    "contain initial number of tokens equal to specified capacity" in {
      val ctx = TestContext()
      val testTimer: Timer[IO] = ctx.timer[IO]

      val someRefillTime = 1234.milliseconds
      val capacity = 5
      val createBucket =
        TokenBucket.local[IO](capacity, someRefillTime)(ioEffect, testTimer.clock)

      val takeExtraToken = createBucket
        .flatMap(testee => {
          val takeFiveTokens: IO[List[TokenAvailability]] =
            (1 to 5).toList.traverse(_ => testee.takeTokens(1))
          val checkTokensUpToCapacity =
            takeFiveTokens.map(tokens =>
              tokens must contain(TokensAvailable: TokenAvailability).forall)
          checkTokensUpToCapacity *> testee.takeTokens(1)
        })

      val result = takeExtraToken.unsafeToFuture()

      result must haveClass[TokensUnavailable].await
    }

    "add another token at specified interval when not at capacity" in {
      val ctx = TestContext()
      val testTimer: Timer[IO] = ctx.timer[IO]

      val capacity = 1
      val createBucket =
        TokenBucket.local[IO](capacity, 100.milliseconds)(ioEffect, testTimer.clock)

      val takeTokenAfterRefill = createBucket.flatMap(testee => {
        testee.takeTokens(1) *> testTimer.sleep(101.milliseconds) *> testee.takeTokens(1)
      })

      val result = takeTokenAfterRefill.unsafeToFuture()

      ctx.tick(101.milliseconds)

      result must beEqualTo(TokensAvailable).await
    }

    "not add another token at specified interval when at capacity" in {
      val ctx = TestContext()
      val testTimer: Timer[IO] = ctx.timer[IO]
      val capacity = 5
      val createBucket =
        TokenBucket.local[IO](capacity, 100.milliseconds)(ioEffect, testTimer.clock)

      val takeExtraToken = createBucket.flatMap(testee => {
        val takeFiveTokens: IO[List[TokenAvailability]] = (1 to 5).toList.traverse(_ => {
          testee.takeTokens(1)
        })
        testTimer.sleep(300.milliseconds) >> takeFiveTokens >> testee.takeTokens(1)
      })

      val result = takeExtraToken.unsafeToFuture()

      ctx.tick(300.milliseconds)

      result must haveClass[TokensUnavailable].await
    }

    "only return a single token when only one token available and there are multiple concurrent requests" in {
      val ctx = TestContext()
      val testTimer: Timer[IO] = ctx.timer[IO]
      val capacity = 1
      val createBucket =
        TokenBucket.local[IO](capacity, 100.milliseconds)(ioEffect, testTimer.clock)

      val takeTokensSimultaneously = createBucket.flatMap(testee => {
        (1 to 5).toList.parTraverse(_ => testee.takeTokens(1))
      })

      val result = takeTokensSimultaneously.unsafeToFuture()

      result must contain(TokensAvailable: TokenAvailability).exactly(1.times).await
    }

    "return the time until the next token is available when no token is available" in {
      val ctx = TestContext()
      val testTimer: Timer[IO] = ctx.timer[IO]
      val capacity = 1
      val createBucket =
        TokenBucket.local[IO](capacity, 100.milliseconds)(ioEffect, testTimer.clock)

      val takeTwoTokens = createBucket.flatMap(testee => {
        testee.takeTokens(1) *> testTimer.sleep(75.milliseconds) *> testee.takeTokens(1)
      })

      val result = takeTwoTokens.unsafeToFuture()

      ctx.tick(75.milliseconds)

      result must beEqualTo(TokensUnavailable(Some(25.milliseconds))).await
    }
  }

  "Throttle" should {
    val alwaysOkApp = HttpApp[IO] { _ =>
      Ok()
    }

    "allow a request to proceed when the rate limit has not been reached" in {
      val limitNotReachedBucket = new RequestLimiter[IO, IO] {
        override def takeToken(request: Request[IO]): IO[TokenAvailability] = TokensAvailable.pure[IO]
      }

      val testee = Throttle(limitNotReachedBucket)(alwaysOkApp)
      val req = Request[IO](uri = uri("/"))

      testee(req) must returnStatus(Status.Ok)
    }

    "deny a request when the rate limit had been reached" in {
      val limitReachedBucket = new RequestLimiter[IO, IO] {
        override def takeToken(request: Request[IO]): IO[TokenAvailability] = TokensUnavailable(None).pure[IO]
      }

      val testee = Throttle(limitReachedBucket)(alwaysOkApp)
      val req = Request[IO](uri = uri("/"))

      testee(req) must returnStatus(Status.TooManyRequests)
    }
  }
}
