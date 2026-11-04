package okay.facts

import okay.effects.json.Json
import okay.scala3.*

/**
 * THE FACTS OVER THE WIRE: a `Facts` operation as JSON and its answer — how a host asks a source that lives elsewhere
 * (okay-mall: each shop's data read through that shop's own object). `client` performs a source's operations by a
 * call; `serve` answers a call by the local handler
 */
object Wire:
  def request[X](op: Facts[X]): Json = op match
    case Facts.Find(q) => Json.obj("find" -> Codec.query(q))
    case Facts.Get(ent, prop) => Json.obj("get" -> Json.obj("ent" -> Codec.ent(ent), "prop" -> Json.str(prop)))
    case Facts.Act(name, args, l) => Json.obj("act" -> Json.obj("name" -> Json.str(name),
      "lang" -> Json.str(l.toString),
      "args" -> Json.Obj(args.toVector.map((k, v) => k -> Codec.value(v)))))
    case Facts.Prefetch(finds, gets) => Json.obj("prefetch" -> Json.obj(
      "finds" -> Json.Arr(finds.map((q, ps) => Json.obj("query" -> Codec.query(q),
        "props" -> Json.Arr(ps.map(Json.str))))),
      "gets" -> Json.Arr(gets.map((e, p) => Json.obj("ent" -> Codec.ent(e), "prop" -> Json.str(p))))))

  /** an answer read back as the operation's result (a failed call: nothing found, no value, the action refused) */
  def answer[X](op: Facts[X], j: Either[String, Json]): X = op match
    case Facts.Find(_) => j.toOption.flatMap(_.field("found")).collect { case Json.Arr(xs) => xs.map(Codec.foundOf) }
      .getOrElse(Vector.empty[Found])
    case Facts.Get(_, _) => j.toOption.flatMap(_.field("fact")).map(Codec.factOf)
    case Facts.Act(_, _, _) => j.flatMap(x => x.field("acted").map(Codec.actedOf).toRight(x.field("error")
      .collect { case Json.Str(e) => e }.getOrElse("refused")))
    case Facts.Prefetch(_, _) => ()

  /** A PREFETCH'S ANSWER read: per find what it found and, per entity found, the properties asked (absent: none);
   * per get its fact. A failed call: nothing — the later reads ask again */
  def fetched(op: Facts.Prefetch, j: Either[String, Json]): Option[(Vector[(Vector[Found], Vector[Map[String,
    Option[Fact]]])], Vector[Option[Fact]])] =
    def fact(x: Json): Option[Fact] = x match
      case Json.Null => None
      case f => Some(Codec.factOf(f))
    for
      a <- j.toOption
      fs <- a.field("finds").collect { case Json.Arr(xs) => xs }
      gs <- a.field("gets").collect { case Json.Arr(xs) => xs }
      if fs.size == op.finds.size && gs.size == op.gets.size
    yield (fs.zip(op.finds).map { (x, f) =>
      (x("found").list.map(Codec.foundOf), x("facts").list.map(m => f._2.map(p => p -> m.field(p).flatMap(fact))
        .toMap))
    }, gs.map(fact))

  /** a call answered by the local `Facts` */
  def serve[R[_]](j: Json)(using In[Facts, R]): Json ! R =
    j.field("find").map(q => Facts.find[R](Codec.queryOf(q)).map(fs => Json.obj("found" -> Json.Arr(fs.map(
        Codec.found)))))
      .orElse(j.field("get").map(g => Facts.get[R](Codec.entOf(g("ent")), g("prop").string).map(f =>
        Json.obj("fact" -> f.fold(Json.Null)(Codec.fact)))))
      .orElse(j.field("act").map(a => Facts.act[R](a("name").string, a("args").record.map((k, v) =>
        k -> Codec.valueOf(v)).toMap, Lang.parse(a("lang").string).getOrElse(Lang.pl)).map {
          case Left(e) => Json.obj("error" -> Json.str(e))
          case Right(x) => Json.obj("acted" -> Codec.acted(x))
        }))
      .orElse(j.field("prefetch").map(prefetched[R]))
      .getOrElse(pure(Json.obj("error" -> Json.str("find, get or act"))))

  private def seq[R[_], A, B](xs: Vector[A])(f: A => B ! R): Vector[B] ! R =
    xs.foldLeft(pure[Vector[B], R](Vector.empty))((acc, a) => acc.flatMap(bs => f(a).map(bs :+ _)))

  /** a prefetch answered where the data is: each find, the properties asked of each found, each get */
  private def prefetched[R[_]](j: Json)(using In[Facts, R]): Json ! R =
    def fact(f: Option[Fact]): Json = f.fold(Json.Null)(Codec.fact)
    val finds = j("finds").list.map(x => (Codec.queryOf(x("query")), x("props").list.map(_.string)))
    val gets = j("gets").list.map(x => (Codec.entOf(x("ent")), x("prop").string))
    for
      fs <- seq[R, (Query, Vector[String]), Json](finds)((q, ps) => Facts.find[R](q).flatMap(found =>
        seq[R, Found, Json](found)(f => seq[R, String, (String, Json)](ps)(p => Facts.get[R](f.ent, p).map(x =>
          p -> fact(x))).map(Json.Obj(_))).map(facts => Json.obj("found" -> Json.Arr(found.map(Codec.found)),
          "facts" -> Json.Arr(facts)))))
      gs <- seq[R, (Ent, String), Json](gets)((e, p) => Facts.get[R](e, p).map(fact))
    yield Json.obj("finds" -> Json.Arr(fs), "gets" -> Json.Arr(gs))
