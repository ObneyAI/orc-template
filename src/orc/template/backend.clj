(ns orc.template.backend
  "Barebones grain + orc backend.

  CQRS / event sourcing on a Postgres event store, with the orc agent framework
  wired to OpenRouter (google/gemini-3-flash-preview). No web/UI layer — you
  drive the system from the REPL and build orc bots against the grain context.

  Holds the Integrant system (Postgres event store, LMDB read-model cache,
  pub/sub, todo-processor polling, periodic-task triggers) plus your grain
  primitives (commands, queries, read models, processors, periodic tasks) and
  the orc/OpenRouter provider wiring.

  REPL:  (def app (start))   ;; needs Postgres up + OPENROUTER_API_KEY set
         (stop app)"
  (:require [ai.obney.grain.code-agent-tools.interface :as code-agent-tools]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            ;; Side-effect require: registers the :postgres event-store backend.
            [ai.obney.grain.event-store-postgres-v3.interface]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.periodic-task.interface :as pt]
            [ai.obney.grain.pubsub.interface :as ps]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            ;; Side-effect require: loads + registers all orc commands, queries,
            ;; todo-processors and schemas into the global registries.
            [ai.obney.orc.orc-service.interface :as orc]
            [litellm.router :as litellm-router]
            [com.brunobonacci.mulog :as u]
            [integrant.core :as ig]))

;; --------------------------------------------------------------------------- ;;
;; Tenant                                                                       ;;
;; --------------------------------------------------------------------------- ;;
;; grain is multi-tenant at the structural level; a single-tenant app just
;; needs one stable tenant id. (Regenerate this uuid for your own app.)

