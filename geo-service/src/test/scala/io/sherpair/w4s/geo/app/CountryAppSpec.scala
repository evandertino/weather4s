package io.sherpair.w4s.geo.app

import cats.data.Kleisli
import cats.effect.{ConcurrentEffect, IO}
import io.sherpair.w4s.FakeAuth
import io.sherpair.w4s.domain.{Country, CountryCount}
import io.sherpair.w4s.engine.Engine
import io.sherpair.w4s.geo.{GeoSpec, IOengine}
import io.sherpair.w4s.geo.cache.CacheRef
import io.sherpair.w4s.geo.engine.EngineOps
import org.http4s.{EntityDecoder, Request, Response, Status}
import org.http4s.Method.GET
import org.http4s.Uri.unsafeFromString
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.server.Router
import org.http4s.syntax.kleisli._

class CountryAppSpec extends GeoSpec with FakeAuth {

  "GET -> /geo/countries" should {
    "return the number of total, available and not-available-yet countries" in new IOengine {
      val responseIO = withMemberRoutes(Request[IO](GET, unsafeFromString(s"${C.root}/countries")))

      implicit val countryCountDecoder: EntityDecoder[IO, CountryCount] = jsonOf[IO, CountryCount]

      val response = responseIO.unsafeRunSync
      response.status shouldBe Status.Ok

      // val body = response.body.compile.toVector.unsafeRunSync.map(_.toChar).mkString
      response.as[CountryCount].unsafeRunSync should have(
        Symbol("total")(Country.numberOfCountries),
        Symbol("available")(0),
        Symbol("notAvailableYet")(Country.numberOfCountries)
      )
    }
  }

  "GET -> /geo/country/{id}" should {
    "return 404 when the resource url is incomplete" in new IOengine {
      val response =
        withMemberRoutes(Request[IO](GET, unsafeFromString(s"${C.root}/country/"))).unsafeRunSync

      response.status shouldBe Status.NotFound
    }
  }

  "GET -> /geo/country/{id}" should {
    "return the requested country" in new IOengine {
      val expectedCode = countryUnderTest.code
      val expectedName = countryUnderTest.name

      val responseIO = withMemberRoutes(Request[IO](GET, unsafeFromString(s"${C.root}/country/${expectedCode}")))

      implicit val countryDecoder: EntityDecoder[IO, Country] = jsonOf[IO, Country]

      val response = responseIO.unsafeRunSync
      response.status shouldBe Status.Ok

      response.as[Country].unsafeRunSync should have(
        Symbol("code")(expectedCode),
        Symbol("name")(expectedName)
      )
    }
  }

  "GET -> /geo/country/{id}" should {
    "return \"NotFound\" if the request concerns an unknown country" in new IOengine {
      val responseIO = withMemberRoutes(Request[IO](GET, unsafeFromString(s"${C.root}/country/unknown")))

      val response = responseIO.unsafeRunSync
      response.status shouldBe Status.NotFound
    }
  }

  private def withMemberRoutes(
    request: Request[IO])(implicit CE: ConcurrentEffect[IO], E: Engine[IO]
  ): IO[Response[IO]] = {

    // For the time being... not used with the tests we have at this time
    val client: Client[IO] = Client.fromHttpApp[IO](Kleisli.pure(Response[IO](Status.NoContent)))

    for {
      implicit0(engineOps: EngineOps[IO]) <- EngineOps[IO](C.clusterName)
      countriesCache <- engineOps.init
      cacheRef <- CacheRef[IO](countriesCache)
      authoriser = withMemberAuth[IO]
      response <- Router((C.root, authoriser(new CountryApp[IO](cacheRef, client).routes))).orNotFound.run(request)
    } yield response
  }
}
