package io.sherpair.geo.domain

import scala.util.Success

import cats.effect.Sync
import com.sksamuel.elastic4s.{Hit, HitReader, Indexable}
import com.sksamuel.elastic4s.ElasticApi.indexInto
import com.sksamuel.elastic4s.requests.indexes.IndexRequest
import com.sksamuel.elastic4s.requests.searches.SearchResponse
import io.circe.{Decoder, Encoder, HCursor, Json}
import io.circe.derivation.deriveEncoder
import io.circe.parser.decode
import io.circe.syntax._

case class Country(code: String, name: String, updated: Long = epochAsLong)

object Country {
  def apply(code: String, name: String): Country = new Country(code, name, epochAsLong)

  val indexName: String = "countries"
  val jsonFile: String = "countries.json"

  val mapping: String =
    """{
      | "mappings": {
      |   "properties": {
      |      "code":    { "type": "text" },
      |      "name":    { "type": "text" },
      |      "updated": { "type": "long" }
      |    }
      |  }
      |}""".stripMargin

  implicit val encoder: Encoder.AsObject[Country] = deriveEncoder[Country]

  implicit val indexable: Indexable[Country] = (country: Country) => s"""{
      | "code":    "${country.code}",
      | "name":    "${country.name}",
      | "updated": "${country.updated}"
      |}""".stripMargin

  implicit val HitReader: HitReader[Country] = (hit: Hit) =>
    Success(
      new Country(
        hit.sourceField("code").toString,
        hit.sourceField("name").toString,
        hit.sourceField("updated").toString.toLong
      )
    )

  def decodeFromElastic(response: SearchResponse): Countries = response.to[Country].toList

  def decodeFromJson[F[_]](json: String)(implicit S: Sync[F]): F[Countries] = {
    implicit val decoder: Decoder[Country] =
      (hCursor: HCursor) =>
        for {
          code <- hCursor.get[String]("code")
          name <- hCursor.get[String]("name")
        } yield Country(code, name, epochAsLong)

    decode[Countries](json) match {
      case Left(error) => S.raiseError(error)
      case Right(countries) => S.delay(countries)
    }
  }

  def encodeToJson[F[_]](countries: Countries)(implicit S: Sync[F]): F[Json] = S.delay(countries.asJson)

  def encodeForElastic(indexName: String, countries: Countries): List[IndexRequest] =
    countries.map(country => indexInto(indexName).source(country).id(country.code))
}