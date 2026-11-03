package okay.facts

import okay.scala3.*

/** WHAT THE AUDIT KEEPS (specs/design.md §8): every value read for an answer (whose, which property, which version,
 * when, for which parameter), and every answer's outcome — never a conversation's words */
enum Audited:
  case Read(fact: Fact, param: String, at: Long)
  /** an answer given: its level, its violations seen, the regenerations asked, whether flagged or blocked */
  case Answered(level: Level, violations: Vector[String], regenerations: Int, flagged: Boolean, blocked: Boolean,
                tokens: Int, at: Long)

/** THE AUDIT, an effect: its sink a handler — nowhere, a list (tests), the shop's object and the operator's dashboard
 * (okay-mall) */
enum Audit[X]:
  case Record(entry: Audited) extends Audit[Unit]

object Audit:
  def record[R[_]](e: Audited)(using In[Audit, R]): Unit ! R = perform[Audit](Record(e))

  def ignore[R[_], Rest[_], A](p: A ! R)(using Split[Audit, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Audit, Rest, A, A](pure)([X] => (op: Audit[X]) => op match
      case Record(_) => ()))

  /** the entries, in order, and the value */
  def collect[R[_], Rest[_], A](p: A ! R)(using Split[Audit, R, Rest]): (Vector[Audited], A) ! Rest =
    handle(p, Handler.stateful[Audit, Rest, A, (Vector[Audited], A), Vector[Audited]](Vector.empty)(
      (es, a) => pure((es, a)))(
      [X] => (op: Audit[X], cell: Cell[Vector[Audited]]) => op match
        case Record(e) => cell.set(cell.get :+ e)))
