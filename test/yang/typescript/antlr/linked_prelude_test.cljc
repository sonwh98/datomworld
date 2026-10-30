(ns yang.typescript.antlr.linked-prelude-test
  "T1 step S5: the JavaScript runtime profile and its hook prelude as the
   linker modules `js` and `jssp`. Both emit from the single definition
   lists their bundled twins load; the specs declare every free name of
   their trees; a linked run on the three vector VMs prints what the
   bundled run prints; the AST walker's link stays refused until L-b. The
   published modules and the linked runs are
   `yang.typescript.antlr.linked-harness`'s: one publication per process,
   served `:trusted`, so every test that publishes or links is slow."
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.stream :as stream]
    [dao.test-slow :as slow]
    [yang.safepoint :as safepoint]
    [yang.core.uast :as u]
    [yang.typescript.antlr.linked-harness :as h]
    [yang.typescript.antlr.lower :as lower]
    [yang.typescript.antlr.lower-portable-test :as portable]
    [yang.typescript.antlr.prelude :as prelude]
    [yang.typescript.antlr.prelude-parity-test :as parity]
    [yang.typescript.antlr.render :as render]
    [yang.typescript.antlr.safepoint :as hooks]
    [yin.vm :as vm]
    [yin.vm.data :as data]
    [yin.vm.encoder :as encoder]
    [yin.vm.engine :as engine]
    [yin.vm.module :as module]
    [yin.vm.test-utils :as tu]))


(defn- refusal-of
  "The ex-data of what `thunk` throws, or nil when it returns."
  [thunk]
  (try (thunk)
       nil
       (catch #?(:cljd Object :clj Exception :cljs :default) e
         (or (ex-data e) {}))))


