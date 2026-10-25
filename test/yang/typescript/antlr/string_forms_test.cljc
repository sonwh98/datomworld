(ns yang.typescript.antlr.string-forms-test
  "T1 step S8: the two-form string gate (design 3.4, plan acceptance items 4
   to 6) on every host. A `\"\\uD800\"` literal lowers to the `:js/units`
   form, projects to rows, decodes and runs on all four VMs reading back as
   `:js/units`; indexing and `.slice(0, 1)` of a surrogate pair give
   `:js/units`, `.charCodeAt` and `.length` count units, and concatenating
   the halves yields `:js/str` again; a continuation and a lifted slice
   holding `:js/units` keep their bytes through `engine/lift-slice` and a
   receiving task."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.test-slow :as slow]
            [yang.core.uast :as u]
            [yang.typescript.antlr.lower :as lower]
            [yang.typescript.antlr.lower-portable-test :as portable]
            [yang.typescript.antlr.prelude :as prelude]
            [yang.typescript.antlr.prelude-parity-test :as parity]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as walker]
            [yin.vm.data :as data]
            [yin.vm.debruijn.register :as rvm]
            [yin.vm.debruijn.stack :as dvm]
            [yin.vm.engine :as engine]
            [yin.vm.integer :as integer]
            [yin.vm.module :as module]
            [yin.vm.semantic :as semantic]
            [yin.vm.semantic.derive :as derive]
            [yin.vm.semantic.linearize :as sr-linearize]
            [yin.vm.test-utils :as tu]
            [yin.vm.values :as values]))


(def ^:private lone
  "The packet of `console.log(\"\\uD800\")`."
  (portable/program
    (portable/log-stmt
      [:singleExpression [:literal ["StringLiteral" "\"\\uD800\""]]])))


(def ^:private pair
  "The packet of `console.log(\"\\uD83D\\uDE00\")`."
  (portable/program
    (portable/log-stmt
      [:singleExpression
       [:literal ["StringLiteral" "\"\\uD83D\\uDE00\""]]])))


