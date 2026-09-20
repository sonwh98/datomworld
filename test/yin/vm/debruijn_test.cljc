(ns yin.vm.debruijn-test
  "D0+D1 of docs/design/yin.vm.debruijn-projection.md: the published
   :yin.debruijn/* dimension with its hash domain, §5's canonical value
   table and NFC seam, §2's root framing and input validation, and §3's
   scope resolution — all exercised over the emitter's own datoms. Node
   hashes are D2/D3; nothing here asserts a fingerprint."
  (:require [clojure.test :refer [are deftest is testing]]
            [dao.jing :as jing]
            [yin.vm :as vm]
            [yin.vm.debruijn :as d]
            [yin.vm.engine :as engine]))


;; =============================================================================
;; Fixtures: AST builders, the emitter's datoms, and defect capture
;; =============================================================================

(defn- lit
  [v]
  {:type :literal, :value v})


(defn- v
  [s]
  {:type :variable, :name s})


(defn- lam
  [params body]
  {:type :lambda, :params params, :body body})


(defn- app
  [op & args]
  {:type :application, :operator op, :operands (vec args)})


(defn- if-node
  [t c a]
  {:type :if, :test t, :consequent c, :alternate a})


(defn- tail
  [node]
  (assoc node :tail? true))


(defn- datoms-of
  [ast]
  (second (vm/ast->datoms-with-root ast)))


(defn- project
  [ast]
  (d/project-datoms (datoms-of ast)))


(defn- throws-data
  "The ex-data of the exception `thunk` throws, or nil when it returns."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj Exception :cljs js/Error :cljd Object) e
         (or (ex-data e) {}))))


(def ^:private worked-example
  "The essay's `(fn [count] (+ count 1))`."
  (lam '[count] (app (v '+) (v 'count) (lit 1))))


(def ^:private every-type
  "One term exercising every §2 grammar type. The store-put value is a
   plain scalar: §2 gives :vm/store-put no children, so :yin/value is a
   scalar slot there."
  (app (lam '[x y]
            (if-node (app (v '<) (v 'x) (lit 1))
                     {:type :stream/put,
                      :target {:type :stream/make, :buffer 4},
                      :val {:type :stream/next,
                            :source {:type :stream/cursor,
                                     :source {:type :stream/close,
                                              :source {:type :vm/store-get,
                                                       :key 'k}}}}}
                     (app (v 'list)
                          (lit {:a [1 2]})
                          {:type :vm/store-put, :key 'k, :val 5}
                          {:type :vm/gensym, :prefix "g"}
                          {:type :vm/current-continuation}
                          {:type :vm/resume, :parked-id :p1, :val (lit 7)}
                          {:type :dao.stream.apply/call,
                           :op :op/echo,
                           :operands [(lit 1)]}
                          (if-node (lit true) {:type :vm/park} (lit nil)))))
       (lit 3)))


;; =============================================================================
;; D0: the published dimension
;; =============================================================================

(deftest the-dimension-is-published-with-its-hash-domain
  (let [dimension (into {} (map (fn [[_s a val]] [a val]) d/descriptor))]
    (is (= 22 (:dim/arity dimension)))
    (is (= 22 (count (:dim/slots dimension))))
    (is (= [:yin.debruijn/hash :yin.debruijn/type :yin.debruijn/arity
            :yin.debruijn/bound :yin.debruijn/free :yin.debruijn/value
            :yin.debruijn/op :yin.debruijn/key :yin.debruijn/prefix
            :yin.debruijn/buffer :yin.debruijn/parked-id :yin.debruijn/macro?
            :yin.debruijn/body :yin.debruijn/operator
            :yin.debruijn/operands :yin.debruijn/test :yin.debruijn/consequent
            :yin.debruijn/alternate :yin.debruijn/target :yin.debruijn/val-node
            :yin.debruijn/source :yin.debruijn/root]
           (mapv first (:dim/slots dimension)))
        "the slots are declared in §4's order")
    (is (= [:ordered-vector :ref]
           (nth (some #(when (= :yin.debruijn/operands (first %)) %)
                      (:dim/slots dimension))
                1))
        "the ordered-vector slot type is declared")
    (is (= :sha256 (get-in dimension [:dim/encoding :hash])))
    (is (= [:yin.debruijn/hash :yin.debruijn/root]
           (get-in dimension [:dim/encoding :hash-excluded-slots])))
    (is (= d/canonical-value-table (get-in dimension [:dim/encoding :values])))
    (is (= #{:d1 :d3} (set (map first (:dim/projection-to dimension)))))
    (is (= :d5 (ffirst (:dim/lift-from dimension))))
    (is (= (jing/content-hash d/descriptor) d/dimension-hash)
        "the domain separator is the descriptor's content hash, datom.md's
         dimension identity — not an ad hoc string")
    (is (re-matches #"^[0-9a-f]{64}$" d/dimension-hash))))


;; =============================================================================
;; D0: the canonical value table as data and validation (§5)
;; =============================================================================

(deftest canonical-classes-are-the-tables
  (are [x expected] (= expected (d/canonical-class x))
    nil :nil
    true :bool
    "s" :string
    :ns/k :keyword
    'sym :symbol
    7 :int64
    7.0 :int64
    1.5 :double
    (/ 0.0 0.0) :double
    (/ 1.0 0.0) :double
    (/ -1.0 0.0) :double
    -0.0 :double
    0.0 :int64
    9.3e18 :double
    [1 2.5] :vector
    (list 1 2.5) :list
    {:a 1} :map
    #{1} :set
    [nil "x" :k] :vector
    {:a [1.0 #{2}]} :map
    [(fn [x] x)] nil
    {"k" (fn [x] x)} nil)
  (is (d/canonical-value? [1 {:a #{2}}]))
  (is (not (d/canonical-value? {:a (fn [x] x)}))))


(deftest jvm-out-of-domain-values-diagnose
  ;; §5's out-of-domain numerics, asserted where the host can express them
  #?(:clj (are [x] (nil? (d/canonical-class x))
            5N
            (bigint 9223372036854775808)
            1/2
            (java.math.BigDecimal. "1.1")
            \a
            [1 \c])))


(deftest a-javascript-number-is-int64-only-when-safe
  ;; an unsafe integer's exact classification is not recoverable (§5)
  #?(:cljs (do
             (is (= :int64 (d/canonical-class 9007199254740991)))
             (is (nil? (d/canonical-class 9007199254740992)))
             (is (nil? (d/canonical-class -9007199254740992))))))


(deftest the-nfc-seam-composes-on-every-host
  (let [decomposed "e\u0301", composed "\u00e9"]
    (is (= composed (d/normalize-nfc decomposed)))
    (is (= composed (d/normalize-nfc composed)))
    (is (= "abc" (d/normalize-nfc "abc")))))


;; =============================================================================
;; D0: the walked node grammar (§2)
;; =============================================================================

(deftest the-node-grammar-is-the-designs-walk-vocabulary
  (is (= {:literal []
          :variable []
          :lambda [[:yin/body :node]]
          :application [[:yin/operator :node] [:yin/operands :nodes]]
          :dao.stream.apply/call [[:yin/operands :nodes]]
          :if [[:yin/test :node] [:yin/consequent :node] [:yin/alternate :node]]
          :vm/gensym []
          :vm/store-get []
          :vm/store-put []
          :vm/current-continuation []
          :vm/park []
          :vm/resume [[:yin/val-node :node]]
          :stream/make []
          :stream/put [[:yin/target :node] [:yin/val-node :node]]
          :stream/cursor [[:yin/source :node]]
          :stream/next [[:yin/source :node]]
          :stream/close [[:yin/source :node]]}
         (into {} (map (fn [[tag grammar]] [tag (mapv identity (:children grammar))]))
               d/node-grammar))))


(deftest every-node-type-projects-in-grammar-order
  (is (= {:yin.debruijn/type :application,
          :yin.debruijn/operator
          {:yin.debruijn/type :lambda,
           :yin.debruijn/arity 2,
           :yin.debruijn/body
           {:yin.debruijn/type :if,
            :yin.debruijn/test {:yin.debruijn/type :application,
                                :yin.debruijn/operator {:yin.debruijn/type :variable,
                                                        :yin.debruijn/free '<},
                                :yin.debruijn/operands [{:yin.debruijn/type :variable,
                                                         :yin.debruijn/bound [0 0]}
                                                        {:yin.debruijn/type :literal,
                                                         :yin.debruijn/value 1}]},
            :yin.debruijn/consequent
            {:yin.debruijn/type :stream/put,
             :yin.debruijn/target {:yin.debruijn/type :stream/make,
                                   :yin.debruijn/buffer 4},
             :yin.debruijn/val-node
             {:yin.debruijn/type :stream/next,
              :yin.debruijn/source {:yin.debruijn/type :stream/cursor,
                                    :yin.debruijn/source {:yin.debruijn/type :stream/close,
                                                          :yin.debruijn/source {:yin.debruijn/type :vm/store-get,
                                                                                :yin.debruijn/key 'k}}}}},
            :yin.debruijn/alternate
            {:yin.debruijn/type :application,
             :yin.debruijn/operator {:yin.debruijn/type :variable,
                                     :yin.debruijn/free 'list},
             :yin.debruijn/operands
             [{:yin.debruijn/type :literal, :yin.debruijn/value {:a [1 2]}}
              {:yin.debruijn/type :vm/store-put,
               :yin.debruijn/value 5,
               :yin.debruijn/key 'k}
              {:yin.debruijn/type :vm/gensym, :yin.debruijn/prefix "g"}
              {:yin.debruijn/type :vm/current-continuation}
              {:yin.debruijn/type :vm/resume,
               :yin.debruijn/parked-id :p1,
               :yin.debruijn/val-node {:yin.debruijn/type :literal,
                                       :yin.debruijn/value 7}}
              {:yin.debruijn/type :dao.stream.apply/call,
               :yin.debruijn/op :op/echo,
               :yin.debruijn/operands [{:yin.debruijn/type :literal,
                                        :yin.debruijn/value 1}]}
              {:yin.debruijn/type :if,
               :yin.debruijn/test {:yin.debruijn/type :literal,
                                   :yin.debruijn/value true},
               :yin.debruijn/consequent {:yin.debruijn/type :vm/park},
               :yin.debruijn/alternate {:yin.debruijn/type :literal,
                                        :yin.debruijn/value nil}}]}}},
          :yin.debruijn/operands [{:yin.debruijn/type :literal,
                                   :yin.debruijn/value 3}]}
         (:root (project every-type)))))


(deftest the-walk-names-children-in-fixed-order
  (testing ":if walks test, consequent, alternate"
    (is (= [:yin/test]
           (:path (throws-data
                    #(d/project-datoms
                       [[-16 :yin/type :if 0 1]
                        [-16 :yin/test -901 0 1]
                        [-16 :yin/consequent -902 0 1]
                        [-16 :yin/alternate -903 0 1]
                        [-16 :yin/root true 0 1]]))))))
  (testing "an application walks its operator before its operands"
    (is (= [:yin/operator]
           (:path (throws-data
                    #(d/project-datoms
                       [[-16 :yin/type :application 0 1]
                        [-16 :yin/operator -901 0 1]
                        [-16 :yin/operands [-902] 0 1]
                        [-16 :yin/root true 0 1]]))))))
  (testing "a stream put walks its target before its value"
    (is (= [:yin/target]
           (:path (throws-data
                    #(d/project-datoms
                       [[-16 :yin/type :stream/put 0 1]
                        [-16 :yin/target -901 0 1]
                        [-16 :yin/val-node -902 0 1]
                        [-16 :yin/root true 0 1]])))))))


;; =============================================================================
;; D1: scope resolution (§3)
;; =============================================================================

(deftest renamed-binders-project-to-one-graph-at-one-level
  (let [expected {:yin.debruijn/type :lambda,
                  :yin.debruijn/arity 1,
                  :yin.debruijn/body {:yin.debruijn/type :variable,
                                      :yin.debruijn/bound [0 0]}}]
    (is (= expected (:root (project (lam '[x] (v 'x))))))
    (is (= expected (:root (project (lam '[y] (v 'y))))))))


(deftest renamed-binders-project-to-one-graph-across-levels
  (let [renamable (fn [a b]
                    (lam a (lam b (app (v '+) (v (first a)) (v (first b))))))
        expected {:yin.debruijn/type :lambda,
                  :yin.debruijn/arity 1,
                  :yin.debruijn/body
                  {:yin.debruijn/type :lambda,
                   :yin.debruijn/arity 1,
                   :yin.debruijn/body
                   {:yin.debruijn/type :application,
                    :yin.debruijn/operator {:yin.debruijn/type :variable,
                                            :yin.debruijn/free '+},
                    :yin.debruijn/operands [{:yin.debruijn/type :variable,
                                             :yin.debruijn/bound [1 0]}
                                            {:yin.debruijn/type :variable,
                                             :yin.debruijn/bound [0 0]}]}}}]
    (is (= expected (:root (project (renamable '[x] '[y])))))
    (is (= expected (:root (project (renamable '[a] '[b])))))
    (is (= expected (:root (project (renamable '[very-long-name] '[q])))))))


(deftest the-essays-example-resolves-its-binder
  (is (= {:yin.debruijn/type :lambda,
          :yin.debruijn/arity 1,
          :yin.debruijn/body
          {:yin.debruijn/type :application,
           :yin.debruijn/operator {:yin.debruijn/type :variable,
                                   :yin.debruijn/free '+},
           :yin.debruijn/operands [{:yin.debruijn/type :variable,
                                    :yin.debruijn/bound [0 0]}
                                   {:yin.debruijn/type :literal,
                                    :yin.debruijn/value 1}]}}
         (:root (project worked-example))
         (:root (project (lam '[n] (app (v '+) (v 'n) (lit 1))))))))


(deftest nearest-binder-shadows-with-frame-depth-and-position
  (is (= {:bound [0 0]} (d/resolve-name '[[x]] 'x)))
  (is (= {:bound [1 0]} (d/resolve-name '[[] [x]] 'x)))
  (is (= {:bound [1 0]} (d/resolve-name '[[y] [x]] 'x)))
  (is (= {:bound [0 1]} (d/resolve-name '[[a b]] 'b)))
  (is (= {:bound [0 2]} (d/resolve-name '[[a b c]] 'c)))
  (testing "an intermediate binder shifts only its own frame depth"
    (is (= [1 0]
           (get-in (project (lam '[x] (lam '[p q r] (v 'x))))
                   [:root :yin.debruijn/body :yin.debruijn/body :yin.debruijn/bound])))
    (is (= [0 2]
           (get-in (project (lam '[x] (lam '[p q r] (v 'r))))
                   [:root :yin.debruijn/body :yin.debruijn/body :yin.debruijn/bound]))))
  (testing "shadowing picks the innermost binder"
    (is (= [1 0]
           (get-in (project (lam '[x] (lam '[y] (v 'x))))
                   [:root :yin.debruijn/body :yin.debruijn/body :yin.debruijn/bound]))
        "distinct names: the occurrence still reaches the outer binder")
    (is (= [0 0]
           (get-in (project (lam '[x] (lam '[x] (v 'x))))
                   [:root :yin.debruijn/body :yin.debruijn/body :yin.debruijn/bound]))
        "the inner binder shadows the outer")
    (is (= [0 0]
           (get-in (project (lam '[x] (lam '[y] (lam '[x] (v 'x)))))
                   [:root :yin.debruijn/body :yin.debruijn/body :yin.debruijn/body
                    :yin.debruijn/bound])))))


(deftest duplicate-parameters-are-rightmost-wins
  (is (= {:yin.debruijn/type :lambda,
          :yin.debruijn/arity 2,
          :yin.debruijn/body {:yin.debruijn/type :variable,
                              :yin.debruijn/bound [0 1]}}
         (:root (project (lam '[x x] (v 'x))))))
  (testing "the position names the argument the VM binds"
    (let [params '[x x]
          args [1 2]
          bound (second (:bound (d/resolve-name [params] 'x)))]
      (is (= (nth args bound) (get (engine/bind-params params args) 'x)))
      (is (= 2 (get (engine/bind-params params args) 'x))))))


(deftest free-names-are-preserved-exactly
  (is (= {:yin.debruijn/type :variable, :yin.debruijn/free 'y}
         (:yin.debruijn/body (:root (project (lam '[x] (v 'y)))))))
  (is (= {:yin.debruijn/type :variable, :yin.debruijn/free 'foo/bar}
         (:root (project (v 'foo/bar)))))
  (is (= {:free 'z} (d/resolve-name [] 'z))))


;; =============================================================================
;; Input identity: order, metadata, tolerated attributes (§2)
;; =============================================================================

(deftest shuffled-datom-input-projects-the-same-graph
  (let [direct (project every-type)
        ds (datoms-of every-type)
        rotated (vec (concat (drop 3 ds) (take 3 ds)))]
    (is (= direct (d/project-datoms ds)))
    (is (= direct (d/project-datoms (vec (reverse ds)))))
    (is (= direct (d/project-datoms rotated)))))


(deftest transaction-metadata-and-other-namespaces-never-enter-identity
  (let [ds (datoms-of worked-example)
        restamped (mapv (fn [x] [(nth x 0) (nth x 1) (nth x 2) 5 42]) ds)
        decorated (into (vec ds)
                        [[-16 :yin.code/pc 3 0 1]
                         [-16 :db/ident :whatever 0 1]
                         [-999 :yin.macro/event :x 0 1]])]
    (is (= (d/project-datoms ds) (d/project-datoms restamped))
        "t and m — here an assert with other provenance — are not identity")
    (is (= (d/project-datoms ds) (d/project-datoms decorated))
        "non-:yin/* namespaces are ignored, walked or not")))


(deftest tolerated-emission-metadata-is-ignored
  (let [ds (datoms-of worked-example)]
    (is (= (d/project-datoms ds)
           (d/project-datoms (conj (vec ds) [-16 :yin/macro-name 'm 0 1])))
        ":yin/macro-name is tolerated emission metadata")))


(deftest the-tail-flag-changes-nothing
  (let [plain (lam '[x] (app (v 'f) (v 'x) (lit 1)))
        tailed (tail (lam '[x] (tail (app (v 'f) (tail (v 'x)) (lit 1)))))]
    (is (= (project plain) (project tailed)))))


(deftest an-explicit-macro-false-projects-like-an-absent-one
  (let [ds (datoms-of (lam '[x] (v 'x)))]
    (is (= (d/project-datoms ds)
           (d/project-datoms (conj (vec ds) [-17 :yin/macro? false 0 1])))
        "only a truthy :yin/macro? projects :yin.debruijn/macro?")))


(deftest shared-nodes-resolve-per-their-lexical-context
  (testing "one shared source node under equal lexical contexts"
    (let [shared (assoc (v 'x) :eid -40)
          operands (get-in (project (app (v 'f) shared shared))
                           [:root :yin.debruijn/operands])]
      (is (= 2 (count operands)))
      (is (= (first operands) (second operands)))))
  (testing "one shared source node under unequal lexical contexts"
    (let [body (assoc (v 'x) :eid -41)
          operands (get-in (project (app (v 'list)
                                         (lam '[x] body)
                                         (lam '[y] body)))
                           [:root :yin.debruijn/operands])
          [under-x under-y] (map :yin.debruijn/body operands)]
      (is (= {:yin.debruijn/type :variable, :yin.debruijn/bound [0 0]} under-x))
      (is (= {:yin.debruijn/type :variable, :yin.debruijn/free 'x} under-y)))))


(deftest the-memo-never-affects-output-identity
  (testing "the same term as a shared graph (memo hits) and as a tree (all misses)"
    (let [shared (assoc (lam '[x] (v 'x)) :eid -60)
          graph (app (v 'list) shared shared)
          tree (app (v 'list) (lam '[x] (v 'x)) (lam '[x] (v 'x)))]
      (is (some (fn [[_e a v]] (and (= :yin/operands a) (= [-60 -60] v)))
                (datoms-of graph))
          "the graph really shares one source entity, so the second occurrence
           is a [eid stack] memo hit")
      (is (not-any? (fn [[_e a v]] (and (= :yin/operands a) (apply = v)))
                    (datoms-of tree))
          "the tree's operands are distinct entities: every memo key misses")
      (is (= (project tree) (project graph))))))


;; =============================================================================
;; Root framing (§2)
;; =============================================================================

(deftest adjacent-rooted-graphs-reset-the-fact-index
  (let [[_root-one ds-one] (vm/ast->datoms-with-root (lam '[x] (v 'x)))
        [_root-two ds-two] (vm/ast->datoms-with-root (lam '[y] (v 'y)))
        frames (d/frame-datoms (vec (concat ds-one ds-two)))]
    (is (= 2 (count frames)))
    (is (= (set (map first ds-one)) (set (map first ds-two)))
        "both graphs mint -16-based tempids, so a merged index would collide")
    (is (= (:root (d/project-datoms ds-one))
           (:root (d/project-datoms (first frames)))))
    (is (= (:root (d/project-datoms ds-two))
           (:root (d/project-datoms (second frames)))))
    (is (= (:root (d/project-datoms (first frames)))
           (:root (d/project-datoms (second frames))))
        "renamed binders: the two graphs are one projection")))


(deftest a-partial-frame-at-end-of-stream-diagnoses
  (let [[_root-one ds-one] (vm/ast->datoms-with-root (lam '[x] (v 'x)))
        [_root-two ds-two] (vm/ast->datoms-with-root (lam '[y] (v 'y)))]
    (is (= :partial-frame
           (:rule (throws-data #(d/frame-datoms (vec (concat ds-one
                                                             [(first ds-two)])))))))
    (is (= 1 (count (d/frame-datoms ds-one)))
        "a stream ending exactly at its marker frames cleanly")))


(deftest trailing-other-namespace-datoms-do-not-open-a-frame
  (let [[_root ds] (vm/ast->datoms-with-root worked-example)
        decorated (into (vec ds)
                        [[-16 :yin.code/pc 3 0 1]
                         [-16 :db/ident :whatever 0 1]
                         [-999 :yin.macro/event :done 0 1]])]
    (is (= [ds] (d/frame-datoms decorated))
        "ignored-namespace datoms after the last marker are not a partial frame,
         and the frames carry :yin/* datoms only")
    (is (= (d/project-datoms ds)
           (d/project-datoms (first (d/frame-datoms decorated)))))))


;; =============================================================================
;; Diagnostics (§1/§2)
;; =============================================================================

(defn- defect
  "The diagnostic facets of projecting `datoms`, or nil when it projects."
  [datoms]
  (when-let [data (throws-data #(d/project-datoms datoms))]
    (select-keys data [:rule :entity :path :attribute :slot :operator :roots])))


(deftest dangling-references-diagnose
  (is (= {:rule :dangling-ref, :entity -999, :path [:yin/body]}
         (defect (mapv (fn [[e a _val t m :as x]]
                         (if (= :yin/body a) [e a -999 t m] x))
                       (datoms-of (lam '[x] (v 'x)))))))
  (is (= {:rule :dangling-ref, :entity -500, :path []}
         (defect [[-500 :yin/tail? true 0 1]
                  [-500 :yin/root true 0 1]]))
      "a root with facts but no :yin/type is not a node"))


(deftest duplicate-structural-facts-diagnose
  (is (= {:rule :duplicate-fact, :entity -17, :attribute :yin/type}
         (defect (conj (datoms-of (lam '[x] (v 'x)))
                       [-17 :yin/type :lambda 0 1])))))


(deftest cycles-diagnose
  (is (= {:rule :cycle, :entity -16, :path [:yin/operator :yin/operator]}
         (defect [[-16 :yin/type :application 0 1]
                  [-16 :yin/operator -17 0 1]
                  [-17 :yin/type :application 0 1]
                  [-17 :yin/operator -16 0 1]
                  [-16 :yin/root true 0 1]]))))


(deftest root-count-diagnoses
  (let [ds (datoms-of (lam '[x] (v 'x)))]
    (is (= {:rule :missing-root} (defect (vec (butlast ds)))))
    (is (= {:rule :multiple-roots, :roots [-17 -99]}
           (defect (conj (vec ds) [-99 :yin/root true 0 1]))))))


(deftest unknown-node-types-diagnose
  (is (= {:rule :unknown-node-type, :entity -17, :path []}
         (defect (mapv (fn [[e a _val t m :as x]]
                         (if (= :yin/type a) [e a :bogus t m] x))
                       (datoms-of (lam '[x] (v 'x))))))))


(deftest unknown-yin-attributes-diagnose-on-walked-nodes
  (is (= {:rule :unknown-attribute, :entity -18, :path [:yin/body], :attribute :yin/extra}
         (defect (conj (datoms-of (lam '[x] (v 'x)))
                       [-18 :yin/extra 1 0 1]))))
  (is (nil? (defect (conj (datoms-of (lam '[x] (v 'x)))
                          [-909 :yin/extra 1 0 1])))
      "an attribute on a node the walk never reaches is not diagnosed"))


(deftest retracts-diagnose
  (is (= {:rule :retract}
         (defect (mapv (fn [[e a val _t _m :as x]]
                         (if (= :yin/type a) [e a val 0 0] x))
                       (datoms-of (lam '[x] (v 'x))))))))


(deftest unexpanded-macro-call-sites-diagnose
  (is (= {:rule :unexpanded-macro, :entity -17, :operator -18, :path []}
         (defect (datoms-of (app {:type :lambda, :macro? true, :params '[x], :body (v 'x)}
                                 (lit 1))))))
  (testing "a macro lambda outside operator position is ordinary content"
    (is (= {:yin.debruijn/type :lambda, :yin.debruijn/macro? true}
           (select-keys
             (get-in (project (app (v 'f)
                                   {:type :lambda,
                                    :macro? true,
                                    :params '[x],
                                    :body (v 'x)}))
                     [:root :yin.debruijn/operands 0])
             [:yin.debruijn/type :yin.debruijn/macro?])))))


(deftest unsupported-values-diagnose
  (is (= {:rule :unsupported-value, :slot :yin/value, :entity -17, :path []}
         (defect (datoms-of (lit (fn [x] x))))))
  (is (= :unsupported-value
         (:rule (defect (datoms-of (lam '[x] (app (v 'f) (lit (fn [y] y)))))))))
  #?(:clj (is (= :unsupported-value
                 (:rule (defect (datoms-of (lit 1/2))))))))


(deftest malformed-inputs-diagnose
  (is (= :malformed-datom (:rule (defect [[-16 :yin/type]]))))
  (is (= :malformed-datom (:rule (defect [[:yin/root true 0 1]]))))
  (is (= :unsupported-value
         (:rule (defect (mapv (fn [[e a _val t m :as x]]
                                (if (= :yin/params a) [e a nil t m] x))
                              (datoms-of (lam '[x] (v 'x)))))))
      "a nilled fact is present with a nil value, not an absent fact")
  (is (= :missing-slot
         (:rule (defect [[-16 :yin/type :literal 0 1]
                         [-16 :yin/root true 0 1]]))))
  (is (= :unsupported-value
         (:rule (defect [[-16 :yin/type :literal 0 1]
                         [-16 :yin/value (fn [q] q) 0 1]
                         [-16 :yin/root true 0 1]]))))
  (is (= :unsupported-value
         (:rule (defect [[-16 :yin/type :lambda 0 1]
                         [-16 :yin/params :x 0 1]
                         [-16 :yin/body -17 0 1]
                         [-17 :yin/type :literal 0 1]
                         [-17 :yin/value 1 0 1]
                         [-16 :yin/root true 0 1]])))
      "params must be an ordered vector of symbols"))
