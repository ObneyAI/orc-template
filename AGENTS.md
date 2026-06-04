# Agent notes — orc-template

Barebones [grain](https://github.com/ObneyAI/grain) + [orc](https://github.com/ObneyAI/orc)
**backend**. CQRS / event sourcing on a **Postgres** event store, plus the orc
agent framework with the **OpenRouter** provider (`google/gemini-3-flash-preview`).
No web/UI layer — driven from the REPL.

## Layout
- `src/orc/template/backend.clj` — Integrant system (Postgres event store,
  LMDB read-model cache, pub/sub, todo-processor polling, periodic triggers), the
  orc/OpenRouter wiring, and all grain primitives.

## Hard architectural rule
The only things to add are grain/orc **primitives**: `defcommand`, `defquery`,
`defreadmodel`, `defprocessor`, `defperiodic`, their malli schemas
(`defschemas`), and orc workflows. If a change isn't one of those, it's probably
wrong — keep the system uniform.

## Running
1. `docker compose up -d postgres`
2. `export OPENROUTER_API_KEY=sk-or-...`
3. `./scripts/nrepl.sh` (or `clojure -M:dev`), then
   `(def app (orc.template.backend/start))`
4. Build/execute orc workflows against `(::context app)`.

## orc / LLM provider
orc reads its provider from the context key `:dscloj-provider :openrouter`.
`register-openrouter!` (called by `start`) registers it with litellm against
OpenRouter, defaulting to `google/gemini-3-flash-preview`. Per-node model
overrides are supported in the orc DSL. See orc's
`ai.obney.orc.orc-service.interface` for the DSL + execution API.

## Adding an HTTP API later (optional)
This start has no web server. If you want to expose commands/queries over HTTP,
add `grain-core-v2`'s `command-request-handler-v2` / `query-request-handler` and
the `webserver` (Pedestal) component, compose their routes, and start the server
in a new Integrant key.

## Pinned dependency SHAs (deps.edn)
grain `d34b4496…`, orc `83c315ad…` — a known-good, mutually-compatible set (from
the `ObneyAI/grain-todo-list` reference repo). Bump deliberately, together.
