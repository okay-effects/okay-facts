package okay.facts

/**
 * THE TEMPLATE'S LANGUAGE: an ICU MessageFormat subset, pure (Scala.js has no `java.text`, and ICU4J is not there):
 * text; `{name}` a parameter; `{name, plural, [offset:n] one {…} few {…} many {…} other {…} =0 {…}}` with `#` the
 * number; `{name, select, true {…} other {…}}`; `{name, number}` / `{name, date}` / `{name, time}` (the value as the
 * resolver renders it); `''` an apostrophe, `'{'` … `'}'` quoted text, as ICU quotes
 */
object Icu:
  enum Part:
    case Lit(text: String)
    case Arg(name: String, style: String = "")
    case Plural(name: String, offset: Int, cases: Vector[(String, Vector[Part])])
    case Select(name: String, cases: Vector[(String, Vector[Part])])
    /** `#` in a plural's case: the number */
    case Hash(name: String, offset: Int)

  final case class Error(at: Int, what: String)

  def parse(s: String): Either[Error, Vector[Part]] =
    val p = Parser(s)
    try
      val parts = p.message(None, inPlural = None)
      if p.i < s.length then Left(Error(p.i, "unbalanced '}'")) else Right(parts)
    catch case Failed(at, what) => Left(Error(at, what))

  private final case class Failed(at: Int, what: String) extends RuntimeException(what)

  private final class Parser(s: String):
    var i = 0
    def fail(what: String): Nothing = throw Failed(i, what)
    def ws(): Unit = while i < s.length && s.charAt(i).isWhitespace do i += 1
    def ident(): String =
      ws()
      val start = i
      while i < s.length && (s.charAt(i).isLetterOrDigit || s.charAt(i) == '_' || s.charAt(i) == '=' ||
        s.charAt(i) == '-') do i += 1
      if i == start then fail("a name") else s.substring(start, i)

    /** parts up to a `}` of the enclosing block (not consumed) or the end; `#` is a part inside a plural */
    def message(close: Option[Char], inPlural: Option[(String, Int)]): Vector[Part] =
      val out = Vector.newBuilder[Part]
      val text = new StringBuilder
      def flush(): Unit = if text.nonEmpty then { out += Part.Lit(text.toString); text.clear() }
      var done = false
      while !done && i < s.length do
        s.charAt(i) match
          case '}' => done = true
          case '{' =>
            flush()
            i += 1
            out += argument(inPlural)
          case '#' if inPlural.isDefined =>
            flush()
            out += Part.Hash(inPlural.get._1, inPlural.get._2)
            i += 1
          case '\'' =>
            if i + 1 < s.length && s.charAt(i + 1) == '\'' then { text += '\''; i += 2 }
            else if i + 1 < s.length && (s.charAt(i + 1) == '{' || s.charAt(i + 1) == '}' ||
              (s.charAt(i + 1) == '#' && inPlural.isDefined)) then
              i += 1
              var quoted = true
              while quoted && i < s.length do
                if s.charAt(i) == '\'' then
                  if i + 1 < s.length && s.charAt(i + 1) == '\'' then { text += '\''; i += 2 }
                  else { quoted = false; i += 1 }
                else { text += s.charAt(i); i += 1 }
            else { text += '\''; i += 1 }
          case c => text += c; i += 1
      if close.isEmpty && done then fail("unbalanced '}'")
      flush()
      out.result()

    def argument(inPlural: Option[(String, Int)]): Part =
      val name = ident()
      ws()
      if i >= s.length then fail("'}'")
      s.charAt(i) match
        case '}' => i += 1; Part.Arg(name)
        case ',' =>
          i += 1
          val kind = ident()
          ws()
          kind match
            case "plural" | "selectordinal" =>
              expect(',')
              ws()
              var offset = 0
              if s.startsWith("offset:", i) then
                i += "offset:".length
                ws()
                val start = i
                while i < s.length && s.charAt(i).isDigit do i += 1
                offset = s.substring(start, i).toIntOption.getOrElse(fail("an offset"))
              Part.Plural(name, offset, cases(Some(name -> offset)))
            case "select" =>
              expect(',')
              Part.Select(name, cases(inPlural))
            case "number" | "date" | "time" | "duration" =>
              ws()
              val style = if i < s.length && s.charAt(i) == ',' then { i += 1; ident() } else ""
              ws()
              expect('}')
              Part.Arg(name, style)
            case other => fail(s"plural, select or number, not $other")
        case _ => fail("',' or '}'")

    def expect(c: Char): Unit =
      ws()
      if i < s.length && s.charAt(i) == c then i += 1 else fail(s"'$c'")

    def cases(inPlural: Option[(String, Int)]): Vector[(String, Vector[Part])] =
      val out = Vector.newBuilder[(String, Vector[Part])]
      var more = true
      while more do
        ws()
        if i < s.length && s.charAt(i) == '}' then { i += 1; more = false }
        else
          val key = ident()
          expect('{')
          val body = message(Some('}'), inPlural)
          if i >= s.length then fail("'}'")
          i += 1
          out += key -> body
      val all = out.result()
      if !all.exists(_._1 == "other") then fail("an 'other' case")
      all

  /** the names of the parameters a message uses */
  def names(parts: Vector[Part]): Vector[String] = parts.flatMap {
    case Part.Arg(n, _) => Vector(n)
    case Part.Plural(n, _, cs) => n +: cs.flatMap(c => names(c._2))
    case Part.Select(n, cs) => n +: cs.flatMap(c => names(c._2))
    case Part.Hash(n, _) => Vector(n)
    case Part.Lit(_) => Vector.empty
  }.distinct

  /** the message's own text, every case of it: what the validator scans for digits and names */
  def texts(parts: Vector[Part]): Vector[String] = parts.flatMap {
    case Part.Lit(t) => Vector(t)
    case Part.Plural(_, _, cs) => cs.flatMap(c => texts(c._2))
    case Part.Select(_, cs) => cs.flatMap(c => texts(c._2))
    case _ => Vector.empty
  }

  /** each placeholder `{name}` and the text right after it (in its own block): where a unit must not follow */
  def after(parts: Vector[Part]): Vector[(String, String)] =
    parts.zipWithIndex.flatMap {
      case (Part.Arg(n, _), i) => Vector(n -> (parts.lift(i + 1) match
        case Some(Part.Lit(t)) => t
        case _ => ""))
      case (Part.Hash(n, _), i) => Vector(n -> (parts.lift(i + 1) match
        case Some(Part.Lit(t)) => t
        case _ => ""))
      case (Part.Plural(_, _, cs), _) => cs.flatMap(c => after(c._2))
      case (Part.Select(_, cs), _) => cs.flatMap(c => after(c._2))
      case _ => Vector.empty
    }

  /** a piece of a rendered message: text the template wrote, or a value the resolver rendered for a parameter */
  enum Seg:
    case Text(text: String)
    case Value(param: String, text: String)

  /**
   * THE MESSAGE RENDERED: each placeholder by `shown` (the parameter's value in words), a plural's case by the
   * number's category in `l` (`=n` first), a select's by the value's key (`select`), `#` the number
   */
  def format(parts: Vector[Part], l: Lang, shown: String => String, number: String => Option[BigDecimal],
             select: String => String): Vector[Seg] =
    parts.flatMap {
      case Part.Lit(t) => Vector(Seg.Text(t))
      case Part.Arg(n, _) => Vector(Seg.Value(n, shown(n)))
      case Part.Hash(n, offset) => number(n).map(x => Seg.Value(n, Render.number(x - offset, l))).toVector
      case Part.Plural(n, offset, cs) =>
        val x = number(n).getOrElse(BigDecimal(0))
        val exact = cs.collectFirst { case (k, body) if k.startsWith("=") && k.drop(1).toIntOption
          .exists(e => BigDecimal(e) == x) => body }
        val body = exact.getOrElse {
          val cat = Render.category(x - offset, l)
          cs.collectFirst { case (k, b) if k == cat => b }.getOrElse(cs.collectFirst { case ("other", b) => b }.get)
        }
        format(body, l, shown, number, select)
      case Part.Select(n, cs) =>
        val key = select(n)
        format(cs.collectFirst { case (k, b) if k == key => b }.getOrElse(cs.collectFirst {
          case ("other", b) => b }.get), l, shown, number, select)
    }

  /** literal text quoted for a message: braces and apostrophes as ICU reads them back */
  def quote(text: String): String =
    text.replace("'", "''").replace("{", "'{'").replace("}", "'}'")
