# BACKLOG — okay-facts

Stages in order; each is specified in specs/design.md §14 (what, done-when). Promote the next one to SPRINT.md
before starting it.

1. **core** — metamodel, handles, expressions, `{params, template}`, ICU rendering with plurals (pl/uk/ru/en),
   validator, fake model port. §4–§9.
2. **binding** — the conversation loop over okay-llm ports, L0–L4 modes, streaming with `params` first, policies. §10.
3. **tck** — the conformance suite and model passports; Workers AI candidates. §11.
4. **okay-shop assistant** — the shop's fact schema, `find`/`open`/`book`, the widget (in okay-shop, released). §12.
5. **okay-mall chat** — `ChatObject`, the mall-domain cookie, focus, mall-wide find, per-shop enabling (in
   okay-mall). §12. Demo tenants only until the operator says otherwise.
6. **measure** — usual (data in context) vs okay-facts. §14.6.
7. **demo, proxy, MCP, importers** — §13, §11, §4.
