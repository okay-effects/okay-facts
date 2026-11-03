package okay.facts

import okay.facts.tck.Demo
import okay.llm.Llm
import okay.scala3.*
import okay.scala3.std.Clock

/** the binding layer on the fake model port: fixed outputs replayed, the demo's data, Warsaw's 2026-11-03 noon */
class BindSuite extends munit.FunSuite:
  type B[X] = (Llm + Facts + Clock + Audit + Emit)[X]

  def turn(s: Setup, outputs: Vector[String], user: String, c: Conversation = Conversation(), l: Lang = Lang.pl,
           focus: String = "shop:barber-demo"): ((Vector[String], Vector[Audited]), (Conversation, Reply)) =
    val focusShop = Some(focus).filter(_.startsWith("shop:")).map(_.stripPrefix("shop:"))
    val program = Emit.collect(Audit.collect(Bind.turn[B](s, c, user, l, focus)))
    val (_, (emitted, (audit, out))) = run(Demo.facts(focusShop)(Clock.virtual(Demo.now)(Model.replay(outputs)(
      program))))
    ((emitted, audit), out)

  val l3: Setup = Setup(Demo.schema, "You are the assistant of a fictional barber.", Level.L3)
  val l0: Setup = l3.copy(level = Level.L0)

  val findCombo: String = Model.call("find", """{"class": "Service", "text": "strzyżenie broda"}""")
  val findSlots: String = Model.call("find", """{"class": "Slot", "where": ["service = #v1", "date = tomorrow",
    "time >= 17:00"]}""".replace("\n", " "))
  val answer: String = """{"params": {"s": "name(#v1)", "p": "price(#v1)", "d": "duration(#v1)", "t1": "time(#s1)",
    "m1": "name(master(#s1))", "t2": "time(#s2)"}, "template": "Tak, {s} kosztuje {p} i trwa {d}. Jutro wolne: {t1}
    ({m1}) albo {t2}. Zapisać?"}""".replace("\n    ", " ").replace("\n", " ")
  val expected = "Tak, Strzyżenie + broda kosztuje 90,00 zł i trwa 45 minut. Jutro wolne: 17:30 (Marek) albo 18:15. " +
    "Zapisać?"

  test("§12's dialogue at L3: tool calls find, the JSON answer renders from the data; the model saw no value"):
    val (_, (c, r)) = turn(l3, Vector(findCombo, findSlots, answer), "Ile kosztuje strzyżenie z brodą i kiedy jutro " +
      "wieczorem?")
    assertEquals(r.rendered.map(_.text), Some(expected))
    assertEquals(r.violations, Vector.empty)
    assertEquals(c.handles.all.map(_.name).filter(Set("#shop1", "#v1", "#s1", "#s2")), Vector("#shop1", "#v1", "#s1",
      "#s2"))
    val seen = c.history.map(_.render).mkString
    for v <- Seq("90", "17:30", "18:15", "45", "Marek") do assert(!seen.contains(v), s"the model saw $v")

  test("§12's dialogue at L0: command lines, markers — the same text as the L3 JSON path"):
    val markers = "Tak, ⟦name(#v1)⟧ kosztuje ⟦price(#v1)⟧ i trwa ⟦duration(#v1)⟧. Jutro wolne: ⟦time(#s1)⟧ " +
      "(⟦name(master(#s1))⟧) albo ⟦time(#s2)⟧. Zapisać?"
    val (_, (_, r)) = turn(l0, Vector("⟦find Service text=\"strzyżenie broda\"⟧",
      "⟦find Slot where=\"service = #v1; date = tomorrow; time >= 17:00\"⟧", markers), "Ile kosztuje…?")
    assertEquals(r.rendered.map(_.text), Some(expected))
    val (_, (_, r3)) = turn(l3, Vector(findCombo, findSlots, answer), "Ile kosztuje…?")
    assertEquals(r.rendered.map(_.chips.map(c => (c.text, c.entity, c.sources))),
      r3.rendered.map(_.chips.map(c => (c.text, c.entity, c.sources))))

  test("L3: a text answer is asked again by the JSON schema, without tools"):
    val (_, (_, r)) = turn(l3, Vector(findCombo, "Sure, let me tell you.", """{"params": {"p": "price(#v1)"},
      "template": "Kosztuje {p}."}"""), "Cena?")
    assertEquals(r.rendered.map(_.text), Some("Kosztuje 90,00 zł."))
    assertEquals(r.raw.size, 3)

  test("regenerate: a template with digits re-asked, the violation named; then rendered"):
    val bad = """{"params": {"s": "name(#v1)"}, "template": "{s} kosztuje 90 zł."}"""
    val good = """{"params": {"s": "name(#v1)", "p": "price(#v1)"}, "template": "{s} kosztuje {p}."}"""
    val (_, (_, r)) = turn(l3, Vector(findCombo, bad, good), "Cena?")
    assertEquals(r.rendered.map(_.text), Some("Strzyżenie + broda kosztuje 90,00 zł."))
    assertEquals(r.regenerations, 1)
    assertEquals(r.violations.map(_.code), Vector("digits"))

  test("regenerate: a made-up handle, again and again — then the fixed notice, nothing invented"):
    val made = """{"params": {"p": "price(#v9)"}, "template": "Kosztuje {p}."}"""
    val (_, (_, r)) = turn(l3, Vector(findCombo, made), "Cena?")
    assertEquals(r.rendered, None)
    assert(r.blocked)
    assertEquals(r.notice, Some(Bind.notice("blocked", Lang.pl)))
    assertEquals(r.violations.map(_.code), Vector("handle", "handle", "handle"))

  test("block: the first violation, no answer; flag: delivered marked, its values still the resolver's"):
    val named = """{"params": {"p": "price(#v1)"}, "template": "Strzyżenie + broda kosztuje {p}."}"""
    val (_, (_, blocked)) = turn(l3.copy(policy = Policy.Block), Vector(findCombo, named), "Cena?")
    assert(blocked.blocked && blocked.rendered.isEmpty)
    val (_, (_, flagged)) = turn(l3.copy(policy = Policy.Flag), Vector(findCombo, named), "Cena?")
    assert(flagged.flagged)
    assertEquals(flagged.rendered.map(_.text), Some("Strzyżenie + broda kosztuje 90,00 zł."))

  test("the audit: every value read for an answer, and the answer's outcome"):
    val ((_, audit), _) = turn(l3, Vector(findCombo, findSlots, answer), "?")
    val reads = audit.collect { case Audited.Read(f, p, _) => p -> f.prop }
    assertEquals(reads.map(_._1).distinct, Vector("s", "p", "d", "t1", "m1", "t2"))
    assert(reads.contains("p" -> "price"))
    assertEquals(audit.collect { case a: Audited.Answered => a.violations }, Vector(Vector.empty))

  test("the focus moves to another shop: the model told, the context kept, find looks there by default"):
    val (_, (c1, _)) = turn(l3, Vector(findCombo, """{"params": {"p": "price(#v1)"}, "template": "{p}"}"""), "?",
      focus = "shop:barber-demo")
    val findManicure = Model.call("find", """{"class": "Manicure"}""")
    val (_, (c2, r2)) = turn(l3, Vector(findManicure, """{"params": {"c": "price(#v2) < price(#v1)"},
      "template": "{c, select, true {Taniej.} other {Drożej.}}"}"""), "A manicure here, cheaper?", c1,
      focus = "shop:nails-demo")
    assertEquals(c2.focus, "shop:nails-demo")
    assert(c2.history.exists(_.render.contains("now on the pages of #shop2")))
    assertEquals(c2.handles.get("#v1").map(_.ent.source), Some("barber-demo"))
    assertEquals(c2.handles.all.filter(_.ent.cls == "Manicure").map(_.ent.source).distinct, Vector("nails-demo"))
    assertEquals(r2.rendered.map(_.text), Some("Drożej."))

  test("find over the mall with a hidden filter; an action gives a card, its handle never a value"):
    val find = Model.call("find", """{"class": "Manicure", "where": ["price <= 150 PLN"], "scope": "mall"}""")
    val book = Model.call("book", """{"slot": "#s1"}""")
    val findSlot = Model.call("find", """{"class": "Slot", "where": ["service = #v1", "date = tomorrow"]}""")
    val (_, (c, r)) = turn(l3, Vector(find, findSlot, book, """{"params": {"t": "time(#s1)"},
      "template": "Potwierdź rezerwację na {t} w karcie."}"""), "manicure jutro?", focus = "mall")
    assertEquals(c.handles.all.filter(_.ent.cls == "Manicure").map(_.ent.source).distinct, Vector("nails-demo",
      "nails-ola"))
    assertEquals(r.cards.collect { case Card.Book(_, _, lines) => lines }, Vector(Vector("10:00")))
    assertEquals(r.rendered.map(_.text), Some("Potwierdź rezerwację na 10:00 w karcie."))

  test("a bad find is told to the model as an error, the conversation goes on"):
    val bad = Model.call("find", """{"class": "Service", "where": ["colour = red"]}""")
    val (_, (c, r)) = turn(l3, Vector(bad, findCombo, """{"params": {"p": "price(#v1)"}, "template": "{p}"}"""), "?")
    assertEquals(r.rendered.map(_.text), Some("90,00 zł"))
    assertEquals(c.handles.all.map(_.name).headOption, Some("#shop1"))

  test("streamed with params first: the pieces add up to the answer; a split plural held back until whole"):
    val s = l3.copy(stream = true)
    val streamed = """{"params": {"p": "price(#v1)", "n": "count(masters(#v1))"}, "template": "Kosztuje {p}.
      {n, plural, one {Robi to jedna osoba.} few {Robią to # osoby.} other {Robi to # osób.}} Zapraszamy!"}"""
      .replace("\n      ", " ")
    val ((pieces, _), (_, r)) = turn(s, Vector(findCombo, "Already?", streamed), "?")
    assertEquals(r.rendered.map(_.text), Some("Kosztuje 90,00 zł. Robią to 2 osoby. Zapraszamy!"))
    assertEquals(pieces.mkString, "Kosztuje 90,00 zł. Robią to 2 osoby. Zapraszamy!")
    assert(pieces.size >= 3, pieces.toString)
    assert(!pieces.exists(p => p.contains("Robią") && !p.contains("osoby")), pieces.toString)

  test("streamed: a digit midway withdraws what was sent; the answer then regenerated whole"):
    val s = l3.copy(stream = true)
    val bad = """{"params": {"p": "price(#v1)"}, "template": "Kosztuje {p}, czyli 90 zł."}"""
    val good = """{"params": {"p": "price(#v1)"}, "template": "Kosztuje {p}."}"""
    val ((pieces, _), (_, r)) = turn(s, Vector(findCombo, "Hm.", bad, good), "?")
    assert(pieces.contains(""), pieces.toString)
    assertEquals(r.rendered.map(_.text), Some("Kosztuje 90,00 zł."))

  test("the conversation as JSON, and back"):
    val (_, (c, _)) = turn(l3, Vector(findCombo, findSlots, answer), "?")
    assertEquals(Conversation.fromJson(Conversation.toJson(c)), c)

  test("a find's conditions: a path through a link, a link by name, an expression over handles, a bare number"):
    def keys(args: String, h: Handles = Handles(), focus: Option[String] = None): Vector[String] =
      run(Demo.facts(focus)(Bind.query[Facts](okay.effects.json.Json.fromText(args), Demo.schema, Demo.today, h,
        Lang.pl).flatMap {
        case Left(e) => pure(Vector(s"error: $e"))
        case Right(q) => Facts.find[Facts](q).map(_.map(_.ent.key))
      }))
    assertEquals(keys("""{"class": "Manicure", "where": ["shop.city = Kraków"], "scope": "mall"}"""), Vector("japanese"))
    assertEquals(keys("""{"class": "Manicure", "where": ["city(shop) = Kraków"], "scope": "mall"}"""),
      Vector("japanese"))
    assert(keys("""{"class": "Slot", "where": ["service = manicure hybrydowy", "date = tomorrow"], "scope": "mall"}""")
      .forall(_.startsWith("lila@")))
    val h = Handles().issue("v", Demo.service("nails-demo", "hybrid", "Manicure"), 3, Vector.empty)._1
    assertEquals(keys("""{"class": "Manicure", "where": ["price < price(#v1)"], "scope": "mall"}""", h),
      Vector("classic"))
    assertEquals(keys("""{"class": "Service", "where": ["price <= 80"], "scope": "mall"}"""), Vector("cut", "classic"))
    assert(keys("""{"class": "Service", "where": ["colour = red"]}""").head.startsWith("error"))
