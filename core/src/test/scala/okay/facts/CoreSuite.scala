package okay.facts

import okay.facts.tck.Demo
import okay.scala3.*

/** the shared ground of the core's tests: the demo's data, a conversation's handles as `find` would issue them */
trait Ground extends munit.FunSuite:
  type F[X] = Facts[X]

  def facts[A](p: A ! F, focus: Option[String] = None): A = run(Demo.facts(focus)(p))

  /** the handles of a conversation that found, in order: the combo (`#v1`), its two evening slots (`#s1`, `#s2`),
   * a nail studio's manicure (`#v2`), the barber (`#shop1`), the nail studio (`#shop2`), Marek (`#m1`) */
  val handles: Handles =
    val rows = Demo.rows
    def row(e: Ent) = rows.find(_.ent == e).get
    val slot17 = rows.find(r => r.ent.cls == "Slot" && r.ent.key.startsWith("marek@") &&
      r.values("time") == Value.At(Zone.instant(Demo.tomorrow, 17 * 60 + 30))).get
    val slot18 = rows.find(r => r.ent.cls == "Slot" && r.ent.key.startsWith("kuba@")).get
    Vector(row(Demo.service("barber-demo", "combo")), slot17, slot18,
      row(Demo.service("nails-demo", "hybrid", "Manicure")), row(Demo.shop("barber-demo")),
      row(Demo.shop("nails-demo")),
      row(Demo.master("barber-demo", "marek")))
      .foldLeft(Handles())((h, r) => h.issue(Demo.schema.prefix(r.ent.cls), r.ent, r.version,
        Table.visible(Demo.schema, r, Lang.pl))._1)

  def render(a: Answer, l: Lang = Lang.pl, h: Handles = handles): Either[Vector[Violation], Rendered] =
    facts(Resolve[F](a, h, Demo.schema, l))

class RenderSuite extends munit.FunSuite:
  test("plural categories: pl, uk, ru, en (CLDR's integer rules)"):
    val pl = Seq(0, 1, 2, 4, 5, 11, 12, 14, 21, 22, 25, 102, 112).map(Render.category(_, Lang.pl))
    assertEquals(pl, Seq("many", "one", "few", "few", "many", "many", "many", "many", "many", "few", "many", "few",
      "many"))
    val ru = Seq(0, 1, 2, 5, 11, 21, 22, 25, 111, 101).map(Render.category(_, Lang.ru))
    assertEquals(ru, Seq("many", "one", "few", "many", "many", "one", "few", "many", "many", "one"))
    assertEquals(Seq(1, 21, 3, 12).map(Render.category(_, Lang.uk)), Seq("one", "one", "few", "many"))
    assertEquals(Seq(1, 2, 0).map(Render.category(_, Lang.en)), Seq("one", "other", "other"))

  test("a count renders with its agreeing noun in all four languages"):
    def seats(n: Long, l: Lang) = Render(Value.Count(n, Noun.seat), l)
    assertEquals(Seq(1L, 3L, 5L, 22L).map(seats(_, Lang.pl)), Seq("1 wolne miejsce", "3 wolne miejsca",
      "5 wolnych miejsc", "22 wolne miejsca"))
    assertEquals(Seq(1L, 3L, 5L, 21L).map(seats(_, Lang.uk)), Seq("1 вільне місце", "3 вільні місця",
      "5 вільних місць", "21 вільне місце"))
    assertEquals(Seq(1L, 3L, 5L, 11L).map(seats(_, Lang.ru)), Seq("1 свободное место", "3 свободных места",
      "5 свободных мест", "11 свободных мест"))
    assertEquals(Seq(1L, 3L).map(seats(_, Lang.en)), Seq("1 free seat", "3 free seats"))

  test("durations: minutes and hours, agreeing"):
    assertEquals(Render(Value.Minutes(45), Lang.pl), "45 minut")
    assertEquals(Render(Value.Minutes(1), Lang.pl), "1 minuta")
    assertEquals(Render(Value.Minutes(22), Lang.pl), "22 minuty")
    assertEquals(Render(Value.Minutes(75), Lang.pl), "1 godzina 15 minut")
    assertEquals(Render(Value.Minutes(120), Lang.uk), "2 години")
    assertEquals(Render(Value.Minutes(21), Lang.ru), "21 минута")
    assertEquals(Render(Value.Minutes(45), Lang.ru), "45 минут")
    assertEquals(Render(Value.Minutes(90), Lang.en), "1 hour 30 minutes")

  test("money, times in Warsaw (summer and winter), days"):
    assertEquals(Render(Value.zl(9000), Lang.pl), "90,00 zł")
    assertEquals(Render(Value.zl(1234550), Lang.uk), "12 345,50 zł")
    assertEquals(Render(Value.zl(9000), Lang.en), "90.00 zł")
    assertEquals(Render(Value.zl(9000), Lang.pl, "short"), "90 zł")
    assertEquals(Render(Value.At(Zone.instant("2026-11-04", 17 * 60 + 30)), Lang.pl), "17:30")
    assertEquals(Render(Value.At(Zone.instant("2026-07-04", 9 * 60)), Lang.pl), "09:00")
    assertEquals(Zone.offset(Zone.instant("2026-07-04", 9 * 60)), 7200000L)
    assertEquals(Zone.offset(Zone.instant("2026-11-04", 9 * 60)), 3600000L)
    assertEquals(Render(Value.Day("2026-11-04"), Lang.pl), "4 listopada")
    assertEquals(Render(Value.Day("2026-11-04"), Lang.ru, "long"), "среда, 4 ноября")
    assertEquals(Zone.weekday("2026-11-03"), 2)

