(ns yin.vm.ucf.rows
  "Version-3 row codec. Pure row construction, graph decoding and
   discovery-order cursor-cell numbering (design note §1), then full task
   transport for the semantic-register kernel (continuation design §7):
   lift-task walks a quiescent machine into an abstract task and emits it;
   lower-task decodes, checks and restores one into a fresh receiver.
   An abstract row is {:kind keyword :slots vector}. Structural references
   contain abstract rows; values use [:lit d], [:row abstract-row], [:cursor-cell id].
   A task's :cursor-cells row holds alternating source identities and :cursor-cell-body
   rows. Install children are separate tasks with separate cursor-cell scopes.

   The census is ordered numerically by n (draft §3, \"sorted by n\"), so
   :yin.k/cc-10 follows :yin.k/cc-9; its keys must spell cc-N with no leading
   zero, else :slot-kind.

   Default value markers are plain maps {:yin.k/row abstract-row} and
   {:yin.k/cursor-cell keyword}. A guest map whose sole key is :yin.k/row or
   :yin.k/cursor-cell collides with them and is not portable: on encode and decode
   every literal of that shape is refused :yin.k/non-portable. Explicit
   markers use the caller value encoder."
  (:require [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.stream.apply :as apply2]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.module :as module]
            [yin.vm.values :as values]
            [yin.vm.semantic-register.code :as code]
            [yin.vm.semantic-register.frame :as frame]
            [yin.vm.semantic-register.scan :as scan]
            [yin.vm.ucf.remote :as remote]))


(def profile {:yin.code/contract "v4" :yin.k/version 1})


(def pending-table
  {:next [:C]
   :put [[:ref :stream] :V :D]
   :ffi [:C :D :D]
   :ffi-request [:D :V [:ref :stream] [:ref :stream] :C :D]
   :link-request [:D :S [:ref :stream] [:ref :stream] :C :D :D]
   :link-response [:D :S [:ref :stream] [:ref :stream] :C]
   :install [:S]})


(def row-table
  "Slot descriptors, excluding id and kind. :repeat/:pairs are variadic;
   :refs is an ordered vector of structural references. :pending is
   discriminated by pending-table. Nothing in this table is executable."
  {:vec [:repeat :V] :list [:repeat :V] :set [:repeat :V]
   :map [:pairs :V :V] :bindings [:pairs :S :V] :store [:pairs :D :V]
   :env [[:ref :bindings] :optional-D]
   :closure [:A :N :syms [:ref :bindings] :optional-D]
   :prim [:S] :stream [:D :D] :reified [[:ref :act]] :parked-ref [:D]
   :act [:A :N [:ref :env] [:ref :window] [:ref :k] :N]
   :tail [:A :N [:ref :k]]
   :window [:pairs :N :V] :k [:repeat [:ref :kframe]]
   :kframe [:A :N [:ref :env] [:ref :window] :N]
   :wait [[:ref :act :tail] [:ref :pending]] :pending :pending
   :module-store [:D [:ref :store]]
   :parked-table [:pairs :D [:ref :act :tail]]
   :install [:S :D :D :D [:ref :task]]
   :cursor-cells [:pairs :C [:ref :cursor-cell-body]] :cursor-cell-body [[:ref :stream] :D]
   :task [:N :D :D :D :N [:ref :bindings] [:ref :store]
          [:refs :module-store] [:ref :parked-table] [:ref :cursor-cells]
          :D :D [:refs :install] [:refs :wait] :optional-D :optional-V :optional-D]})


(defn node
  [kind slots]
  {:kind kind :slots (if (= :window kind)
                       (vec (mapcat identity (sort-by first (partition 2 slots))))
                       (vec slots))})


(defn- refuse!
  [status kind path]
  (throw (ex-info "Row codec refused data"
                  {:yin.k/status status :yin.k/kind kind :yin.k/path path})))


(defn- bad!
  [kind path]
  (refuse! :yin.k/undecodable kind path))


(defn- marker-shaped?
  [x]
  (and (map? x) (= 1 (count x)) (or (contains? x :yin.k/row) (contains? x :yin.k/cursor-cell))))


(defn- check-literal-markers!
  [x]
  (when (marker-shaped? x) (refuse! :yin.k/non-portable :marker []))
  (when (coll? x)
    (doseq [v (if (map? x) (mapcat identity x) x)] (check-literal-markers! v)))
  (when (meta x) (check-literal-markers! (meta x))))


