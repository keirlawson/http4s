package org.http4s.server.middleware

import cats.effect.IO
import org.http4s.Http4sSpec
import scala.concurrent.duration._
//import cats.implicits._

//FIXME implement
class ThrottleSpec extends Http4sSpec {
  "LocalTokenBucket" should {

    "contain initial number of tokens equal to specified capacity" in {
      val capacity = 5
      val createBucket = LocalTokenBucket[IO](capacity, 365.days)

      createBucket.unsafeRunSync()

      println("ran it")

//      createBucket.flatMap(testee => {
//
//        //FIXME bet this can be done with a traverse
//        println("got here")
//        val takeFiveTokens: IO[List[TokenAvailability]] = (1 to 5).map(_ => testee.takeToken).toList.sequence
//        val checkTokens = takeFiveTokens.map(tokens => tokens must contain(TokenAvailable))
//        checkTokens
//      }).unsafeRunSync
      1 must_== 1
    }

    "add another token at specified interval when not at capacity" in {
      1 must_== 1
    }

    "not add another token at specified interval when at capacity" in {
      1 must_== 1
    }

    "remove a token and return TokenAvailable when takeToken called and there are tokens available" in {
      1 must_== 1
    }

    "return TokenUnavailable when there are no tokens remaining and takeToken is called" in {
      1 must_== 1
    }
  }

  "Throttle" should {
    "allow a request to proceed when the rate limit has not been reached" in {
      1 must_== 1
    }

    "deny a request when the rate limit had been reached" in {
      1 must_== 1
    }
  }
}
