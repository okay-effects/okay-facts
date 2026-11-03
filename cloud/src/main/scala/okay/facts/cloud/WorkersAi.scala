package okay.facts.cloud

import okay.effects.json.Json
import okay.llm.{Ask, Finished, Gen, Llm, Reply, Timing, ToolCall}
import okay.scala3.*
import okay.serverless.cloud.Ai

/**
 * THE WORKERS AI BINDING AS okay-llm's PORT (specs/design.md §11): `Llm` answered by okay-serverless' `Ai` — a chat
 * (`messages`, `tools`, `max_tokens`, `temperature`, `response_format`) run by the model named, its answer read in
 * whichever shape the model gives (OpenAI's `choices`, Workers AI's `response` and `tool_calls`, the Responses API's
 * `output`). The binding answers whole: one piece, then the end. A failure is not thrown: the reply's reasoning says
 * `error: …` and the turn tells the user the assistant is unavailable
 */
object WorkersAi:
  /** the model's input for an ask: the chat's shape (gpt-oss: the Responses shape, its instructions apart) */
  def input(model: String, a: Ask): Json =
    if model.contains("gpt-oss") then
      val (system, rest) = a.messages.partition(_("role") == Json.Str("system"))
      Json.Obj(Vector("instructions" -> Json.str(system.map(_("content")).collect { case Json.Str(s) => s }
        .mkString("\n\n")), "input" -> Json.Arr(rest.map(responsesItem)), "max_output_tokens" ->
        Json.num(a.maxTokens), "reasoning" -> Json.obj("effort" -> Json.str("low"))) ++
        (if a.tools.isEmpty then Vector.empty else Vector("tools" -> Json.Arr(a.tools.map(t => t.field("function")
          .collect { case Json.Obj(fs) => Json.Obj(("type" -> Json.str("function")) +: fs) }.getOrElse(t))))) ++
        a.format.flatMap(_.field("json_schema")).map(s => "text" -> Json.obj("format" -> Json.Obj(
          ("type" -> Json.str("json_schema")) +: s.record))))
    else Json.Obj(Vector("messages" -> Json.Arr(a.messages), "max_tokens" -> Json.num(a.maxTokens)) ++
      (if a.tools.isEmpty then Vector.empty else Vector("tools" -> Json.Arr(a.tools))) ++
      a.sampling.temperature.map("temperature" -> Json.num(_)) ++ a.sampling.seed.map("seed" -> Json.num(_)) ++
      a.format.map("response_format" -> _))

  /** a chat message as a Responses item: tool calls and results as function calls and their outputs */
  private def responsesItem(m: Json): Json = m("role") match
    case Json.Str("tool") => Json.obj("type" -> Json.str("function_call_output"),
      "call_id" -> m.field("tool_call_id").getOrElse(Json.str("call")), "output" -> m("content"))
    case Json.Str("assistant") if m.field("tool_calls").isDefined =>
      val c = m("tool_calls").list.head
      Json.obj("type" -> Json.str("function_call"), "call_id" -> c.field("id").getOrElse(Json.str("call")),
        "name" -> c("function")("name"), "arguments" -> c("function")("arguments"))
    case _ => Json.obj("role" -> m("role"), "content" -> m("content"))

  private def text(x: Json): Option[String] = x match
    case Json.Str(s) => Some(s)
    case o: Json.Obj => Some(Json.print(o))
    case _ => None

  private def arguments(x: Json): Json = x match
    case Json.Str(s) => try Json.fromText(s) catch case _: IllegalArgumentException => Json.obj()
    case Json.Null => Json.obj()
    case other => other

  /** a model's answer read, whatever its shape: its text, its tool calls, its reasoning; the tokens it counted */
  def reply(j: Json): (Reply, Int, Int) =
    val usage = j("usage")
    val tokensIn = usage.field("prompt_tokens").orElse(usage.field("input_tokens")).map(_.int).getOrElse(0)
    val tokensOut = usage.field("completion_tokens").orElse(usage.field("output_tokens")).map(_.int).getOrElse(0)
    val choice = j.field("choices").collect { case Json.Arr(cs) if cs.nonEmpty => cs.head("message") }
    val r = choice match
      case Some(m) =>
        Reply(m.field("reasoning_content").orElse(m.field("reasoning")).flatMap(text).getOrElse(""),
          m.field("content").flatMap(text).getOrElse(""),
          m.field("tool_calls").collect { case Json.Arr(cs) => cs.map(c => ToolCall(c("function")("name").string,
            arguments(c("function")("arguments")))) }.getOrElse(Vector.empty))
      case None if j.field("output").isDefined =>
        val items = j("output").list
        Reply(items.filter(_("type") == Json.Str("reasoning")).flatMap(_.field("content").toVector.flatMap(_.list))
          .flatMap(_.field("text")).flatMap(text).mkString,
          items.filter(_("type") == Json.Str("message")).flatMap(_.field("content").toVector.flatMap(_.list))
            .flatMap(_.field("text")).flatMap(text).mkString,
          items.filter(_("type") == Json.Str("function_call")).map(c => ToolCall(c("name").string,
            arguments(c("arguments")))))
      case None =>
        Reply("", j.field("response").flatMap(text).getOrElse(""),
          j.field("tool_calls").collect { case Json.Arr(cs) => cs.map(c => ToolCall(c("name").string,
            arguments(c("arguments")))) }.getOrElse(Vector.empty))
    (r, tokensIn, tokensOut)

  /** `Llm` by the binding: each generation one `Ai` call of `model` (or the one an ask names) */
  def llm[R[_], Rest[_], A](model: String)(p: A ! R)(using Split[Llm, R, Rest], In[Ai, Rest]): A ! Rest =
    val running = scala.collection.mutable.HashMap.empty[Long, (Finished, Boolean)]
    var ids = 0L
    handle(p, Handler.general[Llm, Rest, A, A](pure)([X] => (op: Llm[X], k: X => A ! Rest) => op match
      case Llm.Name() => k(model)
      case Llm.Models() => k(Vector(model))
      case Llm.Count(a) => k(a.messages.map(_.render.length).sum / 4)
      case Llm.Start(a) =>
        val m = a.model.getOrElse(model)
        Ai.attempt[Rest](m, input(m, a)).flatMap { res =>
          ids += 1
          val f = res match
            case Left(why) => Finished(Reply(s"error: $why", "", Vector.empty), Timing(0, 0, 0, 0), byStop = false)
            case Right(j) => j.field("error").flatMap(text) match
              case Some(why) => Finished(Reply(s"error: $why", "", Vector.empty), Timing(0, 0, 0, 0), byStop = false)
              case None =>
                val (r, in, out) = reply(j)
                Finished(r, Timing(in, 0, out, 0), byStop = true)
          running(ids) = (f, false)
          k(Gen(ids))
        }
      case Llm.Next(g) => running.get(g.id) match
        case Some((f, false)) =>
          running(g.id) = (f, true)
          k(Some(f.reply.content).filter(_.nonEmpty))
        case _ => k(None)
      case Llm.Finish(g) => k(running.remove(g.id).map(_._1).getOrElse(Finished(Reply("error: no generation", "",
        Vector.empty), Timing(0, 0, 0, 0), byStop = false)))))
