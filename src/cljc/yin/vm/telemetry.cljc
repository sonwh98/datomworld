(ns yin.vm.telemetry
  (:require
    [dao.stream :as ds]
    [yin.vm :as vm]))


(def ^:private component-width 1000)
(def ^:private max-coll-items 8)
(def ^:private max-summary-depth 3)


(defn enabled?
  [state]
  (boolean (get-in state [:telemetry :stream])))


(defn- normalize-config
  [telemetry model]
  (when telemetry
    (when-not (map? telemetry)
      (throw (ex-info "Telemetry config must be a map"
                      {:telemetry telemetry})))
    (let [stream (:stream telemetry)]
      (when-not stream
        (throw (ex-info "Telemetry config requires :stream"
                        {:telemetry telemetry})))
      {:stream stream
       :vm-id (or (:vm-id telemetry)
                  (keyword (str (name model) "-" (gensym "vm"))))})))


(defn install
  [state model]
  (let [telemetry (normalize-config (:telemetry state) model)]
    (cond-> (assoc state
                   :vm-model model
                   :telemetry-step (or (:telemetry-step state) 0)
                   :telemetry-t (or (:telemetry-t state) 0))
      telemetry (assoc :telemetry telemetry))))


(defn next-telemetry-state
  [state]
  (if (enabled? state)
    (-> state
        (update :telemetry-step (fnil inc 0))
        (update :telemetry-t (fnil inc 0)))
    state))


(defn- model
  [state]
  (:vm-model state))


(defn- value
  [state]
  (:value state))


(defn- blocked?
  [state]
  (boolean (:blocked state)))


(defn- halted?
  [state]
  (boolean (:halted state)))


(defn- control
  [state]
  (if (satisfies? vm/IVMState state)
    (vm/control state)
    (:control state)))


(defn- environment
  [state]
  (if (satisfies? vm/IVMState state)
    (vm/environment state)
    (:env state)))


(defn summarize-store
  [state]
  (if (satisfies? vm/IVMState state)
    (vm/store state)
    (:store state)))


(defn summarize-k
  [state]
  (if (satisfies? vm/IVMState state)
    (vm/continuation state)
    (:k state)))


(defn summarize-control
  [state]
  (control state))


(defn summarize-env
  [state]
  (environment state))


(defn- scalar?
  [x]
  (or (nil? x)
      (boolean? x)
      (number? x)
      (string? x)
      (keyword? x)
      (symbol? x)))


(defn type-tag
  [value]
  (cond
    (nil? value) :nil
    (boolean? value) :boolean
    (number? value) :number
    (string? value) :string
    (keyword? value) :keyword
    (symbol? value) :symbol
    (fn? value) :host-fn
    (satisfies? ds/IDaoStreamReader value) :dao-stream
    (vector? value) :vector
    (map? value) :map
    (sequential? value) :sequence
    :else :opaque))


(defn- cursor-map?
  [value]
  (and (map? value)
       (contains? value :stream-id)
       (contains? value :position)))


(defn- root-value-attrs
  [opts]
  (cond-> [[:vm/event (or (:event opts) (:phase opts))]
           [:vm/phase (:phase opts)]]
    (:effect-type opts) (conj [:vm/effect-type (:effect-type opts)])
    (:parked-id opts) (conj [:vm/parked-id (:parked-id opts)])
    (:bridge-op opts) (conj [:vm/bridge-op (:bridge-op opts)])
    (:arg-shape opts) (conj [:vm/arg-shape (:arg-shape opts)])))


(defn- snapshot-base-id
  [step]
  (- (+ 1025 (* step component-width))))


(defn- append-datom
  [datoms e a v t]
  (swap! datoms conj [e a v t 0]))


