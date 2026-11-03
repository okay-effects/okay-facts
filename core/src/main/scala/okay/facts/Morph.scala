package okay.facts

/** A GRAMMATICAL CASE a template asks of a name (specs/design.md §17): `name(master(#s1)) as gen` */
enum Case:
  case nom, gen, dat, acc, ins, loc

object Case:
  def parse(s: String): Option[Case] = Case.values.find(_.toString == s.trim.toLowerCase)

/**
 * NAMES IN THEIR CASES (specs/design.md §17): a person's first name in Polish, Ukrainian or Russian put in the case
 * its construction asks — by rules for the common patterns ("Marek" → "Marka", "Markiem"; "Ewa" → "Ewy", "Ewą";
 * "Олег" → "Олега", "Олегом"; "Ірина" → "Ірини"; "Мария" → "Марии"), a parenthesised tail kept ("Marek (demo)" →
 * "Marka (demo)"). NEVER GUESSED: a name of another script than the language's, of two words or more, or of an ending
 * the rules do not know has no form (`None`), and the template keeps a construction that needs no case. English: the
 * name as it is. An owner's own forms (the data's `name.<case>`) come before these rules (`Resolve`)
 */
object Morph:
  def inflect(name: String, l: Lang, c: Case): Option[String] =
    if c == Case.nom || l == Lang.en then Some(name)
    else
      val trimmed = name.trim
      val cut = trimmed.indexOf(" (")
      val (head, tail) = if cut > 0 && trimmed.endsWith(")") then (trimmed.substring(0, cut), trimmed.substring(cut))
        else (trimmed, "")
      if head.isEmpty || head.exists(ch => ch.isWhitespace || ch.isDigit) || !head.head.isUpper then None
      else
        val cyr = head.forall(ch => ch >= 'Ѐ' && ch <= 'ԯ' || ch == '\'' || ch == '’' || ch == 'ʼ' ||
          ch == '-')
        val lat = head.forall(ch => (ch >= 'A' && ch <= 'z' && ch.isLetter) || (ch >= 'À' && ch <= 'ɏ') ||
          ch == '-')
        val form = l match
          case Lang.pl if lat => polish(head, c)
          case Lang.uk if cyr => ukrainian(head, c)
          case Lang.ru if cyr => russian(head, c)
          case _ => None
        form.map(_ + tail)

  private def endsWith(w: String, xs: String*): Boolean = xs.exists(w.endsWith)

  // ---- Polish

  private def polish(w: String, c: Case): Option[String] =
    val lw = w.toLowerCase
    if lw.endsWith("a") then polishA(w, c)
    else if Set("paweł")(lw) then Map(Case.gen -> "Pawła", Case.acc -> "Pawła", Case.ins -> "Pawłem",
      Case.dat -> "Pawłowi", Case.loc -> "Pawle").get(c)
    else if endsWith(lw, "ek") && lw.length >= 4 && !"aeiouyąęó".contains(lw(lw.length - 3)) then
      val stem = w.dropRight(2) + "k"
      Map(Case.gen -> "a", Case.acc -> "a", Case.ins -> "iem", Case.dat -> "owi", Case.loc -> "u").get(c).map(stem + _)
    else if endsWith(lw, "ec", "eł", "ś", "ź", "ć", "ń") || womenOfConsonants(lw) then None
    else if lw.last.isLetter && !"aeiouyąęó".contains(lw.last) then
      val velar = endsWith(lw, "k", "g")
      val softU = endsWith(lw, "k", "g", "h", "ch", "cz", "sz", "rz", "ż", "j", "l", "c")
      c match
        case Case.gen | Case.acc => Some(w + "a")
        case Case.ins => Some(w + (if velar then "iem" else "em"))
        case Case.dat => Some(w + "owi")
        case Case.loc => Option.when(softU)(w + "u")
        case Case.nom => Some(w)
    else None

  /** women's names ending in a consonant: not declined in Polish (never taken for a man's) */
  private val womenOfConsonants: Set[String] = Set("inez", "miriam", "ruth", "karin", "doris", "agnes", "ingrid",
    "astrid", "carmen", "rachel", "abigail", "esther", "margaret", "elizabeth", "lilith", "judyt", "noor", "ann",
    "kim", "jasmin", "yasmin", "dagmar", "gudrun", "sigrid", "mercedes", "dolores", "iris", "beatrix")

  /** a name in -a (Ewa, Anna, Kasia, Natalia, Maja, Agnieszka, Kuba — a man's -a declines alike) */
  private def polishA(w: String, c: Case): Option[String] =
    val lw = w.toLowerCase
    val stem = w.dropRight(1)
    val ls = lw.dropRight(1)
    val soft = endsWith(lw, "sia", "zia", "cia", "nia", "dzia")
    // gen (and dat, loc of the soft ones): -i after a velar, a soft stem, l, j; -ii after a consonant + i; else -y
    val gen =
      if soft then Some(stem)
      else if lw.endsWith("ia") then Some(stem + "i")
      else if lw.endsWith("ja") then Some(if "aeiouyó".contains(ls(ls.length - 2)) then w.dropRight(2) + "i"
        else stem + "i")
      else if endsWith(ls, "k", "g", "l") then Some(stem + "i")
      else if endsWith(ls, "cz", "sz", "rz", "ż", "c", "dz") then Some(stem + "y")
      else if ls.nonEmpty && !"aeiouyąęó".contains(ls.last) then Some(stem + "y")
      else None
    val datLoc =
      if soft || lw.endsWith("ia") || lw.endsWith("ja") || endsWith(ls, "l") then gen
      else
        val mutations = Vector("k" -> "ce", "g" -> "dze", "t" -> "cie", "d" -> "dzie", "r" -> "rze", "ł" -> "le",
          "n" -> "nie", "m" -> "mie", "w" -> "wie", "b" -> "bie", "p" -> "pie", "s" -> "sie", "z" -> "zie",
          "f" -> "fie")
        mutations.collectFirst { case (from, to) if ls.endsWith(from) && !ls.endsWith("sz") && !ls.endsWith("cz") &&
          !ls.endsWith("rz") => stem.dropRight(from.length) + to }
    c match
      case Case.gen => gen
      case Case.dat | Case.loc => datLoc
      case Case.acc => gen.map(_ => stem + "ę")
      case Case.ins => gen.map(_ => stem + "ą")
      case Case.nom => Some(w)

  // ---- Ukrainian

  private def ukrainian(w: String, c: Case): Option[String] =
    val lw = w.toLowerCase
    val stem = w.dropRight(1)
    val ls = lw.dropRight(1)
    val mixed = (s: String) => endsWith(s, "ж", "ч", "ш", "щ")
    if lw == "ігор" then Map(Case.gen -> "Ігоря", Case.acc -> "Ігоря", Case.ins -> "Ігорем", Case.dat -> "Ігорю",
      Case.loc -> "Ігорю").get(c)
    else if lw.endsWith("ія") then Map(Case.gen -> "ії", Case.dat -> "ії", Case.loc -> "ії", Case.acc -> "ію",
      Case.ins -> "ією").get(c).map(w.dropRight(2) + _)
    else if lw.endsWith("я") then Map(Case.gen -> "і", Case.dat -> "і", Case.loc -> "і", Case.acc -> "ю",
      Case.ins -> "ею").get(c).map(stem + _)
    else if lw.endsWith("а") then
      val dat = Vector("к" -> "ц", "г" -> "з", "х" -> "с").collectFirst { case (a, b) if ls.endsWith(a) =>
        stem.dropRight(1) + b + "і" }.getOrElse(stem + "і")
      c match
        case Case.gen => Some(stem + (if mixed(ls) then "і" else "и"))
        case Case.dat | Case.loc => Some(dat)
        case Case.acc => Some(stem + "у")
        case Case.ins => Some(stem + (if mixed(ls) then "ею" else "ою"))
        case Case.nom => Some(w)
    else if lw.endsWith("ій") then Map(Case.gen -> "я", Case.acc -> "я", Case.ins -> "єм", Case.dat -> "єві",
      Case.loc -> "єві").get(c).map(w.dropRight(1) + _)
    else if lw.endsWith("о") && lw.length >= 4 then Map(Case.gen -> "а", Case.acc -> "а", Case.ins -> "ом",
      Case.dat -> "ові", Case.loc -> "ові").get(c).map(stem + _)
    else if lw.last.isLetter && !"аеєиіїоуюяьй".contains(lw.last) then Map(Case.gen -> "а", Case.acc -> "а",
      Case.ins -> (if mixed(lw) then "ем" else "ом"), Case.dat -> "ові", Case.loc -> "ові").get(c).map(w + _)
    else None

  // ---- Russian

  private def russian(w: String, c: Case): Option[String] =
    val lw = w.toLowerCase
    val stem = w.dropRight(1)
    val ls = lw.dropRight(1)
    val exceptions = Map("павел" -> "Павл", "лев" -> "Льв", "пётр" -> "Петр")
    if lw.endsWith("ия") then Map(Case.gen -> "и", Case.dat -> "и", Case.loc -> "и", Case.acc -> "ю",
      Case.ins -> "ей").get(c).map(stem + _)
    else if lw.endsWith("я") then Map(Case.gen -> "и", Case.dat -> "е", Case.loc -> "е", Case.acc -> "ю",
      Case.ins -> "ей").get(c).map(stem + _)
    else if lw.endsWith("а") then
      val i = endsWith(ls, "г", "к", "х", "ж", "ч", "ш", "щ")
      val e = endsWith(ls, "ж", "ч", "ш", "щ", "ц")
      c match
        case Case.gen => Some(stem + (if i then "и" else "ы"))
        case Case.dat | Case.loc => Some(stem + "е")
        case Case.acc => Some(stem + "у")
        case Case.ins => Some(stem + (if e then "ей" else "ой"))
        case Case.nom => Some(w)
    else if lw.endsWith("ий") then Map(Case.gen -> "я", Case.acc -> "я", Case.ins -> "ем", Case.dat -> "ю",
      Case.loc -> "и").get(c).map(w.dropRight(1) + _)
    else if lw.endsWith("й") || lw.endsWith("ь") then Map(Case.gen -> "я", Case.acc -> "я", Case.ins -> "ем",
      Case.dat -> "ю", Case.loc -> "е").get(c).map(stem + _)
    else if lw.last.isLetter && !"аеёиоуыэюяь".contains(lw.last) then
      val base = exceptions.get(lw).getOrElse(w)
      val e = endsWith(lw, "ж", "ч", "ш", "щ", "ц")
      Map(Case.gen -> "а", Case.acc -> "а", Case.ins -> (if e then "ем" else "ом"), Case.dat -> "у",
        Case.loc -> "е").get(c).map(base + _)
    else None
