(ns yin.vm.semantic
  (:require
    [dao.db :as db]
    [yin.module :as module]
    [yin.vm :as vm]
    [yin.vm.engine :as engine]))


;; =============================================================================
;; Semantic VM
;; =============================================================================
;;
;; Interprets datom 5-tuples [e a v t m] (from vm/ast->datoms) by graph
;; traversal. Looks up node attributes via entity ID scanning the datom set.
;;
;; Proves the same computation can be recovered purely from the datom stream
;; without reconstructing the original AST maps.
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
(def ^:private ATTR_COUNT 18)


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
   :yin/source ATTR_SOURCE})


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


(defn find-by-type
  "Find all nodes of a given type. db must satisfy IDaoDb."
  [db node-type]
  (db/find-eids-by-av db :yin/type node-type))


(defn find-applications
  "Find all function applications in the db."
  [db]
  (find-by-type db :application))


(defn find-lambdas
  "Find all lambda definitions in the db."
  [db]
  (find-by-type db :lambda))


(defn find-variables
  "Find all variable references in the db."
  [db]
  (find-by-type db :variable))


;; =============================================================================
;; VM Records
;; =============================================================================

(defrecord SemanticVM
  [blocked    ; true if blocked
   control    ; current control state {:type :node/:value, ...}
   datoms     ; AST datoms
   env        ; lexical environment
   halted     ; true if execution completed
   id-counter ; unique ID counter
   index      ; Entity index {eid [datom...]}
   node-id-counter ; unique negative ID counter for AST nodes
   parked     ; parked continuations
   primitives ; primitive operations
   run-queue  ; vector of runnable continuations
   stack      ; continuation stack
   store      ; heap memory
   value      ; final result value
   wait-set   ; vector of parked continuations waiting on streams
   index-arr  ; array-backed node index for hot loop
   index-base-id ; base id for index-arr offset calculation
   ])


;; =============================================================================
;; VM: Execute AST Datoms
;; =============================================================================

(defn- semantic-return
  "Materialize SemanticVM state from CES fields."
  [^SemanticVM vm control env stack halted? val]
  (->SemanticVM
    (:blocked vm)
    control
    (:datoms vm)
    env
    halted?
    (:id-counter vm)
    (:index vm)
    (:node-id-counter vm)
    (:parked vm)
    (:primitives vm)
    (:run-queue vm)
    stack
    (:store vm)
    val
    (:wait-set vm)
    (:index-arr vm)
    (:index-base-id vm)))


