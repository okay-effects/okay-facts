package okay.facts.router

import okay.effects.json.Json
import okay.facts.Lang

/** AN INTENT, as data (specs/design.md §17): its name, its rank (the lower first among rules), its rules (portable
 * patterns, `Rules`), its authored examples by language (the probe is fitted on them, the table distilled over them) */
final case class Intent(name: String, rank: Int = 50, rules: Vector[String] = Vector.empty,
                        examples: Map[Lang, Vector[String]] = Map.empty)

object Intents:
  /** `{intents: [{name, rank?, rules?, examples: {pl: [...], …}}]}` */
  def parse(json: String): Vector[Intent] = Json.fromText(json)("intents").list.map { i =>
    def strs(j: Json): Vector[String] = j match
      case Json.Arr(xs) => xs.collect { case Json.Str(s) => s }
      case _ => Vector.empty
    Intent(i("name").string, i.field("rank").map(_.int).getOrElse(50), i.field("rules").map(strs)
      .getOrElse(Vector.empty), i.field("examples").collect { case Json.Obj(fs) => fs.flatMap((k, v) =>
      Lang.parse(k).map(_ -> strs(v))).toMap }.getOrElse(Map.empty))
  }

  /** every example: (text, intent, language) */
  def examples(intents: Vector[Intent]): Vector[(String, String, Lang)] =
    for i <- intents; (l, xs) <- i.examples.toVector.sortBy(_._1.ordinal); x <- xs yield (x, i.name, l)

/** where a message went and why: by a rule (its pattern), by the probe (its probability and margin over the next),
 * or nowhere (no word of it known) */
final case class Route(intent: String, by: String, p: Double = 0.0, margin: Double = 0.0, rule: String = ""):
  /** confident: a rule, or the probe past both thresholds */
  def confident(minP: Double, minMargin: Double): Boolean =
    by == "rule" || (by == "probe" && p >= minP && margin >= minMargin)

/**
 * ONE MESSAGE IN, ONE INTENT OUT (okay-chat's routing, specs/design.md §17): the intents' rules first, in rank order;
 * then the probe over the table's vector of the message
 */
final class Router(val rules: Vector[(String, String, Re)], val probe: Probe):
  def byRule(text: String): Option[(String, String)] = rules.find((_, _, re) => re.test(text)).map((i, p, _) => (i, p))

  def route(table: Table, text: String): Route = byRule(text) match
    case Some((intent, pattern)) => Route(intent, "rule", 1.0, 1.0, pattern)
    case None => table.encode(text).map(byMeaning).getOrElse(Route("?", "unknown"))

  def byMeaning(v: Array[Float]): Route =
    val r = probe.ranked(v)
    Route(r.head._1, "probe", r.head._2, r.head._2 - r.lift(1).map(_._2).getOrElse(0.0))

object Router:
  def apply(intents: Vector[Intent], probe: Probe): Router =
    new Router(intents.sortBy(_.rank).flatMap(i => i.rules.map(r => (i.name, r, Rules.compile(r)))), probe)

  /** the probe fitted on the intents' examples (the build's tool; tests on small sets) */
  def fit(table: Table, intents: Vector[Intent], epochs: Int = 300): Probe =
    Probe.train(Intents.examples(intents).flatMap((t, i, _) => table.encode(t).map(_ -> i)), epochs)
