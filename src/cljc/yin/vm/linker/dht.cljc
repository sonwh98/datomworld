(ns yin.vm.linker.dht
  "The linker over `dao.jing.dht`, as plain Clojure (docs/design/
   yin.vm.linker.dht.md sections 4, 5.4, 7 and 10; slice L2): publish a
   module through a `dao.space.dht` node, load its closure onto another
   node by manifest address, check its dependency names against the
   reader's own name environment, and link it over the node's local store.

   The load is `dao.space.dht/load` with the closure walker of
   `yin.vm.linker.closure` as its walk: the node stays the only step
   owner and fetches each blob the walk answers missing.  A link runs
   only after the whole closure is `:loaded`, over a local runtime on the
   node's own store, so it asks no peer for anything.

   Names come from the reader's composition: the principals it declares
   and the index snapshots it chose to load.  Nothing here is read from an
   index as authority, and nothing depends on `yin.repl`."
  (:require [dao.space.dht :as dht]
            [yin.vm.linker :as linker]
            [yin.vm.linker.authority :as authority]
            [yin.vm.linker.closure :as closure]
            [yin.vm.linker.publish :as publish]))


(def module-kind
  "The `:kind` of a module closure load."
  :yin.module/manifest)


(defn- refused
  [reason data]
  (merge {:status :refused :reason reason} data))


;; =============================================================================
;; Publication (5.4)
;; =============================================================================

(defn publish!
  "`publish-module!` on the node's store: the blobs join the node's
   current publication window, reported by the announcement that closes
   it."
  [node spec]
  (publish/publish-module! (dht/store node) spec))


;; =============================================================================
;; The module load (4.3)
;; =============================================================================

(defn closure-walk
  "The closure walk of `manifest-address` as a `dao.space.dht` load walk:
   the walker's three outcomes in the load's three shapes."
  [manifest-address]
  (fn [handle]
    (let [w (closure/walk handle manifest-address)]
      (case (:yin.link.closure/outcome w)
        :missing {::dht/walk :missing :address (:address w)}
        :complete {::dht/walk :complete :value w}
        :invalid {::dht/walk :invalid :address (:address w) :defect (:defect w)}))))


(defn load-module
  "Start loading the closure of `manifest-address` onto `node`; answers
   the node.  `dao.space.dht/step` fetches what is missing."
  [node manifest-address]
  (dht/load node manifest-address {:kind module-kind
                                   :walk (closure-walk manifest-address)}))


(defn module-status
  "The load status of `manifest-address` (4.3), or nil."
  [node manifest-address]
  (dht/load-status node manifest-address))


(defn- not-loaded
  [node manifest-address]
  (refused :yin.link.dht/not-loaded
           {:address manifest-address
            :load (dissoc (module-status node manifest-address) :value)}))


(defn- loaded-closure
  [node manifest-address]
  (let [status (module-status node manifest-address)]
    (when (and (= :loaded (:status status)) (= module-kind (:kind status)))
      (:value status))))


;; =============================================================================
;; Names: the snapshot set and the fold (7.2, 7.3)
;; =============================================================================

(defn snapshots
  "The sorted vector of index manifests whose load is `:loaded` on `node`."
  [node]
  (dht/loaded-indexes node))


(defn- snapshot-events
  "The authority events of every snapshot, assembled per snapshot (an
   entity id is local to its index) and concatenated in snapshot order,
   each envelope with its proof once."
  [node snapshot]
  (into []
        (comp (mapcat (fn [s]
                        (authority/events-from-datoms
                          (filter (fn [[_ a]]
                                    (contains? #{:yin.module/envelope
                                                 :yin.module/proof}
                                               a))
                                  (dht/loaded-datoms node s)))))
              (distinct))
        snapshot))


