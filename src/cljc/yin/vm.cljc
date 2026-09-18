(ns yin.vm
  "Canonical data model (Datoms), schema, and primitives for the Yin Abstract
   Machine on DaoStream v2.

   The shared kernel of `yin.vm` carries over unchanged. What changes is the
   stream wiring:

   - **The host supplies streams.** v1 hardcoded a ring buffer descriptor and
     called `ds/open!`. Here a composition hands the interpreter a
     `:make-stream` constructor exactly as it hands it `:primitives`. There is
     no default, because a default would smuggle the hardcoded transport back
     in: absent a supplied constructor, `:stream/make` is unsupported and says
     so.
   - **Program observation lives outside the VM.** The program stream handle,
     cursor, and gap count belong to `dao.stream.observer`; the VM never
     polls a program stream and no longer accepts `:in-stream` at
     construction. Language-level stream effects and FFI still operate their
     own streams and cursors inside the VM.
   - **Cursors are opaque.** The VM's internal cursor representation is
     `{:stream-id id :cursor <opaque>}`. There is no position and no seek.
   - **Construction is all-or-nothing.** Creating the FFI pair and minting its
     cursors are stream operations with their own outcomes. Any non-`ok`
     outcome fails construction; a VM cannot be half-built.
   - **`call-in-cursor-key` is dropped.** v1 wrote it and nothing read it."
  (:refer-clojure :exclude [eval])
  (:require [dao.datom :as datom]
            [dao.jing :as jing]
            [dao.space.query :as query]
            [dao.stream :as stream]))


;; =============================================================================
;; Protocols
;; =============================================================================
;; Common VM interfaces implemented by all execution engines.

