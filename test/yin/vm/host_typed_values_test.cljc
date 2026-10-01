(ns yin.vm.host-typed-values-test
  "D6/D7 slice A (Architect design, host-typed closures and continuations):
   a closure and a captured continuation are host types only a kernel
   mints, opaque to guest primitives, owned by the task that minted them,
   and equal structurally; application refuses in one qualified
   vocabulary; a lambda binder must be a symbol; and heap reclamation
   never reads guest data as a kernel shape. Every program runs on all
   four VMs -- the AST walker, the semantic VM, and the de Bruijn stack
   and register VMs."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [yin.repl :as repl]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as walker]
            [yin.vm.debruijn-linearize :as dl]
            [yin.vm.debruijn-register-compile :as rc]
            [yin.vm.debruijn.register :as rvm]
            [yin.vm.debruijn.stack :as dvm]
            [yin.vm.engine :as engine]
            [yin.vm.linearize :as linearize]
            [yin.vm.module :as module]
            [yin.vm.semantic :as semantic]
            [yin.vm.test-utils :as tu]
            [yin.vm.values :as values]))


;; =============================================================================
;; The four VMs
;; =============================================================================

(def ^:private opts
  {:capability-secret tu/secret,
   :primitives vm/primitives,
   :modules (module/register-cell-module (module/default-registry))})


(def ^:private kernels
  "Per VM: `:image` compiles an AST to the image the kernel loads and
   attaches, `:load` builds the loaded, not yet run, VM over it."
  {:ast-walker {:image vm/ast->semantic-bytecode,
                :load (fn [image o]
                        (walker/vm-load-rows (walker/create-vm o)
                                             image
                                             vm/ast-contract))},
   :semantic {:image (fn [ast]
                       (:vector (linearize/lower-rows
                                  (vm/ast->semantic-bytecode ast)))),
              :load (fn [image o]
                      (semantic/load-vector (semantic/create-vm o)
                                            image
                                            vm/semantic-contract))},
   :stack {:image (fn [ast] (:image (dl/adapt (vm/ast->datoms ast)))),
           :load (fn [image o]
                   (dvm/create-vm image (assoc o :contract vm/stack-contract)))},
   :register {:image (fn [ast]
                       (:image (rc/adapt (second (vm/ast->datoms-with-root
                                                   ast))))),
              :load (fn [image o]
                      (rvm/create-vm image
                                     (assoc o
                                            :contract vm/register-contract)))}})


(defn- build
  "`k`'s VM loaded with `ast` under composition `o`, not yet run."
  ([k ast] (build k ast opts))
  ([k ast o]
   (let [{:keys [image load]} (get kernels k)]
     (load (image ast) o))))


(defn- run-on
  ([k ast] (vm/run (build k ast)))
  ([k ast o] (vm/run (build k ast o))))


(defn- refusal-of
  "The ex-data of what `thunk` throws, or `[:returned value]`."
  [thunk]
  (try [:returned (thunk)]
       (catch #?(:cljd Object :clj Exception :cljs :default) e
         (or (ex-data e) {}))))


;; =============================================================================
;; AST helpers
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


