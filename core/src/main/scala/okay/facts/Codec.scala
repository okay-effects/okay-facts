package okay.facts

import okay.effects.json.Json

/** VALUES, FACTS AND ENTITIES AS JSON: what a conversation keeps, what a source answers over the wire (the mall's
 * shops through their objects) — every value exact, typed by a `t` field */
object Codec:
  def ent(e: Ent): Json = Json.arr(Json.str(e.source), Json.str(e.cls), Json.str(e.key))
  def entOf(j: Json): Ent = Ent(j.list(0).string, j.list(1).string, j.list(2).string)

  def fact(f: Fact): Json = Json.obj("value" -> value(f.value), "ent" -> ent(f.ent), "prop" -> Json.str(f.prop),
    "version" -> Json.num(f.version), "at" -> Json.num(f.updatedAt))
  def factOf(j: Json): Fact = Fact(valueOf(j("value")), entOf(j("ent")), j("prop").string, j("version").long,
    j("at").long)

  private def forms(f: Forms): Json = Json.arr(Json.str(f.one), Json.str(f.few), Json.str(f.many), Json.str(f.other))
  private def formsOf(j: Json): Forms = Forms(j.list(0).string, j.list(1).string, j.list(2).string, j.list(3).string)
  def noun(n: Noun): Json = Json.arr(forms(n.pl), forms(n.uk), forms(n.ru), forms(n.en))
  def nounOf(j: Json): Noun = Noun(formsOf(j.list(0)), formsOf(j.list(1)), formsOf(j.list(2)), formsOf(j.list(3)))

  def value(v: Value): Json = v match
    case Value.Money(m, c) => Json.obj("t" -> Json.str("money"), "minor" -> Json.num(m), "currency" -> Json.str(c))
    case Value.Minutes(n) => Json.obj("t" -> Json.str("minutes"), "n" -> Json.num(n))
    case Value.At(ms) => Json.obj("t" -> Json.str("at"), "ms" -> Json.num(ms))
    case Value.Day(d) => Json.obj("t" -> Json.str("day"), "date" -> Json.str(d))
    case Value.Clock(m) => Json.obj("t" -> Json.str("clock"), "minutes" -> Json.num(m))
    case Value.Num(n) => Json.obj("t" -> Json.str("num"), "n" -> Json.Num(n))
    case Value.Count(n, w) => Json.obj("t" -> Json.str("count"), "n" -> Json.num(n), "noun" -> noun(w))
    case Value.Text(t) => Json.obj("t" -> Json.str("text"), "by" -> Json.Obj(Lang.values.toVector.flatMap(l =>
      t.get(l).map(s => l.toString -> Json.str(s)))))
    case Value.Url(u) => Json.obj("t" -> Json.str("url"), "url" -> Json.str(u))
    case Value.Bool(b) => Json.obj("t" -> Json.str("bool"), "b" -> Json.bool(b))
    case Value.Entity(e) => Json.obj("t" -> Json.str("ent"), "ent" -> ent(e))
    case Value.Many(vs) => Json.obj("t" -> Json.str("many"), "of" -> Json.Arr(vs.map(value)))

  def valueOf(j: Json): Value = j("t").string match
    case "money" => Value.Money(j("minor").long, j("currency").string)
    case "minutes" => Value.Minutes(j("n").int)
    case "at" => Value.At(j("ms").long)
    case "day" => Value.Day(j("date").string)
    case "clock" => Value.Clock(j("minutes").int)
    case "num" => Value.Num(j("n") match { case Json.Num(n) => n; case other => BigDecimal(other.double) })
    case "count" => Value.Count(j("n").long, nounOf(j("noun")))
    case "text" => Value.Text(j("by").record.flatMap((k, s) => Lang.parse(k).map(_ -> s.string)).toMap)
    case "url" => Value.Url(j("url").string)
    case "bool" => Value.Bool(j("b").boolean)
    case "ent" => Value.Entity(entOf(j("ent")))
    case "many" => Value.Many(j("of").list.map(valueOf))
    case other => throw IllegalArgumentException(s"a value's type: $other")

  def found(f: Found): Json = Json.obj("ent" -> ent(f.ent), "visible" -> Json.Arr(f.visible.map((k, v) =>
    Json.arr(Json.str(k), Json.str(v)))), "version" -> Json.num(f.version))
  def foundOf(j: Json): Found = Found(entOf(j("ent")), j("visible").list.map(p => p.list(0).string ->
    p.list(1).string), j("version").long)

  def card(c: Card): Json = c match
    case Card.Open(url, title) => Json.obj("kind" -> Json.str("open"), "url" -> Json.str(url),
      "title" -> Json.str(title))
    case Card.Book(token, title, lines) => Json.obj("kind" -> Json.str("book"), "token" -> Json.str(token),
      "title" -> Json.str(title), "lines" -> Json.Arr(lines.map(Json.str)))
  def cardOf(j: Json): Card = j("kind").string match
    case "open" => Card.Open(j("url").string, j("title").string)
    case _ => Card.Book(j("token").string, j("title").string, j("lines").list.map(_.string))

  def acted(a: Acted): Json = Json.Obj(Vector("pinned" -> Json.Obj(a.pinned.toVector.map((k, f) => k -> fact(f))),
    "visible" -> Json.Arr(a.visible.map((k, v) => Json.arr(Json.str(k), Json.str(v))))) ++
    a.ent.map(e => "ent" -> ent(e)) ++ a.card.map(c => "card" -> card(c)))
  def actedOf(j: Json): Acted = Acted(j.field("ent").map(entOf), j("pinned") match
      case Json.Obj(fs) => fs.map((k, f) => k -> factOf(f)).toMap
      case _ => Map.empty,
    j("visible") match
      case Json.Arr(ps) => ps.map(p => p.list(0).string -> p.list(1).string)
      case _ => Vector.empty,
    j.field("card").map(cardOf))

  def query(q: Query): Json = Json.obj("cls" -> Json.str(q.cls), "text" -> Json.str(q.text),
    "filters" -> Json.Arr(q.filters.map(f => Json.obj("prop" -> Json.str(f.prop), "op" -> Json.str(f.op.toString),
      "value" -> value(f.value)))),
    "scope" -> Json.str(q.scope match
      case Scope.Focus => "focus"
      case Scope.All => "mall"
      case Scope.Source(id) => "source:" + id),
    "limit" -> Json.num(q.limit), "lang" -> Json.str(q.lang.toString))
  def queryOf(j: Json): Query = Query(j("cls").string, j("text").string, j("filters").list.map(f =>
    Filter(f("prop").string, Op.valueOf(f("op").string), valueOf(f("value")))),
    j("scope").string match
      case "focus" => Scope.Focus
      case "mall" => Scope.All
      case s => Scope.Source(s.stripPrefix("source:")),
    j("limit").int, Lang.parse(j("lang").string).getOrElse(Lang.pl))
