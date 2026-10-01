(ns yin.repl.dht
  "The REPL's DHT index store, `--index-store dht:<dir>` (DHT epic slice
   S5: docs/design/dao.jing.dht.md section 10; docs/design/
   yin.repl.dao.space-index.md, \"DHT store\").  It is composed with the
   plain Clojure path, `dao.space.dht`, and adds only what the shell owns:
   the durable directory, the lines it prints, and when it admits
   evaluation.

   `open` opens `<dir>` exactly as `file:<dir>` does — the exclusive lock,
   the content log, HEAD, the validated snapshot (dao.space.store) — and
   joins a `dao.space.dht` node with that locked store as its `:local`.
   The indexer publishes through the node's store: each put inserts
   locally and appends one replicate request, and the manifest reads back
   locally, so a round never waits on the network.  The HEAD write then
   announces the publication to the node.

   `step` is the node's one step owner, called by the host's single
   ticker (yin.repl.main/step-all) with the host's clock reading; it
   renders the node's events as the lines the REPL prints: where the
   socket bound, and for each publication its result — acknowledged,
   sent to N peers, or partial or not acknowledged with the count of
   blobs not sent, the first reason, and whether the node is retrying —
   and each later change of it (yin.vm.linker.dht.md 5.5.6).  A load's
   failure reason is data; it is rendered as text here.

   A reader handed a manifest address (`:manifest`) loads it through
   `dao.space.dht/load-index` before the shell admits any evaluation, as a
   restart installs its recovery first, then writes it to HEAD and
   rehydrates the indexer from the loaded datoms, so `q` answers the
   remote run's facts.  A load that cannot complete, or a socket that
   cannot bind, refuses the shell (`refusal`); it never starts over an
   empty index.  Remote indexes are also reachable at the prompt, through
   the `dao.space.dht` host module (yin.repl.query), over the same node."
  (:require [clojure.string :as str]
            [dao.space.dht :as dht]
            [dao.space.store :as durable]
            [yin.repl.index :as index]
            [yin.repl.store :as store]))


(def default-max-inbound-bytes
  "The CLI default of the inbound storage bound; `dao.space.dht`'s."
  dht/default-max-inbound-bytes)


(defn- refused-head
  "Why a reader cannot hydrate `manifest` into a directory whose own HEAD
   already names `recovered`, or nil.  Hydration never replaces an index
   the directory holds."
  [dir manifest recovered]
  (when (and manifest recovered (not= manifest recovered))
    (str dir " already holds an index (HEAD names " recovered "); hydrate "
         manifest " into an empty directory")))


(defn open
  "Open a `dht:<dir>` store from its spec (yin.repl.store/checked-spec):
   the durable directory store, and the `dao.space.dht` node joined over
   it.  Answers the store handle the shell keeps — the node's store, with
   the durable HEAD write announcing each publication, the recovery, and
   the directory — carrying the node under `:dht`, which the shell moves
   onto its own state (yin.repl/create-state)."
  [spec]
  (let [{:keys [dir peers publish? bind-host bind-port max-inbound-bytes
                manifest bind!]} (store/checked-spec spec)
        base (durable/open dir)]
    (try
      (when-some [why (refused-head dir manifest
                                    (get-in base [:recovery :manifest]))]
        (throw (ex-info why {:dir dir :manifest manifest})))
      (let [node (dht/join {:local base
                            :peers peers
                            :publish? publish?
                            :bind-host bind-host
                            :bind-port bind-port
                            :max-inbound-bytes max-inbound-bytes
                            :bind! (or bind! (dht/default-bind))})
            node (cond-> (assoc node ::dir dir)
                   (and manifest (not= manifest (get-in base [:recovery :manifest])))
                   (-> (dht/load-index manifest)
                       (assoc ::hydrating manifest)))]
        (assoc (dht/store node)
               :head-fn (fn [manifest-address]
                          ((:head-fn base) manifest-address)
                          (dht/announce! node manifest-address))
               :recovery (:recovery base)
               :durable-dir dir
               :dht node))
      (catch #?(:cljd Object :clj Throwable :cljs :default) e
        (store/close! base)
        (throw e)))))


