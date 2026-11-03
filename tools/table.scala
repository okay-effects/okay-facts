//> using scala 3.3.8
//> using jvm 21
//> using repository https://okay-effects.github.io/okay-simple/maven
//> using repository https://okay-effects.github.io/okay-scala/maven
//> using repository https://okay-effects.github.io/okay-llm/maven
//> using repository https://okay-effects.github.io/okay-facts/maven
//> using dep io.github.okay-effects::okay-effects-json-value:0.1.1
//> using dep com.microsoft.onnxruntime:onnxruntime:1.30.0
//> using dep ai.djl.huggingface:tokenizers:0.36.0
//> using file ../router/shared/src/main/scala/okay/facts/router/Table.scala

/**
 * THE STATIC TABLE, DISTILLED (okay-facts specs/design.md §17): every unit of a corpus — the words, pairs and triples
 * of the intents' examples, the words of vocabularies and word lists — embedded ONCE by the sentence encoder
 * (paraphrase-multilingual-MiniLM-L12-v2 through onnxruntime on the JVM: mean over the positions, unit length), cut by
 * PCA to `--dim`, weighted by SIF over the corpus' frequencies, written as safetensors (vectors in half precision).
 * The encodings are cached (`--cache`), so another cut is a second's work.
 *
 *   scala-cli run tools/table.scala -- --model <dir with model.onnx, tokenizer.json> --out <file.safetensors>
 *     [--intents <intents.json>]… [--words <file>]… [--massive <dir> [--massive-min 3]] [--dim 256] [--cache <file>]
 */
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.{OnnxTensor, OrtEnvironment, OrtSession}
import okay.effects.json.Json
import okay.facts.router.Table

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

@main def table(args: String*): Unit =
  def all(flag: String): Vector[String] = args.sliding(2).collect { case Seq(`flag`, v) => v }.toVector
  def one(flag: String): Option[String] = all(flag).lastOption
  val model = Paths.get(one("--model").getOrElse(sys.error("--model <dir>")))
  val out = Paths.get(one("--out").getOrElse(sys.error("--out <file>")))
  val dim = one("--dim").map(_.toInt).getOrElse(256)
  val massiveMin = one("--massive-min").map(_.toInt).getOrElse(3)

  // ---- the corpus: texts whose units go in (examples: words, pairs, triples), word lists (words only)
  val examples = all("--intents").flatMap { f =>
    Json.fromText(Files.readString(Paths.get(f)))("intents").list.flatMap(i => i.field("examples").toVector.flatMap(
      _.record.flatMap(_._2.list.map(_.string))))
  }
  val vocabulary = all("--words").flatMap(f => Files.readAllLines(Paths.get(f)).asScala.toVector
    .filterNot(_.trim.startsWith("#")))
  val massive = one("--massive").toVector.flatMap { dir =>
    Vector("pl-PL", "ru-RU", "en-US").flatMap(loc => Files.readAllLines(Paths.get(dir, s"$loc.jsonl")).asScala
      .toVector.map(l => Json.fromText(l)("utt").string))
  }
  val massiveWords = massive.flatMap(Table.tokens).groupBy(identity).collect { case (w, n) if n.size >= massiveMin &&
    !w.exists(_.isDigit) => w }.toVector
  val units = (examples.flatMap(Table.units3) ++ vocabulary.flatMap(Table.tokens) ++ massiveWords)
    .filter(u => u.nonEmpty && u.length <= 60).distinct.sorted
  println(s"units: ${units.size} (examples ${examples.size}, vocabulary lines ${vocabulary.size}, " +
    s"massive words ${massiveWords.size})")

  // ---- the encodings, cached by unit
  val cache = one("--cache").map(Paths.get(_))
  val known: Map[String, Array[Float]] = cache.filter(Files.exists(_)).fold(Map.empty)(read)
  val missing = units.filterNot(known.contains)
  val fresh = if missing.isEmpty then Map.empty[String, Array[Float]] else encodeAll(model, missing)
  val vectors = known ++ fresh
  cache.foreach(c => if fresh.nonEmpty then write(c, vectors))

  // ---- PCA to `dim`, SIF weights over the corpus, the file
  val rows = units.map(vectors)
  val (mean, basis) = pca(rows, dim)
  val projected = rows.map(project(mean, basis, _))
  val corpus = examples ++ vocabulary ++ massive
  val counts = corpus.flatMap(Table.units3).groupBy(identity).view.mapValues(_.size.toDouble).toMap
  val total = math.max(counts.values.sum, 1.0)
  val weights = units.map(u => 1e-3 / (1e-3 + counts.getOrElse(u, 1.0) / total))
  val kept = variance(rows, mean, basis)
  Files.write(out, Table.write(units, projected, weights, Map("encoder" -> "paraphrase-multilingual-MiniLM-L12-v2",
    "format" -> "1", "count" -> units.size.toString, "pca" -> dim.toString, "variance" -> f"$kept%.3f")))
  println(s"written $out: ${units.size} units × $dim, ${Files.size(out)} bytes, variance kept ${"%.3f".format(kept)}")

