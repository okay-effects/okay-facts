# BACKLOG — okay-facts

Stages in order; each is specified in specs/design.md §14 (what, done-when). Promote the next one to SPRINT.md
before starting it. Stages 1–5 are done (SPRINT.md).

6. **measure** (in SPRINT with the router lane) — usual (data in context) vs okay-facts on the same TCK
   questions and model: wrong prices, slots, bookings, tokens, latency (§14.6). The TCK's runner gives the
   okay-facts lane; the usual lane needs a prompt with the rows as text and a checker of its free text.
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
