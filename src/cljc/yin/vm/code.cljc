(ns yin.vm.code
  "Well-formedness of linear executable datoms (`:yin.code/*`).

   A segment is one batch: exactly one `:yin.code/type :segment` entity plus
   its instruction entities, whose order is an explicit `:yin.code/pc` fact
   (`docs/design/yin.vm.semantic.md` §2). This namespace judges the shape of a
   batch and nothing else. Decoding it into an image and executing that image
   belong to the semantic VM; the mnemonic-to-opcode mapping is the loader's
   interpretation, not a fact checked here.

   The same split holds one level up, for the canonical instruction vector of
   UCF §7.3.2 (`docs/design/yin.vm.code-as-tuples.md` §7.5): the §7.5
   validator lives here; decoding the vector into an image belongs to the
   semantic VM."
  (:require [dao.jing :as jing]
            [yin.vm :as vm]))


(def mnemonics
  "The instruction vocabulary of §2.4."
  #{:const :var :closure :push :call :return :jump :branch-false :halt :gensym
    :store-get :store-put :stream-make :stream-put :stream-cursor :stream-next
    :stream-close :park :resume :current-continuation :ffi-call})


(def terminators
  "Instructions after which control never reaches pc + 1."
  #{:jump :return :halt})


(def ^:private structural-attrs
  #{:yin.code/segment :yin.code/pc :yin.code/op})


(def ^:private ref-attrs
  [:yin.code/target :yin.code/body])


(def ^:private required-ref
  {:jump :yin.code/target, :branch-false :yin.code/target, :closure :yin.code/body})


(defn- count?
  [x]
  (and (integer? x) (not (neg? x))))


(defn- defect
  [rule entity]
  {:rule rule, :entity entity})


(defn- index-batch
  "Entity ids in order of first appearance, and each entity's attribute map.
   A repeated single-valued attribute keeps its last value, as
   `yin.vm/index-datoms` reads one."
  [datoms]
  (reduce (fn [[order attrs] [e a v]]
            [(if (contains? attrs e) order (conj order e))
             (assoc-in attrs [e a] v)])
          [[] {}]
          datoms))


;; Each rule below takes the indexed batch and returns a defect or nil. Rules
;; run in order and the first defect wins, so a rule may assume every earlier
;; one held.

(defn- one-segment
  "Rule 1. A batch with no segment entity names no entity; a batch with
   several names the second."
  [{:keys [segments]}]
  (when-not (= 1 (count segments))
    (defect :one-segment (second segments))))


(defn- instruction-shape
  "§2.3: every instruction names this segment and carries a §2.4 op. The
   later rules read both, so a batch that lacks them cannot be judged by them."
  [{:keys [seg attrs instructions]}]
  (some (fn [e]
          (let [ia (get attrs e)]
            (when-not (and (= seg (:yin.code/segment ia))
                           (contains? mnemonics (:yin.code/op ia)))
              (defect :instruction-shape e))))
        instructions))


(defn- dense-pcs
  "Rule 2. The first instruction whose pc is missing, out of range, or
   already taken is named; a count short of the length names the segment."
  [{:keys [seg attrs instructions]}]
  (let [length (get-in attrs [seg :yin.code/length])]
    (if-not (count? length)
      (defect :dense-pcs seg)
      (loop [es instructions
             seen #{}]
        (if-let [[e & more] (seq es)]
          (let [pc (get-in attrs [e :yin.code/pc])]
            (if (and (count? pc) (< pc length) (not (contains? seen pc)))
              (recur more (conj seen pc))
              (defect :dense-pcs e)))
          (when-not (= length (count seen))
            (defect :dense-pcs seg)))))))


(defn- sorted-by-pc
  "Rule 3. The pcs are a permutation of 0 .. length-1 by rule 2, so the batch
   is sorted exactly when each instruction's pc is its position among the
   instructions."
  [{:keys [attrs instructions]}]
  (some (fn [[i e]]
          (when-not (= i (get-in attrs [e :yin.code/pc]))
            (defect :sorted-by-pc e)))
        (map-indexed vector instructions)))


(defn- dangling-target
  "Rule 4. A ref that is present must name an instruction of this batch, and
   a `:jump`, `:branch-false`, or `:closure` without its ref resolves nowhere."
  [{:keys [attrs instructions]}]
  (let [instruction? (set instructions)]
    (some (fn [e]
            (let [ia (get attrs e)
                  required (get required-ref (:yin.code/op ia))
                  refs (cond-> (filterv #(contains? ia %) ref-attrs)
                         required (conj required))]
              (when-not (every? #(instruction? (get ia %)) refs)
                (defect :dangling-target e))))
          instructions)))


(defn- missing-terminator
  "Rule 5. Blocks are delimited by labels (pc 0, targets, bodies) and by
   terminators. A block that does not end in a terminator ends only because
   its successor is labelled, which the rule allows, so the one instruction
   that can violate it is the last: nothing labelled follows pc length-1. An
   empty segment has no terminator at all and names the segment."
  [{:keys [seg attrs instructions]}]
  (if-let [e (peek instructions)]
    (when-not (contains? terminators (get-in attrs [e :yin.code/op]))
      (defect :missing-terminator e))
    (defect :missing-terminator seg)))


(defn- negative-argc
  "Rule 6."
  [{:keys [attrs instructions]}]
  (some (fn [e]
          (let [ia (get attrs e)]
            (when (and (contains? #{:call :ffi-call} (:yin.code/op ia))
                       (not (count? (:yin.code/argc ia))))
              (defect :negative-argc e))))
        instructions))


(def ^:private rules
  [one-segment instruction-shape dense-pcs sorted-by-pc dangling-target
   missing-terminator negative-argc])


(defn well-formed?
  "Check one segment batch of `[e a v t m]` datoms against §2.6.

   Returns nil when the batch is well formed, else the first defect as
   `{:rule r :entity e}`, where r is one of `:one-segment`,
   `:instruction-shape`, `:dense-pcs`, `:sorted-by-pc`, `:dangling-target`,
   `:missing-terminator`, `:negative-argc`, and e is the offending entity
   (nil when the defect is an absent segment).

   An instruction is any non-segment entity carrying `:yin.code/segment`,
   `:yin.code/pc`, or `:yin.code/op`; entities with none of them are not
   judged."
  [datoms]
  (let [[order attrs] (index-batch datoms)
        segments (filterv #(= :segment (get-in attrs [% :yin.code/type])) order)
        segment? (set segments)
        instructions (filterv (fn [e]
                                (and (not (segment? e))
                                     (some #(contains? (get attrs e) %)
                                           structural-attrs)))
                              order)
        batch {:attrs attrs,
               :segments segments,
               :seg (first segments),
               :instructions instructions}]
    (some #(% batch) rules)))


;; =============================================================================
;; The canonical instruction vector (UCF §7.3.2, §7.5)
;; =============================================================================

(def vector-operand-table
  "§2.4's operand table in UCF §7.3.2's positional form: mnemonic → ordered
   `[attribute kind]` operands. Position `i` of a canonical tuple (from 1)
   holds the operand entry `i-1` names; this one table drives the §7.5
   validator's rules. Each kind names the
   rule that judges it: `:data`, `:sym`, `:syms`, `:kw`, `:str`, `:bool`,
   and `:buffer` belong to `:operand-kind` (a nil passes it and is
   `:saturation`'s defect); `:pc` to `:target-bounds`; `:uint` to `:argc`."
  {:const [[:yin.code/value :data]],
   :var [[:yin.code/name :sym]],
   :closure [[:yin.code/params :syms] [:yin.code/body :pc]],
   :push [],
   :call [[:yin.code/argc :uint] [:yin.code/tail? :bool]],
   :return [],
   :jump [[:yin.code/target :pc]],
   :branch-false [[:yin.code/target :pc]],
   :halt [],
   :gensym [[:yin.code/prefix :str]],
   :store-get [[:yin.code/key :data]],
   :store-put [[:yin.code/key :data] [:yin.code/value :data]],
   :stream-make [[:yin.code/buffer :buffer]],
   :stream-put [],
   :stream-cursor [],
   :stream-next [],
   :stream-close [],
   :park [],
   :resume [[:yin.code/parked-id :kw]],
   :current-continuation [],
   :ffi-call [[:yin.code/ffi-op :kw] [:yin.code/argc :uint]]})


(def ^:private operand-kind-checks
  "§7.5 :operand-kind — kind → predicate. Absent kinds (`:pc`, `:uint`) are
   not judged by the rule: `:target-bounds` and `:argc` own them. A nil in
   a saturated slot passes here; `:saturation` owns it, the §7.4 `:slot-kind`
   precedent."
  {:data vm/plain-data?,
   :sym symbol?,
   :syms (fn [x] (and (vector? x) (every? symbol? x))),
   :kw keyword?,
   :str (fn [x] (or (nil? x) (string? x))),
   :bool (fn [x] (or (nil? x) (boolean? x))),
   :buffer (fn [x] (or (nil? x) (count? x)))})


(def ^:private saturated-operands
  "§7.5 :saturation — the `[mnemonic index]` operands a canonical tuple
   materializes (UCF §7.3.2); a nil in one of them is that rule's defect."
  #{[:gensym 1] [:stream-make 1] [:call 2] [:ffi-call 2]})


(defn- tuple-defect
  [rule pc]
  {:rule rule, :pc pc})


(defn- mnemonic-of
  "The mnemonic of one tuple, or nil when the element has no first element
   that could be one. A tuple is a vector — UCF §7.3.2's positional form —
   so a list element has no mnemonic."
  [t]
  (when (and (vector? t) (seq t))
    (nth t 0)))


(defn- tuple-kinds
  "The operand kinds of one tuple's mnemonic, in tuple order."
  [t]
  (mapv second (get vector-operand-table (nth t 0))))


;; Each rule below takes the whole vector and returns a defect or nil. Rules
;; run in §7.5's order and the first defect wins, so a rule may assume every
;; earlier one held — exactly as `rules` above runs over a batch.

(defn- nonempty
  "Rule 1. An empty vector names pc 0, where the first instruction belongs;
   so does anything a pc cannot index: the canonical form is a vector
   (UCF §7.3.2), so a list outer is not the form at any length."
  [v]
  (when-not (and (vector? v) (pos? (count v)))
    (tuple-defect :nonempty 0)))


(defn- mnemonic
  "Rule 2."
  [v]
  (some (fn [pc]
          (when-not (contains? mnemonics (mnemonic-of (nth v pc)))
            (tuple-defect :mnemonic pc)))
        (range (count v))))


(defn- arity
  "Rule 3. §2.4's operand table as saturated and made positional by UCF
   §7.3.2: the mnemonic plus exactly its operands."
  [v]
  (some (fn [pc]
          (let [t (nth v pc)]
            (when-not (= (inc (count (get vector-operand-table (nth t 0))))
                         (count t))
              (tuple-defect :arity pc))))
        (range (count v))))


(defn- operand-kind
  "Rule 4. An operand is judged only by the kind §7.5 gives it."
  [v]
  (some (fn [pc]
          (let [t (nth v pc)
                kinds (tuple-kinds t)]
            (some (fn [i]
                    (let [check (get operand-kind-checks (nth kinds (dec i)))]
                      (when (and check (not (check (nth t i))))
                        (tuple-defect :operand-kind pc))))
                  (range 1 (count t)))))
        (range (count v))))


(defn- saturation
  "Rule 5. A saturated operand is materialized in a canonical tuple; a nil
   one names its pc."
  [v]
  (some (fn [pc]
          (let [t (nth v pc)]
            (when (some #(and (contains? saturated-operands [(nth t 0) %])
                              (nil? (nth t %)))
                        (range 1 (count t)))
              (tuple-defect :saturation pc))))
        (range (count v))))


(defn- target-bounds
  "Rule 6. A resolved pc must be an integer inside the segment."
  [v]
  (let [length (count v)]
    (some (fn [pc]
            (let [t (nth v pc)
                  kinds (tuple-kinds t)]
              (some (fn [i]
                      (when (= :pc (nth kinds (dec i)))
                        (let [x (nth t i)]
                          (when-not (and (integer? x) (<= 0 x (dec length)))
                            (tuple-defect :target-bounds pc)))))
                    (range 1 (count t)))))
          (range (count v)))))


(defn- terminator
  "Rule 7. Nothing labelled follows pc length-1, so the one instruction
   that can violate the rule is the last."
  [v]
  (let [pc (dec (count v))]
    (when-not (contains? terminators (mnemonic-of (nth v pc)))
      (tuple-defect :terminator pc))))


(defn- argc
  "Rule 8. The :uint operands of `:call` and `:ffi-call`."
  [v]
  (some (fn [pc]
          (let [t (nth v pc)
                kinds (tuple-kinds t)]
            (some (fn [i]
                    (when (= :uint (nth kinds (dec i)))
                      (when-not (count? (nth t i))
                        (tuple-defect :argc pc))))
                  (range 1 (count t)))))
        (range (count v))))


(def ^:private vector-rules
  [nonempty mnemonic arity operand-kind saturation target-bounds terminator
   argc])


(defn well-formed-vector?
  "Check one canonical instruction vector (UCF §7.3.2) against §7.5
   (`docs/design/yin.vm.code-as-tuples.md`).

   Returns nil when the vector is well formed, else the first defect as
   `{:rule r :pc p}` — `:nonempty`, `:mnemonic`, `:arity`,
   `:operand-kind`, `:saturation`, `:target-bounds`, `:terminator`, or
   `:argc` — with p the offending pc (`:nonempty` names pc 0, where the
   missing first instruction belongs). Rules run in §7.5's order, each
   assuming the earlier ones held. §2.6 rules 1–4 are satisfied by the
   vector form by construction (pc is the index); rules 5–6 and the UCF
   §7.3.4 checks are the eight here. The address check — a vector hashing
   to the address it claims — is not a structural rule and is not made
   here: a correct hash is never structural validation."
  [v]
  (some #(% v) vector-rules))


;; =============================================================================
;; Segment rows (§6.2)
;; =============================================================================

(defn project-segment
  "§6.2 (`docs/design/yin.vm.code-as-tuples.md`): the canonical instruction
   vector with pc prepended — one `[pc tag & ops]` row per instruction, the
   tag being the mnemonic. The relation is per segment: pcs collide across
   segments, and source scope belongs to the interpreter, never to a tuple
   slot, so bare-pc rows from different segments are never unioned into one
   relation. A query over several segments takes them as separate sources
   or uses the segment-qualified form (`project-segment-qualified`)."
  [v]
  (mapv (fn [pc t] (into [pc] t)) (range) v))


(defn project-segment-qualified
  "§6.2's segment-qualified form `[segment-address pc tag & ops]`: one
   address column prepended, so every tag's arity gains one uniformly and
   per-tag arity stays fixed. The address is the vector's own
   `(jing/segment-key v)` — the §7.1/UCF §7.3.2 rule the loaders already
   follow — computed here rather than claimed by a caller, so a segment's
   rows are named by the content they are projected from."
  [v]
  (let [addr (jing/segment-key v)]
    (mapv (fn [r] (into [addr] r)) (project-segment v))))