/** every text through the encoder: mean over the positions, unit length (okay-onnx's `Encoder`) */
def encodeAll(model: Path, texts: Vector[String]): Map[String, Array[Float]] =
  val tokenizer = HuggingFaceTokenizer.newInstance(model.resolve("tokenizer.json"),
    Map("truncation" -> "true", "maxLength" -> "128").asJava)
  val env = OrtEnvironment.getEnvironment
  val session = env.createSession(model.resolve("model.onnx").toString, new OrtSession.SessionOptions)
  val started = System.nanoTime()
  val out = texts.zipWithIndex.map { (t, i) =>
    if i % 2000 == 0 then println(s"  encoding $i / ${texts.size}")
    val enc = tokenizer.encode(t)
    val in = Map("input_ids" -> OnnxTensor.createTensor(env, Array(enc.getIds)),
      "attention_mask" -> OnnxTensor.createTensor(env, Array(enc.getAttentionMask)),
      "token_type_ids" -> OnnxTensor.createTensor(env, Array(enc.getTypeIds))).asJava
    val res = session.run(in)
    try
      val h = res.get(0).getValue.asInstanceOf[Array[Array[Array[Float]]]](0)
      val acc = new Array[Float](h(0).length)
      for r <- h; j <- r.indices do acc(j) += r(j)
      t -> Table.normalised(acc.map(_ / h.length))
    finally
      res.close()
      in.values.forEach(_.close())
  }.toMap
  println(f"  encoded ${texts.size} in ${(System.nanoTime() - started) / 1e9}%.1f s")
  session.close()
  out

/** okay's `Static.fitPca`: the covariance, subspace iteration from a fixed seed */
def pca(rows: Vector[Array[Float]], k: Int, sweeps: Int = 40): (Array[Double], Array[Array[Double]]) =
  val n = rows.size
  val d = rows.head.length
  val mean = Array.tabulate(d)(j => rows.iterator.map(_(j).toDouble).sum / n)
  val cov = Array.ofDim[Double](d, d)
  val c = new Array[Double](d)
  for r <- rows do
    var j = 0
    while j < d do { c(j) = r(j) - mean(j); j += 1 }
    var a = 0
    while a < d do
      val ca = c(a)
      val row = cov(a)
      var b = 0
      while b < d do { row(b) += ca * c(b); b += 1 }
      a += 1
  val rnd = new scala.util.Random(7)
  def orth(m: Array[Array[Double]]): Array[Array[Double]] =
    val o = new Array[Array[Double]](m.length)
    for i <- m.indices do
      val v = m(i).clone()
      for p <- 0 until i do
        val u = o(p)
        val dot = (0 until d).map(j => v(j) * u(j)).sum
        for j <- 0 until d do v(j) -= dot * u(j)
      val norm = math.sqrt(v.map(x => x * x).sum)
      o(i) = v.map(x => if norm > 0 then x / norm else 0.0)
    o
  var q = orth(Array.fill(math.min(k, d))(Array.fill(d)(rnd.nextGaussian())))
  for _ <- 1 to sweeps do
    q = orth(q.map(v => Array.tabulate(d)(a => (0 until d).map(b => cov(a)(b) * v(b)).sum)))
  (mean, q)

def project(mean: Array[Double], basis: Array[Array[Double]], v: Array[Float]): Array[Float] =
  basis.map(u => (0 until v.length).map(j => (v(j) - mean(j)) * u(j)).sum.toFloat)

def variance(rows: Vector[Array[Float]], mean: Array[Double], basis: Array[Array[Double]]): Double =
  val total = rows.map(v => v.indices.map(j => { val c = v(j) - mean(j); c * c }).sum).sum
  val inSub = rows.map(v => project(mean, basis, v).map(x => x.toDouble * x).sum).sum
  if total > 0 then inSub / total else 0.0

/** the cache: `unit \t v1 v2 …` a line */
def read(p: Path): Map[String, Array[Float]] = Files.readAllLines(p).asScala.iterator.map { l =>
  val tab = l.indexOf('\t')
  l.substring(0, tab) -> l.substring(tab + 1).split(' ').map(_.toFloat)
}.toMap

def write(p: Path, m: Map[String, Array[Float]]): Unit =
  Files.write(p, m.toVector.sortBy(_._1).map((k, v) => k + "\t" + v.mkString(" ")).asJava)