class IcuSuite extends munit.FunSuite:
  def show(t: String, values: Map[String, Value], l: Lang = Lang.pl): String =
    val parts = Icu.parse(t).fold(e => fail(e.toString), identity)
    Icu.format(parts, l, n => Render(values(n), l), n => Render.plural(values(n)), n => values(n) match
      case Value.Bool(b) => b.toString
      case _ => "other").map {
      case Icu.Seg.Text(x) => x
      case Icu.Seg.Value(_, x) => x
    }.mkString

  test("placeholders, plurals with #, selects, quotes"):
    val n = Value.Count(3, Noun.seat)
    assertEquals(show("Zostały {n}.", Map("n" -> n)), "Zostały 3 wolne miejsca.")
    assertEquals(show("{n, plural, one {Zostało # miejsce} few {Zostały # miejsca} many {Zostało # miejsc} " +
      "other {Zostało # miejsca}}", Map("n" -> Value.Num(5))), "Zostało 5 miejsc")
    assertEquals(show("{n, plural, =0 {Brak} other {Są}}", Map("n" -> Value.Num(0))), "Brak")
    assertEquals(show("{c, select, true {Tak, taniej.} other {Nie, drożej.}}", Map("c" -> Value.Bool(false))),
      "Nie, drożej.")
    assertEquals(show("It''s '{'literal'}' {x}", Map("x" -> Value.text("ok")), Lang.en), "It's {literal} ok")
    assertEquals(Icu.names(Icu.parse("{a} {b, plural, other {# {c}}}").toOption.get), Vector("a", "b", "c"))

  test("errors: unclosed, no other, unbalanced"):
    assert(Icu.parse("{a").isLeft)
    assert(Icu.parse("{a, plural, one {x}}").isLeft)
    assert(Icu.parse("a } b").isLeft)

  test("quoting text for a message reads back the same"):
    val raw = "It's {odd} — 'quoted'"
    assertEquals(show(Icu.quote(raw), Map.empty, Lang.en), raw)

