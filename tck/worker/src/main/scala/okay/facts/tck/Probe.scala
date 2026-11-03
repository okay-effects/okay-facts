package okay.facts.tck

import okay.effects.json.{Descent, Json}
import okay.scala3.*
import okay.serverless.cloud.{Ai, App, Cloud, Request, Response, text}
import okay.serverless.cloudflare.Worker

import scala.scalajs.js.annotation.JSExportTopLevel

/** THE TCK'S WORKER: `POST /` `{model, input}` — the input run by the `AI` binding, the model's answer as it is (a
 * failure `{"error": …}`, 502). Only on `wrangler dev`, for the TCK's runner; never deployed */
object Probe extends App:
  override def fetch(r: Request): Response ! Cloud =
    Descent.parse(r.text).toOption match
      case Some(j) if r.method == "POST" => Ai.attempt[Cloud](j("model").string, j("input")).map {
          case Right(answer) => Response(200, Map("Content-Type" -> "application/json"), Json.print(answer))
          case Left(why) => Response(502, Map("Content-Type" -> "application/json"), Json.print(Json.obj(
            "error" -> Json.str(why))))
        }
      case _ => pure(Response(400, Map.empty, "POST {model, input}"))

  @JSExportTopLevel("worker")
  val worker: Worker = Worker(this, Map.empty)
