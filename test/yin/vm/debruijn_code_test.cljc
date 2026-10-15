(ns yin.vm.debruijn-code-test
  "B1 (docs/design/yin.vm.debruijn.stack.md S2): standalone tests of the
   `:yin.debruijn.code/*` dimension and its validator -- hand-built
   canonical instruction vectors, plus the retained B0 normalization
   contract and execution fixtures. Golden
   bytes and H values are pinned literals, computed once against this
   file's own encoding and checked on every host lane this test file
   compiles for; a later change to the opcode table, the scalar tag
   order, or the framing byte rules must fail these fixtures rather than
   silently forking H (verified manually during development: a one-line
   reorder of `scalar-classes` was tried and reverted, and it broke every
   golden fixture below -- see the final report)."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as ast-walker]
            [yin.vm.parity-test :as parity]
            [yin.vm.values :as values]
            [yin.vm.test-utils :as tu]
            [yin.vm-test :as vm-test]
            [yin.vm.debruijn-code :as dc]))


;; =============================================================================
;; Malformed rows are rejected
;; =============================================================================

(deftest malformed-rows-are-rejected
  (testing "an empty vector"
    (is (= {:rule :nonempty, :pc 0} (dc/image-defect []))))
  (testing "an unknown mnemonic"
    (is (= {:rule :mnemonic, :pc 0} (dc/image-defect [[:nope] [:return]]))))
  (testing "wrong arity (:const takes exactly one operand)"
    (is (= {:rule :arity, :pc 0} (dc/image-defect [[:const 1 2] [:return]]))))
  (testing "wrong operand kind (:gensym's prefix is :str)"
    (is (= {:rule :operand-kind, :pc 0} (dc/image-defect [[:gensym 5] [:return]]))))
  (testing "an unsaturated operand (:gensym's prefix may not be nil)"
    (is (= {:rule :saturation, :pc 0} (dc/image-defect [[:gensym nil] [:return]]))))
  (testing "a block that does not end in a terminator"
    (is (= {:rule :terminator, :pc 0} (dc/image-defect [[:const 1]]))))
  (testing "a shape-valid vector is accepted"
    (is (nil? (dc/image-defect [[:const 1] [:return]])))))


;; =============================================================================
;; Out-of-range bound operands are rejected
;; =============================================================================

(deftest out-of-range-bound-operands-are-rejected
  (testing "a dangling :jump target"
    (is (= {:rule :target-bounds, :pc 0} (dc/image-defect [[:jump 5] [:return]]))))
  (testing "a dangling :closure body"
    (is (= {:rule :target-bounds, :pc 0} (dc/image-defect [[:closure 1 9] [:return]]))))
  (testing "the design's own [5 0] case: depth 5 past a one-level chain"
    (let [image [[:closure 1 3] [:push] [:return] [:load-bound 5 0] [:return]]]
      (is (nil? (dc/well-formed-image? image))
          "shape-valid on its own -- the generic structural rules alone accept it")
      (is (= {:rule :scope, :pc 3} (dc/image-defect image))
          "the scope rule rejects it before execution")))
  (testing "position out of range for the enclosing arity"
    (let [image [[:closure 1 2] [:return] [:load-bound 0 3] [:return]]]
      (is (= {:rule :scope, :pc 2} (dc/image-defect image)))))
  (testing "a negative depth or position"
    (is (= {:rule :operand-kind, :pc 2}
           (dc/image-defect [[:closure 1 2] [:return] [:load-bound -1 0] [:return]]))
        "a negative :uint operand is already rejected by the generic shape rule"))
  (testing "a valid bound reference at depth 0 is accepted"
    (is (nil? (dc/image-defect [[:closure 1 2] [:return] [:load-bound 0 0] [:return]]))))
  (testing "nested closures: depth counts frames outward through the chain"
    ;; outer closure arity 2 (body at pc 2), inner closure arity 1 (body at
    ;; pc 5); the inner body's depth 1 reaches the outer frame's position 1
    (let [image [[:closure 2 2]                     ; pc 0
                 [:return]                            ; pc 1
                 [:closure 1 5]                        ; pc 2 (outer body)
                 [:push]                                ; pc 3
                 [:return]                              ; pc 4
                 [:load-bound 1 1]                       ; pc 5 (inner body)
                 [:return]]]                             ; pc 6
      (is (nil? (dc/image-defect image)) "depth 1 reaches the outer frame")
      (is (= {:rule :scope, :pc 5}
             (dc/image-defect (assoc image 5 [:load-bound 2 0])))
          "depth 2 has no third enclosing frame"))))


