(ns yang.python.antlr.c3-gate-parser-test
  "The C3 gate's programs from source, through the JVM parser (slice S7):
   each packet in `c3-programs` is what the parser makes of its
   docstring, each docstring is the c3-corpus-v1 source of the same name,
   and each source prints its corpus stdout on every VM, naive and under
   no-op hooks."
  (:require
    [clojure.test :refer [deftest is testing]]
    [yang.python.antlr.c3-gate-test :as gate]
    [yang.python.antlr.c3-programs :as programs]
    [yang.python.antlr.e2e-test :as e2e]
    [yang.python.antlr.parser :as parser]
    [yang.python.antlr.prelude :as prelude]
    [yang.python.antlr.safepoint :as hooks]
    [yin.vm.data :as data]
    [yin.vm.integer :as integer]
    [yin.vm.module :as module]))


(def ^:private program-vars
  "The program defs, in `programs/programs` order."
  [#'programs/promotion #'programs/demotion #'programs/numeric-keys
   #'programs/floor-division #'programs/shifts #'programs/power
   #'programs/conversions #'programs/signed-zero #'programs/limits
   #'programs/small-profile])


(defn- node-shape
  [node]
  (select-keys node [:id :kind :type :text :rule :children]))


(deftest packets-are-the-parsers-and-the-corpus-test
  (testing "each packet is the parser's for its docstring, and each
            docstring is the corpus source of the same name"
    (is (= (count programs/programs) (count program-vars)))
    (doseq [[[name pk] v] (map vector programs/programs program-vars)]
      (is (identical? pk @v) name)
      (is (= (map node-shape
                  (:yang.cst/nodes (parser/parse-source (:doc (meta v)))))
             (map node-shape (:yang.cst/nodes pk)))
          name)
      (is (= (:source (gate/program name)) (:doc (meta v))) name))))


(defn- small-registry
  []
  (-> (module/empty-registry)
      module/register-cell-module
      (data/register-data-module {::data/max-items 1048576})
      (prelude/register-integer-module
        {::integer/max-bits 60, ::integer/max-digits 5})
      prelude/admit))


(defn- small-every-vm=
  "`e2e/every-vm=` under the `small` composition."
  [expected source]
  (doseq [[label opts] [["naive" {}] ["no-op hooks" {:hooks hooks/noop-uast}]]]
    (let [results (e2e/run-python (small-registry) source opts)]
      (is (not (contains? results :diagnostics)) (pr-str results))
      (doseq [k [:ast-walker :semantic :stack :register]]
        (is (= expected (get results k)) (str label " " k))))))


(deftest ^:slow c3-sources-on-every-vm-test
  (doseq [{:keys [name profile source stdout]} (gate/read-corpus)]
    (testing name
      ((if (= "small" profile) small-every-vm= e2e/every-vm=)
       {:py/out stdout, :py/exception nil}
       source))))
