(ns yin.vm.debruijn-register-contract-test
  "R0 (docs/design/yin.vm.debruijn.register.md, 'R0: contract and
   corpus'): freezes the register descriptor's contract version, the
   operand mapping every resolved node type gets under section 4.4, the
   two-way address law over the reused B0/B2 corpus, and a set of pure
   data checks on the descriptor's own field list (section 3).

   This phase builds no register lowerer, no register VM, and no
   `:yin.debruijn.register/*` image. It reads only already-merged code:
   `yin.vm.debruijn-resolve/resolve` (B2) and
   `yin.vm.debruijn-linearize/lower-stack`/`adapt` (B2). Everything below
   is data and tests against that code, not a new evaluator.

   DECISION (design section 8, DECIDED item 1, quoted): 'R0-R2 are
   lowerer and format work; R4 is gated by R3.' This file does not
   assume, import, construct, or exercise a register kernel. There is no
   `yin.vm.debruijn.register` namespace yet, and nothing here implies one
   is coming; a register kernel is authorized only if R3's benchmark
   shows a material benefit over the committed stack VM (design section
   1 and section 6's R3/R4 boxes)."
  (:require [clojure.test :refer [deftest is testing]]
            [yin.vm :as vm]
            [yin.vm.debruijn-linearize :as dl]
            [yin.vm.debruijn-resolve :as dr]
            [yin.vm.parity-test :as parity]))


;; =============================================================================
;; 1. The register descriptor's contract version
;; =============================================================================

(def register-contract-version
  "3, the version of the `:yin.debruijn.register/*` descriptor's
   contract (Phase R2). Bumped only when register opcode shape,
   allocation, scalar framing, or control-flow rules change."
  3)


(deftest register-contract-version-is-a-frozen-positive-integer
  (is (integer? register-contract-version))
  (is (pos? register-contract-version))
  (is (= 3 register-contract-version) "Phase R2 version"))


;; =============================================================================
;; 2. The operand mapping (design section 4.4)
;; =============================================================================
;; Every node type `yin.vm.debruijn-resolve/resolve`'s own `case` dispatch
;; in `emit-node!` handles, read from that file directly (its file box
;; forbids editing that namespace to export this list, so this vocabulary
;; is this phase's own frozen copy, not a derived value).

(def resolver-node-type-vocabulary
  "The complete `:yin/type` vocabulary `yin.vm.debruijn-resolve/resolve`
   can produce a resolved record for, per its `emit-node!` case dispatch."
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
;; addresses(resolve x) = addresses(lower-stack (resolve x)), over the
;; reused B0 parity corpus plus B2's duplicate-parameter and shared-
;; occurrence fixtures. This is the baseline R1 extends with a third,
;; register-image sequence once a register lowerer exists; neither side
;; below reaches into a register lowerer, since none exists yet.

(defn- ast-datoms
  [ast]
  (second (vm/ast->datoms-with-root ast)))


(defn- resolved-var-addresses
  "The sequence of resolved variable references read directly off
   resolved tuples, in evaluation order (design section 2.2): operator
   then operands left to right, `if` test then arms, the main body first
   then each out-of-line lambda body in discovery order, an occurrence
   referenced twice contributing twice. A bound reference appears as
   `[:bound depth position]`, a free reference as `[:free name]`.

   Deliberately mirrors `yin.vm.debruijn-linearize/flatten-resolved`'s
   own walk order (main sequence first, lambda bodies queued and
   processed after) without calling it -- that function is private, and
   this phase's job is to independently pin the same law its private
   walk already produces, not to reuse its internals."
  [get-attr root]
  (let [addrs (atom []), bodies (atom [])]
    (letfn [(walk
              [e]
              (case (get-attr e :yin/type)
                :variable
                (let [free (get-attr e :yin.resolved/free)]
                  (swap! addrs conj
                         (if (some? free)
                           [:free free]
                           [:bound (get-attr e :yin.resolved/depth)
                            (get-attr e :yin.resolved/position)])))

                :lambda (swap! bodies conj (get-attr e :yin/body))

                :application
                (do (walk (get-attr e :yin/operator))
                    (run! walk (get-attr e :yin/operands)))

                :dao.stream.apply/call (run! walk (get-attr e :yin/operands))

                :if
                (do (walk (get-attr e :yin/test))
                    (walk (get-attr e :yin/consequent))
                    (walk (get-attr e :yin/alternate)))

                :stream/put
                (do (walk (get-attr e :yin/target))
                    (walk (get-attr e :yin/val-node)))

                (:stream/cursor :stream/next :stream/close)
                (walk (get-attr e :yin/source))

                :vm/resume (walk (get-attr e :yin/val-node))

                nil))]
      (walk root)
      (loop [i 0]
        (when-let [body (get @bodies i)]
          (walk body)
          (recur (inc i))))
      @addrs)))


(defn- stack-image-var-addresses
  "The sequence of `:load-bound`/`:load-free` operands read off a
   `:yin.debruijn.code/*` image in pc order, the same shape
   `resolved-var-addresses` returns."
  [image]
  (into []
        (keep (fn [t]
                (case (nth t 0)
                  :load-bound [:bound (nth t 1) (nth t 2)]
                  :load-free [:free (nth t 1)]
                  nil)))
        image))


(defn resolved-addresses-of
  "Public: R1's own three-way address-law test calls this directly
   (never via var-quote reflection, which does not port to ClojureDart)
   rather than duplicating this file's fixture-building code."
  [ast]
  (let [{:keys [tuples]} (dr/resolve (ast-datoms ast))
        {:keys [get-attr root-id]} (vm/index-datoms tuples)]
    (resolved-var-addresses get-attr root-id)))


(defn stack-addresses-of
  "Public for the same reason as `resolved-addresses-of`."
  [ast]
  (stack-image-var-addresses (:image (dl/adapt (ast-datoms ast)))))


;; -----------------------------------------------------------------------------
;; B2's duplicate-parameter and shared-occurrence fixtures, reconstructed
;; here exactly as `yin.vm.debruijn-resolve-test`/`yin.vm.debruijn-
;; linearize-test` build them, per this lineage's own convention that
;; each test file keeps its own copy of the AST fixture builders.

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
  "Design section 3's field table, transcribed as data."
  {:namespace :yin.debruijn.register
   :contract-version register-contract-version
   :source :resolved-tuples
   :register-image :body-ranges-plus-positional-instructions
   :scalar-encoding :b1-scalar-bytes
   :lexical-addressing :load-bound
   :free-addressing :load-free
   :validation [:shape :targets :body-scope :register-bounds
                :live-shape :live-bounds :live-tail :live-exact]
   :lift :to-named-semantics})


(deftest descriptor-field-list-has-every-declared-field
  (is (= #{:namespace :contract-version :source :register-image
           :scalar-encoding :lexical-addressing :free-addressing
           :validation :lift}
         (set (keys register-descriptor-fields)))))


(deftest descriptor-contract-version-field-matches-the-frozen-constant
  (is (= register-contract-version (:contract-version register-descriptor-fields))))


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
