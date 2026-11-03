package okay.facts.router

import scala.scalajs.js
import scala.scalajs.js.JSStringOps.*

/**
 * A REGEX, JavaScript's own (in a Worker): V8 compiles it natively — Scala.js's `java.util.regex` would build every
 * class of a rule anew at an isolate's start (specs/design.md §17). The same class on the JVM is `java.util.regex`'s;
 * `Rules` gives both the same pattern
 */
final class Re private (native: js.RegExp, global: js.RegExp):
  def test(s: String): Boolean =
    native.lastIndex = 0
    native.test(s)

  /** group `n` of the first match */
  def group(s: String, n: Int = 1): Option[String] =
    native.lastIndex = 0
    val m = native.exec(s)
    if m == null || m.length <= n then None else m(n).toOption

  /** every match: its start, its end, its groups (an absent group empty) */
  def all(s: String): Vector[(Int, Int, Vector[String])] =
    global.lastIndex = 0
    val out = Vector.newBuilder[(Int, Int, Vector[String])]
    var m = global.exec(s)
    while m != null do
      val at = m.index
      val whole = m(0).getOrElse("")
      out += ((at, at + whole.length, (1 until m.length).toVector.map(i => m(i).getOrElse(""))))
      if whole.isEmpty then global.lastIndex = global.lastIndex + 1
      m = global.exec(s)
    out.result()

  def replaceAll(s: String, by: String): String = s.jsReplace(global, by)

object Re:
  /** a pattern as `Rules` writes it, case-insensitive */
  def apply(pattern: String): Re = new Re(new js.RegExp(pattern, "iu"), new js.RegExp(pattern, "giu"))
