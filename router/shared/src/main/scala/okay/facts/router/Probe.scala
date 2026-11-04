package okay.facts.router

import okay.effects.json.Json

/**
 * THE PROBE (okay's `Probe`, specs/design.md §17): multinomial logistic regression over the table's normalised
 * vectors, by plain gradient descent — weights from zero, the rows in order, so a fit is deterministic. Fitted at
 * BUILD time (`tools/fit.scala`); a host carries the weights, classes × dimensions
 */
final case class Probe(classes: Vector[String], w: Vector[Array[Double]], b: Array[Double]):
  /** each class's probability, best first */
  def ranked(v: Array[Float]): Vector[(String, Double)] =
    val p = probabilities(v)
    classes.indices.map(i => (classes(i), p(i))).sortBy(-_._2).toVector

  def probabilities(v: Array[Float]): Array[Double] =
    val z = Array.tabulate(classes.length) { c =>
      var s = b(c)
      var i = 0
      val row = w(c)
      while i < v.length do { s += row(i) * v(i); i += 1 }
      s
    }
    Probe.softmax(z)

object Probe:
  def softmax(z: Array[Double]): Array[Double] =
    val m = z.max
    val e = z.map(x => math.exp(x - m))
    val s = e.sum
    if s == 0.0 then e else e.map(_ / s)

  /** okay's `Probe.train`, its defaults: 300 epochs, rate 0.5 */
  def train(rows: Vector[(Array[Float], String)], epochs: Int = 300, rate: Double = 0.5): Probe =
    val classes = rows.map(_._2).distinct.sorted
    val dim = rows.headOption.map(_._1.length).getOrElse(0)
    val p = Probe(classes, classes.map(_ => new Array[Double](dim)), new Array[Double](classes.length))
    val indexed = rows.map((v, c) => (v, classes.indexOf(c)))
    var epoch = 0
    while epoch < epochs do
      for (u, target) <- indexed do
        val probs = p.probabilities(u)
        var c = 0
        while c < classes.length do
          val err = probs(c) - (if c == target then 1.0 else 0.0)
          val row = p.w(c)
          var i = 0
          while i < dim do { row(i) = row(i) - rate * err * u(i); i += 1 }
          p.b(c) = p.b(c) - rate * err
          c += 1
      epoch += 1
    p

  /** the weights as JSON, rounded to 6 decimals (the file a host carries) */
  def toJson(p: Probe): Json =
    def r(d: Double): Json = Json.Num(BigDecimal(d).setScale(6, BigDecimal.RoundingMode.HALF_EVEN))
    Json.obj("classes" -> Json.Arr(p.classes.map(Json.str)), "w" -> Json.Arr(p.w.map(row => Json.Arr(row.toVector
      .map(r)))), "b" -> Json.Arr(p.b.toVector.map(r)))

  def fromJson(j: Json): Probe = Probe(j("classes").list.map(_.string),
    j("w").list.map(_.list.map(_.double).toArray), j("b").list.map(_.double).toArray)

  /** the weights as bytes (`Weights`): the file a host carries since round 3 */
  def toBytes(p: Probe): Array[Byte] = Weights.write(Json.obj("kind" -> Json.str("probe"), "classes" ->
    Json.Arr(p.classes.map(Json.str))), Vector("w" -> p.w, "b" -> Vector(p.b)))

  def fromBytes(bytes: Array[Byte]): Probe =
    val (h, a) = Weights.read(bytes)
    Probe(h("classes").list.map(_.string), a("w"), a("b").head)
