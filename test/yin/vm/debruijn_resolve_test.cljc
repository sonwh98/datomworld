(ns yin.vm.debruijn-resolve-test
  "B2 (docs/design/yin.vm.debruijn.stack.md S3.1): completion tests for
   `yin.vm.debruijn-resolve`.

   Every program below is a plain map AST run through `yin.vm/ast->datoms-
   with-root`, then `yin.vm.debruijn-resolve/resolve`. The AST fixture
   builders are this file's own copy, matching `yin.vm.linearize-test`'s
   construction style, so this file needs no change to that namespace."
  (:require [clojure.test :refer [deftest is testing]]
            [yin.vm :as vm]
            [yin.vm.debruijn-resolve :as dr]))


;; =============================================================================
;; AST fixtures
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


(defn- tail
  [node]
  (assoc node :tail? true))


(defn- if-node
  [t c a]
  {:type :if, :test t, :consequent c, :alternate a})


(def ^:private worked-example
  "`((fn [x] (+ x 1)) 10)`: one level of closure nesting, no duplicate
   parameters, one free primitive reference."
  (app (lam '[x] (tail (app (v '+) (v 'x) (lit 1)))) (lit 10)))


(def ^:private duplicate-param
  "`(fn [x x] x)`: `resolve-name`'s rightmost-wins duplicate-parameter
   case, the design's own named example."
  (lam '[x x] (tail (v 'x))))


(def ^:private free-variable
  "A body that references a name no enclosing closure binds: `y` resolves
   to `:yin.resolved/free`, never `:yin.resolved/depth`/`:yin.resolved/
   position`."
  (lam '[x] (tail (app (v '+) (v 'x) (v 'y)))))


