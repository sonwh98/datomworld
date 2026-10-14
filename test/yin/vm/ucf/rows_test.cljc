(ns yin.vm.ucf.rows-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.jing.cbor-fixtures :as fx]
            [yang.python.antlr.int-contract-fixtures :as ints]
            [yin.vm :as vm]
            [yin.vm.ucf.rows-support :as support]
            [dao.test-slow :as slow]
            [yin.vm.semantic-register.frame-test :as ft]
            [yin.vm.values :as values]
            [yin.vm.engine :as engine]
            [yin.vm.ucf.rows :as rows]))


(defn error
  [f]
  (try (f) nil (catch #?(:cljd Object :clj Throwable :cljs :default) e (ex-data e))))


(defn task-node
  [cursor-cells result]
  (rows/node :task [3 rows/profile :halted :root 0 (rows/node :bindings [])
                    (rows/node :store []) [] (rows/node :parked-table [])
                    (rows/node :cursor-cells (mapcat identity cursor-cells)) {} {} [] [] nil result nil]))


(defn cursor-cell-body
  [stream position]
  (rows/node :cursor-cell-body [(rows/node :stream [stream {:peer stream}]) position]))


(defn fixture
  [a b x y]
  (let [payload [:row (rows/node :map
                                 [[:lit 'both] [:row (rows/node :set [[:cursor-cell x] [:cursor-cell y]])]
                                  [:lit 'one] [:cursor-cell x]
                                  [:lit 'streams] [:row (rows/node :set [[:row (rows/node :stream [a {:peer a}])]
                                                                         [:row (rows/node :stream [b {:peer b}])]])]
                                  [:lit 'cursors] [:row (rows/node :map [[:cursor-cell x] [:lit :x] [:cursor-cell y] [:lit :y]])]])]
        task (task-node [[x (cursor-cell-body a 0)] [y (cursor-cell-body b 0)]] [:lit nil])]
    (rows/node :task (assoc (:slots task) 6 (rows/node :store ['payload payload])))))


(declare portable-frame)


(defn- encoded
  [x]
  (cond (and (map? x) (contains? x :yin.k/row)) [:row (:yin.k/row x)]
        (and (map? x) (contains? x :yin.k/cursor-cell)) [:cursor-cell (:yin.k/cursor-cell x)]
        :else [:lit x]))


(defn- portable-value
  [m x]
  ;; Classify native test values into the decoded codec domain. No seals
  ;; travel. Captures retain their entire act and K; closures retain the
  ;; code address, binders, lexical bindings and module context.
  (cond
    (values/continuation? x)
    {:yin.k/row (rows/node :reified
                           [(rows/decode (rows/frame->rows
                                           (portable-frame m (ft/canonical m (values/payload x) false)) encoded))])}
    (values/closure? x)
    (let [p (values/payload x) e (:env p)]
      {:yin.k/row (rows/node :closure [(get-in m [:code (:segment p) :address]) (:entry p) (:params p)
                                       (rows/node :bindings
                                                  (mapcat (fn [[k v]] [k (encoded (portable-value m v))])
                                                          (sort #(cbor/encoded-compare (first %1) (first %2))
                                                                (engine/without-store-of e))))
                                       (engine/env-store-of e)])})
    (fn? x) {:yin.k/row (rows/node :prim ['fixture/primitive])}
    (and (map? x) (= :cursor-ref (:type x))) {:yin.k/cursor-cell (:id x)}
    (and (map? x) (= :stream-ref (:type x))) {:yin.k/row (rows/node :stream [(:id x) :fixture/served])}
    (coll? x)
    (let [kind (cond (map? x) :map (vector? x) :vec (set? x) :set :else :list)
          xs (if (map? x) (mapcat identity x) x)
          vs (mapv #(encoded (portable-value m %)) xs)]
      (if (some #(not= :lit (first %)) vs)
        {:yin.k/row (rows/node kind vs)}
        x))
    :else x))


(defn portable-frame
  [m f]
  (let [bindings (fn [mappings] (into {} (map (fn [[k v]] [k (portable-value m v)])) mappings))
        env #(update % :yin.k/bindings bindings)]
    (cond-> (update f :yin.k/k #(mapv (fn [k]
                                        (-> k (update :yin.k/env env)
                                            (update :yin.k/window bindings))) %))
      (= :act (:yin.k/state f)) (update :yin.k/env env)
      (= :act (:yin.k/state f)) (update :yin.k/window bindings)
      (contains? f :yin.k/pending) (update :yin.k/pending #(case (:yin.k/reason %)
                                                             :next (assoc % :yin.k/cursor-cell :test/cursor)
                                                             :put (assoc % :yin.k/stream (rows/node :stream [:fixture/w :fixture/served])
                                                                         :yin.k/value "v" :yin.k/op-id nil) %)))))


(deftest bijection
  (doseq [fixture (ft/reference-machines)
          {:keys [frame]} (ft/reference-frames fixture)]
    (let [f (portable-frame (:machine fixture) frame) wire (rows/frame->rows f encoded) back (rows/rows->frame wire)]
      (is (= f back))
      (is (= (:yin.k/root wire) (:yin.k/root (rows/frame->rows back encoded)))))))


(deftest row-ids-are-jing-default
  (let [wire (rows/emit (fixture :stream/a :stream/b :source/x :source/y))]
    (is (seq (:yin.k/rows wire)))
    (doseq [[id & body] (:yin.k/rows wire)]
      (is (= id (jing/segment-key (vec body))))
      (is (= :blake3 (:algorithm (jing/parse-segment-address id)))))))


(deftest carrier-row-algorithm-is-part-of-the-profile
  (let [wire (rows/emit (task-node [] [:lit nil]))
        root (:yin.k/root wire)
        body (subvec (first (filter #(= root (first %)) (:yin.k/rows wire))) 1)
        sha (jing/segment-key body {:algorithm :sha256})
        foreign (-> wire
                    (assoc :yin.k/root sha)
                    (update :yin.k/rows #(mapv (fn [r] (if (= root (first r)) (assoc r 0 sha) r)) %)))
        refusal (error #(rows/decode foreign))]
    (is (jing/segment-matches? sha body))
    (is (= :task (:kind (rows/decode wire))))
    (is (= :yin.k/undecodable (:yin.k/status refusal)))
    (is (= :row-algorithm (:yin.k/kind refusal)))))


(deftest carried-code-verifies-the-address-algorithm
  (let [image [[:const 0 1] [:halt 0]]
        address (jing/segment-key image {:algorithm :sha256})
        task (task-node [] [:lit nil])
        wire (rows/emit (rows/node :task (assoc (:slots task) 11 {address image})))]
    (is (= {address image} (nth (:slots (rows/decode wire)) 11)))
    (is (= :yin.k/hash-mismatch
           (:yin.k/status (error #(rows/decode
                                    (rows/emit (rows/node :task
                                                          (assoc (:slots task) 11 {address [[:const 0 2] [:halt 0]]}))))))))))


(defn- replace-row
  "Rehash the edited row and all ancestors, without canonicalizing slots."
  [wire id body]
  (let [index (into {} (map (fn [r] [(first r) r])) (:yin.k/rows wire))
        memo (atom {})]
    (letfn [(rewrite
              [x]
              (cond
                (contains? index x)
                (or (get @memo x)
                    (let [rbody (if (= x id) body (subvec (get index x) 1))
                          b (mapv rewrite rbody) nid (jing/segment-key b)]
                      (swap! memo assoc x nid)
                      nid))
                (vector? x) (mapv rewrite x)
                :else x))]
      (let [root (rewrite (:yin.k/root wire))
            rs (mapv (fn [[old new]] (into [new] (mapv rewrite (if (= old id) body (subvec (get index old) 1))))) @memo)]
        (assoc wire :yin.k/root root :yin.k/rows rs)))))


(deftest window-sorted-and-unique
  (let [f (assoc ft/worked-frame :yin.k/window {1 :f 3 nil})
        wire (rows/frame->rows f) r (first (filter #(= :window (second %)) (:yin.k/rows wire)))
        id (first r)]
    (doseq [body [[:window 3 [:lit nil] 1 [:lit :f]] [:window 1 [:lit :f] 1 [:lit nil]]]]
      (let [d (error #(rows/rows->frame (replace-row wire id body)))]
        (is (= :yin.k/undecodable (:yin.k/status d)))
        (is (contains? #{:row-order :duplicate-key} (:yin.k/kind d)))))))


(deftest canonical-under-is-renaming-invariant
  (let [a (rows/emit (fixture :lane-a/s1 :lane-a/s2 :source/x :source/y))
        b (rows/emit (fixture :lane-b/s2 :lane-b/s1 :other/y :other/x))
        ca (rows/canonical-under a {:lane-a/s1 :s1 :lane-a/s2 :s2})
        cb (rows/canonical-under b {:lane-b/s2 :s1 :lane-b/s1 :s2})]
    (is (= (:yin.k/root ca) (:yin.k/root cb)))
    (is (= (:yin.k/root ca)
           (:yin.k/root (rows/canonical-under
                          (rows/emit-numbered (fixture :lane-a/s1 :lane-a/s2 :source/x :source/y)
                                              {:source/x :yin.k/cc-2 :source/y :yin.k/cc-1})
                          {:lane-a/s1 :s1 :lane-a/s2 :s2}))))
    (is (= (set (:yin.k/rows ca)) (set (:yin.k/rows cb))))
    (is (= :missing-stream-correspondence
           (:yin.k/kind (error #(rows/canonical-under a {:lane-a/s1 :s1})))))))


(deftest alias-topology-and-census
  (let [x :local/x y :local/y body (cursor-cell-body :s 0)
        a (task-node [[x body] [y body]] [:row (rows/node :vec [[:cursor-cell x] [:cursor-cell x] [:cursor-cell y]])])
        b (task-node [[x body] [y body]] [:row (rows/node :vec [[:cursor-cell x] [:cursor-cell y] [:cursor-cell y]])])
        c (task-node [[x body] [y (cursor-cell-body :s 1)]] [:row (rows/node :vec [[:cursor-cell x] [:cursor-cell x] [:cursor-cell y]])])]
    (is (not= (:yin.k/root (rows/emit a)) (:yin.k/root (rows/emit b))))
    (is (not= (:yin.k/root (rows/emit a)) (:yin.k/root (rows/emit c))))
    (let [wire (rows/emit a)]
      (is (= (:yin.k/root wire) (:yin.k/root (rows/emit (rows/decode wire)))))
      (is (= 2 (count (rows/canonical-numbering a)))))))


(deftest symmetric-cursor-cells-are-interchangeable
  (let [body (cursor-cell-body :s 0)
        make-task (fn [a b] (task-node [[a body] [b body]] [:row (rows/node :set [[:cursor-cell a] [:cursor-cell b]])]))
        a (rows/emit (make-task :x :y)) b (rows/emit (make-task :b :a))]
    (is (= (:yin.k/root a) (:yin.k/root b)))
    (is (= 2 (count (rows/canonical-numbering (make-task :x :y)))))
    (is (= :resource-limit (:yin.k/kind (error #(rows/emit (make-task :x :y) {:work 1})))))
    (is (= :yin.k/unsupported (:yin.k/status (error #(rows/emit (make-task :x :y) {:work 1})))))
    (is (= [:work] (:yin.k/path (error #(rows/emit (make-task :x :y) {:work 1})))))))


(deftest gates-and-graph-before-hashes
  (let [wire (rows/emit (task-node [] [:lit nil]))]
    (doseq [version [0 1 2 4 3.5]]
      (is (= :yin.k/profile-mismatch (:yin.k/status (error #(rows/decode (assoc wire :yin.k/version version)))))))
    (is (= :duplicate-row (:yin.k/kind (error #(rows/decode (update wire :yin.k/rows conj (first (:yin.k/rows wire))))))))
    (is (= :unreachable-row (:yin.k/kind (error #(rows/decode (update wire :yin.k/rows conj [:fake :prim 'f]))))))
    (is (= :row-shape (:yin.k/kind (error #(rows/decode (assoc wire :extra 1))))))
    (let [root (:yin.k/root wire)
          root-row (first (filter #(= root (first %)) (:yin.k/rows wire)))
          store (first (first (filter #(= :store (second %)) (:yin.k/rows wire))))
          forge (fn [i x] (update wire :yin.k/rows (fn [rs] (mapv #(if (= root (first %)) (assoc % i x) %) rs))))]
      ;; Slot n of the task row is at index n + 2.
      (is (= :task-shape (:yin.k/kind (error #(rows/decode (forge 14 5))))))
      (is (= :reference-kind (:yin.k/kind (error #(rows/decode (forge 7 store))))))
      (is (= :yin.k/hash-mismatch (:yin.k/status (error #(rows/decode (forge 17 [:lit 1]))))))
      (is (= :task (second root-row))))
    (let [child (task-node [] [:lit nil])
          parent (rows/emit (rows/node :task (assoc (:slots (task-node [] [:lit nil])) 12
                                                    [(rows/node :install ['a :running nil {} child])])))
          root (:yin.k/root parent)
          cyclic (update parent :yin.k/rows
                         (fn [rs] (mapv #(if (= :install (second %)) (assoc % 6 root) %) rs)))]
      (is (= :cyclic (:yin.k/kind (error #(rows/decode cyclic))))))))


(deftest numbering-precedes-frame-defect
  (let [abstract (fixture :s1 :s2 :x :y)
        slots (:slots abstract)
        abstract (rows/node :task (assoc slots 5 (rows/node :bindings ['yin/def [:lit 1]])))
        wire (rows/emit abstract)
        d (error #(rows/decode (rows/emit-numbered abstract {:x :yin.k/cc-9 :y :yin.k/cc-8})))]
    (is (= :noncanonical-cursor-cells (:yin.k/kind d)))
    (is (= :reserved-name (:yin.k/kind (error #(rows/decode wire))))))
  ;; An unresolved numbering is also reported before the frame defect.
  (let [body (cursor-cell-body :s 0)
        cycle (task-node [[:a body] [:b body]]
                         [:row (rows/node :set [[:row (rows/node :vec [[:cursor-cell :a] [:cursor-cell :b]])]
                                                [:row (rows/node :vec [[:cursor-cell :b] [:cursor-cell :a]])]])])
        abstract (rows/node :task (assoc (:slots cycle) 5 (rows/node :bindings ['yin/def [:lit 1]])))
        wire (rows/emit-numbered abstract {:a :yin.k/cc-1 :b :yin.k/cc-2})
        d (error #(rows/decode wire))]
    (is (= :yin.k/undecodable (:yin.k/status d)))
    (is (= :unresolved-cursor-cells (:yin.k/kind d)))
    (is (= [(:yin.k/root wire) 15] (:yin.k/path d)))))


(deftest schema-and-literal-canonicality
  (let [body (cursor-cell-body :s 0)]
    (is (= :missing-cursor-cell (:yin.k/kind (error #(rows/decode (rows/emit (task-node [] [:cursor-cell :missing])))))))
    ;; The lift refuses these two states itself (totality, tie), so their
    ;; carriers are built under a supplied numbering.
    (is (= :extra-cursor-cell
           (:yin.k/kind (error #(rows/decode (rows/emit-numbered (task-node [[:x body]] [:lit nil]) {:x :yin.k/cc-1}))))))
    (is (= :reference-free-row
           (:yin.k/kind (error #(rows/decode (rows/emit (task-node [] [:row (rows/node :vec [[:lit 1]])])))))))
    (is (= :value-reference-kind
           (:yin.k/kind (error #(rows/decode (rows/emit (task-node [] [:row (rows/node :window [])])))))))
    (let [wire (rows/emit (task-node [] [:lit nil]))]
      (is (= :yin.k/unsupported (:yin.k/status (error #(rows/decode wire {:rows 1}))))))
    (is (= :literal (:yin.k/kind (error #(rows/decode (rows/emit (task-node [] [:lit (cbor/encode 1)])))))))
    ;; Distinct rows whose decoded elements are equal ([x [1]] = [x (1)]).
    (is (= :duplicate-key
           (:yin.k/kind (error #(rows/decode (rows/emit-numbered
                                               (task-node [[:x body]]
                                                          [:row (rows/node :set [[:row (rows/node :vec [[:cursor-cell :x] [:lit [1]]])]
                                                                                 [:row (rows/node :vec [[:cursor-cell :x] [:lit (list 1)]])]])])
                                               {:x :yin.k/cc-1}))))))
    (let [task (task-node [[:x body]] [:cursor-cell :x])
          wire (rows/emit task)
          census (first (filter #(= :cursor-cells (second %)) (:yin.k/rows wire)))]
      (doseq [k [:x :yin.k/cc-01 :yin.k/cc-0 :yin.k/cc- :yin.k/c-1 :yin.k/cur-1]]
        (is (= :slot-kind (:yin.k/kind (error #(rows/decode (replace-row wire (first census) [:cursor-cells k (nth census 3)])))))))
      (is (= :slot-kind (:yin.k/kind (error #(rows/decode (rows/emit-numbered task {})))))))))


(deftest child-scope-and-shared-child-dag
  (let [child (rows/node :task (assoc (:slots (task-node [[:x (cursor-cell-body :child/s 2)]] [:cursor-cell :x])) 3 :child))
        root (task-node [[:x (cursor-cell-body :root/s 1)]] [:cursor-cell :x])
        parent (rows/node :task (assoc (:slots root) 12 [(rows/node :install ['a :running nil {} child])
                                                         (rows/node :install ['b :running nil {} child])]))
        wire (rows/emit parent)]
    (is (= 2 (count (filter #(= :task (second %)) (:yin.k/rows wire)))))
    (is (= (:yin.k/root wire) (:yin.k/root (rows/emit (rows/decode wire)))))
    (is (= (:yin.k/root (rows/canonical-under wire {:child/s :child :root/s :root}))
           (:yin.k/root (rows/emit (rows/decode (rows/canonical-under wire {:child/s :child :root/s :root}))))))))


(deftest pending-envelope-and-nil-literal-are-preserved
  (let [stream (rows/node :stream [:s :descriptor])
        envelope {:call/id :id :arguments [nil {:extra nil}] :unknown nil}
        f (assoc ft/worked-frame :yin.k/pending
                 {:yin.k/reason :ffi-request :yin.k/call-id :id :yin.k/envelope envelope
                  :yin.k/request-stream stream :yin.k/response-stream stream
                  :yin.k/response-cursor-cell :cursor-cell :yin.k/op-id nil})
        wire (rows/frame->rows f)]
    (is (= f (rows/rows->frame wire)))
    (is (contains? (get-in (rows/rows->frame wire) [:yin.k/pending :yin.k/envelope]) :unknown))))


(deftest task-root-checks-carried-code-and-frame
  (let [wait (rows/decode (rows/frame->rows ft/worked-frame))
        base (task-node [[:test/cursor (cursor-cell-body :s 0)]] nil)
        task (rows/node :task (-> (:slots base) (assoc 2 :blocked) (assoc 11 ft/worked-code)
                                  (assoc 13 [wait]) (assoc 16 {:yin.k/policy :yin.k/fork})))
        wire (rows/emit task)
        bad-state (rows/node :act (assoc (:slots (first (:slots wait))) 3 (rows/node :window [])))
        bad-task (rows/node :task (assoc (:slots task) 13 [(rows/node :wait [bad-state (second (:slots wait))])]))
        bad-code [[:halt 99]]]
    (is (= (:yin.k/root wire) (:yin.k/root (rows/emit (rows/decode wire)))))
    (let [d (error #(rows/decode (rows/emit bad-task)))]
      (is (= :window-missing (:yin.k/kind d)))
      (is (= :yin.k/undecodable (:yin.k/status d)))
      (is (jing/parse-segment-address (first (:yin.k/path d)))))
    ;; X1 is judged before any wait.
    (is (= :reserved-name
           (:yin.k/kind (error #(rows/decode (rows/emit (rows/node :task (assoc (:slots bad-task) 5 (rows/node :bindings ['yin/def [:lit 1]])))))))))
    (is (= :code
           (:yin.k/kind (error #(rows/decode (rows/emit (rows/node :task (assoc (:slots task) 11 {(jing/segment-key bad-code) bad-code}))))))))))


(defn- reversed
  "The same abstract task with every :set and :map listed in reverse."
  [n]
  (letfn [(value [v] (if (= :row (first v)) [:row (reversed (second v))] v))]
    (case (:kind n)
      :set (rows/node :set (vec (reverse (mapv value (:slots n)))))
      :map (rows/node :map (vec (mapcat (fn [[k v]] [(value k) (value v)]) (reverse (partition 2 (:slots n))))))
      :store (rows/node :store (vec (mapcat (fn [[k v]] [k (value v)]) (partition 2 (:slots n)))))
      :task (rows/node :task (assoc (:slots n) 6 (reversed (nth (:slots n) 6))))
      n)))


(deftest unordered-containers-are-order-free
  ;; Identity correspondence: only the listed order of :set elements
  ;; (streams and cursor cells) and :map entries differs.
  (let [a (fixture :s1 :s2 :x :y) b (reversed a) ident {:s1 :s1 :s2 :s2}]
    (is (not= a b))
    (is (= (:yin.k/root (rows/emit a)) (:yin.k/root (rows/emit b))))
    (is (= (:yin.k/root (rows/canonical-under (rows/emit a) ident))
           (:yin.k/root (rows/canonical-under (rows/emit b) ident))))))


;; Discovery-order numbering (design note
;; yin.vm.semantic-register-vm.cursor-cell-numbering.md §5, items 12 to 21).

(defn- descriptors
  [kind slots]
  (let [schema (get rows/row-table kind)]
    (cond
      (= schema :pending) (into [:D] (get rows/pending-table (first slots)))
      (= :repeat (first schema)) (vec (repeat (count slots) (second schema)))
      (= :pairs (first schema)) (vec (take (count slots) (cycle (rest schema))))
      :else schema)))


(defn- rename-and-reorder
  "The task with every cursor cell renamed by `renaming` and the members of
   every :set and :map, and the census pairs, listed as (reorder xs) gives;
   install children included."
  [task renaming reorder]
  (letfn [(cell [x] (get renaming x x))
          (value
            [v]
            (case (first v)
              :cursor-cell [:cursor-cell (cell (second v))]
              :row [:row (go (second v))]
              v))
          (go
            [n]
            (let [{:keys [kind slots]} n]
              (case kind
                :set (rows/node :set (vec (reorder (mapv value slots))))
                :map (rows/node :map (vec (mapcat identity (reorder (mapv (fn [[k v]] [(value k) (value v)])
                                                                          (partition 2 slots))))))
                :cursor-cells (rows/node :cursor-cells (vec (mapcat identity (reorder (mapv (fn [[c b]] [(cell c) b])
                                                                                            (partition 2 slots))))))
                (rows/node kind (mapv (fn [d x]
                                        (cond
                                          (and (vector? d) (= :ref (first d))) (go x)
                                          (and (vector? d) (= :refs (first d))) (mapv go x)
                                          (= :C d) (cell x)
                                          (contains? #{:V :optional-V} d) (if x (value x) x)
                                          :else x))
                                      (descriptors kind slots) slots)))))]
    (go task)))


(defn- census-cells
  "Source ids of every census in the tree, install children included."
  [task]
  (let [acc (atom [])]
    (letfn [(go
              [n]
              (when (= :cursor-cells (:kind n)) (swap! acc into (take-nth 2 (:slots n))))
              (doseq [[d x] (map vector (descriptors (:kind n) (:slots n)) (:slots n))]
                (cond
                  (and (vector? d) (= :ref (first d))) (go x)
                  (and (vector? d) (= :refs (first d))) (doseq [c x] (go c))
                  (and (contains? #{:V :optional-V} d) x (= :row (first x))) (go (second x))
                  :else nil)))]
      (go task)
      (vec (distinct @acc)))))


(defn- renamed-reversed
  "Every cursor cell renamed into the r namespace, every set, map and census reversed."
  [task]
  (rename-and-reorder task (into {} (map (fn [c] [c (keyword "r" (str (namespace c) "-" (name c)))])) (census-cells task))
                      (comp vec reverse)))


(defn- verdict
  [d]
  (select-keys d [:yin.k/status :yin.k/kind :yin.k/path]))


(defn- root-of
  [task]
  (:yin.k/root (rows/emit task)))


(defn- c
  [x]
  [:cursor-cell x])


(defn- v
  [xs]
  [:row (rows/node :vec (mapv c xs))])


(defn- s
  [xs]
  [:row (rows/node :set (vec xs))])


(defn- equal-bodies
  [ids result]
  (task-node (mapv #(vector % (cursor-cell-body :s 0)) ids) result))


(defn- review-counterexample
  "'a bound to #{X Y} with equal bodies, 'b bound to X."
  []
  (let [t (equal-bodies [:x :y] [:lit nil])]
    (rows/node :task (assoc (:slots t) 6 (rows/node :store ['a (s [(c :x) (c :y)]) 'b (c :x)])))))


(defn- path-abc
  []
  (equal-bodies [:a :b :c] (s [(v [:a :b]) (v [:b :c])])))


(defn- two-sets-shared
  []
  (equal-bodies [:x :y :z] [:row (rows/node :vec [(s [(c :x) (c :y)]) (s [(c :y) (c :z)])])]))


(defn- two-cycle
  []
  (equal-bodies [:a :b] (s [(v [:a :b]) (v [:b :a])])))


(defn- loop-and-two-cycle
  []
  (equal-bodies [:l :a :b] (s [(v [:l :l]) (v [:a :b]) (v [:b :a])])))


(defn- with-child
  "parent with one install 'a holding child (slot 3 set to :child)."
  [parent child]
  (rows/node :task (assoc (:slots parent) 12
                          [(rows/node :install ['a :running nil {} (rows/node :task (assoc (:slots child) 3 :child))])])))


(defn- frame-task
  "A blocked task carrying the worked code plus a park segment. The wait's
   act holds wait-values in its window register 1 and in one K frame's
   window register 1; env-value, when given, is bound to e0 in every
   environment; the parked state holds parked-value in its K frame's window."
  [census wait-values env-value parked-value]
  (let [park-vector [[:closure 0 [] 2] [:halt 0] [:park 0] [:return 0]]
        park-address (jing/segment-key park-vector)
        wait (rows/decode (rows/frame->rows ft/worked-frame))
        [act pending] (:slots wait)
        [seg pc env _window _k rd] (:slots act)
        [_ store-of] (:slots env)
        base (task-node (conj (vec census) [:test/cursor (cursor-cell-body :s 0)]) nil)
        bindings (rows/node :bindings (if env-value ['e0 env-value] []))
        env (rows/node :env [bindings store-of])
        kframe (fn [x] (rows/node :kframe [seg pc env (rows/node :window [1 x]) rd]))
        [w1 w2] wait-values
        wait-act (rows/node :act [seg pc env (rows/node :window [1 w1]) (rows/node :k (if w2 [(kframe w2)] [])) rd])
        parked (rows/node :act [park-address 3 env (rows/node :window []) (rows/node :k [(kframe parked-value)]) 0])]
    (rows/node :task (-> (:slots base) (assoc 2 :blocked) (assoc 5 bindings)
                         (assoc 11 (assoc ft/worked-code park-address park-vector))
                         (assoc 8 (rows/node :parked-table (if parked-value [7 parked] [])))
                         (assoc 13 [(rows/node :wait [wait-act pending])])
                         (assoc 16 {:yin.k/policy :yin.k/fork})))))


(defn- frame-fixtures
  []
  [(frame-task [[:x (cursor-cell-body :s 0)] [:y (cursor-cell-body :s 0)]]
               [(c :x) (c :y)] (c :y) (s [(c :x) (c :y)]))
   (frame-task [[:x (cursor-cell-body :s 0)] [:y (cursor-cell-body :s 0)]]
               [(s [(c :x) (c :y)])] nil (c :y))])


(deftest canonical-roots-are-pinned
  (is (= :segment/blake3-fab223d9b3540fdce0ccf2132f8a4041917a158a32295470a4936754742d3ac4
         (:yin.k/root (rows/emit (fixture :s1 :s2 :x :y)))))
  (is (= :segment/blake3-c4a7c7e6de3b44400efec5930bc3d85d86b29153b4b2d548acba630bfbc3da7d
         (:yin.k/root (rows/frame->rows ft/worked-frame))))
  ;; The draft §6 case at its smallest: a directed loop and a directed
  ;; 2-cycle over equal-body cursor cells. The loop's key separates it; the
  ;; 2-cycle's two edges tie, share their cursor cells and are refused.
  (let [task (loop-and-two-cycle)
        d (error #(rows/emit task))]
    (is (= {:yin.k/status :yin.k/non-portable :yin.k/kind :unresolved-cursor-cells :yin.k/path [15]} (verdict d)))
    (is (= [:a :b] (:yin.k/cursor-cells d)))
    (is (= (verdict d) (verdict (error #(rows/emit (renamed-reversed task)))))))
  (doseq [[pin task]
          [[:segment/blake3-a850fce6317b426090fa65758805c26b3628efe75d16d507095563c1608ab533
            (equal-bodies [:x :y] [:row (rows/node :vec [(s [(c :x) (c :y)]) (c :x)])])]
           [:segment/blake3-4993b5b192de7d903a90f7bc87dde8c7d5ac007c2b013ef62b998dc988282576
            (equal-bodies [:x :y] (v [:x :x :y]))]
           [:segment/blake3-536c810b07c7cab1dd812fcb1bbf0c7433dc41af4e39037c5f278e82abf1e032
            (equal-bodies [:x :y] (v [:x :y :y]))]
           [:segment/blake3-e67405ecd326c239a84e29a50ca53dfe503fa6baf20c5b79e685c878a40257d7
            (equal-bodies [:x :y :z :w] [:row (rows/node :vec [(s [(s [(c :x) (c :y)]) (s [(c :z) (c :w)])]) (c :x)])])]
           [:segment/blake3-6e7c7a6b0674c41c21e33d9630d4aaf7fc92e4b30234a9c2a1b8c7bae7792f88
            (let [ids (mapv #(keyword "src" (str "x" %)) (range 10))]
              (task-node (mapv (fn [id i] [id (cursor-cell-body :s i)]) ids (range)) (v ids)))]]]
    (is (= pin (root-of task)))
    (is (= pin (root-of (renamed-reversed task))))))


(deftest census-is-numeric-from-ten-cursor-cells
  (let [ids (mapv #(keyword "src" (str "x" %)) (range 10))
        task (task-node (mapv (fn [id i] [id (cursor-cell-body :s i)]) ids (range))
                        [:row (rows/node :vec (mapv #(vector :cursor-cell %) ids))])
        wire (rows/emit task)
        census (first (filter #(= :cursor-cells (second %)) (:yin.k/rows wire)))]
    (is (= (mapv #(keyword "yin.k" (str "cc-" %)) (range 1 11)) (vec (take-nth 2 (drop 2 census)))))
    (is (= (:yin.k/root wire) (:yin.k/root (rows/emit (rows/decode wire)))))))


(deftest marker-shaped-guest-maps-are-not-portable
  (doseq [v [{:yin.k/row 1} {:yin.k/cursor-cell "c"}]]
    (is (= :yin.k/non-portable
           (:yin.k/status (error #(rows/frame->rows (assoc ft/worked-frame :yin.k/window {1 v})))))))
  (let [f (assoc ft/worked-frame :yin.k/window {1 {:yin.k/row 1 :other 2}})]
    (is (= f (rows/rows->frame (rows/frame->rows f)))))
  (let [wire (rows/frame->rows ft/worked-frame)
        window (first (first (filter #(= :window (second %)) (:yin.k/rows wire))))]
    (doseq [v [{:yin.k/row 1} {:yin.k/cursor-cell :c}]]
      (is (= :yin.k/non-portable
             (:yin.k/status (error #(rows/rows->frame (replace-row wire window [:window 1 [:lit v]])))))))))


(deftest well-formed-guest-markers-are-refused
  (doseq [v [{:yin.k/row (rows/node :prim ['guest/f])} {:yin.k/cursor-cell :c}]]
    (is (= :yin.k/non-portable
           (:yin.k/status (error #(rows/frame->rows (assoc ft/worked-frame :yin.k/window {1 v})))))))
  (let [f (assoc ft/worked-frame :yin.k/window {1 {:yin.k/row (rows/node :prim ['guest/f])}})]
    (is (= f (rows/rows->frame (rows/frame->rows f encoded))))))


(deftest every-literal-context-refuses-markers
  (doseq [v [{:yin.k/row 1} {:yin.k/cursor-cell :c}]]
    (doseq [task [(task-node [] [:lit v])
                  (task-node [] [:lit [v]])
                  (task-node [] [:lit (with-meta 'guest v)])
                  (rows/node :task (assoc (:slots (task-node [] [:lit nil])) 6
                                          (rows/node :store ['v [:lit v]])))
                  (rows/node :task (assoc (:slots (task-node [] [:lit nil])) 16 v))]]
      (is (= :yin.k/non-portable (:yin.k/status (error #(rows/emit task))))))
    (let [wire (rows/emit (task-node [] [:lit nil]))
          root (:yin.k/root wire)
          row (first (filter #(= root (first %)) (:yin.k/rows wire)))
          store (first (filter #(= :store (second %)) (:yin.k/rows wire)))]
      (doseq [[id body] [[root (assoc (subvec row 1) 16 [:lit v])]
                         [root (assoc (subvec row 1) 16 [:lit [v]])]
                         [root (assoc (subvec row 1) 16 [:lit (with-meta 'guest v)])]
                         [root (assoc (subvec row 1) 17 v)]
                         [(first store) [:store 'v [:lit v]]]]]
        (is (= :yin.k/non-portable
               (:yin.k/status (error #(rows/decode (replace-row wire id body))))))))))


(deftest cursor-cell-and-identity-shaped-literal-are-distinct
  (let [task (task-node [[:x (cursor-cell-body :s 0)]]
                        [:row (rows/node :set [[:cursor-cell :x] [:lit [:identity :yin.k/cc-1]]])])
        wire (rows/emit task)]
    (is (nil? (error #(rows/decode wire))))
    (is (= (:yin.k/root wire) (:yin.k/root (rows/emit (rows/decode wire)))))))


(defn seven-cursor-cell-triangle-square
  []
  (let [ids [:a :b :c :d :e :f :g]
        edges [[:a :b] [:b :c] [:c :a] [:d :e] [:e :f] [:f :g] [:g :d]]]
    (task-node (mapv #(vector % (cursor-cell-body :s 0)) ids)
               [:row (rows/node :set (mapv (fn [[a b]] [:row (rows/node :vec [[:cursor-cell a] [:cursor-cell b]])]) edges))])))


(deftest seven-cursor-cells-are-refused
  ;; A triangle edge entered first and a square edge entered first give
  ;; different rows, so the tied edges are not interchangeable.
  (let [task (seven-cursor-cell-triangle-square)
        d (error #(rows/emit task))]
    (is (= {:yin.k/status :yin.k/non-portable :yin.k/kind :unresolved-cursor-cells :yin.k/path [15]} (verdict d)))
    (is (= [:a :b :c :d :e :f :g] (:yin.k/cursor-cells d)))
    (is (= (verdict d) (verdict (error #(rows/emit (renamed-reversed task))))))))


(deftest large-literals-in-ordered-positions-are-not-spelled
  (let [large (vec (range 400000))
        base (task-node [[:x (cursor-cell-body :s 0)]] [:lit large])
        ;; Keep the census cursor cell reachable so decode also validates the carrier.
        reachable (rows/node :task (assoc (:slots base) 5
                                          (rows/node :bindings ['cursor [:cursor-cell :x]])))
        custody (rows/node :task (assoc (:slots reachable) 15 [:lit nil]))
        custody (rows/node :task (assoc (:slots custody) 16 large))
        in-set (equal-bodies [:x :y] (s [[:row (rows/node :vec [(c :x) [:lit large]])]
                                         [:row (rows/node :vec [(c :y) [:lit large]])]]))
        refusal (fn [budget]
                  (let [counter (atom {})
                        d (error #(rows/canonical-numbering in-set budget counter))]
                    [(verdict d) @counter]))]
    ;; base's census cursor cell is unreachable: a totality defect, not a refusal.
    (let [d (error #(rows/emit base))]
      (is (= {:missing [:x] :extra []} d))
      (is (nil? (:yin.k/status d))))
    (doseq [task [reachable custody]]
      (let [wire (rows/emit task)
            expected (rows/node :task
                                (-> (:slots task)
                                    (assoc 5 (rows/node :bindings ['cursor [:cursor-cell :yin.k/cc-1]]))
                                    (assoc 9 (rows/node :cursor-cells [:yin.k/cc-1 (cursor-cell-body :s 0)]))))]
        (is (= expected (rows/decode wire)))))
    ;; Inside a guest set the literal is spelled in a key and must be paid
    ;; for before it is encoded; every refusal comes in bounded steps.
    (let [limit {:yin.k/status :yin.k/unsupported :yin.k/kind :resource-limit :yin.k/path [:work]}
          [d used] (refusal {})]
      (is (= limit d))
      (is (> 100000 (:estimator-steps used 0))))
    (let [[d used] (refusal {:work 2000})]
      ;; The depth scan refuses at its 2001st position; the estimator never runs.
      (is (= [:work] (:yin.k/path d)))
      (is (= 2001 (:work used)))
      (is (= 0 (:estimator-steps used 0))))
    (let [[d used] (refusal {:work 900000})]
      ;; The scan's 800k positions pass; the estimator aborts early.
      (is (= [:work] (:yin.k/path d)))
      (is (< 0 (:estimator-steps used 0) 1000)))))


(deftest discovery-order-numbering
  (let [n (fn [& kvs] (into {} (map (fn [[k i]] [k (keyword "yin.k" (str "cc-" i))])) (partition 2 kvs)))]
    (is (= (n :x 1 :y 2) (rows/canonical-numbering (fixture :s1 :s2 :x :y))))
    (is (= (n :y 1 :x 2) (rows/canonical-numbering (review-counterexample))))
    (is (= (n :x 1 :y 2) (rows/canonical-numbering (equal-bodies [:x :y] (v [:x :x :y])))))
    (is (= (n :x 1 :y 2) (rows/canonical-numbering (equal-bodies [:x :y] (v [:x :y :y])))))
    (is (= (n :y 1 :x 2) (rows/canonical-numbering (equal-bodies [:x :y] [:row (rows/node :vec [(s [(c :x) (c :y)]) (c :x)])]))))
    (is (= (n :b 1 :a 2) (rows/canonical-numbering
                           (equal-bodies [:a :b] [:row (rows/node :vec [(s [(s [(v [:a :b]) (v [:b :a])])]) (c :a)])]))))
    (is (= (n :x 1 :y 2 :z 3) (rows/canonical-numbering (two-sets-shared))))
    (is (= (n :a 1 :b 2 :c 3) (rows/canonical-numbering (path-abc))))
    ;; Frames: the free environment (slot 5) is met before the parked table
    ;; (slot 8) and the waits (slot 13).
    (doseq [[task pin] (map vector (frame-fixtures)
                            [:segment/blake3-0b9ef61ebd08535b10e136a6452d942fb0598ad2dd5a13665d7756fac69702b5
                             :segment/blake3-ff124bfc1fd2e6c2b8ce29487204b442b8c1143f71339ccab5efd6e48ccc142f])]
      (is (= (n :y 1 :x 2 :test/cursor 3) (rows/canonical-numbering task)))
      (let [wire (rows/emit task)]
        (is (= pin (:yin.k/root wire)))
        (is (nil? (error #(rows/decode wire))))
        (is (= (:yin.k/root wire) (root-of (renamed-reversed task))))))))


(deftest occurrence-count-separates-members
  (doseq [[pin task]
          [[:segment/blake3-2d9873a8f860440d056768d02172a59f28856f804677a5ac0eaba8a8b6edbcf7 (review-counterexample)]
           [:segment/blake3-726a9fbb35cfe76b6d3caf63c5f7e4ce61d4e73d34efda9c3674eb73db830da1 (path-abc)]
           [:segment/blake3-d84480d4d7e186937a9165be103f7cfaf0d3f4ced83ff65f5be3e906a48107a2 (two-sets-shared)]]]
    (is (= pin (root-of task)))
    (is (= pin (root-of (renamed-reversed task))))))


(deftest interchangeable-ties
  (doseq [[pin task]
          [[:segment/blake3-07eda35d34844aa8e1ce89f07395bd877a49e7a69bdabea55bcf22e600671494
            (equal-bodies [:x :y] (s [(c :x) (c :y)]))]
           [:segment/blake3-da69c679d10e5356b17cdb24909a43d7475df9114bba9c09a8f98ac1a35b971e
            (equal-bodies [:x :y] (s [(v [:x :x]) (v [:y :y])]))]
           [:segment/blake3-49d4478ce74e89a973c9640498342dfea85867eff1662b9656f7e6b9bcd44e93
            (equal-bodies [:x :y] [:row (rows/node :map [(c :x) [:lit 1] (c :y) [:lit 1]])])]
           [:segment/blake3-18b70de6d4533d5ca797464568b5dd7812ca4b2f36a1e8fb64ef823a2eb3da45
            (equal-bodies [:x :y :z :w] (s [(s [(c :x) (c :y)]) (s [(c :z) (c :w)])]))]]]
    (is (= pin (root-of task)))
    (is (= pin (root-of (renamed-reversed task))))))


(deftest alias-pattern-is-part-of-the-key
  (doseq [[pin a b]
          [[:segment/blake3-387f294a2f1963f2f2241e8527d8698dedff5d50de4f46bb762b456a01375090
            (equal-bodies [:x :y :z] (s [(v [:x :x]) (v [:y :z])]))
            (equal-bodies [:x :y :z] (s [(v [:y :z]) (v [:x :x])]))]
           [:segment/blake3-be62ba43d9d07dc7b5e29375e195ab839ab37b721074af816e540615f6167ee9
            (equal-bodies [:x :y :z] [:row (rows/node :map [(c :x) (c :x) (c :y) (c :z)])])
            (equal-bodies [:x :y :z] [:row (rows/node :map [(c :y) (c :z) (c :x) (c :x)])])]
           ;; Balanced aliases: four cursor cells of one body, every count 2;
           ;; only the alias pattern and the local index separate the members.
           [:segment/blake3-5376f133e83957e727611ee977a7b565e8e3c6b41a916f7a1857ecaf84f83907
            (equal-bodies [:x :y :z :w] (s [(v [:x :x :y :y]) (v [:z :w :z :w])]))
            (equal-bodies [:x :y :z :w] (s [(v [:z :w :z :w]) (v [:x :x :y :y])]))]]]
    (is (= pin (root-of a)))
    (is (= pin (root-of b)))
    (is (= pin (root-of (renamed-reversed a)))))
  (let [witness (equal-bodies [:a :b] [:row (rows/node :vec [(s [(s [(v [:a :b]) (v [:b :a])])]) (c :a)])])
        wire (rows/emit witness)]
    (is (= {:b :yin.k/cc-1 :a :yin.k/cc-2} (rows/canonical-numbering witness)))
    (is (= :segment/blake3-0193312113d9c4928b148f220ef82a623c397e1b98cd67fac8712d1fc286a032 (:yin.k/root wire)))
    (is (= (:yin.k/root wire) (root-of (rows/decode wire))))))


(defn- refused-states
  "[name task path cursor-cells] for the states of design note §3."
  []
  (let [body (cursor-cell-body :s 0) body1 (cursor-cell-body :s 1)]
    [["2-cycle" (two-cycle) [15] [:a :b]]
     ["3-cycle" (equal-bodies [:a :b :c] (s [(v [:a :b]) (v [:b :c]) (v [:c :a])])) [15] [:a :b :c]]
     ["loop and 2-cycle" (loop-and-two-cycle) [15] [:a :b]]
     ["seven" (seven-cursor-cell-triangle-square) [15] [:a :b :c :d :e :f :g]]
     ["two equal sets" (equal-bodies [:x :y] [:row (rows/node :vec [(s [(c :x) (c :y)]) (s [(c :x) (c :y)])])]) [15 0] [:x :y]]
     ["mixed bodies" (task-node [[:a body] [:b body1] [:c body1]] (s [(v [:a :b]) (v [:a :c])])) [15] [:a :b :c]]
     ["anchored" (equal-bodies [:a :b :c :d :e]
                               [:row (rows/node :vec [(s [(v [:a :b]) (v [:a :c]) (v [:b :d]) (v [:c :e]) (v [:d :b]) (v [:e :c])])
                                                      (c :a)])])
      [15 0] [:b :c :d :e]]
     ["two 2-cycles" (equal-bodies [:a :b :c :d] (s [(s [(v [:a :b]) (v [:b :a])]) (s [(v [:c :d]) (v [:d :c])])]))
      [15] [:a :b :c :d]]
     ;; Equal incomplete keys, non-isomorphic members: a directed 3-cycle
     ;; beside a 3-edge orientation with a source and a sink.
     ["3-cycle beside fan" (equal-bodies [:a :b :c :d :e :f]
                                         (s [(s [(v [:a :b]) (v [:b :c]) (v [:c :a])]) (s [(v [:d :e]) (v [:f :e]) (v [:d :f])])]))
      [15] [:a :b :c :d :e :f]]
     ["nested set beside edges" (equal-bodies [:x :y :z :w]
                                              [:row (rows/node :vec [(s [(s [(c :x) (c :y)])]) (s [(v [:x :z]) (v [:y :w])])])])
      [15 0 :member] [:w :x :y :z]]]))


(deftest unresolved-cursor-cells-refuse
  (doseq [[label task path cells] (refused-states)]
    (let [d (error #(rows/emit task))
          numbering (zipmap (census-cells task) (map #(keyword "yin.k" (str "cc-" %)) (range 1 100)))
          wire (rows/emit-numbered task numbering)
          decoded (error #(rows/decode wire))]
      (is (= {:yin.k/status :yin.k/non-portable :yin.k/kind :unresolved-cursor-cells :yin.k/path path} (verdict d)) label)
      (is (= cells (:yin.k/cursor-cells d)) label)
      (is (= (verdict d) (verdict (error #(rows/emit (renamed-reversed task))))) label)
      (is (= {:yin.k/status :yin.k/undecodable :yin.k/kind :unresolved-cursor-cells
              :yin.k/path (into [(:yin.k/root wire)] path)}
             (verdict decoded))
          label)))
  ;; An install child's refusal names the install; decode prefixes the root.
  (let [parent (with-child (task-node [] [:lit nil]) (two-cycle))
        wire (rows/emit-numbered parent {} (fn [_child _path] {:a :yin.k/cc-1 :b :yin.k/cc-2}))
        d (error #(rows/decode wire))]
    (is (= {:yin.k/status :yin.k/non-portable :yin.k/kind :unresolved-cursor-cells :yin.k/path [12 'a 15]}
           (verdict (error #(rows/emit parent)))))
    (is (= {:yin.k/status :yin.k/undecodable :yin.k/kind :unresolved-cursor-cells
            :yin.k/path [(:yin.k/root wire) 12 'a 15]}
           (verdict d)))
    (is (= [:yin.k/cc-1 :yin.k/cc-2] (:yin.k/cursor-cells d)))))


(deftest numbering-is-total
  (let [body (cursor-cell-body :s 0)]
    (doseq [[task defect] [[(task-node [[:x body] [:y body]] [:cursor-cell :x]) {:missing [:y] :extra []}]
                           [(task-node [[:x body]] (v [:x :y])) {:missing [] :extra [:y]}]]]
      (let [d (error #(rows/emit task))]
        (is (= defect d))
        (is (nil? (:yin.k/status d)))))))


(deftest gates-and-work
  (let [limit (fn [k] {:yin.k/status :yin.k/unsupported :yin.k/kind :resource-limit :yin.k/path [k]})
        cells (fn [k]
                (let [ids (mapv #(keyword "src" (str "n" %)) (range k))]
                  (task-node (mapv (fn [id i] [id (cursor-cell-body :s i)]) ids (range)) (v ids))))
        deep (fn [k] (equal-bodies [:x] (reduce (fn [inner _] [:row (rows/node :vec [inner])]) (c :x) (range k))))
        deep-literal (reduce (fn [inner _] [inner]) 1 (range 3000))]
    (is (= (limit :cursor-cells) (verdict (error #(rows/emit (cells 65))))))
    (is (= (limit :cursor-cells) (verdict (error #(rows/emit (cells 2) {:cursor-cells 1})))))
    ;; The depth gate runs before any recursive scan of the task.
    (is (= (limit :depth) (verdict (error #(rows/emit (deep 4) {:depth 3})))))
    (is (= (limit :depth) (verdict (error #(rows/emit (deep 3000) {:depth 3})))))
    (is (= (limit :depth) (verdict (error #(rows/emit (deep 3000))))))
    (is (= (limit :depth) (verdict (error #(rows/emit (equal-bodies [:x] [:row (rows/node :vec [(c :x) [:lit deep-literal]])])
                                                      {:depth 3})))))
    (is (= (limit :depth) (verdict (error #(rows/emit (equal-bodies [:x :y] (s [[:row (rows/node :vec [(c :x) [:lit deep-literal]])]
                                                                                (c :y)])))))))
    (is (nil? (error #(rows/decode (rows/emit (deep 200))))))
    ;; Large descriptors are charged once, in the rank labels, not per key.
    (let [big (fn [i] (rows/node :cursor-cell-body [(rows/node :stream [:s (apply str (repeat 65536 "d"))]) i]))
          task (task-node [[:x (big 0)] [:y (big 0)] [:z (big 1)] [:w (big 1)]]
                          (s [(s [(c :x) (c :y)]) (s [(c :z) (c :w)]) (s [(c :x) (c :z)])]))]
      (is (= 4 (count (rows/canonical-numbering task)))))
    (let [task (fixture :s1 :s2 :x :y)]
      (is (= (limit :work) (verdict (error #(rows/emit task {:work 1})))))
      (is (= (limit :work) (verdict (error #(rows/decode (rows/emit task) {:work 1}))))))))


;; Generated states (design note §5 item 18): a portable seeded generator of
;; tasks over one to six cursor cells. Every numbered state's carrier decodes
;; and re-emits to its root; renaming every cursor cell and shuffling every
;; set, map and census gives the same root, or the same refusal and path.

(defn- rng
  [seed]
  (atom (inc (mod seed 2147483646))))


(defn- next-int!
  ;; Park-Miller: every product stays below 2^53, exact on all three hosts.
  [r n]
  (mod (swap! r #(mod (* % 48271) 2147483647)) n))


(defn- shuffler
  [r]
  (fn [xs]
    (reduce (fn [out i]
              (let [j (next-int! r (inc i)) x (nth out i)]
                (-> out (assoc i (nth out j)) (assoc j x))))
            (vec xs) (range (dec (count xs)) 0 -1))))


(defn- decoded
  "Portable decoded equality of an abstract value, as decode checks it."
  [x]
  (case (first x)
    :lit [:lit (second x)]
    :cursor-cell [:identity (second x)]
    :row (let [n (second x) ss (:slots n)]
           (case (:kind n)
             :vec [:seq (mapv decoded ss)]
             :set [:set (set (map decoded ss))]
             :map [:map (into {} (map (fn [[k y]] [(decoded k) (decoded y)])) (partition 2 ss))]
             [:row n]))))


(defn- distinct-by
  [f xs]
  (second (reduce (fn [[seen out] x]
                    (let [k (f x)] (if (contains? seen k) [seen out] [(conj seen k) (conj out x)])))
                  [#{} []] xs)))


(defn- gen-value
  "A random V over cells: literals, cursor cells, vectors, sets, maps with
   cursor-ref keys, sets of cursor-cell pairs (aliases included), sets of sets."
  [r cells depth]
  (let [pick (fn [xs] (nth xs (next-int! r (count xs))))
        cc (fn [] (c (pick cells)))
        some-ref? (fn [xs] (some #(not= :lit (first %)) xs))]
    (case (next-int! r (if (pos? depth) 9 3))
      0 [:lit (pick [1 2 :k "s" nil])]
      (1 2) (cc)
      3 (let [xs (mapv (fn [_] (gen-value r cells (dec depth))) (range (inc (next-int! r 3))))]
          (if (some-ref? xs) [:row (rows/node :vec xs)] [:lit (mapv second xs)]))
      4 (let [xs (distinct-by decoded (mapv (fn [_] (gen-value r cells (dec depth))) (range (inc (next-int! r 4)))))]
          (if (some-ref? xs) [:row (rows/node :set xs)] [:lit (set (map second xs))]))
      5 (let [ps (distinct-by (comp decoded first)
                              (mapv (fn [_] [(gen-value r cells (dec depth)) (gen-value r cells (dec depth))])
                                    (range (inc (next-int! r 3)))))]
          (if (some-ref? (mapcat identity ps))
            [:row (rows/node :map (vec (mapcat identity ps)))]
            [:lit (into {} (map (fn [[a b]] [(second a) (second b)])) ps)]))
      (6 7) (s (distinct-by decoded (mapv (fn [_] [:row (rows/node :vec [(cc) (cc)])]) (range (+ 2 (next-int! r 3))))))
      8 (s (distinct-by decoded (mapv (fn [_] (s (distinct-by decoded (mapv (fn [_] (cc)) (range (inc (next-int! r 3)))))))
                                      (range (+ 2 (next-int! r 2)))))))))


(defn- reached
  [x]
  (case (first x)
    :cursor-cell #{(second x)}
    :row (reduce into #{} (map reached (:slots (second x))))
    #{}))


(defn- gen-task
  "A halted task: one to six cursor cells over two bodies, one to three store
   bindings and the result holding random values."
  [seed]
  (let [r (rng seed)
        cells (mapv #(keyword "src" (str "c" %)) (range (inc (next-int! r 6))))
        bodies (mapv (fn [_] (cursor-cell-body :s (if (zero? (next-int! r 3)) 1 0))) cells)
        names (vec (take (inc (next-int! r 3)) '[a b c]))
        values (mapv (fn [_] (gen-value r cells 3)) names)
        result (gen-value r cells 2)
        used (reduce into (reached result) (map reached values))]
    (if (empty? used)
      (gen-task (+ seed 7919))
      (let [t (task-node (filterv #(contains? used (first %)) (map vector cells bodies)) result)]
        (rows/node :task (assoc (:slots t) 6 (rows/node :store (vec (mapcat vector names values)))))))))


(defn- gen-frame-task
  [seed]
  (let [r (rng seed)
        cells (mapv #(keyword "src" (str "f" %)) (range (inc (next-int! r 4))))
        pick (fn [] (gen-value r cells 2))
        wait-values (mapv (fn [_] (pick)) (range (inc (next-int! r 2))))
        env-value (when (zero? (next-int! r 2)) (pick))
        parked-value (when (zero? (next-int! r 2)) (pick))
        used (reduce into #{} (map reached (remove nil? (conj wait-values env-value parked-value))))]
    (if (empty? used)
      (gen-frame-task (+ seed 7919))
      (frame-task (mapv #(vector % (cursor-cell-body :s 0)) (filterv used cells)) wait-values env-value parked-value))))


(defn- gen-child-task
  "A parent with two installs of one random child task."
  [seed]
  (let [parent (gen-task seed)
        child (rows/node :task (assoc (:slots (gen-task (+ 100000 seed))) 3 :child))]
    (rows/node :task (assoc (:slots parent) 12 [(rows/node :install ['a :running nil {} child])
                                                (rows/node :install ['b :running nil {} child])]))))


(defn- gen-incidence-task
  "The fourth review's tie-heavy incidence families: two to eight cursor
   cells of one body; edge sets, overlapping groups, aliased maps, repeated
   and nested containers, reversed edges, ordered anchors."
  [seed]
  (let [r (rng seed)
        k (+ 2 (next-int! r 7))
        cs (mapv #(keyword "src" (str "i" %)) (range k))
        cc (fn [] (c (nth cs (next-int! r k))))
        vv (fn [xs] [:row (rows/node :vec (vec xs))])
        sd (fn [xs] (s (distinct-by decoded xs)))
        m (fn [ps] [:row (rows/node :map (vec (mapcat identity (distinct-by (comp decoded first) ps))))])
        group (fn [] (sd (mapv (fn [_] (cc)) (range (+ 2 (next-int! r 4))))))
        es (mapv (fn [_] (vv [(cc) (cc)])) (range (+ 2 (next-int! r 9))))
        value (case (mod seed 10)
                0 (sd es)
                1 (vv [(sd es) (sd ((shuffler r) es))])
                2 (sd (mapv (fn [_] (group)) (range 4)))
                3 (vv [(sd es) (cc)])
                4 (sd (mapv #(vv [% (group)]) es))
                5 (m (mapv (fn [x] [(c x) (cc)]) cs))
                6 (vv [(sd es) (sd (mapv #(vv (reverse (:slots (second %)))) es))])
                7 (sd (mapv #(m [[(cc) %] [[:lit :anchor] (group)]]) es))
                8 (sd [(vv [(sd es) (group)]) (vv [(group) (sd ((shuffler r) es))])])
                9 (vv [(sd es) (sd (mapv (fn [_] (group)) (range 4))) (cc)]))]
    (equal-bodies (vec (sort-by str (reached value))) value)))


(defn- outcome
  [task]
  (let [counter (atom {})]
    (try
      (rows/canonical-numbering task rows/default-budget counter)
      (let [wire (rows/emit task)]
        {:root (:yin.k/root wire) :wire wire :work (:work @counter)})
      (catch #?(:cljd Object :clj Throwable :cljs :default) e
        (let [d (ex-data e)]
          (if (= :unresolved-cursor-cells (:yin.k/kind d))
            {:refused (verdict d)}
            {:error (or d (str e))}))))))


(defn- search-states
  "Run the invariance and decode checks over (gen seed) for seeds; return the
   failures and counts."
  [label gen seeds]
  (let [r (rng 11)
        stats (reduce
                (fn [acc seed]
                  (let [task (gen seed)
                        ids (census-cells task)
                        renaming (zipmap ids (map #(keyword "other" (str "z" %)) ((shuffler r) (range (count ids)))))
                        other (rename-and-reorder task renaming (shuffler r))
                        a (outcome task) b (outcome other)]
                    (cond
                      (:error a) (update acc :failures conj [seed :error (:error a)])
                      (:refused a) (cond-> (update acc :refused inc)
                                     (not= (:refused a) (:refused b)) (update :failures conj [seed :refusal (:refused a) b]))
                      :else (let [again (try (root-of (rows/decode (:wire a)))
                                             (catch #?(:cljd Object :clj Throwable :cljs :default) e
                                               {:decode (or (ex-data e) (str e))}))]
                              (cond-> (-> acc (update :numbered inc) (update :max-work max (:work a)))
                                (not= (:root a) (:root b)) (update :failures conj [seed :invariance (:root a) b])
                                (not= (:root a) again) (update :failures conj [seed :decode again]))))))
                {:numbered 0 :refused 0 :max-work 0 :failures []}
                seeds)]
    (println "counterexample-search" label (count seeds) "states:" (dissoc stats :failures)
             "failures:" (count (:failures stats)))
    stats))


(deftest counterexample-search-quick
  (doseq [[label gen seeds] [["random values" gen-task (range 250)]
                             ["frame-bearing" gen-frame-task (range 40)]
                             ["with children" gen-child-task (range 20)]
                             ["incidence families" gen-incidence-task (range 100)]]]
    (let [{:keys [numbered refused failures]} (search-states label gen seeds)]
      (is (= [] failures) label)
      (is (= (count seeds) (+ numbered refused)) label))))


(deftest ^:slow counterexample-search
  (slow/guard
    "counterexample-search"
    (fn []
      (doseq [[label gen seeds] [["random values" gen-task (range 1000 3000)]
                                 ["frame-bearing" gen-frame-task (range 1000 1200)]
                                 ["with children" gen-child-task (range 1000 1100)]
                                 ["incidence families" gen-incidence-task (range 1000 2000)]]]
        (let [{:keys [numbered refused failures]} (search-states label gen seeds)]
          (is (= [] failures) label)
          (is (= (count seeds) (+ numbered refused)) label))))))


(deftest legacy-version-headers-refuse-before-attachment
  (let [pack (rows/emit (task-node [] [:lit nil]))]
    (doseq [version [0 1 2]]
      (let [attached (atom 0)
            refusal (error #(rows/lower-task
                              (support/new-machine)
                              (assoc pack :yin.k/version version)
                              (fn [_] (swap! attached inc))))]
        (is (= {:yin.k/status :yin.k/profile-mismatch
                :yin.k/kind :version :yin.k/path [:yin.k/version]}
               refusal))
        (is (zero? @attached))))))


(deftest numeric-scalars-round-trip-through-rows
  (let [payload (reduce (fn [m n] (assoc m n (ints/value n)))
                        {:floats (mapv cbor/float64
                                       [0.0 -0.0 ##NaN ##Inf 5e-324
                                        (ints/float-value "f:2^53")
                                        (ints/float-value "f:2^63")])
                         :strings ["plain" "😀"] :true true :none nil}
                        ints/int-names)
        ;; Literal :data slots use plain-data?, which accepts float64 but
        ;; not JS/Dart BigInt carriers. Keep the full JVM literal coverage;
        ;; the other AST lanes use integers native on every host. The full
        ;; machine payload still crosses the halted-result path on all hosts.
        literal-payload #?(:clj payload
                           :default (select-keys payload
                                                 [:floats :strings :true :none
                                                  "0" "1" "-1" "2^31-1" "2^31"
                                                  "2^31+1" "2^32" "2^53-1" "-2^53+1"]))
        encoded #(fx/bytes->hex (cbor/encode %))]
    (doseq [[expected machine]
            [[payload (assoc (support/halted-machine) :value payload)]
             [literal-payload (vm/run (support/load-ast (support/new-machine)
                                                        (support/lit literal-payload)))]]]
      (let [pack (support/lift machine nil)
            restored (rows/lower-task (support/new-machine) pack (fn [_] nil))]
        (is (vm/halted? restored))
        (is (= (encoded expected) (encoded (vm/value restored))))))
    (let [fixture (ints/read-file)]
      (doseq [n ints/int-names]
        (is (= (ints/column fixture n "cbor") (encoded (ints/value n))) n)))))


(deftest lowering-isolates-task-state-from-a-populated-receiver
  (doseq [[label source path expected]
          [[:reader (vm/run (support/load-ast
                              (support/new-machine)
                              (support/then
                                (support/def! 'reader-owned (support/lit 7))
                                (support/next-of
                                  (support/cursor-of {:type :stream/make :buffer 4})))))
            [] "B"]
           [:install-child (support/parked-installer) [:installs 'host.mod :vm] 42]]]
    (testing (name label)
      (let [pack (support/lift source nil)
            ;; Share attached handles so install records can be compared
            ;; directly, including the child's entire VM. No constructor
            ;; runs during either lower; the task's streams are attached.
            handles (atom {})
            attach! (fn [descriptor]
                      (let [id (:dao.stream/identity descriptor)
                            h (or (get @handles id)
                                  (let [h (support/one-slot-stream id)]
                                    (swap! handles assoc id h)
                                    h))]
                        {:dao.stream/outcome :dao.stream/ok :dao.stream/handle h}))
            fresh (support/receiver {:make-stream nil :attach-stream attach!})
            receiver-stream (support/one-slot-stream "receiver-only")
            ;; A fixed composition key is already reserved by lower-task,
            ;; so adding it does not rename the task's fresh keys. This
            ;; permits exact equality of all seven fields, without masking
            ;; differences behind a relift or a normalization of VM state.
            resource-key vm/call-in-stream-key
            populated (assoc (support/load-ast fresh (support/lit :receiver-only))
                             :store (assoc {} 'receiver-only :wrong)
                             :module-stores (assoc {} 'receiver.mod (assoc {} 'x :wrong))
                             :parked (assoc {} :receiver-only {:type :parked-continuation})
                             :installs (assoc {} 'receiver.mod {:phase :running})
                             :wait-set [{:reason :next :receiver-only true}]
                             :ready-queue [{:receiver-only true}]
                             :resources (assoc {} resource-key receiver-stream))
            clean (rows/lower-task fresh pack attach!)
            restored (rows/lower-task populated pack attach!)
            fields [:code :store :module-stores :parked :installs :wait-set :ready-queue]
            clean-task (get-in clean path)
            restored-task (get-in restored path)
            stream-key (:stream-id (first (:wait-set restored-task)))
            cursor-key (get-in restored-task [:wait-set 0 :cursor-ref :id])
            ;; Also occupy the exact key the clean task would use. This
            ;; lower must allocate different keys, rather than overwrite
            ;; or reuse the receiver's stream (including its cursor key).
            collision-receiver (assoc populated :resources (assoc {} stream-key receiver-stream))
            collision (when (= :reader label)
                        (rows/lower-task collision-receiver pack attach!))
            collision-task (get-in collision path)
            collision-stream-key (:stream-id (first (:wait-set collision-task)))
            collision-cursor-key (get-in collision-task [:wait-set 0 :cursor-ref :id])]
        (is (seq (:code populated)) "the receiver already owns code")
        (doseq [field fields]
          (is (not= (get populated field) (get clean field))
              (str field " has receiver-only state to discard"))
          (is (= (get clean field) (get restored field))
              (str field " equals a fresh receiver's lower"))
          (is (= (get clean-task field) (get restored-task field))
              (str field " is isolated in the resumed task, including an install child")))
        (is (not (contains? (:store restored) 'receiver-only)))
        (is (not (contains? (:module-stores restored) 'receiver.mod)))
        (is (not (contains? (:parked restored) :receiver-only)))
        (is (not (contains? (:installs restored) 'receiver.mod)))
        (is (not-any? :receiver-only (:wait-set restored)))
        (is (= receiver-stream (get (:resources restored) resource-key))
            "resource merging retains the receiver's composition stream")
        (is (not (contains? (:resources populated) stream-key)))
        (is (not (contains? (:resources populated) cursor-key)))
        (is (contains? (:resources restored-task) stream-key))
        (is (contains? (:resources restored-task) cursor-key))
        (when collision
          (is (= receiver-stream (get (:resources collision) stream-key)))
          (is (not (contains? (:resources collision-receiver) collision-stream-key)))
          (is (not (contains? (:resources collision-receiver) collision-cursor-key))))
        (is (= (:stream-id (first (:wait-set clean-task))) stream-key))
        (is (= :next (:reason (first (:wait-set restored-task)))))
        (is (empty? (:ready-queue restored-task)))
        (doseq [m (cond-> [clean restored] collision (conj collision))]
          (let [task (get-in m path)
                _ (is (= :dao.stream/ok
                         (:dao.stream/outcome
                           (stream/append! (support/stream-of task) "B"))))
                done (vm/run (vm/run m))]
            (is (vm/halted? done))
            (is (= expected (vm/value done))
                "the source read resumes to the same value in every receiver")))))))


(deftest result-only-values-complete-the-census
  (doseq [[f expected] [[(fn [a _b] [a a]) 1]
                        [(fn [a b] [a b]) 2]]]
    (let [pack (support/lift (support/halted-with-cursors f) nil)
          {:keys [attach! count]} (support/attacher)
          recv (rows/lower-task (support/new-machine) pack attach!)
          [a b] (vm/value recv)]
      (is (= expected @count))
      (is (= expected (clojure.core/count (filter #(= :cursor-cell-body (second %)) (:yin.k/rows pack)))))
      (is (= (= 1 expected) (= a b)))))
  (doseq [m [(vm/run (support/load-ast (support/new-machine)
                                       (support/lam [] (support/lit 1))))
             (support/halted-result-closure)]]
    (let [pack (support/lift m nil)
          {:keys [attach! count]} (support/attacher)
          recv (rows/lower-task (support/new-machine) pack attach!)]
      (is (values/closure? (vm/value recv)))
      (is (seq (:code recv)) "the result-only closure carries its code")
      (when (seq (:module-stores m))
        (is (= 2 @count))
        (is (= #{'c 'd} (set (keys (get-in recv [:module-stores 'host.mod])))))))))
