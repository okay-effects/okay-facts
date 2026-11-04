package okay.facts.router

/**
 * THE GUARD, a turn's first step (specs/design.md §17 "The turn decision" step 1): the host's patterns by category, in
 * order — the first category whose pattern matches takes the turn. Rules only: no table, no probe, no router; so a
 * host runs it before its router and its model lane in every configuration (the router on, off, its table missing).
 * A turn it takes is answered by the host's fixed authored answer for its category, never by the model
 */
final class Guard(val patterns: Vector[(String, String, Re)]):
  def isEmpty: Boolean = patterns.isEmpty
  /** the categories it knows, in order */
  def categories: Vector[String] = patterns.map(_._1).distinct

  /** the first category whose pattern matches: (category, pattern) */
  def check(text: String): Option[(String, String)] =
    patterns.find((_, _, re) => re.test(text)).map((c, p, _) => (c, p))

  /** the turn's route when the guard takes it: `by = guard`, its category in `guard`, its pattern in `rule` */
  def route(text: String): Option[Route] = check(text).map((c, g) => Route("other", "guard", 1.0, 1.0, g, c))

object Guard:
  val empty: Guard = new Guard(Vector.empty)

  /** from (category, pattern) pairs (`Intents.guard`) */
  def apply(pairs: Vector[(String, String)]): Guard = new Guard(pairs.map((c, p) => (c, p, Rules.compile(p))))

  /** from a host's intents file (its `guard`) */
  def parse(json: String): Guard = apply(Intents.guard(json))
