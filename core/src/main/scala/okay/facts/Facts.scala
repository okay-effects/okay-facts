package okay.facts

import okay.scala3.*

/** A FACT: a value read from a source — whose (the entity), which property, at which version, when it changed. What a
 * rendered parameter shows as its source */
final case class Fact(value: Value, ent: Ent, prop: String, version: Long = 0L, updatedAt: Long = 0L)

/** a comparison of a query: a property against a value (`price <= 150 PLN`, `service = #v1`, `date = tomorrow`) */
enum Op:
  case Eq, Ne, Lt, Le, Gt, Ge

object Op:
  def parse(s: String): Option[Op] = s.trim match
    case "=" | "==" => Some(Eq)
    case "!=" | "<>" => Some(Ne)
    case "<" => Some(Lt)
    case "<=" => Some(Le)
    case ">" => Some(Gt)
    case ">=" => Some(Ge)
    case _ => None

  def holds(op: Op, a: Value, b: Value): Boolean = op match
    case Eq => Value.same(a, b)
    case Ne => !Value.same(a, b)
    case Lt => Value.compare(a, b).exists(_ < 0)
    case Le => Value.compare(a, b).exists(_ <= 0)
    case Gt => Value.compare(a, b).exists(_ > 0)
    case Ge => Value.compare(a, b).exists(_ >= 0)

final case class Filter(prop: String, op: Op, value: Value)

/** WHERE a query looks: the focus (the current shop, or the mall on its pages), the whole mall, or one source */
enum Scope:
  case Focus, All
  case Source(id: String)

/** A QUERY of `find`: a class (its subclasses too), words matched against the visible texts, filters on any
 * property — hidden ones applied by the resolver, never shown —, where, how many */
final case class Query(cls: String, text: String = "", filters: Vector[Filter] = Vector.empty,
                       scope: Scope = Scope.Focus, limit: Int = 8, lang: Lang = Lang.pl)

/** what a query found: the entity, and its visible descriptors only (in the user's language) */
final case class Found(ent: Ent, visible: Vector[(String, String)], version: Long = 0L)

/** AN ACTION'S RESULT: what it made or what it points at (its pinned facts: rendered as they were), and what the
 * client is to do (`card`: a booking to confirm, a page to open), or why not */
final case class Acted(ent: Option[Ent] = None, pinned: Map[String, Fact] = Map.empty,
                       visible: Vector[(String, String)] = Vector.empty, card: Option[Card] = None)

/** WHAT THE WIDGET DRAWS beside an answer, from the resolver: a link to open, a booking to confirm */
enum Card:
  case Open(url: String, title: String)
  case Book(token: String, title: String, lines: Vector[String])

/**
 * THE DATA, AS AN EFFECT (specs/design.md §3): a query (`find`), a property of an entity (`get`), an action
 * (`act`). A source is its handler: a shop's data (okay-shop), a mall's shops through their objects (okay-mall), a
 * table in memory (tests)
 */
enum Facts[X]:
  case Find(query: Query) extends Facts[Vector[Found]]
  case Get(ent: Ent, prop: String) extends Facts[Option[Fact]]
  case Act(name: String, args: Map[String, Value], lang: Lang) extends Facts[Either[String, Acted]]

object Facts:
  def find[R[_]](q: Query)(using In[Facts, R]): Vector[Found] ! R = perform[Facts](Find(q))
  def get[R[_]](ent: Ent, prop: String)(using In[Facts, R]): Option[Fact] ! R = perform[Facts](Get(ent, prop))
  def act[R[_]](name: String, args: Map[String, Value], lang: Lang)(using In[Facts, R])
    : Either[String, Acted] ! R = perform[Facts](Act(name, args, lang))

  /** the facts of rows in memory: `Table`'s find and get; no actions */
  def table[R[_], Rest[_], A](schema: Schema, rows: () => Vector[Row])(p: A ! R)(using Split[Facts, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Facts, Rest, A, A](pure)([X] => (op: Facts[X]) => op match
      case Find(q) => Table.find(schema, rows(), q)
      case Get(ent, prop) => Table.get(rows(), ent, prop)
      case Act(name, _, _) => Left(s"no action $name here")))

