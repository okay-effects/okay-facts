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
- [x] **router lane, round 2** (done 2026-11-04: released okay-facts, okay-shop, okay-serverless 0.1.0; okay-mall
  deployed; §14.6 the held-out row, §16 the decisions; round 3 in BACKLOG) (BACKLOG "The router lane, next"; operator 2026-11-03) — accuracy, cold start, the TCK
  with `case`, the modes bug, measured again on a NEW frozen held-out set. Plan:
  1. Error analysis of every miss of measure/2026-11-03 (intent, slots), by cause → okay-mall measure/2026-11-04/.
  2. Held-out set (≥200 new turns, another author, 4 languages, out-of-domain and adversarial) written and FROZEN
     (its sha256) before any tuning; tuning only on the old 180 + dev 45 + the intents' examples (train/dev).
  3. Data first (okay-shop assistant/intents.json: phrasings, negatives for `other`, typos, no diacritics,
     transliterations, mixed language, short follow-ups), then features/rules (combined slots+service+time, price
     limits, parts of the day; `When`: weekend, "в пятницу", "після 16", "after 4pm"; masters in any case); the
     threshold calibrated for router precision ≥ 97% on dev (§16).
  4. The no-data branches: modes without modes → its own authored answer; every intent × missing data tested.
  5. Cold start: where the 0.9 s goes (fetch, copies, F16, JSON of the probe), then the fix (128 wide / int8 /
     zero-copy / cache / prewarm) — target < 150 ms CPU, first answer p50 < 1 s, ≤ 1 point accuracy lost.
  6. TCK again (deepseek-v4.1-flash-1017, gpt-oss-120b, newer tool+JSON models), passports in §16.
  7. Releases (okay-facts, okay-shop; okay-serverless if touched) under 0.1.0, okay-mall deployed, verify-apex 27/27;
     the measurement on the held-out set (auto / model / usual), §14.6 a new dated row beside the old.
- [x] **router lane, round 3 + the fallback model** (operator 2026-11-04; done 2026-11-04: released okay-facts and
  okay-shop 0.1.0; okay-mall deployed; §14.6 round 3's rows — the router 76.9% at 91.9%, the 97%
  not met; §16 the curve, the fixes, the fallback verified; round 4 in BACKLOG).
  Plan (spec first; tests; releases under 0.1.0; okay-mall deployed; verify-apex 27/27):
  1. A NEW held-out set (≥300 turns, an independent author, another style; ≥15% for the model, injections in 4
     languages, typos, Latin letters, follow-ups) written and FROZEN (sha256) in okay-mall measure/2026-11-04-r3/
     before any tuning. The 2026-11-04 set (spent) joins train/dev with the 2026-11-03 ones.
  2. Spec §17 "The turn decision": rules are evidence, not verdicts — a guard (injection, meta, others' data,
     payments, advice, out of domain → the model lane), a short list of safe deterministic rules (greet, thanks,
     help), and a calibrated combiner (rules' hits + the probe + the slots' evidence → p) for everything else; the
     threshold for router precision ≥ 97% on k-fold over train/dev; the precision–coverage curve in §16.
  3. Fixes: at-client (modes) vs area; a laundry's pickup; the mall's home: a shop named, a price of one service;
     `When`: a month only with a day's number ("Marek's", "u Marka"); cancel stems (скасуйте, отмените,
     odwołajcie); "only the haircut" comparisons; injections in more wordings.
  4. Speed: a turn's reads batched (`Facts.Many`, `Facts.Expand`: one call to a shop's object); the probe and the
     combiner as binary; a first message with no open < 1 s p50 (measured).
  5. The fallback model `glm-5.3` (§16, okay-mall's runbook), verified once on production by an admin-only forced
     fallback.
  6. The measurement on the new set, run once: auto / model / usual beside 2026-11-03 and 2026-11-04 (§14.6).
- [x] **distance and near** (operator 2026-11-04: okay-mall's geolocation, distance and maps) — `distance(#a, #b)`,
  `Value.Point`, `Value.Meters`, the router's `Near` slot (§6, §17); released with okay-mall's maps.
