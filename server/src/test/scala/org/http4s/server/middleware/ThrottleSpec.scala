package org.http4s.server.middleware

import cats.effect.IO
import org.http4s.Http4sSpec
import scala.concurrent.duration._
import cats.implicits._

class ThrottleSpec extends Http4sSpec {
  "LocalTokenBucket" should {

    "contain initial number of tokens equal to specified capacity" in {
      val capacity = 5
      val createBucket = LocalTokenBucket[IO](capacity, 365.days)

      createBucket.flatMap(testee => {

        val takeFiveTokens: IO[List[TokenAvailability]] = (1 to 5).toList.traverse(_ => testee.takeToken)
        val checkTokensUpToCapacity = takeFiveTokens.map(tokens => tokens must not contain TokenUnavailable)
        val checkTokenAfterCapacity = testee.takeToken.map(_ must_== TokenUnavailable)
        checkTokensUpToCapacity >> checkTokenAfterCapacity
      }).unsafeRunSync
    }

    "add another token at specified interval when not at capacity" in {
      val capacity = 1
      val createBucket = LocalTokenBucket[IO](capacity, 100.milliseconds)

      val takeTokenAfterRefill = createBucket.flatMap(testee => {
        testee.takeToken >> IO { Thread.sleep(500) } >> testee.takeToken
      })

      takeTokenAfterRefill must returnValue(TokenAvailable)
    }

    //FIXME if refill happens to occur in middle of taking tokens test will fail
    "not add another token at specified interval when at capacity" in {
      val capacity = 5
      val createBucket = LocalTokenBucket[IO](capacity, 100.milliseconds)

      val takeExtraToken = createBucket.flatMap(testee => {
        val takeFiveTokens: IO[List[TokenAvailability]] = (1 to 5).toList.traverse(_ => testee.takeToken)
        IO { Thread.sleep(500) } >> takeFiveTokens >> testee.takeToken
      })

      takeExtraToken must returnValue(TokenUnavailable)
    }
  }
  
  //FIXME implement
  "Throttle" should {
    "allow a request to proceed when the rate limit has not been reached" in {
      1 must_== 1
    }

    "deny a request when the rate limit had been reached" in {
      1 must_== 1
    }
  }
}
