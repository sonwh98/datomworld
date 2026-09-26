(ns yin.vm.telemetry-test
  "The v2 telemetry suite `vm-telemetry-design.md` prescribes, reduced to the
   two live VMs: the four core tests are parameterized over `:ast-walker` and
   `:semantic`; the smoke tests are one deftest per model driven through the
   protocol's own `step` (the `:step` boundary lives there, not in `run`);
   serialization and bridge tests exercise the shared emit path through the
   walker, whose store carries the richest values (an FFI pair, a made
   stream, a cursor).

   Sinks are `dao.stream.memory-log` handles — complete retention, so an
   assertion sees every datom a run emitted."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.datom :as datom]
            [dao.stream :as stream]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as ast-walker]
            [yin.vm.linearize :as linearize]
            [yin.vm.semantic :as semantic]
            [yin.vm.telemetry :as telemetry]
            [yin.vm.test-utils :as tu]))


;; =============================================================================
;; Reading a sink back as snapshots
;; =============================================================================

(defn- snapshot-groups
  "Drained datoms as ordered snapshots `{:t t, :entities {eid {a [v …]}}}`.
   One snapshot is one `t` (D3), and a cardinality-many attribute (`:vm.
   summary/item`, `:vm.summary/entry`) collects into a vector rather than
   being overwritten by its last value."
  [datoms]
  (mapv (fn [t]
          {:t t
           :entities (reduce (fn [entities [e a v]]
                               (update-in entities [e a] (fnil conj []) v))
                             {}
                             (filter #(= t (nth % 3)) datoms))})
        (vec (distinct (map #(nth % 3) datoms)))))


(defn- root-of
  "The snapshot's root: the entity typed `:vm/snapshot`."
  [{:keys [entities]}]
  (some (fn [[eid entity]]
          (when (= [:vm/snapshot] (:vm/type entity)) [eid entity]))
        entities))


(defn- phase-seq
  "Every root's `:vm/phase`, in emission order. `:vm/phase` is written once
   per snapshot, on the root, so the attribute itself is the timeline."
  [datoms]
  (mapv #(nth % 2)
        (filter (fn [[_e a _v]] (= :vm/phase a)) datoms)))


(declare reconstruct)


(defn- reconstruct
  "One summary entity back into plain data — refs resolved, entry keys back
   to their scalars — so a test asserts on the tree an analyzer rebuilds:
   `{:type t, :value v, :count n, :identity <node>, :items […], :entries
   {<key> <node>}}`."
  [entities eid]
  (let [entity (get entities eid)]
    (when entity
      (let [entry-pairs (fn [entry-eids]
                          (into {}
                                (map (fn [entry-eid]
                                       (let [entry (get entities entry-eid)]
                                         [(let [key (reconstruct
                                                      entities
                                                      (first (:vm.summary/key
                                                               entry)))]
                                            (or (:value key) key))
                                          (reconstruct
                                            entities
                                            (first (:vm.summary/value-ref
                                                     entry)))])))
                                entry-eids))]
        (cond-> {:type (first (:vm.summary/type entity))}
          (contains? entity :vm.summary/value)
          (assoc :value (first (:vm.summary/value entity)))

          (contains? entity :vm.summary/truncated?)
          (assoc :truncated? (first (:vm.summary/truncated? entity)))

          (contains? entity :vm.summary/count)
          (assoc :count (first (:vm.summary/count entity)))

          (:vm.summary/identity entity)
          (assoc :identity (reconstruct entities
                                        (first (:vm.summary/identity entity))))

          (:vm.summary/item entity)
          (assoc :items (mapv (partial reconstruct entities)
                              (:vm.summary/item entity)))

          (:vm.summary/entry entity)
          (assoc :entries (entry-pairs (:vm.summary/entry entity))))))))


(defn- raw-host-value?
  "True when x or anything inside it is a function or a stream handle — the
   two shapes `vm-telemetry-design.md` bans from every datom."
  [x]
  (some (fn [v] (or (fn? v) (stream/descriptor? v)))
        (tree-seq coll? seq x)))


;; =============================================================================
;; The two live VMs, as one shape
;; =============================================================================

(def ^:private models
  "The models the design still has, keyed as `create-vm` keys them. `:create`
   is opts->VM, `:load` is (VM, AST)->loaded VM, `:run` is (VM, AST)->final
   VM: the walker loads and runs in one `eval`; the semantic VM executes
   `:yin.code/*` segments, so its AST path composes the linearizer in front
   of its own loader."
  {:ast-walker
   {:create (fn [opts]
              (ast-walker/create-vm (merge {:make-stream tu/make-stream,
                                            :capability-secret tu/secret}
                                           opts)))
    :load (fn [vm ast]
            (ast-walker/vm-load-program vm (vm/ast->datoms ast)
                                        vm/ast-contract))
    :run (fn [vm ast] (vm/eval vm ast))}

   :semantic
   {:create (fn [opts]
              (semantic/create-vm (merge {:make-stream tu/make-stream,
                                          :capability-secret tu/secret}
                                         opts)))
    :load (fn [vm ast]
            ((linearize/ast-loader semantic/vm-load-program)
             vm (vm/ast->datoms ast) vm/ast-contract))
    :run (fn [vm ast]
           (vm/run ((linearize/ast-loader semantic/vm-load-program)
                    vm (vm/ast->datoms ast) vm/ast-contract)))}})


(def ^:private tiny-ast {:type :literal, :value 42})


(def ^:private sum-ast
  {:type :application,
   :operator {:type :variable, :name '+},
   :operands [{:type :literal, :value 10} {:type :literal, :value 20}]})


(def ^:private stream-io-ast
  "Make a stream, put one value, mint a cursor, read the value back: the
   store then holds one made stream handle and one cursor entry with a
   position — the two facts the serialization tests are about."
  {:type :application,
   :operator {:type :lambda,
              :params ['s],
              :body {:type :application,
                     :operator {:type :lambda,
                                :params ['p],
                                :body {:type :stream/next,
                                       :source {:type :stream/cursor,
                                                :source {:type :variable,
                                                         :name 's}}}},
                     :operands [{:type :stream/put,
                                 :target {:type :variable, :name 's},
                                 :val {:type :literal, :value 99}}]}}
   :operands [{:type :stream/make, :buffer 4}]})


(defn- step-to-halt
  "Drive the protocol's own `step` until the VM halts, on a fuel budget. This
   is the driver that crosses the `:step` boundary; `vm/run` never does."
  [vm]
  (loop [v vm, fuel 64]
    (if (or (vm/halted? v) (zero? fuel))
      v
      (recur (vm/step v) (dec fuel)))))


;; =============================================================================
;; The four core tests, parameterized over both models
;; =============================================================================

(deftest disabled-vms-emit-nothing-by-default-test
  (doseq [[model {:keys [create run]}] models]
    (testing (str model " built with no :telemetry key at all emits nothing")
      (let [beside (tu/new-memory-log)
            done (run (create {}) sum-ast)]
        (is (= 30 (vm/value done)) "evaluation itself is unchanged")
        (is (nil? (:telemetry done)))
        (is (not (telemetry/enabled? done)))
        (is (= [] (tu/drain beside))
            "the sink a composition held beside the VM, never handed to it,
             receives nothing")))))


(deftest enabled-vms-write-datoms-to-the-provided-sink-test
  (doseq [[model {:keys [create run]}] models]
    (testing (str model)
      (let [sink (tu/new-memory-log)
            done (run (create {:telemetry {:stream sink, :vm-id ::probe}})
                      sum-ast)
            datoms (tu/drain sink)]
        (is (= 30 (vm/value done)) "telemetry does not perturb evaluation")
        (is (pos? (count datoms)) "the run emitted snapshots")
        (is (every? datom/local-datom? datoms)
            "every datom is [e a v t m]-shaped with namespaced a")
        (is (every? #(>= (nth % 0) datom/first-user-id) datoms)
            "entity ids stay out of the reserved block")
        (is (= #{1} (set (map #(nth % 4) datoms)))
            "emitted datoms assert by default")
        (is (= [model] (vec (distinct (map #(nth % 2)
                                           (filter (fn [[_e a _v]]
                                                     (= :vm/model a))
                                                   datoms)))))
            "every root carries the model keyword")
        (is (= [::probe] (vec (distinct (map #(nth % 2)
                                             (filter (fn [[_e a _v]]
                                                       (= :vm/vm-id a))
                                                     datoms)))))
            "every root carries the supplied instance id")))))


(deftest snapshots-carry-root-facts-and-component-refs-test
  (doseq [[model {:keys [create]}] models]
    (testing (str model)
      (let [sink (tu/new-memory-log)
            _vm0 (create {:telemetry {:stream sink}})
            snaps (snapshot-groups (tu/drain sink))
            [init] snaps]
        (testing "the :init snapshot: facts present, absent components omitted"
          (let [[_root root-facts] (root-of init)
                entities (:entities init)]
            (is (= [:init] (:vm/phase root-facts)))
            (is (= [0] (:vm/step root-facts)))
            (is (contains? root-facts :vm/blocked?))
            (is (contains? root-facts :vm/halted?))
            (is (= [:nil] (get-in entities [(first (:vm/value root-facts))
                                            :vm.summary/type]))
                "a fresh VM's value is nil, summarized as its own node")
            (is (contains? root-facts :vm/environment))
            (is (contains? root-facts :vm/store)
                "an FFI pair lives in the store, so the store is summarized")
            (is (not (contains? root-facts :vm/control))
                "a fresh VM has no control: omitted, never a nil ref")
            (is (not (contains? root-facts :vm/continuation)))))
        (testing "every ref resolves inside its own snapshot"
          (doseq [snap snaps
                  :let [[_root root-facts] (root-of snap)
                        ref-attrs [:vm/value :vm/control :vm/environment
                                   :vm/store :vm/continuation]]]
            (doseq [attr ref-attrs
                    eid (get root-facts attr [])]
              (is (contains? (:entities snap) eid)
                  (str attr " resolves within t=" (:t snap))))))))))


(deftest counters-are-monotonic-across-steps-test
  (doseq [[model {:keys [create load]}] models]
    (testing (str model)
      (let [sink (tu/new-memory-log)
            done (-> (create {:telemetry {:stream sink}})
                     (load sum-ast)
                     step-to-halt
                     vm/run)
            datoms (tu/drain sink)
            snaps (snapshot-groups datoms)
            phases (phase-seq datoms)
            steps (mapv (fn [snap] (first (:vm/step (second (root-of snap)))))
                        snaps)]
        (is (vm/halted? done))
        (is (pos? (count (filter #{:step} phases)))
            "the protocol-step driver crossed :step boundaries")
        (is (= :init (first phases)))
        (is (= :halt (peek phases)) "the terminal snapshot is last")
        (is (= steps (range (count steps)))
            ":vm/step counts every snapshot from zero, in order")
        (is (apply < (mapv :t snaps))
            "t is strictly increasing: one snapshot, one transaction")
        (is (every? (fn [[a b]]
                      (> (apply min (keys (:entities b)))
                         (apply max (keys (:entities a)))))
                    (partition 2 1 snaps))
            "a snapshot never reuses an entity id an earlier one minted")))))


;; =============================================================================
;; Per-VM smoke tests (vm-telemetry-design.md: at least :init, :step, :halt)
;; =============================================================================

(deftest ast-walker-smoke-test
  (let [sink (tu/new-memory-log)
        done (-> (ast-walker/create-vm {:make-stream tu/make-stream,
                                        :capability-secret tu/secret
                                        :telemetry {:stream sink}})
                 (ast-walker/vm-load-program (vm/ast->datoms tiny-ast)
                                             vm/ast-contract)
                 vm/step
                 vm/run)
        phases (phase-seq (tu/drain sink))]
    (is (vm/halted? done))
    (is (= 42 (vm/value done)))
    (is (= :init (first phases)))
    (is (= [:step] (vec (rest (butlast phases))))
        "one loaded literal is one protocol step")
    (is (= :halt (peek phases)))))


(deftest semantic-smoke-test
  (let [sink (tu/new-memory-log)
        done (-> (semantic/create-vm {:make-stream tu/make-stream,
                                      :capability-secret tu/secret
                                      :telemetry {:stream sink}})
                 ((linearize/ast-loader semantic/vm-load-program)
                  (vm/ast->datoms tiny-ast)
                  vm/ast-contract)
                 step-to-halt
                 vm/run)
        phases (phase-seq (tu/drain sink))]
    (is (vm/halted? done))
    (is (= 42 (vm/value done)))
    (is (= :init (first phases)))
    (is (pos? (count (filter #{:step} phases)))
        "the row machine steps per instruction, so at least one :step")
    (is (= :halt (peek phases)))))


;; =============================================================================
;; Serialization: nothing raw survives; identities and positions do
;; =============================================================================

(deftest summaries-never-embed-raw-handles-or-host-functions-test
  (let [sink (tu/new-memory-log)
        _vm (ast-walker/create-vm {:make-stream tu/make-stream,
                                   :capability-secret tu/secret
                                   :env {'host-fn inc}
                                   :telemetry {:stream sink}})
        datoms (tu/drain sink)]
    (is (pos? (count datoms)) "the :init snapshot alone is the fixture")
    (is (every? (fn [[_e _a v]] (not (raw-host-value? v))) datoms)
        "no datom carries a function, a stream handle, or a host object")
    (is (some (fn [[_e a v]]
                (and (= :vm.summary/type a)
                     (= :vm.summary/host-fn v)))
              datoms)
        "the environment's host function is tagged, not embedded")
    (is (some (fn [[_e a v]] (and (= :vm.summary/type a) (= :stream v)))
              datoms)
        "the resources' FFI pair summarizes as stream identity nodes")))


(deftest store-summaries-preserve-stream-and-cursor-identities-test
  (doseq [[model {:keys [create run]}] models]
    (testing (str model)
      (let [sink (tu/new-memory-log)
            done (run (create {:telemetry {:stream sink}}) stream-io-ast)
            snaps (snapshot-groups (tu/drain sink))
            last-snap (peek snaps)
            [_root root-facts] (root-of last-snap)
            ;; stream handles and cursor cells live in the private
            ;; resources table (yin.vm.linker.md 7.3, r8), summarized beside
            ;; the store
            resources (reconstruct (:entities last-snap)
                                   (first (:vm/resources root-facts)))
            store (reconstruct (:entities last-snap)
                               (first (:vm/store root-facts)))
            handle (get (:resources done) :stream-0)
            ;; gensym ids come from one counter, so the program's cursor key
            ;; is whatever the run minted; find it by what it points at.
            cursor-key (some (fn [[k v]]
                               (when (and (map? v)
                                          (= :stream-0 (:stream-id v)))
                                 k))
                             (:resources done))
            real-identity (:dao.stream/identity (stream/descriptor handle))
            real-position (get-in (:resources done)
                                  [cursor-key :cursor
                                   :dao.stream.ringbuffer/position])
            stream-node (get (:entries resources) :stream-0)
            cursor-node (get (:entries resources) cursor-key)]
        (is (= 99 (vm/value done)))
        (is (some? handle) "the program really made a stream")
        (is (some? cursor-key) "and really minted a cursor on it")
        (is (= :stream (:type stream-node))
            "a held handle is a stream summary, keyed by its resource id")
        (is (not (contains? (:entries store) :stream-0))
            "and no store entry holds it")
        (is (= real-identity (get-in stream-node [:identity :value]))
            "the stream's logical identity survives for an analyzer")
        (is (= :map (:type cursor-node)))
        (is (= :stream-0 (get-in cursor-node [:entries :stream-id :value]))
            "the cursor entry names its stream")
        (is (= real-identity
               (get-in cursor-node
                       [:entries :cursor
                        :entries :dao.stream.ringbuffer/identity :value])))
        (is (= real-position
               (get-in cursor-node
                       [:entries :cursor
                        :entries :dao.stream.ringbuffer/position :value]))
            "the read position survives as a number an analyzer can order")))))


;; =============================================================================
;; The bridge
;; =============================================================================

(deftest bridge-dispatch-emits-and-preserves-resumed-state-test
  (let [sink (tu/new-memory-log)
        result (vm/eval
                 (ast-walker/create-vm
                   {:make-stream tu/make-stream, :capability-secret tu/secret,
                    :bridge {:op/echo identity},
                    :telemetry {:stream sink}})
                 {:type :dao.stream.apply/call,
                  :op :op/echo,
                  :operands [{:type :literal, :value 42}]})
        datoms (tu/drain sink)
        phases (phase-seq datoms)]
    (testing "the round trip still completes"
      (is (vm/halted? result))
      (is (= 42 (vm/value result)) "the resumed continuation got the answer")
      (is (empty? (:parked result)) "the parked continuation was consumed")
      (is (contains? (:resources result) :yin/call-in)
          "the FFI pair survives the resumed state")
      (is (not (contains? (vm/store result) :yin/call-in))
          "in the private resources, never the store"))
    (testing "both sides of the bridge emit their :bridge snapshots"
      (is (pos? (count (filter #{:bridge} phases))))
      (is (pos? (count (filter #{:park} phases)))
          "parking the call is a :park boundary")
      (is (some (fn [[_e a v]] (and (= :vm/bridge-op a) (= :op/echo v)))
                datoms)
          "a :bridge root names the op"))
    (testing "the parked identity survives the park snapshot"
      (let [park-snap (some (fn [snap]
                              (when (= [:park] (:vm/phase
                                                 (second (root-of snap))))
                                snap))
                            (snapshot-groups datoms))]
        (is (some? park-snap))
        (is (some (fn [[_e a v]] (= :parked-0 v)) datoms)
            "the parked continuation's identity is a summary key, so the
             analyzer that resumes by id finds it")))))
