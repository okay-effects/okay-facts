package okay.facts

/**
 * VALUES IN WORDS, by the resolver only (specs/design.md §7): money as the language writes it (`90,00 zł`), a
 * duration with its agreeing noun (`45 minut`, `1 godzina 15 minut`), a time in Warsaw (`17:30`), a day
 * (`4 listopada`), a count with its noun by the language's plural rules (`3 wolne miejsca`)
 */
object Render:
  /** ICU's (CLDR's) cardinal plural category of an integer: pl, uk, ru, en */
  def category(n: Long, l: Lang): String =
    val i = math.abs(n)
    val m10 = i % 10
    val m100 = i % 100
    l match
      case Lang.en => if i == 1 then "one" else "other"
      case Lang.pl =>
        if i == 1 then "one"
        else if m10 >= 2 && m10 <= 4 && !(m100 >= 12 && m100 <= 14) then "few"
        else "many"
      case Lang.uk | Lang.ru =>
        if m10 == 1 && m100 != 11 then "one"
        else if m10 >= 2 && m10 <= 4 && !(m100 >= 12 && m100 <= 14) then "few"
        else "many"

  /** a number's category: an integer's by the rules, a fraction `other` */
  def category(n: BigDecimal, l: Lang): String =
    if n.isWhole then category(n.toLongExact, l) else "other"

  def counted(n: Long, noun: Noun, l: Lang): String = s"$n ${noun(l).of(category(n, l))}"

  private def grouped(digits: String): String =
    if digits.length <= 4 then digits
    else digits.reverse.grouped(3).mkString(" ").reverse

  def money(minor: Long, currency: String, l: Lang): String =
    val sign = if minor < 0 then "-" else ""
    val a = math.abs(minor)
    val whole = grouped((a / 100).toString)
    val cents = f"${a % 100}%02d"
    val unit = if currency == "PLN" then "zł" else currency
    l match
      case Lang.en => s"$sign$whole.$cents $unit"
      case _ => s"$sign$whole,$cents $unit"

  def duration(minutes: Int, l: Lang): String =
    val h = minutes / 60
    val m = minutes % 60
    if h == 0 then counted(m, Noun.minute, l)
    else if m == 0 then counted(h, Noun.hour, l)
    else s"${counted(h, Noun.hour, l)} ${counted(m, Noun.minute, l)}"

  def time(ms: Long): String =
    val m = Zone.minuteOfDay(ms)
    f"${m / 60}%02d:${m % 60}%02d"

  def clock(minutes: Int): String = f"${minutes / 60}%02d:${minutes % 60}%02d"

  private val months: Map[Lang, Vector[String]] = Map(
    Lang.pl -> Vector("stycznia", "lutego", "marca", "kwietnia", "maja", "czerwca", "lipca", "sierpnia", "września",
      "października", "listopada", "grudnia"),
    Lang.uk -> Vector("січня", "лютого", "березня", "квітня", "травня", "червня", "липня", "серпня", "вересня",
      "жовтня", "листопада", "грудня"),
    Lang.ru -> Vector("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября",
      "октября", "ноября", "декабря"),
    Lang.en -> Vector("January", "February", "March", "April", "May", "June", "July", "August", "September",
      "October", "November", "December"))

  private val weekdays: Map[Lang, Vector[String]] = Map(
    Lang.pl -> Vector("poniedziałek", "wtorek", "środa", "czwartek", "piątek", "sobota", "niedziela"),
    Lang.uk -> Vector("понеділок", "вівторок", "середа", "четвер", "пʼятниця", "субота", "неділя"),
    Lang.ru -> Vector("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье"),
    Lang.en -> Vector("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"))

  def date(d: String, l: Lang): String =
    s"${d.slice(8, 10).toInt} ${months(l)(d.slice(5, 7).toInt - 1)}"

  /** `wtorek, 4 listopada` */
  def dayLong(d: String, l: Lang): String = s"${weekdays(l)(Zone.weekday(d) - 1)}, ${date(d, l)}"

  def number(n: BigDecimal, l: Lang): String =
    val s = n.bigDecimal.stripTrailingZeros.toPlainString
    if l == Lang.en then s else s.replace('.', ',')

  def bool(b: Boolean, l: Lang): String = (b, l) match
    case (true, Lang.pl) => "tak"
    case (false, Lang.pl) => "nie"
    case (true, Lang.uk) => "так"
    case (false, Lang.uk) => "ні"
    case (true, Lang.ru) => "да"
    case (false, Lang.ru) => "нет"
    case (true, Lang.en) => "yes"
    case (false, Lang.en) => "no"

  /** a value in words; `hint` a formatting hint (`short`, `long`, `date`, `datetime`); an entity's name is the
   * caller's (`names`), already read */
  def apply(v: Value, l: Lang, hint: String = "", names: Ent => String = _.key): String = v match
    case Value.Money(m, c) =>
      if hint == "short" && m % 100 == 0 then (if c == "PLN" then s"${grouped((m / 100).toString)} zł"
        else s"${m / 100} $c")
      else money(m, c, l)
    case Value.Minutes(n) => duration(n, l)
    case Value.At(ms) => hint match
      case "date" => date(Zone.date(ms), l)
      case "datetime" | "long" => s"${dayLong(Zone.date(ms), l)}, ${time(ms)}"
      case _ => time(ms)
    case Value.Day(d) => if hint == "long" then dayLong(d, l) else date(d, l)
    case Value.Clock(m) => clock(m)
    case Value.Num(n) => number(n, l)
    case Value.Count(n, noun) => counted(n, noun, l)
    case Value.Text(t) => Value.in(t, l)
    case Value.Url(u) => u
    case Value.Bool(b) => bool(b, l)
    case Value.Entity(e) => names(e)
    case Value.Many(vs) => vs.map(apply(_, l, hint, names)).mkString(", ")

  /** the integer an ICU plural selects by, when the value has one */
  def plural(v: Value): Option[BigDecimal] = v match
    case Value.Count(n, _) => Some(BigDecimal(n))
    case Value.Num(n) => Some(n)
    case Value.Minutes(n) => Some(BigDecimal(n))
    case Value.Money(m, _) => Some(BigDecimal(m) / 100)
    case _ => None
