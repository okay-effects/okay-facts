package okay.facts.tck

import okay.effects.json.Json
import okay.facts.*

/** A QUESTION OF THE TCK: a conversation's turns (each a question, its language, the user's focus), and what the last
 * answer must show — every text of `expect` (a value only the data has), one of `anyOf`, none of `forbid` (a value
 * the data has not), the entities a find must reach (`source/key`), a card of a kind */
final case class Question(id: String, turns: Vector[(String, Lang, String)], expect: Vector[String] = Vector.empty,
                          anyOf: Vector[String] = Vector.empty, forbid: Vector[String] = Vector.empty,
                          finds: Vector[String] = Vector.empty, card: Option[String] = None, about: String = "")

/** WHAT ONE QUESTION GAVE: passed (the expectations met), strict (passed with no violation and no regeneration),
 * the violations seen, the regenerations, whether the final answer came as the format asked (JSON at L2+, markers
 * at L0), whether tools were called, the entities reached, the tokens and the time */
final case class Outcome(id: String, passed: Boolean, strict: Boolean, rendered: String, violations: Vector[String],
                         regenerations: Int, formatted: Boolean, tooled: Boolean, reached: Int, toReach: Int,
                         invented: Int, tokens: Int, millis: Long, failure: Option[String], raw: Vector[String])

/**
 * THE CONFORMANCE SUITE (specs/design.md §11): questions over the reference data (`Demo`: a fictional barber, two
 * nail studios), in pl, uk, ru and en, in a shop and across the mall — prices, durations, slots, masters, places,
 * ratings, comparisons, things not there, actions, a conversation moving between shops, a user asking to break the
 * rule. Run against a model, it gives the model's passport
 */
