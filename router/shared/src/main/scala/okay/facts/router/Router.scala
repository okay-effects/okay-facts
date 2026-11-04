package okay.facts.router

import okay.effects.json.Json
import okay.facts.Lang

/** AN INTENT, as data (specs/design.md §17): its name, its rank (the lower first among rules), its rules (portable
 * patterns, `Rules`: evidence for the combiner), its safe rules (whole-message patterns answered at once — greetings,
 * help; a short list), its authored examples by language (the probe is fitted on them, the table distilled over
 * them) */
final case class Intent(name: String, rank: Int = 50, rules: Vector[String] = Vector.empty,
                        examples: Map[Lang, Vector[String]] = Map.empty, safe: Vector[String] = Vector.empty)

object Intents:
  private def strs(j: Json): Vector[String] = j match
    case Json.Arr(xs) => xs.collect { case Json.Str(s) => s }
    case _ => Vector.empty

  /** `{guard?: [...], intents: [{name, rank?, safe?, rules?, examples: {pl: [...], …}}]}` */
  def parse(json: String): Vector[Intent] = Json.fromText(json)("intents").list.map { i =>
    Intent(i("name").string, i.field("rank").map(_.int).getOrElse(50), i.field("rules").map(strs)
      .getOrElse(Vector.empty), i.field("examples").collect { case Json.Obj(fs) => fs.flatMap((k, v) =>
      Lang.parse(k).map(_ -> strs(v))).toMap }.getOrElse(Map.empty), i.field("safe").map(strs).getOrElse(Vector.empty))
  }

  /** the guard's patterns by category, in order — (category, pattern): a turn that matches one is answered by the
   * host's fixed answer for its category, never by the model (injections, others' data, bargaining, payments,
   * amenities, health, advice, out of domain); a bare pattern is of the category `guard` */
  def guard(json: String): Vector[(String, String)] = Json.fromText(json).field("guard").toVector.flatMap {
    case Json.Arr(xs) => xs.flatMap {
      case Json.Str(p) => Vector("guard" -> p)
      case o => o("patterns").list.collect { case Json.Str(p) => o("category").string -> p }
    }
    case _ => Vector.empty
  }

  /** every example: (text, intent, language) */
  def examples(intents: Vector[Intent]): Vector[(String, String, Lang)] =
    for i <- intents; (l, xs) <- i.examples.toVector.sortBy(_._1.ordinal); x <- xs yield (x, i.name, l)

/** where a message went and why (specs/design.md §17 "The turn decision"): the guard (its pattern, its category),
 * a safe rule (its pattern), the combiner (its calibrated probability and margin over the next), nowhere (no word of
 * it known, no rule); `rule` and `probe` are the evidence's own first guess (`glance`) */
final case class Route(intent: String, by: String, p: Double = 0.0, margin: Double = 0.0, rule: String = "",
                       guard: String = ""):
  /** confident: a safe rule, or the combiner (or the probe's glance) past both thresholds; never the guard */
  def confident(minP: Double, minMargin: Double): Boolean =
    by == "safe" || by == "rule" || ((by == "combined" || by == "probe") && p >= minP && margin >= minMargin)

/**
 * ONE MESSAGE IN, ONE INTENT OUT (specs/design.md §17): the guard, the safe rules, then the combiner over the
 * evidence — the probe's probabilities over the table's vector, every intent's rule hits, a short follow-up's
 * previous intent, the host's own evidence. A rule's hit alone never answers
 */
