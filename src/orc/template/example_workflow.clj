(ns orc.template.example-workflow
  "A minimal example orc behaviour tree — your starting template for building bots.

  It demonstrates the three things every orc workflow has:

    1. A BLACKBOARD — the typed, shared memory the tree reads from and writes to.
       Each key is a malli schema and may carry a `{:description ...}` property.
       orc forwards those descriptions to dscloj's LLM signature for every node
       that reads/writes the key — so you describe each field ONCE here, and the
       node `:instruction` only has to say what to DO, not re-explain the fields.
    2. A TREE of NODES — here a `sequence` (run children in order) of two `llm`
       leaves; the first writes a value the second reads, so you can see data
       flow through the blackboard between steps.
    3. BUILD + EXECUTE — `build-workflow!` persists the tree to the grain event
       store (idempotent, deterministic id from the name); `execute` runs it.

  No load-time side effects — nothing is built or called until you invoke
  `build!` / `run!`. Copy this namespace, rename it, and grow your own bots.

  See `ai.obney.orc.orc-service.interface` for the full DSL, and docs/orc-guide.md
  for the blackboard-description pattern."
  (:require [ai.obney.orc.orc-service.interface :as orc]))

(def model
  "Default model for this example's LLM nodes (override per-node with :model)."
  "google/gemini-3-flash-preview")

(def workflow
  (orc/workflow "example-brainstorm"
    ;; Blackboard: each key is a malli schema; the `{:description ...}` property
    ;; is passed to the LLM as the field's description (for any node that reads
    ;; or writes that key). Describe the data here, once.
    (orc/blackboard
     {:topic      [:string {:description "The subject the user wants explained."}]
      :key-points [:string {:description "Three concise, factual bullet points about the topic."}]
      :summary    [:string {:description "A single beginner-friendly paragraph summarizing the topic."}]})

    ;; Tree: nodes declare what to DO (:instruction) and which blackboard keys
    ;; they consume/produce (:reads / :writes). The field descriptions above mean
    ;; instructions can stay short.
    (orc/sequence "main"
      (orc/llm "brainstorm"
        :model model
        :instruction "List three key points about the topic."
        :reads  [:topic]
        :writes [:key-points])

      (orc/llm "summarize"
        :model model
        :instruction "Summarize the topic for a beginner, using the key points."
        :reads  [:topic :key-points]
        :writes [:summary]))))

(defn build!
  "Persist (or idempotently update) the example workflow in the event store.
  Returns the deterministic sheet-id. Safe to call repeatedly."
  [ctx]
  (orc/build-workflow! ctx workflow))

(defn run!
  "Build + execute the example against a topic string.

  `ctx` is the grain context, e.g. (:orc.template.backend/context app).
  Returns {:status :success|:failure|:timeout :outputs {...} :duration-ms n}."
  [ctx topic]
  (let [sheet-id (build! ctx)]
    (orc/execute ctx sheet-id {:topic topic} :timeout-ms 90000)))

(comment
  ;; Prereqs: `docker compose up -d` and `export OPENROUTER_API_KEY=sk-or-...`
  (require '[orc.template.backend :as backend])
  (def app (backend/start))
  (def ctx (:orc.template.backend/context app))

  ;; See the tree shape without running it:
  (orc/print-tree workflow)

  ;; Run it (two real LLM calls through OpenRouter):
  (run! ctx "event sourcing")
  ;; => {:status :success
  ;;     :outputs {:topic "event sourcing"
  ;;               :key-points "- ..."
  ;;               :summary "Event sourcing is ..."}
  ;;     :duration-ms 1234}

  (backend/stop app))
