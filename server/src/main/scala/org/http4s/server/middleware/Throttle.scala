package org.http4s.server.middleware

import org.http4s.{Http, Response, Status}
import cats.data.Kleisli
import cats.effect.{Async, Timer}
import cats.effect.concurrent.Ref
import scala.concurrent.duration._
import cats.implicits._
import fs2.Stream

sealed trait TokenAvailability
case object TokenAvailable extends TokenAvailability
case object TokenUnavailable extends TokenAvailability

//FIXME add tests
//FIXME general refactor
//FIXME default lower limit for token update frequency?
trait TokenBucket[F[_]] {
  def takeToken: F[Boolean]
}

class LocalTokenBucket[F[_]] private (capacity: Int, tokenCounter: Ref[F, Int]) extends TokenBucket[F] {
  def takeToken: F[TokenAvailability] = {
    tokenCounter.modify({
      case 0 => (0, TokenUnavailable)
      case value: Int => (value - 1, TokenAvailable)
    })
  }

  def addToken: F[Unit] = {
    tokenCounter.update { count =>
      if (count < capacity) {
        count + 1
      } else {
        count
      }
    }
  }
}

object LocalTokenBucket {
  def apply[F[_]](capacity: Int, refillEvery: FiniteDuration)(implicit F: Async[F], timer: Timer[F]): F[LocalTokenBucket[F]] = Ref[F].of(capacity).map { counter =>
    new LocalTokenBucket(capacity, counter)
  }.flatTap(bucket => {
    Stream.fixedRate[F](refillEvery).evalMap(_ => bucket.addToken).compile.drain
  })
}

//FIXME add constructor to pass own Throttle
//FIXME option to set 429 body?
object Throttle {
  def apply[F[_], G[_]](amount: Int, per: FiniteDuration)(http: Http[F, G])(implicit F: Async[F], timer: Timer[F]): Http[F, G] = {
    val refillFrequency = per / amount.toLong
    val createBucket = LocalTokenBucket(amount, refillFrequency)
    Kleisli.liftF(createBucket).flatMap({ bucket =>
      Kleisli { req =>
        bucket.takeToken.flatMap {
          case TokenAvailable => http(req)
          case TokenUnavailable => Response[G](Status.TooManyRequests).pure[F]
        }
      }
    })
  }
}