package org.http4s.server.middleware

import cats.effect.{IO, Timer}
import cats.effect.laws.util.TestContext
import org.http4s.{Http4sSpec, HttpApp, Request, Status}
import cats.implicits._
import org.http4s.dsl.io._
import cats.effect.IO.ioConcurrentEffect
import org.specs2.matcher.FutureMatchers
import scala.concurrent.duration._
import org.specs2.concurrent.ExecutionEnv

class ThrottleSpec(implicit ee: ExecutionEnv) extends Http4sSpec with FutureMatchers {
  "LocalTokenBucket" should {

    "contain initial number of tokens equal to specified capacity" in {
      val ctx = TestContext()
      val testTimer: Timer[IO] = ctx.timer[IO]

      val someRefillTime = 1234.milliseconds
      val capacity = 5
      val createBucket =
        TokenBucket.local[IO](capacity, someRefillTime)(ioConcurrentEffect(testTimer), testTimer)

      val result = createBucket
        .evalMap(testee => {
          val takeFiveTokens: IO[List[TokenAvailability]] =
            (1 to 5).toList.traverse(_ => testee.takeToken)
          val checkTokensUpToCapacity =
            takeFiveTokens.map(tokens => tokens must not contain TokenUnavailable)
          checkTokensUpToCapacity *> testee.takeToken
        })
        .compile
        .toList
        .map(_.head)
        .unsafeToFuture()

      ctx.tick()

      result must beEqualTo(TokenUnavailable).await
    }

    "add another token at specified interval when not at capacity" in {
      val ctx = TestContext()
      val testTimer: Timer[IO] = ctx.timer[IO]

      val capacity = 1
      val createBucket =
        TokenBucket.local[IO](capacity, 100.milliseconds)(ioConcurrentEffect(testTimer), testTimer)

      val takeTokenAfterRefill = createBucket.evalMap(testee => {
        testee.takeToken *> testTimer.sleep(101.milliseconds) *> testee.takeToken
      })

      val result = takeTokenAfterRefill.compile.toList.map(_.head).unsafeToFuture()

      ctx.tick(101.milliseconds)

      result must beEqualTo(TokenAvailable).await
    }

    "not add another token at specified interval when at capacity" in {
      val ctx = TestContext()
      val testTimer: Timer[IO] = ctx.timer[IO]
      val capacity = 5
      val createBucket =
        TokenBucket.local[IO](capacity, 100.milliseconds)(ioConcurrentEffect(testTimer), testTimer)

      val takeExtraToken = createBucket.evalMap(testee => {
        val takeFiveTokens: IO[List[TokenAvailability]] = (1 to 5).toList.traverse(_ => {
          testee.takeToken
        })
        testTimer.sleep(300.milliseconds) >> takeFiveTokens >> testee.takeToken
      })

      val result = takeExtraToken.compile.toList.map(_.head).unsafeToFuture()

      ctx.tick(300.milliseconds)

      result must beEqualTo(TokenUnavailable).await
    }
  }

  "Throttle" should {
    val alwaysOkApp = HttpApp[IO] { _ =>
      Ok()
    }

    "allow a request to proceed when the rate limit has not been reached" in {
      val limitNotReachedBucket = new TokenBucket[IO] {
        override def takeToken: IO[TokenAvailability] = TokenAvailable.pure[IO]
      }

      val testee = Throttle(limitNotReachedBucket)(alwaysOkApp)
      val req = Request[IO](uri = uri("/"))

      testee(req) must returnStatus(Status.Ok)
    }

    "deny a request when the rate limit had been reached" in {
      val limitReachedBucket = new TokenBucket[IO] {
        override def takeToken: IO[TokenAvailability] = TokenUnavailable.pure[IO]
      }

      val testee = Throttle(limitReachedBucket)(alwaysOkApp)
      val req = Request[IO](uri = uri("/"))

      testee(req) must returnStatus(Status.TooManyRequests)
    }
  }
}
