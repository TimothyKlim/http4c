package com.ii.http4c

import java.util.concurrent.TimeUnit

import scalaz._
import Scalaz._
import argonaut._
import Argonaut._
import org.http4s._
import org.http4s.dsl._
import com.ii.http4c.Health._
import ArgonautInstances._

import scala.concurrent.duration.Duration
import scalaz.concurrent.Task

object Health {

  sealed trait HealthCheckStatus
  final case object HealthCheckSuccess extends HealthCheckStatus
  final case class HealthCheckFailure(reason: Throwable, fatal: Boolean) extends HealthCheckStatus

  case class HealthCheck(name: String, check: Task[HealthCheckStatus])

  case class HealthCheckResult(name: String, status: HealthCheckStatus, timeTaken: Duration)

  case class HealthReportResult(results: List[HealthCheckResult]) {
    def isFatal = results.exists { x =>
      x.status match {
        case HealthCheckFailure(_, fatal) => fatal
        case e => false
      }
    }
  }

  case class HealthReport(checks: List[HealthCheck]) {
    def check: Task[HealthReportResult] = {
      val results: Task[List[HealthCheckResult]] = checks.traverseU { c =>
        val timedTask = timeM(Task.apply(System.currentTimeMillis()), c.check)
        timedTask.map { case (duration, status) => HealthCheckResult(c.name, status, duration) }
      }
      results.map(x => HealthReportResult(x))
    }
  }

  implicit def HealthCheckStatusEncodeJson = EncodeJson[HealthCheckStatus] { h =>
    h match {
      case HealthCheckSuccess =>
        Json("healthy" := true)
      case HealthCheckFailure(reason, fatal) =>
        Json("healthy" := false, "fatal" := fatal, "message" := reason.getMessage)
    }
  }

  implicit def HealthCheckResultEncodeJson = EncodeJson[HealthCheckResult] { h =>
    Json(
      h.name := h.status.asJson.deepmerge(Json("time" := s"${h.timeTaken.toMillis.toString}ms"))
    )
  }

  implicit def HealthReportResultEncodeJson = EncodeJson[HealthReportResult] { h =>
    h.results.foldLeft(jEmptyObject){ case (i, e) => i.deepmerge(e.asJson) }
  }

  private def timeM[M[_]: Monad, A](now: M[Long], task: M[A]): M[(Duration, A)] = {
    now.flatMap { start =>
      for {
        x <- task
        end <- now
      } yield (Duration(end - start, TimeUnit.MILLISECONDS), x)
    }
  }
}

case class HealthReportService(healthReport: HealthReport) {
  val service = HttpService {
    case req @ GET -> Root => {
      healthReport.check.flatMap { result =>
        if (result.isFatal)
          InternalServerError(result.asJson)
        else
          Ok(result.asJson)
      }
    }
  }
}