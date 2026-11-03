# SPRINT — okay-facts

Stages of BACKLOG.md in flight (specs/design.md §14); decisions and results in §16.

- [x] **core** (stage 1) — the metamodel, handles, the expression language, `{params, template}`, the ICU subset with
  plurals pl/uk/ru/en, the validator (every §9 check, and worded comparisons as claims), the fake model port; JVM and
  Scala.js. Done: `CoreSuite`, `BindSuite` (42 tests both platforms). Released 0.1.0 (republished as it grew).
- [x] **binding** (stage 2) — a turn over okay-llm's `Llm` (L0 markers and command lines, L2 tools, L3 JSON schema),
  policies, focus, audit, params-first streaming (`Stream`/`Emit`), `Wire`; `okay-facts-cloud`: Workers AI as the
  port (retries on a model's per-minute limit, a fallback model). Done: the same §12 answer renders identically by
  L0 and L3 (`BindSuite`); okay-llm got Scala.js and `Ask.format` (republished 0.1.0).
- [x] **tck** (stage 3) — 36 questions, the passport; `tck/worker/run.sh`. Done: passports of ten Workers AI models,
  the final run of four at L3 and two at L0 in §16; chosen deepseek-v4.1-flash-1017 (L3, policy regenerate), fallback
  gpt-oss-120b.
- [x] **okay-shop assistant** (stage 4) — okay-shop's `ShopFacts`, `Bookings.chat`, the widget, the privacy section
  (okay-shop specs/shop.md "The assistant", `AssistantSuite`); released okay-shop 0.1.0.
- [x] **okay-mall chat** (stage 5) — `ChatObject`, the cookie, focus, `MallFacts`, `ChatGuard`, per-shop switch,
  audit and dashboard; §12's flow on MemoryCloud, wrangler dev, production (every shop and the landing, 2026-11-03).
- [x] **router lane** (stage 8, §17; operator 2026-11-03) and **measure** (stage 6, §14.6) — done 2026-11-03: released
  okay-facts 0.1.0 (with okay-facts-router) and okay-shop 0.1.0, deployed on every shop of the mall and its home;
  §14.6 measured (the router 86% of 180 turns, 0 wrong facts in every lane). Plan, as it went:
  1. okay-facts: module `router` (`Re`/`Rules` portable, `Table` F16 in place, `Probe`, `Detect`, `When`, `Amount`,
     `Nearest`, `Router`); core `Morph` + case hints + validator `case` + `Bind.authored` + `Audited.Routed`; tools
     `table.scala`, `fit.scala`, `coverage.scala`; tests both platforms; release 0.1.0 (republished).
  2. okay-shop: the shop's intents (15) as data with authored phrasings pl/uk/ru/en, the authored answers, `ShopDesk`
     (the router lane over `ShopFacts`), masters' name forms, the widget's lane in the debug view; tests; release.
  3. okay-mall: the table in R2 (read on an isolate's first request; the host from its blobs' file), the turn
     decision in `ChatObject` (lane/intent/confidence logged), the Directory's semantic scorer; the 150-turn set and
     the measurement; deploy; verify-apex 27/27.