(defn- def-key
  "The literal key of a `(yin/def k v)` application node, else nil."
  [node]
  (when (and (map? node)
             (= :application (:type node))
             (= 'yin/def (get-in node [:operator :name])))
    (get-in node [:operands 0 :value])))


;; =============================================================================
;; The module emitters
;; =============================================================================

(deftest jssp-module-uast-is-one-wide-application-test
  (let [ast hooks/module-uast
        operands (:operands ast)
        definitions (butlast operands)]
    (is (= :application (:type ast)))
    (is (= :lambda (:type (:operator ast))))
    (is (= (count operands) (count (:params (:operator ast)))))
    (is (nil? (get-in ast [:operator :body :value])))
    (is (= (u/sexp->uast '(require (quote js)))
           (dissoc (last operands) :tail?)))
    (testing "the placeholders come first, each the literal :js/uninit"
      (let [n (count hooks/runtime-keys)
            placeholders (take n definitions)]
        (is (= (mapv hooks/strip hooks/runtime-keys)
               (mapv def-key placeholders)))
        (is (every? #(= {:type :literal, :value :js/uninit}
                        (dissoc (get-in % [:operands 1]) :tail?))
                    placeholders))))
    (testing "then every definition, stripped, in definition order"
      (is (= (mapv (comp hooks/strip first) hooks/function-definitions)
             (mapv def-key (drop (count hooks/runtime-keys) definitions)))))
    (testing "no symbol of the module's own namespace survives stripping"
      (is (not-any? (fn [x] (and (symbol? x) (= "js.sp" (namespace x))))
                    (tree-seq coll? seq ast))))
    (testing "`js/*` references stay qualified: the base prelude's exports"
      (is (some (fn [x] (and (symbol? x) (= "js" (namespace x))))
                (tree-seq coll? seq ast))))))


(deftest jssp-module-exports-test
  (is (= (set (map (comp hooks/strip first) hooks/function-definitions))
         hooks/module-exports))
  (is (every? #(contains? hooks/module-exports %) '[attach! loop call]))
  (is (not-any? namespace hooks/module-exports))
  (is (not (contains? hooks/module-exports 'cursor)))
  (is (empty? (filter #(contains? vm/primitives %) hooks/module-exports))))


(deftest js-module-exports-stage0-entry-points-test
  (testing "the lowering's literal, for-in and JSON entry points are exported"
    (is (every? #(contains? prelude/module-exports %)
                '[new-object new-array literal-proto! for-in-start for-in-next
                  install-json! define-accessor-half! json-stringify json-parse]))))


(deftest jssp-module-spec-test
  (let [spec (hooks/module-spec (h/registry) :test/js-address)]
    (is (= 'jssp (:name spec) hooks/module-name))
    (is (= hooks/module-uast (:ast spec)))
    (is (= hooks/module-exports (:exports spec)))
    (is (= {'js :test/js-address} (:requires spec)))
    (testing "a js-qualified free name is covered by the requirement"
      (is (empty? (filter #(= "js" (namespace %)) (keys (:primitives spec))))))
    (testing "the qualified declarations are exactly the host names"
      (is (= hooks/host-names
             (set (filter namespace (keys (:primitives spec)))))))
    (testing "a free name no registry module supplies is refused"
      (let [no-cell (-> (module/empty-registry)
                        (data/register-data-module {::data/max-items 1048576})
                        (prelude/register-integer-module h/integer-limits))
            refusal (refusal-of #(hooks/module-spec no-cell :test/js-address))]
        (is (= :yang.typescript.antlr/undeclared-free
               (:yang.typescript.antlr/refusal refusal)))))))


(deftest js-module-spec-declares-every-free-name-test
  (let [call-address (:call-address @h/published)
        spec (prelude/module-spec (h/registry) call-address)]
    (is (= 'js (:name spec)))
    (is (= prelude/module-exports (:exports spec)))
    (is (= {'yang.call call-address} (:requires spec)))
    (testing "a registry without the data module is refused"
      (let [no-data (-> (module/empty-registry)
                        module/register-cell-module
                        (prelude/register-integer-module h/integer-limits))
            refusal (refusal-of #(prelude/module-spec no-data call-address))]
        (is (= :yang.typescript.antlr/undeclared-free
               (:yang.typescript.antlr/refusal refusal)))
        (is (= "data" (namespace (:name refusal))))))))


;; =============================================================================
;; Publication
;; =============================================================================

(defn- publication
  []
  (let [{:keys [result address]} @h/published
        links (:links result)]
    (testing "js publishes with no undeclared free name"
      (is (some? address))
      (doseq [f [:yin.semantic/code :yin.debruijn.code :yin.debruijn.register]]
        (is (= :ok (get-in links [f :status])) (str f))))
    (testing "the AST walker is refused :undeclared-free on a sibling read
              until L-b; L-b's landing flips this assertion"
      (let [walker (get links :yin.ast/code)]
        (is (= :refused (:status walker)))
        (is (= :undeclared-free (:reason walker)))
        (is (contains? prelude/module-exports (:name walker)))))
    (testing "jssp publishes over js"
      (let [{:keys [result]} @h/published-jssp]
        (doseq [f [:yin.semantic/code :yin.debruijn.code :yin.debruijn.register]]
          (is (= :ok (get-in (:links result) [f :status])) (str f)))))))


(deftest ^:slow publication-test
  (slow/guard "publication-test" publication))


;; =============================================================================
;; Linked runs equal bundled runs
;; =============================================================================

(def ^:private programs
  "Hand-built packets: name -> packet."
  {:log-literal (portable/program (portable/log-stmt (portable/num-expr "1"))),
   :var-and-log (portable/program
                  (portable/var-stmt "var" "x" (portable/num-expr "7"))
                  (portable/log-stmt (portable/name-expr "x"))),
   :let-in-tdz (portable/program
                 (portable/log-stmt (portable/name-expr "y"))
                 (portable/var-stmt "let" "y" (portable/num-expr "1")))})


(defn- bundled
  [ast]
  (render/output ((get parity/runners :semantic) ast)))


(defn- linked
  [k ast]
  (render/output (vm/value (h/run-linked k ast))))


(defn- linked-equals-bundled
  []
  (doseq [[nm pk] programs
          :let [expected (bundled (lower/lower-packet pk))
                ast (lower/lower-packet pk {:prelude :linked})]]
    (doseq [k (keys h/backends)]
      (is (= expected (linked k ast)) (str nm " " k)))))


(deftest ^:slow linked-equals-bundled-test
  (slow/guard "linked-equals-bundled-test" linked-equals-bundled))


;; =============================================================================
;; The hook prelude linked: signals through the module boundary
;; =============================================================================

(def ^:private while-loop
  "`while (cond) { }` over singleExpression form `cond`."
  (fn [cond-form]
    [:iterationStatement
     ["While" "while"] ["OpenParen" "("]
     [:expressionSequence cond-form]
     ["CloseParen" ")"]
     [:statement [:block ["OpenBrace" "{"] ["CloseBrace" "}"]]]]))


(def ^:private true-expr
  [:singleExpression [:literal ["BooleanLiteral" "true"]]])


(def ^:private false-expr
  [:singleExpression [:literal ["BooleanLiteral" "false"]]])


(def ^:private spin
  (portable/program (while-loop true-expr)))


(def ^:private caught
  (portable/program
    [:tryStatement
     ["Try" "try"]
     [:block ["OpenBrace" "{"]
      [:statementList [:statement (while-loop true-expr)]]
      ["CloseBrace" "}"]]
     [:catchProduction
      ["Catch" "catch"] ["OpenParen" "("]
      [:identifier ["Identifier" "e"]]
      ["CloseParen" ")"]
      [:block ["OpenBrace" "{"]
       [:statementList [:statement (portable/log-stmt (portable/num-expr "9"))]]
       ["CloseBrace" "}"]]]]))


(def ^:private spin-then-log
  (portable/program (while-loop false-expr)
                    (portable/log-stmt (portable/num-expr "5"))))


(defn- envelope
  [pk]
  (let [[_ [[port e]]] (lower/lower-transform {:prelude :linked} pk)]
    (assert (= :program port))
    e))


(defn- derived-ast
  "`A'` for `pk` under the linked profile."
  [pk]
  (let [projected (encoder/project (envelope pk))
        {:keys [envelope]} (safepoint/derive-envelope projected
                                                      hooks/linked-profile
                                                      :test/derived)]
    (vm/semantic-bytecode->ast (nth (:yin/batch envelope) (:yin/root envelope)))))


(defn- with-signals
  "A linked `prep` handing the task a signal stream holding `signals`."
  [signals]
  (fn [task]
    (let [handle (tu/new-stream 8)]
      (doseq [s signals] (stream/append! handle s))
      (let [[ref task] (engine/attach-resource task handle)]
        (assoc-in task [:store hooks/signals-key] ref)))))


(defn- outcome
  [task]
  [(vm/halted? task) (vm/blocked? task) (render/output (vm/value task))])


(defn- on-every-vm
  [ast signals]
  (into {}
        (map (fn [k]
               [k (try (outcome (h/run-linked k ast
                                              {:link-source (h/safepoint-source),
                                               :prep (with-signals signals)}))
                       (catch #?(:cljd Object :clj Exception :cljs :default) e
                         [:thrown (ex-message e)]))]))
        (keys h/backends)))


(defn- every=
  [expected results]
  (doseq [[k result] results]
    (is (= expected result) (str k))))


(defn- linked-interrupt
  []
  (testing "while (true) with one signal ends Interrupt, uncaught, through
            the module boundary"
    (every= [true false {:js/out [], :js/uncaught "Interrupt: interrupted"}]
            (on-every-vm (hooks/linked-program (derived-ast spin)) [2])))
  (testing "a try/catch around the loop does not catch it"
    (every= [true false {:js/out [], :js/uncaught "Interrupt: interrupted"}]
            (on-every-vm (hooks/linked-program (derived-ast caught)) [2]))))


(deftest ^:slow linked-interrupt-test
  (slow/guard "linked-interrupt-test" linked-interrupt))


(defn- linked-no-park
  []
  (testing "with no signal pending the derived program finishes without
            blocking and prints what the naive one prints"
    (every= [true false {:js/out ["5"], :js/uncaught nil}]
            (on-every-vm (hooks/linked-program (derived-ast spin-then-log))
                         []))))


(deftest ^:slow linked-no-park-test
  (slow/guard "linked-no-park-test" linked-no-park))


(defn- fail-closed
  []
  (testing "the derived linked program with no jssp binding names the hook"
    (doseq [[k result] (on-every-vm (derived-ast spin) [])]
      (is (= :thrown (first result)) (str k))
      (is (re-find #"jssp/loop" (second result)) (str k)))))


(deftest ^:slow linked-fail-closed-test
  (slow/guard "linked-fail-closed-test" fail-closed))
