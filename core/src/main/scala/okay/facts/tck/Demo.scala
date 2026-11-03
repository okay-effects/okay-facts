package okay.facts.tck

import okay.facts.*
import okay.scala3.*

/**
 * THE REFERENCE DATA (specs/design.md §11, §14): two FICTIONAL service shops and a third for the mall's queries — a
 * barber in Kraków, two nail studios — as the metamodel's rows, at a fixed now (2026-11-03 12:00 in Warsaw). What the
 * tests and the TCK bind against; never a real business's data
 */
object Demo:
  private def t(pl: String, uk: String, ru: String, en: String): Value =
    Value.Text(Map(Lang.pl -> pl, Lang.uk -> uk, Lang.ru -> ru, Lang.en -> en))
  private def one(s: String): Value = Value.text(s)

  val today = "2026-11-03"
  val tomorrow = "2026-11-04"
  val now: Long = Zone.instant(today, 12 * 60)

  /** schema.org's words for the shop's world, with the overlay's keys and cardinalities (specs/design.md §12) */
  val schema: Schema = Schema(Vector(
    Cls("Shop", "shop", Vector(
      Prop("name", Type.Text, visible = true), Prop("category", Type.Text, visible = true),
      Prop("city", Type.Text, visible = true), Prop("address", Type.Text), Prop("url", Type.Url),
      Prop("hours", Type.Text), Prop("rating", Type.Number), Prop("reviews", Type.Count(Noun.review))),
      key = Vector("id"), iri = "https://schema.org/LocalBusiness", about = "a shop of the mall"),
    Cls("Service", "v", Vector(
      Prop("name", Type.Text, visible = true), Prop("description", Type.Text, visible = true),
      Prop("price", Type.Money, about = "what it costs"), Prop("duration", Type.Duration, about = "how long it takes"),
      Prop("shop", Type.Entity("Shop"), about = "whose service it is"),
      Prop("masters", Type.Entity("Master"), functional = false, about = "who does it"),
      Prop("bookable", Type.Bool, about = "whether it is booked online")),
      key = Vector("shop", "id"), iri = "https://schema.org/Service", about = "a service offered, with its price"),
    Cls("Manicure", "v", Vector.empty, parents = Set("Service"), about = "a manicure service"),
    Cls("Master", "m", Vector(
      Prop("name", Type.Text), Prop("shop", Type.Entity("Shop")),
      Prop("services", Type.Entity("Service"), functional = false)),
      key = Vector("shop", "id"), iri = "https://schema.org/Person", about = "a person doing the services"),
    Cls("Slot", "s", Vector(
      Prop("name", Type.Text, visible = true), Prop("time", Type.Time, about = "when it starts"),
      Prop("date", Type.Date, about = "its day"), Prop("master", Type.Entity("Master")),
      Prop("service", Type.Entity("Service")), Prop("shop", Type.Entity("Shop")),
      Prop("available", Type.Bool, about = "whether it is still free")),
      key = Vector("shop", "master", "start"), about = "a free start of a service with a master"),
    Cls("Booking", "b", Vector(
      Prop("name", Type.Text), Prop("time", Type.Time), Prop("price", Type.Money), Prop("master", Type.Entity("Master")),
      Prop("service", Type.Entity("Service")), Prop("shop", Type.Entity("Shop")), Prop("ref", Type.Text)),
      about = "a booking made in the chat: its values pinned as booked"),
    Cls("Review", "r", Vector(
      Prop("rating", Type.Number), Prop("text", Type.Text), Prop("shop", Type.Entity("Shop"))),
      iri = "https://schema.org/Review", about = "a client's review")))

  def shop(id: String): Ent = Ent(id, "Shop", id)
  def service(shopId: String, key: String, cls: String = "Service"): Ent = Ent(shopId, cls, key)
  def master(shopId: String, key: String): Ent = Ent(shopId, "Master", key)

  private def slotRow(shopId: String, svc: Ent, svcName: Value, m: String, date: String, hm: String): Row =
    val start = Zone.instant(date, hm.take(2).toInt * 60 + hm.drop(3).toInt)
    Row(Ent(shopId, "Slot", s"$m@$start"), Map("name" -> svcName, "time" -> Value.At(start), "date" -> Value.Day(date),
      "master" -> Value.Entity(master(shopId, m)), "service" -> Value.Entity(svc),
      "shop" -> Value.Entity(shop(shopId)), "available" -> Value.Bool(true)), version = 40, updatedAt = now)

  val combo: Value = t("Strzyżenie + broda", "Стрижка + борода", "Стрижка + борода", "Haircut + beard")
  val cut: Value = t("Strzyżenie męskie", "Чоловіча стрижка", "Мужская стрижка", "Men's haircut")
  val hybrid: Value = t("Manicure hybrydowy", "Гібридний манікюр", "Гибридный маникюр", "Hybrid manicure")
  val classic: Value = t("Manicure klasyczny", "Класичний манікюр", "Классический маникюр", "Classic manicure")
  val olaManicure: Value = t("Manicure japoński", "Японський манікюр", "Японский маникюр", "Japanese manicure")

  def rows: Vector[Row] = Vector(
    Row(shop("barber-demo"), Map("name" -> one("Barber Demo"), "category" -> t("fryzjer męski", "барбер", "барбер",
      "barber"), "city" -> one("Kraków"), "address" -> one("ul. Przykładowa 1, 30-001 Kraków"),
      "url" -> Value.Url("https://barber-demo.example/"), "rating" -> Value.Num(4.8),
      "reviews" -> Value.Count(23, Noun.review)), 7, now),
    Row(shop("nails-demo"), Map("name" -> one("Studio Paznokci Lila"), "category" -> t("paznokcie", "нігті", "ногти",
      "nails"), "city" -> one("Wrocław"), "address" -> one("ul. Testowa 3, 50-001 Wrocław"),
      "url" -> Value.Url("https://nails-demo.example/"), "rating" -> Value.Num(4.6),
      "reviews" -> Value.Count(12, Noun.review)), 3, now),
    Row(shop("nails-ola"), Map("name" -> one("Paznokcie Ola"), "category" -> t("paznokcie", "нігті", "ногти", "nails"),
      "city" -> one("Kraków"), "address" -> one("ul. Wzorcowa 5, 30-002 Kraków"),
      "url" -> Value.Url("https://nails-ola.example/"), "rating" -> Value.Num(4.9),
      "reviews" -> Value.Count(2, Noun.review)), 2, now),
    Row(service("barber-demo", "combo"), Map("name" -> combo, "description" -> t("komplet", "комплекс", "комплекс",
      "the set"), "price" -> Value.zl(9000), "duration" -> Value.Minutes(45),
      "shop" -> Value.Entity(shop("barber-demo")), "bookable" -> Value.Bool(true),
      "masters" -> Value.Many(Vector(Value.Entity(master("barber-demo", "marek")),
        Value.Entity(master("barber-demo", "kuba"))))), 40, now),
    Row(service("barber-demo", "cut"), Map("name" -> cut, "description" -> t("nożyczki i maszynka", "ножиці і машинка",
      "ножницы и машинка", "scissors and clippers"), "price" -> Value.zl(7000), "duration" -> Value.Minutes(30),
      "shop" -> Value.Entity(shop("barber-demo")), "bookable" -> Value.Bool(true),
      "masters" -> Value.Many(Vector(Value.Entity(master("barber-demo", "marek")),
        Value.Entity(master("barber-demo", "ewa"))))), 40, now),
    Row(service("barber-demo", "voucher"), Map("name" -> t("Voucher prezentowy", "Подарунковий ваучер",
      "Подарочный ваучер", "Gift voucher"), "price" -> Value.zl(10000), "shop" -> Value.Entity(shop("barber-demo")),
      "bookable" -> Value.Bool(false)), 40, now),
    Row(service("nails-demo", "hybrid", "Manicure"), Map("name" -> hybrid, "description" -> t("z przygotowaniem",
      "з підготовкою", "с подготовкой", "with preparation"), "price" -> Value.zl(11000),
      "duration" -> Value.Minutes(60), "shop" -> Value.Entity(shop("nails-demo")), "bookable" -> Value.Bool(true),
      "masters" -> Value.Many(Vector(Value.Entity(master("nails-demo", "lila"))))), 3, now),
    Row(service("nails-demo", "classic", "Manicure"), Map("name" -> classic, "description" -> t("bez lakieru",
      "без лаку", "без лака", "no polish"), "price" -> Value.zl(8000), "duration" -> Value.Minutes(45),
      "shop" -> Value.Entity(shop("nails-demo")), "bookable" -> Value.Bool(true)), 3, now),
    Row(service("nails-demo", "pedicure"), Map("name" -> t("Pedicure", "Педикюр", "Педикюр", "Pedicure"),
      "description" -> t("klasyczny", "класичний", "классический", "classic"), "price" -> Value.zl(13000),
      "duration" -> Value.Minutes(75), "shop" -> Value.Entity(shop("nails-demo")), "bookable" -> Value.Bool(true)),
      3, now),
    Row(service("nails-ola", "japanese", "Manicure"), Map("name" -> olaManicure, "description" -> t("naturalny",
      "натуральний", "натуральный", "natural"), "price" -> Value.zl(14000), "duration" -> Value.Minutes(90),
      "shop" -> Value.Entity(shop("nails-ola")), "bookable" -> Value.Bool(true)), 2, now),
    Row(master("barber-demo", "marek"), Map("name" -> one("Marek"), "shop" -> Value.Entity(shop("barber-demo")),
      "services" -> Value.Many(Vector(Value.Entity(service("barber-demo", "combo")),
        Value.Entity(service("barber-demo", "cut"))))), 40, now),
    Row(master("barber-demo", "kuba"), Map("name" -> one("Kuba"), "shop" -> Value.Entity(shop("barber-demo")),
      "services" -> Value.Many(Vector(Value.Entity(service("barber-demo", "combo"))))), 40, now),
    Row(master("barber-demo", "ewa"), Map("name" -> one("Ewa"), "shop" -> Value.Entity(shop("barber-demo")),
      "services" -> Value.Many(Vector(Value.Entity(service("barber-demo", "cut"))))), 40, now),
    Row(master("nails-demo", "lila"), Map("name" -> one("Lila"), "shop" -> Value.Entity(shop("nails-demo"))), 3, now),
    slotRow("barber-demo", service("barber-demo", "combo"), combo, "marek", tomorrow, "17:30"),
    slotRow("barber-demo", service("barber-demo", "combo"), combo, "kuba", tomorrow, "18:15"),
    slotRow("barber-demo", service("barber-demo", "combo"), combo, "marek", tomorrow, "11:00"),
    slotRow("barber-demo", service("barber-demo", "cut"), cut, "ewa", tomorrow, "10:00"),
    slotRow("nails-demo", service("nails-demo", "hybrid", "Manicure"), hybrid, "lila", tomorrow, "10:00"),
    slotRow("nails-demo", service("nails-demo", "hybrid", "Manicure"), hybrid, "lila", tomorrow, "12:30"),
    slotRow("nails-demo", service("nails-demo", "classic", "Manicure"), classic, "lila", tomorrow, "09:00"),
    slotRow("nails-ola", service("nails-ola", "japanese", "Manicure"), olaManicure, "ola", tomorrow, "09:30"),
    Row(Ent("barber-demo", "Review", "r1"), Map("rating" -> Value.Num(5), "text" -> one("Szybko i dokładnie."),
      "shop" -> Value.Entity(shop("barber-demo"))), 1, now))

  /** the demo's facts in memory: queries by `Table`, scoped — `focus` the shop given, else every shop */
  def facts[R[_], Rest[_], A](focus: Option[String])(p: A ! R)(using Split[Facts, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Facts, Rest, A, A](pure)(
      [X] => (op: Facts[X]) => op match
        case Facts.Find(q) =>
          val in = q.scope match
            case Scope.Focus => focus
            case Scope.All => None
            case Scope.Source(id) => Some(id)
          Table.find(schema, rows.filter(r => in.forall(_ == r.ent.source)), q)
        case Facts.Get(ent, prop) => Table.get(rows, ent, prop)
        case Facts.Act(name, args, _) => name match
          case "open" => args.get("shop") match
            case Some(Value.Entity(e)) => Table.get(rows, e, "url").map(f => Acted(Some(e), Map("url" -> f),
              card = Some(Card.Open(Render(f.value, Lang.pl), e.source)))).toRight("no such shop")
            case _ => Left("open takes a shop")
          case "book" => args.get("slot") match
            case Some(Value.Entity(e)) if e.cls == "Slot" => Table.get(rows, e, "time").map(f => Acted(card = Some(
              Card.Book(e.key, "book", Vector(Render(f.value, Lang.pl)))))).toRight("no such slot")
            case _ => Left("book takes a slot")
          case other => Left(s"no action $other here")))
