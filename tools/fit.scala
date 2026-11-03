//> using scala 3.3.8
//> using jvm 21
//> using repository https://okay-effects.github.io/okay-simple/maven
//> using repository https://okay-effects.github.io/okay-scala/maven
//> using repository https://okay-effects.github.io/okay-llm/maven
//> using repository https://okay-effects.github.io/okay-facts/maven
//> using dep io.github.okay-effects::okay-facts:0.1.0
//> using file ../router/shared/src/main/scala/okay/facts/router/Table.scala
//> using file ../router/shared/src/main/scala/okay/facts/router/Probe.scala
//> using file ../router/shared/src/main/scala/okay/facts/router/Router.scala
//> using file ../router/shared/src/main/scala/okay/facts/router/Rules.scala
//> using file ../router/jvm/src/main/scala/okay/facts/router/Re.scala

/**
 * THE PROBE, FITTED (okay-facts specs/design.md §17) on the intents' examples over the table, and held-out numbers to
 * choose the thresholds by: every 5th example left out, the probe fitted on the rest, the left-out routed (rules
 * first, as the router does); the share right, and right among the confident at each threshold. Then the probe on
 * every example, written.
 *   scala-cli run tools/fit.scala -- <intents.json> <table.safetensors> <out probe.json>
 */
import okay.effects.json.Json
import okay.facts.router.{Intent, Intents, Probe, Route, Router, Table}
import java.nio.file.{Files, Paths}

@main def fit(intentsFile: String, tableFile: String, out: String): Unit =
  val intents = Intents.parse(Files.readString(Paths.get(intentsFile)))
  val table = Table(Files.readAllBytes(Paths.get(tableFile)))
  val all = Intents.examples(intents)
  val (held, kept) = all.zipWithIndex.partition(_._2 % 5 == 2)
  def only(keep: Set[(String, String)]): Vector[Intent] = intents.map(i => i.copy(examples = i.examples.map((l, xs) =>
    l -> xs.filter(x => keep((x, i.name))))))
  val train = only(kept.map(x => (x._1._1, x._1._2)).toSet)
  val router = Router(train, Router.fit(table, train))
  val routed = held.map(_._1).map((t, i, l) => (i, router.route(table, t), l))
  println(s"held out: ${routed.size}; right ${routed.count((i, r, _) => r.intent == i)} " +
    s"(by rule ${routed.count(_._2.by == "rule")}, unknown ${routed.count(_._2.by == "unknown")})")
  for (l, xs) <- routed.groupBy(_._3).toVector.sortBy(_._1.ordinal) do
    println(s"  $l: ${xs.count((i, r, _) => r.intent == i)} / ${xs.size}")
  for p <- Seq(0.3, 0.4, 0.5, 0.6, 0.7, 0.8); m <- Seq(0.1, 0.2, 0.3) do
    val conf = routed.filter(_._2.confident(p, m))
    println(f"  p >= $p%.1f, margin >= $m%.1f: confident ${conf.size}%3d of ${routed.size}, right " +
      f"${conf.count((i, r, _) => r.intent == i)}%3d (${100.0 * conf.count((i, r, _) => r.intent == i) /
        math.max(conf.size, 1)}%.1f%%)")
  routed.filter((i, r, _) => r.intent != i).foreach((i, r, l) => println(s"  wrong: $l $i → ${r.intent} (${r.by}, " +
    f"${r.p}%.2f)"))
  val probe = Router.fit(table, intents)
  Files.writeString(Paths.get(out), Json.print(Probe.toJson(probe)))
  println(s"written $out: ${probe.classes.size} classes × ${probe.w.head.length}")
