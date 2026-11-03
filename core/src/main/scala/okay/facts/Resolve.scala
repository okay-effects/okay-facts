package okay.facts

import okay.effects.json.Json
import okay.scala3.*

/** A RENDERED PARAMETER, a chip: its text, its type, the entity it belongs to (its handle and name), where it came
 * from (source, key, property, version, when it changed) — and, for a slot or a shop, what a click does */
final case class Chip(param: String, text: String, tpe: String, handle: Option[String], entity: String,
                      sources: Vector[Fact], link: Option[String] = None)

/** A RENDERED ANSWER: the text the user reads, in pieces (template text or a chip), and the model's raw answer */
final case class Rendered(segments: Vector[Icu.Seg], chips: Vector[Chip], raw: Answer):
  def text: String = segments.map {
    case Icu.Seg.Text(t) => t
    case Icu.Seg.Value(_, t) => t
  }.mkString

object Rendered:
  def toJson(r: Rendered): Json = Json.obj(
    "text" -> Json.str(r.text),
    "segments" -> Json.Arr(r.segments.map {
      case Icu.Seg.Text(t) => Json.obj("text" -> Json.str(t))
      case Icu.Seg.Value(p, t) => Json.obj("param" -> Json.str(p), "text" -> Json.str(t))
    }),
    "chips" -> Json.Arr(r.chips.map(c => Json.Obj(Vector("param" -> Json.str(c.param), "text" -> Json.str(c.text),
      "type" -> Json.str(c.tpe), "entity" -> Json.str(c.entity),
      "sources" -> Json.Arr(c.sources.map(f => Json.obj("source" -> Json.str(f.ent.source),
        "key" -> Json.str(f.ent.key), "class" -> Json.str(f.ent.cls), "property" -> Json.str(f.prop),
        "version" -> Json.num(f.version), "updatedAt" -> Json.num(f.updatedAt))))) ++
      c.handle.map(h => "handle" -> Json.str(h)) ++ c.link.map(l => "link" -> Json.str(l))))),
    "raw" -> Answer.toJson(r.raw))

/**
 * THE RENDERER (specs/design.md §7, §8): an answer validated, each parameter evaluated from the data, the template
 * formatted in the user's language with the values in — or the violations, for the policy to decide
 */
object Resolve:
  /** rendered whatever the template's own words (policy `flag`): digits, names and units there are the model's, every
   * value still the resolver's; none when a parameter itself is wrong */
  def lenient[R[_]](a: Answer, handles: Handles, schema: Schema, l: Lang)(using In[Facts, R]): Option[Rendered] ! R =
    apply(a, handles, schema, l, strict = false).map(_.toOption)

  def apply[R[_]](a: Answer, handles: Handles, schema: Schema, l: Lang, strict: Boolean = true)(using In[Facts, R])
    : Either[Vector[Violation], Rendered] ! R =
    val static = Validator.check(a, handles, schema).filter(v => strict || !Set("digits", "name", "unit")(v.code))
    if static.nonEmpty then pure(Left(static))
    else values[R](a.params, handles, schema, l).map {
      case Left(v) => Left(Vector(v))
      case Right(byName) => format(a, byName, l).toRight(Vector(Violation("syntax", "the template")))
    }

  /** every parameter evaluated (they type-check: the validator said so), with its chip; or the first that fails */
  def values[R[_]](params: Vector[(String, String)], handles: Handles, schema: Schema, l: Lang)(using In[Facts, R])
    : Either[Violation, Map[String, (Computed, Chip)]] ! R =
    val exprs = params.map((n, e) => n -> Expr.parse(e))
    exprs.collectFirst { case (n, Left(why)) => Violation("parse", s"$n: $why") } match
      case Some(v) => pure(Left(v))
      case None => Eval.each(exprs.collect { case (n, Right(x)) => n -> x })((n, x) => Eval(x, handles).flatMap {
          case Left(why) => pure(Left(Violation(if boolType(x, handles, schema) == Type.Bool then "claim"
            else "value", s"$n = ${Expr.show(x)}: $why")))
          case Right(c) => chip(n, x, c, handles, l).map(ch => Right(n -> (c, ch)))
        }).map(_.map(_.toMap))

  /** the template formatted with the values: none when it does not parse or names a value not there */
  def format(a: Answer, byName: Map[String, (Computed, Chip)], l: Lang): Option[Rendered] =
    Icu.parse(a.template).toOption.filter(parts => Icu.names(parts).forall(byName.contains)).map { parts =>
      val segs = Icu.format(parts, l, n => byName(n)._2.text, n => Render.plural(byName(n)._1.value),
        n => byName(n)._1.value match
          case Value.Bool(b) => b.toString
          case Value.Text(t) => Value.in(t, l)
          case Value.Num(x) => x.toString
          case _ => "other")
      Rendered(segs, a.params.flatMap((n, _) => byName.get(n).map(_._2)), a)
    }

  private def boolType(x: Expr, handles: Handles, schema: Schema): Type =
    Expr.typeOf(x, handles, schema).getOrElse(Type.Text)

  private def hint(x: Expr): String = x match
    case Expr.As(_, h) => h
    case _ => ""

  /** the chip of a parameter: its value in words (an entity by its name), the entity it is about (the one handle it
   * names, else none), its sources; a slot's chip books it, a shop's links to it */
  private def chip[R[_]](n: String, x: Expr, c: Computed, handles: Handles, l: Lang)(using In[Facts, R])
    : Chip ! R =
    val about = Expr.handles(x).distinct match
      case Vector(one) => handles.get(one)
      case _ => None
    def nameOf(ent: Ent): String ! R =
      handles.of(ent).flatMap(h => h.pinned.get("name")) match
        case Some(f) => pure(Render(f.value, l))
        case None => Facts.get[R](ent, "name").map(_.fold(handles.of(ent).flatMap(_.visible.collectFirst {
          case ("name", v) => v }).getOrElse(ent.key))(f => Render(f.value, l)))
    val entities = (c.value match
      case Value.Entity(e) => Vector(e)
      case Value.Many(vs) => vs.collect { case Value.Entity(e) => e }
      case _ => Vector.empty).distinct
    Eval.each(entities)(e => nameOf(e).map(s => Right(e -> s))).flatMap { named =>
      val names = named.toOption.getOrElse(Vector.empty).toMap
      val text = Render(c.value, l, hint(x), e => names.getOrElse(e, e.key))
      val link = about.flatMap(h => if h.ent.cls == "Slot" && Expr.show(x).startsWith("time(") then
        Some("book:" + h.name) else None).orElse(c.value match
        case Value.Url(u) => Some(u)
        case _ => None)
      about.fold(pure[String, R](""))(h => nameOf(h.ent)).map(entity =>
        Chip(n, text, Value.typeOf(c.value).show, about.map(_.name), entity, c.facts, link))
    }
