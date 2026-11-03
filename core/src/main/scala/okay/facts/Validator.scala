package okay.facts

/** A VIOLATION of the rule, named (specs/design.md §9): what a regeneration tells the model, what an audit counts */
final case class Violation(code: String, detail: String):
  def show: String = s"$code: $detail"

/**
 * THE VALIDATOR (specs/design.md §9), on every answer before rendering:
 *
 *  - the template's own text has no digits and no declared entity's name (a plain scan of one string — why the
 *    template is apart from its bindings), and no unit after a counted value's placeholder;
 *  - every placeholder has a parameter and every parameter is used;
 *  - every parameter parses, type-checks, names only handles issued in this conversation, every path on it is
 *    functional, and it takes its value from the data (no literal);
 *  - claims written as expressions evaluate (checked by the renderer, `Resolve`).
 */
object Validator:
  /** words a unit is written with: never after a placeholder of a counted value (the value renders with its own) */
  val units: Vector[String] = Vector("zł", "zl", "pln", "gr", "min", "minut", "godz", "h", "hour", "minute", "хв",
    "хвилин", "год", "мин", "минут", "час", "szt", "шт", "pcs", "miejsc", "місц", "мест", "seat", "termin")

  def check(a: Answer, handles: Handles, schema: Schema): Vector[Violation] =
    Icu.parse(a.template) match
      case Left(e) => Vector(Violation("syntax", s"the template at ${e.at}: ${e.what}"))
      case Right(parts) =>
        val texts = Icu.texts(parts)
        val digits = texts.filter(_.exists(_.isDigit)).map(t => Violation("digits", s"'${t.trim.take(40)}'"))
        val names = declared(handles).filter(n => texts.exists(t => contains(t, n)))
          .map(n => Violation("name", s"'$n' is written in the template: bind it as a parameter"))
        val used = Icu.names(parts)
        val missing = used.filterNot(n => a.param(n).isDefined).map(n => Violation("placeholder",
          s"{$n} has no parameter"))
        val unused = a.params.map(_._1).filterNot(used.contains).map(n => Violation("unused",
          s"parameter '$n' is not in the template"))
        val typed = a.params.map((n, e) => n -> (Expr.parse(e) match
          case Left(why) => Left(Violation("parse", s"$n = $e: $why"))
          case Right(x) => Expr.typeOf(x, handles, schema).left.map(why => Violation(code(why), s"$n = $e: $why"))))
        val types = typed.collect { case (n, Right(t)) => n -> t }.toMap
        val unitAfter = Icu.after(parts).collect { case (n, rest) if types.get(n).exists(counted) &&
          unitFirst(rest) => Violation("unit", s"a unit after {$n}: '${rest.trim.take(20)}' — the value has its own") }
        digits ++ names ++ unitAfter ++ missing ++ unused ++ typed.collect { case (_, Left(v)) => v }

  private def code(why: String): String =
    if why.startsWith("unknown handle") then "handle"
    else if why.startsWith("non-functional") then "functional"
    else if why.contains("literal") || why.contains("names no handle") || why.contains("no text literals") then
      "literal"
    else "type"

  private def counted(t: Type): Boolean = t match
    case Type.Money | Type.Duration | Type.Number | Type.Count(_) => true
    case _ => false

  private def unitFirst(rest: String): Boolean =
    val w = Words.split(rest.trim.takeWhile(c => !".,;:!?)".contains(c))).headOption.getOrElse("")
    w.nonEmpty && units.exists(u => w == Words.norm(u) || (u.length >= 3 && w.startsWith(Words.norm(u))))

  /** the names the model was shown (its handles' `name`s, 3 letters or more): never typed into a template */
  def declared(handles: Handles): Vector[String] =
    handles.all.flatMap(h => h.visible.collect { case ("name", n) => n } ++ h.pinned.get("name").toVector.flatMap(
      f => f.value match
        case Value.Text(t) => t.values.toVector
        case _ => Vector.empty)).map(_.trim).filter(n => Words.split(n).mkString.length >= 3).distinct

  /** whether `text` has `name` as whole words, case and diacritics aside */
  def contains(text: String, name: String): Boolean =
    val ws = Words.split(name)
    val tw = Words.split(text)
    ws.nonEmpty && tw.indices.exists(i => tw.slice(i, i + ws.size) == ws)
