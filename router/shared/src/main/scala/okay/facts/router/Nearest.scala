package okay.facts.router

import okay.effects.json.Json
import okay.facts.Words

/**
 * WORDS OF ONE THING (specs/design.md §17): a host's groups of stems that name the same — "szafa", "meble",
 * "wardrobe", "шкаф"; "syn", "dziecko", "kids", "дитина" — so a text's word stands for a name's word it shares a group
 * with. Stems match a word's start, both folded to plain Latin (`Spell.fold`: a word typed in Latin letters for a
 * Cyrillic one matches too); a stem of 3 letters matches the whole word only ("syn", never "synonim"), one of fewer
 * is ignored
 */
final case class Lexicon(groups: Vector[Vector[String]], ignore: Vector[String] = Vector.empty):
  private val stems: Vector[(String, Int)] = groups.zipWithIndex.flatMap((g, i) => g.map(s => Spell.fold(s) -> i))
    .filter(_._1.length >= 3)

  /** the groups a word is in: by its start, or by its start past a verb's prefix ("po|czyścić", "wy|prać",
   * "по|чистить", "ви|прати") */
  def of(word: String): Set[Int] =
    val w = Spell.fold(word)
    val bare = Lexicon.prefixes.collect { case p if w.startsWith(p) && w.length - p.length >= 4 => w.drop(p.length) }
    def has(x: String, s: String) = if s.length >= 4 then x.startsWith(s) else x == s
    stems.collect { case (s, i) if has(w, s) || bare.exists(has(_, s)) => i }.toSet

  /** whether two words share a group */
  def same(a: String, b: String): Boolean = stems.nonEmpty && (of(a) & of(b)).nonEmpty

  private val idle = ignore.map(Spell.fold).filter(_.length >= 3)

  /** a word of a name that says nothing of which thing it is ("drobna", "small", "and"): the word itself, or its
   * start when the entry has 4 letters or more */
  def ignored(word: String): Boolean = idle.nonEmpty && {
    val w = Spell.fold(word)
    idle.exists(i => w == i || (i.length >= 4 && w.startsWith(i)))
  }

object Lexicon:
  val empty: Lexicon = Lexicon(Vector.empty)

  /** verbs' prefixes of Polish, Ukrainian and Russian, folded, the longest first */
  val prefixes: Vector[String] = Vector("prze", "przy", "pere", "pod", "під", "при", "po", "по", "wy", "ви", "вы", "za",
    "за", "na", "на", "roz", "роз", "раз", "рас", "s", "с", "z", "з", "u", "у", "o", "о", "ob", "об", "od", "от", "від")
    .map(Spell.fold).distinct.sortBy(-_.length)

  /** `{groups: [["szaf", "mebl", "furniture", "шкаф"], …], ignore: ["drobn", "small", …]}` */
  def parse(json: String): Lexicon =
    val j = Json.fromText(json)
    Lexicon(j("groups").list.map(_.list.map(_.string)), j.field("ignore").toVector.flatMap(_.list.map(_.string)))

/**
 * THE CANDIDATES NEAREST A TEXT (specs/design.md §17): a service among a shop's, a city among the mall's — each
 * candidate by its names (any of its languages). A candidate's score is its meaning's nearness — the best cosine of
 * a unit of the text (a word, a pair, a triple) to one of its names — beside its words' presence (whole, by prefix,
 * by stem, one edit away — `near`; in plain Latin, for a word typed in another script's letters; or a
 * word of the same thing by the host's `Lexicon`), each word counted by its rarity among the candidates (a word every
 * name has tells little); `pick` says one, several (too close to tell: the host asks), or none (nothing near enough)
 */