class ExprSuite extends Ground:
  def typed(s: String): Either[String, Type] =
    Expr.parse(s).flatMap(Expr.typeOf(_, handles, Demo.schema))
  def eval(s: String): Either[String, Value] = facts(Eval[F](Expr.parse(s).toOption.get, handles)).map(_.value)

  test("parses references, paths, aggregates, comparisons, hints"):
    assertEquals(Expr.parse("price(#v1)"), Right(Expr.Call("price", Vector(Expr.H("#v1")))))
    assertEquals(Expr.parse("price(#v1) <= 200 PLN").map(Expr.show), Right("price(#v1) <= 200 PLN"))
    assertEquals(Expr.parse("argmin(price, #v1, #v2)").map(Expr.show), Right("argmin(price, #v1, #v2)"))
    assertEquals(Expr.parse("price(#v1) as short"), Right(Expr.As(Expr.Call("price", Vector(Expr.H("#v1"))),
      "short")))
    assertEquals(Expr.parse("time(#s1) >= 17:00").map(Expr.show), Right("time(#s1) >= 17:00"))
    assert(Expr.parse("price(\"90 zł\")").isLeft)
    assert(Expr.parse("price(#v1").isLeft)

  test("types: references, paths over functional links, claims"):
    assertEquals(typed("price(#v1)"), Right(Type.Money))
    assertEquals(typed("name(master(#s1))"), Right(Type.Text))
    assertEquals(typed("city(shop(#v2))"), Right(Type.Text))
    assertEquals(typed("price(#v1) < price(#v2)"), Right(Type.Bool))
    assertEquals(typed("price(#v1) - price(#v2)"), Right(Type.Money))
    assertEquals(typed("min(price(masters(#v1)))").isLeft, true)
    assertEquals(typed("count(masters(#v1))"), Right(Type.Number))
    assertEquals(typed("argmin(price, #v1, #v2)"), Right(Type.Entity("Service")))

  test("refusals: a made-up handle, a non-functional path, a literal, a type error"):
    assert(typed("price(#v9)").left.exists(_.startsWith("unknown handle #v9")))
    assert(typed("name(masters(#v1))").left.exists(_.startsWith("non-functional path")))
    assert(typed("200 PLN").left.exists(_.contains("literal")))
    assert(typed("price(#v1) + 10 PLN").left.exists(_.contains("literal")))
    assert(typed("price(#v1) < duration(#v1)").isLeft)
    assert(typed("colour(#v1)").left.exists(_.contains("no property")))

  test("evaluates from the data: values, differences, claims, aggregates over sets"):
    assertEquals(eval("price(#v1)"), Right(Value.zl(9000)))
    assertEquals(eval("price(#v2) - price(#v1)"), Right(Value.zl(2000)))
    assertEquals(eval("price(#v1) < price(#v2)"), Right(Value.Bool(true)))
    assertEquals(eval("price(#v1) <= 80 PLN"), Right(Value.Bool(false)))
    assertEquals(eval("time(#s1) >= 17:00"), Right(Value.Bool(true)))
    assertEquals(eval("count(masters(#v1))"), Right(Value.Num(2)))
    assertEquals(eval("argmin(price, #v1, #v2)"), Right(Value.Entity(Demo.service("barber-demo", "combo"))))
    assertEquals(eval("min(time(#s1), time(#s2))"), Right(Value.At(Zone.instant(Demo.tomorrow, 17 * 60 + 30))))
    assertEquals(eval("sum(price(#v1), price(#v2))"), Right(Value.zl(20000)))