;; =============================================================================
;; Two closures declaring different arities for one body pc are rejected
;; (debruijn-b1-fix P1-2): the reviewer's exact fixture, in both
;; declaration orders, proving the fix does not just pick one winner by
;; walk order but rejects the conflict itself.
;; =============================================================================

(deftest conflicting-body-declarations-are-rejected
  (testing "arity-2 declared first, arity-1 second"
    (is (= {:rule :scope-conflict, :pc 3}
           (dc/image-defect [[:closure 2 3] [:closure 1 3] [:return]
                             [:load-bound 0 1] [:return]]))))
  (testing "the same fixture with the two :closure instructions swapped"
    (is (= {:rule :scope-conflict, :pc 3}
           (dc/image-defect [[:closure 1 3] [:closure 2 3] [:return]
                             [:load-bound 0 1] [:return]]))
        "rejected either way -- not just when the smaller arity is walked first")))


;; =============================================================================
;; Exact scalar spelling is preserved through encode/hash
;; =============================================================================

(def ^:private const-return
  "One :const value framed by a single :return -- the minimal carrier for
   every scalar-spelling fixture below."
  (fn [v] [[:const v] [:return]]))


(deftest exact-scalar-spelling-is-preserved
  ;; 1 vs 1.0 is its own JVM/Dart-only test below
  ;; (`one-vs-one-point-zero-distinct-on-jvm-and-dart`): a JS number
  ;; carries no long/double provenance of its own once it is integral, so
  ;; CLJS cannot hold that distinction (S2 item 4) and is excluded here.
  (testing "composed and decomposed e-acute hash to distinct values (no NFC)"
    (let [composed (const-return "é")
          decomposed (const-return "é")]
      (is (not= composed decomposed) "the two strings are not even = as data")
      (is (not= (dc/image-hash composed) (dc/image-hash decomposed))
          "and their images hash distinctly, proving no NFC silently unified them")))
  (testing "exact UTF-8 bytes: a string's encoded content is the string itself, unmodified"
    (is (= "0700000005hello" (dc/encode-scalar "hello")))))


;; =============================================================================
;; A ratio fixture: encoded on the JVM, refused where there is no ratio type.
;; Dart joins CLJS here (`host-ratio?`): ClojureDart has no ratio type and
;; no `numerator`/`denominator`, so a `1/2` literal is not even reader-safe
;; outside the :clj branch (S2 item 5's own comment on this, below).
;; =============================================================================

(deftest ratio-fixture
  (testing "the JVM choice: ratio has its own scalar class and encodes"
    #?(:clj
       (do
         (is (= :ratio (dc/scalar-class 1/2)))
         (is (some? (dc/encode-scalar 1/2)))
         (is (nil? (dc/validate-host-support (const-return 1/2))))
         (is (not= (dc/image-hash (const-return 1/2)) (dc/image-hash (const-return 2)))))
       :default nil)))


;; =============================================================================
;; 1 vs 1.0 hash distinctly (JVM/Dart); ratio and char are JVM-only
;; =============================================================================
;; CLJS cannot hold the 1/1.0 distinction (S2 item 4): a JS number carries
;; no long/double provenance of its own once it is integral. CLJS has no
;; ratio or character type at all, and ClojureDart's `char?` does not
;; reliably distinguish a genuine char from a one-codepoint string, so
;; this dimension never classifies a CLJS or CLJD value :ratio or :char
;; (`host-ratio?`, `host-char?`).

