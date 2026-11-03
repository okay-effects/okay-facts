package okay.facts.router

import okay.facts.{Table as Rows, Words}

/**
 * THE CANDIDATES NEAREST A TEXT (specs/design.md §17): a service among a shop's, a city among the mall's — each
 * candidate by its names (any of its languages). A candidate's score is its meaning's nearness — the best cosine of
 * a unit of the text (a word, a pair, a triple) to one of its names — beside its words' presence (whole, by prefix,
 * by stem, one edit away, as `find` matches them); `pick` says one, several (too close to tell: the host asks), or
 * none (nothing near enough)
 */
object Nearest:
  final case class Candidate(key: String, names: Vector[String])

  enum Pick:
    case One(key: String, score: Double)
    case Several(keys: Vector[String])
    case Nothing

  /** the words that mean something: 3 letters or more */
  private def meant(s: String): Vector[String] = Words.split(s).filter(_.length >= 3)

  /** the share of a name's words the text has */
  def lexical(text: String, name: String): Double =
    val have = meant(text)
    val want = meant(name)
    if want.isEmpty || have.isEmpty then 0.0
    else want.count(w => have.exists(h => Rows.matching(h, w) >= 0.6 || Rows.matching(w, h) >= 0.6)).toDouble /
      want.size

  /** the best cosine of a unit of the text to the name */
  def semantic(table: Table, units: Vector[Array[Float]], name: String): Double =
    table.encode(name).fold(0.0)(n => units.map(Table.dot(_, n)).maxOption.getOrElse(0.0))

  /** every candidate's score, the best first: 0.6 its meaning's nearness, 0.4 its words' presence */
  def rank(table: Table, text: String, cs: Vector[Candidate]): Vector[(String, Double)] =
    val units = Table.units3(text).flatMap(table.vector)
    cs.map(c => c.key -> c.names.filter(_.nonEmpty).map(n => 0.6 * semantic(table, units, n) + 0.4 *
      lexical(text, n)).maxOption.getOrElse(0.0)).sortBy(-_._2)

  /** one when the best passes `threshold` and leads the next by `margin`; several when two or more pass and are
   * closer than that; else nothing */
  def pick(ranked: Vector[(String, Double)], threshold: Double, margin: Double): Pick =
    ranked.filter(_._2 >= threshold) match
      case Vector() => Pick.Nothing
      case (k, s) +: rest if rest.headOption.forall(s - _._2 >= margin) => Pick.One(k, s)
      case passing => Pick.Several(passing.takeWhile(x => passing.head._2 - x._2 < margin).map(_._1))
