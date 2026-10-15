(ns yin.vm.debruijn-register-code-test
  (:require [clojure.test :refer [deftest is testing]]
            [yin.vm.parity-test :as parity]
            [yin.vm.semantic.corpus :refer [lit if-node]]
            [yin.vm.semantic.linearize :as sr-linearize]
            [yin.vm.semantic.derive :as derive]
            [yin.vm.debruijn-register-code :as rcode]))


(deftest golden-descriptor-hash-test
  (is (= "aa757659a28f9787e44cd04d02d9f4412eba1088d46a1b07adf9fa820d07e74c"
         rcode/descriptor-hash)))


;; Register contract facts retained through projected semantic vectors.


;; =============================================================================
;; 1. The register descriptor's contract version
;; =============================================================================

(def register-contract-version
  "4, the version of the `:yin.debruijn.register/*` descriptor's
   contract (the \"r2\" contract, Rule R's `:define`). Bumped only when
   register opcode shape, allocation, scalar framing, or control-flow
   rules change."
  4)


(deftest register-contract-version-is-a-frozen-positive-integer
  (is (integer? register-contract-version))
  (is (pos? register-contract-version))
  (is (= 4 register-contract-version) "Rule R version"))


;; =============================================================================
;; 2. The operand mapping (design section 4.4)
;; =============================================================================
;; Frozen vocabulary from the original register operand-mapping contract.

(def resolver-node-type-vocabulary
  "The original register contract vocabulary; preserved as a format fact."
  #{:literal :variable :lambda :application :dao.stream.apply/call :if
    :vm/gensym :vm/store-get :vm/store-put :stream/make :stream/put
    :stream/cursor :stream/next :stream/close :vm/park
    :vm/current-continuation :vm/resume})


(def register-instruction-mnemonics
  "Section 4.4's register instruction set, as data, so the operand
   mapping below can be checked against it without hand-copying mnemonic
   spellings into each entry's assertion."
  #{:const :load-bound :load-free :closure :move :call :branch-false
    :jump :return :halt :store-get :store-put :gensym :stream-make
    :stream-put :stream-cursor :stream-next :stream-close :ffi-call
    :current-continuation :park :resume})


