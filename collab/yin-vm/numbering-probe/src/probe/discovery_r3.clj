(ns probe.discovery-r3
  "ROUND-3 PROTOTYPE, kept unchanged for the note's appendix on the D4/D5/D6
   variants (not adopted). The base design is probe.discovery.
   Prototype of discovery-order cursor-cell numbering (note D1-D6, round 3 of
   2026-10-12) over the 6a abstract task representation (yin.vm.ucf.rows).
   Not repo code; a scratch project under collab/.

   Vocabulary: UCF 7.5.3. A cursor cell is the VM box the carrier serializes;
   its source id is the lift's local name; the procedure assigns :yin.k/cc-n.

   Shape of the procedure (one algorithm, three uses):
   - a RUN traverses an item (D1), numbers cursor cells on first meeting (D2),
     keys every :set / :map element by a speculative local run and defers equal
     keys to the run's worklist (D1), re-examines the worklist to a fixpoint
     (D3), groups the surviving tied elements into components by shared
     unnumbered cursor cells, encodes every component by a local run over its
     component item and adopts the least encoding's components (D4). A
     component holding two elements of one group is refused (D5) or, with
     :residue-search, resolved by trials (D6): one local run per element of
     the duplicated group with that element entered first, the least
     spelling adopted.
   - the root run assigns [:n k], k = 1, 2, ...; a LOCAL run assigns [:u j r]
     (j from the enclosing run's local counter, r the body rank) and its
     spelling is the key. A KEY run is speculative: it never refuses, and its
     residue (ties or conflicts) is spelled [:tie K ...] in the key. A
     COMPONENT run (and a D6 trial) must be complete, else D5.
   - :work is charged per position visited or spelled and per literal byte
     copied into a spelling; :search per D6 trial; exceeding either refuses
     :yin.k/unsupported :resource-limit, never a canonicality verdict."
  (:require [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [yin.vm.ucf.rows :as rows]
            [yin.vm.ucf.rows-test :as rt]
            [yin.vm.semantic-register.frame-test :as ft]))


;; ---------------------------------------------------------------- schema

(def synthetic-table
  "Rows that exist only inside the procedure: a :map entry as an ordered pair,
   and a component as the ordered list of its (group-id item) pairs, where the
   item is the group's one element or a synthetic :set of its elements."
  {:entry [:V :V] :component [:pairs :D :V]})


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
  [x]
  (mapv #(bit-and (int %) 255) (seq (cbor/encode x))))


(defn compare-bytes
  [a b]
  (loop [i 0]
    (cond (= i (count a)) (if (= i (count b)) 0 -1)
          (= i (count b)) 1
          (= (nth a i) (nth b i)) (recur (inc i))
          :else (compare (nth a i) (nth b i)))))


(defn by-key-bytes
  [f xs]
  (sort #(compare-bytes (f %1) (f %2)) xs))


(def keyed-structural #{:bindings :store :parked-table})


(defn elem-item
  "The item an element is entered and keyed as: a :set element is its V; a
   :map entry [k v] is the synthetic ordered row [:entry k v], so that the
   alias pattern across key and value is part of the key."
  [kind e]
  (if (= :map kind) [:row {:kind :entry :slots (vec e)}] e))


(defn child-task?
  [ctx n]
  (and (= :task (:kind n)) (not (identical? n (:task ctx)))))


;; --------------------------------------------------------------- budgets

(def default-budget
  "6a's budget without :label-bytes; :search now counts D6 trials."
  (dissoc rows/default-budget :label-bytes))


(defn- spend!
  [ctx category amount]
  (let [n (get (swap! (:counter ctx) update category (fnil + 0) amount) category)]
    (when (> n (get (:budget ctx) category))
      (throw (ex-info "resource limit"
                      {:yin.k/status :yin.k/unsupported :yin.k/kind :resource-limit :yin.k/path [category]})))))


(defn- literal-bytes
  [x]
  (count (cbor/encode x)))


(defn item-cells
  "Cursor cells occurring in slot (d x); census and install children excluded.
   Memoized per procedure on the slot value."
  [ctx d x]
  (or (get @(:cells-memo ctx) [d x])
      (let [acc (atom #{})]
        (letfn [(slot
                  [d x]
                  (cond (ref? d) (node x)
                        (refs? d) (run! node x)
                        (= :C d) (swap! acc conj x)
                        (v? d) (when x (case (first x)
                                         :cursor-cell (swap! acc conj (second x))
                                         :row (node (second x))
                                         nil))
                        :else nil))
                (node
                  [n]
                  (let [{:keys [kind slots]} n]
                    (when-not (or (= :cursor-cells kind) (child-task? ctx n))
                      (dorun (map slot (descriptors kind slots) slots)))))]
          (slot d x)
          (swap! (:cells-memo ctx) assoc [d x] @acc)
          @acc))))


(defn body-label
  [body]
  (let [[stream position] (:slots body)]
    [:body (into [:stream] (:slots stream)) position]))


(defn body-ranks
  "Cursor cell -> rank of its body label among the census's distinct body
   labels in bytewise order. Rank order equals label order, so spelling the
   rank instead of the label changes no comparison and no numbering; it keeps
   key bytes independent of the descriptor size."
  [bodies]
  (let [labels (into {} (map (fn [[c b]] [c (body-label b)])) bodies)
        ranks (into {} (map-indexed (fn [i l] [l i]))
                    (by-key-bytes bytes-key (distinct (vals labels))))]
    (into {} (map (fn [[c l]] [c (get ranks l)])) labels)))


;; ------------------------------------------------------------------ runs

(def ^:dynamic *trace* false)


(defn- new-run
  [ctx num tag next first-item]
  {:ctx ctx :num (atom num) :tag tag :next (atom next) :first (atom first-item)
   :groups (atom []) :gid (atom 0) :refused (atom nil)})


(defn- meet!
  "D2. A cursor cell met for the first time receives the run's next number."
  [run c]
  (when-not (contains? @(:num run) c)
    (let [k @(:next run)]
      (swap! (:num run) assoc c [(:tag run) k])
      (swap! (:next run) inc)
      true)))


(declare enter-node! enter-slot! unordered! local-run resolve! spell-slot spell-node)


(defn- enter-slot!
  [run d x path]
  (spend! (:ctx run) :work 1)
  (cond (ref? d) (enter-node! run x path)
        (refs? d) (dorun (map-indexed (fn [i n] (enter-node! run n (conj path i))) x))
        (= :C d) (meet! run x)
        (v? d) (when x (case (first x)
                         :cursor-cell (meet! run (second x))
                         :row (enter-node! run (second x) path)
                         nil))
        :else nil))


(defn- enter-node!
  "D1. Canonical traversal: table order for slots; ordered repeated slots in
   order; keyed structural rows in the Jing byte order of the key; :window in
   ascending register order; installs and module stores by name; :set and
   :map by local keys with ties deferred; census and children not entered."
  [run n path]
  (let [{:keys [kind slots]} n ctx (:ctx run) ds (descriptors kind slots)]
    (cond
      (= :cursor-cells kind) nil
      (child-task? ctx n) nil
      (= :task kind)
      (doseq [[i d x] (map vector (range) ds slots)]
        (if (contains? #{7 12} i)
          (doseq [child (by-key-bytes #(bytes-key (first (:slots %))) x)]
            (enter-node! run child (conj path i (first (:slots child)))))
          (enter-slot! run d x (conj path i))))
      (contains? keyed-structural kind)
      (doseq [[k v] (by-key-bytes #(bytes-key (first %)) (partition 2 slots))]
        (enter-slot! run (second ds) v (conj path k)))
      (= :window kind)
      (doseq [[r v] (sort-by first (partition 2 slots))] (enter-slot! run :V v (conj path r)))
      (= :set kind) (unordered! run :set (vec slots) path)
      (= :map kind) (unordered! run :map (mapv vec (partition 2 slots)) path)
      :else (dorun (map-indexed (fn [i [d x]] (enter-slot! run d x (conj path i))) (map vector ds slots))))))


(defn- local-key
  [run item]
  (let [r (local-run run item nil)] [(bytes-key (:spell r)) (:spell r)]))


(defn- unordered!
  "D1 for an unordered container: key every element by a speculative local
   run under the numbering at this moment; enter singletons in key order;
   defer equal runs as one tie group (deferral order = canonical order). A D6
   trial run enters its forced element first, when its container is keyed."
  [run kind elems path]
  (let [f @(:first run)
        forced (when f (first (keep-indexed (fn [i e] (when (= f (elem-item kind e)) i)) elems)))]
    (when forced
      (reset! (:first run) nil)
      (enter-slot! run :V (elem-item kind (nth elems forced)) (conj path forced)))
    (let [keyed (vec (keep-indexed (fn [i e] (when (not= i forced) [(local-key run (elem-item kind e)) e i])) elems))
          groups (partition-by (comp first first) (sort #(compare-bytes (first (first %1)) (first (first %2))) keyed))]
      (when (and *trace* (= :n (:tag run)))
        (println "  [sort]" path "num" @(:num run))
        (doseq [g groups] (println "    " (if (= 1 (count g)) "enter" "DEFER") (mapv (fn [[[_ sp] e _]] [e sp]) g))))
      (doseq [g groups]
        (if (= 1 (count g))
          (let [[_ e i] (first g)] (enter-slot! run :V (elem-item kind e) (conj path i)))
          (swap! (:groups run) conj {:id (swap! (:gid run) inc) :kind kind :path path
                                     :key (second (first (first g))) :elems (mapv second g)}))))))


(defn- fixpoint!
  "D3. Re-examine every deferred group in deferral order under the current
   numbering; repeat while a round numbers a cursor cell and groups remain."
  [run]
  (loop []
    (let [before (count @(:num run)) groups @(:groups run)]
      (reset! (:groups run) [])
      (doseq [{:keys [kind elems path]} groups] (unordered! run kind elems path))
      (when (and (seq @(:groups run)) (> (count @(:num run)) before)) (recur)))))


(defn- components
  "D4. Tied elements of all surviving groups, linked by shared unnumbered
   cursor cells (union-find). A component is :conflict when it holds two
   elements of one group; :dup-group is the least such group."
  [run]
  (let [num @(:num run) ctx (:ctx run)
        by-id (into {} (map (juxt :id identity)) @(:groups run))
        elems (vec (for [g @(:groups run) [i e] (map-indexed vector (:elems g))]
                     {:group (:id g) :kind (:kind g) :elem e :idx i
                      :cells (set (remove #(contains? num %) (item-cells ctx :V (elem-item (:kind g) e))))}))
        parent (atom (vec (range (count elems))))
        find (fn find [i] (let [p (@parent i)] (if (= p i) i (let [r (find p)] (swap! parent assoc i r) r))))
        union (fn [i j] (let [ri (find i) rj (find j)] (when (not= ri rj) (swap! parent assoc ri rj))))
        by-cell (reduce (fn [m [i el]] (reduce (fn [m c] (update m c (fnil conj []) i)) m (:cells el)))
                        {} (map-indexed vector elems))]
    (doseq [[_ is] by-cell] (doseq [j (rest is)] (union (first is) j)))
    (->> (group-by find (range (count elems)))
         (sort-by first)
         (mapv (fn [[_ is]]
                 (let [members (vec (sort-by (juxt :group :idx) (map elems is)))
                       dup (first (sort (keep (fn [[g c]] (when (> c 1) g)) (frequencies (map :group members)))))]
                   {:members members
                    :conflict (some? dup)
                    :dup-group dup
                    :path (:path (get by-id (or dup (:group (first members)))))
                    :cells (vec (sort (distinct (mapcat :cells members))))}))))))


(defn- component-item
  "[:component g₁ i₁ g₂ i₂ …] in group order; iᵢ is the group's one element
   in the component, or a synthetic :set of its elements when there are
   several (the D6 case)."
  [comp]
  [:row {:kind :component
         :slots (vec (mapcat (fn [ms]
                               [(:group (first ms))
                                (if (= 1 (count ms))
                                  (elem-item (:kind (first ms)) (:elem (first ms)))
                                  [:row {:kind :set :slots (mapv #(elem-item (:kind %) (:elem %)) ms)}])])
                             (partition-by :group (:members comp))))}])


(defn- component-encoding
  "D4 for a certified component: a local run over its item, required to be
   complete. D5 for a conflicting component: refuse. D6 (:residue-search): one
   trial per element of the duplicated group, that element entered first; the
   least complete spelling is the encoding. :permissive (diagnostic only):
   a single trial with the host-first element."
  [run comp]
  (let [ctx (:ctx run) item (component-item comp)]
    (cond
      (not (:conflict comp)) (local-run run item nil)
      (:permissive ctx)
      (let [m (first (filter #(= (:dup-group comp) (:group %)) (:members comp)))]
        (local-run run item (elem-item (:kind m) (:elem m))))
      (:residue-search ctx)
      (let [es (filter #(= (:dup-group comp) (:group %)) (:members comp))
            trials (mapv (fn [m] (spend! ctx :search 1) (local-run run item (elem-item (:kind m) (:elem m)))) es)]
        (when (and *trace* (= :n (:tag run)))
          (doseq [t trials] (println "  [trial]" (:complete? t) (:spell t))))
        (or (first (remove :complete? trials))
            (first (sort-by #(bytes-key (:spell %)) compare-bytes trials))))
      :else {:complete? false
             :refused {:kind :unresolved-cursor-cells :path (:path comp) :cursor-cells (:cells comp)}})))


(defn- adopt!
  "D4. Enter a component by adopting its local run: its fresh cursor cells
   receive the enclosing run's next numbers in local-index order."
  [run comp]
  (let [fresh (->> (:num (:ce comp))
                   (remove (fn [[c _]] (contains? @(:num run) c)))
                   (sort-by (comp second val)))]
    (doseq [[c _] fresh] (meet! run c))))


(defn- remove-elements!
  [run comps]
  (let [gone (set (for [c comps m (:members c)] [(:group m) (:idx m)]))]
    (swap! (:groups run)
           (fn [gs]
             (vec (keep (fn [g]
                          (let [es (vec (keep-indexed (fn [i e] (when-not (gone [(:id g) i]) e)) (:elems g)))]
                            (when (seq es) (assoc g :elems es))))
                        gs))))))


(defn- resolve!
  "D3-D6 to completion. Leaves :refused set (D5) with the worklist as of the
   last fixpoint, or the worklist empty."
  [run]
  (loop []
    (fixpoint! run)
    (when (and *trace* (= :n (:tag run)))
      (println "  [resolve] num" @(:num run) "groups" (mapv (fn [g] [(:id g) (:path g) (mapv (fn [e] (if (= :cursor-cell (first e)) (second e) e)) (:elems g))]) @(:groups run))))
    (when (seq @(:groups run))
      (let [comps (mapv #(assoc % :ce (component-encoding run %)) (components run))
            bad (seq (remove #(:complete? (:ce %)) comps))]
        (if bad
          ;; The verdict is invariant; the diagnostic names every conflicting
          ;; component's cells (sorted) and the least conflicting group's path,
          ;; so it does not depend on host order either.
          (reset! (:refused run) {:kind :unresolved-cursor-cells
                                  :path (:path (:refused (:ce (first (sort-by #(or (:dup-group %) Long/MAX_VALUE) bad)))))
                                  :cursor-cells (vec (sort (distinct (mapcat #(:cursor-cells (:refused (:ce %))) bad))))})
          (let [sorted (sort #(compare-bytes (bytes-key (:spell (:ce %1))) (bytes-key (:spell (:ce %2)))) comps)
                least (bytes-key (:spell (:ce (first sorted))))
                least-run (take-while #(= least (bytes-key (:spell (:ce %)))) sorted)]
            (when (and *trace* (= :n (:tag run)))
              (doseq [c sorted] (println "  [component]" (mapv (fn [m] [(:group m) (:cells m)]) (:members c)) "CE" (:spell (:ce c))))
              (println "  [adopt]" (count least-run)))
            (doseq [c least-run] (adopt! run c))
            (remove-elements! run least-run)
            (recur)))))))


(defn- local-run
  "A nested run on one item from the enclosing run's numbering, tag :u. Its
   result is a key: the spelling under its final numbering, surviving ties
   spelled [:tie K ...]. `first-item`, when given (D6 trial), is entered
   before its siblings. Memoized on (item, numbering restricted to the item's
   cursor cells, local counter, first-item)."
  [run item first-item]
  (let [ctx (:ctx run)
        cells (item-cells ctx :V item)
        num @(:num run)
        start (if (= :u (:tag run)) @(:next run) 0)
        mk [item (select-keys num cells) start first-item]]
    (or (get @(:memo ctx) mk)
        (let [sub (new-run ctx num :u start first-item)]
          (enter-slot! sub :V item [])
          (resolve! sub)
          (let [spell (spell-slot sub :V item [])
                r {:spell spell
                   ;; Restricted to the item's own cursor cells: the result is a
                   ;; function of the memo key, never of the enclosing context.
                   :num (select-keys @(:num sub) cells)
                   :complete? (and (nil? @(:refused sub)) (empty? @(:groups sub)))
                   :refused @(:refused sub)}]
            (swap! (:counter ctx) update :key-bytes (fnil + 0) (count (bytes-key spell)))
            (swap! (:counter ctx) update :local-runs (fnil + 0) 1)
            (swap! (:memo ctx) assoc mk r)
            r)))))


;; -------------------------------------------------------------- spelling

(defn- spell-cell
  [run c]
  (let [[t k] (get @(:num run) c) ctx (:ctx run)]
    (case t
      :n [:cursor-cell [:n k]]
      :u [:cursor-cell [:u k (if (= :label (:spell-body ctx))
                               (body-label (get (:bodies ctx) c))
                               (get (:ranks ctx) c))]]
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
                     (do (spend! (:ctx run) :work (literal-bytes (second x))) x)))
        :else (do (spend! (:ctx run) :work (literal-bytes x)) x)))


(defn- spell-node
  "The content encoding CE of a row under the run's final numbering."
  [run n path]
  (let [{:keys [kind slots]} n ctx (:ctx run) ds (descriptors kind slots)]
    (cond
      (= :cursor-cells kind) [:cursor-cells]
      (child-task? ctx n) [:child]
      (= :task kind)
      (into [kind]
            (map-indexed (fn [i [d x]]
                           (if (contains? #{7 12} i)
                             (vec (by-key-bytes #(bytes-key (nth % 1))
                                                (map #(spell-node run % (conj path i (first (:slots %)))) x)))
                             (spell-slot run d x (conj path i))))
                         (map vector ds slots)))
      (contains? keyed-structural kind)
      (into [kind] (mapcat identity (by-key-bytes #(bytes-key (first %))
                                                  (map (fn [[k v]] [k (spell-slot run (second ds) v (conj path k))])
                                                       (partition 2 slots)))))
      (= :window kind)
      (into [kind] (mapcat (fn [[r v]] [r (spell-slot run :V v (conj path r))]) (sort-by first (partition 2 slots))))
      (contains? #{:set :map} kind)
      (let [elems (if (= :set kind) (vec slots) (mapv vec (partition 2 slots)))
            tied (filter #(= path (:path %)) @(:groups run))
            tied-elems (set (mapcat :elems tied))
            plain (keep-indexed (fn [i e] (when-not (tied-elems e) (spell-slot run :V (elem-item kind e) (conj path i)))) elems)
            ties (map #(into [:tie] (repeat (count (:elems %)) (:key %))) tied)]
        (into [kind] (by-key-bytes bytes-key (concat plain ties))))
      :else (into [kind] (map-indexed (fn [i [d x]] (spell-slot run d x (conj path i))) (map vector ds slots))))))


;; ------------------------------------------------------------- procedure

(def ^:dynamic *numbering-opts*
  "Options for every run of the procedure in scope (root and install children):
   :residue-search (D6), :permissive (diagnostic), :spell-body :label (the
   round-2 spelling, for the byte measurement), :budget."
  {})


(defn discovery-numbering
  "Returns {:numbering {src -> :yin.k/cc-n} :used {...}} or {:refused {...}}.
   Throws :resource-limit when the budget is exceeded, and a defect (never a
   refusal) when the numbering is not total."
  ([task] (discovery-numbering task *numbering-opts*))
  ([task opts]
   (let [census (nth (:slots task) 9)
         bodies (into {} (map vec) (partition 2 (:slots census)))
         ctx {:task task
              :bodies bodies
              :ranks (body-ranks bodies)
              :memo (atom {}) :cells-memo (atom {})
              :budget (merge default-budget (:budget opts))
              :counter (atom {})
              :permissive (:permissive opts)
              :residue-search (:residue-search opts)
              :spell-body (:spell-body opts)}
         run (new-run ctx {} :n 1 nil)]
     (enter-node! run task [])
     (resolve! run)
     (if-let [r @(:refused run)]
       {:refused r :used @(:counter ctx)}
       (let [numbered (set (keys @(:num run))) declared (set (keys bodies))]
         (when (seq @(:groups run)) (throw (ex-info "residue after resolve" {})))
         (when (not= numbered declared)
           (throw (ex-info "numbering is not total" {:missing (vec (remove numbered declared))
                                                     :extra (vec (remove declared numbered))})))
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
                   :yin.k/path (into path (:path r)) :yin.k/cursor-cells (:cursor-cells r)})))


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
                                 ;; An install child: its own scope, numbered by the same procedure.
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
   :unresolved-cursor-cells (D5) for the root or for any install child (path
   [12 <name> …]); a budget refusal propagates as itself."
  [task]
  (let [r (discovery-numbering task)]
    (when (:refused r) (refuse-unresolved! (:refused r) []))
    (let [[id rows] (emit-node* task (:numbering r) {} [])]
      {:yin.k/handoff true :yin.k/version 3 :yin.k/root id :yin.k/rows (vec (vals rows))})))


(defn decode*
  "Receiver side: 6a's decode* with its own numbering check off (it still runs
   6a's recompute, under 6a's budget), then the whole discovery recomputation
   (root and install children) under one translation: only a lift-side
   :yin.k/non-portable of kind :unresolved-cursor-cells becomes
   :yin.k/undecodable :unresolved-cursor-cells with the path prefixed by the
   carried root id; anything else (a resource refusal) propagates as itself.
   In the real codec this check sits at carrier step 6, before
   validate-frames; here it runs after 6a's decode* returns."
  [wire]
  (let [task (@#'rows/decode* wire (assoc rows/default-budget :check-numbering false))
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
    task))


(defn error
  [f]
  (try (f) nil (catch Throwable e (or (ex-data e) {:throwable (str e)}))))


(defn do-root
  "Root id, {:refused …} for D5, or {:resource-limit path} for a budget."
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
  "Rename every cursor cell by `bij` and shuffle every :set, :map and the
   census in listed order (shuffle is rebound by callers for determinism)."
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
  "Portable decoded equality of an abstract value (check-abstract!'s rule):
   sets as sets, maps as maps, cursor cells by identity."
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
  "Random V over `cells`, depth-bounded. Shapes: literals, cursor cells,
   vectors, sets, maps (cursor cells as keys too), sets of cursor-cell pairs
   (edges, aliases such as [X X] included), sets of sets."
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

(defn permissive-roots
  "For a refused state: run the procedure with D5 replaced by host-first entry,
   over `n` shuffles of every unordered container. More than one root is a
   witness that the state is not interchangeable; one root over n shuffles is
   not a proof."
  [task n]
  (let [r (java.util.Random. 7)]
    (with-redefs [shuffle (seeded-shuffle r)]
      (->> (repeatedly n #(rename-and-shuffle task identity))
           (map (fn [t]
                  (binding [*numbering-opts* (assoc *numbering-opts* :permissive true)]
                    (let [d (discovery-numbering t)
                          [id _] (emit-node* t (:numbering d) {} [])]
                      id))))
           distinct count))))


(defn invariant-roots
  "Distinct roots of a state over `n` renamings plus shuffles (1 = invariant
   under the samples)."
  [task n]
  (let [r (java.util.Random. 5) cells (all-census-cells task)]
    (with-redefs [shuffle (seeded-shuffle r)]
      (->> (repeatedly n #(rename-and-shuffle task (zipmap cells (map (fn [i] (keyword "r" (str "z" i))) (shuffle (range (count cells)))))))
           (map do-root)
           distinct count))))


(defn report-fixture
  [name task]
  (let [d (discovery-numbering task)]
    (println name)
    (println "  DO :" (or (:refused d) (into (sorted-map) (:numbering d))) "| work" (:work (:used d)) "| search" (or (:search (:used d)) 0))
    (println "  DO root:" (do-root task))
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
      (and (map? d1) (:refused d1)) {:refused (:refused d1)}
      (map? d1) {:resource-limit (:resource-limit d1)}
      :else
      (cond-> {:root d1 :invariant (= d1 d2)
               :used (:used (discovery-numbering task))}
        true (assoc :decode (let [e (error #(decode* (emit* task)))]
                              (cond (nil? e) :ok
                                    (= :resource-limit (:yin.k/kind e)) :sixa-recompute-limit
                                    :else e)))))))


(defn search
  [label gen seeds]
  (let [r (java.util.Random. 11)
        stats (atom {:numbered 0 :refused 0 :resource-limit 0 :invariance-fail 0 :decode-ok 0 :decode-fail 0 :decode-6a-limit 0
                     :refused-witnessed-noninterchangeable 0 :refused-not-distinguished 0
                     :max-work 0 :max-search 0 :max-local-runs 0})]
    (doseq [seed seeds]
      (let [task (gen seed) o (invariance-check task r)]
        (cond
          (:refused o)
          (do (swap! stats update :refused inc)
              (if (> (permissive-roots task 12) 1)
                (swap! stats update :refused-witnessed-noninterchangeable inc)
                (swap! stats update :refused-not-distinguished inc)))
          (:resource-limit o)
          (do (swap! stats update :resource-limit inc) (println "RESOURCE LIMIT" label seed (:resource-limit o)))
          :else
          (do (swap! stats update :numbered inc)
              (swap! stats update :max-work max (:work (:used o) 0))
              (swap! stats update :max-search max (:search (:used o) 0))
              (swap! stats update :max-local-runs max (:local-runs (:used o) 0))
              (when-not (:invariant o) (swap! stats update :invariance-fail inc) (println "INVARIANCE FAIL" label seed))
              (case (:decode o)
                :ok (swap! stats update :decode-ok inc)
                :sixa-recompute-limit (swap! stats update :decode-6a-limit inc)
                (do (swap! stats update :decode-fail inc) (println "DECODE FAIL" label seed (:decode o))))))))
    (println label (count seeds) "states:" @stats)
    @stats))


(defn -main
  []
  (let [body (rt/cursor-cell-body :s 0)
        s (fn [es] [:row (rows/node :set es)])
        v (fn [cs] [:row (rows/node :vec (mapv #(vector :cursor-cell %) cs))])
        c (fn [x] [:cursor-cell x])
        task (fn [ids result] (rt/task-node (mapv #(vector % body) ids) result))
        two-cycle (task [:a :b] (s [(v [:a :b]) (v [:b :a])]))
        three-cycle (task [:a :b :c] (s [(v [:a :b]) (v [:b :c]) (v [:c :a])]))
        path-abc (task [:a :b :c] (s [(v [:a :b]) (v [:b :c])]))
        loop-2cycle (task [:l :a :b] (s [(v [:l :l]) (v [:a :b]) (v [:b :a])]))
        seven (rt/seven-cursor-cell-triangle-square)
        ;; codex round 2: a key-building local run reaches a conflict, the root run does not.
        local-d5-witness (task [:a :b] [:row (rows/node :vec [(s [(s [(v [:a :b]) (v [:b :a])])]) (c :a)])])
        refused-list [["loop + 2-cycle" loop-2cycle] ["2-cycle" two-cycle] ["3-cycle" three-cycle]
                      ["seven-cell" seven] ["path #{[A B] [B C]}" path-abc]]]
    (println "== fixtures (D5 mode: no residue search)")
    (report-fixture "fixture :s1 :s2 :x :y (pinned fab223...)" (rt/fixture :s1 :s2 :x :y))
    (report-fixture "review counterexample 'a #{X Y}, 'b X" (fixture-review-counterexample))
    (report-fixture "symmetric pair #{X Y}" (task [:x :y] (s [(c :x) (c :y)])))
    (report-fixture "alias a: [x x y]" (task [:x :y] (v [:x :x :y])))
    (report-fixture "alias b: [x y y]" (task [:x :y] (v [:x :y :y])))
    (report-fixture "#{[X X] [Y Y]}" (task [:x :y] (s [(v [:x :x]) (v [:y :y])])))
    (report-fixture "{X 1, Y 1}" (task [:x :y] [:row (rows/node :map [(c :x) [:lit 1] (c :y) [:lit 1]])]))
    (report-fixture "codex A: #{[X X] [Y Z]}" (task [:x :y :z] (s [(v [:x :x]) (v [:y :z])])))
    (report-fixture "codex A reversed" (task [:x :y :z] (s [(v [:y :z]) (v [:x :x])])))
    (report-fixture "codex B: {X X, Y Z}" (task [:x :y :z] [:row (rows/node :map [(c :x) (c :x) (c :y) (c :z)])]))
    (report-fixture "codex B reversed" (task [:x :y :z] [:row (rows/node :map [(c :y) (c :z) (c :x) (c :x)])]))
    (report-fixture "codex C: #{#{X Y} #{Z W}}" (task [:x :y :z :w] (s [(s [(c :x) (c :y)]) (s [(c :z) (c :w)])])))
    (report-fixture "codex C renamed" (task [:q :r :s :t] (s [(s [(c :s) (c :t)]) (s [(c :q) (c :r)])])))
    (report-fixture "[#{X Y} #{X Y}]" (task [:x :y] [:row (rows/node :vec [(s [(c :x) (c :y)]) (s [(c :x) (c :y)])])]))
    (report-fixture "[#{X Y} X]" (task [:x :y] [:row (rows/node :vec [(s [(c :x) (c :y)]) (c :x)])]))
    (report-fixture "[#{#{X Y} #{Z W}} X]" (task [:x :y :z :w] [:row (rows/node :vec [(s [(s [(c :x) (c :y)]) (s [(c :z) (c :w)])]) (c :x)])]))
    (report-fixture "#{[#{X Y} #{X Y}] [#{P Q} #{R S}]} (equal keys would be unsound; keys differ)"
                    (task [:x :y :p :q :r :s] (s [[:row (rows/node :vec [(s [(c :x) (c :y)]) (s [(c :x) (c :y)])])]
                                                  [:row (rows/node :vec [(s [(c :p) (c :q)]) (s [(c :r) (c :s)])])]])))
    (report-fixture "codex r2 witness [#{#{[A B] [B A]}} A] (speculative key conflict; root run numbers)" local-d5-witness)
    (println "  invariant roots over 24 renamings+shuffles:" (invariant-roots local-d5-witness 24)
             "| decode:" (or (error #(decode* (emit* local-d5-witness))) :ok))
    (report-fixture "loop + 2-cycle #{[L L] [A B] [B A]} (6a pinned 55ff16...)" loop-2cycle)
    (report-fixture "2-cycle #{[A B] [B A]}" two-cycle)
    (report-fixture "3-cycle #{[A B] [B C] [C A]}" three-cycle)
    (report-fixture "path #{[A B] [B C]}" path-abc)
    (report-fixture "seven-cell triangle+square" seven)
    (let [ids (mapv #(keyword "src" (str "x" %)) (range 10))]
      (report-fixture "ten cells in a vec" (rt/task-node (mapv (fn [id i] [id (rt/cursor-cell-body :s i)]) ids (range))
                                                         (v ids))))
    (println "\n== refused states: permissive roots over 24 shuffles (>1 = witnessed non-interchangeable)")
    (doseq [[name t] refused-list]
      (println " " name ":" (permissive-roots t 24) "distinct root(s)"))
    (println "\n== D6 residue search on the refused states: root, invariance over 24 renamings+shuffles, trials, decode")
    (binding [*numbering-opts* {:residue-search true}]
      (doseq [[name t] refused-list]
        (let [d (discovery-numbering t)]
          (println " " name ":" (do-root t) "| numbering" (into (sorted-map) (:numbering d))
                   "| trials" (:search (:used d)) "| work" (:work (:used d))
                   "| distinct roots" (invariant-roots t 24)
                   "| decode:" (or (error #(decode* (emit* t))) :ok)
                   "| 6a:" (safe-6a-root t)))))
    (println "\n== D6 does not move a D1-D4 pin (same roots with residue search on)")
    (binding [*numbering-opts* {:residue-search true}]
      (doseq [[name t] [["fixture" (rt/fixture :s1 :s2 :x :y)] ["review" (fixture-review-counterexample)]
                        ["codex A" (task [:x :y :z] (s [(v [:x :x]) (v [:y :z])]))]
                        ["codex C" (task [:x :y :z :w] (s [(s [(c :x) (c :y)]) (s [(c :z) (c :w)])]))]
                        ["[#{X Y} #{X Y}]" (task [:x :y] [:row (rows/node :vec [(s [(c :x) (c :y)]) (s [(c :x) (c :y)])])])]
                        ["witness" local-d5-witness]]]
        (println " " name ":" (do-root t))))
    (println "\n== install child refusal translates in decode (path prefixed by the root id, then [12 name ...])")
    (let [parent (with-child (rt/task-node [] [:lit nil]) two-cycle)]
      (println "  lift:" (error #(emit* parent)))
      (println "  decode of a carrier built with emit-numbered:"
               (error #(decode* (rows/emit-numbered parent {}))))
      (binding [*numbering-opts* {:residue-search true}]
        (println "  with D6, lift root:" (do-root parent) "| decode:" (or (error #(decode* (emit* parent))) :ok))))
    (println "\n== budgets: {:work 1} and {:search 0} are resource refusals, never canonicality verdicts")
    (binding [*numbering-opts* {:budget {:work 1}}]
      (println "  loop+2-cycle {:work 1}:" (error #(emit* loop-2cycle))))
    (binding [*numbering-opts* {:residue-search true :budget {:search 1}}]
      (println "  3-cycle D6 {:search 1}:" (error #(emit* three-cycle))))
    (binding [*numbering-opts* {:budget {:work 1}}]
      (println "  decode of a valid carrier under {:work 1} propagates the resource refusal:"
               (error #(decode* (binding [*numbering-opts* {}] (emit* (rt/fixture :s1 :s2 :x :y)))))))
    (println "\n== key bytes versus stream descriptor size (codex r2: 128 -> 65536 chars grew key bytes 3509 -> 592208)")
    (doseq [n [128 65536]]
      (let [big (fn [i] (rows/node :cursor-cell-body [(rows/node :stream [:s (apply str (repeat n "d"))]) i]))
            t (rt/task-node [[:x (big 0)] [:y (big 0)] [:z (big 1)] [:w (big 1)]]
                            (s [(s [(c :x) (c :y)]) (s [(c :z) (c :w)]) (s [(c :x) (c :z)])]))]
        (doseq [mode [:label :rank]]
          (let [d (discovery-numbering t {:spell-body mode})]
            (println "  descriptor" n "chars, body spelled by" mode ": key bytes" (:key-bytes (:used d))
                     "| work" (:work (:used d)) "| local runs" (:local-runs (:used d)) "| root" (do-root t))))))
    (println "\n== invariance under element reversal + renaming/shuffling")
    (let [r (java.util.Random. 3)]
      (doseq [[name t] [["#{[X X] [Y Y]}" (task [:x :y] (s [(v [:x :x]) (v [:y :y])]))]
                        ["{X 1, Y 1}" (task [:x :y] [:row (rows/node :map [(c :x) [:lit 1] (c :y) [:lit 1]])])]
                        ["#{X Y}" (task [:x :y] (s [(c :x) (c :y)]))]
                        ["review counterexample" (fixture-review-counterexample)]
                        ["codex A" (task [:x :y :z] (s [(v [:x :x]) (v [:y :z])]))]
                        ["codex B" (task [:x :y :z] [:row (rows/node :map [(c :x) (c :x) (c :y) (c :z)])])]
                        ["codex C" (task [:x :y :z :w] (s [(s [(c :x) (c :y)]) (s [(c :z) (c :w)])]))]
                        ["[#{X Y} #{X Y}]" (task [:x :y] [:row (rows/node :vec [(s [(c :x) (c :y)]) (s [(c :x) (c :y)])])])]]]
        (let [rev (with-redefs [shuffle (comp vec reverse)] (rename-and-shuffle t identity))
              ren (with-redefs [shuffle (seeded-shuffle r)] (rename-and-shuffle t (fn [x] (keyword "q" (clojure.core/name x)))))]
          (println name "DO:" (do-root t) "| reversed equal:" (= (do-root t) (do-root rev))
                   "| renamed+shuffled equal:" (= (do-root t) (do-root ren)) "| decode:" (or (error #(decode* (emit* t))) :ok)))))
    (println "\n== frame-bearing fixtures (act window, K frame window, environments, parked state)")
    (let [t (frame-task [[:x body] [:y body]] [(c :x) (c :y)] (c :y) (s [(c :x) (c :y)]))]
      (report-fixture "wait window x, K window y, env y, parked K window #{x y}" t)
      (println "  decode:" (or (error #(decode* (emit* t))) :ok)))
    (let [t (frame-task [[:x body] [:y body]] [(s [(c :x) (c :y)])] nil (c :y))]
      (report-fixture "wait window #{x y}, parked K window y (parked table, slot 8, precedes frames, slot 13)" t)
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
    (println "\n== counterexample search, D5 mode (refuse the residue)")
    (search "random values" gen-task (range 2000))
    (search "frame-bearing" gen-frame-task (range 200))
    (search "with children" gen-child-task (range 100))
    (println "\n== counterexample search, D6 mode (residue search)")
    (binding [*numbering-opts* {:residue-search true}]
      (search "random values" gen-task (range 2000))
      (search "frame-bearing" gen-frame-task (range 200))
      (search "with children" gen-child-task (range 100)))))
