package okay.facts.router

import okay.facts.{Words, Zone}

/** WHEN A MESSAGE ASKS FOR: a day (`2026-11-05`), a time window (minutes of the day: from, until), an exact time,
 * and the part of the day it named (`morning`, `afternoon`, `evening`) */
final case class When(day: Option[String] = None, from: Option[Int] = None, until: Option[Int] = None,
                      at: Option[Int] = None, part: Option[String] = None):
  def isEmpty: Boolean = day.isEmpty && from.isEmpty && until.isEmpty && at.isEmpty

/**
 * DAYS AND TIMES IN WORDS (specs/design.md §17), Polish, Ukrainian, Russian, English, relative to today in Warsaw:
 * today, tomorrow, the day after, a weekday (the next one, today included), the weekend (its Saturday), `5.11`,
 * `5 listopada`, `November 5`; a part of the day; `o 17`, `na 10:30`, `о 10`, `в 10`, `at 5pm` (exact), `po 17`,
 * `після 17`, `after 5` (from), `przed 12`, `до 12`, `before 12` (until), a bare `17:30`. An hour of 1 to 7
 * without `am` is the afternoon's (`po 5` is 17:00). A number with a currency after it is a price, never a time. The
 * text is matched lower case, diacritics off;
 * Ukrainian and Russian words typed in Latin letters too ("zavtra", "v pyatnicu posle 16")
 */