(defn- handle-return-value
  [vm]
  (let [{:keys [control stack]} vm
        val (:val control)]
    (if (empty? stack)
      (assoc vm
             :halted true
             :value val)
      (let [frame (peek stack)
            new-stack (pop stack)]
        (case (:type frame)
          :if (let [{consequent :consequent, alternate :alternate, env-restore :env} frame]
                (assoc vm
                       :control {:type :node, :id (if val consequent alternate)}
                       :env env-restore
                       :stack new-stack))
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
                             :stack new-stack))
                    (assoc vm
                           :control {:type :value, :val result}
                           :env env-call
                           :stack new-stack)))
                (if (= :closure (:type fn-val))
                  (let [{body-node :body-node, env-clo :env} fn-val]
                    (if tail?
                      ;; TCO: skip restore-env
                      (assoc vm
                             :control {:type :node, :id body-node}
                             :env env-clo
                             :stack new-stack)
                      (assoc vm
                             :control {:type :node, :id body-node}
                             :env env-clo ; Switch to closure env
                             :stack (conj new-stack
                                          {:type :restore-env,
                                           :env env-call}))))
                  (throw (ex-info "Cannot apply non-function" {:fn fn-val}))))
              ;; Prepare to eval args
              (let [first-arg (first operands)]
                (assoc vm
                       :control {:type :node, :id first-arg}
                       :env env-call
                       :stack (conj new-stack
                                    {:type :app-args,
                                     :fn fn-val,
                                     :evaluated [],
                                     :operands operands,
                                     :next-idx 1,
                                     :env env-call,
                                     :tail? tail?})))))
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
                                            {:stack new-stack,
                                             :env env-call,
                                             :reason :put,
                                             :stream-id (:stream-id r),
                                             :datom (:val e)}),
                              :stream/next (fn [_s _e r]
                                             {:stack new-stack,
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
                               :stack new-stack)))
                    (assoc vm
                           :control {:type :value, :val result}
                           :env env-call
                           :stack new-stack)))
                (if (= :closure (:type fn-val))
                  (let [{params :params, body-node :body-node, env-clo :env} fn-val
                        new-env (merge env-clo (zipmap params new-evaluated))]
                    (if tail?
                      ;; TCO: skip restore-env
                      (assoc vm
                             :control {:type :node, :id body-node}
                             :env new-env
                             :stack new-stack)
                      (assoc vm
                             :control {:type :node, :id body-node}
                             :env new-env ; Closure env + args
                             :stack (conj new-stack
                                          {:type :restore-env,
                                           :env env-call}))))
                  (throw (ex-info "Cannot apply non-function" {:fn fn-val}))))
              ;; More args to eval
              (let [next-arg (nth operands next-idx)]
                (assoc vm
                       :control {:type :node, :id next-arg}
                       :env env-call
                       :stack (conj new-stack
                                    (assoc frame
                                           :evaluated new-evaluated
                                           :next-idx (inc next-idx)))))))
          :restore-env (assoc vm
                              :control control ; Pass value up
                              :env (:env frame) ; Restore caller env
                              :stack new-stack)
          ;; Stream continuation frames
          :stream-put-target (let [stream-ref val
                                   val-node (:val-node frame)]
                               (assoc vm
                                      :control {:type :node, :id val-node}
                                      :stack (conj new-stack
                                                   {:type :stream-put-val,
                                                    :stream-ref stream-ref,
                                                    :env (:env frame)})))
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
                                          {:stack new-stack,
                                           :env (:env frame),
                                           :reason :put,
                                           :stream-id (:stream-id r),
                                           :datom put-val})}})]
            (if blocked?
              (assoc state :control nil)
              (assoc state
                     :control {:type :value, :val value}
                     :env (:env frame)
                     :stack new-stack)))
          :stream-cursor-source
          (let [stream-ref val
                effect {:effect :stream/cursor, :stream stream-ref}
                {:keys [state value]} (engine/handle-effect vm effect {})]
            (assoc state
                   :control {:type :value, :val value}
                   :env (:env frame)
                   :stack new-stack))
          :stream-next-cursor
          (let [cursor-ref val
                effect {:effect :stream/next, :cursor cursor-ref}
                {:keys [state value blocked?]}
                (engine/handle-effect
                  vm
                  effect
                  {:park-entry-fns {:stream/next
                                    (fn [_s _e r]
                                      {:stack new-stack,
                                       :env (:env frame),
                                       :reason :next,
                                       :cursor-ref (:cursor-ref r),
                                       :stream-id (:stream-id r)})}})]
            (if blocked?
              (assoc state :control nil)
              (assoc state
                     :control {:type :value, :val value}
                     :env (:env frame)
                     :stack new-stack)))
          :stream-close-source
          (let [stream-ref val
                effect {:effect :stream/close, :stream stream-ref}
                {:keys [state value]} (engine/handle-effect vm effect {})]
            (assoc state
                   :control {:type :value, :val value}
                   :env (:env frame)
                   :stack new-stack))
          :resume-val
          (let [resume-val val
                parked-id (:parked-id frame)]
            (engine/resume-continuation vm
                                        parked-id
                                        resume-val
                                        (fn [new-state parked rv]
                                          (assoc new-state
                                                 :stack (:stack parked)
                                                 :env (:env parked)
                                                 :control {:type :value,
                                                           :val rv}))))
          ;; Default
          (throw (ex-info "Unknown frame type" {:frame frame})))))))


