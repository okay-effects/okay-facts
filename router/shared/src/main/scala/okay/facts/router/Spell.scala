package okay.facts.router

import okay.facts.{Table as Rows, Words}

/**
 * NAMES ACROSS SCRIPTS AND FORMS (specs/design.md §17): a place's or a person's name as a message writes it —
 * declined ("w Krakowie", "u Marka", "do Swarzędza"), in Cyrillic for a Latin name ("Кракові", "Марека", "Любонь")
 * — matched against the data's name: both folded to plain Latin (Cyrillic transliterated, Polish digraphs and
 * diacritics simplified, w→v, y→i), then whole, by prefix, by stem or one edit away (as `find` matches words)
 */
object Spell:
  private val cyr: Map[Char, String] = Map('а' -> "a", 'б' -> "b", 'в' -> "v", 'г' -> "g", 'ґ' -> "g", 'д' -> "d",
    'е' -> "e", 'є' -> "e", 'ё' -> "o", 'ж' -> "zh", 'з' -> "z", 'и' -> "i", 'і' -> "i", 'ї' -> "i", 'й' -> "j",
    'к' -> "k", 'л' -> "l", 'м' -> "m", 'н' -> "n", 'о' -> "o", 'п' -> "p", 'р' -> "r", 'с' -> "s", 'т' -> "t",
    'у' -> "u", 'ф' -> "f", 'х' -> "h", 'ц' -> "c", 'ч' -> "ch", 'ш' -> "sh", 'щ' -> "sh", 'ъ' -> "", 'ы' -> "i",
    'ь' -> "", 'э' -> "e", 'ю' -> "u", 'я' -> "a", '\'' -> "", 'ʼ' -> "", '’' -> "")

  /** a word folded to plain Latin */
  def fold(word: String): String =
    val s = Words.norm(word).flatMap(c => cyr.getOrElse(c, c.toString))
    s.replace("sz", "sh").replace("cz", "ch").replace("rz", "zh").replace("ż", "zh").replace("w", "v")
      .replace("y", "i").replace("ch", "h").replace("kh", "h")

  /** the words of a text, folded */
  def words(text: String): Vector[String] = Words.split(text).map(fold).filter(_.nonEmpty)

  /** whether a word of the text is the name's word (4 letters or more: by prefix, stem or one edit; of 3: its first
   * two and an ending) */
  def near(word: String, name: String): Boolean =
    val (w, n) = (fold(word), fold(name))
    if w.length < 3 then w == n
    else if n.length < 4 then w == n || (n.length == 3 && w.startsWith(n.dropRight(1)) && w.length <= 4)
    else Rows.matching(w, n) >= 0.5 || Rows.matching(n, w) >= 0.5 || (w.length >= 4 && n.startsWith(w.dropRight(1)) &&
      w.length >= n.length - 2) || (n.length >= 5 && w.startsWith(n.dropRight(2)))

  /** whether the text names the name: each of its words (3 letters or more) has a near word in the text */
  def names(text: String, name: String): Boolean =
    val want = Words.split(name.replaceAll("\\(.*?\\)", " ")).filter(_.length >= 3)
    val have = Words.split(text)
    want.nonEmpty && want.forall(w => have.exists(near(_, w)))
