(ns yin.vm.linker.interpreter
  "The signed-name link source's interpreter, and the composition's one
   require for the link pair (yin.vm.linker.md sections 6.1, 6.5 and 9,
   M5; yin.vm.linker.dht.md 4.1 and 7).

   The generic machinery — the link pair and the content pair, one link
   attempt, the serve rounds, the admission state a signed-name source
   mints, and the retirement of an abandoned run — lives in
   `yin.vm.linker.pair` and is re-exposed here, so a composition drives
   both sources through this one namespace.  What lives here is the
   serving of the source whose content is a node the composition is
   handed each round: name resolution against the reader's authority,
   closure and stage loads, dependency-binding checks, admission
   questions, and the link over the node's own store.

   That split is the dependency rule of
   docs/design/yin.repl.dht-composition.md section 2: this namespace
   imports `dao.space.dht` and `yin.vm.linker.dht`, so an engine that
   requires it loads the DHT implementation with it.  An engine therefore
   requires `yin.vm.linker.pair` and drives its own links with
   `round-local`, which takes no node; `round`'s `[linker node]`
   interface is the composition's alone (`yin.shell.linker/serve-links`).

   A signed-name link source holds no handle: each serve round receives
   the composition's `dao.space.dht` node and answers the node after it.
   A resolved name whose closure is not loaded starts its load
   (`yin.vm.linker.dht/load-module`) and reports the link `:pending`,
   naming the manifest it waits on; the node's own step fetches.  A
   failed load is refused in its section 9 shape and forgotten; a loaded
   one has its dependency bindings checked, then links over a local
   runtime on the node's store, asking no peer.

   No clock, no atom, no global: every input is an argument or a
   composition value, and every outcome is plain data."
  (:require [dao.space.dht :as dht]
            [yin.vm.linker :as linker]
            [yin.vm.linker.admission :as admission]
            [yin.vm.linker.dht :as linker.dht]
            [yin.vm.linker.pair :as pair]))


;; =============================================================================
;; The generic pair machinery, re-exposed
;;
;; These are `yin.vm.linker.pair`'s own vars, so a composition and an
;; engine that require different namespaces of the pair still name one
;; implementation, one set of budgets and one pending keyword.  The
;; engine requires the pair directly and never this namespace: a require
;; of it loads the DHT implementation this file composes.
;; =============================================================================

(def pair-capacity pair/pair-capacity)
(def content-capacity pair/content-capacity)
(def attempt-budget pair/attempt-budget)
(def serve-steps pair/serve-steps)
(def serve-budget pair/serve-budget)
(def formats pair/formats)
(def make-pair pair/make-pair)
(def composition pair/composition)
(def make pair/make)
(def reconfigure pair/reconfigure)
(def serve-requests pair/serve-requests)
(def signed-source? pair/signed-source?)
(def round-local pair/round-local)
(def run-descriptor pair/run-descriptor)
(def retire pair/retire)


(defn authority
  "The reader's authority of a link source (yin.vm.linker.dht.md 7.1):
   its declared principals and its direct name entries, as the plain
   `yin.vm.linker.dht/authority` composes them."
  [source]
  (linker.dht/authority (select-keys source [:principals :name-env])))


;; =============================================================================
;; The DHT link source (yin.vm.linker.dht.md 4.1)
;; =============================================================================

(defn- dht-link
  "Link the loaded closure of `address` over a local runtime on the
   node's store (`yin.vm.linker/local-runtime`), `:verifying`, discharge
   deferred.  A local store answers every content request at once, so no
   attempt budget applies; an `:absent` refusal after the load is a
   walker defect, `:yin.link.dht/closure-incomplete`."
  [node request address]
  (let [res (linker/link-manifest
              (linker/local-runtime (dht/store node)
                                    {:formats pair/formats})
              address
              (:yin.link/format request)
              {}
              {:contract (:yin.link/contract request)
               :name (:yin.link/name request)
               :derivation :verifying
               :defer-discharge true})]
    (cond
      (linker/ok? res) {:status :ok
                        :image {:value (:value res)}
                        :manifest (:manifest res)
                        :obligations (:obligations res)}
      (= :absent (:reason res)) {:status :refused
                                 :reason :yin.link.dht/closure-incomplete
                                 :address (:address res)}
      :else res)))


(defn- dht-attempt
  "One attempt against the DHT source for the resolved `address`:
   `{:node n :body b}`, `b` `pair/pending-body` with `:waits` the manifest
   whose load the link waits on.  No record starts the load; a record of
   another kind, whatever its status, is refused
   `:dao.space.dht/kind-conflict` and left to its owner, neither
   forgotten, waited on nor linked; `:loading` waits; `:failed` is
   refused and forgotten, so a later require loads again; `:loaded`
   checks the dependency bindings, the first that is not `:ok` refusing
   before any link, then links."
  [node authority request address]
  (let [status (linker.dht/module-status node address)]
    (cond
      (nil? status) {:node (linker.dht/load-module node address)
                     :body pair/pending-body
                     :waits address}

      (not= linker.dht/module-kind (:kind status))
      {:node node
       :body {:status :refused
              :reason :dao.space.dht/kind-conflict
              :address address
              :recorded (:kind status)}}

      (= :loading (:status status))
      {:node node, :body pair/pending-body, :waits address}

      (= :failed (:status status)) {:node (dht/forget node address)
                                    :body (linker.dht/load-refusal status)}

      :else
      (let [bindings (linker.dht/dependency-bindings node authority address)
            unbound (if (map? bindings)
                      bindings
                      (some (fn [b]
                              (when-not (= :ok (:binding b))
                                (merge {:status :refused
                                        :reason :yin.link.dht/dependency-binding}
                                       b)))
                            bindings))]
        {:node node
         :body (or unbound (dht-link node request address))}))))


