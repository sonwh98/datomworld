(ns yin.vm.debruijn-linearize-test
  "B2 (docs/design/yin.vm.debruijn.stack.md S3.2): completion tests for
   `yin.vm.debruijn-linearize`.

   Every program below is a plain map AST run through `yin.vm/ast->datoms-
   with-root`, then `yin.vm.linearize/lower` (unmodified) and
   `yin.vm.debruijn-linearize/adapt`. The corpus is the same construction
   style `yin.vm.linearize-test`'s own corpus uses, so this file needs no
   change to that namespace and repeats none of its traversal."
  (:require [clojure.test :refer [deftest is testing]]
            [yin.vm :as vm]
            [yin.vm.code :as code]
            [yin.vm.debruijn-code :as dc]
            [yin.vm.debruijn-linearize :as dl]
            [yin.vm.debruijn.stack :as dvm]
            [yin.vm.linearize :as linearize]))


;; =============================================================================
;; AST fixtures (yin.vm.linearize-test's own construction style)
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
  "`((fn [x] (+ x 1)) 10)`, the same worked example `yin.vm.linearize-test`
   uses -- a simple program with no duplicate parameters, no free-variable
   surprises beyond the primitive `+`, and one level of closure nesting."
  (app (lam '[x] (tail (app (v '+) (v 'x) (lit 1)))) (lit 10)))


(def ^:private duplicate-param
  "`(fn [x x] x)`: `resolve-name`'s rightmost-wins duplicate-parameter
   case, the design's own named example for the lift law."
  (lam '[x x] (tail (v 'x))))


(def ^:private free-variable
  "A body that references a name no enclosing closure binds: `y` lowers
   to `:load-free`, never `:load-bound`."
  (lam '[x] (tail (app (v '+) (v 'x) (v 'y)))))


