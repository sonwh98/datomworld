(ns yin.vm.semantic
  (:require
    [dao.db :as db]
    [dao.db.in-memory :as in-memory]
    [dao.stream :as ds]
    [dao.stream.apply :as dao.stream.apply]
    [yin.module :as module]
    [yin.vm :as vm]
    [yin.vm.engine :as engine]
    [yin.vm.host-ffi :as host-ffi]
    [yin.vm.macro :as macro]
    [yin.vm.telemetry :as telemetry]))


;; =============================================================================
;; Semantic VM
;; =============================================================================
;;
;; Executes Yin datoms by traversing the entity graph.
;; Standard record layout for CESK model.
;; Optimized with array-backed node indexing and a mutable hot-loop.
;; =============================================================================


(def ^:private cardinality-many-attrs
  "Attributes materialized as repeated datoms in DataScript."
  #{:yin/operands})


;; --- Optimized Node Representation ---

(def ^:private ATTR_TYPE 0)
(def ^:private ATTR_VALUE 1)
(def ^:private ATTR_NAME 2)
(def ^:private ATTR_PARAMS 3)
(def ^:private ATTR_BODY 4)
(def ^:private ATTR_TEST 5)
(def ^:private ATTR_CONSEQUENT 6)
(def ^:private ATTR_ALTERNATE 7)
(def ^:private ATTR_OPERATOR 8)
(def ^:private ATTR_OPERANDS 9)
(def ^:private ATTR_TARGET 10)
(def ^:private ATTR_VAL_NODE 11)
(def ^:private ATTR_KEY 12)
(def ^:private ATTR_PREFIX 13)
(def ^:private ATTR_BUFFER 14)
(def ^:private ATTR_PARKED_ID 15)
(def ^:private ATTR_TAIL 16)
(def ^:private ATTR_SOURCE 17)
(def ^:private ATTR_OP 18)
(def ^:private ATTR_COUNT 19)


;; --- Hot-loop continuation frame tags (primitive integers) ---

(def ^:private FT_IF 0)
(def ^:private FT_APP_OP 1)
(def ^:private FT_APP_ARGS 2)
(def ^:private FT_RESTORE_ENV 3)


(def ^:private attr->idx
  {:yin/type ATTR_TYPE
   :yin/value ATTR_VALUE
   :yin/name ATTR_NAME
   :yin/params ATTR_PARAMS
   :yin/body ATTR_BODY
   :yin/test ATTR_TEST
   :yin/consequent ATTR_CONSEQUENT
   :yin/alternate ATTR_ALTERNATE
   :yin/operator ATTR_OPERATOR
   :yin/operands ATTR_OPERANDS
   :yin/target ATTR_TARGET
   :yin/val-node ATTR_VAL_NODE
   :yin/key ATTR_KEY
   :yin/prefix ATTR_PREFIX
   :yin/buffer ATTR_BUFFER
   :yin/parked-id ATTR_PARKED_ID
   :yin/tail? ATTR_TAIL
   :yin/source ATTR_SOURCE
   :yin/op ATTR_OP})


(defn- make-semantic-object-array
  [n]
  #?(:clj (object-array (int n))
     :cljs (let [arr (js/Array. n)]
             (loop [i 0]
               (if (< i n)
                 (do (aset arr i nil) (recur (inc i)))
                 arr)))
     :cljd (object-array (int n))))


(defn- semantic-object-array-length
  [arr]
  #?(:clj (alength ^objects arr)
     :cljs (.-length arr)
     :cljd (alength arr)))


(defn- semantic-object-array-get
  [arr idx]
  #?(:clj (aget ^objects arr (int idx))
     :cljs (aget arr idx)
     :cljd (aget arr (int idx))))


(defn- semantic-object-array-set!
  [arr idx val]
  #?(:clj (aset ^objects arr (int idx) val)
     :cljs (aset arr idx val)
     :cljd (aset arr (int idx) val)))


(defn- datom-node-attrs
  "Get node attributes directly from indexed datoms.
   Returns an Object array for O(1) attribute access."
  [index node-id]
  (let [datoms (get index node-id)
        arr (make-semantic-object-array ATTR_COUNT)]
    (doseq [[_e a v _t _m] datoms]
      (when-let [idx (get attr->idx a)]
        (if (contains? cardinality-many-attrs a)
          (let [existing (semantic-object-array-get arr idx)]
            (if (vector? v)
              (semantic-object-array-set! arr idx (into (or existing []) v))
              (semantic-object-array-set! arr idx (conj (or existing []) v))))
          (semantic-object-array-set! arr idx v))))
    arr))


;; =============================================================================
;; SemanticVM Record
;; =============================================================================

(defrecord SemanticVM
  [blocked    ; true if blocked
   bridge     ; explicit host-side FFI bridge state
   control    ; current control state {:type :node/:value, ...}
   datoms     ; AST datoms
   db         ; DaoDB AST store
   env        ; lexical environment
   halted     ; true if execution completed
   id-counter ; unique ID counter
   index      ; Entity index {eid node-attr-array}
   node-id-counter ; unique negative ID counter for AST nodes
   parked     ; parked continuations
   primitives ; primitive operations
   run-queue  ; vector of runnable continuations
   k          ; linked-list of continuation frames
   store      ; heap memory
   value      ; final result value
   wait-set   ; vector of parked continuations waiting on streams
   index-arr  ; array-backed node index for hot loop
   index-base-id ; base id for index-arr offset calculation
   macro-registry ; {macro-lambda-eid -> (fn [ctx] {:datoms [...] :root-eid eid})}
   telemetry    ; optional telemetry config
   telemetry-step ; telemetry snapshot counter
   telemetry-t  ; telemetry transaction counter
   vm-model     ; telemetry model keyword
   ])


(declare semantic-expand-macro-call)


;; =============================================================================
;; VM: Execute AST Datoms
;; =============================================================================

(defn- semantic-return
  "Materialize SemanticVM state from CES fields."
  [^SemanticVM vm control env k halted? val]
  (->SemanticVM
    (:blocked vm)
    (:bridge vm)
    control
    (:datoms vm)
    (:db vm)
    env
    halted?
    (:id-counter vm)
    (:index vm)
    (:node-id-counter vm)
    (:parked vm)
    (:primitives vm)
    (:run-queue vm)
    k
    (:store vm)
    val
    (:wait-set vm)
    (:index-arr vm)
    (:index-base-id vm)
    (:macro-registry vm)
    (:telemetry vm)
    (:telemetry-step vm)
    (:telemetry-t vm)
    (:vm-model vm)))


