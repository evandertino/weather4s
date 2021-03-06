package io.sherpair.w4s.geo.cache

import java.time.Instant

import scala.concurrent.duration._

import cats.effect.IO
import cats.syntax.option._
import io.sherpair.w4s.domain.Meta
import io.sherpair.w4s.geo.{GeoSpec, IOengine}
import io.sherpair.w4s.geo.engine.EngineOps

class CacheHandlerSpec extends GeoSpec {

  "CacheHandler" when {
    "one country's localities are loaded into the engine on user's behalf" should {
      "trigger a cache renewal" in new IOengine {

        val timeTheUpdateTookPlace = Instant.now.toEpochMilli
        val expectedCountry = countryUnderTest.copy(updated = timeTheUpdateTookPlace)

        val maybeCountry =
          for {
            implicit0(engineOps: EngineOps[IO]) <- EngineOps[IO](C.clusterName)
            countriesCache <- engineOps.init
            cacheRef <- CacheRef[IO](countriesCache)

            // Ok. Now let's update the 2 indexes handled by the Geo service. The document/record in the
            // "countries" index for the country under test (Indonesia), and thereafter the unique document
            // in the "meta" index, which informs the CacheHandler when the engine gets updated.
            // It's indeed the update of the "meta" index to trigger the cache renewal by the CacheHandler.
            _ <- engineOps.upsertCountry(expectedCountry)
            _ <- engineOps.upsertMeta(Meta(Instant.now.toEpochMilli))

            // The CacheHandler should stop straight after the 1st iteration.
            _ <- cacheRef.stopCacheHandler

            // Starting the CacheHandler (but not as a Fiber), which should update the cache.
            _ <- new CacheHandler[IO](cacheRef, engineOps, 1 second).start

            // Retrieve the country under test
            maybeCountry <- cacheRef.countryByCode(expectedCountry.code)
          }
          yield maybeCountry

        maybeCountry.unsafeRunSync shouldBe expectedCountry.some
      }
    }
  }
}
