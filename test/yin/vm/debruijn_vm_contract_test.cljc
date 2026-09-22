(ns yin.vm.debruijn-vm-contract-test
  "B0 (docs/design/yin.vm.debruijn-vm.md, 'B0: contract and normalizer'):
   freezes the result/error normalizer and the parity corpus that later
   phases (B1+) depend on. Adds no evaluator, no AST, no dimension -- it
   only pins tools that already exist.

   The normalizer wraps `yin.vm.parity-test/normalize`, which already
   strips a closure's host environment and reduces a stream-ref to its id;
   this namespace extends that with the design's arity-only closure rule,
   the cursor-ref-by-id rule, and the type-only continuation rule (S1,
   'Architecture and invariants'), and applies the whole rule set
   recursively with the same traversal `yin.vm/strip-reader-positions`
   already uses, so a store snapshot or an ex-data map normalizes its
   nested values too.

   The corpus is `yin.vm.parity-test/corpus` (the v2 parity corpus) plus
   `yin.vm-test/semantic-bytecode-corpus` (content_test's own every-tag
   reuse target, already proved by `yin.vm-test/semantic-bytecode-corpus-
   covers-every-tag` to cover every `semantic-bytecode-grammar` tag).
   `yin.vm.completion-test`'s own extra fixtures (`scoping-corpus`,
   `kitchen-sink`, `maker-program`, and `conformance-corpus` itself) are
   `^:private` and the file box for this phase forbids editing that
   namespace to expose them, so they are not required here; the coverage
   assertion below still holds because the reused corpus alone already
   spans every tag."
  (:require [clojure.test :refer [deftest is testing]]
            [yin.vm :as v2]
            [yin.vm.ast-walker :as ast-walker]
            [yin.vm.completion :as completion]
            [yin.vm.parity-test :as parity]
            [yin.vm.test-utils :as tu]
            [yin.vm-test :as vm-test]))


;; =============================================================================
;; The normalizer
;; =============================================================================

