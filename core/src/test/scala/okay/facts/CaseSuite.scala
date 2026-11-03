package okay.facts

import okay.facts.tck.Demo
import okay.scala3.*
import okay.scala3.std.Clock

/** NAMES IN THEIR CASES (specs/design.md §17): the rules, an owner's forms first, a case-asked parameter's select by
 * its sureness, the validator's `case`; and the router's answer into the conversation (`Bind.authored`) */
class CaseSuite extends Ground:
  test("Polish first names: genitive, instrumental, dative, accusative; a parenthesised tail kept"):
    def pl(n: String, c: Case) = Morph.inflect(n, Lang.pl, c)
    assertEquals(Seq("Marek", "Marek (demo)", "Adam", "Tomasz", "Jacek", "Henryk", "Kuba", "Ewa (demo)", "Anna",
      "Kasia", "Natalia", "Maja", "Agnieszka", "Ola", "Patrycja").map(pl(_, Case.gen)), Seq("Marka", "Marka (demo)",
      "Adama", "Tomasza", "Jacka", "Henryka", "Kuby", "Ewy (demo)", "Anny", "Kasi", "Natalii", "Mai", "Agnieszki",
      "Oli", "Patrycji").map(Some(_)))
    assertEquals(Seq("Marek", "Adam", "Henryk", "Ewa", "Kasia", "Kuba").map(pl(_, Case.ins)), Seq("Markiem", "Adamem",
      "Henrykiem", "Ewą", "Kasią", "Kubą").map(Some(_)))
    assertEquals(Seq("Marek", "Ewa", "Agnieszka", "Olga", "Marta").map(pl(_, Case.dat)), Seq("Markowi", "Ewie",
      "Agnieszce", "Oldze", "Marcie").map(Some(_)))
    assertEquals(pl("Ewa", Case.acc), Some("Ewę"))
    assertEquals(pl("Paweł", Case.gen), Some("Pawła"))

  test("never guessed: other scripts, two words, unknown endings, women's names of a consonant"):
    for (n, l) <- Seq("Marek" -> Lang.uk, "Олег" -> Lang.pl, "Anna Nowak" -> Lang.pl, "Antoni" -> Lang.pl,
      "Hugo" -> Lang.pl, "Inez" -> Lang.pl, "Staś" -> Lang.pl, "Studio 7" -> Lang.pl) do
      assertEquals(Morph.inflect(n, l, Case.gen), None, n)
    assertEquals(Morph.inflect("Marek", Lang.en, Case.gen), Some("Marek"))
    assertEquals(Morph.inflect("Marek", Lang.pl, Case.nom), Some("Marek"))

  test("Ukrainian and Russian first names"):
    def uk(n: String, c: Case) = Morph.inflect(n, Lang.uk, c)
    def ru(n: String, c: Case) = Morph.inflect(n, Lang.ru, c)
    assertEquals(Seq("Олег", "Андрій", "Петро", "Ірина", "Марія", "Оля", "Ігор").map(uk(_, Case.gen)), Seq("Олега",
      "Андрія", "Петра", "Ірини", "Марії", "Олі", "Ігоря").map(Some(_)))
    assertEquals(Seq("Олег", "Ірина", "Марія", "Андрій").map(uk(_, Case.ins)), Seq("Олегом", "Іриною", "Марією",
      "Андрієм").map(Some(_)))
    assertEquals(uk("Наталка", Case.dat), Some("Наталці"))
    assertEquals(Seq("Иван", "Андрей", "Игорь", "Анна", "Ольга", "Мария", "Павел", "Наташа").map(ru(_, Case.gen)),
      Seq("Ивана", "Андрея", "Игоря", "Анны", "Ольги", "Марии", "Павла", "Наташи").map(Some(_)))
    assertEquals(Seq("Иван", "Анна", "Наташа", "Мария").map(ru(_, Case.ins)), Seq("Иваном", "Анной", "Наташей",
      "Марией").map(Some(_)))

  val inCase: Answer = Answer(Vector("t" -> "time(#s1)", "m" -> "name(master(#s1)) as gen"),
    "Jutro: {t} {m, select, sure {u {m}} other {(mistrz: {m})}}.")

  test("a case-asked name: its form when known, the select's `sure`; English needs none"):
    val r = render(inCase).toOption.get
    assertEquals(r.text, "Jutro: 17:30 u Marka.")
    val chip = r.chips.find(_.param == "m").get
    assertEquals((chip.kase, chip.sure), ("gen", true))
    assertEquals(render(inCase.copy(template = "Tomorrow: {t} {m, select, sure {with {m}} other {({m})}}."), Lang.en)
      .map(_.text), Right("Tomorrow: 17:30 with Marek."))

  test("not known (Latin letters in a Ukrainian sentence): the nominative in the construction needing no case"):
    val r = render(inCase.copy(template = "Завтра: {t} {m, select, sure {у {m}} other {(майстер: {m})}}."), Lang.uk)
    assertEquals(r.map(_.text), Right("Завтра: 17:30 (майстер: Marek)."))

  test("an owner's form comes first (the data's `name.<case>`)"):
    val owner = Demo.rows.map(r => if r.ent == Demo.master("barber-demo", "marek") then
      r.copy(values = r.values + ("name.gen" -> Value.Text(Map(Lang.pl -> "Mareczka")))) else r)
    val r = run(Facts.table[F, Pure, Either[Vector[Violation], Rendered]](Demo.schema, () => owner)(
      Resolve[F](inCase, handles, Demo.schema, Lang.pl)))
    assertEquals(r.map(_.text), Right("Jutro: 17:30 u Mareczka."))

  test("the validator: a person's name asks its case, a case other than nom inside its own select"):
    def codes(a: Answer) = Validator.check(a, handles, Demo.schema).map(_.code)
    assertEquals(codes(Answer(Vector("m" -> "name(master(#s1))"), "u {m}")), Vector("case"))
    assertEquals(codes(Answer(Vector("m" -> "master(#s1) as gen"), "u {m}")), Vector("case"))
    assertEquals(codes(Answer(Vector("m" -> "name(master(#s1)) as gen"), "{m, select, sure {u {m}} other {{m}}}")),
      Vector.empty)
    assertEquals(codes(Answer(Vector("m" -> "name(master(#s1)) as nom"), "Mistrz: {m}")), Vector.empty)
    // a shop's name is not a person's
    assertEquals(codes(Answer(Vector("w" -> "name(#shop1)"), "w {w}")), Vector.empty)

  test("the router's answer into the conversation: rendered, kept for the model lane, audited"):
    val s = Setup(Demo.schema, "a fictional barber")
    val a = Answer(Vector("s" -> "name(#v1)", "p" -> "price(#v1)"), "{s} kosztuje {p}.")
    val start = Conversation(handles = Handles(handles.all.take(1), Map("v" -> 1)))
    val program = Audit.collect(Bind.authored[Facts + Clock + Audit](s, Conversation(), "ile za combo?", Lang.pl,
      "shop:barber-demo", start.handles, None, a, Vector.empty, "price", 0.9))
    val (_, (audit, out)) = run(Demo.facts(Some("barber-demo"))(Clock.virtual(Demo.now)(program)))
    val (c, reply) = out.toOption.get
    assertEquals(reply.rendered.map(_.text), Some("Strzyżenie + broda kosztuje 90,00 zł."))
    val seen = c.history.map(_.render).mkString
    assert(seen.contains("#v1") && seen.contains("{s} kosztuje {p}.") && !seen.contains("90"), seen)
    assert(audit.exists { case Audited.Routed("price", 0.9, Vector(), _) => true; case _ => false })
    assertEquals(audit.count { case Audited.Read(_, _, _) => true; case _ => false }, 2)
    // a broken authored answer is told, not rendered
    val (_, bad) = run(Demo.facts(Some("barber-demo"))(Clock.virtual(Demo.now)(Audit.ignore(
      Bind.authored[Facts + Clock + Audit](s, Conversation(), "x", Lang.pl, "shop:barber-demo", start.handles, None,
        Answer(Vector.empty, "90 zł"), Vector.empty, "price", 0.9)))))
    assertEquals(bad.left.map(_.map(_.code)), Left(Vector("digits")))
