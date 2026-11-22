(ns yang.rt.effect-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing.mem :as mem]
            [yang.clojure :as clj]
            [yang.clojure.core-impl :as core-impl]
            [yang.clojure.core-module :as core-module]
            [yang.clojure.prelude :as prelude]
            [yang.core.call :as call]
            [yang.core.uast :as u]
            [yang.rt.effect :as effect]
            [yang.rt.int :as int-rt]
            [yang.typescript.antlr.linked-harness :as h]
            [yin.vm :as vm]
            [yin.vm.integer :as integer]
            [yin.vm.linker.interpreter :as link]
            [yin.vm.linker.publish :as publish]))


(def ^:private registry (prelude/admit (h/registry)))

(def ^:private published
  (delay
    (let [store (mem/create-content-mem)
          install (fn [spec]
                    (let [result (publish/publish-module! store spec)]
                      (or (:address result)
                          (throw (ex-info "Publication refused" result)))))
          call-address (install (call/module-spec registry))
          effect-address (install (effect/module-spec registry))
          int-address (install (int-rt/module-spec registry effect-address))
          clj-address (install (prelude/module-spec registry call-address effect-address))
          impl-address (install (core-impl/module-spec registry call-address clj-address int-address))
          core-address (install (core-module/module-spec registry clj-address impl-address))]
      (link/composition
        {:content-store store
         :derivation :trusted
         :name-env {'yang.call call-address 'yrt.effect effect-address
                    'yrt.int int-address 'clj clj-address
                    'clojure.core.impl impl-address 'clojure.core core-address}}))))


(defn- run-runtime [backend bundled? form]
  (let [body (u/sexp->uast form)]
    (vm/value
      (h/run-linked backend
        (if bundled?
          (u/mark-tails (u/seq-nodes [effect/uast int-rt/uast body]))
          (u/then (u/sexp->uast '(require 'yrt.int)) body))
        {:modules registry :link-source @published}))))


(deftest abortive-control
  (let [form
        '(let [ctx (cell/new (yrt.effect/ctx nil 0 0 nil))
               log (cell/new [])
               note (fn [v] (cell/set! log (conj (cell/get log) v)))
               on-empty (fn [e] (conj [] :empty e))
               normal (yrt.effect/try-value ctx (fn [] :normal) note)
               orelse (yrt.effect/try-orelse ctx (fn [] :ignored)
                        note (fn [] :orelse))
               raised (yrt.effect/try-value ctx
                        (fn []
                          (yrt.effect/try-finally ctx
                            (fn []
                              (yrt.effect/handle ctx :unrelated
                                (fn [payload k] (note :unexpected-handler))
                                (fn []
                                  (yrt.effect/try-finally ctx
                                    (fn [] (yrt.effect/raise ctx :boom on-empty))
                                    (fn [e] (note (conj [] :inner e)))
                                    :inner-normal))))
                            (fn [e] (note (conj [] :outer e)))
                            :outer-normal))
                        (fn [e] (conj [] :caught e)))
               normal-finally (yrt.effect/try-finally ctx
                                (fn [] 3) note :finally-normal)
               ec (yrt.effect/call-ec ctx
                    (fn [exit]
                      (yrt.effect/try-finally ctx (fn [] (exit 4)) note :unused))
                    :escape)
               terminal (yrt.effect/call-terminal ctx
                          (fn [exit]
                            (yrt.effect/try-finally ctx (fn [] (exit 5)) note :unused)))]
           (conj [] normal orelse raised normal-finally ec terminal
                 (cell/get log) (yrt.effect/handlers ctx)
                 (yrt.effect/raise ctx :root on-empty)))]
    (doseq [backend (keys h/backends), bundled? [true false]]
      (testing (str backend " bundled=" bundled?)
        (is (= [:normal :orelse [:caught :boom] 3 4 5
                [[:inner :boom] [:outer :boom] :finally-normal :escape]
                nil [:empty :root]]
               (run-runtime backend bundled? form)))))))


