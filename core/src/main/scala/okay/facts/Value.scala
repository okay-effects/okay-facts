package okay.facts

/** the user's language: the four the first users speak (specs/design.md §7) */
enum Lang:
  case pl, uk, ru, en

object Lang:
  def parse(s: String): Option[Lang] = Lang.values.find(_.toString == s.trim.toLowerCase.take(2))

/** a word's forms by ICU's plural categories (one, few, many, other) in one language */
final case class Forms(one: String, few: String, many: String, other: String):
  def of(category: String): String = category match
    case "one" => one
    case "few" => few
    case "many" => many
    case _ => other

/** A COUNTABLE'S NOUN in the four languages: a count renders with it, agreeing (`45 minut`, `3 wolne miejsca`) */
final case class Noun(pl: Forms, uk: Forms, ru: Forms, en: Forms):
  def apply(l: Lang): Forms = l match
    case Lang.pl => pl
    case Lang.uk => uk
    case Lang.ru => ru
    case Lang.en => en

object Noun:
  val minute: Noun = Noun(Forms("minuta", "minuty", "minut", "minuty"), Forms("хвилина", "хвилини", "хвилин", "хвилини"),
    Forms("минута", "минуты", "минут", "минуты"), Forms("minute", "minutes", "minutes", "minutes"))
  val hour: Noun = Noun(Forms("godzina", "godziny", "godzin", "godziny"), Forms("година", "години", "годин", "години"),
    Forms("час", "часа", "часов", "часа"), Forms("hour", "hours", "hours", "hours"))
  val slot: Noun = Noun(Forms("wolny termin", "wolne terminy", "wolnych terminów", "wolnego terminu"),
    Forms("вільне вікно", "вільні вікна", "вільних вікон", "вільного вікна"),
    Forms("свободное окно", "свободных окна", "свободных окон", "свободного окна"),
    Forms("free slot", "free slots", "free slots", "free slots"))
  val seat: Noun = Noun(Forms("wolne miejsce", "wolne miejsca", "wolnych miejsc", "wolnego miejsca"),
    Forms("вільне місце", "вільні місця", "вільних місць", "вільного місця"),
    Forms("свободное место", "свободных места", "свободных мест", "свободного места"),
    Forms("free seat", "free seats", "free seats", "free seats"))
  val review: Noun = Noun(Forms("opinia", "opinie", "opinii", "opinii"), Forms("відгук", "відгуки", "відгуків",
    "відгуку"), Forms("отзыв", "отзыва", "отзывов", "отзыва"), Forms("review", "reviews", "reviews", "reviews"))

/** A VALUE'S TYPE (the metamodel's value types, specs/design.md §4) */
enum Type:
  case Money, Duration, Time, Date, Number, Text, Url, Bool
  case Count(noun: Noun)
  case Entity(cls: String)
  /** a set: what a non-functional path yields — only an aggregate takes it */
  case Many(of: Type)

  def show: String = this match
    case Count(_) => "Count"
    case Entity(c) => c
    case Many(t) => s"Set[${t.show}]"
    case t => t.toString

/** AN ENTITY of a source: which source (a shop's id), its class, its key there — never shown to the model */
final case class Ent(source: String, cls: String, key: String)

/** A VALUE, as the resolver has it: typed, exact; rendered only by `Render` */
enum Value:
  /** money in minor units (grosze) */
  case Money(minor: Long, currency: String)
  case Minutes(n: Int)
  /** an instant (ms, UTC): a time, rendered in the zone */
  case At(ms: Long)
  /** a day, `2026-11-04` */
  case Day(date: String)
  /** a time of a day in minutes (`17:00` = 1020): a literal of a comparison */
  case Clock(minutes: Int)
  case Num(n: BigDecimal)
  case Count(n: Long, noun: Noun)
  /** a text in the four languages (a plain one the same in each) */
  case Text(byLang: Map[Lang, String])
  case Url(url: String)
  case Bool(b: Boolean)
  case Entity(ent: Ent)
  case Many(values: Vector[Value])

object Value:
  def text(s: String): Value = Text(Lang.values.map(_ -> s).toMap)
  def zl(minor: Long): Value = Money(minor, "PLN")

  /** a text in `l`, else Polish, else English, else any */
  def in(t: Map[Lang, String], l: Lang): String =
    t.get(l).filter(_.nonEmpty).orElse(t.get(Lang.pl).filter(_.nonEmpty)).orElse(t.get(Lang.en))
      .orElse(t.values.headOption).getOrElse("")

  /** a value's type, as far as a value tells */
  def typeOf(v: Value): Type = v match
    case Money(_, _) => Type.Money
    case Minutes(_) => Type.Duration
    case At(_) | Clock(_) => Type.Time
    case Day(_) => Type.Date
    case Num(_) => Type.Number
    case Count(_, n) => Type.Count(n)
    case Text(_) => Type.Text
    case Url(_) => Type.Url
    case Bool(_) => Type.Bool
    case Entity(e) => Type.Entity(e.cls)
    case Many(vs) => Type.Many(vs.headOption.map(typeOf).getOrElse(Type.Text))

  /** ORDER between two values of comparable kinds — money of one currency, durations, numbers and counts, times
   * (an instant against an instant, a day — its local date —, or a time of day), days; none: not comparable */
  def compare(a: Value, b: Value): Option[Int] = (a, b) match
    case (Money(x, c), Money(y, d)) if c == d => Some(x.compareTo(y))
    case (Minutes(x), Minutes(y)) => Some(x.compareTo(y))
    case (Num(x), Num(y)) => Some(x.compare(y))
    case (Count(x, _), Count(y, _)) => Some(x.compareTo(y))
    case (Count(x, _), Num(y)) => Some(BigDecimal(x).compare(y))
    case (Num(x), Count(y, _)) => Some(x.compare(BigDecimal(y)))
    case (At(x), At(y)) => Some(x.compareTo(y))
    case (At(x), Day(d)) => Some(Zone.date(x).compareTo(d))
    case (Day(d), At(x)) => Some(d.compareTo(Zone.date(x)))
    case (At(x), Clock(m)) => Some(Zone.minuteOfDay(x).compareTo(m))
    case (Clock(m), At(x)) => Some(m.compareTo(Zone.minuteOfDay(x)))
    case (Clock(x), Clock(y)) => Some(x.compareTo(y))
    case (Day(x), Day(y)) => Some(x.compareTo(y))
    case _ => None

  /** equality as a filter means it: an entity by its key, a text by any of its languages (case and diacritics aside) */
  def same(a: Value, b: Value): Boolean = (a, b) match
    case (Entity(x), Entity(y)) => x == y
    case (Text(x), Text(y)) => x.values.exists(s => y.values.exists(t => Words.norm(s) == Words.norm(t)))
    case (Bool(x), Bool(y)) => x == y
    case (Url(x), Url(y)) => x == y
    case _ => compare(a, b).contains(0)

