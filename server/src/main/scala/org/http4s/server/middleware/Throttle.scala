package org.http4s.server.middleware

import org.http4s.{Http, Request, Response, Status}
import cats.data.Kleisli
import cats.effect.{Async, Clock, Sync}
import cats.effect.concurrent.Ref
import scala.concurrent.duration.FiniteDuration
import cats.implicits._
import cats.Applicative
import java.net.InetAddress
import java.util.concurrent.TimeUnit.NANOSECONDS
import org.http4s.headers.`Content-Length`
import org.http4s.internal.DefaultedMap
import scala.concurrent.duration._

/**
  * Transform a service to reject any calls the go over a given rate.
  */
//FIXME review scaladoc
object Throttle {

  sealed abstract class TokenAvailability extends Product with Serializable
  case object TokensAvailable extends TokenAvailability
  final case class TokensUnavailable(retryAfter: Option[FiniteDuration]) extends TokenAvailability

  /**
    * A token bucket for use with the [[Throttle]] middleware.  Consumers can take tokens which will be refilled over time.
    * Implementations are required to provide their own refill mechanism.
    *
    * Possible implementations include a remote TokenBucket service to coordinate between different application instances.
    */
  //FIXME return a different ADT
  trait RequestLimiter[F[_], G[_]] {
    def takeToken(request: Request[G]): F[TokenAvailability]
  }

  object RequestLimiter {

    /**
      * Creates an in-memory [[RequestLimiter]].
      *
      * @param capacity the number of tokens the bucket can hold and starts with.
      * @param refillEvery the frequency with which to add another token if there is capacity spare.
      * @return A task to create the [[RequestLimiter]].
      */

    def simple[F[_], G[_]](bucket: TokenBucket[F]): RequestLimiter[F, G] = (_: Request[G]) => bucket.takeTokens(1)

    //FIXME document restriction on no chunked etc
    //FIXME capacity should be in bytes
    //relevant spec - https://www.w3.org/Protocols/rfc2616/rfc2616-sec4.html#sec4.4
    def size[F[+_], G[_]](bucket: TokenBucket[F])(
      implicit F: Applicative[F]): RequestLimiter[F, G] = { request =>
      val length = request.headers.get(`Content-Length`)
      length match {
        case Some(l) => bucket.takeTokens(l.length.toInt)//FIXME long here, what if too long?
        case None => TokensUnavailable(None).pure[F]
      }
    }

    //FIXME deal with possible bucket errors
    //Each IP gets its own tokenbucket, so we take an F[Bucket] to allow us to create new ones
    def perIp[F[_], G[_]](createBucket: F[TokenBucket[F]])(
      implicit F: Async[F]): F[RequestLimiter[F, G]] = {

      val createMap = DefaultedMap.create[F, InetAddress, TokenBucket[F]](createBucket)

      createMap.map({ map =>
        new RequestLimiter[F, G] {
          override def takeToken(r: Request[G]): F[TokenAvailability] = {
            val availability: F[TokenAvailability] = r.from match {//FIXME make from vs remote optional
              case Some(address) => map.get(address).flatMap { _.takeTokens(1) }
              case None => (TokensUnavailable(None): TokenAvailability).pure[F]
            }
            availability
          }
        }
      })

    }

  }

  trait TokenBucket[F[_]] {
    def takeTokens(requestedTokens: Int): F[TokenAvailability]
  }

  object TokenBucket {
    def local[F[_]](capacity: Int, refillEvery: FiniteDuration)(
      implicit F: Sync[F],
      clock: Clock[F]): F[TokenBucket[F]] = {

      def getTime = clock.monotonic(NANOSECONDS)
      val bucket = getTime.flatMap(time => Ref[F].of((capacity.toDouble, time)))

      bucket.map { counter =>
        new TokenBucket[F] {
          def takeTokens(requestedTokens: Int): F[TokenAvailability] = {
            val attemptUpdate = counter.access.flatMap {
              case ((previousTokens, previousTime), setter) =>
                getTime.flatMap(currentTime => {
                  val timeDifference = currentTime - previousTime
                  val tokensToAdd = timeDifference.toDouble / refillEvery.toNanos.toDouble
                  val newTokenTotal = Math.min(previousTokens + tokensToAdd, capacity.toDouble)

                  val attemptSet: F[Option[TokenAvailability]] = if (newTokenTotal >= requestedTokens) {
                    setter((newTokenTotal - requestedTokens, currentTime))
                      .map(_.guard[Option].as(TokensAvailable))
                  } else {
                    val timeToNextToken = refillEvery.toNanos - timeDifference
                    val unavailable: TokenAvailability = TokensUnavailable(timeToNextToken.nanos.some)
                    unavailable.some.pure[F]
                  }

                  attemptSet
                })
            }

            def loop: F[TokenAvailability] = attemptUpdate.flatMap { attempt =>
              attempt.fold(loop)(token => token.pure[F])
            }
            loop
          }

        }
      }

    }
  }

  /**
    * Limits the supplied service to a given rate of calls using an in-memory [[RequestLimiter]]
    *
    * @param amount the number of calls to the service to permit within the given time period.
    * @param per the time period over which a given number of calls is permitted.
    * @param http the service to transform.
    * @return a task containing the transformed service.
    */
  def apply[F[_], G[_]](amount: Int, per: FiniteDuration)(
      http: Http[F, G])(implicit F: Sync[F], timer: Clock[F]): F[Http[F, G]] = {
    val refillFrequency = per / amount.toLong
    val createBucket: F[TokenBucket[F]] = TokenBucket.local(amount, refillFrequency)
    val createLimiter: F[RequestLimiter[F, G]] = createBucket.map(RequestLimiter.simple(_))
    createLimiter.map(limiter => apply(limiter)(http))
  }

  def defaultResponse[F[_]](retryAfter: Option[FiniteDuration]): Response[F] = {
    val _ = retryAfter
    Response[F](Status.TooManyRequests)
  }

  /**
    * Limits the supplied service using a provided [[RequestLimiter]]
    *
    * @param limiter a [[RequestLimiter]] to use to track the rate of incoming requests.
    * @param throttleResponse a function that defines the response when throttled, may be supplied a suggested retry time depending on bucket implementation.
    * @param http the service to transform.
    * @return a task containing the transformed service.
    */
  def apply[F[_], G[_]](
      limiter: RequestLimiter[F, G],
      throttleResponse: Option[FiniteDuration] => Response[G] = defaultResponse[G] _)(
      http: Http[F, G])(implicit F: Sync[F]): Http[F, G] =
    Kleisli { req =>
      limiter.takeToken(req).flatMap {
        case TokensAvailable => http(req)
        case TokensUnavailable(retryAfter) => throttleResponse(retryAfter).pure[F]
      }
    }
}
