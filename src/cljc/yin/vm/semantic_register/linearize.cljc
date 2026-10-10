(ns yin.vm.semantic-register.linearize
  "`project`: the Universal AST → the register-shaped canonical vector
   (`docs/design/yin.vm.semantic-register-vm.md` §3.3).

   One flattening, two lanes, as `yin.vm.linearize` has them: the datom
   lane reads `:yin/*` datoms through an entity index, the row lane reads
   code-as-tuples rows (§5.1) by the grammar table's positions. Both hand
   the same walk a node reader, so the order is shared by construction:
   operator, then operands left to right; a conditional's test,
   consequent, alternate; occurrences expanded positionally; saturated
   defaults; lambda bodies out of line after the main sequence, in one
   FIFO queue that bodies discovered inside bodies join at the back.

   Registers are body-local and minted pre-order: a destination is minted
   when its expression is entered, before its children; a conditional's
   arms are lowered into the conditional's destination and mint nothing
   themselves; a `:resume` mints like every other expression though no
   instruction names its id as a destination. No moves, folds or
   rewrites.

   Provenance is a side table beside the vector, never inside it; the
   code address is `ucf/code-address` of the vector."
  (:require [yin.vm :as vm]
            [yin.vm.ucf :as ucf]))


(def unsupported
  "Node types outside the shared supported AST vocabulary, rejected at
   projection naming the node."
  #{:yin/macro-expand :vm/store-update})


(def ^:private slot-position
  "`[tag field]` → the field's row position (§2.5: id 0, tag 1, first
   slot 2), from `vm/semantic-bytecode-grammar`. Provenance path steps
   are these positions in both lanes."
  (reduce-kv (fn [m tag slots]
               (reduce (fn [m i]
                         (assoc m [tag (first (nth slots i))] (+ 2 i)))
                       m
                       (range (count slots))))
             {}
             vm/semantic-bytecode-grammar))


(def ^:private defaults
  "§2.4 / UCF §7.3.2 saturation, applied before lowering. The row lane's
   rows are already saturated; the datom lane's facts may omit these."
  {[:vm/gensym :prefix] "id",
   [:stream/make :buffer] vm/default-stream-capacity,
   [:application :operands] [],
   [:dao.stream.apply/call :operands] []})


