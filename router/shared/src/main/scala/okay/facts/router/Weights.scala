package okay.facts.router

import okay.effects.json.Json

import java.nio.{ByteBuffer, ByteOrder}

/**
 * WEIGHTS AS BYTES (specs/design.md §17 "The probe"): `OKW1`, a 4-byte little-endian length, a small JSON header —
 * the host's fields and `arrays: [{name, rows, cols}]` —, then each array's values as little-endian F32, row after
 * row, in the header's order. Read in well under a millisecond where the JSON of the same numbers took 10.7 ms
 */
object Weights:
  private val magic = "OKW1".getBytes("US-ASCII")

  def write(header: Json, arrays: Vector[(String, Vector[Array[Double]])]): Array[Byte] =
    val head = Json.Obj(header match
      case Json.Obj(fs) => fs.filterNot(_._1 == "arrays")
      case _ => Vector.empty) match
      case Json.Obj(fs) => Json.Obj(fs :+ ("arrays" -> Json.Arr(arrays.map((n, rows) => Json.obj("name" -> Json.str(n),
        "rows" -> Json.num(rows.size), "cols" -> Json.num(rows.headOption.map(_.length).getOrElse(0)))))))
    val h = Json.print(head).getBytes("UTF-8")
    val n = arrays.map(_._2.map(_.length).sum).sum
    val b = ByteBuffer.allocate(8 + h.length + 4 * n).order(ByteOrder.LITTLE_ENDIAN)
    b.put(magic).putInt(h.length).put(h)
    for (_, rows) <- arrays; row <- rows; x <- row do b.putFloat(x.toFloat)
    b.array()

  /** the header and the arrays by name (rows × cols) */
  def read(bytes: Array[Byte]): (Json, Map[String, Vector[Array[Double]]]) =
    val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val m = new Array[Byte](4)
    b.get(m)
    require(m.sameElements(magic), "not OKW1 weights")
    val h = new Array[Byte](b.getInt())
    b.get(h)
    val head = Json.fromText(new String(h, "UTF-8"))
    val arrays = head("arrays").list.map { a =>
      val (rows, cols) = (a("rows").int, a("cols").int)
      a("name").string -> Vector.fill(rows)(Array.fill(cols)(b.getFloat().toDouble))
    }.toMap
    (head, arrays)
