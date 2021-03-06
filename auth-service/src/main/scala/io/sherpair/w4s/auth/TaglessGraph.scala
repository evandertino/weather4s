package io.sherpair.w4s.auth

import cats.effect.{ConcurrentEffect => CE, ContextShift => CS, Resource, Timer}
import io.sherpair.w4s.auth.app.Routes
import io.sherpair.w4s.auth.config.AuthConfig
import io.sherpair.w4s.auth.repository.Repository
import io.sherpair.w4s.domain.Logger
import io.sherpair.w4s.http.{maybeWithSSLContext, HttpServer}
import org.http4s.server.Server

object TaglessGraph {

  type TaglessGraphRes[F[_]] = Resource[F, Server[F]]

  def apply[F[_]: CE: CS: Logger: Timer](repo: Resource[F, Repository[F]])(implicit C: AuthConfig): TaglessGraphRes[F] =
    for {
      implicit0(repository: Repository[F]) <- repo
      _ <- Resource.liftF(repository.init)

      routes <- Routes[F]
      sslContextO <- maybeWithSSLContext
      server <- HttpServer(routes, sslContextO)
    }
    yield server
}
