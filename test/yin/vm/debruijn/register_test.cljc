(ns yin.vm.debruijn.register-test
  "R4 (docs/design/yin.vm.debruijn.register.md S5, S6 R4): completion
   tests for `yin.vm.debruijn.register` -- both the pure-program tier
   and the effects tier -- verified across JVM, Node.js/CLJS, and
   ClojureDart with B0-normalizer parity against the Semantic VM and
   Stack VM."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [yin.vm :as vm]
            [yin.vm.debruijn-linearize :as dl]
            [yin.vm.debruijn-register-code :as rcode]
            [yin.vm.debruijn-register-compile :as rc]
            [yin.vm.debruijn-register-effects :as effects]
            [yin.vm.debruijn-vm-contract-test :as b0]
            [yin.vm.debruijn.register :as rvm]
            [yin.vm.debruijn.stack :as dvm]
            [yin.vm.engine :as engine]
            [yin.vm.linearize :as linearize]
            [yin.vm.parity-test :as parity]
            [yin.vm.semantic :as semantic]
            [yin.vm.test-utils :as tu]))


;; =============================================================================
;; AST Helpers & Compilation
;; =============================================================================

(defn- lit
  [x]
  {:type :literal, :value x})


(defn- v
  [sym]
  {:type :variable, :name sym})


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
  [test-node cons-node alt-node]
  {:type :if, :test test-node, :consequent cons-node, :alternate alt-node})


(defn- ast-datoms
  [ast]
  (second (vm/ast->datoms-with-root ast)))


(defn- adapted-register
  [ast]
  (:image (rc/adapt (ast-datoms ast))))


(defn- adapted-stack
  [ast]
  (:image (dl/adapt (vm/ast->datoms ast))))


(def ^:private load-semantic-ast
  (vm/fresh-code-loader (linearize/ast-loader semantic/vm-load-program)
                        vm/ast-contract))


(defn- semantic-run
  "Run `ast` on a fresh semantic VM."
  ([ast] (semantic-run ast {}))
  ([ast opts]
   (vm/run (load-semantic-ast (semantic/create-vm
                                (merge {:make-stream tu/make-stream,
                                        :primitives vm/primitives}
                                       opts))
                              (vm/ast->datoms ast)))))


(defn- stack-run
  "Run `ast` through stack `adapt` on a fresh de Bruijn stack VM."
  ([ast] (stack-run ast {}))
  ([ast opts]
   (vm/run (dvm/create-vm (adapted-stack ast)
                          (merge {:make-stream tu/make-stream,
                                  :primitives vm/primitives,
                                  :contract vm/stack-contract}
                                 opts)))))


(defn- register-run
  "Run `ast-or-image` on a fresh register VM."
  ([ast-or-image] (register-run ast-or-image {}))
  ([ast-or-image opts]
   (let [img (if (and (map? ast-or-image)
                      (contains? ast-or-image :instructions))
               ast-or-image
               (adapted-register ast-or-image))]
     (vm/run (rvm/create-vm img
                            (merge {:make-stream tu/make-stream,
                                    :primitives vm/primitives,
                                    :contract vm/register-contract}
                                   opts))))))


(defn- register-val
  ([ast-or-image] (register-val ast-or-image {}))
  ([ast-or-image opts]
   (b0/normalize (vm/value (register-run ast-or-image opts)))))


(defn- semantic-val
  ([ast] (semantic-val ast {}))
  ([ast opts]
   (b0/normalize (vm/value (semantic-run ast opts)))))


(defn- stack-val
  ([ast] (stack-val ast {}))
  ([ast opts]
   (b0/normalize (vm/value (stack-run ast opts)))))


(defn- fill-live
  [{:keys [bodies instructions] :as pre-image}]
  (reduce
    (fn [instrs bi]
      (reduce-kv (fn [ins pc live]
                   (let [t (nth ins pc)
                         slot (rcode/live-slot-index (first t))]
                     (assoc ins pc (assoc t slot live))))
                 instrs
                 (rcode/body-liveness pre-image bi)))
    instructions
    (range (count bodies))))


(defn- hand-image
  ([reg-count instructions]
   (let [pre {:bodies [{:locals 0,
                        :registers reg-count,
                        :start 0,
                        :end (dec (count instructions))}],
              :instructions instructions}]
     (assoc pre :instructions (fill-live pre)))))


;; =============================================================================
;; 1. Pure Program Tier: Literals, Loads, and Expressions
;; =============================================================================