(defn- normalize-node
  "One node of the design's B0 normalizer: reduces a closure to its arity,
   a cursor reference to its id, and a parked or reified continuation to
   its bare type; everything else -- including the stream-ref-by-id and
   host-fn rules -- defers to `parity/normalize`, reused rather than
   reimplemented."
  [value]
  (cond
    (and (map? value) (= :closure (:type value)))
    {:type :closure,
     :arity (if (contains? value :arity) (:arity value) (count (:params value)))}

    (and (map? value) (= :cursor-ref (:type value)))
    {:type :cursor-ref, :id (:id value)}

    (and (map? value)
         (contains? #{:parked-continuation :reified-continuation} (:type value)))
    {:type (:type value)}

    :else (parity/normalize value)))


(defn normalize
  "B0's frozen result/error normalizer (design doc S1): a value comparable
   independent of host or object identity. Depth-first, transforming
   children before the collection itself -- the same traversal shape as
   `yin.vm/strip-reader-positions`, reused here for a different rewrite --
   so a normalized closure, continuation, or stream/cursor reference nested
   inside a store snapshot or an ex-data map is caught too."
  [value]
  (let [value' (cond (record? value) value
                     (map? value) (into (empty value)
                                        (map (fn [[k v]] [(normalize k) (normalize v)]))
                                        value)
                     (set? value) (into (empty value) (map normalize) value)
                     (vector? value) (mapv normalize value)
                     (sequential? value) (apply list (map normalize value))
                     :else value)]
    (normalize-node value')))


(defn normalize-error
  "The design's error rule (S1): the message, plus ex-data with the value
   normalizer recursively applied to every value it holds."
  [ex]
  {:message (ex-message ex), :data (normalize (ex-data ex))})


;; =============================================================================
;; The corpus
;; =============================================================================
;; Reused, not duplicated: the v2 parity corpus and the codec's every-tag
;; corpus, both already public and already exercised elsewhere for exactly
;; this purpose (content_test.cljc requires both under the same names).

(def ^:private tag-corpus
  "Every AST the emitter's grammar defines a tag for, reused from
   `yin.vm-test/semantic-bytecode-corpus` -- content_test's own reuse
   target for the same claim."
  vm-test/semantic-bytecode-corpus)


;; =============================================================================
;; Helpers for the fixtures this namespace adds
;; =============================================================================

(defn- lit
  [v]
  {:type :literal, :value v})


(defn- variable
  [n]
  {:type :variable, :name n})


(defn- lambda
  [params body]
  {:type :lambda, :params params, :body body})


(defn- app
  [f & args]
  {:type :application, :operator f, :operands (vec args)})


;; =============================================================================
;; 1. Coverage: every tag the reused corpora exercise appears here
;; =============================================================================

(deftest all-node-fixture-coverage-test
  (testing "the reused every-tag corpus plus the v2 parity corpus together
            exercise every semantic-bytecode tag -- the same technique
            `yin.vm-test/semantic-bytecode-corpus-covers-every-tag` already
            uses to prove this of `tag-corpus` alone"
    (is (= (set (keys v2/semantic-bytecode-grammar))
           (into #{}
                 (comp (mapcat (comp vals :rows v2/ast->semantic-bytecode))
                       (map second))
                 (concat tag-corpus (map second parity/corpus)))))))


;; =============================================================================
;; 2. Named-VM self-parity
;; =============================================================================

(deftest named-vm-self-parity-test
  (testing "every parity-corpus program normalizes the same across two
            independent named-VM runs"
    (doseq [[name ast _expected] parity/corpus]
      (testing name
        (is (= (normalize (tu/compile-and-run ast))
               (normalize (tu/compile-and-run ast)))))))
  (testing "the walker fed on a datom batch agrees with the walker fed on
            rows, the same two paths `parity-test/ast-walker-parity-test`
            already compares"
    (doseq [[name ast _expected] parity/corpus]
      (testing name
        (let [via-batch (normalize (v2/value (v2/eval (tu/create-vm) ast)))
              via-rows (normalize
                         (v2/value
                           (v2/run
                             (ast-walker/vm-load-rows
                               (tu/create-vm) (v2/ast->semantic-bytecode ast)))))]
          (is (= via-batch via-rows)))))))


;; =============================================================================
;; 3. Idempotence of the normalizer
;; =============================================================================

(def ^:private idempotence-fixtures
  [42
   "hello"
   nil
   [1 2 3]
   {:x 10, :y 20}
   #{1 2}
   {:type :closure, :params '[x y], :body (variable 'x), :env {'+ +}}
   {:type :stream-ref, :id :stream-3}
   {:type :cursor-ref, :id :cursor-1}
   {:type :parked-continuation, :id :parked-2, :k nil, :env {}}
   {:type :reified-continuation, :k nil, :env {'x 1}}
   +
   {:store/a {:type :closure, :params '[x], :body (variable 'x), :env {}},
    :store/b {:type :stream-ref, :id :stream-0},
    :store/c 7}])


(deftest normalizer-idempotence-test
  (doseq [value idempotence-fixtures]
    (testing (pr-str value)
      (is (= (normalize value) (normalize (normalize value)))))))


;; =============================================================================
;; 4. Normalized comparisons per category
;; =============================================================================

(deftest closure-normalization-test
  (let [ast (lambda '[x] (variable 'x))
        run1 (normalize (v2/value (v2/eval (tu/create-vm) ast)))
        run2 (normalize (v2/value (v2/eval (tu/create-vm) ast)))]
    (is (= {:type :closure, :arity 1} run1) "arity only, no params or body")
    (is (= run1 run2) "self-parity")))


(deftest continuation-normalization-test
  (testing "a reified continuation (:vm/current-continuation)"
    (let [ast {:type :vm/current-continuation}
          run1 (normalize (v2/value (v2/eval (tu/create-vm) ast)))
          run2 (normalize (v2/value (v2/eval (tu/create-vm) ast)))]
      (is (= {:type :reified-continuation} run1) "type only, no k or env")
      (is (= run1 run2) "self-parity")))
  (testing "a parked continuation (:vm/park), UCF-shaped per
            yin.vm.completion"
    (let [ast {:type :vm/park}
          run1 (normalize (v2/value (v2/eval (tu/create-vm) ast)))
          run2 (normalize (v2/value (v2/eval (tu/create-vm) ast)))]
      (is (= {:type :parked-continuation} run1) "type only, no id, k or env")
      (is (= run1 run2) "self-parity"))))


(deftest error-normalization-test
  (let [ast (app (lit 1) (lit 2))
        catch-normalized
        (fn []
          (try
            (v2/eval (tu/create-vm) ast)
            ::no-error
            (catch #?(:cljd Object :clj Exception :cljs :default) e
              (normalize-error e))))
        run1 (catch-normalized)
        run2 (catch-normalized)]
    (is (= "Cannot apply non-function" (:message run1)))
    (is (= {:fn 1} (:data run1)) "ex-data with the value normalizer applied")
    (is (= run1 run2) "self-parity")))


(deftest stream-normalization-test
  (let [ast {:type :stream/make, :buffer 4}
        run1 (normalize (v2/value (v2/eval (tu/create-vm) ast)))
        run2 (normalize (v2/value (v2/eval (tu/create-vm) ast)))]
    (is (= {:type :stream-ref, :id :stream-0} run1) "by identity, as parity/normalize already reduces it")
    (is (= run1 run2) "self-parity")))


(deftest cursor-normalization-test
  (let [make-cursor (fn []
                      (let [vm0 (v2/eval (tu/create-vm) {:type :stream/make, :buffer 4})
                            sref (v2/value vm0)
                            vm1 (v2/eval vm0 {:type :stream/cursor, :source (lit sref)})]
                        (normalize (v2/value vm1))))
        run1 (make-cursor)
        run2 (make-cursor)]
    (is (= :cursor-ref (:type run1)))
    (is (= run1 run2) "self-parity across two fresh VMs")))


(deftest store-normalization-test
  (let [ast {:type :vm/store-put, :key :b0/k, :val {:type :literal, :ignored true}}
        make-store (fn []
                     (let [vm (v2/eval (tu/create-vm) ast)]
                       ;; host handles and telemetry are excluded (S1):
                       ;; `completion/ffi-pair-keys` already names the
                       ;; default VM's own call-in/call-out/cursor keys,
                       ;; the same set `completion-test` excludes from a
                       ;; store slice.
                       (normalize (apply dissoc (v2/store vm) completion/ffi-pair-keys))))
        run1 (make-store)
        run2 (make-store)]
    (is (= {:type :literal, :ignored true} (:b0/k run1))
        "a plain-data store value normalizes unchanged")
    (is (= run1 run2) "self-parity")))


;; =============================================================================
;; 5. Duplicate parameters
;; =============================================================================

(deftest duplicate-parameter-normalization-test
  (testing "rightmost binding wins (engine/bind-params zips params over
            args into a map, so a repeated name keeps its last value), and
            the two normalized runs still agree"
    (let [ast (app (lambda '[x x] (variable 'x)) (lit 1) (lit 2))
          run1 (normalize (v2/value (v2/eval (tu/create-vm) ast)))
          run2 (normalize (v2/value (v2/eval (tu/create-vm) ast)))]
      (is (= 2 run1))
      (is (= run1 run2))))
  (testing "the closure itself, unapplied, still normalizes to its arity
            with the duplicate name collapsed by nothing -- arity counts
            declared params, not distinct names"
    (let [ast (lambda '[x x] (variable 'x))
          run1 (normalize (v2/value (v2/eval (tu/create-vm) ast)))]
      (is (= {:type :closure, :arity 2} run1)))))
