package org.http4s.internal

import cats.effect.{IO, Timer}
import cats.effect.concurrent.Ref
import cats.effect.laws.util.TestContext
import cats.implicits._
import org.specs2.concurrent.ExecutionEnv
import org.specs2.matcher.FutureMatchers
import org.specs2.mutable.Specification
import scala.concurrent.duration._

class DefaultedMapSpec(implicit ee: ExecutionEnv) extends Specification with FutureMatchers{

  "DefaultedMap" should {
    "create a new entry for a key not previously queried" in {
      val someEntryContent = "entry content"
      val someKey = 123
      val testee = DefaultedMap.create[IO, Int, String](someEntryContent.pure[IO])

      testee.flatMap(_.get(someKey)).unsafeRunSync() must_== someEntryContent
    }

    "return a previously created entry without creating a new one for the same key" in {
      val creationCount = Ref.of[IO, Int](0)
      val someEntryContent = "entry content"
      val someKey = 123

      val timesCreated = creationCount.flatMap({ count =>
        val entryCreator = count.update(_ + 1) *> someEntryContent.pure[IO]
        val testee = DefaultedMap.create[IO, Int, String](entryCreator)

        testee.flatMap({map =>
          val getEntryFiveTimes =
            (1 to 5).toList.traverse(_ => map.get(someKey))


          getEntryFiveTimes.map(entries => entries must contain(be(someEntryContent)).foreach) *> count.get
        })
      })


      timesCreated.unsafeRunSync() must_== 1
    }

    "block and return an entry currently in the process of creation once created" in {
      val someEntryContent = "entry content"
      val someKey = 123
      val ctx = TestContext()
      implicit val cs = ctx.contextShift[IO]
      val testTimer: Timer[IO] = ctx.timer[IO]
      val slowCreator = testTimer.sleep(100.milliseconds).as(someEntryContent)
      val testee = DefaultedMap.create[IO, Int, String](slowCreator)

      val result = testee.flatMap(map =>
        (map.get(someKey), testTimer.sleep(50.milliseconds) *> map.get(someKey)).parMapN((_, later) => later)
      ).unsafeToFuture()

      ctx.tick(101.milliseconds)

      result must beEqualTo(someEntryContent).await
    }

    "return an error when new entry creation fails" in {
      val someKey = 123
      val failingCreation = IO.raiseError(new RuntimeException("creation failed"))

      val testee = DefaultedMap.create[IO, Int, String](failingCreation)

      testee.flatMap(_.get(someKey)).attempt.unsafeRunSync() must beLeft
    }

//   FIXME change structure to permit this
//    "return an error when requesting an entry whose creation failed" in {
//
//    }
//   FIXME implement cancelation support
//    "support cancelling creation of an entry" in {
//
//    }
//
//    "return an error when requestin an entry whose creation was cancelled" in {
//
//    }
  }

}
