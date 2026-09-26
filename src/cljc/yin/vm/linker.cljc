(ns yin.vm.linker
  "The code linker (docs/design/yin.vm.linker.md): fetches and verifies
   code over a DaoStream content pair (section 6). The linker holds no
   DaoJing handle: every read is a `:jing/get-content` request on a
   `dao.stream.rpc` client, answered by whatever serves the pair -- a
   local store, a `dao.jing.dht/create-content-dht` handle, or a remote
   endpoint -- behind `dao.jing.remote/default-handlers`.

   The portable interface is stepped (section 6.3): `link-state`,
   `request-link`, `step`, and `abandon` are pure functions over explicit
   state. `fetch` is host policy over a link runtime (section 6.4): it
   drives one link to its completion and runs step 5b against the
   receiver. `verify` (steps 3 to 5a) and `discharge` (step 5b) are the
   pure checks over values already in hand.

   One format-neutral admission-then-six-step pipeline serves every
   format (sections 4.1-4.2). A format record supplies the contract, the
   identity mint and match, the row-local validator, the whole-value
   validator, the three position-bearing scanners of section 4.1
   (`:obligations-fn`, `:definitions-fn`, `:applications-fn`), and the
   parts worklist; the four records are `ast-format`, `semantic-format`,
   `stack-format`, and `register-format` (section 5). Storage
   addresses (`jing/segment-key`) and VM identities (H, R) are separate
   preimages for the de Bruijn formats and the same preimage for the
   storage-derived ones, so step 2 checks the address and step 3 checks
   the identity; neither subsumes the other (I5).

   The rpc client, the indexes, the format records, and the bounds are
   linker-local state (section 6.2); the receiver environment is an
   explicit argument. There is no global loader, registry, callback, or
   cache here, and every outcome -- success or refusal -- is a returned
   plain data map (section 4.3)."
  (:require #?@(:cljd [["dart:typed_data" :as typed]])
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.space.query :as query]
            [dao.stream.apply :as apply]
            [dao.stream.rpc :as rpc]
            [yin.vm :as vm]
            [yin.vm.code :as code]
            [yin.vm.debruijn-code :as debruijn-code]
            [yin.vm.debruijn-linearize :as linearize]
            [yin.vm.debruijn-register-code :as debruijn-register-code]
            [yin.vm.debruijn-register-compile :as register-compile]
            [yin.vm.debruijn-resolve :as resolve]
            [yin.vm.module :as module]))


;; =============================================================================
;; Refusal vocabulary (section 4.3)
;; =============================================================================

(def refusal-reasons
  "Every reason a linker refusal may carry."
  #{:invalid-request :absent :address-mismatch :hash-mismatch
    :descriptor-defect :parts-limit :contract-mismatch :use-before-definition
    :unresolved-free :shadowed-free :unsupported-format :pairing-mismatch})


(defn refused
  "A qualified refusal: `{:status :refused, :reason reason}` merged with
   the step's own evidence `data`."
  ([reason] (refused reason {}))
  ([reason data]
   (merge data {:status :refused, :reason reason})))


(defn refused?
  "True when `res` is a linker refusal."
  [res]
  (and (map? res) (= :refused (:status res))))


(defn ok?
  "True when `res` is a verified linker outcome."
  [res]
  (and (map? res) (= :ok (:status res))))


;; =============================================================================
;; Free-name scanners (sections 5.1, 5.2)
;; =============================================================================

(defn- distinct-names
  [names]
  (vec (distinct names)))


(defn stack-free-names
  "Every `:load-free` name in a canonical stack instruction vector, in pc
   order of first appearance."
  [instruction-vector]
  (distinct-names
    (keep (fn [t] (when (= :load-free (nth t 0)) (nth t 1)))
          instruction-vector)))


(defn register-free-names
  "Every `:load-free` name across all bodies of a register image map, in
   pc order of first appearance. A register `:load-free` is
   `[:load-free rd name]`."
  [register-image-map]
  (distinct-names
    (keep (fn [t] (when (= :load-free (nth t 0)) (nth t 2)))
          (:instructions register-image-map))))


(defn- all-var-names
  "The conservative fallback for a vector whose layout the scope walk
   cannot read: every `:var` name, all free. Over-approximating
   obligations is always admissible."
  [v]
  (set (keep (fn [t]
               (when (and (vector? t)
                          (= :var (nth t 0))
                          (< 1 (count t)))
                 (nth t 1)))
             v)))


(defn- closure-spans
  "`[[c body r] ...]` for every `:closure` of canonical vector `v`: the
   body of the closure at pc `c` is `[body r]`, `r` the first `:return`
   at or after `body` (the out-of-line bodies of UCF section 7.3.2); nil
   `r` when there is none."
  [v]
  (let [n (count v)]
    (keep (fn [c]
            (let [t (nth v c)]
              (when (and (vector? t)
                         (= :closure (nth t 0))
                         (< 2 (count t)))
                (let [body (nth t 2)]
                  [c body
                   (first (filter (fn [pc]
                                    (let [u (nth v pc)]
                                      (and (vector? u)
                                           (= :return (nth u 0)))))
                                  (range body n)))]))))
          (range n))))


(defn- spans-conform?
  "Whether `spans` is the closed, off-pc-0, pairwise-disjoint body layout
   the scope walk reads; anything else rules every `:var` free."
  [spans]
  (let [pairs (map (fn [[_ b r]] [b r]) spans)]
    (and (every? (fn [[b r]] (and r (pos? b))) pairs)
         (= (count pairs) (count (distinct pairs)))
         (every? (fn [[[b1 r1] [b2 r2]]] (or (< r1 b2) (< r2 b1)))
                 (for [x pairs, y pairs :when (neg? (compare x y))]
                   [x y])))))


(defn- vector-layout
  "The closure body spans of canonical vector `v` when they are the
   closed, off-pc-0, pairwise-disjoint layout the scope walk reads, else
   nil -- every scanner then degrades (section 4.1)."
  [v]
  (let [spans (closure-spans v)]
    (when (spans-conform? spans) spans)))


