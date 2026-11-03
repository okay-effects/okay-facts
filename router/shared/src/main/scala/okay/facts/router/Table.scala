package okay.facts.router

import java.nio.charset.StandardCharsets.UTF_8
import okay.effects.json.Json

/**
 * THE STATIC TABLE (specs/design.md §17): a vector per unit — a word, two or three adjacent words — distilled from a
 * sentence encoder (paraphrase-multilingual-MiniLM-L12-v2, okay's `Static`), cut by PCA, half precision, with a SIF
 * weight. A text's vector is its known units' weighted mean, normalised: a lookup and a sum, no inference.
 *
 * A safetensors file read from its bytes IN PLACE: `vecs` (F16 `[n, dim]`) stays where it lies, a row decoded when a
 * text uses it; `labels.offsets` (U64) and `labels.bytes` (U8) the units; `weights` (F64)
 */
final class Table private (bytes: Array[Byte], vecsAt: Int, weightsAt: Int, offsAt: Int, labelsAt: Int, n: Int,
                           labels: Option[java.util.HashMap[String, Integer]], val dim: Int,
                           val meta: Map[String, String]):
  def size: Int = n
  def has(unit: String): Boolean = row(unit).isDefined

  private inline def u16(at: Int): Int = (bytes(at) & 0xff) | ((bytes(at + 1) & 0xff) << 8)

  private def weight(row: Int): Double = java.lang.Double.longBitsToDouble(Table.le64(bytes, weightsAt + 8 * row))

  /** a unit's vector, or none */
  def vector(unit: String): Option[Array[Float]] = row(unit).map { r =>
    val v = new Array[Float](dim)
    add(r, 1.0, v)
    Table.normalised(v)
  }

  /** a unit's row: by binary search over the labels' bytes where they lie (a file's labels in order — the writer's),
   * else by a map made at the start */
  private def row(unit: String): Option[Int] = labels match
    case Some(m) =>
      val r = m.get(unit)
      if r == null then None else Some(r.intValue)
    case None =>
      val key = unit.getBytes(UTF_8)
      var lo = 0
      var hi = n - 1
      var found = -1
      while found < 0 && lo <= hi do
        val mid = (lo + hi) >>> 1
        val c = Table.compare(key, bytes, labelsAt + start(mid), labelsAt + start(mid + 1))
        if c == 0 then found = mid else if c < 0 then hi = mid - 1 else lo = mid + 1
      Option.when(found >= 0)(found)

  private inline def start(i: Int): Int = Table.le64(bytes, offsAt + 8 * i).toInt

  private def add(row: Int, w: Double, acc: Array[Float]): Unit =
    val at = vecsAt + row * dim * 2
    var i = 0
    while i < dim do
      acc(i) = acc(i) + (Table.half(u16(at + 2 * i)) * w).toFloat
      i += 1

  /** a text's vector: its units' weighted mean, normalised — or none, no unit of it known */
  def encode(text: String): Option[Array[Float]] = encodeUnits(Table.units3(text))

  def encodeUnits(units: Vector[String]): Option[Array[Float]] =
    val acc = new Array[Float](dim)
    var known = 0
    for u <- units do
      row(u) match
        case Some(r) =>
          add(r, weight(r), acc)
          known += 1
        case None =>
    if known == 0 then None else Some(Table.normalised(acc))

  /** the share of a text's words the table knows */
  def coverage(text: String): Double =
    val ws = Table.tokens(text)
    if ws.isEmpty then 1.0 else ws.count(has).toDouble / ws.size

