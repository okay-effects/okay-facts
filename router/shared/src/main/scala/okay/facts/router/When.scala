package okay.facts.router

import okay.facts.{Words, Zone}

/** WHEN A MESSAGE ASKS FOR: a day (`2026-11-05`), a time window (minutes of the day: from, until), an exact time,
 * and the part of the day it named (`morning`, `afternoon`, `evening`) */
final case class When(day: Option[String] = None, from: Option[Int] = None, until: Option[Int] = None,
                      at: Option[Int] = None, part: Option[String] = None):
  def isEmpty: Boolean = day.isEmpty && from.isEmpty && until.isEmpty && at.isEmpty

/**
 * DAYS AND TIMES IN WORDS (specs/design.md §17), Polish, Ukrainian, Russian, English, relative to today in Warsaw:
 * today, tomorrow, the day after, a weekday (the next one, today included), `5.11`, `5 listopada`, `November 5`; a part
 * of the day; `o 17`, `na 10:30`, `о 10`, `в 10`, `at 5pm` (exact), `po 17`, `після 17`, `after 5` (from), `przed 12`,
 * `до 12`, `before 12` (until), a bare `17:30`. An hour of 1 to 7 without `am` is the afternoon's (`po 5` is 17:00).
 * A number with a currency after it is a price, never a time. The text is matched lower case, diacritics off
 */
