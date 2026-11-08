(ns yang.typescript.antlr.source-call-test
  "S11: real source calls through the shared convention (§8.10.11).
   The composition publishes the separately lowered PHP body, runs it, and
   projects its resolved functions into the JS global namespace. Neither
   frontend's call nodes or function bodies are rewritten."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [dao.test-slow :as slow]
    [yang.core.module :as core-module]
    [yang.core.uast :as u]
    [yang.php.antlr.lower :as php-lower]
    [yang.php.antlr.portable-harness :as php-h]
    [yang.php.antlr.prelude :as php-prelude]
    [yang.typescript.antlr.linked-harness :as h]
    [yang.typescript.antlr.lower :as lower]
    [yang.typescript.antlr.prelude :as prelude]
    [yang.typescript.antlr.prelude-parity-test :as parity]
    [yang.typescript.antlr.render :as render]
    [yang.typescript.antlr.source-call-fixtures :as fixtures]
    [yin.vm :as vm]))


(def ^:private published-php
  (delay (h/publish-guest-module!
           (php-prelude/module-spec (h/registry)
                                    (:call-address @h/published)))))


(defn- source-module
  "An ordinary module exporting the unmodified lowered source body."
  [name packet]
  (core-module/module-spec
    :yang.typescript.antlr/refusal :yang.typescript.antlr/undeclared-free
    (h/registry)
    {:name name,
     :ast (core-module/module-tree
            [(core-module/require-node 'php)
             (u/def! 'body (u/lam [] (php-lower/lower-module-body packet)))]),
     :exports '#{body},
     :runtime-name 'php,
     :runtime-address (:address @published-php)}))


(defn- projection
  "Namespace composition only: preserve each resolved function cell."
  [names]
  (u/sexp->uast
    (cons 'do
          (map (fn [name]
                 (list 'js/define-global! name (list 'php/resolve-fn name name)))
               names))))


(defn- check-source
  [{:keys [module php-form functions ts-form expected]}]
  (let [expected {:js/out (str/split-lines expected), :js/uncaught nil}
        php-packet (php-h/packet php-form)
        ts-packet (php-h/packet ts-form)
        project (projection functions)
        bundled (u/mark-tails
                  (u/seq-nodes [(php-lower/lower-packet php-packet)
                                prelude/uast project
                                (u/app (u/v 'js/run-main) (lower/lower-unit ts-packet))]))]
    (testing "bundled source on every VM"
      (doseq [[backend run] parity/runners]
        (is (= expected (render/output (run bundled))) (str backend))))
    (testing "published PHP source and linked JS on each admitted VM"
      (let [donor (h/publish-guest-module! (source-module module php-packet))
            source (h/source {'yang.call (:call-address @h/published),
                              'js (:address @h/published),
                              'php (:address @published-php),
                              module (:address donor)})
            ast (u/mark-tails
                  (u/seq-nodes
                    [(core-module/require-node module)
                     (u/sexp->uast '(php/init!))
                     (u/app (u/v 'php/run-main) (u/v (symbol (str module) "body")))
                     (core-module/require-node 'yang.call)
                     (core-module/require-node 'js)
                     (u/sexp->uast '(js/init!))
                     project
                     (lower/lower-packet ts-packet {:prelude :linked})]))]
        (is (some? (:address donor)))
        (doseq [backend (keys h/backends)]
          (is (= :ok (get-in donor [:links (case backend
                                             :semantic :yin.semantic/code
                                             :stack :yin.debruijn.code
                                             :register :yin.debruijn.register)
                                    :status]))
              (str backend " source publication"))
          (is (= expected
                 (render/output
                   (vm/value (h/run-linked backend ast {:link-source source}))))
              (str backend)))))))


;; Dart gate (2026-11-08): `check-source` marks tails over the bundled
;; PHP+prelude+TS program and loads it through `yin.vm/ast->datoms` /
;; `datoms->ast`; those recursive walkers overflow the Dart stack on the
;; prelude-chained tree (depth ~900 for one PHP fixture's stage AST) where
;; the JVM and Node complete. No parser is involved (both frontends lower
;; checked-in CST forms), so this is not the missing Dart PHP recognizer;
;; making the shared walkers iterative is the un-gating fix and lives
;; outside the typescript/php test surface.
#?(:cljd nil
   :default
   (deftest ^:slow source-calls-php-function-test
     (slow/guard "source-calls-php-function-test" #(check-source fixtures/direct))))


#?(:cljd nil
   :default
   (deftest ^:slow source-foreign-store-and-callback-test
     (slow/guard "source-foreign-store-and-callback-test" #(check-source fixtures/callback))))
