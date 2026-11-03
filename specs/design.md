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
  half precision, with SIF weights; a text's vector is the weighted mean of its known units', normalised — a lookup
  and a sum, no inference. A safetensors file (`vecs` F16 `[n, dim]`, `labels.offsets` U64, `labels.bytes` U8,
  `weights` F64), read from its bytes in place: a row is decoded when a text uses it. Its coverage (the share of a
  text's words it knows) is reported.
- **The probe** (`Probe`): multinomial logistic regression over the table's vectors (okay's `Probe`: weights from
  zero, rows in order, 300 epochs, rate 0.5 — deterministic), fitted at BUILD time by a tool; the host carries the
  weights (JSON, classes × dim).
- **Language** (`Detect`): the message's script (any Cyrillic letter: Cyrillic), then letters only one language has
  and everyday words; an amount with its currency counts for none («do 150 zł» is not Polish); no evidence: the
  conversation's last language within the script, else the page's.
- **Slots, generic** — `When`: a day (today, tomorrow, the day after, a weekday, `5.11`, `5 listopada`), a part of
  the day (morning, afternoon, evening) and a time (`o 17`, `po 17`, `o 10:30`, `до 12`, `after 5pm`) in pl, uk, ru,
  en, relative to today in Warsaw (§ Zone); `Amount`: a price limit (`do 150 zł`, `poniżej 150`, `до 150`, `under
  150`, `max 150`); `Nearest`: the candidates nearest a text in the table (each unit of the text against each
  candidate's name, in any of its languages), with a threshold and a margin — two close: ambiguous, the host asks.
- **The router** (`Router`): the intents' rules first (in rank order), then the probe over the message's vector →
  `Route(intent, by: rule | probe | unknown, p, margin)`; `confident` when by a rule, or `p` and the margin pass the
  host's thresholds (calibrated on held-out authored rows, recorded in §16).
- **The data** (`Intents`): `{intents: [{name, rank, rules: [...], examples: {pl: [...], uk: [...], ru: [...],
  en: [...]}}]}` — a host's own file; the probe is fitted on its examples, the table distilled over its units.
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

### The turn decision (the host's)

Router first: a confident route whose slots resolve answers by the router; anything else — a probe below the
thresholds, a message of no known word, a slot it cannot resolve that the intent needs — goes to the model lane as
before (its validator, its policy, its budget). The host logs per turn the lane, the intent and the confidence —
never the message's words. A host can force a lane (tests, the measurement).

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
