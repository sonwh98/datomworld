(ns yang.typescript.antlr.prelude-module-test
  "The `js` prelude as one definition list with two emitters. The module
   emitter strips the namespace `js/`, so a definition named like a
   primitive would shadow it inside the module, and a binder named like a
   definition would shadow that definition inside the function that binds
   it; neither may exist. The module's free names are all declared by the
   host registry."
  (:require [clojure.test :refer [deftest is testing]]
            [yang.core.uast :as u]
            [yang.typescript.antlr.prelude :as prelude]
            [yin.vm :as vm]
            [yin.vm.data :as data]
            [yin.vm.integer :as integer]
            [yin.vm.module :as module]))


(def ^:private registry
  (-> (module/empty-registry)
      module/register-cell-module
      (data/register-data-module {::data/max-items 1048576})
      (prelude/register-integer-module {::integer/max-bits 100000,
                                        ::integer/max-digits 4300})
      prelude/admit))


(defn- binders
  "Every lambda parameter in the map AST `ast`."
  [ast]
  (into #{}
        (comp (filter #(and (map? %) (= :lambda (:type %)))) (mapcat :params))
        (tree-seq coll? seq ast)))


(deftest no-definition-shadows-a-primitive-test
  (let [exported (set (map name (filter #(= "js" (namespace %))
                                        (map first prelude/definitions))))]
    (is (empty? (filter #(contains? vm/primitives (symbol %)) exported)))
    (is (not-any? #{"fn" "if" "do" "let" "quote" "yin"} exported))))


(deftest no_binder_shadows_a_definition_test
  (let [defined (set (map #(symbol (name %))
                          (filter #(= "js" (namespace %))
                                  (map first prelude/definitions))))
        bound (binders (u/sexp->uast
                         (cons 'do (map second prelude/definitions))))]
    (is (= #{} (set (filter defined bound))))))


(deftest module-spec-declares-every-free-name-test
  (testing "a registry with the host modules supplies the module's free names"
    (let [spec (prelude/module-spec registry)]
      (is (= 'js (:name spec)))
      (is (= prelude/module-exports (:exports spec)))
      (is (contains? (:exports spec) 'init!))
      (is (contains? (:exports spec) 'get-prop)))))
