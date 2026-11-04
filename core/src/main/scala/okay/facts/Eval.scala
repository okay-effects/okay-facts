package okay.facts

import okay.scala3.*

/** A VALUE EVALUATED, with the facts it was computed from (each read audited, each a chip's source) */
final case class Computed(value: Value, facts: Vector[Fact])

/**
 * THE EVALUATOR (specs/design.md §6, §8): an expression's value from the data — every property read through `Facts`
 * at the time of rendering (current), an action's handle by its pinned values (as they were) — computed here, never
 * by the model
 */
object Eval:
  type Res = Either[String, Computed]

  def apply[R[_]](e: Expr, handles: Handles)(using In[Facts, R]): Res ! R = e match
    case Expr.H(n) => pure(handles.get(n).map(h => Computed(Value.Entity(h.ent), Vector.empty))
      .toRight(s"unknown handle $n"))
    case Expr.Lit(v) => pure(Right(Computed(v, Vector.empty)))
    case Expr.Word(w) => pure(Left(s"unknown word '$w'"))
    case Expr.As(x, _) => apply(x, handles)
    case Expr.Not(x) => apply(x, handles).map(_.flatMap {
        case Computed(Value.Bool(b), fs) => Right(Computed(Value.Bool(!b), fs))
        case _ => Left("'!' takes a boolean")
      })
    case Expr.Bin(op, l, r) => apply(l, handles).flatMap {
        case Left(e) => pure(Left(e))
        case Right(a) => apply(r, handles).map(_.flatMap(b => binary(op, a, b)))
      }
    case Expr.Call(fn, args) if Expr.aggregates(fn) => aggregate(fn, args, handles)
    // the distance between two places: each an entity's `point` (or a point), the great circle's metres
    case Expr.Call("distance", Vector(a, b)) => point(a, handles).flatMap {
        case Left(e) => pure(Left(e))
        case Right((p, fp)) => point(b, handles).map(_.map((q, fq) =>
            Computed(Value.Meters(Geo.meters(p, q)), fp ++ fq)))
      }
    case Expr.Call(fn, Vector(arg)) => apply(arg, handles).flatMap {
        case Left(e) => pure(Left(e))
        case Right(Computed(v, fs)) => property(fn, v, handles).map(_.map(c => Computed(c.value, fs ++ c.facts)))
      }
    case Expr.Call(fn, _) => pure(Left(s"$fn takes one argument"))

  /** a property of an entity — its pinned value when its handle has one, else the source's now — or of each of a set */
  def property[R[_]](prop: String, v: Value, handles: Handles)(using In[Facts, R]): Res ! R = v match
    case Value.Entity(ent) =>
      handles.of(ent).flatMap(_.pinned.get(prop)) match
        case Some(f) => pure(Right(Computed(f.value, Vector(f))))
        case None => Facts.get[R](ent, prop).map(_.map(f => Computed(f.value, Vector(f)))
          .toRight(s"no '$prop' of ${handles.of(ent).fold(ent.key)(_.name)}"))
    case Value.Many(vs) => each(vs)(property(prop, _, handles)).map(_.map(cs => Computed(Value.Many(cs.flatMap(c =>
        c.value match
          case Value.Many(inner) => inner
          case one => Vector(one))), cs.flatMap(_.facts))))
    case other => pure(Left(s"'$prop' of a value, not an entity"))

  /** a place's point: a point as it is, an entity's `point` property */
  private def point[R[_]](e: Expr, handles: Handles)(using In[Facts, R])
    : Either[String, (Value.Point, Vector[Fact])] ! R =
    apply(e, handles).flatMap {
      case Left(x) => pure(Left(x))
      case Right(Computed(p: Value.Point, fs)) => pure(Right((p, fs)))
      case Right(Computed(v: Value.Entity, fs)) => property("point", v, handles).map(_.flatMap {
          case Computed(p: Value.Point, more) => Right((p, fs ++ more))
          case _ => Left("no point")
        })
      case Right(_) => pure(Left("distance takes two places"))
    }

  /** `f` on each, in order; the first failure */
  def each[R[_], E, A, B](xs: Vector[A])(f: A => Either[E, B] ! R): Either[E, Vector[B]] ! R =
    xs.foldLeft(pure[Either[E, Vector[B]], R](Right(Vector.empty)))((acc, a) => acc.flatMap {
      case Left(e) => pure(Left(e))
      case Right(bs) => f(a).map(_.map(bs :+ _))
    })

  private def binary(op: String, a: Computed, b: Computed): Res =
    val fs = a.facts ++ b.facts
    def bool(x: Boolean) = Right(Computed(Value.Bool(x), fs))
    (op, a.value, b.value) match
      case ("&&", Value.Bool(x), Value.Bool(y)) => bool(x && y)
      case ("||", Value.Bool(x), Value.Bool(y)) => bool(x || y)
      case ("==", x, y) => bool(Value.same(x, y))
      case ("!=", x, y) => bool(!Value.same(x, y))
      case (cmp, x, y) if Set("<", "<=", ">", ">=")(cmp) => Value.compare(x, y) match
        case Some(c) => bool(cmp match
          case "<" => c < 0
          case "<=" => c <= 0
          case ">" => c > 0
          case _ => c >= 0)
        case None => Left(s"${Value.typeOf(x).show} and ${Value.typeOf(y).show} do not compare")
      case ("+" | "-", x, y) => arith(op, x, y).map(Computed(_, fs))
      case _ => Left(s"'$op' does not apply")

  private def arith(op: String, x: Value, y: Value): Either[String, Value] =
    val s = if op == "+" then 1 else -1
    (x, y) match
      case (Value.Money(a, c), Value.Money(b, d)) if c == d => Right(Value.Money(a + s * b, c))
      case (Value.Minutes(a), Value.Minutes(b)) => Right(Value.Minutes(a + s * b))
      case (Value.Num(a), Value.Num(b)) => Right(Value.Num(a + s * b))
      case _ => Left(s"'$op' takes two values of one kind")

  private def aggregate[R[_]](fn: String, args: Vector[Expr], handles: Handles)(using In[Facts, R]): Res ! R =
    val (key, rest) = if fn == "argmin" || fn == "argmax" then (args.headOption, args.drop(1)) else (None, args)
    each(rest)(apply(_, handles)).flatMap {
      case Left(e) => pure(Left(e))
      case Right(cs) =>
        val values = cs.flatMap(c => c.value match
          case Value.Many(vs) => vs
          case one => Vector(one))
        val fs = cs.flatMap(_.facts)
        fn match
          case "count" => pure(Right(Computed(Value.Num(values.size), fs)))
          case "argmin" | "argmax" => key match
            case Some(Expr.Word(p)) => each(values)(v => property(p, v, handles).map(_.map(v -> _))).map(_.flatMap {
                by =>
                  val sorted = by.sortWith((a, b) => Value.compare(a._2.value, b._2.value).exists(_ < 0))
                  (if fn == "argmin" then sorted.headOption else sorted.lastOption)
                    .map((v, _) => Computed(v, fs ++ by.flatMap(_._2.facts))).toRight(s"$fn of nothing")
              })
            case _ => pure(Left(s"$fn's first argument is a property's name"))
          case _ => pure(fold(fn, values).map(Computed(_, fs)))
    }

  private def fold(fn: String, values: Vector[Value]): Either[String, Value] =
    if values.isEmpty then Left(s"$fn of nothing")
    else fn match
      case "min" => Right(values.reduce((a, b) => if Value.compare(b, a).exists(_ < 0) then b else a))
      case "max" => Right(values.reduce((a, b) => if Value.compare(b, a).exists(_ > 0) then b else a))
      case "sum" => values.tail.foldLeft[Either[String, Value]](Right(values.head))((acc, v) => acc.flatMap(
        arith("+", _, v)))
      case "avg" => fold("sum", values).flatMap {
          case Value.Money(m, c) => Right(Value.Money(math.round(m.toDouble / values.size), c))
          case Value.Minutes(n) => Right(Value.Minutes(math.round(n.toDouble / values.size).toInt))
          case Value.Num(n) => Right(Value.Num(n / values.size))
          case _ => Left(s"avg of these")
        }
      case other => Left(s"unknown aggregate $other")