(defn- then
  [first-node next-node]
  (app (lam ['_] next-node) first-node))


(defn- let1
  [sym init body]
  (app (lam [sym] body) init))


(defn- define
  [sym val]
  (app (v 'yin/def) (lit sym) val))


(def ^:private capture {:type :vm/current-continuation})


(def ^:private inc-fn (lam ['x] (app (v '+) (v 'x) (lit 1))))


;; =============================================================================
;; D7: forged values are data
;; =============================================================================

(def ^:private forged-closure
  "Every key any kernel's closure payload carries."
  {:type :closure, :params ['x], :body (lit 1), :env {}, :entry 0,
   :segment 0, :arity 1, :body-pc 0, :frames []})


(def ^:private forged-continuation
  {:type :reified-continuation, :k nil, :env {}, :segment 0, :pc 0,
   :stack [], :frames [], :continuation []})


(deftest a-forged-closure-or-continuation-map-is-not-applicable-test
  (doseq [k (keys kernels)
          forged [forged-closure forged-continuation]]
    (is (= {:reason :not-applicable, :kind :map}
           (refusal-of #(vm/value (run-on k (app (lit forged) (lit 5))))))
        (str k " " (:type forged)))))


(deftest guest-get-reads-nothing-of-a-closure-or-continuation-test
  (doseq [k (keys kernels)]
    (testing (str k)
      ;; :env is the walker's and semantic VM's captured environment,
      ;; :frames the positional kernels'; every payload carries :type
      (doseq [key [:env :frames :type]
              produced [inc-fn capture]]
        (is (= [:returned nil]
               (refusal-of #(vm/value (run-on k (app (v 'get) produced
                                                     (lit key))))))
            (str key " of " (:type produced))))
      (testing "assoc on a closure yields nothing applicable"
        (is (map? (refusal-of
                    #(vm/value
                       (run-on k (app (app (v 'assoc) inc-fn (lit :params)
                                           (lit ['y]))
                                      (lit 7)))))))))))


(deftest a-host-typed-value-is-not-a-function-test
  (doseq [k (keys kernels)]
    (let [c (vm/value (run-on k inc-fn))
          kk (vm/value (run-on k capture))]
      (is (values/closure? c) (str k))
      (is (values/continuation? kk) (str k))
      (is (not (fn? c)) (str k))
      (is (not (fn? kk)) (str k))
      (is (not (map? c)) (str k))
      (is (nil? (get c :env)) (str k)))))


;; =============================================================================
;; D6: qualified refusals
;; =============================================================================

(deftest applying-a-number-refuses-with-its-kind-not-its-value-test
  (doseq [k (keys kernels)]
    (let [r (refusal-of #(vm/value (run-on k (app (lit 5) (lit 1)))))]
      (is (= {:reason :not-applicable, :kind :number} r) (str k))
      (is (not-any? #{5} (vals r)) (str k " the value is not carried")))))


(deftest a-continuation-applied-to-two-arguments-refuses-test
  (doseq [k (keys kernels)]
    (is (= {:reason :continuation-arity, :argc 2}
           (refusal-of
             #(vm/value (run-on k (let1 'k capture
                                        (app (v 'k) (lit 1) (lit 2)))))))
        (str k))))


;; =============================================================================
;; The store-of entrance: a binder must be a symbol
;; =============================================================================

(def ^:private store-of-program
  "A lambda whose binder would plant the module-store key in a named
   kernel's environment, applied, writing the store."
  (app (lam ['p] {:type :vm/store-put, :key 'x, :val 1})
       (lit :victim-module)))


(def ^:private bad-binders
  "Each non-symbol binder with the kind its refusal names: the keyword
   that would plant the module-store key, and the two falsy binders a
   `some` over the params would read as none."
  [[:yin.k/store-of :keyword] [nil :nil] [false :boolean]])


(def ^:private load-semantic-ast
  (vm/fresh-code-loader (linearize/ast-loader semantic/vm-load-program)
                        vm/ast-contract))


(def ^:private raw-runners
  "Each VM fed a binder by the path that reaches its own refusal: the
   walker its map AST (the row validator would refuse first), the semantic
   VM its AST through the lowering, and the positional kernels the AST
   their resolver compiles. The semantic code loader also refuses a
   non-symbol binder by operand kind, so its transition check is defence
   in depth that no loader reaches. Each takes the program to run."
  {:ast-walker (fn [ast]
                 (vm/run (walker/vm-load-program (walker/create-vm opts)
                                                 (vm/ast->datoms ast)
                                                 vm/ast-contract))),
   :semantic (fn [ast]
               (vm/run (load-semantic-ast (semantic/create-vm opts)
                                          (vm/ast->datoms ast)))),
   :stack (fn [ast] (run-on :stack ast)),
   :register (fn [ast] (run-on :register ast))})


(deftest a-non-symbol-binder-is-refused-test
  (doseq [[k run] raw-runners
          [binder kind] bad-binders]
    (is (= {:reason :non-symbol-parameter, :kind kind}
           (select-keys (refusal-of
                          #(vm/value (run (walk/postwalk-replace
                                            {'p binder}
                                            store-of-program))))
                        [:reason :kind]))
        (str k " " (pr-str binder))))
  (testing "a symbol binder runs"
    (doseq [k (keys kernels)]
      (is (= 1 (vm/value (run-on k store-of-program))) (str k)))))


;; =============================================================================
;; Ownership: a raw value of another task refuses; lift and lower carry it
;; =============================================================================

(defn- with-stream
  "`vm` holding a reference to stream `handle` in its store under 's."
  [vm handle]
  (let [[ref vm] (engine/attach-resource vm handle)]
    (assoc-in vm [:store 's] ref)))


(def ^:private task-a (assoc opts :capability-secret "task-a"))


(def ^:private task-b (assoc opts :capability-secret "task-b"))


(def ^:private read-and-apply
  "Read the first value of stream `s` and apply it to 7."
  (let1 'c {:type :stream/cursor, :source (v 's)}
        (app {:type :stream/next, :source (v 'c)} (lit 7))))


(deftest a-value-handed-raw-to-another-task-refuses-test
  (doseq [k (keys kernels)
          [label produced] [["closure" inc-fn] ["continuation" capture]]]
    (let [handle (tu/new-stream 8)
          _ (vm/run (with-stream (build k {:type :stream/put,
                                           :target (v 's),
                                           :val produced}
                                        task-a)
                      handle))]
      (is (= {:reason :foreign-value, :kind (keyword label)}
             (refusal-of #(vm/value (vm/run (with-stream
                                              (build k read-and-apply task-b)
                                              handle)))))
          (str k " " label))))
  (testing "through a stream inside one task, a closure still applies"
    (doseq [k (keys kernels)]
      (is (= 8 (vm/value (vm/run (with-stream
                                   (build k
                                          (then {:type :stream/put,
                                                 :target (v 's),
                                                 :val inc-fn}
                                                read-and-apply)
                                          task-a)
                                   (tu/new-stream 8)))))
          (str k)))))


(defn- lowered
  "Lift the closure task A defines as `f` and lower it into a task B
   loaded with `caller`: B's store holds the lowered value as `f`."
  [k caller]
  (let [{:keys [image load]} (get kernels k)
        module-image (image (define 'f inc-fn))
        a (vm/run (load module-image task-a))
        lifted (engine/lift-slice a :segment/own ['f])
        b (load (image caller) task-b)
        entry (assoc lifted
                     :images {(module/image-identity a module-image)
                              module-image})
        received (engine/receive-module b 'm entry)]
    [a
     (assoc-in received
               [:store 'f]
               (get-in (module/resolve-module (:modules received) 'm)
                       [:bindings 'f]))]))


(deftest the-same-closure-through-lift-and-lower-applies-test
  (doseq [k (keys kernels)]
    (let [[a b] (lowered k (app (v 'f) (lit 7)))]
      (is (values/closure? (get (:store b) 'f)) (str k))
      (is (= (:owner b) (values/owner (get (:store b) 'f)))
          (str k " the lower mints with the receiver's owner"))
      (is (not= (:owner a) (:owner b)) (str k))
      (is (= 8 (vm/value (vm/run b))) (str k)))))


(defn- lower-tampered
  "The refusal of lowering, into task B, task A's lifted `f` with its
   marker changed by `tamper`, or `[:returned state]`."
  [k tamper]
  (let [{:keys [image load]} (get kernels k)
        module-image (image (define 'f inc-fn))
        a (vm/run (load module-image task-a))
        lifted (update-in (engine/lift-slice a :segment/own ['f])
                          [:slice 'f]
                          tamper)
        b (load (image (lit 0)) task-b)]
    (refusal-of #(engine/receive-module
                   b
                   'm
                   (assoc lifted
                          :images {(module/image-identity a module-image)
                                   module-image})))))


(deftest a-lower-takes-its-binders-from-the-attached-lambda-test
  (testing "named kernels: the wire's params are checked, then must be the
            attached lambda's"
    (doseq [k [:ast-walker :semantic]]
      (doseq [[binder kind] bad-binders]
        (is (= {:reason :non-symbol-parameter, :kind kind}
               (select-keys (lower-tampered k #(assoc % :yin.k/params [binder]))
                            [:reason :kind]))
            (str k " " (pr-str binder))))
      (doseq [params ['[y] '[x y] '[]]]
        (is (= :marker-mismatch
               (:reason (lower-tampered k #(assoc % :yin.k/params params))))
            (str k " " params)))))
  (testing "positional kernels bind by position: the wire's arity must be
            the attached lambda's"
    (doseq [k [:stack :register]
            arity [0 2]]
      (is (= :marker-mismatch
             (:reason (lower-tampered k #(assoc % :yin.k/arity arity))))
          (str k " arity " arity))))
  (testing "an untampered marker lowers"
    (doseq [k (keys kernels)]
      (is (vector? (lower-tampered k identity)) (str k)))))


(deftest a-foreign-closure-refuses-to-lift-test
  (doseq [k (keys kernels)]
    (let [a (run-on k (define 'f inc-fn) task-a)
          b (assoc-in (run-on k (lit 0) task-b) [:store 'f]
                      (get (:store a) 'f))]
      (is (= {:yin.k/status :yin.k/non-portable,
              :yin.k/kind :foreign-value,
              :yin.k/hint :closure}
             (refusal-of #(engine/lift-slice b :segment/own ['f])))
          (str k)))))


;; =============================================================================
;; Equality: structural over kind, owner and payload
;; =============================================================================

(def ^:private two-from-one-lambda
  (let1 'mk (lam [] (lam ['x] (v 'x)))
        (app (v 'conj) (app (v 'conj) (lit []) (app (v 'mk))) (app (v 'mk)))))


(deftest closures-from-one-lambda-are-equal-test
  (doseq [k (keys kernels)]
    (let [[c1 c2] (vm/value (run-on k two-from-one-lambda))]
      (testing (str k)
        (is (values/closure? c1))
        (is (not (identical? c1 c2)))
        (is (= c1 c2))
        (is (= (hash c1) (hash c2)) "hash agrees with equality")
        (is (= 1 (count (hash-set c1 c2))) "a set member")
        (is (not= c1 (values/payload c1)) "never equal to a look-alike map")
        (is (not= (values/payload c1) c1))
        (is (not= c1 (values/closure "another-owner" (values/payload c1)))
            "the owner is part of equality")
        (is (not= c1 (values/continuation (values/owner c1)
                                          (values/payload c1)))
            "the kind is part of equality")))))


(deftest guest-equality-agrees-test
  (doseq [k (keys kernels)]
    (is (true? (vm/value (run-on k (let1 'mk (lam [] (lam ['x] (v 'x)))
                                         (app (v '=) (app (v 'mk))
                                              (app (v 'mk)))))))
        (str k))))


(deftest two-runs-give-equal-vm-states-test
  (let [program (then (define 'f inc-fn)
                      (then (define 'k capture)
                            (app (v 'f) (lit 1))))]
    (doseq [k (keys kernels)]
      (let [a (run-on k program)
            b (run-on k program)]
        (is (values/closure? (get (:store a) 'f)) (str k))
        (is (values/continuation? (get (:store a) 'k)) (str k))
        (is (= a b) (str k))))))


;; =============================================================================
;; Two-mode heap trace
;; =============================================================================

(defn- new-cell
  [x]
  (app (v 'cell/new) x))


(defn- quiesce
  [vm]
  (assoc vm
         :value nil
         :control nil
         :env {}
         :k nil
         :stack []
         :registers []
         :frames []
         :continuation []))


(defn- survivors
  [vm]
  (set (map :value (vals (:heap (engine/collect vm))))))


(defn- frame-shaped
  "A guest map shaped like `k`'s own kernel value that prunes the key it
   hides `ref` under, before the two-mode trace."
  [k ref]
  (case k
    :ast-walker {:type :eval-operator, :frame {:hidden ref}}
    :semantic {:type :eval-operator, :frame {:hidden ref}}
    :stack {:format :yin.debruijn.code, :segment ref}
    :register {:format :yin.debruijn.register, :segment ref}))


(deftest a-guest-frame-shaped-map-keeps-its-cell-test
  (doseq [k (keys kernels)]
    (let [result (run-on k (new-cell (lit :keep)) (assoc opts :gc-threshold
                                                         1000000))
          ref (vm/value result)
          q (quiesce result)
          m (frame-shaped k ref)]
      (doseq [[place vm] {"the store" (assoc-in q [:store 'x] m),
                          "a module store" (assoc-in q [:module-stores :m 'x] m),
                          "the value register" (assoc q :value m),
                          "a parked entry's value"
                          (assoc-in q [:parked :p] {:value m}),
                          "a closure's environment"
                          (assoc-in q [:store 'x]
                                    (values/closure (:owner q)
                                                    {:type :closure,
                                                     :params [],
                                                     :env {'y m}}))}]
        (is (= #{:keep} (survivors vm)) (str k ": " place))))))


(defn- positional?
  [k]
  (contains? #{:stack :register} k))


(defn- guest-frame
  "An expression building `k`'s frame-shaped map around cell ref `c`."
  [k c]
  (if (positional? k)
    (app (v 'assoc) (lit (dissoc (frame-shaped k nil) :segment)) (lit :segment)
         c)
    (app (v 'assoc) (lit (dissoc (frame-shaped k nil) :frame)) (lit :frame)
         (app (v 'assoc) (lit {}) (lit :hidden) c))))


(defn- hidden-ref
  "An expression reading the cell ref back out of the map `x` names."
  [k x]
  (if (positional? k)
    (app (v 'get) x (lit :segment))
    (app (v 'get) (app (v 'get) x (lit :frame)) (lit :hidden))))


(deftest a-guest-frame-shaped-map-survives-a-collection-in-a-run-test
  (testing "the cell is reachable only through the map in the store when
            the next allocations collect: its own binding returned with
            the call that made it"
    (doseq [k (keys kernels)]
      (is (= :keep
             (vm/value
               (run-on k
                       (then (define 'x
                               (app (lam []
                                         (let1 'c (new-cell (lit :keep))
                                               (guest-frame k (v 'c))))))
                             (then (new-cell (lit :g1))
                                   (then (new-cell (lit :g2))
                                         (app (v 'cell/get)
                                              (hidden-ref k (v 'x))))))
                       (assoc opts :gc-threshold 1))))
          (str k)))))


;; =============================================================================
;; Printing is opaque
;; =============================================================================

(def ^:private secret-word
  "A value only a captured environment, frame or stack holds."
  "s3cr3t-42")


(defn- leaks?
  "True when `text` shows anything a closure or continuation captured or
   carries: the captured value, an environment, frames, or `owner`."
  [text owner]
  (or (str/includes? text secret-word)
      (str/includes? text ":env")
      (str/includes? text ":frames")
      (str/includes? text ":stack")
      (str/includes? text (str owner))))


(deftest a-closure-or-continuation-prints-opaquely-test
  (doseq [k (keys kernels)]
    (let [c (vm/value (run-on k (let1 's (lit secret-word)
                                      (lam ['x] (v 's)))))
          kk (vm/value (run-on k (let1 's (lit secret-word) capture)))]
      (testing (str k)
        (is (str/includes? (pr-str (values/payload c)) secret-word)
            "the closure did capture the value")
        (is (str/includes? (pr-str (values/payload kk)) secret-word)
            "the continuation did capture the value")
        (doseq [[label text]
                {"str" (str c),
                 "pr-str" (pr-str c),
                 "str of a continuation" (str kk),
                 "pr-str of a continuation" (pr-str kk),
                 "nested pr-str" (pr-str [c {:k kk}]),
                 "nested str" (str [c {:k kk}]),
                 "REPL display" (repl/format-value c),
                 "nested REPL display" (repl/format-value [c {:k kk}])}]
          (is (not (leaks? text (values/owner c))) (str label ": " text)))
        (is (= "{:type :closure}" (str c) (pr-str c) (repl/format-value c)))
        (is (= "{:type :continuation}"
               (str kk) (pr-str kk) (repl/format-value kk)))
        (is (= "[{:type :closure} {:k {:type :continuation}}]"
               (pr-str [c {:k kk}])
               (repl/format-value [c {:k kk}])))))))


(deftest guest-printing-of-a-closure-is-opaque-test
  (doseq [vm-type (keys repl/vm-constructors)]
    (let [[_ texts]
          (reduce (fn [[state texts] line]
                    (let [[state' text] (repl/eval-input state line)]
                      [state' (conj texts text)]))
                  [(repl/create-state {:vm-type vm-type}) []]
                  [(str "(def f (let [s \"" secret-word "\"] (fn [x] s)))")
                   "(f 1)"
                   "f"
                   "(println f)"
                   "(print (conj [] f (assoc {} :k f)))"
                   "(prn f)"])
          [_ applied shown & printed] texts]
      (testing (str vm-type)
        (is (= (pr-str secret-word) applied) "the closure holds the value")
        (is (= "{:type :closure}" shown))
        (doseq [text printed]
          (is (not (str/includes? text secret-word)) text)
          (is (str/includes? text "{:type :closure}") text))))))
