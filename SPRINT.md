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
- [x] **the guard answers** (operator 2026-11-04: a guarded turn never goes to the model lane; done 2026-11-04:
  released okay-facts and okay-shop 0.1.0, okay-mall deployed, verify-apex 27/27; false positives 11 → 1, §16) — §17 "The turn
  decision" step 1, §16. Plan: 1. okay-facts: the guard as categories in data (`{category, patterns}`), `Route.guard`;
  tests; release 0.1.0. 2. okay-shop: `intents.json`'s guard in categories (injection, others, bargain, health,
  payments, amenities, advice, domain), narrowed by the false positives measured on every set (okay-mall
  `scripts/guard-fp.scala`: 11 before), an authored answer per category (`answers.json` `guard.*`, pl/uk/ru/en, the
  shop's values by the resolver: contact or page, services, a price in context, the address); tests; release.
  3. okay-mall: the answers taken (no model), counters per category in the shop's chat counts and the home's, the
  dashboard's chat column, a strike count per conversation (3 injections or others' data → 10 minutes of the fixed
  answer); tests; deploy; verify-apex 27/27; screenshots.
- [x] **router lane, round 4** (operator 2026-11-04, after the guard answers; §17 "Round 4"; done 2026-11-04: released
  okay-facts and okay-shop 0.1.0, okay-mall deployed, verify-apex 27/27; the held-out run: 83.9% at 88.9%, the 97% not
  met; §14.6, §16). Plan (spec first;
  releases under 0.1.0; okay-mall deployed; verify-apex 27/27):
  0. A NEW held-out set (≥250 turns, an independent author who saw only the demo shops' data) written and FROZEN
     (sha256) in okay-mall measure/2026-11-04-r4/ before any tuning; run once.
  1. okay-facts: `Places` (gazetteer matching), `tools/gazetteer.scala` (forms generated: Polish declension,
     Ukrainian/Russian transliteration and declension, exonyms), `Prop.comparableBy` and the violation `comparable`;
     tests; release.
  2. okay-shop: `assistant/places.tsv` (cities over 20 000 — GUS 2021 —, the districts of the 10 largest cities,
     the demo shops' places; names as TERYT has them) → `places.json`; the place slot and area answers by it; a shop
     named on the mall's home in both scripts with typos; the mall's own contact; `kind` of a service (`About.kinds`)
     and comparisons of the same kind only (`compare.none.kind`); the confusion's pairs fixed by evidence and the
     close ones asked back (`clarify` with suggestions; the widget's buttons); tests; release.
  3. okay-mall: the kinds from the catalogue; the gazetteer in the search and the geocoder; the mall's contact as a
     fact; the measurement (auto) on the new set beside the old rows, the guard's false positives on it; deploy.
- [ ] **the guard independent of the router** (operator 2026-11-05; §17 "The turn decision" step 1) — today the guard
  runs only inside the router: `CHAT_ROUTER=off` or a table that failed to load sends guarded turns to the model. Plan:
  1. okay-facts: `Guard` (the patterns alone, no table), the router holding it; `Table.empty`; tests; release 0.1.0.
  2. okay-shop: `ShopRouter.guardTurn` (no table: the answers' services by words), `guardReady` (the data whole),
     `turn` guarded first by it; tests; release 0.1.0; szykownia and it-consulting tested.
  3. okay-mall: the guard first in every turn (router on, off, table missing, a lane forced, the host); its data
     failed: the chat closed (a fixed answer, `Log.error`, the monitor's `chat.guard`); `CHAT_STRIKES`,
     `CHAT_COOL_MINUTES`; tests; deploy; verify-apex 27/27; production checked.