object Nearest:
  final case class Candidate(key: String, names: Vector[String])

  enum Pick:
    case One(key: String, score: Double)
    case Several(keys: Vector[String])
    case Nothing

  /** the words that mean something: 3 letters or more */
  private def meant(s: String): Vector[String] = Words.split(s).filter(_.length >= 3)

  /** two forms of one word: the same, one the other's start (4 letters or more; of 3, its plural: "kid", "kids" —
   * never "men", "mena"), a stem in common (4 letters, or all but the last two: "broda", "brodę"; never "czas",
   * "czyszczenie"), or one edit apart (5 letters or more) */
  def near(a: String, b: String): Boolean =
    a == b || (a.length >= 4 && b.startsWith(a)) || (b.length >= 4 && a.startsWith(b)) || b == a + "s" ||
      a == b + "s" || {
      val common = a.lazyZip(b).takeWhile(_ == _).size
      a.length >= 4 && b.length >= 4 && common >= math.max(4, math.min(a.length, b.length) - 2)
    } || (a.length >= 5 && b.length >= 5 && oneEdit(a, b))

  private def oneEdit(a: String, b: String): Boolean =
    if math.abs(a.length - b.length) > 1 then false
    else
      val i = a.lazyZip(b).takeWhile(_ == _).size
      if a.length == b.length then a.drop(i + 1) == b.drop(i + 1)
      else if a.length > b.length then a.drop(i + 1) == b.drop(i) else a.drop(i) == b.drop(i + 1)

  /** a word of the text as a word of a name: by its letters, in plain Latin, or by the lexicon */
  def same(have: String, want: String, lexicon: Lexicon = Lexicon.empty): Boolean =
    near(have, want) || {
      val (h, w) = (Spell.fold(have), Spell.fold(want))
      (h != have || w != want) && near(h, w)
    } || lexicon.same(have, want)

  /** the share of a name's words the text has (each by `weight`, 1 by default) */
  def lexical(text: String, name: String, lexicon: Lexicon = Lexicon.empty, weight: String => Double = _ => 1.0)
    : Double =
    val have = meant(text)
    val want = meant(name).filterNot(lexicon.ignored)
    if want.isEmpty || have.isEmpty then 0.0
    else want.filter(w => have.exists(same(_, w, lexicon))).map(weight).sum / want.map(weight).sum

  /** how many of the text's words a name accounts for */
  def explained(text: String, name: String, lexicon: Lexicon = Lexicon.empty): Int =
    val want = meant(name).filterNot(lexicon.ignored)
    meant(text).count(h => want.exists(same(h, _, lexicon)))

  /** the best cosine of a unit of the text to the name */
  def semantic(table: Table, units: Vector[Array[Float]], name: String): Double =
    table.encode(name).fold(0.0)(n => units.map(Table.dot(_, n)).maxOption.getOrElse(0.0))

  /** every candidate's score, the best first, by its best name: every word of the name in the text — 0.7, a tenth of
   * its meaning's nearness and 0.12 for each word of the text it accounts for (three at most: "haircut and beard" is
   * the haircut with a beard more than the beard); else 0.85 of its meaning's nearness, or its words' share (by
   * rarity) and its meaning together when that is more (the table's single words are noisy across languages: words
   * first, meaning beside them) */
  def rank(table: Table, text: String, cs: Vector[Candidate], lexicon: Lexicon = Lexicon.empty)
    : Vector[(String, Double)] =
    val units = Table.units3(text).flatMap(table.vector)
    // a name's word by its rarity: 1 over the number of candidates with a name having it
    val words = cs.map(c => c.names.flatMap(meant).distinct)
    val df = scala.collection.mutable.Map.empty[String, Int]
    def rarity(w: String): Double = 1.0 / df.getOrElseUpdate(w, math.max(1, words.count(_.exists(near(w, _)))))
    def score(n: String): Double =
      val lex = lexical(text, n, lexicon, rarity)
      val sem = semantic(table, units, n)
      if lex >= 0.999 then 0.7 + 0.1 * math.max(sem, 0.0) + 0.12 * math.min(explained(text, n, lexicon), 3)
      else math.max(0.45 * lex + 0.45 * sem, 0.85 * sem)
    cs.map(c => c.key -> c.names.filter(_.nonEmpty).map(score).maxOption.getOrElse(0.0)).sortBy(-_._2)

  /** one when the best passes `threshold` and leads the next by `margin`; several when two or more pass and are
   * closer than that; else nothing */
  def pick(ranked: Vector[(String, Double)], threshold: Double, margin: Double): Pick =
    ranked.filter(_._2 >= threshold) match
      case Vector() => Pick.Nothing
      case (k, s) +: rest if rest.headOption.forall(s - _._2 >= margin) => Pick.One(k, s)
      case passing => Pick.Several(passing.takeWhile(x => passing.head._2 - x._2 < margin).map(_._1))
