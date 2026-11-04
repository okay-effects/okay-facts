package okay.facts.router

import okay.effects.json.Json

/**
 * THE COMBINER (specs/design.md §17 "The turn decision"): multinomial logistic regression over a turn's evidence —
 * the probe's probabilities, the rules' hits, a follow-up's previous intent, the host's named evidence —, its scores
 * divided by a temperature: a calibrated probability per intent. The threshold an answer needs is the file's, chosen
 * out of fold for the router's precision (§16). Fitted at build time by full-batch gradient descent with L2, from
 * zero — deterministic
 */
final case class Combiner(classes: Vector[String], features: Vector[String], w: Vector[Array[Double]],
                          b: Array[Double], temperature: Double = 1.0, threshold: Double = 0.5):
  def scores(x: Array[Double]): Array[Double] = Array.tabulate(classes.length) { c =>
    var s = b(c)
    val row = w(c)
    var i = 0
    while i < x.length do { s += row(i) * x(i); i += 1 }
    s
  }

  def probabilities(x: Array[Double], t: Double = temperature): Array[Double] = Probe.softmax(scores(x).map(_ / t))

  def ranked(x: Array[Double]): Vector[(String, Double)] =
    val p = probabilities(x)
    classes.indices.map(i => (classes(i), p(i))).sortBy(-_._2).toVector

object Combiner:
  /** fitted on rows (evidence, class index, weight): the mean weighted log-loss plus `l2` times the squared weights */
  def train(classes: Vector[String], features: Vector[String], rows: Vector[(Array[Double], Int, Double)],
            l2: Double = 1e-3, epochs: Int = 1500, rate: Double = 0.5): Combiner =
    val d = features.length
    val k = classes.length
    val w = Vector.fill(k)(new Array[Double](d))
    val b = new Array[Double](k)
    val total = math.max(rows.map(_._3).sum, 1e-9)
    var e = 0
    while e < epochs do
      val gw = Vector.fill(k)(new Array[Double](d))
      val gb = new Array[Double](k)
      val c0 = Combiner(classes, features, w, b)
      for (x, y, wt) <- rows do
        val p = c0.probabilities(x, 1.0)
        var c = 0
        while c < k do
          val err = (p(c) - (if c == y then 1.0 else 0.0)) * wt / total
          val g = gw(c)
          var i = 0
          while i < d do { g(i) += err * x(i); i += 1 }
          gb(c) += err
          c += 1
      var c = 0
      while c < k do
        var i = 0
        while i < d do { w(c)(i) -= rate * (gw(c)(i) + 2 * l2 * w(c)(i)); i += 1 }
        b(c) -= rate * gb(c)
        c += 1
      e += 1
    Combiner(classes, features, w, b)

  /** the temperature of least log-loss over (evidence, class) rows, on a grid */
  def temperature(c: Combiner, rows: Vector[(Array[Double], Int)]): Double =
    val grid = (2 to 60).map(_ / 10.0)
    grid.minBy(t => rows.map((x, y) => -math.log(math.max(c.probabilities(x, t)(y), 1e-12))).sum)

  def toBytes(c: Combiner): Array[Byte] =
    Weights.write(Json.obj("kind" -> Json.str("combiner"), "classes" -> Json.Arr(c.classes.map(Json.str)),
      "features" -> Json.Arr(c.features.map(Json.str)), "temperature" -> Json.num(c.temperature),
      "threshold" -> Json.num(c.threshold)), Vector("w" -> c.w, "b" -> Vector(c.b)))

  def fromBytes(bytes: Array[Byte]): Combiner =
    val (h, a) = Weights.read(bytes)
    Combiner(h("classes").list.map(_.string), h("features").list.map(_.string), a("w"), a("b").head,
      h("temperature").double, h("threshold").double)
