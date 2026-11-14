(ns yang.clojure.donor-link-test
  (:require [clojure.test :refer [deftest is]]
            [yang.clojure :as clj]
            [yang.clojure.prelude :as prelude]
            [yang.core.module :as module]
            [yang.core.prelude :as core]
            [yang.core.uast :as u]
            [yang.typescript.antlr.linked-harness :as h]
            [yin.vm :as vm]
            [yin.vm.linker.interpreter :as link]))


(deftest linked-donor-uses-runtime-context
  (let [{:keys [store call-address]} @h/published
        registry (prelude/admit (h/registry))
        runtime (h/publish-guest-module! (prelude/module-spec registry call-address))
        unit (clj/lower-unit '[(try (throw 42) (catch Exception e e))])
        ast (u/annotate-call-sites
              (module/module-tree [(module/require-node 'clj) (u/def! 'body unit)]))
        spec (module/module-spec :test/refusal :test/undeclared registry
                                 {:name 'test.clj-donor :ast ast :exports '#{body}
                                  :runtime-name 'clj :runtime-address (:address runtime)
                                  :deps {'yang.call call-address}})
        donor (h/publish-guest-module! spec)
        caller-ast (u/annotate-call-sites
                     (module/module-tree
                       [(module/require-node 'clj)
                        (module/require-node 'test.clj-donor)
                        (u/def! 'run (u/sexp->uast
                                       '(fn [] (clj/run-main test.clj-donor/body))))]))
        caller (h/publish-guest-module!
                 (module/module-spec :test/refusal :test/undeclared registry
                                     {:name 'test.caller :ast caller-ast :exports '#{run}
                                      :runtime-name 'clj :runtime-address (:address runtime)
                                      :deps {'test.clj-donor (:address donor)}}))
        source (link/composition
                 {:content-store store :derivation :trusted
                  :name-env {'clj (:address runtime) 'yang.call call-address
                             'test.clj-donor (:address donor) 'test.caller (:address caller)}})
        program (u/sexp->uast '(do (require 'test.caller) (test.caller/run)))]
    (is (contains? prelude/module-exports 'rt-ctx))
    (is (some? (:address donor)) (pr-str donor))
    (is (some? (:address caller)) (pr-str caller))
    (is (not (contains? (core/free-names unit) 'clj.rt/ctx)))
    (is (contains? (core/free-names unit) 'clj/try))
    (doseq [backend [:semantic :stack :register]]
      (is (= 42 (vm/value (h/run-linked backend program {:modules registry :link-source source})))
          (str backend)))))
