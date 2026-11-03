package okay.facts.tck

import okay.effects.json.Json
import okay.facts.*
import okay.facts.cloud.WorkersAi
import okay.llm.Llm
import okay.scala3.*
import okay.scala3.std.Clock
import okay.serverless.cloud.Ai

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration

/**
 * THE TCK'S RUNNER (specs/design.md §11): every question of `Suite` asked of each model through the Workers AI port
 * (`WorkersAi`, okay-serverless' `Ai` answered by the TCK's Worker on `wrangler dev`), the reference data in memory;
 * each model's passport written (`<out>/<model>.json`) and its row printed.
 *   run <worker's URL> <out dir> <L0|L2|L3> <model> [<model> …]
 */
object Passports:
  type B[X] = (Llm + Ai + Facts + Clock + Audit + Emit)[X]

  private val http = HttpClient.newHttpClient()

  /** `Ai` by the TCK's Worker */
  def viaWorker[R[_], Rest[_], A](base: String)(p: A ! R)(using Split[Ai, R, Rest]): A ! Rest =
    def call(model: String, input: Json): Either[String, Json] =
      val req = HttpRequest.newBuilder(URI.create(base)).header("content-type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(Json.print(Json.obj("model" -> Json.str(model), "input" -> input))))
        .timeout(java.time.Duration.ofSeconds(120)).build()
      try
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        val j = scala.util.Try(Json.fromText(res.body)).toOption
        if res.statusCode == 200 then j.toRight(s"not JSON: ${res.body.take(200)}")
        else Left(j.flatMap(_.field("error")).map(_.render).getOrElse(s"HTTP ${res.statusCode}"))
      catch case e: java.io.IOException => Left(e.toString)
    handle(p, Handler.tail[Ai, Rest, A, A](pure)([X] => (op: Ai[X]) => op match
      case Ai.Run(m, input) => call(m, input).fold(e => throw RuntimeException(e), identity)
      case Ai.Attempt(m, input) => call(m, input)))

  def ask(base: String, model: String, s: Setup, q: Question): Outcome =
    val started = System.nanoTime()
    val (conv, last, tokens, regens, violations, raw) = q.turns.foldLeft((Conversation(), Option.empty[Reply], 0, 0,
      Vector.empty[String], Vector.empty[String])) { case ((c, _, t, g, v, r), (text, l, focus)) =>
      val shop = Some(focus).filter(_.startsWith("shop:")).map(_.stripPrefix("shop:"))
      val program = Emit.none(Audit.ignore(Bind.turn[B](s, c, text, l, focus)))
      val (_, (c2, reply)) = run(Demo.facts(shop)(Clock.virtual(Demo.now)(viaWorker(base)(WorkersAi.llm(model)(
        program)))))
      (c2, Some(reply), t + reply.tokens, g + reply.regenerations, v ++ reply.violations.map(_.code), r ++ reply.raw)
    }
    Suite.score(q, last.get, conv.handles, tokens, (System.nanoTime() - started) / 1000000, regens, violations, raw,
      s.level)

  def main(args: Array[String]): Unit =
    val Array(base, out, level, models*) = args: @unchecked
    val lvl = Level.valueOf(level)
    Files.createDirectories(Path.of(out))
    given ExecutionContext = ExecutionContext.global
    val s = Setup(Demo.schema, "You are the assistant of a mall of small service shops (fictional demo shops).", lvl)
    val all = models.map(m => Future {
      val outcomes = Suite.questions.map { q =>
        val o = ask(base, m, s, q)
        // once more after a pause when the platform failed (capacity, a timeout): the model's own failures stay
        if o.failure.isDefined then { Thread.sleep(5000); ask(base, m, s, q) } else o
      }
      val p = Passport(m, lvl, outcomes)
      Files.writeString(Path.of(out, m.replace('/', '_') + s"-$level.json"), Json.pretty(p.toJson))
      println(s"done $m: ${p.passed}/${p.n}")
      p
    })
    val passports = Await.result(Future.sequence(all), Duration.Inf)
    println("| model | run | reached | passed | strict | formatted | tools | violations | regenerations | " +
      "entities | tokens/q | time/q | recommended |")
    println("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    passports.foreach(p => println(p.row))