(defn without-runner
  "The store handle the shell keeps: `open`'s answer without the node,
   which the shell carries as its own `:dht`.  Any other handle is
   answered as it is."
  [handle]
  (dissoc handle :dht))


;; =============================================================================
;; Lines
;; =============================================================================

(defn- address-text
  [host port]
  (if (str/includes? host ":") (str "[" host "]:" port) (str host ":" port)))


(defn- reason-text
  "One blob's failure reason as the REPL prints it."
  [node {:keys [reason peers]}]
  (case reason
    :dao.jing.dht/solo (str "solo — no --dht-peer is configured, so nothing "
                            "was sent; the index is in " (::dir node) " only")
    :dao.jing.dht/unpublished (str "publication is off — this node fetches "
                                   "only; start with --dht-publish to share")
    :dao.jing.dht/too-few-peers (str "too few peers — sent to " peers " of "
                                     (dht/ack-peers node) " before the "
                                     "acknowledgement deadline")
    :dao.jing.dht/oversize "a blob exceeds the DHT message bound"
    :dao.jing.dht/absent "a blob was no longer in the local store"
    :dao.jing.dht/busy "too many writes were pending"
    :dao.space.dht/backlog-full "the replicate backlog was full"
    :dao.space.dht/publications-full (str "too many publications awaited "
                                          "their first report")
    :dao.space.dht/cancelled "cancelled"
    (str reason)))


(def ^:private ended-text
  {:dao.space.dht/terminal "every remaining failure is final"
   :dao.space.dht/cancelled "cancelled"
   :dao.space.dht/displaced "displaced by newer publications"})


(defn- result-text
  "A publication's result (yin.vm.linker.dht.md 5.5.6): when it is not
   acknowledged, the count of failed blobs, the first reason, and whether
   the node is retrying."
  [node {:keys [result blobs peers failed repairing? ended]}]
  (if (= :acknowledged result)
    (str "acknowledged: sent to " peers " peers")
    (str (if (= :partial result)
           "PARTIAL: the manifest was sent; "
           "NOT acknowledged: ")
         (reason-text node (first failed))
         "; " (count failed) " of " blobs " blobs not sent"
         (cond
           repairing? "; retrying while the node is open"
           ended (str "; not retrying (" (get ended-text ended (str ended)) ")")
           :else "; not retrying")
         "; the local copy is durable")))


(defn- failure-text
  "A load's failure reason, data (dao.space.dht 4.3), as text."
  [{:dao.space.dht/keys [failure] :keys [address cause defect outcome]}]
  (case failure
    :miss (str "no peer produced " address " (" (name cause) ")")
    :invalid (str "the content " (when address (str "at " address " "))
                  "is invalid (" (:code defect) ")"
                  (when-let [text (:text defect)] (str ": " text)))
    :unaskable (str "could not ask for " address " (" (name outcome) ")")
    (pr-str failure)))


(defn- event-line
  [node {:keys [manifest blobs host port id reason datoms fetched], :as event}]
  (case (::dht/event event)
    :bound (str "dht: node " (subs id 0 16) " listening on "
                (address-text host port) "; peers: "
                (str/join ", " (map #(address-text (:host %) (:port %))
                                    (:peers node)))
                (if (:publish? node) "; publishing" "; fetch-only"))
    :bind-failed (str "dht: refused: the socket could not bind: " reason)
    (:published :republished)
    (str "dht: " (name (::dht/event event)) " " manifest " (" blobs " blobs) — "
         (result-text node event))
    :publication-unknown (str "dht: published " manifest
                              " — acknowledgement unknown: facts were lost")
    :loaded (str (if (= manifest (::hydrating node)) "dht: hydrated " "dht: loaded ")
                 manifest " — "
                 (when datoms (str datoms " datoms, "))
                 fetched " blobs fetched"
                 (when (= manifest (::hydrating node)) "; evaluation admitted"))
    :load-failed (str "dht: " (if (= manifest (::hydrating node)) "refused: " "")
                      "loading " manifest " failed: " (failure-text reason)
                      (when (= manifest (::hydrating node))
                        "; the remote index was not hydrated"))
    (pr-str event)))


