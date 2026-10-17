(ns yin.vm.ucf.rows-test
  (:require [clojure.test :refer [deftest is]]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [yin.vm.semantic-register.frame-test :as ft]
            [yin.vm.values :as values]
            [yin.vm.engine :as engine]
            [yin.vm.ucf.rows :as rows]))


(defn error
  [f]
  (try (f) nil (catch #?(:cljd Object :clj Throwable :cljs :default) e (ex-data e))))


(defn task-node
  [cells result]
  (rows/node :task [3 rows/profile :halted :root 0 (rows/node :bindings [])
                    (rows/node :store []) [] (rows/node :parked-table [])
                    (rows/node :cells (mapcat identity cells)) {} {} [] [] nil result nil]))


(defn cell-body
  [stream position]
  (rows/node :cell-body [(rows/node :stream [stream {:peer stream}]) position]))


(defn fixture
  [a b x y]
  (let [payload [:row (rows/node :map
                                 [[:lit 'both] [:row (rows/node :set [[:cell x] [:cell y]])]
                                  [:lit 'one] [:cell x]
                                  [:lit 'streams] [:row (rows/node :set [[:row (rows/node :stream [a {:peer a}])]
                                                                         [:row (rows/node :stream [b {:peer b}])]])]
                                  [:lit 'cursors] [:row (rows/node :map [[:cell x] [:lit :x] [:cell y] [:lit :y]])]])]
        task (task-node [[x (cell-body a 0)] [y (cell-body b 0)]] [:lit nil])]
    (rows/node :task (assoc (:slots task) 6 (rows/node :store ['payload payload])))))


(declare portable-frame)


(defn- encoded
  [x]
  (cond (and (map? x) (contains? x :yin.k/row)) [:row (:yin.k/row x)]
        (and (map? x) (contains? x :yin.k/cell)) [:cell (:yin.k/cell x)]
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
    (and (map? x) (= :cursor-ref (:type x))) {:yin.k/cell (:id x)}
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
                                                             :next (assoc % :yin.k/cell :test/cursor)
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
                                              {:source/x :yin.k/c-2 :source/y :yin.k/c-1})
                          {:lane-a/s1 :s1 :lane-a/s2 :s2}))))
    (is (= (set (:yin.k/rows ca)) (set (:yin.k/rows cb))))
    (is (= :missing-stream-correspondence
           (:yin.k/kind (error #(rows/canonical-under a {:lane-a/s1 :s1})))))))


(deftest alias-topology-and-census
  (let [x :local/x y :local/y body (cell-body :s 0)
        a (task-node [[x body] [y body]] [:row (rows/node :vec [[:cell x] [:cell x] [:cell y]])])
        b (task-node [[x body] [y body]] [:row (rows/node :vec [[:cell x] [:cell y] [:cell y]])])
        c (task-node [[x body] [y (cell-body :s 1)]] [:row (rows/node :vec [[:cell x] [:cell x] [:cell y]])])]
    (is (not= (:yin.k/root (rows/emit a)) (:yin.k/root (rows/emit b))))
    (is (not= (:yin.k/root (rows/emit a)) (:yin.k/root (rows/emit c))))
    (let [wire (rows/emit a)]
      (is (= (:yin.k/root wire) (:yin.k/root (rows/emit (rows/decode wire)))))
      (is (= 2 (count (rows/canonical-numbering a)))))))


(deftest refinement-search-on-symmetric-cells-is-bounded
  (let [body (cell-body :s 0)
        make-task (fn [a b] (task-node [[a body] [b body]] [:row (rows/node :set [[:cell a] [:cell b]])]))
        a (rows/emit (make-task :x :y)) b (rows/emit (make-task :b :a))]
    (is (= (:yin.k/root a) (:yin.k/root b)))
    (is (= 2 (count (rows/canonical-numbering (make-task :x :y)))))
    (is (= :resource-limit (:yin.k/kind (error #(rows/emit (make-task :x :y) {:search 1})))))
    (is (= :yin.k/unsupported (:yin.k/status (error #(rows/emit (make-task :x :y) {:search 1})))))))


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
        d (error #(rows/decode (rows/emit-numbered abstract {:x :yin.k/c-9 :y :yin.k/c-8})))]
    (is (= :noncanonical-cells (:yin.k/kind d)))
    (is (= :reserved-name (:yin.k/kind (error #(rows/decode wire)))))))


(deftest schema-and-literal-canonicality
  (let [body (cell-body :s 0)]
    (is (= :missing-cell (:yin.k/kind (error #(rows/decode (rows/emit (task-node [] [:cell :missing])))))))
    (is (= :extra-cell (:yin.k/kind (error #(rows/decode (rows/emit (task-node [[:x body]] [:lit nil])))))))
    (is (= :reference-free-row
           (:yin.k/kind (error #(rows/decode (rows/emit (task-node [] [:row (rows/node :vec [[:lit 1]])])))))))
    (is (= :value-reference-kind
           (:yin.k/kind (error #(rows/decode (rows/emit (task-node [] [:row (rows/node :window [])])))))))
    (let [wire (rows/emit (task-node [] [:lit nil]))]
      (is (= :yin.k/unsupported (:yin.k/status (error #(rows/decode wire {:rows 1}))))))
    (is (= :literal (:yin.k/kind (error #(rows/decode (rows/emit (task-node [] [:lit (cbor/encode 1)])))))))
    ;; Distinct rows whose decoded elements are equal ([x [1]] = [x (1)]).
    (is (= :duplicate-key
           (:yin.k/kind (error #(rows/decode (rows/emit (task-node [[:x body]]
                                                                   [:row (rows/node :set [[:row (rows/node :vec [[:cell :x] [:lit [1]]])]
                                                                                          [:row (rows/node :vec [[:cell :x] [:lit (list 1)]])]])])))))))
    (let [task (task-node [[:x body]] [:cell :x])
          wire (rows/emit task)
          census (first (filter #(= :cells (second %)) (:yin.k/rows wire)))]
      (doseq [k [:x :yin.k/c-01 :yin.k/c-0 :yin.k/c-]]
        (is (= :slot-kind (:yin.k/kind (error #(rows/decode (replace-row wire (first census) [:cells k (nth census 3)])))))))
      (is (= :slot-kind (:yin.k/kind (error #(rows/decode (rows/emit-numbered task {})))))))))


(deftest child-scope-and-shared-child-dag
  (let [child (rows/node :task (assoc (:slots (task-node [[:x (cell-body :child/s 2)]] [:cell :x])) 3 :child))
        root (task-node [[:x (cell-body :root/s 1)]] [:cell :x])
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
                  :yin.k/response-cell :cell :yin.k/op-id nil})
        wire (rows/frame->rows f)]
    (is (= f (rows/rows->frame wire)))
    (is (contains? (get-in (rows/rows->frame wire) [:yin.k/pending :yin.k/envelope]) :unknown))))


(deftest task-root-checks-carried-code-and-frame
  (let [wait (rows/decode (rows/frame->rows ft/worked-frame))
        base (task-node [[:test/cursor (cell-body :s 0)]] nil)
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
  ;; (streams and cells) and :map entries differs.
  (let [a (fixture :s1 :s2 :x :y) b (reversed a) ident {:s1 :s1 :s2 :s2}]
    (is (not= a b))
    (is (= (:yin.k/root (rows/emit a)) (:yin.k/root (rows/emit b))))
    (is (= (:yin.k/root (rows/canonical-under (rows/emit a) ident))
           (:yin.k/root (rows/canonical-under (rows/emit b) ident))))))


(deftest canonical-roots-are-pinned
  (is (= :segment/blake3-be82d50b14804bb7a69103631b9c45759b49b67dbb3cebe75a1259fed6b4a937
         (:yin.k/root (rows/emit (fixture :s1 :s2 :x :y)))))
  (is (= :segment/blake3-c4a7c7e6de3b44400efec5930bc3d85d86b29153b4b2d548acba630bfbc3da7d
         (:yin.k/root (rows/frame->rows ft/worked-frame))))
  ;; The draft §6 case at its smallest: a directed loop and a directed
  ;; 2-cycle over equal-body cells leave all three cells in one class, and
  ;; no automorphism exchanges the loop with a 2-cycle vertex.
  (let [shape (fn [[l a b]]
                [[:row (rows/node :vec [[:cell l] [:cell l]])]
                 [:row (rows/node :vec [[:cell a] [:cell b]])]
                 [:row (rows/node :vec [[:cell b] [:cell a]])]])
        graph (fn [ids edges] (task-node (mapv #(vector % (cell-body :s 0)) ids) [:row (rows/node :set edges)]))
        task (graph [:l :a :b] (shape [:l :a :b]))
        renamed (graph [:z/b :z/a :z/l] (vec (reverse (shape [:z/l :z/a :z/b]))))
        numbering (rows/canonical-numbering task)
        root (:yin.k/root (rows/emit task))]
    (is (= :resource-limit (:yin.k/kind (error #(rows/emit task {:search 2})))))
    (is (= :segment/blake3-8899f95d330a174ee098e10f16ecf2d017d5aae67d4f50d8369222342e6d36e9 root))
    (is (= root (:yin.k/root (rows/emit renamed))))
    (is (= :yin.k/c-3 (numbering :l)))
    (is (= (:z/l (rows/canonical-numbering renamed)) (numbering :l)))))


(deftest census-is-numeric-from-ten-cells
  (let [ids (mapv #(keyword "src" (str "x" %)) (range 10))
        task (task-node (mapv (fn [id i] [id (cell-body :s i)]) ids (range))
                        [:row (rows/node :vec (mapv #(vector :cell %) ids))])
        wire (rows/emit task)
        census (first (filter #(= :cells (second %)) (:yin.k/rows wire)))]
    (is (= (mapv #(keyword "yin.k" (str "c-" %)) (range 1 11)) (vec (take-nth 2 (drop 2 census)))))
    (is (= (:yin.k/root wire) (:yin.k/root (rows/emit (rows/decode wire)))))))


(deftest marker-shaped-guest-maps-are-not-portable
  (doseq [v [{:yin.k/row 1} {:yin.k/cell "c"}]]
    (is (= :yin.k/non-portable
           (:yin.k/status (error #(rows/frame->rows (assoc ft/worked-frame :yin.k/window {1 v})))))))
  (let [f (assoc ft/worked-frame :yin.k/window {1 {:yin.k/row 1 :other 2}})]
    (is (= f (rows/rows->frame (rows/frame->rows f)))))
  (let [wire (rows/frame->rows ft/worked-frame)
        window (first (first (filter #(= :window (second %)) (:yin.k/rows wire))))]
    (doseq [v [{:yin.k/row 1} {:yin.k/cell :c}]]
      (is (= :yin.k/non-portable
             (:yin.k/status (error #(rows/rows->frame (replace-row wire window [:window 1 [:lit v]])))))))))


(deftest well-formed-guest-markers-are-refused
  (doseq [v [{:yin.k/row (rows/node :prim ['guest/f])} {:yin.k/cell :c}]]
    (is (= :yin.k/non-portable
           (:yin.k/status (error #(rows/frame->rows (assoc ft/worked-frame :yin.k/window {1 v})))))))
  (let [f (assoc ft/worked-frame :yin.k/window {1 {:yin.k/row (rows/node :prim ['guest/f])}})]
    (is (= f (rows/rows->frame (rows/frame->rows f encoded))))))


(deftest every-literal-context-refuses-markers
  (doseq [v [{:yin.k/row 1} {:yin.k/cell :c}]]
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


(deftest cell-and-identity-shaped-literal-are-distinct
  (let [task (task-node [[:x (cell-body :s 0)]]
                        [:row (rows/node :set [[:cell :x] [:lit [:identity :yin.k/c-1]]])])
        wire (rows/emit task)]
    (is (nil? (error #(rows/decode wire))))
    (is (= (:yin.k/root wire) (:yin.k/root (rows/emit (rows/decode wire)))))))


(defn seven-cell-triangle-square
  []
  (let [ids [:a :b :c :d :e :f :g]
        edges [[:a :b] [:b :c] [:c :a] [:d :e] [:e :f] [:f :g] [:g :d]]]
    (task-node (mapv #(vector % (cell-body :s 0)) ids)
               [:row (rows/node :set (mapv (fn [[a b]] [:row (rows/node :vec [[:cell a] [:cell b]])]) edges))])))


(deftest intermediate-label-bytes-are-bounded
  (let [d (error #(rows/canonical-numbering (seven-cell-triangle-square)))]
    (is (= :resource-limit (:yin.k/kind d)))
    (is (= [:label-bytes] (:yin.k/path d)))))
