# okay-facts

A fact resolver for LLMs. The model never receives or writes facts: it refers to them by handles in a template with
named parameters (like JDBC's named parameters), and okay-facts evaluates and renders every value from your data and
ontology, with a provable source. Connects as a library or as a proxy compatible with the Anthropic and OpenAI APIs;
for agents whose output it does not own, an MCP server that verifies answers.

Status: specification (`specs/design.md`). First user: okay-mall's chat across all shops.
