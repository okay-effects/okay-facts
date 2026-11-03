package okay.facts.cloud

import okay.effects.json.Json
import okay.facts.*
import okay.facts.tck.Demo
import okay.llm.Llm
import okay.scala3.*
import okay.scala3.std.Clock
import okay.serverless.cloud.Ai

/** the Workers AI port on answers of the shapes the binding gives (as recorded from the models, 2026-11-03) */
class WorkersAiSuite extends munit.FunSuite:
  type B[X] = (Llm + Ai + Facts + Clock + Audit + Emit)[X]

  /** `Ai` answered in order from fixed answers; each input kept */
  def answers[R[_], Rest[_], A](out: Vector[Either[String, Json]], seen: scala.collection.mutable.Buffer[Json])(
    p: A ! R)(using Split[Ai, R, Rest]): A ! Rest =
    var i = -1
    handle(p, Handler.tail[Ai, Rest, A, A](pure)([X] => (op: Ai[X]) => op match
      case Ai.Run(_, input) =>
        seen += input
        i += 1
        out(i).getOrElse(Json.obj())
      case Ai.Attempt(_, input) =>
        seen += input
        i += 1
        out(i)))

  /** OpenAI's shape, as llama-3.3-70b answers a tool call */
  def choiceCall(name: String, args: String): Json = Json.obj("choices" -> Json.arr(Json.obj("message" -> Json.obj(
    "role" -> Json.str("assistant"), "content" -> Json.Null, "tool_calls" -> Json.arr(Json.obj("id" -> Json.str("x"),
      "type" -> Json.str("function"), "function" -> Json.obj("name" -> Json.str(name),
        "arguments" -> Json.str(args))))))), "usage" -> Json.obj("prompt_tokens" -> Json.num(200),
    "completion_tokens" -> Json.num(20)))

  test("a dialogue through the binding: tool calls in OpenAI's shape, the answer as JSON mode's object"):
    val seen = scala.collection.mutable.Buffer.empty[Json]
    val out = Vector(Right(choiceCall("find", """{"class": "Service", "text": "strzyżenie broda"}""")),
      Right(Json.obj("response" -> Json.str("Let me answer."))),
      Right(Json.obj("response" -> Json.obj("params" -> Json.obj("p" -> Json.str("price(#v1)")),
        "template" -> Json.str("Kosztuje {p}.")), "usage" -> Json.obj("prompt_tokens" -> Json.num(300),
        "completion_tokens" -> Json.num(15)))))
    val s = Setup(Demo.schema, "A fictional barber's assistant.", Level.L3)
    val program = Emit.none(Audit.ignore(Bind.turn[B](s, Conversation(), "Ile?", Lang.pl, "shop:barber-demo")))
    val (_, (_, r)) = run(Demo.facts(Some("barber-demo"))(Clock.virtual(Demo.now)(answers(out, seen)(
      WorkersAi.llm("@cf/meta/llama-3.3-70b-instruct-fp8-fast")(program)))))
    assertEquals(r.rendered.map(_.text), Some("Kosztuje 90,00 zł."))
    assertEquals(r.tokens, 535)
    assert(seen.head.field("tools").isDefined)
    assertEquals(seen(2).field("response_format").map(_("type")), Some(Json.str("json_schema")))
    assertEquals(seen(2).field("tools"), None)
    assertEquals(seen(2)("messages").list.map(_("role").string).takeRight(3), Vector("assistant", "tool", "user"))

  test("a failed model: the notice, no answer invented"):
    val s = Setup(Demo.schema, "A fictional barber's assistant.", Level.L3)
    val program = Emit.none(Audit.ignore(Bind.turn[B](s, Conversation(), "Ile?", Lang.uk, "mall")))
    val (_, (_, r)) = run(Demo.facts(None)(Clock.virtual(Demo.now)(answers(Vector(Left("capacity")),
      scala.collection.mutable.Buffer.empty)(WorkersAi.llm("m")(program)))))
    assertEquals(r.rendered, None)
    assertEquals(r.failure, Some("capacity"))
    assertEquals(r.notice, Some(Bind.notice("unavailable", Lang.uk)))

  test("the shapes read: Workers AI's own, the Responses API's"):
    val native = Json.obj("response" -> Json.Null, "tool_calls" -> Json.arr(Json.obj("name" -> Json.str("find"),
      "arguments" -> Json.obj("class" -> Json.str("Slot")))))
    assertEquals(WorkersAi.reply(native)._1.toolCalls.map(_.name), Vector("find"))
    val responses = Json.obj("output" -> Json.arr(Json.obj("type" -> Json.str("reasoning"), "content" -> Json.arr(
      Json.obj("text" -> Json.str("hm")))), Json.obj("type" -> Json.str("message"), "content" -> Json.arr(Json.obj(
      "type" -> Json.str("output_text"), "text" -> Json.str("{\"params\":{},\"template\":\"Hej\"}"))))),
      "usage" -> Json.obj("input_tokens" -> Json.num(5), "output_tokens" -> Json.num(7)))
    val (r, in, outT) = WorkersAi.reply(responses)
    assertEquals((r.reasoning, r.content, in, outT), ("hm", "{\"params\":{},\"template\":\"Hej\"}", 5, 7))


  test("the model's per-minute limit: asked again after a pause, then answered"):
    val s = Setup(Demo.schema, "A fictional barber's assistant.", Level.L3)
    val program = Emit.none(Audit.ignore(Bind.turn[B](s, Conversation(), "Hej", Lang.pl, "mall")))
    val out = Vector(Left("AiError: 3021: rate limiting: inference request per min rate reached"),
      Right(Json.obj("response" -> Json.obj("params" -> Json.obj(), "template" -> Json.str("Cześć!")))))
    val (waited, (_, r)) = run(Demo.facts(None)(Clock.virtual(Demo.now)(answers(out,
      scala.collection.mutable.Buffer.empty)(WorkersAi.llm("m")(program)))))
    assertEquals(r.rendered.map(_.text), Some("Cześć!"))
    assertEquals(waited - Demo.now, 2000L)