class ValidatorSuite extends Ground:
  def codes(a: Answer): Vector[String] = Validator.check(a, handles, Demo.schema).map(_.code)

  test("a clean answer passes"):
    assertEquals(codes(Answer(Vector("s" -> "name(#v1)", "p" -> "price(#v1)"), "{s} kosztuje {p}.")), Vector.empty)

  test("digits in the template"):
    assertEquals(codes(Answer(Vector("s" -> "name(#v1)"), "{s} kosztuje 90 zł.")), Vector("digits"))
    assertEquals(codes(Answer(Vector("n" -> "count(masters(#v1))"),
      "{n, plural, one {# osoba} other {# osób, w tym 2 nowe}}")), Vector("digits"))

  test("a declared entity's name written in the template"):
    assertEquals(codes(Answer(Vector("p" -> "price(#v1)"), "Strzyżenie + broda kosztuje {p}.")), Vector("name"))
    assertEquals(codes(Answer(Vector("p" -> "price(#v1)"), "Usługa w Barber Demo kosztuje {p}.")), Vector("name"))

  test("a unit after a counted value's placeholder"):
    assertEquals(codes(Answer(Vector("d" -> "duration(#v1)"), "Trwa {d} minut.")), Vector("unit"))
    assertEquals(codes(Answer(Vector("p" -> "price(#v1)"), "Kosztuje {p} zł.")), Vector("unit"))

  test("a placeholder without a parameter, a parameter not used"):
    assertEquals(codes(Answer(Vector("p" -> "price(#v1)"), "Kosztuje {p}, trwa {d}.")), Vector("placeholder"))
    assertEquals(codes(Answer(Vector("p" -> "price(#v1)", "d" -> "duration(#v1)"), "Kosztuje {p}.")),
      Vector("unused"))

  test("a parameter that does not parse, does not type, names a made-up handle, a non-functional path, a literal"):
    assertEquals(codes(Answer(Vector("p" -> "price(#v1"), "{p}")), Vector("parse"))
    assertEquals(codes(Answer(Vector("p" -> "price(#v1) < duration(#v1)"), "{p}")), Vector("type"))
    assertEquals(codes(Answer(Vector("p" -> "price(#v7)"), "{p}")), Vector("handle"))
    assertEquals(codes(Answer(Vector("p" -> "name(masters(#v1))"), "{p}")), Vector("functional"))
    assertEquals(codes(Answer(Vector("p" -> "90 PLN"), "{p}")), Vector("literal"))
    assertEquals(codes(Answer(Vector.empty, "{p, select, true {x} other {y")), Vector("syntax"))

  test("claims are expressions, evaluated by the resolver"):
    val a = Answer(Vector("c" -> "price(#v1) <= 200 PLN"), "{c, select, true {Mieści się w budżecie.} other {Za " +
      "drogo.}}")
    assertEquals(render(a).map(_.text), Right("Mieści się w budżecie."))
    val wrong = Answer(Vector("c" -> "price(#shop1) <= 200 PLN"), "{c}")
    assertEquals(codes(wrong), Vector("type"))

class ResolveSuite extends Ground:
  /** specs/design.md §12: the model's raw output has no fact; the user reads every value from the data */
  val example: Answer = Answer(Vector("s" -> "name(#v1)", "p" -> "price(#v1)", "d" -> "duration(#v1)",
    "t1" -> "time(#s1)", "m1" -> "name(master(#s1)) as nom", "t2" -> "time(#s2)"),
    "Tak, {s} kosztuje {p} i trwa {d}. Jutro wolne: {t1} ({m1}) albo {t2}. Zapisać?")

  test("the dialogue example of §12 renders from the data, every value a chip with its entity and source"):
    val r = render(example).fold(v => fail(v.toString), identity)
    assertEquals(r.text, "Tak, Strzyżenie + broda kosztuje 90,00 zł i trwa 45 minut. Jutro wolne: 17:30 (Marek) albo " +
      "18:15. Zapisać?")
    val price = r.chips.find(_.param == "p").get
    assertEquals(price.entity, "Strzyżenie + broda")
    assertEquals(price.handle, Some("#v1"))
    assertEquals(price.sources.map(f => (f.ent.source, f.ent.key, f.prop, f.version)), Vector(("barber-demo",
      "combo", "price", 40L)))
    assertEquals(r.chips.find(_.param == "t1").flatMap(_.link), Some("book:#s1"))
    assertEquals(r.chips.find(_.param == "m1").map(_.entity), Some("Strzyżenie + broda"))

  test("the same answer in four languages: values in each language's words"):
    val en = Answer(example.params, "Yes, {s} costs {p} and takes {d}. Tomorrow: {t1} ({m1}) or {t2}. Book it?")
    assertEquals(render(en, Lang.en).map(_.text), Right("Yes, Haircut + beard costs 90.00 zł and takes 45 minutes. " +
      "Tomorrow: 17:30 (Marek) or 18:15. Book it?"))
    val uk = Answer(Vector("s" -> "name(#v1)", "d" -> "duration(#v1)"), "{s} триває {d}.")
    assertEquals(render(uk, Lang.uk).map(_.text), Right("Стрижка + борода триває 45 хвилин."))
    val ru = Answer(Vector("d" -> "duration(#v2)"), "Займёт {d}.")
    assertEquals(render(ru, Lang.ru).map(_.text), Right("Займёт 1 час."))

  test("a cross-shop comparison is an expression; its boolean picks the template's words"):
    val a = Answer(Vector("c" -> "price(#v1) < price(#v2)", "d" -> "price(#v2) - price(#v1)"),
      "{c, select, true {Tak, taniej o {d}.} other {Nie.}}")
    assertEquals(render(a).map(_.text), Right("Tak, taniej o 20,00 zł."))

  test("a pinned action's values render as they were; others current"):
    val booked = Ent("barber-demo", "Booking", "BRB-1")
    val pinned = Map("price" -> Fact(Value.zl(9000), booked, "price", 40), "time" -> Fact(Value.At(Zone.instant(
      Demo.tomorrow, 17 * 60 + 30)), booked, "time", 40))
    val (h, b) = handles.issue("b", booked, 40, Vector.empty, pinned)
    val r = render(Answer(Vector("t" -> "time(#b1)", "p" -> "price(#b1)"), "Zarezerwowano: {t}, {p}."), h = h)
    assertEquals(b.name, "#b1")
    assertEquals(r.map(_.text), Right("Zarezerwowano: 17:30, 90,00 zł."))

  test("violations are returned, nothing rendered"):
    assertEquals(render(Answer(Vector("p" -> "price(#v1)"), "Kosztuje 90 zł.")).left.map(_.map(_.code)),
      Left(Vector("digits", "unused")))

