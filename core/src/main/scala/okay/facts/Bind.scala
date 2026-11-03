package okay.facts

import okay.effects.json.Json
import okay.llm.{Ask, Finished, Llm, Sampling, ToolCall}
import okay.scala3.*
import okay.scala3.std.Clock

/** WHAT A MODEL CAN DO (specs/design.md §10), the strongest mode used: L0 text with markers, L1 the same streamed,
 * L2 native tool calls, L3 tool calls and a JSON schema for the answer, L4 a grammar (a local model) */
enum Level:
  case L0, L1, L2, L3, L4
  def tools: Boolean = ordinal >= 2
  def schema: Boolean = ordinal >= 3

/** what a violation does (specs/design.md §9): re-ask naming it (the default), deliver it marked, or answer nothing */
enum Policy:
  case Regenerate, Flag, Block

/** A DEPLOYMENT'S BINDING: its schema, what the assistant is (a sentence the prompt starts with), the model's level,
 * the policy, the class a focus is (a shop), the limits of a turn */
final case class Setup(schema: Schema, about: String, level: Level = Level.L3, policy: Policy = Policy.Regenerate,
                       focusClass: String = "Shop", rounds: Int = 4, retries: Int = 2, maxTokens: Int = 600,
                       temperature: Double = 0.2, keep: Int = 16, stream: Boolean = false,
                       actions: Vector[(String, String, String)] =
                         Vector("open" -> "shop" -> "the shop's page opened for the user (a link card)",
                           "book" -> "slot" -> "a booking card shown: the user confirms it with their name and contact")
                         .map { case ((a, b), c) => (a, b, c) })

/** A CONVERSATION as the binding keeps it: its handles, what the model saw of it (no value ever), its focus */
final case class Conversation(handles: Handles = Handles(), history: Vector[Json] = Vector.empty,
                              focus: String = "mall")

object Conversation:
  def toJson(c: Conversation): Json = Json.obj("handles" -> Handles.toJson(c.handles),
    "history" -> Json.Arr(c.history), "focus" -> Json.str(c.focus))
  def fromJson(j: Json): Conversation = Conversation(Handles.fromJson(j("handles")), j("history") match
    case Json.Arr(xs) => xs
    case _ => Vector.empty, j.field("focus").map(_.string).getOrElse("mall"))

/** A TURN'S OUTCOME: the rendered answer (or the fixed notice when none could be given), the cards to draw, what the
 * validator saw and did, the tokens spent, the model's raw outputs (the debug view: no value in them) */
final case class Reply(rendered: Option[Rendered], notice: Option[String] = None, cards: Vector[Card] = Vector.empty,
                       violations: Vector[Violation] = Vector.empty, regenerations: Int = 0, flagged: Boolean = false,
                       blocked: Boolean = false, tokens: Int = 0, raw: Vector[String] = Vector.empty,
                       failure: Option[String] = None)

/**
 * THE BINDING LAYER (specs/design.md §10): a conversation's turn with the model — it gets handles only, finds by
 * tools (L2+) or a text protocol (L0), answers `{params, template}` (by a JSON schema at L3, markers at L0), the
 * validator decides, the resolver renders. A program of `Llm` (okay-llm's port), `Facts`, `Audit` and `Clock`
 */
