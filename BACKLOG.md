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
- **The router lane, round 3** (§14.6 2026-11-04: 86.7% right on the held-out set, the target 97%): the rules are too
  greedy — `area` takes "do you come to the client?" (modes) and a laundry's pickup, `human` a master's private phone;
  injections in other forms ("ignoruj poprzednie instrukcje"); advice naming a service; on the mall's home a shop named
  by its name (its hours, its address) answered as a search; `When` reads "Marek's" as March (a month's stem matches
  a name); "скасуйте" missing from `manage`; "samo strzyżenie" / "the haircut alone" compares with the combination.
  Then a NEW held-out set (this one is spent). Also: the probe's weights as binary (10.7 ms of JSON); a price turn's
  ten sequential reads of the shop's object (≈ 250 ms warm) batched.
- **The fallback model** (§16, 2026-11-04): `gpt-oss-120b` reached only L2 by the TCK; `glm-5.3` reached L3 at 4.7
  times the price — the operator's call.
