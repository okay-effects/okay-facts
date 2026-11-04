# okay-facts — design (spec, 2026-11-03)

Status: specification, nothing implemented. Written from the operator's design session of 2026-11-03; the operator
assigns the implementation to another agent. Read this whole file before writing code; keep it true after.

## 1. What it is

okay-facts is a **fact resolver for LLMs**. It stands on the path of the answer from the model to the user:

- the model gets **handles** (`#s1`, `#v7`), never values;
- the model answers with a **template and named parameters** whose bindings are **expressions over handles**;
- okay-facts evaluates the expressions against the data, renders the values in the user's language, and hands the
  user a text in which every fact came from the data, each with its source.

The analogy is JDBC's prepared statement with named parameters (Spring's `NamedParameterJdbcTemplate`, `:name`):
the model writes the statement, the resolver binds the values. A value can never come from the model, as a value can
never become part of the SQL.

It is **not RAG**: RAG puts the data into the model (text in the context) and the model retypes it; okay-facts keeps
the data out of the model. It is **not MCP**: MCP is one of its interfaces, the weakest (§11). Both stay useful next
to it: RAG for documents and free text, MCP/tools for actions.

## 2. The rule

> The model never receives, writes or computes a fact. It refers; we resolve.

Closed on three sides:

1. **Input** — searches return handles, not values (§5). Properties the model may see for understanding the request
   are marked `visible` (§4); volatile and numeric ones (`price`, `stock`, `time`) are `hidden` and never reach it.
2. **Computation** — comparisons, sums, minima, counts, "is it in stock" are derived facts: the model writes an
   expression, the resolver computes it (§6).
3. **Output** — the model's answer is `{params, template}` (§7); the template may contain no fact (no digits, no
   declared entity names); the validator enforces it (§9), and on capable models the schema/grammar makes a violation
   impossible (§10).

### The honest boundary

Guaranteed: every value in the answer comes from the resolver, exact, with its source and version; every derived
value is computed by the resolver; a handle can only point at something found in this conversation.

Not guaranteed: **the choice of reference**. The model can bind `price(#v2)` where it talks about `#v1` — the value is
exact but belongs to another entity (as a prepared statement still lets you select the wrong column). Mitigations
(all required): a value chip always shows its entity ("90,00 zł — Strzyżenie + broda"); structured answers bind an
item's fields to its own handle; claims like "under 200 zł" are themselves expressions the resolver evaluates.
State the guarantee in user-facing docs exactly like this: *the model cannot get a value wrong; it can only choose the
wrong reference, and that error is always visible and checkable.*

## 3. Architecture

```
user ◄── binding layer: {params, template} → rendered text + facts[] with sources ◄── model (any)
              │                                                                       ▲
              └── resolver: ontology, data sources, find, expressions, audit ────────┘ (handles only)
```

Two parts:

- **Resolver** — knows the ontology and the data; executes queries (`find`), evaluates expressions, returns typed
  values with provenance, writes the audit.
- **Binding layer** — runs the conversation with the model: gives it handles only, makes it answer in the template
  format, validates, renders, streams.

In okay-effects terms: a data read, a query, a model call and an audit record are effects; data sources, model
providers and audit sinks are handlers; interceptors apply policy (tenancy, rate limits, forbidden properties).

## 4. Ontology: the metamodel and importers

The core never depends on a particular ontology. It works on a small internal **metamodel** holding only what binding
needs:

| Metamodel element | Why |
|---|---|
| classes, subclass hierarchy | typed search; inference ("a dress is clothing") |
| properties: datatype and object (links) | parameters and paths |
| value types with formatting (`money(PLN)`, `duration`, `time`, `count`, `text`, `url`) | rendering |
| identity (keys) | how an entity is found and addressed |
| functional / cardinality | when a path yields exactly one value (§6 "paths") |
| constraints | the validator's checks on data and claims |
| visibility policy per property: `visible` / `hidden` | §2 input side |
| mappings between vocabularies | several ontologies at once |

**Importers** translate into the metamodel (each one is a handler; adding one never changes the core):

- OWL / RDFS (Turtle, RDF/XML) — inference only for the decidable OWL 2 profiles **RL** (rules), **EL** (large
  hierarchies), **QL** (queries over SQL); anything beyond is taken as vocabulary without inference;
- SHACL — shapes: cardinalities, types, keys; also the **overlay** mechanism below;
- schema.org / JSON-LD — web vocabularies, page markup, product/service feeds (okay-shop's `Structured.business`
  already writes a shop as one schema.org node with its offers);
- LinkML, JSON Schema, GraphQL SDL;
- SQL DDL / database introspection — tables → classes, FK → links, PK/UNIQUE → keys and functionality;
- okay-store records.

**Overlay**: ontologies rarely declare keys (`owl:hasKey`) or cardinalities. Missing ones are added as SHACL shapes or
our annotations on top, never by editing the source ontology. Without them the core cannot know whether a path is
exact: it refuses (like a compiler refusing a non-unique join) or returns a set marked as such.

**Mappings** (`owl:equivalentClass`, `owl:sameAs`, SKOS `exactMatch`, R2RML / JSON-LD contexts for SQL columns) are
proposed by a model during onboarding if useful, **approved by a human**, and frozen as data. Never mapped on the fly
at runtime.

**Big ontologies** (SNOMED-size) never go into the prompt: the model gets an ontology search tool (like tool search)
returning the relevant classes and properties.

## 5. Handles and queries

### Handles

- Opaque, short, per conversation: `#s1`, `#v7`, `#m2`, `#shop3`. The model never sees real ids (SKUs, slot keys).
- Each handle is stored in the conversation (§12 `ChatObject`) as `(source, entity key, version)`, e.g.
  `#s1 → (barber-demo, slot/2026-11-04T17:30/marek, v40)`. Handles are **global within the conversation**: one found
  in another shop stays valid after the focus moves.
- A handle the resolver did not issue in this conversation is rejected (the model cannot invent references).

### Queries

The model calls typed tools; the resolver executes them exactly and returns handles plus `visible` descriptors only.

```
find(class, filters…, scope?)  → [#handle: visible descriptors]
  find(Service, matches = "strzyżenie + broda")                         → #v1: "Strzyżenie + broda"
  find(Slot, service = #v1, date = tomorrow, after = 17:00)            → #s1, #s2        (times hidden)
  find(Service ⊓ Manicure, city = Kraków, price ≤ 150 PLN, scope = mall) → #v7, #v9, #v12
```

