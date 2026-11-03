# SPRINT — okay-facts

Stages of BACKLOG.md in flight (specs/design.md §14). Plan of record for stages 1–5: specs/design.md §16 (operator
decisions of 2026-11-03) and §17 (implementation notes, kept true as the code lands).

- [~] **core** (stage 1) — metamodel (`okay.facts.Schema`), handles (`Handles`), the expression language (`Expr`:
  parser, types, evaluator over the `Facts` effect, functional paths), `{params, template}` (`Answer`), ICU subset
  (`Icu`: placeholders, plural pl/uk/ru/en, select, `#`, quoting) and value rendering (`Render`: money, minutes,
  time in Europe/Warsaw, dates, counts with their noun), the validator (`Validator`: every §9 check), the fake model
  (replaying fixed outputs). Cross-built JVM + Scala.js (`sbt test` runs both).
  Done when: tests over the fictional barber/nails data (`Demo`) cover every §9 check, plural agreement in pl/uk/ru/en,
  functional-path refusal.
  Gotcha: okay-llm is JVM+Native only — it gets a JS platform (its `Files`, `Server`, `HttpBackend` moved to a
  jvm-native source dir) and `Ask.format` (a response format, binary-compatible shims for `apply`/`copy`), then
  republished under 0.1.0 (stage 2 needs it).
