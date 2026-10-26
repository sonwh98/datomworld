(ns yang.typescript.antlr.safepoint-test
  "T1 step S5, bundled profile on the JVM from source: the lowering's site
   marks reach the side table, the `yang.safepoint` stage inserts the
   `js.sp` hooks, a no-op hook prelude leaves the program's output as the
   naive one prints it, and a pending signal ends a `while (true)` loop with
   the guest value `Interrupt` uncaught: a `try`/`catch` around the loop
   does not catch it. The linked twin of these runs is in
   `yang.typescript.antlr.linked-prelude-test`."
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.stream :as stream]
    [dao.test-slow :as slow]
    [yang.safepoint :as safepoint]
    [yang.typescript.antlr.cst :as cst]
    [yang.typescript.antlr.lower :as lower]
    [yang.typescript.antlr.prelude :as prelude]
    [yang.typescript.antlr.render :as render]
    [yang.typescript.antlr.safepoint :as hooks]
    [yin.vm :as vm]
    [yin.vm.data :as data]
    [yin.vm.debruijn.register :as rvm]
    [yin.vm.debruijn.stack :as dvm]
    [yin.vm.encoder :as encoder]
    [yin.vm.engine :as engine]
    [yin.vm.integer :as integer]
    [yin.vm.module :as module]
    [yin.vm.semantic :as semantic]
    [yin.vm.semantic.derive :as derive]
    [yin.vm.semantic.linearize :as sr-linearize]
    [yin.vm.test-utils :as tu]))


(def ^:private worker (cst/make-worker :ts))


(defn- parse
  [src]
  (let [pk (cst/parse-source worker [:unit 0] src)]
    (is (= :yang.cst/ok (:yang.cst/outcome pk)) src)
    pk))


(def ^:private programs
  {:while-true "while (true) { }",
   :for-in (str "const o = {a: 1, b: 2}; let t = ''; "
                "for (const k in o) { t += k; } console.log(t);"),
   :for-in-forever "const o = {a: 1}; while (true) { for (const k in o) { } }",
   :caught "try { while (true) { } } catch (e) { console.log('caught'); }",
   :def-and-while (str "function f(n) { return n + 1; } var i = 0; "
                       "while (i < 5) { i = f(i); } console.log(i);")})


(def ^:private opts
  {:make-stream tu/make-stream,
   :capability-secret tu/secret,
   :primitives vm/primitives,
   :modules (-> (module/empty-registry)
                module/register-cell-module
                (data/register-data-module {::data/max-items 1048576})
                (prelude/register-integer-module
                  {::integer/max-bits 100000, ::integer/max-digits 4300})
                module/register-stream-module
                prelude/admit)})


(def ^:private load-semantic-ast
  (vm/fresh-code-loader semantic/load-ast vm/ast-contract))


(def ^:private runners
  "Each takes an AST and `prep`, applied to the VM before it runs."
  {:ast-walker (fn [ast prep] (vm/eval (prep (tu/create-vm opts)) ast)),
   :semantic (fn [ast prep]
               (vm/run (load-semantic-ast (prep (semantic/create-vm opts))
                                          (vm/ast->datoms ast)))),
   :stack (fn [ast prep]
            (vm/run (prep (dvm/create-vm
                            (:image (derive/stack-image
                                      (:vector (sr-linearize/project ast))))
                            (assoc opts :contract vm/stack-contract))))),
   :register (fn [ast prep]
               (vm/run (prep (rvm/create-vm
                               (derive/register-image
                                 (:vector (sr-linearize/project ast)))
                               (assoc opts :contract vm/register-contract)))))})


(defn- with-signals
  "A `prep` handing the task a signal stream holding `signals`."
  [signals]
  (fn [vm]
    (let [handle (tu/new-stream 8)]
      (doseq [s signals] (stream/append! handle s))
      (let [[ref vm] (engine/attach-resource vm handle)]
        (assoc-in vm [:store hooks/signals-key] ref)))))


(defn- outcome
  "A finished run as `[halted? blocked? rendered-value]`."
  [vm]
  [(vm/halted? vm) (vm/blocked? vm) (render/output (vm/value vm))])


(defn- on-every-vm
  [ast prep]
  (into {}
        (map (fn [[k run]]
               [k (try (outcome (run ast prep))
                       (catch Exception e [:thrown (ex-message e)]))]))
        runners))


