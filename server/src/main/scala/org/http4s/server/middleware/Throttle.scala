package org.http4s.server.middleware

import org.http4s.{Http, Response, Status}
import cats.data.Kleisli
import cats.effect.{Concurrent, Timer}
import cats.effect.concurrent.Ref
import scala.concurrent.duration._
import cats.implicits._
import fs2.Stream

sealed trait TokenAvailability
case object TokenAvailable extends TokenAvailability
case object TokenUnavailable extends TokenAvailability

/**
  * A token bucket for use with the [[Throttle]] middleware.  Consumers can take tokens which will be refilled over time.
  * Implementations are required to provide their own refill mechanism.
  *
  * Possible implementations include a remote TokenBucket service to coordinate between different application instances.
  */
trait TokenBucket[F[_]] {
  def takeToken: F[TokenAvailability]
}

object TokenBucket {

  /**
    * Creates an in-memory [[TokenBucket]].
    *
    * @param capacity the number of tokens the bucket can hold and starts with.
    * @param refillEvery the frequency with which to add another token if there is capacity spare.
    * @return A singleton stream containing the [[TokenBucket]].
    */
  def local[F[_]](capacity: Int, refillEvery: FiniteDuration)(
      implicit F: Concurrent[F],
      timer: Timer[F]): Stream[F, TokenBucket[F]] =
    Stream.eval(Ref[F].of(capacity)).flatMap { counter =>
      val refill = Stream
        .fixedRate[F](refillEvery)
        .evalMap(_ =>
          counter.update { count =>
            Math.min(count + 1, capacity)
        })
      val bucket = new TokenBucket[F] {
        override def takeToken: F[TokenAvailability] =
          counter.modify({
            case 0 => (0, TokenUnavailable)
            case value: Int => (value - 1, TokenAvailable)
          })
      }
      Stream(bucket).concurrently(refill)
    }
}

/**
  * Transform a service to reject any calls the go over a given rate with a 429 response.
  */
object Throttle {

  /**
    * Limits the supplied service to a given rate of calls using an in-memory [[TokenBucket]]
    *
    * @param amount the number of calls to the service to permit within the given time period.
    * @param per the time period over which a given number of calls is permitted.
    * @param http the service to transform.
    * @return a singleton stream containing the transformed service.
    */
  def apply[F[_], G[_]](amount: Int, per: FiniteDuration)(
      http: Http[F, G])(implicit F: Concurrent[F], timer: Timer[F]): Stream[F, Http[F, G]] = {
    val refillFrequency = per / amount.toLong
    val createBucket = TokenBucket.local(amount, refillFrequency)
    createBucket.map(bucket => apply(bucket)(http))
  }

  /**
    * Limits the supplied service using a provided [[TokenBucket]]
    *
    * @param bucket a [[TokenBucket]] to use to track the rate of incoming requests.
    * @param http the service to transform.
    * @return a singleton stream containing the transformed service.
    */
  def apply[F[_], G[_]](bucket: TokenBucket[F])(http: Http[F, G])(
      implicit F: Concurrent[F]): Http[F, G] =
    Kleisli { req =>
      bucket.takeToken.flatMap {
        case TokenAvailable => http(req)
        case TokenUnavailable => Response[G](Status.TooManyRequests).pure[F]
      }
    }
}