class TableSuite extends Ground:
  def found(q: Query, focus: Option[String] = None): Vector[String] =
    facts(Facts.find[F](q), focus).map(_.ent.key)

  test("words match the visible texts in any language; filters on hidden values by the resolver"):
    assertEquals(found(Query("Service", "strzyżenie broda"), Some("barber-demo")).headOption, Some("combo"))
    assertEquals(found(Query("Service", "haircut beard", lang = Lang.en), Some("barber-demo")).headOption,
      Some("combo"))
    assertEquals(found(Query("Manicure", filters = Vector(Filter("price", Op.Le, Value.zl(12000))),
      scope = Scope.All)), Vector("hybrid", "classic"))
    val tomorrowMorning = Vector(Filter("date", Op.Eq, Value.Day(Demo.tomorrow)), Filter("time", Op.Lt,
      Value.Clock(12 * 60)))
    assertEquals(found(Query("Slot", filters = tomorrowMorning :+ Filter("service", Op.Eq, Value.Entity(
      Demo.service("nails-demo", "hybrid", "Manicure")))), Some("nails-demo")).size, 1)

  test("visible descriptors only: no price, no time reaches the model"):
    val f = facts(Facts.find[F](Query("Slot", scope = Scope.All, limit = 2)))
    assert(f.forall(_.visible.map(_._1).toSet.subsetOf(Set("name"))))
    assert(f.flatMap(_.visible.map(_._2)).forall(!_.exists(_.isDigit)))