(def node-type->register-mapping
  "For every resolved-tuple node type in `resolver-node-type-vocabulary`,
   which register instruction shape from section 4.4's table it maps to.
   `:variable` carries two shapes under one key because its register
   mapping depends on the resolution it already carries (bound vs free),
   not on its node type alone."
  {:literal
   {:register-instructions [:const], :shape "[op rd value]"}

   :variable
   {:register-instructions [:load-bound :load-free]
    :bound {:instruction :load-bound, :shape "[op rd depth position]"}
    :free {:instruction :load-free, :shape "[op rd name]"}}

   :lambda
   {:register-instructions [:closure], :shape "[op rd arity body-pc]"}

   :application
   {:register-instructions [:call]
    :shape "[op rd fn-reg arg-regs tail? live]"}

   :if
   {:register-instructions [:branch-false :jump]
    :shape "[op cond-reg target] then [op target]; both arms write the
             expression's one destination register (design section 4.3)"}

   :dao.stream.apply/call
   {:register-instructions [:ffi-call]
    :shape "[op rd ffi-op arg-regs live]"}

   :stream/make
   {:register-instructions [:stream-make], :shape "[op rd capacity]"}

   :stream/put
   {:register-instructions [:stream-put]
    :shape "[op rd stream-reg value-reg live]"}

   :stream/cursor
   {:register-instructions [:stream-cursor], :shape "[op rd stream-reg]"}

   :stream/next
   {:register-instructions [:stream-next]
    :shape "[op rd cursor-reg live]"}

   :stream/close
   {:register-instructions [:stream-close], :shape "[op rd stream-reg]"}

   :vm/gensym
   {:register-instructions [:gensym], :shape "[op rd prefix]"}

   :vm/store-get
   {:register-instructions [:store-get], :shape "[op rd key]"}

   :vm/store-put
   {:register-instructions [:store-put], :shape "[op rd key value]"}

   :vm/park
   {:register-instructions [:park], :shape "[op rd live]"}

   :vm/resume
   {:register-instructions [:resume], :shape "[op parked-id value-reg]"}

   :vm/current-continuation
   {:register-instructions [:current-continuation], :shape "[op rd live]"}})


(deftest operand-mapping-covers-every-resolver-node-type-exactly
  (testing "no node type is silently unaccounted for"
    (is (= resolver-node-type-vocabulary
           (set (keys node-type->register-mapping))))))


(deftest all-mappings-name-only-section-four-four-instructions
  (doseq [[type mapping] node-type->register-mapping]
    (testing type
      (is (every? register-instruction-mnemonics
                  (:register-instructions mapping))))))


;; =============================================================================
;; 3. The two-way address law (design section 2.2)
;; =============================================================================
;; Resolution and stack lowering preserve the semantic vector's address order.
;; The B0 corpus and B2 shared-occurrence fixtures pin that law.

(defn resolved-addresses-of
  [ast]
  (derive/addresses (:vector (derive/resolve (:vector (sr-linearize/project ast))))))


(defn stack-addresses-of
  [ast]
  (derive/addresses (:image (derive/stack-image (:vector (sr-linearize/project ast))))))


;; -----------------------------------------------------------------------------
;; B2's duplicate-parameter and shared-occurrence fixtures are kept local
;; to this test file, following the corpus fixture convention.

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


(def ^:private duplicate-param
  "`(fn [x x] x)`: `resolve-name`'s rightmost-wins duplicate-parameter
   case, reused from B2's own named example."
  (lam '[x x] (tail (v 'x))))


(def ^:private free-variable
  "A body referencing a name no enclosing closure binds, reused from
   B2's own fixture of the same name."
  (lam '[x] (tail (app (v '+) (v 'x) (v 'y)))))


