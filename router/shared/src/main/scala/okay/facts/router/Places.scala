package okay.facts.router

import okay.effects.json.Json
import okay.facts.{Lang, Words}

/** A PLACE of a gazetteer (specs/design.md §17 "Round 4"): its id, its name by language, a city or a district (of
 * `city`, a city's id), the forms it is written in (folded: `Spell.fold` of every word), whether a lone lower-case
 * word of it is a common word ("rury", "centrum": named only when capitalised, or with more words) */
final case class Place(id: String, name: Map[Lang, String], kind: String, city: String, forms: Vector[String],
                       common: Boolean = false):
  def in(l: Lang): String = name.getOrElse(l, name.getOrElse(Lang.pl, id))

/** a place found in a text: the places its words name (several: one name of many places — a district of each city),
 * the words' span (from, until) */
final case class PlaceHit(places: Vector[Place], from: Int, until: Int, said: String)

/**
 * PLACES IN ANY SCRIPT AND CASE (specs/design.md §17 "Round 4"): a gazetteer — the host's data, its forms generated
 * by `PlaceForms` — matched in a text: its words folded to plain Latin (case, diacritics, Cyrillic transliterated), the
 * longest run of words that is a form first, else a word of 7 letters or more one edit away from a one-word form
 * (shorter ones are too near other words: "sobote" is one edit from "sopote"). A match needs a reason to be
 * a place: a capital letter as written, a preposition before it ("w", "na", "do", "в", "у", "до", "in", "v"…), or
 * more than one word; a common word's place needs the capital
 */
final class Places(val all: Vector[Place]):
  private val byForm: Map[String, Vector[Place]] = all.flatMap(p => p.forms.distinct.map(_ -> p)).groupMap(_._1)(_._2)
  private val longest: Int = math.max(1, byForm.keys.map(_.count(_ == ' ') + 1).maxOption.getOrElse(1))
  private val single: Vector[(String, Place)] = all.flatMap(p => p.forms.filter(f => !f.contains(' ') &&
    f.length >= 7).distinct.map(_ -> p))

  def get(id: String): Option[Place] = all.find(_.id == id)

  /** the places a text names, left to right */
  def find(text: String): Vector[PlaceHit] =
    val raw = Places.tokens(text)
    val ws = raw.map(t => Spell.fold(t))
    val out = Vector.newBuilder[PlaceHit]
    var i = 0
    while i < ws.size do
      val hit = (math.min(longest, ws.size - i) to 1 by -1).iterator.flatMap { n =>
        val phrase = ws.slice(i, i + n).mkString(" ")
        val exact = byForm.getOrElse(phrase, Vector.empty)
        val loose = if n > 1 || exact.nonEmpty then Vector.empty else Places.loose(phrase, single)
        val ps = if exact.nonEmpty then exact else loose
        val why = n > 1 || raw(i).headOption.exists(_.isUpper) || (i > 0 && Places.prepositions(ws(i - 1)))
        // a common word's place: only as a name is written — capitalised (or of several words)
        val ok = ps.filter(p => why && (!p.common || n > 1 || raw(i).headOption.exists(_.isUpper)))
        Option.when(ok.nonEmpty)(PlaceHit(ok.distinct, i, i + n, raw.slice(i, i + n).mkString(" ")))
      }.nextOption()
      hit match
        case Some(h) => out += h; i = h.until
        case None => i += 1
    out.result()

  /** the text with each place named written as its Polish name (a city's one; a district's, its city's after it when
   * the text names no city) — for a search by words, a geocoder */
  def canonical(text: String): String =
    val hits = find(text)
    if hits.isEmpty then text
    else
      val raw = Places.tokens(text)
      val named = hits.flatMap(_.places).filter(_.kind == "city").map(_.id).toSet
      val out = Vector.newBuilder[String]
      var i = 0
      for h <- hits do
        out ++= raw.slice(i, h.from)
        val p = h.places.find(_.kind == "city").orElse(h.places.find(d => named(d.city))).getOrElse(h.places.head)
        out += (if p.kind == "district" && !named(p.city) && h.places.size == 1 then
          p.in(Lang.pl) + get(p.city).fold("")(c => " " + c.in(Lang.pl)) else p.in(Lang.pl))
        i = h.until
      out ++= raw.drop(i)
      out.result().mkString(" ")

object Places:
  val empty: Places = Places(Vector.empty)

  /** the words before a place's name that say it is one */
  val prepositions: Set[String] = Set("v", "ve", "na", "do", "z", "ze", "ot", "od", "kolo", "okolo", "pod", "nad",
    "przi", "u", "iz", "in", "to", "from", "near", "around", "dla", "po", "pri", "vozle", "bilia", "bila").map(
    Spell.fold)

  /** a lone word near a one-word form: one edit away, 7 letters or more */
  def loose(w: String, single: Vector[(String, Place)]): Vector[Place] =
    if w.length >= 7 then single.collect { case (f, p) if oneEdit(w, f) => p } else Vector.empty

  /** one edit apart: a letter changed, added or dropped */
  def oneEdit(a: String, b: String): Boolean =
    if a == b || math.abs(a.length - b.length) > 1 then false
    else if a.length == b.length then a.zip(b).count(_ != _) == 1
    else
      val (s, l) = if a.length < b.length then (a, b) else (b, a)
      (0 to s.length).exists(i => s.substring(0, i) + l.charAt(i) + s.substring(i) == l)

  /** a text's words as written: runs of letters and digits (an apostrophe inside a word kept; a hyphen splits) — by
   * the characters themselves, no regular expression (Scala.js) */
  def tokens(text: String): Vector[String] =
    val out = Vector.newBuilder[String]
    val w = new StringBuilder
    for c <- text do
      if c.isLetterOrDigit || ((c == '\'' || c == '’' || c == 'ʼ') && w.nonEmpty) then w += c
      else if w.nonEmpty then { out += w.toString; w.clear() }
    if w.nonEmpty then out += w.toString
    out.result()

  /** `{about?, source?, places: [{id, name: {pl, uk, ru, en}, kind, city?, forms: [...], common?}]}` */
  def parse(json: String): Places =
    val j = Json.fromText(json)
    Places(j("places").list.map { p =>
      Place(p("id").string, p("name").record.flatMap((k, v) => Lang.parse(k).map(_ -> v.string)).toMap,
        p("kind").string, p.field("city").collect { case Json.Str(s) => s }.getOrElse(""),
        p("forms").list.map(_.string), p.field("common").contains(Json.Bool(true)))
    })
