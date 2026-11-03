package okay.facts.router

/**
 * RULES IN A PORTABLE DIALECT (specs/design.md §17): a pattern written once, matched alike in a Worker (JavaScript's
 * `RegExp`) and on the JVM (`java.util.regex`). `\w` is a letter of Latin (with its extensions: Polish) or Cyrillic
 * (with its supplement: Ukrainian), a combining mark, a digit, `_`, the joiners, the apostrophes Ukrainian writes;
 * `\s` white space; `\d` a digit; `\b` a boundary of `\w` by lookaround — each as EXPLICIT RANGES, never a Unicode
 * property class (V8 builds `\p{…}` anew per class: 0.4 ms each, against 0.02 ms for ranges, measured by
 * okay-chat-edge). Case-insensitive by the platform. Any other escape of a letter is refused: a construct the
 * translation does not know is seen, not mistranslated. A letter of another script is not a word character here
 */
object Rules:
  /** a word character, as the body of a class */
  val W: String = "0-9A-Za-z_\\u00AA\\u00B5\\u00BA\\u00C0-\\u00D6\\u00D8-\\u00F6\\u00F8-\\u024F\\u0300-\\u036F" +
    "\\u0400-\\u052F\\u1E00-\\u1EFF\\u200C\\u200D\\u02BC\\u2019'"
  /** white space */
  val S: String = "\\t\\n\\u000B\\f\\r \\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000"
  private val B = s"(?:(?<=[$W])(?![$W])|(?<![$W])(?=[$W]))"

  /** a portable pattern compiled by the platform */
  def compile(pattern: String): Re = Re(translate(pattern))

  def translate(src: String): String =
    val out = new StringBuilder
    var inClass = false
    var i = 0
    while i < src.length do
      val c = src.charAt(i)
      if c == '\\' && i + 1 < src.length then
        val e = src.charAt(i + 1)
        out ++= ((e, inClass) match
          case ('w', false) => s"[$W]"
          case ('w', true) => W
          case ('W', false) => s"[^$W]"
          case ('s', false) => s"[$S]"
          case ('s', true) => S
          case ('S', false) => s"[^$S]"
          case ('d', false) => "[0-9]"
          case ('d', true) => "0-9"
          case ('b', false) => B
          case (x, _) if !x.isLetterOrDigit => s"\\$x"
          case (x, _) => throw IllegalArgumentException(s"an escape the translation does not know: \\$x in $src"))
        i += 2
      else
        if c == '[' && !inClass then inClass = true
        else if c == ']' && inClass then inClass = false
        out += c
        i += 1
    out.result()
