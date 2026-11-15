(ns yang.php.antlr.eval-emitter-test
  (:require [clojure.test :refer [deftest is]]
            [dao.host.text :as text]
            [yang.core.prelude :as core-prelude]
            [yang.core.uast :as u]
            [yang.php.antlr.e2e-harness :as harness]
            [yang.php.antlr.eval-coordinator :as coordinator]
            [yang.php.antlr.lower :as lower]
            [yang.php.antlr.prelude :as prelude]
            [yin.vm :as vm]
            [yin.vm.test-utils :as tu]))


(defn- packet
  [source]
  (:cst (coordinator/compile-request
          (coordinator/make-worker)
          {:yang.compile/unit :eval
           :yang.php/source (text/units->string (map #(bit-and % 255) (text/utf8-encode source)))})))


(deftest eval-entry-borrows-runtime-and-scope
  (let [entry (:ast (lower/eval-entry (packet "$x = 2; return $x;")))
        free (core-prelude/free-names entry)]
    (is (= :lambda (:type entry)))
    (is (= '[%runtime %scope %ctx %hooks] (:params entry)))
    (is (not-any? #(= "php" (namespace %)) free))
    (is (not (contains? free '%scope)))
    (is (not (contains? free '%ctx)))))


(deftest eval-entry-binds-derived-hooks
  (let [entry (:ast (lower/eval-entry (packet "while (false) {}")))
        params (into #{} (mapcat :params) (filter #(and (map? %) (= :lambda (:type %)))
                                                  (tree-seq coll? seq entry)))]
    (is (= '%eval-loop (:loop lower/eval-hook-profile)))
    (is (= '%eval-call (get-in lower/eval-hook-profile [:call :hook])))
    (is (= '%eval-line (get-in lower/eval-hook-profile [:line :hook])))
    (is (every? params '[%eval-loop %eval-call %eval-line]))))


(deftest runtime-environment-uses-prelude-inventory
  (let [free (core-prelude/free-names (lower/eval-runtime-node))]
    (is (contains? free 'php/dict-get))
    (is (contains? free 'php/call-ec))
    (is (not-any? #(= "php.rt" (namespace %)) free))))


(deftest eval-module-is-code-only
  (let [spec (lower/eval-module-spec (harness/host-registry)
                                     'eval.test (packet "return 7;"))]
    (is (= 'eval.test (:name spec)))
    (is (= '#{entry} (:exports spec)))
    (is (empty? (:requires spec)))
    (is (= '#{entry} (core-prelude/defined-keys (:ast spec))))))


(deftest eval-entry-runs-with-the-callers-runtime
  (let [entry (:ast (lower/eval-entry (packet "$x = 2; return $x;")))
        invoke (u/app entry (lower/eval-runtime-node) (u/lit :php/globals)
                      (u/lit :php/null) (u/lit {}))
        main (u/app (u/v 'php/run-main)
                    (u/lam [] (u/app (u/v 'php/echo) (u/app (u/v 'php/to-string) invoke)))
                    (u/lit :php/null))
        ast (u/then prelude/uast main)]
    (is (= {:ast-walker "2", :semantic "2", :stack "2", :register "2"}
           (harness/run-vms (harness/host-registry) ast)))))


(deftest duplicate-functions-remain-caller-executable
  (let [request {:yang.compile/unit :eval
                 :yang.php/source "function before() {} function f() {} function F() {}"}
        result (coordinator/compile-request (coordinator/make-worker) request)]
    (is (:cst result) (pr-str result))
    (is (:ast (lower/eval-entry (:cst result))))))


(defn- invoke-entry
  [source]
  (u/app (:ast (lower/eval-entry (packet source)))
         (lower/eval-runtime-node) (u/lit :php/globals) (u/lit :php/null) (u/lit {})))


(deftest collision-retains-the-bound-prefix
  (let [setup (invoke-entry (str "function occupied() { echo 'old'; } "
                                 "register_shutdown_function(function () { "
                                 "echo function_exists('before') ? 'B' : '-'; "
                                 "occupied(); echo function_exists('after') ? 'A' : '-'; });"))
        collision (invoke-entry "function before() {} function occupied() {} function after() {} echo 'body';")
        main (u/app (u/v 'php/run-main)
                    (u/lam [] (u/then setup collision)) (u/lit :php/null))
        result (harness/run-vms (harness/host-registry) (u/then prelude/uast main))]
    (doseq [backend [:ast-walker :semantic :stack :register]]
      (is (= "Bold-PHP Fatal error:  Cannot redeclare occupied()" (get result backend))
          (pr-str [backend result])))))


(deftest repeated-entry-does-not-reuse-class-hoist-state
  (let [setup (invoke-entry (str "register_shutdown_function(function () { "
                                 "echo class_exists('C', false) ? 'C' : '-'; });"))
        entry (:ast (lower/eval-entry (packet "class C {} echo 'first';")))
        call (u/app (u/v '%entry) (lower/eval-runtime-node) (u/lit :php/globals)
                    (u/lit :php/null) (u/lit {}))
        main (u/app (u/v 'php/run-main)
                    (u/lam [] (u/then setup (u/let1 '%entry entry (u/then call call))))
                    (u/lit :php/null))
        result (harness/run-vms (harness/host-registry) (u/then prelude/uast main))]
    (doseq [backend [:ast-walker :semantic :stack :register]]
      (is (= "firstCPHP Fatal error:  Cannot declare class C, because the name is already in use"
             (get result backend))
          (pr-str [backend result])))))


(deftest function-collision-runs-shutdown-without-unwinding-guest-code
  (let [setup (invoke-entry (str "class D { function __destruct() { echo 'D'; } } "
                                 "$d = new D; function occupied() {} "
                                 "register_shutdown_function(function () { "
                                 "echo function_exists('before') ? 'B' : '-'; "
                                 "echo function_exists('after') ? 'A' : '-'; });"))
        collision (invoke-entry "function before() {} function occupied() {} function after() {}")
        guarded (u/app (u/v 'php/try) (u/lam [] collision)
                       (u/lam ['%e] (u/app (u/v 'php/echo) (u/lit "catch")))
                       (u/lam [] (u/app (u/v 'php/echo) (u/lit "finally"))))
        main (u/app (u/v 'php/run-main) (u/lam [] (u/then setup guarded)) (u/lit :php/null))
        session (tu/make-observer-session (tu/create-vm {:modules (harness/host-registry)}))]
    (tu/queue-ast! session (u/then prelude/uast main))
    (let [result (vm/value (:consumer (tu/run-session session)))]
      (is (= "B-" (:php/out result)))
      (is (= 255 (:php/status result)))
      (is (= [[64 "Cannot redeclare occupied()"]]
             (mapv (juxt :errno :message) (:php/diag result)))))))


(deftest ^{:oracle :pending} deferred-class-collision-retains-later-early-class
  ;; Hand-derived from the atomicity ruling; PHP oracle remains pending.
  (let [setup (invoke-entry (str "class Taken {} register_shutdown_function(function () { "
                                 "echo class_exists('Before', false) ? 'B' : '-'; "
                                 "echo class_exists('After', false) ? 'A' : '-'; });"))
        collision (invoke-entry "class Before {} class Taken {} class After {} echo 'body';")
        main (u/app (u/v 'php/run-main) (u/lam [] (u/then setup collision)) (u/lit :php/null))
        result (harness/run-vms (harness/host-registry) (u/then prelude/uast main))]
    (doseq [backend [:ast-walker :semantic :stack :register]]
      (is (= "BAPHP Fatal error:  Cannot declare class Taken, because the name is already in use"
             (get result backend))
          (pr-str [backend result])))))


(deftest ^{:oracle :pending} mixed-declarations-before-duplicate-function
  (let [setup (invoke-entry (str "register_shutdown_function(function () { "
                                 "echo class_exists('First', false) ? 'C' : '-'; "
                                 "echo function_exists('before') ? 'B' : '-'; "
                                 "echo class_exists('After', false) ? 'A' : '-'; });"))
        collision (invoke-entry (str "class First {} function before() {} function repeat() {} "
                                     "function REPEAT() {} class After {}"))
        main (u/app (u/v 'php/run-main) (u/lam [] (u/then setup collision)) (u/lit :php/null))
        result (harness/run-vms (harness/host-registry) (u/then prelude/uast main))]
    (doseq [backend [:ast-walker :semantic :stack :register]]
      (is (= "CB-PHP Fatal error:  Cannot redeclare REPEAT()" (get result backend))
          (pr-str [backend result])))))
