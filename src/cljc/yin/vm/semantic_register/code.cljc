(ns yin.vm.semantic-register.code
  "Well-formedness of the register-shaped canonical vector
   (`docs/design/yin.vm.semantic-register-vm.md` §2.2-2.3, §3).

   The vector is UCF §7.3.2's positional form over the §2.3 table: one
   `[op rd …]` tuple per instruction, pc as index, body-local virtual
   register ids. This namespace owns the operand table, the contract stamp,
   the §3.1 grammar parser (with the result-or-terminal outcome relation),
   and the §3.4 rules 1-13, which run in order, first defect wins, each
   defect naming its rule and pc, as `yin.vm.code/well-formed-vector?`
   does for the stack-shaped vector. Decoding into an executable image
   belongs to the evaluator (phase 4); the static facts of §8.1 are
   `yin.vm.semantic-register.analysis`'s."
  (:require [yin.vm :as vm]
            [yin.vm.ucf :as ucf]))


(def contract
  "The semantic execution contract of the register-shaped vector (§6).
   This namespace's own stamp; `yin.vm/semantic-contract` names the
   running stack-shaped machine until cutover."
  "v4")


(def operand-table
  "§2.3 in UCF §7.3.2's positional form: mnemonic → ordered `[attribute
   kind]` operands; position `i` of a tuple (from 1) holds entry `i-1`.
   The kinds are `yin.vm.code/vector-operand-table`'s plus `:reg` (a
   non-negative integer register id) and `:regs` (a vector of them), as
   the register design's R2 descriptor names them. `:yin.code/rd` marks
   the destination; every other `:reg`/`:regs` operand is a use, and the
   uses of a tuple, in table order, name its child expressions in §3.1's
   order."
  {:const [[:yin.code/rd :reg] [:yin.code/value :data]],
   :var [[:yin.code/rd :reg] [:yin.code/name :sym]],
   :closure [[:yin.code/rd :reg] [:yin.code/params :syms]
             [:yin.code/body :pc]],
   :call [[:yin.code/rd :reg] [:yin.code/fn-reg :reg]
          [:yin.code/arg-regs :regs] [:yin.code/tail? :bool]],
   :branch-false [[:yin.code/cond-reg :reg] [:yin.code/target :pc]],
   :jump [[:yin.code/target :pc]],
   :return [[:yin.code/value-reg :reg]],
   :halt [[:yin.code/value-reg :reg]],
   :gensym [[:yin.code/rd :reg] [:yin.code/prefix :str]],
   :store-get [[:yin.code/rd :reg] [:yin.code/key :data]],
   :store-put [[:yin.code/rd :reg] [:yin.code/key :data]
               [:yin.code/value :data]],
   :stream-make [[:yin.code/rd :reg] [:yin.code/buffer :buffer]],
   :stream-put [[:yin.code/rd :reg] [:yin.code/stream-reg :reg]
                [:yin.code/value-reg :reg]],
   :stream-cursor [[:yin.code/rd :reg] [:yin.code/stream-reg :reg]],
   :stream-next [[:yin.code/rd :reg] [:yin.code/cursor-reg :reg]],
   :stream-close [[:yin.code/rd :reg] [:yin.code/stream-reg :reg]],
   :ffi-call [[:yin.code/rd :reg] [:yin.code/ffi-op :kw]
              [:yin.code/arg-regs :regs]],
   :current-continuation [[:yin.code/rd :reg]],
   :park [[:yin.code/rd :reg]],
   :resume [[:yin.code/parked-id :kw] [:yin.code/value-reg :reg]],
   :define [[:yin.code/rd :reg] [:yin.code/name :sym] [:yin.code/rs :reg]]})


(def mnemonics
  "The §2.3 vocabulary. There is no `:push` and no `:move`."
  (set (keys operand-table)))


(defn reg?
  "A register id: a non-negative integer."
  [x]
  (and (integer? x) (not (neg? x))))


(defn rd
  "The destination slot of tuple `t`, or nil when its mnemonic has none
   (`:branch-false`, `:jump`, `:return`, `:halt`, `:resume`)."
  [t]
  (when (= :yin.code/rd (ffirst (get operand-table (nth t 0))))
    (nth t 1)))


(defn uses
  "The register operands of tuple `t` in table order, which is §3.1's
   child order: `:reg` operands other than the destination, and each
   element of a `:regs` operand. Values are returned as they stand; rules
   that judge registers filter with `reg?`."
  [t]
  (into []
        (comp (map-indexed (fn [i [a kind]]
                             (cond (= :yin.code/rd a) nil
                                   (= :reg kind) [(nth t (inc i))]
                                   (= :regs kind) (nth t (inc i))
                                   :else nil)))
              cat)
        (get operand-table (nth t 0))))


(defn tail-call?
  "True for a `:call` whose `tail?` is exactly true: a runtime terminator
   (§3.3 item 4)."
  [t]
  (and (= :call (nth t 0)) (true? (nth t 4))))


(defn runtime-terminator?
  "§3.4 items 10 and 12: a tail `:call` or a `:resume` ends its path."
  [t]
  (or (= :resume (nth t 0)) (tail-call? t)))


(defn- tuple-defect
  [rule pc]
  {:rule rule, :pc pc})


(defn- mnemonic-of
  [t]
  (when (and (vector? t) (seq t))
    (nth t 0)))


(defn- tuple-kinds
  [t]
  (mapv second (get operand-table (nth t 0))))


(defn- pcs
  [v]
  (range (count v)))


;; =============================================================================
;; Items 1-5: the vector form (§3.4 "items 1-5 stand"; code-as-tuples §7.5)
;; =============================================================================
;; As for the stack-shaped vector, item 1 (one segment) is `:nonempty`, item
;; 2 (instruction shape) is `:mnemonic` `:arity` `:operand-kind`
;; `:saturation`, items 3-4 (dense, sorted pcs) hold by construction, and
;; item 5 (refs resolve) is `:target-bounds`. Each rule assumes the earlier
;; ones held.

(defn- nonempty
  "Item 1. The canonical form is a non-empty vector."
  [{:keys [v]}]
  (when-not (and (vector? v) (pos? (count v)))
    (tuple-defect :nonempty 0)))


(defn- mnemonic
  "Item 2."
  [{:keys [v]}]
  (some (fn [pc]
          (when-not (contains? mnemonics (mnemonic-of (nth v pc)))
            (tuple-defect :mnemonic pc)))
        (pcs v)))


(defn- arity
  "Item 2: the mnemonic plus exactly its operands."
  [{:keys [v]}]
  (some (fn [pc]
          (let [t (nth v pc)]
            (when-not (= (inc (count (get operand-table (nth t 0))))
                         (count t))
              (tuple-defect :arity pc))))
        (pcs v)))


(defn- count?
  [x]
  (and (integer? x) (not (neg? x))))


(def ^:private operand-kind-checks
  "Kind → predicate. `:pc` belongs to `:target-bounds`. A nil in a
   saturated slot passes here and is `:saturation`'s defect."
  {:data vm/plain-data?,
   :sym symbol?,
   :syms (fn [x] (and (vector? x) (every? symbol? x))),
   :kw keyword?,
   :str (fn [x] (or (nil? x) (string? x))),
   :bool (fn [x] (or (nil? x) (boolean? x))),
   :buffer (fn [x] (or (nil? x) (count? x))),
   :reg reg?,
   :regs (fn [x] (and (vector? x) (every? reg? x)))})


(def ^:private deferred-operands
  "`[mnemonic index]` operands whose kind §3.4 assigns to a later item:
   item 10 judges `:call` and `:ffi-call` argument registers and `tail?`,
   item 12 `:resume`'s parked id and value register. Here a `:regs`
   operand is only required to be a vector, which the item 6 parse needs
   to count children; the rest is left to its item."
  {[:call 3] vector?,
   [:call 4] nil,
   [:ffi-call 3] vector?,
   [:resume 1] nil,
   [:resume 2] nil})


(defn- operand-kind
  "Item 2. An operand is judged only by its kind."
  [{:keys [v]}]
  (some (fn [pc]
          (let [t (nth v pc)
                kinds (tuple-kinds t)]
            (some (fn [i]
                    (let [k [(nth t 0) i]
                          check (if (contains? deferred-operands k)
                                  (get deferred-operands k)
                                  (get operand-kind-checks
                                       (nth kinds (dec i))))]
                      (when (and check (not (check (nth t i))))
                        (tuple-defect :operand-kind pc))))
                  (range 1 (count t)))))
        (pcs v)))


(def ^:private saturated-operands
  "UCF §7.3.2 saturation: the `[mnemonic index]` operands a canonical
   tuple materializes. `:ffi-call` has no argc: its argument registers are
   explicit."
  #{[:gensym 2] [:stream-make 2] [:call 4]})


(defn- saturation
  "Item 2."
  [{:keys [v]}]
  (some (fn [pc]
          (let [t (nth v pc)]
            (when (some #(and (contains? saturated-operands [(nth t 0) %])
                              (nil? (nth t %)))
                        (range 1 (count t)))
              (tuple-defect :saturation pc))))
        (pcs v)))


(defn- target-bounds
  "Item 5. A resolved pc must be an integer inside the segment."
  [{:keys [v]}]
  (let [n (count v)]
    (some (fn [pc]
            (let [t (nth v pc)
                  kinds (tuple-kinds t)]
              (some (fn [i]
                      (when (= :pc (nth kinds (dec i)))
                        (let [x (nth t i)]
                          (when-not (and (integer? x) (<= 0 x (dec n)))
                            (tuple-defect :target-bounds pc)))))
                    (range 1 (count t)))))
          (pcs v))))


;; =============================================================================
;; Item 6: body partition, ownership, and the §3.1 parse
;; =============================================================================

(defn body-ranges
  "The §3.4 item 6 partition of a vector that passed items 1-5, before
   ownership is judged: `[{:start s :end e :owner pc-or-nil} …]` in pc
   order, the main sequence at 0 and one body per distinct `:closure`
   body-pc, each body running to the next one's start. `:owner` is the pc
   of the first `:closure` naming the body (nil for main)."
  [v]
  (let [owners (reduce (fn [m pc]
                         (let [t (nth v pc)]
                           (if (and (= :closure (nth t 0))
                                    (not (contains? m (nth t 3))))
                             (assoc m (nth t 3) pc)
                             m)))
                       {}
                       (pcs v))
        starts (vec (sort (conj (set (keys owners)) 0)))
        ends (conj (subvec starts 1) (count v))]
    (mapv (fn [s e]
            {:start s, :end e, :owner (when (pos? s) (get owners s))})
          starts
          ends)))


(defn body-index
  "pc → index into `bodies` (a `body-ranges` result) of the body holding it."
  [bodies pc]
  (loop [lo 0
         hi (dec (count bodies))]
    (if (>= lo hi)
      lo
      (let [mid (quot (+ lo hi 1) 2)]
        (if (<= (:start (nth bodies mid)) pc)
          (recur mid hi)
          (recur lo (dec mid)))))))


(defn- partition-defect
  "Item 6's partition and ownership clauses, in this order: a `:closure`
   body-pc of 0 (it would own main) or naming a body another `:closure`
   already owns; an owner inside the body it owns; an owner chain that
   does not reach main; a jump or branch target in another body; a body
   whose last instruction is not its terminator (`:halt` for main,
   `:return` otherwise)."
  [v bodies]
  (let [body-of #(body-index bodies %)]
    (or (some (fn [pc]
                (let [t (nth v pc)]
                  (when (and (= :closure (nth t 0))
                             (or (zero? (nth t 3))
                                 (not= pc (:owner (nth bodies
                                                       (body-of (nth t 3)))))))
                    (tuple-defect :body-partition pc))))
              (pcs v))
        (some (fn [i]
                (let [owner (:owner (nth bodies i))]
                  (when (= i (body-of owner))
                    (tuple-defect :body-partition owner))))
              (range 1 (count bodies)))
        (some (fn [i]
                (loop [j i
                       seen #{}]
                  (cond (zero? j) nil
                        (contains? seen j)
                        (tuple-defect :body-partition
                                      (:owner (nth bodies i)))
                        :else (recur (body-of (:owner (nth bodies j)))
                                     (conj seen j)))))
              (range 1 (count bodies)))
        (some (fn [pc]
                (let [t (nth v pc)]
                  (when (and (contains? #{:jump :branch-false} (nth t 0))
                             (not= (body-of pc)
                                   (body-of (peek t))))
                    (tuple-defect :body-partition pc))))
              (pcs v))
        (some (fn [i]
                (let [last-pc (dec (:end (nth bodies i)))]
                  (when-not (= (if (zero? i) :halt :return)
                               (nth (nth v last-pc) 0))
                    (tuple-defect :body-partition last-pc))))
              (range (count bodies))))))


(defn- child-count
  "The number of child expressions §3.1 gives a tuple's production."
  [t]
  (case (nth t 0)
    :call (inc (count (nth t 3)))
    :ffi-call (count (nth t 3))
    :stream-put 2
    (:stream-cursor :stream-next :stream-close :define :resume) 1
    0))


(defn- parse-range
  "Parse pcs `[s, e)` into a sequence of complete §3.1 expressions by
   shift-reduce: an instruction node reduces the child expressions it
   takes from the top of the stack; `[:branch-false c Lalt]` reduces the
   test and recursively parses the consequent up to the `[:jump Lend]` at
   `Lalt - 1` and the alternate over `[Lalt, Lend)`, each to exactly one
   expression. Returns `[exprs nil]`, or `[nil pc]` naming the defect.

   A node is `{:op mnemonic :pc pc :children […]}`; a conditional is
   `{:op :if :pc branch-pc :jump jump-pc :end Lend :children [test then
   else]}`."
  [v s e]
  (loop [pc s
         stack []]
    (if (>= pc e)
      [stack nil]
      (let [t (nth v pc)
            op (nth t 0)]
        (case op
          (:jump :return :halt) [nil pc]
          :branch-false
          (let [alt (nth t 2)
                jpc (dec alt)]
            (if (or (empty? stack)
                    (< jpc (inc pc))
                    (> alt e)
                    (not= :jump (nth (nth v jpc) 0)))
              [nil pc]
              (let [end (nth (nth v jpc) 1)]
                (if (or (< end alt) (> end e))
                  [nil jpc]
                  (let [[then d1] (parse-range v (inc pc) jpc)]
                    (cond d1 [nil d1]
                          (not= 1 (count then)) [nil jpc]
                          :else
                          (let [[else d2] (parse-range v alt end)]
                            (cond d2 [nil d2]
                                  (not= 1 (count else)) [nil jpc]
                                  :else (recur end
                                               (conj (pop stack)
                                                     {:op :if,
                                                      :pc pc,
                                                      :jump jpc,
                                                      :end end,
                                                      :children
                                                      [(peek stack)
                                                       (nth then 0)
                                                       (nth else 0)]}))))))))))
          (let [n (child-count t)
                split (- (count stack) n)]
            (if (neg? split)
              [nil pc]
              (recur (inc pc)
                     (conj (subvec stack 0 split)
                           {:op op,
                            :pc pc,
                            :children (subvec stack split)})))))))))


(defn- parse-body
  "§3.1 `body ::= expr(r) [:return r] | expr(r) [:halt r]`: the body's
   pcs before its terminator parse to exactly one expression. Returns the
   body with `:expr`, or `{:defect pc}`."
  [v {:keys [start end], :as body}]
  (let [term (dec end)
        [exprs d] (parse-range v start term)]
    (cond d {:defect d}
          (not= 1 (count exprs)) {:defect term}
          :else (assoc body :expr (nth exprs 0)))))


(defn- parse-bodies
  "Item 6 over a vector that passed items 1-5: `{:bodies [body …]}` with
   every body parsed, or `{:defect d}`."
  [v]
  (let [bodies (body-ranges v)]
    (if-let [d (partition-defect v bodies)]
      {:defect d}
      (reduce (fn [acc body]
                (let [parsed (parse-body v body)]
                  (if-let [pc (:defect parsed)]
                    (reduced {:defect (tuple-defect :not-expression-structured
                                                    pc)})
                    (update acc :bodies conj parsed))))
              {:bodies []}
              bodies))))


(defn- body-partition
  "Item 6. Partition and ownership defects are `:body-partition`; a body
   that does not parse under §3.1 is `:not-expression-structured`."
  [{:keys [parsed]}]
  (:defect @parsed))


;; =============================================================================
;; The parsed tree: expressions, minted ids, leaves, outcomes
;; =============================================================================

(defn- leaves
  "§3.2: the pcs of the leaf results of an expression subtree. An
   instruction with a destination is its own leaf; a conditional's leaves
   are its arms' leaves; a `:resume` supplies none."
  [v node]
  (case (:op node)
    :if (let [[_ th el] (:children node)]
          (into (leaves v th) (leaves v el)))
    :resume #{}
    #{(:pc node)}))


(defn- child-refs
  "The register each child of `node` is named by: the tuple's uses in
   order, or a conditional's test register."
  [v node]
  (let [t (nth v (:pc node))]
    (if (= :if (:op node))
      [(nth t 1)]
      (uses t))))


(defn- minted
  "Every expression of a body that mints a destination (§3.3 item 2:
   every expression except a conditional's arm), in pre-order, as `[node
   id]`. An expression with a destination slot mints the id the slot
   names. A conditional or a `:resume` has no slot of its own and mints
   the register its parent names it by (the parent's use, the test
   register, or the body's terminator operand); when that operand is not
   a register (items 10, 12 judge it), a conditional falls back to its
   first leaf's destination and a `:resume` mints nil."
  [v {:keys [expr end]}]
  (let [out (volatile! [])]
    (letfn [(walk
              [node ref arm?]
              (when-not arm?
                (vswap! out conj
                        [node
                         (case (:op node)
                           :if (if (reg? ref)
                                 ref
                                 (when-let [pc (first (sort (leaves v node)))]
                                   (rd (nth v pc))))
                           :resume (when (reg? ref) ref)
                           (rd (nth v (:pc node))))]))
              (if (= :if (:op node))
                (let [[c th el] (:children node)]
                  (walk c (nth (nth v (:pc node)) 1) false)
                  (walk th nil true)
                  (walk el nil true))
                (dorun (map (fn [child ref] (walk child ref false))
                            (:children node)
                            (concat (child-refs v node) (repeat nil))))))]
      (walk expr (nth (nth v (dec end)) 1) false)
      @out)))


(defn- body-pcs
  [{:keys [start end]}]
  (range start end))


(defn registers
  "The register count `k` of a parsed body: the number of expressions
   that mint (§3.4 item 7)."
  [v body]
  (count (minted v body)))


;; =============================================================================
;; Items 7-13
;; =============================================================================

(defn- for-bodies
  "Run `f` over every parsed body in pc order; the first defect wins."
  [{:keys [v parsed]} f]
  (some #(f v %) (:bodies @parsed)))


(defn- register-ids
  "Item 7. With `k` the number of minting expressions: every register a
   body names is below `k`; each id is minted by exactly one expression;
   an id whose result subtree has leaves has a syntactic definition
   (otherwise it is definition-less)."
  [ctx]
  (for-bodies
    ctx
    (fn [v body]
      (let [ms (minted v body)
            k (count ms)]
        (or (some (fn [pc]
                    (let [t (nth v pc)
                          named (cond->> (filter reg? (uses t))
                                  (rd t) (cons (rd t)))]
                      (when (some #(>= % k) named)
                        (tuple-defect :register-ids pc))))
                  (body-pcs body))
            (some (fn [[[node id] seen]]
                    (when (or (nil? id) (contains? seen id))
                      (tuple-defect :register-ids (:pc node))))
                  (map list ms (reductions conj #{} (map second ms))))
            (let [defined (set (keep #(rd (nth v %)) (body-pcs body)))]
              (some (fn [[node id]]
                      (when (and (seq (leaves v node))
                                 (not (contains? defined id)))
                        (tuple-defect :register-ids (:pc node))))
                    ms)))))))


(defn- assignment-walk
  "Item 8's forward walk over one expression from the assigned set `a`.
   Returns `{:assigned a'}` when the expression continues, `{:terminal
   true}` when its path ends (a terminal child, a tail `:call`, a
   `:resume`, or a conditional both of whose arms end), or `{:defect d}`.
   After a terminal child the rest of the expression is layout syntax and
   is not walked."
  [v node a]
  (if (= :if (:op node))
    (let [[c th el] (:children node)
          rc (assignment-walk v c a)]
      (cond (:defect rc) rc
            (:terminal rc) rc
            :else
            (let [a (:assigned rc)
                  creg (nth (nth v (:pc node)) 1)]
              (if-not (contains? a creg)
                {:defect (tuple-defect :definite-assignment (:pc node))}
                (let [r1 (assignment-walk v th a)
                      r2 (assignment-walk v el a)]
                  (cond (:defect r1) r1
                        (:defect r2) r2
                        :else (let [cont (keep :assigned [r1 r2])]
                                (if (empty? cont)
                                  {:terminal true}
                                  {:assigned (reduce (fn [x y]
                                                       (set (filter x y)))
                                                     cont)}))))))))
    (let [r (reduce (fn [acc child]
                      (let [rc (assignment-walk v child (:assigned acc))]
                        (if (:assigned rc) rc (reduced rc))))
                    {:assigned a}
                    (:children node))]
      (if-not (:assigned r)
        r
        (let [a (:assigned r)
              t (nth v (:pc node))]
          (cond (some #(and (reg? %) (not (contains? a %))) (uses t))
                {:defect (tuple-defect :definite-assignment (:pc node))}
                (runtime-terminator? t) {:terminal true}
                (rd t) {:assigned (conj a (rd t))}
                :else r))))))


(defn- definite-assignment
  "Item 8. On every runtime path every register read has been written:
   a forward walk over the structured control flow of §3.1, the join of
   a conditional intersecting its continuing arms only. Layout syntax
   after a runtime terminator is on no path and is not judged here."
  [ctx]
  (for-bodies
    ctx
    (fn [v {:keys [expr end]}]
      (let [r (assignment-walk v expr #{})
            term-pc (dec end)
            reg (nth (nth v term-pc) 1)]
        (cond (:defect r) (:defect r)
              (and (:assigned r) (not (contains? (:assigned r) reg)))
              (tuple-defect :definite-assignment term-pc))))))


(defn- exclusive-definitions
  "Item 9 (§3.2). For each minted id, the pcs whose destination names it
   are exactly the leaf results of the expression that minted it (the
   empty set for a definition-less id). The defect names the least pc
   where the two sets differ."
  [ctx]
  (for-bodies
    ctx
    (fn [v body]
      (let [defs (reduce (fn [m pc]
                           (if-let [r (rd (nth v pc))]
                             (update m r (fnil conj #{}) pc)
                             m))
                         {}
                         (body-pcs body))]
        (some (fn [[node id]]
                (let [want (leaves v node)
                      have (get defs id #{})]
                  (when-not (= want have)
                    (tuple-defect :exclusive-definitions
                                  (first (sort (concat (remove have want)
                                                       (remove want
                                                               have))))))))
              (minted v body))))))


(defn- call-operands
  "Item 10. `:call` and `:ffi-call` argument vectors contain registers
   only; `tail?` is a boolean. The item's layout-syntax clause (what
   follows a runtime terminator is exactly what the enclosing §3.1
   productions require) is discharged by item 6's parse, which accepts
   nothing else, and item 8 keeps layout syntax off every path."
  [{:keys [v]}]
  (some (fn [pc]
          (let [t (nth v pc)]
            (when (case (nth t 0)
                    :call (not (and (every? reg? (nth t 3))
                                    (boolean? (nth t 4))))
                    :ffi-call (not (every? reg? (nth t 3)))
                    false)
              (tuple-defect :call-operands pc))))
        (pcs v)))


(defn- reserved-name
  "Item 11, Rule R: no `:var`, `:define`, `:closure` binder, or store key
   names a reserved name."
  [{:keys [v]}]
  (some (fn [pc]
          (let [t (nth v pc)]
            (when (case (nth t 0)
                    (:var :define :store-get :store-put) (vm/reserved-name?
                                                           (nth t 2))
                    :closure (some vm/reserved-name? (nth t 2))
                    false)
              (tuple-defect :reserved-name pc))))
        (pcs v)))


(defn- resume-operands
  "Item 12. `:resume`'s parked id is a keyword and its value operand a
   register. That it is a runtime terminator with layout syntax after it,
   wherever it occurs, is the parse's (item 6) and item 8's."
  [{:keys [v]}]
  (some (fn [pc]
          (let [t (nth v pc)]
            (when (and (= :resume (nth t 0))
                       (not (and (keyword? (nth t 1)) (reg? (nth t 2)))))
              (tuple-defect :resume-operands pc))))
        (pcs v)))


(defn- with-registers
  "Tuple `t` with its destination set to `d` and its uses, in table
   order, taken from `rs`."
  [t d rs]
  (let [rs (volatile! rs)
        take! (fn [n]
                (let [x (subvec @rs 0 n)]
                  (vswap! rs subvec n)
                  x))]
    (into [(nth t 0)]
          (map-indexed (fn [i [a kind]]
                         (cond (= :yin.code/rd a) d
                               (= :reg kind) (nth (take! 1) 0)
                               (= :regs kind) (take! (count (nth t (inc i))))
                               :else (nth t (inc i)))))
          (get operand-table (nth t 0)))))


(defn- reprojected
  "The body's register-bearing tuples re-projected from its parse tree
   under §3.3's minting: pre-order, body-local from 0, an arm lowered
   into its conditional's destination, every expression (a `:resume`
   included) minting on entry. Returns `{pc tuple}`; jumps carry no
   register and are absent."
  [v {:keys [expr end]}]
  (let [ctr (volatile! 0)
        out (volatile! {})
        put! (fn [pc t] (vswap! out assoc pc t))]
    (letfn [(fresh
              [node]
              (let [d @ctr]
                (vswap! ctr inc)
                (lower node d)
                d))
            (lower
              [node d]
              (let [t (nth v (:pc node))]
                (if (= :if (:op node))
                  (let [[c th el] (:children node)]
                    (put! (:pc node) (assoc t 1 (fresh c)))
                    (lower th d)
                    (lower el d))
                  (put! (:pc node)
                        (with-registers t d (mapv fresh (:children node)))))))]
      (let [r (fresh expr)
            term-pc (dec end)]
        (put! term-pc (assoc (nth v term-pc) 1 r))
        @out))))


(defn- reprojected-segment
  "The whole segment re-projected from its parse trees under §3.3: the
   main sequence first, then bodies out of line in FIFO queue order (a
   `:closure` enqueues the body at its body-pc, wherever it is emitted),
   each conditional's labels placed by the walk, registers minted as in
   `reprojected`, labels resolved to pcs last. Literal operands are copied
   from the parsed tuples, which items 1-5 already found saturated."
  [v bodies]
  (let [by-start (into {} (map (juxt :start identity)) bodies)
        code (volatile! [])
        labels (volatile! {})
        queue (volatile! [])
        label-count (volatile! 0)
        ctr (volatile! 0)
        fresh-label! (fn [] (vswap! label-count inc))
        mark! (fn [l] (vswap! labels assoc l (count @code)))
        emit! (fn [t] (vswap! code conj t))]
    (letfn [(fresh
              [node]
              (let [d @ctr]
                (vswap! ctr inc)
                (lower node d)
                d))
            (lower
              [node d]
              (let [t (nth v (:pc node))]
                (if (= :if (:op node))
                  (let [[c th el] (:children node)
                        else (fresh-label!)
                        end (fresh-label!)]
                    (emit! [:branch-false (fresh c) else])
                    (lower th d)
                    (emit! [:jump end])
                    (mark! else)
                    (lower el d)
                    (mark! end))
                  (let [t (with-registers t d (mapv fresh (:children node)))]
                    (if (= :closure (nth t 0))
                      (let [l (fresh-label!)]
                        (vswap! queue conj [l (get by-start (nth t 3))])
                        (emit! (assoc t 3 l)))
                      (emit! t))))))
            (body!
              [{:keys [expr end]}]
              (vreset! ctr 0)
              (let [r (fresh expr)]
                (emit! [(nth (nth v (dec end)) 0) r])))]
      (body! (first bodies))
      (loop [i 0]
        (when-let [[l body] (get @queue i)]
          (mark! l)
          (body! body)
          (recur (inc i))))
      (let [labels @labels]
        (mapv (fn [t]
                (case (nth t 0)
                  :jump (assoc t 1 (get labels (nth t 1)))
                  :branch-false (assoc t 2 (get labels (nth t 2)))
                  :closure (assoc t 3 (get labels (nth t 3)))
                  t))
              @code)))))


(defn- canonical-projection
  "Item 13 (amended). Re-projecting the parsed trees of the whole segment
   under §3.3 reproduces the entire vector. Register ids are compared
   first, body by body at their own pcs: a difference is
   `:noncanonical-registers` at the least differing pc. With ids equal,
   any remaining difference (bodies out of FIFO order, labels placed
   otherwise) is `:noncanonical-layout` at the least differing pc."
  [{:keys [v parsed], :as ctx}]
  (or (for-bodies ctx
                  (fn [v body]
                    (let [canon (reprojected v body)]
                      (some (fn [pc]
                              (when-let [t (get canon pc)]
                                (when-not (= t (nth v pc))
                                  (tuple-defect :noncanonical-registers pc))))
                            (body-pcs body)))))
      (let [canon (reprojected-segment v (:bodies @parsed))]
        (some (fn [pc]
                (when-not (= (get canon pc) (nth v pc))
                  (tuple-defect :noncanonical-layout pc)))
              (range (max (count v) (count canon)))))))


(def rules
  "§3.4 items 1-13 in order; each takes the validation context and
   returns a defect or nil, and may assume every earlier rule held.
   Items 1-5 are the vector form's rules; item 6 yields `:body-partition`
   or `:not-expression-structured`."
  [nonempty mnemonic arity operand-kind saturation target-bounds
   body-partition register-ids definite-assignment exclusive-definitions
   call-operands reserved-name resume-operands canonical-projection])


(defn- context
  [v]
  {:v v, :parsed (delay (parse-bodies v))})


(defn well-formed?
  "Check one register-shaped canonical vector against §3.4. Returns nil
   when it is well formed, else the first defect as `{:rule r :pc p}`,
   where r is one of `:nonempty` `:mnemonic` `:arity` `:operand-kind`
   `:saturation` `:target-bounds` (items 1-5), `:body-partition`
   `:not-expression-structured` (6), `:register-ids` (7),
   `:definite-assignment` (8), `:exclusive-definitions` (9),
   `:call-operands` (10), `:reserved-name` (11), `:resume-operands` (12),
   `:noncanonical-registers` `:noncanonical-layout` (13)."
  [v]
  (let [ctx (context v)]
    (some #(% ctx) rules)))


(defn parse
  "The parsed bodies of a well-formed vector, in pc order: `[{:start s
   :end e :owner closure-pc-or-nil :expr tree :registers k} …]`."
  [v]
  (mapv (fn [body] (assoc body :registers (registers v body)))
        (:bodies (parse-bodies v))))


(defn load-vector
  "The direct load path (code-as-tuples §7.1): require the `\"v4\"`
   stamp (`:contract-missing`, `:contract-mismatch`), validate under
   §3.4 (a defect throws naming rule and pc), and return the image
   `{:vector v :address A :bodies [{:start :end :owner :registers} …]}`,
   A being `ucf/code-address` of the vector. Both load paths end here, so
   they run one validator and yield one image."
  [v stamp]
  (vm/check-contract! contract stamp)
  (when-let [defect (well-formed? v)]
    (throw (ex-info (str "Cannot load vector: " (name (:rule defect))
                         " (pc " (:pc defect) ")")
                    {:defect defect})))
  {:vector v,
   :address (ucf/code-address v),
   :bodies (mapv #(dissoc % :expr) (parse v))})
