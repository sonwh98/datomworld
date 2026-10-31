(ns probe.discovery
  "Prototype of discovery-order cursor-cell numbering, the BASE design of the
   note of 2026-10-12 (round 4), over the 6a abstract task representation
   (yin.vm.ucf.rows). Not repo code; a scratch project under collab/. The
   round-3 variants (components, residue search) live in probe.discovery-r3.

   Vocabulary: UCF 7.5.3. A cursor cell is the VM box the carrier serializes;
   its source id is the lift's local name; the procedure assigns :yin.k/cc-n.

   The rule. A cursor cell gets the next number the first time the walk meets
   it. The walk is pre-order over the task in table order. A guest :set or
   :map is turned into a vector of its members (a map entry as [:entry k v])
   sorted by KEY: the spelling of a local walk over the member under the
   current numbering, with numbered cells spelled by number and unnumbered
   ones by local first-occurrence index, body rank and occurrence count.
   Members whose keys tie are POSTPONED to the run's queue and re-keyed after
   the rest of the walk, until a pass numbers nothing. A remaining tie group
   is entered in listed order when the TIE TEST holds (common key complete;
   the members' unnumbered cells pairwise disjoint and occurring in no member
   of another remaining group); otherwise the root walk refuses
   :yin.k/non-portable :unresolved-cursor-cells and a key walk spells the
   group as [:tie K ... K] in its key.

   Budgets: :cursor-cells (the census's pair count, before anything is
   built), :depth (enforced by one bounded iterative scan of the task before
   any recursive traversal, and again in the walk), :work (one unit per
   position scanned, visited, keyed or spelled, plus 6a's representation
   bound of every value before it is CBOR-encoded for a comparison, computed
   iteratively against the remaining budget). Exceeding one is
   :yin.k/unsupported :resource-limit, never a canonicality verdict.

   Diagnostic switches (*numbering-opts*): :permissive enters a failing group
   in listed order instead of refusing (used to test interchangeability by
   exhaustion or sampling); :count-spelling false drops the occurrence count
   from the key (the owner's literal direction, for measurement)."
  (:require [clojure.set :as set]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [yin.vm.ucf.rows :as rows]
            [yin.vm.ucf.rows-test :as rt]
            [yin.vm.semantic-register.frame-test :as ft]))


;; ---------------------------------------------------------------- schema

(def synthetic-table
  "The one row that exists only inside the procedure: a :map entry as an
   ordered pair, so the alias pattern across key and value is in its key."
  {:entry [:V :V]})


(defn descriptors
  [kind slots]
  (let [schema (or (get rows/row-table kind) (get synthetic-table kind))]
    (cond (= schema :pending) (into [:D] (get rows/pending-table (first slots)))
          (= :repeat (first schema)) (vec (repeat (count slots) (second schema)))
          (= :pairs (first schema)) (vec (take (count slots) (cycle (rest schema))))
          :else schema)))


(defn ref?
  [d]
  (and (vector? d) (= :ref (first d))))


(defn refs?
  [d]
  (and (vector? d) (= :refs (first d))))


(defn v?
  [d]
  (contains? #{:V :optional-V} d))


(defn bytes-key
  "Uncharged encoding; generators and diagnostics only."
  [x]
  (mapv #(bit-and (int %) 255) (seq (cbor/encode x))))


(defn compare-bytes
  [a b]
  (loop [i 0]
    (cond (= i (count a)) (if (= i (count b)) 0 -1)
          (= i (count b)) 1
          (= (nth a i) (nth b i)) (recur (inc i))
          :else (compare (nth a i) (nth b i)))))


(def keyed-structural #{:bindings :store :parked-table})


(defn elem-item
  "The item a member is keyed and entered as: a :set member is its V; a :map
   entry [k v] is the synthetic ordered row [:entry k v]."
  [kind e]
  (if (= :map kind) [:row {:kind :entry :slots (vec e)}] e))


(defn child-task?
  [ctx n]
  (and (= :task (:kind n)) (not (identical? n (:task ctx)))))


;; --------------------------------------------------------------- budgets

(def default-budget
  "6a's budget without :label-bytes and :search; :work now also counts
   representation bytes, so its default takes over :label-bytes' scale."
  (assoc (dissoc rows/default-budget :label-bytes :search) :work 4194304))


(defn- limit!
  [ctx category]
  (throw (ex-info "resource limit"
                  {:yin.k/status :yin.k/unsupported :yin.k/kind :resource-limit :yin.k/path [category]
                   ;; probe-only diagnostic (counters at the refusal), not part of the contract
                   :probe/used @(:counter ctx)})))


(defn- spend!
  [ctx category amount]
  (let [n (get (swap! (:counter ctx) update category (fnil + 0) amount) category)]
    (when (> n (get (:budget ctx) category)) (limit! ctx category))))


(defn- charge-rep-bound!
  "6a's conservative representation bound of x (check-label-bytes!'s `size`:
   128 per node or scalar, 4 per character, 16 plus the length per byte
   payload, metadata included), charged to :work. Computed with an explicit
   stack, no recursion, against the budget REMAINING at the call: the running
   total is checked after every node, so the estimator stops after at most
   remaining/16 steps on any input and never finishes reading a value it
   cannot afford. The bound is an abstract charge: an upper bound of the
   encoding's bytes and of the encoder's intermediate nodes, not a measured
   host-heap ceiling (the comparison vector and the encoder's intermediates
   use more heap than the payload length). Reading x allocates the lazy
   element sequences and `str` of names; nothing proportional to the
   encoding."
  [ctx x]
  (let [remaining (- (get (:budget ctx) :work) (get @(:counter ctx) :work 0))]
    (loop [stack (list (list x)) total 0 steps 0]
      (if (empty? stack)
        (do (swap! (:counter ctx) update :estimator-steps (fnil + 0) steps)
            (spend! ctx :work total))
        (let [s (first stack) stack (rest stack)]
          (if (empty? s)
            (recur stack total steps)
            (let [x (first s)
                  stack (cons (rest s) stack)
                  n (cond (cbor/byte-payload? x) (+ 16 (alength ^bytes x))
                          (string? x) (+ 128 (* 4 (count x)))
                          (or (keyword? x) (symbol? x)) (+ 128 (* 4 (count (str x))))
                          :else 128)
                  total (+ total n)]
              (when (> total remaining)
                (swap! (:counter ctx) update :estimator-steps (fnil + 0) (inc steps))
                (limit! ctx :work))
              (recur (cond->> stack
                       (coll? x) (cons (if (map? x) (mapcat identity x) (seq x)))
                       (meta x) (cons (list (meta x))))
                     total (inc steps)))))))))


(defn- key-bytes!
  "Charge the representation bound of x to :work, then encode x for comparison.
   Every encoding the procedure makes goes through here."
  [ctx x]
  (charge-rep-bound! ctx x)
  (let [bs (bytes-key x)]
    (swap! (:counter ctx) update :encoded-bytes (fnil + 0) (count bs))
    bs))


(defn- depth-scan!
  "The :depth gate, enforced BEFORE any recursive traversal of the task: one
   bounded pre-order scan with an explicit stack (no recursion) over rows,
   members, structural and literal data and the census; install children are
   skipped (each is gated by its own procedure). The depth of a position is
   the walk's measure, its slot steps from the task (a member step counts
   one, a map entry's key and value two), plus, inside data, its collection
   nesting (metadata counts one level). A position deeper than :depth refuses
   [:depth]; every position costs one :work unit. After the scan every
   recursive read of the task (cell scans, memo-key hashing, spelling,
   encoding, 6a's emitter) nests at most :depth levels."
  [ctx task]
  (let [limit (get (:budget ctx) :depth)
        node (fn [n k] [:node nil n k])
        slot (fn [d v k] [:slot d v k])
        data (fn [v k] [:data nil v k])]
    ;; The stack holds lazy sequences of entries, so pushing a container's
    ;; elements is O(1): every position is charged when it is popped, before
    ;; any work proportional to it is done.
    (loop [stack (list (list (node task 0)))]
      (when (seq stack)
        (let [s (first stack) stack (rest stack)]
          (if (empty? s)
            (recur stack)
            (let [[tag d x k] (first s) stack (cons (rest s) stack)]
              (spend! ctx :work 1)
              (when (> k limit) (limit! ctx :depth))
              (recur
                (cons
                  (case tag
                    :node
                    (let [{:keys [kind slots]} x k' (inc k)]
                      (if (child-task? ctx x)
                        nil
                        (let [ds (descriptors kind slots)]
                          (cond
                            (= :task kind)
                            (mapcat (fn [i d v]
                                      (if (contains? #{7 12} i)
                                        (map (fn [child] (node child (inc k'))) v)
                                        [(slot d v k')]))
                                    (range) ds slots)
                            (contains? keyed-structural kind)
                            (mapcat (fn [[key v]] [(data key k') (slot (second ds) v k')]) (partition 2 slots))
                            (= :window kind)
                            (mapcat (fn [[r v]] [(data r k') (slot :V v k')]) (partition 2 slots))
                            (= :set kind) (map (fn [v] (slot :V v k')) slots)
                            (= :map kind)
                            (mapcat (fn [[a b]] [(slot :V a (inc k')) (slot :V b (inc k'))]) (partition 2 slots))
                            :else (map (fn [d v] (slot d v k')) ds slots)))))
                    :slot
                    (cond (ref? d) [(node x k)]
                          (refs? d) (map (fn [n] (node n (inc k))) x)
                          (= :C d) nil
                          (v? d) (when x
                                   (case (first x)
                                     :cursor-cell nil
                                     :row [(node (second x) k)]
                                     :lit [(data (second x) k)]
                                     [(data x k)]))
                          :else [(data x k)])
                    :data
                    (concat (when (coll? x) (map (fn [e] (data e (inc k))) (if (map? x) (mapcat identity x) x)))
                            (when (meta x) [(data (meta x) (inc k))])))
                  stack)))))))))


(defn- sort-by-key!
  "xs in the bytewise order of (f x), each encoded once (stable)."
  [ctx f xs]
  (mapv second (sort #(compare-bytes (first %1) (first %2)) (mapv (fn [x] [(key-bytes! ctx (f x)) x]) xs))))


;; ------------------------------------------------------------ cell scans

(defn- walk-cells
  "Call f on every cursor cell at a position under slot (d x); census and
   install children excluded; one :work unit per position."
  [ctx d x f]
  (letfn [(slot
            [d x]
            (spend! ctx :work 1)
            (cond (ref? d) (node x)
                  (refs? d) (run! node x)
                  (= :C d) (f x)
                  (v? d) (when x (case (first x) :cursor-cell (f (second x)) :row (node (second x)) nil))
                  :else nil))
          (node
            [n]
            (when-not (or (= :cursor-cells (:kind n)) (child-task? ctx n))
              (dorun (map slot (descriptors (:kind n) (:slots n)) (:slots n)))))]
    (slot d x)))


(defn item-cells
  "The cursor cells under slot (d x), memoized per procedure on the slot value."
  [ctx d x]
  (or (get @(:cells-memo ctx) [d x])
      (let [acc (atom #{})]
        (walk-cells ctx d x #(swap! acc conj %))
        (swap! (:cells-memo ctx) assoc [d x] @acc)
        @acc)))


(defn- occurrence-counts
  "Cursor cell -> number of positions of the task holding it."
  [ctx task]
  (let [acc (atom {})]
    (walk-cells ctx [:ref :task] task #(swap! acc update % (fnil inc 0)))
    @acc))


(defn body-label
  [body]
  (let [[stream position] (:slots body)]
    [:body (into [:stream] (:slots stream)) position]))


(defn- body-ranks
  "Cursor cell -> rank of its body label among the census's distinct body
   labels in bytewise order (rank order = label order, so keys stay
   independent of the descriptor's size)."
  [ctx bodies]
  (let [labels (into {} (map (fn [[c b]] [c (body-label b)])) bodies)
        ranks (into {} (map-indexed (fn [i l] [l i])) (sort-by-key! ctx identity (distinct (vals labels))))]
    (into {} (map (fn [[c l]] [c (get ranks l)])) labels)))


;; ------------------------------------------------------------------ runs

(def ^:dynamic *trace* false)


(defn- new-run
  [ctx num tag next]
  {:ctx ctx :num (atom num) :tag tag :next (atom next) :groups (atom []) :gid (atom 0) :refused (atom nil)})


(defn- meet!
  "W2. A cursor cell met for the first time receives the run's next number."
  [run c]
  (when-not (contains? @(:num run) c)
    (let [k @(:next run)]
      (swap! (:num run) assoc c [(:tag run) k])
      (swap! (:next run) inc)
      true)))


(declare enter-node! unordered! local-run spell-slot spell-node)


(defn- enter-slot!
  [run d x path]
  (let [ctx (:ctx run)]
    (spend! ctx :work 1)
    (when (> (count path) (get (:budget ctx) :depth)) (limit! ctx :depth))
    (cond (ref? d) (enter-node! run x path)
          (refs? d) (dorun (map-indexed (fn [i n] (enter-node! run n (conj path i))) x))
          (= :C d) (meet! run x)
          (v? d) (when x (case (first x)
                           :cursor-cell (meet! run (second x))
                           :row (enter-node! run (second x) path)
                           nil))
          :else nil)))


(defn- enter-node!
  "W1. Pre-order: table order for slots; ordered repeated slots in order;
   keyed structural rows in the byte order of the key; :window in register
   order; installs and module stores by name; :set and :map by keys with
   ties postponed; census and children not entered."
  [run n path]
  (let [{:keys [kind slots]} n ctx (:ctx run) ds (descriptors kind slots)]
    (cond
      (= :cursor-cells kind) nil
      (child-task? ctx n) nil
      (= :task kind)
      (doseq [[i d x] (map vector (range) ds slots)]
        (if (contains? #{7 12} i)
          (doseq [child (sort-by-key! ctx #(first (:slots %)) x)]
            (enter-node! run child (conj path i (first (:slots child)))))
          (enter-slot! run d x (conj path i))))
      (contains? keyed-structural kind)
      (doseq [[k v] (sort-by-key! ctx first (partition 2 slots))]
        (enter-slot! run (second ds) v (conj path k)))
      (= :window kind)
      (doseq [[r v] (sort-by first (partition 2 slots))] (enter-slot! run :V v (conj path r)))
      (= :set kind) (unordered! run n :set (vec slots) path)
      (= :map kind) (unordered! run n :map (mapv vec (partition 2 slots)) path)
      :else (dorun (map-indexed (fn [i [d x]] (enter-slot! run d x (conj path i))) (map vector ds slots))))))


(defn- unordered!
  "The transformation: key every member by a local walk under the numbering
   at this moment; members with a unique key are entered in key order; a run
   of equal keys is one tie group, postponed to the run's queue (postponement
   order: containers in walk order, groups within a container in key order).
   A member's path is the container's path plus :member (there is no
   canonical index into an unordered container); a group remembers its
   container by identity."
  [run container kind elems path]
  (let [keyed (mapv (fn [e]
                      ;; one unit per member keyed, so the number of key walks
                      ;; (and of memo lookups, each a read of charged data) is charged
                      (spend! (:ctx run) :work 1)
                      [(local-run run (elem-item kind e) (conj path :member)) e]) elems)
        groups (partition-by (comp :bytes first) (sort #(compare-bytes (:bytes (first %1)) (:bytes (first %2))) keyed))]
    (when (and *trace* (= :n (:tag run)))
      (println "  [sort]" path "num" @(:num run))
      (doseq [g groups] (println "    " (if (= 1 (count g)) "enter" "POSTPONE") (mapv (fn [[r e]] [e (:spell r)]) g))))
    (doseq [g groups]
      (if (= 1 (count g))
        (enter-slot! run :V (elem-item kind (second (first g))) (conj path :member))
        (swap! (:groups run) conj {:id (swap! (:gid run) inc) :container container :kind kind :path path
                                   :key (:spell (first (first g))) :complete? (:complete? (first (first g)))
                                   :elems (mapv second g)})))))


(defn- fixpoint!
  "W3. Re-examine every postponed group in order under the current numbering;
   repeat while a pass numbers a cursor cell and groups remain."
  [run]
  (loop []
    (let [before (count @(:num run)) groups @(:groups run)]
      (reset! (:groups run) [])
      (doseq [{:keys [container kind elems path]} groups] (unordered! run container kind elems path))
      (when (and (seq @(:groups run)) (> (count @(:num run)) before)) (recur)))))


(defn- unnumbered-cells
  [run kind e]
  (let [num @(:num run)]
    (set (remove #(contains? num %) (item-cells (:ctx run) :V (elem-item kind e))))))


(defn- group-failure
  "T, the tie test. nil when the group's members are interchangeable: the
   common key is complete, and the members' unnumbered cursor cells are
   pairwise disjoint and occur in no member of another remaining group.
   Otherwise the reason."
  [run groups g]
  (let [own (mapv #(unnumbered-cells run (:kind g) %) (:elems g))
        others (reduce into #{} (for [h groups :when (not= (:id h) (:id g)) e (:elems h)]
                                  (unnumbered-cells run (:kind h) e)))]
    (cond (not (:complete? g)) :incomplete-key
          (not= (reduce + (map count own)) (count (reduce into #{} own))) :shared-within-group
          (some #(seq (set/intersection % others)) own) :shared-across-groups
          :else nil)))


(defn- resolve!
  "Postponed groups to completion: fixpoint, then the tie test on every
   remaining group; all pass: enter them in listed order and repeat; any
   fails: the root run refuses, a key run leaves them as residue."
  [run]
  (loop []
    (fixpoint! run)
    (let [groups @(:groups run) ctx (:ctx run)]
      (when (seq groups)
        (let [failing (vec (keep (fn [g] (when-let [why (group-failure run groups g)] (assoc g :why why))) groups))]
          (when (and *trace* (= :n (:tag run)))
            (println "  [ties]" (mapv (fn [g] [(:id g) (:path g) (:elems g) (:why g)]) (map #(assoc % :why (group-failure run groups %)) groups))))
          (cond
            (empty? failing)
            ;; Interchangeable groups are independent of each other (their
            ;; cells are confined), so all are entered before the next pass.
            (do (reset! (:groups run) [])
                (doseq [g groups e (:elems g)] (enter-slot! run :V (elem-item (:kind g) e) (conj (:path g) :member)))
                (recur))
            (:permissive ctx)
            ;; Diagnostic only: enter the first group in listed order, then
            ;; re-key the rest (what an unsound "equal keys, host order" rule does).
            (do (reset! (:groups run) (vec (rest groups)))
                (doseq [e (:elems (first groups))] (enter-slot! run :V (elem-item (:kind (first groups)) e) (conj (:path (first groups)) :member)))
                (recur))
            (= :n (:tag run))
            (reset! (:refused run)
                    {:kind :unresolved-cursor-cells
                     :path (:path (first failing))
                     :why (mapv :why failing)
                     :cursor-cells (vec (sort (distinct (mapcat (fn [g] (mapcat #(unnumbered-cells run (:kind g) %) (:elems g))) failing))))})
            :else nil))))))


(defn- local-run
  "A key walk: the same procedure on one member from the enclosing run's
   numbering, tag :u; its result is the member's key (spelling under its
   final numbering, residue spelled [:tie K ...]) and whether it is complete.
   Memoized on (item, numbering restricted to the item's cells, counter)."
  [run item path]
  (let [ctx (:ctx run)
        cells (item-cells ctx :V item)
        num @(:num run)
        start (if (= :u (:tag run)) @(:next run) 0)
        mk [item (select-keys num cells) start]]
    (or (get @(:memo ctx) mk)
        (let [sub (new-run ctx num :u start)]
          (spend! ctx :work (count cells))
          (enter-slot! sub :V item path)
          (resolve! sub)
          (let [spell (spell-slot sub :V item path)
                r {:spell spell :bytes (key-bytes! ctx spell) :complete? (empty? @(:groups sub))}]
            (swap! (:counter ctx) update :local-runs (fnil + 0) 1)
            (swap! (:memo ctx) assoc mk r)
            r)))))


;; -------------------------------------------------------------- spelling

(defn- spell-cell
  [run c]
  (let [[t k] (get @(:num run) c) ctx (:ctx run)]
    (case t
      :n [:cursor-cell [:n k]]
      :u [:cursor-cell (if (:count-spelling ctx)
                         [:u k (get (:ranks ctx) c) (get (:counts ctx) c)]
                         [:u k (get (:ranks ctx) c)])]
      (throw (ex-info "spelling an unnumbered cursor cell" {:cursor-cell c})))))


(defn- spell-slot
  [run d x path]
  (spend! (:ctx run) :work 1)
  (cond (ref? d) (spell-node run x path)
        (refs? d) (vec (map-indexed (fn [i n] (spell-node run n (conj path i))) x))
        (= :C d) (spell-cell run x)
        (v? d) (if (nil? x) x
                   (case (first x)
                     :cursor-cell (spell-cell run (second x))
                     :row [:row (spell-node run (second x) path)]
                     x))
        :else x))


(defn- spell-node
  "The spelling of a row under the run's final numbering: literals and
   structural data as themselves (by reference), rows expanded in place,
   keyed rows in key order, a :set or :map as the sorted vector of its
   members' spellings with each surviving tie group as [:tie K ...]."
  [run n path]
  (let [{:keys [kind slots]} n ctx (:ctx run) ds (descriptors kind slots)]
    (cond
      (= :cursor-cells kind) [:cursor-cells]
      (child-task? ctx n) [:child]
      (= :task kind)
      (into [kind]
            (map-indexed (fn [i [d x]]
                           (if (contains? #{7 12} i)
                             (sort-by-key! ctx #(nth % 1) (mapv #(spell-node run % (conj path i (first (:slots %)))) x))
                             (spell-slot run d x (conj path i))))
                         (map vector ds slots)))
      (contains? keyed-structural kind)
      (into [kind] (mapcat identity (sort-by-key! ctx first
                                                  (mapv (fn [[k v]] [k (spell-slot run (second ds) v (conj path k))])
                                                        (partition 2 slots)))))
      (= :window kind)
      (into [kind] (mapcat (fn [[r v]] [r (spell-slot run :V v (conj path r))]) (sort-by first (partition 2 slots))))
      (contains? #{:set :map} kind)
      (let [elems (if (= :set kind) (vec slots) (mapv vec (partition 2 slots)))
            tied (filter #(identical? n (:container %)) @(:groups run))
            tied-elems (set (mapcat :elems tied))
            plain (keep (fn [e] (when-not (tied-elems e) (spell-slot run :V (elem-item kind e) (conj path :member)))) elems)
            ties (map #(into [:tie] (repeat (count (:elems %)) (:key %))) tied)]
        (into [kind] (sort-by-key! ctx identity (concat plain ties))))
      :else (into [kind] (map-indexed (fn [i [d x]] (spell-slot run d x (conj path i))) (map vector ds slots))))))


;; ------------------------------------------------------------- procedure

(def ^:dynamic *numbering-opts*
  "Options for every run of the procedure in scope (root and install
   children): :permissive, :count-spelling, :budget."
  {})


(defn discovery-numbering
  "Returns {:numbering {src -> :yin.k/cc-n} :used {...}} or {:refused {...}}.
   Throws :resource-limit when a budget is exceeded, and a defect (never a
   refusal) when the numbering is not total."
  ([task] (discovery-numbering task *numbering-opts*))
  ([task opts]
   (let [census (nth (:slots task) 9)
         ctx {:task task
              :memo (atom {}) :cells-memo (atom {})
              :budget (merge default-budget (:budget opts))
              :counter (atom {})
              :permissive (:permissive opts)
              :count-spelling (not= false (:count-spelling opts))}
         ;; gates first: the census's pair count before the bodies map is
         ;; built, the depth scan before any recursive traversal
         _ (spend! ctx :cursor-cells (quot (count (:slots census)) 2))
         _ (depth-scan! ctx task)
         bodies (into {} (map vec) (partition 2 (:slots census)))
         ctx (assoc ctx :bodies bodies)
         ctx (assoc ctx :ranks (body-ranks ctx bodies) :counts (occurrence-counts ctx task))
         run (new-run ctx {} :n 1)]
     (enter-node! run task [])
     (resolve! run)
     (if-let [r @(:refused run)]
       {:refused r :used @(:counter ctx)}
       (let [numbered (set (keys @(:num run))) declared (set (keys bodies))]
         (when (seq @(:groups run)) (throw (ex-info "residue after resolve" {})))
         (when (not= numbered declared)
           (throw (ex-info "numbering is not total" {:missing (vec (sort (remove numbered declared)))
                                                     :extra (vec (sort (remove declared numbered)))})))
         {:numbering (into {} (map (fn [[c [_ k]]] [c (keyword "yin.k" (str "cc-" k))])) @(:num run))
          :used @(:counter ctx)})))))


;; --------------------------------------------------------------- emission
;; 6a's emit-node with install children numbered by discovery order and a
;; row path threaded for the child refusal.

(def ^:private ordered-slots @#'rows/ordered-slots)
(def ^:private check-literal-markers! @#'rows/check-literal-markers!)
(def ^:private byte-compare @#'rows/byte-compare)

(declare emit-node*)


(defn- refuse-unresolved!
  [r path]
  (throw (ex-info "unresolved cursor cells"
                  {:yin.k/status :yin.k/non-portable :yin.k/kind :unresolved-cursor-cells
                   :yin.k/path (into path (:path r)) :yin.k/cursor-cells (:cursor-cells r)
                   ;; probe-only diagnostic, not part of the refusal's contract
                   :probe/why (:why r)})))


(defn- emit-slot*
  [d x numbering rows path]
  (when-not (or (ref? d) (refs? d) (= :C d))
    (check-literal-markers! (if (v? d) (when (= :lit (first x)) (second x)) x)))
  (cond
    (ref? d) (emit-node* x numbering rows path)
    (refs? d) (reduce (fn [[out rs] [j n]]
                        (let [p (conj path (if (= :install (:kind n)) (first (:slots n)) j))
                              [id rs'] (emit-node* n numbering rs p)]
                          [(conj out id) rs']))
                      [[] rows] (map-indexed vector x))
    (= d :C) [(get numbering x x) rows]
    (v? d) (case (first x)
             :row (let [[id rs] (emit-node* (second x) numbering rows path)] [[:row id] rs])
             :cursor-cell [[:cursor-cell (get numbering (second x) (second x))] rows]
             [x rows])
    :else [x rows]))


(defn- emit-node*
  [n numbering rows path]
  (let [{:keys [kind slots]} n
        [slots rows] (reduce (fn [[out rs] [i d x]]
                               (if (and (ref? d) (= :task (:kind x)))
                                 (let [r (discovery-numbering x)
                                       _ (when (:refused r) (refuse-unresolved! (:refused r) path))
                                       [id child-rows] (emit-node* x (:numbering r) {} [])]
                                   [(conj out id) (merge rs child-rows)])
                                 (let [[x' rs'] (emit-slot* d x numbering rs (conj path i))] [(conj out x') rs'])))
                             [[] rows] (map vector (range) (descriptors kind slots) slots))
        slots (if (= kind :task)
                (-> slots
                    (update 7 #(vec (sort (fn [a b] (byte-compare (nth (get rows a) 2) (nth (get rows b) 2))) %)))
                    (update 12 #(vec (sort (fn [a b] (byte-compare (nth (get rows a) 2) (nth (get rows b) 2))) %))))
                (ordered-slots kind slots))
        body (into [kind] slots) id (jing/segment-key body)]
    [id (assoc rows id (into [id] body))]))


(defn emit*
  "Lift side: number by discovery order, emit; refuses :yin.k/non-portable
   :unresolved-cursor-cells for the root or for any install child (path
   [12 <name> ...]); a budget refusal propagates as itself."
  [task]
  (let [r (discovery-numbering task)]
    (when (:refused r) (refuse-unresolved! (:refused r) []))
    (let [[id rows] (emit-node* task (:numbering r) {} [])]
      {:yin.k/handoff true :yin.k/version 3 :yin.k/root id :yin.k/rows (vec (vals rows))})))


(defn decode*
  "Receiver side, in the production order (rows.cljc:570-578): 6a's decode*
   steps 1-5 with its own numbering check off and its frame validation
   suppressed (it still runs 6a's recompute under 6a's budget: a probe
   limitation), then the whole discovery recomputation (root and install
   children) under one translation: only a lift-side :yin.k/non-portable of
   kind :unresolved-cursor-cells becomes :yin.k/undecodable
   :unresolved-cursor-cells with the path prefixed by the carried root id;
   anything else (a resource refusal) propagates as itself; then the root
   comparison; then 6a's validate-frames, exactly as rows.cljc:576-578. So a
   numbering defect is reported before a frame defect, as in production."
  [wire]
  (let [task (with-redefs [rows/validate-frames (constantly nil)]
               (@#'rows/decode* wire (assoc rows/default-budget :check-numbering false)))
        root (:yin.k/root wire)
        id (try (:yin.k/root (emit* task))
                (catch clojure.lang.ExceptionInfo e
                  (let [d (ex-data e)]
                    (if (and (= :yin.k/non-portable (:yin.k/status d)) (= :unresolved-cursor-cells (:yin.k/kind d)))
                      (throw (ex-info "unresolved cursor cells"
                                      (assoc d :yin.k/status :yin.k/undecodable :yin.k/path (into [root] (:yin.k/path d)))))
                      (throw e)))))]
    (when (not= id root)
      (throw (ex-info "noncanonical" {:yin.k/status :yin.k/undecodable :yin.k/kind :noncanonical-cursor-cells
                                      :yin.k/path [root]})))
    (when (= :task (:kind task))
      (when-let [d (rows/validate-frames task)]
        (throw (ex-info "Frame validation failed" (update d :yin.k/path #(into [root] %))))))
    task))


(defn error
  [f]
  (try (f) nil (catch Throwable e (or (ex-data e) {:throwable (str e)}))))


(defn error-brief
  "`error` without the probe's counter diagnostic."
  [f]
  (some-> (error f) (dissoc :probe/used)))


(defn used-at
  "Work charged and estimator steps taken at a resource refusal (from the
   probe diagnostic): estimator steps 0 means the depth scan refused first."
  [f]
  (let [u (:probe/used (error f))] {:work (:work u) :estimator-steps (:estimator-steps u 0)}))


(defn do-root
  "Root id, {:refused ...} for the tie refusal, or {:resource-limit path}."
  [task]
  (try (:yin.k/root (emit* task))
       (catch clojure.lang.ExceptionInfo e
         (let [d (ex-data e)]
           (case (:yin.k/kind d)
             :unresolved-cursor-cells {:refused d}
             :resource-limit {:resource-limit (:yin.k/path d)}
             (throw e))))))


(defn safe-6a-root
  [task]
  (try (:yin.k/root (rows/emit task))
       (catch Throwable e {:refused (select-keys (ex-data e) [:yin.k/kind :yin.k/path])})))


;; ------------------------------------------------------------ generators

(defn rename-and-shuffle
  "Rename every cursor cell by `renaming` and shuffle every :set, :map and
   the census in listed order (shuffle is rebound by callers)."
  [n renaming]
  (letfn [(value
            [v]
            (case (first v)
              :cursor-cell [:cursor-cell (bij (second v))]
              :row [:row (go (second v))]
              v))
          (bij [x] (get renaming x x))
          (go
            [n]
            (let [{:keys [kind slots]} n]
              (case kind
                :set (rows/node :set (vec (shuffle (mapv value slots))))
                :map (rows/node :map (vec (mapcat identity (shuffle (mapv (fn [[k v]] [(value k) (value v)]) (partition 2 slots))))))
                :cursor-cells (rows/node :cursor-cells (vec (mapcat (fn [[c b]] [(bij c) (go b)]) (shuffle (mapv vec (partition 2 slots))))))
                (rows/node kind (mapv (fn [d x]
                                        (cond (ref? d) (go x)
                                              (refs? d) (mapv go x)
                                              (= :C d) (bij x)
                                              (v? d) (if x (value x) x)
                                              :else x))
                                      (descriptors kind slots) slots)))))]
    (go n)))


(defn reorder
  "The task with f applied to the listed members of every :set and :map, in
   walk order (f gets the container's ordinal and its members, a map's as
   [k v] pairs); install children included; census untouched."
  [task f]
  (let [i (atom -1)]
    (letfn [(value [v] (case (first v) :row [:row (go (second v))] v))
            (go
              [n]
              (let [{:keys [kind slots]} n]
                (case kind
                  :set (let [k (swap! i inc) ms (mapv value slots)] (rows/node :set (vec (f k ms))))
                  :map (let [k (swap! i inc) ps (mapv (fn [[a b]] [(value a) (value b)]) (partition 2 slots))]
                         (rows/node :map (vec (mapcat identity (f k ps)))))
                  (rows/node kind (mapv (fn [d x]
                                          (cond (ref? d) (go x)
                                                (refs? d) (mapv go x)
                                                (v? d) (if x (value x) x)
                                                :else x))
                                        (descriptors kind slots) slots)))))]
      (go task))))


(defn all-census-cells
  "Source ids of every census in the tree (root and install children)."
  [task]
  (let [acc (atom [])]
    (letfn [(node
              [n]
              (when (map? n)
                (when (= :cursor-cells (:kind n)) (swap! acc into (take-nth 2 (:slots n))))
                (doseq [[d x] (map vector (descriptors (:kind n) (:slots n)) (:slots n))]
                  (cond (ref? d) (node x) (refs? d) (run! node x)
                        (v? d) (when (and x (= :row (first x))) (node (second x)))))))]
      (node task) (vec @acc))))


(defn seeded-shuffle
  [^java.util.Random r]
  (fn [xs]
    (let [a (java.util.ArrayList. ^java.util.Collection (vec xs))]
      (java.util.Collections/shuffle a r)
      (vec a))))


(defn decoded
  "Portable decoded equality of an abstract value (check-abstract!'s rule)."
  [v]
  (case (first v)
    :lit [:lit (second v)]
    :cursor-cell [:identity (second v)]
    :row (let [n (second v) ss (:slots n)]
           (case (:kind n)
             :vec [:seq (mapv decoded ss)]
             :set [:set (set (map decoded ss))]
             :map [:map (into {} (map (fn [[k x]] [(decoded k) (decoded x)])) (partition 2 ss))]
             [:row n]))))


(defn distinct-decoded
  [vs]
  (vec (vals (into (sorted-map-by #(compare (pr-str %1) (pr-str %2))) (map (fn [e] [(decoded e) e])) vs))))


(defn gen-value
  "Random V over `cells`, depth-bounded: literals, cursor cells, vectors,
   sets, maps (cursor cells as keys too), sets of cursor-cell pairs (edges,
   aliases such as [X X] included), sets of sets."
  [^java.util.Random r cells depth]
  (let [pick (fn [xs] (nth xs (.nextInt r (count xs))))
        cc (fn [] [:cursor-cell (pick cells)])
        k (.nextInt r (if (pos? depth) 9 3))]
    (case k
      0 [:lit (pick [1 2 :k "s" nil])]
      1 (cc)
      2 (cc)
      3 (let [elems (vec (repeatedly (inc (.nextInt r 3)) #(gen-value r cells (dec depth))))]
          (if (some #(not= :lit (first %)) elems) [:row (rows/node :vec elems)] [:lit (mapv second elems)]))
      4 (let [elems (distinct-decoded (repeatedly (inc (.nextInt r 4)) #(gen-value r cells (dec depth))))]
          (if (some #(not= :lit (first %)) elems) [:row (rows/node :set elems)] [:lit (set (map second elems))]))
      5 (let [pairs (vec (vals (into (sorted-map-by #(compare (pr-str %1) (pr-str %2))) (map (fn [p] [(decoded (first p)) p]))
                                     (repeatedly (inc (.nextInt r 3)) #(vector (gen-value r cells (dec depth)) (gen-value r cells (dec depth)))))))]
          (if (some (fn [[a b]] (or (not= :lit (first a)) (not= :lit (first b)))) pairs)
            [:row (rows/node :map (vec (mapcat identity pairs)))]
            [:lit (into {} (map (fn [[a b]] [(second a) (second b)])) pairs)]))
      (6 7) (let [edges (distinct-decoded (repeatedly (+ 2 (.nextInt r 3)) #(vector :row (rows/node :vec [(cc) (cc)]))))]
              [:row (rows/node :set edges)])
      8 (let [inner (distinct-decoded (repeatedly (+ 2 (.nextInt r 2))
                                                  #(let [es (distinct-decoded (repeatedly (inc (.nextInt r 3)) cc))]
                                                     [:row (rows/node :set es)])))]
          [:row (rows/node :set inner)]))))


(defn reached
  [v]
  (let [acc (atom #{})]
    (letfn [(w
              [v]
              (case (first v)
                :cursor-cell (swap! acc conj (second v))
                :row (doseq [s (:slots (second v))] (w s))
                nil))]
      (w v) @acc)))


(defn gen-task
  "A :halted task: 1-6 cursor cells over two bodies (ties common), 1-3 store
   bindings and the result slot hold random values."
  [seed]
  (let [r (java.util.Random. seed)
        k (inc (.nextInt r 6))
        cells (mapv #(keyword "src" (str "c" %)) (range k))
        bodies (mapv (fn [_] (rt/cursor-cell-body :s (if (zero? (.nextInt r 3)) 1 0))) cells)
        names (vec (take (inc (.nextInt r 3)) '[a b c]))
        vals (mapv (fn [_] (gen-value r cells 3)) names)
        result (gen-value r cells 2)
        used (reduce into (reached result) (map reached vals))
        census (keep (fn [[c b]] (when (used c) [c b])) (map vector cells bodies))]
    (if (empty? used)
      (recur (inc seed))
      (let [t (rt/task-node census result)]
        (rows/node :task (assoc (:slots t) 6 (rows/node :store (vec (mapcat (fn [n v] [n v]) names vals)))))))))


(defn gen-incidence-task
  "The reviewer's tie-heavy incidence families (r3 review, seed-driven):
   2-8 cells of one body; edge sets, overlapping groups, aliased maps,
   repeated and nested containers, reversed edges, ordered anchors."
  [seed]
  (let [r (java.util.Random. seed)
        sh (seeded-shuffle r)
        c (fn [x] [:cursor-cell x])
        v (fn [xs] [:row (rows/node :vec (vec xs))])
        s (fn [xs] [:row (rows/node :set (distinct-decoded xs))])
        m (fn [ps]
            [:row (rows/node :map (vec (mapcat identity (vals (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
                                                                    (map (fn [p] [(decoded (first p)) p])) ps)))))])
        n (+ 2 (.nextInt r 7)) cs (mapv #(keyword "src" (str "i" %)) (range n))
        cc #(c (nth cs (.nextInt r n)))
        edge #(v [(cc) (cc)]) es (vec (repeatedly (+ 2 (.nextInt r 9)) edge))
        group #(s (repeatedly (+ 2 (.nextInt r 4)) cc))
        value (case (mod seed 10)
                0 (s es)
                1 (v [(s es) (s (sh es))])
                2 (s (repeatedly 4 group))
                3 (v [(s es) (cc)])
                4 (s (map #(v [% (group)]) es))
                5 (m (mapv (fn [x] [(c x) (cc)]) cs))
                6 (v [(s es) (s (map #(v (reverse (:slots (second %)))) es))])
                7 (s (map #(m [[(cc) %] [[:lit :anchor] (group)]]) es))
                8 (s [(v [(s es) (group)]) (v [(group) (s (sh es))])])
                9 (v [(s es) (s (repeatedly 4 group)) (cc)]))
        b (rt/cursor-cell-body :s 0)]
    (rt/task-node (mapv #(vector % b) (sort (reached value))) value)))


(defn worked-act
  []
  (first (:slots (rows/decode (rows/frame->rows ft/worked-frame)))))


(def park-vector [[:closure 0 [] 2] [:halt 0] [:park 0] [:return 0]])
(def park-address (jing/segment-key park-vector))


(defn frame-task
  "A blocked task carrying the worked code plus a park segment. The wait's
   act holds `wait-values` in its window register 1 and in one K frame's
   window register 1; `env-value`, when given, is bound to e0 in the free
   environment and in every environment; the parked state (P 7) is an act
   at the park site whose K frame holds `parked-value` in register 1.
   Decodes under 6a's validate-frames."
  [census wait-values env-value parked-value]
  (let [base (rt/task-node (conj (vec census) [:test/cursor (rt/cursor-cell-body :s 0)]) nil)
        [seg pc env window k rd] (:slots (worked-act))
        [_ store-of] (:slots env)
        bindings (rows/node :bindings (if env-value ['e0 env-value] []))
        env (rows/node :env [bindings store-of])
        kframe (fn [v] (rows/node :kframe [seg pc env (rows/node :window [1 v]) rd]))
        [w1 w2] wait-values
        wait-act (rows/node :act [seg pc env (rows/node :window [1 w1]) (rows/node :k (if w2 [(kframe w2)] [])) rd])
        pending (second (:slots (rows/decode (rows/frame->rows ft/worked-frame))))
        parked (rows/node :act [park-address 3 env (rows/node :window []) (rows/node :k [(kframe parked-value)]) 0])]
    (rows/node :task (-> (:slots base) (assoc 2 :blocked) (assoc 5 bindings)
                         (assoc 11 (merge ft/worked-code {park-address park-vector}))
                         (assoc 8 (rows/node :parked-table (if parked-value [7 parked] [])))
                         (assoc 13 [(rows/node :wait [wait-act pending])]) (assoc 16 {:yin.k/policy :yin.k/fork})))))


(defn gen-frame-task
  [seed]
  (let [r (java.util.Random. seed)
        k (inc (.nextInt r 4))
        cells (mapv #(keyword "src" (str "f" %)) (range k))
        body (rt/cursor-cell-body :s 0)
        pick (fn [] (gen-value r cells 2))
        wait-values (vec (repeatedly (inc (.nextInt r 2)) pick))
        env-value (when (zero? (.nextInt r 2)) (pick))
        parked-value (when (zero? (.nextInt r 2)) (pick))
        used (reduce into #{} (map reached (remove nil? (concat wait-values [env-value parked-value]))))]
    (if (empty? used)
      (recur (inc seed))
      (frame-task (mapv #(vector % body) (filter used cells)) wait-values env-value parked-value))))


(defn gen-child-task
  "A parent (seed) with two install children: one random child task and the
   review counterexample as a child; the parent's own values stay random."
  [seed]
  (let [parent (gen-task seed)
        child (rows/node :task (assoc (:slots (gen-task (+ 100000 seed))) 3 :child))]
    (rows/node :task (assoc (:slots parent) 12 [(rows/node :install ['a :running nil {} child])
                                                (rows/node :install ['b :running nil {} child])]))))


(defn fixture-review-counterexample
  "'a bound to #{X Y} with equal bodies, 'b bound to X."
  []
  (let [body (rt/cursor-cell-body :s 0)
        t (rt/task-node [[:x body] [:y body]] [:lit nil])]
    (rows/node :task (assoc (:slots t) 6 (rows/node :store ['a [:row (rows/node :set [[:cursor-cell :x] [:cursor-cell :y]])]
                                                            'b [:cursor-cell :x]])))))


(defn with-child
  "`parent` with one install 'a holding `child` (slot 3 set to :child)."
  [parent child]
  (rows/node :task (assoc (:slots parent) 12
                          [(rows/node :install ['a :running nil {} (rows/node :task (assoc (:slots child) 3 :child))])])))


;; ------------------------------------------------------------ diagnostics

(defn- permutations
  [n]
  (if (zero? n) [[]]
      (for [p (permutations (dec n)) i (range n)]
        (vec (concat (subvec p 0 i) [(dec n)] (subvec p i))))))


(defn- factorial
  [n]
  (reduce * 1 (range 1 (inc n))))


(defn- container-sizes
  "Member count of every :set and :map, indexed by the ordinal reorder gives it."
  [task]
  (let [acc (atom {})]
    (reorder task (fn [k ms] (swap! acc assoc k (count ms)) ms))
    (mapv @acc (range (count @acc)))))


(defn- permissive-root
  [t]
  (binding [*numbering-opts* (assoc *numbering-opts* :permissive true)]
    (let [d (discovery-numbering t)] (first (emit-node* t (:numbering d) {} [])))))


(defn classify-refused
  "For a refused state: :interchangeable when every listed order of every
   set and map gives one root under permissive entry (exhaustive when the
   number of orders is at most `limit`), :non-interchangeable when two
   orders differ (exhaustive or sampled), else :undecided (sampled, one
   root). Permissive entry depends on nothing but listed orders, so the
   exhaustive verdict is exact."
  [task limit samples]
  (let [sizes (container-sizes task)
        total (reduce (fn [t n] (min (* t (factorial n)) 1000000000)) 1 sizes)]
    (if (<= total limit)
      (let [choices (reduce (fn [acc n] (for [c acc p (permutations n)] (conj c p))) [[]] sizes)
            roots (into #{} (map (fn [perms] (permissive-root (reorder task (fn [k ms] (mapv #(nth ms %) (nth perms k))))))) choices)]
        (if (= 1 (count roots)) :interchangeable :non-interchangeable))
      (let [r (java.util.Random. 7) sh (seeded-shuffle r)
            roots (into #{} (map (fn [_] (permissive-root (reorder task (fn [_ ms] (sh ms)))))) (range samples))]
        (if (= 1 (count roots)) :undecided :non-interchangeable)))))


(defn invariant-roots
  "Distinct roots of a state over `n` renamings plus shuffles (1 = invariant
   under the samples)."
  [task n]
  (let [r (java.util.Random. 5) cells (all-census-cells task)]
    (with-redefs [shuffle (seeded-shuffle r)]
      (->> (repeatedly n #(rename-and-shuffle task (zipmap cells (map (fn [i] (keyword "r" (str "z" i))) (shuffle (range (count cells)))))))
           ;; A refusal's :cursor-cells names local source ids; the verdict and
           ;; the path are what must be invariant.
           (map (fn [t] (let [r (do-root t)] (if (map? r) (update r :refused dissoc :yin.k/cursor-cells) r))))
           distinct count))))


(defn report-fixture
  [name task]
  (let [d (discovery-numbering task)]
    (println name)
    (println "  DO :" (or (:refused d) (into (sorted-map) (:numbering d))) "| work" (:work (:used d)) "| encoded bytes" (:encoded-bytes (:used d)))
    (println "  DO root:" (do-root task) "| roots over 24 renamings+shuffles:" (invariant-roots task 24))
    (when (:refused d) (println "  interchangeable? (exhaustive/sampled permissive):" (classify-refused task 5040 48)))
    (println "  6a root:" (safe-6a-root task))))


(defn invariance-check
  "Root of the state and of the state renamed and shuffled; decode of the
   carrier. Returns a map of outcomes."
  [task ^java.util.Random r]
  (let [cells (all-census-cells task)
        fresh (zipmap cells (map #(keyword "other" (str "z" %)) (shuffle (range (count cells)))))
        task2 (with-redefs [shuffle (seeded-shuffle r)] (rename-and-shuffle task fresh))
        d1 (do-root task) d2 (do-root task2)]
    (cond
      (and (map? d1) (:refused d1))
      {:refused (:refused d1) :invariant (= (dissoc (:refused d1) :yin.k/cursor-cells) (dissoc (:refused d2) :yin.k/cursor-cells))}
      (map? d1) {:resource-limit (:resource-limit d1)}
      :else
      {:root d1 :invariant (= d1 d2)
       :used (:used (discovery-numbering task))
       :decode (let [e (error #(decode* (emit* task)))]
                 (cond (nil? e) :ok
                       (= :resource-limit (:yin.k/kind e)) :sixa-recompute-limit
                       :else e))})))


(defn search
  [label gen seeds]
  (let [r (java.util.Random. 11)
        stats (atom {:numbered 0 :refused 0 :resource-limit 0 :invariance-fail 0
                     :decode-ok 0 :decode-fail 0 :decode-6a-limit 0
                     :refused-interchangeable 0 :refused-non-interchangeable 0 :refused-undecided 0
                     :refused-only-across-groups 0 :refused-reasons {}
                     :count-spelling-gain 0 :count-spelling-loss 0
                     :max-work 0 :max-encoded-bytes 0 :max-local-runs 0})]
    (doseq [seed seeds]
      (let [task (gen seed) o (invariance-check task r)
            without (binding [*numbering-opts* (assoc *numbering-opts* :count-spelling false)] (do-root task))]
        ;; The occurrence count in the key: states it numbers that the literal
        ;; direction (body rank and local index only) refuses, and the reverse.
        (when (and (:root o) (map? without) (:refused without)) (swap! stats update :count-spelling-gain inc))
        (when (and (:refused o) (keyword? without)) (swap! stats update :count-spelling-loss inc))
        (when-not (:invariant o true)
          (swap! stats update :invariance-fail inc) (println "INVARIANCE FAIL" label seed))
        (cond
          (:refused o)
          (let [why (:probe/why (:refused o))]
            (swap! stats update :refused inc)
            (doseq [w why] (swap! stats update-in [:refused-reasons w] (fnil inc 0)))
            (when (every? #{:shared-across-groups} why) (swap! stats update :refused-only-across-groups inc))
            (swap! stats update (case (classify-refused task 720 48)
                                  :interchangeable :refused-interchangeable
                                  :non-interchangeable :refused-non-interchangeable
                                  :refused-undecided) inc))
          (:resource-limit o)
          (do (swap! stats update :resource-limit inc) (println "RESOURCE LIMIT" label seed (:resource-limit o)))
          :else
          (do (swap! stats update :numbered inc)
              (swap! stats update :max-work max (:work (:used o) 0))
              (swap! stats update :max-encoded-bytes max (:encoded-bytes (:used o) 0))
              (swap! stats update :max-local-runs max (:local-runs (:used o) 0))
              (case (:decode o)
                :ok (swap! stats update :decode-ok inc)
                :sixa-recompute-limit (swap! stats update :decode-6a-limit inc)
                (do (swap! stats update :decode-fail inc) (println "DECODE FAIL" label seed (:decode o))))))))
    (println label (count seeds) "states:" @stats)
    @stats))


(defn -main
  []
  (let [body (rt/cursor-cell-body :s 0)
        body1 (rt/cursor-cell-body :s 1)
        s (fn [es] [:row (rows/node :set es)])
        v (fn [cs] [:row (rows/node :vec (mapv #(vector :cursor-cell %) cs))])
        c (fn [x] [:cursor-cell x])
        task (fn [ids result] (rt/task-node (mapv #(vector % body) ids) result))
        two-cycle (task [:a :b] (s [(v [:a :b]) (v [:b :a])]))
        three-cycle (task [:a :b :c] (s [(v [:a :b]) (v [:b :c]) (v [:c :a])]))
        path-abc (task [:a :b :c] (s [(v [:a :b]) (v [:b :c])]))
        loop-2cycle (task [:l :a :b] (s [(v [:l :l]) (v [:a :b]) (v [:b :a])]))
        seven (rt/seven-cursor-cell-triangle-square)
        witness (task [:a :b] [:row (rows/node :vec [(s [(s [(v [:a :b]) (v [:b :a])])]) (c :a)])])
        mixed (rt/task-node [[:a body] [:b body1] [:c body1]] (s [(v [:a :b]) (v [:a :c])]))
        anchored (task [:a :b :c :d :e] [:row (rows/node :vec [(s [(v [:a :b]) (v [:a :c]) (v [:b :d]) (v [:c :e]) (v [:d :b]) (v [:e :c])]) (c :a)])])
        two-sets-shared (task [:x :y :z] [:row (rows/node :vec [(s [(c :x) (c :y)]) (s [(c :y) (c :z)])])])
        two-sets-equal (task [:x :y] [:row (rows/node :vec [(s [(c :x) (c :y)]) (s [(c :x) (c :y)])])])
        two-2cycles (task [:a :b :c :d] (s [(s [(v [:a :b]) (v [:b :a])]) (s [(v [:c :d]) (v [:d :c])])]))
        ;; equal-size, equal-degree, non-isomorphic: a directed 3-cycle beside
        ;; a 3-edge orientation with a source and a sink; both inner sets key
        ;; [:set [:tie K K K]] with the same K (three pairs of distinct
        ;; count-2 cells), so the outer group's key is incomplete
        cycle-vs-fan (task [:a :b :c :d :e :f] (s [(s [(v [:a :b]) (v [:b :c]) (v [:c :a])]) (s [(v [:d :e]) (v [:f :e]) (v [:d :f])])]))
        nested-vs-edges (task [:x :y :z :w] [:row (rows/node :vec [(s [(s [(c :x) (c :y)])]) (s [(v [:x :z]) (v [:y :w])])])])
        frame-defect (fn [t] (rows/node :task (assoc (:slots t) 5 (rows/node :bindings ['yin/def [:lit 1]]))))
        deep (fn [n] (task [:x] (reduce (fn [inner _] [:row (rows/node :vec [inner])]) (c :x) (range n))))]
    (println "== fixtures (base design)")
    (report-fixture "fixture :s1 :s2 :x :y (6a pin fab223...)" (rt/fixture :s1 :s2 :x :y))
    (report-fixture "review counterexample 'a #{X Y}, 'b X" (fixture-review-counterexample))
    (report-fixture "symmetric pair #{X Y}" (task [:x :y] (s [(c :x) (c :y)])))
    (report-fixture "alias a: [x x y]" (task [:x :y] (v [:x :x :y])))
    (report-fixture "alias b: [x y y]" (task [:x :y] (v [:x :y :y])))
    (report-fixture "#{[X X] [Y Y]}" (task [:x :y] (s [(v [:x :x]) (v [:y :y])])))
    (report-fixture "{X 1, Y 1}" (task [:x :y] [:row (rows/node :map [(c :x) [:lit 1] (c :y) [:lit 1]])]))
    (report-fixture "codex A: #{[X X] [Y Z]}" (task [:x :y :z] (s [(v [:x :x]) (v [:y :z])])))
    (report-fixture "codex A reversed" (task [:x :y :z] (s [(v [:y :z]) (v [:x :x])])))
    (report-fixture "codex B: {X X, Y Z}" (task [:x :y :z] [:row (rows/node :map [(c :x) (c :x) (c :y) (c :z)])]))
    (report-fixture "codex C: #{#{X Y} #{Z W}}" (task [:x :y :z :w] (s [(s [(c :x) (c :y)]) (s [(c :z) (c :w)])])))
    (report-fixture "codex C renamed" (task [:q :r :s :t] (s [(s [(c :s) (c :t)]) (s [(c :q) (c :r)])])))
    (report-fixture "[#{X Y} X]" (task [:x :y] [:row (rows/node :vec [(s [(c :x) (c :y)]) (c :x)])]))
    (report-fixture "[#{#{X Y} #{Z W}} X]" (task [:x :y :z :w] [:row (rows/node :vec [(s [(s [(c :x) (c :y)]) (s [(c :z) (c :w)])]) (c :x)])]))
    (report-fixture "#{[#{X Y} #{X Y}] [#{P Q} #{R S}]}"
                    (task [:x :y :p :q :r :s] (s [[:row (rows/node :vec [(s [(c :x) (c :y)]) (s [(c :x) (c :y)])])]
                                                  [:row (rows/node :vec [(s [(c :p) (c :q)]) (s [(c :r) (c :s)])])]])))
    (report-fixture "codex r2 witness [#{#{[A B] [B A]}} A]" witness)
    (report-fixture "codex r4 balanced-alias witness #{[X X Y Y] [Z W Z W]}" (task [:x :y :z :w] (s [(v [:x :x :y :y]) (v [:z :w :z :w])])))
    (report-fixture "balanced-alias witness reversed" (task [:x :y :z :w] (s [(v [:z :w :z :w]) (v [:x :x :y :y])])))
    (report-fixture "[#{X Y} #{Y Z}] (occurrence count separates Y)" two-sets-shared)
    (report-fixture "path #{[A B] [B C]} (occurrence count separates B)" path-abc)
    (report-fixture "ten cells in a vec" (let [ids (mapv #(keyword "src" (str "x" %)) (range 10))]
                                           (rt/task-node (mapv (fn [id i] [id (rt/cursor-cell-body :s i)]) ids (range)) (v ids))))
    (println "\n== refused fixtures (tie test fails), with the exact interchangeability verdict")
    (report-fixture "2-cycle #{[A B] [B A]}" two-cycle)
    (report-fixture "3-cycle #{[A B] [B C] [C A]}" three-cycle)
    (report-fixture "loop + 2-cycle #{[L L] [A B] [B A]} (6a pinned 55ff16...)" loop-2cycle)
    (report-fixture "seven-cell triangle+square" seven)
    (report-fixture "[#{X Y} #{X Y}] (shared across groups)" two-sets-equal)
    (report-fixture "codex r3 ex.1 mixed bodies #{[A B] [A C]}, A body 0, B C body 1" mixed)
    (report-fixture "codex r3 ex.2 anchored [#{[A B] [A C] [B D] [C E] [D B] [E C]} A]" anchored)
    (report-fixture "#{#{[A B] [B A]} #{[C D] [D C]}} (incomplete keys)" two-2cycles)
    (report-fixture "#{#{[A B] [B C] [C A]} #{[D E] [F E] [D F]}} (equal incomplete keys, non-isomorphic members)" cycle-vs-fan)
    (report-fixture "[#{#{X Y}} #{[X Z] [Y W]}] (key walk accepts inside, root refuses)" nested-vs-edges)
    (println "\n== install child refusal translates in decode (path prefixed by the root id, then [12 name ...])")
    (let [parent (with-child (rt/task-node [] [:lit nil]) two-cycle)]
      (println "  lift:" (error #(emit* parent)))
      (println "  decode of a carrier built with emit-numbered:" (error #(decode* (rows/emit-numbered parent {})))))
    (println "\n== decoder order: discovery recomputation before frame validation (rows.cljc:570-578)")
    (let [kind (fn [wire] (:yin.k/kind (error #(decode* wire))))
          defect (frame-defect (rt/fixture :s1 :s2 :x :y))]
      (println "  wrong numbering + reserved-name frame defect:" (kind (rows/emit-numbered defect {:x :yin.k/cc-9 :y :yin.k/cc-8})) "(expect :noncanonical-cursor-cells)")
      (println "  right numbering + the same defect:" (kind (emit* defect)) "(expect :reserved-name)")
      (println "  2-cycle + the same defect:" (kind (rows/emit-numbered (frame-defect two-cycle) {:a :yin.k/cc-1 :b :yin.k/cc-2})) "(expect :unresolved-cursor-cells)")
      (println "  a valid carrier:" (or (error #(decode* (emit* (rt/fixture :s1 :s2 :x :y)))) :ok)))
    (println "\n== gates and budgets (resource refusals, never canonicality verdicts)")
    (binding [*numbering-opts* {:budget {:work 1}}]
      (println "  loop+2-cycle {:work 1}:" (error-brief #(emit* loop-2cycle)))
      (println "  decode of a valid carrier under {:work 1}:" (error-brief #(decode* (binding [*numbering-opts* {}] (emit* (rt/fixture :s1 :s2 :x :y)))))))
    (binding [*numbering-opts* {:budget {:cursor-cells 1}}]
      (println "  two cells under {:cursor-cells 1}:" (error-brief #(emit* (task [:x :y] (v [:x :y]))))))
    (let [ids (mapv #(keyword "src" (str "n" %)) (range 65))]
      (println "  65 cells under the default budget:" (error-brief #(emit* (rt/task-node (mapv (fn [id i] [id (rt/cursor-cell-body :s i)]) ids (range)) (v ids))))))
    (println "\n== depth gate before any recursive scan (codex r4 HIGH)")
    (binding [*numbering-opts* {:budget {:depth 3}}]
      (println "  [[[[X]]]] under {:depth 3}:" (error-brief #(emit* (deep 4))))
      (println "  one cell inside 3000 nested vectors under {:depth 3}:" (error-brief #(emit* (deep 3000))))
      (println "  one cell inside 10000 nested vectors under {:depth 3}:" (error-brief #(emit* (deep 10000))))
      (println "  a literal nested 3000 deep beside a cell under {:depth 3}:"
               (error-brief #(emit* (task [:x] [:row (rows/node :vec [(c :x) [:lit (reduce (fn [inner _] [inner]) 1 (range 3000))]])])))))
    (println "  3000 nested vectors under the default budget (depth 256):" (error-brief #(emit* (deep 3000))))
    (println "  200 nested vectors under the default budget: root" (do-root (deep 200)))
    (println "  a literal nested 3000 deep inside a set, default budget:"
             (error-brief #(emit* (task [:x :y] (s [[:row (rows/node :vec [(c :x) [:lit (reduce (fn [inner _] [inner]) 1 (range 3000))]])] (c :y)])))))
    (println "\n== large literals: never spelled in an ordered position; charged before encoding in an unordered one")
    (let [large (vec (range 400000))
          reachable (task [:x] [:row (rows/node :vec [(c :x) [:lit large]])])
          in-set (task [:x :y] (s [[:row (rows/node :vec [(c :x) [:lit large]])] [:row (rows/node :vec [(c :y) [:lit large]])]]))
          unreachable (rt/task-node [[:x body]] [:lit large])]
      (println "  ordered: root" (do-root reachable) "| work" (:work (:used (discovery-numbering reachable))))
      (println "  unordered, default budget:" (do-root in-set) "|" (used-at #(emit* in-set)))
      (binding [*numbering-opts* {:budget {:work 2000}}]
        (println "  unordered under {:work 2000} (the depth scan refuses at position 2001, the estimator is never entered):"
                 (error-brief #(emit* in-set)) "|" (used-at #(emit* in-set))))
      (binding [*numbering-opts* {:budget {:work 900000}}]
        (println "  unordered under {:work 900000} (the scan's 800k positions pass, the estimator aborts within remaining/128 steps):"
                 (error-brief #(emit* in-set)) "|" (used-at #(emit* in-set))))
      (println "  unreachable census cell (totality defect, not a refusal):" (error #(discovery-numbering unreachable))))
    (println "\n== key bytes versus stream descriptor size (rank spelling)")
    (doseq [n [128 65536]]
      (let [big (fn [i] (rows/node :cursor-cell-body [(rows/node :stream [:s (apply str (repeat n "d"))]) i]))
            t (rt/task-node [[:x (big 0)] [:y (big 0)] [:z (big 1)] [:w (big 1)]]
                            (s [(s [(c :x) (c :y)]) (s [(c :z) (c :w)]) (s [(c :x) (c :z)])]))
            d (discovery-numbering t)]
        (println "  descriptor" n "chars: work" (:work (:used d)) "| encoded bytes" (:encoded-bytes (:used d))
                 "| local runs" (:local-runs (:used d)) "| root" (do-root t))))
    (println "\n== frame-bearing fixtures (act window, K frame window, environments, parked state)")
    (let [t (frame-task [[:x body] [:y body]] [(c :x) (c :y)] (c :y) (s [(c :x) (c :y)]))]
      (report-fixture "wait window x, K window y, env y, parked K window #{x y}" t)
      (println "  decode:" (or (error #(decode* (emit* t))) :ok)))
    (let [t (frame-task [[:x body] [:y body]] [(s [(c :x) (c :y)])] nil (c :y))]
      (report-fixture "wait window #{x y}, parked K window y" t)
      (println "  decode:" (or (error #(decode* (emit* t))) :ok)))
    (println "\n== install children numbered by discovery order")
    (let [child (rows/node :task (assoc (:slots (fixture-review-counterexample)) 3 :child))
          parent (rows/node :task (assoc (:slots (rt/task-node [[:x (rt/cursor-cell-body :root/s 1)]] (c :x))) 12
                                         [(rows/node :install ['a :running nil {} child])
                                          (rows/node :install ['b :running nil {} child])]))
          wire (emit* parent)]
      (println "  parent root:" (:yin.k/root wire) "| task rows:" (count (filter #(= :task (second %)) (:yin.k/rows wire)))
               "| child numbering:" (into (sorted-map) (:numbering (discovery-numbering child)))
               "| decode:" (or (error #(decode* wire)) :ok)))
    (println "\n== random search (refused states classified by exhaustive permissive entry when <= 720 orders, else 48 samples)")
    (search "random values" gen-task (range 2000))
    (search "frame-bearing" gen-frame-task (range 200))
    (search "with children" gen-child-task (range 100))
    (search "incidence families (reviewer's generator)" gen-incidence-task (range 1000))))
