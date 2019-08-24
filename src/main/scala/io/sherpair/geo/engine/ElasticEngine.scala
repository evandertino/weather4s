package io.sherpair.geo.engine

import java.util.concurrent.Executors

import scala.concurrent.ExecutionContext

import cats.effect.{Async, IO, Resource, Timer}
import cats.syntax.apply._
import cats.syntax.functor._
import com.sksamuel.elastic4s.{ElasticApi, ElasticClient, ElasticDsl, ElasticProperties}
import com.sksamuel.elastic4s.ElasticDsl._
import com.sksamuel.elastic4s.cats.effect.instances._
import com.sksamuel.elastic4s.http.JavaClient
import com.sksamuel.elastic4s.requests.get.{GetRequest, GetResponse}
import com.sksamuel.elastic4s.requests.indexes.{IndexRequest, IndexResponse}
import com.sksamuel.elastic4s.requests.searches.SearchResponse
import com.sksamuel.elastic4s.requests.searches.sort.FieldSort
import io.sherpair.geo.config.Configuration
import io.sherpair.geo.config.Configuration._

class ElasticEngine[F[_]](elasticClient: ElasticClient)(implicit config: Configuration, A: Async[F]) extends Engine[F] {

  def init: F[String] =
    for {
      response <- elasticClient.execute(clusterHealth()).lift
    } yield response.result.status

  def close: F[Unit] = Async[F].delay(elasticClient.close)

  def add(indexRequest: IndexRequest): F[IndexResponse] =
    for {
      response <- elasticClient.execute(indexRequest).lift
    } yield response.result

  def addAll(indexRequests: Seq[IndexRequest]): F[Option[String]] =
    for {
      response <- elasticClient.execute(bulk(indexRequests)).lift
    } yield if (response.result.hasFailures) response.body else None

  def count(indexName: String): F[Long] =
    for {
      response <- elasticClient.execute(ElasticApi.count(indexName)).lift
    } yield response.result.count

  def createIndex(name: String, jsonMapping: String): F[Unit] =
    for {
      _ <- elasticClient.execute(ElasticDsl.createIndex(name).source(jsonMapping)).lift
    } yield ()

  def execUnderGlobalLock[T](f: => F[T]): F[T] = {
    implicit val timer = IO.timer(ExecutionContext.fromExecutor(Executors.newCachedThreadPool()))
    val lock = acquireLock(math.max(1, lockAttempts(config))).lift
    Resource.make(lock)(_ => releaseLock).use(_ => f)
  }

  def getById(indexName: String, id: String): F[GetResponse] =
    for {
      response <- elasticClient.execute(GetRequest(indexName, id)).lift
    } yield response.result

  def indexExists(name: String): F[Boolean] =
    for {
      response <- elasticClient.execute(ElasticApi.indexExists(name)).lift
    } yield response.result.exists

  /*
   * 0 < windowSize param <= MaxWindowSize
   */
  def queryAll(indexName: String, sortBy: Option[Seq[String]], windowSize: Int): F[SearchResponse] = {
    val _windowSize = Math.max(1, Math.min(MaxWindowSize, windowSize))
    val sorts: Seq[FieldSort] = sortBy.map(_.map(fieldSort(_))).getOrElse(Seq.empty)

    for {
      response <- elasticClient.execute(search(indexName).query(matchAllQuery()).sortBy(sorts) size (_windowSize)).lift
    } yield response.result
  }

  private def acquireLock(lockAttempts: Int)(implicit timer: Timer[IO]): IO[Any] =
    elasticClient
      .execute(acquireGlobalLock())
      .handleErrorWith { error =>
        if (lockAttempts > 0) IO.sleep(lockInterval(config)) *> acquireLock(lockAttempts - 1)
        else IO.raiseError(error)
      }

  private def releaseLock: F[Unit] = elasticClient.execute(releaseGlobalLock()).map(_ => ()).lift

  implicit class IOLifter[A](val io: IO[A]) {
    def lift: F[A] = Async[F].liftIO(io)
  }
}

object ElasticEngine {
  def apply[F[_]](implicit config: Configuration, A: Async[F]): ElasticEngine[F] = {
    val cluster: String = s"cluster.name=${clusterName(config)}"
    val host = config.elasticSearch.host

    val elasticProperties: ElasticProperties =
      ElasticProperties(s"http://${host.address}:${host.port}?${cluster}")

    val elasticClient: ElasticClient = ElasticClient(JavaClient(elasticProperties))

    new ElasticEngine[F](elasticClient)
  }
}