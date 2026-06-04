# orc guide (our friendly, evolving version)

> Our working guide to building orc bots in **this** project. Seeded from orc's
> own README and refined as we learn. The pristine upstream docs live in
> [`orc-reference/`](orc-reference/) — when something here is unclear, check
> those (they match the exact orc SHA we depend on). Grow this file as we go.

## Mental model in one minute

- A **workflow** is a **behaviour tree** (orc calls a built tree a *sheet*).
- Every workflow has a **blackboard**: typed shared memory, a map of
  `key → malli schema`. Inputs, intermediate values, and outputs all live here.
- The tree is made of **nodes**:
  - **Composite** nodes control flow: `sequence` (in order, stop on failure),
    `fallback` (first success wins), `parallel` (all at once), `map-each`
    (over a collection).
  - **Leaf** nodes do work: `llm` (prompt → output), `code` (Clojure via SCI),
    `condition` / `llm-condition` (branching), `repl-researcher` (iterative
    code-gen + tool calls), `delegate` (run another workflow).
- Each **leaf** declares `:reads` and `:writes` (which blackboard keys it
  consumes/produces) and, for LLM leaves, an `:instruction` (the prompt) and
  `:model`.
- You **`build-workflow!`** (idempotent; deterministic id from the name) then
  **`execute`** with inputs; you get `{:status :outputs :duration-ms}` back.

## The smallest possible workflow

```clojure
(require '[ai.obney.orc.orc-service.interface :as orc])

(def wf
  (orc/workflow "summarizer"
    (orc/blackboard {:input :string :summary :string})
    (orc/llm "summarize"
      :model "google/gemini-3-flash-preview"
      :instruction "Summarize the input in two sentences."
      :reads [:input] :writes [:summary])))

(let [sheet-id (orc/build-workflow! ctx wf)]
  (orc/execute ctx sheet-id {:input "..."}))
;; => {:status :success :outputs {:input "..." :summary "..."} :duration-ms n}
```

`ctx` is our grain context — `(:orc.template.backend/context app)`.

## Describe fields on the blackboard (not in every prompt)

Each blackboard key is a **malli schema**, and it can carry a `{:description ...}`
property. orc passes that description into dscloj's LLM **signature** for every
node that reads or writes the key (`executor/build-field` →
`extract-schema-description`). So you describe each field **once**, on the
blackboard, and node `:instruction`s only need to say what to *do*.

```clojure
(orc/blackboard
  {:topic      [:string  {:description "The subject the user wants explained."}]
   :key-points [:string  {:description "Three concise, factual bullet points."}]
   :summary    [:string  {:description "A beginner-friendly one-paragraph summary."}]
   ;; properties go in the SECOND position, before any child schemas:
   :leads      [:vector  {:description "Leads to qualify"} [:map-of :keyword :any]]
   :qualified? [:boolean {:description "Whether the lead meets the threshold"}]})
```

- **Node config stays inline** (orc-native): `:instruction`, `:reads`, `:writes`,
  `:model` live on each `orc/llm` node. There is no "instructions in the
  blackboard" mode — instructions are a node concern; *descriptions* are a field
  (blackboard) concern.
- For a `:map` output schema, orc flattens nested fields and uses their inner
  `:description`s too — good for structured extraction.
- Real examples: `daryls-area51/development/src/lead_qualification_demo.clj`,
  `unified_ontology.clj`, `gepa_real_llm_test.clj`.

```clojure
;; Field described once on the blackboard; instruction stays short.
(orc/llm "summarize"
  :model m
  :instruction "Summarize the topic for a beginner, using the key points."
  :reads [:topic :key-points] :writes [:summary])
```

See `src/orc/template/example_workflow.clj` for the working version.

## Node types (cheat sheet)

| Node | Kind | Use |
|------|------|-----|
| `sequence` | composite | run children in order, fail on first failure |
| `fallback` | composite | run in order, succeed on first success |
| `parallel` | composite | run all children concurrently |
| `map-each` | composite | map a subtree over a collection |
| `llm` | leaf | prompt + inputs → outputs |
| `code` | leaf | Clojure via SCI sandbox (`:fn` fq-symbol string) |
| `condition` / `llm-condition` | leaf | branch on code predicate / LLM yes-no |
| `repl-researcher` | leaf | iterative code-gen + tool calls (RLM); see RLM-GUIDE |
| `delegate` | leaf | run another workflow with an isolated blackboard |

## Models & providers — swap and mix

The flow is `orc → litellm → provider`. You register providers with
`litellm.router/register!` and point orc at **one base provider** via the context
key `:dscloj-provider`.

```clojure
(require '[litellm.router :as r])
(r/register! :openrouter {:provider :openrouter :model "google/gemini-3-flash-preview"
                          :config {:api-base "https://openrouter.ai/api/v1"
                                   :api-key (System/getenv "OPENROUTER_API_KEY")}})
;; also: :openai :anthropic :gemini :mistral :ollama  (or r/setup-openai! etc.)
```

- **Different model per node** — set `:model` on the node. orc auto-registers a
  `:<provider>/<model>` config by **cloning the base config**
  (`executor/get-provider-with-model`) — no extra registration. With OpenRouter
  as the base, one key reaches every vendor via the model string:
  ```clojure
  (orc/sequence "main"
    (orc/llm "draft"  :model "google/gemini-3-flash-preview" ...)   ; fast/cheap
    (orc/llm "polish" :model "anthropic/claude-3.5-sonnet"   ...))  ; stronger
  ```
- **Multiple distinct provider backends** (separate keys/endpoints) — register
  several configs; pick the base per workflow via `:dscloj-provider`. Within one
  tree the base is fixed and per-node `:model` varies on top; OpenRouter avoids
  needing more than one base.
- **repl-researcher main + sub LM** — `:model` is the **main** LM that designs &
  generates the tree (Phase 1). `:rlm {:sub-model "..."}` is injected into every
  emitted behaviour-tree `llm` tick (Phase 2) that doesn't pin its own `:model`
  (`executor/inject-sub-model`). So plan with a strong model, run ticks on a
  cheap one:
  ```clojure
  (orc/repl-researcher "research"
    :model "anthropic/claude-3.5-sonnet"
    :rlm {:sub-model "google/gemini-3-flash-preview"}
    :instruction "..." :reads [:input] :writes [:result])
  ```

## Things we've confirmed in this project

- `execute` is synchronous and returns outputs as a map keyed by blackboard
  keywords (e.g. `(get-in result [:outputs :summary])`).
- `build-workflow!` returns the sheet-id; re-building an unchanged definition is
  a zero-event no-op.
- Provider is selected via `:dscloj-provider` in the context; `backend.clj`
  registers it (default OpenRouter) in `register-openrouter!`.

## To explore next (grow this section)

- `repl-researcher` / RLM mode — when the tree shape isn't known up front
  (see `orc-reference/RLM-GUIDE.md`).
- `judges` + the `evaluation` component — LLM-as-judge scoring.
- GEPA — automatic instruction optimization.
- `code` and `map-each` worked examples.
- Wiring orc workflows to grain commands/processors (event-driven bots).