(defn- bytes-key
  [bs]
  ;; Unsigned bytewise order, without a length-first comparison.
  (mapv #(bit-and (int %) 255) (seq bs)))


(defn- compare-bytes
  [a b]
  (loop [i 0]
    (cond
      (= i (count a)) (if (= i (count b)) 0 -1)
      (= i (count b)) 1
      (= (nth a i) (nth b i)) (recur (inc i))
      :else (compare (nth a i) (nth b i)))))


(defn- byte-compare
  [a b]
  (compare-bytes (bytes-key (cbor/encode a)) (bytes-key (cbor/encode b))))


(defn- sorted-bytes
  [xs]
  (vec (sort byte-compare xs)))


(defn- descriptors
  [kind slots]
  ;; :entry is the numbering's synthetic map entry [:entry k v], never a carrier row.
  (let [schema (if (= :entry kind) [:V :V] (get row-table kind))]
    (cond
      (= schema :pending) (into [:D] (get pending-table (first slots)))
      (= :repeat (first schema)) (vec (repeat (count slots) (second schema)))
      (= :pairs (first schema)) (vec (take (count slots) (cycle (rest schema))))
      :else schema)))


(defn- ref?
  [d]
  (and (vector? d) (= :ref (first d))))


(defn- refs?
  [d]
  (and (vector? d) (= :refs (first d))))


(def ^:private census-key #"cc-([1-9][0-9]*)")


(defn- census-order
  ;; Numeric order of cc-N without reading wire data: fewer digits first,
  ;; then the digits. Other keys (a partial emit-numbered) sort last.
  [k]
  (let [digits (when (keyword? k) (second (re-matches census-key (name k))))]
    (if digits [0 (count digits) digits] [1 0 (str k)])))


(defn- ordered-slots
  [kind slots]
  (cond
    (= kind :window) (vec (mapcat identity (sort-by first (partition 2 slots))))
    (= kind :cursor-cells) (vec (mapcat identity (sort-by #(census-order (first %)) (partition 2 slots))))
    (contains? #{:map :bindings :store :parked-table} kind)
    (vec (mapcat identity (sort #(byte-compare (first %1) (first %2)) (partition 2 slots))))
    (= kind :set) (sorted-bytes slots)
    :else slots))


(def default-budget
  "Local resource bounds, independent of canonical bytes. :work counts the
   numbering's positions and the representation bound of every value it
   encodes for a comparison (design note §1.5)."
  {:rows 10000 :cursor-cells 64 :depth 256 :work 4194304 :bytes 16777216})


(declare emit-node canonical-numbering child-numberer validate-frames)


(defn- emit-slot
  [d x numbering rows path number-child]
  (when-not (or (ref? d) (refs? d) (= :C d))
    (check-literal-markers! (if (contains? #{:V :optional-V} d)
                              (when (= :lit (first x)) (second x)) x)))
  (cond
    (ref? d) (emit-node x numbering rows path number-child)
    (refs? d) (reduce (fn [[out rs] [j n]]
                        (let [p (conj path (if (= :install (:kind n)) (first (:slots n)) j))
                              [id rs'] (emit-node n numbering rs p number-child)]
                          [(conj out id) rs']))
                      [[] rows] (map-indexed vector x))
    (= d :C) [(get numbering x x) rows]
    (contains? #{:V :optional-V} d)
    (case (first x)
      :row (let [[id rs] (emit-node (second x) numbering rows path number-child)] [[:row id] rs])
      :cursor-cell [[:cursor-cell (get numbering (second x) (second x))] rows]
      [x rows])
    :else [x rows]))


(defn- emit-node
  ;; path is the row path of n, so a child's refusal reports [12 <install name> ...].
  ([n numbering rows] (emit-node n numbering rows [] (child-numberer default-budget)))
  ([n numbering rows path number-child]
   (let [{:keys [kind slots]} n
         ;; A child is canonicalized in its own identity scope.
         [slots rows] (reduce (fn [[out rs] [i d x]]
                                (if (and (ref? d) (= :task (:kind x)))
                                  (let [[id child-rows] (emit-node x (number-child x path) {} path number-child)]
                                    [(conj out id) (merge rs child-rows)])
                                  (let [[x' rs'] (emit-slot d x numbering rs (conj path i) number-child)]
                                    [(conj out x') rs'])))
                              [[] rows] (map vector (range) (descriptors kind slots) slots))
         slots (if (= kind :task)
                 (-> slots
                     (update 7 #(vec (sort (fn [a b] (byte-compare (nth (get rows a) 2) (nth (get rows b) 2))) %)))
                     (update 12 #(vec (sort (fn [a b] (byte-compare (nth (get rows a) 2) (nth (get rows b) 2))) %))))
                 (ordered-slots kind slots))
         body (into [kind] slots) id (jing/segment-key body)]
     [id (assoc rows id (into [id] body))])))


(defn- spend!
  [counter budget category amount]
  (let [n (get (swap! counter update category (fnil + 0) amount) category)]
    (when (> n (get budget category))
      (refuse! :yin.k/unsupported :resource-limit [category]))))


;; Discovery-order cursor-cell numbering (design note
;; yin.vm.semantic-register-vm.cursor-cell-numbering.md §1). A cursor cell is
;; named by the order in which a fixed pre-order walk of the task meets it. A
;; guest :set or :map is entered as the vector of its members sorted by key:
;; the member's spelling by a key walk, numbered cursor cells by number and
;; the others by local first-occurrence index, body rank and occurrence
;; count. Members with equal keys form a tie group, postponed and re-keyed
;; until a pass numbers nothing; a surviving group is entered in host order
;; only when the tie test proves that harmless, else the lift refuses
;; :unresolved-cursor-cells.


(defn- work!
  [ctx amount]
  (spend! (:counter ctx) (:budget ctx) :work amount))


(defn- charge-representation!
  "Charge to :work, before x is CBOR-encoded for a comparison, a conservative
   bound of its representation: 128 per node or scalar, 4 per character, 16
   plus the length per byte payload, metadata included. Iterative, against
   the budget remaining at the call: it stops as soon as its running total
   exceeds that, so it never reads to the end of a value it cannot afford."
  [ctx x]
  (let [{:keys [counter budget]} ctx
        remaining (- (:work budget) (get @counter :work 0))]
    (loop [stack (list (list x)) total 0 steps 0]
      (if (empty? stack)
        (do (swap! counter update :estimator-steps (fnil + 0) steps)
            (work! ctx total))
        (let [s (first stack) stack (rest stack)]
          (if (empty? s)
            (recur stack total steps)
            (let [x (first s)
                  stack (cons (rest s) stack)
                  total (+ total (cond
                                   (cbor/byte-payload? x) (+ 16 #?(:cljd (.-length x) :default (alength x)))
                                   (string? x) (+ 128 (* 4 (count x)))
                                   (or (keyword? x) (symbol? x)) (+ 128 (* 4 (count (str x))))
                                   :else 128))]
              (if (> total remaining)
                (do (swap! counter update :estimator-steps (fnil + 0) (inc steps))
                    (work! ctx total))
                (recur (cond->> stack
                         (coll? x) (cons (if (map? x) (mapcat identity x) (seq x)))
                         (meta x) (cons (list (meta x))))
                       total (inc steps))))))))))


(defn- key-bytes!
  "Every comparison encoding of the numbering: charged, then encoded."
  [ctx x]
  (charge-representation! ctx x)
  (bytes-key (cbor/encode x)))


(defn- sort-by-key!
  "xs in the bytewise order of (f x), each encoded once."
  [ctx f xs]
  (mapv second (sort #(compare-bytes (first %1) (first %2))
                     (mapv (fn [x] [(key-bytes! ctx (f x)) x]) xs))))


(def ^:private keyed-structural #{:bindings :store :parked-table})


(defn- value-slot?
  [d]
  (contains? #{:V :optional-V} d))


(defn- child-task?
  [ctx n]
  (and (= :task (:kind n)) (not (identical? n (:task ctx)))))


(defn- elem-item
  ;; A :set member is its V; a :map entry is the synthetic ordered row
  ;; [:entry k v], so the alias pattern across key and value is in its key.
  [kind e]
  (if (= :map kind) [:row (node :entry e)] e))


(defn- depth-scan!
  "The :depth gate, before any recursive traversal of the task: one pre-order
   scan with an explicit stack over rows, members, structural and literal
   data and the census, install children skipped (each is gated by its own
   numbering). A position's depth is its slot steps from the task (a member
   step counts one, a map entry's key or value two) plus, inside data, its
   collection nesting. Each position costs one :work unit, charged when it
   is popped, before any work proportional to it."
  [ctx task]
  (let [limit (:depth (:budget ctx))
        row (fn [n k] [:row nil n k])
        slot (fn [d v k] [:slot d v k])
        data (fn [v k] [:data nil v k])]
    (loop [stack (list (list (row task 0)))]
      (when (seq stack)
        (let [s (first stack) stack (rest stack)]
          (if (empty? s)
            (recur stack)
            (let [[tag d x k] (first s)
                  stack (cons (rest s) stack)]
              (work! ctx 1)
              (when (> k limit) (refuse! :yin.k/unsupported :resource-limit [:depth]))
              (recur
                (cons
                  (case tag
                    :row
                    (let [{:keys [kind slots]} x k' (inc k)]
                      (when-not (child-task? ctx x)
                        (let [ds (descriptors kind slots)]
                          (cond
                            (= :task kind)
                            (mapcat (fn [i d v]
                                      (if (contains? #{7 12} i)
                                        (map (fn [child] (row child (inc k'))) v)
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
                    (cond
                      (ref? d) [(row x k)]
                      (refs? d) (map (fn [n] (row n (inc k))) x)
                      (= :C d) nil
                      (value-slot? d) (when x
                                        (case (first x)
                                          :cursor-cell nil
                                          :row [(row (second x) k)]
                                          :lit [(data (second x) k)]
                                          [(data x k)]))
                      :else [(data x k)])
                    :data
                    (concat (when (coll? x) (map (fn [e] (data e (inc k))) (if (map? x) (mapcat identity x) x)))
                            (when (meta x) [(data (meta x) (inc k))])))
                  stack)))))))))


(defn- walk-cells
  "Call f on the cursor cell at every position under slot (d x), the census
   and install children excluded; one :work unit per position. Recursive,
   so it runs only after the depth scan."
  [ctx d x f]
  (letfn [(slot
            [d x]
            (work! ctx 1)
            (cond
              (ref? d) (row x)
              (refs? d) (doseq [n x] (row n))
              (= :C d) (f x)
              (value-slot? d) (when x
                                (case (first x)
                                  :cursor-cell (f (second x))
                                  :row (row (second x))
                                  nil))
              :else nil))
          (row
            [n]
            (when-not (or (= :cursor-cells (:kind n)) (child-task? ctx n))
              (doseq [[d x] (map vector (descriptors (:kind n) (:slots n)) (:slots n))] (slot d x))))]
    (slot d x)))


(defn- item-cells
  "The cursor cells under slot (d x), memoized per numbering on the slot."
  [ctx d x]
  (or (get @(:cells-memo ctx) [d x])
      (let [acc (atom #{})]
        (walk-cells ctx d x #(swap! acc conj %))
        (swap! (:cells-memo ctx) assoc [d x] @acc)
        @acc)))


(defn- body-ranks
  "Cursor cell -> rank of its body label [:body [:stream identity
   descriptor] position] among the census's distinct labels in bytewise
   order, so a key never repeats a descriptor."
  [ctx bodies]
  (let [labels (into {} (map (fn [[c b]]
                               (let [[stream position] (:slots b)]
                                 [c [:body (into [:stream] (:slots stream)) position]])))
                     bodies)
        ranks (into {} (map-indexed (fn [i l] [l i]) (sort-by-key! ctx identity (distinct (vals labels)))))]
    (into {} (map (fn [[c l]] [c (get ranks l)])) labels)))


(defn- new-run
  [ctx num tag start]
  {:ctx ctx :num (atom num) :tag tag :next (atom start)
   :groups (atom []) :gid (atom 0) :refused (atom nil)})


(defn- meet!
  "W2: a cursor cell met for the first time receives the run's next number."
  [run c]
  (when-not (contains? @(:num run) c)
    (swap! (:num run) assoc c [(:tag run) @(:next run)])
    (swap! (:next run) inc)))


(declare enter-node! unordered! key-walk spell-slot spell-node)


(defn- enter-slot!
  [run d x path]
  (let [ctx (:ctx run)]
    (work! ctx 1)
    (when (> (count path) (:depth (:budget ctx)))
      (refuse! :yin.k/unsupported :resource-limit [:depth]))
    (cond
      (ref? d) (enter-node! run x path)
      (refs? d) (doseq [[i n] (map-indexed vector x)] (enter-node! run n (conj path i)))
      (= :C d) (meet! run x)
      (value-slot? d) (when x
                        (case (first x)
                          :cursor-cell (meet! run (second x))
                          :row (enter-node! run (second x) path)
                          nil))
      :else nil)))


(defn- enter-node!
  "W1: pre-order in table order; ordered repeated slots in order; keyed
   structural rows, installs and module stores in the byte order of the
   key; :window in register order; :set and :map by member keys, ties
   postponed; the census and install children are not entered."
  [run n path]
  (let [{:keys [kind slots]} n ctx (:ctx run) ds (descriptors kind slots)]
    (cond
      (or (= :cursor-cells kind) (child-task? ctx n)) nil
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
      :else (doseq [[i d x] (map vector (range) ds slots)] (enter-slot! run d x (conj path i))))))


(defn- unordered!
  "Key every member by a key walk under the numbering at this moment; enter
   members with a unique key in key order and postpone each run of equal
   keys, a tie group, to the run's queue. A member's path is the
   container's path plus :member: no index into an unordered container is
   canonical."
  [run container kind elems path]
  (let [ctx (:ctx run)
        keyed (mapv (fn [e]
                      ;; One unit per member keyed, so key walks and memo lookups are charged.
                      (work! ctx 1)
                      [(key-walk run (elem-item kind e) (conj path :member)) e])
                    elems)
        groups (partition-by (comp :bytes first)
                             (sort #(compare-bytes (:bytes (first %1)) (:bytes (first %2))) keyed))]
    (doseq [g groups]
      (if (= 1 (count g))
        (enter-slot! run :V (elem-item kind (second (first g))) (conj path :member))
        (swap! (:groups run) conj {:id (swap! (:gid run) inc) :container container :kind kind :path path
                                   :key (:spell (first (first g))) :complete? (:complete? (first (first g)))
                                   :elems (mapv second g)})))))


(defn- fixpoint!
  "W3: re-key the postponed groups under the current numbering, in queue
   order; repeat while a pass numbers a cursor cell and groups remain."
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


(defn- tie-failure
  "The tie test, nil when the group passes: its common key is complete, and
   its members' unnumbered cursor cells are pairwise disjoint and occur in
   no member of another surviving group."
  [run groups g]
  (let [own (mapv #(unnumbered-cells run (:kind g) %) (:elems g))
        others (reduce (fn [acc h]
                         (if (= (:id h) (:id g))
                           acc
                           (reduce #(into %1 (unnumbered-cells run (:kind h) %2)) acc (:elems h))))
                       #{} groups)]
    (cond
      (not (:complete? g)) :incomplete-key
      (not= (reduce + (map count own)) (count (reduce into #{} own))) :shared-within-group
      (some (fn [cells] (some #(contains? others %) cells)) own) :shared-across-groups
      :else nil)))


(defn- resolve!
  "The postponed groups to completion: the fixpoint, then the tie test on
   every surviving group. All pass: enter them in host order and repeat. Any
   fails: the root walk refuses; a key walk leaves them as [:tie ...] residue."
  [run]
  (loop []
    (fixpoint! run)
    (let [groups @(:groups run)]
      (when (seq groups)
        (let [failing (filterv #(tie-failure run groups %) groups)]
          (cond
            (empty? failing)
            ;; Passing groups are independent (their cursor cells are
            ;; confined), so all are entered before the next pass.
            (do (reset! (:groups run) [])
                (doseq [g groups e (:elems g)]
                  (enter-slot! run :V (elem-item (:kind g) e) (conj (:path g) :member)))
                (recur))
            (= :n (:tag run))
            (reset! (:refused run)
                    {:path (:path (first failing))
                     :cursor-cells (vec (sort-by str (distinct (mapcat (fn [g] (mapcat #(unnumbered-cells run (:kind g) %) (:elems g)))
                                                                       failing))))})
            :else nil))))))


(defn- key-walk
  "A member's key: the walk over the member from the enclosing walk's
   numbering, local numbers counted on from an enclosing key walk's counter,
   spelled under its final numbering. Memoized on the member, the numbering
   of its cursor cells and the counter."
  [run item path]
  (let [ctx (:ctx run)
        cells (item-cells ctx :V item)
        num @(:num run)
        start (if (= :u (:tag run)) @(:next run) 0)
        memo-key [item (select-keys num cells) start]]
    (or (get @(:memo ctx) memo-key)
        (let [sub (new-run ctx num :u start)]
          (work! ctx (count cells))
          (enter-slot! sub :V item path)
          (resolve! sub)
          (let [spelling (spell-slot sub :V item path)
                k {:spell spelling :bytes (key-bytes! ctx spelling) :complete? (empty? @(:groups sub))}]
            (swap! (:memo ctx) assoc memo-key k)
            k)))))


(defn- spell-cell
  [run c]
  (let [ctx (:ctx run) [tag k] (get @(:num run) c)]
    (case tag
      :n [:cursor-cell [:n k]]
      :u [:cursor-cell [:u k (get (:ranks ctx) c) (get (:counts ctx) c)]]
      (throw (ex-info "Spelling an unnumbered cursor cell" {:cursor-cell c})))))


(defn- spell-slot
  [run d x path]
  (work! (:ctx run) 1)
  (cond
    (ref? d) (spell-node run x path)
    (refs? d) (vec (map-indexed (fn [i n] (spell-node run n (conj path i))) x))
    (= :C d) (spell-cell run x)
    (value-slot? d) (if (nil? x)
                      x
                      (case (first x)
                        :cursor-cell (spell-cell run (second x))
                        :row [:row (spell-node run (second x) path)]
                        x))
    :else x))


(defn- spell-node
  "Literals and structural data as themselves (by reference), rows expanded
   in place, keyed rows in key order, a :set or :map as the sorted vector of
   its members' spellings with each surviving tie group as [:tie K ... K]."
  [run n path]
  (let [{:keys [kind slots]} n ctx (:ctx run) ds (descriptors kind slots)]
    (cond
      (= :cursor-cells kind) [:cursor-cells]
      (child-task? ctx n) [:child]
      (= :task kind)
      (into [kind] (map (fn [i d x]
                          (if (contains? #{7 12} i)
                            (sort-by-key! ctx #(nth % 1) (mapv #(spell-node run % (conj path i (first (:slots %)))) x))
                            (spell-slot run d x (conj path i))))
                        (range) ds slots))
      (contains? keyed-structural kind)
      (into [kind] (mapcat identity (sort-by-key! ctx first
                                                  (mapv (fn [[k v]] [k (spell-slot run (second ds) v (conj path k))])
                                                        (partition 2 slots)))))
      (= :window kind)
      (into [kind] (mapcat (fn [[r v]] [r (spell-slot run :V v (conj path r))]) (sort-by first (partition 2 slots))))
      (contains? #{:set :map} kind)
      (let [elems (if (= :set kind) (vec slots) (mapv vec (partition 2 slots)))
            tied (filterv #(identical? n (:container %)) @(:groups run))
            tied-elems (set (mapcat :elems tied))
            plain (into [] (keep (fn [e]
                                   (when-not (contains? tied-elems e)
                                     (spell-slot run :V (elem-item kind e) (conj path :member)))))
                        elems)
            ties (mapv #(into [:tie] (repeat (count (:elems %)) (:key %))) tied)]
        (into [kind] (sort-by-key! ctx identity (into plain ties))))
      :else (into [kind] (map (fn [i d x] (spell-slot run d x (conj path i))) (range) ds slots)))))


(defn- discovery-numbering
  "The root walk: {:numbering {source -> :yin.k/cc-n}}, or {:refused {:path
   :cursor-cells}} when a tie group fails the tie test. The census's pair
   count is charged first, then the depth scan runs, before anything
   recursive. Throws the resource refusal, or a defect without
   :yin.k/status when the numbering is not a bijection onto the census."
  [task budget counter]
  (let [budget (merge default-budget budget)
        census (:slots (nth (:slots task) 9))]
    (spend! counter budget :cursor-cells (quot (count census) 2))
    (if (empty? census)
      {:numbering {}}
      (let [ctx {:task task :budget budget :counter counter :memo (atom {}) :cells-memo (atom {})}
            _ (depth-scan! ctx task)
            bodies (into {} (map vec) (partition 2 census))
            ranks (body-ranks ctx bodies)
            counts (let [acc (atom {})]
                     (walk-cells ctx [:ref :task] task #(swap! acc update % (fnil inc 0)))
                     @acc)
            run (new-run (assoc ctx :ranks ranks :counts counts) {} :n 1)]
        (enter-node! run task [])
        (resolve! run)
        (if-let [refused @(:refused run)]
          {:refused refused}
          (let [numbered (set (keys @(:num run))) declared (set (keys bodies))]
            (when (or (seq @(:groups run)) (not= numbered declared))
              (throw (ex-info "Cursor-cell numbering is not total"
                              {:missing (vec (sort-by str (remove numbered declared)))
                               :extra (vec (sort-by str (remove declared numbered)))})))
            {:numbering (into {} (map (fn [[c [_ k]]] [c (keyword "yin.k" (str "cc-" k))])) @(:num run))}))))))


(defn- unresolved!
  [refused prefix]
  (throw (ex-info "Row codec refused data"
                  {:yin.k/status :yin.k/non-portable :yin.k/kind :unresolved-cursor-cells
                   :yin.k/path (into prefix (:path refused)) :yin.k/cursor-cells (:cursor-cells refused)})))


(defn canonical-numbering
  "Discovery-order cursor-cell numbering (design note §1): source cursor
   cell -> :yin.k/cc-n, the n-th cursor cell the canonical walk meets.
   Refuses :yin.k/non-portable :unresolved-cursor-cells when tied members of
   a guest set or map cannot be proved interchangeable. A budget exhaustion
   is a resource refusal, never a canonicality verdict. counter, when
   supplied, is the atom the charges accumulate in."
  ([task] (canonical-numbering task default-budget))
  ([task budget] (canonical-numbering task budget (atom {})))
  ([task budget counter]
   (let [r (discovery-numbering task budget counter)]
     (if-let [refused (:refused r)] (unresolved! refused []) (:numbering r)))))


(defn- child-numberer
  "Numbers an install child in its own scope under budget; a refusal's path
   is prefixed by the install's row path [12 <name> ...]."
  [budget]
  (fn [child path]
    (let [r (discovery-numbering child budget (atom {}))]
      (if-let [refused (:refused r)] (unresolved! refused path) (:numbering r)))))


(defn emit-numbered
  "Emit under a supplied numbering. Useful for canonicality probes;
   ordinary callers use emit, which computes the canonical numbering.
   number-child, (fn [child path] numbering), numbers install children;
   by default canonically."
  ([task numbering] (emit-numbered task numbering (child-numberer default-budget)))
  ([task numbering number-child]
   (let [[id rows] (emit-node task numbering {} [] number-child)]
     {:yin.k/handoff true :yin.k/version 3 :yin.k/root id :yin.k/rows (vec (vals rows))})))


(defn emit
  "Emit an abstract task as a self-contained carrier. This is a codec,
   not lift-task: the caller supplies the complete abstract row tree."
  ([task] (emit task default-budget))
  ([task budget]
   (let [budget (merge default-budget budget)
         [id rows] (emit-node task (canonical-numbering task budget) {} [] (child-numberer budget))]
     {:yin.k/handoff true :yin.k/version 3 :yin.k/root id :yin.k/rows (vec (vals rows))})))


(defn- structural?
  [kind d x]
  (cond
    (and (= :cursor-cells kind) (= :C d)) (and (keyword? x) (some? (re-matches census-key (name x))))
    (or (ref? d) (= :C d)) (keyword? x)
    (refs? d) (and (vector? x) (every? keyword? x))
    (contains? #{:V :optional-V} d)
    (or (and (= :optional-V d) (nil? x))
        (and (vector? x) (= 2 (count x)) (contains? #{:lit :row :cursor-cell} (first x))
             (or (= :lit (first x)) (keyword? (second x)))))
    (= :N d) (and (code/reg? x) (<= x 4503599627370495))
    (= :S d) (symbol? x)
    (= :syms d) (and (vector? x) (every? symbol? x))
    (= :A d) (some? (jing/parse-segment-address x))
    :else true))


(defn- literal?
  [x]
  (and (not (cbor/byte-payload? x))
       (or (nil? x) (boolean? x) (string? x) (keyword? x) (symbol? x)
           (number? x)
           (and (coll? x)
                (every? literal? (if (map? x) (mapcat identity x) x))
                (or (nil? (meta x)) (literal? (meta x))))
           (try (some? (cbor/numeric-kind x))
                (catch #?(:cljd Object :clj Throwable :cljs :default) _e false)))))


(def value-row-kinds #{:vec :list :set :map :closure :prim :stream :reified :parked-ref})


(defn- check-abstract!
  [root]
  (letfn [(literal-value
            [x]
            (cond
              (map? x) [:map (into {} (map (fn [[k v]] [(literal-value k) (literal-value v)])) x)]
              (set? x) [:set (set (map literal-value x))]
              (sequential? x) [:seq (mapv literal-value x)]
              :else [:literal x]))
          (value
            [v]
            (case (first v)
              :lit (literal-value (second v))
              :cursor-cell [:identity (second v)]
              :row (let [n (second v) ss (:slots n)]
                     (case (:kind n)
                       :vec [:seq (mapv value ss)]
                       :list [:seq (mapv value ss)]
                       :set [:set (set (map value ss))]
                       :map [:map (into {} (map (fn [[k x]] [(value k) (value x)])) (partition 2 ss))]
                       [:row n]))))
          (reference?
            [v]
            (case (first v)
              :cursor-cell true
              :lit false
              :row (let [n (second v)]
                     (if (contains? #{:vec :list :set :map} (:kind n))
                       (boolean (some reference? (:slots n))) true))
              false))
          (visit
            [n path cursor-cells reached]
            (let [{:keys [kind slots]} n ds (descriptors kind slots)]
              (when (contains? #{:map :set} kind)
                (let [vs (if (= :set kind) slots (map first (partition 2 slots)))
                      decoded (map value vs)]
                  (when-not (= (count decoded) (count (distinct decoded))) (bad! :duplicate-key path))))
              (when (and (contains? #{:vec :list :set :map} kind)
                         (not (some reference? slots))) (bad! :reference-free-row path))
              (doseq [[i d x] (map vector (range) ds slots)]
                (let [p (conj path i)]
                  (cond
                    (= :C d) (do (when-not (contains? cursor-cells x) (bad! :missing-cursor-cell p)) (swap! reached conj x))
                    (and (ref? d) (= :task (:kind x))) (check-task x p)
                    (and (ref? d) (not= :cursor-cells (:kind x))) (visit x p cursor-cells reached)
                    (refs? d) (doseq [[j child] (map-indexed vector x)]
                                (visit child (conj p j) cursor-cells reached))
                    (contains? #{:V :optional-V} d)
                    (when x
                      (case (first x)
                        :lit (when-not (literal? (second x)) (bad! :literal p))
                        :cursor-cell (do (when-not (contains? cursor-cells (second x)) (bad! :missing-cursor-cell p))
                                         (swap! reached conj (second x)))
                        :row (visit (second x) p cursor-cells reached)))
                    :else nil)))))
          (check-task
            [task path]
            (let [slots (:slots task) census (nth slots 9)
                  cursor-cells (into {} (map vec) (partition 2 (:slots census))) reached (atom #{})]
              (visit task path cursor-cells reached)
              (when-not (= (set (keys cursor-cells)) @reached) (bad! :extra-cursor-cell (conj path 9)))))]
    (when (= :task (:kind root)) (check-task root []))))


(defn- decode*
  "Validate gate, local schema, finite reachable graph, hashes, canonical
   form and cursor-cell numbering, then expand structural refs. No attachment.
   Frame checks are available separately through validate-frames."
  ([wire] (decode* wire default-budget))
  ([wire budget]
   (let [budget (merge default-budget budget) counter (atom {})
         rs (:yin.k/rows wire)]
     (when-not (and (= true (:yin.k/handoff wire)) (= 3 (:yin.k/version wire))
                    (code/reg? (:yin.k/version wire)))
       (refuse! :yin.k/profile-mismatch :version [:yin.k/version]))
     (when-not (and (= #{:yin.k/handoff :yin.k/version :yin.k/root :yin.k/rows} (set (keys wire)))
                    (vector? rs) (every? #(and (vector? %) (<= 2 (count %))) rs))
       (bad! :row-shape [:yin.k/rows]))
     (spend! counter budget :rows (count rs))
     (spend! counter budget :bytes (count (seq (cbor/encode wire))))
     (let [index (into {} (map (fn [r] [(first r) r])) rs)
           root (:yin.k/root wire)]
       (when-not (= (count rs) (count index)) (bad! :duplicate-row [:yin.k/rows]))
       (when-not (contains? #{:task :act :tail :wait} (second (get index root))) (bad! :root [:yin.k/root]))
       ;; Gate children before schema, with an active path (DAG sharing is legal).
       (letfn [(gate
                 [id active]
                 (when (contains? active id) (bad! :cyclic [id]))
                 (let [row (get index id) slots (when row (subvec row 2))]
                   (when-not (and (= :task (second row)) (= 17 (count slots))
                                  (vector? (nth slots 12)) (every? keyword? (nth slots 12)))
                     (bad! :task-shape [id]))
                   (when-not (= 3 (nth slots 0)) (refuse! :yin.k/profile-mismatch :version [id 0]))
                   (when-not (= profile (nth slots 1)) (refuse! :yin.k/profile-mismatch :profile [id 1]))
                   (doseq [install (nth slots 12)]
                     (let [r (get index install)]
                       (when-not (and (= :install (second r)) (= 7 (count r))) (bad! :install-shape [install]))
                       (gate (nth r 6) (conj active id))))))]
         (when (= :task (second (get index root))) (gate root #{})))
       (doseq [[id kind & slots] rs]
         (let [schema (get row-table kind) ds (descriptors kind slots)]
           (when-not (and schema
                          (cond
                            (= schema :pending) (contains? pending-table (first slots))
                            (= :pairs (first schema)) (even? (count slots))
                            :else true)
                          (= (count ds) (count slots))) (bad! :row-shape [id]))
           (doseq [[i d x] (map vector (range) ds slots)]
             (when-not (structural? kind d x) (bad! :slot-kind [id i])))))
       (let [seen (atom #{})]
         (letfn [(walk
                   [id active depth]
                   (when (> depth (:depth budget)) (refuse! :yin.k/unsupported :resource-limit [:depth]))
                   (when (contains? active id) (bad! :cyclic [id]))
                   (when-not (contains? @seen id)
                     (let [r (get index id)]
                       (when-not r (bad! :missing-row [id]))
                       (let [kind (second r) slots (subvec r 2)]
                         (doseq [[i d x] (map vector (range) (descriptors kind slots) slots)]
                           (let [targets (cond (ref? d) [x] (refs? d) x
                                               (and (contains? #{:V :optional-V} d) (= :row (first x))) [(second x)]
                                               :else [])]
                             (doseq [target targets]
                               (when (and (contains? #{:V :optional-V} d)
                                          (not (contains? value-row-kinds (second (get index target)))))
                                 (bad! :value-reference-kind [id i]))
                               (when (and (or (ref? d) (refs? d))
                                          (not (contains? (set (rest d)) (second (get index target)))))
                                 (bad! :reference-kind [id i]))
                               (walk target (conj active id) (inc depth))))))
                       (swap! seen conj id))))]
           (walk root #{} 0)
           (when-not (= @seen (set (keys index))) (bad! :unreachable-row [:yin.k/rows]))))
       (doseq [[id & body] rs]
         (when-not (and (= :blake3 (:algorithm (jing/parse-segment-address id)))
                        (= id (jing/segment-key (vec body))))
           (refuse! :yin.k/hash-mismatch :row [id])))
       (doseq [[id kind & slots] rs :when (= :task kind)]
         (doseq [[address v] (nth slots 11)]
           (when-not (= address (jing/segment-key v))
             (refuse! :yin.k/hash-mismatch :code [id 11 address]))
           (when (code/well-formed? v) (bad! :code [id 11 address]))))
       (doseq [[_ kind & slots] rs
               [d x] (map vector (descriptors kind slots) slots)]
         (when-not (or (ref? d) (refs? d) (= :C d))
           (check-literal-markers! (if (contains? #{:V :optional-V} d)
                                     (when (= :lit (first x)) (second x)) x))))
       (doseq [[id kind & slots] rs]
         (when-not (= (vec slots) (ordered-slots kind (vec slots))) (bad! :row-order [id]))
         (when (contains? #{:map :bindings :store :parked-table :window :cursor-cells :set} kind)
           (let [keys (if (= kind :set) slots (map first (partition 2 slots)))]
             (when-not (and (= (count keys) (count (distinct keys)))
                            (= (count keys) (count (distinct (map #(bytes-key (cbor/encode %)) keys)))))
               (bad! :duplicate-key [id])))))
       (letfn [(expand-slot
                 [d x]
                 (cond (ref? d) (expand x) (refs? d) (mapv expand x)
                       (and (contains? #{:V :optional-V} d) (= :row (first x))) [:row (expand (second x))]
                       :else x))
               (expand
                 [id]
                 (let [[_ kind & slots] (get index id)]
                   (node kind (mapv expand-slot (descriptors kind slots) slots))))]
         (let [task (expand root)
               _ (check-abstract! task)
               ;; Only the lift's tie refusal (root or child) is translated; a
               ;; resource refusal propagates as itself.
               canonical (if (= :task (:kind task))
                           (try (emit task budget)
                                (catch #?(:cljd Object :clj Throwable :cljs :default) e
                                  (let [d (ex-data e)]
                                    (if (and (= :yin.k/non-portable (:yin.k/status d))
                                             (= :unresolved-cursor-cells (:yin.k/kind d)))
                                      (throw (ex-info "Row codec refused data"
                                                      (assoc d :yin.k/status :yin.k/undecodable
                                                             :yin.k/path (into [root] (:yin.k/path d)))))
                                      (throw e)))))
                           (let [[id _] (emit-node task {} {})] {:yin.k/root id}))]
           (when (and (not= false (:check-numbering budget)) (not= root (:yin.k/root canonical)))
             (bad! :noncanonical-cursor-cells [root]))
           (when (= :task (:kind task))
             (when-let [d (validate-frames task)]
               (throw (ex-info "Frame validation failed" (update d :yin.k/path #(into [root] %))))))
           task))))))


(defn decode
  "Validate and expand a row pack, including canonical cursor-cell numbering.
   For task roots, run X1 and frame validation after numbering."
  ([wire] (decode* wire default-budget))
  ([wire budget] (decode* wire (dissoc budget :check-numbering))))


(defn canonical-under
  "Test-only §5.5 re-emission under an explicit stream correspondence.
   Missing marker coverage fails; neither traversal order nor original
   cursor-cell numbering chooses a logical identity. Accepts a carrier."
  [wire correspondence]
  (let [task (decode* wire (assoc default-budget :check-numbering false))]
    (letfn [(rewrite-slot
              [d x]
              (cond (ref? d) (rewrite x) (refs? d) (mapv rewrite x)
                    (and (contains? #{:V :optional-V} d) (= :row (first x))) [:row (rewrite (second x))]
                    :else x))
            (rewrite
              [n]
              (let [{:keys [kind slots]} n]
                (if (= :stream kind)
                  (let [id (first slots)]
                    (when-not (contains? correspondence id) (bad! :missing-stream-correspondence [id]))
                    (node :stream [(get correspondence id) (get correspondence id)]))
                  (node kind (mapv rewrite-slot (descriptors kind slots) slots)))))]
      (emit (rewrite task)))))


(defn- value-node
  [x]
  (check-literal-markers! x)
  [:lit x])


(defn- decoded-value
  [v]
  (case (first v)
    :row {:yin.k/row (second v)}
    :cursor-cell {:yin.k/cursor-cell (second v)}
    :lit (if (marker-shaped? (second v)) (refuse! :yin.k/non-portable :marker []) (second v))))


(defn- keyed-node
  [kind m encode]
  (node kind (mapcat (fn [[k v]] [k (encode v)]) m)))


(defn- env-node
  [e encode]
  (node :env [(keyed-node :bindings (:yin.k/bindings e) encode) (:yin.k/store-of e)]))


(defn- kframe-node
  [f encode]
  (node :kframe [(:yin.k/segment f) (:yin.k/pc f) (env-node (:yin.k/env f) encode)
                 (keyed-node :window (:yin.k/window f) encode) (:yin.k/rd f)]))


(def pending-keys
  {:next [:yin.k/cursor-cell]
   :put [:yin.k/stream :yin.k/value :yin.k/op-id]
   :ffi [:yin.k/cursor-cell :yin.k/call-id :yin.k/op]
   :ffi-request [:yin.k/call-id :yin.k/envelope :yin.k/request-stream :yin.k/response-stream :yin.k/response-cursor-cell :yin.k/op-id]
   :link-request [:yin.k/link-id :yin.k/name :yin.k/request-stream :yin.k/response-stream :yin.k/cursor-cell :yin.k/envelope :yin.k/op-id]
   :link-response [:yin.k/link-id :yin.k/name :yin.k/request-stream :yin.k/response-stream :yin.k/cursor-cell]
   :install [:yin.k/name]})


(defn frame->rows
  "Encode a keyed frame to a frame-rooted version-3 row pack. Values are
   plain literals by default. An explicit value encoder classifies portable
   references as [:row abstract-row] or [:cursor-cell id], distinct from guest maps.
   This reshapes; frame/defect owns semantic validity."
  ([f] (frame->rows f value-node))
  ([f encode]
   (let [k (node :k (mapv #(kframe-node % encode) (:yin.k/k f)))
         state (if (= :act (:yin.k/state f))
                 (node :act [(:yin.k/segment f) (:yin.k/pc f) (env-node (:yin.k/env f) encode)
                             (keyed-node :window (:yin.k/window f) encode) k (get-in f [:yin.k/deliver :yin.k/rd])])
                 (node :tail [(:yin.k/segment f) (:yin.k/site f) k]))
         pending (:yin.k/pending f)
         reason (:yin.k/reason pending)
         root (if (contains? f :yin.k/pending)
                (node :wait [state (node :pending
                                         (into [reason]
                                               (map (fn [key d]
                                                      (let [x (get pending key)]
                                                        (if (= :V d) (encode x) x)))
                                                    (get pending-keys reason) (get pending-table reason))))])
                state)
         [id rows] (emit-node root {} {})]
     {:yin.k/handoff true :yin.k/version 3 :yin.k/root id :yin.k/rows (vec (vals rows))})))


(defn- decode-keyed
  [n decode-value]
  (into {} (map (fn [[k v]] [k (decode-value v)])) (partition 2 (:slots n))))


(defn- decode-env
  [n decode-value]
  (let [[bindings store-of] (:slots n)]
    (cond-> {:yin.k/bindings (decode-keyed bindings decode-value)}
      (some? store-of) (assoc :yin.k/store-of store-of))))


(defn- decode-kframe
  [n decode-value]
  (let [[seg pc e w rd] (:slots n)]
    {:yin.k/frame-type :return :yin.k/segment seg :yin.k/pc pc
     :yin.k/env (decode-env e decode-value) :yin.k/window (decode-keyed w decode-value) :yin.k/rd rd}))


(defn- decode-frame
  [n decode-value]
  (if (= :wait (:kind n))
    (let [[state pending] (:slots n) [reason & slots] (:slots pending)]
      (assoc (decode-frame state decode-value) :yin.k/pending
             (into {:yin.k/reason reason}
                   (map (fn [key d x] [key (if (= :V d) (decode-value x) x)])
                        (get pending-keys reason) (get pending-table reason) slots))))
    (let [act? (= :act (:kind n)) [seg pc e w k rd] (:slots n)
          k (if act? k e)]
      (cond-> {:yin.k/state (:kind n) :yin.k/segment seg
               :yin.k/deliver (if act? {:yin.k/deliver :rd :yin.k/rd rd} {:yin.k/deliver :return})
               :yin.k/k (mapv #(decode-kframe % decode-value) (:slots k))}
        act? (assoc :yin.k/pc pc :yin.k/env (decode-env e decode-value) :yin.k/window (decode-keyed w decode-value))
        (not act?) (assoc :yin.k/site pc)))))


(defn rows->frame
  "Validate row syntax and hashes, then reshape to the keyed frame.
   Semantic code/context rules remain frame/defect's responsibility.
   The default decoder produces in-band row/cursor-cell markers that the default
   encoder refuses. frame->rows after rows->frame is identity only when
   both sides are given a classifier for those values."
  ([wire] (rows->frame wire decoded-value))
  ([wire decode-value] (decode-frame (decode wire) decode-value)))


(defn validate-frames
  "Carrier step 7, after decode's canonical numbering. Judge each task's
   X1 first, then waits, parked states, and all reified values. Return the
   first defect with the abstract row path, or nil."
  [task]
  (let [slots (:slots task) code-map (nth slots 11)
        free-env (decode-keyed (nth slots 5) decoded-value)
        waits (nth slots 13)
        ffi-ids (into #{} (keep (fn [w]
                                  (let [[_ p] (:slots w) [reason & xs] (:slots p)]
                                    (case reason :ffi (second xs) :ffi-request (first xs) nil)))) waits)
        ctx {:free-env free-env :ffi-call-ids ffi-ids}]
    (letfn [(judge
              [n context context-map path]
              (when-let [d (frame/defect (decode-frame n decoded-value) code-map context context-map)]
                (-> d (assoc :yin.k/status :yin.k/undecodable)
                    (update :yin.k/path #(into path %)))))
            (captures
              [n path]
              (if (= :reified (:kind n))
                (or (judge (first (:slots n)) :captured ctx path)
                    (captures (first (:slots n)) (conj path 0)))
                (some (fn [[i d x]]
                        (cond
                          (and (ref? d) (= :task (:kind x))) (validate-frames x)
                          (ref? d) (captures x (conj path i))
                          (refs? d) (some (fn [[j child]] (captures child (conj path i j))) (map-indexed vector x))
                          (and (contains? #{:V :optional-V} d) (= :row (first x))) (captures (second x) (conj path i))
                          :else nil))
                      (map vector (range) (descriptors (:kind n) (:slots n)) (:slots n)))))]
      (or
        (when-let [d (frame/task-defect ctx)] (assoc d :yin.k/status :yin.k/undecodable))
        (some (fn [[i w]] (judge w :wait ctx [:frames i])) (map-indexed vector waits))
        (some (fn [[p state]] (judge state :parked (assoc ctx :parked-key p) [:parked p]))
              (partition 2 (:slots (nth slots 8))))
        (captures task [])))))


;; =============================================================================
;; Task transport (continuation design §7): lift-task and lower-task
;; =============================================================================
;;
;; The lift walks a quiescent semantic-register machine into the abstract
;; task the codec above emits: waits, parked records, the store, module
;; stores, the free environment, the result and install children, every
;; value classified as [:lit x], [:row n] or [:cursor-cell source]. A
;; cursor cell's source is the resource id of a cursor reference, or
;; [response-resource position] for a link wait's kept response cursor,
;; so one resource reached twice is one cursor cell. The lower decodes the
;; carrier (gate to frames, §3.3), checks pendings, dependencies and
;; custody for every task of the tree, and only then attaches streams and
;; restores state. Restoration delivers nothing, runs nothing.

(defn- outcome!
  [status kind data]
  (throw (ex-info "Task transport refused"
                  (merge data (cond-> {:yin.k/status status}
                                (some? kind) (assoc :yin.k/kind kind))))))


(defn- unliftable-hold
  "The first hold of m with no wire pending (UCF 7.4.1, 7.4.3), or nil."
  [m]
  (let [waits (:wait-set m)
        cells (filter map? (vals (:resources m)))
        held? #(and (map? %) (contains? % :yin.k/held))]
    (cond
      (some #(= :observe (:reason %)) waits) :observe
      (or (some held? waits) (some held? cells)) :held
      (some :yin.k/unminted cells) :unminted-cursor
      (seq (:yin.k/closes m)) :pending-close
      (some #(and (= :link-request (:reason %)) (not (contains? % :cursor))) waits)
      :link-cursor-not-installed
      :else nil)))


(defn- stream-row!
  "The :stream row of resource sid, served once per resource."
  [ctx sid]
  (or (get-in @(:acc ctx) [:streams sid])
      (let [h (get (:resources (:m ctx)) sid)]
        (when (nil? h)
          (outcome! :yin.k/non-portable :forged-resource-reference {:yin.k/hint sid}))
        (let [marker (remote/lift-marker (:serve! ctx) h)]
          (when (nil? marker) (outcome! :yin.k/unsatisfied nil {}))
          (let [n (node :stream [(:dao.stream/identity marker) (:dao.stream/descriptor marker)])]
            (swap! (:acc ctx) assoc-in [:streams sid] n)
            n)))))


(defn- cell!
  [ctx source sid position]
  (when-not (contains? (:cells @(:acc ctx)) source)
    (let [stream (stream-row! ctx sid)]
      (swap! (:acc ctx) assoc-in [:cells source] (node :cursor-cell-body [stream position]))))
  source)


(defn- cell-for!
  [ctx cid]
  (let [cell (get (:resources (:m ctx)) cid)]
    (when-not (and (map? cell) (contains? cell :stream-id))
      (outcome! :yin.k/non-portable :forged-resource-reference {:yin.k/hint cid}))
    (cell! ctx cid (:stream-id cell) (:cursor cell))))


(defn- address!
  [ctx seg]
  (if-some [a (get-in (:m ctx) [:code seg :address])]
    (do (swap! (:acc ctx) update :segments conj a) a)
    (outcome! :yin.k/non-portable :unaddressed-segment {:yin.k/hint seg})))


(defn- scalar-literal?
  [x]
  (or (nil? x) (boolean? x) (string? x) (keyword? x) (symbol? x) (number? x)
      (and (not (cbor/byte-payload? x)) (cbor/numeric? x))))


(defn- primitive-name
  [ctx f]
  (let [m (:m ctx)
        n (vm/name-of (:primitives m) (or (:primitive-canonical-names m) {}) f)
        profile (when (symbol? n) (vm/profile-of (:primitives m) n))]
    (cond
      (nil? n) (outcome! :yin.k/non-portable :unnamed-function {})
      (not (symbol? n)) (outcome! :yin.k/non-portable :ambiguous-primitive {})
      (= :host (:yin.k/class profile)) (outcome! :yin.k/non-portable :host-state-primitive {:yin.k/name n})
      :else n)))


(declare lift-value state-node)


(defn- collection-value
  "A guest collection: a literal when no member is a reference."
  [ctx kind members original]
  (let [vs (mapv #(lift-value ctx %) members)]
    (if (every? #(= :lit (first %)) vs) [:lit original] [:row (node kind vs)])))


(defn- bindings-node
  [ctx e]
  (node :bindings (mapcat (fn [[k v]] [k (lift-value ctx v)]) e)))


(defn- context!
  "Record that segment a can run from pc at under environment e: a
   closure's entry, an :act's or a return frame's pc (§7.1's discharge)."
  [ctx a at e]
  (swap! (:acc ctx) update :contexts conj {:a a :at at :env (or e {})}))


(defn- store-of!
  [ctx e]
  (let [store-of (engine/env-store-of e)]
    (when (some? store-of) (swap! (:acc ctx) update :stores conj store-of))
    store-of))


(defn- lift-env-node
  [ctx e]
  (let [e (or e {})]
    (node :env [(bindings-node ctx (engine/without-store-of e)) (store-of! ctx e)])))


(defn- closure-node
  [ctx c]
  (let [e (or (:env c) {})
        a (address! ctx (:segment c))]
    (context! ctx a (:entry c) e)
    (node :closure [a (:entry c) (vec (:params c))
                    (bindings-node ctx (engine/without-store-of e)) (store-of! ctx e)])))


(defn- lift-value
  "One machine value as a carrier value: [:lit x], [:row n] or
   [:cursor-cell source]. A reference is authenticated before it is
   encoded; a foreign value, a heap cell and a host object refuse."
  [ctx x]
  (let [m (:m ctx)]
    (cond
      (values/host-typed? x)
      (cond
        (not (values/owned-by? x (:owner m)))
        (outcome! :yin.k/non-portable :foreign-value {:yin.k/hint (values/kind-of x)})
        (values/closure? x) [:row (closure-node ctx (values/payload x))]
        (values/continuation? x) [:row (node :reified [(state-node ctx (values/payload x))])]
        :else (outcome! :yin.k/non-portable :non-canonicalizable {:yin.k/hint (values/kind-of x)}))
      (fn? x) [:row (node :prim [(primitive-name ctx x)])]
      (map? x)
      (case (:type x)
        :stream-ref (if (engine/authentic-ref? m :stream-ref x)
                      [:row (stream-row! ctx (:id x))]
                      (outcome! :yin.k/non-portable :forged-resource-reference {:yin.k/hint :stream-ref}))
        :cursor-ref (if (engine/authentic-ref? m :cursor-ref x)
                      [:cursor-cell (cell-for! ctx (:id x))]
                      (outcome! :yin.k/non-portable :forged-resource-reference {:yin.k/hint :cursor-ref}))
        :parked-continuation (if (contains? (:parked m) (:id x))
                               [:row (node :parked-ref [(:id x)])]
                               (outcome! :yin.k/non-portable :inconsistent-state {:yin.k/hint :parked-reference}))
        :cell-ref (outcome! :yin.k/non-portable :cell {:yin.k/hint (:id x)})
        (collection-value ctx :map (mapcat identity x) x))
      (vector? x) (collection-value ctx :vec x x)
      (set? x) (collection-value ctx :set (vec x) x)
      (seq? x) (collection-value ctx :list (vec x) x)
      (scalar-literal? x) [:lit x]
      :else (outcome! :yin.k/non-portable :host-object {}))))


(defn- window-node
  [ctx w]
  (node :window (mapcat (fn [[r v]] [r (lift-value ctx v)]) w)))


(defn- k-node
  [ctx k]
  (node :k (mapv (fn [f]
                   (let [a (address! ctx (:segment f))]
                     (context! ctx a (:pc f) (:env f))
                     (node :kframe [a (:pc f) (lift-env-node ctx (:env f))
                                    (window-node ctx (:window f)) (:rd f)])))
                 (or k []))))


(defn- state-node
  "An evaluator payload (wait entry, parked record, reified payload) as
   its :act or :tail row; engine fields are the pending's."
  [ctx p]
  (if (contains? p :site)
    ;; a tail state resumes through its K: its own segment never runs again
    (node :tail [(address! ctx (:segment p)) (:site p) (k-node ctx (:k p))])
    (let [a (address! ctx (:segment p))]
      (context! ctx a (:pc p) (:env p))
      (node :act [a (:pc p) (lift-env-node ctx (:env p))
                  (window-node ctx (:window p)) (k-node ctx (:k p)) (get-in p [:deliver :rd])]))))


(defn- pending-node
  "The observed wait's pending (draft §5.3): the reason is the entry's,
   never the site's static kind."
  [ctx e]
  (let [m (:m ctx)]
    (case (:reason e)
      :next (if (contains? e :call-id)
              (node :pending [:ffi (cell-for! ctx (get-in e [:cursor-ref :id])) (:call-id e) (:op e)])
              (node :pending [:next (cell-for! ctx (get-in e [:cursor-ref :id]))]))
      :put (if (:request-sent e)
             (let [carried? (and (:response-cursor e) (:response-stream e))
                   cursor (if carried? (:response-cursor e) vm/call-out-cursor-key)
                   response (if carried? (:response-stream e) vm/call-out-stream-key)]
               (when-not (get (:resources m) cursor) (outcome! :yin.k/unsatisfied nil {}))
               (node :pending [:ffi-request (:call-id e) (lift-value ctx (:datom e)) (stream-row! ctx (:stream-id e))
                               (stream-row! ctx response) (cell-for! ctx cursor) (:op-id e)]))
             (do (when-not (contains? e :datom) (outcome! :yin.k/non-portable :incomplete-write {}))
                 (node :pending [:put (stream-row! ctx (:stream-id e)) (lift-value ctx (:datom e)) (:op-id e)])))
      (:link-request :link-response)
      (let [c (cell! ctx [(:response e) (:cursor e)] (:response e) (:cursor e))
            base [(:reason e) (:link-id e) (:name e) (stream-row! ctx (:request e)) (stream-row! ctx (:response e)) c]]
        (node :pending (if (= :link-request (:reason e)) (conj base (:envelope e) (:op-id e)) base)))
      :install (do (when-not (contains? (:installs m) (:name e))
                     (outcome! :yin.k/non-portable :incomplete-install {:yin.k/name (:name e)}))
                   (node :pending [:install (:name e)]))
      (outcome! :yin.k/non-portable :reason-mismatch {:yin.k/reason (:reason e)}))))


(defn- store-node
  [ctx s]
  (node :store (mapcat (fn [[k v]]
                         (when-not (= :lit (first (lift-value ctx k)))
                           (outcome! :yin.k/non-portable :store-key {}))
                         [k (lift-value ctx v)])
                       s)))


(defn- module-store-nodes
  "Every module store an environment or closure reaches, transitively."
  [ctx]
  (loop [done {}]
    (if-let [mid (first (sort-by str (remove #(contains? done %) (:stores @(:acc ctx)))))]
      (let [stores (:module-stores (:m ctx))]
        (when-not (contains? stores mid)
          (outcome! :yin.k/non-portable :missing-module-store {:yin.k/hint mid}))
        (recur (assoc done mid (node :module-store [mid (store-node ctx (get stores mid))]))))
      (mapv val (sort-by #(str (key %)) done)))))


(defn- installing-names
  [m]
  (into #{} (mapcat (fn [[mn inst]]
                      (map #(symbol (name mn) (name %))
                           (get-in inst [:response :manifest :yin.module/exports]))))
        (:installs m)))


(defn- encloses?
  "Body i of bodies is body j or one of j's ancestors."
  [bodies i j]
  (loop [j j]
    (cond
      (= i j) true
      (nil? (:owner (nth bodies j))) false
      :else (recur (code/body-index bodies (:owner (nth bodies j)))))))


(defn- discharge!
  "The dependency closure over the v4 vectors (§7.1), after the walk that
   found the contexts: every free occurrence is answered, in each carried
   context whose body encloses it, in resolve-var's order: that context's
   E, then the one store engine/active-store selects for it (the root
   store, or the module store its store-of names; both travel), then a
   primitive, an installing module's export, the module registry. Else
   the lift is :yin.k/unsatisfied, discovery :incomplete."
  [ctx code]
  (let [m (:m ctx)
        installing (installing-names m)
        answered? (fn [sym e]
                    (let [store-of (engine/env-store-of e)
                          store (if (some? store-of) (get (:module-stores m) store-of {}) (:store m))]
                      (or (vm/reserved-name? sym) (contains? e sym) (contains? store sym)
                          (let [profile (vm/profile-of (:primitives m) sym)]
                            (when (= :host (:yin.k/class profile))
                              (outcome! :yin.k/non-portable :host-state-primitive {:yin.k/name sym}))
                            (or (some? profile) (contains? installing sym)
                                (and (some? (namespace sym))
                                     (some? (module/resolve-module (:modules m)
                                                                   (symbol (str (namespace sym) "." (name sym)))))))))))
        scanned (into {} (map (fn [[a v]] [a [(code/parse v) (scan/free-occurrences v)]])) code)
        missing (into #{}
                      (mapcat (fn [{:keys [a at env]}]
                                (let [[bodies occurrences] (get scanned a)
                                      home (code/body-index bodies at)]
                                  (keep (fn [o]
                                          (when (and (encloses? bodies home (code/body-index bodies (:at o)))
                                                     (not (answered? (:name o) env)))
                                            (:name o)))
                                        occurrences))))
                      (:contexts @(:acc ctx)))]
    (when (seq missing)
      (outcome! :yin.k/unsatisfied nil {:yin.k/discovery :incomplete :yin.k/missing {:obligations missing}}))))


(declare lift-task*)


(defn- install-node
  [ctx mn inst opts]
  (let [{:keys [phase parent response]} inst]
    (when-not (contains? #{:running :parked} phase)
      (outcome! :yin.k/non-portable :incomplete-install {:yin.k/name mn :yin.k/phase phase}))
    (when-not (try (cbor/encode response) true
                   (catch #?(:cljd Object :clj Throwable :cljs :default) _e false))
      (outcome! :yin.k/non-portable :install-response {:yin.k/name mn}))
    (node :install [mn phase parent response
                    (lift-task* (:vm inst) (:serve! ctx) (dissoc opts :free-env) :child)])))


(defn- lift-task*
  [m serve! opts role]
  (when (seq (:ready-queue m))
    (outcome! :yin.k/not-quiescent nil {:yin.k/ready (count (:ready-queue m))}))
  (when-some [h (unliftable-hold m)]
    (outcome! :yin.k/non-portable :reason-mismatch {:yin.k/hold h}))
  (when (some? (:control m))
    (outcome! :yin.k/not-at-safepoint nil {:yin.k/pc (get-in m [:control :pc])}))
  (let [value (:value m)
        active? (and (map? value) (= :parked-continuation (:type value)) (contains? (:parked m) (:id value)))
        kind (cond active? :parked
                   (seq (:wait-set m)) :blocked
                   (engine/halted-with-empty-queue? m) :halted
                   :else (outcome! :yin.k/not-at-safepoint nil {}))
        ;; §6.3: the explicit argument overrides the kernel's retained field
        free-env (cond (some? (:free-env opts)) (:free-env opts)
                       (some? (:free-env m)) (:free-env m)
                       :else (outcome! :yin.k/non-portable :missing-free-env {}))
        ctx {:m m :serve! serve! :acc (atom {:streams {} :cells {} :stores #{} :segments #{} :contexts #{}})}
        free-node (bindings-node ctx free-env)
        waits (mapv (fn [e] (node :wait [(state-node ctx e) (pending-node ctx e)])) (:wait-set m))
        parked (node :parked-table (mapcat (fn [[p rec]] [p (state-node ctx rec)]) (:parked m)))
        store (store-node ctx (:store m))
        result (when (= :halted kind) (lift-value ctx value))
        installs (mapv (fn [[mn inst]] (install-node ctx mn inst opts)) (sort-by #(str (key %)) (:installs m)))
        module-stores (module-store-nodes ctx)
        acc @(:acc ctx)
        segments (:segments acc)
        code (into {} (map (fn [a]
                             (if-some [v (get-in m [:code (get (:code-aliases m) a) :vector])]
                               [a v]
                               (outcome! :yin.k/unsatisfied nil {:yin.k/segment a}))))
                   segments)
        _ (discharge! ctx code)
        index (or (:source-index opts) {})
        missing-trees (into #{} (remove #(seq (get index %))) segments)
        _ (when (seq missing-trees)
            (outcome! :yin.k/unsatisfied nil {:yin.k/discovery :incomplete :yin.k/missing {:yin.k/trees missing-trees}}))
        requires {:yin.k/cursor-profiles (if (seq (:cells acc)) remote/cursor-profiles #{})
                  :yin.k/segments segments
                  :yin.k/trees (select-keys index segments)}
        task (node :task [3 profile kind role (or (:id-counter m) 0) free-node store module-stores parked
                          (node :cursor-cells (mapcat identity (:cells acc))) requires code installs waits
                          (when (= :parked kind) (:id value)) result nil])]
    ;; §3.3: a frame the evaluator itself minted that fails is inconsistent
    (when-let [d (validate-frames task)]
      (outcome! :yin.k/non-portable :inconsistent-state {:yin.k/defect d}))
    task))


(defn lift-task
  "The lift of §7.1: quiescent semantic-register machine m as a version-3
   carrier, or a refusal thrown as ex-data {:yin.k/status :yin.k/kind}.
   serve! is the exporter's remote-table entry (yin.vm.ucf.remote).
   opts: :free-env, the task's initial free environment (default: the
   kernel's retained :free-env field; the explicit value overrides it);
   :source-index, {A #{tree-address}} the composition knows (§6.1), which
   must cover every carried segment; :budget, the numbering budget."
  ([m serve!] (lift-task m serve! {}))
  ([m serve! opts]
   (let [opts (or opts {})]
     (emit (lift-task* m serve! opts :root) (merge default-budget (:budget opts))))))


(def ^:private fixed-resource-keys
  #{vm/call-in-stream-key vm/call-out-stream-key vm/call-out-cursor-key
    module/link-request-resource module/link-response-resource})


(defn- fresh-key!
  [used]
  (loop [n (inc (count @used))]
    (let [k (keyword "yin.k" (str "k-" n))]
      (if (contains? @used k) (recur (inc n)) (do (swap! used conj k) k)))))


(defn- rows-of
  "Every row of kind under n; install children too unless own-only."
  [n kind own-only?]
  (let [out (atom [])]
    (letfn [(visit
              [n]
              (when (= kind (:kind n)) (swap! out conj n))
              (doseq [[d x] (map vector (descriptors (:kind n) (:slots n)) (:slots n))]
                (cond
                  (and (ref? d) (= :task (:kind x))) (when-not own-only? (visit x))
                  (ref? d) (visit x)
                  (refs? d) (run! visit x)
                  (and (value-slot? d) (vector? x) (= :row (first x))) (visit (second x))
                  :else nil)))]
      (visit n)
      @out)))


(defn- task-tree
  "[task role] for the root and every install child, pre-order."
  [task role]
  (into [[task role]] (mapcat #(task-tree (nth (:slots %) 4) :child)) (nth (:slots task) 12)))


(defn- check-stores!
  "Step 7's task context, beside X1: no root or module store key is a
   reserved name (Rule R), refused here rather than by restore's store-put."
  [_recv task _role]
  (let [[_ _ _ _ _ _ store module-stores] (:slots task)]
    (doseq [s (cons store (map #(second (:slots %)) module-stores))
            k (take-nth 2 (:slots s))]
      (when (vm/reserved-name? k)
        (outcome! :yin.k/undecodable :reserved-name {:yin.k/role :store-key :yin.k/name k})))))


(defn- attaches?
  "The receiving kernel admits vector v (its loader's validation), under
   address a when a is given."
  [recv v a]
  (try (let [r (module/attach-module recv v)] (or (nil? a) (contains? (:code-aliases r) a)))
       (catch #?(:cljd Object :clj Throwable :cljs :default) _e false)))


(defn- check-install!
  "One install record: a phase the engine holds a carried child in, the
   retained response link-install reads (status, manifest naming this
   module, exports) with the image spawn-child loads, and a child that
   is blocked: the engine links a halted child in the sweep it halts in."
  [recv inst]
  (let [[mn phase _ response child] (:slots inst)
        manifest (when (map? response) (:manifest response))
        child-kind (nth (:slots child) 2)]
    (when-not (contains? #{:running :parked} phase)
      (outcome! :yin.k/undecodable :install-phase {:yin.k/name mn :yin.k/phase phase}))
    (when-not (and (map? response) (= :ok (:status response)) (map? manifest)
                   (= mn (:yin.module/name manifest))
                   (coll? (:yin.module/exports manifest)) (every? symbol? (:yin.module/exports manifest))
                   (map? (:image response)) (attaches? recv (:value (:image response)) nil))
      (outcome! :yin.k/undecodable :install-response {:yin.k/name mn}))
    (when-not (= :blocked child-kind)
      (outcome! :yin.k/undecodable :install-child {:yin.k/name mn :yin.k/task-kind child-kind}))))


(defn- check-pendings!
  "Step 8, pending and operations: role, kind, parked id, every :install
   pending names a carried install, every install record."
  [recv task role]
  (let [[_ _ kind role' _ _ _ _ parked _ _ _ installs waits parked-id] (:slots task)]
    (when-not (= role role') (outcome! :yin.k/undecodable :role {:yin.k/role role'}))
    (let [names (into #{} (map #(first (:slots %))) installs)]
      (doseq [w waits]
        (let [[reason n] (:slots (second (:slots w)))]
          (when (and (= :install reason) (not (contains? names n)))
            (outcome! :yin.k/undecodable :incomplete-install {:yin.k/name n})))))
    (case kind
      :halted (when (seq waits) (outcome! :yin.k/undecodable :kind {:yin.k/task-kind kind}))
      :blocked (when (empty? waits) (outcome! :yin.k/undecodable :kind {:yin.k/task-kind kind}))
      :parked (when-not (some #(= parked-id %) (take-nth 2 (:slots parked)))
                (outcome! :yin.k/undecodable :parked-id {}))
      (outcome! :yin.k/undecodable :kind {:yin.k/task-kind kind}))
    (run! #(check-install! recv %) installs)))


(defn- check-closure!
  "A closure row names a :closure tuple of its carried vector: the entry
   (:closure-origin) and that tuple's binders (:closure-binders), as
   lower-closure will demand."
  [code c]
  (let [[a entry params] (:slots c)
        v (get code a)
        _ (when (nil? v) (outcome! :yin.k/undecodable :segment {:yin.k/segment a}))
        tuple (first (filter #(and (= :closure (nth % 0)) (= entry (nth % 3))) v))]
    (cond
      (nil? tuple) (outcome! :yin.k/undecodable :closure-origin {:yin.k/segment a :yin.k/entry entry})
      (not= params (nth tuple 2)) (outcome! :yin.k/undecodable :closure-binders {:yin.k/segment a :yin.k/entry entry})
      :else nil)))


(defn- check-dependencies!
  "Step 9, dependency closure: requires and code agree, every segment has
   its trees (§6.1) and is a vector the receiver's kernel admits under its
   A, every closure names a closure of its code, every :prim is the
   receiver's."
  [recv task _role]
  (let [[_ _ _ _ _ _ _ _ _ _ requires code] (:slots task)]
    (when-not (and (map? requires) (= (set (:yin.k/segments requires)) (set (keys code))))
      (outcome! :yin.k/undecodable :requires {}))
    (let [trees (:yin.k/trees requires)
          missing (into #{} (remove #(seq (get trees %))) (keys code))]
      (when (seq missing)
        (outcome! :yin.k/unsatisfied nil {:yin.k/discovery :incomplete :yin.k/missing {:yin.k/trees missing}})))
    (doseq [[a v] (sort-by #(str (key %)) code)]
      (when-not (attaches? recv v a)
        (outcome! :yin.k/undecodable :segment {:yin.k/segment a})))
    (run! #(check-closure! code %) (rows-of task :closure true))
    (doseq [p (rows-of task :prim true)]
      (when-not (contains? (:primitives recv) (first (:slots p)))
        (outcome! :yin.k/unsatisfied nil {:yin.k/name (first (:slots p))})))))


(defn- check-custody!
  "Step 10: phase 6 carries fork custody only."
  [_recv task _role]
  (when (some? (nth (:slots task) 16))
    (outcome! :yin.k/unsupported :custody {})))


(defn- attach-streams!
  "Step 11: one attach per distinct stream identity of the whole tree."
  [attach! task]
  (reduce (fn [acc s]
            (let [[identity descriptor] (:slots s)]
              (if (contains? acc identity)
                acc
                (if-some [h (remote/lower-marker attach! {:dao.stream/descriptor descriptor})]
                  (assoc acc identity h)
                  (outcome! :yin.k/unsatisfied nil {:dao.stream/identity identity})))))
          {} (rows-of task :stream false)))


(defn- spawn-child
  "A fresh install child of recv's composition (the engine's spawn
   discipline): answers [child recv']."
  [recv response mn]
  (let [n (or (:origins recv) 0)
        origin (keyword (str (name (or (:origin recv) :t0)) "." n))
        registry (reduce (fn [r [m e]] (module/assoc-module r m (dissoc e :bindings)))
                         (:modules recv) (module/module-entries (:modules recv)))
        source (:secret-source recv)]
    [(module/spawn-module recv (:value (:image response))
                          {:modules registry :origin origin
                           :ancestry (conj (vec (:ancestry recv)) mn)
                           :capability-secret (when (fn? source) (source origin))})
     (assoc recv :origins (inc n))]))


(defn- restore-task
  "Step 12 for one task: code by A, fresh resource keys, values re-sealed
   under recv, waits as engine entries, parked records, installs."
  [recv task handles]
  (let [[_ _ kind _ id-counter free-env store module-stores parked census _ code installs waits parked-id result]
        (:slots task)
        base (reduce (fn [r [_ v]] (module/attach-module r v))
                     (assoc recv :code {} :code-aliases {} :program nil)
                     (sort-by #(str (key %)) code))
        aliases (:code-aliases base)
        used (atom (into fixed-resource-keys (keys (:resources base))))
        identities (sort-by str (distinct (map #(first (:slots %)) (rows-of task :stream true))))
        stream-keys (into {} (map (fn [identity] [identity (fresh-key! used)])) identities)
        pairs (mapv vec (partition 2 (:slots census)))
        cell-keys (into {} (map (fn [[c _]] [c (fresh-key! used)])) pairs)
        bodies (into {} pairs)
        stream-key #(get stream-keys (first (:slots %)))
        parked-index (into {} (map vec) (partition 2 (:slots parked)))
        cache (atom {})
        resolving (atom #{})]
    (letfn [(value
              [v]
              (case (first v)
                :lit (second v)
                :cursor-cell (engine/issue-ref base :cursor-ref (get cell-keys (second v)))
                :row (row (second v))))
            (row
              [n]
              (let [ss (:slots n)]
                (case (:kind n)
                  :vec (mapv value ss)
                  :list (apply list (map value ss))
                  :set (into #{} (map value) ss)
                  :map (into {} (map (fn [[k x]] [(value k) (value x)])) (partition 2 ss))
                  :prim (vm/primitive-function (get (:primitives base) (first ss)))
                  :stream (engine/issue-ref base :stream-ref (stream-key n))
                  :closure (let [[a entry params bindings store-of] ss]
                             (module/lower-closure base
                                                   (cond-> {:yin.k/binding :named
                                                            :yin.k/format :yin.semantic-register/code
                                                            :yin.k/segment a :yin.k/entry entry :yin.k/params params
                                                            :yin.k/env (keyed bindings)}
                                                     (some? store-of) (assoc :yin.k/store-of store-of))
                                                   identity))
                  :reified (values/continuation (:owner base) (assoc (payload (first ss)) :type :reified-continuation))
                  :parked-ref (parked-record (first ss)))))
            (keyed
              [n]
              (into {} (map (fn [[k x]] [k (value x)])) (partition 2 (:slots n))))
            (env
              [n]
              (let [[b store-of] (:slots n)]
                (cond-> (keyed b) (some? store-of) (assoc engine/store-of-key store-of))))
            (k-of
              [n]
              (mapv (fn [f]
                      (let [[a pc e w rd] (:slots f)]
                        {:type :return :segment (get aliases a) :pc pc :env (env e) :window (keyed w) :rd rd}))
                    (:slots n)))
            (payload
              [n]
              (if (= :tail (:kind n))
                (let [[a site k] (:slots n)]
                  {:segment (get aliases a) :site site :k (k-of k) :deliver {:deliver :return}})
                (let [[a pc e w k rd] (:slots n)]
                  {:segment (get aliases a) :pc pc :env (env e) :window (keyed w) :k (k-of k)
                   :deliver {:deliver :rd :rd rd}})))
            (parked-record
              [p]
              (or (get @cache p)
                  (do (when (contains? @resolving p)
                        (outcome! :yin.k/non-portable :non-canonicalizable {:yin.k/hint :parked-cycle}))
                      (swap! resolving conj p)
                      (let [r (assoc (payload (get parked-index p)) :type :parked-continuation :id p)]
                        (swap! cache assoc p r)
                        (swap! resolving disj p)
                        r))))
            (cell-stream
              [c]
              (stream-key (first (:slots (get bodies c)))))
            (entry
              [w]
              (let [[state pending] (:slots w)
                    [reason & xs] (:slots pending)
                    p (payload state)]
                (case reason
                  :next (assoc p :reason :next :stream-id (cell-stream (first xs))
                               :cursor-ref {:type :cursor-ref :id (get cell-keys (first xs))})
                  :ffi (let [[c call-id op] xs]
                         (cond-> (assoc p :reason :next :stream-id (cell-stream c) :call-id call-id
                                        :cursor-ref {:type :cursor-ref :id (get cell-keys c)})
                           (some? op) (assoc :op op)))
                  :put (let [[s v op-id] xs]
                         (cond-> (assoc p :reason :put :stream-id (stream-key s) :datom (value v))
                           (some? op-id) (assoc :op-id op-id)))
                  :ffi-request (let [[call-id envelope req resp c op-id] xs
                                     datom (value envelope)]
                                 (cond-> (assoc p :reason :put :stream-id (stream-key req) :datom datom
                                                :request-sent true :call-id call-id :op (apply2/request-op datom)
                                                :response-cursor (get cell-keys c) :response-stream (stream-key resp))
                                   (some? op-id) (assoc :op-id op-id)))
                  (:link-request :link-response)
                  (let [[link-id mn _ _ c envelope op-id] xs]
                    (cond-> (assoc p :reason reason :link-id link-id :name mn
                                   :request module/link-request-resource :response module/link-response-resource
                                   :cursor (second (:slots (get bodies c))))
                      (= :link-request reason) (assoc :envelope envelope)
                      (some? op-id) (assoc :op-id op-id)))
                  :install (assoc p :reason :install :name (first xs)))))]
      (let [store' (reduce (fn [s [k v]] (engine/store-put s k (value v))) {} (partition 2 (:slots store)))
            stores (into {} (map (fn [ms]
                                   (let [[mid s] (:slots ms)]
                                     [mid (into {} (map (fn [[k v]] [k (value v)])) (partition 2 (:slots s)))])))
                         module-stores)
            parked' (into {} (map (fn [[p _]] [p (parked-record p)])) parked-index)
            entries (mapv entry waits)
            link (some #(let [[reason _ _ req resp] (:slots (second (:slots %)))]
                          (when (contains? #{:link-request :link-response} reason) [req resp]))
                       waits)
            [base installs'] (reduce (fn [[r acc] inst]
                                       (let [[mn phase parent response child] (:slots inst)
                                             [spawned r'] (spawn-child r response mn)]
                                         [r' (assoc acc mn {:phase phase :parent parent :response response
                                                            :vm (restore-task spawned child handles)})]))
                                     [base {}] installs)
            fe (keyed free-env)
            resources (merge (:resources base)
                             (into {} (map (fn [[identity k]] [k (get handles identity)])) stream-keys)
                             (into {} (map (fn [[c body]]
                                             [(get cell-keys c) {:stream-id (stream-key (first (:slots body)))
                                                                 :cursor (second (:slots body))}]))
                                   pairs)
                             (when-let [[req resp] link]
                               {module/link-request-resource (get handles (first (:slots req)))
                                module/link-response-resource (get handles (first (:slots resp)))}))
            machine (assoc base :store store' :module-stores stores :parked parked' :installs installs'
                           :resources resources :env fe :free-env fe :window {} :k nil :control nil
                           :ready-queue [] :id-counter (max (or (:id-counter base) 0) id-counter))]
        (case kind
          :halted (assoc machine :halted? true :blocked? false :value (value result) :wait-set [])
          :parked (assoc machine :halted? true :blocked? false :value (get parked' parked-id) :wait-set entries)
          (assoc machine :halted? false :blocked? true :value :yin/blocked :wait-set entries))))))


(defn lower-task
  "The lower of §7.2 into recv, a fresh semantic-register machine of the
   receiver's composition: gate to frames (decode) and the store keys,
   then pendings and install records, dependency closure (code, closures,
   primitives) and custody, each step over the whole tree before the
   next, all before the first attach! (step 11); then restore (step 12),
   which therefore meets no carrier defect. Answers the restored machine,
   or throws the first refusal as ex-data."
  ([recv wire attach!] (lower-task recv wire attach! {}))
  ([recv wire attach! opts]
   (let [task (decode wire (merge default-budget (:budget opts)))]
     (when-not (= :task (:kind task)) (outcome! :yin.k/undecodable :root {}))
     ;; each step over the whole tree before the next (§3.3)
     (doseq [step [check-stores! check-pendings! check-dependencies! check-custody!]
             [t role] (task-tree task :root)]
       (step recv t role))
     (restore-task recv task (attach-streams! attach! task)))))