(defn- park-and-call
  "Park the current semantic continuation stack and emit a DaoCall request."
  [vm op args k env]
  (let [;; 1. Park the continuation with a response-processing frame
        response-k {:type :dao.stream.apply/call, :next k}
        parked (engine/park-continuation vm {:k response-k, :env env})
        parked-id (get-in parked [:value :id])

        ;; 2. Get response stream from store
        call-out (get-in parked [:store vm/call-out-stream-key])
        cursor-data (get-in parked [:store vm/call-out-cursor-key])
        cursor-pos (:position cursor-data)

        ;; 3. Register as reader-waiter on response stream
        waiter-entry {:cursor-ref {:type :cursor-ref, :id vm/call-out-cursor-key}
                      :reason :next
                      :stream-id vm/call-out-stream-key
                      :k response-k
                      :env env}
        _ (when (satisfies? ds/IDaoStreamWaitable call-out)
            (ds/register-reader-waiter! call-out cursor-pos waiter-entry))

        ;; 4. Get request stream from store
        call-in (get-in parked [:store vm/call-in-stream-key])

        ;; 5. Build and emit request
        request (dao.stream.apply/request parked-id op args)
        _ (ds/put! call-in request)]

    ;; 6. Return blocked state
    (assoc (telemetry/emit-snapshot parked :bridge {:bridge-op op})
           :control nil
           :value :yin/blocked
           :blocked true
           :halted false)))


(defn- handle-return-value
  [vm]
  (let [{:keys [control k]} vm
        val (:val control)]
    (if (nil? k)
      (assoc vm
             :halted true
             :value val)
      (let [frame k
            new-k (:next k)]
        (case (:type frame)
          :if (let [{consequent :consequent, alternate :alternate, env-restore :env} frame]
                (assoc vm
                       :control {:type :node, :id (if val consequent alternate)}
                       :env env-restore
                       :k new-k))
          :app-op
          (let [{operands :operands, env-call :env, tail? :tail?} frame
                fn-val val]
            (if (empty? operands)
              ;; 0-arity call
              (if (fn? fn-val)
                (let [result (fn-val)]
                  (if (module/effect? result)
                    (let [{:keys [state value]}
                          (engine/handle-effect vm result {})]
                      (assoc state
                             :control {:type :value, :val value}
                             :env env-call
                             :k new-k))
                    (assoc vm
                           :control {:type :value, :val result}
                           :env env-call
                           :k new-k)))
                (if (= :closure (:type fn-val))
                  (let [{body-node :body-node, env-clo :env} fn-val]
                    (if tail?
                      ;; TCO: skip restore-env
                      (assoc vm
                             :control {:type :node, :id body-node}
                             :env env-clo
                             :k new-k)
                      (assoc vm
                             :control {:type :node, :id body-node}
                             :env env-clo ; Switch to closure env
                             :k {:type :restore-env,
                                 :env env-call,
                                 :next new-k})))
                  (throw (ex-info "Cannot apply non-function" {:fn fn-val}))))
              ;; Prepare to eval args
              (let [first-arg (first operands)]
                (assoc vm
                       :control {:type :node, :id first-arg}
                       :env env-call
                       :k {:type :app-args,
                           :fn fn-val,
                           :evaluated [],
                           :operands operands,
                           :next-idx 1,
                           :env env-call,
                           :tail? tail?,
                           :next new-k}))))
          :app-args
          (let [{fn-val :fn, evaluated :evaluated, operands :operands, next-idx :next-idx, env-call :env, tail? :tail?} frame
                new-evaluated (conj evaluated val)]
            (if (= next-idx (count operands))
              ;; All args evaluated, apply
              (if (fn? fn-val)
                (let [result (apply fn-val new-evaluated)]
                  (if (module/effect? result)
                    (let [{:keys [state value blocked?]}
                          (engine/handle-effect
                            vm
                            result
                            {:park-entry-fns
                             {:stream/put (fn [_s e r]
                                            {:k new-k,
                                             :env env-call,
                                             :reason :put,
                                             :stream-id (:stream-id r),
                                             :datom (:val e)}),
                              :stream/next (fn [_s _e r]
                                             {:k new-k,
                                              :env env-call,
                                              :reason :next,
                                              :cursor-ref (:cursor-ref r),
                                              :stream-id (:stream-id
                                                           r)})}})]
                      (if blocked?
                        (assoc state :control nil)
                        (assoc state
                               :control {:type :value, :val value}
                               :env env-call
                               :k new-k)))
                    (assoc vm
                           :control {:type :value, :val result}
                           :env env-call
                           :k new-k)))
                (if (= :closure (:type fn-val))
                  (let [{params :params, body-node :body-node, env-clo :env} fn-val
                        new-env (merge env-clo (zipmap params new-evaluated))]
                    (if tail?
                      ;; TCO: skip restore-env
                      (assoc vm
                             :control {:type :node, :id body-node}
                             :env new-env
                             :k new-k)
                      (assoc vm
                             :control {:type :node, :id body-node}
                             :env new-env ; Closure env + args
                             :k {:type :restore-env,
                                 :env env-call,
                                 :next new-k})))
                  (throw (ex-info "Cannot apply non-function" {:fn fn-val}))))
              ;; More args to eval
              (let [next-arg (nth operands next-idx)]
                (assoc vm
                       :control {:type :node, :id next-arg}
                       :env env-call
                       :k (assoc frame
                                 :evaluated new-evaluated
                                 :next-idx (inc next-idx))))))
          :dao.stream.apply/args-eval
          (let [{op :op, evaluated :evaluated, operands :operands, next-idx :next-idx, env-call :env} frame
                new-evaluated (conj evaluated val)]
            (if (= next-idx (count operands))
              (park-and-call vm op new-evaluated new-k env-call)
              (let [next-arg (nth operands next-idx)]
                (assoc vm
                       :control {:type :node, :id next-arg}
                       :env env-call
                       :k (assoc frame
                                 :evaluated new-evaluated
                                 :next-idx (inc next-idx))))))
          :dao.stream.apply/call
          (let [result-value (:dao.stream.apply/value val)]
            (assoc vm
                   :control {:type :value, :val result-value}
                   :k new-k))
          :restore-env (assoc vm
                              :control control ; Pass value up
                              :env (:env frame) ; Restore caller env
                              :k new-k)
          ;; Stream continuation frames
          :stream-put-target (let [stream-ref val
                                   val-node (:val-node frame)]
                               (assoc vm
                                      :control {:type :node, :id val-node}
                                      :k {:type :stream-put-val,
                                          :stream-ref stream-ref,
                                          :env (:env frame),
                                          :next new-k}))
          :stream-put-val
          (let [put-val val
                stream-ref (:stream-ref frame)
                effect {:effect :stream/put, :stream stream-ref, :val put-val}
                {:keys [state value blocked?]}
                (engine/handle-effect vm
                                      effect
                                      {:park-entry-fns
                                       {:stream/put
                                        (fn [_s _e r]
                                          {:k new-k,
                                           :env (:env frame),
                                           :reason :put,
                                           :stream-id (:stream-id r),
                                           :datom put-val})}})]
            (if blocked?
              (assoc state :control nil)
              (assoc state
                     :control {:type :value, :val value}
                     :env (:env frame)
                     :k new-k)))
          :stream-cursor-source
          (let [stream-ref val
                effect {:effect :stream/cursor, :stream stream-ref}
                {:keys [state value]} (engine/handle-effect vm effect {})]
            (assoc state
                   :control {:type :value, :val value}
                   :env (:env frame)
                   :k new-k))
          :stream-next-cursor
          (let [cursor-ref val
                effect {:effect :stream/next, :cursor cursor-ref}
                {:keys [state value blocked?]}
                (engine/handle-effect
                  vm
                  effect
                  {:park-entry-fns {:stream/next
                                    (fn [_s _e r]
                                      {:k new-k,
                                       :env (:env frame),
                                       :reason :next,
                                       :cursor-ref (:cursor-ref r),
                                       :stream-id (:stream-id r)})}})]
            (if blocked?
              (assoc state :control nil)
              (assoc state
                     :control {:type :value, :val value}
                     :env (:env frame)
                     :k new-k)))
          :stream-close-source
          (let [stream-ref val
                effect {:effect :stream/close, :stream stream-ref}
                {:keys [state value]} (engine/handle-effect vm effect {})]
            (assoc state
                   :control {:type :value, :val value}
                   :env (:env frame)
                   :k new-k))
          :resume-val
          (let [resume-val val
                parked-id (:parked-id frame)]
            (engine/resume-continuation vm
                                        parked-id
                                        resume-val
                                        (fn [new-state parked rv]
                                          (assoc new-state
                                                 :k (:k parked)
                                                 :env (:env parked)
                                                 :control {:type :value,
                                                           :val rv}))))
          ;; Default
          (throw (ex-info "Unknown frame type" {:frame frame})))))))