(defn- flatten-tree
  "The §3.3 walk from `root` over `reader`, `{:tag (fn [id]) :slot (fn
   [id field]) :check (fn [id])}`. `:check` runs on entering each lowered
   node and throws to refuse it. Returns `{:code [tuple …] :sources [[id
   path] …]}`, one source per pc, labels resolved to pcs."
  [{:keys [tag slot check]} root]
  (let [code (volatile! [])
        sources (volatile! [])
        labels (volatile! {})
        bodies (volatile! [])
        label-count (volatile! 0)
        regs (volatile! 0)
        fresh-label! (fn [] (vswap! label-count inc))
        mark! (fn [l] (vswap! labels assoc l (count @code)))
        emit! (fn [id path t]
                (vswap! sources conj [id path])
                (vswap! code conj t))
        field (fn [id f]
                (let [x (slot id f)]
                  (if (nil? x) (get defaults [(tag id) f]) x)))
        pos (fn [id f] (get slot-position [(tag id) f]))]
    (letfn
      [(definition?
         [id]
         (let [op (slot id :operator)]
           (and (= :variable (tag op))
                (= vm/definition-operator (slot op :name)))))
       (expr
         [id path]
         (let [d @regs]
           (vswap! regs inc)
           (lower id path d)
           d))
       (exprs
         [id f path]
         (let [p (pos id f)
               ids (field id f)]
           (mapv (fn [j] (expr (nth ids j) (conj path [p j])))
                 (range (count ids)))))
       (child
         [id f path]
         (expr (slot id f) (conj path (pos id f))))
       (lower
         [id path d]
         (check id)
         (case (tag id)
           :literal (emit! id path [:const d (slot id :value)])
           :variable (emit! id path [:var d (slot id :name)])
           :lambda (let [l (fresh-label!)]
                     (vswap! bodies conj [l id path])
                     (emit! id path [:closure d (vec (slot id :params)) l]))
           :application
           (if (definition? id)
             ;; Rule R: the value operand, then `:define` with the literal
             ;; key; neither the operator nor the key is an expression.
             (let [[k-id v-id] (field id :operands)
                   rs (expr v-id (conj path [(pos id :operands) 1]))]
               (emit! id path [:define d (slot k-id :value) rs]))
             (let [f (child id :operator path)
                   args (exprs id :operands path)]
               (emit! id path
                      [:call d f args (boolean (slot id :tail?))])))
           :if (let [else (fresh-label!)
                     end (fresh-label!)
                     c (child id :test path)]
                 (emit! id path [:branch-false c else])
                 (lower (slot id :consequent) (conj path (pos id :consequent)) d)
                 (emit! id path [:jump end])
                 (mark! else)
                 (lower (slot id :alternate) (conj path (pos id :alternate)) d)
                 (mark! end))
           :dao.stream.apply/call (let [args (exprs id :operands path)]
                                    (emit! id path
                                           [:ffi-call d (slot id :op) args]))
           :stream/make (emit! id path [:stream-make d (field id :buffer)])
           :stream/put (let [s (child id :target path)
                             x (child id :val path)]
                         (emit! id path [:stream-put d s x]))
           :stream/cursor (emit! id path
                                 [:stream-cursor d (child id :source path)])
           :stream/next (emit! id path
                               [:stream-next d (child id :source path)])
           :stream/close (emit! id path
                                [:stream-close d (child id :source path)])
           :vm/gensym (emit! id path [:gensym d (field id :prefix)])
           :vm/store-get (emit! id path [:store-get d (slot id :key)])
           :vm/store-put (emit! id path
                                [:store-put d (slot id :key) (slot id :val)])
           :vm/park (emit! id path [:park d])
           :vm/current-continuation (emit! id path [:current-continuation d])
           :vm/resume (let [x (child id :val path)]
                        (emit! id path [:resume (slot id :parked-id) x]))
           (throw (ex-info (str "Cannot project node of type " (tag id))
                           {:type (tag id), :id id}))))]
      (let [r (expr root [])]
        (emit! root [] [:halt r]))
      ;; A body may hold lambdas of its own, which join the back of
      ;; `bodies` while this loop runs. Each body numbers its registers
      ;; from 0, and its `:return` names the lambda, as the stack lane's
      ;; does.
      (loop [i 0]
        (when-let [[l lambda path] (get @bodies i)]
          (vreset! regs 0)
          (mark! l)
          (let [r (expr (slot lambda :body) (conj path (pos lambda :body)))]
            (emit! lambda path [:return r]))
          (recur (inc i))))
      (let [labels @labels]
        {:code (mapv (fn [t]
                       (case (nth t 0)
                         :jump (assoc t 1 (get labels (nth t 1)))
                         :branch-false (assoc t 2 (get labels (nth t 2)))
                         :closure (assoc t 3 (get labels (nth t 3)))
                         t))
                     @code),
         :sources @sources}))))


;; =============================================================================
;; The row lane
;; =============================================================================

(defn- row-reader
  "Rows `{id [id tag & slots]}` already passed `vm/validate-rows`, so
   nothing is re-checked."
  [rows]
  {:tag (fn [id] (nth (get rows id) 1)),
   :slot (fn [id f]
           (let [row (get rows id)]
             (nth row (get slot-position [(nth row 1) f]))))
   :check (fn [_id])})


(defn project-rows
  "Project one canonical row set (`vm/ast->semantic-bytecode`'s `{:root
   id, :rows {id row}}`) to the register-shaped vector. The rows are
   validated (§7.4) first and a defect throws naming it. `origin` is nil
   or one of §2.5's ruled occurrence origins.

   Returns `{:vector v :address A :provenance [[pc origin root path] …]}`,
   one provenance row per pc, `path` being the emitting node's §2.5
   structural path from the root row (row-position steps; a `nodes` item
   is a `[position i]` pair); a body's `:return` carries its lambda's
   path, as in `yin.vm.linearize/lower-rows`."
  ([bc] (project-rows bc nil))
  ([{:keys [root rows], :as bc} origin]
   (when-not (or (nil? origin) (vm/occurrence-origin? origin))
     (throw (ex-info "Cannot project rows with a malformed occurrence origin"
                     {:rule :origin, :origin origin})))
   (when-let [{:keys [rule path id]} (vm/validate-rows bc)]
     (throw (ex-info "Cannot project rows that fail validation"
                     (cond-> {:rule rule}
                       path (assoc :path path)
                       id (assoc :id id)))))
   (let [{:keys [code sources]} (flatten-tree (row-reader rows) root)]
     {:vector code,
      :address (ucf/code-address code),
      :provenance (mapv (fn [pc [_ path]] [pc origin root path])
                        (range)
                        sources)})))


(defn project
  "`project-rows` ∘ `vm/ast->semantic-bytecode`: a map AST to the
   register-shaped vector by the row lane."
  ([ast] (project ast nil))
  ([ast origin] (project-rows (vm/ast->semantic-bytecode ast) origin)))


;; =============================================================================
;; The datom lane
;; =============================================================================

(defn- datom-attr
  "The `:yin/*` attribute `vm/ast->datoms` writes for a grammar field."
  [tag f]
  (if (= :val f)
    (if (= :vm/store-put tag) :yin/value :yin/val-node)
    (keyword "yin" (name f))))


(defn- reject-host-value!
  [id f x]
  (when-not (vm/plain-data? x)
    (throw (ex-info (str "Cannot project a host value in " f)
                    {:node id, :field f, :value x}))))


(defn- datom-reader
  "A reader over `vm/index-datoms`' `get-attr`. Datoms are not validated
   as rows are, so `:check` refuses what the row lane's validation would:
   unsupported and unknown node types, Rule R, non-symbol binders, and
   host values in data operands."
  [get-attr]
  (let [tag (fn [id] (get-attr id :yin/type))
        slot (fn [id f] (get-attr id (datom-attr (tag id) f)))]
    {:tag tag,
     :slot slot,
     :check
     (fn [id]
       (let [t (tag id)]
         (when (contains? unsupported t)
           (throw (ex-info (str "Cannot project unsupported node " t)
                           {:type t, :node id})))
         (case t
           :literal (reject-host-value! id :value (slot id :value))
           :variable (let [n (slot id :name)]
                       (when (vm/reserved-name? n)
                         (vm/refuse-reserved! :variable n {:node id})))
           :lambda (vm/check-params! (slot id :params) {:node id})
           :application
           (let [op (slot id :operator)]
             (when (and (= :variable (tag op))
                        (= vm/definition-operator (slot op :name)))
               (vm/definition-name
                 {:operands (mapv (fn [o]
                                    {:type (tag o), :value (slot o :value)})
                                  (slot id :operands))})))
           (:vm/store-get :vm/store-put)
           (let [k (slot id :key)]
             (when (vm/reserved-name? k)
               (vm/refuse-reserved! :store-key k {:node id}))
             (reject-host-value! id :key k)
             (when (= :vm/store-put t)
               (reject-host-value! id :val (slot id :val))))
           nil)))}))


(defn project-datoms
  "Project one AST program, as `[e a v t m]` `:yin/*` datoms, to the
   register-shaped vector by the datom lane. Throws naming the node on an
   unsupported or unknown node type, a Rule R violation, or a host value
   in a data operand.

   Returns `{:vector v :address A :provenance [[pc entity] …]}`, one row
   per pc naming the AST entity the instruction came from (the stack
   lane's `:yin.code/source`)."
  [datoms]
  (let [{:keys [get-attr root-id error]} (vm/index-datoms (vec datoms))]
    (when error
      (throw (ex-info "Cannot project a program with a dangling root" error)))
    (when (nil? root-id)
      (throw (ex-info "Cannot project a program with no root" {})))
    (let [{:keys [code sources]} (flatten-tree (datom-reader get-attr)
                                               root-id)]
      {:vector code,
       :address (ucf/code-address code),
       :provenance (mapv (fn [pc [id _]] [pc id]) (range) sources)})))
