package okay.facts.router

import okay.facts.Lang

/** PLACES IN ANY SCRIPT AND CASE (specs/design.md §17 "Round 4"): forms generated, matched folded */
class PlacesSuite extends munit.FunSuite:
  def place(id: String, pl: String, kind: String = "city", city: String = "", uk: Option[String] = None,
            ru: Option[String] = None, en: Option[String] = None, common: Boolean = false): Place =
    Place(id, Map(Lang.pl -> pl) ++ uk.map(Lang.uk -> _) ++ ru.map(Lang.ru -> _) ++ en.map(Lang.en -> _), kind, city,
      PlaceForms.forms(pl, uk, ru, en), common)

  val places: Places = Places(Vector(place("sopot", "Sopot"), place("swarzedz", "Swarzędz"),
    place("warszawa", "Warszawa", uk = Some("Варшава"), ru = Some("Варшава"), en = Some("Warsaw")),
    place("krakow", "Kraków", uk = Some("Краків"), ru = Some("Краков"), en = Some("Krakow")),
    place("gdynia", "Gdynia"), place("lubon", "Luboń"),
    place("warszawa/mokotow", "Mokotów", "district", "warszawa"),
    place("lublin/rury", "Rury", "district", "lublin", common = true),
    place("gorzow", "Gorzów Wielkopolski")))

  def ids(text: String): Vector[String] = places.find(text).flatMap(_.places.map(_.id))

  test("the Cyrillic of a Polish name, each language's way"):
    assertEquals(PlaceForms.cyrillic("Sopot", Lang.uk), "Сопот")
    assertEquals(PlaceForms.cyrillic("Mokotów", Lang.uk), "Мокотув")
    assertEquals(PlaceForms.cyrillic("Swarzędz", Lang.ru), "Сважендз")
    assertEquals(PlaceForms.cyrillic("Gdynia", Lang.ru), "Гдыня")
    assertEquals(PlaceForms.cyrillic("Gdynia", Lang.uk), "Гдиня")
    assertEquals(PlaceForms.cyrillic("Luboń", Lang.uk), "Любонь")

  test("a place in any script and case: Cyrillic, declined, typed in Latin letters, a typo"):
    for t <- Seq("в Сопот приезжаете?", "а в Сопоте?", "у Сопоті працюєте?", "v Sopote", "dojedziecie do Sopotu?",
        "w Sopocie?", "SOPOT") do assertEquals(ids(t), Vector("sopot"), t)
    for t <- Seq("в Сваржендз приедете?", "w Swarzędzu", "do Swarzedza", "у Сважендзі") do
      assertEquals(ids(t), Vector("swarzedz"), t)
    for t <- Seq("до Варшави", "в Варшаве", "из Варшавы", "w Warszawie", "Warsaw", "z warszawy?") do
      assertEquals(ids(t), Vector("warszawa"), t)
    for t <- Seq("у Кракові", "в Краков", "w Krakowie", "do Krakowa") do assertEquals(ids(t), Vector("krakow"), t)
    assertEquals(ids("na Mokotowie"), Vector("warszawa/mokotow"))
    assertEquals(ids("в Мокотуві"), Vector("warszawa/mokotow"))
    assertEquals(ids("a Luboń?"), Vector("lubon"))
    assertEquals(ids("w Gorzowie Wielkopolskim"), Vector("gorzow"))
    assertEquals(ids("v Gdyne"), Vector("gdynia"))

  test("a common word is no place unless written as one; a word with no reason is none"):
    assertEquals(ids("ile kosztuje wymiana rury?"), Vector.empty)
    assertEquals(ids("mieszkam na Rurach"), Vector("lublin/rury"))
    assertEquals(ids("mieszkam na rurach"), Vector.empty)
    assertEquals(ids("ile kosztuje strzyżenie"), Vector.empty)

  test("a text with its places as their Polish names"):
    assertEquals(places.canonical("манікюр у Сопоті"), "манікюр у Sopot")
    assertEquals(places.canonical("fryzjer na Mokotowie"), "fryzjer na Mokotów Warszawa")