(def ^:private nested-closure
  "Two levels of nesting, an inner body reaching two frames outward, and a
   duplicate binder at the outer level."
  (app (lam '[a a]
            (tail (lam '[b]
                       (tail (app (v '+) (v 'a) (v 'b))))))
       (lit 1)))


(def ^:private macro-lambda
  "A `:lambda` node flagged `:macro? true`, so the corpus exercises
   `:yin/macro?` pass-through through `resolve`/`unresolve`."
  (assoc (lam '[x] (tail (v 'x))) :macro? true))


(def ^:private corpus
  "Every node type `resolve` must handle, reached at least once: :literal
   :variable :lambda :application :if :dao.stream.apply/call :stream/make
   :stream/put :stream/cursor :stream/next :stream/close :vm/gensym
   :vm/store-get :vm/store-put :vm/park :vm/current-continuation
   :vm/resume."
  {:worked-example worked-example,
   :duplicate-param duplicate-param,
   :free-variable free-variable,
   :nested-closure nested-closure,
   :literal (lit 42),
   :if-in-tail (app (lam '[n] (if-node (app (v '<) (v 'n) (lit 1))
                                       (lit :done)
                                       (tail (app (v '-) (v 'n) (lit 1)))))
                    (lit 3)),
   :streams {:type :stream/put,
             :target {:type :stream/make, :buffer 4},
             :val {:type :stream/next,
                   :source {:type :stream/cursor,
                            :source {:type :stream/close,
                                     :source (lit nil)}}}},
   :ffi {:type :dao.stream.apply/call, :op :op/echo, :operands [(lit 1) (lit 2)]},
   :ffi-no-args {:type :dao.stream.apply/call, :op :op/ping, :operands []},
   :default-gensym {:type :vm/gensym},
   :store-and-control (app (lam '[a b c d]
                                (tail (app (v 'vector) (v 'a) (v 'b) (v 'c) (v 'd))))
                           {:type :vm/store-put, :key 'k, :val 5}
                           {:type :vm/store-get, :key 'k}
                           {:type :vm/gensym, :prefix "g"}
                           {:type :vm/current-continuation}),
   :park-resume (if-node (lit false)
                         {:type :vm/park}
                         {:type :vm/resume, :parked-id :p1, :val (lit 7)})
   :macro-lambda macro-lambda})


;; =============================================================================
;; Helpers
;; =============================================================================

(defn- ast-datoms
  [ast]
  (second (vm/ast->datoms-with-root ast)))


(defn- resolved
  [ast]
  (dr/resolve (ast-datoms ast)))


(defn- attrs-of
  "`{eid {attr v}}` from a resolved tuple vector, for reading one
   entity's resolved facts by attribute."
  [tuples]
  (reduce (fn [acc [e a val]] (assoc-in acc [e a] val)) {} tuples))


;; =============================================================================
;; Shared-entity fixtures: an occurrence's resolution depends on its own
;; lexical context, not on a bare entity-keyed memo
;; =============================================================================

(def ^:private shared-variable-body
  "One `:variable` entity (a pre-assigned `:eid`), referenced as the body
   of two lambdas with different parameter vectors -- the exact shape
   `yin.vm.debruijn-test/shared-nodes-resolve-per-their-lexical-context`
   uses against the dormant projection."
  (assoc (v 'x) :eid -41))


(def ^:private shared-variable-under-two-contexts
  (app (v 'list) (lam '[x] shared-variable-body) (lam '[y] shared-variable-body)))


(def ^:private shared-lambda-body
  "A `:lambda` entity (a pre-assigned `:eid`) referenced from two
   different outer lambdas, whose own body references a name that is
   bound under one outer context and free under the other."
  (assoc (lam '[z] (tail (app (v '+) (v 'z) (v 'q)))) :eid -70))


(def ^:private shared-lambda-under-two-contexts
  (app (v 'list) (lam '[p q] shared-lambda-body) (lam '[r] shared-lambda-body)))


;; =============================================================================
;; Every node type is resolved without error, and free names/scalars
;; are preserved exactly
;; =============================================================================

(deftest every-corpus-program-resolves
  (doseq [[label ast] corpus]
    (testing label
      (let [{:keys [tuples source params]} (resolved ast)]
        (is (vector? tuples))
        (is (map? source))
        (is (map? params))
        (is (nil? (dr/validate-resolved tuples source)))))))


(deftest free-names-and-scalars-are-preserved-exactly
  (testing "a free reference keeps its exact symbol"
    (let [{:keys [tuples]} (resolved free-variable)
          attrs (attrs-of tuples)
          frees (keep (fn [[e a]] (when (= a :yin.resolved/free) (get-in attrs [e a])))
                      tuples)]
      (is (= '#{+ y} (set frees)))))
  (testing "a literal's exact scalar value survives unchanged"
    (let [{:keys [tuples]} (resolved (:literal corpus))
          attrs (attrs-of tuples)
          values (keep (fn [[e a]] (when (= a :yin/value) (get-in attrs [e a]))) tuples)]
      (is (= [42] (vec values))))))


;; =============================================================================
;; The duplicate-parameter fixture resolves to [0 1] (rightmost-wins)
;; =============================================================================

(deftest duplicate-parameter-resolves-rightmost-wins
  (let [{:keys [tuples]} (resolved duplicate-param)
        attrs (attrs-of tuples)
        var-eid (some (fn [[e a]] (when (= a :yin.resolved/depth) e)) tuples)]
    (is (some? var-eid))
    (is (= 0 (get-in attrs [var-eid :yin.resolved/depth])))
    (is (= 1 (get-in attrs [var-eid :yin.resolved/position])))))


;; =============================================================================
;; Unsupported node types are refused with a named diagnostic
;; =============================================================================

(deftest unsupported-node-types-are-refused
  (testing "an unexpanded macro-expand node"
    (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
          (dr/resolve (ast-datoms {:type :yin/macro-expand})))))
  (testing "a store-update node"
    (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
          (dr/resolve (ast-datoms {:type :vm/store-update}))))))


;; =============================================================================
;; Deterministic output
;; =============================================================================

(deftest resolving-twice-is-identical
  (doseq [[label ast] corpus]
    (testing label
      (is (= (resolved ast) (resolved ast))))))


;; =============================================================================
;; validate-resolved rejects a hand-built malformed resolved tuple set
;; =============================================================================

(deftest validate-resolved-rejects-an-out-of-range-bound-reference
  (testing "a depth with no enclosing lambda at all"
    (let [tuples [[1 :yin/type :variable 0 :db/add]
                  [1 :yin/root true 0 :db/add]
                  [1 :yin.resolved/depth 5 0 :db/add]
                  [1 :yin.resolved/position 0 0 :db/add]]]
      (is (= {:rule :scope, :entity 1} (dr/validate-resolved tuples {})))))
  (testing "a position past the enclosing lambda's declared arity"
    (let [tuples [[1 :yin/type :lambda 0 :db/add]
                  [1 :yin/root true 0 :db/add]
                  [1 :yin.resolved/arity 1 0 :db/add]
                  [1 :yin/body 2 0 :db/add]
                  [2 :yin/type :variable 0 :db/add]
                  [2 :yin.resolved/depth 0 0 :db/add]
                  [2 :yin.resolved/position 3 0 :db/add]]]
      (is (= {:rule :scope, :entity 2} (dr/validate-resolved tuples {}))))))


(deftest validate-resolved-rejects-other-malformed-shapes
  (testing "a record carrying both a bound and a free resolution"
    (let [tuples [[1 :yin/type :variable 0 :db/add]
                  [1 :yin/root true 0 :db/add]
                  [1 :yin.resolved/depth 0 0 :db/add]
                  [1 :yin.resolved/position 0 0 :db/add]
                  [1 :yin.resolved/free 'x 0 :db/add]]]
      (is (= {:rule :ambiguous-resolution, :entity 1}
             (dr/validate-resolved tuples {1 1})))))
  (testing "a structural ref pointing at an unknown record"
    (let [tuples [[1 :yin/type :application 0 :db/add]
                  [1 :yin/root true 0 :db/add]
                  [1 :yin/operator 99 0 :db/add]
                  [1 :yin/operands [] 0 :db/add]]]
      (is (= {:rule :dangling-ref, :entity 1, :ref 99}
             (dr/validate-resolved tuples {1 1})))))
  (testing "source is not total over the records"
    (let [tuples [[1 :yin/type :vm/park 0 :db/add]
                  [1 :yin/root true 0 :db/add]]]
      (is (= {:rule :source-total} (dr/validate-resolved tuples {}))))))


;; =============================================================================
;; resolve refuses to return an out-of-range program itself
;; =============================================================================
;; `resolve` can never actually build an out-of-range resolution from a
;; well-formed named AST (`resolve-name` only ever returns a depth that
;; indexes a real frame on its own stack), so this only exercises that
;; `resolve` calls `validate-resolved` on its own output, not that
;; ordinary corpus programs can trip it.

(deftest resolve-runs-its-own-validator
  (let [{:keys [tuples source]} (resolved worked-example)]
    (is (nil? (dr/validate-resolved tuples source)))))


;; =============================================================================
;; A cyclic input (via a pre-assigned :eid forming a cycle) is refused
;; =============================================================================

(deftest cyclic-input-is-refused
  (let [tuples [[1 :yin/type :lambda 0 :db/add]
                [1 :yin/params '[x] 0 :db/add]
                [1 :yin/body 2 0 :db/add]
                [2 :yin/type :application 0 :db/add]
                [2 :yin/operator 1 0 :db/add]
                [2 :yin/operands [] 0 :db/add]
                [1 :yin/root true 0 :db/add]]]
    (try
      (dr/resolve tuples)
      (is false "expected a cycle diagnostic")
      (catch #?(:clj Exception :cljs js/Error :cljd Object) e
        (is (= :cycle (:rule (ex-data e))))))))


;; =============================================================================
;; validate-resolved's own scope walk refuses a cyclic hand-built input
;; without overflowing the stack
;; =============================================================================

(deftest validate-resolved-rejects-a-cyclic-scope-walk
  (let [tuples [[1 :yin/type :application 0 :db/add]
                [1 :yin/root true 0 :db/add]
                [1 :yin/operator 2 0 :db/add]
                [1 :yin/operands [] 0 :db/add]
                [2 :yin/type :application 0 :db/add]
                [2 :yin/operator 1 0 :db/add]
                [2 :yin/operands [] 0 :db/add]]]
    (is (= {:rule :cycle, :entity 1} (dr/validate-resolved tuples {1 1, 2 2})))))


;; =============================================================================
;; validate-resolved refuses a hand-built node with no :yin/operands datom
;; at all, not just one that names a dangling ref
;; =============================================================================

(deftest validate-resolved-rejects-a-missing-operands-attribute
  (let [tuples [[1 :yin/type :application 0 :db/add]
                [1 :yin/root true 0 :db/add]
                [1 :yin/operator 2 0 :db/add]
                [2 :yin/type :literal 0 :db/add]
                [2 :yin/value 1 0 :db/add]]]
    (is (= {:rule :missing-operands, :entity 1, :attr :yin/operands}
           (dr/validate-resolved tuples {1 1, 2 2})))))


;; =============================================================================
;; The inverse law: unresolve(resolve x, source, params) = x
;; =============================================================================

(deftest unresolve-inverts-resolve-over-the-corpus
  (doseq [[label ast] corpus]
    (testing label
      (let [datoms (ast-datoms ast)
            {:keys [tuples source params]} (dr/resolve datoms)]
        (is (= datoms (dr/unresolve tuples source params))))))
  (doseq [[label ast] {:shared-variable-under-two-contexts
                       shared-variable-under-two-contexts,
                       :shared-lambda-under-two-contexts
                       shared-lambda-under-two-contexts}]
    (testing label
      (let [datoms (ast-datoms ast)
            {:keys [tuples source params]} (dr/resolve datoms)]
        (is (= datoms (dr/unresolve tuples source params)))))))


;; =============================================================================
;; Occurrence identity: a shared source entity resolves independently per
;; its own lexical context, not once for the whole entity
;; =============================================================================

(deftest a-shared-variable-resolves-per-its-own-context
  (let [{:keys [tuples source]} (resolved shared-variable-under-two-contexts)
        attrs (attrs-of tuples)
        shared-records (filter (fn [rid] (= -41 (get source rid))) (keys source))]
    (is (= 2 (count shared-records))
        "two distinct records for the one shared source entity")
    (let [resolutions (set (map (fn [rid]
                                  (select-keys (get attrs rid)
                                               [:yin.resolved/depth
                                                :yin.resolved/position
                                                :yin.resolved/free]))
                                shared-records))]
      (is (contains? resolutions {:yin.resolved/depth 0, :yin.resolved/position 0})
          "bound under the [x] context")
      (is (contains? resolutions {:yin.resolved/free 'x})
          "free under the [y] context"))))


(deftest a-shared-lambda-resolves-per-its-own-context
  (let [{:keys [tuples source]} (resolved shared-lambda-under-two-contexts)
        attrs (attrs-of tuples)
        shared-lambda-records (filter (fn [rid] (= -70 (get source rid))) (keys source))]
    (is (= 2 (count shared-lambda-records))
        "two distinct records for the one shared lambda entity")
    (is (every? (fn [rid] (= 1 (get-in attrs [rid :yin.resolved/arity])))
                shared-lambda-records))
    (let [free-q (keep (fn [[e a v]] (when (= a :yin.resolved/free) (when (= v 'q) e))) tuples)
          bound-q (keep (fn [[e a]] (when (= a :yin.resolved/depth) e)) tuples)]
      (is (seq free-q) "q is free under the [r] context")
      (is (>= (count bound-q) 2) "z and q (under [p q]) both resolve bound"))))
