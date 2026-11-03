# AGENTS.md — okay-facts

Read `specs/design.md` first: the spec is written before the code and kept true after it. Work from `BACKLOG.md`
(stages in order) and `SPRINT.md` (what is in flight), per the scrumban discipline.

- **The rule**: the model never receives, writes or computes a fact. It sees handles, writes a template with named
  parameters and expressions over handles; our resolver evaluates and renders every value (specs/design.md "The
  rule"). Anything that breaks this rule is a bug, however convenient.
- Effects: okay-scala effects and handlers on okay-serverless (the same stack as okay-mall and okay-shop). Model
  calls go through okay-llm's ports; data sources, the model, audit are effects with handlers; run in one line.
- Cross-built JVM + Scala.js (the resolver runs inside a Cloudflare Worker and on okay-serverless' host).
- Scala 3, lines at most 120 characters, English in code and docs. Scripts are scala-cli, never Python.
- Builds from released libraries only. Tests never need a model: the resolver, the validator and the renderer are
  tested on fixed model outputs; a fake model port replays them.
- Test data is fictional (a demo barber, a demo nail studio); never a real business's data.
- Commits: plain messages, no attribution trailers. Never print or commit a secret.
