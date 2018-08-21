package org.http4s.server.middleware

import org.http4s.{Http, Response, Status}
import cats.data.Kleisli
import cats.effect.Sync
import cats.effect.concurrent.Ref
import scala.concurrent.duration._
import cats.implicits._

trait TokenBucket[F[_]] {
  def takeToken: F[Boolean]
}

class LocalTokenBucket[F[_]] private (capacity: Int, refillEvery: FiniteDuration, tokenCounter: Ref[F, Int]) extends TokenBucket[F] {
  def takeToken: F[Boolean] = {
    tokenCounter.modify({
      case 0 => (0, false)
      case value: Int => (value - 1, true)
    })
  }
}

object LocalTokenBucket {
  def apply[F[_]](capacity: Int, refillEvery: FiniteDuration)(implicit F: Sync[F]): F[LocalTokenBucket[F]] = Ref[F].of(capacity).map { counter =>
    new LocalTokenBucket(capacity, refillEvery, counter)
  }
}

//object Throttle {
//  def apply[F[_], G[_], B](capacity: Int, refillEvery: FiniteDuration)(http: Kleisli[F, Request[G], B]): Kleisli[F, Request[G], B] = apply(LocalTokenBucket(capacity, refillEvery))
//
//  def apply[F[_], G[_], B](tokenBucket: TokenBucket[F])(http: Kleisli[F, Request[G], B]): Kleisli[F, Request[G], B] = {
//    ???
//  }
//}



object Throttle {
  def apply[F[_], G[_]](http: Http[F, G])(implicit F: Sync[F]): Http[F, G] = {
    val createBucket = LocalTokenBucket(10, 1.second)
    Kleisli.liftF(createBucket).flatMap({ bucket =>
      Kleisli { req =>
        bucket.takeToken.flatMap {
          case true => http(req)
          case false => Response[G](Status.TooManyRequests).pure[F]
        }
      }
    })
  }
}