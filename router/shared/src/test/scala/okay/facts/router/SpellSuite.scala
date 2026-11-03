package okay.facts.router

/** names across scripts and forms (specs/design.md §17) */
class SpellSuite extends munit.FunSuite:
  test("names across scripts and forms: places and people as messages write them"):
    for (t, n) <- Seq("w Krakowie" -> "Kraków", "у Кракові" -> "Kraków", "во Вроцлаве" -> "Wrocław",
      "у Варшаві" -> "Warszawa", "do Swarzędza" -> "Swarzędz", "в Любонь" -> "Luboń", "u Marka" -> "Marek (demo)",
      "у Марека" -> "Marek (demo)", "do Ewy" -> "Ewa (demo)", "Кубы" -> "Kuba (demo)", "z Gdyni" -> "Gdynia",
      "в Гданьске" -> "Gdańsk", "Oleśnicy" -> "Oleśnica", "в Познани" -> "Poznań") do
      assert(Spell.names(t, n), s"$t ~ $n")
    for (t, n) <- Seq("w Krakowie" -> "Warszawa", "u Marka" -> "Ewa (demo)", "do Kati" -> "Kuba (demo)",
      "manicure" -> "Marek (demo)", "в Гдині" -> "Gdańsk") do
      assert(!Spell.names(t, n), s"$t !~ $n")
