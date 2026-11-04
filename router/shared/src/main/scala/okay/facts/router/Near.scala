package okay.facts.router

import okay.facts.Words

/** A PLACE ASKED TO BE NEAR: the speaker's own (`Me`) or one named, its words as said (`Named`) — the host geocodes */
enum Near:
  case Me
  case Named(words: String)

/**
 * "NEAR" IN WORDS (specs/design.md §17), Polish, Ukrainian, Russian, English, in Latin letters too: the speaker's own
 * place ("blisko mnie", "w pobliżu", "niedaleko", "поруч", "біля мене", "рядом", "поблизости", "near me", "nearby") or
 * a place named after a word of nearness ("w okolicy Mokotowa", "koło metra Centrum", "біля Оперного театру", "около
 * Центра", "near Centrum metro") — up to four words, up to a punctuation or a word that starts another thing ("na",
 * "do", "za", "for", "under", a number)
 */
object Near:
  private def r(p: String) = Rules.compile(p)
  private val me = r("\\b(?:blisko mnie|kolo mnie|koło mnie|niedaleko mnie|w moje[ij] okolicy|w poblizu|w pobliżu|" +
    "w okolicy|niedaleko|najbliż\\w*|najbliz\\w*|near me|nearby|close to me|around me|closest|nearest|поруч|біля мене|" +
    "недалеко від мене|поблизу|найближч\\w*|рядом|рядом со мной|недалеко от меня|поблизости|возле меня|ближайш\\w*|" +
    "poruch|ryadom|poblizosti)\\b")
  private val named = r("\\b(?:w okolicy|w okolicach|w rejonie|kolo|koło|blisko|niedaleko|przy|obok|near|close to|" +
    "next to|біля|коло|поблизу|недалеко від|в районі|около|возле|рядом с|недалеко от|в районе|bilya|okolo|" +
    "vozle)\\s+((?:(?:metra|metro|stacji|ulicy|ul\\.|placu|станції|метро|станции|улицы|вулиці)\\s+)?" +
    "[^\\s,.;!?\\d]+(?:\\s+[^\\s,.;!?\\d]+){0,3})")
  private val stop = Set("na", "do", "za", "w", "we", "dla", "po", "o", "for", "under", "below", "at", "on", "in",
    "tomorrow", "today", "jutro", "dzis", "dzisiaj", "на", "до", "за", "в", "у", "для", "по", "о", "завтра", "сьогодні",
    "сегодня", "i", "and", "і", "и", "та", "ile", "how", "скільки", "сколько", "mnie", "me", "мене", "меня", "мной")

  def of(text: String): Option[Near] =
    named.all(text).iterator.flatMap { (_, _, g) =>
      val ws = g(0).split("\\s+").toVector.filter(_.nonEmpty).takeWhile(w => !stop(Words.norm(w)))
      Option.when(ws.nonEmpty)(Near.Named(ws.mkString(" ")))
    }.nextOption().orElse(Option.when(me.test(text))(Near.Me))