(deftest literal-test
  (testing "a bare :const, no locals, no calls"
    (let [img (hand-image 1 [[:const 0 42] [:halt 0]])]
      (is (= 42 (register-val img)))
      (is (= (semantic-val (lit 42))
             (stack-val (lit 42))
             (register-val (lit 42)))))))


(deftest load-bound-identity-test
  (testing "a one-arg closure returning its own argument"
    (let [ast (app (lam '[x] (tail (v 'x))) (lit 42))]
      (is (= 42 (register-val ast)))
      (is (= (semantic-val ast) (register-val ast))))))


(deftest load-free-primitive-test
  (testing "(+ 3 4) via :load-free '+ resolved through primitives"
    (let [ast (app (v '+) (lit 3) (lit 4))]
      (is (= 7 (register-val ast)))
      (is (= (semantic-val ast) (register-val ast))))))


(deftest load-free-store-fallback-test
  (testing "a free name absent from primitives resolves through the store"
    (let [img (hand-image 1 [[:load-free 0 'y] [:halt 0]])
          res (register-val img {:store {'y 55}})]
      (is (= 55 res)))))


(deftest nested-closure-capture-test
  (testing "((fn [x] (fn [y] (- x y))) 10) 3) tests innermost-frame-last"
    (let [ast (app (app (lam '[x]
                             (tail (lam '[y]
                                        (tail (app (v '-) (v 'x) (v 'y))))))
                        (lit 10))
                   (lit 3))]
      (is (= 7 (register-val ast)))
      (is (= (semantic-val ast) (register-val ast))))))


(deftest multi-arg-closure-test
  (testing "three-argument closure: (+ a (+ b c))"
    (let [ast (app (lam '[a b c]
                        (tail (app (v '+) (v 'a) (app (v '+) (v 'b) (v 'c)))))
                   (lit 1) (lit 2) (lit 3))]
      (is (= 6 (register-val ast)))
      (is (= (semantic-val ast) (register-val ast))))))


(deftest higher-order-compose-test
  (testing "higher-order function: compose f and g"
    (let [ast (app (lam '[f g x]
                        (tail (app (v 'f) (app (v 'g) (v 'x)))))
                   (lam '[a] (tail (app (v '+) (v 'a) (lit 10))))
                   (lam '[b] (tail (app (v '*) (v 'b) (lit 2))))
                   (lit 5))]
      (is (= 20 (register-val ast)))
      (is (= (semantic-val ast) (register-val ast))))))


(deftest control-flow-branch-test
  (testing "branch-false truthy and falsey branches"
    (let [ast-true (if-node (app (v '<) (lit 1) (lit 2)) (lit 100) (lit 200))
          ast-false (if-node (app (v '>) (lit 1) (lit 2)) (lit 100) (lit 200))]
      (is (= 100 (register-val ast-true)))
      (is (= 200 (register-val ast-false)))
      (is (= (semantic-val ast-true) (register-val ast-true)))
      (is (= (semantic-val ast-false) (register-val ast-false))))))


(deftest store-operations-test
  (testing "store-put and store-get within register VM"
    (let [img (hand-image 2 [[:store-put 0 :foo "hello"]
                             [:store-get 1 :foo]
                             [:halt 1]])
          vm (register-run img)]
      (is (= "hello" (vm/value vm)))
      (is (= "hello" (get (vm/store vm) :foo))))))


;; =============================================================================
;; 2. B0 Parity Corpus & B2 Fixtures
;; =============================================================================

(deftest b0-parity-corpus-test
  (doseq [[label ast _] parity/corpus]
    (testing label
      (is (= (semantic-val ast)
             (stack-val ast)
             (register-val ast))))))


(def ^:private b2-fixtures
  {:duplicate-param
   (lam '[x x] (tail (v 'x))),
   :free-variable
   (lam '[x] (tail (app (v '+) (v 'x) (v 'y)))),
   :nested-closure
   (app (lam '[a a]
             (tail (lam '[b]
                        (tail (app (v '+) (v 'a) (v 'b))))))
        (lit 1)),
   :if-program
   (if-node (app (v '<) (lit 1) (lit 2)) (lit 100) (lit 200)),
   :shared-variable-under-two-contexts
   (let [body (assoc (v 'x) :eid -41)]
     (app (v 'list) (lam '[x] body) (lam '[y] body))),
   :shared-lambda-under-two-contexts
   (let [body (assoc (lam '[z] (tail (app (v '+) (v 'z) (v 'q)))) :eid -70)]
     (app (v 'list) (lam '[p q] body) (lam '[r] body)))})


(deftest b2-fixtures-parity-test
  (let [opts {:primitives (assoc vm/primitives 'list (fn [& args] (vec args)))}]
    (doseq [[label ast] b2-fixtures]
      (testing label
        (is (= (semantic-val ast opts)
               (stack-val ast opts)
               (register-val ast opts)))))))


;; =============================================================================
;; 3. Effects Tier: Gensym
;; =============================================================================

(deftest gensym-test
  (testing "monotonic gensym produces unique IDs and advances id-counter"
    (let [img (hand-image 3 [[:gensym 0 "g"]
                             [:gensym 1 "g"]
                             [:gensym 2 "other"]
                             [:halt 2]])
          vm (register-run img)]
      (is (= :g-0 (nth (:registers vm) 0)))
      (is (= :g-1 (nth (:registers vm) 1)))
      (is (= :other-2 (nth (:registers vm) 2)))
      (is (= 3 (:id-counter vm))))))


;; =============================================================================
;; 4. Effects Tier: Streams (make, put, cursor, next, close)
;; =============================================================================

(defn- scripted-stream
  [outcomes seen]
  (reify
    stream/IDaoStreamReader
    (cursor
      [_ _]
      {:dao.stream/outcome :dao.stream/ok, :dao.stream/cursor :c0})

    (next [_ _] {:dao.stream/outcome :dao.stream/blocked})


    stream/IDaoStreamWriter

    (append!
      [_ v]
      (let [o (first @outcomes)]
        (swap! outcomes rest)
        (swap! seen conj v)
        {:dao.stream/outcome (or o :dao.stream/ok)}))))


(deftest stream-immediate-lifecycle-test
  (testing "stream-make, cursor, put, next, close with sufficient buffer"
    (let [img (hand-image 4 [[:stream-make 0 4]
                             [:stream-cursor 1 0]
                             [:const 2 "hello-stream"]
                             [:stream-put 3 0 2 []]
                             [:stream-next 3 1 []]
                             [:halt 3]])
          vm (register-run img)]
      (is (= "hello-stream" (vm/value vm))))))


(deftest stream-blocking-reader-test
  (testing "stream-next on empty stream parks reader, resumes on write"
    (let [img (hand-image 3 [[:stream-make 0 4]
                             [:stream-cursor 1 0]
                             [:stream-next 2 1 []]
                             [:halt 2]])
          parked (register-run img)]
      (is (true? (vm/blocked? parked)))
      (is (= 1 (count (:wait-set parked))))
      (let [entry (first (:wait-set parked))
            stream-id (:stream-id entry)
            stream-handle (get (vm/store parked) stream-id)]
        (is (= :next (:reason entry)))
        (is (= :yin.debruijn.register (:format entry)))
        (is (= (rcode/register-hash img) (:hash entry)))
        ;; Write to the stream
        (stream/append! stream-handle 99)
        ;; Stepping / running the VM resumes the reader
        (let [resumed (vm/step parked)]
          (is (false? (vm/blocked? resumed)))
          (let [finished (vm/run resumed)]
            (is (true? (vm/halted? finished)))
            (is (= 99 (vm/value finished)))))))))


(deftest stream-blocking-writer-test
  (testing "stream-put on full stream parks writer, resumes on retry"
    (let [outcomes (atom [:dao.stream/full :dao.stream/ok])
          seen (atom [])
          sref {:type :stream-ref, :id :scripted}
          img (hand-image 3 [[:const 0 sref]
                             [:const 1 "payload"]
                             [:stream-put 2 0 1 []]
                             [:halt 2]])
          vm (rvm/create-vm img {:contract vm/register-contract,
                                 :store {:scripted (scripted-stream outcomes
                                                                    seen)}})
          parked (nth (iterate vm/step vm) 3)
          entry (first (:wait-set parked))]
      (is (vm/blocked? parked))
      (is (= 1 (count (:wait-set parked))))
      (is (= :put (:reason entry)))
      (is (= "payload" (:datom entry)))
      (is (= :yin.debruijn.register (:format entry)))
      (let [done (vm/run parked)]
        (is (vm/halted? done))
        (is (= "payload" (vm/value done)))))))


;; =============================================================================
;; 5. Effects Tier: Continuations (current-continuation, park, resume)
;; =============================================================================

(deftest current-continuation-test
  (testing "current-continuation captures reified continuation in rd"
    (let [img (hand-image 2 [[:current-continuation 0 []]
                             [:halt 0]])
          vm (register-run img)
          val (vm/value vm)]
      (is (= :reified-continuation (:type val)))
      (is (= :yin.debruijn.register (:format val)))
      (is (= (rcode/register-hash img) (:hash val)))
      (is (= 0 (:site-pc val)))
      (is (= 1 (:pc val)))
      (is (= 0 (:dest val)))
      (is (= :write-result (:resume-mode val))))))


(deftest park-and-resume-test
  (testing "explicit :park halts VM; resume-continuation delivers value"
    (let [img (hand-image 2 [[:park 0 []]
                             [:halt 0]])
          parked (register-run img)]
      (is (true? (vm/halted? parked)))
      (let [val (vm/value parked)
            park-id (:id val)]
        (is (= :parked-continuation (:type val)))
        (is (some? park-id))
        (let [resumed (engine/resume-continuation parked park-id 777
                                                  rvm/register-restore)
              finished (vm/run resumed)]
          (is (true? (vm/halted? finished)))
          (is (= 777 (vm/value finished))))))))


(deftest bytecode-resume-test
  (testing ":resume instruction resumes a parked continuation"
    (let [body0 [[:park 0 []]
                 [:halt 0]]
          body1 [[:const 0 888]
                 [:resume :parked-0 0]
                 [:return 0]]
          img {:bodies [{:locals 0, :registers 2, :start 0, :end 1}
                        {:locals 0, :registers 2, :start 2, :end 4}],
               :instructions (vec (concat (fill-live {:bodies [{:locals 0,
                                                                :registers 2,
                                                                :start 0,
                                                                :end 1}],
                                                      :instructions body0})
                                          body1))}
          vm0 (rvm/create-vm img {:contract vm/register-contract})
          parked (vm/run vm0)]
      (is (true? (vm/halted? parked)))
      (is (= :parked-0 (get-in parked [:value :id])))
      ;; Now jump to body 1 to execute :resume :parked-0
      (let [vm-resumer (assoc parked :pc 2 :halted? false)
            resumed (vm/run vm-resumer)]
        (is (true? (vm/halted? resumed)))
        (is (= 888 (vm/value resumed)))))))


;; =============================================================================
;; 6. Effects Tier: FFI Bridge Dispatch
;; =============================================================================

(deftest ffi-call-bridge-success-test
  (testing "ffi-call invokes host bridge handler and returns response"
    (let [img (hand-image 3 [[:const 1 50]
                             [:const 2 5]
                             [:ffi-call 0 :math/divide [1 2] []]
                             [:halt 0]])
          bridge {:handlers {:math/divide (fn [a b] (/ a b))}}
          vm (register-run img {:bridge bridge})]
      (is (true? (vm/halted? vm)))
      (is (= 10 (vm/value vm))))))


(deftest ffi-call-bridge-error-test
  (testing "ffi-call error response raises qualified exception"
    (let [img (hand-image 2 [[:const 1 "bad"]
                             [:ffi-call 0 :fail/op [1] []]
                             [:halt 0]])
          bridge {:handlers {:fail/op (fn [_]
                                        (throw (ex-info "Simulated FFI error"
                                                        {:code :bad-input})))}}]
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error :cljd Object)
                            #"FFI call failed"
            (register-run img {:bridge bridge}))))))


;; =============================================================================
;; 7. Protocol Implementations: IVM and IVMState
;; =============================================================================

(deftest ivm-protocol-test
  (let [img (hand-image 1 [[:const 0 99] [:halt 0]])
        vm (rvm/create-vm img {:contract vm/register-contract})]
    (is (false? (vm/halted? vm)))
    (is (false? (vm/blocked? vm)))
    (is (nil? (vm/value vm)))
    (let [vm' (vm/step vm)]
      (is (= 1 (:pc vm')))
      (is (= 99 (nth (:registers vm') 0)))
      (let [vm'' (vm/step vm')]
        (is (true? (vm/halted? vm'')))
        (is (= 99 (vm/value vm'')))
        ;; Reset
        (let [vm-reset (vm/reset vm'')]
          (is (false? (vm/halted? vm-reset)))
          (is (nil? (vm/value vm-reset)))
          (is (= 0 (:pc vm-reset))))))))


(deftest ivm-state-protocol-test
  (let [img (hand-image 1 [[:const 0 123] [:halt 0]])
        vm (rvm/create-vm img {:contract vm/register-contract,
                               :store {:my-key 456}})]
    (is (= {:pc 0} (vm/control vm)))
    (is (= 456 (get (vm/store vm) :my-key)))
    (is (empty? (vm/continuation vm)))
    (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
          (vm/environment vm)))))


(deftest eval-throws-test
  (testing "eval throws informative ex-info pointing to adapt"
    (let [vm (rvm/create-vm (hand-image 1 [[:halt 0]]) {:contract
                                                        vm/register-contract})]
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error :cljd Object)
                            #"executes raw instruction vectors"
            (vm/eval vm (lit 42)))))))


;; =============================================================================
;; 8. Refusals & Defect Diagnostics
;; =============================================================================

(deftest refuse-foreign-continuation-format-test
  (testing "register-restore refuses continuation with foreign format"
    (let [img (hand-image 1 [[:halt 0]])
          vm (rvm/create-vm img {:contract vm/register-contract})
          bad-entry {:format :yin.debruijn.code,
                     :hash (:hash vm),
                     :pc 0,
                     :regs [],
                     :dest 0}]
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error :cljd Object)
                            #"Cannot restore a continuation of another model"
            (rvm/register-restore vm bad-entry 42))))))


(deftest refuse-continuation-hash-mismatch-test
  (testing "register-restore refuses continuation with mismatched hash"
    (let [img (hand-image 1 [[:halt 0]])
          vm (rvm/create-vm img {:contract vm/register-contract})
          bad-entry {:format rvm/format-tag,
                     :hash "wrong-hash",
                     :pc 0,
                     :regs [],
                     :dest 0}]
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error :cljd Object)
                            #"Cannot restore a continuation of another model"
            (rvm/register-restore vm bad-entry 42))))))


(deftest refuse-non-plain-resume-value-test
  (testing "register-restore refuses non-plain resume values like host fn"
    (let [img (hand-image 2 [[:park 0 []] [:halt 0]])
          vm (rvm/create-vm img {:contract vm/register-contract})
          entry (effects/continuation-payload vm (first (:instructions img)))]
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error :cljd Object)
                            #"Resume value must be plain data"
            (rvm/register-restore vm entry (fn [] 42)))))))


(deftest refuse-tampered-continuation-payload-test
  (testing "register-restore refuses tampered continuation payload with defects"
    (let [img (hand-image 2 [[:park 0 []] [:halt 0]])
          vm (rvm/create-vm img {:contract vm/register-contract})
          valid-entry (effects/continuation-payload
                        vm (first (:instructions img)))]
      (testing "tampered live set"
        (let [bad-entry (assoc valid-entry :live [99])]
          (is (thrown-with-msg? #?(:clj Exception :cljs js/Error :cljd Object)
                                #"Corrupt or tampered continuation payload"
                (rvm/register-restore vm bad-entry 42)))))

      (testing "tampered registers out of bounds"
        (let [bad-entry (assoc valid-entry :regs [[99 "tampered"]])]
          (is (thrown-with-msg? #?(:clj Exception :cljs js/Error :cljd Object)
                                #"Corrupt or tampered continuation payload"
                (rvm/register-restore vm bad-entry 42)))))

      (testing "tampered destination register"
        (let [bad-entry (assoc valid-entry :dest 999)]
          (is (thrown-with-msg? #?(:clj Exception :cljs js/Error :cljd Object)
                                #"Corrupt or tampered continuation payload"
                (rvm/register-restore vm bad-entry 42)))))

      (testing "tampered non-boundary site-pc"
        (let [bad-entry (assoc valid-entry :site-pc 1)]
          (is (thrown-with-msg? #?(:clj Exception :cljs js/Error :cljd Object)
                                #"Corrupt or tampered continuation payload"
                (rvm/register-restore vm bad-entry 42)))))

      (testing "tampered malformed return frame"
        (let [bad-entry (assoc valid-entry :continuation [{:corrupt :frame}])]
          (is (thrown-with-msg? #?(:clj Exception :cljs js/Error :cljd Object)
                                #"Corrupt or tampered continuation payload"
                (rvm/register-restore vm bad-entry 42))))))))


(deftest refuse-invalid-image-on-load-test
  (testing "load-image throws on invalid image with defect rule"
    (let [bad-img {:bodies [{:locals 0, :registers 2, :start 0, :end 0}],
                   :instructions [[:const 999 42]]}] ; out of bounds register
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error :cljd Object)
                            #"Invalid register image"
            (rvm/create-vm bad-img {:contract vm/register-contract}))))))


(deftest refuse-unknown-opcode-test
  (testing "step1 throws on unknown opcode in segment"
    (let [bad-img {:bodies [{:locals 0, :registers 1, :start 0, :end 0}],
                   :instructions [[:bad-opcode 0]]}]
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error :cljd Object)
                            #"Unknown opcode"
            (vm/step (rvm/map->DebruijnRegisterVM
                       {:segment bad-img,
                        :hash "fake",
                        :pc 0,
                        :frames [],
                        :registers [nil],
                        :continuation [],
                        :halted? false,
                        :blocked? false})))))))
