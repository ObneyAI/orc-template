# orc-template

A ready-to-run **backend template** for building [orc](https://github.com/ObneyAI/orc)
agents on top of [grain](https://github.com/ObneyAI/grain).

Clone it and you have a working orc backend immediately — the Integrant system,
the grain event store, the LMDB read-model cache, the todo-processor poller,
periodic triggers, and an LLM provider are **already wired**. No `clj-new`, no
hand-rolling the grain/Integrant plumbing. Point it at an event store, set a
provider key, and start building bots.

> Defaults: **OpenRouter** + `google/gemini-3-flash-preview` + **Postgres**.
> Each is a one-liner to change — see
> [Pick your LLM provider](#pick-your-llm-provider) and
> [Pick your event store](#pick-your-event-store).

---

## What you get out of the box

- **grain** — CQRS / event sourcing. Model your domain as a fixed set of
  primitives: `defcommand`, `defquery`, `defreadmodel`, `defprocessor`,
  `defperiodic`, with malli `defschemas`. Events are the source of truth.
- **orc** — agent framework on grain. Define **behaviour trees** that read/write
  a typed **blackboard**; leaf nodes call LLMs (`llm`), run code (`code`),
  iterate (`map-each`), branch, or do iterative tool-use (`repl-researcher`).
- **A booted system** — `backend.clj` wires it all with Integrant and exposes
  `(start)` / `(stop)`. An example tree lives in `example_workflow.clj`.

---

## Quickstart

```bash
# 1. Event store. Default is Postgres via Docker (host :5433):
docker compose up -d
#    (or switch to in-memory / SQLite — see "Pick your event store")

# 2. LLM provider key (default provider is OpenRouter):
export OPENROUTER_API_KEY=sk-or-...

# 3. REPL with the LMDB JVM flags:
./scripts/nrepl.sh        # or: clojure -M:dev
```

```clojure
(require '[orc.template.backend :as backend]
         '[orc.template.example-workflow :as ex])

(def app (backend/start))
(def ctx (:orc.template.backend/context app))

(ex/run! ctx "event sourcing")
;; => {:status :success
;;     :outputs {:topic      "event sourcing"
;;               :key-points "- Stores every state change as an immutable event\n- Rebuilds current state by replaying events\n- Commonly paired with CQRS"
;;               :summary    "Event sourcing records each change to application state as an immutable event, so the current state can be rebuilt by replaying the event history; it is commonly paired with CQRS to separate writes from reads."}
;;     :duration-ms 1873}

(backend/stop app)
```

---

## Pick your LLM provider

orc reads **one base provider** from the grain context key
`:dscloj-provider`. You register that keyword with litellm. Switching vendor is
just a different `register!` call — everything downstream is unchanged.

```clojure
(require '[litellm.router :as r])

;; OpenRouter — one key, reaches every vendor via the model string (this template's default)
(r/register! :openrouter {:provider :openrouter :model "google/gemini-3-flash-preview"
                          :config {:api-base "https://openrouter.ai/api/v1"
                                   :api-key (System/getenv "OPENROUTER_API_KEY")}})

;; OpenAI
(r/register! :openai    {:provider :openai    :model "gpt-4o-mini"
                         :config {:api-key (System/getenv "OPENAI_API_KEY")}})

;; Anthropic
(r/register! :anthropic {:provider :anthropic :model "claude-3-5-sonnet-latest"
                         :config {:api-key (System/getenv "ANTHROPIC_API_KEY")}})

;; Google Gemini (direct)
(r/register! :gemini    {:provider :gemini    :model "gemini-2.5-flash"
                         :config {:api-key (System/getenv "GEMINI_API_KEY")}})

;; Local Ollama
(r/register! :ollama    {:provider :ollama    :model "llama3"
                         :config {:api-base "http://localhost:11434"}})
```

There are also convenience helpers: `r/setup-openai!`, `setup-anthropic!`,
`setup-gemini!`, `setup-mistral!`, `setup-ollama!`, `setup-openrouter!` (each
reads the matching `*_API_KEY` env var).

Then set the base provider in the context (`backend.clj`):
```clojure
:dscloj-provider :openrouter   ;; or :openai / :anthropic / :gemini / :mistral / :ollama
```

### Many models, many providers — how easy it is

**Different model per node.** Set `:model` on the node; orc auto-registers a
`:<provider>/<model>` config by cloning the base provider's config — no extra
setup. With **OpenRouter** as the base, one key + a model string reaches OpenAI,
Anthropic, Google, Mistral, etc., so you can mix vendors freely across nodes.

This complete two-step workflow drafts with a fast Google model and polishes with
an OpenAI model, both through the single OpenRouter key (verified end-to-end):

```clojure
(require '[orc.template.backend :as backend]
         '[ai.obney.orc.orc-service.interface :as orc])

(def app (backend/start))
(def ctx (:orc.template.backend/context app))

(def draft-polish
  (orc/workflow "draft-polish"
    (orc/blackboard
      {:topic    [:string {:description "The subject to write about."}]
       :draft    [:string {:description "A rough first-draft paragraph about the topic."}]
       :polished [:string {:description "A polished, concise, engaging final paragraph."}]})
    (orc/sequence "main"
      (orc/llm "draft"
        :model "google/gemini-3-flash-preview"            ; fast/cheap (Google, via OpenRouter)
        :instruction "Write a rough one-paragraph draft explaining the topic."
        :reads [:topic] :writes [:draft])
      (orc/llm "polish"
        :model "openai/gpt-4o-mini"                       ; different vendor, same OpenRouter key
        :instruction "Rewrite the draft to be clearer, more concise, and more engaging."
        :reads [:draft] :writes [:polished]))))

(let [sheet-id (orc/build-workflow! ctx draft-polish)]
  (orc/execute ctx sheet-id {:topic "behaviour trees for AI agents"} :timeout-ms 90000))
;; => {:status :success
;;     :outputs
;;     {:topic "behaviour trees for AI agents"
;;      :draft "Behaviour trees are a hierarchical model used in game development and robotics to control the decision-making processes of AI agents. Unlike finite state machines, which can become tangled and difficult to manage as complexity increases, behaviour trees organize tasks into a modular tree structure consisting of leaf nodes that represent actions or conditions and composite nodes that control the flow of execution. These composite nodes, such as selectors and sequences, allow the agent to switch between different tasks based on environmental feedback or internal priorities."
;;      :polished "Behaviour trees are a powerful hierarchical model used in game development and robotics for AI decision-making. Unlike finite state machines, which can become unwieldy with complexity, behaviour trees present tasks in a modular tree structure. Leaf nodes represent actions or conditions, while composite nodes, such as selectors and sequences, manage the execution flow. As a result, developers can create complex, responsive behaviours that are scalable and easy to understand."}
;;     :duration-ms 8473}
```

**Multiple distinct provider backends** (e.g. direct OpenAI *and* direct Anthropic
with separate keys) — register several configs and pick the base per workflow via
the context's `:dscloj-provider`. Within one tree the base provider is fixed and
per-node `:model` varies on top of it; OpenRouter sidesteps needing more than one
base.

**repl-researcher: main LM + sub LM.** The researcher node's `:model` is the
**main** LM that designs and generates the tree (Phase 1). The behaviour-tree
ticks it emits (Phase 2) run on a **sub** model: set `:rlm {:sub-model ...}` and
orc injects it into every emitted `llm` node that doesn't pin its own `:model`:

```clojure
(orc/repl-researcher "research"
  :model "anthropic/claude-3.5-sonnet"               ; main: plans & writes the code/tree
  :rlm {:sub-model "google/gemini-3-flash-preview"}  ; sub: runs the cheap per-tick LLM calls
  :instruction "Investigate the question and produce a well-sourced one-paragraph answer."
  :reads [:question]
  :writes [:answer])
```

---

## Pick your event store

Same code, same grain API — only the dependency and the `:conn` map change. grain
creates its schema automatically on first start.

| Backend | Dependency (`deps.edn`) | Require (side-effect) | `:conn` |
|---------|-------------------------|-----------------------|---------|
| **In-memory** | _none_ (in `grain-core-v2`) | _none extra_ | `{:type :in-memory}` |
| **SQLite** | `obneyai/grain-event-store-sqlite-v3` | `ai.obney.grain.event-store-sqlite-v3.interface` | `{:type :sqlite :database-file "storage/events.db"}` (or `":memory:"`) |
| **Postgres** | `obneyai/grain-event-store-postgres-v3` | `ai.obney.grain.event-store-postgres-v3.interface` | `{:type :postgres :server-name "localhost" :port-number "5432" :username "postgres" :password "postgres" :database-name "app"}` |

To switch: change the dep + the side-effect `require` in `backend.clj`, and the
`:conn` map in the `::event-store` Integrant key. Everything else is untouched.
In-memory is great for tests/iteration; SQLite for local single-node apps;
Postgres for production (RLS, per-tenant advisory locks, Fressian serialization).

---

## deps.edn — pinned versions & packages

grain and orc are **git deps pinned to a SHA**. To update grain/orc later, bump
these two SHAs to their remote HEADs (and refresh the snapshots in
`docs/orc-reference/`):

| | Pinned SHA |
|---|---|
| **grain** | `0ab49d7fd3847ce5ec485d29a6fbe292ba4e7856` |
| **orc**   | `ebb9a48d38a2264292bcf47dc3e953acd926a859` |

### Critical — a base orc app

```clojure
{:deps {obneyai/grain-core-v2
        {:git/url "https://github.com/ObneyAI/grain.git"
         :git/sha "0ab49d7fd3847ce5ec485d29a6fbe292ba4e7856"
         :deps/root "projects/grain-core-v2"}

        obneyai/orc
        {:git/url "https://github.com/ObneyAI/orc.git"
         :git/sha "ebb9a48d38a2264292bcf47dc3e953acd926a859"
         :deps/root "projects/orc"}

        integrant/integrant {:mvn/version "1.0.1"}}

 ;; LMDB (grain's read-model cache) needs these JVM flags:
 :aliases {:dev {:jvm-opts ["--add-opens=java.base/java.nio=ALL-UNNAMED"
                            "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"]}}}
```

`grain-core-v2` is the whole CQRS/event-sourcing core (commands, queries, read
models, todo-processors, periodic tasks, pub/sub, kv-store + LMDB) **and includes
an in-memory event store**. `obneyai/orc` is the agent framework. That alone is a
working orc app.

### Event store — choose one

In-memory needs no extra dep (`{:type :in-memory}`). For a durable store, add one
block and require its interface namespace (so the `:conn` type registers):

```clojure
;; SQLite — local, single-node, file-backed
obneyai/grain-event-store-sqlite-v3
{:git/url "https://github.com/ObneyAI/grain.git"
 :git/sha "0ab49d7fd3847ce5ec485d29a6fbe292ba4e7856"
 :deps/root "projects/grain-event-store-sqlite-v3"}

;; Postgres — production (RLS, per-tenant advisory locks, Fressian)
obneyai/grain-event-store-postgres-v3
{:git/url "https://github.com/ObneyAI/grain.git"
 :git/sha "0ab49d7fd3847ce5ec485d29a6fbe292ba4e7856"
 :deps/root "projects/grain-event-store-postgres-v3"}
```

The matching `:conn` maps and requires are in
[Pick your event store](#pick-your-event-store).

### Optional grain packages (à la carte)

```clojure
;; Reactive server-rendered UI over SSE (Datastar)
obneyai/grain-datastar-v2
{:git/url "https://github.com/ObneyAI/grain.git"
 :git/sha "0ab49d7fd3847ce5ec485d29a6fbe292ba4e7856"
 :deps/root "projects/grain-datastar-v2"}

;; Distributed coordination — coordinator election, tenant leases, routing
obneyai/grain-control-plane
{:git/url "https://github.com/ObneyAI/grain.git"
 :git/sha "0ab49d7fd3847ce5ec485d29a6fbe292ba4e7856"
 :deps/root "projects/grain-control-plane"}

;; Exposes the live grain runtime/registries to AI coding agents over nREPL
obneyai/grain-code-agent-tools
{:git/url "https://github.com/ObneyAI/grain.git"
 :git/sha "0ab49d7fd3847ce5ec485d29a6fbe292ba4e7856"
 :deps/root "projects/grain-code-agent-tools"}

;; AWS CloudWatch metrics & dashboards
obneyai/grain-mulog-aws-cloudwatch-emf-publisher
{:git/url "https://github.com/ObneyAI/grain.git"
 :git/sha "0ab49d7fd3847ce5ec485d29a6fbe292ba4e7856"
 :deps/root "projects/grain-mulog-aws-cloudwatch-emf-publisher"}
```

grain also publishes `grain-dspy-extensions` (and v2 event-store backends) — see
the grain repo's `projects/` directory.

### About the orc dep

`obneyai/orc` is **one bundle** that already includes everything: the
`orc-service` engine + DSL (with **repl-researcher** built in), plus GEPA (prompt
optimization), evaluation (LLM-as-judge), ColBERT (retrieval), ontology, MCP
sheet-builder, langfuse, and the litellm provider layer. There are no separate
consumer deps for those — adding `obneyai/orc` gives you all of them.

> **Slim orc (advanced).** ColBERT and ontology pull heavy ML deps (Python /
> DJL + PyTorch). To drop them, depend on the engine component instead of the
> umbrella project and supply `grain-core-v2` yourself:
> ```clojure
> obneyai/orc-service
> {:git/url "https://github.com/ObneyAI/orc.git"
>  :git/sha "ebb9a48d38a2264292bcf47dc3e953acd926a859"
>  :deps/root "components/orc-service"}
> ```
> This keeps the behaviour-tree engine, DSL, and repl-researcher. Component
> coordinates can shift between commits — pin and review.

### What this template ships with

`grain-core-v2` + `grain-event-store-postgres-v3` + `grain-code-agent-tools` +
`obneyai/orc`, plus `integrant` and `org.clojure/data.json`. Drop or swap any —
e.g. remove the Postgres dep for in-memory, or drop `grain-code-agent-tools`
(and its `code-agent-tools/install!` call in `backend.clj`) if you don't want the
AI-agent runtime hooks.

---

## Project layout

```
deps.edn                                  pinned grain/orc git deps + aliases
docker-compose.yml                        local Postgres (host :5433)
scripts/nrepl.sh                          REPL with LMDB JVM flags
AGENTS.md                                 notes for AI coding agents
docs/
  orc-guide.md                            our friendly, evolving orc guide
  orc-reference/                          pristine upstream orc docs at our pinned SHA
src/orc/template/
  backend.clj                             Integrant system + provider wiring + your grain primitives
  example_workflow.clj                    a minimal example orc behaviour tree (your template)
```

---

## Building your own

- **Domain** — add grain primitives in `backend.clj`: `defcommand`, `defquery`,
  `defreadmodel`, `defprocessor`, `defperiodic`, with `defschemas`. Keep changes
  to these primitives — uniformity is the point.
- **Bots** — define orc workflows (own namespace, like `example_workflow.clj`),
  `build-workflow!` them, and `execute`.
- **Describe fields once, on the blackboard.** Each blackboard key is a malli
  schema and can carry a `{:description ...}` property; orc forwards it into the
  LLM signature for any node that reads/writes the key — so node `:instruction`s
  only say what to *do*:
  ```clojure
  (orc/blackboard
    {:topic   [:string {:description "The subject to explain."}]
     :summary [:string {:description "A beginner-friendly one-paragraph summary."}]})
  ```

---

## Configuration reference

| Var | Default | Used for |
|-----|---------|----------|
| `OPENROUTER_API_KEY` | _(required for default provider)_ | OpenRouter auth |
| `PG_HOST` / `PG_PORT` / `PG_USER` / `PG_PASSWORD` / `PG_DATABASE` | `localhost` / `5433` / `postgres` / `postgres` / `orc_template` | Postgres connection (only if using the Postgres store) |

- **Tenant** — one stable `tenant-id` in `backend.clj` (grain is multi-tenant;
  regenerate the uuid for your own app).
- **Reset the event store** (Postgres): `docker compose down -v && docker compose up -d`.

---

## Documentation

- **[docs/orc-guide.md](docs/orc-guide.md)** — our friendly, evolving guide to
  building orc bots in this project. Start here; grow it as we learn.
- **[docs/orc-reference/](docs/orc-reference/)** — verbatim upstream orc docs
  (README, RLM guide, pattern compendium, architecture) pinned to the exact orc
  SHA we depend on.

---

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| `Bind for 0.0.0.0:5432 failed: port is already allocated` | Another Postgres is on 5432; this template uses **5433**. Stop the other one or edit `docker-compose.yml`. |
| `OPENROUTER_API_KEY not set` on `start` | Export the key (or whichever `*_API_KEY` your chosen provider needs) in the shell that launched the REPL. |
| LMDB / `sun.nio.ch` error on start | Start with the `:dev`/`:test` `--add-opens` JVM flags — use `./scripts/nrepl.sh` or `clojure -M:dev`. |
| git deps won't resolve | `clojure -P -M:dev` to force a fetch; confirm the grain/orc SHAs in `deps.edn` exist on GitHub. |

---

## Reference repos

- grain: <https://github.com/ObneyAI/grain>
- orc: <https://github.com/ObneyAI/orc>
- example app this template's setup mirrors: <https://github.com/ObneyAI/grain-todo-list>
