package okay.facts.router

import java.util.regex.{Matcher, Pattern}

/** A REGEX, the JVM's own: the same class in a Worker is JavaScript's; `Rules` gives both the same pattern */
final class Re private (p: Pattern):
  def test(s: String): Boolean = p.matcher(s).find()

  /** group `n` of the first match */
  def group(s: String, n: Int = 1): Option[String] =
    val m = p.matcher(s)
    if m.find() && m.groupCount >= n then Option(m.group(n)) else None

  /** every match: its start, its end, its groups (an absent group empty) */
  def all(s: String): Vector[(Int, Int, Vector[String])] =
    val m = p.matcher(s)
    val out = Vector.newBuilder[(Int, Int, Vector[String])]
    while m.find() do out += ((m.start, m.end, (1 to m.groupCount).toVector.map(i => Option(m.group(i))
      .getOrElse(""))))
    out.result()

  def replaceAll(s: String, by: String): String = p.matcher(s).replaceAll(Matcher.quoteReplacement(by))

object Re:
  /** a pattern as `Rules` writes it, case-insensitive (by Unicode's case) */
  def apply(pattern: String): Re = new Re(Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE))