(defn handle-node-eval
  [vm]
  (let [{:keys [control env k datoms index store primitives]} vm
        node-id (:id control)
        #?(:clj ^objects node-arr :default node-arr) (get index node-id)]
    (if (nil? node-arr)
      (throw (ex-info "Unknown node id in semantic slow path" {:node-id node-id}))
      (let [node-type (aget node-arr ATTR_TYPE)]
        (case node-type
          :literal (assoc vm :control {:type :value, :val (aget node-arr ATTR_VALUE)})
          :variable (let [name (aget node-arr ATTR_NAME)
                          val (engine/resolve-var env store primitives name)]
                      (assoc vm :control {:type :value, :val val}))
          :lambda (assoc vm
                         :control {:type :value,
                                   :val {:type :closure,
                                         :params (aget node-arr ATTR_PARAMS),
                                         :body-node (aget node-arr ATTR_BODY),
                                         :datoms datoms,
                                         :env env}})
          :if (assoc vm
                     :control {:type :node, :id (aget node-arr ATTR_TEST)}
                     :k {:type :if,
                         :consequent (aget node-arr ATTR_CONSEQUENT),
                         :alternate (aget node-arr ATTR_ALTERNATE),
                         :env env,
                         :next k})
          :application (assoc vm
                              :control {:type :node, :id (aget node-arr ATTR_OPERATOR)}
                              :k {:type :app-op,
                                  :operands (aget node-arr ATTR_OPERANDS),
                                  :env env,
                                  :tail? (aget node-arr ATTR_TAIL),
                                  :next k})
          :dao.stream.apply/call (let [operands (or (aget node-arr ATTR_OPERANDS) [])
                                       op (aget node-arr ATTR_OP)]
                                   (if (empty? operands)
                                     (park-and-call vm op [] k env)
                                     (assoc vm
                                            :control {:type :node, :id (first operands)}
                                            :k {:type :dao.stream.apply/args-eval,
                                                :op op,
                                                :evaluated [],
                                                :operands operands,
                                                :next-idx 1,
                                                :env env,
                                                :next k})))
          ;; VM primitives
          :vm/gensym (let [prefix (or (aget node-arr ATTR_PREFIX) "id")
                           [id s'] (engine/gensym vm prefix)]
                       (assoc s' :control {:type :value, :val id}))
          :vm/store-get (let [key (aget node-arr ATTR_KEY)
                              val (get store key)]
                          (assoc vm :control {:type :value, :val val}))
          :vm/store-put (let [key (aget node-arr ATTR_KEY)
                              val (aget node-arr ATTR_VALUE)]
                          (assoc vm
                                 :control {:type :value, :val val}
                                 :store (assoc store key val)))
          ;; Stream operations
          :stream/make (let [capacity (aget node-arr ATTR_BUFFER)
                             effect {:effect :stream/make, :capacity capacity}
                             {:keys [state value]}
                             (engine/handle-effect vm effect {})]
                         (assoc state :control {:type :value, :val value}))
          :stream/put (let [target-node (aget node-arr ATTR_TARGET)]
                        (assoc vm
                               :control {:type :node, :id target-node}
                               :k {:type :stream-put-target,
                                   :val-node (aget node-arr ATTR_VAL_NODE),
                                   :env env,
                                   :next k}))
          :stream/cursor (let [source-node (aget node-arr ATTR_SOURCE)]
                           (assoc vm
                                  :control {:type :node, :id source-node}
                                  :k {:type :stream-cursor-source,
                                      :env env,
                                      :next k}))
          :stream/next (let [source-node (aget node-arr ATTR_SOURCE)]
                         (assoc vm
                                :control {:type :node, :id source-node}
                                :k {:type :stream-next-cursor,
                                    :env env,
                                    :next k}))
          :stream/close (let [source-node (aget node-arr ATTR_SOURCE)]
                          (assoc vm
                                 :control {:type :node, :id source-node}
                                 :k {:type :stream-close-source,
                                     :env env,
                                     :next k}))
          ;; Continuation primitives
          :vm/park (-> (engine/park-continuation vm {:k k, :env env})
                       (assoc :control nil))
          :vm/resume (let [parked-id (aget node-arr ATTR_PARKED_ID)
                           val-node (aget node-arr ATTR_VAL_NODE)]
                       (assoc vm
                              :control {:type :node, :id val-node}
                              :k {:type :resume-val,
                                  :parked-id parked-id,
                                  :env env,
                                  :next k}))
          :vm/current-continuation
          (assoc vm
                 :control {:type :value,
                           :val {:type :reified-continuation, :k k, :env env}})
          ;; Runtime macro expansion: expand :yin/macro-expand node inline
          :yin/macro-expand
          (semantic-expand-macro-call vm node-id node-arr env k)
          (throw (ex-info "Unknown node type" {:node-type (aget node-arr ATTR_TYPE)})))))))


(defn- bind-semantic-env
  [env-clo params evaluated]
  (let [n (count params)]
    (case n
      0 env-clo
      1 (assoc env-clo
               (nth params 0) (nth evaluated 0))
      2 (assoc env-clo
               (nth params 0) (nth evaluated 0)
               (nth params 1) (nth evaluated 1))
      3 (assoc env-clo
               (nth params 0) (nth evaluated 0)
               (nth params 1) (nth evaluated 1)
               (nth params 2) (nth evaluated 2))
      (loop [idx 0
             env env-clo]
        (if (== idx n)
          env
          (recur (inc idx)
                 (assoc env (nth params idx) (nth evaluated idx))))))))


(defn- invoke-semantic-native
  [fn-val evaluated]
  (let [n (count evaluated)]
    (case n
      0 (fn-val)
      1 (fn-val (nth evaluated 0))
      2 (fn-val (nth evaluated 0) (nth evaluated 1))
      3 (fn-val (nth evaluated 0) (nth evaluated 1) (nth evaluated 2))
      4 (fn-val (nth evaluated 0) (nth evaluated 1) (nth evaluated 2) (nth evaluated 3))
      (apply fn-val evaluated))))


(defn- build-semantic-node-index-array
  [index cardinality]
  (if (empty? index)
    [0 (make-semantic-object-array 0)]
    (let [[min-id max-id]
          (reduce-kv (fn [[mn mx] eid _]
                       (if (nil? mn)
                         [eid eid]
                         [(min mn eid) (max mx eid)]))
                     [nil nil]
                     index)
          range-size (inc (- max-id min-id))]
      ;; Only use array-backed indexing if density is reasonable
      ;; (range is less than 4x cardinality)
      (if (<= range-size (* 4 cardinality))
        (let [arr (make-semantic-object-array range-size)]
          (doseq [[eid node-arr] index]
            (semantic-object-array-set! arr (- eid min-id) node-arr))
          [min-id arr])
        [0 (make-semantic-object-array 0)]))))


(defn- create-ast-db
  []
  (in-memory/create vm/schema))


(defn- ast-datoms->tx-data
  "Project canonical AST 5-tuples into DaoDB tx-data, preserving nil facts and m."
  [datoms]
  (mapcat (fn [[e a v _t m]]
            (cond
              (and (contains? cardinality-many-attrs a) (vector? v))
              (map (fn [ref] [:db/add e a ref m]) v)

              :else
              [[:db/add e a v m]]))
          datoms))


(defn- dao-datom->tuple
  [d]
  [(:e d) (:a d) (:v d) (:t d) (:m d)])


(defn- semantic-index-datoms
  [datoms]
  (filter (fn [[_e a _v _t _m]] (contains? attr->idx a)) datoms))


(defn- ast-dao-datom?
  [d]
  (= "yin" (namespace (:a d))))


(defn- build-semantic-index-from-datoms
  [datoms]
  (let [datom-index (group-by first (semantic-index-datoms datoms))]
    (into {}
          (map (fn [[eid _datoms]]
                 [eid (datom-node-attrs datom-index eid)]))
          datom-index)))


(defn- materialize-ast-datoms
  [dao-db]
  (let [active (set (db/datoms dao-db :eavt))
        entries (db/read-segments dao-db 0 (db/basis-t dao-db))]
    (second
      (reduce (fn [[seen acc] [_t added _retracted]]
                (reduce (fn [[seen acc] d]
                          (if (or (contains? seen d)
                                  (not (contains? active d))
                                  (not (ast-dao-datom? d)))
                            [seen acc]
                            [(conj seen d) (conj acc (dao-datom->tuple d))]))
                        [seen acc]
                        added))
              [#{} []]
              entries))))


(defn- refresh-semantic-index-from-db
  [vm dao-db]
  (let [datoms (materialize-ast-datoms dao-db)
        node-map-index (build-semantic-index-from-datoms datoms)
        cardinality (count node-map-index)
        [index-base-id index-arr] (build-semantic-node-index-array node-map-index cardinality)]
    (assoc vm
           :db dao-db
           :datoms datoms
           :index node-map-index
           :index-arr index-arr
           :index-base-id index-base-id)))


(defn- add-tempid-index-aliases
  [vm tempids]
  (if (seq tempids)
    (update vm :index
            (fn [index]
              (reduce-kv (fn [idx tempid eid]
                           (if-let [node-arr (get idx eid)]
                             (assoc idx tempid node-arr)
                             idx))
                         index
                         tempids)))
    vm))


(defn- remap-macro-registry
  [registry tempids]
  (if (seq tempids)
    (into {}
          (map (fn [[eid macro-fn]]
                 [(get tempids eid eid) macro-fn]))
          registry)
    registry))


(defn- tempid?
  [x]
  (and (integer? x) (neg? x)))


(defn- positive-id?
  [x]
  (and (integer? x) (pos? x)))


(defn- ref-values
  [ref-attrs attr value]
  (when (contains? ref-attrs attr)
    (if (vector? value) value [value])))


(defn- ordered-ast-tempids
  [dao-db datoms]
  (let [ref-attrs (:ref-attrs dao-db)]
    (vec
      (distinct
        (reduce (fn [ids [e a v _t m]]
                  (cond-> ids
                    (tempid? e)
                    (conj e)
                    true
                    (into (filter tempid?) (ref-values ref-attrs a v))
                    (tempid? m)
                    (conj m)))
                []
                datoms)))))


(defn- positive-id-ceiling
  [dao-db datoms]
  (let [ref-attrs (:ref-attrs dao-db)]
    (reduce (fn [ceiling [e a v _t m]]
              (let [ids (cond-> []
                          (positive-id? e) (conj e)
                          true (into (filter positive-id?) (ref-values ref-attrs a v))
                          (positive-id? m) (conj m))]
                (reduce (fn [mx id] (max mx (inc id))) ceiling ids)))
            (:next-eid dao-db)
            datoms)))


(defn- ast-tempid-map
  [dao-db datoms]
  (let [tempids (ordered-ast-tempids dao-db datoms)
        start (positive-id-ceiling dao-db datoms)]
    (zipmap tempids (range start (+ start (count tempids))))))


(defn- resolve-ast-datom
  [dao-db tempids [e a v t m]]
  (let [resolve-id (fn [id] (if (tempid? id) (get tempids id id) id))
        v (if (contains? (:ref-attrs dao-db) a)
            (if (vector? v)
              (mapv resolve-id v)
              (resolve-id v))
            v)]
    [(resolve-id e) a v t (resolve-id m)]))


(defn- transact-ast-datoms
  [dao-db datoms]
  (if (seq datoms)
    (let [datoms (vec datoms)
          tempids (ast-tempid-map dao-db datoms)
          resolved-datoms (mapv #(resolve-ast-datom dao-db tempids %) datoms)
          tx-result (db/transact dao-db (ast-datoms->tx-data resolved-datoms))]
      (update tx-result :tempids #(merge tempids (or % {}))))
    {:db dao-db, :db-after dao-db, :db-before dao-db, :tx-data [], :tempids {}}))


(defn- fast-semantic-frame->map
  [frame]
  (if (vector? frame)
    (case (int (nth frame 0))
      0 {:type :if,
         :consequent (nth frame 1),
         :alternate (nth frame 2),
         :env (nth frame 3)}
      1 {:type :app-op,
         :operands (nth frame 1),
         :env (nth frame 2),
         :tail? (nth frame 3)}
      2 {:type :app-args,
         :fn (nth frame 1),
         :evaluated (nth frame 2),
         :operands (nth frame 3),
         :next-idx (nth frame 4),
         :env (nth frame 5),
         :tail? (nth frame 6)}
      3 {:type :restore-env,
         :env (nth frame 1)}
      frame)
    frame))


(defn- semantic-step
  "Execute one step of the semantic VM.
   Operates directly on SemanticVM record (assoc preserves record type)."
  [^SemanticVM vm]
  (let [{:keys [control]} vm]
    (if (= :value (:type control))
      ;; Handle return value from previous step
      (handle-return-value vm)
      ;; Handle node evaluation
      (handle-node-eval vm))))


;; =============================================================================
;; Scheduler
;; =============================================================================

(defn- resume-from-run-queue
  "Pop first entry from run-queue and resume it as the active computation.
   Returns updated state or nil if queue is empty."
  [state]
  (engine/resume-from-run-queue state
                                (fn [base entry]
                                  (assoc base
                                         :k (:k entry)
                                         :env (:env entry)
                                         :control {:type :value,
                                                   :val (:value entry)}))))


;; =============================================================================
;; SemanticVM Protocol Implementation
;; =============================================================================

(defn- semantic-vm-step
  "Execute one step of SemanticVM. Returns updated VM."
  [^SemanticVM vm]
  (semantic-step vm))


(defn- semantic-vm-halted?
  "Returns true if VM has halted."
  [^SemanticVM vm]
  (engine/halted-with-empty-queue? vm))


(defn- semantic-vm-blocked?
  "Returns true if VM is blocked."
  [^SemanticVM vm]
  (engine/vm-blocked? vm))


(defn- semantic-vm-value
  "Returns the current value."
  [^SemanticVM vm]
  (engine/vm-value vm))


(defn- semantic-vm-load-program
  "Load datoms into the VM.
   Expects {:node root-id :datoms [...]}."
  [^SemanticVM vm {:keys [node datoms]}]
  (let [ast-db (or (:db vm) (create-ast-db))
        {:keys [db-after tempids]} (transact-ast-datoms ast-db datoms)
        root-id (get tempids node node)
        vm (-> vm
               (assoc :macro-registry (remap-macro-registry (:macro-registry vm) tempids))
               (refresh-semantic-index-from-db db-after)
               (add-tempid-index-aliases tempids))]
    (assoc vm
           :control {:type :node, :id root-id}
           :k nil
           :halted false
           :value nil
           :blocked false)))


(defn- semantic-append-datoms*
  [^SemanticVM vm new-datoms]
  (let [ast-db (or (:db vm) (create-ast-db))
        {:keys [db-after] :as tx-result} (transact-ast-datoms ast-db new-datoms)]
    (assoc tx-result :vm (-> (refresh-semantic-index-from-db vm db-after)
                             (add-tempid-index-aliases (:tempids tx-result))))))


(defn semantic-append-datoms
  "Append new datoms to the semantic VM's index without resetting execution state.
   For new EIDs, creates a fresh node entry.
   For existing EIDs, patches individual attributes (preserves existing attrs).
   Used by the macro expander to inject expansion output at runtime."
  [^SemanticVM vm new-datoms]
  (:vm (semantic-append-datoms* vm new-datoms)))


(declare invoke-macro-lambda)


(defn- semantic-expand-macro-call
  "Expand a :yin/macro-expand node at runtime.
   Returns updated VM with expansion appended to datom index and control
   pointing to the expansion root."
  [^SemanticVM vm node-id node-arr env k]
  (let [macro-registry (:macro-registry vm)
        op-eid (aget node-arr ATTR_OPERATOR)
        arg-eids (or (aget node-arr ATTR_OPERANDS) [])
        ;; Resolve variable-operator references to their macro lambda EID.
        ;; Yang emits :yin/macro-expand with a :variable operator node when
        ;; the macro EID is not statically known (user-defined macros).
        by-entity (group-by first (:datoms vm))
        get-attr (fn [eid attr]
                   (some (fn [[_ a v]] (when (= a attr) v))
                         (rseq (vec (get by-entity eid)))))
        macro-lambda-eid
        (if (= :variable (get-attr op-eid :yin/type))
          (let [vname (get-attr op-eid :yin/name)]
            (or (get macro/default-name-registry vname)
                (macro/find-macro-lambda-by-name vname (:datoms vm) get-attr)
                op-eid))
          op-eid)
        macro-fn (get macro-registry macro-lambda-eid)
        eid-counter (macro/make-eid-counter! (:datoms vm))
        event-eid (swap! eid-counter dec)
        fresh-eid-fn (fn [] (swap! eid-counter dec))
        ctx {:get-attr get-attr
             :by-entity by-entity
             :arg-eids arg-eids
             :fresh-eid fresh-eid-fn
             :phase :runtime}
        result (if macro-fn
                 (macro-fn ctx)
                 (if (get-attr macro-lambda-eid :yin/macro?)
                   (invoke-macro-lambda macro-lambda-eid ctx (:datoms vm))
                   (throw (ex-info "No macro registered and lambda not found"
                                   {:macro-lambda-eid macro-lambda-eid
                                    :call-eid node-id
                                    :registered-keys (keys macro-registry)}))))
        exp-datoms (:datoms result)
        exp-root (:root-eid result)
        evt-datoms (macro/expansion-event-datoms
                     event-eid node-id macro-lambda-eid exp-root :runtime)
        marked-exp (macro/mark-with-provenance exp-datoms event-eid)
        all-new (vec (concat evt-datoms marked-exp))
        {:keys [tempids] updated-vm :vm} (semantic-append-datoms* vm all-new)
        exp-root (get tempids exp-root exp-root)]
    (assoc updated-vm
           :control {:type :node, :id exp-root}
           :env env
           :k k)))


(defn- semantic-run-active-continuation
  "Hot loop that keeps CES state in JVM locals.
   Uses a flat Object array for the k to avoid vector allocations."
  [^SemanticVM vm-init control-init env-init k-init]
  (let [index (:index vm-init)
        index-arr (:index-arr vm-init)
        index-base-id (int (:index-base-id vm-init))
        primitives (:primitives vm-init)
        datoms (:datoms vm-init)

        ;; Flat k array with grow support
        v-s-arr (volatile! (make-semantic-object-array 256))
        v-s-limit (volatile! 256)

        ensure-capacity! (fn [required-sp]
                           (loop [limit @v-s-limit]
                             (if (>= required-sp limit)
                               (let [new-limit (* limit 2)
                                     new-arr (make-semantic-object-array new-limit)]
                                 (loop [i 0]
                                   (when (< i limit)
                                     (semantic-object-array-set! new-arr i (semantic-object-array-get @v-s-arr i))
                                     (recur (inc i))))
                                 (vreset! v-s-arr new-arr)
                                 (vreset! v-s-limit new-limit)
                                 (recur new-limit))
                               limit)))

        push! (fn [sp val]
                (let [next-sp (inc sp)]
                  (ensure-capacity! next-sp)
                  (semantic-object-array-set! @v-s-arr next-sp val)
                  next-sp))

        copy-k! (fn [ext-k]
                  (loop [k ext-k
                         frames []]
                    (if (nil? k)
                      (let [n (count frames)]
                        (if (zero? n)
                          -1
                          (do
                            (ensure-capacity! (dec n))
                            (loop [i 0]
                              (when (< i n)
                                (semantic-object-array-set! @v-s-arr i (nth frames (- n i 1)))
                                (recur (inc i))))
                            (dec n))))
                      (recur (:next k) (conj frames k)))))

        materialize-k (fn [sp]
                        (loop [i 0
                               k nil]
                          (if (<= i sp)
                            (let [frame (fast-semantic-frame->map (semantic-object-array-get @v-s-arr i))]
                              (recur (inc i) (assoc frame :next k)))
                            k)))]

    ;; Pre-fill k-arr if resuming from non-empty k
    (loop [ctrl-tag (:type control-init)
           ctrl-data (if (= :value (:type control-init))
                       (:val control-init)
                       (:id control-init))
           env env-init
           sp (int (if (nil? k-init) -1 (copy-k! k-init))) ; k pointer
           vm vm-init]
      (case ctrl-tag
        ;; --- 1. Handle Return Value ---
        :value
        (let [val ctrl-data]
          (if (< sp 0)
            (semantic-return vm {:type :value, :val val} env nil true val)
            (let [frame (semantic-object-array-get @v-s-arr sp)
                  new-sp (dec sp)]
              (if (vector? frame)
                (case (int (nth frame 0))
                  0 ; FT_IF
                  (let [consequent (nth frame 1)
                        alternate (nth frame 2)
                        env-restore (nth frame 3)]
                    (recur :node (if val consequent alternate)
                           env-restore
                           new-sp
                           vm))

                  1 ; FT_APP_OP
                  (let [operands (nth frame 1)
                        env-call (nth frame 2)
                        tail? (nth frame 3)
                        fn-val val]
                    (if (empty? operands)
                      ;; Arity-0 call
                      (cond
                        (= :closure (:type fn-val))
                        (let [{body-node :body-node, env-clo :env} fn-val]
                          (if tail?
                            (recur :node body-node env-clo new-sp vm)
                            (let [next-sp (push! new-sp [FT_RESTORE_ENV env-call])]
                              (recur :node body-node env-clo next-sp vm))))

                        (fn? fn-val)
                        (let [result (fn-val)]
                          (if (module/effect? result)
                            (let [state (semantic-return vm
                                                         {:type :value, :val val}
                                                         env
                                                         (materialize-k sp)
                                                         false
                                                         val)
                                  {:keys [state value blocked?]} (engine/handle-effect state result {})]
                              (if blocked?
                                (assoc state :control nil)
                                (recur :value value env-call new-sp state)))
                            (recur :value result env-call new-sp vm)))

                        :else (throw (ex-info "Cannot apply non-function" {:fn fn-val})))

                      ;; Evaluate args
                      (let [first-arg (nth operands 0)
                            next-sp (push! new-sp [FT_APP_ARGS fn-val [] operands 1 env-call tail?])]
                        (recur :node first-arg env-call next-sp vm))))

                  2 ; FT_APP_ARGS
                  (let [fn-val (nth frame 1)
                        evaluated (nth frame 2)
                        operands (nth frame 3)
                        next-idx (nth frame 4)
                        env-call (nth frame 5)
                        tail? (nth frame 6)
                        new-evaluated (conj evaluated val)]
                    (if (== next-idx (count operands))
                      ;; All args evaluated, apply
                      (cond
                        (= :closure (:type fn-val))
                        (let [{params :params, body-node :body-node, env-clo :env} fn-val
                              new-env (bind-semantic-env env-clo params new-evaluated)]
                          (if tail?
                            (recur :node body-node new-env new-sp vm)
                            (let [next-sp (push! new-sp [FT_RESTORE_ENV env-call])]
                              (recur :node body-node new-env next-sp vm))))

                        (fn? fn-val)
                        (let [result (invoke-semantic-native fn-val new-evaluated)]
                          (if (module/effect? result)
                            (let [state (semantic-return vm
                                                         {:type :value, :val val}
                                                         env
                                                         (materialize-k sp)
                                                         false
                                                         val)
                                  park-k (materialize-k new-sp)
                                  {:keys [state value blocked?]}
                                  (engine/handle-effect
                                    state
                                    result
                                    {:park-entry-fns
                                     {:stream/put (fn [_s e r]
                                                    {:k park-k,
                                                     :env env-call,
                                                     :reason :put,
                                                     :stream-id (:stream-id r),
                                                     :datom (:val e)}),
                                      :stream/next (fn [_s _e r]
                                                     {:k park-k,
                                                      :env env-call,
                                                      :reason :next,
                                                      :cursor-ref (:cursor-ref r),
                                                      :stream-id (:stream-id r)})}})]
                              (if blocked?
                                (assoc state :control nil)
                                (recur :value value env-call new-sp state)))
                            (recur :value result env-call new-sp vm)))

                        :else (throw (ex-info "Cannot apply non-function" {:fn fn-val})))

                      ;; More args
                      (let [next-arg (nth operands next-idx)]
                        (semantic-object-array-set! @v-s-arr sp
                                                    [FT_APP_ARGS fn-val new-evaluated operands (inc next-idx) env-call tail?])
                        (recur :node next-arg env-call sp vm))))

                  3 ; FT_RESTORE_ENV
                  (recur ctrl-tag ctrl-data (nth frame 1) new-sp vm)

                  ;; Unknown fast frame fallback
                  (let [state (semantic-return vm
                                               {:type :value, :val val}
                                               env
                                               (materialize-k sp)
                                               false
                                               val)
                        next (handle-return-value state)]
                    (if (or (:blocked next) (:halted next))
                      next
                      (let [next-control (:control next)
                            next-tag (:type next-control)
                            next-data (if (= :value next-tag)
                                        (:val next-control)
                                        (:id next-control))
                            next-k (:k next)
                            next-sp (copy-k! next-k)]
                        (recur next-tag next-data (:env next) next-sp next)))))

                ;; Map-based frame fallback
                (let [state (semantic-return vm
                                             {:type :value, :val val}
                                             env
                                             (materialize-k sp)
                                             false
                                             val)
                      next (handle-return-value state)]
                  (if (or (:blocked next) (:halted next))
                    next
                    (let [next-control (:control next)
                          next-tag (:type next-control)
                          next-data (if (= :value next-tag)
                                      (:val next-control)
                                      (:id next-control))
                          next-k (:k next)
                          next-sp (copy-k! next-k)]
                      (recur next-tag next-data (:env next) next-sp next))))))))

        ;; --- 2. Handle Node Evaluation ---
        :node
        (let [node-id ctrl-data
              idx #?(:clj (unchecked-subtract-int (int node-id) index-base-id)
                     :default (- (int node-id) index-base-id))
              arr-len (semantic-object-array-length index-arr)
              node-arr (if (and (<= 0 idx) (< idx arr-len))
                         (semantic-object-array-get index-arr idx)
                         (get index node-id))]
          (if (nil? node-arr)
            (throw (ex-info "Unknown node id in semantic hot loop"
                            {:node-id node-id,
                             :index-base-id index-base-id,
                             :index-arr-length arr-len}))
            (let [#?(:clj ^objects node-arr :default node-arr) node-arr
                  node-type (aget node-arr ATTR_TYPE)]
              (case node-type
                :literal (recur :value (aget node-arr ATTR_VALUE) env sp vm)
                :variable (let [name (aget node-arr ATTR_NAME)
                                resolved (engine/resolve-var env (:store vm) primitives name)]
                            (recur :value resolved env sp vm))
                :lambda (recur :value {:type :closure,
                                       :params (aget node-arr ATTR_PARAMS),
                                       :body-node (aget node-arr ATTR_BODY),
                                       :datoms datoms,
                                       :env env}
                               env
                               sp
                               vm)
                :if (let [next-sp (push! sp [FT_IF
                                             (aget node-arr ATTR_CONSEQUENT)
                                             (aget node-arr ATTR_ALTERNATE)
                                             env])]
                      (recur :node (aget node-arr ATTR_TEST) env next-sp vm))
                :application (let [next-sp (push! sp [FT_APP_OP
                                                      (aget node-arr ATTR_OPERANDS)
                                                      env
                                                      (aget node-arr ATTR_TAIL)])]
                               (recur :node (aget node-arr ATTR_OPERATOR) env next-sp vm))

                ;; Fallback for VM primitives, stream ops, and runtime macro expansion
                (let [state (semantic-return vm
                                             {:type :node, :id node-id}
                                             env
                                             (materialize-k sp)
                                             false
                                             nil)
                      next (handle-node-eval state)]
                  (if (or (:blocked next)
                          (nil? (:control next))
                          ;; Index changed (e.g., runtime macro expanded new nodes):
                          ;; exit hot loop so outer eval can restart with updated index
                          (not (identical? (:index next) index)))
                    next
                    (let [next-control (:control next)
                          next-tag (:type next-control)
                          next-data (if (= :value next-tag)
                                      (:val next-control)
                                      (:id next-control))
                          next-k (:k next)
                          next-sp (copy-k! next-k)]
                      (recur next-tag next-data (:env next) next-sp next))))))))

        ;; --- 3. Exit: Halted, Blocked, or Scheduler ---
        (let [control (case ctrl-tag
                        :value {:type :value, :val ctrl-data}
                        :node {:type :node, :id ctrl-data}
                        nil)
              result (semantic-return vm control env (materialize-k sp) false nil)]
          (cond
            (:blocked result)
            (let [v' (engine/check-wait-set result)]
              (if-let [resumed (resume-from-run-queue v')]
                (let [resumed-control (:control resumed)
                      resumed-tag (:type resumed-control)
                      resumed-data (if (= :value resumed-tag)
                                     (:val resumed-control)
                                     (:id resumed-control))
                      resumed-k (:k resumed)
                      resumed-sp (copy-k! resumed-k)]
                  (recur resumed-tag resumed-data (:env resumed) resumed-sp resumed))
                v'))
            (seq (or (:run-queue result) []))
            (if-let [resumed (resume-from-run-queue result)]
              (let [resumed-control (:control resumed)
                    resumed-tag (:type resumed-control)
                    resumed-data (if (= :value resumed-tag)
                                   (:val resumed-control)
                                   (:id resumed-control))
                    resumed-k (:k resumed)
                    resumed-sp (copy-k! resumed-k)]
                (recur resumed-tag resumed-data (:env resumed) resumed-sp resumed))
              result)
            :else result))))))


(defn- semantic-vm-run
  "Run a loaded SemanticVM until halt or block."
  [^SemanticVM v]
  (if (or (telemetry/enabled? v)
          (:blocked v)
          (:halted v)
          (seq (:run-queue v)))
    (engine/run-loop v
                     engine/active-continuation?
                     (if (telemetry/enabled? v)
                       (fn [state]
                         (telemetry/emit-snapshot (semantic-vm-step state) :step))
                       semantic-vm-step)
                     resume-from-run-queue)
    ;; Run hot loop, restarting when runtime macro expansion updates the index
    (loop [v v]
      (let [result (semantic-run-active-continuation v
                                                     (:control v)
                                                     (:env v)
                                                     (:k v))]
        (cond
          ;; Blocked: hand off to scheduler (FFI, stream wait-set, run-queue)
          (:blocked result)
          (engine/run-loop result
                           engine/active-continuation?
                           semantic-vm-step
                           resume-from-run-queue)
          ;; Not halted and still has pending control: runtime macro expanded new nodes,
          ;; restart hot loop with the updated index
          (and (not (:halted result)) (some? (:control result)))
          (recur result)
          ;; Halted or no pending control
          :else result)))))


(defn- semantic-vm-eval
  "Evaluate an AST. Owns the step loop with scheduler.
   When ast is non-nil, converts to datoms and loads. When nil, resumes."
  [^SemanticVM vm ast]
  (let [v (if ast
            (let [datoms (vm/ast->datoms ast {:id-start (:node-id-counter vm)})
                  root-id (apply max (map first datoms))
                  min-id (apply min (map first datoms))
                  next-node-id (dec min-id)]
              (semantic-vm-load-program (assoc vm :node-id-counter next-node-id)
                                        {:node root-id, :datoms datoms}))
            vm)]
    (host-ffi/maybe-run v semantic-vm-run)))


(extend-type SemanticVM
  vm/IVMStep
  (step [vm] (telemetry/emit-snapshot (semantic-vm-step vm) :step))
  (halted? [vm] (semantic-vm-halted? vm))
  (blocked? [vm] (semantic-vm-blocked? vm))
  (value [vm] (semantic-vm-value vm))
  vm/IVMRun
  (run [vm] (vm/eval vm nil))
  vm/IVMLoad
  (load-program [vm program] (semantic-vm-load-program vm program))
  vm/IVMEval
  (eval [vm ast] (semantic-vm-eval vm ast))
  vm/IVMState
  (control [vm] (:control vm))
  (environment [vm] (:env vm))
  (store [vm] (:store vm))
  (continuation [vm]
    (when-let [k-head (:k vm)]
      (loop [k k-head
             acc []]
        (if (nil? k)
          acc
          (recur (:next k) (conj acc k)))))))


(defn create-vm
  "Create a new SemanticVM with optional opts map.
   Accepts {:env map, :primitives map, :macro-registry map, :bridge handlers, :telemetry config}."
  ([] (create-vm {}))
  ([opts]
   (let [env (or (:env opts) {})
         base (vm/empty-state {:primitives (:primitives opts)
                               :telemetry (:telemetry opts)
                               :vm-model :semantic})
         bridge-state (host-ffi/bridge-from-opts opts)]
     (-> (map->SemanticVM (merge base
                                 {:bridge bridge-state,
                                  :control nil,
                                  :env env,
                                  :k nil,
                                  :datoms [],
                                  :db (create-ast-db),
                                  :index {},
                                  :index-arr (make-semantic-object-array 0),
                                  :index-base-id 0,
                                  :halted false,
                                  :value nil,
                                  :blocked false,
                                  :node-id-counter -1024,
                                  :macro-registry (or (:macro-registry opts) {})}))
         (telemetry/install :semantic)
         (telemetry/emit-snapshot :init)))))


(defn- bind-variadic-params
  "Bind params vector to arg-eids, handling & rest syntax.
   Returns env map. For [a b & rest] with [e1 e2 e3 e4],
   produces {a e1, b e2, rest [e3 e4]}."
  [params arg-eids]
  (loop [ps (seq params) args (seq arg-eids) env {}]
    (cond
      (nil? ps) env
      (= '& (first ps)) (assoc env (second ps) (vec args))
      :else (recur (next ps) (next args) (assoc env (first ps) (first args))))))


(defn invoke-macro-lambda
  "Execute a macro lambda body in a fresh SemanticVM seeded with `datoms`.
   params are bound to arg-eids (integer AST entity refs).
   Supports variadic params with & rest syntax.
   Injects yin/get-attr, yin/sequence-body, yin/make-lambda, yin/make-def
   as primitives so macro bodies can inspect and construct AST nodes.
   Returns {:datoms [...] :root-eid result-eid}."
  [lambda-eid ctx datoms]
  (let [{:keys [arg-eids get-attr fresh-eid]} ctx
        params   (get-attr lambda-eid :yin/params)
        body-eid (get-attr lambda-eid :yin/body)
        env      (bind-variadic-params params arg-eids)
        ;; Datoms emitted by AST-builder primitives during macro body execution.
        ;; extra-datoms: raw vector returned to the caller as {:datoms ...}.
        ;; extra-index:  {eid {attr v}} map for O(1) lookup in local-get / mark-tail!.
        extra-datoms (atom [])
        extra-index  (atom {})
        emit!        (fn [ds]
                       (swap! extra-datoms into ds)
                       (swap! extra-index
                              (fn [idx]
                                (reduce (fn [acc [e a v _ _]] (assoc-in acc [e a] v))
                                        idx ds))))
        local-get    (fn [eid attr]
                       (or (get-attr eid attr)
                           (get-in @extra-index [eid attr])))
        ;; Macro-context primitives: only available inside a macro body VM.
        macro-prims
        {'yin/get-attr
         (fn [eid attr] (local-get eid attr))
         'yin/sequence-body
         (fn [body-eids]
           (let [bv (vec body-eids)
                 [root-eid new-ds] (macro/sequence-body-eids bv fresh-eid)]
             (emit! new-ds)
             root-eid))
         'yin/make-lambda
         (fn [params-eid body-eid]
           (let [lparams (get-attr params-eid :yin/value)
                 leid    (fresh-eid)]
             ;; Mark tail-position application nodes so TCO fires correctly.
             ;; The body of any lambda is always in tail position.
             ;; Recurse into :if branches and lambda operators (let/do desugaring).
             ;; local-get checks both the outer VM's datoms and extra-index so that
             ;; fresh nodes from yin/sequence-body are reachable.
             (let [visited (volatile! #{})]
               (letfn [(mark-tail!
                         [eid]
                         (when (and eid (not (contains? @visited eid)))
                           (vswap! visited conj eid)
                           (let [t (local-get eid :yin/type)]
                             (cond
                               (#{:application :yin/macro-expand} t)
                               (do (emit! [[eid :yin/tail? true 0 0]])
                                   (let [op-eid (local-get eid :yin/operator)]
                                     (when (= :lambda (local-get op-eid :yin/type))
                                       (mark-tail! (local-get op-eid :yin/body)))))
                               (= :if t)
                               (do (mark-tail! (local-get eid :yin/consequent))
                                   (mark-tail! (local-get eid :yin/alternate)))))))]
                 (mark-tail! body-eid)))
             (emit! [[leid :yin/type   :lambda  0 0]
                     [leid :yin/params lparams  0 0]
                     [leid :yin/body   body-eid 0 0]])
             leid))
         'yin/make-def
         (fn [name-eid value-eid]
           (let [dname  (or (get-attr name-eid :yin/name)
                            (get-attr name-eid :yin/value))
                 op-eid  (fresh-eid)
                 key-eid (fresh-eid)
                 def-eid (fresh-eid)]
             (emit! [[op-eid  :yin/type     :variable              0 0]
                     [op-eid  :yin/name     'yin/def               0 0]
                     [key-eid :yin/type     :literal               0 0]
                     [key-eid :yin/value    dname                  0 0]
                     [def-eid :yin/type     :application           0 0]
                     [def-eid :yin/operator op-eid                 0 0]
                     [def-eid :yin/operands [key-eid value-eid]    0 0]])
             def-eid))}
        vm-base  (create-vm)
        {:keys [tempids] vm-data :vm} (semantic-append-datoms* vm-base datoms)
        body-eid (get tempids body-eid body-eid)
        vm-ready (assoc vm-data
                        :primitives (merge (:primitives vm-data) macro-prims)
                        :control {:type :node, :id body-eid}
                        :env env
                        :halted false
                        :blocked false
                        :k nil)
        result-vm (vm/run vm-ready)
        raw-val   (vm/value result-vm)
        extra     (vec @extra-datoms)]
    (cond
      ;; Ambiguous: a map with both :type and :root-eid is a macro bug.
      (and (map? raw-val) (contains? raw-val :type) (contains? raw-val :root-eid))
      (throw (ex-info "Macro returned ambiguous map with both :type and :root-eid"
                      {:value raw-val}))
      ;; Macro returned an AST map: convert to datoms using current counter.
      (and (map? raw-val) (contains? raw-val :type))
      (let [[root-id exp-datoms] (vm/ast->datoms-with-root
                                   raw-val {:id-start (fresh-eid)})]
        {:datoms (into extra exp-datoms) :root-eid root-id})
      ;; Macro returned a pre-packaged {:datoms [...] :root-eid ...} map.
      (and (map? raw-val) (contains? raw-val :root-eid))
      (update raw-val :datoms #(into extra (or % [])))
      ;; Macro returned a negative-integer EID pointing to a node it created.
      (and (integer? raw-val) (neg? raw-val))
      {:datoms extra :root-eid raw-val}
      ;; Macro returned a plain value — wrap it in a literal node.
      :else
      (let [lit-eid (fresh-eid)]
        (emit! [[lit-eid :yin/type :literal 0 0]
                [lit-eid :yin/value raw-val  0 0]])
        {:datoms (vec @extra-datoms) :root-eid lit-eid}))))


;; =============================================================================
;; Query utilities
;; =============================================================================

(defn find-by-type
  "Find all entity IDs with the given :yin/type value."
  [dao-db t]
  (db/find-eids-by-av dao-db :yin/type t))


(defn find-lambdas
  "Find all lambda entities."
  [dao-db]
  (find-by-type dao-db :lambda))


(defn find-applications
  "Find all application entities."
  [dao-db]
  (find-by-type dao-db :application))


(defn find-variables
  "Find all variable entities."
  [dao-db]
  (find-by-type dao-db :variable))