(deftest one-vs-one-point-zero-distinct-on-jvm-and-dart
  #?(:cljs nil
     :default
     (do
       (is (= :long (dc/scalar-class 1)))
       (is (= :double (dc/scalar-class 1.0)))
       (is (not= (dc/encode-scalar 1) (dc/encode-scalar 1.0)))
       (is (not= (dc/image-hash (const-return 1)) (dc/image-hash (const-return 1.0)))))))


(deftest char-distinct-from-other-classes-on-jvm
  #?(:clj
     (do
       (is (= :char (dc/scalar-class \a)))
       (is (= :string (dc/scalar-class "a"))
           "a genuine char and a one-codepoint string still classify apart")
       (is (not= (dc/encode-scalar \a) (dc/encode-scalar "a")))
       (is (not= (dc/image-hash (const-return \a)) (dc/image-hash (const-return "a")))))
     :default nil))


(deftest ratio-distinct-from-other-classes-on-jvm
  #?(:clj
     (do
       (is (= :ratio (dc/scalar-class 1/2)))
       (is (not= (dc/encode-scalar 1/2) (dc/encode-scalar 2)))
       (is (not= (dc/image-hash (const-return 1/2)) (dc/image-hash (const-return "1/2")))))
     :default nil))


;; =============================================================================
;; :unsupported-value is actually refused, on every host (debruijn-b1-fix
;; P2-2: closes the coverage gap P1-1 survived through -- no test had ever
;; exercised any refusal path before this).
;; =============================================================================

(def ^:private lone-surrogate
  "An unpaired UTF-16 high surrogate: constructible on every host, and
   refused everywhere by `well-formed-utf16?` (S2's own UTF-16 guard)."
  #?(:clj "\uD800"
     :cljs (js/String.fromCharCode 0xD800)
     :cljd (String.fromCharCode 0xD800)))


