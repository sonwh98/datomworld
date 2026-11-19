(ns yang.php.antlr.eval-bridge-test
  (:require [clojure.test :refer [deftest is]]
            [yang.php.antlr.e2e-harness :as h]
            [yang.php.antlr.lower :as lower]
            [yang.php.antlr.portable-harness :as portable]))


(deftest eval-in-caller-scope-on-every-vm
  (doseq [[source expected] [["<?php echo eval(\"return 6 * 7;\");" "42"]
                             ["<?php $x = 40; echo eval('return $x + 2;');" "42"]]]
    (let [results (h/run-php source)]
      (doseq [backend [:ast-walker :semantic :stack :register]]
        (is (= expected (get results backend)) (pr-str [backend source results]))))))


(deftest dedicated-prefix-form-lowers-with-service
  (let [packet (portable/packet [:htmlDocument
                                 [:phpBlock [:topStatement [:statement [:expressionStatement
                                                                        [:expression ["Eval" "eval"] ["(" "("]
                                                                         [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]
                                                                         [")" ")"]] ["SemiColon" ";"]]]]]
                                 ["EOF" "<EOF>"]])]
    (is (:ast (lower/lower-program packet {:eval-service? true})))))


(deftest service-class-collision-lifecycle
  (doseq [source ["<?php class Taken {} register_shutdown_function(function(){ echo 'S'; }); try { eval('class Taken {}'); } catch (Throwable $e) { echo 'catch'; } finally { echo 'finally'; } echo 'after';"
                  "<?php class D { function __destruct(){ echo 'D'; } } $d = new D; eval('class Prior {}'); register_shutdown_function(function(){ echo class_exists('Prior', false) ? 'P' : '-'; }); try { eval('class Prior {}'); } catch (Throwable $e) { echo 'catch'; } finally { echo 'finally'; } echo 'after';"
                  "<?php register_shutdown_function(function(){ echo class_exists('C', false) ? 'C' : '-'; }); function invoke_it(){ eval('class C {}'); } invoke_it(); invoke_it();"]]
    (let [ast (:ast (h/lower-program source nil {:eval-service? true}))
          result (h/run-eval-vm :semantic ast)
          class-name (cond (.contains source "class Taken") "Taken"
                           (.contains source "class Prior") "Prior"
                           :else "C")
          message (str "Cannot declare class " class-name ", because the name is already in use")]
      (is (= (if (= class-name "Taken") "S" (if (= class-name "Prior") "P" "C")) (:php/out result)))
      (is (= {:php/fatal message :php/status 255 :php/uncaught nil}
             (select-keys result [:php/fatal :php/status :php/uncaught])))
      (is (= [[64 message]] (mapv (juxt :errno :message) (:php/diag result)))))))


(deftest syntax-refusal-is-a-guest-parse-error
  (let [results (h/run-php "<?php try { eval('return (;'); } catch (ParseError $e) { echo 'caught'; }")]
    (doseq [backend [:ast-walker :semantic :stack :register]]
      (is (= "caught" (get results backend)) (pr-str [backend results])))))
