package okay.facts.router

import okay.facts.Lang

/**
 * A PLACE'S FORMS, GENERATED (specs/design.md §17 "Round 4"; `tools/gazetteer.scala`): from its official Polish name
 * (and, for the large cities, its exonyms) — the Polish genitive and locative by rules ("w Sopocie", "do Sopotu",
 * "w Krakowie"), the Ukrainian and Russian names by each language's transliteration of Polish ("Mokotów" →
 * "Мокотув", "Swarzędz" → "Сважендз"), their declension (the locative, genitive,
 * dative, accusative, instrumental: "у Сопоті", "в Сопоте", "до Сопота"), every one folded (`Spell.fold`). Rules,
 * not a dictionary: an irregular form the rules miss is matched by its stem (`Places`), or listed by hand in the data
 */
object PlaceForms:
  /** a Polish name in the Cyrillic of `l` (uk or ru): digraphs first, a vowel after a soft consonant or `i` softened */
  def cyrillic(name: String, l: Lang): String = name.split(" ").map(w => word(w, l)).mkString(" ")

  private def word(w0: String, l: Lang): String =
    val uk = l == Lang.uk
    val w = w0
    val lower = w.toLowerCase
    val out = new StringBuilder
    var i = 0
    def at(k: Int): Char = if k < lower.length then lower.charAt(k) else ' '
    def vowel(c: Char) = "aąeęioóuy".contains(c)
    // a vowel after `i` (or `j`) is softened: ia → я, ie → є/е, io → йо/ё, iu → ю
    def soft(c: Char): String = c match
      case 'a' => "я"
      case 'e' => if uk then "є" else "е"
      case 'o' | 'ó' => if uk then "йо" else "ё"
      case 'u' => "ю"
      case 'ą' => if uk then "йон" else "ён"
      case 'ę' => if uk then "єн" else "ен"
      case other => other.toString
    while i < lower.length do
      val c = at(i)
      val next = at(i + 1)
      val (s, n): (String, Int) =
        if lower.startsWith("szcz", i) then ("щ", 4)
        else if lower.startsWith("sz", i) then ("ш", 2)
        else if lower.startsWith("cz", i) then ("ч", 2)
        else if lower.startsWith("rz", i) then ("ж", 2)
        else if lower.startsWith("ch", i) then ("х", 2)
        else if lower.startsWith("dż", i) then ("дж", 2)
        else if lower.startsWith("dź", i) then ("дзь", 2)
        else if lower.startsWith("dzi", i) && vowel(at(i + 3)) then ("дз" + soft(at(i + 3)), 4)
        else if lower.startsWith("dz", i) then ("дз", 2)
        else if c == 'i' && vowel(next) && i > 0 then
          // after a consonant: the consonant soft, the vowel softened ("Gdynia" → "Гдиня", "Kielce" no)
          (soft(next), 2)
        else if (c == 'j') && vowel(next) then
          (if i == 0 || vowel(at(i - 1)) then soft(next) else soft(next), 2)
        else if c == 'l' && vowel(next) && next != 'y' then
          (if next == 'i' then (if uk then "лі" else "ли") else "л" + soft(next), 2)
        else if (c == 'c' || c == 's' || c == 'z' || c == 'n') && next == 'i' && vowel(at(i + 2)) then
          // ci, si, zi, ni before a vowel: a soft consonant and a softened vowel ("Siedlce" → "Седльце", "Nysa")
          (Map('c' -> "ц", 's' -> "с", 'z' -> "з", 'n' -> "н")(c) + soft(at(i + 2)), 3)
        else c match
          case 'a' => ("а", 1)
          case 'ą' => ("он", 1)
          case 'b' => ("б", 1)
          case 'c' => ("ц", 1)
          case 'ć' => ("ць", 1)
          case 'd' => ("д", 1)
          case 'e' => ("е", 1)
          case 'ę' => ("ен", 1)
          case 'f' => ("ф", 1)
          case 'g' => ("г", 1)
          case 'h' => (if uk then "г" else "х", 1)
          case 'i' => (if uk then "і" else "и", 1)
          case 'j' => ("й", 1)
          case 'k' => ("к", 1)
          case 'l' => (if next == 'y' then "л" else "ль", 1)
          case 'ł' => ("л", 1)
          case 'm' => ("м", 1)
          case 'n' => ("н", 1)
          case 'ń' => ("нь", 1)
          case 'o' => ("о", 1)
          case 'ó' => ("у", 1)
          case 'p' => ("п", 1)
          case 'r' => ("р", 1)
          case 's' => ("с", 1)
          case 'ś' => ("сь", 1)
          case 't' => ("т", 1)
          case 'u' => ("у", 1)
          case 'w' => ("в", 1)
          case 'y' => (if uk then "и" else "ы", 1)
          case 'z' => ("з", 1)
          case 'ź' => ("зь", 1)
          case 'ż' => ("ж", 1)
          case other => (other.toString, 1)
      out ++= s
      i += n
    val r = out.result().replace("ьь", "ь")
    if w.headOption.exists(_.isUpper) then r.head.toUpper.toString + r.tail else r

  /** a Cyrillic name's forms in `l`: its nominative and its cases (a feminine in -а/-я, a masculine in a consonant,
   * a soft one in -ь; a neuter or a plural as it is) — every word of an adjective and a noun alike, roughly */
  def cyrillicCases(name: String, l: Lang): Vector[String] =
    val uk = l == Lang.uk
    def cases(w: String): Vector[String] =
      val last = w.lastOption.getOrElse(' ')
      val hard = "гкхжшчщ".contains(w.dropRight(1).lastOption.getOrElse(' '))
      if last == 'а' then
        val s = w.dropRight(1)
        if uk then Vector(w, s + "і", s + (if hard then "и" else "и"), s + "у", s + "ою")
        else Vector(w, s + "е", s + (if hard then "и" else "ы"), s + "у", s + "ой")
      else if last == 'я' then
        val s = w.dropRight(1)
        if uk then Vector(w, s + "і", s + "ю", s + "ею") else Vector(w, s + "е", s + "и", s + "ю", s + "ей")
      else if last == 'ь' then
        val s = w.dropRight(1)
        if uk then Vector(w, s + "і", s + "ю") else Vector(w, s + "и", s + "ью", s + "я", s + "е", s + "ю")
      else if last == 'й' || "оеиіу".contains(last) then Vector(w)
      else if uk then
        // -ів alternates: "Краків" → "Кракова", "у Кракові"
        val b = if w.endsWith("ів") then w.dropRight(2) + "ов" else w
        Vector(w, b + "і", b + "а", b + "у", b + "ом", b + "ові")
      else Vector(w, w + "е", w + "а", w + "у", w + "ом")
    // a one-word name declines; of several, the last word (a noun with its adjective as it is) and each alone
    name.split(" ").toVector match
      case Vector(one) => cases(one)
      case ws => cases(ws.last).map(f => (ws.init :+ f).mkString(" ")) :+ name

  /** a Polish name's genitive and locative by rules (a feminine in -a, a masculine in a consonant, -ów; a plural or
   * an adjective's ending as it is), the nominative first */
  def polishCases(name: String): Vector[String] =
    def cases(w: String): Vector[String] =
      val lw = w.toLowerCase
      def ends(s: String*) = s.exists(lw.endsWith)
      def cut(n: Int) = w.dropRight(n)
      if ends("ów") then Vector(w, cut(2) + "owa", cut(2) + "owie", cut(2) + "owem")
      else if ends("ia", "ja") then Vector(w, cut(1) + "i", cut(1) + "ę")
      else if ends("ka") then Vector(w, cut(1) + "i", cut(2) + "ce")
      else if ends("ga") then Vector(w, cut(1) + "i", cut(2) + "dze")
      else if ends("cha") then Vector(w, cut(1) + "y", cut(3) + "sze")
      else if ends("ra") then Vector(w, cut(1) + "y", cut(1) + "ze")
      else if ends("ta") then Vector(w, cut(1) + "y", cut(2) + "cie")
      else if ends("da") then Vector(w, cut(1) + "y", cut(2) + "dzie")
      else if ends("ła") then Vector(w, cut(1) + "y", cut(2) + "le")
      else if ends("la") then Vector(w, cut(1) + "i")
      else if ends("a") then Vector(w, cut(1) + "y", cut(1) + "ie")
      // a plural ("Katowice", "Kielce", "Tychy", "Piaski", "Rury"): the genitive bare, the locative and dative
      else if ends("ce", "ie") then Vector(w, cut(1), cut(1) + "ach")
      else if ends("y", "i") then Vector(w, cut(1), cut(1) + "ach", cut(1) + "om")
      else if ends("sk", "ck", "k", "g", "ch") then Vector(w, w + "a", w + "u")
      else if ends("t") then Vector(w, w + "u", w + "a", cut(1) + "cie")
      else if ends("d") then Vector(w, w + "u", w + "a", cut(1) + "dzie")
      else if ends("ń") then Vector(w, cut(1) + "nia", cut(1) + "niu")
      else if ends("ź", "ś", "ć") then Vector(w, w.dropRight(1) + (lw.last match
          case 'ź' => "zi"
          case 'ś' => "si"
          case _ => "ci"))
      else if ends("ł") then Vector(w, w + "a", w + "u", cut(1) + "le")
      else if ends("r") then Vector(w, w + "a", w + "u", w + "ze")
      else if ends("n", "m", "p", "b", "f", "s", "z", "w") then Vector(w, w + "a", w + "u", w + "ie")
      else if ends("c", "cz", "sz", "rz", "ż", "dz", "l", "j") then Vector(w, w + "a", w + "u")
      else Vector(w)
    name.split(" ").toVector match
      case Vector(one) => cases(one)
      case ws =>
        // the noun (the first word when the second is an adjective: "Gorzów Wielkopolski"; else the last)
        val adjective = ws.last.toLowerCase.matches(".*(ski|cki|ska|cka|skie|ckie|ny|na|ne|wy|wa|we)$")
        if adjective then cases(ws.head).map(f => (f +: ws.tail).mkString(" ")) ++
          (if ws.head.length >= 6 then cases(ws.head) else Vector.empty)
        else cases(ws.last).map(f => (ws.init :+ f).mkString(" "))

  /** EVERY FORM of a place, folded, deduplicated: its Polish ones, its Cyrillic ones (the exonym when given, else the
   * transliteration) in Ukrainian and Russian, its English name */
  def forms(pl: String, uk: Option[String] = None, ru: Option[String] = None, en: Option[String] = None,
            more: Vector[String] = Vector.empty): Vector[String] =
    // an exonym instead of the letters' transliteration (a transliteration of "Łódź" would read "ludzi")
    val cyr = Vector(uk.getOrElse(cyrillic(pl, Lang.uk)) -> Lang.uk, ru.getOrElse(cyrillic(pl, Lang.ru)) -> Lang.ru)
    (polishCases(pl) ++ cyr.flatMap((n, l) => cyrillicCases(n, l)) ++ en.toVector ++ more)
      .map(f => Spell.words(f).mkString(" ")).filter(_.nonEmpty).distinct