(deftest resumable-control
  (let [form
        '(let [ctx (cell/new (yrt.effect/ctx nil 0 0 nil))
               log (cell/new [])
               note (fn [v] (cell/set! log (conj (cell/get log) v)))
               unhandled (fn [tag payload] (conj [] tag payload))
               resumed (yrt.effect/handle ctx :test
                         (fn [payload k] (do (note :resume-handler) (k (+ 2 payload))))
                         (fn []
                           (yrt.effect/try-finally ctx
                             (fn []
                               (let [v (yrt.effect/perform ctx :test 10 unhandled)]
                                 (do (note :resumed) (+ 1 v))))
                             note :resume-cleanup)))
               aborted (yrt.effect/handle ctx :test
                         (fn [payload k] (do (note :abort-handler) payload))
                         (fn []
                           (yrt.effect/try-finally ctx
                             (fn []
                               (yrt.effect/handle ctx :unrelated
                                 (fn [payload k] (note :unexpected-handler))
                                 (fn []
                                   (do (yrt.effect/perform ctx :test :aborted unhandled)
                                       (note :unreachable)))))
                             (fn [e] (note :abort-cleanup))
                             :unused)))
               thrown (yrt.effect/try-value ctx
                        (fn []
                          (yrt.effect/handle ctx :test
                            (fn [payload k] (yrt.effect/raise ctx payload (fn [e] :unexpected)))
                            (fn []
                              (yrt.effect/try-finally ctx
                                (fn [] (yrt.effect/perform ctx :test :handler-error unhandled))
                                note :unused))))
                        (fn [e] e))]
           (conj [] resumed aborted thrown (cell/get log) (yrt.effect/handlers ctx)
                 (yrt.effect/perform ctx :no-handler 2 unhandled)))]
    (doseq [backend (keys h/backends), bundled? [true false]]
      (testing (str backend " bundled=" bundled?)
        (is (= [13 :aborted :handler-error
                [:resume-handler :resumed :resume-cleanup :abort-handler :abort-cleanup :handler-error]
                nil [:no-handler 2]]
               (run-runtime backend bundled? form)))
        (is (thrown? #?(:cljd Object :clj Exception :cljs js/Error)
              (run-runtime backend bundled?
                '(let [ctx (cell/new (yrt.effect/ctx nil 0 0 nil))
                       saved (cell/new nil)]
                   (do (yrt.effect/handle ctx :test
                         (fn [payload k] (do (cell/set! saved k) (k payload)))
                         (fn [] (yrt.effect/perform ctx :test 1 (fn [tag p] nil))))
                       ((cell/get saved) 2))))))))))


(deftest integer-policy-and-scanners
  (let [form
        '(let [ctx (cell/new (yrt.effect/ctx nil 0 0 nil))
               bit (yrt.effect/handle ctx :int/bit-limit
                     (fn [reason k] (k (conj [] :bit reason)))
                     (fn [] (yrt.int/check-limit ctx (integer/pow 2 100001))))
               digit (yrt.effect/handle ctx :int/digit-limit
                       (fn [reason k] (conj [] :digit reason))
                       (fn [] (yrt.int/check-limit ctx :yin.vm.integer/digit-limit)))
               float (yrt.effect/handle ctx :math/overflow
                       (fn [reason k] (conj [] :float reason))
                       (fn [] (yrt.int/check-limit ctx :yin.vm.integer/float-overflow)))
               cs [48 48 70 102 33]]
           (conj [] (yrt.int/check-limit ctx 19) bit digit float
                 (yrt.int/check-limit-with :yin.vm.integer/digit-limit
                   (fn [r] :wrong) (fn [r] :digit) (fn [r] :wrong))
                 (yrt.int/wrap-unsigned -1 256 255)
                 (yrt.int/wrap-unsigned -257 256 nil)
                 (yrt.int/wrap-signed 255 128 256)
                 (yrt.int/in-range? -128 -128 127)
                 (yrt.int/all-digits? cs 0 4 16)
                 (yrt.int/all-digits? cs 0 5 16)
                 (yrt.int/scan-digits cs 0 5 16 0)
                 (yrt.int/skip-zeros cs 0 5)))]
    (doseq [backend (keys h/backends), bundled? [true false]]
      (testing (str backend " bundled=" bundled?)
        (is (= [19 [:bit :yin.vm.integer/bit-limit]
                [:digit :yin.vm.integer/digit-limit]
                [:float :yin.vm.integer/float-overflow]
                :digit 255 255 -1 true true false 255 2]
               (run-runtime backend bundled? form)))
        (is (= :zero-division
               (try
                 (run-runtime backend bundled?
                   '(let [ctx (cell/new (yrt.effect/ctx nil 0 0 nil))]
                      (yrt.effect/try-value ctx
                        (fn [] (yrt.int/check-limit ctx (integer/floor-div-mod 1 0)))
                        (fn [error] :caught))))
                 (catch #?(:cljd Object :clj Exception :cljs :default) e
                   (::integer/reason (ex-data e))))))))))


