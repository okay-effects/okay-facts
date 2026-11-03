package okay.facts.router

import okay.facts.Words

/** A PRICE LIMIT a message sets, in minor units (grosze): at most, at least */
final case class Amount(max: Option[Long] = None, min: Option[Long] = None):
  def isEmpty: Boolean = max.isEmpty && min.isEmpty

/**
 * PRICE LIMITS IN WORDS (specs/design.md §17), Polish, Ukrainian, Russian, English: `do 150 zł`, `poniżej 150`,
 * `taniej niż 150`, `max 150`, `150 zł max`, `до 150`, `дешевше 150`, `дешевле 150 злотых`, `under 150`, `up to 150`,
 * `less than 150`; `od 100 zł`, `powyżej 100`, `over 100`. A number of 24 or less needs a currency or a price's word
 * (`do 12` is a time)
 */
object Amount:
  private def r(p: String) = Rules.compile(p)
  private val num = "(\\d{1,5}(?:[.,]\\d{1,2})?)"
  private val cur = "(\\s*(?:zl|pln|zlotych|zloty|zlote|злот\\w*|зл))?"
  private val strong = "ponizej|taniej niz|tansze niz|tanszy niz|nie drozej niz|nie wiecej niz|maks\\w*|max|under|" +
    "below|less than|cheaper than|up to|no more than|at most|не дорожче|дешевше|менше ніж|менше|максимум|в межах|" +
    "не дороже|дешевле|меньше|в пределах|до"
  private val maxAfter = r(s"\\b(?:$strong|do)\\s*$num$cur")
  private val maxBefore = r(s"\\b$num$cur\\s*(?:max|maks\\w*|максимум|top|at most)\\b")
  private val minAfter = r(s"\\b(?:od|powyzej|drozej niz|over|above|more than|from|от|від|понад|больше|більше)" +
    s"\\s*$num$cur")

  private def value(n: String): Option[Long] = n.replace(',', '.').toDoubleOption.map(d => math.round(d * 100))

  private def limit(re: Re, t: String, weakWord: String => Boolean): Option[Long] =
    re.all(t).iterator.flatMap { (at, _, g) =>
      val word = t.substring(at).takeWhile(c => !c.isDigit).trim
      value(g(0)).filter(v => g(1).trim.nonEmpty || v > 2400 || !weakWord(word))
    }.nextOption()

  def of(text: String): Amount =
    val t = Words.norm(text)
    // `do`, `до`, `od`, `from`, `от`, `від` are also a time's words: their numbers of 24 or less need a currency
    val weak = Set("do", "до", "od", "from", "от", "від")
    Amount(limit(maxAfter, t, w => weak(w)).orElse(limit(maxBefore, t, _ => false)), limit(minAfter, t, w => weak(w)))