(defn- safe-layout
  "Like `vector-layout`, but a vector the walk cannot read at all (an
   operand that throws) degrades too."
  [v]
  (try (vector-layout v)
       (catch #?(:cljd Object :clj Throwable :cljs :default) _ nil)))


(defn- in-body-pc?
  "Whether pc `p` lies in one of the closure body spans `spans`."
  [spans p]
  (boolean (some (fn [[_ b r]] (<= b p r)) spans)))


(defn- conditional-ranges
  "The pcs a conditional target range covers in canonical vector `v`
   (section 4.1): for every `:jump` or `:branch-false` at pc b with
   target t, the pcs strictly between b and t, either direction -- the
   fall-through a taken branch skips, and a backward jump's body."
  [v]
  (into #{}
        (mapcat (fn [[b t]]
                  (when (and (integer? t) (not= b t))
                    (range (inc (min b t)) (max b t)))))
        (keep (fn [pc]
                (let [t (nth v pc)]
                  (when (and (vector? t)
                             (contains? #{:jump :branch-false}
                                        (nth t 0)))
                    [pc (nth t 1)])))
              (range (count v)))))


(defn- degraded-occurrences
  "The conservative degradation's own occurrence records (section 4.1):
   every name retained at no position, inside a body -- a scanner that
   cannot read positions retains every occurrence and discharges
   nothing."
  [names]
  (vec (sort-by (comp str :name)
                (map (fn [n] {:name n, :at nil, :in-body? true}) names))))


(defn- degraded-definitions
  "The conservative degradation's own definition records (section 4.1):
   every key retained at no position, conditional -- a definition whose
   position is unknown discharges nothing."
  [names]
  (vec (sort-by (comp str :name)
                (map (fn [n] {:name n, :at nil, :conditional? true})
                     names))))


(defn semantic-free-occurrences
  "Every free `:var` occurrence of canonical vector `v` as a section 4.1
   record `{:name sym :at pc :in-body? b}`, in pc order (UCF section
   7.3.2): a `:var` at pc `p` is bound iff `p` lies in the body of a
   `:closure` whose params hold the name, transitively through the body
   holding that closure's own pc. Over a vector whose body spans are not
   closed, off pc 0, and pairwise disjoint -- anything but the
   ast-to-bytecode layout -- every `:var` name degrades to a record with
   no position: over-approximating obligations is always admissible."
  [v]
  (try
    (if-let [spans (vector-layout v)]
      (let [params (into {}
                         (keep (fn [c]
                                 (let [t (nth v c)]
                                   (when (and (vector? t)
                                              (= :closure (nth t 0)))
                                     [c (nth t 1)]))))
                         (range (count v)))
            owner (fn [pc]
                    (some (fn [[c b r]] (when (<= b pc r) c)) spans))
            bound? (fn bound?
                     [pc sym]
                     (when-let [c (owner pc)]
                       (or (some #(= % sym) (get params c))
                           (bound? c sym))))]
        (vec (keep (fn [pc]
                     (let [t (nth v pc)]
                       (when (and (vector? t)
                                  (= :var (nth t 0))
                                  (< 1 (count t))
                                  (not (bound? pc (nth t 1))))
                         {:name (nth t 1), :at pc,
                          :in-body? (in-body-pc? spans pc)})))
                   (range (count v)))))
      (degraded-occurrences (all-var-names v)))
    (catch #?(:cljd Object :clj Throwable :cljs :default) _
      (degraded-occurrences (all-var-names v)))))


(defn semantic-free-names
  "Every `:var` name free at some pc of canonical vector `v` (UCF
   section 7.3.2): the names of `semantic-free-occurrences`' records,
   which carry the scope walk and the degradation."
  [v]
  (into #{} (map :name) (semantic-free-occurrences v)))


(defn- step-order
  "The execution order of two occurrence-path steps of one row (section
   6.1): a `:node` slot's step is its row position, a `:nodes` slot's
   the pair `[position i]`. A bare position evaluates before the
   operands of the `:nodes` slot that follows it."
  [a b]
  (cond
    (and (integer? a) (integer? b)) (compare a b)
    (integer? a) -1
    (integer? b) 1
    :else (let [n (min (count a) (count b))]
            (loop [i 0]
              (if (= i n)
                (compare (count a) (count b))
                (let [c (compare (nth a i) (nth b i))]
                  (if (zero? c) (recur (inc i)) c)))))))


(defn- path-order
  "The execution order of two occurrence paths from one root (section
   4.1's tree order): slot order at the first step where they diverge."
  [a b]
  (let [n (min (count a) (count b))]
    (loop [i 0]
      (if (= i n)
        (compare (count a) (count b))
        (let [c (step-order (nth a i) (nth b i))]
          (if (zero? c) (recur (inc i)) c))))))


(defn- path-before?
  "True when occurrence path `d` executes before occurrence path `u`."
  [d u]
  (neg? (path-order d u)))


(def ^:private tree-occurrence-query
  "`vm/free-names`' free-name query over the row and occurrence
   relations, extended to return each occurrence's path (section 4.1):
   the same rules, one more find column."
  '[:find ?name ?path
    :in $ $occ % ?root
    :where
    [$occ ?root ?path ?v]
    [?v :variable ?name]
    (not (occ-bound? ?root ?path ?name))])


(def ^:private tree-definition-query
  "The definition query behind `vm/ast-requirements` (section 4.1): the
   `:vm/store-put` rows and the definition forms (an application of the
   `yin/def` operator with exactly two operands, the first a literal),
   each binding joined with its binding row's occurrence path -- the
   definitions scanner extends a definition's path one step past its
   operands, to the invocation position (see
   `tree-definition-occurrences`)."
  '[:find ?key ?path
    :in $ $occ ?root
    :where
    [$occ ?root ?path ?v]
    (or-join [?v ?key]
             [?v :vm/store-put ?key _]
             (and [?v :application ?op ?operands _]
                  [?op :variable yin/def]
                  [(count ?operands) 2]
                  [(nth ?operands 0) ?name-id]
                  [?name-id :literal ?key]))])


(def ^:private tree-application-query
  "The application-row query over the same relation (section 4.1): one
   application site per `:application` row, carried with the row's
   operand count -- the site is recorded where the application happens,
   which is only after the operands have run."
  '[:find ?path ?n
    :in $ $occ ?root
    :where
    [$occ ?root ?path ?v]
    [?v :application _ ?operands _]
    [(count ?operands) ?n]])


(defn- tree-row-at
  "The row `path` names from the tree's root, nil when the path leaves
   the tree. A step is a `:node` slot's row position or a `[position i]`
   pair into a `:nodes` slot's vector (section 6.1)."
  [{:keys [root rows]} path]
  (loop [id root, steps (seq path)]
    (if (nil? steps)
      (get rows id)
      (let [row (get rows id)
            step (first steps)
            child (when row
                    (if (vector? step)
                      (let [slot (nth row (nth step 0))]
                        (when (vector? slot) (nth slot (nth step 1))))
                      (nth row step)))]
        (recur child (next steps))))))


