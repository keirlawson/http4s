package org.http4s.server.middleware

import org.http4s.{Http, Response, Status}
import cats.data.Kleisli
import cats.effect.{Concurrent, Timer}
import cats.effect.concurrent.Ref
import scala.concurrent.duration._
import cats.implicits._
import fs2.Stream
import cats.effect.syntax.concurrent._

sealed trait TokenAvailability
case object TokenAvailable extends TokenAvailability
case object TokenUnavailable extends TokenAvailability

/*
 * TODO:
 * Function to decide on throttle response
 * Function to decide how many tokens
 * Make takeToken a function on a Request
 *
 * add tokenbucket.local constructor, without named LocalTokenBucket
 * add means of shutting down the refill stream (.start.map rather than flattap)
 * add stream/bracket constructor for localtokenbucket
 * look at stream.supervise - not released yet
 *
 */

//FIXME scaladoc
trait TokenBucket[F[_]] {
  def takeToken: F[TokenAvailability]
}

class LocalTokenBucket[F[_]] private (capacity: Int, tokenCounter: Ref[F, Int]) extends TokenBucket[F] {
  override def takeToken: F[TokenAvailability] = {
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
  def apply[F[_]](capacity: Int, refillEvery: FiniteDuration)(implicit F: Concurrent[F], timer: Timer[F]): F[LocalTokenBucket[F]] = Ref[F].of(capacity).map { counter =>
    new LocalTokenBucket(capacity, counter)
  }.flatTap(bucket => {
    val refill = Stream.fixedRate[F](refillEvery).evalMap(_ => bucket.addToken).compile.drain
    refill.start.void
  })
}

object Throttle {
  def apply[F[_], G[_]](amount: Int, per: FiniteDuration)(http: Http[F, G])(implicit F: Concurrent[F], timer: Timer[F]): Http[F, G] = {
    val refillFrequency = per / amount.toLong
    val createBucket = LocalTokenBucket(amount, refillFrequency)
    Kleisli.liftF(createBucket).flatMap(apply(_)(http))
  }

  def apply[F[_], G[_]](bucket: TokenBucket[F])(http: Http[F, G])(implicit F: Concurrent[F]): Http[F, G] = {
    Kleisli { req =>
      bucket.takeToken.flatMap {
        case TokenAvailable => http(req)
        case TokenUnavailable => Response[G](Status.TooManyRequests).pure[F]
      }
    }
  }
}