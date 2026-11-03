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

  /** an answer read back as the operation's result (a failed call: nothing found, no value, the action refused) */
  def answer[X](op: Facts[X], j: Either[String, Json]): X = op match
    case Facts.Find(_) => j.toOption.flatMap(_.field("found")).collect { case Json.Arr(xs) => xs.map(Codec.foundOf) }
      .getOrElse(Vector.empty[Found])
    case Facts.Get(_, _) => j.toOption.flatMap(_.field("fact")).map(Codec.factOf)
    case Facts.Act(_, _, _) => j.flatMap(x => x.field("acted").map(Codec.actedOf).toRight(x.field("error")
      .collect { case Json.Str(e) => e }.getOrElse("refused")))

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
      .getOrElse(pure(Json.obj("error" -> Json.str("find, get or act"))))