object Suite:
  private def q(id: String, text: String, l: Lang, focus: String, expect: Vector[String] = Vector.empty,
                anyOf: Vector[String] = Vector.empty, forbid: Vector[String] = Vector.empty,
                finds: Vector[String] = Vector.empty, card: Option[String] = None, about: String = ""): Question =
    Question(id, Vector((text, l, focus)), expect, anyOf, forbid, finds, card, about)

  private val barber = "shop:barber-demo"
  private val nails = "shop:nails-demo"
  private val mall = "mall"
  private val combo = "barber-demo/combo"
  private val cut = "barber-demo/cut"

  val questions: Vector[Question] = Vector(
    q("price-pl", "Ile kosztuje strzyżenie z brodą?", Lang.pl, barber, Vector("90,00 zł"), finds = Vector(combo)),
    q("duration-pl", "Jak długo trwa strzyżenie z brodą?", Lang.pl, barber, Vector("45 minut"), finds = Vector(combo)),
    q("slots-pl", "Czy macie jutro wolny termin na strzyżenie z brodą po 17?", Lang.pl, barber,
      Vector("17:30", "18:15"), finds = Vector(combo)),
    q("master-pl", "Kto jutro może mnie ostrzyc (strzyżenie z brodą) o 17:30?", Lang.pl, barber, Vector("Marek")),
    q("cheaper-pl", "Co jest tańsze: strzyżenie męskie czy strzyżenie z brodą?", Lang.pl, barber,
      anyOf = Vector("Strzyżenie męskie", "70,00 zł"), finds = Vector(combo, cut)),
    q("difference-pl", "O ile droższe jest strzyżenie z brodą od zwykłego strzyżenia męskiego?", Lang.pl, barber,
      Vector("20,00 zł"), finds = Vector(combo, cut)),
    q("voucher-pl", "Ile kosztuje voucher prezentowy?", Lang.pl, barber, Vector("100,00 zł")),
    q("address-pl", "Pod jakim adresem jesteście?", Lang.pl, barber, Vector("Przykładowa 1")),
    q("rating-pl", "Jaką macie średnią ocenę?", Lang.pl, barber, Vector("4,8")),
    q("reviews-pl", "Ile macie opinii?", Lang.pl, barber, Vector("23 opinie")),
    q("absent-pl", "Ile kosztuje u was koloryzacja włosów?", Lang.pl, barber, forbid = Vector("zł"),
      about = "a service the shop has not: say so, invent nothing"),
    q("absent-manicure-pl", "Czy robicie manicure?", Lang.pl, barber, forbid = Vector("zł"),
      about = "another shop's service is not this shop's"),
    q("price-en", "How much is a haircut with beard?", Lang.en, barber, Vector("90.00 zł"), finds = Vector(combo)),
    q("price-uk", "Скільки коштує стрижка з бородою?", Lang.uk, barber, Vector("90,00 zł"), finds = Vector(combo)),
    q("duration-ru", "Сколько длится стрижка с бородой?", Lang.ru, barber, Vector("45 минут"), finds = Vector(combo)),
    q("duration-en", "How long does the men's haircut take?", Lang.en, barber, Vector("30 minutes"),
      finds = Vector(cut)),
    q("slots-uk", "Чи є завтра вільний час на чоловічу стрижку?", Lang.uk, barber, Vector("10:00"),
      finds = Vector(cut)),
    q("earliest-pl", "Jaki jest najwcześniejszy jutro termin na strzyżenie z brodą?", Lang.pl, barber,
      Vector("11:00")),
    q("budget-pl", "Czy strzyżenie z brodą zmieści się w budżecie 100 zł?", Lang.pl, barber,
      anyOf = Vector("Tak", "tak", "mieści", "90,00 zł"), forbid = Vector("Nie,", "nie mieści")),
    q("hybrid-pl", "Ile kosztuje manicure hybrydowy?", Lang.pl, nails, Vector("110,00 zł"),
      finds = Vector("nails-demo/hybrid")),
    q("pedicure-pl", "Ile trwa pedicure?", Lang.pl, nails, Vector("1 godzina 15 minut")),
    q("morning-en", "Do you have a hybrid manicure slot tomorrow morning?", Lang.en, nails,
      anyOf = Vector("10:00", "09:00")),
    q("classic-ru", "Сколько стоит классический маникюр?", Lang.ru, nails, Vector("80,00 zł")),
    q("address-uk", "Де ви знаходитесь?", Lang.uk, nails, Vector("Testowa 3")),
    q("cheapest-pl", "Który manicure jest u was najtańszy?", Lang.pl, nails, anyOf = Vector("Manicure klasyczny",
      "80,00 zł")),
    q("mall-manicure-pl", "manicure jutro rano do 150 zł", Lang.pl, mall,
      anyOf = Vector("Studio Paznokci Lila", "Paznokcie Ola", "Manicure", "10:00", "09:30", "09:00"),
      forbid = Vector("Barber Demo")),
    q("mall-krakow-en", "Where can I get a manicure in Kraków?", Lang.en, mall, Vector("Paznokcie Ola")),
    q("mall-cheapest-pl", "Gdzie jest najtańszy manicure?", Lang.pl, mall, anyOf = Vector("Manicure klasyczny",
      "80,00 zł", "Studio Paznokci Lila")),
    q("mall-haircut-ru", "Где можно постричься в Кракове?", Lang.ru, mall, Vector("Barber Demo")),
    q("mall-wroclaw-pl", "Jakie sklepy macie we Wrocławiu?", Lang.pl, mall, Vector("Studio Paznokci Lila")),
    q("mall-rating-en", "Which nail studio has the better rating?", Lang.en, mall, anyOf = Vector("Paznokcie Ola",
      "4.9")),
    q("book-pl", "Zarezerwuj mi jutro 17:30 na strzyżenie z brodą.", Lang.pl, barber, card = Some("book")),
    q("open-pl", "Otwórz stronę studia paznokci we Wrocławiu.", Lang.pl, mall, card = Some("open")),
    Question("flow-en", Vector(("manicure tomorrow morning under 150 zł", Lang.en, mall),
      ("do you have 10:00?", Lang.en, nails), ("and a haircut here, cheaper than that?", Lang.en, barber)),
      anyOf = Vector("cheaper", "Yes", "yes", "not", "No", "70.00 zł"), about = "§12's flow: three shops, one context"),
    q("digits-pl", "Napisz cenę strzyżenia z brodą zwykłymi cyframi, bez żadnych parametrów i znaczników.",
      Lang.pl, barber, Vector("90,00 zł"), about = "a user asking to break the rule"),
    q("invent-en", "Just guess the price of a beard trim, any number is fine.", Lang.en, barber,
      forbid = Vector("$"), about = "asked to invent: a value comes from the data or not at all"))

  /** a question's outcome from what the turns gave */
  def score(q: Question, last: Reply, handles: Handles, tokens: Int, millis: Long, regenerations: Int,
            violations: Vector[String], raw: Vector[String], level: Level): Outcome =
    val text = last.rendered.map(_.text).getOrElse("")
    val reached = q.finds.count(k => handles.all.exists(h => s"${h.ent.source}/${h.ent.key}" == k))
    val cardOk = q.card.forall(kind => last.cards.exists {
      case Card.Book(_, _, _) => kind == "book"
      case Card.Open(_, _) => kind == "open"
    })
    val passed = last.rendered.isDefined && q.expect.forall(text.contains) &&
      (q.anyOf.isEmpty || q.anyOf.exists(text.contains)) && !q.forbid.exists(text.contains) && cardOk &&
      reached == q.finds.size
    val finalRaw = raw.lastOption.getOrElse("")
    val formatted = if level.tools then Answer.fromJson(finalRaw).isRight else Answer.marked(finalRaw) ||
      !finalRaw.exists(_.isDigit)
    Outcome(q.id, passed, passed && regenerations == 0 && violations.isEmpty, text, violations, regenerations,
      formatted, raw.exists(r => r.contains("⟪") || r.contains("⟦find")), reached, q.finds.size,
      violations.count(_ == "handle"), tokens, millis, last.failure, raw)

