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

/*
 * TODO:
 * Function to decide on throttle response
 * Function to decide how many tokens
 * Make takeToken a function on a Request
 *
 * Consider replacing commented apply
 */

//FIXME scaladoc
trait TokenBucket[F[_]] {
  def takeToken: F[TokenAvailability]
}

object TokenBucket {
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

object Throttle {
//  def apply[F[_], G[_]](amount: Int, per: FiniteDuration)(http: Http[F, G])(implicit F: Concurrent[F], timer: Timer[F]): Http[F, G] = {
//    val refillFrequency = per / amount.toLong
//    val createBucket = LocalTokenBucket(amount, refillFrequency)
//    Kleisli.liftF(createBucket).flatMap(apply(_)(http))
//  }

  def apply[F[_], G[_]](bucket: TokenBucket[F])(http: Http[F, G])(
      implicit F: Concurrent[F]): Http[F, G] =
    Kleisli { req =>
      bucket.takeToken.flatMap {
        case TokenAvailable => http(req)
        case TokenUnavailable => Response[G](Status.TooManyRequests).pure[F]
      }
    }
}