class WireSuite extends Ground:
  test("an operation over the wire: asked as JSON, served by the local facts, read back the same"):
    val ops: Vector[Facts[?]] = Vector(Facts.Find(Query("Service", "strzyżenie broda", scope = Scope.All)),
      Facts.Get(Demo.service("barber-demo", "combo"), "price"),
      Facts.Act("open", Map("shop" -> Value.Entity(Demo.shop("nails-demo"))), Lang.pl))
    for op <- ops do
      val served = facts(Wire.serve[F](okay.effects.json.Json.fromText(Wire.request(op).render)))
      val direct = facts(perform[Facts](op.asInstanceOf[Facts[Any]]))
      assertEquals(Wire.answer(op.asInstanceOf[Facts[Any]], Right(served)), direct)

  test("a prefetch over the wire: one call answers its finds, each found's properties and its gets, as asked alone"):
    val q = Query("Service", scope = Scope.Source("barber-demo"), limit = 20)
    val combo = Demo.service("barber-demo", "combo")
    val op: Facts.Prefetch = Facts.Prefetch(Vector(q -> Vector("price", "duration", "nothing")), Vector(combo -> "name",
      combo -> "nothing"))
    val served = facts(Wire.serve[F](okay.effects.json.Json.fromText(Wire.request(op).render)))
    val Some((Vector((found, props)), gets)) = Wire.fetched(op, Right(served)): @unchecked
    assertEquals(found, facts(Facts.find[F](q)))
    val asked = found.map(f => Vector("price", "duration", "nothing").map(p => p -> facts(Facts.get[F](f.ent, p)))
      .toMap)
    assertEquals(props, asked)
    assert(props.exists(_("price").isDefined) && props.forall(_("nothing").isEmpty))
    assertEquals(gets, Vector(facts(Facts.get[F](combo, "name")), None))
    // a handler with no cache ignores the hint
    assertEquals(facts(Facts.prefetch[F](op.finds, op.gets)), ())

class ChipSuite extends Ground:
  test("a value computed of several entities: the chip names them all"):
    val r = render(Answer(Vector("c" -> "price(#v1) < price(#v2)"), "{c, select, true {Taniej.} other {Drożej.}}"))
    assertEquals(r.map(_.chips.head.entity), Right("Strzyżenie + broda, Manicure hybrydowy"))

class ClaimSuite extends Ground:
  def codes(a: Answer): Vector[String] = Validator.check(a, handles, Demo.schema).map(_.code)
  test("a comparison said in words is a claim the model guessed; inside a select on an expression it is the data's"):
    assertEquals(codes(Answer(Vector("p" -> "price(#v1)"), "Kosztuje {p}, czyli taniej niż manicure.")),
      Vector("claim"))
    assertEquals(codes(Answer(Vector("p" -> "price(#v1)"), "It costs {p}, cheaper than there.")), Vector("claim"))
    assertEquals(codes(Answer(Vector("c" -> "price(#v1) < price(#v2)"),
      "{c, select, true {Tak, taniej.} other {Nie, drożej.}}")), Vector.empty)

class DistanceSuite extends Ground:
  val warsaw = new Value.Point(52.2297, 21.0122)
  val krakow = new Value.Point(50.0647, 19.9450)

  test("a distance is computed here: the great circle's metres, rendered as people say it, compared in km"):
    assert(math.abs(Geo.meters(warsaw, krakow) - 252000) < 2000, Geo.meters(warsaw, krakow))
    assertEquals(Geo.meters(warsaw, warsaw), 0L)
    assertEquals(Render(Value.Meters(1234), Lang.pl), "1,2 km")
    assertEquals(Render(Value.Meters(1234), Lang.en), "1.2 km")
    assertEquals(Render(Value.Meters(847), Lang.uk), "850 m")
    assertEquals(Render(Value.Meters(12400), Lang.ru), "12 km")
    assertEquals(Value.compare(Value.Meters(2500), Value.Num(3)), Some(-1))
    assertEquals(Codec.valueOf(Codec.value(warsaw)), warsaw)
    assertEquals(Codec.valueOf(Codec.value(Value.Meters(5))), Value.Meters(5))

  test("distance(#a, #b) of two places' points (a handle's pinned one too), its sources both"):
    val (h1, a) = Handles().issue("here", Ent("user", "Here", "here"), 0L, Vector.empty, Map("point" ->
      Fact(warsaw, Ent("user", "Here", "here"), "point")))
    val (h2, b) = h1.issue("here", Ent("user", "Here", "there"), 0L, Vector.empty, Map("point" ->
      Fact(krakow, Ent("user", "Here", "there"), "point")))
    val got = facts(Eval[F](Expr.parse(s"distance(${a.name}, ${b.name})").toOption.get, h2))
    assert(got.exists(c => c.value match
      case Value.Meters(m) => math.abs(m - 252000) < 2000 && c.facts.size == 2
      case _ => false), got)
