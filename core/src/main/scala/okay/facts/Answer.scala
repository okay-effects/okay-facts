package okay.facts

import okay.effects.json.Json

/**
 * THE MODEL'S ANSWER (specs/design.md §7): named parameters bound to expressions — never values — and an ICU message
 * with a placeholder for each. `params` comes first: a model writes fields in the schema's order, so the resolver
 * evaluates them while the template is still being written
 */
final case class Answer(params: Vector[(String, String)], template: String):
  def param(name: String): Option[String] = params.collectFirst { case (n, e) if n == name => e }

object Answer:
  /** the answer as JSON, `params` first */
  def toJson(a: Answer): Json = Json.obj("params" -> Json.Obj(a.params.map((k, v) => k -> Json.str(v))),
    "template" -> Json.str(a.template))

  /** THE L3 PATH: a JSON object `{params, template}` — whole, in a code fence, or inside other text */
  def fromJson(text: String): Either[String, Answer] =
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if start < 0 || end <= start then Left("no JSON object")
    else
      try fromValue(Json.fromText(text.substring(start, end + 1)))
      catch case e: IllegalArgumentException => Left(s"not JSON: ${e.getMessage}")

  def fromValue(j: Json): Either[String, Answer] = j match
    case o: Json.Obj if o.has("template") =>
      val params = o.get("params") match
        case Some(Json.Obj(fs)) => fs.map((k, v) => k -> (v match
          case Json.Str(s) => s
          case other => Json.print(other)))
        case _ => Vector.empty
      o.get("template") match
        case Some(Json.Str(t)) => Right(Answer(params, t))
        case _ => Left("the template is not a text")
    case _ => Left("no 'template'")

  val open = "⟦"
  val close = "⟧"

  /**
   * THE L0 PATH: a text with inline markers `⟦price(#v1)⟧` (or `[[price(#v1)]]`, as a model may type them) — each a
   * parameter (`p1`, `p2`, …; one expression one parameter), the rest the template's text, quoted for ICU. The same
   * `{params, template}` the JSON path gives, so the two render alike
   */
  def fromMarkers(text: String): Answer =
    val params = Vector.newBuilder[(String, String)]
    val seen = scala.collection.mutable.LinkedHashMap.empty[String, String]
    val template = new StringBuilder
    var i = 0
    while i < text.length do
      val (o, c) = if text.startsWith(open, i) then (open, close)
        else if text.startsWith("[[", i) then ("[[", "]]") else ("", "")
      val end = if o.isEmpty then -1 else text.indexOf(c, i + o.length)
      if end > 0 then
        val expr = text.substring(i + o.length, end).trim
        val name = seen.getOrElseUpdate(expr, {
          val n = s"p${seen.size + 1}"
          params += n -> expr
          n
        })
        template ++= "{" + name + "}"
        i = end + c.length
      else
        val next = Seq(text.indexOf(open, i + 1), text.indexOf("[[", i + 1)).filter(_ > 0).minOption
          .getOrElse(text.length)
        template ++= Icu.quote(text.substring(i, next))
        i = next
    Answer(params.result(), template.toString)

  /** whether a text carries markers (an L0 answer) */
  def marked(text: String): Boolean =
    text.contains(open) || """\[\[[^\]]*#[a-z]+[0-9]+[^\]]*\]\]""".r.findFirstIn(text).isDefined