(defn handle-node-eval
  [vm]
  (let [{:keys [control env stack datoms index store primitives]} vm
        node-id (:id control)
        node-arr ^objects (get index node-id)]
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
                     :stack (conj stack
                                  {:type :if,
                                   :consequent (aget node-arr ATTR_CONSEQUENT),
                                   :alternate (aget node-arr ATTR_ALTERNATE),
                                   :env env}))
          :application (assoc vm
                              :control {:type :node, :id (aget node-arr ATTR_OPERATOR)}
                              :stack (conj stack
                                           {:type :app-op,
                                            :operands (aget node-arr ATTR_OPERANDS),
                                            :env env,
                                            :tail? (aget node-arr ATTR_TAIL)}))
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
                               :stack (conj stack
                                            {:type :stream-put-target,
                                             :val-node (aget node-arr ATTR_VAL_NODE),
                                             :env env})))
          :stream/cursor (let [source-node (aget node-arr ATTR_SOURCE)]
                           (assoc vm
                                  :control {:type :node, :id source-node}
                                  :stack (conj stack
                                               {:type :stream-cursor-source, :env env})))
          :stream/next (let [source-node (aget node-arr ATTR_SOURCE)]
                         (assoc vm
                                :control {:type :node, :id source-node}
                                :stack (conj stack
                                             {:type :stream-next-cursor, :env env})))
          :stream/close (let [source-node (aget node-arr ATTR_SOURCE)]
                          (assoc vm
                                 :control {:type :node, :id source-node}
                                 :stack (conj stack
                                              {:type :stream-close-source, :env env})))
          ;; Continuation primitives
          :vm/park (-> (engine/park-continuation vm {:stack stack, :env env})
                       (assoc :control nil))
          :vm/resume (let [parked-id (aget node-arr ATTR_PARKED_ID)
                           val-node (aget node-arr ATTR_VAL_NODE)]
                       (assoc vm
                              :control {:type :node, :id val-node}
                              :stack (conj stack
                                           {:type :resume-val,
                                            :parked-id parked-id,
                                            :env env})))
          :vm/current-continuation
          (assoc vm
                 :control {:type :value,
                           :val {:type :reified-continuation, :stack stack, :env env}})
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


(defn- fast-semantic-stack->map
  [stack]
  (if (some vector? stack)
    (mapv fast-semantic-frame->map stack)
    stack))


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
                                         :stack (:stack entry)
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
  (let [datom-index (group-by first datoms)
        node-map-index (into {} (map (fn [[eid _datoms]] [eid (datom-node-attrs datom-index eid)])
                                     datom-index))
        merged-index (merge (:index vm) node-map-index)
        cardinality (count merged-index)
        [index-base-id index-arr] (build-semantic-node-index-array merged-index cardinality)]
    (assoc vm
           :control {:type :node, :id node}
           :stack []
           :datoms (into (:datoms vm) datoms)
           :index merged-index
           :index-arr index-arr
           :index-base-id index-base-id
           :halted false
           :value nil
           :blocked false)))