(defprotocol IVM
  "Unified VM protocol for execution, evaluation, and observable state."

  (step
    [vm]
    "Execute one step of already-loaded work. An idle VM is returned
     unchanged; program input arrives through an attached stream observer.")

  (run
    [vm]
    "Run already-loaded work until halted or blocked.")

  (eval
    [vm ast]
    "Convert and evaluate a supplied AST. Does not drain any independently
     queued program input; explicit observer coordination does that.")

  (reset
    [vm]
    "Reset execution state to a known initial state, preserving loaded program.")

  (halted?
    [vm]
    "Returns true if VM has halted (completed or error).")

  (blocked?
    [vm]
    "Returns true if VM is blocked waiting for external input.")

  (value
    [vm]
    "Returns the current result value, or nil if not yet computed."))


(defprotocol IVMState
  "CESK state accessor protocol.
   Exposes the four components of the CESK machine model.
   Representations are VM-specific but the structure is universal."

  (control
    [vm]
    "Returns the current control state (what is being evaluated).
     VM-specific: AST node, instruction pointer, node ID, etc.")

  (environment
    [vm]
    "Returns the current lexical environment (variable bindings).")

  (store
    [vm]
    "Returns the current store (heap/global state).")

  (continuation
    [vm]
    "Returns the current continuation (what to do next).
     VM-specific: linked frames, stack vector, call-stack, etc."))


;; Primitive operations
;; Arithmetic and comparison ops are direct clojure.core references.
;; Wrapped only where VM semantics require it:
;; - rest: clojure.core/rest returns a lazy seq; wrapped to return a vector
;;   so that conj appends rather than prepends.
;; - /: only the JVM throws on an integral zero divisor; JS and Dart divide
;;   IEEE-754 and yield Infinity, so checked-divide makes the error
;;   host-uniform.
;; - yin/def, require: return effect descriptors consumed by the engine.
(defn- checked-divide
  "clojure.core `/` except a zero divisor throws on every host.

   JVM `/` throws `Divide by zero` for integral division only; JS and Dart
   yield Infinity, so `(/ 1 0)` through the VM silently evaluated to `##Inf`
   on those hosts. The REPL corpus pins divide-by-zero as an error with this
   text on both evaluators (docs/design/yin.vm.divergence-register.md).
   JS and Dart cannot distinguish `0` from `0.0`, so no rule reproduces the
   JVM's double-division `##Inf` cross-host; the uniform error is the
   contract."
  ([x] (checked-divide 1 x))
  ([x y]
   (when (and (number? y) (zero? y))
     (throw (ex-info "Divide by zero" {:divisor y})))
   (/ x y))
  ([x y & more] (reduce checked-divide (checked-divide x y) more)))


(defn primitive-profile
  "Build UCF §7.5.2's published profile record for `name`.

   The address covers the name and all four semantic declarations. The name
   remains the registry key rather than being duplicated in the returned
   record. Profile values contain no host function objects and are therefore
   ordinary portable data."
  [name class arities effects host-state]
  (let [description {:yin.k/name name,
                     :yin.k/class class,
                     :yin.k/arities (vec arities),
                     :yin.k/effects (set effects),
                     :yin.k/host-state host-state}]
    {:yin.k/profile (keyword "yin.k.pp"
                             (str "sha256-" (jing/content-hash description))),
     :yin.k/class class,
     :yin.k/arities (vec arities),
     :yin.k/effects (set effects),
     :yin.k/host-state host-state}))


(defn primitive-function
  "Return the executable value published by a primitive registry entry.

   Bare entries remain supported for composition-supplied legacy registries."
  [entry]
  (if (and (map? entry) (contains? entry :yin.k/function))
    (:yin.k/function entry)
    entry))


(defn- primitive-entry
  [name function class arities effects]
  (assoc (primitive-profile name class arities effects :none)
         :yin.k/function function))


(def primitives
  "The standard executable primitive registry, published with UCF §7.5.2
   profiles. `:yin.k/function` is host-local; the five profile keys are the
   portable publication. `:variadic` means the greatest listed fixed arity
   extends to further arguments."
  (let [pure (fn [name function arities]
               [name (primitive-entry name function :pure arities #{})])]
    (into {}
          [(pure '+ + [0 1 2 :variadic])
           (pure '- - [1 2 :variadic])
           (pure '* * [0 1 2 :variadic])
           (pure '/ checked-divide [1 2 :variadic])
           (pure '= = [0 1 2 :variadic])
           (pure '== = [0 1 2 :variadic])
           (pure '!= not= [1 2 :variadic])
           (pure '< < [1 2 :variadic])
           (pure '> > [1 2 :variadic])
           (pure '<= <= [1 2 :variadic])
           (pure '>= >= [1 2 :variadic])
           (pure 'not not [1])
           (pure 'nil? nil? [1])
           (pure 'empty? empty? [1])
           (pure 'first first [1])
           (pure 'rest (fn [a] (vec (rest a))) [1])
           (pure 'conj conj [1 2 :variadic])
           (pure 'assoc assoc [3 :variadic])
           (pure 'get get [2 3])
           (pure 'vec vec [1])
           (pure 'bytes->str
                 #?(:clj (fn
                           ([b] (String. ^bytes b "UTF-8"))
                           ([b enc] (String. ^bytes b ^String enc)))
                    :cljs (fn [b] (.apply js/String.fromCharCode nil b))
                    :cljd (fn [b] (dart:core/String.fromCharCodes b)))
                 [1 2])
           ['yin/def (primitive-entry
                       'yin/def
                       (fn [k v] {:effect :vm/store-put, :key k, :val v})
                       :effectful [2] #{:vm/store-put})]
           ['require (primitive-entry
                       'require
                       (fn [spec]
                         (let [ns-sym (if (vector? spec) (first spec) spec)]
                           {:effect :module/require, :module ns-sym}))
                       :effectful [1] #{:module/require})]])))


(def ^:private primitive-profile-keys
  [:yin.k/profile :yin.k/class :yin.k/arities :yin.k/effects
   :yin.k/host-state])


(def primitive-profiles
  "Portable projection of the standard registry, retained as a convenience."
  (into {} (map (fn [[name entry]]
                  [name (select-keys entry primitive-profile-keys)]))
        primitives))


(def primitive-canonical-names
  "Canonical-name declarations for intentional aliases in `primitives`.

   The table is name -> canonical name, not function -> name, so it remains
   plain portable data. `=` and `==` intentionally share one host function."
  {'== '=})


(defn profile-of
  "Return `name`'s published profile from a registry, or nil."
  [registry name]
  (when-let [entry (get registry name)]
    (when (and (map? entry) (contains? entry :yin.k/profile))
      (select-keys entry primitive-profile-keys))))


(defn name-of
  "Reverse-look up `primitive` by host-object identity.

   Returns nil when unnamed, `::ambiguous` when several names remain after
   applying `canonical-names`, or the one resolved name. The two-argument
   form declares no aliases."
  ([primitive-map primitive]
   (name-of primitive-map {} primitive))
  ([primitive-map canonical-names primitive]
   (let [names (->> primitive-map
                    (keep (fn [[name entry]]
                            (when (identical? (primitive-function entry)
                                              primitive)
                              name)))
                    (sort-by str)
                    vec)]
     (case (count names)
       0 nil
       1 (first names)
       (let [resolved (set (map #(get canonical-names % %) names))]
         (if (and (= 1 (count resolved))
                  (contains? (set names) (first resolved)))
           (first resolved)
           ::ambiguous))))))


(defn assert-primitive-reverse-lookup-unique!
  "Assert UCF §7.5.2 reverse-lookup uniqueness for an installed primitive map.

   Intentional aliases must resolve directly to one name through
   `canonical-names`. Returns the primitive map for convenient composition."
  [primitive-map canonical-names]
  (doseq [[_ entry] primitive-map
          :let [primitive (primitive-function entry)]
          :when (fn? primitive)
          :when (= ::ambiguous (name-of primitive-map canonical-names primitive))]
    (let [names (->> primitive-map
                     (keep (fn [[name candidate]]
                             (when (identical? (primitive-function candidate)
                                               primitive)
                               name)))
                     (sort-by str)
                     vec)]
      (throw (ex-info "Primitive reverse lookup is ambiguous"
                      {:rule :ambiguous-primitive,
                       :names names}))))
  primitive-map)


(def call-in-stream-key
  "Store key for the shared apply inbound request stream (caller writes, callee reads)."
  :yin/call-in)


(def call-out-stream-key
  "Store key for the shared apply outbound response stream (callee writes, caller reads)."
  :yin/call-out)


(def call-out-cursor-key
  "Store key for the shared apply outbound stream cursor."
  :yin/call-out-cursor)


(def default-call-capacity
  "Capacity of the FFI request/response pair when the composition does not
   declare one.

   This is a correctness parameter, not a tuning knob. The FFI state machine
   bounds outstanding requests to one per parked call and an already-read
   response is harmlessly evictable, so the rule is: capacity at least the
   maximum number of simultaneously parked calls, plus one. Under an
   evict-oldest transport an evicted response leaves its parked call waiting
   forever, which is why a gap at the bridge cursor is fatal rather than
   resumable."
  1024)


(def default-stream-capacity
  "Capacity for `(stream/make)` and `:stream/make` with no declared capacity.

   v1's module-path zero-arity `make` yielded `{:capacity nil}`, which meant
   unbounded. Nil capacity has no v2 meaning, so both the AST path and the
   module path apply this default and therefore agree."
  1024)


(defn create-stream!
  "Create one stream through a host-supplied constructor, or fail.

   `make-stream` is a function of a capacity returning a create outcome. Its
   outcomes are ok, invalid-spec, not-found and transport-error; anything but
   ok fails, because a half-built VM is worse than a construction error."
  [make-stream capacity what]
  (when-not (fn? make-stream)
    (throw (ex-info "This VM was constructed without :make-stream, so it cannot create streams"
                    {:what what})))
  (let [result (make-stream capacity)
        outcome (:dao.stream/outcome result)]
    (if (= :dao.stream/ok outcome)
      (:dao.stream/handle result)
      (throw (ex-info "Stream creation failed" {:what what, :outcome outcome})))))


(defn mint-oldest
  "Mint a cursor at `:dao.stream/oldest` on a handle, or fail.

   Every fabricated v1 cursor was semantically absolute position zero. That
   equals `:dao.stream/oldest` on a fresh stream but diverges from
   `:dao.stream/newest` the moment a stream has history before the cursor
   exists — and callers pre-fill streams before constructing a VM, so the
   choice is observable. Minting is a stream operation, so it carries
   `closed` and `transport-error` outcomes to handle here."
  [handle what]
  (let [result (stream/cursor handle stream/anchor-oldest)
        outcome (:dao.stream/outcome result)]
    (if (= :dao.stream/ok outcome)
      (:dao.stream/cursor result)
      (throw (ex-info "Cursor mint failed" {:what what, :outcome outcome})))))


(defn cursor-entry
  "The VM-internal cursor representation: a stream key and an opaque cursor."
  [stream-id cursor]
  {:stream-id stream-id, :cursor cursor})


;; Bytecode Opcodes
;; =============================================================================
;; Shared instruction set for stack and register bytecode VMs.

(def opcode-table
  {:literal 1,
   :load-var 2,
   :move 3,
   :lambda 4,
   :call 5,
   :return 6,
   :branch 7,
   :jump 8,
   :gensym 9,
   :store-get 10,
   :store-put 11,
   :stream-make 12,
   :stream-put 13,
   :stream-cursor 14,
   :stream-next 15,
   :stream-close 16,
   :park 17,
   :resume 18,
   :current-cont 19,
   :tailcall 20,
   :dao.stream.apply/call 21,
   :push 22,
   :halt 23})


#?(:clj
   (defmacro opcase
     "Dispatch on a numeric opcode using keyword mnemonics.
      Resolved at compile-time to a standard 'case' over integers."
     [op-expr & clauses]
     (let [default (when (odd? (count clauses)) (last clauses))
           paired (if default (butlast clauses) clauses)
           pairs (partition 2 paired)
           ;; Keep a local literal map so CLJD macro host compilation
           ;; does not depend on resolving `opcode-table` as a host
           ;; symbol.
           opcodes {:literal 1,
                    :load-var 2,
                    :move 3,
                    :lambda 4,
                    :call 5,
                    :return 6,
                    :branch 7,
                    :jump 8,
                    :gensym 9,
                    :store-get 10,
                    :store-put 11,
                    :stream-make 12,
                    :stream-put 13,
                    :stream-cursor 14,
                    :stream-next 15,
                    :stream-close 16,
                    :park 17,
                    :resume 18,
                    :current-cont 19,
                    :tailcall 20,
                    :dao.stream.apply/call 21,
                    :push 22,
                    :halt 23}
           resolved (mapcat (fn [[kw body]] [(get opcodes kw) body]) pairs)]
       `(case (int ~op-expr) ~@resolved ~@(when default [default])))))


(def schema
  "DaoDB schema for :yin/ AST datoms.
   Complete data model for the Universal AST as queryable datoms."
  {;; Ref attributes (entity references, tempid resolution)
   :yin/body {:db/valueType :db.type/ref},
   :yin/operator {:db/valueType :db.type/ref},
   :yin/operands {:db/valueType :db.type/ref,
                  :db/cardinality :db.cardinality/many},
   :yin/test {:db/valueType :db.type/ref},
   :yin/consequent {:db/valueType :db.type/ref},
   :yin/alternate {:db/valueType :db.type/ref},
   :yin/source {:db/valueType :db.type/ref},
   :yin/target {:db/valueType :db.type/ref},
   :yin/val-node {:db/valueType :db.type/ref},
   ;; Ground-value attributes. The data model uses Datomic-style schema
   ;; definitions. Value types are declared in comments for the data
   ;; model.
   :yin/type {},      ; keyword (:literal, :variable, :lambda, ...)
   :yin/value {},     ; polymorphic (number, string, boolean, keyword, ...)
   :yin/name {},      ; symbol
   :yin/op {},        ; keyword (dao.stream.apply operation key)
   :yin/params {},    ; vector of symbols
   :yin/key {},       ; symbol
   :yin/prefix {},    ; string
   :yin/buffer {},    ; long
   :yin/parked-id {}, ; keyword
   :yin/tail? {},     ; boolean (tail-position flag)
   :yin/root {},      ; boolean — marks the batch's root entity; the last
   ;; one in batch order wins (yin.vm.macro.md §2.4)
   ;; Macro attributes. Expansion events and their refs belong to the
   ;; expander's `event-schema`, not to the Universal AST.
   :yin/macro? {},    ; boolean — true if lambda is a macro
   :yin/macro-name {} ; symbol — the macro operator's name when it was a
   ;; :variable
   })


(def code-schema
  "DaoDB schema for :yin.code/ linear executable datoms.
   A segment entity plus instruction entities ordered by an explicit
   :yin.code/pc fact; see docs/design/yin.vm.semantic.md §2."
  {;; Ref attributes (entity references, tempid resolution)
   :yin.code/segment {:db/valueType :db.type/ref},      ; instruction → segment
   :yin.code/target {:db/valueType :db.type/ref},       ; branch → instruction
   :yin.code/body {:db/valueType :db.type/ref},         ; closure → entry
   :yin.code/source {:db/valueType :db.type/ref},       ; instruction → AST node
   :yin.code/derived-from {:db/valueType :db.type/ref}, ; segment → AST root
   ;; Ground-value attributes
   :yin.code/type {},      ; keyword (:segment)
   :yin.code/length {},    ; long — instruction count; pcs are 0 .. length-1
   :yin.code/hash {},      ; string — reserved content address
   :yin.code/pc {},        ; long
   :yin.code/op {},        ; keyword mnemonic (:const, :call, :halt, ...)
   :yin.code/value {},     ; polymorphic literal
   :yin.code/name {},      ; symbol
   :yin.code/params {},    ; vector of symbols
   :yin.code/argc {},      ; long
   :yin.code/tail? {},     ; boolean
   :yin.code/prefix {},    ; string
   :yin.code/key {},       ; symbol
   :yin.code/buffer {},    ; long
   :yin.code/ffi-op {},    ; keyword (dao.stream.apply operation key)
   :yin.code/parked-id {}  ; keyword
   })


(def ^:private cardinality-many-attrs
  "Attributes with :db.cardinality/many — their values are vectors of refs
   that must be expanded into individual :db/add assertions."
  #{:yin/operands})


(defn datoms->tx-data
  "Convert [e a v t m] datoms to DaoDB tx-data [:db/add e a v m].
   Expands cardinality-many vector values into individual assertions.

   DaoDB implementations like InMemoryDaoDB do not allow nil as a stored
   value, so nil-valued assertions are omitted in this index projection.
   The canonical datom stream remains unchanged and still carries the
   original nil facts."
  [datoms]
  (mapcat (fn [[e a v _t m]]
            (cond (and (contains? cardinality-many-attrs a) (vector? v))
                  (->> v
                       (remove nil?)
                       (map (fn [ref] [:db/add e a ref m])))
                  (nil? v) []
                  :else [[:db/add e a v m]]))
          datoms))


(defn ast->datoms-with-root
  "Convert AST map into a vector of datoms. A datom is [e a v t m].
   Returns [root-id datoms].

   The last datom is always `[root-id :yin/root true t m]`, so `index-datoms`
   finds the root without depending on emission order.

   Entity IDs are tempids (negative integers: -16, -17, -18...) that get resolved
   to actual entity IDs when transacted. The transactor assigns real positive IDs.

   Options:
     :t - transaction ID (default 0)
     :m - metadata entity reference (default assert; see dao.datom/reserved)
     :id-start - starting entity ID for tempids (default (- datom/first-user-id))"
  ([ast] (ast->datoms-with-root ast {}))
  ([ast opts]
   (let [id-counter (atom (or (:id-start opts) (- datom/first-user-id)))
         t (or (:t opts) 0)
         m (or (:m opts) datom/default-op)
         gen-id #(swap! id-counter dec)
         datoms (atom [])
         emit! (fn [e attr val] (swap! datoms conj [e attr val t m]))
         ;; Track pre-assigned EIDs already emitted so that a shared
         ;; lambda-ast
         ;; (same :eid in definition operand and call-site operator) is
         ;; processed exactly once; every reference resolves to the same
         ;; EID.
         seen-eids (atom #{})]
     (letfn
       [(convert
          [node]
          (let [pre-eid (:eid node)
                e (or pre-eid (gen-id))
                {:keys [type tail?]} node]
            (if (and pre-eid (contains? @seen-eids pre-eid))
              ;; Shared reference already emitted — return EID without
              ;; re-emitting.
              e
              (do
                (when pre-eid (swap! seen-eids conj pre-eid))
                (when tail? (emit! e :yin/tail? true))
                (case type
                  :literal (do (emit! e :yin/type :literal)
                               (emit! e :yin/value (:value node)))
                  :variable (do (emit! e :yin/type :variable)
                                (emit! e :yin/name (:name node)))
                  :lambda (do (emit! e :yin/type :lambda)
                              (when (:macro? node)
                                (emit! e :yin/macro? true))
                              (emit! e :yin/params (:params node))
                              (let [body-id (convert (:body node))]
                                (emit! e :yin/body body-id)))
                  :application (do (emit! e :yin/type :application)
                                   (let [op-id (convert (:operator node))
                                         operand-ids (mapv convert
                                                           (:operands node))]
                                     (emit! e :yin/operator op-id)
                                     (emit! e :yin/operands operand-ids)))
                  :dao.stream.apply/call
                  (do (emit! e :yin/type :dao.stream.apply/call)
                      (emit! e :yin/op (:op node))
                      (let [operand-ids (mapv convert (:operands node))]
                        (emit! e :yin/operands operand-ids)))
                  :if (do (emit! e :yin/type :if)
                          (let [test-id (convert (:test node))
                                cons-id (convert (:consequent node))
                                alt-id (convert (:alternate node))]
                            (emit! e :yin/test test-id)
                            (emit! e :yin/consequent cons-id)
                            (emit! e :yin/alternate alt-id)))
                  ;; VM primitives
                  :vm/gensym (do (emit! e :yin/type :vm/gensym)
                                 (emit! e :yin/prefix (or (:prefix node) "id")))
                  :vm/store-get (do (emit! e :yin/type :vm/store-get)
                                    (emit! e :yin/key (:key node)))
                  :vm/store-put (do (emit! e :yin/type :vm/store-put)
                                    (emit! e :yin/key (:key node))
                                    (emit! e :yin/value (:val node)))
                  ;; Stream operations
                  :stream/make (do
                                 (emit! e :yin/type :stream/make)
                                 (emit! e :yin/buffer (or (:buffer node) 1024)))
                  :stream/put (do (emit! e :yin/type :stream/put)
                                  (let [target-id (convert (:target node))
                                        val-id (convert (:val node))]
                                    (emit! e :yin/target target-id)
                                    (emit! e :yin/val-node val-id)))
                  :stream/cursor (do (emit! e :yin/type :stream/cursor)
                                     (let [source-id (convert (:source node))]
                                       (emit! e :yin/source source-id)))
                  :stream/next (do (emit! e :yin/type :stream/next)
                                   (let [source-id (convert (:source node))]
                                     (emit! e :yin/source source-id)))
                  :stream/close (do (emit! e :yin/type :stream/close)
                                    (let [source-id (convert (:source node))]
                                      (emit! e :yin/source source-id)))
                  ;; Continuation primitives
                  :vm/park (emit! e :yin/type :vm/park)
                  :vm/resume (do (emit! e :yin/type :vm/resume)
                                 (emit! e :yin/parked-id (:parked-id node))
                                 (let [val-id (convert (:val node))]
                                   (emit! e :yin/val-node val-id)))
                  :vm/current-continuation
                  (emit! e :yin/type :vm/current-continuation)
                  ;; Default
                  (throw (ex-info "Unknown AST node type"
                                  {:type type, :node node})))
                e))))]
       (let [root-id (convert ast)]
         (emit! root-id :yin/root true)
         [root-id @datoms])))))


(defn ast->datoms
  "Convert AST map into a vector of datoms. A datom is [e a v t m]."
  ([ast] (ast->datoms ast {}))
  ([ast opts] (second (ast->datoms-with-root ast opts))))


(declare index-datoms)


(defn datoms->ast
  "Convert a vector of datoms [e a v t m] back into an AST map."
  ([datoms]
   (let [indexed (index-datoms datoms)
         root-id (:root-id indexed)]
     (datoms->ast datoms {:indexed indexed, :root-id root-id})))
  ([datoms {:keys [indexed root-id]}]
   (let [{:keys [get-attr]} indexed
         node-type (get-attr root-id :yin/type)
         tail? (get-attr root-id :yin/tail?)
         base (cond-> {:type node-type} tail? (assoc :tail? true))
         recur-ast (fn [eid]
                     (datoms->ast datoms {:indexed indexed, :root-id eid}))]
     (case node-type
       :literal (assoc base :value (get-attr root-id :yin/value))
       :variable (assoc base :name (get-attr root-id :yin/name))
       :lambda (let [body-eid (get-attr root-id :yin/body)
                     macro? (get-attr root-id :yin/macro?)]
                 (cond-> (assoc base
                                :params (get-attr root-id :yin/params)
                                :body (recur-ast body-eid))
                   macro? (assoc :macro? true)))
       :application (let [op-eid (get-attr root-id :yin/operator)
                          operand-eids (get-attr root-id :yin/operands)]
                      ;; §2.4 saturation, applied by the loader: the codec
                      ;; emits only `true`, so an application written without
                      ;; the mark and one stating :tail? false are one value,
                      ;; and this loader and the row loader (§7.2 part 2)
                      ;; yield the same :program
                      (assoc base
                             :tail? (boolean tail?)
                             :operator (recur-ast op-eid)
                             :operands (mapv recur-ast operand-eids)))
       :dao.stream.apply/call (let [op (get-attr root-id :yin/op)
                                    operand-eids (get-attr root-id
                                                           :yin/operands)]
                                (assoc base
                                       :op op
                                       :operands (mapv recur-ast operand-eids)))
       :if (let [test-eid (get-attr root-id :yin/test)
                 cons-eid (get-attr root-id :yin/consequent)
                 alt-eid (get-attr root-id :yin/alternate)]
             (assoc base
                    :test (recur-ast test-eid)
                    :consequent (recur-ast cons-eid)
                    :alternate (recur-ast alt-eid)))
       :vm/gensym (assoc base :prefix (get-attr root-id :yin/prefix))
       :vm/store-get (assoc base :key (get-attr root-id :yin/key))
       :vm/store-put (assoc base
                            :key (get-attr root-id :yin/key)
                            :val (get-attr root-id :yin/value))
       :stream/make (assoc base :buffer (get-attr root-id :yin/buffer))
       :stream/put (let [target-eid (get-attr root-id :yin/target)
                         val-eid (get-attr root-id :yin/val-node)]
                     (assoc base
                            :target (recur-ast target-eid)
                            :val (recur-ast val-eid)))
       :stream/cursor (assoc base
                             :source (recur-ast (get-attr root-id :yin/source)))
       :stream/next (assoc base
                           :source (recur-ast (get-attr root-id :yin/source)))
       :stream/close (assoc base
                            :source (recur-ast (get-attr root-id :yin/source)))
       :vm/park base
       :vm/resume (let [val-eid (get-attr root-id :yin/val-node)]
                    (assoc base
                           :parked-id (get-attr root-id :yin/parked-id)
                           :val (recur-ast val-eid)))
       :vm/current-continuation base
       ;; fallback
       (throw (ex-info "Unknown AST node type in datoms"
                       {:type node-type, :root-id root-id}))))))


(defn plain-data?
  "True when `x` is data a row or code datom may carry (§2.2, §2.5): scalars
   and collections of them, never a host function or object. Metadata
   travels with the value, so it must be plain data too."
  [x]
  (or (nil? x)
      (and (cond (or (boolean? x) (number? x) (string? x) (keyword? x)
                     (symbol? x))
                 true
                 (map? x) (and (every? plain-data? (keys x))
                               (every? plain-data? (vals x)))
                 (coll? x) (every? plain-data? x)
                 :else false)
           (plain-data? (meta x)))))


(defn occurrence-origin?
  "True when `origin` has one of §2.5's portable ruled shapes. `nil` is not
   an occurrence; it is accepted separately by the legacy bare-row lane.

   A source origin is `[:source medium batch-token member-index]`. Medium and
   token are opaque non-nil plain data supplied by the admitting composition,
   and the member index is a non-negative integer. An expansion origin names
   the portable content address of its expansion record."
  [origin]
  (and (vector? origin)
       (case (first origin)
         :source (and (= 4 (count origin))
                      (some? (nth origin 1))
                      (plain-data? (nth origin 1))
                      (some? (nth origin 2))
                      (plain-data? (nth origin 2))
                      (integer? (nth origin 3))
                      (not (neg? (nth origin 3))))
         :expansion (and (= 2 (count origin))
                         (jing/segment-address? (nth origin 1)))
         false)))


(defn source-origin
  "Construct and validate the source occurrence coordinate owned by a batch
   envelope. Throws with `:rule :origin` instead of allowing an incomplete
   coordinate to enter a provenance or frontend side table."
  [medium batch-token member-index]
  (let [origin [:source medium batch-token member-index]]
    (when-not (occurrence-origin? origin)
      (throw (ex-info "Malformed source occurrence origin"
                      {:rule :origin, :origin origin})))
    origin))


(defn loaded-code-floor
  "The lowest entity id claimed by the segments in a `{segment-id image}`
   code map — each segment id minus its length — or 0 when none is loaded.
   A loader minting a fresh local segment id allocates below this floor
   (`linearize/ast-loader` for lowered AST batches, `semantic/load-vector`
   for canonical instruction vectors), so successive loads never collide
   with a live id."
  [code]
  (if (map? code)
    (reduce-kv (fn [floor seg image]
                 (if (and (integer? seg) (map? image))
                   (min floor (- seg (or (:length image) 0)))
                   floor))
               0
               code)
    0))


;; =============================================================================
;; Semantic bytecode: flat content-addressed rows
;; =============================================================================
;; docs/design/yin.vm.code-as-tuples.md §2. One row `[id tag & slots]` per
;; distinct node; `id` is `(jing/segment-key [tag & slots])`.

(def semantic-bytecode-grammar
  "The §2.3 tag table: tag -> ordered `[field kind]` slots. Position `i` of a
   row body (after the tag) holds the map field named at `i`, so this one
   table drives both projection and reconstruction."
  {:literal [[:value :data]],
   :variable [[:name :sym]],
   :lambda [[:params :syms] [:body :node]],
   :application [[:operator :node] [:operands :nodes] [:tail? :bool]],
   :if [[:test :node] [:consequent :node] [:alternate :node]],
   :dao.stream.apply/call [[:op :kw] [:operands :nodes]],
   :vm/gensym [[:prefix :str]],
   :vm/store-get [[:key :key]],
   :vm/store-put [[:key :key] [:val :data]],
   :vm/current-continuation [],
   :vm/park [],
   :vm/resume [[:parked-id :kw] [:val :node]],
   :stream/make [[:buffer :int]],
   :stream/put [[:target :node] [:val :node]],
   :stream/cursor [[:source :node]],
   :stream/next [[:source :node]],
   :stream/close [[:source :node]]})


(def ^:private semantic-bytecode-defaults
  "§2.4 saturation: `[tag field]` -> the default written map→rows. rows→map
   never re-defaults; a nil in one of these slots is a defect."
  {[:vm/gensym :prefix] "id",
   [:stream/make :buffer] default-stream-capacity,
   [:application :tail?] false,
   [:application :operands] [],
   [:dao.stream.apply/call :operands] []})


(defn- strip-symbol-meta
  "§2.5: reader metadata on names is frontend metadata, not content."
  [x]
  (if (symbol? x) (with-meta x nil) x))


(defn- strip-reader-positions
  "§2.5: reader source positions are provenance, not content. Removes
   `:line`/`:column`/`:end-line`/`:end-column` from metadata at every depth
   of a `data`/`key` payload, including inside metadata maps and their own
   metadata, keeping all other metadata and every collection
   type. `dao.jing` already leaves these keys out of the address; this keeps
   them out of the row body too."
  [x]
  (let [x' (cond (record? x) x
                 (map? x) (into (empty x)
                                (map (fn [[k v]]
                                       [(strip-reader-positions k)
                                        (strip-reader-positions v)]))
                                x)
                 (set? x) (into (empty x) (map strip-reader-positions) x)
                 ;; not (into (empty x) ...): a map entry is vector? but its
                 ;; empty is nil, which would rebuild it as a reversed list
                 (vector? x) (mapv strip-reader-positions x)
                 ;; with-meta nil: ClojureDart's list mints its result with
                 ;; cljd.core's own reader metadata, which is not content
                 (sequential? x) (with-meta
                                   (apply list (map strip-reader-positions x))
                                   nil)
                 :else x)
        m (meta x)]
    (if m
      ;; metadata is itself a value: its entries and its own metadata may
      ;; carry reader positions too
      (let [m' (strip-reader-positions
                 (dissoc m :line :column :end-line :end-column))]
        ;; not (not-empty m'): an entry-less map may still carry metadata
        (with-meta x' (when-not (and (empty? m') (nil? (meta m'))) m')))
      x')))


(defn- same-meta?
  "True when the `=` values `a` and `b` also carry `=` metadata at every
   depth, including inside metadata itself (a metadata value, or a metadata
   map's own metadata, compares through `same-meta?` too). `=` alone ignores
   metadata.

   Scope boundary: the set branch assumes ordinary set semantics, at most one
   element per `=`-equivalence class; a set built with a comparator that
   admits several `=`-equal, metadata-distinct elements is out of scope, the
   same class of pathological-input residual `dao.jing.md` documents for its
   own encoder (pathological symbols, scalar metadata). That deferred case is
   silent and order-dependent, not reliably fail-closed: which `=`-equal
   element the lookup picks depends on traversal order, so a metadata
   mismatch may throw or may be silently accepted."
  [a b]
  (and (= (meta a) (meta b))
       (or (nil? (meta a)) (same-meta? (meta a) (meta b)))
       (cond (map? a) (every? (fn [[k v]]
                                (let [[k' v'] (find b k)]
                                  (and (same-meta? k k') (same-meta? v v'))))
                              a)
             (set? a) (every? (fn [e]
                                (same-meta? e (some #(when (= e %) %) b)))
                              a)
             (sequential? a) (every? true? (map same-meta? a b))
             :else true)))


(defn ast->semantic-bytecode
  "Project a map AST to flat content-addressed rows (§6.1 codec boundary
   projection). Returns `{:root row-id, :rows {row-id [row-id tag & slots]}}`.

   Only the §2.3 slots of each node enter its row: `:eid`, `:macro?`,
   `:yang/*` keys, non-`:application` `:tail?` marks, and any other key are
   dropped. `sym`/`syms` slots lose all metadata; `data`/`key` payloads lose
   reader positions at every depth and keep all other metadata. Defaults are
   saturated per §2.4. Children are projected first, so a parent's body
   commits to its children's ids; structurally identical subtrees mint one
   id and are stored as one row (§4.4).

   Known limitation, inherited from `dao.jing` and not fixed here: the
   transitional encoder does not hash metadata on scalars, so two payloads
   differing only in retained symbol metadata (e.g. `^{:meaning 1} x` and
   `^{:meaning 2} x` as literal values) mint one address. Such a collision
   throws `\"Semantic bytecode address collision\"` rather than letting one
   row's metadata silently replace the other's. It disappears when
   `dao.jing`'s encoding covers scalar metadata."
  [ast]
  (let [rows (atom {})]
    (letfn
      [(slot-value
         [tag node [field kind]]
         (let [v (get node field)
               v (if (nil? v) (get semantic-bytecode-defaults [tag field]) v)]
           (case kind
             :node (convert v)
             :nodes (mapv convert v)
             :sym (strip-symbol-meta v)
             :syms (mapv strip-symbol-meta v)
             :bool (boolean v)
             (:data :key) (strip-reader-positions v)
             v)))
       (convert
         [node]
         (let [tag (:type node)
               slots (or (get semantic-bytecode-grammar tag)
                         (throw (ex-info "Unknown AST node type"
                                         {:type tag, :node node})))
               body (into [tag] (map #(slot-value tag node %)) slots)
               id (jing/segment-key body)
               row (into [id] body)
               prior (get @rows id)]
           (when (and prior
                      (not (and (= prior row) (same-meta? prior row))))
             (throw (ex-info "Semantic bytecode address collision"
                             {:id id, :row row, :prior prior})))
           (swap! rows assoc id row)
           id))]
      (let [root (convert ast)]
        {:root root, :rows @rows}))))


(def ^:private source-position-keys
  #{:file :line :column :end-line :end-column})


(defn- reader-position
  [node]
  (let [source (:yang/source-position node)
        m (meta node)
        value (merge (select-keys m source-position-keys)
                     (select-keys node source-position-keys)
                     (when (map? source) source))]
    (when (some #(contains? value %) [:file :line :column])
      value)))


(defn- frontend-meta-entries
  "Ordered `[key value]` entries attached to one Universal AST node. Yang
   keys and non-position map metadata are node facts. Reader metadata on a
   name or parameter is qualified by its semantic field so two parameters
   cannot silently claim the same key at one lambda occurrence."
  [tag node]
  (let [slots (get semantic-bytecode-grammar tag)
        node-meta (apply dissoc (or (meta node) {}) source-position-keys)
        yang (for [[k v] node
                   :when (and (= "yang" (namespace k))
                              (not= :yang/source-position k))]
               [k v])
        own (concat yang
                    (sort-by (comp pr-str key) node-meta))
        names (mapcat
                (fn [[field kind]]
                  (let [value (get node field)]
                    (case kind
                      :sym (for [[k v] (sort-by (comp pr-str key)
                                                (apply dissoc (or (meta value) {})
                                                       source-position-keys))]
                             [[field k] v])
                      :syms (mapcat
                              (fn [i sym]
                                (for [[k v] (sort-by (comp pr-str key)
                                                     (apply dissoc (or (meta sym) {})
                                                            source-position-keys))]
                                  [[field i k] v]))
                              (range) value)
                      nil)))
                slots)]
    (concat own names)))


(defn ast-side-tables
  "Project occurrence-owned frontend facts from a map AST without putting
   them in canonical rows. Returns the §2.5 relations
   `{:source-positions [[origin root path file line col] ...]
     :frontend-metadata [[origin root path key value] ...]}`.

   `bc` must be the projection of `ast`; its root makes both relations join
   the canonical row and occurrence relations. Structural paths use the same
   row positions as `occurrences` and lowering."
  [ast bc origin]
  (when-not (occurrence-origin? origin)
    (throw (ex-info "Side tables require a ruled occurrence origin"
                    {:rule :origin, :origin origin})))
  (let [root (:root bc)
        positions (atom [])
        metadata (atom [])]
    (letfn [(visit
              [node path]
              (let [tag (:type node)
                    slots (or (get semantic-bytecode-grammar tag)
                              (throw (ex-info "Unknown AST node type"
                                              {:type tag, :node node})))
                    position (reader-position node)]
                (when position
                  (swap! positions conj
                         [origin root path (:file position) (:line position)
                          (:column position)]))
                (doseq [[k v] (frontend-meta-entries tag node)]
                  (when-not (and (plain-data? k) (plain-data? v))
                    (throw (ex-info "Frontend metadata is not plain data"
                                    {:rule :frontend-metadata, :path path,
                                     :key k, :value v})))
                  (swap! metadata conj [origin root path k v]))
                (doseq [[j [field kind]] (map-indexed vector slots)
                        :let [pos (+ j 2)
                              value (get node field)]]
                  (case kind
                    :node (visit value (conj path pos))
                    :nodes (doseq [[i child] (map-indexed vector value)]
                             (visit child (conj path [pos i])))
                    nil))))]
      (visit ast [])
      {:source-positions @positions, :frontend-metadata @metadata})))


(defn- semantic-bytecode-slot-kind-ok?
  "§2.2/§7.4 `:slot-kind`: whether `v` conforms to slot `kind`."
  [kind v]
  (case kind
    :node (jing/segment-address? v)
    :nodes (and (vector? v) (nil? (meta v)) (every? jing/segment-address? v))
    (:data :key) (plain-data? v)
    :sym (symbol? v)
    :syms (and (vector? v) (nil? (meta v)) (every? symbol? v))
    :kw (keyword? v)
    :str (string? v)
    :int (and (integer? v) (not (neg? v)))
    :bool (or (true? v) (false? v))))


(defn- semantic-bytecode-row-tag
  [row]
  (when (and (vector? row) (<= 2 (count row)))
    (nth row 1)))


(defn- semantic-bytecode-child-refs
  "`[[path-suffix child-id] ..]` for every `node`/`nodes` slot of a row
   whose `tag` and `body` already passed `:arity`, in slot order; a `node`
   slot's suffix is its slot index, a `nodes` slot's suffix is `[slot-index
   item-index]` (§2.5)."
  [tag body]
  (let [slots (get semantic-bytecode-grammar tag)]
    (mapcat (fn [i [_ kind] v]
              (case kind
                :node [[[i] v]]
                :nodes (map-indexed (fn [j cid] [[[i j] cid]]) v)
                nil))
            (range 1 (inc (count slots))) slots (rest body))))


(defn validate-rows
  "§7.4: validate a projected row set before reconstruction. `rows` is
   `{id [id tag & slots]}`; `root` is the root row's id.

   Returns nil when every row is well-formed, else the first defect as
   `{:rule r :path p}`, or `{:rule :root-reachable :id id}` for a row of the
   loaded set nothing reaches. Rules run in §7.4's order -- `:tag`,
   `:arity`, `:slot-kind`, `:saturation`, `:id-resolves`, `:acyclic`,
   `:root-reachable` -- discovered by descending from the root row, so a
   path is always available except for `:root-reachable`'s unreached rows.
   Each rule assumes the earlier ones held."
  [{:keys [root rows]}]
  (let [safe-tag (fn [row] (when (and (vector? row) (<= 2 (count row))) (nth row 1)))
        safe-slots (fn [tag] (get semantic-bytecode-grammar tag))
        safe-children (fn [id]
                        (let [row (get rows id)]
                          (if row
                            (let [tag (safe-tag row)
                                  slots (safe-slots tag)]
                              (if (and slots (= (count slots) (dec (count (subvec row 1)))))
                                (mapcat (fn [i [_ kind] v]
                                          (case kind
                                            :node (if (semantic-bytecode-slot-kind-ok? kind v) [[[i] v]] [])
                                            :nodes (if (semantic-bytecode-slot-kind-ok? kind v)
                                                     (map-indexed (fn [j cid] [[i j] cid]) v)
                                                     [])
                                            nil))
                                        (range 1 (inc (count slots))) slots (rest (subvec row 1)))
                                []))
                            [])))
        paths (loop [queue [[root []]]
                     q-idx 0
                     visited {root []}]
                (if (= q-idx (count queue))
                  visited
                  (let [[id path] (nth queue q-idx)
                        children (safe-children id)
                        unvisited (remove #(contains? visited (second %)) children)
                        new-visited (into {} (map (fn [[suffix cid]] [cid (into path suffix)])) unvisited)]
                    (recur (into queue (map (fn [[suffix cid]] [cid (into path suffix)]) unvisited))
                           (inc q-idx)
                           (merge visited new-visited)))))
        defect-path (fn [id] (get paths id))
        defect-res (fn [rule id & [path-suffix]]
                     (if-let [p (defect-path id)]
                       {:rule rule :path (if path-suffix (into p path-suffix) p)}
                       {:rule rule :id id}))
        tag-defect (some (fn [[id row]]
                           (let [tag (safe-tag row)]
                             (when-not (safe-slots tag)
                               (defect-res :tag id))))
                         rows)
        arity-defect (some (fn [[id row]]
                             (let [tag (safe-tag row)
                                   slots (safe-slots tag)
                                   body (subvec row 1)]
                               (when (not= (count slots) (dec (count body)))
                                 (defect-res :arity id))))
                           rows)
        slot-kind-defect (some (fn [[id row]]
                                 (let [tag (safe-tag row)
                                       slots (safe-slots tag)
                                       body (subvec row 1)]
                                   (some identity
                                         (map (fn [i [field kind] v]
                                                (when-not (or (semantic-bytecode-slot-kind-ok? kind v)
                                                              (and (nil? v) (contains? semantic-bytecode-defaults [tag field])))
                                                  (defect-res :slot-kind id [i])))
                                              (range 1 (inc (count slots))) slots (rest body)))))
                               rows)
        saturation-defect (some (fn [[id row]]
                                  (let [tag (safe-tag row)
                                        slots (safe-slots tag)
                                        body (subvec row 1)]
                                    (some identity
                                          (map (fn [i [field kind] v]
                                                 (when (and (nil? v)
                                                            (contains? semantic-bytecode-defaults [tag field]))
                                                   (defect-res :saturation id [i])))
                                               (range 1 (inc (count slots))) slots (rest body)))))
                                rows)
        id-resolves-defect (or (when-not (contains? rows root)
                                 {:rule :id-resolves :path []})
                               (some (fn [[id row]]
                                       (some (fn [[suffix cid]]
                                               (when-not (contains? rows cid)
                                                 (defect-res :id-resolves id suffix)))
                                             (safe-children id)))
                                     rows))
        acyclic-defect (letfn [(check-cycle
                                 [id on-path]
                                 (cond
                                   (contains? on-path id) (defect-res :acyclic id)
                                   :else
                                   (let [row (get rows id)]
                                     (when row
                                       (let [on-path' (conj on-path id)]
                                         (some (fn [[suffix cid]]
                                                 (check-cycle cid on-path'))
                                               (safe-children id)))))))]
                         (check-cycle root #{}))
        root-reachable-defect (some (fn [id]
                                      (when-not (contains? paths id)
                                        {:rule :root-reachable :id id}))
                                    (keys rows))]
    (or tag-defect arity-defect slot-kind-defect saturation-defect id-resolves-defect acyclic-defect root-reachable-defect)))


(defn semantic-bytecode->ast
  "Reconstruct the canonical map AST from `{:root row-id, :rows {row-id row}}`
   (§7.1 load-time reconstruction), the inverse of `ast->semantic-bytecode`.

   Calls `validate-rows` first; a defect throws `ex-info` carrying its
   `:rule` and `:path` (or `:id`). Every reached row is then rebuilt, which
   still runs its own `:shape` and `:content-address` checks -- the address
   check is not a structural grammar rule (§7.5) and stays separate. Nothing
   is re-defaulted. A row reached through several parents is rebuilt once
   and shared."
  [{:keys [root rows], :as bc}]
  (when-let [{:keys [rule path id]} (validate-rows bc)]
    (throw (ex-info "Semantic bytecode row set failed validation"
                    (cond-> {:rule rule}
                      path (assoc :path path)
                      id (assoc :id id)))))
  (let [built (atom {})]
    (letfn
      [(defect
         [rule msg data]
         (throw (ex-info msg (assoc data :rule rule))))
       (child
         [kind id v]
         (case kind
           :node (build v)
           ;; as for :syms: projection mints a metadata-free vector
           :nodes (if (and (vector? v) (nil? (meta v)))
                    (mapv build v)
                    (defect :slot-kind
                      "Semantic bytecode nodes slot is not a metadata-free vector"
                      {:id id, :value v}))
           ;; projection always mints a metadata-free vector, so a vector
           ;; carrying metadata (which dao.jing hashes) could never re-project
           ;; to its own address
           :syms (if (and (vector? v) (nil? (meta v)) (every? symbol? v))
                   v
                   (defect :slot-kind
                     "Semantic bytecode syms slot is not a metadata-free vector of symbols"
                     {:id id, :value v}))
           :bool (if (or (true? v) (false? v))
                   v
                   (defect :slot-kind
                     "Semantic bytecode bool slot is not true or false"
                     {:id id, :value v}))
           v))
       (build
         [id]
         (if-let [node (get @built id)]
           node
           (let [row (get rows id)
                 _ (when (nil? row)
                     (defect :id-resolves
                       "Semantic bytecode id resolves to no row"
                       {:id id}))
                 _ (when-not (and (vector? row) (<= 2 (count row)))
                     (defect :shape
                       "Semantic bytecode row is not [id tag & slots]"
                       {:id id, :row row}))
                 body (subvec row 1)
                 _ (when-not (= id (first row) (jing/segment-key body))
                     (defect :content-address
                       "Semantic bytecode row id is not its content address"
                       {:id id, :row row}))
                 tag (first body)
                 slots (get semantic-bytecode-grammar tag)
                 _ (when (nil? slots)
                     (defect :tag
                       "Unknown AST node type in semantic bytecode"
                       {:id id, :type tag}))
                 _ (when-not (= (count slots) (dec (count body)))
                     (defect :arity
                       "Semantic bytecode row arity does not match its tag"
                       {:id id, :row row}))
                 node (reduce
                        (fn [m [[field kind] v]]
                          (when (and (nil? v)
                                     (contains? semantic-bytecode-defaults
                                                [tag field]))
                            (defect :saturation
                              "Semantic bytecode saturated slot is nil"
                              {:id id, :field field}))
                          (assoc m field (child kind id v)))
                        {:type tag}
                        (map vector slots (rest body)))]
             (swap! built assoc id node)
             node)))]
      (build root))))


;; =============================================================================
;; The occurrence relation and root-scoped occurrence rules
;; =============================================================================
;; §6.1/§4.5: the structural half of the AST indexer — not yet an observer.

(defn- occurrence-child-places
  "`[[path-step child-id] …]` for every `node`/`nodes` slot of a row, in slot
   order. A `:node` slot's step is its row position (id 0, tag 1, first slot
   2 — the base of §2.5's own `[2 [3 0]]` example), a `:nodes` slot's step
   is the pair `[position i]`. Note `validate-rows`' defect paths number
   the first slot 1; §2.5's example is normative here."
  [row]
  (let [slots (get semantic-bytecode-grammar (semantic-bytecode-row-tag row))]
    (mapcat (fn [j [_ kind] v]
              (let [pos (+ j 2)]
                (case kind
                  :node (when v [[pos v]])
                  :nodes (map-indexed (fn [i cid] [[pos i] cid]) v)
                  nil)))
            (range (count slots)) slots (drop 2 row))))


(defn occurrences
  "§6.1: the occurrence relation of a projected row set — one
   `[root path node]` tuple per place, so a structurally shared subtree
   (§4.4) yields one tuple per place and one node id. Computed by the same
   walk as the projection, so it is generic over every §2.3 tag. It is a
   pure function of the tree and carries no origin; a query fixes origin
   from the composition's side and joins on `[root path]` (§6.1). Assumes
   the walk terminates: projected ids are content addresses of their
   children, so a projected set is acyclic by construction (§4.1)."
  [{:keys [root rows]}]
  (loop [frontier [[[] root]]
         tuples []]
    (if (empty? frontier)
      (set tuples)
      (let [children (mapcat (fn [[path id]]
                               (let [row (get rows id)]
                                 (map (fn [[step cid]] [(conj path step) cid])
                                      (when row (occurrence-child-places row)))))
                             frontier)]
        (recur children
               (into tuples (map (fn [[path id]] [root path id]) frontier)))))))


(def occurrence-rules
  "§4.5/§7.7: root-scoped occurrence rules. `?root` is threaded through
   every head (`p-up`/`occ-anc`/`occ-bound?`) and the `[$occ …]` join is
   constrained to one root, so a binder in another tree of the same
   relation can never classify this tree's occurrence — the gap §4.5 names
   in an unscoped rule set. The ancestor walk is over path prefixes
   (`path-pop`), not row edges, so it is generic over every §2.3 tag. `$`
   must be the row relation and `$occ` the occurrence relation; the rules
   need `member?` and `path-pop` under `:fns` — see `occurrence-fns`.
   `?root` must be bound at every call: the bodies never bind it, and an
   unscoped invocation fails loudly rather than enumerate."
  '[[(p-up ?root ?child ?parent) [(path-pop ?child) ?parent]]
    [(occ-anc ?root ?a ?d) (p-up ?root ?d ?a)]
    [(occ-anc ?root ?a ?d) (p-up ?root ?d ?m) (occ-anc ?root ?a ?m)]
    [(occ-bound? ?root ?path ?name)
     (occ-anc ?root ?lam-path ?path)
     [$occ ?root ?lam-path ?lam]
     [?lam :lambda ?params _]
     [(member? ?params ?name)]]])


(def occurrence-fns
  "The `:fns` map `occurrence-rules` requires (§6.3: quoted-symbol keys):
   membership over a `:syms`/`:nodes` vector, and one step up a §2.5 path
   (nil at the root, where no occurrence tuple carries the answer anyway)."
  {'member? (fn [coll x] (boolean (some #(= % x) coll))),
   'path-pop (fn [p] (when (pos? (count p)) (subvec p 0 (dec (count p)))))})


(defn free-names
  "§7.7 free-name extraction as a function: every `:variable` name free at
   some occurrence of `root`'s tree. `db` is the row relation
   (`query/relation` over the rows) and `occ` the occurrence relation
   (`occurrences`); both may hold several trees unioned (§6.1) — the rules
   are root-scoped, so only `root`'s binder classifies `root`'s
   occurrences. Conservative by construction: a name whose row is bound at
   one occurrence and free at another is included, never silently dropped
   (§7.6.1)."
  [db occ root]
  (set (map first
            (query/collect
              (query/q '[:find ?name :in $ $occ % ?root
                         :where
                         [$occ ?root ?path ?v]
                         [?v :variable ?name]
                         (not (occ-bound? ?root ?path ?name))]
                       db occ occurrence-rules root
                       {:fns occurrence-fns})))))


;; =============================================================================
;; The footprint table and the §7.7 syntactic extraction queries
;; =============================================================================
;; §7.7: the code part of UCF §7.6.1's conservative fixed point is the
;; queries below, run over the flat rows of every reachable tree ($ast) and
;; the segment-qualified rows of every reachable segment ($code). They
;; supply the fixed point's inputs; the value, name-satisfaction, and
;; module parts remain the walk UCF §7.6.1 specifies. Free names are
;; `free-names` above; everything else the syntactic queries fill is here.

(def footprint-table
  "§7.7.1's effect normalization as a data value, keyed by the execution
   contract stamp (UCF §7.3.3) — the effect a syntax tag or mnemonic raises
   is part of that contract, lifted out of the reference machine's hot loop
   (`semantic.cljc`). Two directions: `:tags` over every §2.3 syntax tag
   and `:mnemonics` over every §2.4 mnemonic, each to the set of effect
   identifiers it contributes. Every tag and every mnemonic has a row, so
   \"no external effect\" is an explicit `#{}`, never an absence; a tag or
   mnemonic outside the table is `:yin.k/undecodable`, the outcome the
   validators give it — and the extraction queries' tag/mnemonic sets are
   table subsets, so over validated rows the lookup is total. The design
   table's tag↔mnemonic correspondence is 1:1 for the effect rows and
   deliberately lumped for the last (5 tags lower to 9 mnemonics; see
   `yin.vm.linearize`), so it is not re-stated as a map here. Its \"other
   requirement\" column is realized by the extraction queries themselves:
   an FFI op goes to the receiver-capability requirement
   (`:yin.k/ffi-ops` — the pair's own store keys are never a store-slice
   requirement, UCF §7.6.2), a store key to the store slice, a parked id
   to the parked-record obligation, and a call's effects are its callee's
   profile effects (§7.7.2). A `:variable`'s name obligation is
   conditional on freeness (§4.5) — a query (`free-names`), not a
   per-tag footprint fact."
  {"v2"
   {:tags {:stream/make #{:stream/make},
           :stream/put #{:stream/put},
           :stream/cursor #{:stream/cursor},
           :stream/next #{:stream/next},
           :stream/close #{:stream/close},
           ;; an FFI call is not an effect kind: it goes through the FFI pair
           :dao.stream.apply/call #{},
           :vm/park #{},                        ; scheduler-internal
           :vm/resume #{},
           :vm/gensym #{},                      ; the id counter is machine state
           :vm/store-get #{},
           :vm/store-put #{},
           :vm/current-continuation #{},
           ;; from syntax alone; a call's effects are its callee's profile
           :literal #{}, :variable #{}, :lambda #{}, :application #{}, :if #{}},
    :mnemonics {:stream-make #{:stream/make},
                :stream-put #{:stream/put},
                :stream-cursor #{:stream/cursor},
                :stream-next #{:stream/next},
                :stream-close #{:stream/close},
                :ffi-call #{},
                :park #{},
                :resume #{},
                :gensym #{},
                :store-get #{},
                :store-put #{},
                :current-continuation #{},
                :const #{}, :var #{}, :closure #{}, :push #{}, :call #{},
                :return #{}, :jump #{}, :branch-false #{}, :halt #{}}}})


(def ^:private extraction-fns
  "The `:fns` the §7.7 queries need (§6.3: quoted-symbol keys): the
   effect-raising tag/mnemonic sets are judged by `contains?`."
  {'contains? contains?})


(defn- extract
  "One §7.7 extraction query over `db`, its single find column as a set."
  [q db]
  (set (map first (query/collect (query/q q db {:fns extraction-fns})))))


(def ^:private ast-extraction-queries
  "§7.7 over `$ast`, the union of the flat rows of the reachable trees,
   verbatim from the design."
  {:store-keys '[:find ?key :in $ast
                 :where (or [$ast _ :vm/store-get ?key]
                            [$ast _ :vm/store-put ?key _])],
   :ffi-ops '[:find ?op :in $ast
              :where [$ast _ :dao.stream.apply/call ?op _]],
   :parked-ids '[:find ?pid :in $ast
                 :where [$ast _ :vm/resume ?pid _]],
   ;; syntax that raises an effect: the tag, to be normalized by the
   ;; footprint table
   :effect-raisers '[:find ?tag :in $ast
                     :where [$ast _ ?tag & _]
                     [(contains? #{:stream/make :stream/put :stream/cursor
                                   :stream/next :stream/close
                                   :dao.stream.apply/call}
                                 ?tag)]]})


(def ^:private segment-extraction-queries
  "§7.7 over `$code`, the union of the segment-qualified rows
   (`yin.vm.code/project-segment-qualified`) of the reachable segments,
   verbatim from the design. The segment-side free-name query (derive free
   names from the vector's own scoping) is §7.7's to name but stays
   unverified there; `free-names` over `$ast`/`$occ` is the shipped
   extraction."
  {:store-keys '[:find ?key :in $code
                 :where (or [$code _ _ :store-get ?key]
                            [$code _ _ :store-put ?key _])],
   :ffi-ops '[:find ?op :in $code
              :where [$code _ _ :ffi-call ?op _]],
   :parked-ids '[:find ?pid :in $code
                 :where [$code _ _ :resume ?pid]],
   :effect-raisers '[:find ?mn :in $code
                     :where [$code _ _ ?mn & _]
                     [(contains? #{:stream-make :stream-put :stream-cursor
                                   :stream-next :stream-close :ffi-call}
                                 ?mn)]]})


(defn- requirements
  "The §7.7 syntactic requirement set of one relation under contract
   `\"v2\"`: the three value queries as extracted, and the effect-raising
   tags/mnemonics normalized through the footprint table's `dir` direction
   before any union, so the union is over one vocabulary (§7.7.1)."
  [db queries dir]
  (let [table (get-in footprint-table ["v2" dir])]
    {:store-keys (extract (:store-keys queries) db),
     :ffi-ops (extract (:ffi-ops queries) db),
     :parked-ids (extract (:parked-ids queries) db),
     :effects (into #{} (mapcat table) (extract (:effect-raisers queries) db))}))


(defn ast-requirements
  "§7.7's syntactic requirement set from a tree relation `$ast` — a
   `query/relation` over the flat rows of the reachable trees (e.g.
   `(vals (:rows (ast->semantic-bytecode ast)))`, unioned across trees):
   store keys, FFI ops, parked ids, and effect identifiers, effects
   already normalized by the footprint table. These are the fields of
   `:yin.k/requires` the syntactic queries fill; free names are
   `free-names`, and the value/name/module parts of UCF §7.6.1's fixed
   point remain the walk that consumes this."
  [db]
  (requirements db ast-extraction-queries :tags))


(defn segment-requirements
  "The same over a `$code` relation of segment-qualified rows
   (`yin.vm.code/project-segment-qualified`). For every tree,
   `(ast-requirements tree-relation)` and this over its lowered segment's
   rows are equal in every field — the §7.7.1 conformance obligation."
  [db]
  (requirements db segment-extraction-queries :mnemonics))


(def ^:private many-attrs #{:yin/operands :yin/args :yin/params})


(defn index-datoms
  "Index AST datoms by entity.
   Returns {:by-entity map, :get-attr fn, :root-id int}, plus
   `:error {:rule :dangling-root :entity e}` when the root fact names an
   entity with no `:yin/type`.

   The root is the entity of the last `:yin/root` fact in batch order. A
   batch with no root fact falls back to the structural heuristic below. A
   dangling root is recorded, not thrown: the index still names it, and
   decoding it is what fails loudly.

   Optional opts:
   - :by-entity precomputed {eid [datom ...]} index
   - :root-id explicit root entity id override"
  ([ast-as-datoms] (index-datoms ast-as-datoms {}))
  ([ast-as-datoms {:keys [by-entity root-id]}]
   (let [datoms (vec ast-as-datoms)
         by-entity (or by-entity (group-by first datoms))
         get-attr (fn [e attr]
                    (let [matching (filter (fn [d] (= (nth d 1) attr))
                                           (get by-entity e))]
                      (if (contains? many-attrs attr)
                        (vec (mapcat (fn [d]
                                       (let [v (nth d 2)]
                                         (if (vector? v) v [v])))
                                     matching))
                        (when (seq matching) (nth (last matching) 2)))))
         root-fact (when-not root-id
                     (some (fn [d] (when (= :yin/root (nth d 1)) d))
                           (rseq datoms)))
         dangling (when (and root-fact
                             (nil? (get-attr (first root-fact) :yin/type)))
                    {:rule :dangling-root, :entity (first root-fact)})
         root-id
         (or root-id
             (first root-fact)
             (when (seq by-entity)
               ;; Heuristic for finding the root:
               ;; 1. Find all eids that have a :yin/type
               ;; 2. Prefer the ones that are NOT used as structural
               ;; children
               ;; 3. Return the LAST such eid (most recent addition)
               ;; 4. Fallback to max eid
               (let [type-eids (keep (fn [[e datoms]]
                                       (when (some #(= :yin/type (nth % 1))
                                                   datoms)
                                         e))
                                     by-entity)
                     referenced-eids
                     (set (mapcat (fn [[_ a v]]
                                    (when (contains?
                                            #{:yin/body :yin/operator
                                              :yin/operands :yin/target
                                              :yin/val-node}
                                            a)
                                      (if (vector? v) v [v])))
                                  datoms))
                     unreferenced (filter (complement referenced-eids)
                                          type-eids)]
                 (or (last (sort unreferenced))
                     (apply max (keys by-entity))))))]
     (cond-> {:by-entity by-entity, :get-attr get-attr, :root-id root-id}
       dangling (assoc :error dangling)))))


(defn empty-state
  "Return an initial immutable VM state map.

   Options:
     :primitives   primitive operations map (defaults to `primitives`)
     :primitive-profiles published profile registry (defaults to `primitive-profiles`)
     :primitive-canonical-names name -> canonical name for intentional aliases
     :modules      module registry value (see `yin.vm.module`)
     :make-stream  (fn [capacity] -> create outcome); no default
     :call-in      explicit inbound request handle
     :call-out     explicit outbound response handle
     :call-capacity capacity for a constructed FFI pair
     :vm-model     telemetry model keyword
     :telemetry    telemetry config {:stream <dao.stream writer> :vm-id <id>};
                   validated by telemetry/install, which both create-vms call
                   after this

   The FFI pair comes from explicitly supplied `:call-in`/`:call-out` first —
   matching v1's precedence, so a composition handing over streams directly is
   never silently overridden — else from `:make-stream`, else the store holds
   no pair at all. A VM with no pair is coherent: it still operates streams the
   composition handed it. What it cannot do is make a
   `:dao.stream.apply/call`.

   This state holds no program-observation fields. The program stream handle,
   cursor, and gap count belong to `dao.stream.observer`, which a host
   composes beside the VM rather than inside it."
  ([] (empty-state {}))
  ([opts]
   (let [installed-primitives (or (:primitives opts) primitives)
         installed-profiles (or (:primitive-profiles opts)
                                (into {} (keep (fn [[name _]]
                                                 (when-let [profile
                                                            (profile-of
                                                              installed-primitives
                                                              name)]
                                                   [name profile])))
                                      installed-primitives))
         standard-aliases (into {}
                                (filter
                                  (fn [[alias canonical]]
                                    (and (identical?
                                           (primitive-function
                                             (get installed-primitives alias))
                                           (primitive-function
                                             (get primitives alias)))
                                         (identical?
                                           (primitive-function
                                             (get installed-primitives canonical))
                                           (primitive-function
                                             (get primitives canonical))))))
                                primitive-canonical-names)
         canonical-names (merge standard-aliases
                                (:primitive-canonical-names opts))
         _ (assert-primitive-reverse-lookup-unique! installed-primitives
                                                    canonical-names)
         make-stream (:make-stream opts)
         capacity (or (:call-capacity opts) default-call-capacity)
         supplied-in (:call-in opts)
         supplied-out (:call-out opts)]
     (when (and (not= (some? supplied-in) (some? supplied-out))
                (not (fn? make-stream)))
       (throw (ex-info "Half a call pair is not a call pair: supply both :call-in and :call-out, or :make-stream, or neither"
                       {:call-in? (some? supplied-in),
                        :call-out? (some? supplied-out)})))
     (let [pair? (or (and supplied-in supplied-out) (fn? make-stream))
           close-quietly
           (fn [handle]
             (try (stream/close! handle)
                  (catch #?(:cljd Object :clj Throwable :cljs :default) _ nil)))
           ;; Construction is all-or-nothing operationally as well: a failure
           ;; after a stream this function created closes that stream before
           ;; the error propagates, so an unreachable host resource is not
           ;; leaked. Streams the composition supplied are never closed here;
           ;; their owner outlives this VM.
           pair-store
           (when pair?
             (let [created (atom [])]
               (try
                 (let [call-in (or supplied-in
                                   (let [h (create-stream! make-stream
                                                           capacity
                                                           :call-in)]
                                     (swap! created conj h)
                                     h))
                       call-out (or supplied-out
                                    (let [h (create-stream! make-stream
                                                            capacity
                                                            :call-out)]
                                      (swap! created conj h)
                                      h))]
                   {call-in-stream-key call-in,
                    call-out-stream-key call-out,
                    call-out-cursor-key (cursor-entry call-out-stream-key
                                                      (mint-oldest call-out
                                                                   :call-out))})
                 (catch #?(:cljd Object :clj Throwable :cljs :default) e
                   (doseq [h @created] (close-quietly h))
                   (throw e)))))]
       {:store (or pair-store {}),
        :parked {},
        :id-counter 0,
        :ready-queue [],
        :wait-set [],
        :primitives installed-primitives,
        :primitive-profiles installed-profiles,
        :primitive-canonical-names canonical-names,
        :modules (:modules opts),
        :make-stream make-stream,
        :call-capacity capacity,
        :telemetry (:telemetry opts),
        :telemetry-step 0,
        :telemetry-t 0,
        :telemetry-eid datom/first-user-id,
        :vm-model (:vm-model opts)}))))
