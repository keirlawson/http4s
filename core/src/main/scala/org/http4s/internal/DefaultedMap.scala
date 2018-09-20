package org.http4s.internal

import cats.effect.Async
import cats.effect.concurrent.{Deferred, Ref}
import cats.implicits._

trait DefaultedMap[F[_], A, B] {
  def get(key: A): F[B]
}

//FIXME base on concurrent version of memoize
//FIXME Either for errors
//FIXME what do we do in case of error? probably permit request
//FIXME what about interupts?
object DefaultedMap {
  def create[F[_], A, B](createDefault: F[B])(implicit F: Async[F]): F[DefaultedMap[F, A, B]] =
    Ref.of[F, Map[A, Deferred[F, B]]](Map.empty).map { ref =>
      new DefaultedMap[F, A, B] {
        override def get(key: A): F[B] = Deferred.uncancelable[F, B].flatMap { deferred =>
          ref.modify { map =>
            map.get(key) match {
              case Some(other) => map -> other.get
              case None => (map + (key -> deferred)) -> createDefault.flatTap(deferred.complete)
            }
          }.flatten
        }
      }
    }

}