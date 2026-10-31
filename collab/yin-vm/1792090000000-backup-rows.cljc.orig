(ns yin.vm.ucf.rows
  "Version-3 row codec. Pure row construction, graph decoding and canonical
   cell numbering; no machine attachment, dependency scanner or transport.
   An abstract row is {:kind keyword :slots vector}. Structural references
   contain abstract rows; values use [:lit d], [:row abstract-row], [:cell id].
   A task's :cells row holds alternating source identities and :cell-body
   rows. Install children are separate tasks with separate cell scopes.

   The census is ordered numerically by n (draft §3, \"sorted by n\"), so
   :yin.k/c-10 follows :yin.k/c-9; its keys must spell c-N with no leading
   zero, else :slot-kind.

   Default value markers are plain maps {:yin.k/row abstract-row} and
   {:yin.k/cell keyword}. A guest map whose sole key is :yin.k/row or
   :yin.k/cell collides with them and is not portable: on encode and decode
   every literal of that shape is refused :yin.k/non-portable. Explicit
   markers use the caller value encoder."
  (:require [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [yin.vm.semantic-register.code :as code]
            [yin.vm.semantic-register.frame :as frame]))


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
   :cells [:pairs :C [:ref :cell-body]] :cell-body [[:ref :stream] :D]
   :task [:N :D :D :D :N [:ref :bindings] [:ref :store]
          [:refs :module-store] [:ref :parked-table] [:ref :cells]
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
  (and (map? x) (= 1 (count x)) (or (contains? x :yin.k/row) (contains? x :yin.k/cell))))


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
  (let [schema (get row-table kind)]
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


(def ^:private census-key #"c-([1-9][0-9]*)")


(defn- census-order
  ;; Numeric order of c-N without reading wire data: fewer digits first,
  ;; then the digits. Other keys (a partial emit-numbered) sort last.
  [k]
  (let [digits (when (keyword? k) (second (re-matches census-key (name k))))]
    (if digits [0 (count digits) digits] [1 0 (str k)])))


(defn- ordered-slots
  [kind slots]
  (cond
    (= kind :window) (vec (mapcat identity (sort-by first (partition 2 slots))))
    (= kind :cells) (vec (mapcat identity (sort-by #(census-order (first %)) (partition 2 slots))))
    (contains? #{:map :bindings :store :parked-table} kind)
    (vec (mapcat identity (sort #(byte-compare (first %1) (first %2)) (partition 2 slots))))
    (= kind :set) (sorted-bytes slots)
    :else slots))


(declare emit-node canonical-numbering validate-frames)


(defn- emit-slot
  [d x numbering rows]
  (when-not (or (ref? d) (refs? d) (= :C d))
    (check-literal-markers! (if (contains? #{:V :optional-V} d)
                              (when (= :lit (first x)) (second x)) x)))
  (cond
    (ref? d) (emit-node x numbering rows)
    (refs? d) (reduce (fn [[out rs] n]
                        (let [[id rs'] (emit-node n numbering rs)] [(conj out id) rs']))
                      [[] rows] x)
    (= d :C) [(get numbering x x) rows]
    (contains? #{:V :optional-V} d)
    (case (first x)
      :row (let [[id rs] (emit-node (second x) numbering rows)] [[:row id] rs])
      :cell [[:cell (get numbering (second x) (second x))] rows]
      [x rows])
    :else [x rows]))


(defn- emit-node
  [n numbering rows]
  (let [{:keys [kind slots]} n
        ;; A child is canonicalized in its own identity scope.
        [slots rows] (reduce (fn [[out rs] [d x]]
                               (if (and (ref? d) (= :task (:kind x)))
                                 (let [[id child-rows] (emit-node x (canonical-numbering x) {})]
                                   [(conj out id) (merge rs child-rows)])
                                 (let [[x' rs'] (emit-slot d x numbering rs)] [(conj out x') rs'])))
                             [[] rows] (map vector (descriptors kind slots) slots))
        slots (if (= kind :task)
                (-> slots
                    (update 7 #(vec (sort (fn [a b] (byte-compare (nth (get rows a) 2) (nth (get rows b) 2))) %)))
                    (update 12 #(vec (sort (fn [a b] (byte-compare (nth (get rows a) 2) (nth (get rows b) 2))) %))))
                (ordered-slots kind slots))
        body (into [kind] slots) id (jing/segment-key body)]
    [id (assoc rows id (into [id] body))]))


(def default-budget
  "Local resource bounds, independent of canonical bytes."
  {:rows 10000 :cells 64 :depth 256 :work 200000 :search 10000 :bytes 16777216 :label-bytes 1048576})


(defn- spend!
  [counter budget category amount]
  (let [n (get (swap! counter update category (fnil + 0) amount) category)]
    (when (> n (get budget category))
      (refuse! :yin.k/unsupported :resource-limit [category]))))


(defn- check-label-bytes!
  "Conservative byte/representation bound before CBOR normalization or
   comparison vectors allocate. Byte payloads are counted without copying;
   container overhead also bounds the number of intermediate tree nodes."
  [x budget]
  (let [limit (:label-bytes budget)]
    (letfn [(size
              [x remaining]
              (let [n (cond
                        (cbor/byte-payload? x) (+ 16 #?(:cljd (.-length x) :default (alength x)))
                        (string? x) (+ 128 (* 4 (count x)))
                        (or (keyword? x) (symbol? x)) (+ 128 (* 4 (count (str x))))
                        (coll? x) (reduce (fn [total v] (+ total (size v (- remaining total))))
                                          128 (if (map? x) (mapcat identity x) x))
                        :else 128)]
                (when (> n remaining)
                  (refuse! :yin.k/unsupported :resource-limit [:label-bytes]))
                (if (meta x) (+ n (size (meta x) (- remaining n))) n)))]
      (size x limit))))


(declare canonical-numbering)


(defn canonical-numbering
  "Draft §6 refinement and exhaustive individualization search. Returns
   source-cell -> :yin.k/c-N. No encounter-order tie break. A budget
   exhaustion is a resource refusal, never a canonicality verdict."
  ([task] (canonical-numbering task default-budget))
  ([task budget]
   (let [budget (merge default-budget budget) counter (atom {})
         cells (into {} (map vec) (partition 2 (:slots (nth (:slots task) 9))))
         ids (vec (keys cells))
         checked-encode (fn [x] (check-label-bytes! x budget) (cbor/encode x))
         checked-key (fn [x] (check-label-bytes! x budget) (bytes-key x))
         checked-sort (fn [xs]
                        (doseq [x xs] (check-label-bytes! x budget))
                        (vec (sort (fn [a b]
                                     (compare-bytes (checked-key (checked-encode a))
                                                    (checked-key (checked-encode b)))) xs)))
         labels (into {} (map (fn [[id body]]
                                (let [[stream position] (:slots body)]
                                  [id (checked-encode [:body (checked-encode (into [:stream] (:slots stream)))
                                                       (checked-encode position)])])) cells))]
     (spend! counter budget :cells (count ids))
     (letfn [(ce-slot
               [d x ls]
               (spend! counter budget :work 1)
               (cond
                 (ref? d) (ce x ls)
                 (refs? d) (mapv #(ce % ls) x)
                 (= :C d) [:cell (get ls x)]
                 (contains? #{:V :optional-V} d)
                 (case (first x) :cell [:cell (get ls (second x))]
                       :row [:row (ce (second x) ls)] x)
                 :else x))
             (ce
               [n ls]
               (let [{:keys [kind slots]} n]
                 (cond
                   (= :cells kind) [:cells]
                   (and (= :task kind) (not (identical? n task)))
                   (let [[id _rows] (emit-node n (canonical-numbering n budget) {})]
                     [:child (checked-encode id)])
                   :else
                   (let [ss (mapv #(ce-slot %1 %2 ls) (descriptors kind slots) slots)
                         ss (cond
                              (contains? #{:map :store :bindings :parked-table} kind)
                              (checked-sort (mapv vec (partition 2 ss)))
                              (= :set kind) (checked-sort ss)
                              (= :task kind) (-> ss (update 7 checked-sort) (update 12 checked-sort))
                              :else ss)]
                     (into [kind] ss)))))
             (positions
               [ls]
               (let [out (atom (zipmap ids (repeat [])))]
                 (letfn [(visit-slot
                           [d x path depth]
                           (spend! counter budget :work 1)
                           (when (> depth (:depth budget))
                             (refuse! :yin.k/unsupported :resource-limit [:depth]))
                           (cond
                             (= :C d) (swap! out update x conj (checked-encode path))
                             (ref? d) (visit x path (inc depth))
                             (contains? #{:V :optional-V} d)
                             (case (first x)
                               :cell (swap! out update (second x) conj (checked-encode path))
                               :row (visit (second x) path (inc depth)) nil)
                             :else nil))
                         (visit
                           [n path depth]
                           (let [{:keys [kind slots]} n ds (descriptors kind slots)]
                             (when-not (or (= :cells kind)
                                           (and (= :task kind) (not (identical? n task))))
                               (cond
                                 (contains? #{:map :store :bindings :parked-table} kind)
                                 (doseq [[i [key value]] (map-indexed vector (partition 2 slots))]
                                   (let [kd (nth ds (* 2 i)) vd (nth ds (inc (* 2 i)))
                                         ck (ce-slot kd key ls)]
                                     (visit-slot kd key (conj path [kind :key ck]) depth)
                                     (visit-slot vd value (conj path [kind :val ck]) depth)))
                                 (= :set kind)
                                 (doseq [x slots] (visit-slot :V x (conj path [kind :elem (ce-slot :V x ls)]) depth))
                                 :else
                                 (doseq [[i d x] (map vector (range) ds slots)]
                                   (if (refs? d)
                                     (doseq [[j child] (map-indexed vector x)]
                                       (visit child
                                              (conj path (if (= i 13) [:task :frame j]
                                                             [kind :val (first (:slots child))]))
                                              (inc depth)))
                                     (visit-slot d x (conj path [kind i]) depth)))))))]
                   (visit task [] 0)
                   @out)))
             (classes
               [ls]
               (vec (sort #(compare-bytes (first %1) (first %2))
                          (map (fn [[label members]] [label (vec members)])
                               (reduce (fn [m id] (update m (checked-key (get ls id)) (fnil conj []) id)) {} ids)))))
             (refine
               [ls]
               (let [ps (positions ls)
                     next-ls (into {} (map (fn [id]
                                             [id (checked-encode [:sig (get ls id)
                                                                  (vec (sort #(compare-bytes (checked-key %1) (checked-key %2)) (get ps id)))])]) ids))]
                 (if (= (count (classes ls)) (count (classes next-ls))) next-ls (recur next-ls))))
             (search
               [ls depth]
               (spend! counter budget :search 1)
               (let [ls (refine ls) cs (classes ls)
                     unresolved (first (filter #(> (count (second %)) 1) cs))]
                 (if unresolved
                   (reduce (fn [best id]
                             (let [leaf (search (assoc ls id (checked-encode [:ind depth (get ls id)])) (inc depth))]
                               (if (or (nil? best) (neg? (compare-bytes (:key leaf) (:key best)))) leaf best)))
                           nil (second unresolved))
                   (let [numbering (into {} (map-indexed (fn [i [_ members]]
                                                           [(first members) (keyword "yin.k" (str "c-" (inc i)))]) cs))
                         [id rows] (emit-node task numbering {})]
                     ;; This digest preimage contains the input row's own inline bodies,
                     ;; not growing refinement labels. Decode bounds input by :bytes;
                     ;; emit imposes no additional input-body byte limit here.
                     {:numbering numbering :key (bytes-key (cbor/encode (subvec (get rows id) 1)))}))))]
       (if (empty? ids) {} (:numbering (search labels 0)))))))


(defn emit-numbered
  "Emit under a supplied numbering. Useful for canonicality probes;
   ordinary callers use emit, which computes the canonical numbering."
  [task numbering]
  (let [[id rows] (emit-node task numbering {})]
    {:yin.k/handoff true :yin.k/version 3 :yin.k/root id :yin.k/rows (vec (vals rows))}))


(defn emit
  "Emit an abstract task as a self-contained carrier. This is a codec,
   not lift-task: the caller supplies the complete abstract row tree."
  ([task] (emit task default-budget))
  ([task budget]
   (let [[id rows] (emit-node task (canonical-numbering task budget) {})]
     {:yin.k/handoff true :yin.k/version 3 :yin.k/root id :yin.k/rows (vec (vals rows))})))


(defn- structural?
  [kind d x]
  (cond
    (and (= :cells kind) (= :C d)) (and (keyword? x) (some? (re-matches census-key (name x))))
    (or (ref? d) (= :C d)) (keyword? x)
    (refs? d) (and (vector? x) (every? keyword? x))
    (contains? #{:V :optional-V} d)
    (or (and (= :optional-V d) (nil? x))
        (and (vector? x) (= 2 (count x)) (contains? #{:lit :row :cell} (first x))
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
              :cell [:identity (second v)]
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
              :cell true
              :lit false
              :row (let [n (second v)]
                     (if (contains? #{:vec :list :set :map} (:kind n))
                       (boolean (some reference? (:slots n))) true))
              false))
          (visit
            [n path cells reached]
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
                    (= :C d) (do (when-not (contains? cells x) (bad! :missing-cell p)) (swap! reached conj x))
                    (and (ref? d) (= :task (:kind x))) (check-task x p)
                    (and (ref? d) (not= :cells (:kind x))) (visit x p cells reached)
                    (refs? d) (doseq [[j child] (map-indexed vector x)]
                                (visit child (conj p j) cells reached))
                    (contains? #{:V :optional-V} d)
                    (when x
                      (case (first x)
                        :lit (when-not (literal? (second x)) (bad! :literal p))
                        :cell (do (when-not (contains? cells (second x)) (bad! :missing-cell p))
                                  (swap! reached conj (second x)))
                        :row (visit (second x) p cells reached)))
                    :else nil)))))
          (check-task
            [task path]
            (let [slots (:slots task) census (nth slots 9)
                  cells (into {} (map vec) (partition 2 (:slots census))) reached (atom #{})]
              (visit task path cells reached)
              (when-not (= (set (keys cells)) @reached) (bad! :extra-cell (conj path 9)))))]
    (when (= :task (:kind root)) (check-task root []))))


(defn- decode*
  "Validate gate, local schema, finite reachable graph, hashes, canonical
   form and cell numbering, then expand structural refs. No attachment.
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
         (when (contains? #{:map :bindings :store :parked-table :window :cells :set} kind)
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
               canonical (if (= :task (:kind task)) (emit task budget)
                             (let [[id _] (emit-node task {} {})] {:yin.k/root id}))]
           (when (and (not= false (:check-numbering budget)) (not= root (:yin.k/root canonical)))
             (bad! :noncanonical-cells [root]))
           (when (= :task (:kind task))
             (when-let [d (validate-frames task)]
               (throw (ex-info "Frame validation failed" (update d :yin.k/path #(into [root] %))))))
           task))))))


(defn decode
  "Validate and expand a row pack, including canonical cell numbering.
   For task roots, run X1 and frame validation after numbering."
  ([wire] (decode* wire default-budget))
  ([wire budget] (decode* wire (dissoc budget :check-numbering))))


(defn canonical-under
  "Test-only §5.5 re-emission under an explicit stream correspondence.
   Missing marker coverage fails; neither traversal order nor original
   cell numbering chooses a logical identity. Accepts a carrier."
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
    :cell {:yin.k/cell (second v)}
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
  {:next [:yin.k/cell]
   :put [:yin.k/stream :yin.k/value :yin.k/op-id]
   :ffi [:yin.k/cell :yin.k/call-id :yin.k/op]
   :ffi-request [:yin.k/call-id :yin.k/envelope :yin.k/request-stream :yin.k/response-stream :yin.k/response-cell :yin.k/op-id]
   :link-request [:yin.k/link-id :yin.k/name :yin.k/request-stream :yin.k/response-stream :yin.k/cell :yin.k/envelope :yin.k/op-id]
   :link-response [:yin.k/link-id :yin.k/name :yin.k/request-stream :yin.k/response-stream :yin.k/cell]
   :install [:yin.k/name]})


(defn frame->rows
  "Encode a keyed frame to a frame-rooted version-3 row pack. Values are
   plain literals by default. An explicit value encoder classifies portable
   references as [:row abstract-row] or [:cell id], distinct from guest maps.
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
   The default decoder produces in-band row/cell markers that the default
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