;; =============================================================================
;; The step
;; =============================================================================

(defn- hydrated
  "Install the loaded remote index as this directory's: HEAD first, then
   the indexer, exactly as a restart's recovery is installed."
  [shell node]
  (let [manifest (::hydrating node)
        recovery {:manifest manifest :datoms (dht/loaded-datoms node manifest)}]
    ((:head-fn (dht/local node)) manifest)
    (-> shell
        (update :indexer index/rehydrate recovery)
        (assoc :index-recovery recovery
               :dht (dissoc node ::hydrating)))))


(defn step
  "Advance the shell's node once at the host's clock reading `now`
   (dao.space.dht/step) and answer `[shell' lines]`: its events as lines,
   a completed hydration installed, a failed one or a failed bind
   recorded as the shell's refusal.  A shell without a DHT store is
   answered unchanged."
  [shell now]
  (if-let [node (:dht shell)]
    (let [[node events] (dht/step node now)
          lines (mapv #(event-line node %) events)
          hydrating (::hydrating node)
          status (when hydrating (:status (dht/load-status node hydrating)))
          node (cond-> node
                 (and hydrating (= :failed status))
                 (assoc ::refusal (str/join "\n" (filter #(str/includes? % "refused")
                                                         lines)))
                 (dht/refusal node)
                 (assoc ::refusal (dht/refusal node)))
          shell (assoc shell :dht node)]
      [(if (= :loaded status) (hydrated shell node) shell) lines])
    [shell []]))


(defn refusal
  "The text that refused the shell's DHT store after startup — a failed
   bind or a hydration that could not complete — or nil."
  [shell]
  (get-in shell [:dht ::refusal]))


(defn admitting?
  "True when the shell may evaluate: no hydration is outstanding and none
   was refused."
  [shell]
  (let [node (:dht shell)]
    (or (nil? node)
        (and (nil? (::hydrating node)) (nil? (::refusal node))))))


(defn busy?
  "True while the node owes the REPL a line; the ticker keeps its base
   cadence then."
  [shell]
  (boolean (some-> (:dht shell) dht/busy?)))


(defn banner
  "What the REPL states at startup about a DHT store spec, before the
   node steps once — and so before anything is shared."
  [{:keys [dir peers publish? bind-host bind-port]}]
  (let [content (durable/content-path dir)
        peers-text (str/join ", " (map #(address-text (:host %) (:port %)) peers))]
    (cond-> []
      (empty? peers)
      (conj (str "dht: solo — no --dht-peer, so no socket is opened; "
                 "publications are durable in " dir " and acknowledged by no one"))

      (seq peers)
      (conj (str "dht: binding " (address-text bind-host bind-port)
                 (when (zero? bind-port) " (ephemeral)")
                 "; peers " peers-text))

      (and publish? (seq peers))
      (conj (str "dht: publishing is ON. Everything in " content
                 " will be shared: the whole code index this directory holds"
                 " — every program recovered from HEAD and every program"
                 " evaluated from now on — with any peer that asks for its"
                 " address. Omit --dht-publish to fetch only."))

      (and publish? (empty? peers))
      (conj (str "dht: publishing is on, but solo: nothing in " content
                 " leaves this process"))

      (and (not publish?) (seq peers))
      (conj "dht: fetch-only — nothing this node holds is shared (--dht-publish shares it)"))))