/** AN ENTITY'S ROW: its values by property, its version, when it changed */
final case class Row(ent: Ent, values: Map[String, Value], version: Long = 1L, updatedAt: Long = 0L)

/**
 * A SOURCE'S QUERIES OVER ROWS, pure: the class and its subclasses; every word of the text matching a word of the
 * row's visible texts (whole, by prefix, or one edit away from 4 letters); every filter holding — on any property,
 * hidden or not; ranked by the words' matches, then in the rows' order
 */
object Table:
  def find(schema: Schema, rows: Vector[Row], q: Query): Vector[Found] =
    val words = Words.split(q.text)
    rows.iterator.filter(r => schema.isA(r.ent.cls, q.cls))
      .filter(r => q.filters.forall(f => r.values.get(f.prop).exists(v => holds(f.op, v, f.value))))
      .map(r => r -> score(schema, r, words)).filter(_._2 >= 0).toVector.zipWithIndex
      .sortBy { case ((_, s), i) => (-s, i) }.take(q.limit)
      .map { case ((r, _), _) => Found(r.ent, visible(schema, r, q.lang), r.version) }

  /** a set value holds a filter when one of its members does */
  private def holds(op: Op, v: Value, against: Value): Boolean = v match
    case Value.Many(vs) => vs.exists(holds(op, _, against))
    case one => Op.holds(op, one, against)

  /** the row's match of the words: -1 when a word matches nothing; no words: 0 */
  def score(schema: Schema, r: Row, words: Vector[String]): Double =
    if words.isEmpty then 0
    else
      val texts = schema.props(r.ent.cls).filter(_.visible).flatMap(p => r.values.get(p.name)).flatMap(textsOf)
      val have = texts.flatMap(Words.split)
      val each = words.map(w => have.map(h => matching(w, h)).maxOption.getOrElse(0.0))
      if each.contains(0.0) then -1 else each.sum

  private def textsOf(v: Value): Vector[String] = v match
    case Value.Text(t) => t.values.toVector
    case Value.Many(vs) => vs.flatMap(textsOf)
    case _ => Vector.empty

  /** whole 1, prefix 0.8 (3 letters or more), one edit 0.5 (4 letters or more), else 0 */
  def matching(w: String, h: String): Double =
    if w == h then 1.0
    else if w.length >= 3 && h.startsWith(w) then 0.8
    else if h.length >= 4 && w.length >= 4 && h.startsWith(w.take(w.length - 2)) then 0.6     // a stem: broda, brody
    else if w.length >= 4 && edits(w, h) <= 1 then 0.5
    else 0.0

  private def edits(a: String, b: String): Int =
    if math.abs(a.length - b.length) > 1 then 2
    else
      val d = Array.tabulate(a.length + 1, b.length + 1)((i, j) => if i == 0 then j else if j == 0 then i else 0)
      for i <- 1 to a.length; j <- 1 to b.length do
        d(i)(j) = math.min(math.min(d(i - 1)(j) + 1, d(i)(j - 1) + 1), d(i - 1)(j - 1) +
          (if a(i - 1) == b(j - 1) then 0 else 1))
      d(a.length)(b.length)

  /** the visible properties' texts in the language: what the model may read */
  def visible(schema: Schema, r: Row, l: Lang): Vector[(String, String)] =
    schema.props(r.ent.cls).filter(_.visible).flatMap(p => r.values.get(p.name).flatMap(v => shown(v, l))
      .map(p.name -> _))

  private def shown(v: Value, l: Lang): Option[String] = v match
    case Value.Text(t) => Some(Value.in(t, l)).filter(_.nonEmpty)
    case Value.Many(vs) => Some(vs.flatMap(shown(_, l)).mkString(", ")).filter(_.nonEmpty)
    case Value.Bool(b) => Some(b.toString)
    case _ => None

  def get(rows: Vector[Row], ent: Ent, prop: String): Option[Fact] =
    rows.find(_.ent == ent).flatMap(r => r.values.get(prop).map(v => Fact(v, ent, prop, r.version, r.updatedAt)))
