package okay.facts

/** A PROPERTY of a class: its type (a value's, or a link's `Type.Entity`), whether the model may see it (`visible`:
 * names, a category, a city — never a price, a time, a stock), whether it yields one value (`functional`: a path
 * through it is exact) */
final case class Prop(name: String, tpe: Type, visible: Boolean = false, functional: Boolean = true,
                      about: String = "")

/** A CLASS of the metamodel (specs/design.md §4): its name, its parents, its properties, the prefix of its handles
 * (`v` → `#v1`), its key (identity), the vocabulary term it comes from (`https://schema.org/Service`) */
final case class Cls(name: String, prefix: String, props: Vector[Prop], parents: Set[String] = Set.empty,
                     key: Vector[String] = Vector.empty, iri: String = "", about: String = "")

/** THE METAMODEL a deployment binds by: classes, their hierarchy, their properties. An ontology's importer makes one;
 * an overlay adds the keys and the cardinalities a vocabulary leaves out */
final case class Schema(classes: Vector[Cls]):
  def cls(name: String): Option[Cls] = classes.find(_.name == name)

  /** whether `sub` is `sup` or below it */
  def isA(sub: String, sup: String): Boolean =
    sub == sup || cls(sub).exists(_.parents.exists(isA(_, sup)))

  /** a property of a class, its own or inherited */
  def prop(clsName: String, name: String): Option[Prop] =
    cls(clsName).flatMap(c => c.props.find(_.name == name).orElse(c.parents.iterator.flatMap(prop(_, name))
      .nextOption()))

  /** every property of a class, its own first */
  def props(clsName: String): Vector[Prop] =
    cls(clsName).toVector.flatMap(c => c.props ++ c.parents.toVector.flatMap(props)).distinctBy(_.name)

  def prefix(clsName: String): String = cls(clsName).map(_.prefix).getOrElse("x")