(defn- literals
  "Every literal value in `tree`."
  [tree]
  (into []
        (comp (filter #(and (map? %) (= :literal (:type %))))
              (map :value))
        (tree-seq coll? seq tree)))


(deftest literal-forms-test
  (testing "a lone surrogate literal lowers to the :js/units form"
    (is (some #{{:js/units [55296]}} (literals (lower/lower-module-body lone)))))
  (testing "a well-formed pair lowers to the :js/str form"
    (is (some #{{:js/str "😀"}} (literals (lower/lower-module-body pair))))
    (is (not-any? #(contains? % :js/units)
                  (filter map? (literals (lower/lower-module-body pair)))))))


(defn- through-rows
  "`ast` projected to rows, canonically encoded, decoded and read back as a
   program."
  [ast]
  (vm/semantic-bytecode->ast
    (cbor/decode (jing/canonical-bytes (vm/ast->semantic-bytecode ast)))))


(defn- lone-literal-on-every-vm
  []
  (let [ast (lower/lower-packet lone)
        decoded (through-rows ast)]
    (testing "projecting, encoding and decoding keeps the literal and the
              program's address"
      (is (some #{{:js/units [55296]}} (literals decoded)))
      (is (= (jing/segment-key (vm/ast->semantic-bytecode ast))
             (jing/segment-key (vm/ast->semantic-bytecode decoded)))))
    (testing "the program as lowered and as decoded logs :js/units"
      (doseq [program [ast decoded]
              [k run] parity/runners]
        (is (= {:js/out [[{:js/units [55296]}]], :js/uncaught nil}
               (run program))
            (str k))))))


(deftest ^:slow lone-literal-on-every-vm-test
  (slow/guard "lone-literal-on-every-vm-test" lone-literal-on-every-vm))


(def ^:private emoji {:js/str "😀"})


(defn- call-method
  [recv method & args]
  (list 'js/call
        (list 'js/get-value recv {:js/str method})
        recv
        (reduce (fn [acc a] (list 'js/vconj acc a)) [] args)))


(def ^:private cases
  "[form expected]: the acceptance string items, over the pair U+1F600."
  [[(list 'js/get-value emoji {:js/str "length"}) {:n 2}]
   [(list 'js/get-value {:js/units [55296]} {:js/str "length"}) {:n 1}]
   [(list 'js/get-value emoji {:js/str "0"}) {:js/units [55357]}]
   [(list 'js/get-value emoji {:js/str "1"}) {:js/units [56832]}]
   [(call-method emoji "slice" {:n 0} {:n 1}) {:js/units [55357]}]
   [(call-method emoji "slice" {:n 1}) {:js/units [56832]}]
   [(call-method emoji "charCodeAt" {:n 0}) {:n 55357}]
   [(call-method emoji "charCodeAt" {:n 1}) {:n 56832}]
   [(call-method emoji "codePointAt" {:n 0}) {:n 128512}]
   [(list 'js/str-concat
          (call-method emoji "slice" {:n 0} {:n 1})
          (call-method emoji "slice" {:n 1}))
    emoji]
   [(list 'js/strict-eq {:js/units [55296]} {:js/units [55296]}) true]
   [(list 'js/strict-eq {:js/units [55296]} {:js/units [56320]}) false]])


(defn- string-items-on-every-vm
  []
  (let [form (parity/program-form
               (reduce (fn [acc [f _]] (list 'js/vconj acc f)) [] cases))
        expected (mapv (fn [[_ e]] (parity/expected-value e)) cases)]
    (doseq [[k result] (parity/run-with-prelude prelude/uast form)]
      (testing (str k)
        (if (and (vector? result) (= (count expected) (count result)))
          (doseq [[i exp act] (map vector (range) expected result)]
            (is (cbor/content= exp act)
                (str (pr-str (first (nth cases i))) " => " (pr-str act)
                     ", expected " (pr-str exp))))
          (is (nil? result) "a vector of results"))))))


(deftest ^:slow string-items-on-every-vm-test
  (slow/guard "string-items-on-every-vm-test" string-items-on-every-vm))


;; =============================================================================
;; Carrier lifecycle: lift and a receiving task
;; =============================================================================

(defn- b64
  [v]
  (jing/bytes->base64 (jing/canonical-bytes v)))


(defn- unit-vectors
  "The unit vectors `x` carries, as a `:js/units` map or as the lifted
   carrier's literal entries `[:js/units units]`."
  [x]
  (set (keep (fn [n]
               (cond (and (map? n) (contains? n :js/units)) (:js/units n)
                     (and (sequential? n) (= :js/units (first n))) (second n)))
             (tree-seq coll? seq x))))


(def ^:private units-values
  "Values holding :js/units the lifecycle must keep byte for byte."
  [{:js/units [55296]}
   {:js/units [97 55357]}
   [{:js/units [56832]} {:js/str "😀"} {:js/units [0 65535]}]])


(deftest lift-keeps-units-test
  (let [names (mapv #(symbol (str "x" %)) (range (count units-values)))
        child {:store (zipmap names units-values)}
        lifted (engine/lift-slice child :segment/own names)]
    (doseq [[n v] (map vector names units-values)]
      (is (= (unit-vectors v) (unit-vectors (get-in lifted [:slice n])))
          (str n))
      (is (seq (unit-vectors (get-in lifted [:slice n]))) (str n)))
    (is (= {} (:cells lifted)) "no cell")))


(def ^:private opts
  {:make-stream tu/make-stream,
   :capability-secret tu/secret,
   :primitives vm/primitives,
   :modules (-> (module/empty-registry)
                module/register-cell-module
                (data/register-data-module {::data/max-items 1048576})
                (prelude/register-integer-module {::integer/max-bits 100000,
                                                  ::integer/max-digits 4300})
                prelude/admit)})


(def ^:private kernels
  "Per VM: compile an AST to the image the kernel loads, and load it."
  {:ast-walker {:image vm/ast->semantic-bytecode,
                :load (fn [image o]
                        (walker/vm-load-rows (walker/create-vm o) image
                                             vm/ast-contract))},
   :semantic {:image (fn [ast]
                       (:vector (sr-linearize/project-rows
                                  (vm/ast->semantic-bytecode ast)))),
              :load (fn [image o]
                      (semantic/load-vector (semantic/create-vm o) image
                                            vm/semantic-contract))},
   :stack {:image (fn [ast]
                    (:image (derive/stack-image
                              (:vector (sr-linearize/project ast))))),
           :load (fn [image o]
                   (dvm/create-vm image
                                  (assoc o :contract vm/stack-contract)))},
   :register {:image (fn [ast]
                       (derive/register-image
                         (:vector (sr-linearize/project ast)))),
              :load (fn [image o]
                      (rvm/create-vm image
                                     (assoc o :contract
                                            vm/register-contract)))}})


(defn- define
  [sym val]
  (u/app (u/v 'yin/def) (u/lit sym) val))


(defn- units-in
  "The `:js/units` maps in `x`."
  [x]
  (filter #(and (map? %) (contains? % :js/units))
          (tree-seq coll? seq x)))


(defn- continuation-and-closure
  []
  (testing "a continuation whose environment holds :js/units: the machine
            payload admits it and keeps its bytes (a reified continuation
            does not lift: UCF refuses it as :non-canonicalizable)"
    (doseq [[k {:keys [image load]}] kernels]
      (let [capture-in-env (u/app (u/lam '[c] {:type :vm/current-continuation})
                                  (u/lit units-values))
            a (vm/run (load (image (define 'k capture-in-env)) opts))
            c (get (:store a) 'k)]
        (is (values/continuation? c) (str k))
        (is (vm/machine-data? c) (str k))
        (is (= (set (map b64 (units-in units-values)))
               (set (map b64 (units-in (values/payload c)))))
            (str k)))))
  (testing "a closure whose environment holds :js/units: a lift keeps its
            bytes, and the closure lowered into another task returns them
            unchanged, on every VM"
    (let [task-a (assoc opts :capability-secret "task-a")
          task-b (assoc opts :capability-secret "task-b")
          module-ast (define 'f (u/app (u/lam '[c] (u/lam [] (u/v 'c)))
                                       (u/lit units-values)))]
      (doseq [[k {:keys [image load]}] kernels]
        (let [module-image (image module-ast)
              a (vm/run (load module-image task-a))
              f (get (:store a) 'f)]
          (is (values/closure? f) (str k))
          (is (vm/machine-data? f) (str k))
          (let [lifted (engine/lift-slice a :segment/own ['f])]
            (is (= (unit-vectors units-values) (unit-vectors lifted))
                (str k))
            (let [b (load (image (u/app (u/v 'f))) task-b)
                  received (engine/receive-module
                             b 'm (assoc lifted :images
                                         {(module/image-identity
                                            a module-image)
                                          module-image}))
                  b (assoc-in received [:store 'f]
                              (get-in (module/resolve-module
                                        (:modules received) 'm)
                                      [:bindings 'f]))]
              (is (= (b64 units-values) (b64 (vm/value (vm/run b))))
                  (str k)))))))))


(deftest ^:slow continuation-and-closure-test
  (slow/guard "continuation-and-closure-test" continuation-and-closure))
