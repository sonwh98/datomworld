(ns yin.vm.engine
  "Shared evaluation machinery for Yin VMs on DaoStream v2.

   What the port changed, and why:

   - **The host supplies streams.** `:stream/make` calls the composition's
     `:make-stream`; there is no transport identity in this namespace. Every
     other stream handler is a protocol call on a handle already in the
     private `:resources` table, which no store instruction reads, reached
     through a reference whose seal is verified first.
   - **Cursors are opaque.** A cursor cell in `:resources` is
     `{:stream-id id :cursor <opaque>}`. Nothing increments one.
   - **Outcomes are maps, and every handler is total over its closed set.**
     `blocked` and `full` park; `end` and `gap` are values the program sees;
     the rest are errors that name their outcome.
   - **Waiters are gone.** No v2 transport is waitable, so the polling wait
     set is the mechanism rather than a fallback. A poll is a synchronous
     `next` or `append!` on the handle in `:resources`; no scheduler runtime
     sits between the VM and its streams. Cadence comes from the driver
     above the VM.
   - **`:stream/take` is gone.** Destructive read needs a reader position in
     the medium, which the contract retired.
   - **The module registry is a value** carried in VM state, so effect
     dispatch and `resolve-var` read a supplied registry rather than a global.
   - **Program observation is not engine work.** `ready-for-ingress?` lives
     here because it speaks the scheduler's own vocabulary, but the program
     handle, cursor, and gap count belong to `dao.stream.observer`;
     nothing in this namespace polls a program stream."
  (:refer-clojure :exclude [gensym])
  (:require [clojure.set]
            [dao.jing :as jing]
            [dao.stream :as stream]
            [dao.stream.waitset :as waitset]
            [yin.vm :as vm]
            [yin.vm.linker :as linker]
            [yin.vm.module :as module]
            [yin.vm.telemetry :as telemetry]))


(defn- outcome
  [result]
  (:dao.stream/outcome result))


(defn- fail
  [message data]
  (throw (ex-info message data)))


(defn bind-params
  "Zips params with args, nil-filling any params beyond args' length.
   Extra args beyond params' length are dropped. §7.7.2: an under-arity
   call leaves missing parameter names bound to nil, not absent."
  [params args]
  (into {} (map vector params (concat args (repeat nil)))))


(defn resolve-var
  "Look up a variable name: env -> store -> primitives -> module registry.

   A reserved name (Rule R: `yin/def` is syntax, never a name) is refused
   before env or store is consulted, so no binding, store entry, or
   registry can give it a meaning. Every executing variable lookup of
   every engine goes through here.

   The registry is a value supplied by the composition, not a global."
  [env store primitives registry name]
  (when (vm/reserved-name? name)
    (vm/refuse-reserved! :variable name))
  (if-let [pair (find env name)]
    (val pair)
    (if-let [pair (find store name)]
      (val pair)
      (if-let [pair (find primitives name)]
        (vm/primitive-function (val pair))
        (if-let [resolved (when (namespace name)
                            (module/resolve-module
                              registry
                              (symbol (str (namespace name)
                                           "."
                                           (clojure.core/name name)))))]
          resolved
          (fail (str "Unable to resolve symbol: " name " in this context")
                {:symbol name}))))))


(defn check-store-key!
  "Refuse a program store access whose key is a reserved name (Rule R):
   the definition operator is never a store key."
  [key]
  (when (vm/reserved-name? key)
    (vm/refuse-reserved! :store-key key)))


(defn store-put
  "The one program store write: every definition transition, the
   `:vm/store-put` effect, and the four direct store instructions write
   through here, and a reserved key is refused. Every other store write
   `yin.vm.store-write-audit-test` detects is state construction on its
   allowlist; that namespace states what it detects and the residual it
   leaves to review."
  [store key val]
  (check-store-key! key)
  (assoc store key val))


;; =============================================================================
;; Module stores (yin.vm.linker.md section 7.3, r6 and r7)
;; =============================================================================
;;
;; A linked module's closures carry `:yin.k/store-of m`, the manifest
;; address of the module whose store their body resolves against. The
;; positional kernels hold the running body's `:store-of` as a register,
;; saved in every return frame and parked payload; the named kernels carry
;; it in the lexical environment under the same key, so a closure captures
;; it with its environment, a frame's saved environment restores it, and a
;; halt strips it. The receiving task instantiates each module's store
;; under `:module-stores {m {sym value}}`, never into its own `:store`.

(def store-of-key
  "The key a closure (and, for the named kernels, an environment) carries
   the manifest address of its module store under."
  :yin.k/store-of)


(defn env-store-of
  "The module store a named kernel's environment `env` runs against, or
   nil at top level."
  [env]
  (get env store-of-key))


(defn without-store-of
  "`env` with no module store context: what a halt leaves, so the next
   top-level input never routes through a stale module store."
  [env]
  (if (contains? env store-of-key) (dissoc env store-of-key) env))


(defn store-context
  "The module store the running body of `state` routes to, or nil: the
   positional kernels' `:store-of` register, else the named kernels'
   environment."
  [state]
  (or (:store-of state) (env-store-of (:env state))))


(defn active-store
  "The store free reads and `:store-get` resolve against: while a module
   closure runs (`store-of` non-nil), its module store, with no ambient
   fallback -- a key the module store lacks is absent; otherwise the
   task's own store."
  [state store-of]
  (if (some? store-of)
    (get (:module-stores state) store-of {})
    (:store state)))


(defn put-active
  "Write `key` in the active store of `state`: the module store
   `store-of` names, or the task's own store at top level. Both go
   through `store-put`, so a reserved key is refused either way."
  [state store-of key val]
  (if (some? store-of)
    (update-in state [:module-stores store-of]
               (fn [module-store] (store-put (or module-store {}) key val)))
    (assoc state :store (store-put (:store state) key val))))


(defn- gen-id
  "Generate a unique keyword ID from a prefix and counter value."
  [prefix id-counter]
  (keyword (str prefix "-" id-counter)))


(defn vm-blocked?
  "Returns true if the VM is blocked."
  [vm]
  (boolean (:blocked? vm)))


(defn vm-value
  "Returns the current value of the VM."
  [vm]
  (:value vm))


(defn halted-with-empty-queue?
  "Returns true if the VM has halted and its ready-queue is empty."
  [vm]
  (and (boolean (:halted? vm)) (empty? (or (:ready-queue vm) []))))


(defn restore-initial-env
  "After eval, restore :env to initial-env when the computation halted.
   Blocked and parked states keep the active lexical env for resumption."
  [initial-env result]
  (if (halted-with-empty-queue? result) (assoc result :env initial-env) result))


(defn active-continuation?
  "Returns true when the currently active continuation should keep stepping."
  [vm]
  (and (not (:blocked? vm)) (not (:halted? vm))))


(defn ready-for-ingress?
  "Returns true when the VM is between evaluations and can accept another
   program batch: not blocked, nothing scheduled or waiting, no active
   continuation, and no loaded work.

   This predicate gates observer coordination above the VM. The VM itself
   never polls a program stream: `step` and `run` execute loaded work only."
  [vm]
  (let [has-bytecode? (contains? vm :bytecode)
        bytecode (:bytecode vm)
        no-bytecode? (and has-bytecode?
                          (or (nil? bytecode)
                              (and (sequential? bytecode) (empty? bytecode))))]
    (and (not (:blocked? vm))
         (empty? (or (:ready-queue vm) []))
         (empty? (or (:wait-set vm) []))
         (nil? (:k vm))
         (or (:halted? vm) (nil? (:control vm)) no-bytecode?))))