(def ^:private shared-variable-body
  (assoc (v 'x) :eid -41))


(def ^:private shared-variable-under-two-contexts
  "One `:variable` entity referenced as the body of two lambdas with
   different parameter vectors, reused from B2's own fixture of the same
   name: resolves bound under `[x]` and free under `[y]`."
  (app (v 'list) (lam '[x] shared-variable-body) (lam '[y] shared-variable-body)))


(def ^:private shared-lambda-body
  (assoc (lam '[z] (tail (app (v '+) (v 'z) (v 'q)))) :eid -70))


(def ^:private shared-lambda-under-two-contexts
  "A `:lambda` entity referenced from two outer lambdas, reused from
   B2's own fixture of the same name: its body's `q` resolves bound
   under one outer context and free under the other."
  (app (v 'list) (lam '[p q] shared-lambda-body) (lam '[r] shared-lambda-body)))


(def address-law-extra-fixtures
  "B2's duplicate-parameter and shared-occurrence fixtures the R0 box
   names by name, added to the reused B0 parity corpus below. Public for
   the same reason as `resolved-addresses-of`: R1's own address-law test
   reuses this directly, never via var-quote reflection."
  {:duplicate-param duplicate-param,
   :free-variable free-variable,
   :shared-variable-under-two-contexts shared-variable-under-two-contexts,
   :shared-lambda-under-two-contexts shared-lambda-under-two-contexts})


(deftest address-law-holds-over-the-b0-parity-corpus
  (testing "addresses(resolve x) = addresses(lower-stack (resolve x)) for
            every yin.vm.parity-test/corpus program"
    (doseq [[name ast _expected] parity/corpus]
      (testing name
        (is (= (resolved-addresses-of ast) (stack-addresses-of ast)))))))


(deftest address-law-holds-over-b2s-duplicate-and-shared-occurrence-fixtures
  (doseq [[label ast] address-law-extra-fixtures]
    (testing label
      (is (= (resolved-addresses-of ast) (stack-addresses-of ast))))))


;; =============================================================================
;; 4. Pure data checks on the register descriptor's declared field list
;; =============================================================================
;; Design section 3's table, as data, checked for internal consistency
;; against this phase's own operand-mapping table where that is checkable
;; without R1's code -- this is a small part of the phase, not its
;; center of gravity.

(def register-descriptor-fields
  "The retained format fields from the original contract. Resolved-tuple
   input and raw-image lifting retired with the old lowerers."
  {:namespace :yin.debruijn.register
   :contract-version register-contract-version
   :register-image :body-ranges-plus-positional-instructions
   :scalar-encoding :b1-scalar-bytes
   :lexical-addressing :load-bound
   :free-addressing :load-free
   :validation [:shape :targets :body-scope :register-bounds
                :live-shape :live-bounds :live-tail :live-exact]})


(deftest descriptor-field-list-has-every-declared-field
  (is (= #{:namespace :contract-version :register-image
           :scalar-encoding :lexical-addressing :free-addressing
           :validation}
         (set (keys register-descriptor-fields)))))


(deftest descriptor-contract-version-field-matches-the-frozen-constant
  (is (= register-contract-version rcode/contract-version (:contract-version register-descriptor-fields))))


(deftest descriptor-lexical-and-free-addressing-match-the-operand-mapping
  (testing "'Lexical addressing: :load-bound depth/position remains
            explicit' agrees with the :bound branch this phase's own
            operand-mapping table gives :variable"
    (is (= (:lexical-addressing register-descriptor-fields)
           (get-in node-type->register-mapping [:variable :bound :instruction]))))
  (testing "'Free addressing: :load-free name remains exact' agrees with
            the :free branch"
    (is (= (:free-addressing register-descriptor-fields)
           (get-in node-type->register-mapping [:variable :free :instruction])))))


(deftest descriptor-declared-instructions-are-in-section-four-fours-instruction-set
  (is (contains? register-instruction-mnemonics (:lexical-addressing register-descriptor-fields)))
  (is (contains? register-instruction-mnemonics (:free-addressing register-descriptor-fields))))


(def ^:private simple-image {:bodies [{:locals 0 :registers 1 :start 0 :end 1}] :instructions [[:const 0 42] [:halt 0]]})


(def ^:private fixture-a-all-dead
  (app (v '+) (lit 1) (lit 2)))


(def ^:private fixture-d-nested-non-tail-calls
  (app (v 'f) (app (v 'g) (app (v 'h) (lit 1)))))


(def ^:private fixture-e-tail-call
  (lam '[x] (tail (app (v 'f) (v 'x)))))


(defn- call-pc
  [instructions]
  (first (keep-indexed (fn [pc t] (when (= :call (first t)) pc)) instructions)))


(deftest validator-rejects-out-of-range-register-reference
  (let [bad (update-in simple-image [:instructions 0]
                       (fn [t] (assoc t 1 999)))]
    (is (some? (rcode/register-image-defect bad)))))


(deftest validator-rejects-malformed-terminator
  (let [last-pc (dec (count (:instructions simple-image)))
        bad (update-in simple-image [:instructions last-pc] (fn [_] [:const 0 42]))]
    (is (some? (rcode/register-image-defect bad)))))


(def ^:private four-bodies
  "Body 0 branches around a call that keeps reg 1 live; body 1 reads its
   own reg 0 after a call; body 2 has no terminator and falls into body
   3, which reads reg 0."
  {:bodies [{:locals 0, :registers 3, :start 0, :end 6}
            {:locals 0, :registers 3, :start 7, :end 10}
            {:locals 0, :registers 2, :start 11, :end 12}
            {:locals 0, :registers 1, :start 13, :end 13}],
   :instructions [[:const 0 1] [:const 1 2] [:branch-false 0 5]
                  [:call 2 1 [0] false []] [:jump 6] [:move 2 1] [:halt 1]
                  [:const 0 3] [:const 1 4] [:call 2 0 [1] false []]
                  [:return 0]
                  [:const 0 5] [:call 1 0 [] false []]
                  [:return 0]]})


(deftest body-liveness-reads-only-its-own-body-test
  (testing "each body's boundary live sets"
    (is (= [{3 [1]} {9 [0]} {12 []} {}]
           (mapv #(rcode/body-liveness four-bodies %) (range 4)))))
  (testing "a successor in another body reads as empty: body 3's read of
            reg 0 does not reach body 2's call"
    (is (= [] (get (rcode/body-liveness four-bodies 2) 12))))
  (testing "a backward jump into another body reads as empty, and a
            negative target throws"
    (let [back (assoc-in four-bodies [:instructions 10] [:jump 0])
          neg (assoc-in four-bodies [:instructions 10] [:jump -1])]
      (is (= [] (get (rcode/body-liveness back 1) 9)))
      (is (thrown? #?(:cljd Object :clj Exception :cljs :default)
            (rcode/body-liveness neg 1)))))
  (testing "a successor past the image still throws"
    (is (thrown? #?(:cljd Object :clj Exception :cljs :default)
          (rcode/body-liveness (update four-bodies :instructions pop)
                               2)))))


(deftest validator-rejects-invalid-r2-operand-shapes
  (testing "negative capacity on stream-make"
    (let [bad {:bodies [{:locals 0, :registers 2, :start 0, :end 1}],
               :instructions [[:stream-make 0 -1] [:halt 0]]}]
      (is (= :operand-kind (:rule (rcode/register-image-defect bad))))))

  (testing "non-string prefix on gensym"
    (let [bad {:bodies [{:locals 0, :registers 2, :start 0, :end 1}],
               :instructions [[:gensym 0 :not-a-string] [:halt 0]]}]
      (is (= :operand-kind (:rule (rcode/register-image-defect bad))))))

  (testing "non-keyword op on ffi-call"
    (let [bad {:bodies [{:locals 0, :registers 2, :start 0, :end 1}],
               :instructions [[:ffi-call 0 "not-kw" [] []] [:halt 0]]}]
      (is (= :operand-kind (:rule (rcode/register-image-defect bad))))))

  (testing "non-keyword parked-id on resume"
    (let [bad {:bodies [{:locals 0, :registers 2, :start 0, :end 1}],
               :instructions [[:resume "not-kw" 0] [:halt 0]]}]
      (is (= :operand-kind (:rule (rcode/register-image-defect bad))))))

  (testing "non-vector arg-regs on ffi-call"
    (let [bad {:bodies [{:locals 0, :registers 2, :start 0, :end 1}],
               :instructions [[:ffi-call 0 :op "not-vec" []] [:halt 0]]}]
      (is (= :operand-kind (:rule (rcode/register-image-defect bad))))))

  (testing "unsorted live set on stream-put"
    (let [bad {:bodies [{:locals 0, :registers 3, :start 0, :end 1}],
               :instructions [[:stream-put 0 1 2 [2 1]] [:halt 0]]}]
      (is (= :live-shape (:rule (rcode/register-image-defect bad))))))

  (testing "out of bounds live register on park"
    (let [bad {:bodies [{:locals 0, :registers 2, :start 0, :end 1}],
               :instructions [[:park 0 [99]] [:halt 0]]}]
      (is (= :live-bounds (:rule (rcode/register-image-defect bad)))))))


(deftest resume-terminates-control-flow-test
  (let [image {:bodies [{:locals 0, :registers 3, :start 0, :end 4}],
               :instructions [[:const 0 10]
                              [:const 1 20]
                              [:park 2 []]
                              [:resume :p1 0]
                              [:halt 1]]}
        liveness (rcode/body-liveness image 0)]
    ;; At pc 2 (:park), only reg 0 is live (used by :resume), NOT reg 1
    ;; (used by :halt after :resume), proving liveness stops at :resume.
    (is (= [0] (get liveness 2)))))


(deftest validator-rejects-out-of-range-jump-target
  (let [if-node-target (if-node (lit true) (lit 1) (lit 2))
        image (derive/register-image (:vector (sr-linearize/project if-node-target)))
        bad-pc (first (keep-indexed (fn [pc t] (when (= :jump (nth t 0)) pc)) (:instructions image)))
        bad (update-in image [:instructions bad-pc] (fn [t] (assoc t 1 999)))]
    (is (some? (rcode/register-image-defect bad)))))


(deftest validator-rejects-cross-body-jump-target
  (let [ast (app (lam '[x] (tail (v 'x))) (if-node (lit true) (lit 1) (lit 2)))
        image (derive/register-image (:vector (sr-linearize/project ast)))
        instructions (:instructions image)
        other-body-start (:start (nth (:bodies image) 1))
        bad-pc (first (keep-indexed (fn [pc t] (when (#{:jump :branch-false} (nth t 0)) pc))
                                    instructions))
        target-i (case (nth (nth instructions bad-pc) 0) :jump 1 :branch-false 2)
        bad (update-in image [:instructions bad-pc] (fn [t] (assoc t target-i other-body-start)))]
    (is (nil? (rcode/register-image-defect image))
        "the unmodified image is a legitimate within-body jump and must still pass")
    (is (= :jump-scope (:rule (rcode/register-image-defect bad))))))


(deftest validator-rejects-unsorted-or-duplicate-live-indices
  (let [image (derive/register-image (:vector (sr-linearize/project fixture-a-all-dead)))
        pc (call-pc (:instructions image))
        unsorted (update-in image [:instructions pc] (fn [t] (assoc t 5 [1 0])))
        duplicate (update-in image [:instructions pc] (fn [t] (assoc t 5 [1 1])))]
    (is (= :live-shape (:rule (rcode/register-image-defect unsorted))))
    (is (= :live-shape (:rule (rcode/register-image-defect duplicate))))))


(deftest validator-rejects-out-of-range-live-index
  (let [image (derive/register-image (:vector (sr-linearize/project fixture-a-all-dead)))
        pc (call-pc (:instructions image))
        bad (update-in image [:instructions pc] (fn [t] (assoc t 5 [999])))]
    (is (= :live-bounds (:rule (rcode/register-image-defect bad))))))


(deftest validator-rejects-nonempty-live-on-a-tail-call
  (let [image (derive/register-image (:vector (sr-linearize/project fixture-e-tail-call)))
        instructions (:instructions image)
        pc (first (keep-indexed (fn [pc t] (when (and (= :call (nth t 0)) (true? (nth t 4))) pc))
                                instructions))
        bad (update-in image [:instructions pc] (fn [t] (assoc t 5 [0])))]
    (is (= :live-tail (:rule (rcode/register-image-defect bad))))))


(deftest validator-rejects-a-well-formed-but-wrong-live-set
  (let [image (derive/register-image (:vector (sr-linearize/project fixture-d-nested-non-tail-calls)))
        instructions (:instructions image)
        ;; the outer call (writes reg0) correctly has live `[]`; `[1]` is
        ;; well formed (ascending, in bounds) but not what `body-liveness`
        ;; computes for this pc.
        pc (first (keep-indexed (fn [pc t] (when (and (= :call (nth t 0)) (= [] (nth t 5))) pc))
                                instructions))
        bad (update-in image [:instructions pc] (fn [t] (assoc t 5 [1])))
        defect (rcode/register-image-defect bad)]
    (is (= :live-exact (:rule defect)))
    (is (= [] (:expected defect)))
    (is (= [1] (:actual defect)))))