(defn- fold
  "The fold of the reader's snapshot set under `authority`
   `{:principals {principal declaration} :name-env {name address}}`, and
   every envelope it saw by id."
  [node authority]
  (let [snapshot (snapshots node)
        events (snapshot-events node snapshot)
        env (authority/name-environment
              {:snapshot snapshot :principals (or (:principals authority) {})}
              events)]
    {:env (update env :names into
                  (map (fn [[n address]]
                         [n {:status :ok :address address
                             :yin.link/provenance :composition}]))
                  (:name-env authority))
     :envelopes (into {}
                      (map (fn [e]
                             (let [env (:yin.module/envelope e)]
                               [(authority/assertion-id env) env])))
                      events)}))


(defn names
  "The reader's name environment: `authority/name-environment` over the
   envelopes of the loaded index snapshots, under the principals
   `authority` declares, with its direct `:name-env` entries winning,
   their provenance `:composition`."
  [node authority]
  (:env (fold node authority)))


;; =============================================================================
;; Dependency bindings (7.4)
;; =============================================================================

(defn- diagnostics-for
  "The fold's diagnostics whose envelope binds or retracts `n`."
  [{:keys [env envelopes]} n]
  (let [name-of (fn [e]
                  (if (= :retract (:yin.module/op e))
                    (:yin.module/name (get envelopes (:yin.module/of e)))
                    (:yin.module/name e)))]
    (filterv (fn [d] (= n (name-of (get envelopes (:id d)))))
             (:diagnostics env))))


(defn dependency-bindings
  "One entry per requirement of the loaded closure of `manifest-address`,
   in walk order: whether the dependency's name resolves, in the reader's
   own name environment, to the address its publisher pinned.  `:binding`
   is `:ok`, `:absent` (with the fold's `:diagnostics` for that name),
   `:ambiguous` (`:addresses`, `:asserters`) or `:mismatch` (`:resolved`,
   `:asserters`).  Refused `:yin.link.dht/not-loaded` unless the closure
   is `:loaded`."
  [node authority manifest-address]
  (if-some [w (loaded-closure node manifest-address)]
    (let [folded (fold node authority)]
      (mapv (fn [{:keys [module name manifest]}]
              (let [entry (get-in folded [:env :names name])
                    base {:module module :name name :pinned manifest}
                    asserters (get-in entry [:yin.link/provenance
                                             :yin.module/asserted-by])]
                (cond
                  (= :ambiguous-name (:reason entry))
                  (assoc base :binding :ambiguous
                         :addresses (:addresses entry)
                         :asserters (:asserters entry))

                  (not= :ok (:status entry))
                  (assoc base :binding :absent
                         :diagnostics (diagnostics-for folded name))

                  (= manifest (:address entry))
                  (assoc base :binding :ok)

                  :else
                  (assoc base :binding :mismatch
                         :resolved (:address entry)
                         :asserters (or asserters [:composition])))))
            (:requires w)))
    (not-loaded node manifest-address)))


;; =============================================================================
;; Link (4.1 step 4)
;; =============================================================================

(defn link
  "`link-manifest` of `format-kw` through `manifest-address` over the
   node's local store, `:verifying` with discharge deferred unless `opts`
   says otherwise.  Refused `:yin.link.dht/not-loaded` unless the closure
   is `:loaded`; a link refused `:absent` after it is a walker defect,
   `:yin.link.dht/closure-incomplete`."
  ([node manifest-address format-kw]
   (link node manifest-address format-kw nil))
  ([node manifest-address format-kw opts]
   (let [format (some #(when (= format-kw (:format %)) %) publish/code-formats)]
     (cond
       (nil? (loaded-closure node manifest-address))
       (not-loaded node manifest-address)

       (nil? format)
       (linker/refused :unsupported-format {:format format-kw})

       :else
       (let [res (publish/link-local (dht/local node) manifest-address format opts)]
         (if (= :absent (:reason res))
           (refused :yin.link.dht/closure-incomplete {:address (:address res)})
           res))))))
