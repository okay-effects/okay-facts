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
- **The router lane, next** (§14.6, §16): wrong intents among the router's answers (14 of 165 in-domain turns: a
  mall-wide search taken for `masters`, an area question for `address`) and ordinary questions sent to `other` —
  more phrasings and a new held-out set; the table 128 wide (half the bytes to read in a new isolate); a modes
  question in a shop without modes says the address's absence (`ShopRouter.one`'s fallback) — its own answer.