(def tenant-id #uuid "5f3b2c10-0000-4000-8000-000000000001")

;; --------------------------------------------------------------------------- ;;
;; LLM provider (orc → litellm → provider)                                      ;;
;; --------------------------------------------------------------------------- ;;
;; orc reads ONE base provider keyword from the context (`:dscloj-provider`,
;; below). Register that keyword with litellm here. This template defaults to
;; OpenRouter, but swapping is a one-liner — e.g.:
;;   (litellm-router/register! :openai    {:provider :openai
;;                                          :model "gpt-4o-mini"
;;                                          :config {:api-key (System/getenv "OPENAI_API_KEY")}})
;;   (litellm-router/register! :anthropic {:provider :anthropic
;;                                          :model "claude-3-5-sonnet-latest"
;;                                          :config {:api-key (System/getenv "ANTHROPIC_API_KEY")}})
;;   (litellm-router/register! :gemini    {:provider :gemini :model "gemini-2.5-flash"
;;                                          :config {:api-key (System/getenv "GEMINI_API_KEY")}})
;; then set :dscloj-provider to that keyword in ::context.
;;
;; Per-node models just work: set :model on any orc/llm node and orc clones this
;; base config for that model automatically. With OpenRouter, one key reaches all
;; vendors via the model string (e.g. "anthropic/claude-3.5-sonnet"). For a
;; repl-researcher, :model is the main LM and :rlm {:sub-model "..."} runs the
;; emitted behaviour-tree ticks on a cheaper sub LM. See docs/orc-guide.md.

(def openrouter-model "google/gemini-3-flash-preview")

(defn register-openrouter!
  "Register the :openrouter provider with litellm, reading the API key from the
  OPENROUTER_API_KEY env var. Idempotent enough to call on every start."
  []
  (let [api-key (or (System/getenv "OPENROUTER_API_KEY")
                    (throw (ex-info "OPENROUTER_API_KEY not set" {})))
        cfg {:provider :openrouter
             :model openrouter-model
             :config {:api-base "https://openrouter.ai/api/v1"
                      :api-key api-key}}]
    (litellm-router/register! :openrouter cfg)
    ;; Also register under the fully-qualified name some orc call sites use.
    (litellm-router/register! (keyword (str "openrouter/" openrouter-model)) cfg)
    :registered))

;; --------------------------------------------------------------------------- ;;
;; Grain primitives                                                             ;;
;; --------------------------------------------------------------------------- ;;
;; Build your domain here with grain's macros — defcommand / defquery /
;; defreadmodel / defprocessor / defperiodic — and their malli schemas
;; (defschemas). Keep everything to these primitives; that uniformity is the
;; whole point. orc workflows are built/executed against the context below.

;; --------------------------------------------------------------------------- ;;
;; Integrant system                                                             ;;
;; --------------------------------------------------------------------------- ;;

(def system
  {::logger {}

   ;; Event store. Swap backends by changing this :conn map (and, for sqlite/
   ;; postgres, the matching dep + side-effect require at the top of this ns):
   ;;   in-memory : {:type :in-memory}                         ;; no extra dep
   ;;   sqlite    : {:type :sqlite :database-file "storage/events.db"}
   ;;               ;; + dep grain-event-store-sqlite-v3
   ;;               ;; + require ai.obney.grain.event-store-sqlite-v3.interface
   ;;   postgres  : the map below (this template's default)
   ::event-store {:event-pubsub (ig/ref ::event-pubsub)
                  :conn {:type          :postgres
                         :server-name   (or (System/getenv "PG_HOST") "localhost")
                         :port-number   (or (System/getenv "PG_PORT") "5433")
                         :username      (or (System/getenv "PG_USER") "postgres")
                         :password      (or (System/getenv "PG_PASSWORD") "postgres")
                         :database-name (or (System/getenv "PG_DATABASE") "orc_template")}}

   ::event-pubsub {:type :core-async
                   :topic-fn :event/type}

   ::cache {}

   ::context {:event-store      (ig/ref ::event-store)
              :cache            (ig/ref ::cache)
              :event-pubsub     (ig/ref ::event-pubsub)
              :tenant-id        tenant-id
              :command-registry (cp/global-command-registry)
              :query-registry   (qp/global-query-registry)
              ;; orc picks its LLM provider from here:
              :dscloj-provider  :openrouter}

   ;; Polls the event store and dispatches registered todo-processors (including
   ;; orc's behaviour-tree tickers). Gets the full context so orc handlers have
   ;; the event store, registries and provider available.
   ::processors {:event-store (ig/ref ::event-store)
                 :tenant-id   tenant-id
                 :context     (ig/ref ::context)}

   ::periodic-triggers {:event-store (ig/ref ::event-store)
                        :tenant-id   tenant-id}})

;; --------------------------------------------------------------------------- ;;
;; Integrant lifecycle                                                          ;;
;; --------------------------------------------------------------------------- ;;

(defmethod ig/init-key ::logger [_ _]
  (u/start-publisher! {:type :console :pretty? true}))

(defmethod ig/halt-key! ::logger [_ stop-fn]
  (stop-fn))

(defmethod ig/init-key ::event-store [_ config]
  (es/start config))

(defmethod ig/halt-key! ::event-store [_ event-store]
  (es/stop event-store))

(defmethod ig/init-key ::event-pubsub [_ config]
  (ps/start config))

(defmethod ig/halt-key! ::event-pubsub [_ event-pubsub]
  (ps/stop event-pubsub))

(defmethod ig/init-key ::cache [_ _]
  (kv/start
   (lmdb/->KV-Store-LMDB {:storage-dir "storage/cache"
                          :db-name "orc-template"})))

(defmethod ig/halt-key! ::cache [_ cache]
  (kv/stop cache))

(defmethod ig/init-key ::context [_ context]
  context)

(defmethod ig/init-key ::processors [_ {:keys [event-store tenant-id context]}]
  (tp/start-tenant-poller
   {:event-store event-store
    :tenant-ids #{tenant-id}
    :context context
    :poll-interval-ms 250}))

(defmethod ig/halt-key! ::processors [_ poller]
  (tp/stop-tenant-poller poller))

(defmethod ig/init-key ::periodic-triggers [_ {:keys [event-store tenant-id]}]
  (pt/start-periodic-triggers!
   {:append-fn     (partial es/append event-store)
    :tenant-ids-fn (constantly #{tenant-id})}))

(defmethod ig/halt-key! ::periodic-triggers [_ triggers]
  (pt/stop-periodic-triggers! triggers))

;; --------------------------------------------------------------------------- ;;
;; Start / stop                                                                 ;;
;; --------------------------------------------------------------------------- ;;

(defn start
  "Boot the system. Requires Postgres reachable (see docker-compose.yml) and
  OPENROUTER_API_KEY in the environment."
  []
  (register-openrouter!)
  (let [app (ig/init system)]
    (u/set-global-context! {:app-name "orc-template" :env "dev"})
    ;; Exposes the live grain runtime/registries to grain-code-agent-tools.
    (code-agent-tools/install! {:system app
                                :context (::context app)
                                :mode :dev})
    app))

(defn stop
  [app]
  (ig/halt! app))

(comment
  ;; 1. docker compose up -d postgres
  ;; 2. export OPENROUTER_API_KEY=sk-or-...
  (def app (start))
  (def ctx (::context app))

  ;; Build + run a trivial orc workflow against gemini-3-flash-preview.
  ;; See ai.obney.orc.orc-service.interface for the DSL + execution API.

  (stop app))