;; =============================================================================
;; Private engine resources and sealed references (yin.vm.linker.md section
;; 7.3, r8 to r11)
;; =============================================================================
;;
;; Stream handles, cursor cells, the FFI pair, and the link pair live in the
;; private `:resources` table of VM state. The engine's own machinery reads
;; and writes only that table; no store instruction and no `resolve-var`
;; step consults it. A program holds only references, and every reference
;; the engine issues carries a seal over its kind and id under the task's
;; capability secret, which the composition minted and which lives in VM
;; state alone. Every effect that resolves a program-supplied reference
;; verifies the seal first: a literal with a wrong or missing seal fails
;; closed with `:forged-resource-reference`.

(defn- seal-of
  "The seal of a `kind` reference to resource `id` under `secret`."
  [secret kind id]
  (jing/content-hash [:yin.k/seal secret kind id] {:algorithm :sha256}))


(defn- cursor-cell?
  "True when a resource is a cursor cell, `{:stream-id id :cursor c}`."
  [resource]
  (and (map? resource) (contains? resource :stream-id)))


(defn issue-ref
  "A reference of `kind` (`:stream-ref` or `:cursor-ref`) to resource
   `id`, sealed under the task's capability secret. A task composed
   without a secret cannot issue one: it fails closed rather than issue a
   reference anyone could forge."
  [state kind id]
  (let [secret (:capability-secret state)]
    (when (nil? secret)
      (fail (str "This task has no capability secret, so it cannot issue"
                 " a resource reference")
            {:reason :no-capability-secret, :kind kind, :id id}))
    {:type kind, :id id, :seal (seal-of secret kind id)}))


(defn authentic-ref?
  "True when `ref` is a `kind` reference this task issued: its seal
   verifies under the task's secret, and it names a live resource of that
   kind."
  [state kind ref]
  (let [secret (:capability-secret state)
        id (when (map? ref) (:id ref))
        resource (get (:resources state) id)]
    (boolean (and (some? secret)
                  (= kind (:type ref))
                  (some? resource)
                  (= (:seal ref) (seal-of secret kind id))
                  (= (= :cursor-ref kind) (cursor-cell? resource))))))


(defn check-ref!
  "The resource id a program-supplied `kind` reference names, once its
   seal verifies (r10). Anything else fails closed with
   `:forged-resource-reference`, naming the effect and the id it named."
  [state effect-kind kind ref]
  (if (authentic-ref? state kind ref)
    (:id ref)
    (fail "Forged resource reference"
          {:reason :forged-resource-reference,
           :effect effect-kind,
           :kind kind,
           :id (when (map? ref) (:id ref))})))


(declare gensym)


(defn attach-resource
  "Install the host stream `handle` a composition hands a task under a
   fresh resource id, and return `[stream-ref state]` with the reference
   sealed under the task's secret. This is how a stream reaches a program:
   never through the store."
  [state handle]
  (let [[id state] (gensym state "stream")
        ref (issue-ref state :stream-ref id)]
    [ref (assoc-in state [:resources id] handle)]))


(defn make-woken-run-queue-entries
  "Transform woken wait-set entries into ready-queue entries.
   Readers (with :cursor-ref) record the successor cursor the transport
   returned as a resource update. Writers (no :cursor-ref) just stamp
   :value. Each woken result's :status rides onto the ready entry, where
   terminal-resume-outcome reads it.

   The ready entry stays pure data: any `:stream` handle a poll resolved
   is dropped -- the resource updates carry the successor cursor, and
   handles are re-resolved from ids when needed again -- so an entry
   neither waits nor runs holding a host object."
  [state woken]
  (mapv (fn [{:keys [entry value cursor status], :as woken-entry}]
          (let [cursor-ref (:cursor-ref entry)
                updates
                (or (:resource-updates woken-entry)
                    (when (and cursor-ref cursor)
                      (let [cursor-id (:id cursor-ref)
                            cursor-data (get (:resources state) cursor-id)]
                        {cursor-id (assoc cursor-data :cursor cursor)})))]
            (dissoc (assoc entry
                           :value value
                           :resource-updates updates
                           :status status
                           :cursor cursor)
                    :stream)))
        woken))


(defn handle-make
  "Handle :stream/make. Creates a stream through the composition's
   `:make-stream`, holds the handle in the private `:resources` table, and
   answers a sealed reference; the reference is issued first, so a task
   with no secret creates no stream.

   A nil capacity has no v2 meaning, so the module path defaults exactly as
   the AST path does. Absent a supplied constructor this fails and says so;
   silently falling back to a private transport is the failure mode the rule
   exists to prevent.
   Returns [stream-ref updated-state]."
  [state effect id]
  (let [capacity (or (:capacity effect) vm/default-stream-capacity)
        stream-ref (issue-ref state :stream-ref id)
        handle (vm/create-stream! (:make-stream state) capacity :stream/make)]
    [stream-ref (assoc-in state [:resources id] handle)]))


(defn handle-put
  "Handle :stream/put. Total over the five append outcomes, once the
   reference verifies.
   Returns {:value v :state s} on success, {:park true :stream-id id :state s}
   on `full`. `closed`, `invalid-value` and `transport-error` are errors that
   name their outcome, as v1's throw on a closed stream did."
  [state effect]
  (let [stream-id (check-ref! state :stream/put :stream-ref (:stream effect))
        val (:val effect)
        handle (get (:resources state) stream-id)
        result (stream/append! handle val)
        o (outcome result)]
    (case o
      :dao.stream/ok {:value val, :state state}
      :dao.stream/full {:park true, :stream-id stream-id, :state state}
      (fail "Stream append failed" {:outcome o, :stream-id stream-id}))))


(defn handle-cursor
  "Handle :stream/cursor. Mints an opaque cursor at `:dao.stream/oldest`
   into a cursor cell in `:resources`, and answers a sealed reference.

   v1 fabricated `{:position 0}` and touched no stream. Minting is a stream
   operation, so `closed` and `transport-error` arrive here.
   Returns [cursor-ref updated-state]."
  [state effect id]
  (let [stream-id (check-ref! state :stream/cursor :stream-ref (:stream effect))
        cursor-ref (issue-ref state :cursor-ref id)
        cursor (vm/mint-oldest (get (:resources state) stream-id)
                               :stream/cursor)]
    [cursor-ref
     (assoc-in state [:resources id] (vm/cursor-entry stream-id cursor))]))


(defn handle-next
  "Handle :stream/next. Total over the seven read outcomes, once the
   reference verifies.

   `ok` advances the cursor cell to the exact returned successor. `blocked`
   parks. `end` yields nil, as v1 did. `gap` advances to the recovery cursor
   and yields `:dao.stream/gap`, so a program that reads it learns values were
   lost. The three terminal outcomes are errors."
  [state effect]
  (let [cursor-ref (:cursor effect)
        cursor-id (check-ref! state :stream/next :cursor-ref cursor-ref)
        resources (:resources state)
        cursor-data (get resources cursor-id)
        stream-id (:stream-id cursor-data)
        handle (get resources stream-id)]
    (when (nil? handle)
      (fail "Stream not found for cursor" {:stream-id stream-id}))
    (let [result (stream/next handle (:cursor cursor-data))
          o (outcome result)
          advance (fn [value]
                    {:value value,
                     :state (assoc-in state
                                      [:resources cursor-id :cursor]
                                      (:dao.stream/cursor result))})]
      (case o
        :dao.stream/ok (advance (:dao.stream/value result))
        :dao.stream/blocked {:park true,
                             :cursor-ref cursor-ref,
                             :stream-id stream-id,
                             :state state}
        :dao.stream/end {:value nil, :state state}
        :dao.stream/gap (advance :dao.stream/gap)
        (fail "Stream read failed"
              {:outcome o, :stream-id stream-id, :cursor-id cursor-id})))))