object When:
  private def r(p: String) = Rules.compile(p)

  private val dayAfter = r("\\b(?:pojutrze|day after tomorrow|післязавтра|послезавтра|после завтра)\\b")
  private val tomorrow = r("\\b(?:jutro|jutra|tomorrow|tmrw|завтра|завтрашн\\w*)\\b")
  private val today = r("\\b(?:dzis|dzisiaj|dzisiejsz\\w*|today|tonight|сьогодні|сегодня|сегодняшн\\w*)\\b")
  private val weekdays: Vector[(Int, Re)] = Vector(
    1 -> "poniedzialek|poniedzialku|понеділок|понеділка|понедельник|понедельника|monday|mon",
    2 -> "wtorek|wtorku|вівторок|вівторка|вторник|вторника|tuesday|tue|tues",
    3 -> "sroda|srode|srody|середа|середу|середи|среда|среду|среды|wednesday|wed",
    4 -> "czwartek|czwartku|четвер|четверга|четверг|thursday|thu|thurs",
    5 -> "piatek|piatku|п'?ятниц\\w*|friday|fri",
    6 -> "sobota|sobote|soboty|субота|суботу|суботи|суббота|субботу|субботы|saturday|sat",
    7 -> "niedziela|niedziele|niedzieli|неділя|неділю|неділі|воскресенье|воскресенья|sunday|sun")
    .map((d, p) => d -> r(s"\\b(?:$p)\\b"))

  private val months: Vector[String] = Vector(
    "stycz\\w*|січ\\w*|январ\\w*|jan\\w*", "lut\\w*|лют\\w*|феврал\\w*|feb\\w*", "mar\\w*|берез\\w*|март\\w*",
    "kwie\\w*|квіт\\w*|апрел\\w*|apr\\w*", "maj\\w*|трав\\w*|ма[яи]|may", "czerw\\w*|черв\\w*|июн\\w*|jun\\w*",
    "lip\\w*|лип\\w*|июл\\w*|jul\\w*", "sierp\\w*|серп\\w*|август\\w*|aug\\w*",
    "wrze\\w*|верес\\w*|сентябр\\w*|sep\\w*",
    "pazdz\\w*|жовт\\w*|октябр\\w*|oct\\w*", "listopad\\w*|листопад\\w*|ноябр\\w*|nov\\w*",
    "grud\\w*|груд\\w*|декабр\\w*|dec\\w*")
  private val dayMonth = months.zipWithIndex.map((p, i) => (i + 1) -> r(s"\\b([0-3]?\\d)(?:st|nd|rd|th|go|ego)?\\s+" +
    s"(?:of\\s+)?(?:$p)\\b"))
  private val monthDay = months.zipWithIndex.map((p, i) => (i + 1) -> r(s"\\b(?:$p)\\s+([0-3]?\\d)(?:st|nd|rd|th)?\\b"))
  private val numeric = r("\\b([0-3]?\\d)[./]([01]?\\d)\\b(?![.:/]\\d)")

  private val morning = r("\\b(?:rano|rana|z rana|przed poludniem|przedpoludniem|morning|зранку|вранці|ранку|" +
    "утром|утра|с утра|до обеда|до обіду)\\b")
  private val afternoon = r("\\b(?:po poludniu|popoludniu|popoludnie|afternoon|після обіду|по обіді|вдень|удень|днем|" +
    "после обеда|в обед|w poludnie)\\b")
  private val evening = r("\\b(?:wieczorem|wieczor|wieczora|evening|tonight|ввечері|увечері|ввечері|вечора|вечір|" +
    "вечером|вечера|вечер)\\b")

  private val currency = "\\s*(?:zl|pln|zlotych|zloty|zlote|злот\\w*|зл|eur|euro|€|\\$|usd|грн|руб\\w*)"
  private val hhmm = "(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)?"
  private val atTime = r(s"\\b(?:o|na|at|about|around|okolo|ok|о|об|в|во|на|к|біля|около)\\s+(?:godz\\w*\\s+)?$hhmm" +
    s"(?!\\d|$currency|\\s*(?:min|minut|хв|мин|osob|раз))")
  private val fromTime = r(s"\\b(?:po|od|after|from|since|later than|після|з|від|после|с|от|позже)\\s+" +
    "(?:godz\\w*\\s+)?" +
    s"$hhmm(?!\\d|$currency|\\s*(?:min|minut|хв|мин))")
  private val untilTime = r(s"\\b(?:przed|do|before|until|till|by|до|перед|раньше|раніше)\\s+(?:godz\\w*\\s+)?$hhmm" +
    s"(?!\\d|$currency|\\s*(?:min|minut|хв|мин))")
  private val bare = r("\\b(\\d{1,2}):(\\d{2})\\b")

  /** an hour as said: 1 to 7 without `am` is the afternoon's; `pm` adds 12; past 23 none */
  private def minutes(h: String, m: String, ampm: String): Option[Int] =
    for
      hh <- h.toIntOption
      mm <- if m.isEmpty then Some(0) else m.toIntOption
      if mm < 60
      hour = if ampm.startsWith("p") && hh < 12 then hh + 12
        else if ampm.startsWith("a") then hh % 12
        else if hh >= 1 && hh <= 7 then hh + 12 else hh
      if hour <= 23
    yield hour * 60 + mm

  private def first(re: Re, t: String): Option[Int] =
    re.all(t).headOption.flatMap((_, _, g) => minutes(g(0), g(1), g(2)))

  def of(text: String, today0: String): When =
    val t = Words.norm(text).replace('’', '\'').replace('ʼ', '\'')
    val day =
      if dayAfter.test(t) then Some(Zone.plus(today0, 2))
      else if tomorrow.test(t) then Some(Zone.plus(today0, 1))
      else if today.test(t) then Some(today0)
      else dated(t, today0).orElse(weekdays.find(_._2.test(t)).map((d, _) =>
        Zone.plus(today0, Math.floorMod(d - Zone.weekday(today0), 7))))
    val part = if morning.test(t) then Some("morning") else if afternoon.test(t) then Some("afternoon")
      else if evening.test(t) then Some("evening") else None
    val at = first(atTime, t).orElse(bare.all(t).headOption.flatMap((_, _, g) => minutes(g(0), g(1), "")))
    val from = first(fromTime, t).orElse(part.collect { case "afternoon" => 12 * 60; case "evening" => 17 * 60 })
    val until = first(untilTime, t).orElse(part.collect { case "morning" => 12 * 60; case "afternoon" => 17 * 60 })
    When(day, if at.isDefined then None else from, if at.isDefined then None else until, at,
      part.orElse(if t.contains("tonight") then Some("evening") else None))

  /** a date said: `5.11`, `5 listopada`, `November 5` — this year, or the next when past */
  private def dated(t: String, today0: String): Option[String] =
    val dm = dayMonth.iterator.flatMap((m, re) => re.group(t).flatMap(_.toIntOption).map(d => (d, m))).nextOption()
      .orElse(monthDay.iterator.flatMap((m, re) => re.group(t).flatMap(_.toIntOption).map(d => (d, m))).nextOption())
      .orElse(numeric.all(t).headOption.flatMap((_, _, g) => for d <- g(0).toIntOption; m <- g(1).toIntOption
        yield (d, m)))
    dm.filter((d, m) => d >= 1 && d <= 31 && m >= 1 && m <= 12).map { (d, m) =>
      val y = today0.take(4).toInt
      val thisYear = f"$y%04d-$m%02d-$d%02d"
      if thisYear < today0 then f"${y + 1}%04d-$m%02d-$d%02d" else thisYear
    }
