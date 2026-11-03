# okay-facts

A fact resolver for LLMs. The model never receives or writes facts: it refers to them by handles in a template with
named parameters (like JDBC's named parameters), and okay-facts evaluates and renders every value from your data and
ontology, with a provable source.

```json
{"params": {"s": "name(#v1)", "p": "price(#v1)", "t1": "time(#s1)"},
 "template": "Tak, {s} kosztuje {p}. Jutro wolne: {t1}. Zapisać?"}
```

→ "Tak, Strzyżenie + broda kosztuje 90,00 zł. Jutro wolne: 17:30. Zapisać?" — every value a chip with its entity and
source. *The model cannot get a value wrong; it can only choose the wrong reference, and that error is always visible
and checkable.*

- **core** (`okay-facts`, JVM and Scala.js): the metamodel, handles, the expression language (paths, aggregates,
  comparisons), `{params, template}` as an ICU message (plural rules of pl, uk, ru, en; pure Scala), the validator,
  the binding loop over [okay-llm](https://github.com/okay-effects/okay-llm)'s `Llm` port (L0 markers to L3 JSON
  schema; policies regenerate, flag, block; params-first streaming), the audit, the TCK.
- **cloud** (`okay-facts-cloud`): the Workers AI binding as the `Llm` port, on
  [okay-serverless](https://github.com/okay-effects/okay-serverless)' `Ai`.

The design: [specs/design.md](specs/design.md). First user: a mall's chat across its shops
([okay-shop](https://github.com/okay-effects/okay-shop)'s storefront assistant).

```scala
resolvers ++= Seq("okay-facts" at "https://okay-effects.github.io/okay-facts/maven",
  "okay-llm" at "https://okay-effects.github.io/okay-llm/maven",
  "okay-scala" at "https://okay-effects.github.io/okay-scala/maven",
  "okay-simple" at "https://okay-effects.github.io/okay-simple/maven")
libraryDependencies += "io.github.okay-effects" %%% "okay-facts" % "0.1.0"
```

Apache-2.0.
