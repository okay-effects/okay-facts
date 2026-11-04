package okay.facts

import okay.effects.json.Json
import okay.scala3.*

/** A RENDERED PARAMETER, a chip: its text, its type, the entity it belongs to (its handle and name), where it came
 * from (source, key, property, version, when it changed) — and, for a slot or a shop, what a click does; a name asked
 * in a case: the case, and whether its form was known (`sure`; not: the nominative) */
final case class Chip(param: String, text: String, tpe: String, handle: Option[String], entity: String,
                      sources: Vector[Fact], link: Option[String] = None, kase: String = "", sure: Boolean = true)

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
      c.handle.map(h => "handle" -> Json.str(h)) ++ c.link.map(l => "link" -> Json.str(l)) ++
      Option.when(c.kase.nonEmpty)("case" -> Json.str(c.kase)) ++ Option.when(c.kase.nonEmpty)("sure" ->
      Json.bool(c.sure))))),
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
          case Right(c) => comparable[R](n, x, c, schema).flatMap {
              case Some(v) => pure(Left(v))
              case None => chip(n, x, c, handles, l).map(ch => Right(n -> (c, ch)))
            }
        }).map(_.map(_.toMap))

  /** COMPARISONS ONLY OF COMPARABLE THINGS (§17 "Round 4"): a comparison or an extreme (`<`, `>`, `min`, `argmin`…)
   * over a property that says what it is comparable by (`Prop.comparableBy`), of entities of different sources —
   * every one of them must have that property alike, else the violation `comparable`. Within one source, any */
  def comparable[R[_]](n: String, x: Expr, c: Computed, schema: Schema)(using In[Facts, R]): Option[Violation] ! R =
    def compares(e: Expr): Boolean = e match
      case Expr.Bin(op, l, r) => Set("<", "<=", ">", ">=")(op) || compares(l) || compares(r)
      case Expr.Call(fn, args) => Set("min", "max", "argmin", "argmax")(fn) || args.exists(compares)
      case Expr.Not(y) => compares(y)
      case Expr.As(y, _) => compares(y)
      case _ => false
    val byWhat = c.facts.flatMap(f => schema.prop(f.ent.cls, f.prop).map(_.comparableBy).filter(_.nonEmpty)
      .map(k => f.ent -> k)).distinct
    if !compares(x) || byWhat.map(_._1.source).distinct.size < 2 then pure(None)
    else Eval.each(byWhat)((e, k) => Facts.get[R](e, k).map(v => Right[String, Option[Value]](v.map(_.value))))
      .map {
        case Right(kinds) if kinds.forall(_.isDefined) && kinds.flatten.forall(Value.same(_, kinds.flatten.head)) =>
          None
        case _ => Some(Violation("comparable", s"$n = ${Expr.show(x)}: values of different kinds of thing across " +
          s"sources ('${byWhat.map(_._2).distinct.mkString(", ")}' not alike): compare only things of one kind"))
      }

  /** the template formatted with the values: none when it does not parse or names a value not there */
  def format(a: Answer, byName: Map[String, (Computed, Chip)], l: Lang): Option[Rendered] =
    Icu.parse(a.template).toOption.filter(parts => Icu.names(parts).forall(byName.contains)).map { parts =>
      val segs = Icu.format(parts, l, n => byName(n)._2.text, n => Render.plural(byName(n)._1.value),
        n => if byName(n)._2.kase.nonEmpty then (if byName(n)._2.sure then "sure" else "other")
          else byName(n)._1.value match
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
      val plain = Render(c.value, l, hint(x), e => names.getOrElse(e, e.key))
      val link = about.flatMap(h => if h.ent.cls == "Slot" && Expr.show(x).startsWith("time(") then
        Some("book:" + h.name) else None).orElse(c.value match
        case Value.Url(u) => Some(u)
        case _ => None)
      // the entity: the one handle's name; a value computed of several, their names
      val entityName: String ! R = about match
        case Some(h) => nameOf(h.ent)
        case None => Eval.each(Expr.handles(x).distinct.flatMap(handles.get))(h => nameOf(h.ent).map(n =>
          Right[String, String](n))).map(_.toOption.getOrElse(Vector.empty).distinct.mkString(", "))
      val kase = Case.parse(hint(x))
      val whose = c.value match
        case Value.Entity(e) => Some(e)
        case Value.Text(_) => c.facts.lastOption.filter(_.prop == "name").map(_.ent)
        case _ => None
      // a name in a case: the owner's form (`name.<case>`) first, else the rules; neither: the nominative, not sure
      val inCase: Option[String] ! R = (kase, whose) match
        case (Some(k), Some(e)) => Facts.get[R](e, s"name.$k").map(_.flatMap(f => f.value match
            case Value.Text(t) => t.get(l).map(_.trim).filter(_.nonEmpty)
            case _ => None).orElse(Morph.inflect(plain, l, k)))
        case (Some(k), None) => pure(Morph.inflect(plain, l, k))
        case _ => pure(Some(plain))
      inCase.flatMap(form => entityName.map(entity =>
        Chip(n, form.getOrElse(plain), Value.typeOf(c.value).show, about.map(_.name), entity, c.facts, link,
          kase.fold("")(_.toString), form.isDefined)))
    }