(defn handle-close
  "Handle :stream/close, once the reference verifies. `close!` is total
   over {ok} and wakes nothing: a reader parked on this stream learns of
   the close from its own next `next`.
   Returns {:state s'}."
  [state effect]
  (let [stream-id (check-ref! state :stream/close :stream-ref (:stream effect))]
    (stream/close! (get (:resources state) stream-id))
    {:state state}))


(def ^:private waitset-resolver
  "The engine's composition for `dao.stream.waitset/check`: the whole
   resolver contract in one value, both functions synchronous and pure over
   the immutable resources table. Neither touches a transport -- the
   library polls, the engine maps.

   `:resolve` maps one whole parked entry to its live handle plus the
   cursor to read from -- or the value to append -- out of the VM's
   `:resources`. The cursor is re-resolved on every round even when an
   earlier round baked a value into the entry: another waiter on the same
   cursor-ref may have advanced the cell since. `:advance` commits a woken
   reader's successor -- or recovery -- cursor to its cell before later
   entries resolve, so a shared cursor-ref advances within the round."
  {:resolve (fn [resources entry]
              (if-let [cursor-ref (:cursor-ref entry)]
                (let [cursor-data (get resources (:id cursor-ref))]
                  {:stream (or (:stream entry)
                               (get resources (:stream-id cursor-data)))
                   :cursor (:cursor cursor-data)})
                {:stream (or (:stream entry) (get resources (:stream-id entry)))
                 :value (:datom entry)}))
   :advance (fn [resources entry cursor]
              (let [cursor-id (:id (:cursor-ref entry))]
                (if cursor-id
                  (assoc-in resources [cursor-id :cursor] cursor)
                  resources)))})


;; =============================================================================
;; Lift and lower: the portable encoding a linked slice crosses in
;; (yin.vm.linker.md section 7.3, acts 1 and 4; UCF section 7.5.1)
;; =============================================================================

(defn- non-portable!
  [kind data]
  (fail "Value is not portable"
        (merge data {:yin.k/status :yin.k/non-portable, :yin.k/kind kind})))


(defn- scalar?
  [x]
  (or (nil? x) (boolean? x) (number? x) (string? x) (keyword? x)
      (symbol? x)))


(defn- encode-primitive
  "A host function as UCF 7.5.2's primitive marker: its one name in
   `vm`'s primitives, with that name's profile address."
  [vm f]
  (let [n (vm/name-of (:primitives vm)
                      (or (:primitive-canonical-names vm) {})
                      f)
        profile (when (symbol? n) (vm/profile-of (:primitives vm) n))]
    (cond
      (nil? n) (non-portable! :unnamed-function {})
      (not (symbol? n)) (non-portable! :ambiguous-primitive {})
      (= :host (:yin.k/class profile))
      (non-portable! :host-state-primitive {:yin.k/name n})
      :else {:yin.k/tag :yin.k/primitive,
             :yin.k/name n,
             :yin.k/profile (:yin.k/profile profile)})))


(defn- stream-marker
  "UCF 7.5.1's stream marker for the stream resource `id` of `vm`: the
   handle's descriptor and logical identity, never the handle. A handle
   with no descriptor surface cannot be attached elsewhere and refuses as
   `:in-memory-handle`."
  [vm id]
  (let [handle (get (:resources vm) id)
        d (when (stream/descriptor? handle) (stream/descriptor handle))]
    (if (= :dao.stream/ok (outcome d))
      {:yin.k/tag :yin.k/stream,
       :dao.stream/identity (:dao.stream/identity d),
       :dao.stream/descriptor (:dao.stream/descriptor d)}
      (non-portable! :in-memory-handle {:yin.k/hint id}))))


(defn- cell-for!
  "The logical cursor cell (UCF 7.5.3) the cursor resource `id` of `vm`
   lifts to, minted once per lift: two references to one cell share it."
  [vm found id]
  (or (get-in @found [:cell-of id])
      (let [cid (keyword "yin.k" (str "c-" (count (:cells @found))))
            cell (get (:resources vm) id)
            lifted {:yin.k/stream (stream-marker vm (:stream-id cell)),
                    :yin.k/position (:cursor cell)}]
        (swap! found #(-> %
                          (assoc-in [:cell-of id] cid)
                          (assoc-in [:cells cid] lifted)))
        cid)))


(defn- encoder
  "UCF 7.5.1's `encode` over values of `vm`, recording into `found` every
   closure origin segment, module store, and cursor cell the encoding
   reaches. A closure lifts through the kernel and names the module store
   its body resolves against: its own `:store-of`, or `own` when it has
   none -- a closure the child's own body made. Every literal map is
   wrapped, so a program's data can never forge a marker. A stream or
   cursor reference is authenticated before it is encoded (r11): its seal
   and resource kind must verify under the emitter's own secret, or it
   refuses as `:forged-resource-reference`; only then is it encoded,
   without its seal, as a stream marker or a cell reference. A
   continuation, a host object, and an unnamed host function refuse as
   UCF 7.5.4 kinds."
  [vm own found]
  (letfn [(encode
            [x]
            (cond
              (scalar? x) x
              (map? x)
              (case (:type x)
                :closure
                (let [marker (module/lift-closure vm x encode)
                      m (or (get marker store-of-key) own)]
                  (swap! found (fn [acc]
                                 (-> acc
                                     (update :origins conj
                                             (:yin.k/segment marker))
                                     (update :stores conj m))))
                  (assoc marker store-of-key m))
                (:stream-ref :cursor-ref)
                (cond
                  (not (authentic-ref? vm (:type x) x))
                  (non-portable! :forged-resource-reference
                                 {:yin.k/hint (:type x)})
                  (= :stream-ref (:type x)) (stream-marker vm (:id x))
                  :else {:yin.k/tag :yin.k/cursor-ref,
                         :yin.k/cell (cell-for! vm found (:id x))})
                (:reified-continuation :parked-continuation)
                (non-portable! :non-canonicalizable {:yin.k/hint (:type x)})
                {:yin.k/tag :yin.k/literal,
                 :yin.k/entries (into []
                                      (mapcat (fn [[k v]]
                                                [(encode k) (encode v)]))
                                      x)})
              (vector? x) (mapv encode x)
              (set? x) (into #{} (map encode) x)
              (seq? x) (apply list (map encode x))
              (fn? x) (encode-primitive vm x)
              :else (non-portable! :host-object
                                   {:yin.k/hint
                                    #?(:cljd (str (.-runtimeType x))
                                       :default (str (type x)))})))]
    encode))


