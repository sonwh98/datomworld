(ns yin.vm.debruijn-test
  "D0-D3 of docs/design/yin.vm.debruijn-projection.md: the published
   :yin.debruijn/* dimension with its hash domain, §5's canonical value
   table and NFC seam, §2's root framing and input validation, §3's
   scope resolution, D2's Merkle records — node hashes, the root
   fingerprint, hash-consing, and the d5 storage adapter — and D3's
   settled byte rules with pinned cross-host byte fixtures. The pinned
   literals are asserted by the JVM, Node, and Dart lanes alike: one
   byte string, three hosts."
  (:require [clojure.test :refer [are deftest is testing]]
            [dao.datom :as datom]
            [dao.jing :as jing]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ring]
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
    (is (= (jing/sha256 (d/encode-value d/descriptor)) d/dimension-hash)
        "the domain separator is the descriptor encoded through the settled
         canonical encoder and hashed — D3's re-pin of datom.md's dimension
         identity, not an ad hoc string")
    (is (= "1f4ec95cdfd666cf5da2be5f47fd3a7e3c942dfa2ea87e61e0062270f61a0803"
           d/dimension-hash)
        "the settled digest, pinned: descriptor drift fails here as a
         descriptor failure, not as a definition restating itself")
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
    #?@(:cljs [] :default [7.0 :double])
    1.5 :double
    (/ 0.0 0.0) :double
    (/ 1.0 0.0) :double
    (/ -1.0 0.0) :double
    -0.0 :double
    #?@(:cljs [0.0 :int64] :default [0.0 :double])
    #?@(:cljs [] :default [9.3e18 :double])
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
             (is (nil? (d/canonical-class -9007199254740992)))
             (is (nil? (d/canonical-class 9.3e18)))
             (is (nil? (d/canonical-class 9223372036854775808)))
             (is (= :unsupported-value
                    (:rule (throws-data #(d/encode-value 9007199254740992)))))
             (is (= "0200000010ffffffffffff1f00" (d/encode-value 9007199254740991))))))


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


;; =============================================================================
;; D2: Merkle records, hash-consing, and the d5 storage adapter (§4-§5)
;; =============================================================================

(deftest the-fingerprint-is-the-root-records-hash
  (let [p (project worked-example)]
    (is (= (:fingerprint p)
           (:yin.debruijn/hash (get (:records p) (:fingerprint p)))))
    (is (re-matches #"^[0-9a-f]{64}$" (:fingerprint p)))
    (is (every? #(re-matches #"^[0-9a-f]{64}$" %) (keys (:records p))))))


(deftest identity-inputs-never-enter-the-fingerprint
  (let [base (project (lam '[x] (v 'x)))
        renamed (project (lam '[y] (v 'y)))
        [_shifted-root shifted] (vm/ast->datoms-with-root (lam '[x] (v 'x))
                                                          {:id-start -100})
        decorated (conj (vec (datoms-of (lam '[x] (v 'x))))
                        [-17 :yin/macro-name 'm 0 1]
                        [-17 :yin/tail? true 0 1])]
    (is (= (:fingerprint base) (:fingerprint renamed))
        "binder names and bound occurrence names never enter identity")
    (is (= (:records base) (:records renamed)))
    (is (= (:fingerprint base) (:fingerprint (d/project-datoms shifted)))
        "source tempids are storage layout, not identity")
    (is (= (:fingerprint base) (:fingerprint (d/project-datoms decorated)))
        "tail flags and macro-name never enter identity")))


(deftest content-changes-change-the-fingerprint
  (let [fp (comp :fingerprint project)]
    (are [a b] (not= (fp a) (fp b))
      (lam '[x] (app (v '+) (v 'x) (lit 1)))
      (lam '[x] (app (v '+) (v 'x) (lit 2)))
      (lam '[x] (v 'y))
      (lam '[x] (v 'z))
      {:type :vm/store-get, :key 'k}
      {:type :vm/store-get, :key 'k2}
      (lam '[x] (v 'x))
      (lam '[x y] (v 'x))
      (lam '[x] (v 'x))
      (lam '[x x] (v 'x))
      (if-node (v 'a) (lit 1) (lit 2))
      (if-node (v 'a) (lit 2) (lit 1))
      {:type :stream/make, :buffer 4}
      {:type :stream/make, :buffer 8}
      {:type :vm/resume, :parked-id :p1, :val (lit 1)}
      {:type :vm/resume, :parked-id :p2, :val (lit 1)}
      {:type :dao.stream.apply/call, :op :op/echo, :operands [(lit 1)]}
      {:type :dao.stream.apply/call, :op :op/ping, :operands [(lit 1)]}
      (lam '[x] (v 'x))
      {:type :lambda, :macro? true, :params '[x], :body (v 'x)})))


(deftest operand-order-changes-the-hash
  (let [left (project (app (v 'f) (v 'x) (v 'y)))
        right (project (app (v 'f) (v 'y) (v 'x)))]
    (is (not= (:fingerprint left) (:fingerprint right)))
    (is (not= (:records left) (:records right)))))


(deftest tree-and-graph-emission-are-one-merkle-graph
  (let [shared (assoc (lam '[x] (v 'x)) :eid -60)
        graph (project (app (v 'list) shared shared))
        tree (project (app (v 'list) (lam '[x] (v 'x)) (lam '[x] (v 'x))))]
    (is (= (:records tree) (:records graph)))
    (is (= (:fingerprint tree) (:fingerprint graph)))
    (is (= 4 (count (:records graph)))
        "app, free list, one hash-consed lambda, its bound body")))


(deftest equal-subterms-hash-cons-to-one-record
  (let [p (project (app (v 'f) (lam '[x] (v 'x)) (lam '[y] (v 'y))))
        lambdas (filter #(= :lambda (:yin.debruijn/type %)) (vals (:records p)))]
    (is (= 1 (count lambdas))
        "two alpha-equal source lambdas are one projected record")
    (is (= 4 (count (:records p))))))


(deftest shared-under-unequal-contexts-hash-differently
  (let [body (assoc (v 'x) :eid -41)
        p (project (app (v 'list) (lam '[x] body) (lam '[y] body)))
        vars (vals (:records p))
        bound-h (:yin.debruijn/hash (some #(when (:yin.debruijn/bound %) %) vars))
        free-h (:yin.debruijn/hash (some #(when (= 'x (:yin.debruijn/free %)) %) vars))]
    (is (not= bound-h free-h))
    (is (= 6 (count (:records p)))
        "the two lambdas stay distinct: their bodies differ by resolution")))


(deftest the-storage-adapter-round-trips
  (let [p (project every-type)
        ds (d/projected->datoms p)
        by-entity (group-by first ds)
        roots (filter #(= :yin.debruijn/root (nth % 1)) ds)
        root-e (first (first roots))
        root-hash (some (fn [x]
                          (when (and (= root-e (first x))
                                     (= :yin.debruijn/hash (nth x 1)))
                            (nth x 2)))
                        ds)]
    (is (= (select-keys p [:fingerprint :records]) (d/datoms->projected ds)))
    (is (= (count (keys by-entity)) (count (:records p)))
        "one entity per hash-consed record")
    (is (every? #(>= (first %) datom/first-user-id) ds)
        "handles are local layout from first-user-id")
    (is (every? #(and (zero? (nth % 3)) (= datom/default-op (nth % 4))) ds)
        "t 0 and the assert op are the adapter's fixed provenance")
    (is (= 1 (count roots)))
    (is (= (:fingerprint p) root-hash)
        "the root marker's entity carries the fingerprint as its hash")
    (is (= (count (filter #(= :yin.debruijn/operands (nth % 1)) ds))
           (count (filter :yin.debruijn/operands (vals (:records p)))))
        ":yin.debruijn/operands stays one ordered vector datom, never
         cardinality-many")))


(deftest projected-dangling-child-hashes-diagnose
  (let [ds (d/projected->datoms (project (lam '[x] (v 'x))))
        body-hash (some (fn [x] (when (= :yin.debruijn/body (nth x 1)) (nth x 2))) ds)
        body-e (some (fn [x]
                       (when (and (= :yin.debruijn/hash (nth x 1))
                                  (= body-hash (nth x 2)))
                         (first x)))
                     ds)
        pruned (vec (remove #(= body-e (first %)) ds))]
    (is (= :dangling-ref
           (:rule (throws-data #(d/datoms->projected pruned)))))))


;; =============================================================================
;; D2 fix round: preimage-keyed consing, NFC idents, verified content address
;; =============================================================================

(deftest the-consing-memo-separates-what-the-hash-separates
  (testing "a list literal alongside a vector literal, in one program"
    (let [mixed (project (app (v 'list) (lit (list 1 2)) (lit [1 2])))
          all-list (project (app (v 'list) (lit (list 1 2)) (lit (list 1 2))))
          hashes (->> (:records mixed) vals
                      (filter #(= :literal (:yin.debruijn/type %)))
                      (map :yin.debruijn/hash))]
      (is (= 2 (count hashes)))
      (is (not= (first hashes) (second hashes))
          "Clojure = merges '(1 2) with [1 2]; the preimage does not")
      (is (not= (:fingerprint mixed) (:fingerprint all-list)))))
  (testing "0.0 alongside -0.0, in one program"
    (let [hashes (->> (project (app (v 'list) (lit 0.0) (lit -0.0)))
                      (:records) vals
                      (filter #(= :literal (:yin.debruijn/type %)))
                      (map :yin.debruijn/hash))]
      (is (= 2 (count hashes)))
      (is (not= (first hashes) (second hashes))
          "= holds for the signed zeros; the preimage keeps them apart"))))


(deftest decomposed-and-composed-ident-parts-hash-identically
  (let [decomposed (v (symbol "e\u0301"))
        composed (v (symbol "\u00e9"))]
    (is (= (:fingerprint (project decomposed))
           (:fingerprint (project composed)))
        "ident parts pass the NFC seam: one free name, one hash")))


(deftest the-reader-verifies-the-content-address
  (let [ds (d/projected->datoms (project (lam '[x] (v 'x))))
        tampered (mapv (fn [x]
                         (if (= :yin.debruijn/bound (nth x 1))
                           [(nth x 0) (nth x 1) [1 0] (nth x 3) (nth x 4)]
                           x))
                       ds)]
    (is (= :hash-mismatch
           (:rule (throws-data #(d/datoms->projected tampered)))))
    (is (= :malformed-hash
           (:rule (throws-data
                    #(d/datoms->projected
                       (mapv (fn [x]
                               (if (= :yin.debruijn/hash (nth x 1))
                                 [(nth x 0) (nth x 1) 42 (nth x 3) (nth x 4)]
                                 x))
                             ds)))))
        "a non-string hash value diagnoses rather than throwing a host
         regex error")))


(deftest integral-doubles-and-integers-are-distinct
  ;; a JS number cannot tell 1 from 1.0; that host limit stays in the JS
  ;; adapter, so only the JVM and Dart lanes assert the distinction
  (when #?(:cljs false :default true)
    (let [int-version (project (lam '[x] (app (v '+) (v 'x) (lit 1))))
          double-version (project (lam '[x] (app (v '+) (v 'x) (lit 1.0))))]
      (is (not= (:fingerprint int-version) (:fingerprint double-version))
          "contract version 1: int64 and double are disjoint classes")
      (is (not= (:records int-version) (:records double-version))))))


;; =============================================================================
;; D3: the settled byte rules, pinned (§5, §8's cross-host rows)
;; =============================================================================
;; Every literal below is asserted by the JVM, Node, and Dart lanes alike:
;; one pinned byte string, three hosts. If any host's encoder diverges by one
;; bit, its lane fails here — that is the cross-host byte-identity proof, and
;; the essay fingerprint below is the stable §8 row. (A live pair transport —
;; one Dart peer spawned by the JVM/Node lanes, per the R5 precedent — needs
;; files outside this dispatch's box and is flagged to the orchestrator.)

(deftest the-settled-scalar-bytes-are-pinned
  (are [expected v] (= expected (d/encode-value v))
    "02000000100100000000000000" 1
    #?@(:cljs []
        :default ["0300000010000000000000f03f" 1.0])
    "0200000010ffffffffffffffff" -1
    "0200000010ffffffffffff1f00" 9007199254740991
    "0300000010000000000000f87f" (/ 0.0 0.0)
    "03000000100000000000000080" -0.0
    "0300000010000000000000f83f" 1.5
    "0300000010000000000000f07f" (/ 1.0 0.0)
    "0400000003abc" "abc"
    "0400000002é" "é"
    "0400000003€" "€"
    "0400000004😀" "😀"
    "060000001a0400000002op0400000004echo" :op/echo
    "070000001800000000000400000004list" 'list
    "0000000000" nil
    "010000000201" true
    "010000000200" false)
  ;; the full int64 extremes exist only where a 64-bit integer does: a JS
  ;; number cannot hold them exactly — §5's JS-number rule — so the boundary
  ;; row every host shares is 2^53-1 above
  (when #?(:cljs false :default true)
    (are [expected v] (= expected (d/encode-value v))
      "0200000010ffffffffffffff7f" 9223372036854775807
      "02000000100000000000000080" -9223372036854775808))
  (is (= (str "0a00000034" (d/encode-value 1) (d/encode-value 2))
         (d/encode-value [1 2])))
  (is (= (str "0b00000034" (d/encode-value 1) (d/encode-value 2))
         (d/encode-value (list 1 2))))
  (is (not= (d/encode-value [1 2]) (d/encode-value (list 1 2)))
      "the D0 vector/list split is two tags over one ordering")
  ;; the unpaired surrogates are SLICED from a well-formed pair at runtime:
  ;; a lone-surrogate string literal does not survive ClojureDart's macro
  ;; host, which rewrites it before the test ever runs
  (let [lone-high (subs "😀" 0 1)
        lone-low (subs "😀" 1 2)]
    (are [v] (= :unsupported-value (:rule (throws-data #(d/encode-value v))))
      lone-high
      lone-low
      (str "a" lone-high "b")
      (symbol (str "a" lone-high "b"))
      (keyword (str "a" lone-low "b")))
    (is (nil? (d/canonical-class lone-high)))))


(deftest colliding-map-keys-diagnose
  ;; iteration order must never decide which entry survives a merge
  (when #?(:cljs false :default true)
    (is (= 2 (count (d/canonical-value {1 :a, 1.0 :b})))
        "1 and 1.0 are distinct keys under canonicalisation")
    (is (not= (d/encode-value {1 :a}) (d/encode-value {1.0 :a}))))
  (is (= :unsupported-value
         (:rule (throws-data #(d/canonical-value {"é" :a, "é" :b}))))
      "two string keys with one NFC form collide")
  (is (= :unsupported-value
         (:rule (throws-data #(d/encode-value {"é" :a, "é" :b}))))
      "the encode path sees the same collision as equal key encodings")
  (is (= :unsupported-value
         (:rule (defect (datoms-of (lit {"é" :a, "é" :b})))))
      "and a projection diagnoses it at the walk"))


(deftest nfc-and-collection-order-limits-hold
  (is (= (d/encode-value "e\u0301") (d/encode-value "\u00e9"))
      "the NFC limit: a decomposed string and its composed form are one
       canonical encoding — an inherited collision of the repository
       encoding, declared in §5")
  (is (= (d/encode-value {:a 1, :b 2}) (d/encode-value {:b 2, :a 1})))
  (is (= (d/encode-value #{1 2}) (d/encode-value (conj #{2} 1)))
      "maps and sets sort by encoded parts, so iteration order never
       enters the bytes"))


(deftest the-essays-fingerprint-is-stable
  (is (= "aed64f20d7150b6a149939f5af307eca809fd9b23000ed44e526353f7f4bfbc0"
         (:fingerprint (project worked-example))
         (:fingerprint (d/project-datoms
                         (vec (reverse (datoms-of worked-example)))))
         (:fingerprint (d/project-datoms
                         (vec (concat (drop 3 (datoms-of worked-example))
                                      (take 3 (datoms-of worked-example)))))))
      "the essay's (fn [count] (+ count 1)): one pinned fingerprint for the
       renamed binder and shuffled datom input alike"))


(deftest records-carry-canonical-scalars
  (let [int-version (project (lam '[x] (app (v '+) (v 'x) (lit 1))))
        double-version (project (lam '[x] (app (v '+) (v 'x) (lit 1.0))))
        literal-value (fn [p]
                        (->> (:records p) vals
                             (some #(when (= :literal (:yin.debruijn/type %))
                                      (:yin.debruijn/value %)))))]
    (is (= 1 (literal-value int-version)))
    (when #?(:cljs false :default true)
      (is (= 1.0 (literal-value double-version)))
      (is (double? (literal-value double-version))
          "the integral double's record keeps its double spelling")))
  (let [decomposed (project (lit "e\u0301"))
        composed (project (lit "\u00e9"))]
    (is (= (:fingerprint decomposed) (:fingerprint composed)))
    (is (= (:records decomposed) (:records composed)))
    (is (= "\u00e9" (->> (:records decomposed) vals
                         (some #(when (= :literal (:yin.debruijn/type %))
                                  (:yin.debruijn/value %)))))
        "the decomposed input's record carries the composed NFC spelling")))


;; =============================================================================
;; D4: the dao.stream forward adapter (§6, §7-D4)
;; =============================================================================

(defn- buffer
  [capacity]
  (:dao.stream/handle (ring/create! {:dao.stream/type ring/transport-type,
                                     ring/capacity-key capacity})))


(defn- fill!
  [handle values]
  (doseq [v values]
    (stream/append! handle v))
  handle)


(defn- sealed!
  "Close `handle` and return it — close! answers an outcome map, not the
   handle, so a threading composition would lose the handle."
  [handle]
  (stream/close! handle)
  handle)


(defn- oldest-cursor
  [handle]
  (:dao.stream/cursor (stream/cursor handle stream/anchor-oldest)))


(defn- drain
  [handle]
  (loop [cursor (oldest-cursor handle), out []]
    (let [result (stream/next handle cursor)]
      (if (= :dao.stream/ok (:dao.stream/outcome result))
        (recur (:dao.stream/cursor result) (conj out (:dao.stream/value result)))
        out))))


(defn- drive
  "A host driver over forward-step: step while the adapter says
   :continue. A :retry is the adapter handing cadence back — here the
   test is the host, so each drive call is one cadence tick."
  [source destination state]
  (loop [state state, guard 4000]
    (if (or (zero? guard) (not (contains? #{nil :continue} (:status state))))
      state
      (recur (d/forward-step source destination state {:batch-budget 8})
             (dec guard)))))


(defn- pump
  "A patient host: step through retries too — every tick is another
   cadence chance for a blocked read or a full write."
  [source destination state]
  (loop [state state, guard 4000]
    (if (or (zero? guard) (not (contains? #{nil :continue :retry} (:status state))))
      state
      (recur (d/forward-step source destination state {:batch-budget 8})
             (dec guard)))))


(defn- scripted-writer
  "A writer answering the queued outcomes, then ok, recording what it
   accepts — macro-test's shape."
  [outcomes]
  (let [accepted (atom [])
        queue (atom outcomes)]
    {:accepted accepted,
     :writer (reify stream/IDaoStreamWriter
               (append!
                 [_ v]
                 (let [outcome (or (first @queue) :dao.stream/ok)]
                   (swap! queue rest)
                   (when (= :dao.stream/ok outcome)
                     (swap! accepted conj v))
                   {:dao.stream/outcome outcome})))}))


(defn- refusing-reader
  "A reader answering one fixed non-ok outcome forever."
  [outcome]
  (reify stream/IDaoStreamReader
    (next
      [_ _cursor]
      {:dao.stream/outcome outcome})))


(deftest forward-step-projects-one-graph-to-a-destination
  (let [expected (d/projected->datoms (project worked-example))
        source (-> (buffer 64) (fill! (datoms-of worked-example)) (sealed!))
        destination (buffer 64)
        final (drive source destination (d/forward-initial-state (oldest-cursor source)))]
    (is (= :source-ended (:status final)))
    (is (= expected (drain destination)))
    (is (pos? (:forwarded final))
        ":forwarded counts this call's writes, as dao.stream.forward's does")))


(deftest forward-step-frames-adjacent-graphs-that-reuse-tempids
  (let [[_ ds-one] (vm/ast->datoms-with-root (lam '[x] (v 'x)))
        [_ ds-two] (vm/ast->datoms-with-root (lam '[x] (lit 42)))
        source (-> (buffer 64) (fill! (concat ds-one ds-two)) (sealed!))
        destination (buffer 64)
        final (drive source destination (d/forward-initial-state (oldest-cursor source)))
        forwarded (drain destination)]
    (is (= :source-ended (:status final)))
    (is (= (set (map first ds-one)) (set (map first ds-two)))
        "both graphs mint -16-based tempids")
    (is (not= (d/project-datoms ds-one) (d/project-datoms ds-two))
        "the two graphs are semantically different, so a replay of one
         projection for the other cannot pass")
    (is (= (concat (d/projected->datoms (d/project-datoms ds-one))
                   (d/projected->datoms (d/project-datoms ds-two)))
           forwarded))
    (is (= 2 (count (filter #(= :yin.debruijn/root (nth % 1)) forwarded)))
        "two graphs, two explicit root markers")))


(deftest end-of-stream-with-a-partial-frame-diagnoses
  (testing "unclosed :yin/* datoms are a terminal partial frame"
    (let [partial (vec (butlast (datoms-of worked-example)))
          source (-> (buffer 64) (fill! partial) (sealed!))
          destination (buffer 64)
          final (drive source destination (d/forward-initial-state (oldest-cursor source)))]
      (is (= :partial-frame (:status final)))
      (is (= :partial-frame (:rule (:diagnostic final))))
      (is (= partial (:datoms (:diagnostic final))))
      (is (zero? (:forwarded final)))
      (is (= final (d/forward-step source destination final {:batch-budget 8}))
          "terminal state is data: re-stepping it is a no-op")))
  (testing "trailing decorations from other namespaces close cleanly"
    (let [source (-> (buffer 64)
                     (fill! (datoms-of worked-example))
                     (fill! [[-16 :yin.code/pc 3 0 1], [-9 :db/ident :x 0 1]])
                     (sealed!))
          destination (buffer 64)
          final (drive source destination (d/forward-initial-state (oldest-cursor source)))]
      (is (= :source-ended (:status final))))))


(deftest blocked-input-retries-without-progress
  (let [source (fill! (buffer 64) (datoms-of worked-example))
        destination (buffer 64)
        final (drive source destination (d/forward-initial-state (oldest-cursor source)))]
    (is (= :retry (:status final)))
    (is (= :dao.stream/blocked (:outcome final)))
    (is (= (d/projected->datoms (project worked-example)) (drain destination))
        "the closed graph was forwarded before the tail blocked")))


(deftest a-half-read-frame-is-carried-across-a-retry
  (let [datoms (datoms-of worked-example)
        half (quot (count datoms) 2)
        source (fill! (buffer 64) (take half datoms))
        destination (buffer 64)
        blocked (drive source destination (d/forward-initial-state (oldest-cursor source)))]
    (is (= :retry (:status blocked)))
    (is (= :dao.stream/blocked (:outcome blocked)))
    (is (= (vec (take half datoms)) (:frame blocked))
        "the half-read frame rides in the retry state — dropping it would
         silently lose the graph's first datoms")
    (is (zero? (:forwarded blocked))
        "nothing is forwarded before the frame closes")
    (fill! source (drop half datoms))
    (sealed! source)
    (let [final (pump source destination blocked)]
      (is (= :source-ended (:status final)))
      (is (= (d/projected->datoms (project worked-example)) (drain destination))
          "the retried step completes the SAME graph, first half included"))))


(deftest internal-defects-are-never-mislabeled-input
  (is (= {:status :invalid-input, :diagnostic {:rule :dangling-ref}}
         (d/exception-diagnostic (ex-info "dangling" {:rule :dangling-ref}))))
  ;; a throwable carrying no diagnostic data is an internal defect with
  ;; its message — never :invalid-input. The message is the host's
  ;; ex-message: ClojureDart renders a plain Exception's as its toString.
  (let [plain #?(:clj (RuntimeException. "boom")
                 :cljs (js/Error. "boom")
                 :cljd (Exception. "boom"))
        message #?(:clj "boom"
                   :cljs "boom"
                   :cljd "Exception: boom")]
    (is (= {:status :internal-error,
            :diagnostic {:rule :internal-error, :message message}}
           (d/exception-diagnostic plain)))))


(deftest pending-writes-wait-for-host-cadence
  (let [expected (d/projected->datoms (project worked-example))
        source (-> (buffer 64) (fill! (datoms-of worked-example)) (sealed!))
        {:keys [accepted writer]} (scripted-writer [:dao.stream/full])
        first-pass (drive source writer (d/forward-initial-state (oldest-cursor source)))]
    (is (= :retry (:status first-pass)))
    (is (= :dao.stream/full (:outcome first-pass)))
    (is (= (count expected) (count (:pending first-pass))))
    (is (empty? @accepted)
        "a full destination keeps every pending write explicit")
    (let [second-pass (pump source writer first-pass)]
      (is (= :source-ended (:status second-pass)))
      (is (= expected @accepted))
      (is (pos? (:forwarded second-pass)) "the retry cadence completed the writes"))))


(deftest destination-defects-are-terminal
  (let [source (-> (buffer 64) (fill! (datoms-of worked-example)) (sealed!))
        destination (sealed! (buffer 64))
        final (drive source destination (d/forward-initial-state (oldest-cursor source)))]
    (is (= :destination-closed (:status final)))
    (is (= :dao.stream/closed (:outcome final)))))


(deftest source-transport-defects-are-terminal
  (let [destination (buffer 8)]
    (doseq [[outcome status] [[:dao.stream/transport-error :source-transport-error]
                              [:dao.stream/gap :source-gap]
                              [:dao.stream/cursor-mismatch :source-cursor-mismatch]
                              [:dao.stream/invalid-cursor :source-invalid-cursor]]]
      (let [final (d/forward-step (refusing-reader outcome)
                                  destination
                                  (d/forward-initial-state :cursor)
                                  {:batch-budget 4})]
        (is (= status (:status final)))
        (is (= outcome (:outcome final)))))))


(deftest invalid-stream-input-is-a-terminal-diagnostic
  (testing "a malformed datom"
    (let [source (-> (buffer 64) (fill! [(first (datoms-of worked-example))])
                     (fill! [[16 :yin/type]])
                     (sealed!))
          destination (buffer 8)
          final (drive source destination (d/forward-initial-state (oldest-cursor source)))]
      (is (= :invalid-input (:status final)))
      (is (= :malformed-datom (:rule (:diagnostic final))))))
  (testing "a projection defect through the stream"
    (let [dangling (mapv (fn [[e a _v :as x]]
                           (if (= :yin/body a) [e a -999 (nth x 3) (nth x 4)] x))
                         (datoms-of (lam '[x] (v 'x))))
          source (-> (buffer 64) (fill! dangling) (sealed!))
          destination (buffer 8)
          final (drive source destination (d/forward-initial-state (oldest-cursor source)))]
      (is (= :invalid-input (:status final)))
      (is (= :dangling-ref (:rule (:diagnostic final)))))))


(deftest wrongly-typed-projected-slots-diagnose-before-hashing
  (let [ds (d/projected->datoms (project (lam '[x] (v 'x))))
        revalue (fn [attr value]
                  (mapv (fn [x]
                          (if (= attr (nth x 1))
                            [(nth x 0) (nth x 1) value (nth x 3) (nth x 4)]
                            x))
                        ds))]
    (is (= :unsupported-value
           (:rule (throws-data #(d/datoms->projected (revalue :yin.debruijn/bound 5)))))
        "a scalar slot outside its declared tuple shape")
    (is (= :unsupported-value
           (:rule (throws-data #(d/datoms->projected (revalue :yin.debruijn/body 42)))))
        "a numeric child ref")))


;; =============================================================================
;; Epic-audit fix round: set collisions, the reader's record gate, the
;; shared-subgraph bound, per-type attributes, and the D4 outcome gaps
;; =============================================================================

(deftest colliding-set-elements-diagnose
  ;; a set whose members merge changes cardinality, not just a spelling:
  ;; the map rule's collision diagnostic applies, never a silent collapse
  (when #?(:cljs false :default true)
    (let [s (hash-set 1 1.0)]
      (is (= 2 (count s)) "the host keeps 1 and 1.0 apart as set members")
      (is (= 2 (count (d/canonical-value s))))
      (is (string? (d/encode-value s)))
      (is (not= (:fingerprint (project (lit s)))
                (:fingerprint (project (lit #{1}))))
          "1 and 1.0 stay distinct members, never sharing #{1}'s fingerprint")))
  (let [s (hash-set "é" "é")]
    (is (= 2 (count s)))
    (is (= :unsupported-value (:rule (throws-data #(d/canonical-value s))))
        "two spellings of é are one NFC element")
    (is (= :unsupported-value (:rule (throws-data #(d/encode-value s))))
        "the encode path sees the same collision as equal element encodings")
    (is (= :unsupported-value (:rule (defect (datoms-of (lit s))))))
    (is (some? (:fingerprint (project (lit #{"é" "e"}))))
        "a set whose members stay distinct still projects")))


(defn- restated
  "Projected datoms `ds` with every `attr` fact's attribute renamed to
   `to` and its value replaced by `value-fn` of the old one."
  [ds attr to value-fn]
  (mapv (fn [x]
          (if (= attr (nth x 1))
            [(nth x 0) to (value-fn (nth x 2)) (nth x 3) (nth x 4)]
            x))
        ds))


(deftest the-reader-checks-records-against-the-grammar
  (let [free-ds (d/projected->datoms (project (lam '[x] (v 'y))))
        reader-rule (fn [ds] (:rule (throws-data #(d/datoms->projected ds))))]
    (testing "a slot renamed within its type's tag class keeps its hash"
      (let [swapped (restated free-ds :yin.debruijn/free :yin.debruijn/key identity)]
        (is (= (filter #(= :yin.debruijn/hash (nth % 1)) free-ds)
               (filter #(= :yin.debruijn/hash (nth % 1)) swapped))
            "the stored addresses are untouched: only the grammar can tell")
        (is (= {:rule :unknown-attribute,
                :attribute :yin.debruijn/key,
                :type :variable}
               (select-keys (throws-data #(d/datoms->projected swapped))
                            [:rule :attribute :type])))))
    (is (= :unknown-attribute
           (reader-rule (restated free-ds :yin.debruijn/free :yin.debruijn/value identity)))
        ":free to :value on a :variable")
    (is (= :unknown-attribute
           (reader-rule (restated free-ds :yin.debruijn/arity :yin.debruijn/buffer identity)))
        ":arity to :buffer on a :lambda — both int64")
    (is (= :missing-slot
           (reader-rule (vec (remove #(= :yin.debruijn/arity (nth % 1)) free-ds))))
        "a required slot absent from its record")
    (is (= :missing-slot
           (reader-rule (vec (remove #(= :yin.debruijn/free (nth % 1)) free-ds))))
        "a :variable carries one of :bound and :free")))


(deftest integral-double-records-cross-hosts
  ;; fingerprints minted on the JVM for literals holding integral doubles;
  ;; a JS host receives those doubles as plain numbers and must refuse
  ;; them as :unsupported-value, never diagnose :hash-mismatch
  (let [cases
        [[1.0
          "3eff0da5d025906b7a668c5b52dbf29fb5df4c6b2ea0a8b1bb733c08e35241cc"]
         [[1.0]
          "2f9d03004e233a3d9e78eae688350aaaffd1701977097153434e9b5d36b407a2"]
         [{:a 1.0}
          "411b0a57a72c09a3314fec224a042159523ec7bf3864b6e7d19eb8e269cace58"]
         [#{1.0}
          "b3ee9e8c72e84771fcd582ec779e46954e59a3351ea0d6eaf385d650716d8394"]]]
    (doseq [[v jvm-fingerprint] cases]
      #?(:cljs
         (let [wire (mapv (fn [x]
                            (if (= :yin.debruijn/hash (nth x 1))
                              [(nth x 0) (nth x 1) jvm-fingerprint (nth x 3)
                               (nth x 4)]
                              x))
                          (d/projected->datoms (project (lit v))))]
           (is (= :unsupported-value
                  (:rule (throws-data #(d/datoms->projected wire))))
               (str "a foreign integral double " (pr-str v))))
         :default
         (let [p (project (lit v))]
           (is (= jvm-fingerprint (:fingerprint p)))
           (is (= (:records p)
                  (:records (d/datoms->projected (d/projected->datoms p))))
               (str "fully supported and read back: " (pr-str v))))))))


(deftest the-reader-requires-canonical-spellings
  (let [reader-rule (fn [ds] (:rule (throws-data #(d/datoms->projected ds))))
        respelled (fn [ast attr value]
                    (restated (d/projected->datoms (project ast)) attr attr (constantly value)))]
    ;; a JS number has one spelling per value, so 1.0 is 1 there
    (when #?(:cljs false :default true)
      (is (= :hash-mismatch
             (reader-rule (respelled (lit 1) :yin.debruijn/value 1.0)))
          "a stored 1.0 is a different value than 1 at 1's address")
      (is (= :unsupported-value
             (reader-rule (respelled (lam '[x] (v 'x)) :yin.debruijn/arity 1.0)))
          "an int64 slot holding a double"))
    (is (= :noncanonical-value
           (reader-rule (respelled (lit "é") :yin.debruijn/value "é")))
        "a decomposed string under the composed one's address")
    (is (= :noncanonical-value
           (reader-rule (respelled (lit {:a [1 "é"]})
                                   :yin.debruijn/value
                                   {:a [1 "é"]})))
        "spelling is checked inside collections")
    (is (= :noncanonical-value
           (reader-rule (respelled (v (symbol "é"))
                                   :yin.debruijn/free
                                   (symbol "é"))))
        "and in ident parts")))


(deftest every-written-record-passes-the-reader
  (let [p (project (app (v 'f)
                        {:type :lambda, :macro? true, :params '[x], :body (v 'x)}
                        (lit false)
                        (lit nil)
                        (lit #{"a" 1 :k})
                        (lit (list 1.5 -0.0 (/ 0.0 0.0)))
                        (lit {"é" [1 2.5]})))]
    (is (= (:fingerprint p)
           (:fingerprint (d/datoms->projected (d/projected->datoms p))))
        "the gate adds nothing the writer does not already guarantee: false
         literals, nil, a true :macro?, sets, doubles, NaN, and NFC keys
         round-trip")))


(defn- doubling-chain
  "`depth` applications, each of whose two operands is the previous
   level — ONE shared source entity per level, so the unfolded term has
   2^depth leaves while the emitted graph grows linearly."
  [depth]
  (reduce (fn [prev k]
            (assoc (app (v 'f) prev prev) :eid (- -1000 k)))
          (assoc (lit 0) :eid -999)
          (range depth)))


(defn- doubling-tree
  [depth]
  (reduce (fn [prev _] (app (v 'f) prev prev)) (lit 0) (range depth)))


(deftest shared-subgraphs-are-minted-once
  (testing "a deep doubling chain mints once per source entity"
    (let [depth 40
          ds (datoms-of (doubling-chain depth))
          entities (count (distinct (keep (fn [[e a]] (when (= :yin/type a) e)) ds)))
          counted (d/project-datoms-counted ds)]
      (is (= (inc (* 2 depth)) entities)
          "one entity per level, one per level's `f`, and the leaf")
      (is (= entities (:mints counted))
          "one mint per [eid lexical-context] occurrence, never one per
           unfolded path — 2^40 paths here")
      (is (= (+ depth 2) (count (:records counted)))
          "the levels, the leaf, and one hash-consed free `f`")
      ;; never compare :root here: it is a 2^40-leaf tree under structural
      ;; sharing, and = over two separately built copies walks every leaf
      (is (= (select-keys (d/project-datoms ds) [:fingerprint :records])
             (select-keys counted [:fingerprint :records]))
          "the counted seam is output-neutral")))
  (testing "the graph is the tree-built term's Merkle graph"
    (let [graph (project (doubling-chain 6))
          tree (project (doubling-tree 6))]
      (is (= (:fingerprint tree) (:fingerprint graph)))
      (is (= (:records tree) (:records graph))))))


(deftest known-attributes-on-the-wrong-type-diagnose
  ;; the emitter mints the root first, at -17
  (is (= {:rule :unknown-attribute, :entity -17, :path [], :attribute :yin/body}
         (defect (conj (datoms-of (lit 1)) [-17 :yin/body 999 0 1])))
      "a dangling :yin/body on a :literal is diagnosed, not ignored")
  (is (= {:rule :unknown-attribute, :entity -17, :path [], :attribute :yin/macro?}
         (defect (conj (datoms-of (app (v 'f) (lit 1))) [-17 :yin/macro? true 0 1])))
      ":macro? belongs to the :lambda row only")
  (is (= {:rule :unknown-attribute, :entity -17, :path [], :attribute :yin/params}
         (defect (conj (datoms-of (v 'x)) [-17 :yin/params '[x] 0 1])))))


(deftest renamed-programs-store-equal-datoms
  (is (= (d/projected->datoms (project worked-example))
         (d/projected->datoms (project (lam '[n] (app (v '+) (v 'n) (lit 1))))))
      "alpha-equivalent programs write the identical projected d5 tuples,
       not only one fingerprint"))


(deftest internal-rules-are-internal-errors
  (is (= :internal-error
         (:status (d/exception-diagnostic
                    (ex-info "writer defect" {:rule :missing-record, :hash "h"}))))
      "a rule only the projection's own code can produce is its defect")
  (is (= {:rule :missing-record, :hash "h"}
         (get-in (d/exception-diagnostic
                   (ex-info "writer defect" {:rule :missing-record, :hash "h"}))
                 [:diagnostic :data]))
      "the defect's data rides along for diagnosis")
  (is (= :internal-error
         (:status (d/exception-diagnostic (ex-info "no rule" {:detail 1}))))
      "ex-data without a :rule is not an input diagnostic"))


(deftest a-non-positive-batch-budget-is-invalid-input
  (doseq [budget [0 -1 nil 1.5]]
    (let [source (-> (buffer 64) (fill! (datoms-of worked-example)) (sealed!))
          destination (buffer 64)
          state (d/forward-initial-state (oldest-cursor source))
          final (d/forward-step source destination state {:batch-budget budget})]
      (is (= :invalid-input (:status final)) (str "budget " (pr-str budget)))
      (is (= {:rule :invalid-budget, :batch-budget budget} (:diagnostic final)))
      (is (zero? (:forwarded final)))
      (is (= (:cursor state) (:cursor final)) "no work was done")
      (is (= final (d/forward-step source destination final {:batch-budget budget}))
          "terminal, never an endless :continue"))))


(deftest destination-outcomes-map-to-terminal-statuses
  (doseq [[outcome status] [[:dao.stream/invalid-value :destination-invalid-value]
                            [:dao.stream/transport-error :destination-transport-error]
                            [:dao.stream/closed :destination-closed]]]
    (let [source (-> (buffer 64) (fill! (datoms-of worked-example)) (sealed!))
          {:keys [accepted writer]} (scripted-writer [outcome])
          final (drive source writer (d/forward-initial-state (oldest-cursor source)))]
      (is (= status (:status final)))
      (is (= outcome (:outcome final)))
      (is (empty? @accepted))
      (is (seq (:pending final)) "the refused write stays pending in the state")
      (is (= final (d/forward-step source writer final {:batch-budget 8}))
          "re-stepping a terminal destination defect is a no-op")
      (is (empty? @accepted) "and never retries the write"))))


(deftest every-terminal-state-is-idempotent
  (let [closed-source (fn [] (-> (buffer 64) (fill! (datoms-of worked-example)) (sealed!)))
        initial (fn [source] (d/forward-initial-state (oldest-cursor source)))
        cases [[:source-ended
                (let [s (closed-source)] [s (buffer 64) (initial s)])]
               [:destination-closed
                (let [s (closed-source)] [s (sealed! (buffer 64)) (initial s)])]
               [:source-gap
                [(refusing-reader :dao.stream/gap) (buffer 8)
                 (d/forward-initial-state :cursor)]]
               [:invalid-input
                (let [s (-> (buffer 8) (fill! [[16 :yin/type]]) (sealed!))]
                  [s (buffer 8) (initial s)])]]]
    (doseq [[status [source destination state]] cases]
      (let [final (drive source destination state)]
        (is (= status (:status final)))
        (is (= final (d/forward-step source destination final {:batch-budget 8}))
            (str status " re-steps to itself"))))))


;; =============================================================================
;; D6: the remaining §8 matrix rows
;; =============================================================================

(defn- pairwise-distinct?
  [xs]
  (= (count xs) (count (set xs))))


(deftest zero-one-and-multi-parameter-lambdas-are-distinct
  (let [fp (comp :fingerprint project)
        body (app (v 'f) (lit 1))]
    (is (pairwise-distinct? [(fp (lam '[] body))
                             (fp (lam '[x] body))
                             (fp (lam '[x y] body))])
        "arity alone separates lambdas over one body")
    (is (= [0 1 2]
           (map #(:yin.debruijn/arity (:root (project (lam % body))))
                ['[] '[x] '[x y]])))
    (is (= (fp (lam '[] body)) (fp (lam '[] body)))
        "a zero-parameter lambda has no binder to rename")
    (is (= (fp (lam '[a] (v 'a))) (fp (lam '[x] (v 'x)))))
    (is (= (fp (lam '[a b] (app (v 'f) (v 'a) (v 'b))))
           (fp (lam '[x y] (app (v 'f) (v 'x) (v 'y))))))
    (is (not= (fp (lam '[a] (v 'a))) (fp (lam '[a b] (v 'a))))
        "a renamed twin must also match arity")))


(deftest parameter-order-is-identity
  (let [fp (comp :fingerprint project)
        bound-of (fn [ast] (get-in (project ast) [:root :yin.debruijn/body :yin.debruijn/bound]))]
    (is (= [0 0] (bound-of (lam '[x y] (v 'x)))))
    (is (= [0 1] (bound-of (lam '[y x] (v 'x)))))
    (is (not= (fp (lam '[x y] (v 'x))) (fp (lam '[y x] (v 'x)))))
    (is (= (fp (lam '[a b] (v 'a))) (fp (lam '[x y] (v 'x)))))
    (is (= (fp (lam '[a b] (v 'b))) (fp (lam '[y x] (v 'x)))))))


(deftest stream-operations-change-the-fingerprint
  (let [fp (comp :fingerprint project)
        source {:type :stream/make, :buffer 4}
        over (fn [tag] {:type tag, :source source})]
    (is (pairwise-distinct? [(fp (over :stream/cursor))
                             (fp (over :stream/next))
                             (fp (over :stream/close))])
        "one source, three operations, three identities")
    (is (not= (fp {:type :stream/put, :target source, :val (lit 7)})
              (fp {:type :stream/put, :target (lit 7), :val source}))
        "a put's target and value are ordered slots")))


(deftest continuation-markers-are-distinct-and-stable
  (let [fp (comp :fingerprint project)
        markers [{:type :vm/current-continuation} {:type :vm/park} (lit nil)]]
    (is (pairwise-distinct? (map fp markers))
        "the two markers differ from each other and from a literal")
    (doseq [m markers]
      (is (= (fp m) (fp m) (:fingerprint (d/project-datoms (vec (reverse (datoms-of m))))))
          "stable under repeated projection and shuffled input"))
    (is (pairwise-distinct? [(fp (lam '[x] {:type :vm/park}))
                             (fp (lam '[x] {:type :vm/current-continuation}))])
        "and they stay distinct as subterms")))
