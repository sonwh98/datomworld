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

(deftest contract-version-is-one-ahead-of-r0s-frozen-constant-per-section-4-5
  (is (= 1 r0/register-contract-version)
      "R0's frozen copy is untouched by this phase, per section 4.5's own deferral")
  (is (= 2 rcode/contract-version)
      "src's canonical version bumped 1 -> 2 for the live-set operand (section 4.5)")
  (is (= (inc r0/register-contract-version) rcode/contract-version)
      "the two are expected to differ by exactly this phase's version bump"))


;; =============================================================================
;; 2. Deferred node types match R0's frozen diagnostics exactly
;; =============================================================================

(deftest deferred-diagnostics-match-r0s-frozen-table-exactly
  (let [r0-deferred (into {}
                          (keep (fn [[type mapping]]
                                  (when (:deferred-to mapping) [type (:diagnostic mapping)])))
                          r0/node-type->register-mapping)]
    (is (= r0-deferred rc/deferred-diagnostics))))


(deftest lower-register-refuses-every-deferred-node-type-with-r0s-diagnostic
  (doseq [[type diagnostic] rc/deferred-diagnostics]
    (testing type
      (let [ast (case type
                  :dao.stream.apply/call {:type type, :op :identity, :operands []}
                  (:stream/put) {:type type, :target (lit 1), :val (lit 1)}
                  (:stream/cursor :stream/next :stream/close) {:type type, :source (lit 1)}
                  :stream/make {:type type, :buffer 8}
                  :vm/gensym {:type type, :prefix "g"}
                  :vm/store-get {:type type, :key :k}
                  :vm/store-put {:type type, :key :k, :val 1}
                  :vm/park {:type type}
                  :vm/current-continuation {:type type}
                  :vm/resume {:type type, :parked-id :p, :val (lit 1)})
            caught (try (adapted ast) nil (catch #?(:cljd Object :clj Exception :cljs :default) e e))]
        (is (some? caught))
        (is (= diagnostic (:rule (ex-data caught))))))))


;; =============================================================================
;; 3. Determinism: byte-identical repeated lowerings
;; =============================================================================
;; `parity/corpus` includes three R2-scoped programs ("store put then
;; get", "gensym", "stream make"), which `lower-register` correctly
;; refuses (already exercised above); this and the two sections below
;; exercise the register-lowerable subset.

(defn- ast-has-deferred-type?
  [node]
  (cond
    (map? node) (or (contains? rc/deferred-diagnostics (:type node))
                    (some ast-has-deferred-type? (vals node)))
    (sequential? node) (some ast-has-deferred-type? node)
    :else false))


(def ^:private register-corpus
  (remove (fn [[_name ast _expected]] (ast-has-deferred-type? ast)) parity/corpus))


(deftest lowering-is-deterministic-across-repeated-runs
  (doseq [[name ast _] register-corpus]
    (testing name
      (let [a (adapted ast), b (adapted ast)]
        (is (= (:image a) (:image b)))
        (is (= (rcode/register-hash (:image a)) (rcode/register-hash (:image b))))))))


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
;; 4b. `:move`/`:store-get`/`:store-put` are never emitted (design 4.4's
;;     docstring on `opcode-table`)
;; =============================================================================
;; `:store-get`/`:store-put` are R2-only forward declarations and `:move`
;; is never needed by a target-register-passing walk (see the opcode-
;; table docstring in `yin.vm.debruijn-register-code`); this asserts that
;; claim directly over every emitted instruction in the full register-
;; lowerable corpus plus B2's fixtures, rather than leaving it a silent
;; absence.

(deftest move-and-store-mnemonics-are-never-emitted
  (doseq [[name ast _] register-corpus]
    (testing name
      (doseq [t (:instructions (:image (adapted ast)))]
        (is (not (contains? #{:move :store-get :store-put} (nth t 0)))))))
  (doseq [[label ast] {:duplicate-param duplicate-param,
                       :free-variable free-variable,
                       :nested-closure nested-closure,
                       :if-program if-program,
                       :shared-variable shared-variable-under-two-contexts,
                       :shared-lambda shared-lambda-under-two-contexts}]
    (testing label
      (doseq [t (:instructions (:image (adapted ast)))]
        (is (not (contains? #{:move :store-get :store-put} (nth t 0))))))))


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

(deftest register-hash-is-stable-for-a-fixed-program
  (let [{:keys [image]} (adapted worked-example)]
    (is (= (rcode/register-hash image) (rcode/register-hash image)))
    (is (string? (rcode/register-hash image)))
    (is (= 64 (count (rcode/register-hash image))) "sha256 hex digest length")))


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

(def ^:private fixture-b-live-across-branch
  (app (v '+) (v 'a) (if-node (v 'test) (app (v 'f) (lit 1)) (lit 0))))


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
              (is (= live (nth (nth instructions pc) 5))
                  (str name " body " bi " pc " pc)))))))))