(def ^:private nested-closure
  "Two levels of nesting, an inner body reaching two frames outward, and a
   duplicate binder at the outer level to exercise both features in one
   corpus program."
  (app (lam '[a a]
            (tail (lam '[b]
                       (tail (app (v '+) (v 'a) (v 'b))))))
       (lit 1)))


(def ^:private corpus
  "Every named mnemonic `yin.vm.code/mnemonics` declares, reached at least
   once: :const :var :closure :push :call :return :jump :branch-false
   :halt :gensym :store-get :store-put :stream-make :stream-put
   :stream-cursor :stream-next :stream-close :park :resume
   :current-continuation :ffi-call."
  {:worked-example worked-example,
   :duplicate-param duplicate-param,
   :free-variable free-variable,
   :nested-closure nested-closure,
   :literal (lit 42),
   :zero-arity-call (app (lam [] (lit 1))),
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
   :default-buffer {:type :stream/make},
   :store-and-control (app (lam '[a b c d]
                                (tail (app (v 'vector) (v 'a) (v 'b) (v 'c) (v 'd))))
                           {:type :vm/store-put, :key 'k, :val 5}
                           {:type :vm/store-get, :key 'k}
                           {:type :vm/gensym, :prefix "g"}
                           {:type :vm/current-continuation}),
   :park-resume (if-node (lit false)
                         {:type :vm/park}
                         {:type :vm/resume, :parked-id :p1, :val (lit 7)})})


;; =============================================================================
;; Helpers
;; =============================================================================

(defn- ast-datoms
  [ast]
  (second (vm/ast->datoms-with-root ast)))


(defn- named-vector
  "The named canonical vector `adapt` itself derives from `ast`, computed
   independently here (same public step, `named-canonical-vector` ∘
   `lower`) for the structural comparison and lift-law tests."
  [ast]
  (:vector (dl/named-canonical-vector (linearize/lower (ast-datoms ast)))))


(defn- adapted
  [ast]
  (dl/adapt (ast-datoms ast)))


;; =============================================================================
;; Shared-entity fixtures: each occurrence lowers independently and
;; correctly, matching yin.vm.debruijn-resolve-test's own fixtures
;; =============================================================================

(def ^:private shared-variable-body
  (assoc (v 'x) :eid -41))


(def ^:private shared-variable-under-two-contexts
  (app (v 'list) (lam '[x] shared-variable-body) (lam '[y] shared-variable-body)))


;; =============================================================================
;; Every node and opcode is handled; images are shape and scope valid
;; =============================================================================

(deftest every-corpus-program-adapts-to-a-valid-image
  (doseq [[label ast] corpus]
    (testing label
      (let [{:keys [image side-table]} (adapted ast)]
        (is (nil? (dc/well-formed-image? image)) "B1's generic shape rule")
        (is (nil? (dc/image-defect image)) "B1's full validator, including scope")
        (is (map? side-table))))))


;; =============================================================================
;; Deterministic output
;; =============================================================================

(deftest adapting-twice-is-byte-identical
  (doseq [[label ast] corpus]
    (testing label
      (let [datoms (ast-datoms ast)
            h1 (dc/image-hash (:image (dl/adapt datoms)))
            h2 (dc/image-hash (:image (dl/adapt datoms)))]
        (is (= h1 h2))))))


;; =============================================================================
;; Structural opcode-by-opcode comparison with the named vector
;; =============================================================================

(deftest structural-comparison-differs-only-at-var-and-closure
  (doseq [[label ast] corpus]
    (testing label
      (let [nv (named-vector ast)
            {:keys [image]} (adapted ast)]
        (is (= (count nv) (count image)) "same instruction count")
        (dotimes [pc (count nv)]
          (let [nt (nth nv pc), dt (nth image pc)
                nop (nth nt 0), dop (nth dt 0)]
            (testing (str "pc " pc)
              (case nop
                :var
                (is (contains? #{:load-bound :load-free} dop)
                    "a :var rewrites to :load-bound or :load-free")

                :closure
                (do (is (= :closure dop))
                    (is (= (count (nth nt 1)) (nth dt 1))
                        "arity is the original parameter vector's count")
                    (is (= (nth nt 2) (nth dt 2)) "the body pc is unchanged"))

                (is (= nt dt) "every other pc is copied unchanged")))))))))


;; =============================================================================
;; Every image B2 emits round trips through B3
;; =============================================================================

(deftest corpus-images-load-and-run-on-b3
  (doseq [[label ast] [[:literal (:literal corpus)]
                       [:duplicate-param duplicate-param]
                       [:worked-example worked-example]
                       [:zero-arity-call (:zero-arity-call corpus)]
                       [:if-in-tail (:if-in-tail corpus)]]]
    (testing label
      (let [{:keys [image]} (adapted ast)]
        (is (nil? (dc/image-defect image)))
        (is (some? (dvm/create-vm image {:primitives vm/primitives})) "loads")
        (let [result (vm/run (dvm/create-vm image {:primitives vm/primitives}))]
          (is (vm/halted? result)))))))


;; =============================================================================
;; Every mnemonic yin.vm.code/mnemonics declares is reached by the corpus
;; =============================================================================
;; qwen3.8-max's independent review of the fused implementation found the
;; corpus docstring's coverage claim above was never actually computed or
;; asserted. This test closes that gap: the union of mnemonics emitted
;; across the adapted corpus, translated from this dimension's own
;; `:load-bound`/`:load-free`/`:closure` spellings back to the named
;; mnemonics they replace, must equal `code/mnemonics` exactly.

(def ^:private debruijn->named-mnemonic
  {:load-bound :var, :load-free :var, :closure :closure})


(deftest corpus-reaches-every-named-mnemonic
  (let [emitted (into #{}
                      (mapcat (fn [[_ ast]]
                                (map (fn [t] (get debruijn->named-mnemonic (nth t 0) (nth t 0)))
                                     (:image (adapted ast)))))
                      corpus)]
    (is (= code/mnemonics emitted))))


;; =============================================================================
;; The lift correctness law
;; =============================================================================

(def ^:private lift-law-programs
  {:simple worked-example,
   :duplicate-param duplicate-param,
   :free-variable free-variable,
   :nested-closure nested-closure})


(deftest lift-with-the-adapters-own-side-table-is-exact
  (doseq [[label ast] lift-law-programs]
    (testing label
      (let [nv (named-vector ast)
            {:keys [image side-table]} (adapted ast)
            lifted (dl/lift image side-table)]
        (is (= nv lifted)
            "lift(adapt(lower x), side-table) = canonical-vector(lower x), exactly")))))


(deftest lift-with-synthesized-names-is-alpha-equivalent
  (doseq [[label ast] lift-law-programs]
    (testing label
      (let [nv (named-vector ast)
            {:keys [image]} (adapted ast)
            lifted (dl/lift image nil)]
        (is (not= nv lifted)
            "synthesized names are not the original spelling")
        (is (vector? lifted)
            "lift did not throw: its own round-trip check accepted the synthesized names -- the operational definition of alpha-equivalence this bytecode form admits, since synthesized names are pairwise distinct and fresh against every free name by construction")))))


(defn- closure-params
  "The parameter vector `lifted` (a named canonical vector) declares for
   its `:closure` at `pc`, for asserting on which names a fallback
   actually used."
  [lifted pc]
  (nth (nth lifted pc) 1))


(deftest a-foreign-side-table-that-fails-to-round-trip-falls-back-to-synthesis
  (testing "the vacuous fixture qwen flagged: wrong arity alone rejects it before round-trip ever runs"
    (let [{:keys [image side-table]} (adapted worked-example)
          wrong (assoc-in side-table [0 :params] '[not-the-real-name not-the-real-name])
          lifted (dl/lift image wrong)]
      (is (not= '[not-the-real-name not-the-real-name] (closure-params lifted 0))
          "the wrong table is not trusted verbatim")))
  (testing "a same-arity capturing name that genuinely fails round-trip"
    ;; `(fn [x] (+ x y))`: the closure has arity 1 and a free reference to
    ;; `y`. A side table claiming the single parameter is spelled `y` has
    ;; the SAME arity as the real one, so `lift-params` accepts it without
    ;; ever reaching synthesis -- but using it would re-resolve the free
    ;; `y` occurrence as bound, so `round-trips?` must reject it and
    ;; `lift` must fall back to synthesis, unlike the vacuous fixture
    ;; above where the wrong arity alone was already enough to reject it.
    (let [ast (lam '[x] (tail (app (v '+) (v 'x) (v 'y))))
          {:keys [image side-table]} (dl/adapt (ast-datoms ast))
          closure-pc (first (keep-indexed (fn [pc t] (when (= :closure (nth t 0)) pc)) image))
          wrong (assoc-in side-table [closure-pc :params] '[y])
          lifted (dl/lift image wrong)]
      (is (not= '[y] (closure-params lifted closure-pc))
          "a same-arity capturing name fails round-trip and falls back to synthesis"))))


;; =============================================================================
;; Named lowering semantics are unchanged: `lower`'s own output is untouched
;; =============================================================================

(deftest lower-itself-is-unmodified-by-this-namespace
  (testing "well-formed? still judges lower's raw datom output the same way"
    (let [datoms (linearize/lower (ast-datoms worked-example))]
      (is (nil? (code/well-formed? datoms))))))


;; =============================================================================
;; A shared source entity lowers independently and correctly per occurrence
;; =============================================================================

(deftest a-shared-variable-lowers-independently-per-occurrence
  (let [{:keys [image side-table]} (adapted shared-variable-under-two-contexts)]
    (is (nil? (dc/image-defect image)))
    (is (some #(= [:load-bound 0 0] %) image)
        "the occurrence under [x] loads bound")
    (is (some #(= [:load-free 'x] %) image)
        "the occurrence under [y] loads free")
    (is (= 2 (count (filter #(= -41 (:source %)) (vals side-table))))
        "both loads carry the shared entity's own source id")))


;; =============================================================================
;; lower-stack refuses a hand-built invalid resolved-tuple set, calling
;; validate-resolved before doing anything else
;; =============================================================================

(deftest lower-stack-refuses-an-invalid-resolved-tuple-set
  (let [bad {:tuples [[1 :yin/type :variable 0 :db/add]
                      [1 :yin/root true 0 :db/add]
                      [1 :yin.resolved/depth 5 0 :db/add]
                      [1 :yin.resolved/position 0 0 :db/add]]
             :source {1 -1}
             :params {}}]
    (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
          (dl/lower-stack bad)))))
