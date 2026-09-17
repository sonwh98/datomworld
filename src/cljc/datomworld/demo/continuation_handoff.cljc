(ns datomworld.demo.continuation-handoff
  "Handoff payload keys for Yin VM v2 continuation transfer.

   v1 handed a continuation between a register VM and a stack VM, so it needed
   one key list per engine shape. For the ast-walker there is one payload
   shape and one key list, and the two endpoints differ only in which VM they
   are. The semantic VM's handoff, below, ships in-band instead.

   What the payload carries: the CESK machine (`:control`, `:env`, `:k`,
   `:store`), the scheduler (`:ready-queue`, `:wait-set`, `:parked`,
   `:id-counter`), the observable state (`:halted?`, `:blocked?`, `:value`),
   and the loaded program (`:program`). What it deliberately omits:

   - `:in-stream` and `:in-cursor`. The v2 VM owns no program medium —
     `dao.stream.observer` does — and `create-vm` rejects `:in-stream`.
   - `:make-stream`, `:primitives`, `:modules`, `:bridge`, `:call-capacity`,
     `:telemetry`. Those are composition, not execution state. `handoff->vm`
     takes them from the receiving side's freshly constructed VM, which is
     what makes a payload movable: a function is not a value this demo
     pretends to serialize."
  (:require [clojure.edn :as edn]
            [clojure.walk :as walk]
            [yin.vm :as vm]
            [yin.vm.semantic :as semantic]))


(def handoff-keys
  [:program :control :env :k :store :parked :id-counter :ready-queue :wait-set
   :halted? :blocked? :value])


(defn vm-key->model
  "The evaluator model a vm-key names. Both endpoints are the ast-walker; the
   keys are kept so a v2 demo reads like the v1 one it replaced."
  [_vm-key]
  :ast-walker)


(defn vm-state->handoff
  "Project a v2 VM onto the portable handoff payload."
  [vm-key vm-state]
  (when vm-state
    (assoc (select-keys vm-state handoff-keys) :vm-model (vm-key->model vm-key))))


(defn handoff->vm-state
  "Rebuild a v2 VM from a handoff payload.

   The base VM supplies the composition — `:make-stream`, `:primitives`,
   `:modules`, `:bridge`, `:call-capacity` — so the payload never has to carry
   a function. `:primitives` is not re-associated afterwards the way the v1
   version did: the ast-walker resolves variables through the store and the
   module registry value, not through a mutable primitives field on the
   payload, so overwriting the base's would discard the registry this
   composition supplied."
  [vm-key payload make-vm]
  (let [base (make-vm vm-key)]
    (merge base (vm-state->handoff vm-key payload))))


;; =============================================================================
;; Semantic VM: the continuation is the datoms that travel
;; =============================================================================
;;
;; A semantic VM between instructions is a code segment plus five registers —
;; {:segment :pc :value :stack :env :k} — all plain data (yin.vm.semantic.md
;; §7). Nothing travels off-stream: the handoff batch is the segment's
;; `:yin.code/*` datoms followed by one registers datom whose value is EDN
;; text, so the receiver rebuilds the machine from bytes it read, not from a
;; host object it shares with the sender. Definitions the program made
;; (symbol-keyed store entries) travel with the registers; primitives,
;; modules, and the FFI pair are the receiving composition's.

(def registers-attr :yin.continuation/registers)


(def tag-key
  "The one key the register encoding interprets. A map carrying it is a tag:
   `{tag-key :primitive, :name n}` is a host primitive, resolved by name
   against the receiver's primitives, and `{tag-key :quote, :value m}` is an
   ordinary map `m` that happened to carry `tag-key` itself. Every other value
   is literal data, so no program value can decode as executable identity."
  :yin.continuation/tag)


(defn- encode-primitives
  "Replace each host function in `x` with a primitive tag naming it in
   `primitives`, and quote any ordinary map that carries `tag-key`. Throws on
   a host function that is not a primitive: it has no portable encoding
   (§7 item 2)."
  [primitives x]
  (let [names (into {} (map (fn [[n f]] [f n])) primitives)]
    (walk/postwalk (fn [v]
                     (cond (fn? v)
                           (if-let [n (get names v)]
                             {tag-key :primitive, :name n}
                             (throw (ex-info "Cannot ship a continuation holding a host function"
                                             {:value (str v)})))
                           (and (map? v) (contains? v tag-key))
                           {tag-key :quote, :value v}
                           :else v))
                   x)))


