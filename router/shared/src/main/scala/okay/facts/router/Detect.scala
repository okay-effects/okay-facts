package okay.facts.router

import okay.facts.Lang

/**
 * WHICH LANGUAGE A MESSAGE IS IN (okay-chat's `Lang`, ported by okay-chat-edge; specs/design.md §17): the script
 * first (any Cyrillic letter: Cyrillic — Latin rides inside as a name or a brand), then within it the letters one
 * language owns alone and everyday words; an amount with its currency counts for none («do 150 zł» is not Polish).
 * The unique best with any evidence wins; else none, and the conversation's last language within the script answers
 */
object Detect:
  private val marks: Map[Lang, Set[Char]] = Map(
    Lang.ru -> Set('ы', 'э', 'ъ', 'ё'), Lang.uk -> Set('і', 'ї', 'є', 'ґ'),
    Lang.pl -> Set('ą', 'ć', 'ę', 'ł', 'ń', 'ś', 'ź', 'ż'), Lang.en -> Set.empty)

  // okay-chat's words, and a shop's everyday ones
  private val words: Map[Lang, Set[String]] = Map(
    Lang.uk -> Set("не", "що", "як", "мені", "мене", "потрібен", "потрібно", "треба", "шукаю", "хто", "де", "чи",
      "дуже", "будь", "ласка", "так", "ні", "куди", "сьогодні", "ввечері", "зранку", "вранці", "зараз", "якщо",
      "чому", "коли", "скільки", "коштує", "хочу", "можу", "маю", "буде", "було", "знайти", "привіт", "вітаю",
      "добрий", "доброго", "дякую", "дякуємо", "бувай", "записатися", "запис", "вільно", "майстер", "адреса",
      "працюєте", "стрижка", "манікюр", "дешевше", "дорожче", "або", "також", "його", "цей", "ця", "ваш", "ваша"),
    Lang.ru -> Set("что", "как", "мне", "меня", "нужен", "нужно", "ищу", "кто", "где", "очень", "пожалуйста", "да",
      "нет", "это", "куда", "сегодня", "вечером", "утром", "сейчас", "если", "почему", "когда", "сколько", "стоит",
      "хочу", "могу", "будет", "было", "найти", "привет", "здравствуйте", "добрый", "доброе", "спасибо", "пока",
      "записаться", "запись", "свободно", "мастер", "адрес", "работаете", "стрижка", "маникюр", "дешевле",
      "дороже", "или", "тоже", "его", "этот", "эта", "ваш", "ваша", "можно"),
    Lang.pl -> Set("nie", "tak", "jest", "jestem", "czy", "gdzie", "kto", "potrzebuję", "szukam", "proszę",
      "dziękuję", "mam", "chcę", "dla", "cześć", "dzień", "witam", "dobry", "dzięki", "pa", "ile", "kosztuje",
      "jutro", "dziś", "dzisiaj", "rano", "wieczorem", "macie", "jakie", "jaki", "która", "którą", "wolne", "wolny",
      "termin", "zapisać", "umówić", "strzyżenie", "taniej", "drożej", "albo", "też", "się", "mnie", "na",
      "po", "ten", "ta", "wasz", "pan", "pani", "można", "otwarte"),
    Lang.en -> Set("the", "is", "are", "need", "want", "can", "please", "thanks", "you", "my", "me", "for", "who",
      "what", "how", "hi", "hello", "hey", "morning", "evening", "bye", "cheers", "much", "price", "tomorrow",
      "today", "free", "book", "open", "where", "when", "does", "have", "any", "cheaper", "haircut", "an",
      "it", "this", "that", "your", "with", "and", "or", "of", "in", "at"))

  // an amount with its currency (and a time): not a language's
  private val amount = Rules.compile("\\d[\\d\\s.,:]*\\s*(?:zł|zl|pln|złot\\w*|злот\\w*|зл|€|eur|\\$|usd|грн|₴|" +
    "руб\\w*|₽)?")

  private def cyrillic(c: Char): Boolean = c >= 'Ѐ' && c <= 'ԯ'
  private def latin(c: Char): Boolean = (c >= 'a' && c <= 'z') || (c >= 'À' && c <= 'ɏ')

  /** the language to answer in: the message's own, else the last one if the message's script is its, else the
   * script's first (Cyrillic: `fallback` if Cyrillic, else Russian; Latin: `fallback` if Latin, else English) */
  def answer(text: String, last: Option[Lang], fallback: Lang = Lang.pl): Lang =
    of(text).getOrElse {
      val t = text.toLowerCase
      def isCyr(l: Lang) = l == Lang.ru || l == Lang.uk
      if t.exists(cyrillic) then last.filter(isCyr).orElse(Some(fallback).filter(isCyr)).getOrElse(Lang.ru)
      else if t.exists(latin) then last.filterNot(isCyr).orElse(Some(fallback).filterNot(isCyr)).getOrElse(Lang.en)
      else last.getOrElse(fallback)
    }

  /** the message's own language, when it says it */
  def of(text: String): Option[Lang] =
    val t = amount.replaceAll(text.toLowerCase, " ")
    val ws = Table.tokens(t)
    val candidates =
      if t.exists(cyrillic) then Vector(Lang.ru, Lang.uk)
      else if t.exists(latin) then Vector(Lang.pl, Lang.en)
      else Vector.empty
    val scored = candidates.map(l => l -> (t.count(marks(l)) + ws.count(words(l)))).sortBy(-_._2)
    scored match
      case (best, n) +: rest if n > 0 && rest.headOption.forall(_._2 < n) => Some(best)
      case _ => None
