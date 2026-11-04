package okay.facts

/**
 * THE EXPRESSION LANGUAGE (specs/design.md §6): what the model writes in `params` — references over handles, paths
 * over links, aggregates, comparisons and arithmetic, phrases, formatting hints. Small, typed, evaluated only by the
 * resolver
 */
enum Expr:
  /** `#v1` */
  case H(name: String)
  /** `200 PLN`, `30 min`, `17:00`, `3`, `true` — only as a comparison's operand */
  case Lit(value: Value)
  /** a word: a property's name as `argmin`'s first argument, `today`, `tomorrow` */
  case Word(word: String)
  case Call(fn: String, args: Vector[Expr])
  case Bin(op: String, left: Expr, right: Expr)
  case Not(e: Expr)
  /** a formatting hint: `price(#v1) as short` */
  case As(e: Expr, hint: String)

object Expr:
  final case class Error(what: String)

  // ---- THE PARSER: recursive descent over the characters

  def parse(s: String): Either[String, Expr] =
    val p = new Parser(s)
    try
      val e = p.expr()
      p.ws()
      if p.i < s.length then Left(s"unexpected '${s.substring(p.i).take(12)}' at ${p.i}") else Right(e)
    catch case Failed(at, what) => Left(s"$what at $at")

  private final case class Failed(at: Int, what: String) extends RuntimeException(what)

  private val units: Map[String, BigDecimal => Value] = Map(
    "pln" -> (n => Value.Money((n * 100).toLongExact, "PLN")), "zł" -> (n => Value.Money((n * 100).toLongExact, "PLN")),
    "zl" -> (n => Value.Money((n * 100).toLongExact, "PLN")), "min" -> (n => Value.Minutes(n.toIntExact)),
    "minutes" -> (n => Value.Minutes(n.toIntExact)), "minut" -> (n => Value.Minutes(n.toIntExact)),
    "h" -> (n => Value.Minutes((n * 60).toIntExact)))

  private final class Parser(s: String):
    var i = 0
    def fail(what: String): Nothing = throw Failed(i, what)
    def ws(): Unit = while i < s.length && s.charAt(i).isWhitespace do i += 1
    def peek(t: String): Boolean = { ws(); s.startsWith(t, i) }
    def eat(t: String): Boolean = if peek(t) then { i += t.length; true } else false

    def expr(): Expr = or()
    def or(): Expr =
      var l = and()
      while eat("||") do l = Bin("||", l, and())
      l
    def and(): Expr =
      var l = cmp()
      while eat("&&") do l = Bin("&&", l, cmp())
      l
    def cmp(): Expr =
      val l = add()
      Seq("<=", ">=", "==", "!=", "<", ">", "=").find(peek) match
        case Some(op) =>
          i += op.length
          Bin(if op == "=" then "==" else op, l, add())
        case None => l
    def add(): Expr =
      var l = unary()
      var more = true
      while more do
        if peek("+") then { i += 1; l = Bin("+", l, unary()) }
        else if peek("-") && !s.startsWith("->", i) then { i += 1; l = Bin("-", l, unary()) }
        else more = false
      l
    def unary(): Expr =
      if eat("!") then Not(unary())
      else
        val e = primary()
        ws()
        if s.startsWith("as ", i) || s.startsWith("as\t", i) then
          i += 2
          As(e, word())
        else e
    def word(): String =
      ws()
      val start = i
      while i < s.length && (s.charAt(i).isLetterOrDigit || s.charAt(i) == '_') do i += 1
      if i == start then fail("a name") else s.substring(start, i)
    def primary(): Expr =
      ws()
      if i >= s.length then fail("an expression")
      val c = s.charAt(i)
      if c == '(' then
        i += 1
        val e = expr()
        if !eat(")") then fail("')'")
        e
      else if c == '#' then
        i += 1
        H("#" + word())
      else if c.isDigit then number()
      else if c == '"' || c == '\'' then fail("no text literals: a value comes from the data")
      else if c.isLetter then
        val w = word()
        if eat("(") then
          val args = Vector.newBuilder[Expr]
          if !eat(")") then
            args += expr()
            while eat(",") do args += expr()
            if !eat(")") then fail("')'")
          (w, args.result()) match
            // `min(price, #v1, #v7)` as `min(price(#v1), price(#v7))`: a property's name first, as argmin takes it
            case (f, Word(p) +: rest) if Set("min", "max", "sum", "avg")(f) && rest.nonEmpty =>
              Call(f, rest.map(x => Call(p, Vector(x))))
            case (f, as) => Call(f, as)
        else w match
          case "true" => Lit(Value.Bool(true))
          case "false" => Lit(Value.Bool(false))
          case other => Word(other)
      else fail(s"unexpected '$c'")
    def number(): Expr =
      val start = i
      while i < s.length && (s.charAt(i).isDigit || s.charAt(i) == '.' || s.charAt(i) == ',') do i += 1
      val text = s.substring(start, i).replace(',', '.')
      if i < s.length && s.charAt(i) == ':' then
        i += 1
        val m = i
        while i < s.length && s.charAt(i).isDigit do i += 1
        val (h, mm) = (text.toIntOption, s.substring(m, i).toIntOption)
        if h.isEmpty || mm.isEmpty || h.get > 24 || mm.get > 59 then fail("a time HH:MM")
        Lit(Value.Clock(h.get * 60 + mm.get))
      else
        val n = text.toDoubleOption.map(BigDecimal(_)).getOrElse(fail("a number"))
        val save = i
        ws()
        val u = new StringBuilder
        while i < s.length && s.charAt(i).isLetter do { u += s.charAt(i); i += 1 }
        units.get(u.toString.toLowerCase) match
          case Some(f) => Lit(f(n))
          case None => i = save; Lit(Value.Num(n))

  /** an expression as it reads: the raw form the debug view and the history show */
  def show(e: Expr): String = e match
    case H(n) => n
    case Lit(v) => v match
      case Value.Money(m, c) => s"${BigDecimal(m) / 100} $c"
      case Value.Minutes(n) => s"$n min"
      case Value.Clock(m) => Render.clock(m)
      case Value.Num(n) => n.toString
      case Value.Bool(b) => b.toString
      case other => other.toString
    case Word(w) => w
    case Call(f, as) => s"$f(${as.map(show).mkString(", ")})"
    case Bin(op, l, r) => s"${show(l)} $op ${show(r)}"
    case Not(x) => s"!${show(x)}"
    case As(x, h) => s"${show(x)} as $h"

  /** every handle an expression names */
  def handles(e: Expr): Vector[String] = e match
    case H(n) => Vector(n)
    case Call(_, as) => as.flatMap(handles)
    case Bin(_, l, r) => handles(l) ++ handles(r)
    case Not(x) => handles(x)
    case As(x, _) => handles(x)
    case _ => Vector.empty

  // ---- THE TYPES

  val aggregates: Set[String] = Set("sum", "min", "max", "avg", "count", "argmin", "argmax")
  private val numeric: Set[Type] = Set(Type.Money, Type.Duration, Type.Number)

  /**
   * AN EXPRESSION'S TYPE against the handles issued and the schema — or why not: an unknown handle (the model made it
   * up), an unknown function, a property its class has not, a non-functional path where one value is needed, a
   * literal where a value must come from the data, a type error
   */
  def typeOf(e: Expr, handles: Handles, schema: Schema, top: Boolean = true): Either[String, Type] =
    def t(x: Expr) = typeOf(x, handles, schema, top = false)
    val result: Either[String, Type] = e match
      case H(n) => handles.get(n).map(h => Type.Entity(h.ent.cls)).toRight(s"unknown handle $n")
      case Lit(v) => Right(Value.typeOf(v))
      case Word(w) => Left(s"unknown word '$w'")
      case As(x, _) => t(x)
      case Not(x) => t(x).flatMap(tt => if tt == Type.Bool then Right(Type.Bool) else Left("'!' takes a boolean"))
      case Bin(op, l, r) if Set("&&", "||")(op) =>
        for a <- t(l); b <- t(r); res <- if a == Type.Bool && b == Type.Bool then Right(Type.Bool)
          else Left(s"'$op' takes booleans") yield res
      case Bin(op, l, r) if Set("<", "<=", ">", ">=", "==", "!=")(op) =>
        for a <- t(l); b <- t(r); res <- if comparable(a, b) then Right(Type.Bool)
          else Left(s"${a.show} and ${b.show} do not compare") yield res
      case Bin(op, l, r) =>
        if isLit(l) || isLit(r) then Left(s"a literal in '$op': a value comes from the data")
        else for a <- t(l); b <- t(r); res <- if a == b && numeric(a) then Right(a)
          else Left(s"'$op' takes two values of one kind, not ${a.show} and ${b.show}") yield res
      case Call(fn, args) if aggregates(fn) => aggregate(fn, args, handles, schema)
      case Call("distance", Vector(a, b)) =>
        def place(x: Expr): Either[String, Unit] = t(x).flatMap {
          case Type.Point => Right(())
          case Type.Entity(cls) if schema.prop(cls, "point").exists(_.tpe == Type.Point) => Right(())
          case other => Left(s"distance of ${other.show}: not a place")
        }
        for _ <- place(a); _ <- place(b) yield Type.Distance
      case Call(fn, Vector(arg)) => t(arg).flatMap(at => property(fn, at, schema))
      case Call(fn, args) => Left(s"$fn takes one argument, not ${args.size}")
    result.flatMap { tt =>
      if top && isLit(e) then Left("a parameter is a literal: a value comes from the data")
      else if top && !hasHandle(e) then Left("a parameter names no handle: a value comes from the data")
      else tt match
        case Type.Many(_) if top => Left(s"non-functional path in ${show(e)}: it yields a set, not one value")
        case other => Right(other)
    }

  private def isLit(e: Expr): Boolean = e match
    case Lit(_) => true
    case As(x, _) => isLit(x)
    case _ => false

  private def hasHandle(e: Expr): Boolean = handles(e).nonEmpty

  private def comparable(a: Type, b: Type): Boolean = (a, b) match
    case (Type.Count(_), Type.Number) | (Type.Number, Type.Count(_)) | (Type.Count(_), Type.Count(_)) => true
    case (Type.Time, Type.Date) | (Type.Date, Type.Time) => true
    // a bare number against money or a duration: its main unit (`price(#v1) <= 150`)
    case (Type.Money, Type.Number) | (Type.Number, Type.Money) | (Type.Duration, Type.Number) |
         (Type.Number, Type.Duration) => true
    case (Type.Distance, Type.Number) | (Type.Number, Type.Distance) => true
    case (Type.Entity(_), Type.Entity(_)) => true
    case _ => a == b && !a.isInstanceOf[Type.Many]

  /** a property applied: to an entity its type (a non-functional link a set); to a set, a set of its values */
  private def property(fn: String, at: Type, schema: Schema): Either[String, Type] = at match
    case Type.Entity(cls) => schema.prop(cls, fn) match
      case Some(p) => Right(if p.functional then p.tpe else Type.Many(p.tpe))
      case None => Left(s"$cls has no property '$fn'")
    case Type.Many(inner) => property(fn, inner, schema).map {
        case m @ Type.Many(_) => m
        case one => Type.Many(one)
      }
    case other => Left(s"'$fn' of ${other.show}: not an entity")

  /** the class every one of entity types is: one of them, the others below it */
  private def common(types: Vector[Type], schema: Schema): Option[String] =
    val cs = types.collect { case Type.Entity(c) => c }
    cs.find(c => cs.forall(schema.isA(_, c)))

  private def aggregate(fn: String, args: Vector[Expr], handles: Handles, schema: Schema): Either[String, Type] =
    def t(x: Expr) = typeOf(x, handles, schema, top = false)
    val (key, rest) = if fn == "argmin" || fn == "argmax" then (args.headOption, args.drop(1)) else (None, args)
    if rest.isEmpty then Left(s"$fn of nothing")
    else
      val each = rest.map(x => t(x).map {
        case Type.Many(inner) => inner
        case one => one
      })
      each.collectFirst { case Left(e) => e } match
        case Some(e) => Left(e)
        case None =>
          val types = each.collect { case Right(x) => x }.distinct
          fn match
            case "count" => Right(Type.Number)
            case "argmin" | "argmax" => key match
              case Some(Word(p)) => types match
                case es if es.nonEmpty && es.forall(_.isInstanceOf[Type.Entity]) && common(es, schema).isDefined =>
                  val c = common(es, schema).get
                  schema.prop(c, p) match
                    case Some(prop) if numeric(prop.tpe) || prop.tpe == Type.Time => Right(Type.Entity(c))
                    case Some(_) => Left(s"$fn by '$p': not a quantity")
                    case None => Left(s"$c has no property '$p'")
                case _ => Left(s"$fn takes entities of one class")
              case _ => Left(s"$fn's first argument is a property's name")
            case _ => types match
              case Vector(x) if numeric(x) || (x == Type.Time && fn != "sum" && fn != "avg") => Right(x)
              case Vector(x) => Left(s"$fn of ${x.show}")
              case _ => Left(s"$fn of values of different kinds")
