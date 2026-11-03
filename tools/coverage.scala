//> using scala 3.3.8
//> using jvm 21
//> using repository https://okay-effects.github.io/okay-simple/maven
//> using dep io.github.okay-effects::okay-effects-json-value:0.1.1
//> using file ../router/shared/src/main/scala/okay/facts/router/Table.scala

/**
 * A TABLE'S COVERAGE of a set of texts (okay-facts specs/design.md §17): the share of their words it knows, by
 * language, and the share of texts it knows nothing of (those the router cannot read by meaning at all).
 *   scala-cli run tools/coverage.scala -- <turns.jsonl: {text, lang}> <table.safetensors>…
 */
import okay.effects.json.Json
import okay.facts.router.Table
import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

@main def coverage(turns: String, tables: String*): Unit =
  val rows = Files.readAllLines(Paths.get(turns)).asScala.toVector.map(Json.fromText)
    .filter(_.field("text").isDefined).map(j => (j("text").string, j("lang").string))
  for f <- tables.filterNot(_.startsWith("--")) do
    val t = Table(Files.readAllBytes(Paths.get(f)))
    println(s"$f: ${t.size} units × ${t.dim}")
    for (l, xs) <- rows.groupBy(_._2).toVector.sortBy(_._1) :+ ("all" -> rows) do
      val words = xs.flatMap((s, _) => Table.tokens(s))
      val known = words.count(t.has)
      val blind = xs.count((s, _) => t.encode(s).isEmpty)
      println(f"  $l%-3s words ${100.0 * known / words.size}%5.1f%% known ($known of ${words.size}); " +
        f"texts with no known word: $blind of ${xs.size}")