(defn- tree-enclosure
  "Whether the occurrence at `path` lies inside a lambda body, and
   whether an `:if` branch or a lambda body encloses it -- section 4.1's
   `:in-body?` and `:conditional?`. Each proper prefix of `path` names
   the row the next step leaves: a `:lambda`'s body slot is 3, an `:if`'s
   branches 3 and 4 (the section 2.3 grammar's own positions)."
  [tree path]
  (loop [i 0, in-body? false, conditional? false]
    (if (or (and in-body? conditional?) (>= i (count path)))
      [in-body? conditional?]
      (let [row (tree-row-at tree (subvec path 0 i))
            tag (and row (nth row 1))
            step (nth path i)]
        (recur (inc i)
               (or in-body? (and (= :lambda tag) (= 3 step)))
               (or conditional?
                   (and (= :lambda tag) (= 3 step))
                   (and (= :if tag) (or (= 3 step) (= 4 step)))))))))


(defn- tree-rows
  "The row and occurrence relations the tree scanners query, with the
   tree's root."
  [tree]
  (let [root (:root tree)
        db (query/relation (vals (:rows tree)))
        occ (query/relation (vm/occurrences tree))]
    {:root root, :db db, :occ occ}))


(defn- tree-free-name-occurrences
  "Every free `:variable` occurrence of the tree `{:root root :rows {id
   row}}` as a section 4.1 record `{:name sym :at [root path] :in-body?
   b}`, in the tree's own path order: `vm/free-names`' occurrence rules
   wrapped to carry each occurrence's path (section 4.1). The definition
   operator is syntax, never a name (Rule R): the validator admits it
   only as a definition's operator, so it is never an occurrence."
  [tree]
  (let [{:keys [root db occ]} (tree-rows tree)]
    (->> (query/collect
           (query/q tree-occurrence-query db occ
                    vm/occurrence-rules root
                    {:fns vm/occurrence-fns}))
         (remove (fn [[name _]] (vm/reserved-name? name)))
         (map (fn [[name path]]
                (let [[in-body? _] (tree-enclosure tree path)]
                  {:name name, :at [root path], :in-body? in-body?})))
         (sort-by (comp second :at) path-order)
         vec)))


(defn- tree-definition-occurrences
  "Every constant store key the tree binds at module level as a section
   4.1 record `{:name key :at [root path] :conditional? b}`: the
   store-key query behind `vm/ast-requirements` (`:vm/store-put` rows
   and definition forms), each binding carried with its enclosing tags
   (section 4.1). A definition's binding is recorded at its invocation
   position -- the application row's own path extended one step past
   its two operands, where the engine writes the key only after the
   value operand has run -- so a read inside the value operand precedes
   the definition and keeps its obligation, exactly as for application
   sites (a `:vm/store-put` row binds at its own execution position and
   keeps its own path). The definition operator is syntax, never a name
   (Rule R): the validator has refused every tree that could rebind or
   alias it, and no engine resolves it, so every definition form is a
   store."
  [tree]
  (let [{:keys [root db occ]} (tree-rows tree)]
    (->> (query/collect
           (query/q tree-definition-query db occ root))
         (map (fn [[key path]]
                (let [row (tree-row-at tree path)
                      at-path (if (and row (= :application (nth row 1)))
                                (conj path [3 2])
                                path)
                      [_ conditional?] (tree-enclosure tree at-path)]
                  {:name key, :at [root at-path],
                   :conditional? conditional?})))
         (sort-by (comp second :at) path-order)
         vec)))


(defn- tree-application-sites
  "Every application row of the tree as a section 4.1 record `{:at
   [root path]}` -- one application site at its invocation position: the
   row's own path extended one step past its operands, the position at
   which the walker applies the operator after evaluating them
   (section 4.1), so a definition inside an operand dominates an
   application of the enclosing form."
  [tree]
  (let [{:keys [root db occ]} (tree-rows tree)]
    (->> (query/collect
           (query/q tree-application-query db occ root))
         (map (fn [[path n]]
                {:at [root (conj path [3 n])]}))
         (sort-by (comp second :at) path-order)
         vec)))


(defn- stack-free-occurrences
  "Every `:load-free` occurrence of a canonical stack instruction vector
   as a section 4.1 record `{:name sym :at pc :in-body? b}`, in pc
   order. Over a vector whose closure body spans the scope walk cannot
   read, every `:load-free` name degrades to a record with no position
   (section 4.1)."
  [v]
  (if-let [spans (safe-layout v)]
    (vec (keep-indexed
           (fn [pc t]
             (when (and (vector? t) (= :load-free (nth t 0)))
               {:name (nth t 1), :at pc,
                :in-body? (in-body-pc? spans pc)}))
           v))
    (degraded-occurrences (stack-free-names v))))


(defn- binding-key
  "The key a vector-format instruction `t` binds, or nil: a `:store-put`
   or `:define` carries it at `slot`."
  [t slot]
  (when (and (vector? t)
             (contains? #{:store-put :define} (nth t 0))
             (< slot (count t)))
    (nth t slot)))


(defn- vector-definition-occurrences
  "Every `:store-put` and `:define` key the canonical instruction vector
   `v` binds, as a section 4.1 record `{:name key :at pc :conditional?
   b}`: conditional when a jump target range or a closure body encloses
   the pc (section 4.1). Degrades to records with no position over a
   vector whose body spans the scope walk cannot read."
  [v]
  (if-let [spans (safe-layout v)]
    (let [ranges (conditional-ranges v)]
      (vec (keep-indexed
             (fn [pc t]
               (when-let [k (binding-key t 1)]
                 {:name k, :at pc,
                  :conditional? (or (contains? ranges pc)
                                    (in-body-pc? spans pc))}))
             v)))
    (degraded-definitions (keep (fn [t] (binding-key t 1)) v))))


(defn- vector-application-sites
  "Every application site of the canonical instruction vector `v` as a
   section 4.1 record `{:at pc}`: a `:call` or `:ffi-call` pc -- a
   direct call, a call of an imported name, or a call of a primitive
   that receives a function argument, the callee possibly applying what
   it is handed (section 4.1). Degrades to records with no position over
   a vector whose body spans the scope walk cannot read."
  [v]
  (let [call? (fn [t]
                (and (vector? t)
                     (contains? #{:call :ffi-call} (nth t 0))))]
    (if (safe-layout v)
      (vec (keep-indexed (fn [pc t] (when (call? t) {:at pc})) v))
      (vec (keep (fn [t] (when (call? t) {:at nil})) v)))))


(defn- register-body-at
  "The index of the declared body of register image map `image` whose pc
   range covers `p`, or nil: body 0 is the main program, every other
   body a lambda's."
  [image p]
  (some (fn [i]
          (let [b (nth (:bodies image) i)]
            (when (and (integer? (:start b)) (integer? (:end b))
                       (<= (:start b) p (:end b)))
              i)))
        (range (count (:bodies image)))))


(defn- register-in-body?
  "Whether pc `p` of register image map `image` lies in a lambda body
   rather than the main program (section 4.1)."
  [image p]
  (let [i (register-body-at image p)]
    (boolean (and i (pos? i)))))


(defn- register-conditional-ranges
  "The conditional target range pcs of a register image's instructions
   (section 4.1): a `:jump`'s target is its first operand, a
   `:branch-false`'s its second."
  [image]
  (into #{}
        (mapcat (fn [[b t]]
                  (when (and (integer? t) (not= b t))
                    (range (inc (min b t)) (max b t)))))
        (keep-indexed
          (fn [pc t]
            (when (vector? t)
              (case (nth t 0)
                :jump [pc (nth t 1)]
                :branch-false [pc (nth t 2)]
                nil)))
          (:instructions image))))


(defn- register-free-occurrences
  "Every `:load-free` occurrence across all bodies of a register image
   map as a section 4.1 record `{:name sym :at pc :in-body? b}`, in pc
   order. A register `:load-free` is `[:load-free rd name]`."
  [image]
  (vec (keep-indexed
         (fn [pc t]
           (when (and (vector? t) (= :load-free (nth t 0)))
             {:name (nth t 2), :at pc,
              :in-body? (register-in-body? image pc)}))
         (:instructions image))))


(defn- register-definition-occurrences
  "Every `:store-put` and `:define` key the register image binds, as a
   section 4.1 record `{:name key :at pc :conditional? b}`: conditional
   when a jump target range or a lambda body encloses the pc (section
   4.1). A register `:store-put` is `[:store-put rd key value]`, a
   `:define` `[:define rd key rs]`."
  [image]
  (let [ranges (register-conditional-ranges image)]
    (vec (keep-indexed
           (fn [pc t]
             (when-let [k (binding-key t 2)]
               {:name k, :at pc,
                :conditional? (or (contains? ranges pc)
                                  (register-in-body? image pc))}))
           (:instructions image)))))


(defn- register-application-sites
  "Every application site of the register image as a section 4.1 record
   `{:at pc}`: a `:call` or `:ffi-call` pc (section 4.1)."
  [image]
  (vec (keep-indexed
         (fn [pc t]
           (when (and (vector? t)
                      (contains? #{:call :ffi-call} (nth t 0)))
             {:at pc}))
         (:instructions image))))


;; =============================================================================
;; Row-local validation and the parts worklist (sections 5.1, 4.2 step 2)
;; =============================================================================

(defn row-local-defect
  "The section 7.4 row-local rules -- `:tag`, `:arity`, `:slot-kind`,
   `:saturation` -- over one row body `[tag & slots]`, nil when the body
   is locally well formed. The whole-tree rules (`:id-resolves`,
   `:acyclic`, `:root-reachable`) cannot run over one body and are never
   reported here: a locally well-formed body's child ids resolve in
   step 2's worklist. A malformed row's slots are never followed
   (section 5.1). A body that is not a vector is a `:tag` defect: only a
   vector can be a row."
  [body]
  (let [row (if (vector? body) (into [::root] body) [::root body])
        res (vm/validate-rows {:root ::root, :rows {::root row}})]
    (when (contains? #{:tag :arity :slot-kind :saturation} (:rule res))
      res)))


(defn- body-child-ids
  "The child row ids of one fetched body's `node`/`nodes` slots, by the
   grammar's own positions. Only valid segment addresses are followed: a
   slot holding something else stays in the row for `validate-rows` to
   name as its `:slot-kind` defect, exactly as it would in a
   hand-supplied row set."
  [body]
  (let [slots (get vm/semantic-bytecode-grammar (first body))]
    (when (and slots (= (count slots) (dec (count body))))
      (mapcat (fn [[_field kind] v]
                (case kind
                  :node (when (jing/segment-address? v) [v])
                  :nodes (filter jing/segment-address? v)
                  nil))
              slots (rest body)))))


;; =============================================================================
;; Format records (section 5)
;; =============================================================================

(def stack-format
  "The `:yin.debruijn.code` format record: stack images identified by H
   under the \"b2\" contract (`vm/stack-contract`), one canonical vector
   per image. Free names are `:load-free` operands, definitions
   `:store-put` and `:define` operands, application sites the call
   opcodes -- all position-bearing by pc (section 5.3)."
  {:format              :yin.debruijn.code,
   :contract            vm/stack-contract,
   :identity-fn         debruijn-code/image-hash,
   :identity-matches-fn (fn [H v] (= H (debruijn-code/image-hash v))),
   :row-defect-fn       debruijn-code/image-defect,
   :validate-fn         debruijn-code/image-defect,
   :obligations-fn      stack-free-occurrences,
   :definitions-fn      vector-definition-occurrences,
   :applications-fn     vector-application-sites,
   :parts-fn            (constantly nil)})


(def register-format
  "The `:yin.debruijn.register` format record: register images identified
   by R under the \"r2\" contract (`vm/register-contract`), one image
   map per image. Free names are `[:load-free rd name]` operands across
   all bodies, definitions the `:store-put` and `:define` keys,
   application sites the call opcodes (section 5.4)."
  {:format              :yin.debruijn.register,
   :contract            vm/register-contract,
   :identity-fn         debruijn-register-code/register-hash,
   :identity-matches-fn (fn [R v]
                          (= R (debruijn-register-code/register-hash v))),
   :row-defect-fn       debruijn-register-code/register-image-defect,
   :validate-fn         debruijn-register-code/register-image-defect,
   :obligations-fn      register-free-occurrences,
   :definitions-fn      register-definition-occurrences,
   :applications-fn     register-application-sites,
   :parts-fn            (constantly nil)})


(def semantic-format
  "The `:yin.semantic/code` format record: the canonical positional
   instruction vector stored as the exact payload at its own
   `segment-key` (UCF section 7.3.4), under the \"v3\" contract
   (`vm/semantic-contract`). Identity and address are one preimage; step
   3 still runs, because it is the only check that the index did not
   point the identity at a different, correctly stored payload (section
   4.2). Free names are `:var`
   operands, definitions `:store-put` and `:define` operands,
   application sites the call opcodes (section 5.2)."
  {:format              :yin.semantic/code,
   :contract            vm/semantic-contract,
   :identity-fn         jing/segment-key,
   :identity-matches-fn jing/segment-matches?,
   :row-defect-fn       code/well-formed-vector?,
   :validate-fn         code/well-formed-vector?,
   :obligations-fn      semantic-free-occurrences,
   :definitions-fn      vector-definition-occurrences,
   :applications-fn     vector-application-sites,
   :parts-fn            (constantly nil)})


(def ast-format
  "The `:yin.ast/code` format record: one row body `[tag & slots]` per
   address (D3), the root row's id the tree's identity, under the \"v3\"
   contract (`vm/ast-contract`). Step 2 is a bounded worklist over the
   grammar's `node`/`nodes` slots; the whole tree is assembled for step
   4 by prepending each part's address, the shape `validate-rows` takes.
   All three scanners are Datalog over the rows, position-bearing by
   occurrence path (section 5.1)."
  {:format              :yin.ast/code,
   :contract            vm/ast-contract,
   :identity-fn         jing/segment-key,
   :identity-matches-fn jing/segment-matches?,
   :row-defect-fn       row-local-defect,
   :validate-fn         vm/validate-rows,
   :obligations-fn      tree-free-name-occurrences,
   :definitions-fn      tree-definition-occurrences,
   :applications-fn     tree-application-sites,
   :parts-fn            body-child-ids})


;; =============================================================================
;; The H and R indexes (section 6)
;; =============================================================================

(defn address-attribute
  "The index attribute a format's identities are published under:
   `:yin.debruijn.code/address` or `:yin.debruijn.register/address`."
  [format]
  (keyword (name (:format format)) "address"))


(defn index-from-datoms
  "A plain identity -> address map read from index datoms
   `[identity attribute address]`, keeping only the attribute `format`
   publishes under. The index is composition data; an entry is a claim,
   not a proof (step 3 checks it)."
  [format datoms]
  (let [attr (address-attribute format)]
    (into {}
          (keep (fn [[e a v]] (when (= attr a) [e v])))
          datoms)))


;; =============================================================================
;; The receiver environment (step 5)
;; =============================================================================
;; The receiver binds a free name in the order
;; `yin.vm.engine/resolve-var` uses: free env -> store -> primitives ->
;; module registry. A name the image publisher meant as a primitive or a
;; module binding must resolve there; a receiver whose free env or store
;; also binds it would bind it differently, so that is refused as
;; shadowed rather than executed (D11).

(defn- obligation-name
  "The name an obligation carries: a section 4.1 record's `:name`, or
   the bare name itself."
  [obligation]
  (if (map? obligation) (:name obligation) obligation))


(defn- shadowed?
  [{:keys [free-env store]} obligation]
  (let [sym (obligation-name obligation)]
    (or (contains? free-env sym) (contains? store sym))))


(defn- resolvable?
  [{:keys [primitives modules]} obligation]
  (let [sym (obligation-name obligation)]
    (or (contains? primitives sym)
        (some (fn [k] (and (map? k) (= sym (:name k))))
              (keys primitives))
        (and (symbol? sym)
             (some? (namespace sym))
             (some? (module/resolve-module
                      modules
                      (symbol (str (namespace sym) "." (name sym)))))))))


(defn discharge
  "Step 5b, pure (section 6.4): the first refusal for the obligations
   `obligations` against `receiver` (`{:free-env :store :primitives
   :modules}`, every key optional), or nil when every obligation binds in
   the receiver identically. An obligation is a section 4.1 record; a
   bare name is accepted for compositions that scan names themselves.
   The registry
   discharges an obligation under its own entry -- a composition that
   derives its registry from the same scan keys the records -- or under
   the bare name."
  [receiver obligations]
  (some (fn [obligation]
          (cond (shadowed? receiver obligation)
                (refused :shadowed-free
                         {:name (obligation-name obligation)})
                (not (resolvable? receiver obligation))
                (refused :unresolved-free
                         {:name (obligation-name obligation)})))
        obligations))


;; =============================================================================
;; Step 5a: the occurrence/definition join (section 4.2)
;; =============================================================================

(defn- precedes?
  "True when position `d` executes before position `u` under the
   position's own order (section 4.1): a pc for the vector formats, the
   tree's slot order for an occurrence path `[root path]`. Positions of
   different shapes, or of different roots, never discharge each other."
  [d u]
  (cond
    (and (integer? d) (integer? u)) (< d u)
    (and (vector? d) (vector? u)
         (= 2 (count d)) (= 2 (count u))
         (= (nth d 0) (nth u 0)))
    (path-before? (nth d 1) (nth u 1))
    :else false))


(defn- scanned
  "The records `slot`'s scanner yields for `value`, or nil when the slot
   is missing or the scanner cannot run -- step 5a then falls under the
   conservative degradation (section 4.1)."
  [format slot value]
  (try (when-let [f (get format slot)] (f value))
       (catch #?(:cljd Object :clj Throwable :cljs :default) _ nil)))


(defn- undischarged
  "Step 5a's join: the free-name occurrences the scanners yielded,
   against the definitions and the application sites. A definition
   discharges an occurrence by defined-before-use under control-flow
   dominance -- an unconditional definition whose position precedes the
   occurrence's (section 4.1's order rules). A main-sequence occurrence
   whose every definition is later is `:use-before-definition`; one
   whose only definitions are conditional and earlier keeps its
   obligation. A lambda-body occurrence is discharged only by an
   unconditional definition that precedes every application site; an
   image with no application site discharges a body occurrence against
   every unconditional definition; with no usable application positions
   nothing inside a body is discharged at all. A position the scanners
   could not compute retains its occurrence and never discharges.
   Returns `{:obligations [...]}` -- the retained records, in scan
   order -- or a `:use-before-definition` refusal naming the
   occurrence."
  [occurrences definitions applications]
  (let [sites (vec applications)
        usable-sites? (and (sequential? applications)
                           (every? (fn [s] (some? (:at s))) sites))
        by-name (group-by :name definitions)
        unconditional (fn [defs]
                        (filter (fn [d]
                                  (and (some? (:at d))
                                       (false? (:conditional? d))))
                                defs))
        verdict
        (fn [occ]
          (let [at (:at occ)]
            (if (nil? at)
              :retained
              (let [defs (get by-name (:name occ))]
                (if-not (:in-body? occ)
                  (let [dominating (filter (fn [d]
                                             (precedes? (:at d) at))
                                           (unconditional defs))]
                    (cond
                      (seq dominating) :discharged
                      (and (seq defs)
                           (every? (fn [d]
                                     (and (some? (:at d))
                                          (precedes? at (:at d))))
                                   defs))
                      :use-before-definition
                      :else :retained))
                  (cond
                    (not usable-sites?) :retained
                    (empty? sites)
                    (if (seq (unconditional defs))
                      :discharged
                      :retained)
                    :else
                    (if (seq (filter (fn [d]
                                       (every? (fn [s]
                                                 (precedes? (:at d)
                                                            (:at s)))
                                               sites))
                                     (unconditional defs)))
                      :discharged
                      :retained)))))))]
    (reduce (fn [acc occ]
              (case (verdict occ)
                :discharged acc
                :retained (update acc :obligations conj occ)
                :use-before-definition
                (reduced {:obligations []
                          :defect (refused :use-before-definition
                                           {:name (:name occ),
                                            :at (:at occ)})})))
            {:obligations []}
            occurrences)))


;; =============================================================================
;; Step 2's reads over the content pair (sections 4.2, 6.1)
;; =============================================================================

(def ^:private missing
  "Opaque per-host sentinel for a read that carried no payload, never a
   keyword: a keyword sentinel could collide with a genuinely stored
   payload, and on cljs each keyword literal site is its own object."
  #?(:cljd (Object.)
     :clj (Object.)
     :cljs (js-obj)))


(def ^:private get-content-op
  "The content pair's one read operation: `dao.jing.remote`'s existing
   wire vocabulary (section 6.1). The linker writes no protocol of its
   own for content."
  :jing/get-content)


(defn- presence-envelope?
  "True only for the exact wire envelope `{:found? boolean :value v}` that
   `:jing/get-content` answers."
  [x]
  (and (map? x)
       (= #{:found? :value} (set (keys x)))
       (let [f (:found? x)] (or (true? f) (false? f)))))


(defn- answered-text
  "Step 2's read: the Base64 reply text one content-pair completion
   carries, or `missing` when it carries none -- not found, an error
   response, a malformed envelope, a value that is not text, or a
   request lost by the medium. A failing read has no payload for this
   link, so it fails closed as `:absent`, never as execution. Nothing is
   decoded here, so that step 2's byte cap runs before the text is."
  [completion]
  (let [response (:dao.stream.rpc/response completion)
        answer (when (and (some? response)
                          (nil? (apply/response-error response)))
                 (apply/response-ok response))]
    (if (and (presence-envelope? answer)
             (true? (:found? answer))
             (string? (:value answer)))
      (:value answer)
      missing)))


(defn- least-decoded-length
  "The fewest bytes Base64 text of `text`'s length can decode to: strict
   padded Base64 of n characters is 3n/4 bytes less at most two of
   padding. Text over the budget by this measure is over it however it
   decodes, so it is refused without being decoded; text within it is
   decoded and meets the exact decoded-length cap."
  [text]
  (- (* 3 (quot (count text) 4)) 2))


(defn- text-bytes
  "The bytes strict padded Base64 `text` decodes to, or `missing` when it
   is not strict Base64 (fails closed as `:absent`)."
  [text]
  (try (jing/base64->bytes text)
       (catch #?(:cljd Object :clj Throwable :cljs :default) _
         missing)))


(defn- decoded
  "The value of canonical bytes bs, or nil when they do not decode."
  [bs]
  (try (cbor/decode bs)
       (catch #?(:cljd Object :clj Throwable :cljs :default) _
         nil)))


(defn- byte-count
  "The length of host bytes bs, for the `:max-bytes` bound."
  [bs]
  #?(:clj (alength ^bytes bs)
     :cljs (.-length bs)
     :cljd (.-length ^typed/Uint8List bs)))


(defn- identity-of
  "The identity `value` mints under the format, or nil when it cannot be
   minted at all (a malformed payload mints no identity). Used by
   `publish!` and for refusal evidence, never by verification: step 3 is
   `:identity-matches-fn`, directed by the identity the request carries
   (I5)."
  [format value]
  (try ((:identity-fn format) value)
       (catch #?(:cljd Object :clj Throwable :cljs :default) _
         nil)))


(defn- matches-identity?
  "Step 3's check, directed by the identity the request carries. False
   when the payload cannot be matched at all (a malformed payload
   matches no identity)."
  [format identity value]
  (try ((:identity-matches-fn format) identity value)
       (catch #?(:cljd Object :clj Throwable :cljs :default) _ false)))


(defn- row-defect
  "Step 2's per-part check, or nil when the part is locally well formed.
   A `:row-defect-fn` that throws refuses like a validator."
  [format value]
  (try ((:row-defect-fn format) value)
       (catch #?(:cljd Object :clj Throwable :cljs :default) _
         {:rule :validator-refused})))


(defn- validation-defect
  [format value]
  (try ((:validate-fn format) value)
       (catch #?(:cljd Object :clj Throwable :cljs :default) _
         {:rule :validator-refused})))


(defn- checked-part
  "One worklist visit over the Base64 reply `text` the content pair
   answered for `address`: bound the text against the byte `budget`
   remaining before it is decoded at all, decode it strictly, check the
   decoded size against the budget before hashing or decoding the
   payload, verify the bytes against the address they were requested
   at, then validate the part row-locally -- in that order, so an
   oversized reply is refused before its text is decoded, an oversized
   row before its bytes are hashed or decoded, mismatched bytes are never
   decoded for evidence, and a malformed row is refused before anything
   it references is decoded or enqueued. The order is total: no reply
   (`:absent`), then the text bound (`:parts-limit`), then text that is
   not strict Base64 (`:absent`), then the decoded bound
   (`:parts-limit`), so oversize text is `:parts-limit` whether or not it
   is Base64. Returns `{:value v :bytes n}` or a refusal."
  [format identity address text budget]
  (cond
    (identical? missing text)
    (refused :absent {:address address})

    (and budget (> (least-decoded-length text) budget))
    (refused :parts-limit {:bound :max-bytes, :address address})

    :else
    (let [bs (text-bytes text)]
      (cond
        (identical? missing bs)
        (refused :absent {:address address})

        (and budget (> (byte-count bs) budget))
        (refused :parts-limit {:bound :max-bytes, :address address})

        (not (jing/segment-bytes-match? address bs))
        (refused :address-mismatch {:address address})

        :else
        (let [value (decoded bs)]
          (if (nil? value)
            (refused :address-mismatch {:address address, :value nil})
            (if-let [defect (row-defect format value)]
              (refused :descriptor-defect
                       {:identity identity, :address address,
                        :defect defect})
              {:value value, :bytes (byte-count bs)})))))))


(def default-bounds
  "The safe finite composition bounds a fetch falls back to where the
   caller supplies none (section 4.2 step 2): generous for every honest
   image the compositions hold, finite for a hostile one, so no fetch
   path walks an unbounded worklist."
  {:max-parts 4096, :max-depth 256, :max-bytes 16777216})


(defn- bounded
  "The composition's `bounds` with every bound it left nil (or never
   named) taking `default-bounds`."
  [bounds]
  (into default-bounds
        (map (fn [[k v]] [k (or v (get default-bounds k))]))
        (or bounds {})))


(defn- enqueue-children
  "The children of one fetched part that are not already enqueued, each
   paired with its depth, up to the parts budget: the first child beyond
   the remaining `max-parts` is refused `:parts-limit`, naming the bound
   and its own address, before it is fetched (section 4.2). Returns
   `{:queue [[address depth] ...] :fresh [address ...]}` or a refusal."
  [child-addrs enqueued depth max-parts]
  (loop [[child & more] (filter (fn [a] (not (contains? enqueued a)))
                                (distinct child-addrs))
         n (count enqueued)
         acc []]
    (cond
      (nil? child)
      {:queue (mapv (fn [a] [a (inc depth)]) acc), :fresh acc}

      (>= n max-parts)
      (refused :parts-limit {:bound :max-parts, :address child})

      :else
      (recur more (inc n) (conj acc child)))))


;; =============================================================================
;; verify: steps 3 to 5a over values in hand (section 6.4)
;; =============================================================================

(defn verify
  "Steps 3 to 5a (section 4.2), pure, over values already in hand: the
   check a link runs the moment step 2 completes, exported so tests and
   compositions holding a payload can run it without a stream (section
   6.4). `format` is a format record, `identity` the requested identity,
   `address` the root's storage address, and `parts` `{address value}`
   as step 2 fetched them, the root included. Returns the step-6 outcome
   `{:status :ok :format f :identity i :address a :value v :obligations
   [...]}`, plus `:parts` for a multi-part format, or a refusal.
   Discharge against a receiver (5b) is `discharge`'s.

   Whether the format has parts at all is the record's own answer over
   the root (`:parts-fn` returns nil for a single payload, a sequence --
   possibly empty -- for a multi-part format); a multi-part whole is the
   tree assembled by prepending each part's address, the shape
   `validate-rows` takes (section 5.1)."
  [format identity address parts]
  (let [root-value (get parts address)
        multi? (some? ((:parts-fn format) root-value))
        whole (if multi?
                {:root identity,
                 :rows (into {}
                             (map (fn [[a v]] [a (into [a] v)]))
                             parts)}
                root-value)]
    (if-not (matches-identity? format identity root-value)
      (refused :hash-mismatch
               {:expected identity, :actual (identity-of format root-value)})
      (if-let [defect (validation-defect format whole)]
        (refused :descriptor-defect {:identity identity, :defect defect})
        (let [joined (undischarged
                       ((:obligations-fn format) whole)
                       (scanned format :definitions-fn whole)
                       (scanned format :applications-fn whole))]
          (or (:defect joined)
              (merge {:status :ok,
                      :format (:format format),
                      :identity identity,
                      :address address,
                      :value whole,
                      :obligations (:obligations joined)}
                     (when multi? {:parts parts}))))))))


;; =============================================================================
;; The stepped core (sections 6.2, 6.3)
;; =============================================================================

(defn link-state
  "The linker-local state (section 6.2), constructed once by the
   composition. `:rpc` is a `dao.stream.rpc` client state on the content
   pair; `:formats` maps a format keyword to its record (section 5);
   `:indexes` maps a format keyword to its index, a map or function from
   identity to storage address (the identity function for the two
   storage-derived formats, section 3); `:bounds` is `{:max-parts n
   :max-depth d :max-bytes b}`, every bound left nil taking
   `default-bounds`. Functions live here and only here; no socket, atom,
   handle, or scheduler does. The name environment, authority,
   derivation, and fallback policies of section 8 are M4's."
  [{:keys [rpc formats indexes bounds]}]
  {:rpc rpc,
   :formats (or formats {}),
   :indexes (or indexes {}),
   :bounds (bounded bounds),
   :links {},
   :order [],
   :routes {},
   :outbox [],
   :next-fetch 0})


(def ^:private request-keys
  "The closed key set of a link request (section 6.3)."
  #{:yin.link/id :yin.link/format :yin.link/contract :yin.link/name
    :yin.link/identity})


(defn- portable-scalar?
  "Whether `x` is a value a link request may carry beside its id: a
   scalar, keyword, symbol, or address -- never a function, handle,
   collection, or host object (section 6.3)."
  [x]
  (or (nil? x) (true? x) (false? x) (number? x) (string? x)
      (keyword? x) (symbol? x)))


(defn- link-id?
  "Whether `x` is a well-formed link id: the `[origin counter]` pair of
   section 7.2, two non-nil portable scalars."
  [x]
  (and (vector? x)
       (= 2 (count x))
       (every? (fn [e] (and (some? e) (portable-scalar? e))) x)))


(defn- in-flight?
  "Whether `id` names a link whose completion is still owed."
  [state id]
  (or (contains? (:links state) id)
      (some (fn [c] (= id (:yin.link/id c))) (:outbox state))))


(defn- request-defect
  "Step 0 (section 4.2) over a map request whose id is well formed: the
   first admission refusal, or nil. The request's shape first -- the
   closed key set, portability, a format, one of name or identity
   (`:invalid-request`) -- then the format record
   (`:unsupported-format`), then the contract, which is required
   (`:invalid-request`) and must be the record's (`:contract-mismatch`)
   -- so no validator ever runs under the wrong table. The evidence names
   keys, never the offending value: a refusal travels on a stream."
  [state request]
  (let [format-kw (:yin.link/format request)
        record (get (:formats state) format-kw)
        contract (:yin.link/contract request)]
    (cond
      (some (fn [k] (not (contains? request-keys k))) (keys request))
      (refused :invalid-request {:defect :closed-key-set})

      (some (fn [[k v]]
              (and (not= :yin.link/id k)
                   (not (portable-scalar? v))))
            request)
      (refused :invalid-request
               {:defect :non-portable,
                :key (some (fn [[k v]]
                             (when (and (not= :yin.link/id k)
                                        (not (portable-scalar? v)))
                               k))
                           request)})

      (nil? format-kw)
      (refused :invalid-request {:missing :format})

      (= (contains? request :yin.link/name)
         (contains? request :yin.link/identity))
      (refused :invalid-request {:defect :exactly-one-of})

      (nil? record)
      (refused :unsupported-format {:format format-kw})

      (nil? contract)
      (refused :invalid-request {:missing :contract})

      (not= contract (:contract record))
      (refused :contract-mismatch
               {:expected contract, :actual (:contract record)}))))


(defn- root-address
  "Step 1 (section 4.2): the storage address the linker-local index holds
   for the request's identity, or a refusal -- `:absent` for no entry or
   an entry that is no Jing address, and `:parts-limit` when a
   non-positive parts quota refuses the root before it is read. A
   request by module name resolves through the name environment of
   section 8, which is M4's; with none observed the name is `:absent`
   now."
  [state request]
  (let [identity (:yin.link/identity request)
        index (get (:indexes state) (:yin.link/format request))
        address (when (and index (contains? request :yin.link/identity))
                  (index identity))]
    (cond
      (contains? request :yin.link/name)
      (refused :absent {:name (:yin.link/name request)})

      (nil? address)
      (refused :absent {:identity identity})

      (not (jing/segment-address? address))
      (refused :absent {:address address})

      (not (pos? (:max-parts (:bounds state))))
      (refused :parts-limit {:bound :max-parts, :address address})

      :else address)))


(defn- completion
  "The completion of link `id` for `outcome` (section 6.3): the verified
   image under `:image` with its obligations beside it, or the refusal
   or loss tagged with the id."
  [id outcome]
  (if (ok? outcome)
    {:yin.link/id id,
     :status :ok,
     :image (dissoc outcome :status :obligations),
     :obligations (:obligations outcome)}
    (assoc outcome :yin.link/id id)))


(defn- finish
  "Retire link `id`'s bookkeeping and append its one completion."
  [state id outcome]
  (-> state
      (update :links dissoc id)
      (update :order (fn [order] (filterv (fn [x] (not= id x)) order)))
      (update :outbox conj (completion id outcome))))


(defn request-link
  "Admit one link request (section 6.3) and return `[state link-id]`.
   `request` is plain data: `:yin.link/id`, `:yin.link/format`,
   `:yin.link/contract`, and exactly one of `:yin.link/name` or
   `:yin.link/identity`. Admission (step 0) and the index lookup (step
   1) touch no stream, so a request they refuse completes at the next
   `step` under its id -- or under `:yin.link/id nil` when the id is not
   a well-formed `[origin counter]` pair or already names a link whose
   completion is owed. Nothing is sent here: `step` issues every content
   request."
  [state request]
  (let [raw-id (when (map? request) (:yin.link/id request))
        duplicate? (and (link-id? raw-id) (in-flight? state raw-id))
        id (when (and (link-id? raw-id) (not duplicate?)) raw-id)
        defect (cond
                 (not (map? request))
                 (refused :invalid-request {:defect :not-a-map})

                 duplicate?
                 (refused :invalid-request {:defect :duplicate-id})

                 (nil? id)
                 (refused :invalid-request {:defect :id})

                 :else (request-defect state request))
        address (when-not defect (root-address state request))]
    (cond
      defect [(update state :outbox conj (completion id defect)) id]
      (refused? address) [(update state :outbox conj (completion id address))
                          id]
      :else
      [(-> state
           (assoc-in [:links id]
                     {:format (:yin.link/format request),
                      :identity (:yin.link/identity request),
                      :address address,
                      :queue [[address 0]],
                      :at 0,
                      :parts {},
                      :bytes 0,
                      :enqueued #{address},
                      :awaiting nil})
           (update :order conj id))
       id])))


(defn- receive-part
  "Route the reply `text` answered for link `id`'s current worklist entry
   through step 2's checks; enqueue the part's children under the parts
   budget; and, when the worklist is empty, run steps 3 to 5a at once
   (section 6.3: they never touch a stream)."
  [state id text]
  (let [link (get-in state [:links id])
        {:keys [queue at parts bytes enqueued]} link
        record (get (:formats state) (:format link))
        {:keys [max-parts max-bytes]} (:bounds state)
        [address depth] (nth queue at)
        res (checked-part record (:identity link) address text
                          (when max-bytes (- max-bytes bytes)))]
    (if (refused? res)
      (finish state id res)
      (let [value (:value res)
            enqueued' (enqueue-children ((:parts-fn record) value)
                                        enqueued depth max-parts)]
        (if (refused? enqueued')
          (finish state id enqueued')
          (let [link' (assoc link
                             :queue (into queue (:queue enqueued'))
                             :at (inc at)
                             :parts (assoc parts address value)
                             :bytes (+ bytes (:bytes res))
                             :enqueued (into enqueued (:fresh enqueued'))
                             :awaiting nil)]
            (if (= (:at link') (count (:queue link')))
              (finish state id (verify record (:identity link)
                                       (:address link) (:parts link')))
              (assoc-in state [:links id] link'))))))))


(defn- route-completion
  "Route one raw content-pair completion to the link awaiting its id. A
   completion no link claims -- the late answer of an abandoned link --
   is dropped."
  [state raw]
  (let [rid (:dao.stream.rpc/id raw)
        id (get (:routes state) rid)]
    (if (and (some? id) (contains? (:links state) id))
      (receive-part (update state :routes dissoc rid) id (answered-text raw))
      (update state :routes dissoc rid))))


(defn- issue-request
  "Issue link `id`'s next content request, in worklist order, unless one
   is already awaited. The depth bound is checked before the read; a
   terminal client has no payload for the link (`:absent`, failing
   closed); a writer still owing an unsent envelope issues nothing this
   step -- `rpc` retains one envelope, retried by the next step."
  [state id]
  (let [link (get-in state [:links id])
        rpc-state (:rpc state)]
    (if (or (nil? link) (some? (:awaiting link)))
      state
      (let [[address depth] (nth (:queue link) (:at link))
            max-depth (:max-depth (:bounds state))]
        (cond
          (and max-depth (> depth max-depth))
          (finish state id
                  (refused :parts-limit {:bound :max-depth, :address address}))

          (:terminal rpc-state)
          (finish state id (refused :absent {:address address}))

          (rpc/unsent? rpc-state)
          state

          :else
          (let [r (rpc/request! rpc-state get-content-op [address])
                rid (:dao.stream.rpc/id r)
                state (assoc state :rpc (:dao.stream.rpc/state r))]
            (if (some? rid)
              (-> state
                  (assoc-in [:links id :awaiting] rid)
                  (assoc-in [:routes rid] id))
              (finish state id (refused :absent {:address address})))))))))


(defn step
  "One non-waiting advance of every in-flight link (section 6.3), in a
   fixed order: re-attempt the unsent content request; poll the content
   response medium at most `budget` elements; route each correlated
   response to its link and run the verification step it enables; issue
   the next content request each link's worklist needs; take the
   completions. Returns `{:state s :completions [...] :diagnostics
   [...]}`: each completion carries its `:yin.link/id` exactly once; a
   link with none yet is `:pending`, and the caller steps again when it
   chooses. Diagnostics are the rpc client's, taken exactly once.

   This is an interpreter step -- it performs stream operations -- under
   a single-owner precondition: one caller, one state thread."
  [state budget]
  (let [rpc0 (:rpc state)
        rpc1 (if (and (rpc/unsent? rpc0) (not (:terminal rpc0)))
               (:dao.stream.rpc/state (rpc/request! rpc0 nil nil))
               rpc0)
        rpc2 (:dao.stream.rpc/state (rpc/poll! rpc1 budget))
        rpc3 (if (and (:terminal rpc2) (rpc/unsent? rpc2))
               (rpc/abandon-unsent rpc2 (:terminal rpc2))
               rpc2)
        [raw rpc4] (rpc/take-completed rpc3)
        [diagnostics rpc5] (rpc/take-diagnostics rpc4)
        routed (reduce route-completion (assoc state :rpc rpc5) raw)
        issued (reduce issue-request routed (:order routed))]
    {:state (assoc issued :outbox []),
     :completions (:outbox issued),
     :diagnostics diagnostics}))


(defn abandon
  "Give up on link `link-id` with `reason` (section 6.3): its
   bookkeeping is retired and it completes `{:status :lost :reason
   reason}` at the next `step`, so a caller that gives up still receives
   exactly one completion. A content request it still owed the writer is
   abandoned with it; one already on the wire is answered into nothing.
   An id with no link in flight leaves the state unchanged."
  [state link-id reason]
  (if-let [link (get-in state [:links link-id])]
    (let [rid (:awaiting link)
          rpc-state (:rpc state)]
      (-> state
          (assoc :rpc (if (and (some? rid)
                               (= rid (get-in rpc-state [:unsent :id])))
                        (rpc/abandon-unsent rpc-state reason)
                        rpc-state))
          (update :routes dissoc rid)
          (finish link-id {:status :lost, :reason reason})))
    state))


;; =============================================================================
;; fetch: host policy over a link runtime (section 6.4)
;; =============================================================================

(def ^:private fetch-budget
  "How many response-medium elements one `fetch` advance may consume."
  32)


(defn- fetched
  "The host-policy outcome of one completion: step 5b against `receiver`
   over a verified image, flattened to the step-6 shape of section 4.2;
   a refusal or loss as it completed, without its link id."
  [c receiver]
  (if (= :ok (:status c))
    (or (discharge receiver (:obligations c))
        (merge {:status :ok}
               (:image c)
               {:obligations (:obligations c)}))
    (dissoc c :yin.link/id)))


(defn fetch
  "Fetches and verifies an image by its identity: host policy over an
   explicit link runtime (section 6.4), one surface for all four
   formats. Returns the verified image `{:status :ok ...}` or a
   qualified refusal.

   `runtime` is `{:state link-state :drive (fn [state] state')}`.
   `:drive` is the composition's driver: it advances whatever serves the
   content pair (`dao.stream.rpc/serve-once!` over a handle, or nothing
   when a remote server runs elsewhere) and returns the link state; after
   each drive `fetch` steps the linker, until the one link completes.
   `fetch` holds no DaoJing handle and cannot call `jing/get`: the
   content handle is reachable only from the server side of the pair.
   The runtime's state is consumed: one runtime serves one fetch, and a
   composition that links again builds a fresh runtime or holds the
   stepped interface itself. Liveness is the drive's: a drive that never
   lets the pair answer never completes, and it may `abandon` instead.
   `fetch` takes no deadline. A composition that wants one holds a
   `dao.lease` over the link in its drive, and the judge that lapses it
   on silence calls `abandon` (docs/design/yin.vm.linker.md section 6.3).

   `format` is a format keyword whose record the state holds; `identity`
   is H or R, a root row id, or a vector address. `receiver` is the
   receiving VM's free-name environment `{:free-env :store :primitives
   :modules}`; an empty receiver accepts only a closed image. `opts`
   carries the requester's contract `:contract`, which is required: the
   shorter arities supply none, so they are `:invalid-request`, and
   every caller names the contract it runs, normally the record's own.
   No contract stamp is ever assigned here.

   The steps run in strict order (section 4.2), with no format-specific
   branching:
     0. the request is admissible                    :invalid-request
        a record for the format                      :unsupported-format
        the requested contract equals the record's   :contract-mismatch
     1. address <- (index identity)                  :absent
     2. the bounded worklist of :parts-fn, each      :absent,
        part read over the pair, sized, verified,    :address-mismatch,
        then row-checked before any child it names   :descriptor-defect,
        is hashed, decoded, or enqueued              :parts-limit
     3. ((:identity-matches-fn format) identity root :hash-mismatch
     4. ((:validate-fn format) whole) is nil         :descriptor-defect
     5a. the scanners' occurrences join their        :use-before-definition
         definitions: an unconditional definition
         that dominates the occurrence discharges
         it; the rest are retained
     5b. every retained obligation binds in the      :unresolved-free,
         receiver                                    :shadowed-free
     6. the verified image

   For a multi-part format the outcome carries `:parts {address body}`."
  ([runtime format identity]
   (fetch runtime format identity {}))
  ([runtime format identity receiver]
   (fetch runtime format identity receiver nil))
  ([runtime format identity receiver opts]
   (let [{:keys [state drive]} runtime
         id [::fetch (:next-fetch state)]
         request (cond-> {:yin.link/id id,
                          :yin.link/format format,
                          :yin.link/identity identity}
                   (contains? opts :contract)
                   (assoc :yin.link/contract (:contract opts)))
         [state _] (request-link (update state :next-fetch inc) request)]
     (loop [state state]
       (let [r (step (drive state) fetch-budget)]
         (if-let [c (some (fn [c] (when (= id (:yin.link/id c)) c))
                          (:completions r))]
           (fetched c receiver)
           (recur (:state r))))))))


;; =============================================================================
;; Publishing (the mint-side counterpart the tests and compositions use)
;; =============================================================================

(defn publish!
  "Store `image` in `handle` and return its index datom
   `[identity attribute address]`. The address is the store's own
   `segment-key`; the identity is the format's own mint. The caller owns
   the index: nothing is recorded here. The two storage-derived formats
   mint through `yin.vm.content` instead (section 9)."
  [handle format image]
  (let [address (jing/materialize! handle image)]
    [((:identity-fn format) image) (address-attribute format) address]))


;; =============================================================================
;; Same-root pairing (section 7)
;; =============================================================================

(defn pairing-datoms
  "The mint-time pairing datoms recorded beside a named root."
  [root H R]
  [[root :yin.debruijn.code/hash H]
   [root :yin.debruijn.register/hash R]])


(defn root-pairing
  "`{:root root :H H :R R}` read from pairing datoms, or nil when either
   half is missing."
  [root datoms]
  (let [value-of (fn [attr]
                   (some (fn [[e a v]] (when (and (= root e) (= attr a)) v))
                         datoms))
        H (value-of :yin.debruijn.code/hash)
        R (value-of :yin.debruijn.register/hash)]
    (when (and H R) {:root root, :H H, :R R})))


(defn- relowered-hashes
  [source-datoms]
  (try
    (let [resolved (resolve/resolve source-datoms)]
      {:H (debruijn-code/image-hash
            (:image (linearize/lower-stack resolved))),
       :R (debruijn-register-code/register-hash
            (:image (register-compile/lower-register resolved)))})
    (catch #?(:cljd Object :clj Throwable :cljs :default) _
      {:H nil, :R nil})))


(defn verify-same-root-pairing
  "Re-lowers the named `source-datoms` locally (`resolve`, then
   `lower-stack` for H and `lower-register` for R) and confirms both
   recomputed identities equal the claimed pair. Returns `{:status :ok
   :root root :H H :R R}` or a `:pairing-mismatch` refusal carrying the
   expected and actual pairs."
  [root H R source-datoms]
  (let [actual (relowered-hashes source-datoms)]
    (if (and (= H (:H actual)) (= R (:R actual)))
      {:status :ok, :root root, :H H, :R R}
      (refused :pairing-mismatch
               {:root root, :expected {:H H, :R R}, :actual actual}))))


(defn- fallback-fetch
  [runtime pairing receiver trust]
  (let [res (fetch runtime (:format stack-format) (:H pairing) receiver
                   {:contract (:contract stack-format)})]
    (if (ok? res)
      (assoc res
             :trust trust
             :fallback {:root (:root pairing), :from (:R pairing)})
      res)))


(defn trusted-fallback
  "R -> H fallback on composition trust (section 7, path 1): reads the
   root's pairing and fetches its stack image by H through `runtime`,
   whose state holds `stack-format` and its H index. The outcome names
   its trust as `:trust :composition`. A root without a recorded pairing
   is `:absent`."
  [runtime root pairing-datoms receiver]
  (if-let [pairing (root-pairing root pairing-datoms)]
    (fallback-fetch runtime pairing receiver :composition)
    (refused :absent {:root root})))


(defn verifying-fallback
  "R -> H fallback that verifies the pairing first (section 7, path 2):
   re-lowers the root's named `source-datoms` and refuses with
   `:pairing-mismatch` unless both recomputed identities match, then
   fetches the stack image by H through `runtime`. The outcome names its
   trust as `:trust :verified`."
  [runtime root pairing-datoms source-datoms receiver]
  (if-let [{:keys [H R], :as pairing} (root-pairing root pairing-datoms)]
    (let [verdict (verify-same-root-pairing root H R source-datoms)]
      (if (refused? verdict)
        verdict
        (fallback-fetch runtime pairing receiver :verified)))
    (refused :absent {:root root})))