object Table:
  /** the table from a safetensors file's bytes (kept, not copied) */
  def apply(bytes: Array[Byte]): Table =
    val headerLen = le64(bytes, 0).toInt
    val header = Json.fromText(new String(bytes, 8, headerLen, UTF_8))
    val base = 8 + headerLen
    def range(name: String): (Int, Int) =
      val offs = header(name)("data_offsets").list.map(_.long.toInt)
      (base + offs(0), base + offs(1))
    val meta = header.field("__metadata__").collect { case Json.Obj(fs) => fs.collect { case (k, Json.Str(v)) =>
      k -> v }.toMap }.getOrElse(Map.empty)
    val (va, _) = range("vecs")
    val (wa, _) = range("weights")
    val (oa, ob) = range("labels.offsets")
    val (la, _) = range("labels.bytes")
    val n = (ob - oa) / 8 - 1
    def at(i: Int) = la + le64(bytes, oa + 8 * i).toInt
    // the labels in their bytes' order (the writer's): read where they lie; else a map, made once
    var sorted = true
    var i = 1
    while sorted && i < n do
      sorted = compareIn(bytes, at(i - 1), at(i), at(i), at(i + 1)) < 0
      i += 1
    val labels = Option.when(!sorted) {
      val m = new java.util.HashMap[String, Integer](n * 2)
      var j = 0
      while j < n do
        m.put(new String(bytes, at(j), at(j + 1) - at(j), UTF_8), j)
        j += 1
      m
    }
    val dim = meta.get("dim").flatMap(_.toIntOption).getOrElse(header("vecs")("shape").list(1).int)
    new Table(bytes, va, wa, oa, la, n, labels, dim, meta)

  /** a key's bytes against bytes `[from, until)` of `b`, unsigned, lexicographic */
  def compare(key: Array[Byte], b: Array[Byte], from: Int, until: Int): Int =
    val len = until - from
    var i = 0
    var c = 0
    while c == 0 && i < key.length && i < len do
      c = (key(i) & 0xff) - (b(from + i) & 0xff)
      i += 1
    if c != 0 then c else key.length - len

  private def compareIn(b: Array[Byte], a0: Int, a1: Int, b0: Int, b1: Int): Int =
    var i = 0
    var c = 0
    while c == 0 && a0 + i < a1 && b0 + i < b1 do
      c = (b(a0 + i) & 0xff) - (b(b0 + i) & 0xff)
      i += 1
    if c != 0 then c else (a1 - a0) - (b1 - b0)

  /** a table's safetensors file: the units, their vectors (each `dim` long, kept in half precision), their weights,
   * the metadata (`dim` added) — the build's writer (`tools/table.scala`), and tests' */
  def write(units: Vector[String], vecs: Vector[Array[Float]], weights: Vector[Double],
            meta: Map[String, String]): Array[Byte] =
    val dim = vecs.headOption.map(_.length).getOrElse(0)
    val bs = units.map(_.getBytes(UTF_8))
    val order = units.indices.toVector.sortWith((x, y) => compare(bs(x), bs(y), 0, bs(y).length) < 0)
    // the units in their bytes' order: a reader searches them where they lie
    val labels = order.map(bs)
    val vecBytes = units.size * dim * 2
    val offBytes = (units.size + 1) * 8
    val labBytes = labels.map(_.length).sum
    val wBytes = units.size * 8
    // the weights 8-aligned after the labels' bytes: padded with spaces in the header
    def json(pad: Int): String =
      val a = vecBytes; val b = a + offBytes; val c = b + labBytes; val d = c + wBytes
      val m = (meta + ("dim" -> dim.toString)).toVector.sortBy(_._1).map((k, v) => s"\"$k\":\"$v\"").mkString(",")
      s"""{"__metadata__":{$m},"vecs":{"dtype":"F16","shape":[${units.size},$dim],"data_offsets":[0,$a]},""" +
        s""""labels.offsets":{"dtype":"U64","shape":[${units.size + 1}],"data_offsets":[$a,$b]},""" +
        s""""labels.bytes":{"dtype":"U8","shape":[$labBytes],"data_offsets":[$b,$c]},""" +
        s""""weights":{"dtype":"F64","shape":[${units.size}],"data_offsets":[$c,$d]}}""" + " " * pad
    val bare = json(0).getBytes(UTF_8).length
    val pad = Math.floorMod(-(8 + bare), 8)
    val header = json(pad).getBytes(UTF_8)
    val out = java.io.ByteArrayOutputStream()
    def u64(n: Long): Unit = (0 until 8).foreach(i => out.write(((n >>> (8 * i)) & 0xff).toInt))
    u64(header.length)
    out.write(header)
    for v <- order.map(vecs); x <- v do { val h = toHalf(x); out.write(h & 0xff); out.write((h >>> 8) & 0xff) }
    var at = 0L
    u64(0)
    for l <- labels do { at += l.length; u64(at) }
    labels.foreach(out.write)
    for w <- order.map(weights) do u64(java.lang.Double.doubleToLongBits(w))
    out.toByteArray

  def le64(b: Array[Byte], at: Int): Long =
    var r = 0L
    var i = 7
    while i >= 0 do
      r = (r << 8) | (b(at + i) & 0xffL)
      i -= 1
    r

  /** half precision to single: all 65 536 values, made once */
  private val halves: Array[Float] =
    val t = new Array[Float](65536)
    var h = 0
    while h < 65536 do
      val s = if (h & 0x8000) != 0 then -1.0 else 1.0
      val e = (h >> 10) & 0x1f
      val f = h & 0x3ff
      t(h) = (
        if e == 0 then s * math.pow(2, -14) * (f / 1024.0)
        else if e == 31 then (if f == 0 then s * Double.PositiveInfinity else Double.NaN)
        else s * math.pow(2, e - 15) * (1 + f / 1024.0)).toFloat
      h += 1
    t
  inline def half(h: Int): Float = halves(h)

  /** single precision to half, rounded to nearest (the table's writer) */
  def toHalf(x: Float): Int =
    val bits = java.lang.Float.floatToIntBits(x)
    val sign = (bits >>> 16) & 0x8000
    val e = ((bits >>> 23) & 0xff) - 127 + 15
    val m = bits & 0x7fffff
    if e <= 0 then
      if e < -10 then sign
      else
        val mm = (m | 0x800000) >> (1 - e)
        sign | ((mm + 0x1000) >> 13)
    else if e >= 31 then sign | 0x7c00
    else
      val r = sign | (e << 10) | (m >> 13)
      if (m & 0x1000) != 0 then r + 1 else r

  /** okay's `Static.tokens`: lower case, the runs of letters, digits and apostrophes */
  def tokens(text: String): Vector[String] =
    val out = Vector.newBuilder[String]
    val b = new StringBuilder
    for c <- text.toLowerCase do
      if Character.isLetterOrDigit(c) || c == '\'' || c == '’' || c == 'ʼ' then b += c
      else if b.nonEmpty then { out += b.toString; b.clear() }
    if b.nonEmpty then out += b.toString
    out.result()

  /** okay's `Static.units3`: the words, their adjacent pairs and triples */
  def units3(text: String): Vector[String] = units(tokens(text))

  def units(ws: Vector[String]): Vector[String] =
    ws ++ ws.sliding(2).collect { case Vector(a, b) => s"$a $b" } ++
      ws.sliding(3).collect { case Vector(a, b, c) => s"$a $b $c" }

  def normalised(v: Array[Float]): Array[Float] =
    var s = 0.0
    var i = 0
    while i < v.length do { s += v(i) * v(i); i += 1 }
    val n = math.sqrt(s)
    if n > 0 then
      i = 0
      while i < v.length do { v(i) = (v(i) / n).toFloat; i += 1 }
    v

  def dot(a: Array[Float], b: Array[Float]): Double =
    var s = 0.0
    var i = 0
    while i < a.length do { s += a(i) * b(i); i += 1 }
    s