object When:
  private def r(p: String) = Rules.compile(p)
  private def words(ws: String*) = r(s"\\b(?:${ws.mkString("|")})\\b")

  // each with its spellings in Latin letters too ("zavtra", "v pyatnicu"): a message typed without Cyrillic
  private val dayAfter = words("pojutrze", "day after tomorrow", "післязавтра", "послезавтра", "после завтра",
    "poslezavtra", "pislyazavtra", "pisliazavtra")
  private val tomorrow = words("jutro", "jutra", "tomorrow", "tmrw", "tmr", "завтра", "завтрашн\\w*", "zavtra")
  private val today = words("dzis", "dzisiaj", "dzisiejsz\\w*", "today", "tonight", "сьогодні", "сегодня",
    "сегодняшн\\w*", "segodnya", "segodnia", "sevodnya", "sohodni", "sogodni")
  /** the weekend: its Saturday (or today, on a Saturday or Sunday) */
  private val weekend = words("weekend\\w*", "weeknd", "na weekendzie", "вихідн\\w*", "выходн\\w*", "vyhodn\\w*",
    "vykhodn\\w*", "vihidn\\w*", "vykhidn\\w*")
  private val weekdays: Vector[(Int, Re)] = Vector(
    1 -> "poniedzialek|poniedzialku|понеділок|понеділка|понедельник|понедельника|monday|mon|ponedelnik\\w*|ponedilok",
    2 -> "wtorek|wtorku|вівторок|вівторка|вторник|вторника|tuesday|tue|tues|vtornik\\w*|vivtorok",
    3 -> "sroda|srode|srody|середа|середу|середи|среда|среду|среды|wednesday|wed|sred[auy]|sered[auy]",
    4 -> "czwartek|czwartku|четвер|четверга|четверг|thursday|thu|thurs|chetverg\\w*|chetver",
    5 -> "piatek|piatku|п'?ятниц\\w*|friday|fri|p'?[yj]?atnic\\w*|p'?[yj]?atnyc\\w*",
    6 -> "sobota|sobote|soboty|субота|суботу|суботи|суббота|субботу|субботы|saturday|sat|subbot\\w*|subot\\w*",
    7 -> ("niedziela|niedziele|niedzieli|неділя|неділю|неділі|воскресенье|воскресенья|sunday|sun|voskresen\\w*|" +
      "nedil\\w*"))
    .map((d, p) => d -> r(s"\\b(?:$p)\\b"))

  // a month as its own word (lower case, diacritics off — `й` is `и`), never a stem: "marek's", "u Marka", "majster"
  // are no month. The Slavic forms (and their Latin spellings) after a day's number; the English ones either side
  private val slavic: Vector[String] = Vector(
    "stycznia|styczen|styczniu|січня|січень|січні|января|январь|январе|yanvarya|sichnya",
    "lutego|luty|lutym|лютого|лютии|лютому|февраля|февраль|феврале|fevralya|lyutogo",
    "marca|marzec|marcu|березня|березень|березні|марта|март|марте|marta|bereznya",
    "kwietnia|kwiecien|kwietniu|квітня|квітень|квітні|апреля|апрель|апреле|aprelya|kvitnya",
    "maja|maj|maju|травня|травень|травні|мая|маи|мае|maya|travnya",
    "czerwca|czerwiec|czerwcu|червня|червень|червні|июня|июнь|июне|iyunya|chervnya",
    "lipca|lipiec|lipcu|липня|липень|липні|июля|июль|июле|iyulya|lypnya",
    "sierpnia|sierpien|sierpniu|серпня|серпень|серпні|августа|август|августе|avgusta|serpnya",
    "wrzesnia|wrzesien|wrzesniu|вересня|вересень|вересні|сентября|сентябрь|сентябре|sentyabrya|veresnya",
    "pazdziernika|pazdziernik|pazdzierniku|жовтня|жовтень|жовтні|октября|октябрь|октябре|oktyabrya|zhovtnya",
    "listopada|listopad|listopadzie|листопада|листопад|листопаді|ноября|ноябрь|ноябре|noyabrya|lystopada",
    "grudnia|grudzien|grudniu|грудня|грудень|грудні|декабря|декабрь|декабре|dekabrya|grudnya")
  private val english: Vector[String] = Vector("january|jan", "february|feb", "march|mar", "april|apr", "may",
    "june|jun", "july|jul", "august|aug", "september|sept|sep", "october|oct", "november|nov", "december|dec")
  private val dayMonth = slavic.zip(english).zipWithIndex.map { case ((s, e), i) => (i + 1) ->
    r(s"\\b([0-3]?\\d)(?:st|nd|rd|th|go|ego|-?го|-?ого)?\\s+(?:of\\s+)?(?:$s|$e)\\b") }
  private val monthDay = english.zipWithIndex.map((p, i) => (i + 1) ->
    r(s"\\b(?:$p)\\.?\\s+([0-3]?\\d)(?:st|nd|rd|th)?\\b(?![:.]\\d)"))
  private val numeric = r("\\b([0-3]?\\d)[./]([01]?\\d)\\b(?![.:/]\\d)")

  private val morning = r("\\b(?:rano|rana|z rana|przed poludniem|przedpoludniem|morning|зранку|вранці|ранку|" +
    "утром|утра|с утра|до обеда|до обіду|utrom|utra|s utra|zranku|vranci|vrantsi|do obeda|do obidu)\\b")
  private val afternoon = r("\\b(?:po poludniu|popoludniu|popoludnie|afternoon|після обіду|по обіді|вдень|удень|днем|" +
    "после обеда|в обед|w poludnie|posle obeda|pislya obidu|pislia obidu|dnem|vden)\\b")
  private val evening = r("\\b(?:wieczorem|wieczor|wieczora|evening|tonight|ввечері|увечері|ввечері|вечора|вечір|" +
    "вечером|вечера|вечер|vecherom|vechera|vecher|uvecheri|vvecheri|vechorom)\\b")

  private val currency = "\\s*(?:zl|pln|zlotych|zloty|zlote|злот\\w*|зл|eur|euro|€|\\$|usd|грн|руб\\w*)"
  private val hhmm = "(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)?"
  private val atTime = r(s"\\b(?:o|na|at|about|around|okolo|ok|о|об|в|во|на|к|біля|около|v|vo|ob|kolo)" +
    s"\\s+(?:godz\\w*\\s+)?$hhmm" +
    s"(?!\\d|$currency|\\s*(?:min|minut|хв|мин|osob|раз))")
  private val fromTime = r(s"\\b(?:po|od|after|from|since|later than|після|з|від|после|с|от|позже|" +
    "posle|pislya|pislia|pisla|vid|ot|nie wczesniej niz|not before)\\s+" +
    "(?:godz\\w*\\s+)?" +
    s"$hhmm(?!\\d|$currency|\\s*(?:min|minut|хв|мин))")
  private val untilTime = r(s"\\b(?:przed|do|before|until|till|by|до|перед|раньше|раніше|pered|ranshe|ranishe|" +
    s"nie pozniej niz|no later than)\\s+(?:godz\\w*\\s+)?$hhmm" +
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
        Zone.plus(today0, Math.floorMod(d - Zone.weekday(today0), 7)))).orElse(Option.when(weekend.test(t))(
        if Zone.weekday(today0) == 7 then today0 else Zone.plus(today0, Math.floorMod(6 - Zone.weekday(today0), 7))))
    val part = if morning.test(t) then Some("morning") else if afternoon.test(t) then Some("afternoon")
      else if evening.test(t) then Some("evening") else None
    val saidFrom = first(fromTime, t)
    val saidUntil = first(untilTime, t)
    // a bare "12:00" is the time asked only when no "after" or "until" took it ("до 12:00" is until noon)
    val at = first(atTime, t).orElse(if saidFrom.isDefined || saidUntil.isDefined then None
      else bare.all(t).headOption.flatMap((_, _, g) => minutes(g(0), g(1), "")))
    val from = saidFrom.orElse(part.collect { case "afternoon" => 12 * 60; case "evening" => 17 * 60 })
    val until = saidUntil.orElse(part.collect { case "morning" => 12 * 60; case "afternoon" => 17 * 60 })
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
