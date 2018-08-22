package org.http4s.server.middleware

import org.http4s.Http4sSpec

//FIXME implement
class ThrottleSpec extends Http4sSpec {
  "LocalTokenBucket" should {
    "contain initial number of tokens equal to specified capacity" in {
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