/** WARSAW'S TIME (the mall's shops are in Poland): CET, CEST from the last Sunday of March 01:00 UTC to the last Sunday
 * of October 01:00 UTC — pure, for Scala.js too (no java.time there) */
object Zone:
  private val day = 86400000L

  /** days since 1970-01-01 of a civil date (Howard Hinnant's algorithm) */
  def days(y: Int, m: Int, d: Int): Long =
    val yy = if m <= 2 then y - 1 else y
    val era = Math.floorDiv(yy, 400)
    val yoe = yy - era * 400
    val doy = (153 * (m + (if m > 2 then -3 else 9)) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    era * 146097L + doe - 719468

  /** the civil date of a day number */
  def civil(z0: Long): (Int, Int, Int) =
    val z = z0 + 719468
    val era = Math.floorDiv(z, 146097L)
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = (doy - (153 * mp + 2) / 5 + 1).toInt
    val m = (if mp < 10 then mp + 3 else mp - 9).toInt
    ((yoe + era * 400 + (if m <= 2 then 1 else 0)).toInt, m, d)

  private def lastSunday(y: Int, m: Int): Long =
    val last = days(y, m + 1, 1) - 1
    last - Math.floorMod(last + 4, 7L)    // 1970-01-01 was a Thursday: (days + 4) mod 7 is 0 on a Sunday

  /** the offset of Warsaw from UTC at an instant, in ms */
  def offset(ms: Long): Long =
    val (y, _, _) = civil(Math.floorDiv(ms, day))
    val start = lastSunday(y, 3) * day + 3600000L
    val end = lastSunday(y, 10) * day + 3600000L
    if ms >= start && ms < end then 7200000L else 3600000L

  def local(ms: Long): Long = ms + offset(ms)
  def date(ms: Long): String =
    val (y, m, d) = civil(Math.floorDiv(local(ms), day))
    f"$y%04d-$m%02d-$d%02d"
  def minuteOfDay(ms: Long): Int = (Math.floorMod(local(ms), day) / 60000L).toInt
  /** a day (`2026-11-04`) shifted by `n` days */
  def plus(date: String, n: Int): String =
    val (y, m, d) = civil(days(date.take(4).toInt, date.slice(5, 7).toInt, date.slice(8, 10).toInt) + n)
    f"$y%04d-$m%02d-$d%02d"
  /** 1 Monday … 7 Sunday */
  def weekday(date: String): Int =
    (Math.floorMod(days(date.take(4).toInt, date.slice(5, 7).toInt, date.slice(8, 10).toInt) + 3, 7L) + 1).toInt
  /** the instant of a local time of Warsaw on a day */
  def instant(date: String, minutes: Int): Long =
    val localMs = days(date.take(4).toInt, date.slice(5, 7).toInt, date.slice(8, 10).toInt) * day + minutes * 60000L
    localMs - offset(localMs - offset(localMs))

/** WORDS COMPARED as people mean them: lower case, diacritics off (ą→a, ł→l, ё→е, ї→і …), split on what is not a
 * letter or a digit */
object Words:
  private val plain: Map[Char, Char] = Map('ą' -> 'a', 'ć' -> 'c', 'ę' -> 'e', 'ł' -> 'l', 'ń' -> 'n', 'ó' -> 'o',
    'ś' -> 's', 'ź' -> 'z', 'ż' -> 'z', 'ё' -> 'е', 'й' -> 'и', 'ї' -> 'і', 'є' -> 'е', 'ґ' -> 'г', 'é' -> 'e',
    'á' -> 'a', 'ü' -> 'u', 'ö' -> 'o', 'ä' -> 'a')
  def norm(s: String): String = s.toLowerCase.map(c => plain.getOrElse(c, c))
  def split(s: String): Vector[String] =
    val out = Vector.newBuilder[String]
    val w = new StringBuilder
    for c <- norm(s) do
      if c.isLetterOrDigit then w += c
      else if w.nonEmpty then { out += w.toString; w.clear() }
    if w.nonEmpty then out += w.toString
    out.result()
