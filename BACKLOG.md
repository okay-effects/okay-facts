# BACKLOG — okay-facts

Stages in order; each is specified in specs/design.md §14 (what, done-when). Promote the next one to SPRINT.md
before starting it. Stages 1–5 are done (SPRINT.md).

6. **measure** — done with the router lane (SPRINT, §14.6). Next: the same on a large catalogue and on data changed
   after loading (§13), where the usual way's errors are expected.
7. **demo, proxy, MCP, importers** — §13, §11, §4.

Found while building (2026-11-03):

- **Names in their cases** (Polish "u Marka", Ukrainian/Russian likewise): forms by case in the data, a hint
  `name(master(#s1)) as gen`; until then the prompt avoids case-bound names.
- **Worded claims beyond the stem list**: "less", "more", "mniej", "więcej" are not caught (too common); a better
  check: a comparative next to a value's placeholder.
- **Streaming through the Workers AI port**: needs okay-serverless' `Ai` to stream (`stream: true`); the library's
  `Stream` is ready.
- **Strict mode**: no Workers AI model reaches 90% strict (§16); revisit with new models by the TCK.

9. **rules and constraints** (operator, 2026-11-03; after the router lane and the measure) — derived facts and found
   solutions as resolver facts with proofs: `require` / `prefer` / `solve` in `Expr`, a Datalog handler (the shops'
   rules), SAT / weighted MaxSAT with UNSAT cores as explanations, the model's hints ordering the search, intent
   facts apart from data facts. specs/design.md §18.
- **The router lane, round 4** (§14.6 round 3: 91.9% right on a fresh held-out set at 76.9% answered, the target
  97%): a Cyrillic place's evidence (at the client's vs the area), advice and payments in more words for the guard
  ("заплатити", "w domu" for a stain), a named shop on the mall's home asking its hours, free times vs a booking, a
  comparison's relevance (an unrelated cheaper service: an exact value of a wrong reference); then a new held-out set
  (round 3's is spent).
- **The router lane, round 5** (§14.6 round 4: 88.9% right at 83.9% answered on a fresh held-out set, the target 97%):
  places typed in Latin letters for Cyrillic decided as the area when the combiner says a price; a shop named with a
  typo or in a Cyrillic transliteration of its Polish name on the mall's home; the hours in short words ("графік?",
  "по вторникам работаете?"); advice in a service's words; the place slot's label; then a new held-out set (round 4's
  is spent).
