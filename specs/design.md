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
  the okay-circuits notes; `owl:FunctionalProperty` is the name of the condition.)
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

| Level | The model can | Mode | Guarantee |
|---|---|---|---|
| L0 | text only | inline markers; queries by a text protocol (`⟦find …⟧` lines executed, conversation continued); render after | values exact; validator catches violations |
| L1 | + streaming | the same, rendered live | same, no delay |
| L2 | + tool calls | native `find`/actions | exact search and filters |
| L3 | + structured outputs / JSON Schema | `{params, template}` by schema | the answer has no place for a value |
| L4 | + grammar / logits (local models: okay-mlx, llama.cpp) | constrained generation: digits and declared entities impossible outside placeholders | a violation is technically impossible |
| L5 | own weights | an okay-circuits module inside the model (research, not in scope here) | by construction |

Strict mode requires L3 or L4; L0–L2 run with policy `regenerate`/`block`.

**Streaming**: with `params` first, the resolver evaluates them as soon as the `params` object closes; the template
then streams rendered. A placeholder split across chunks (`{pri` + `ce}`) is held back until closed.

## 11. Connecting models and agents

**The rule of connection: the strict guarantee exists only where okay-facts stands between the model and the user.**

| Way | Looks like | Who writes the final answer | Guarantee |
|---|---|---|---|
| 1. Library / SDK | your app calls okay-facts, it calls the model | model the template, okay-facts the values | strict |
| 2. Proxy (Anthropic- and OpenAI-compatible endpoints) | the app changes `base_url` (+ `X-Okay-Facts: <tenant>/<schema>`); okay-facts runs the tool loop inside, returns bound text + `facts[]` | same | strict |
| 3. MCP server | a foreign agent (Claude Code, Desktop, Cursor…) calls `find`, `get_fact`, `verify_answer` | the foreign model | verification and audit only |

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

| Class | Properties (visibility) |
|---|---|
| `Shop` (schema.org LocalBusiness) | name (visible), category (visible), city (visible), address (hidden), url (hidden), hours (hidden), rating (hidden) |
| `Service` (Offer of a Service) | name (visible), price (hidden), duration (hidden), master (link) |
| `Master` (Person) | name (hidden), services (link) |
| `Slot` | time (hidden), master (link), service (link) |
| `Review` | rating (hidden), text (hidden) |

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

- Library name and repository: `okay-facts` (private or public — the operator decides; the product name for
  businesses may differ).
- Which Workers AI model reaches L3 reliably (stage 3 decides).
- Whether the chat widget is part of okay-shop's pages' script or a separate script.
- Retention of conversations and the client's data in `ChatObject` (GDPR: purpose, period, deletion on request).
