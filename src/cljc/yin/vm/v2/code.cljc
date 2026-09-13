(ns yin.vm.v2.code
  "Well-formedness of linear executable datoms (`:yin.code/*`).

   A segment is one batch: exactly one `:yin.code/type :segment` entity plus
   its instruction entities, whose order is an explicit `:yin.code/pc` fact
   (`docs/design/yin.vm.semantic.md` §2). This namespace judges the shape of a
   batch and nothing else. Decoding it into an image and executing that image
   belong to the semantic VM; the mnemonic-to-opcode mapping is the loader's
   interpretation, not a fact checked here.")


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
   `yin.vm.v2/index-datoms` reads one."
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