final class Router(val rules: Vector[(String, String, Re)], val probe: Probe,
                   val safe: Vector[(String, String, Re)] = Vector.empty,
                   val guard: Guard = Guard.empty,
                   val combiner: Option[Combiner] = None):
  def byRule(text: String): Option[(String, String)] = rules.find((_, _, re) => re.test(text)).map((i, p, _) => (i, p))
  def bySafe(text: String): Option[(String, String)] = safe.find((_, _, re) => re.test(text)).map((i, p, _) => (i, p))
  /** the guard's first category whose pattern matches: (category, pattern) */
  def byGuard(text: String): Option[(String, String)] = guard.check(text)
  /** the intents any of whose rules match */
  def hits(text: String): Set[String] = rules.collect { case (i, _, re) if re.test(text) => i }.toSet

  /** the evidence's first guess (round 2's route): the first rule by rank, else the probe — a follow-up's previous
   * question is read so */
  def glance(table: Table, text: String): Route = byRule(text) match
    case Some((intent, pattern)) => Route(intent, "rule", 1.0, 1.0, pattern)
    case None => table.encode(text).map(byMeaning).getOrElse(Route("?", "unknown"))

  /** round 2's route, kept for the tools' comparisons */
  def route(table: Table, text: String): Route = glance(table, text)

  def byMeaning(v: Array[Float]): Route =
    val r = probe.ranked(v)
    Route(r.head._1, "probe", r.head._2, r.head._2 - r.lift(1).map(_._2).getOrElse(0.0))

  /** the evidence of a message laid out as the combiner reads it: the probe's probabilities, the rule hits, the
   * previous intent (each per class, the probe's order), then the host's named evidence in the order `names` asks */
  def evidence(table: Table, text: String, prev: Option[String], extras: Map[String, Double],
               names: Vector[String]): (Array[Double], Boolean) =
    val cs = probe.classes
    val v = table.encode(text)
    val probs = v.map(probe.probabilities).getOrElse(new Array[Double](cs.length))
    val hit = hits(text)
    val base = Router.base(cs)
    val x = names.map { n =>
      val i = base.indexOf(n)
      if i >= 0 && i < cs.length then probs(i)
      else if i >= cs.length && i < 2 * cs.length then (if hit(cs(i - cs.length)) then 1.0 else 0.0)
      else if i >= 2 * cs.length then (if prev.contains(cs(i - 2 * cs.length)) then 1.0 else 0.0)
      else extras.getOrElse(n, 0.0)
    }.toArray
    (x, v.isDefined || hit.nonEmpty)

  /** THE TURN'S DECISION: the guard, a safe rule, else the combiner (none: the glance) */
  def decide(table: Table, text: String, prev: Option[String] = None, extras: Map[String, Double] = Map.empty)
    : Route =
    guard.route(text)
      .orElse(bySafe(text).map((i, p) => Route(i, "safe", 1.0, 1.0, p)))
      .getOrElse(combiner match
        case None => glance(table, text)
        case Some(c) =>
          val (x, known) = evidence(table, text, prev, extras, c.features)
          if !known then Route("?", "unknown")
          else
            val r = c.ranked(x)
            Route(r.head._1, "combined", r.head._2, r.head._2 - r.lift(1).map(_._2).getOrElse(0.0)))

object Router:
  def apply(intents: Vector[Intent], probe: Probe, guard: Vector[(String, String)] = Vector.empty,
            combiner: Option[Combiner] = None): Router =
    val sorted = intents.sortBy(_.rank)
    new Router(sorted.flatMap(i => i.rules.map(r => (i.name, r, Rules.compile(r)))), probe,
      sorted.flatMap(i => i.safe.map(r => (i.name, r, Rules.compile(r)))),
      Guard(guard),
      combiner)

  /** the evidence's own names, per class: `probe:<c>`, `rule:<c>`, `prev:<c>` */
  def base(classes: Vector[String]): Vector[String] =
    classes.map("probe:" + _) ++ classes.map("rule:" + _) ++ classes.map("prev:" + _)

  /** the probe fitted on the intents' examples (the build's tool; tests on small sets) */
  def fit(table: Table, intents: Vector[Intent], epochs: Int = 300): Probe =
    Probe.train(Intents.examples(intents).flatMap((t, i, _) => table.encode(t).map(_ -> i)), epochs)
