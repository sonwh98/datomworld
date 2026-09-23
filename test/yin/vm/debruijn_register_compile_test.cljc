(ns yin.vm.debruijn-register-compile-test
  "R1 (docs/design/yin.vm.debruijn.register.md S6, 'R1: register dimension
   and lowerer'): completion tests for `yin.vm.debruijn-register-code` and
   `yin.vm.debruijn-register-compile`.

   Every program below is a plain map AST run through `yin.vm/ast->datoms-
   with-root`, then `yin.vm.debruijn-register-compile/adapt`. The AST
   fixture builders (`v`/`lam`/`app`/`tail`) and the duplicate-parameter,
   free-variable, and shared-occurrence programs are this file's own
   copies, in this lineage's own convention (`yin.vm.debruijn-linearize-
   test`, `yin.vm.debruijn-register-contract-test`): reconstructed here
   rather than required from another test namespace's private fixtures,
   but built to the exact same shape B2's own fixtures use so the address-
   law and lift-law corpora below line up with R0's and B2's own."
  (:require [clojure.test :refer [deftest is testing]]
            [yin.vm :as vm]
            [yin.vm.debruijn-linearize :as dl]
            [yin.vm.debruijn-register-code :as rcode]
            [yin.vm.debruijn-register-compile :as rc]
            [yin.vm.debruijn-register-contract-test :as r0]
            [yin.vm.linearize :as linearize]
            [yin.vm.parity-test :as parity]))


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
  "`((fn [x] (+ x 1)) 10)`, the same worked example B2's own test uses."
  (app (lam '[x] (tail (app (v '+) (v 'x) (lit 1)))) (lit 10)))


(def ^:private duplicate-param
  (lam '[x x] (tail (v 'x))))


(def ^:private free-variable
  (lam '[x] (tail (app (v '+) (v 'x) (v 'y)))))


(def ^:private nested-closure
  "Two levels of nesting, an inner body reaching two frames outward, and a
   duplicate binder at the outer level -- B2's own lift-law fixture of the
   same name, reconstructed here."
  (app (lam '[a a]
            (tail (lam '[b]
                       (tail (app (v '+) (v 'a) (v 'b))))))
       (lit 1)))


(def ^:private if-program
  (if-node (app (v '<) (lit 1) (lit 2)) (lit 100) (lit 200)))


(def ^:private fixture-b-live-across-branch
  (app (v '+) (v 'a) (if-node (v 'test) (app (v 'f) (lit 1)) (lit 0))))


(def ^:private shared-variable-body
  (assoc (v 'x) :eid -41))


(def ^:private shared-variable-under-two-contexts
  (app (v 'list) (lam '[x] shared-variable-body) (lam '[y] shared-variable-body)))


(def ^:private shared-lambda-body
  (assoc (lam '[z] (tail (app (v '+) (v 'z) (v 'q)))) :eid -70))


(def ^:private shared-lambda-under-two-contexts
  (app (v 'list) (lam '[p q] shared-lambda-body) (lam '[r] shared-lambda-body)))


(defn- ast-datoms
  [ast]
  (second (vm/ast->datoms-with-root ast)))


(defn- adapted
  [ast]
  (rc/adapt (ast-datoms ast)))


(defn- named-vector
  [ast]
  (:vector (dl/named-canonical-vector (linearize/lower (ast-datoms ast)))))


;; =============================================================================
;; 1. The contract-version chicken-and-egg with R0
;; =============================================================================
;; B1's own precedent (`yin.vm.debruijn-code/lowering-contract-version`)
;; is that the version lives in src as the canonical source and B0's test
;; (`debruijn_code_test.cljc`) imports and compares it. R0
;; (`debruijn_register_contract_test.cljc`) predates this file and could
;; not import a not-yet-built src namespace, so it froze its own copy of
;; the integer instead; that file's own box forbids editing it after the
;; fact. Before design section 4.5, this test applied B1's precedent
;; one-sidedly, asserting the two integers equal.
;;
;; Section 4.5 bumps `rcode/contract-version` to 2 (the `:call` `live`
;; operand) but its own text assigns re-pinning R0's frozen constant to a
;; LATER phase, not this one -- so the two are now expected to differ by
;; exactly the section 4.5 bump, and this test is updated to record that
;; fact rather than to assert an equality section 4.5 itself breaks.

(deftest contract-version-matches-r0-at-version-3
  (is (= 3 r0/register-contract-version)
      "R0's frozen copy updated to version 3 in Phase R2")
  (is (= 3 rcode/contract-version)
      "src's canonical version at version 3 (Phase R2)")
  (is (= r0/register-contract-version rcode/contract-version)))


;; =============================================================================
;; 2. Exact image lowerings for every Phase R2 node
;; =============================================================================

(deftest exact-image-lowering-for-r2-nodes-test
  (testing ":vm/store-get"
    (let [{:keys [image]} (adapted {:type :vm/store-get, :key :my-k})]
      (is (= [[:store-get 0 :my-k] [:halt 0]] (:instructions image)))))

  (testing ":vm/store-put"
    (let [{:keys [image]} (adapted {:type :vm/store-put, :key :my-k, :val 42})]
      (is (= [[:store-put 0 :my-k 42] [:halt 0]] (:instructions image)))))

  (testing ":vm/gensym"
    (let [{:keys [image]} (adapted {:type :vm/gensym, :prefix "my-prefix"})]
      (is (= [[:gensym 0 "my-prefix"] [:halt 0]] (:instructions image)))))

  (testing ":stream/make"
    (let [{:keys [image]} (adapted {:type :stream/make, :buffer 32})]
      (is (= [[:stream-make 0 32] [:halt 0]] (:instructions image)))))

  (testing ":stream/put"
    (let [{:keys [image]} (adapted {:type :stream/put,
                                    :target (lit :s),
                                    :val (lit 99)})]
      (is (= [[:const 1 :s]
              [:const 2 99]
              [:stream-put 0 1 2 []]
              [:halt 0]]
             (:instructions image)))))

  (testing ":stream/cursor"
    (let [{:keys [image]} (adapted {:type :stream/cursor,
                                    :source (lit :s)})]
      (is (= [[:const 1 :s]
              [:stream-cursor 0 1]
              [:halt 0]]
             (:instructions image)))))

  (testing ":stream/next"
    (let [{:keys [image]} (adapted {:type :stream/next,
                                    :source (lit :c)})]
      (is (= [[:const 1 :c]
              [:stream-next 0 1 []]
              [:halt 0]]
             (:instructions image)))))

  (testing ":stream/close"
    (let [{:keys [image]} (adapted {:type :stream/close,
                                    :source (lit :s)})]
      (is (= [[:const 1 :s]
              [:stream-close 0 1]
              [:halt 0]]
             (:instructions image)))))

  (testing ":dao.stream.apply/call"
    (let [{:keys [image]} (adapted {:type :dao.stream.apply/call,
                                    :op :math/add,
                                    :operands [(lit 10) (lit 20)]})]
      (is (= [[:const 1 10]
              [:const 2 20]
              [:ffi-call 0 :math/add [1 2] []]
              [:halt 0]]
             (:instructions image)))))

  (testing ":vm/current-continuation"
    (let [{:keys [image]} (adapted {:type :vm/current-continuation})]
      (is (= [[:current-continuation 0 []] [:halt 0]] (:instructions image)))))

  (testing ":vm/park"
    (let [{:keys [image]} (adapted {:type :vm/park})]
      (is (= [[:park 0 []] [:halt 0]] (:instructions image)))))

  (testing ":vm/resume"
    (let [{:keys [image]} (adapted {:type :vm/resume,
                                    :parked-id :p1,
                                    :val (lit "ok")})]
      (is (= [[:const 1 "ok"]
              [:resume :p1 1]
              [:halt 0]]
             (:instructions image))))))


(deftest child-allocation-and-temp-release-test
  (let [ast (app (v '+)
                 {:type :stream/put, :target (lit :s), :val (lit 1)}
                 (lit 2))
        {:keys [image]} (adapted ast)]
    (is (nil? (rcode/register-image-defect image)))))


;; =============================================================================
;; 3. Determinism: byte-identical repeated lowerings
;; =============================================================================

(def ^:private register-corpus
  parity/corpus)


(deftest lowering-is-deterministic-across-repeated-runs
  (doseq [[name ast _] register-corpus]
    (testing name
      (let [a (adapted ast), b (adapted ast)]
        (is (= (:image a) (:image b)))
        (is (= (rcode/register-hash (:image a))
               (rcode/register-hash (:image b))))))))


;; =============================================================================
;; 4. The validator accepts every image lower-register emits
;; =============================================================================

(deftest validator-accepts-every-corpus-image
  (doseq [[name ast _] register-corpus]
    (testing name
      (is (nil? (rcode/register-image-defect (:image (adapted ast))))))))


(deftest validator-accepts-b2-fixtures
  (doseq [[label ast] {:duplicate-param duplicate-param,
                       :free-variable free-variable,
                       :nested-closure nested-closure,
                       :if-program if-program,
                       :shared-variable shared-variable-under-two-contexts,
                       :shared-lambda shared-lambda-under-two-contexts}]
    (testing label
      (is (nil? (rcode/register-image-defect (:image (adapted ast))))))))


;; =============================================================================
;; 4b. `:move` is never emitted (design 4.4's docstring on `opcode-table`)
;; =============================================================================

(deftest move-mnemonic-is-never-emitted
  (doseq [[name ast _] register-corpus]
    (testing name
      (doseq [t (:instructions (:image (adapted ast)))]
        (is (not= :move (nth t 0))))))
  (doseq [[label ast] {:duplicate-param duplicate-param,
                       :free-variable free-variable,
                       :nested-closure nested-closure,
                       :if-program if-program,
                       :shared-variable shared-variable-under-two-contexts,
                       :shared-lambda shared-lambda-under-two-contexts}]
    (testing label
      (doseq [t (:instructions (:image (adapted ast)))]
        (is (not= :move (nth t 0)))))))


;; =============================================================================
;; 5. The validator rejects hand-built out-of-range images
;; =============================================================================

(def ^:private simple-image
  (:image (adapted (lit 42))))


(deftest validator-rejects-out-of-range-register-reference
  (let [bad (update-in simple-image [:instructions 0]
                       (fn [t] (assoc t 1 999)))]
    (is (some? (rcode/register-image-defect bad)))))


(deftest validator-rejects-out-of-range-jump-target
  (let [if-node-target (if-node (lit true) (lit 1) (lit 2))
        image (:image (adapted if-node-target))
        bad-pc (first (keep-indexed (fn [pc t] (when (= :jump (nth t 0)) pc)) (:instructions image)))
        bad (update-in image [:instructions bad-pc] (fn [t] (assoc t 1 999)))]
    (is (some? (rcode/register-image-defect bad)))))


(deftest validator-rejects-malformed-terminator
  (let [last-pc (dec (count (:instructions simple-image)))
        bad (update-in simple-image [:instructions last-pc] (fn [_] [:const 0 42]))]
    (is (some? (rcode/register-image-defect bad)))))


;; A hand-built `:jump` (or `:branch-false`) target that is a valid,
;; in-range instruction-vector index but names a pc inside a DIFFERENT
;; body than the instruction itself: `target-bounds-rule` alone cannot
;; catch this (the target is in range), so it must be `jump-scope-rule`.
;; `(app (lam [x] (tail x)) (if-node true 1 2))` gives two bodies: body 0
;; (the main program, pcs 0-7, containing an in-body `:jump` at pc 4) and
;; body 1 (the lambda, pcs 8-9). Body 1's own `:start` (8) is a valid
;; instruction-vector index but not inside body 0.

(deftest validator-rejects-cross-body-jump-target
  (let [ast (app (lam '[x] (tail (v 'x))) (if-node (lit true) (lit 1) (lit 2)))
        image (:image (adapted ast))
        instructions (:instructions image)
        other-body-start (:start (nth (:bodies image) 1))
        bad-pc (first (keep-indexed (fn [pc t] (when (#{:jump :branch-false} (nth t 0)) pc))
                                    instructions))
        target-i (case (nth (nth instructions bad-pc) 0) :jump 1 :branch-false 2)
        bad (update-in image [:instructions bad-pc] (fn [t] (assoc t target-i other-body-start)))]
    (is (nil? (rcode/register-image-defect image))
        "the unmodified image is a legitimate within-body jump and must still pass")
    (is (= :jump-scope (:rule (rcode/register-image-defect bad))))))


;; =============================================================================
;; 6. The three-way address law (design section 2.2, extended)
;; =============================================================================
;; addresses(resolve x) = addresses(lower-stack (resolve x)) is R0's own
;; two-way law, already pinned over this same corpus. This extends it
;; with a third sequence read directly off the register image's own
;; `:load-bound`/`:load-free` operands, in pc order -- the register
;; image's own instructions are already laid out in the lowerer's walk
;; order (main body first, then each out-of-line body in discovery
;; order, occurrences expanded positionally), the same order R0's
;; `resolved-var-addresses`/`stack-image-var-addresses` helpers use, so
;; reading operands off in pc order needs no separate walk of its own.

(defn- register-image-var-addresses
  [image]
  (into []
        (keep (fn [t]
                (case (nth t 0)
                  :load-bound [:bound (nth t 2) (nth t 3)]
                  :load-free [:free (nth t 2)]
                  nil)))
        (:instructions image)))


(defn- register-addresses-of
  [ast]
  (register-image-var-addresses (:image (adapted ast))))


(deftest address-law-holds-three-way-over-the-b0-parity-corpus
  (doseq [[name ast _expected] register-corpus]
    (testing name
      (let [resolved (r0/resolved-addresses-of ast)]
        (is (= resolved (r0/stack-addresses-of ast)))
        (is (= resolved (register-addresses-of ast)))))))


(deftest address-law-holds-three-way-over-b2s-extra-fixtures
  (doseq [[label ast] r0/address-law-extra-fixtures]
    (testing label
      (let [resolved (r0/resolved-addresses-of ast)]
        (is (= resolved (r0/stack-addresses-of ast)))
        (is (= resolved (register-addresses-of ast)))))))


;; =============================================================================
;; 7. The lift law
;; =============================================================================

(def ^:private lift-law-programs
  {:simple worked-example,
   :duplicate-param duplicate-param,
   :free-variable free-variable,
   :nested-closure nested-closure})


(deftest lift-with-the-lowerers-own-side-table-is-exact
  (doseq [[label ast] lift-law-programs]
    (testing label
      (let [nv (named-vector ast)
            {:keys [image side-table]} (adapted ast)
            lifted (rc/lift image side-table)]
        (is (= nv lifted)
            "lift(lower-register(resolve x), side-table) = canonical-vector(lower x), exactly")))))


(deftest lift-with-synthesized-names-is-alpha-equivalent
  (doseq [[label ast] lift-law-programs]
    (testing label
      (let [nv (named-vector ast)
            {:keys [image]} (adapted ast)
            lifted (rc/lift image nil)]
        (is (not= nv lifted) "synthesized names are not the original spelling")
        (is (vector? lifted)
            "lift did not throw: its own round-trip check accepted the synthesized names")))))


;; =============================================================================
;; 8. A shared source entity lowers independently and correctly per
;;    occurrence, and its two register images pass the validator
;; =============================================================================

(deftest a-shared-variable-lowers-independently-per-occurrence
  (let [{:keys [image side-table]} (adapted shared-variable-under-two-contexts)]
    (is (nil? (rcode/register-image-defect image)))
    (is (= (named-vector shared-variable-under-two-contexts) (rc/lift image side-table)))))


(deftest a-shared-lambda-lowers-independently-per-occurrence
  (let [{:keys [image side-table]} (adapted shared-lambda-under-two-contexts)]
    (is (nil? (rcode/register-image-defect image)))
    (is (= (named-vector shared-lambda-under-two-contexts) (rc/lift image side-table)))))


;; =============================================================================
;; 9. R agreement: repeated lowerings and a golden R for a fixed program
;; =============================================================================

(def golden-descriptor-hash
  "2621ded6caa3bbcb6ccd948b74876dd4eeb88c29c157dda75a97b4e8e77c2db0")


(deftest golden-descriptor-hash-test
  (is (= golden-descriptor-hash rcode/descriptor-hash)))


(deftest register-hash-is-stable-for-a-fixed-program
  (let [{:keys [image]} (adapted worked-example)]
    (is (= (rcode/register-hash image) (rcode/register-hash image)))
    (is (string? (rcode/register-hash image)))
    (is (= 64 (count (rcode/register-hash image))) "sha256 hex digest length")))


(deftest golden-pure-r1-image-and-r-test
  (let [{:keys [image]} (adapted worked-example)
        expected-image
        {:bodies [{:locals 0, :registers 3, :start 0, :end 3}
                  {:locals 1, :registers 5, :start 4, :end 8}],
         :instructions [[:closure 1 1 4]
                        [:const 2 10]
                        [:call 0 1 [2] false []]
                        [:halt 0]
                        [:load-free 2 '+]
                        [:load-bound 3 0 0]
                        [:const 4 1]
                        [:call 1 2 [3 4] true []]
                        [:return 1]]}
        expected-r
        "c85f9adbb70bc0297abcb2b4b0362d740b57ed3010a29cb5a98d509900cca3b7"]
    (is (= expected-image image))
    (is (= expected-r (rcode/register-hash image)))))


(deftest golden-effect-bearing-images-and-r-test
  (testing "store-put"
    (let [{:keys [image]} (adapted {:type :vm/store-put, :key :k, :val 42})]
      (is (= {:bodies [{:locals 0, :registers 1, :start 0, :end 1}],
              :instructions [[:store-put 0 :k 42] [:halt 0]]}
             image))
      (is (= "438804bf11aaeaf549fbeb056325f8c7c47691b1e67dc98542f99890cf7bddb2"
             (rcode/register-hash image)))))

  (testing "gensym"
    (let [{:keys [image]} (adapted {:type :vm/gensym, :prefix "g"})]
      (is (= {:bodies [{:locals 0, :registers 1, :start 0, :end 1}],
              :instructions [[:gensym 0 "g"] [:halt 0]]}
             image))
      (is (= "81226f50963186da38bf643046ccce90bab48c96ca9502f0bf40d4a7286a81c1"
             (rcode/register-hash image)))))

  (testing "stream-make"
    (let [{:keys [image]} (adapted {:type :stream/make, :buffer 64})]
      (is (= {:bodies [{:locals 0, :registers 1, :start 0, :end 1}],
              :instructions [[:stream-make 0 64] [:halt 0]]}
             image))
      (is (= "d7d5d98c741ab674cd4d3a4607477a1198bf594fd2cc9205570ab2cab78b0857"
             (rcode/register-hash image)))))

  (testing "stream-put"
    (let [{:keys [image]} (adapted {:type :stream/put,
                                    :target (lit :s),
                                    :val (lit 99)})]
      (is (= {:bodies [{:locals 0, :registers 3, :start 0, :end 3}],
              :instructions [[:const 1 :s]
                             [:const 2 99]
                             [:stream-put 0 1 2 []]
                             [:halt 0]]}
             image))
      (is (= "4e7d3f3ef6ed453d2ab40ad75f6adf5c074b0361969c9e2b7e4b302cfd3b1bac"
             (rcode/register-hash image)))))

  (testing "stream-cursor"
    (let [{:keys [image]} (adapted {:type :stream/cursor, :source (lit :s)})]
      (is (= {:bodies [{:locals 0, :registers 2, :start 0, :end 2}],
              :instructions [[:const 1 :s]
                             [:stream-cursor 0 1]
                             [:halt 0]]}
             image))
      (is (= "634fda35541bd3e5407f3bdcee60e979de5d662ad9219f4243da9b4cb8358a60"
             (rcode/register-hash image)))))

  (testing "stream-next"
    (let [{:keys [image]} (adapted {:type :stream/next, :source (lit :c)})]
      (is (= {:bodies [{:locals 0, :registers 2, :start 0, :end 2}],
              :instructions [[:const 1 :c]
                             [:stream-next 0 1 []]
                             [:halt 0]]}
             image))
      (is (= "411124300dcf612467a6633e54a440c3873c385dea85ef21f4e84f1b9300d417"
             (rcode/register-hash image)))))

  (testing "stream-close"
    (let [{:keys [image]} (adapted {:type :stream/close, :source (lit :s)})]
      (is (= {:bodies [{:locals 0, :registers 2, :start 0, :end 2}],
              :instructions [[:const 1 :s]
                             [:stream-close 0 1]
                             [:halt 0]]}
             image))
      (is (= "91876ea3afe2f8edee52b97dc27ecd2ed9957f7a056973491c60d3a5d673b01d"
             (rcode/register-hash image))))))


(deftest golden-nested-control-flow-with-live-boundary-test
  (let [{:keys [image]} (adapted fixture-b-live-across-branch)
        expected-image
        {:bodies [{:locals 0, :registers 6, :start 0, :end 10}],
         :instructions [[:load-free 1 '+]
                        [:load-free 2 'a]
                        [:load-free 4 'test]
                        [:branch-false 4 8]
                        [:load-free 4 'f]
                        [:const 5 1]
                        [:call 3 4 [5] false [1 2]]
                        [:jump 9]
                        [:const 3 0]
                        [:call 0 1 [2 3] false []]
                        [:halt 0]]}
        expected-r
        "086bee81730aae053fcf9028835aef271e9b035358b2acd71fd6b4e3568f2ff1"]
    (is (= expected-image image))
    (is (= expected-r (rcode/register-hash image)))))


(deftest golden-ffi-and-resume-images-test
  (testing "ffi"
    (let [{:keys [image]} (adapted {:type :dao.stream.apply/call,
                                    :op :math/add,
                                    :operands [(lit 10) (lit 20)]})]
      (is (= {:bodies [{:locals 0, :registers 3, :start 0, :end 3}],
              :instructions [[:const 1 10]
                             [:const 2 20]
                             [:ffi-call 0 :math/add [1 2] []]
                             [:halt 0]]}
             image))
      (is (= "f80f2aaf1d7096390bf54aa13f45d9b62dfdc7d5777cdf0d7b2472eafbb9e6ee"
             (rcode/register-hash image)))))

  (testing "resume"
    (let [{:keys [image]} (adapted {:type :vm/resume,
                                    :parked-id :p1,
                                    :val (lit "ok")})]
      (is (= {:bodies [{:locals 0, :registers 2, :start 0, :end 2}],
              :instructions [[:const 1 "ok"]
                             [:resume :p1 1]
                             [:halt 0]]}
             image))
      (is (= "bf3e63231bf72fc9556a83a379e11bc1f4fb4e5be454b5d00ebba5dbf0b0f3cc"
             (rcode/register-hash image))))))


;; =============================================================================
;; 10. Hand-derived live-set fixtures (design section 4.5's own work list)
;; =============================================================================
;; Each fixture's expected `live` at the named call pc is worked out here
;; by hand, off the lowerer's own known allocation discipline (lowest-
;; free-index-first, freed the instant a value is consumed by its
;; parent) and design section 4.5's own dataflow equations, BEFORE
;; running `body-liveness` -- the assertions below check that hand
;; derivation against the actual computed value, not merely that the
;; function agrees with itself.

;; --- A: a non-tail call whose temporaries are all dead afterward -----------
;; `(+ 1 2)`, the whole program. Lowering (main body, locals 0): reg0 is
;; reserved for the program's own return value before anything else is
;; lowered (`lower-register`'s own `main-temp`), so the call's operands
;; start at reg1: pc0 `[:load-free 1 '+]`, pc1 `[:const 2 1]`, pc2
;; `[:const 3 2]`, pc3 `[:call 0 1 [2 3] false live]`, pc4 `[:halt 0]`.
;; After the call only `:halt` runs, and it reads only reg0 (the call's
;; own destination, excluded by definition) -- reg1/reg2/reg3 are never
;; read again. Expected live at pc3: `[]`.

(def ^:private fixture-a-all-dead
  (app (v '+) (lit 1) (lit 2)))


(deftest fixture-a-non-tail-call-with-all-temps-dead-afterward
  (let [{:keys [image]} (adapted fixture-a-all-dead)
        call-pc (first (keep-indexed (fn [pc t] (when (= :call (nth t 0)) pc))
                                     (:instructions image)))]
    (is (nil? (rcode/register-image-defect image)))
    (is (= [] (nth (nth (:instructions image) call-pc) 5))
        "no register survives the program's only call")))


;; --- B: a call inside one `if` arm, a temp live across it from before the
;;        branch -----------------------------------------------------------
;; `(+ a (if test (f 1) 0))`. Lowering order (main body, locals 0):
;; reg0 = program return value (reserved first). The outer `+` application
;; lowers its operator into reg1 (pc0 `[:load-free 1 '+]`) and its first
;; operand `a` into reg2 BEFORE its second operand, the `if`, is lowered
;; at all (pc1 `[:load-free 2 'a]`) -- so reg2 is defined strictly before
;; the branch. The `if`'s own target is reg3: its test goes to reg4 (pc2
;; `[:load-free 4 'test]`), then `[:branch-false 4 else]` (pc3). The
;; consequent `(f 1)` reuses freed reg4 for `f` (pc4 `[:load-free 4 'f]`),
;; reg5 for the literal (pc5 `[:const 5 1]`), then the inner call (pc6
;; `[:call 3 4 [5] false live]`) writes its result to reg3, the `if`'s
;; own target. After the `if` (either arm), the outer `+` call reads
;; reg1 (`+`), reg2 (`a`), and reg3 (the `if`'s result): pc9 `[:call 0 1
;; [2 3] false live]`. So at the inner call (pc6), reg1 and reg2 are both
;; still needed afterward (by pc9) and neither is written again in
;; between: expected live `[1 2]`.

(deftest fixture-b-call-inside-if-arm-with-a-temp-live-across-it-from-before-the-branch
  (let [{:keys [image]} (adapted fixture-b-live-across-branch)
        instructions (:instructions image)
        inner-call-pc (first (keep-indexed
                               (fn [pc t]
                                 (when (and (= :call (nth t 0)) (not (nth t 4))
                                            (= 3 (nth t 1)))
                                   pc))
                               instructions))]
    (is (nil? (rcode/register-image-defect image)))
    (is (some? inner-call-pc) "the consequent's own inner call, target reg3, must exist")
    (is (= [1 2] (nth (nth instructions inner-call-pc) 5))
        "reg1 ('+') and reg2 ('a'), both defined before the branch, are both needed by the outer call after the if")))


;; --- C: a temp live in one arm but not the other ----------------------------
;; `(if test (g (f 1) x) (k 2))`. The consequent, `(g (f 1) x)`, mirrors
;; fixture B's inner shape: `g` loads into reg1 (pc2), the inner `(f 1)`
;; call writes reg2 (pc5 `[:call 2 3 [4] false live]`), then `x` loads
;; into reg3 (pc6), then the consequent's OWN outer call (pc7 `[:call 0 1
;; [2 3] false live]`) writes the if's target reg0 directly, reading reg1
;; again. So at the inner call (pc5), reg1 ('g') is still needed
;; afterward: live `[1]`. The alternate, `(k 2)`, is flat -- its one call
;; (pc11 `[:call 0 1 [2] false live]`) writes reg0, the if's own target,
;; directly; nothing in the alternate arm reads anything again after it,
;; and the branch's own successor after either arm is just `:halt`
;; reading reg0 (the call's own destination, excluded by definition): live
;; `[]`. Same register (1) reused by the allocator in both arms (matching
;; free-list state on entry to each), live in the consequent's inner call,
;; empty at the alternate's own call.

(def ^:private fixture-c-live-in-one-arm-not-the-other
  (if-node (v 'test) (app (v 'g) (app (v 'f) (lit 1)) (v 'x)) (app (v 'k) (lit 2))))


(deftest fixture-c-temp-live-in-one-arm-but-not-the-other
  (let [{:keys [image]} (adapted fixture-c-live-in-one-arm-not-the-other)
        instructions (:instructions image)
        consequent-inner-call-pc (first (keep-indexed
                                          (fn [pc t]
                                            (when (and (= :call (nth t 0)) (= 2 (nth t 1)))
                                              pc))
                                          instructions))
        alternate-call-pc (first (keep-indexed
                                   (fn [pc t]
                                     (when (and (= :call (nth t 0)) (= 0 (nth t 1))
                                                (> pc consequent-inner-call-pc))
                                       pc))
                                   instructions))]
    (is (nil? (rcode/register-image-defect image)))
    (is (= [1] (nth (nth instructions consequent-inner-call-pc) 5))
        "the consequent arm's inner call still needs reg1 ('g') for its own outer call afterward")
    (is (= [] (nth (nth instructions alternate-call-pc) 5))
        "the alternate arm's call writes the if's own target directly; nothing survives it")))


;; --- D: nested non-tail calls -----------------------------------------------
;; `(f (g (h 1)))`. reg0 is the program's return value; reg1 holds `f`
;; (pc0), reg3 holds `g` (pc1), reg5 holds `h` (pc2), reg6 the literal
;; (pc3). The innermost call (pc4 `[:call 4 5 [6] false live]`) writes
;; reg4; the middle call (pc5 `[:call 2 3 [4] false live]`) reads reg4
;; and writes reg2; the outer call (pc6 `[:call 0 1 [2] false live]`)
;; reads reg2 and writes reg0, then `:halt` reads only reg0. Working
;; backward: after the outer call, nothing survives (live `[]`); the
;; middle call still needs reg1 ('f') for the outer call afterward (live
;; `[1]`); the innermost call still needs both reg1 ('f', for the outer
;; call) and reg3 ('g', for the middle call) afterward (live `[1 3]`).

(def ^:private fixture-d-nested-non-tail-calls
  (app (v 'f) (app (v 'g) (app (v 'h) (lit 1)))))


(deftest fixture-d-nested-non-tail-calls-test
  (let [{:keys [image]} (adapted fixture-d-nested-non-tail-calls)
        instructions (:instructions image)
        live-of-call-writing (fn [rd]
                               (nth (first (filter (fn [t] (and (= :call (nth t 0)) (= rd (nth t 1))))
                                                   instructions))
                                    5))]
    (is (nil? (rcode/register-image-defect image)))
    (is (= [1 3] (live-of-call-writing 4)) "innermost call: reg1 ('f') and reg3 ('g') both needed later")
    (is (= [1] (live-of-call-writing 2)) "middle call: reg1 ('f') needed by the outer call")
    (is (= [] (live-of-call-writing 0)) "outer call: nothing survives it but its own result")))


;; --- E: a tail call, confirming its live is always [] -----------------------
;; `(fn [x] (tail (f x)))`. The lambda body (body 1, locals 1, x = reg0):
;; temp 0 (reg1) is reserved for the body's own return register, so `f`
;; loads into reg2, `x` (a bound reference) into reg3, then the tail
;; call `[:call <ret-reg> 2 [3] true live]` writes the body's own return
;; register and has no
;; successor at all (the successor rule gives a tail call `{}`), so its
;; live-out, and so its live, is `[]` regardless of what a caller might
;; still want -- exactly the design's own point: a tail call saves
;; nothing because it never returns to this body.

(def ^:private fixture-e-tail-call
  (lam '[x] (tail (app (v 'f) (v 'x)))))


(deftest fixture-e-tail-call-live-is-always-empty
  (let [{:keys [image]} (adapted fixture-e-tail-call)
        instructions (:instructions image)
        tail-call-pc (first (keep-indexed (fn [pc t] (when (and (= :call (nth t 0)) (true? (nth t 4))) pc))
                                          instructions))]
    (is (nil? (rcode/register-image-defect image)))
    (is (some? tail-call-pc))
    (is (= [] (nth (nth instructions tail-call-pc) 5)) "a tail call's live is always []")))


;; =============================================================================
;; 11. The four live-set validator rules (design section 4.5)
;; =============================================================================

(defn- call-pc
  [instructions]
  (first (keep-indexed (fn [pc t] (when (= :call (nth t 0)) pc)) instructions)))


(deftest validator-rejects-unsorted-or-duplicate-live-indices
  (let [{:keys [image]} (adapted fixture-a-all-dead)
        pc (call-pc (:instructions image))
        unsorted (update-in image [:instructions pc] (fn [t] (assoc t 5 [1 0])))
        duplicate (update-in image [:instructions pc] (fn [t] (assoc t 5 [1 1])))]
    (is (= :live-shape (:rule (rcode/register-image-defect unsorted))))
    (is (= :live-shape (:rule (rcode/register-image-defect duplicate))))))


(deftest validator-rejects-out-of-range-live-index
  (let [{:keys [image]} (adapted fixture-a-all-dead)
        pc (call-pc (:instructions image))
        bad (update-in image [:instructions pc] (fn [t] (assoc t 5 [999])))]
    (is (= :live-bounds (:rule (rcode/register-image-defect bad))))))


(deftest validator-rejects-nonempty-live-on-a-tail-call
  (let [{:keys [image]} (adapted fixture-e-tail-call)
        instructions (:instructions image)
        pc (first (keep-indexed (fn [pc t] (when (and (= :call (nth t 0)) (true? (nth t 4))) pc))
                                instructions))
        bad (update-in image [:instructions pc] (fn [t] (assoc t 5 [0])))]
    (is (= :live-tail (:rule (rcode/register-image-defect bad))))))


(deftest validator-rejects-a-well-formed-but-wrong-live-set
  (let [{:keys [image]} (adapted fixture-d-nested-non-tail-calls)
        instructions (:instructions image)
        ;; the outer call (writes reg0) correctly has live `[]`; `[1]` is
        ;; well formed (ascending, in bounds) but not what `body-liveness`
        ;; computes for this pc.
        pc (first (keep-indexed (fn [pc t] (when (and (= :call (nth t 0)) (= 0 (nth t 1))) pc))
                                instructions))
        bad (update-in image [:instructions pc] (fn [t] (assoc t 5 [1])))
        defect (rcode/register-image-defect bad)]
    (is (= :live-exact (:rule defect)))
    (is (= [] (:expected defect)))
    (is (= [1] (:actual defect)))))


;; =============================================================================
;; 12. The lowerer's own output never trips its own `:live-exact` check
;; =============================================================================
;; `lower-register` fills `live` FROM `body-liveness`, the exact function
;; the validator's `:live-exact` rule uses to check it -- this asserts
;; that identity directly, over every corpus/B2-fixture program, rather
;; than relying only on `validator-accepts-every-corpus-image` to imply
;; it.

(deftest lowered-live-matches-freshly-recomputed-body-liveness-exactly
  (doseq [[name ast _] register-corpus]
    (testing name
      (let [{:keys [image]} (adapted ast)
            {:keys [bodies instructions]} image]
        (doseq [bi (range (count bodies))]
          (let [expected (rcode/body-liveness image bi)]
            (doseq [[pc live] expected]
              (let [inst (nth instructions pc)
                    slot (rcode/live-slot-index (first inst))]
                (is (= live (nth inst slot))
                    (str name " body " bi " pc " pc))))))))))


;; =============================================================================
;; 13. Live-set fixtures for Phase R2 boundary instructions
;; =============================================================================

(deftest r2-boundary-instructions-live-set-test
  (testing "stream-put live set"
    (let [ast (app (v '+) (v 'a)
                   {:type :stream/put, :target (lit :s), :val (lit 1)})
          {:keys [image]} (adapted ast)
          insts (:instructions image)
          pc (first (keep-indexed
                      (fn [i t] (when (= :stream-put (first t)) i))
                      insts))]
      (is (nil? (rcode/register-image-defect image)))
      (is (some? pc))
      (is (= [1 2] (nth (nth insts pc) 4)))))

  (testing "stream-next live set"
    (let [ast (app (v '+) (v 'a)
                   {:type :stream/next, :source (lit :c)})
          {:keys [image]} (adapted ast)
          insts (:instructions image)
          pc (first (keep-indexed
                      (fn [i t] (when (= :stream-next (first t)) i))
                      insts))]
      (is (nil? (rcode/register-image-defect image)))
      (is (some? pc))
      (is (= [1 2] (nth (nth insts pc) 3)))))

  (testing "ffi-call live set"
    (let [ast (app (v '+) (v 'a)
                   {:type :dao.stream.apply/call,
                    :op :math/add,
                    :operands [(lit 1) (lit 2)]})
          {:keys [image]} (adapted ast)
          insts (:instructions image)
          pc (first (keep-indexed
                      (fn [i t] (when (= :ffi-call (first t)) i))
                      insts))]
      (is (nil? (rcode/register-image-defect image)))
      (is (some? pc))
      (is (= [1 2] (nth (nth insts pc) 4)))))

  (testing "current-continuation live set"
    (let [ast (app (v '+) (v 'a)
                   {:type :vm/current-continuation})
          {:keys [image]} (adapted ast)
          insts (:instructions image)
          pc (first (keep-indexed
                      (fn [i t] (when (= :current-continuation (first t)) i))
                      insts))]
      (is (nil? (rcode/register-image-defect image)))
      (is (some? pc))
      (is (= [1 2] (nth (nth insts pc) 2)))))

  (testing "park live set"
    (let [ast (app (v '+) (v 'a)
                   {:type :vm/park})
          {:keys [image]} (adapted ast)
          insts (:instructions image)
          pc (first (keep-indexed
                      (fn [i t] (when (= :park (first t)) i))
                      insts))]
      (is (nil? (rcode/register-image-defect image)))
      (is (some? pc))
      (is (= [1 2] (nth (nth insts pc) 2))))))


;; =============================================================================
;; 14. Resume CFG: control and liveness do not flow past :resume
;; =============================================================================

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


;; =============================================================================
;; 15. Validator tests for R2 operand kinds and shapes
;; =============================================================================

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


;; =============================================================================
;; 16. Lift law extended to Phase R2 nodes
;; =============================================================================

(def ^:private r2-lift-programs
  {:store-get {:type :vm/store-get, :key :k}
   :store-put {:type :vm/store-put, :key :k, :val 42}
   :gensym {:type :vm/gensym, :prefix "g"}
   :stream-make {:type :stream/make, :buffer 8}
   :stream-put {:type :stream/put, :target (lit :s), :val (lit 1)}
   :stream-cursor {:type :stream/cursor, :source (lit :s)}
   :stream-next {:type :stream/next, :source (lit :c)}
   :stream-close {:type :stream/close, :source (lit :s)}
   :ffi {:type :dao.stream.apply/call,
         :op :math/add,
         :operands [(lit 1) (lit 2)]}
   :park {:type :vm/park}
   :current-continuation {:type :vm/current-continuation}
   :resume {:type :vm/resume, :parked-id :p1, :val (lit 42)}})


(deftest r2-lift-law-exact-test
  (doseq [[label ast] r2-lift-programs]
    (testing label
      (let [nv (named-vector ast)
            {:keys [image side-table]} (adapted ast)
            lifted (rc/lift image side-table)]
        (is (= nv lifted)
            (str "lift(lower-register(resolve x), side-table) = "
                 "canonical-vector(lower x) for " label))))))