- **Filtering by hidden values is done by the resolver** ("≤ 150 PLN" is applied to data the model never sees).
- `scope`: `focus` (default: the current shop, or the mall when on the mall's pages), `mall`, or a shop handle.
- Tool arguments use strict JSON Schemas generated from the ontology (classes, properties, operators, value types).

### Actions

Actions are tools too, taking handles: `open(#shop3)` (the resolver computes the URL — a URL is a fact),
`book(#s1, client)`. An action's confirmation is rendered by the resolver from the action's result (pinned version,
§8). The client's own data (name, phone) said in the chat is stored in the conversation and bound by handles
(`#client`), never retyped by the model.

## 6. The expression language

What the model writes inside `params`. Small, typed, evaluated only by the resolver.

- **References**: `price(#v1)`, `time(#s1)`, `master(#s1)`, `name(#shop3)`, `url(#shop3)`.
- **Paths** over links: `price(item(#order1))`, `city(address(#shop3))`. A path is exact only if every property on it
  is functional (keys/cardinality from the ontology or the overlay); otherwise the expression is rejected at
  validation, or typed as a set. (Why: a non-unique join averages two rows into a value that belongs to neither — see
  the okay-inlay notes; `owl:FunctionalProperty` is the name of the condition.)
- **Aggregates**: `sum`, `min`, `max`, `argmin`, `argmax`, `count`, `avg` over handles or over `slots(#v1, tomorrow)`.
- **Comparisons and arithmetic**: `price(#v1) - price(#v7)`, `price(#v1) < price(#v7)` (a boolean renders as a phrase).
- **Phrases**: derived facts rendered as whole localized phrases, e.g. `stockText(#v1, M)` → "w magazynie 3 szt." /
  "brak"; `available(#s1)`; so that factual claims in words ("in stock", "free tomorrow") also come from the resolver.
- **Distance** (2026-11-04, okay-mall's maps): `distance(#shop1, #here1)` — two places, each an entity's `point`
  (a property of type `Point`, hidden) or a handle's pinned point — the great circle's metres (`Value.Meters`,
  haversine on the mean radius), rendered "850 m" (to 10 m), "1,2 km" (a decimal below 10 km), "12 km"; against a bare
  number in kilometres (`distance(#shop1, #here1) <= 3`). A client's place enters only as a handle the host issued
  with its point pinned (`#here1`), never a value of the model's.
- **Formatting hints** (optional): `price(#v1) as short`.

Type errors, unknown handles, non-functional paths → validation failure (§9).

## 7. The answer format: `{params, template}`

```json
{
  "params": {
    "service":  "name(#v1)",
    "price":    "price(#v1)",
    "duration": "duration(#v1)",
    "t1":       "time(#s1)",
    "m1":       "master(#s1)",
    "t2":       "time(#s2)"
  },
  "template": "Tak, {service} kosztuje {price} i trwa {duration}. Jutro wolne: {t1} u {m1} albo {t2}. Zapisać?"
}
```

| JDBC | okay-facts |
|---|---|
| SQL with `:price` | `template` with `{price}` |
| `setParameter("price", value)` | `params.price = "price(#v1)"` — an expression, never a value |
| the driver binds | the resolver evaluates and renders |

- `params` comes **before** `template` in the schema: models generate fields in schema order, so the resolver
  evaluates the parameters while the model is still writing the template, and the template streams to the user with
  the values already in (§10 streaming).
- The template is an **ICU MessageFormat** message (placeholders, plural/select). Rendering of values is by the
  resolver with the user's locale (pl, uk, ru, en first).
- **Plural and case agreement** (pl/uk/ru): a bare number next to model-written words breaks agreement ("1 minut",
  "5 miejsca"). Values of countable types render **together with their agreeing noun** by ICU plural rules
  ("45 minut", "1 wolne miejsce", "3 wolne miejsca"); the template never writes the unit after a placeholder.
- Rendered output: the plain text plus `facts[]` — per parameter: rendered value, type, entity (handle, name),
  source (`source`, key, property), version, `updatedAt`. Clients draw parameters as **chips** (value + entity +
  source on hover); a slot chip is clickable (`book`), a shop chip links to the shop.

Models without JSON output (L0–L1) use inline markers with the same expressions — `⟦price(#v1)⟧` — parsed into the
same `{params, template}` before validation.

## 8. Rendering, versions, provenance, audit

- By default a value renders **current** at render time (a price changed between turns shows the new price).
- Action confirmations **pin** the version they acted on ("booked for 17:30, 90,00 zł" never changes afterwards).
- Every evaluated parameter is an audited read: who (conversation, tenant), what (source, key, property, version),
  when, in which answer. The audit sink is a handler (okay-mall: the shop's object, the operator's dashboard).
- Tenancy is an interceptor: a conversation can read only the sources its scope allows; in a mall every shop's data
  is read through that shop's own object.

## 9. The validator

Runs on every answer before rendering. Policy per deployment: `regenerate` (re-ask with the violation named; the
default), `flag`, `block` (regulated cases: better no answer than an unbacked number). No silent `replace` in strict
mode.

Checks:

- the **template** contains no digits and no declared entity names/values outside placeholders (a plain scan of one
  string — this is why the template is separate from the bindings);
- every placeholder has a parameter and every parameter is used;
- every **parameter** parses, type-checks, references only handles issued in this conversation, and every path is
  functional;
- claims written as expressions evaluate (a model's "under 200 zł" is `price(#v1) <= 200 PLN`, evaluated).

## 10. Enforcement by model capability

okay-facts detects what a model can do and uses the strongest mode:

- **L0** — text only. Inline markers; queries by a text protocol (`⟦find …⟧` lines executed, the conversation
  continued); render after the answer. Values exact; the validator catches violations.
- **L1** — + streaming. The same, rendered live.
- **L2** — + tool calls. Native `find` and actions: exact search and filters.
- **L3** — + structured outputs / JSON Schema. `{params, template}` by schema: the answer has no place for a value.
- **L4** — + grammar / logits (local models: okay-mlx, llama.cpp). Constrained generation: digits and declared
  entities impossible outside placeholders — a violation is technically impossible.
- **L5** — own weights. An okay-inlay module inside the model (research, not in scope here): by construction.

Strict mode requires L3 or L4; L0–L2 run with policy `regenerate`/`block`.

**Streaming**: with `params` first, the resolver evaluates them as soon as the `params` object closes; the template
then streams rendered. A placeholder split across chunks (`{pri` + `ce}`) is held back until closed.

## 11. Connecting models and agents

**The rule of connection: the strict guarantee exists only where okay-facts stands between the model and the user.**

1. **Library / SDK** — your app calls okay-facts, it calls the model; the model writes the template, okay-facts the
   values. **Strict.**
2. **Proxy** (Anthropic- and OpenAI-compatible endpoints) — the app changes `base_url` (and sends
   `X-Okay-Facts: <tenant>/<schema>`); okay-facts runs the tool loop inside and returns the bound text and `facts[]`.
   **Strict.**
3. **MCP server** — a foreign agent (Claude Code, Desktop, Cursor…) calls `find`, `get_fact`, `verify_answer`; the
   foreign model writes the final answer. **Verification and audit only.**

- The library is one line: `ShopFacts.bind(model = …)(request)`.
- The MCP mode returns a **verdict** on an answer (each number: green — backed by a bound fact; red — no source or a
  mismatch; grey — not a fact) and, where the host supports MCP UI resources (MCP Apps), cards drawn by the host from
  data. It must be documented as verification, not as the guarantee.

**Model ports** go through okay-llm (effects + gateway), four adapters cover almost everything: Anthropic Messages
(Claude direct, Bedrock, Vertex, Foundry), OpenAI-compatible (OpenAI, Mistral, DeepSeek, Groq, Together, OpenRouter,
vLLM, Ollama, LM Studio, Workers AI's compatible endpoints), the Workers AI binding (inside a Worker), local
(okay-mlx, llama.cpp — with logits/grammar).

**TCK (conformance suite)**: ~100 questions over a reference ontology and dataset, run against a model; measures
marker/template discipline, violations (digits in templates, invented handles, forbidden properties), query
correctness (classes, properties, filters), tokens and latency per answer. Output: the model's passport — level,
recommended mode and policy. Run it before enabling any model.

## 12. okay-mall: one chat across the mall and its shops

okay-mall (private, `~/work/my/okay-mall`) is the first user. Its shops are service businesses (atelier, barber,
nails, yoga, laundry, handyman, phone and shoe repair): services with prices in PLN and durations, masters with
calendars, free slots, opening hours, address, reviews, in pl/uk/ru/en. Each shop is a `ShopObject` Actor (`SHOPS`,
by the shop's id); the list is `Registry`; okay-shop's `Structured.business` already writes a shop as schema.org.

### Requirements (operator, 2026-11-03)

- The chat works **across all shops** (finding the right one) and **inside one shop** when the user is on its pages.
- Moving between shops keeps the conversation's context.
- The model never handles values; everything resolves in our Worker; answers are templates with named parameters.

### The conversation object

A new Actor, **`ChatObject`, one per conversation**, holding: the dialogue history; the **handle table** (§5); the
**focus** (`mall` or `shop:<id>`); the client's data said in the chat (bound by `#client`).

### Sessions across subdomains

Shops live on subdomains of `MALL_DOMAIN` (`barber-demo.szykownia.pl`, `nails-demo.szykownia.pl`, the mall on the
apex). localStorage is per origin, so the conversation id is a **cookie on the mall's domain** (`Domain=.<MALL_DOMAIN>`,
`Secure`, `HttpOnly`, `SameSite=Lax`; the domain read from `MALL_DOMAIN`, never written in code). The widget on any
page of the mall connects to the same `ChatObject`. Later, shops on their own domains: a session hand-off through a
redirect with a one-use token (deferred).

### Focus

- On a shop's page the widget sends `focus = shop:<id>`; the model gets a mid-conversation system message "the user
  is now on <shop>"; `find` defaults to that shop.
- "Where else, cheaper?" → `find(…, scope = mall)` over all shops (now: asking every `ShopObject` through `Registry`;
  fine for tens of shops; later a `Directory` index object updated on every change of services).
- The model proposes to go to a shop → `open(#shop3)`; the URL is computed by the resolver.
- On the mall's pages the focus is `mall`.
- Handles from earlier shops stay valid; cross-shop comparisons are expressions (`argmin(price, #v1, #v7)`).

Example flow (one conversation):

```
[mall home]   "manicure tomorrow morning under 150 zł"  → find over the mall → handles from 3 shops
[nails-demo]  "do you have 10:00?"                      → focus moved, same context, find in nails-demo
[barber-demo] "and a haircut here, cheaper than that?"  → price(#v1) < price(#v7), computed by the resolver
```

### The shop's facts (from okay-shop's data, ontology: schema.org + overlay)

- `Shop` (schema.org LocalBusiness): name, category, city (visible); address, url, hours, rating (hidden).
- `Service` (Offer of a Service): name (visible); price, duration (hidden); master (link).
- `Master` (Person): name (hidden); services (link).
- `Slot`: time (hidden); master, service (links).
- `Review`: rating, text (hidden).

Keys: shop id; service id within a shop; slot = (shop, master, start). Slots are computed by the resolver from the
masters' calendars and working hours (the booking already in okay-shop), never listed to the model.

### Where the code lives (open core, okay-mall's AGENTS.md)

```
okay-facts  (this library)   resolver, metamodel and importers, expressions, {params, template}, ICU rendering,
                             validator, binding layer, TCK, proxy and MCP modes
okay-shop   (public engine)  the storefront's assistant: the shop's fact schema from its data, find/open/book tools,
                             the chat widget in the pages' script; nothing multi-tenant
okay-mall   (private)        ChatObject, the session cookie, focus, mall-wide find via Registry, enabling per shop in
                             the admin, the model choice and limits, audit in the operator's dashboard
```

A change of the engine is made in okay-shop (spec, tests, released) and taken by okay-mall by a rebuild, never
copied.

### Model

- Default: Workers AI through okay-serverless' `Ai` (the `AI` binding already in okay-mall's wrangler.toml). The model
  is chosen by the TCK (tool calls + JSON mode needed for L3; llama-3.3-70b, already used for translation, is a
  candidate to test, not a decision).
- Optional: Claude through AI Gateway (the same code, another adapter).
- Without Cloudflare (okay-serverless' host): a local model through okay-mlx with a grammar (L4).

### Dialogue example (barber-demo, Polish user)

The model's raw output contains no fact:

```json
{"params": {"s": "name(#v1)", "p": "price(#v1)", "d": "duration(#v1)", "t1": "time(#s1)", "m1": "master(#s1)",
            "t2": "time(#s2)"},
 "template": "Tak, {s} kosztuje {p} i trwa {d}. Jutro wolne: {t1} u {m1} albo {t2}. Zapisać?"}
```

The user sees: "Tak, Strzyżenie + broda kosztuje 90,00 zł i trwa 45 minut. Jutro wolne: 17:30 u Marka albo 18:15.
Zapisać?" — the price, duration, times and master as chips; `17:30` clickable. On "yes, 17:30, Anna, +48 …" the model
calls `book(#s1, #client)`; the confirmation is rendered from the booking's result (pinned); the owner gets the
existing Telegram notification.

## 13. The demo (like okay-guard's)

- **Side by side page** (no install, like okay-uplc's open demo): an editable catalogue (a fictional shop, one copy
  per visitor in a Durable Object), the same question answered "as usual" (same model, data in context) and "with
  okay-facts"; numbers marked green/red; a **chaos** switch changing prices/slots every 10 s; **"what the model
  wrote"** showing the raw `{params, template}` — no number, no name in it. A counter: wrong numbers, usual vs facts.
- **MCP to any agent**: `claude mcp add okay-facts https://…/mcp`; the studio shows the live feed of reads and the
  verdict on the agent's answer.
- **Any ontology**: a Wikidata tab (public SPARQL as a handler), each number linked to its Wikidata statement.
- Honesty: the "usual" lane is fair (same model, same data, published setup); errors must arise naturally (large
  catalogue, data changed after loading, similar names, arithmetic).

## 14. Stages

Each stage: spec section first, tests, then code; done-when is the gate.

1. **Core** — metamodel, handles, expressions (parser, types, evaluator), `{params, template}`, ICU rendering with
   plurals (pl/uk/ru/en), validator, a fake model port replaying fixed outputs. Done when: tests over a fictional
   barber/nails dataset cover every check in §9, plural agreement in all four languages, functional-path refusal.
2. **Binding layer** — the conversation loop over okay-llm ports (L0–L4 modes), streaming with `params` first,
   policies. Done when: the same answers render identically through the L0 marker path and the L3 JSON path.
3. **TCK** — the suite and the passport; run against Workers AI candidates. Done when: a passport for at least two
   Workers AI models, with the level each reaches.
4. **okay-shop: the storefront's assistant** — the shop's fact schema, `find`/`open`/`book`, the widget; tests on the
   two fictional sites. Done when: the dialogue example of §12 passes end to end in tests on `Memory`.
5. **okay-mall: the chat across the mall** — `ChatObject`, the cookie, focus, mall-wide find, enabling per shop.
   Done when: the example flow of §12 passes on `MemoryCloud`, then on `wrangler dev` with the demo tenants
   (barber-demo, nails-demo). Never on production or Szykownia's data before the operator says so.
6. **Measure** — the same assistant with the shop's data in context vs okay-facts: wrong prices/slots/bookings,
   tokens, latency. Report in this spec.
7. **Demo page** (§13), then the proxy and the MCP mode (§11), then importers beyond schema.org (§4).
8. **The router lane** (§17) — the module `okay-facts-router` (rules, the static table, the probe, language,
   `When`/`Amount`/`Nearest`), names in their cases (`Morph`, the validator's `case`), `Bind.authored`; okay-shop's
   shop intents and authored answers; okay-mall's turn decision and the Directory's semantic scorer. Done when: the
   router's answers pass the validator in every language in tests; the measurement of stage 6 (§14.6) recorded with
   the router lane in it; deployed on every shop with the model lane behind it.

### 14.6 Measured (2026-11-03: the router lane, the model lane, the usual way)

`okay-mall`'s `Measure` (its `measure/turns.jsonl`: 180 fictional turns — pl 69, uk 35, ru 35, en 41 — over six demo
shops and the mall's home, 15 of them for the model: advice, payments, rule-breaking; `measure/dev.jsonl`, 45 others,
was the one debugged by). The mall on `MemoryCloud` with its demo tenants, now Wednesday 2026-11-04 09:00 Warsaw;
the model `deepseek-v4.1-flash-1017` (fallback `gpt-oss-120b`) through Workers AI (the TCK's Worker on `wrangler
dev`). `auto`: as deployed — the router, the model behind it; `model`: the model lane alone; `usual`: the same model
with the shop's data as text in its prompt (services, prices, durations, masters, hours, the week's free starts —
the prompt published beside the results, `usual.md`), answering freely. Wrong facts: a price, a duration, a time or
a master's name in an answer that the data does not have (checked by code, each flag then read by a person).

| lane | answered by the router | wrong facts | tokens a turn | cost a turn | latency p50 / p95 |
|---|---|---|---|---|---|
| auto (router, model behind) | 154 of 180 (86%) | 0 | 820 | $0.00031 | 12 ms / 12.3 s |
| model lane alone | — | 0 | 6 212 | $0.00229 | 9.4 s / 31.0 s |
| usual (data in the prompt) | — | 0 (3 flagged: "every 15 minutes", "under 300 zł" — not wrong) | 5 308 | $0.00175 | 3.7 s / 17.4 s |

- **The router**: answered 154 turns (pl 61/69, uk 33/35, ru 32/35, en 28/41); its intent right in 137 of them
  (89%); of the 15 turns meant for the model, 12 declined, 3 answered (a request to break the rule answered with a
  price — every value still the resolver's); 14 in-domain turns declined to the model (not confident, `other`), 14
  answered with another intent (most: a mall-wide search taken for `masters`, an area question for `address`).
  Slots right: 114 of 143 asked (80%) — the service among the shop's, the day, the time, the master, the city, the
  price limit; a bare "strzyżenie" where a shop has three asked back ("which one?") on purpose. In the router's own
  time: p50 10 ms, p95 41 ms (in-process); on production, end to end through the Worker, the conversation's object and
  the shops' objects: 0.5–0.9 s warm, ~3 s for a new conversation's first answer (its object's isolate reads the
  table: 0.9 s of CPU). The model behind it, for what the router declined: p50 8.3 s.
- **Wrong facts: 0 in every lane.** The usual way made none on this small, clean data either — the six demo shops
  fit the prompt whole; the difference okay-facts guarantees (no value can be wrong; only a reference) is
  structural, and the usual way's errors grow with the data (§13: a large catalogue, data changed after loading,
  similar names). What the router changes is the cost and the time: 7.5 times fewer tokens a turn than the model
  lane, an answer in milliseconds for most turns.
- The model lane was slower than on production (6.7 s) because the account's per-minute limit was shared by the
  runs; its expected values were shown in 178 of 180 answers (auto 173: the router's wrong intents; usual 167, its
  values in its own formats, "75 min" for "1 godzina 15 minut").
- **The table**: okay-chat's static table (5 363 units, a marketplace's) knew 52.5% of the set's words (pl 48.6%,
  uk 49.4%, ru 43.2%, en 64.1%; 6 turns with no known word); the shop domain's table built for this (13 163 units:
  the intents' examples' words, pairs and triples, okay-shop's `assistant/vocabulary.txt`, MASSIVE's pl/ru/en words
  seen three times or more — CC BY 4.0) knows 87.2% (pl 86.4%, uk 81.3%, ru 84.6%, en 93.1%; 1 turn with none).
  256 wide (PCA keeps 99.3% of the variance; 128 wide would keep 92.7% with the same held-out intent accuracy),
  7.1 MB in half precision. The probe on held-out authored rows (every 5th): 92% right, 94% among the confident.

**Again, 2026-11-04** (the router lane's round 2, §16; okay-mall `measure/2026-11-04/report.md`): a NEW held-out set,
340 turns by an author who never saw the phrasings (pl 147, uk 64, ru 61, en 68; 49 for the model — advice,
rule-breaking, others' data, out of domain, nonsense; typos, no diacritics, Latin letters, mixed languages, follow-ups),
frozen before any tuning (sha256 `4fd0ac90…a19e`), run once; the lanes one after another, paced — the platform refused
none. The rows of 2026-11-03 above are kept for comparison (another set: 180 turns).

| lane (2026-11-04, 340 held-out turns) | answered by the router | wrong facts | tokens a turn | cost a turn | latency p50 / p95 |
|---|---|---|---|---|---|
| auto (router, model behind) | 270 of 340 (79%) | 0 | 1 117 | $0.00043 | 23 ms / 7.8 s |
| model lane alone | — | 0 (5 flagged: a visit's fee in a card's price, a sum — the resolver's) | 6 288 | $0.00234 | 5.0 s / 15.8 s |
| usual (data in the prompt) | — | 0 (7 flagged: a 15-minute step, the user's own numbers) | 4 540 | $0.00152 | 2.3 s / 5.1 s |

- **The router on the held-out set**: its intent right in 234 of its 270 answers (**86.7%** — the target of 97% NOT
  met); slots right 215 of 225 (95.6%); 30 in-domain turns declined, 9 meant for the model answered; the model lane's
  share 20.6%. 31 of the 36 wrong are by rules: "do you come to the client?" (modes) taken for `area` (10), a laundry's
  pickup for `area` (2), injections and advice the rules lack (9 meant for the model), a price or a named shop's hours
  on the mall's home answered as a search (7). On the train/dev turns of 2026-11-03 the same router: 98.8% and 100%.
  In the router's own time p50 16 ms, p95 51 ms; the model behind it p50 6.1 s.
- **Cold start** (production): a new conversation's first answer p50 1.9 s → 0.74 s (p95 0.94 s) as the widget does
  (the chat's open warms the conversation's object; the table read in place); a message with no open first 1.70 s; the
  table's loading CPU in a fresh isolate 102–139 ms (was ≈ 0.4–0.6 s).

**Round 3, 2026-11-04** (§16 "The router lane, round 3", §17 "The turn decision"; okay-mall
`measure/2026-11-04-r3/report.md`): a NEW held-out set, 320 turns written fresh by an independent author who saw only
the demo shops' data (pl 128, uk 64, ru 64, en 64; 51 for the model, 20 injections in four languages; 33 follow-ups;
48 on the mall's home), frozen before any tuning (sha256 `b0998bac…7397`), run once, paced; the fallback `glm-5.3`.

| set | lane | answered by the router | router precision | slots | model share | wrong facts | tokens a turn | cost a turn | latency p50 / p95 |
|---|---|---|---|---|---|---|---|---|---|
| 2026-11-03 (180) | auto | 154 (86%) | 89% | 80% | 14% | 0 | 820 | $0.00031 | 12 ms / 12.3 s |
| 2026-11-04 (340) | auto | 270 (79.4%) | 86.7% | 95.6% | 20.6% | 0 | 1 117 | $0.00043 | 23 ms / 7.8 s |
| round 3 (320) | auto | 246 (76.9%) | **91.9%** | 88.7% | 23.1% | 0 | 1 090 | $0.00043 | 60 ms / 8.9 s |
| round 3 (320) | model lane alone | — | — | — | 100% | 0 | 5 819 | $0.00217 | 5.8 s / 24.5 s |
| round 3 (320) | usual (data in the prompt) | — | — | — | 100% | 0 (7 flagged, none wrong) | 4 586 | $0.00154 | 2.6 s / 6.2 s |

- **The router**: precision 91.9% (86.7% before) — the 97% still NOT met; the combiner's out-of-fold 97.1% at 84.4%
  coverage on train/dev did not carry over whole. Its 20 wrong: 5 meant for the model (advice naming a service, a
  stain washed at home, paying on delivery in words the guard lacks, a dog), 4 on the mall's home (a named shop's
  hours, the mall's own contact), 3 at the client's vs a place written in Cyrillic, 8 near intents (free times vs a
  booking, a date's hours, a phone vs the address). In its own time p50 37 ms, p95 288 ms (the prefetch's call
  included); the model behind it p50 5.9 s.
- **Wrong facts: 0 in every lane.** One router answer compared a suit's cleaning with unrelated cheaper services of
  other shops — every value exact, the reference irrelevant: the honest boundary of §2, now an open item.
- **A first message**: the page warmed when idle and a message with no open p50 0.42 s (p95 0.62 s); after the chat's
  open p50 0.52 s (0.74 s before); a cold object with no warming at all 1.58 s (was 1.70 s).

## 15. Gotchas

- Non-unique links make paths wrong silently (an averaged value belonging to nobody): enforce functionality.
- Slavic plurals: never let the template write a unit after a number placeholder.
- Schema order matters for streaming: `params` before `template`.
- Prices change between turns: render current, pin on actions.
- A big ontology in the prompt costs tokens and attention: ontology search instead.
- Mappings between ontologies are human-approved data, never runtime model output.
- In MCP mode the foreign model sees values: say "verification", never "guarantee".
- The cookie's domain comes from `MALL_DOMAIN`; custom domains need a hand-off (deferred).

## 16. Open questions (decide while implementing, record here)

### Operator decisions (2026-11-03)

- **Public**: okay-facts is PUBLIC — okay-effects/okay-facts on GitHub, Apache-2.0 like okay-shop; released as a Maven
  repository on GitHub Pages (RELEASING.md), republished under 0.1.0 while unused by others (the tag moved, the
  dependents' Coursier cache cleared, Pages checked by sha1 before they rebuild).
- **Production: all shops**: once stage 5 passes on `MemoryCloud` and on `wrangler dev` with the demo tenants, every
  shop of the mall gets the chat — the real atelier (`atelier`) too — and the landing (apex). it.szykownia.pl is not
  the mall's: untouched. Each shop has an off switch in the admin (default on).
- **Retention: 30 days** for a conversation and the client's data said in it: `ChatObject` deletes its storage after
  30 days without activity (its alarm); "delete this conversation" in the widget deletes it at once. The chat is in
  the platform's privacy policy (`/_z/privacy`: purpose, data, 30 days, the processor Cloudflare / Workers AI,
  rights) and, minimally, in each shop's privacy page (okay-shop's `Privacy`): the atelier's privacy page changes,
  as intended.
- **Model: Workers AI only** (okay-serverless' `Ai`, the `AI` binding). Stage 3 runs the TCK on the current Workers AI
  candidates with tool calling; the default is chosen by the passport. Strict mode on production needs L3 (tool
  calls + JSON schema); with no model reaching L3 reliably, the best one with policy `regenerate` — said so here.

### Decided while implementing (2026-11-03)

- **Repository**: `okay-facts`, public (okay-effects/okay-facts), Apache-2.0; artifacts `okay-facts` (the core and the
  binding, JVM and Scala.js) and `okay-facts-cloud` (the Workers AI binding as okay-llm's `Llm` port). okay-llm got a
  Scala.js platform and `Ask.format` (a response format; the old `apply`/`copy` kept), republished under 0.1.0.
- **The widget** is part of okay-shop's pages' script (`site.js`), active only where the page carries its marker
  (`Look.chat`); a page without the marker is byte for byte as before.
- **Retention**: the operator's 30 days (above); built in okay-mall's `ChatObject`.
- **The model** — the TCK (§11) on Workers AI, 36 questions over `tck.Demo` (pl, uk, ru, en; prices, durations, slots,
  masters, places, ratings, comparisons, absences, actions, a flow across three shops, users asking to break the
  rule), the final run with the library as released (the claim check of worded comparisons in; `tck/worker/run.sh`):

  (passed — the expectations met; strict — with no violation nor regeneration; formatted — the final answer as
  the level asks; tools — tool calls used; entities — the finds that reached the expected ones; per question.)

  - `deepseek-v4.1-flash-1017`, run at L3: reached **L3**; passed 35/36, strict 31, formatted 35, tools 36;
    violations claim 1, digits 3, name 1, parse 1; 5 regenerations; entities 13/13; 5 273 tokens, 6.7 s.
  - `gpt-oss-120b`, L3: **L3**; 31/36, strict 30, formatted 35, tools 31; name 1; 1 regeneration; 12/13; 4 252
    tokens, 12.3 s.
  - `glm-5.3-flash`, L3: **L2** (formatted 32/36); 31/36, strict 28, tools 33; name 3; 3; 12/13; 4 728 tokens, 32.9 s.
  - `llama-3.3-70b-instruct-fp8-fast`, L3: **L3**; 23/36, strict 17, formatted 36, tools 35; digits 4, handle 8,
    name 2, parse 2, placeholder 2, syntax 1, type 3, unused 2; 10; 9/13; 8 394 tokens, 11.0 s.
  - `deepseek-v4.1-flash-1017`, L0 (markers, command lines): **L0**; 30/36, strict 24; claim 10, digits 19, name 3;
    16 regenerations; 13/13; 3 990 tokens, 7.6 s.
  - `llama-3.3-70b-instruct-fp8-fast`, L0: **L0**; 19/36, strict 11; claim 6, digits 17, handle 8, name 6, parse 13,
    type 2; 31; 11/13; 4 786 tokens, 6.5 s.

  Earlier runs of the day (the resolver before its last fixes; L3): deepseek-v4.1-flash 36/36 and 34/36, glm-5.3-flash
  32/36 and 33/36, nemotron-3-120b 22/36 (L0), gemma-4-26b 20/36 (L2), qwen3-30b 19/36 (L0), qwen3.8-27b 4/36,
  mistral-small-3.1 1/36 (L2: no JSON answer), llama-4-scout 3/36 (tool calls written as text: L0; 20/36 run at L0).
  **Chosen: deepseek-v4.1-flash-1017** (`CHAT_MODEL`), L3, policy `regenerate`; the fallback **gpt-oss-120b** (L3).
  Strict mode (no regeneration, 90% strict) is reached by no model reliably — deepseek 31–34 of 36 strict across
  three runs: production runs L3 with `regenerate`, as the operator said for this case. Cost: about 5 300 tokens a
  question, $0.30 / $1.20 per million in / out (≈ $0.002 an answer). Notes: gpt-oss works only in the chat shape
  (its Responses shape refuses tools through the binding); a model's per-minute limit (`3021`) is retried by the port.
- **The TCK again, with the validator's `case`** (2026-11-04; `tck/worker/run.sh`, the two current models in one run,
  the new candidates in a second — the account's per-minute limit shared; results in `tck/results/2026-11-04/`):
  - `deepseek-v4.1-flash-1017`, L3: **L3**; passed 34/36 (both misses the platform's `3021`, still there after the
    runner's retry), strict 31, formatted 34, tools 36; violations case 3, claim 1, digits 1, name 1, value 1; 5
    regenerations; entities 13/13; 5 747 tokens, 6.7 s. Its 3 `case` were one English question (a master's name not
    asking a case, which English must too); regenerated with `as nom`, passed.
  - `gpt-oss-120b`, L3: **L2** (tools in 29 of 36, under the 80% L3 needs); 29/36, strict 28, formatted 32; claim 2,
    digits 3; 4 regenerations; 11/13; 5 254 tokens, 10.3 s — its reasoning channel leaked into two answers, two empty.
  - New with tool calls and a response format: `glm-5.3` **L3** 32/36, strict 27, formatted 33, tools 35; case 3,
    claim 2, digits 2, name 1, value 1; 7; 13/13; 5 246 tokens, 7.7 s ($1.40 / $4.40 per million: 4.7 times the
    default's price); `deepseek-v4-pro-0813` **L2** 26/36 (8 empty answers after right finds), 9 263 tokens, 25.2 s;
    `kimi-k2.6` **L0** 15/36 (22 empty answers), 37.2 s. Not run: glm-5.2, deepseek-v4-flash-0731, the small ones.
  - **Kept: deepseek-v4.1-flash-1017** (none clearly better). The fallback `gpt-oss-120b` reached only L2 this time;
    `glm-5.3` is the better fallback by the TCK at 4.7 times the price — the operator's call (BACKLOG).

### The router lane, round 2 (2026-11-04; §14.6, §17)

- **Data first**: okay-shop's phrasings 744 → ~1 000 (typos, no diacritics, Latin letters, mixed languages, short
  follow-ups, harder `other`: payments, advice, others' data, injections), rules for `other` (rank 5: injections,
  others' data, payments and amenities, advice), compare (`najtaniej`, "cheaper option"), area (pickup, cover), hours
  (opening and closing times), reviews, human (an administrator's phone), greetings with "that's all".
- **Features where data cannot**: a host's `Lexicon` (words of one thing, a verb's prefix passed over, words that say
  nothing ignored), a name's words by their rarity among the candidates and the words it accounts for (`Nearest`);
  `When`: the weekend, Ukrainian and Russian in Latin letters; the host's (okay-shop's `ShopRouter`): a short
  follow-up takes the last question's intent, the words' evidence (a service or a master, and a time) beside an unsure
  probe, a person not the shop's, the conversation's service the one its last answer named, the answer's own intent
  reported (a shop's question answered by a search is a search), what a shop has not given said honestly (the modes
  bug: a ways-of-working question said the address' absence).
- **Threshold**: p ≥ 0.7, margin ≥ 0.2 — on held-out authored rows (every 5th) what the router would answer is right
  97.1% at it (96.2% at 0.6), on the train/dev turns 98.8–100%; the held-out set: 86.7% (§14.6) — its wrong ones are
  by rules (p = 1), which no threshold reaches. Not met; next: below.
- **The table**: 256 wide in int8 (a byte a dimension, a scale a row) — 4.47 MB, the same held-out accuracy as half
  precision; 128 wide (2.48 MB) lost 1.7 points of intents on the train/dev turns (more than the 1 allowed): kept 256.
  Read in place from a view of R2's bytes (okay-serverless' `Blobs.view`, released 2026-11-04): no copy; the chat's
  first open warms the conversation's object (okay-mall).

### The router lane, round 3 (operator 2026-11-04; §14.6, §17)

- **The fallback model: `glm-5.3`** (operator, 2026-11-04): `@cf/zai-org/glm-5.3` (L3, 32/36 by the TCK of
  2026-11-04) is the model lane's fallback (`CHAT_MODEL_FALLBACK`), asked when the default fails (its per-minute
  limit past the port's retries, an error); `deepseek-v4.1-flash-1017` stays the default. At 4.7 times the default's
  price it is asked only on a failure.
- **Rules are evidence, not verdicts** (§17 "The turn decision"): the guard → the model lane; the safe rules (greet,
  help) the only ones answered alone; the combiner for everything else, calibrated out of fold. Train/dev: the
  intents' examples (the probe), the 180 + 45 turns of 2026-11-03 and the 340 of 2026-11-04 (spent as a held-out set:
  from now on train/dev only, as the 180 became in round 2); the new held-out set of round 3 (okay-mall
  `measure/2026-11-04-r3/`) frozen before any tuning and run once.
- **Reads in one call** (`Facts.Prefetch`, a hint a handler may ignore); **the probe and the combiner binary**
  (`Weights`).
- **The combiner, calibrated** (okay-mall `Fit`, `measure/2026-11-04-r3/curve.md`): 64 inputs (16 intents × probe,
  rules, previous intent, and 16 of the host's evidence), L2 1e-4, temperature 0.8, out of fold (5 folds) over the 565
  train/dev turns; the guard took 44 of them (all meant for the model), the safe rules 18 (all right). Out of fold:
  threshold 0.30 — 86.7% answered at 95.5%; 0.50 — 85.0% at 96.7%; **0.53 — 84.4% at 97.1% (chosen)**; 0.60 — 82.8% at
  97.6%; 0.70 — 81.2% at 98.5%; 0.80 — 78.4% at 98.6%; 0.90 — 74.9% at 99.3%. On the new held-out set: 76.9% at 91.9%
  (§14.6).
- **Fixes**: at the client's is the ways of working (the area's examples and rules moved; with a place named, the
  area); a laundry's pickup a booking ("zamów odbiór"); the mall's home: a shop named by its name answers its own facts,
  a price of a service one shop has its price — asked before the conversation's last service; `When`: a month only as
  its word next to a day's number; cancel's stems (скасуйте, анулюйте, odwołajcie, отмените); "only the haircut" the
  plain name; the guard in four languages (ignore or forget instructions, the system prompt, "you are now", role-play,
  a price set by the user, discounts, others' data, payments, advice and health, out of domain).
- **Speed**: a turn's reads of a shop in one call (`Facts.Prefetch`, the mall's handler keeps a turn's answers: a price
  turn 10 → 1 call), the guards together, the tokens and the audit after the answer, the probe binary (JVM warm 2.37 →
  0.095 ms); a first message after the page's idle warming p50 0.42 s (§14.6).
- **The fallback `glm-5.3`, verified on production** (2026-11-04): the operator's `POST /_admin/api/chat/try` with
  `force: fallback` (the default asked as `@cf/okay/forced-failure`, a model no platform has) on barber-demo — the
  fallback answered (a model-lane answer rendered by the resolver: "Na wesele polecam Strzyżenie + broda — komplet.
  Koszt: 110,00 zł, …"); a client's request never forces it (its header stripped, `ChatSuite`).
- **Open** (round 4): the 97% on fresh text; a Cyrillic place's evidence; advice and payments in more words; a
  comparison's relevance (an unrelated cheaper service is an exact value of a wrong reference).

### The guard answers (operator 2026-11-04; §17 "The turn decision")

- **No model behind the guard**: a guarded turn is answered by the host's fixed authored answer for its category
  (okay-shop: injection, others, bargain, health, payments, amenities, advice, domain — `answers.json` `guard.*`, the
  shop's values by the resolver: its name, a few services, the price in context, the address, its contact or page, the
  client's own booking; the mall's home without values). Validated like any authored answer; never the user's words.
- **The guard is data by category** (`{category, patterns}`, `Route.guard`); okay-mall counts each category (never
  the text) and cools a conversation down for 10 minutes after its 3rd injection or others'-data turn (fixed answers
  only: a rate limit, not a ban).
- **False positives** (okay-mall `scripts/guard-fp.scala`: an in-domain turn — an intent not `other` — the guard
  takes), on 2026-11-03's 180 and dev 45, 2026-11-04's 340, round 3's 320 (all now dev data) and the intents'
  1 130 phrasings: **11 before → 1 after** (round 3: "warto do was iść?", "dziewczyny polecacie?" — reviews —, "як
  написати адміністрації торгового центру?" — "торг" was bargaining —; phrasings: "co zrobić?" after "nie dam rady
  jutro" in four languages — manage —, "ile płacę za …" three times — price —, "czy jest parking przy was?" —
  address). The one left is the last, now an amenity's answer that gives the address. Turns meant for the model
  the guard takes: 87 of 118 → 104 of 118 (the rest go to the combiner, its `other` to the model lane).

### Implementation notes (2026-11-03)

- **Find** is generous with what models write: a link filtered by a handle, by a name (`service = haircut`) or by a
  path (`shop.city = Kraków`, `city(shop) = Kraków`); an expression over handles as a value (`price < price(#v1)`);
  a bare number against money or minutes in its main unit; text matched on words of 3 letters or more, the best
  matches only (a row with half the words where another has them all is dropped). Visible descriptors never carry a
  digit (an owner's description "3 seats" is a fact).
- **Claims in words**: a comparative word (cheaper, earlier, longer, fits — pl, uk, ru, en stems) in the template's
  own text outside a select is a violation (`claim`): the comparison must be an expression whose boolean chooses the
  words. A stem list, not a grammar: "less"/"more" are not in it (too common) — a known gap.
- **Names in their cases**: the resolver renders a name as the data has it ("u Marek" where Polish wants "u Marka");
  forms by case from the data are not built.
- **Streaming**: params-first streaming is in the library (`Stream`, `Emit`; tested on the fake port); the Workers AI
  port answers whole (okay-serverless' `Ai` returns the whole JSON), so the mall renders after the answer.
- **Stage 6 (measure)** not done yet: BACKLOG.

### The router lane (decided while implementing, 2026-11-03; §17)

- **Where things live**: okay-facts — the generic module `okay-facts-router` (rules, `Table`, `Probe`, `Detect`,
  `When`, `Amount`, `Nearest`, `Spell` — names across scripts and forms), `Morph` and the case hints in the core,
  `Bind.authored`, the tools; okay-shop — the shop's intents, phrasings, answers, vocabulary, the table and the probe
  as data (`assistant/`), `ShopRouter`; okay-mall — the turn decision in `ChatObject`, the table in R2, the
  Directory's semantic scorer, the measurement. okay-chat-edge unchanged.
- **The table** is the shop domain's own (okay-chat's knew half the words, §14.6), distilled by `tools/table.scala`
  from the model file okay-chat fetched (onnxruntime 1.30 and the HuggingFace tokenizer on the JVM; never Python).
  Single words are noisy across languages in it ("haircut" ~ "strzyżenie" 0.09, "haircut" ~ "manicure" 0.49 after
  centring): a service is found by its names' words first (the shops' names are in four languages), the meaning beside
  them (0.85 of the cosine alone; ties of the same words asked). It is read in place: its labels kept in their bytes'
  order and searched where they lie (no map of 13 000 units built at an isolate's start).
- **Thresholds**: a route is confident by a rule, or by the probe at p ≥ 0.6 and a margin ≥ 0.2 (held-out authored
  rows: 94% right among the confident); a service at 0.6 and a lead of 0.06 (0.75 — all its words — when a
  follow-up would otherwise take the conversation's). A class `other` (advice, payments, amenities, rule-breaking) is
  the model's.
- **Names in their cases**: the model lane's prompt asks a case for a person's name and the validator's `case` holds
  it to it (§17); `tck.Demo`'s masters are persons too. Rules for Polish, Ukrainian and Russian first names; a Latin
  name in a Cyrillic sentence, two words, an unknown ending: the nominative in a construction needing no case.
- **Worker limits** (2026-11: 64 MiB uncompressed, no compressed limit, 1 s to start, 128 MB an isolate): the mall's
  Worker is 7.0 MB (1.13 MB gzipped; +90 KB for the router's data and code), starting in 35–50 ms; the table is not
  in it but in R2, read once an isolate.

## 17. The router lane (operator, 2026-11-03: a lane with no generative model in front of the model lane)

Most turns of a shop's chat are a few kinds of question — a price, a free time, the hours, the address — answered by
the same sentence with other values. okay-chat (`~/work/my/okay-chat`, its edge port `okay-chat-edge`) routes such
turns with no model on the path: rules, a static embedding table, a probe. The router lane does that here, and its
answers are okay-facts answers: an **authored** `{params, template}` per intent and language, its parameters
expressions over handles that code found by `find`, validated and rendered by the same resolver (§7–§9). The model
lane (§10) answers what the router does not understand, budgeted as before.

```
turn ─► router: rules ─► static table ─► probe ─► intent, confidence
          │ confident, its slots resolved            │ below the threshold, or a slot it cannot resolve
          ▼                                          ▼
        find by code → handles; the intent's authored {params, template} → validator → render
                                                     the model lane (Bind.turn): as before
```

### The rule, unchanged

The router never produces a value either: it chooses an intent and fills the slots of a `find`; the answer's
template is written offline by people (drafted by a model at most, then reviewed and committed as data), its
parameters are expressions over the handles found. Tests check every authored template against the validator in
every language, and the validator runs on the router's answers at runtime as on the model's.

### The module `okay-facts-router` (JVM and Scala.js; generic — intents and phrases are data)

- **Rules** (`Rules`, `Re`): patterns in a portable dialect — `\w`, `\b`, `\s`, `\d`, case-insensitive — where a
  word character is a letter of Latin (with its extensions: Polish) or Cyrillic (with its supplement: Ukrainian),
  a combining mark or a digit; translated to explicit ranges on BOTH platforms (JavaScript's own `RegExp` in a
  Worker, `java.util.regex` on the JVM), so a rule matches alike on each. Measured in okay-chat-edge: Scala.js's
  `java.util.regex` builds every `\w` as a class of all of Unicode (188 rules: 420 ms, more than an isolate's start
  may take); native `\p{…}` classes 209 ms (V8 builds each anew, 0.4 ms); explicit ranges 0.02 ms a class. Any other
  escape is refused, so a construct the translation does not know is seen, not mistranslated.
- **The static table** (`Table`): a vector per unit — a word, two or three adjacent words — distilled from
  paraphrase-multilingual-MiniLM-L12-v2 (model2vec's method with okay-chat's teacher, okay's `Static`), cut by PCA,
  with SIF weights; a text's vector is the weighted mean of its known units', normalised — a lookup and a sum, no
  inference. A safetensors file (`vecs` I8 `[n, dim]` with `scales` F32 `[n]` — a byte a dimension, a row its bytes
  times its scale, the largest magnitude over 127 — or F16 `[n, dim]`; `labels.offsets` U64, `labels.bytes` U8,
  `weights` F64), read IN PLACE from a buffer over its bytes (`java.nio.ByteBuffer`: on the JVM over an array, in a
  Worker a view of the object's own `ArrayBuffer` by okay-serverless' `Blobs.view` — never copied): a row is decoded
  when a text uses it. Its coverage (the share of a text's words it knows) is reported.
- **The probe** (`Probe`): multinomial logistic regression over the table's vectors (okay's `Probe`: weights from
  zero, rows in order, 300 epochs, rate 0.5 — deterministic), fitted at BUILD time by a tool; the host carries the
  weights — binary since round 3 (`Weights`: a 4-byte length, a small JSON header — the classes, the shapes, the
  combiner's evidence names, temperature and threshold —, then little-endian F32; 16 × 256 read in well under a
  millisecond, where the JSON took 10.7 ms in a Worker's isolate).
- **Language** (`Detect`): the message's script (any Cyrillic letter: Cyrillic), then letters only one language has
  and everyday words; an amount with its currency counts for none («do 150 zł» is not Polish); no evidence: the
  conversation's last language within the script, else the page's.
- **Slots, generic** — `When`: a day (today, tomorrow, the day after, a weekday, the weekend — its Saturday —, `5.11`,
  `5 listopada` — a month only as its own word next to a day's number, never a stem that starts a name: "Marek's 10:15",
  "u Marka 5" are no March), a part of the day (morning, afternoon, evening) and a time (`o 17`, `po 17`, `o 10:30`, `до
  12`, `after 5pm`) in pl, uk, ru, en — Ukrainian and Russian typed in Latin letters too (`zavtra`, `v pyatnicu posle
  16`) —, relative to today in Warsaw (§ Zone); `Amount`: a price limit (`do 150 zł`, `poniżej 150`, `до 150`, `under
  150`, `max 150`, `deshevle 100`); `Near` (2026-11-04): a place asked to be near — the speaker's own ("blisko mnie", "w
  pobliżu", "поруч", "рядом", "near me") or one named ("w okolicy Mokotowa", "koło metra Centrum", "біля Оперного",
  "около Центра", "near Centrum metro": its words as said, the host geocodes them); `Nearest`: the candidates nearest a
  text (each unit of the text against each candidate's name, in any of its languages): its words first — a name's word
  present in the text by its form (`near`: the same, a start of 4 letters, a stem in common, one edit), in plain Latin
  (`Spell.fold`: "strizhka" is "стрижка"), or by the host's `Lexicon` (groups of stems of one thing — "szafa", "meble",
  "wardrobe", "шкаф"; a verb's prefix passed over: "po|czyścić"; a stem of 3 letters the whole word only; words that say
  nothing of which service it is ignored: "drobna", "small", "telefon"), each word weighed by its rarity among the
  candidates (1 over how many have it) —, its meaning beside them; a name with all its words wins by the text's words it
  accounts for ("haircut and beard" is the haircut with a beard, not the beard); a threshold and a margin — two close:
  ambiguous, the host asks.
- **The router** (`Router`, round 3): the guard's patterns, then the safe rules, then the combiner over the evidence
  (the probe's probabilities, every intent's rule hits, a follow-up's previous intent, the host's evidence) →
  `Route(intent, by: guard | safe | combined | unknown, p, margin, rule, guard)` — `guard` the guard's category when
  it took the turn; `confident` when safe, or the combiner's `p` passes the threshold its file carries (calibrated out
  of fold on train/dev, §16; "The turn decision" below). A rule's hit is evidence, never an answer by itself.
- **The data** (`Intents`): `{guard: [{category, patterns: [...]}…], intents: [{name, rank, safe: [...], rules: [...],
  examples: {pl: [...], uk: [...], ru: [...], en: [...]}}]}` — a host's own file (a bare pattern in `guard` is of the
  category `guard`); the categories in order, the first whose pattern matches takes the turn (`Intents.guard`: the
  pairs (category, pattern)); the probe is fitted on its examples, the table distilled over its units. The
  `Lexicon` (`{groups: [[stems…]…], ignore: [words…]}`) is the host's too.
- **Tools** (`tools/`, scala-cli, never Python): `table.scala` (the units of a corpus embedded by the ONNX encoder —
  onnxruntime and the HuggingFace tokenizer on the JVM, the model file okay-chat fetches —, PCA, F16, the file
  written), `fit.scala` (the probe), `coverage.scala` (a table's coverage of a set of texts).

### Names in their cases (core: `Morph`; the model lane too)

A name in a Polish, Ukrainian or Russian sentence takes the case its construction asks ("u Marka", "z Markiem",
"у Олега"). Rendered as the data has it, it reads wrong ("u Marek"). So:

- An expression may ask a case: `name(master(#s1)) as gen` (also `master(#s1) as gen`): `nom`, `gen`, `dat`,
  `acc`, `ins`, `loc`.
- The form comes from the data first — an owner's form, the entity's property `name.<case>` (a text by language;
  okay-shop's master config, optional) —, else from rules for common first names (`Morph`: Polish, Ukrainian,
  Russian; male and female patterns, a parenthesised tail kept: "Marek (demo)" → "Marka (demo)"); never guessed:
  a name the rules are not sure of (another script than the sentence's, an ending they do not know) has no form.
- A case-asked parameter selects by its sureness: `{m, select, sure {u {m}} other {(mistrz: {m})}}` — `sure` when
  the form was found, `other` when not, `{m}` then the nominative — so the template keeps a construction that needs
  no case when the form is not known. English: always `sure`, the name as it is.
- The validator (code `case`): a parameter rendering a person's name (a class marked `person`) must ask a case; a
  case other than `nom` only inside its own select with an `other` case. The model lane's prompt says so; the
  router's templates are written so.

### The authored answer (core: `Bind.authored`)

`Bind.authored(setup, conversation, user, lang, focus, issued, answer, cards)` is the router's way into the same
conversation: the handles it found issued in the table, the answer validated and rendered by `Resolve`, the history
kept as the model lane keeps it (the user's words, "Found: …" with the handles' visible words, the answer's own
`{params, template}`), so a later turn on the model lane knows what the router said; audited (`Audited.Routed`:
intent, confidence; the reads as any answer's).

### The turn decision (the host's; round 3, operator 2026-11-04: rules are evidence, not verdicts)

A rule that fires is not an answer: on the held-out set of 2026-11-04 31 of the router's 36 wrong intents came from
rules answering with p = 1, which no threshold can stop. So a turn is decided in three steps, in order:

1. **The guard** (`Intents.guard`, the host's patterns by category): a prompt injection or a question about the
   assistant itself (ignore or forget the instructions, the system prompt, "you are now", role-play, a developer
   mode), other people's data (other clients, who booked, a master's private phone, earnings), a price set by the user,
   discounts and bargaining, payments, amenities, health, advice and recommendations, out of domain (the weather,
   sports, code, recipes, poems) → **the host's fixed, authored answer for the category** (operator, 2026-11-04: a
   guarded turn NEVER goes to the model lane). Not the model's, not a domain intent's: an authored `{params,
   template}` per category and language (the turn's), through the same validator, its values only the resolver's
   (`Bind.authored`, `by = guard`, the intent `guard.<category>`) — never the user's words echoed. The host keeps a
   counter per category (never the text) and a strike count per conversation (injections and others' data): past
   its limit, a cool-down of fixed answers only (a rate limit, not a ban; okay-mall). A false refusal now has no model
   behind it, so the patterns are narrow and their false positives measured on every set (§16).
2. **The safe rules** (`Intent.safe`, an explicitly short list — greetings, thanks, goodbyes, "help", "what can you
   do"): whole-message patterns whose answer has no fact and no slot; answered at once (`by = safe`). Nothing else is
   ever answered by a rule alone.
3. **The combiner** (`Combiner`) for everything else: multinomial logistic regression over a turn's evidence, its
   output divided by a temperature — a calibrated probability per intent:
   - the probe's probabilities (one per intent);
   - the rules' hits (one per intent: any of its rules matches — every rule tried, not the first);
   - a short follow-up's context: the previous question's intent (probe and rules), one per intent, when the message
     is short (≤ 2 words of 3 letters or more, ≤ 5 words) — zeros otherwise;
   - the host's evidence, named in the combiner's file (okay-shop: a service of the shop named by all its words, a
     master named, a person named who is not the shop's, a place named, a day or a time, an exact time, a booking's
     word, a price limit, "alone" ("samo", "only", "тільки"), the mall's home, a shop named by its name there, short,
     opened by "a"/"and"/"а"/"і").
   The answer is the top intent when its probability is at least the threshold (and it is not `other`); else the
   model lane. No known word and no rule hit: the model lane (`unknown`).

**Calibration.** The combiner is fitted at build time (`okay.mall.Fit`, okay-mall's train/dev turns: the sets of
2026-11-03 and the spent held-out of 2026-11-04 — never a frozen held-out set), with L2, from zero; 5-fold: each
turn's probabilities from a fit on the other four folds (out of fold), the temperature fitted on them (least
log-loss), then the threshold — the lowest whose out-of-fold router precision (the right intent among the answered;
a turn meant for the model answered is wrong) is ≥ 97% —, the precision–coverage curve recorded (§16). The final
weights are fitted on all of train/dev; the file carries the weights, the temperature, the threshold and the
evidence's names (binary, §17 "The probe").

Then, as before: a decided intent whose slots resolve answers by its authored answer; a slot it cannot resolve that
the intent needs → the model lane. The host logs per turn the lane, the intent, the probability and how it was
decided (`guard`, `safe`, `combined`, `unknown`) — never the message's words. A host can force a lane (tests, the
measurement).

**Reads in one call.** A turn's reads of a shop's facts are batched (core: `Facts.Prefetch(finds, gets)` — a hint:
these finds, with the named properties of each entity found, and these properties will be asked; a handler with no
cache ignores it, so it never changes an answer; over the wire one call, answered where the data is): the router asks,
in one call, everything a turn of a shop may need (its services in every language with their prices,
durations and whether bookable; its masters with their names; its places; the shop's own properties). The host's
handler keeps a turn's answers (a turn is a snapshot: an action forgets them), so the later reads and the
renderer's are answered from them.

### Round 4 (operator 2026-11-04): the error classes fixed by data and structure, not by more phrasings

Round 3's wrong answers (§14.6) fall in classes no threshold reaches; each is fixed deterministically:

1. **Places in any script and case** (`Places`, okay-facts-router; the data is the host's): a gazetteer — entries
   `{id, name, kind: city | district, city, forms}` — whose forms are folded (`Spell.fold`: case, diacritics,
   Cyrillic in Latin letters) and matched as whole word runs, the longest first, a word of 6 letters or more one edit
   away. The forms are GENERATED by `tools/gazetteer.scala` from a list of official names (the host's TSV: name,
   kind, city, source): Polish declension (the genitive and locative by rules with the irregular ones listed — "w
   Krakowie", "do Krakowa", "w Sopocie", "w Łodzi", "na Mokotowie"), Ukrainian and Russian transliteration of the
   Polish name (each language's conventions — "Варшава", "Краків"/"Краков", "Сопот", "Мокотув") with their own
   declension (the locative, genitive, dative and accusative: "у Варшаві", "в Варшаве", "Варшави", "Варшавы", "в
   Сопоті", "в Сопоте", "до Сопота"), the exonyms of the large cities by hand (Warszawa → Варшава, not "Варшава" by
   letters by chance; Wrocław → Вроцлав; Kraków → Краків/Краков), and the Latin spelling of the Cyrillic forms
   (`fold`, so "v Sopote" is "в Сопоте"). `Places.find(text)` → the places named (their ids, the words they took);
   `Places.canonical(text)` the text with each place written as its Polish name. A host uses it for its place slot
   (the router's evidence `place`), its area answers (a place named matched against the places it serves by id, not
   by letters), its search (a city or a district in any form) and its geocoder (the Polish name asked).
2. **Named entities on a page that lists several** (the mall's home): a shop named by its name — in either script,
   with a typo — is a slot like a service (`Spell` folding and one edit per word of 4 letters or more, the words by
   their rarity among the names); with a shop named and a shop's own fact asked (hours, address, contact, prices),
   the answer is that shop's, never a search. The host's own facts (the mall's contact) are an entity of its own.
3. **Comparisons only of comparable things** (core): a property may say what it is comparable by
   (`Prop.comparableBy`, e.g. a service's `price` by its `kind`); a comparison (`<`, `<=`, `>`, `>=`, `min`, `max`,
   `argmin`, `argmax`) whose values are that property of entities of DIFFERENT sources is evaluated only when every
   entity has the same value of it — else the violation `comparable` (the model lane regenerates; the router never
   writes one: it filters by the kind first and, with none of the same kind elsewhere, says so in its authored
   answer). Within one source any two may be compared (a user who names both asks for it). The kind is the host's
   data (okay-mall: the catalogue niche's template service a service is nearest to, `niche/template`).
4. **Near intents asked back, not guessed**: the confusion of the router's decisions over every train/dev set,
   counted by pair; the systematic pairs fixed by evidence that tells them apart (a host's feature: a place named
   for area vs modes; an exact time or a day for free times vs hours; a booking's word for book vs slots; a phone's
   word vs an address's); what stays close — the combiner's top two within a margin (`clarify`, calibrated on
   train/dev) and both answerable — is asked back by an authored question with the two intents as suggestions
   (the host's `suggest`: a label and the text a click sends; no fact in them), never answered by a guess. A
   clarification is counted apart in the measure (asked back: not answered, not wrong).

### Measure (stage 6, §14)

A fixed set of realistic turns (≥150, pl/uk/ru/en, over fictional demo shops; data) run through: the router lane
(with the model lane behind it, as deployed), the model lane alone, and a "usual" baseline — the same model with the
shop's data as text in its prompt, answering freely (the prompt published beside the results) — counting the share
answered by the router, the router's accuracy (intent; slots), wrong facts in each lane's answers (a price, a time,
a duration or a name not true of the data), latency p50/p95 and cost a turn. Results in §14.6.

## 18. Rules and constraints (operator, 2026-11-03; stage 9, planned)

The rule (§2) widens from values to reasoning: the model writes neither a value, nor a conclusion, nor a solution.
A **derived fact** (from rules) and a **found solution** (from constraints) are facts like any other: produced by the
resolver, bound by handles, rendered by templates, each with its **proof**. Origin: the okay-inlay research
(~/work/my/okay-inlay specs/inlay.md §8) — Datalog and a SAT solver built inside transformers; here the same
programs run as the resolver's handlers in the Worker (one program, many handlers: the compiled form is a deeper
port of the same thing, later).

### 18.1 Two kinds of facts

- **Data facts** — prices, slots, masters, hours, addresses: from the sources; the model never sees a hidden one and
  never writes any (§2, unchanged).
- **Intent facts** — what the user wants: "rather before noon", "at Anna's if possible", "300 zł at most". They are
  the user's words, not data, so the model *extracts* them (that is its semantic work) — always marked as the model's
  judgement with its confidence, kept apart from data facts in every proof, and confirmable ("did I get it right that
  you prefer the morning?"). A number the user said (300 zł) is an intent literal (`Expr.Lit`, already allowed as a
  comparison's operand), never a data value.

### 18.2 The language grows (`Expr`, §6)

- `require(e)` — a hard constraint over handles and variables: `require(price(?a) + price(?b) <= 300 PLN)`,
  `require(consecutive(?a, ?b))`, `require(day(?a) = tomorrow)`.
- `prefer(e, w)` — a soft constraint with weight `w` (the model's confidence that the user wants it):
  `prefer(before(?a, 12:00), 0.8)`, `prefer(master(?a) = #m2, 0.6)`.
- `solve(?a in slots(#v1), ?b in slots(#v2))` — choice variables over found handles; the result is new handles
  (`#s3`, `#s7`) issued in the conversation's table, used in `params` as any handle.
- Rules are not written by the model: they come from the ontology (§4; OWL 2 RL is Datalog) and the shop's policies
  ("a booking at least 2 h before closing", "a master does only the services she is linked to", "manicure ⊑ nail
  service", "Mokotów ⊑ Warszawa").

### 18.3 The resolver's new handlers

- **Datalog** (bottom-up, semi-naive; small — a shop's data and rules) — derived facts with their derivation trees.
- **SAT / weighted MaxSAT** (small: tens to hundreds of variables for a booking) — hard clauses must hold, soft ones
  maximize total weight; an UNSAT core when nothing satisfies the hard ones.
- **The model's hints**: per choice variable a guessed value and a confidence (from the conversation) — used to order
  the search (decide confident variables first, try the guess first, branch only where unsure); they speed the search
  and never change its result (okay-inlay `Gated.sc`, `Branching.sc`: counts and answers equal under every hint).
- In the Worker these are ordinary code (milliseconds at these sizes); on okay-serverless' host the same; the
  compiled-into-weights form (okay-inlay) is research.

### 18.4 Proofs and explanations

- An answer's `facts[]` (§7) gains `proof`: for a derived fact its derivation (rules applied, data facts used, with
  sources); for a solution the hard constraints it meets (each evaluated on data facts) and the soft ones it honours
  or drops (with their weights and that they are the model's judgements).
- **No solution** — the UNSAT core (a minimal set of conflicting conditions) becomes the explanation, rendered by the
  resolver's phrases: "not consecutive at Anna's before noon: she is busy in the morning (calendar)"; MaxSAT's
  relaxations become the alternatives ("consecutive at Anna's at {s3}, or before noon at {m2}'s"). The model chooses
  words for them; it never invents an alternative.

### 18.5 The flow (okay-mall, a booking)

"Manicure and pedicure back to back tomorrow, rather before noon, at Anna's if possible, 300 zł at most":

1. The model: `find` the two services (`#v1`, `#v2`); writes `require` (back to back, tomorrow, the sum ≤ 300 PLN —
   an expression, no price known), `prefer` (morning 0.8, Anna 0.6), `solve`; hints if it has them.
2. The resolver: data from the shops' objects, the rules (Datalog), the solver; returns `#s3`, `#s7` with the proof.
3. The answer: `{params, template}` as always — "{s1} manicure at {m1}'s, right after it {s2} pedicure, together
   {sum}" — every value a chip, every "why" a branch of the proof; on no solution, the core's explanation and the
   relaxations.

### 18.6 Checks the validator adds (§9)

- `require` / `prefer` / `solve` parse and type-check; variables range over issued handles only; a `prefer` weight is
  in (0, 1]; a literal appears only as an intent literal (a comparison's operand), never as a data value.
- A template may not state a solution's feasibility or a reason in words: "free", "possible", "because" come from the
  resolver's phrases of the proof (the worded-claims check, §9, extended).

### 18.7 Stage 9 (after the router lane and the measure)

1. `Expr`: `require`, `prefer`, `solve`, choice variables; the validator's checks (§18.6). Tests without a model.
2. Datalog handler + the shops' rules from okay-shop's data (service links, hours, policies); derivations in `facts[]`.
3. SAT / MaxSAT handler, UNSAT cores, relaxations; phrases for proofs and explanations (pl/uk/ru/en).
4. Hints: values and confidences from the model's answer (an optional `hints` field), search ordering by them.
5. okay-mall: the booking flow of §18.5 on the demo tenants; measure against the current chat on multi-condition
   requests (right answers, "no" answers with a correct reason, turns, latency).