(defn- summarize*
  [datoms alloc-id t value depth]
  (let [eid (alloc-id)]
    (cond
      (scalar? value)
      (do
        (append-datom datoms eid :vm.summary/type (type-tag value) t)
        (when-not (nil? value)
          (append-datom datoms eid :vm.summary/value value t))
        eid)

      (fn? value)
      (do
        (append-datom datoms eid :vm.summary/type :host-fn t)
        eid)

      (satisfies? ds/IDaoStreamReader value)
      (do
        (append-datom datoms eid :vm.summary/type :dao-stream t)
        eid)

      (cursor-map? value)
      (do
        (append-datom datoms eid :vm.summary/type :cursor t)
        (append-datom datoms eid :vm.summary/stream-id (:stream-id value) t)
        (append-datom datoms eid :vm.summary/position (:position value) t)
        eid)

      (map? value)
      (do
        (append-datom datoms eid :vm.summary/type :map t)
        (append-datom datoms eid :vm.summary/count (count value) t)
        (if (pos? depth)
          (let [items (take max-coll-items value)]
            (doseq [[k v] items]
              (let [entry-id (alloc-id)
                    value-id (summarize* datoms alloc-id t v (dec depth))]
                (append-datom datoms eid :vm.summary/entry entry-id t)
                (append-datom datoms entry-id :vm.summary/type :map-entry t)
                (append-datom datoms entry-id :vm.summary/key (if (scalar? k) k (type-tag k)) t)
                (append-datom datoms entry-id :vm.summary/value-ref value-id t)))
            (when (> (count value) max-coll-items)
              (append-datom datoms eid :vm.summary/truncated? true t)))
          (append-datom datoms eid :vm.summary/truncated? true t))
        eid)

      (or (vector? value) (sequential? value))
      (let [items (vec (take max-coll-items value))]
        (append-datom datoms eid :vm.summary/type (if (vector? value) :vector :sequence) t)
        (append-datom datoms eid :vm.summary/count (count value) t)
        (if (pos? depth)
          (doseq [item items]
            (append-datom datoms eid :vm.summary/item (summarize* datoms alloc-id t item (dec depth)) t))
          (append-datom datoms eid :vm.summary/truncated? true t))
        (when (> (count value) max-coll-items)
          (append-datom datoms eid :vm.summary/truncated? true t))
        eid)

      :else
      (do
        (append-datom datoms eid :vm.summary/type :opaque t)
        eid))))


(defn snapshot-datoms
  [state opts]
  (let [step (:telemetry-step state)
        t (:telemetry-t state)
        datoms (atom [])
        next-id (atom (snapshot-base-id step))
        alloc-id (fn []
                   (let [eid @next-id]
                     (swap! next-id dec)
                     eid))
        root-id (alloc-id)
        control-id (summarize* datoms alloc-id t (summarize-control state) max-summary-depth)
        env-id (summarize* datoms alloc-id t (summarize-env state) max-summary-depth)
        store-id (summarize* datoms alloc-id t (summarize-store state) max-summary-depth)
        k-id (summarize* datoms alloc-id t (summarize-k state) max-summary-depth)
        root-value (value state)]
    (append-datom datoms root-id :vm/type :vm/snapshot t)
    (append-datom datoms root-id :vm/vm-id (get-in state [:telemetry :vm-id]) t)
    (append-datom datoms root-id :vm/model (model state) t)
    (append-datom datoms root-id :vm/step step t)
    (append-datom datoms root-id :vm/blocked? (blocked? state) t)
    (append-datom datoms root-id :vm/halted? (halted? state) t)
    (append-datom datoms root-id :vm/control control-id t)
    (append-datom datoms root-id :vm/env env-id t)
    (append-datom datoms root-id :vm/store store-id t)
    (append-datom datoms root-id :vm/k k-id t)
    (if (scalar? root-value)
      (when-not (nil? root-value)
        (append-datom datoms root-id :vm/value root-value t))
      (append-datom datoms root-id :vm/value-ref (summarize* datoms alloc-id t root-value max-summary-depth) t))
    (doseq [[attr v] (root-value-attrs opts)]
      (append-datom datoms root-id attr v t))
    @datoms))


(defn event-datoms
  [state phase opts]
  (snapshot-datoms state (assoc opts :phase phase)))


(defn emit-snapshot
  ([state phase]
   (emit-snapshot state phase {}))
  ([state phase opts]
   (if-not (enabled? state)
     state
     (let [state' (next-telemetry-state state)
           stream (get-in state' [:telemetry :stream])]
       (doseq [datom (event-datoms state' phase opts)]
         (ds/put! stream datom))
       state'))))