(defn- dht-context
  "The stage context of a DHT source over `node`: its store, every
   loaded snapshot's datoms and the declared principals."
  [source node]
  {:store (dht/store node)
   :datoms (into []
                 (mapcat #(linker.dht/snapshot-datoms node %))
                 (linker.dht/snapshots node))
   :principals (:principals source)})


(defn- dht-staged
  "`dht-attempt` of the resolved `address` through the composition's stage,
   a second staged load.  The module itself is phase one: loaded, its
   dependency bindings checked and linked under the composition's policy.
   Only an `:ok` module is staged, its refusal or pending state standing
   as it is.  Phase two is the stage load (`yin.vm.linker.dht/load-stage`,
   keyed per address): the stage reads the node's store and reports what
   it lacks, `step` fetches it, and the load is pending on its key
   meanwhile.  Its context, the loaded snapshot set's datoms and the
   declared principals, is captured when the load starts; a snapshot
   change while it is pending does not re-resolve.  A `:failed` load is
   refused and forgotten, so a later require starts over; a `:loaded`
   decision that refuses is forgotten likewise (a later index load may
   change it), one that is `:ok` stays.  A substituted address is then
   attempted in the module's place and its `:ok` response annotated."
  [source node declared request address]
  (let [first-try (dht-attempt node declared request address)]
    (if (or (nil? (:stage source))
            (not= :ok (:status (:body first-try))))
      first-try
      (let [node (:node first-try)
            key (linker.dht/stage-key address)
            status (linker.dht/stage-status node address)]
        (cond
          (nil? status)
          {:node (linker.dht/load-stage node (:stage source)
                                        (dht-context source node)
                                        (:yin.link/name request) address)
           :body pair/pending-body
           :waits key}

          (= :loading (:status status))
          {:node node, :body pair/pending-body, :waits key}

          (= :failed (:status status))
          {:node (dht/forget node key)
           :body (linker.dht/stage-refusal status)}

          :else
          (let [d (pair/decision request address (:value status))]
            (cond
              (:refused d) {:node (dht/forget node key), :body (:refused d)}
              (nil? (:extra d)) first-try
              :else (update (dht-attempt node declared request (:address d))
                            :body pair/annotated (:extra d)))))))))


(defn- awaited-heads
  "The followed principals of `source` with no installed head on `node`:
   a name absent now may be asserted by the first head of one of them."
  [source node]
  (let [installed (linker.dht/installed-heads node)]
    (vec (remove #(contains? installed %) (:follow source)))))


(defn- admitted
  "A request whose admission question was asked (yin.vm.linker.dht.md
   7.5): pending while unanswered, with no re-resolution; refused as the
   answer says; or, allowed, staged and attempted against the address the
   question was bound to, never a fresh resolution.  An `:ok` response
   names the admission it rode on."
  [source node declared request q a]
  (cond
    (nil? a) {:node node, :body pair/pending-body,
              :asking (:yin.admit/id q)}

    (some? (admission/judge a))
    {:node node, :body (pair/admission-refusal q (admission/judge a))}

    :else
    (let [{:keys [node body waits]}
          (dht-staged source node declared request (:yin.admit/address q))]
      {:node node
       :waits waits
       :asking (when (= pair/pending-body body) (:yin.admit/id q))
       :body (if (= :ok (:status body))
               (assoc body
                      :yin.admit/id (:yin.admit/id q)
                      :yin.admit/decision :allow
                      :yin.admit/asserters (:yin.admit/asserters q)
                      :yin.admit/address (:yin.admit/address q))
               body)})))


(defn- dht-answer
  "The signed-name source's answer to one link request, the `:serve-dht`
   a composition installs for its serve round
   (`yin.vm.linker.pair/serve-requests`): a request already asked about
   goes by its question (`admitted`); otherwise its name is resolved.  A
   direct `:name-env` entry, provenance `:composition`, is the
   composition's own and is not asked; a signed name is asked: the
   question is appended and the link parks on it.  With no admission
   state the question cannot be asked, `:yin.admit/unconfigured`."
  [source node admission request]
  (let [declared (authority source)
        name (:yin.link/name request)
        q (some-> admission (admission/asked (:yin.link/id request)))]
    (if q
      (admitted source node declared request q
                (admission/answer-to admission q))
      (let [entry (linker.dht/resolve-name node declared name)]
        (cond
          (not= :ok (:status entry))
          (if (and (= :absent (:reason entry))
                   (seq (awaited-heads source node)))
            {:node node, :body pair/pending-body,
             :heads (awaited-heads source node)}
            {:node node, :body entry})

          (= :composition (:yin.link/provenance entry))
          (dht-staged source node declared request (:address entry))

          (nil? admission)
          {:node node
           :body {:status :refused
                  :reason :yin.admit/unconfigured
                  :name name}}

          :else
          (let [{:keys [id] :as asked} (admission/ask admission request entry)]
            {:node node
             :body pair/pending-body
             :admission (:admission asked)
             :asking id
             :asked? (some? id)}))))))


(defn round
  "One serve round over the composition's linker and its `node`: drain
   once, serve requests to the first pending, then answer in process.
   Always return the successor, even when nothing reports progress.
   Answers `{:linker l' :dht node' :pending p :answered n :progress?
   bool}`; the successor node is the one the source advanced, and it is
   the composition's to keep — an engine never sees it
   (docs/design/yin.repl.dht-composition.md section 7)."
  ([linker node] (round linker node serve-budget))
  ([linker node budget]
   (pair/round-with linker node dht-answer budget)))


(defn serve
  "Serve requests over the legacy composition, draining answers once."
  ([comp] (serve comp serve-budget))
  ([comp budget]
   (pair/serve (assoc comp :serve-dht dht-answer) budget)))
