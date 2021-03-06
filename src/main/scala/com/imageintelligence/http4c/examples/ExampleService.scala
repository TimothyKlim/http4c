package com.imageintelligence.http4c.examples

import java.time.Duration

import com.auth0.jwt.interfaces.DecodedJWT
import com.imageintelligence.http4c.middleware._
import org.http4s.AuthedRequest
import org.http4s.Response
import org.http4s.server._
import org.http4s.server.blaze.BlazeBuilder

import scalaz._
import Scalaz._
import scalaz.concurrent.Task

object ExampleService extends ServerApp {

  val jwtAuthedMiddleware: AuthMiddleware[DecodedJWT] = JWTAuthMiddleware("example secret", jwt => jwt.right)

  val rateLimitingMiddleware: Middleware[AuthedRequest[DecodedJWT], Response, AuthedRequest[DecodedJWT], Response] = RateLimitingMiddleware.simpleThrottling[AuthedRequest[DecodedJWT], String](req =>
    req.authInfo.getIssuer, 1, Duration.ofSeconds(10)
  )

  val composedRateLimitedAuthMiddleware: AuthMiddleware[DecodedJWT] =
    jwtAuthedMiddleware.compose(rateLimitingMiddleware)

  val compiledService = Router(
    "/health"       -> ExampleHealthService.service,
    "/users"        -> ExampleUserService.service,
    "/authed"       -> jwtAuthedMiddleware(ExampleAuthedService.service),
    "/bytes"        -> ExampleBytesService.service,
    "/argonaut"     -> ExampleArgonautService.service,
    "/api-response" -> ExampleApiResponse.service,
    "/rate-limited" -> composedRateLimitedAuthMiddleware(ExampleAuthedService.service)
  )

  val metricsMiddleware = MetricsMiddleware(x => println(x), (x, y, z) => println(x), "example")(_)
  val jsonLoggingMiddleware = LoggingMiddleware.jsonLoggingMiddleware(x => println(x))(_)
  val basicLoggingMiddleware = LoggingMiddleware.basicLoggingMiddleware(x => println(x))(_)
  val middlewareStack = metricsMiddleware andThen jsonLoggingMiddleware andThen basicLoggingMiddleware

  val compiledServiceWithMiddleware = middlewareStack(compiledService)

  def server(args: List[String]): Task[Server] = {
    BlazeBuilder
      .bindHttp(8080, "0.0.0.0")
      .mountService(compiledServiceWithMiddleware)
      .start
  }
}