(defn- decode-primitives
  "Invert `encode-primitives`. Walks top-down so a quoted map's own
   `tag-key` is never read as a tag; only its contents are decoded."
  [primitives x]
  (let [decode-children #(walk/walk (partial decode-primitives primitives)
                                    identity
                                    %)]
    (if (and (map? x) (contains? x tag-key))
      (case (get x tag-key)
        :primitive (or (get primitives (:name x))
                       (throw (ex-info "The receiver has no such primitive"
                                       {:name (:name x)})))
        :quote (decode-children (:value x))
        (throw (ex-info "Unknown continuation tag" {:tag (get x tag-key)})))
      (decode-children x))))


(defn- composition-key?
  "Store keys the receiving composition supplies for itself: the FFI pair."
  [k]
  (contains? #{vm/call-in-stream-key vm/call-out-stream-key
               vm/call-out-cursor-key}
             k))


(defn resource-keys
  "Store keys holding live resources the registers cannot carry: anything
   that is neither a definition (a symbol) nor the composition's FFI pair.
   These are stream handles made by `:stream-make`; a `{:type :stream-ref}`
   in the registers names one, and on the receiver it would name nothing."
  [vm-state]
  (into []
        (remove #(or (symbol? %) (composition-key? %)))
        (keys (:store vm-state))))


(defn shippable?
  "True when a semantic VM is between instructions with nothing outside its
   registers: no wait set, ready queue, or parked continuation, and no stream
   handle in its store."
  [vm-state]
  (boolean (and vm-state
                (:control vm-state)
                (not (:halted? vm-state))
                (not (:blocked? vm-state))
                (empty? (:wait-set vm-state))
                (empty? (:ready-queue vm-state))
                (empty? (:parked vm-state))
                (empty? (resource-keys vm-state)))))


(defn semantic-registers
  "The plain-data registers of a shippable semantic VM. A primitive the
   program resolved (a `+` pushed before its call) travels by name; any other
   host function throws, as does a VM holding stream handles, which would
   arrive as references to nothing."
  [vm-state]
  (when-let [ks (seq (resource-keys vm-state))]
    (throw (ex-info "Cannot ship a continuation holding live stream resources"
                    {:resource-keys (vec ks)})))
  (encode-primitives
    (:primitives vm-state)
    {:segment (get-in vm-state [:control :segment]),
     :pc (get-in vm-state [:control :pc]),
     :value (:value vm-state),
     :stack (:stack vm-state),
     :env (:env vm-state),
     :k (:k vm-state),
     :id-counter (:id-counter vm-state),
     :defs (into {} (filter (comp symbol? key)) (:store vm-state))}))


(defn continuation-datoms
  "The handoff batch for a shippable semantic VM running `code-datoms`: the
   segment, then one registers datom carrying EDN text."
  [code-datoms vm-state]
  (let [registers (semantic-registers vm-state)
        k-eid (dec (reduce min 0 (map first code-datoms)))]
    (conj (vec code-datoms)
          [k-eid registers-attr (pr-str registers) 0 0])))


(defn datoms->semantic-vm
  "Rebuild a semantic VM from a received handoff batch: load the shipped
   segment through the ordinary loader into `(make-vm)`, then install the
   registers read back from EDN."
  [batch make-vm]
  (let [code (filterv #(= "yin.code" (namespace (nth % 1))) batch)
        registers-datom (some #(when (= registers-attr (nth % 1)) %) batch)
        _ (when-not registers-datom
            (throw (ex-info "Handoff batch carries no registers" {:batch-size (count batch)})))
        base (make-vm)
        {:keys [segment pc value stack env k id-counter defs]}
        (decode-primitives (:primitives base)
                           (edn/read-string (nth registers-datom 2)))
        loaded (semantic/vm-load-program base code)]
    (when-not (= segment (:program loaded))
      (throw (ex-info "Handoff registers name a segment the batch does not carry"
                      {:segment segment, :shipped (:program loaded)})))
    (-> loaded
        (update :store merge defs)
        (assoc :control {:segment segment, :pc pc}
               :value value
               :stack stack
               :env env
               :k k
               :id-counter id-counter
               :halted? false
               :blocked? false))))