(defn- decoder
  "UCF 7.5.1's `decode` into `vm`'s coordinates: every map is a marker, a
   closure lowers through the kernel, a primitive is the receiver's own
   function under an equal profile or `:yin.k/unsatisfied`, and a stream
   or cursor marker is a fresh reference sealed under the receiver's
   secret to the resource `lower-resources` installed for it -- `streams`
   maps a stream identity, `cells` a cell id, to a resource id."
  [vm streams cells]
  (letfn [(decode
            [x]
            (cond
              (map? x)
              (case (:yin.k/tag x)
                :yin.k/literal (into {}
                                     (map (fn [[k v]] [(decode k) (decode v)]))
                                     (partition 2 (:yin.k/entries x)))
                :yin.k/closure (module/lower-closure vm x decode)
                :yin.k/primitive
                (let [n (:yin.k/name x)
                      entry (get (:primitives vm) n)]
                  (if (and (some? entry)
                           (= (:yin.k/profile x)
                              (:yin.k/profile (vm/profile-of (:primitives vm)
                                                             n))))
                    (vm/primitive-function entry)
                    (fail "Primitive unsatisfied at the receiver"
                          {:yin.k/status :yin.k/unsatisfied,
                           :yin.k/name n,
                           :yin.k/profile (:yin.k/profile x)})))
                :yin.k/stream
                (issue-ref vm :stream-ref
                           (get streams (:dao.stream/identity x)))
                :yin.k/cursor-ref
                (issue-ref vm :cursor-ref (get cells (:yin.k/cell x)))
                (fail "Undecodable value"
                      {:yin.k/status :yin.k/undecodable,
                       :yin.k/tag (:yin.k/tag x)}))
              (vector? x) (mapv decode x)
              (set? x) (into #{} (map decode) x)
              (seq? x) (apply list (map decode x))
              :else x))]
    decode))


(defn- markers
  "Every marker tagged `tag` inside the encoded value `x`."
  [tag x]
  (filter #(and (map? %) (= tag (:yin.k/tag %)))
          (tree-seq coll? (fn [n] (if (map? n) (vals n) (seq n))) x)))


(defn- attach-stream!
  "The handle the composition's `:attach-stream` gives for a stream
   marker's descriptor, or `:yin.k/unsatisfied` when it has none or the
   attach does not answer `ok`."
  [state marker]
  (let [attach (:attach-stream state)
        result (when (fn? attach) (attach (:dao.stream/descriptor marker)))]
    (if (= :dao.stream/ok (outcome result))
      (:dao.stream/handle result)
      (fail "Stream reference cannot be attached at the receiver"
            {:yin.k/status :yin.k/unsatisfied,
             :dao.stream/identity (:dao.stream/identity marker),
             :outcome (outcome result)}))))


(defn- lower-resources
  "Resource lowering (r9): install, in the receiver's private
   `:resources`, an attached handle under a fresh resource id for every
   stream the encoded `values` name -- directly or through a cursor cell
   -- and a cursor cell seeded with its carried position for every cell
   they reference, two references to one cell sharing one entry. No store
   key is ever created. Returns `[state streams cells]`, the id maps the
   decoder remaps references by."
  [state values cells]
  (let [used (sort-by str (distinct (map :yin.k/cell
                                         (markers :yin.k/cursor-ref values))))
        lifted (mapv (fn [cid]
                       (or (get cells cid)
                           (fail "Undecodable value"
                                 {:yin.k/status :yin.k/undecodable,
                                  :yin.k/cell cid})))
                     used)
        [state streams]
        (reduce (fn [[s acc] marker]
                  (let [ident (:dao.stream/identity marker)]
                    (if (contains? acc ident)
                      [s acc]
                      (let [handle (attach-stream! s marker)
                            [id s] (gensym s "stream")]
                        [(assoc-in s [:resources id] handle)
                         (assoc acc ident id)]))))
                [state {}]
                (concat (markers :yin.k/stream values)
                        (map :yin.k/stream lifted)))
        [state cell-ids]
        (reduce (fn [[s acc] [cid cell]]
                  (let [[id s] (gensym s "cursor")
                        stream-id (get streams
                                       (get-in cell [:yin.k/stream
                                                     :dao.stream/identity]))]
                    [(assoc-in s [:resources id]
                               (vm/cursor-entry stream-id
                                                (:yin.k/position cell)))
                     (assoc acc cid id)]))
                [state {}]
                (map vector used lifted))]
    [state streams cell-ids]))


(defn- encode-store
  [encode store]
  (into {} (map (fn [[k v]] [(encode k) (encode v)])) store))


(defn- decode-store
  [decode snapshot]
  (into {} (map (fn [[k v]] [(decode k) (decode v)])) snapshot))


(defn lift-slice
  "Act 1 of `linked` (yin.vm.linker.md section 7.3): the halted child's
   exports and module stores in the portable encoding. `own` is the
   module's manifest address. Returns `{:slice {sym encoded} :stores
   {address snapshot} :cells {cell-id cell} :origins #{segment}}`: one
   snapshot per module -- the child's own store under `own`, and every
   dependency store the encoding reaches as it stands in the child at
   halt, mutations included -- and the logical cursor cells the encoded
   references name. A `:store-of` with no snapshot is
   `:yin.k/non-portable` of kind `:missing-module-store`; any refused leaf
   refuses the lift."
  [child own exports]
  (let [found (atom {:origins #{}, :stores #{}, :cells {}, :cell-of {}})
        encode (encoder child own found)
        store (:store child)
        slice (into {} (map (fn [k] [k (encode (get store k))])) exports)
        stores (loop [stores {own (encode-store encode store)}]
                 (let [pending (remove #(contains? stores %)
                                       (sort-by str (:stores @found)))]
                   (if (empty? pending)
                     stores
                     (recur
                       (reduce
                         (fn [acc m]
                           (if-let [s (get-in child [:module-stores m])]
                             (assoc acc m (encode-store encode s))
                             (non-portable! :missing-module-store
                                            {store-of-key m})))
                         stores
                         pending)))))]
    {:slice slice,
     :stores stores,
     :cells (:cells @found),
     :origins (:origins @found)}))


(defn receive-module
  "Acts 3 and 4 of `linked` for one receiving task (yin.vm.linker.md
   section 7.3): attach every origin image of `entry` the kernel does not
   yet hold, non-destructively; lower the resources the slice and the
   snapshots it instantiates reference into the task's private
   `:resources` (r9); lower the slice against this task's own
   coordinates into the entry's `:bindings`, every reference re-sealed
   under this task's secret (r10); and instantiate each store snapshot
   into `:module-stores` unless the task already holds that module's
   store -- first link wins, a live instance is never overwritten.
   Nothing is written into the task's own `:store`."
  [state module-name entry]
  (let [attached (reduce module/attach-module state
                         (map val (sort-by key (:images entry))))
        held (or (:module-stores attached) {})
        fresh (into {} (remove #(contains? held (key %))) (:stores entry))
        [attached streams cells]
        (lower-resources attached
                         (concat (vals (:slice entry)) (vals fresh))
                         (:cells entry))
        decode (decoder attached streams cells)
        bindings (into {}
                       (map (fn [[k v]] [k (decode v)]))
                       (:slice entry))
        stores (into held
                     (map (fn [[m snapshot]]
                            [m (decode-store decode snapshot)]))
                     fresh)]
    (-> attached
        (assoc :module-stores stores)
        (update :modules module/assoc-module module-name
                (assoc entry :bindings bindings)))))


;; =============================================================================
;; Link waits and the install child (yin.vm.linker.md sections 7.2 to 7.4)
;; =============================================================================

(def ^:private link-reasons
  "The wait reasons `require` adds: the two link states and the install."
  #{:link-request :link-response :install})


(defn- link-entry?
  [entry]
  (contains? link-reasons (:reason entry)))


(defn- ready
  "A woken ready entry: the registers, resumed with `value`."
  [entry value]
  (-> entry
      (dissoc :envelope :request :response :cursor)
      (assoc :value value)))


(defn- refused-entry
  "A woken ready entry that raises `refusal` as the effect's error at its
   resume, the way an FFI `error` raises."
  [entry refusal]
  (assoc (ready entry nil) :status :link-refused :refusal refusal))


(defn- retire
  "Record link `id` as retired: a response for it is afterwards a
   duplicate (`:restored`) or late (`:abandoned`)."
  [state id how]
  (assoc-in state [:link-retired id] how))


(defn- skip
  "Record one skipped response (section 7.2, step 7): a telemetry
   diagnostic, and one entry under `:link-diagnostics`. A response another
   live entry of this task awaits is not a skip kind and is passed over
   silently."
  [state entry id]
  (let [retired (get-in state [:link-retired id])
        kind (cond (= :restored retired) :duplicate
                   (= :abandoned retired) :late
                   (some #(= id (:link-id %)) (:wait-set state)) nil
                   :else :unknown)]
    (if (nil? kind)
      state
      (let [d {:kind kind, :id id, :entry (:link-id entry)}]
        (-> state
            (update :link-diagnostics (fnil conj []) d)
            (telemetry/emit-snapshot :link-skip d))))))


(defn take-link-diagnostics
  "Every skip diagnostic recorded since the last take, and the state
   without them: each is handed out exactly once."
  [state]
  [(vec (:link-diagnostics state)) (dissoc state :link-diagnostics)])


(defn- obligation-name
  [obligation]
  (if (map? obligation) (:name obligation) obligation))


(defn- discharge-defect
  "Step 5b against the live state of the receiving task (section 7.2,
   step 7): `yin.vm.linker/discharge` over the response's obligations,
   then profile equality for every obligation the manifest names under
   `:yin.module/primitives` -- a same-named primitive of a different
   profile is `:unresolved-free`. A namespaced obligation of a module the
   manifest requires is the install's to satisfy: the child requires it,
   and the receiving task receives it at `linked`."
  [state response]
  (let [manifest (:manifest response)
        requires (set (keys (:yin.module/requires manifest)))
        deferred? (fn [o]
                    (let [n (obligation-name o)]
                      (and (symbol? n)
                           (some? (namespace n))
                           (contains? requires (symbol (namespace n))))))
        obligations (remove deferred? (:obligations response))
        receiver {:free-env (or (:free-env state) {}),
                  :store (:store state),
                  :primitives (:primitives state),
                  :modules (:modules state)}]
    (or (linker/discharge receiver obligations)
        (some (fn [o]
                (let [n (obligation-name o)
                      expected (get-in manifest [:yin.module/primitives n])
                      actual (:yin.k/profile
                               (or (vm/profile-of (:primitives state) n)
                                   (get (:primitive-profiles state) n)))]
                  (when (and (some? expected) (not= expected actual))
                    (linker/refused :unresolved-free
                                    {:name n,
                                     :expected expected,
                                     :actual actual}))))
              obligations))))


(defn- error-refusal
  "The refusal an install raises for a thrown error: the error's own
   `:reason` when it carries one (`:require-cycle`), its non-portable
   status when a lift refused, and `:install-error` otherwise."
  [module-name e]
  (let [d (or (ex-data e) {})]
    (merge d
           {:status :refused,
            :reason (or (:reason d)
                        (when (= :yin.k/non-portable (:yin.k/status d))
                          :yin.k/non-portable)
                        :install-error),
            :module module-name,
            :message (ex-message e)})))


(defn- linked-entries
  "The `[name entry]` pairs of a registry that were linked, not
   host-registered: they carry a manifest address."
  [registry]
  (filterv (fn [[_ entry]] (some? (:address entry)))
           (module/module-entries registry)))


(defn- manifest-address
  "The manifest's address: a manifest is content named by its address."
  [response]
  (jing/segment-key (:manifest response)))


(defn- spawn-child
  "`loading` (section 7.3): a fresh child of the parent's backend over the
   verified image, under a fresh origin tag, its install ancestry the
   parent's plus this module, and a module view of its own: each linked
   entry the parent holds is attached and lowered into the child's
   coordinates here, never the parent's lowered values reused. The
   child's capability secret comes from the composition's
   `:secret-source`, a function of the child's origin tag; a composition
   that supplies none gives the child no secret, so the child can issue
   no resource reference (fail closed)."
  [state module-name response]
  (let [n (or (:origins state) 0)
        origin (keyword (str (name (or (:origin state) :t0)) "." n))
        registry (reduce (fn [r [m entry]]
                           (module/assoc-module r m (dissoc entry :bindings)))
                         (:modules state)
                         (linked-entries (:modules state)))
        source (:secret-source state)
        child (module/spawn-module state
                                   (:value (:image response))
                                   {:modules registry,
                                    :origin origin,
                                    :ancestry (conj (vec (:ancestry state))
                                                    module-name),
                                    ;; the composition mints each task's
                                    ;; secret (r10); without a source the
                                    ;; child can issue no reference
                                    :capability-secret
                                    (when (fn? source) (source origin))})
        child (reduce (fn [c [m entry]] (receive-module c m entry))
                      child
                      (linked-entries registry))]
    [child (assoc state :origins (inc n))]))


(defn- wake-installed
  "Move every `:install` wait entry naming `module-name` to the ready
   queue, woken with `value`, or with `refusal` raised."
  [state module-name value refusal]
  (let [waiter? (fn [e]
                  (and (= :install (:reason e)) (= module-name (:name e))))
        waiters (filter waiter? (:wait-set state))]
    (-> state
        (update :wait-set (fn [ws] (filterv (complement waiter?) ws)))
        (update :ready-queue (fnil into [])
                (map (fn [e]
                       (if refusal (refused-entry e refusal) (ready e value))))
                waiters))))


(defn- refuse-install
  "`refused` (section 7.3): nothing from the child's store is published,
   every waiter is restored with the refusal as the effect's error, and
   the install entry is removed so a later require may try again."
  [state module-name refusal]
  (-> state
      (update :installs dissoc module-name)
      (wake-installed module-name nil refusal)))


(defn- verified-images
  "Every image this scheduler verified that the child's slice may name as
   an origin: the install's own, and those held by identity under the
   registry of the child or of the receiving task (section 7.3, act 2)."
  [state child response]
  (into [(:value (:image response))]
        (mapcat (fn [[_ entry]] (vals (:images entry))))
        (concat (linked-entries (:modules child))
                (linked-entries (:modules state)))))


(defn- link-install
  "`validated` then `linked` (section 7.3): the child reached halt; every
   export must be a key of its store (`:export-missing`); the slice is
   lifted (act 1) and its origins checked against the images this
   scheduler verified (act 2, `:foreign-image`); the receiving task then
   receives, first, the entry -- its store snapshots win over the plain
   registry snapshot of a dependency, since the child's mutations are
   what the slice carries -- and then every module the child linked that
   it lacks (acts 3 and 4), and every waiter is restored with the name."
  [state module-name {:keys [vm response]}]
  (let [manifest (:manifest response)
        address (manifest-address response)
        exports (sort-by str (:yin.module/exports manifest))
        missing (vec (remove #(contains? (:store vm) %) exports))]
    (if (seq missing)
      (refuse-install state module-name
                      {:status :refused,
                       :reason :export-missing,
                       :module module-name,
                       :names missing})
      (let [lifted (try (lift-slice vm address exports)
                        (catch #?(:cljd Object :clj Throwable :cljs :default) e
                          {:refusal (error-refusal module-name e)}))]
        (if-let [refusal (:refusal lifted)]
          (refuse-install state module-name refusal)
          (let [verified (verified-images state vm response)
                origin-of (fn [seg]
                            (some #(when (module/image-holds? vm % seg) %)
                                  verified))
                foreign (first (remove origin-of
                                       (sort-by str (:origins lifted))))]
            (if (some? foreign)
              (refuse-install state module-name
                              {:status :refused,
                               :reason :foreign-image,
                               :module module-name,
                               :segment foreign})
              (let [images (into {}
                                 (map (fn [seg]
                                        (let [img (origin-of seg)]
                                          [(module/image-identity vm img)
                                           img])))
                                 (:origins lifted))
                    entry (module/module-entry
                            (:modules (module/link-module
                                        nil manifest (:derivation response)
                                        (assoc lifted :images images)))
                            module-name)
                    received (receive-module state module-name entry)
                    received (reduce
                               (fn [s [m dep]]
                                 (if (some? (module/module-entry
                                              (:modules (:modules s)) m))
                                   s
                                   (receive-module s m (dissoc dep :bindings))))
                               received
                               (linked-entries (:modules vm)))]
                (-> received
                    (update :installs dissoc module-name)
                    (wake-installed module-name module-name nil))))))))))


(defn- advance-install
  "Step the install child of `module-name` in this round, beside every
   other task (section 7.3): `running` runs it until it halts or parks.
   A parked child stays `parked`; a halted one goes on to `validated`; an
   error refuses the install. An error while publishing -- attaching an
   origin image, lowering the slice or a store snapshot, or receiving a
   dependency -- refuses it too, at phase `:linked`, from the state the
   round started with: nothing half-published survives, every waiter is
   restored with the refusal, and no throw leaves the round."
  [state module-name]
  (let [inst (get-in state [:installs module-name])
        [child refusal]
        (try [(vm/run (:vm inst)) nil]
             (catch #?(:cljd Object :clj Throwable :cljs :default) e
               [nil (error-refusal module-name e)]))]
    (cond
      refusal (refuse-install state module-name refusal)
      (:blocked? child)
      (assoc-in state [:installs module-name]
                (assoc inst :vm child :phase :parked))
      (:halted? child)
      (try (link-install state module-name (assoc inst :vm child))
           (catch #?(:cljd Object :clj Throwable :cljs :default) e
             (refuse-install state module-name
                             (assoc (error-refusal module-name e)
                                    :phase :linked))))
      :else (assoc-in state [:installs module-name]
                      (assoc inst :vm child :phase :running)))))


(defn- advance-installs
  [state]
  (reduce advance-install state (sort-by str (keys (:installs state)))))


(defn- start-install
  "`loading`: spawn the child, or refuse on a loader defect. Returns
   `[state refusal]`."
  [state module-name response link-id]
  (try
    (let [[child state] (spawn-child state module-name response)]
      [(assoc-in state [:installs module-name]
                 {:phase :running, :vm child, :parent link-id,
                  :response response})
       nil])
    (catch #?(:cljd Object :clj Throwable :cljs :default) e
      [state (assoc (error-refusal module-name e) :phase :loading)])))


(defn- settle
  "Act on the response correlated with `entry`: a refusal (or loss) is
   raised as the effect's error; `:ok` runs step 5b against the live
   state and, discharged, starts the install child, the entry becoming
   an `:install` waiter. A module linked or installing meanwhile answers
   or joins instead, and a manifest naming another module than the one
   required is `:module-name-mismatch`, never installed. Returns `[state
   waiting woken]`."
  [state entry response]
  (let [state (retire state (:link-id entry) :restored)
        module-name (:name entry)
        waiter (-> entry
                   (dissoc :envelope :request :response :cursor :link-id)
                   (assoc :reason :install))]
    (cond
      (not= :ok (:status response))
      [state nil (refused-entry entry response)]

      (some? (module/resolve-module (:modules state) module-name))
      [state nil (ready entry module-name)]

      (contains? (:installs state) module-name) [state waiter nil]

      ;; the linker checks the name (section 8.1); a response that names
      ;; another module is never installed under this one
      (not= module-name (get-in response [:manifest :yin.module/name]))
      [state nil
       (refused-entry entry
                      (linker/refused :module-name-mismatch
                                      {:requested module-name,
                                       :manifest (get-in
                                                   response
                                                   [:manifest
                                                    :yin.module/name])}))]

      :else
      (if-let [refusal (discharge-defect state response)]
        [state nil (refused-entry entry refusal)]
        (let [[state refusal] (start-install state module-name response
                                             (:link-id entry))]
          (if refusal
            [state nil (refused-entry entry refusal)]
            [state waiter nil]))))))


(defn- poll-link-response
  "Poll a `:link-response` entry's kept cursor (section 7.2, step 7):
   advance past every response whose `:yin.link/id` is not its own,
   keeping the cursor at the first unconsumed position, and act only on
   exact correlation. `blocked` keeps waiting; `gap` and the terminal
   outcomes are raised as the effect's error. Returns `[state waiting
   woken]`."
  [state entry]
  (let [reader (get (:resources state) module/link-response-resource)]
    (loop [state state
           cursor (:cursor entry)]
      (let [r (stream/next reader cursor)
            o (outcome r)]
        (case o
          :dao.stream/ok
          (let [v (:dao.stream/value r)
                id (when (map? v) (:yin.link/id v))
                entry (assoc entry :cursor (:dao.stream/cursor r))]
            (if (= id (:link-id entry))
              (settle state entry v)
              (recur (skip state entry id) (:cursor entry))))
          :dao.stream/blocked [state (assoc entry :cursor cursor) nil]
          [(retire state (:link-id entry) :restored)
           nil
           (refused-entry entry
                          {:status :refused,
                           :reason :link-stream,
                           :outcome o,
                           :link-id (:link-id entry)})])))))


(defn- poll-link-entry
  "One poll of a link wait entry: a `:link-request` retries its retained
   envelope and, appended, polls on as a `:link-response`; an `:install`
   waits for its install. Returns `[state waiting woken]`."
  [state entry]
  (case (:reason entry)
    :link-request
    (let [entry' (module/append-link-request (:resources state) entry)]
      (if (= :link-response (:reason entry'))
        (poll-link-response state entry')
        [state entry' nil]))
    :link-response (poll-link-response state entry)
    :install [state entry nil]))


(defn- poll-links
  "Poll every link wait entry once, in wait-set order, and then step the
   install children. Returns the state with the still-waiting link
   entries appended to `others` and the woken ones queued."
  [state links others]
  (let [[state waiting woken]
        (reduce (fn [[state waiting woken] entry]
                  (let [[state w k] (poll-link-entry state entry)]
                    [state
                     (cond-> waiting w (conj w))
                     (cond-> woken k (conj k))]))
                [state [] []]
                links)]
    (-> state
        (assoc :wait-set (into (vec others) waiting))
        (update :ready-queue (fnil into []) woken)
        advance-installs)))


(defn abandon-link
  "A composition gives up on link `link-id` (section 7.2, step 7): the
   entry leaves the wait set and is restored with `reason` raised as the
   effect's error; the id is retired, so a response arriving later is
   `:late`. The linker side's `abandon` is the composition's call. An id
   no entry holds leaves the state unchanged."
  [state link-id reason]
  (let [held? (fn [e]
                (and (contains? #{:link-request :link-response} (:reason e))
                     (= link-id (:link-id e))))]
    (if-let [entry (first (filter held? (:wait-set state)))]
      (-> state
          (update :wait-set (fn [ws] (filterv (complement held?) ws)))
          (retire link-id :abandoned)
          (update :ready-queue (fnil conj [])
                  (refused-entry entry
                                 {:status :lost,
                                  :reason reason,
                                  :link-id link-id})))
      state)))


(defn abandon-installs
  "A composition gives up on every install in flight (the abandoned case
   of section 7.2, step 7, reaching section 7.3's `refused`): each child
   is dropped -- nothing from its store is published -- and every
   `:install` waiter is restored with the reason as the require's error,
   so the install entry is removed and a later require may try again. A
   state with no installs is unchanged. The dropped children's own link
   ids are never minted again by a state that carries `:origins` forward
   past them."
  [state reason]
  (reduce (fn [state module-name]
            (refuse-install state module-name
                            {:status :lost, :reason reason}))
          state
          (keys (:installs state))))


(defn check-wait-set
  "Check wait-set entries against their transports.

   The sweep is `dao.stream.waitset/check` — this engine was that library's
   seed and is now its first consumer. Every entry is resolved to a live
   handle and an opaque cursor out of the store by `waitset-resolver` and
   polled with a synchronous `next` or `append!` — resolution happens per
   poll, so the stored entry itself never carries a handle. There is no
   transport-local waking to fall back from: this is the only mechanism.

   Entries are polled one at a time in wait-set order, and a woken reader's
   successor cursor is written back to the store before the next entry is
   resolved. Waiters sharing a cursor-ref therefore read distinct values —
   the value at the cursor wakes the first, its successor wakes the next —
   instead of every waiter reading the value at the shared pre-poll
   cursor. An entry that stays waiting is retained in its stored,
   resource-id form.

   Each woken result's `:status` is stamped onto its ready entry, where
   `terminal-resume-outcome` reads it: an outcome the immediate operation
   raises as an error fails the same way when the resume pops it — and a
   waitset diagnostic, an entry the sweep could not poll at all, raises
   there too, before any continuation is restored.

   The link wait entries `require` adds (yin.vm.linker.md section 7.2) are
   polled first, by the engine rather than the stream sweep: a
   `:link-request` retries its envelope, a `:link-response` advances its
   own kept cursor to the response carrying its id, and an `:install`
   waits on its install child. The install children are then stepped in
   this same round (section 7.3), so a child that halts restores its
   waiters here; nothing about a child runs inside a restore."
  [state]
  (let [wait-set (:wait-set state)]
    (if (and (empty? wait-set) (empty? (:installs state)))
      state
      (let [state (poll-links state
                              (filterv link-entry? wait-set)
                              (remove link-entry? wait-set))
            wait-set (:wait-set state)
            streams (filterv (complement link-entry?) wait-set)
            {:keys [woken store], :as result}
            (waitset/check {:waiting streams}
                           waitset-resolver
                           (:resources state))
            v (assoc state
                     :resources store
                     :wait-set (into (:waiting (:waitset result))
                                     (filter link-entry?)
                                     wait-set))]
        (update v
                :ready-queue (fnil into [])
                (make-woken-run-queue-entries v woken))))))


(declare handle-effect resume-continuation)


(defn run-loop
  "Generic eval loop with scheduler support. Ready entries are pure data;
   `resume-fn` pops and restores them."
  [state active? step-fn resume-fn]
  (loop [v state]
    (let [q (or (:ready-queue v) [])]
      (cond (active? v) (recur (step-fn v))
            (:blocked? v) (let [v' (check-wait-set v)]
                            (if-let [resumed (resume-fn v')]
                              (recur resumed)
                              (telemetry/emit-snapshot v' :blocked)))
            (seq q) (if-let [resumed (resume-fn v)]
                      (recur resumed)
                      v)
            :else (if (:halted? v) (telemetry/emit-snapshot v :halt) v)))))


(def ^:private waitset-diagnostics
  "The statuses `dao.stream.waitset/check` wakes an entry it could not poll
   at all with: an unsupported reason, an unresolvable entry, or an answer —
   or transport — it could not interpret. No park site in `yin.vm` produces
   an entry that earns one, and an entry that earns one must not reach a
   restore function as a value."
  #{:dao.stream.waitset/unsupported-reason
    :dao.stream.waitset/unresolved
    :dao.stream.waitset/invalid-answer})


(defn- terminal-resume-outcome
  "The outcome a woken entry resolved with, when it is one the immediate
   path raises as an error. `handle-put` and `handle-next` throw for every
   outcome outside ok/end/gap, and whether the first attempt blocked must not
   change that. A waitset diagnostic is terminal whatever the entry's
   `:reason` — an unsupported reason is by definition outside the
   `#{:next :put}` this check otherwise reads — so it is named here rather
   than falling through to the restore. An entry that was never polled
   carries no status."
  [entry]
  (let [status (:status entry)]
    (cond
      (contains? waitset-diagnostics status) status
      (= :link-refused status) status
      (contains? #{:next :put} (:reason entry))
      (when-not (contains? #{nil :ok :end :dao.stream/gap} status) status))))


(defn- throw-terminal-resume!
  "Fail a resumed entry exactly as the immediate operation would have. A
   waitset diagnostic names its status and the entry that earned it: there
   is no stream operation whose error vocabulary it belongs to."
  [entry o]
  (cond
    (contains? waitset-diagnostics o)
    (fail "Wait-set entry woke with a diagnostic" {:status o, :entry entry})
    ;; a refused or lost link, or a refused install: the effect's error
    (= :link-refused o)
    (fail (str "Module link refused: "
               (clojure.core/name (or (:reason (:refusal entry)) :refused)))
          (assoc (:refusal entry) :link-module (:name entry)))
    (= :next (:reason entry))
    (fail "Stream read failed"
          {:outcome o,
           :stream-id (:stream-id entry),
           :cursor-id (:id (:cursor-ref entry))})
    :else (fail "Stream append failed"
                {:outcome o, :stream-id (:stream-id entry)})))


(defn resume-from-run-queue
  "Pop first entry from the ready-queue, merge its resource updates into
   the private `:resources` table, and restore VM-specific context.

   Restoration is dispatched here rather than carried on the entry, so a
   ready entry holds registers, ids, and values — never a closure. The
   terminal-outcome check runs before the restore: a woken retry that ended
   in an outcome the immediate operation raises as an error must fail the
   same way here, not reach Yin code as a value.

   `restore-fn` is called with three arguments, `base entry val`, where
   `val` is the woken `:value` — the same signature `resume-continuation`
   uses, so one restore serves both engine call sites
   (`yin.vm.engine.md` §7, edit 2)."
  [state restore-fn]
  (let [run-queue (or (:ready-queue state) [])]
    (when (seq run-queue)
      (let [entry (first run-queue)
            rest-queue (subvec run-queue 1)
            updates (:resource-updates entry)
            ;; a woken entry only ever carries engine-minted keyword keys
            ;; (cursor ids)
            _ (when-let [k (some #(when-not (keyword? %) %) (keys updates))]
                (fail "Ready entry resource update carries a non-minted key"
                      {:rule :resource-update-key, :key k}))
            base (assoc state
                        :ready-queue rest-queue
                        :resources (merge (:resources state) updates)
                        :blocked? false
                        :halted? false)]
        (if-let [terminal (terminal-resume-outcome entry)]
          (throw-terminal-resume! entry terminal)
          (restore-fn base entry (:value entry)))))))


(defn scheduler-round
  "One round between continuations: poll the wait set, then resume whatever
   woke, through `restore-fn` (`base entry val -> state`, the one restore
   signature `yin.vm.engine.md` §2.1 states). Returns the polled state when
   nothing woke, so a caller always gets a state back.

   This is `yin.vm.semantic/scheduler-round` with its restore made a
   parameter (`yin.vm.engine.md` §7, edit 1); every VM was writing this
   composition by hand."
  [state restore-fn]
  (let [v' (check-wait-set state)]
    (or (resume-from-run-queue v' restore-fn) v')))


(defn park-continuation
  "Add a parked continuation entry and halt the VM."
  [state cont-fields]
  (let [id-counter (or (:id-counter state) 0)
        park-id (keyword (str "parked-" id-counter))
        parked (merge {:type :parked-continuation, :id park-id} cont-fields)]
    (-> state
        (update :parked assoc park-id parked)
        (assoc :value parked
               :halted? true
               :id-counter (inc id-counter))
        (telemetry/emit-snapshot :park {:parked-id park-id}))))


(defn resume-continuation
  "Restore state from a parked continuation."
  [state parked-id resume-val restore-fn]
  (if-let [parked (get-in state [:parked parked-id])]
    (let [new-state (update state :parked dissoc parked-id)]
      (-> (restore-fn new-state parked resume-val)
          (telemetry/emit-snapshot :resume {:parked-id parked-id})))
    (fail "Cannot resume: parked continuation not found"
          {:parked-id parked-id})))


(defn gensym
  "Generate a unique ID and return [id updated-state]."
  ([state] (gensym state "id"))
  ([state prefix]
   (let [id-counter (or (:id-counter state) 0)
         id (gen-id prefix id-counter)]
     [id (assoc state :id-counter (inc id-counter))])))


(defn handle-stream-block
  [result entry]
  (if (:park result)
    (let [entry (or entry
                    (fail "Parked entry required for blocking stream"
                          {:result result}))
          new-state (-> (:state result)
                        (update :wait-set (fnil conj []) entry)
                        (assoc :value :yin/blocked
                               :blocked? true
                               :halted? false))]
      {:state new-state, :value :yin/blocked, :blocked? true})
    {:state (:state result), :value (:value result), :blocked? false}))


(defn handle-effect
  "Dispatch an effect and return {:state updated-state :value v :blocked? bool}.
   park-entry-fns maps :stream/put and :stream/next to functions that build
   wait entries.

   A parked entry is stored as the builder left it plus, for a writer, the
   `:datom` it retries: registers and resource ids only. Neither the live
   stream handle nor a restore closure is attached — `check-wait-set`
   resolves handles from `:resources` at every poll, and the scheduler that pops
   a woken entry dispatches restoration itself — so a blocked entry is pure
   data and survives serialization."
  [state effect {:keys [park-entry-fns], :as opts}]
  (let [park-entry (get park-entry-fns (:effect effect))
        result
        (case (:effect effect)
          :vm/store-put {:state (put-active state
                                            (store-context state)
                                            (:key effect)
                                            (:val effect)),
                         :value (:val effect),
                         :blocked? false}
          :stream/make
          (let [[id s'] (gensym state "stream")
                [stream-ref new-state] (handle-make s' effect id)]
            {:state new-state, :value stream-ref, :blocked? false})
          :stream/cursor
          (let [[id s'] (gensym state "cursor")
                [cursor-ref new-state] (handle-cursor s' effect id)]
            {:state new-state, :value cursor-ref, :blocked? false})
          :stream/put
          (let [result (handle-put state effect)]
            (if (:park result)
              (let [built-entry (when park-entry
                                  (park-entry state effect result))
                    built-entry (if (and built-entry (not (:datom built-entry)))
                                  (assoc built-entry :datom (:val effect))
                                  built-entry)]
                (handle-stream-block result built-entry))
              {:state (:state result),
               :value (:value result),
               :blocked? false}))
          :stream/next
          (let [result (handle-next state effect)]
            (if (:park result)
              (let [built-entry (when park-entry
                                  (park-entry state effect result))]
                (handle-stream-block result built-entry))
              {:state (:state result),
               :value (:value result),
               :blocked? false}))
          :stream/close
          (let [close-result (handle-close state effect)]
            {:state (:state close-result), :value nil, :blocked? false})
          (let [[id s'] (gensym state "effect")
                handler (module/get-effect-handler (:modules state)
                                                   (:effect effect))]
            (if handler
              (handler s' effect (assoc opts :id id))
              (fail "Unknown effect" {:effect (:effect effect)}))))]
    (update result
            :state
            (fn [result-state]
              (telemetry/emit-snapshot (assoc result-state
                                              :value (:value result))
                                       :effect
                                       {:effect-type (:effect effect)})))))


;; =============================================================================
;; Program Cache Machinery
;; =============================================================================

(def default-compiled-cache-limit 8)


(defn build-program-index
  [datoms]
  (group-by first (vec datoms)))


(defn frame-versions
  [frame]
  (loop [f frame
         acc #{}]
    (if (map? f)
      (let [acc (cond-> acc (:compiled-version f) (conj (:compiled-version f)))]
        (recur (:next f) acc))
      acc)))


(defn collect-frame-versions
  [frames]
  (reduce (fn [acc f] (into acc (frame-versions f))) #{} (or frames [])))


(defn pinned-compiled-versions
  [vm]
  (let [active (:active-compiled-version vm)
        k-versions (frame-versions (:k vm))
        parked-versions (collect-frame-versions (vals (:parked vm)))
        ready-versions (collect-frame-versions (:ready-queue vm))
        wait-versions (collect-frame-versions (:wait-set vm))]
    (cond-> (clojure.set/union k-versions
                               parked-versions
                               ready-versions
                               wait-versions)
      active (conj active))))


(defn trim-compiled-cache
  [vm]
  (let [cache (:compiled-by-version vm)
        limit
        (max 1 (or (:compiled-cache-limit vm) default-compiled-cache-limit))]
    (if (> (count cache) limit)
      (let [pinned (pinned-compiled-versions vm)
            sorted-versions (reverse (sort (keys cache)))
            to-keep (into pinned (take limit sorted-versions))]
        (assoc vm :compiled-by-version (select-keys cache to-keep)))
      vm)))


(defn cache-compiled-artifact
  [vm version artifact program-index]
  (-> vm
      (assoc :compile-dirty? false
             :datom-index program-index
             :active-compiled-version version)
      (update :compiled-by-version (fnil assoc {}) version artifact)
      trim-compiled-cache))


(defn ensure-compiled-version
  [vm version compile-fn]
  (if-let [artifact (get-in vm [:compiled-by-version version])]
    [vm artifact]
    (let [program-datoms (vec (or (:datoms vm) []))
          program-root-eid (:program-root-eid vm)]
      (when (or (empty? program-datoms) (nil? program-root-eid))
        (fail "Canonical program is not loaded" {:program-version version}))
      (let [program-index (or (:datom-index vm)
                              (build-program-index program-datoms))
            artifact (compile-fn program-root-eid program-datoms program-index)
            vm' (cache-compiled-artifact vm version artifact program-index)]
        [vm' artifact]))))


(defn maybe-recompile-at-boundary
  [vm compile-fn]
  (if (and (:compile-dirty? vm)
           (seq (:datoms vm))
           (some? (:program-root-eid vm)))
    (ensure-compiled-version vm (:program-version vm) compile-fn)
    [vm nil]))