(defn- envelope
  [pk]
  (let [[_ [[port e]]] (lower/lower-transform {} pk)]
    (assert (= :program port))
    e))


(defn- derive*
  [pk profile]
  (let [projected (encoder/project (envelope pk))
        {:keys [envelope record]} (safepoint/derive-envelope projected profile
                                                             :test/derived)]
    {:projected projected, :derived envelope, :record record}))


(defn- tree-of
  [e]
  (nth (:yin/batch e) (:yin/root e)))


(defn- derived-ast
  [pk profile]
  (vm/semantic-bytecode->ast (tree-of (:derived (derive* pk profile)))))


(defn- every=
  [expected results]
  (doseq [[k result] results]
    (is (= expected result) (str k))))


(deftest marks-reach-the-side-table-test
  (let [{:keys [projected]} (derive* (parse (:def-and-while programs))
                                     hooks/profile)
        marks (map #(nth % 4)
                   (filter #(= :yang/site (nth %  3))
                           (:yin/frontend-metadata projected)))
        kinds (frequencies (map #(if (vector? %) (first %) %) marks))]
    (is (= {:call 1, :loop 1} kinds))
    (is (some #(and (vector? %) (= "f" (second %))) marks)
        "the function's mark carries its name")
    (testing "every derived row validates"
      (is (nil? (vm/validate-rows
                  (tree-of (:derived (derive* (parse (:def-and-while programs))
                                              hooks/profile)))))))))


(deftest for-in-loop-site-test
  (let [{:keys [projected]} (derive* (parse (:for-in programs)) hooks/profile)
        marks (map #(nth % 4)
                   (filter #(= :yang/site (nth % 3))
                           (:yin/frontend-metadata projected)))
        kinds (frequencies (map #(if (vector? %) (first %) %) marks))]
    (is (= 1 (:loop kinds)) "the for-in loop lambda carries one :loop site")))


(deftest insertion-is-deterministic-test
  (let [pk (parse (:def-and-while programs))]
    (is (= (derive* pk hooks/profile) (derive* pk hooks/profile)))))


(defn- interrupt
  []
  (testing "while (true) with one signal appended ends Interrupt, uncaught"
    (every= [true false {:js/out [], :js/uncaught "Interrupt: interrupted"}]
            (on-every-vm (hooks/program hooks/uast
                                        (derived-ast (parse (:while-true programs))
                                                     hooks/profile))
                         (with-signals [2]))))
  (testing "a try/catch around the loop does not catch it"
    (every= [true false {:js/out [], :js/uncaught "Interrupt: interrupted"}]
            (on-every-vm (hooks/program hooks/uast
                                        (derived-ast (parse (:caught programs))
                                                     hooks/profile))
                         (with-signals [2])))))


(deftest ^:slow interrupt-test
  (slow/guard "interrupt-test" interrupt))


(defn- no-park
  []
  (testing "with an empty signal stream the derived program finishes without
            blocking and prints what the naive one prints"
    (every= [true false {:js/out ["5"], :js/uncaught nil}]
            (on-every-vm (hooks/program hooks/uast
                                        (derived-ast (parse (:def-and-while programs))
                                                     hooks/profile))
                         (with-signals [])))))


(defn- for-in-interrupt
  []
  (testing "an interrupt inside a for-in loop ends Interrupt, uncaught"
    (every= [true false {:js/out [], :js/uncaught "Interrupt: interrupted"}]
            (on-every-vm (hooks/program hooks/uast
                                        (derived-ast (parse (:for-in-forever programs))
                                                     hooks/profile))
                         (with-signals [2])))))


(deftest ^:slow for-in-interrupt-test
  (slow/guard "for-in-interrupt-test" for-in-interrupt))


(deftest ^:slow no-park-test
  (slow/guard "no-park-test" no-park))


(defn- transparency
  []
  (testing "under no-op hooks the derived program prints what the naive
            program prints"
    (let [pk (parse (:def-and-while programs))
          naive (on-every-vm (tree-of (envelope pk)) (with-signals []))
          noop (on-every-vm (hooks/program hooks/noop-uast
                                           (derived-ast pk hooks/profile))
                            (with-signals []))]
      (every= [true false {:js/out ["5"], :js/uncaught nil}] naive)
      (is (= naive noop)))))


(deftest ^:slow transparency-test
  (slow/guard "transparency-test" transparency))