(defn- semantic-run-active-continuation
  "Hot loop that keeps CES state in JVM locals.
   Uses a flat Object array for the stack to avoid vector allocations."
  [^SemanticVM vm-init control-init env-init stack-init]
  (let [index (:index vm-init)
        index-arr (:index-arr vm-init)
        index-base-id (int (:index-base-id vm-init))
        primitives (:primitives vm-init)
        datoms (:datoms vm-init)

        ;; Flat stack array with grow support
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

        copy-stack! (fn [ext-stack]
                      (let [n (count ext-stack)]
                        (ensure-capacity! (dec n))
                        (loop [i 0]
                          (when (< i n)
                            (semantic-object-array-set! @v-s-arr i (nth ext-stack i))
                            (recur (inc i))))
                        (dec n)))

        materialize-stack (fn [sp]
                            (let [n (inc sp)]
                              (loop [i 0
                                     acc (transient [])]
                                (if (< i n)
                                  (recur (inc i) (conj! acc (fast-semantic-frame->map (semantic-object-array-get @v-s-arr i))))
                                  (persistent! acc)))))]

    ;; Pre-fill stack-arr if resuming from non-empty stack
    (let [sp (if (empty? stack-init) -1 (copy-stack! stack-init))]
      (loop [ctrl-tag (:type control-init)
             ctrl-data (if (= :value (:type control-init))
                         (:val control-init)
                         (:id control-init))
             env env-init
             sp (int sp) ; stack pointer
             vm vm-init]
        (case ctrl-tag
          ;; --- 1. Handle Return Value ---
          :value
          (let [val ctrl-data]
            (if (< sp 0)
              (semantic-return vm {:type :value, :val val} env [] true val)
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
                                                           (materialize-stack sp)
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
                                                           (materialize-stack sp)
                                                           false
                                                           val)
                                    park-stack (materialize-stack new-sp)
                                    {:keys [state value blocked?]}
                                    (engine/handle-effect
                                      state
                                      result
                                      {:park-entry-fns
                                       {:stream/put (fn [_s e r]
                                                      {:stack park-stack,
                                                       :env env-call,
                                                       :reason :put,
                                                       :stream-id (:stream-id r),
                                                       :datom (:val e)}),
                                        :stream/next (fn [_s _e r]
                                                       {:stack park-stack,
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
                                                 (materialize-stack sp)
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
                              next-stack (:stack next)
                              next-sp (copy-stack! next-stack)]
                          (recur next-tag next-data (:env next) next-sp next)))))

                  ;; Map-based frame fallback
                  (let [state (semantic-return vm
                                               {:type :value, :val val}
                                               env
                                               (materialize-stack sp)
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
                            next-stack (:stack next)
                            next-sp (copy-stack! next-stack)]
                        (recur next-tag next-data (:env next) next-sp next))))))))

          ;; --- 2. Handle Node Evaluation ---
          :node
          (let [node-id ctrl-data
                idx (unchecked-subtract-int (int node-id) index-base-id)
                arr-len (semantic-object-array-length index-arr)
                node-arr (if (and (<= 0 idx) (< idx arr-len))
                           (semantic-object-array-get index-arr idx)
                           (get index node-id))]
            (if (nil? node-arr)
              (throw (ex-info "Unknown node id in semantic hot loop"
                              {:node-id node-id,
                               :index-base-id index-base-id,
                               :index-arr-length arr-len}))
              (let [node-arr ^objects node-arr
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

                  ;; Fallback for VM primitives and stream ops
                  (let [state (semantic-return vm
                                               {:type :node, :id node-id}
                                               env
                                               (materialize-stack sp)
                                               false
                                               nil)
                        next (handle-node-eval state)]
                    (if (or (:blocked next) (nil? (:control next)))
                      next
                      (let [next-control (:control next)
                            next-tag (:type next-control)
                            next-data (if (= :value next-tag)
                                        (:val next-control)
                                        (:id next-control))
                            next-stack (:stack next)
                            next-sp (copy-stack! next-stack)]
                        (recur next-tag next-data (:env next) next-sp next))))))))

          ;; --- 3. Exit: Halted, Blocked, or Scheduler ---
          (let [control (case ctrl-tag
                          :value {:type :value, :val ctrl-data}
                          :node {:type :node, :id ctrl-data}
                          nil)
                result (semantic-return vm control env (materialize-stack sp) false nil)]
            (cond
              (:blocked result) result
              (seq (or (:run-queue result) []))
              (if-let [resumed (resume-from-run-queue result)]
                (let [resumed-control (:control resumed)
                      resumed-tag (:type resumed-control)
                      resumed-data (if (= :value resumed-tag)
                                     (:val resumed-control)
                                     (:id resumed-control))
                      resumed-stack (:stack resumed)
                      resumed-sp (copy-stack! resumed-stack)]
                  (recur resumed-tag resumed-data (:env resumed) resumed-sp resumed))
                result)
              :else result)))))))


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
    (if (or (:blocked v) (:halted v) (seq (:run-queue v)))
      (engine/run-loop v
                       engine/active-continuation?
                       semantic-vm-step
                       resume-from-run-queue)
      (semantic-run-active-continuation v (:control v) (:env v) (:stack v)))))


(extend-type SemanticVM
  vm/IVMStep
  (step [vm] (semantic-vm-step vm))
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
  (continuation [vm] (:stack vm)))


(defn create-vm
  "Create a new SemanticVM with optional opts map.
   Accepts {:env map, :primitives map}."
  ([] (create-vm {}))
  ([opts]
   (let [env (or (:env opts) {})]
     (map->SemanticVM (merge (vm/empty-state (select-keys opts [:primitives]))
                             {:control nil,
                              :env env,
                              :stack [],
                              :datoms [],
                              :index {},
                              :index-arr (make-semantic-object-array 0),
                              :index-base-id 0,
                              :halted false,
                              :value nil,
                              :blocked false,
                              :node-id-counter -1024})))))