(defn- unsupported-value-rule
  "Runs `f`, returning the :rule of the ex-info it throws, or ::no-throw."
  [f]
  (try (f) ::no-throw
       (catch #?(:cljd Object :clj Exception :cljs :default) e (:rule (ex-data e)))))


(deftest unsupported-value-is-refused-not-silently-hashed
  (testing "encode-scalar refuses a lone UTF-16 surrogate directly"
    (is (= :unsupported-value (unsupported-value-rule #(dc/encode-scalar lone-surrogate)))))
  (testing "image-hash refuses it inside a :const operand"
    (is (= :unsupported-value
           (unsupported-value-rule #(dc/image-hash (const-return lone-surrogate))))))
  (testing "image-defect rejects it inside a :str operand before hashing (P1-1's
            first entry point: the structural :str kind check)"
    (is (= {:rule :operand-kind, :pc 0}
           (dc/image-defect [[:gensym lone-surrogate] [:return]]))))
  (testing "image-hash also refuses it inside a :str operand (P1-1's second
            entry point: encode-operand, independent of whether image-defect
            ran first)"
    (is (= :unsupported-value
           (unsupported-value-rule #(dc/image-hash [[:gensym lone-surrogate] [:return]])))))
  (testing "the JVM refuses a BigDecimal/Float instead of silently folding it
            to the nearest double (debruijn-b1-fix P2-1), pinning host-double?
            against a regression to the old complement rule"
    #?(:clj
       (do
         (is (= :unsupported-value (unsupported-value-rule #(dc/encode-scalar 0.1M))))
         (is (= :unsupported-value (unsupported-value-rule #(dc/encode-scalar (float 1.5))))))
       :default nil)))


;; =============================================================================
;; A ratio component outside int64 range is refused, qualified, not with an
;; unqualified host exception (debruijn-b1-fix P3-2)
;; =============================================================================

(deftest ratio-out-of-long-range-is-refused
  #?(:clj
     (is (= :unsupported-value
            (unsupported-value-rule #(dc/encode-scalar (/ 10000000000000000000N 3)))))
     :default nil))


;; =============================================================================
;; :uint/:pc operands are bounded to the safe-integer range on CLJS, so a
;; validation outcome does not diverge by host (debruijn-b1-fix P3-3)
;; =============================================================================

(deftest unsafe-integer-uint-operand-is-rejected-on-cljs
  #?(:cljs
     (is (= {:rule :operand-kind, :pc 0} (dc/image-defect [[:call 1e30 true] [:return]])))
     :default nil))


;; =============================================================================
;; Identical bytes across all three hosts for the common scalar domain
;; =============================================================================
;; Pinned literals below are this pattern's own check: the same expected
;; string is asserted on every host this file compiles for, so a
;; divergence on any one host fails its own lane's run of this test.

(def ^:private common-domain-fixtures
  "Values every host can represent identically: no char, ratio, or
   bigint. `image-hash` values are pinned below in the golden-corpus
   test; this table pins `encode-scalar` output directly, one level
   lower, for values not otherwise covered there."
  [[nil "0000000000"]
   [true "010000000201"]
   [false "010000000200"]
   [42 "02000000102a00000000000000"]
   ["hello" "0700000005hello"]
   [:a/b "08000000160700000001a0700000001b"]
   ['x/y "09000000160700000001x0700000001y"]
   [[1 2] "0a000000340200000010010000000000000002000000100200000000000000"]
   [#{1 2} "0d000000340200000010010000000000000002000000100200000000000000"]])


(deftest identical-bytes-across-hosts-for-common-domain
  (doseq [[v expected] common-domain-fixtures]
    (testing (pr-str v)
      (is (= expected (dc/encode-scalar v))))))


;; =============================================================================
;; Golden image bytes and H values for a frozen corpus, on all three lanes
;; =============================================================================

(def ^:private golden-corpus
  "A small, fixed corpus of hand-built executable images, covering
   :const, :closure/:load-bound, :load-free, and the scalar classes this
   phase tests. Pinned bytes and H below were computed once against this
   file's own encoding (see the namespace docstring for how the pin was
   proved load-bearing)."
  {:const-long [[:const 42] [:return]]
   :const-string [[:const "hello"] [:return]]
   :closure-bound [[:closure 1 2] [:return] [:load-bound 0 0] [:return]]
   :load-free [[:load-free 'x] [:return]]})


(def ^:private golden-expected
  "[encoded-image-hex image-hash], by the same keys as `golden-corpus`."
  ;; The image-hash literals below changed in the debruijn-b1-fix review
  ;; round (P3-4): the descriptor used to hash two prose sentences (the
  ;; :image-hash formula string and the lift-morphism sentence), so
  ;; removing them from the hashed descriptor -- keeping it pure data --
  ;; forked descriptor-hash, and therefore every H here, exactly once.
  ;; `encode-image` (the image bytes themselves) does not depend on the
  ;; descriptor at all and is unchanged.
  ;; Rule R moved every H again (lowering-contract-version 2, the "b2"
  ;; contract) and the image bytes too: `:define` joined the sorted
  ;; mnemonic tag table, shifting the tag of every mnemonic after it.
  {:const-long ["0302000000102a000000000000000f"
                "fa643897ef8a5377b4db7682ec9118149e7c4c01d6b3a1491c4bbe8fe60fd2ba"]
   :const-string ["030700000005hello0f"
                  "d748b428803c06b957fb945df2ca2627633fc8e1cdde0caadb1e90df6f19c5d3"]
   :closure-bound [(str "02010000000000000002000000000000000f0a0000000000"
                        "00000000000000000000000f")
                   "64afffdc1e88fe97911cfac010b98c5f147123942486b947f60c66d20a03f6d7"]
   :load-free ["0b090000001500000000000700000001x0f"
               "9ab244f27567aa96519d602c3555c85bbf283489e8c5d919f70976c3a42e8c63"]})


(deftest golden-corpus-bytes-and-hash
  (doseq [[k image] golden-corpus]
    (testing k
      (let [[expected-bytes expected-hash] (get golden-expected k)]
        (is (nil? (dc/image-defect image)) "every golden fixture is itself valid")
        (is (= expected-bytes (dc/encode-image image)))
        (is (= expected-hash (dc/image-hash image)))))))


;; =============================================================================
;; This dimension's own opcode table
;; =============================================================================

(deftest opcode-table-is-this-dimensions-own-data
  (is (= 23 (count dc/opcode-table)))
  (is (not (contains? dc/opcode-table :var)))
  (doseq [slots (vals dc/opcode-table)
          [attr kind] slots]
    (is (= "yin.debruijn.code" (namespace attr)))
    (is (contains? #{:data :sym :kw :str :bool :buffer :pc :uint} kind)))
  (testing ":var splits into :load-bound and :load-free; :closure changes shape"
    (is (not (contains? dc/opcode-table :var)))
    (is (= [[:yin.debruijn.code/depth :uint] [:yin.debruijn.code/position :uint]]
           (:load-bound dc/opcode-table)))
    (is (= [[:yin.debruijn.code/name :sym]] (:load-free dc/opcode-table)))
    (is (= [[:yin.debruijn.code/arity :uint] [:yin.debruijn.code/body :pc]]
           (:closure dc/opcode-table)))))


;; =============================================================================
;; The descriptor
;; =============================================================================

(deftest descriptor-declares-the-contract
  (is (= dc/lowering-contract-version
         (some #(when (= :dim/lowering-contract-version (second %)) (nth % 2)) dc/descriptor)))
  (is (= (count dc/opcode-table)
         (some #(when (= :dim/arity (second %)) (nth % 2)) dc/descriptor)))
  (is (= "7b7c6ae92e5fe53577718b615a3ed0bdd81796bf8074efe8c07d7005c45f3489" dc/descriptor-hash))
  (testing "the hashed descriptor is pure data: no string value anywhere in it (P3-4)"
    (is (not (some #(some string? (tree-seq coll? seq %)) dc/descriptor)))))


;; =============================================================================
;; Host-boundary scalar-class support
;; =============================================================================

(deftest host-boundary-refusal-for-an-unsupported-class
  ;; A ratio or bigint literal is read by the host reader itself, not just
  ;; evaluated, so a branch naming a host without that type cannot even
  ;; contain one as a fixture -- CLJS has neither type to construct a
  ;; value from at all, and this dimension does not classify :cljd as
  ;; having ratio or bigint either (see `host-ratio?`, `host-bigint?`).
  ;; This checks the declared support set directly, then exercises the
  ;; actual refusal/acceptance path where genuine instances are
  ;; host-reader-safe to build (JVM).
  (is (= (set/difference (set dc/scalar-classes) #?(:cljs #{:char :ratio :bigint}
                                                    :cljd #{:char :ratio :bigint}
                                                    :clj #{}))
         dc/host-supported-scalar-classes))
  #?(:clj
     (do
       (is (nil? (dc/validate-host-support (const-return 10000000000000000000N)))
           "the JVM has a verified bigint predicate (host-bigint?), so bigint is supported here")
       (is (nil? (dc/validate-host-support (const-return 1/2)))
           "the JVM supports every declared class this phase tests"))
     :default nil))


;; B0 contract facts and shared normalizer, retained after lowerer retirement.
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
    ;; a host-typed closure or continuation normalizes as its payload
    (values/host-typed? value) (normalize-node (values/payload value))

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
;; this purpose (mint_test.cljc requires both under the same names).

(def ^:private tag-corpus
  "Every AST the emitter's grammar defines a tag for, reused from
   `yin.vm-test/semantic-bytecode-corpus` -- mint_test's own reuse
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
    (is (= (set (keys vm/semantic-bytecode-grammar))
           (into #{}
                 (comp (mapcat (comp vals :rows vm/ast->semantic-bytecode))
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
        (let [via-batch (normalize (vm/value (vm/eval (tu/create-vm) ast)))
              via-rows (normalize
                         (vm/value
                           (vm/run
                             (ast-walker/vm-load-rows
                               (tu/create-vm)
                               (vm/ast->semantic-bytecode ast)
                               vm/ast-contract))))]
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
        run1 (normalize (vm/value (vm/eval (tu/create-vm) ast)))
        run2 (normalize (vm/value (vm/eval (tu/create-vm) ast)))]
    (is (= {:type :closure, :arity 1} run1) "arity only, no params or body")
    (is (= run1 run2) "self-parity")))


(deftest continuation-normalization-test
  (testing "a reified continuation (:vm/current-continuation)"
    (let [ast {:type :vm/current-continuation}
          run1 (normalize (vm/value (vm/eval (tu/create-vm) ast)))
          run2 (normalize (vm/value (vm/eval (tu/create-vm) ast)))]
      (is (= {:type :reified-continuation} run1) "type only, no k or env")
      (is (= run1 run2) "self-parity")))
  (testing "a parked continuation (:vm/park), UCF-shaped"
    (let [ast {:type :vm/park}
          run1 (normalize (vm/value (vm/eval (tu/create-vm) ast)))
          run2 (normalize (vm/value (vm/eval (tu/create-vm) ast)))]
      (is (= {:type :parked-continuation} run1) "type only, no id, k or env")
      (is (= run1 run2) "self-parity"))))


(deftest error-normalization-test
  (let [ast (app (lit 1) (lit 2))
        catch-normalized
        (fn []
          (try
            (vm/eval (tu/create-vm) ast)
            ::no-error
            (catch #?(:cljd Object :clj Exception :cljs :default) e
              (normalize-error e))))
        run1 (catch-normalized)
        run2 (catch-normalized)]
    (is (= "Cannot apply non-function" (:message run1)))
    (is (= {:reason :not-applicable, :kind :number} (:data run1))
        "a qualified refusal: the operator's kind, never the value")
    (is (= run1 run2) "self-parity")))


(deftest stream-normalization-test
  (let [ast {:type :stream/make, :buffer 4}
        run1 (normalize (vm/value (vm/eval (tu/create-vm) ast)))
        run2 (normalize (vm/value (vm/eval (tu/create-vm) ast)))]
    (is (= {:type :stream-ref, :id :stream-0} run1) "by identity, as parity/normalize already reduces it")
    (is (= run1 run2) "self-parity")))


(deftest cursor-normalization-test
  (let [make-cursor (fn []
                      (let [vm0 (vm/eval (tu/create-vm) {:type :stream/make, :buffer 4})
                            sref (vm/value vm0)
                            vm1 (vm/eval vm0 {:type :stream/cursor, :source (lit sref)})]
                        (normalize (vm/value vm1))))
        run1 (make-cursor)
        run2 (make-cursor)]
    (is (= :cursor-ref (:type run1)))
    (is (= run1 run2) "self-parity across two fresh VMs")))


(deftest store-normalization-test
  (let [ast {:type :vm/store-put, :key :b0/k, :val {:type :literal, :ignored true}}
        make-store (fn []
                     (let [vm (vm/eval (tu/create-vm) ast)]
                       ;; host handles and telemetry are excluded (S1):
                       ;; exclude the default VM's call-in/call-out/cursor keys.
                       (normalize (apply dissoc (vm/store vm)
                                         #{vm/call-in-stream-key vm/call-out-stream-key
                                           vm/call-out-cursor-key}))))
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
          run1 (normalize (vm/value (vm/eval (tu/create-vm) ast)))
          run2 (normalize (vm/value (vm/eval (tu/create-vm) ast)))]
      (is (= 2 run1))
      (is (= run1 run2))))
  (testing "the closure itself, unapplied, still normalizes to its arity
            with the duplicate name collapsed by nothing -- arity counts
            declared params, not distinct names"
    (let [ast (lambda '[x x] (variable 'x))
          run1 (normalize (vm/value (vm/eval (tu/create-vm) ast)))]
      (is (= {:type :closure, :arity 2} run1)))))