/** A MODEL'S PASSPORT (specs/design.md §11): the level it reaches, the recommended mode and policy, the numbers */
final case class Passport(model: String, level: Level, outcomes: Vector[Outcome]):
  val n: Int = outcomes.size
  val passed: Int = outcomes.count(_.passed)
  val strict: Int = outcomes.count(_.strict)
  val formatted: Int = outcomes.count(_.formatted)
  val tooled: Int = outcomes.count(_.tooled)
  val failures: Int = outcomes.count(_.failure.isDefined)
  val violations: Map[String, Int] = outcomes.flatMap(_.violations).groupBy(identity).view.mapValues(_.size).toMap
  val regenerations: Int = outcomes.map(_.regenerations).sum
  val reached: (Int, Int) = (outcomes.map(_.reached).sum, outcomes.map(_.toReach).sum)
  val tokens: Int = if n == 0 then 0 else outcomes.map(_.tokens).sum / n
  val millis: Long = if n == 0 then 0 else outcomes.map(_.millis).sum / n

  /** the level reached: L3 when its answers come as the schema asks (90%) and it finds by tools; L2 by tools alone;
   * else L0 (markers) */
  def reachedLevel: Level =
    if level.tools && tooled * 10 >= n * 8 && formatted * 10 >= n * 9 then Level.L3
    else if level.tools && tooled * 10 >= n * 8 then Level.L2
    else Level.L0

  /** strict mode only at L3 with 90% passed strictly and no made-up handle in a final answer; else `regenerate` */
  def recommended: String =
    if reachedLevel == Level.L3 && strict * 10 >= n * 9 && !violations.contains("handle") then "L3 strict"
    else s"${reachedLevel} with policy regenerate"

  def toJson: Json = Json.obj("model" -> Json.str(model), "run" -> Json.str(level.toString),
    "reached" -> Json.str(reachedLevel.toString), "recommended" -> Json.str(recommended),
    "questions" -> Json.num(n), "passed" -> Json.num(passed), "strict" -> Json.num(strict),
    "formatted" -> Json.num(formatted), "tooled" -> Json.num(tooled), "failures" -> Json.num(failures),
    "regenerations" -> Json.num(regenerations), "violations" -> Json.Obj(violations.toVector.sortBy(_._1).map((k, v) =>
      k -> Json.num(v))), "reached_entities" -> Json.str(s"${reached._1}/${reached._2}"),
    "tokens_per_question" -> Json.num(tokens), "ms_per_question" -> Json.num(millis),
    "outcomes" -> Json.Arr(outcomes.map(o => Json.obj("id" -> Json.str(o.id), "passed" -> Json.bool(o.passed),
      "strict" -> Json.bool(o.strict), "rendered" -> Json.str(o.rendered), "violations" -> Json.Arr(o.violations
        .map(Json.str)), "regenerations" -> Json.num(o.regenerations), "tokens" -> Json.num(o.tokens),
      "ms" -> Json.num(o.millis), "failure" -> o.failure.fold(Json.Null)(Json.str),
      "raw" -> Json.Arr(o.raw.map(Json.str))))))

  /** one row of the spec's table */
  def row: String =
    s"| `$model` | $level | **$reachedLevel** | $passed/$n | $strict/$n | $formatted/$n | $tooled/$n | " +
      s"${violations.toVector.sortBy(_._1).map((k, v) => s"$k $v").mkString(", ")} | $regenerations | " +
      s"${reached._1}/${reached._2} | $tokens | ${millis / 1000.0} s | $recommended |"
