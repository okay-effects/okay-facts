package okay.facts

import okay.effects.json.Json

/** A HANDLE the model refers by (`#v1`): the entity it stands for, the version it was found at, what the model was
 * shown of it, and — for an action's result — its values pinned as they were */
final case class Handle(name: String, ent: Ent, version: Long = 0L, visible: Vector[(String, String)] = Vector.empty,
                        pinned: Map[String, Fact] = Map.empty)

/**
 * THE CONVERSATION'S HANDLES (specs/design.md §5): opaque, short, global within the conversation, issued only by the
 * resolver — one entity, one handle, however often found. A name not in the table is a reference the model made up
 */
final case class Handles(all: Vector[Handle] = Vector.empty, next: Map[String, Int] = Map.empty):
  def get(name: String): Option[Handle] = all.find(_.name == name)
  def of(ent: Ent): Option[Handle] = all.find(_.ent == ent)

  /** the entity's handle: the one it has (its descriptors and version refreshed), else a new one of its prefix */
  def issue(prefix: String, ent: Ent, version: Long, visible: Vector[(String, String)],
            pinned: Map[String, Fact] = Map.empty): (Handles, Handle) =
    of(ent) match
      case Some(h) =>
        val fresh = h.copy(version = version, visible = if visible.isEmpty then h.visible else visible,
          pinned = h.pinned ++ pinned)
        (copy(all = all.map(x => if x.name == h.name then fresh else x)), fresh)
      case None =>
        val n = next.getOrElse(prefix, 0) + 1
        val h = Handle(s"#$prefix$n", ent, version, visible, pinned)
        (Handles(all :+ h, next.updated(prefix, n)), h)

object Handles:
  /** the handle table as JSON, for the conversation's storage; facts pinned with their values */
  def toJson(h: Handles): Json = Json.obj(
    "all" -> Json.Arr(h.all.map(x => Json.obj("name" -> Json.str(x.name), "ent" -> Codec.ent(x.ent),
      "version" -> Json.num(x.version), "visible" -> Json.Arr(x.visible.map((k, v) =>
        Json.arr(Json.str(k), Json.str(v)))), "pinned" -> Json.Obj(x.pinned.toVector.map((k, f) =>
        k -> Codec.fact(f)))))),
    "next" -> Json.Obj(h.next.toVector.map((k, n) => k -> Json.num(n))))

  def fromJson(j: Json): Handles = Handles(
    j("all") match
      case Json.Arr(xs) => xs.map(x => Handle(x("name").string, Codec.entOf(x("ent")), x("version").long,
        x("visible") match
          case Json.Arr(ps) => ps.map(p => p.list(0).string -> p.list(1).string)
          case _ => Vector.empty,
        x("pinned") match
          case Json.Obj(fs) => fs.map((k, f) => k -> Codec.factOf(f)).toMap
          case _ => Map.empty))
      case _ => Vector.empty,
    j("next") match
      case Json.Obj(fs) => fs.map((k, n) => k -> n.int).toMap
      case _ => Map.empty)