(deftest clojure-runtime-parity
  (let [forms '[(defn get [m k] :shadowed)
                (defn + [a b] :shadowed-add)
                (defn <= [a b] :shadowed-lte)
                (defn assoc [m k v] :shadowed-assoc)
                (defn f [x]
                  (try (throw x)
                       (finally (cell/set! log (conj (cell/get log) :cleanup)))))
                (def log (cell/new []))
                [(get nil :missing) (+ 1 2) (<= 1 2) (assoc {} :k 1)
                 (try (f 42) (catch Exception e e)) (cell/get log)
                 (let [value 9 m {:k value}] (m :k))
                 (clojure.core/get (clojure.core/assoc {} :k 12) :k)
                 (map inc [1 2])
                 (do (cell/set! f {})
                     (try (f 43) (catch Exception e :refused)))]]
        opts (dissoc clj/unit-opts :linked-runtime)
        expected [:shadowed :shadowed-add :shadowed-lte :shadowed-assoc 42 [:cleanup] 9 12 '(2 3) :refused]
        bundled (clj/compile-program forms true opts)
        linked (u/then (u/sexp->uast '(require 'clj))
                       (u/app (u/v 'clj/run-main) (clj/lower-unit forms)))]
    (doseq [backend (keys h/backends)]
      (let [run #(vm/value (h/run-linked backend % {:modules registry :link-source @published}))]
        (is (= [:yin.k/ok expected] (run bundled)) (str backend))
        (is (= expected (run linked)) (str backend))))))


(deftest clojure-core-facade-and-cold-policy
  (let [forms '[[(try (get {} (fn [] 1))
                       (catch Exception e (:clj/policy e)))
                 (try (let [m {}] (m (fn [] 1)))
                      (catch Exception e (:clj/policy e)))
                 (try (let [k (keyword nil "a/b")] (k {}))
                      (catch Exception e (:clj/policy e)))
                 (try (rem 9 0)
                      (catch Exception e (:clj/policy e)))
                 (try (inc 9007199254740991)
                      (catch Exception e (:clj/policy e)))
                 (inc 1.5)
                 (str {:z 1 :a ["😀"]})
                 (let [f (fn [] 1)]
                   [(fn? f) (map? f) (coll? f) (set? f) (vector? f)
                    (seq? f) (sequential? f) (list? f) (reduced? f) (str f)])]]
        expected [:coll/unsupported-key :coll/unsupported-key :coll/unsupported-key
                  :math/div-zero :int/bit-limit
                  2.5 "{:a [\"😀\"], :z 1}" [true false false false false false false false false "#<fn>"]]
        modules (integer/register-integer-module registry {::integer/max-bits 53
                                                            ::integer/max-digits 4300})
        bundled (clj/compile-program forms true (dissoc clj/unit-opts :linked-runtime))
        linked (u/then (u/sexp->uast '(require 'clj))
                       (u/app (u/v 'clj/run-main) (clj/lower-unit forms)))
        facade (u/sexp->uast
                 '(do (require 'clojure.core)
                      (clj/init!)
                      (let [before (clojure.core/identity)
                            _ (clojure.core/init!)
                            f (clojure.core/identity)
                            _ (clojure.core/init!)]
                        (conj [] before
                              (data/content= f (clojure.core/export 'identity))
                              (clj/invoke-callable f [42] [])))))]
    (doseq [backend (keys h/backends)]
      (let [run #(vm/value (h/run-linked backend % {:modules modules :link-source @published}))]
        (is (= [:yin.k/ok expected] (run bundled)) (str backend))
        (is (= expected (run linked)) (str backend))
        (is (= [nil true 42] (run facade)) (str backend))))))


(def ^:private depth-fixture
  '(do (require 'clj)
       (let [ctx (do (clj/init!) (clj/rt-ctx))
             base-frame (yrt.effect/frame :boundary nil nil 9)
             top (yrt.effect/frame :effect nil base-frame 17)]
         (do (cell/set! ctx (yrt.effect/ctx top 99 31 :activation))
             (yrt.effect/pop-frame ctx top)
             (let [depth (get (cell/get ctx) :depth)]
               (do (yrt.effect/restore! ctx (yrt.effect/ctx base-frame 9 1 :restored))
                   (conj [] (data/nth top 4) (yrt.effect/frame-depth top) depth
                         (get (cell/get ctx) :depth) (get (cell/get ctx) :base)
                         (get (cell/get ctx) :frame))))))))


(deftest clojure-depth-and-live-base
  (doseq [backend (keys h/backends)]
    (is (= [17 2 17 9 31 :restored]
           (vm/value (h/run-linked backend (u/sexp->uast depth-fixture)
                                   {:modules registry :link-source @published})))
        (str backend))))


(deftest concurrent-clojure-contexts
  (doseq [backend (keys h/backends)]
    (let [task (fn [value]
                 (let [pair (link/make-pair)
                       ast (u/sexp->uast
                             (list 'do (list 'require (list 'quote 'clj))
                                   (list 'clj/run-main
                                         (list 'fn []
                                               (list 'clj/try
                                                     (list 'fn [] (list 'clj/throw value))
                                                     (list 'fn ['e] 'e))))))]
                   [((get h/backends backend) ast (h/composition registry pair)) pair]))
          tick (fn [[machine pair]]
                 (let [machine (if (vm/halted? machine) machine (vm/step machine))
                       pair (if (vm/blocked? machine)
                              (:pair (link/serve {:pair pair :source @published}))
                              pair)]
                   [machine pair]))
          final (loop [tasks [(task 41) (task 99)] fuel 5000]
                  (if (or (zero? fuel) (every? (comp vm/halted? first) tasks))
                    tasks
                    (recur (mapv tick tasks) (dec fuel))))]
      (is (= [41 99] (mapv (comp vm/value first) final)) (str backend)))))


(deftest provenance-doors-use-executable-addresses
  (let [opts (dissoc clj/unit-opts :linked-runtime)
        genuine (clj/compile-program '[(let [f 3] (f))] true opts)
        run (fn [backend ast]
              (vm/value (h/run-linked backend ast {:modules registry :link-source @published})))
        forged '(do (yin/def 'clj/invoke-callable
                       (fn [v] (cell/dsg__new-provenance v v)))
                    (clj/invoke-callable :forgery))]
    (doseq [backend (keys h/backends)]
      (testing (str backend)
        (is (= [:yin.k/error {:yang.call/reason :yang.call/not-callable
                              :yang.call/data {:yang.call/callee 3}}]
               (run backend genuine)))
        (doseq [direct [forged '(cell/dsg__provenance nil)]
                program [(u/then prelude/uast (u/sexp->uast direct))
                         (u/then (u/sexp->uast direct) prelude/uast)]]
          (is (= :private-provenance
                 (try
                   (run backend (u/mark-tails program))
                   (catch #?(:cljd Object :clj Exception :cljs :default) e
                     (:reason (ex-data e)))))))))))


(deftest callable-entry-checks-owner-and-binding
  (let [form
        '(let [ran (cell/new 0)
               body (fn [args]
                      (do (cell/set! ran (+ 1 (cell/get ran)))
                          (let [value (data/nth args 0)]
                            (if (= value :throw)
                              (clj/throw {:yang.call/reason :yang.call/not-callable})
                              value))))
               f (clj/make-callable {} body)
               g (clj/make-callable {} body)
               entry (yang.call/entry f)
               report (fn [c] c)
               invalid (entry (assoc {} :yang.call/raise report))
               mismatch (entry (yang.call/invocation g [] [] report))
               unsupported (entry (yang.call/invocation-with
                                    f [] [] report {:yang.call/binding-profile :unknown}))
               before (cell/get ran)
               normal (yang.call/invoke f
                        [{:yang.call/reason :yang.call/thrown :yang.call/value :data}]
                        [] report)
               thrown (yang.call/invoke f [:throw] [] report)]
           (conj [] (get invalid :yang.call/reason)
                 (get mismatch :yang.call/reason)
                 (= g (get (get mismatch :yang.call/data) :yang.call/callee))
                 (= f (get (get mismatch :yang.call/data) :yang.call/owner))
                 (get unsupported :yang.call/reason)
                 (get (get unsupported :yang.call/data) :yang.call/binding-profile)
                 before normal thrown (cell/get ran)
                 (clj/admitted-callable? f)
                 (clj/admitted-callable? {:type :cell-ref :id :fake})
                 (clj/admitted-callable? (yang.call/entry f))))
        expected [:yang.call/invalid-invocation :yang.call/invalid-invocation true true
                  :yang.call/unsupported-binding :unknown 0
                  {:yang.call/reason :yang.call/thrown :yang.call/value :data}
                  {:yang.call/reason :yang.call/thrown
                   :yang.call/value {:yang.call/reason :yang.call/not-callable}}
                  2 true false false]]
    (doseq [backend (keys h/backends), bundled? [true false]]
      (let [run (fn [form]
                  (vm/value
                    (h/run-linked backend
                      (u/then (if bundled? prelude/bundled-uast (u/sexp->uast '(require 'clj)))
                              (u/app (u/v 'clj/run-main) (u/lam [] (u/sexp->uast form))))
                      {:modules registry :link-source @published})))]
        (is (= expected (run form)) (str backend " bundled=" bundled?))
        (is (= :not-applicable
               (try
                 (run '(let [f (clj/make-callable {} (fn [args] :never))]
                         ((yang.call/entry f) {})))
                 (catch #?(:cljd Object :clj Exception :cljs :default) e
                   (:reason (ex-data e))))))))))