object Bind:
  // ---- the model's words for the user, when no answer can be given

  def notice(why: String, l: Lang): String = (why, l) match
    case ("blocked", Lang.pl) => "Nie mogę teraz podać pewnej odpowiedzi. Spróbuj zapytać inaczej."
    case ("blocked", Lang.uk) => "Зараз не можу дати певну відповідь. Спробуйте запитати інакше."
    case ("blocked", Lang.ru) => "Сейчас не могу дать точный ответ. Попробуйте спросить иначе."
    case ("blocked", _) => "I cannot give a reliable answer right now. Please try asking differently."
    case (_, Lang.pl) => "Asystent jest chwilowo niedostępny. Spróbuj za chwilę."
    case (_, Lang.uk) => "Асистент тимчасово недоступний. Спробуйте трохи пізніше."
    case (_, Lang.ru) => "Ассистент временно недоступен. Попробуйте чуть позже."
    case _ => "The assistant is unavailable for a moment. Please try again shortly."

  private def msg(role: String, content: String): Json =
    Json.obj("role" -> Json.str(role), "content" -> Json.str(content))

  /** the answer's JSON schema (L3): `params` first — a model writes in the schema's order (§10 streaming) */
  val answerSchema: Json = Json.obj("type" -> Json.str("json_schema"), "json_schema" -> Json.obj(
    "name" -> Json.str("answer"), "strict" -> Json.bool(true), "schema" -> Json.obj(
      "type" -> Json.str("object"),
      "properties" -> Json.obj(
        "params" -> Json.obj("type" -> Json.str("object"), "additionalProperties" -> Json.obj(
          "type" -> Json.str("string")), "description" -> Json.str("name → an expression over handles")),
        "template" -> Json.obj("type" -> Json.str("string"),
          "description" -> Json.str("an ICU message with {name} placeholders; no digits, no names of things"))),
      "required" -> Json.arr(Json.str("params"), Json.str("template")))))

  /** the tools' JSON schemas (L2+): `find`, and the setup's actions — each taking a handle */
  def tools(s: Setup): Vector[Json] =
    def fn(name: String, about: String, props: (String, Json)*)(required: String*) = Json.obj(
      "type" -> Json.str("function"), "function" -> Json.obj("name" -> Json.str(name),
        "description" -> Json.str(about), "parameters" -> Json.obj("type" -> Json.str("object"),
          "properties" -> Json.Obj(props.toVector), "required" -> Json.Arr(required.toVector.map(Json.str)))))
    val str = (about: String) => Json.obj("type" -> Json.str("string"), "description" -> Json.str(about))
    fn("find", "Find entities; returns handles (#v1) with their visible words only. Values stay hidden: filter on " +
      "them with `where`.",
      "class" -> Json.obj("type" -> Json.str("string"), "enum" -> Json.Arr(s.schema.classes.map(c =>
        Json.str(c.name)))),
      "text" -> str("words to match in names (any language)"),
      "where" -> Json.obj("type" -> Json.str("array"), "items" -> Json.obj("type" -> Json.str("string")),
        "description" -> Json.str("conditions `property op value`: `price <= 150 PLN`, `service = #v1`, " +
          "`date = tomorrow`, `time >= 17:00`, `duration <= 60 min`")),
      "scope" -> str("`focus` (default: where the user is), `mall` (every shop), or a shop's handle"),
      "limit" -> Json.obj("type" -> Json.str("integer")))("class") +:
      s.actions.map((name, arg, about) => fn(name, about, arg -> str(s"the $arg's handle"))(arg))

  // ---- the prompt

  private def typeWord(t: Type): String = t match
    case Type.Entity(c) => c
    case Type.Many(x) => s"set of ${typeWord(x)}"
    case Type.Count(_) => "Count"
    case other => other.toString

  def system(s: Setup, l: Lang, today: String, handles: Handles, focus: String): String =
    val classes = s.schema.classes.map { c =>
      val parent = if c.parents.isEmpty then "" else s" (a ${c.parents.mkString(", ")})"
      val props = s.schema.props(c.name).map(p => s"${p.name}: ${typeWord(p.tpe)}${
        if p.visible then " [visible]" else ""}${if p.about.nonEmpty then s" (${p.about})" else ""}").mkString(", ")
      s"- ${c.name}$parent #${c.prefix}…: ${c.about}. $props"
    }.mkString("\n")
    val known = if handles.all.isEmpty then "none yet" else handles.all.takeRight(40).map(h =>
      s"${h.name} ${h.ent.cls}${h.visible.collectFirst { case ("name", n) => s" \"$n\"" }.getOrElse("")}")
      .mkString("; ")
    val language = l match
      case Lang.pl => "Polish"
      case Lang.uk => "Ukrainian"
      case Lang.ru => "Russian"
      case Lang.en => "English"
    val finding = if s.level.tools then "Find with the `find` tool (and act with the action tools) before you answer."
      else
        """To find, write ONLY command lines and stop; the results come back as the next message:
          |⟦find Service text="haircut beard"⟧   ⟦find Slot where="service = #v1; date = tomorrow; time >= 17:00"⟧
          |⟦find Service where="price <= 150 PLN" scope="mall"⟧   ⟦open #shop1⟧   ⟦book #s1⟧""".stripMargin
    val answering = if s.level.tools then
      """ANSWER as JSON only: {"params": {"s": "name(#v1)", "p": "price(#v1)"}, "template": "{s} costs {p}."}"""
      else
        """ANSWER in plain text with each value as a marker holding its expression: "⟦name(#v1)⟧ costs ⟦price(#v1)⟧."
          |A marker is the ONLY way to say a value.""".stripMargin
    s"""${s.about}
       |Answer in $language, briefly and kindly. Today is ${weekday(today)}.
       |
       |THE RULE: you never see, write or compute a fact — no name of a thing found, no price, duration, time, date,
       |address, count or any digit. Things come to you as HANDLES (#v1, #s2) with a few visible words. You write
       |EXPRESSIONS over handles; we evaluate them and put the values in.
       |
       |CLASSES and their properties (hidden values are usable in expressions):
       |$classes
       |
       |EXPRESSIONS: property(handle) — price(#v1), time(#s1); paths — name(master(#s1)), city(shop(#v2));
       |comparisons give a boolean — price(#v1) < price(#v7), price(#v1) <= 150 PLN; differences —
       |price(#v7) - price(#v1); aggregates — min, max, sum, avg, count, argmin(price, #v1, #v7); hints — time(#s1) as
       |datetime, price(#v1) as short. Never a value of your own except as a comparison's operand.
       |
       |TEMPLATE: an ICU message. {p} for a parameter; never a unit after a money or duration (it renders with its
       |own); {c, select, true {…} other {…}} for a boolean; {n, plural, one {…} few {…} many {…} other {…}} with #.
       |No digits, no names of things found — always parameters. A comparison is a fact too: never say "cheaper",
       |"earlier", "longer", "fits the budget" in your own words — bind the expression and let a select choose
       |the words:
       |{c, select, true {cheaper here} other {not cheaper here}} with c = price(#v2) < price(#v1).
       |
       |$finding
       |$answering
       |FIND FIRST what the user asks about, then answer with the handles found only — never a handle you were not
       |given. A link is filtered by a handle (service = #v1) or by a name (service = haircut); through a link by a
       |path (shop.city = Kraków). A shop's own facts (address, hours, rating, reviews) are its properties:
       |address(#shop1). Every placeholder of the template is a parameter. If nothing was found, say so without
       |inventing. Handles known: $known.
       |The user is on: ${if focus == "mall" then "the mall's home (find looks in every shop)" else
         focus.stripPrefix("shop:") + "'s pages (find looks in this shop by default)"}.""".stripMargin

  private def weekday(date: String): String =
    Vector("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")(Zone.weekday(date) - 1)

  // ---- a find's arguments read

  /** a filter's value as the model writes it: a handle, a relative day (`today`, `tomorrow`, a weekday), a date,
   * a time of day, money, minutes, a number, a boolean, else a text */
  def literal(s: String, today: String, handles: Handles): Either[String, Value] =
    val t = s.trim.stripPrefix("\"").stripSuffix("\"").trim
    val days = Vector("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
    t.toLowerCase match
      case h if h.startsWith("#") => handles.get(h).map(x => Value.Entity(x.ent)).toRight(s"unknown handle $h")
      case "today" | "dziś" | "dzisiaj" | "сьогодні" | "сегодня" => Right(Value.Day(today))
      case "tomorrow" | "jutro" | "завтра" => Right(Value.Day(Zone.plus(today, 1)))
      case "true" => Right(Value.Bool(true))
      case "false" => Right(Value.Bool(false))
      case d if days.contains(d) =>
        val ahead = Math.floorMod(days.indexOf(d) + 1 - Zone.weekday(today), 7)
        Right(Value.Day(Zone.plus(today, ahead)))
      case d if d.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") => Right(Value.Day(d))
      case other =>
        Expr.parse(other) match
          case Right(Expr.Lit(v)) => Right(v)
          case _ => Right(Value.text(t))

  private val ops = Seq("<=", ">=", "!=", "==", "<", ">", "=")
  private val Called = """(\w+)\s*\(\s*(\w+)\s*\)""".r

  /**
   * `price <= 150 PLN`: a property of the class, an operator, a value — a literal, a handle, or an expression over
   * handles (`price < price(#v1)`, evaluated here). A link by a path (`shop.city = Kraków`, `city(shop) = Kraków`) or
   * by a name (`service = haircut`) is found first: the filter then holds for any entity found
   */
  def condition[R[_]](cls: String, s: String, schema: Schema, today: String, handles: Handles, scope: Scope,
                      l: Lang)(using In[Facts, R]): Either[String, Filter] ! R =
    ops.map(op => op -> s.indexOf(op)).filter(_._2 > 0).sortBy(_._2).headOption match
      case None => pure(Left(s"'$s': write property op value"))
      case Some((op, at)) =>
        val lhs = s.substring(0, at).trim
        val raw = s.substring(at + op.length).trim
        val path = lhs match
          case Called(inner, link) => Some(link -> inner)
          case p if p.contains('.') => Some(p.takeWhile(_ != '.') -> p.dropWhile(_ != '.').drop(1))
          case _ => None
        def linked(link: String, nested: Query): Either[String, Filter] ! R =
          Facts.find[R](nested.copy(scope = scope, limit = 50, lang = l)).map(found =>
            Right(Filter(link, Op.Eq, Value.Many(found.map(f => Value.Entity(f.ent))))))
        path match
          case Some((link, inner)) => schema.prop(cls, link).map(_.tpe) match
            case Some(Type.Entity(target)) => condition[R](target, s"$inner $op $raw", schema, today, handles, scope,
                l).flatMap {
                case Left(e) => pure(Left(e))
                case Right(f) => linked(link, Query(target, filters = Vector(f)))
              }
            case _ => pure(Left(s"$cls has no link '$link'"))
          case None => schema.prop(cls, lhs) match
            case None => pure(Left(s"$cls has no property '$lhs' (it has ${schema.props(cls).map(_.name)
                .mkString(", ")})"))
            case Some(p) => (p.tpe, Op.parse(op).get) match
              // a link by its name: the entities of that name
              case (Type.Entity(target), Op.Eq) if !raw.trim.startsWith("#") =>
                linked(lhs, Query(target, raw.stripPrefix("\"").stripSuffix("\"")))
              case (_, o) if raw.contains("#") && raw.contains("(") => Expr.parse(raw) match
                case Left(e) => pure(Left(s"'$raw': $e"))
                case Right(x) => Eval[R](x, handles).map(_.map(c => Filter(lhs, o, c.value)))
              case (_, o) => pure(literal(raw, today, handles).map(v => Filter(lhs, o, v)))

  def query[R[_]](args: Json, schema: Schema, today: String, handles: Handles, l: Lang)(using In[Facts, R])
    : Either[String, Query] ! R =
    val cls = args.field("class").collect { case Json.Str(c) => c.trim }.getOrElse("")
    val scope = args.field("scope").collect { case Json.Str(x) => x.trim }.getOrElse("focus") match
      case "mall" | "all" => Right(Scope.All)
      case "focus" | "" => Right(Scope.Focus)
      case h if h.startsWith("#") => handles.get(h).map(x => Scope.Source(x.ent.source)).toRight(s"unknown handle $h")
      case other => Left(s"scope '$other': focus, mall or a shop's handle")
    val limit = args.field("limit").flatMap {
      case Json.Num(n) => Some(n.toInt)
      case Json.Str(n) => n.trim.toIntOption
      case _ => None
    }.getOrElse(6).max(1).min(10)
    if schema.cls(cls).isEmpty then
      pure(Left(s"class '$cls' unknown: one of ${schema.classes.map(_.name).mkString(", ")}"))
    else scope match
      case Left(e) => pure(Left(e))
      case Right(sc) =>
        val where = args.field("where") match
          case Some(Json.Arr(xs)) => xs.collect { case Json.Str(x) => x }
          case Some(Json.Str(x)) => x.split(";").toVector
          case _ => Vector.empty
        Eval.each(where.map(_.trim).filter(_.nonEmpty))(condition[R](cls, _, schema, today, handles, sc, l)).map(
          _.map(filters => Query(cls, args.field("text").collect { case Json.Str(x) => x }.getOrElse(""), filters,
            sc, limit, l)))

  // ---- a turn

  /** a turn's state: the handles, the turn's own messages, the cards, the tokens, the raw outputs */
  private final case class St(handles: Handles, msgs: Vector[Json], cards: Vector[Card], tokens: Int,
                              raw: Vector[String], failure: Option[String] = None)

  def turn[R[_]](s: Setup, c: Conversation, user: String, l: Lang, focus: String)(using In[Llm, R], In[Facts, R],
    In[Clock, R], In[Audit, R], In[Emit, R]): (Conversation, Reply) ! R =
    Clock.now[R].flatMap { now =>
      val today = Zone.date(now)
      focused(s, c, focus, l).flatMap { (handles, note) =>
        val start = St(handles, note.toVector.map(msg("system", _)) :+ msg("user", user), Vector.empty, 0, Vector.empty)
        rounds(s, c, start, l, today, focus, 0).flatMap((st, text) => finish(s, c, st, text, l, today, focus, 0))
          .flatMap { (st, reply) =>
            val found = st.handles.all.filterNot(h => c.handles.get(h.name).isDefined)
            val kept = (c.history ++ note.toVector.map(msg("system", _)) :+ msg("user", user)) ++
              (if found.isEmpty then Vector.empty else Vector(msg("system", "Found: " + found.map(h =>
                s"${h.name} ${h.ent.cls}${h.visible.map((k, v) => s" $k=\"$v\"").mkString}").mkString("; ")))) ++
              reply.rendered.map(r => msg("assistant", if s.level.tools then Json.print(Answer.toJson(r.raw))
                else markers(r.raw))).toVector
            val next = Conversation(st.handles, trim(kept, s.keep), focus)
            Clock.now[R].flatMap(at => Audit.record[R](Audited.Answered(s.level, reply.violations.map(_.code),
              reply.regenerations, reply.flagged, reply.blocked, reply.tokens, at)).flatMap(_ =>
              reply.rendered.fold(pure[Unit, R](()))(r => r.chips.foldLeft(pure[Unit, R](()))((acc, ch) =>
                acc.flatMap(_ => ch.sources.foldLeft(pure[Unit, R](()))((a2, f) => a2.flatMap(_ =>
                  Audit.record[R](Audited.Read(f, ch.param, at))))))))).as(next -> reply)
          }
      }
    }

  /** the history at most `keep` messages, cut at a user's message */
  private def trim(h: Vector[Json], keep: Int): Vector[Json] =
    if h.size <= keep then h
    else
      val from = h.indices.drop(h.size - keep).find(i => h(i)("role") == Json.Str("user")).getOrElse(h.size - keep)
      h.drop(from)

  /** an answer as its markers again (the L0 history) */
  private def markers(a: Answer): String =
    Icu.parse(a.template).toOption.fold(a.template)(parts => parts.map {
      case Icu.Part.Lit(t) => t
      case Icu.Part.Arg(n, _) => a.param(n).fold("")(e => s"⟦$e⟧")
      case _ => ""
    }.mkString)

  /** a focus moved to a shop: its handle issued, the model told (a mid-conversation system message) */
  private def focused[R[_]](s: Setup, c: Conversation, focus: String, l: Lang)(using In[Facts, R])
    : (Handles, Option[String]) ! R =
    if focus == c.focus then pure((c.handles, None))
    else if !focus.startsWith("shop:") then pure((c.handles, Some("The user is now on the mall's home: find looks " +
      "in every shop by default.")))
    else Facts.find[R](Query(s.focusClass, scope = Scope.Source(focus.stripPrefix("shop:")), limit = 1, lang = l))
      .map(_.headOption match
        case None => (c.handles, None)
        case Some(f) =>
          val (h, x) = c.handles.issue(s.schema.prefix(f.ent.cls), f.ent, f.version, f.visible)
          (h, Some(s"The user is now on the pages of ${x.name}${x.visible.collectFirst { case ("name", n) =>
            s" \"$n\"" }.getOrElse("")}: find looks there by default; handles found before stay valid.")))

  private def ask(s: Setup, c: Conversation, st: St, l: Lang, today: String, focus: String, tools: Boolean,
                  format: Boolean): Ask =
    new Ask(msg("system", system(s, l, today, st.handles, focus)) +: (c.history ++ st.msgs),
      if tools then this.tools(s) else Vector.empty, s.maxTokens, false, Sampling(temperature = Some(s.temperature)),
      None, if format then Some(answerSchema) else None)

  private def call[R[_]](a: Ask)(using In[Llm, R]): Finished ! R = Llm.complete[R](a)

  /** the failure a port reports in a reply's reasoning (`error: …`), if any */
  private def failed(f: Finished): Option[String] =
    Some(f.reply.reasoning).filter(_.startsWith("error:")).map(_.stripPrefix("error:").trim)

  /** the rounds of finding: tool calls (L2+) or command lines (L0) executed, until the model writes its answer */
  private def rounds[R[_]](s: Setup, c: Conversation, st: St, l: Lang, today: String, focus: String, i: Int)(
    using In[Llm, R], In[Facts, R]): (St, String) ! R =
    call[R](ask(s, c, st, l, today, focus, s.level.tools && i < s.rounds, format = false)).flatMap { f =>
      val spent = st.copy(tokens = st.tokens + f.timing.promptTokens + f.timing.tokens,
        raw = st.raw :+ (f.reply.content + f.reply.toolCalls.map(t => s" ⟪${t.name} ${Json.print(t.arguments)}⟫")
          .mkString))
      failed(f) match
        case Some(why) => pure((spent.copy(failure = Some(why)), ""))
        case None =>
          val commands = if s.level.tools then f.reply.toolCalls else Commands.parse(f.reply.content)
          if commands.isEmpty || i >= s.rounds then pure((spent, f.reply.content))
          else Eval.each(commands.zipWithIndex)((t, k) => execute[R](s, t, today, l, spent.handles).map(x =>
            Right[String, (ToolCall, Int, Either[String, Json], Option[Card], Handles => Handles)](
              (t, k, x._1, x._2, x._3)))).flatMap {
            case Left(_) => pure((spent, f.reply.content))
            case Right(done) =>
              // the handles issued in order, each result told by the handles it made
              val (handles, results) = done.foldLeft((spent.handles, Vector.empty[(ToolCall, Int, Json)])) {
                case ((h, acc), (t, k, res, _, issue)) =>
                  val h2 = issue(h)
                  (h2, acc :+ (t, k, res.fold(e => Json.obj("error" -> Json.str(e)), identity)))
              }
              val cards = done.flatMap(_._4)
              val msgs = if s.level.tools then
                Json.obj("role" -> Json.str("assistant"), "content" -> Json.str(f.reply.content),
                  "tool_calls" -> Json.Arr(results.map((t, k, _) => Json.obj("id" -> Json.str(s"call_${i}_$k"),
                    "type" -> Json.str("function"), "function" -> Json.obj("name" -> Json.str(t.name),
                      "arguments" -> Json.str(Json.print(t.arguments))))))) +:
                  results.map((t, k, r) => Json.obj("role" -> Json.str("tool"), "tool_call_id" ->
                    Json.str(s"call_${i}_$k"), "name" -> Json.str(t.name), "content" -> Json.str(Json.print(r))))
                else Vector(msg("assistant", f.reply.content), msg("user", "Results:\n" + results.map((t, _, r) =>
                  s"${t.name} ${Json.print(t.arguments)} → ${Json.print(r)}").mkString("\n") +
                  "\nNow answer (or find more)."))
              rounds(s, c, spent.copy(handles = handles, msgs = spent.msgs ++ msgs, cards = spent.cards ++ cards), l,
                today, focus, i + 1)
          }
    }

  /** one tool call executed against the handles the round starts with: its result for the model (handles and
   * visible words only), a card, the handles it issues (a function of the table: several calls issue in order) */
  private def execute[R[_]](s: Setup, t: ToolCall, today: String, l: Lang, handles: Handles)(using In[Facts, R])
    : (Either[String, Json], Option[Card], Handles => Handles) ! R =
      t.name match
        case "find" => query[R](t.arguments, s.schema, today, handles, l).flatMap {
          case Left(e) => pure((Left(e), None, identity))
          case Right(q) => Facts.find[R](q).map { found =>
              val issue = (h: Handles) => found.foldLeft(h)((acc, f) =>
                acc.issue(s.schema.prefix(f.ent.cls), f.ent, f.version, f.visible)._1)
              val after = issue(handles)
              val shown = found.map(f => Json.Obj(Vector("handle" -> Json.str(after.of(f.ent).get.name),
                "class" -> Json.str(f.ent.cls)) ++ f.visible.map((k, v) => k -> Json.str(v))))
              (Right(if shown.isEmpty then Json.obj("found" -> Json.Arr(Vector.empty),
                "note" -> Json.str("nothing matched: say so, or find with fewer conditions"))
                else Json.obj("found" -> Json.Arr(shown))), None, issue)
            }
        }
        case name if s.actions.exists(_._1 == name) =>
          val arg = s.actions.find(_._1 == name).get._2
          t.arguments.field(arg).orElse(t.arguments.record.headOption.map(_._2)) match
            case Some(Json.Str(h)) if handles.get(h.trim).isDefined =>
              val target = handles.get(h.trim).get
              Facts.act[R](name, Map(arg -> Value.Entity(target.ent)), l).map {
                case Left(e) => (Left(e), None, identity)
                case Right(a) =>
                  val issue = (x: Handles) => a.ent.fold(x)(e => x.issue(s.schema.prefix(e.cls), e, 0L, a.visible,
                    a.pinned)._1)
                  (Right(Json.Obj(Vector("done" -> Json.str(name), arg -> Json.str(target.name)) ++
                    a.ent.flatMap(e => issue(handles).of(e)).map(x => "handle" -> Json.str(x.name)) ++
                    a.card.map(_ => "note" -> Json.str("a card is shown to the user")))), a.card, issue)
              }
            case Some(Json.Str(h)) => pure((Left(s"unknown handle $h"), None, identity))
            case _ => pure((Left(s"$name takes $arg: a handle"), None, identity))
        case other => pure((Left(s"no tool '$other'"), None, identity))

  // ---- the answer

  /** the model's answer read: JSON (L2+), markers, or plain text (a template without parameters) */
  def read(text: String, l: Level): Answer =
    val clean = text.replaceAll("(?s)<think>.*?</think>", "").trim
    val json = if l.tools || clean.startsWith("{") || clean.startsWith("```") then Answer.fromJson(clean).toOption
      else None
    json.getOrElse(if Answer.marked(clean) then Answer.fromMarkers(clean) else Answer(Vector.empty, Icu.quote(clean)))

  private def finish[R[_]](s: Setup, c: Conversation, st: St, text: String, l: Lang, today: String, focus: String,
                           tries: Int)(using In[Llm, R], In[Facts, R], In[Emit, R]): (St, Reply) ! R =
    if st.failure.isDefined then pure((st, Reply(None, Some(notice("unavailable", l)), st.cards, tokens = st.tokens,
      raw = st.raw, failure = st.failure)))
    else
      // L3: an answer that is not the JSON asked for is asked again, by the schema, without tools
      val formatted: (St, String) ! R =
        if s.level.schema && Answer.fromJson(text).isLeft then
          val a = ask(s, c, st.copy(msgs = st.msgs :+ msg("user", "Answer now: the JSON {params, template} only.")),
            l, today, focus, tools = false, format = true)
          // streamed: the parameters evaluated as soon as they close, the template sent as it grows (§10)
          if s.stream then Stream.answer[R](a, st.handles, s.schema, l).map((t, n) => (st.copy(tokens = st.tokens + n,
            raw = st.raw :+ t), t))
          else call[R](a).map(f => (st.copy(tokens = st.tokens + f.timing.promptTokens + f.timing.tokens,
            raw = st.raw :+ f.reply.content, failure = failed(f)), f.reply.content))
        else pure((st, text))
      formatted.flatMap { (st2, answerText) =>
        if st2.failure.isDefined then pure((st2, Reply(None, Some(notice("unavailable", l)), st2.cards,
          tokens = st2.tokens, raw = st2.raw, failure = st2.failure)))
        else
          val a = read(answerText, s.level)
          Resolve[R](a, st2.handles, s.schema, l).flatMap {
            case Right(r) => pure((st2, Reply(Some(r), cards = st2.cards, regenerations = tries, tokens = st2.tokens,
              raw = st2.raw)))
            case Left(vs) => s.policy match
              case Policy.Regenerate if tries < s.retries =>
                val again = st2.copy(msgs = st2.msgs :+ msg("assistant", answerText) :+ msg("user",
                  "Your answer broke the rule: " + vs.map(_.show).mkString("; ") + ". Write it again: no digits " +
                    "and no names in the template, every placeholder a parameter bound to an expression over the " +
                    s"handles known (${st2.handles.all.map(_.name).mkString(", ")})."))
                call[R](ask(s, c, again, l, today, focus, tools = false, format = s.level.schema)).flatMap(f =>
                  finish(s, c, again.copy(tokens = again.tokens + f.timing.promptTokens + f.timing.tokens,
                    raw = again.raw :+ f.reply.content, failure = failed(f)), f.reply.content, l, today, focus,
                    tries + 1)).map((x, reply) => (x, reply.copy(violations = vs ++ reply.violations)))
              case Policy.Flag if vs.forall(v => Set("digits", "name", "unit")(v.code)) =>
                // delivered marked: the template's own words carry the violation, every value still the resolver's
                Resolve.lenient[R](a, st2.handles, s.schema, l).map(r => (st2, Reply(r, if r.isEmpty then
                  Some(notice("blocked", l)) else None, st2.cards, vs, tries, flagged = r.isDefined,
                  blocked = r.isEmpty, tokens = st2.tokens, raw = st2.raw)))
              case _ => pure((st2, Reply(None, Some(notice("blocked", l)), st2.cards, vs, tries, blocked = true,
                tokens = st2.tokens, raw = st2.raw)))
          }
      }

/** THE L0 TEXT PROTOCOL: command lines `⟦find Service text="…" where="…; …" scope="mall"⟧`, `⟦open #shop1⟧`,
 * `⟦book #s1⟧` read as tool calls */
object Commands:
  private val Line = """(?:⟦|\[\[)\s*(find|open|book)\s+([^⟧\]]*)(?:⟧|\]\])""".r
  private val Pair = """(\w+)\s*=\s*"([^"]*)"""".r
  private val Bare = """(\w+)\s*=\s*([^\s"]+)""".r

  def parse(text: String): Vector[ToolCall] =
    Line.findAllMatchIn(text).map { m =>
      val body = m.group(2).trim
      m.group(1) match
        case "find" =>
          val cls = body.takeWhile(c => !c.isWhitespace)
          val rest = body.drop(cls.length)
          val quoted = Pair.findAllMatchIn(rest).map(p => p.group(1) -> p.group(2)).toVector
          val bare = Bare.findAllMatchIn(Pair.replaceAllIn(rest, "")).map(p => p.group(1) -> p.group(2)).toVector
          val fields = (quoted ++ bare).map {
            case ("where", w) => "where" -> Json.Arr(w.split(";").toVector.map(_.trim).filter(_.nonEmpty)
              .map(Json.str))
            case (k, v) => k -> Json.str(v)
          }
          ToolCall("find", Json.Obj(("class" -> Json.str(cls)) +: fields))
        case action => ToolCall(action, Json.obj(if action == "open" then "shop" -> Json.str(body)
          else "slot" -> Json.str(body)))
    }.toVector
