(ns yang.php.antlr.e2e-test
  "End to end on the JVM: source -> CST packet -> lowering stage -> program
   envelope -> the bundled program on each of the four VMs (the AST walker,
   the semantic, stack and register VMs). Every fixture of
   `yang.php.antlr.programs` prints the same text on all four."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [yang.php.antlr.e2e-harness :as h]
    [yang.php.antlr.programs :as programs]))


(def ^:private vms [:ast-walker :semantic :stack :register])


(deftest ^:slow fixtures-on-every-vm-test
  (doseq [{:keys [id source out]} programs/fixtures]
    (testing (name id)
      (let [results (h/run-php source)]
        (is (not (contains? results :diagnostics)) (pr-str results))
        (doseq [k vms]
          (is (= out (get results k)) (str (name id) " on " k)))))))


(deftest fixtures-on-the-walker-test
  (doseq [{:keys [id source out]} programs/fixtures]
    (is (= out (h/run-walker source)) (name id))))


(defn- matrix-rows
  [out]
  (let [[eq sp] (str/split-lines out)]
    {:eq (mapv #(apply str %) (partition 18 eq)),
     :sp (mapv #(apply str (map {\0 "<", \1 "=", \2 ">"} %)) (partition 18 sp))}))


(deftest scalar-comparison-matrix-test
  (let [{:keys [eq sp]} (matrix-rows (h/run-walker programs/matrix-source))]
    (is (= programs/equal-matrix eq))
    (is (= programs/spaceship-matrix sp))
    (testing "cells reasoned from the manual, independent of the golden"
      (let [idx (zipmap programs/matrix-values (range))]
        (doseq [[a b eq? cmp] programs/known-cells
                :let [i (idx a), j (idx b)]]
          (is (= (if eq? \1 \0) (get-in eq [i j])) (str a " == " b))
          (is (= (get {-1 \<, 0 \=, 1 \>} cmp) (get-in sp [i j])) (str a " <=> " b)))))))


(deftest float-text-test
  (let [src (str "<?php\n"
                 (apply str (for [[lit] programs/float-cases]
                              (str "echo " lit ", \"|\"; var_dump(" lit ");\n"))))
        expected (str (apply str (for [[_ echo dump] programs/float-cases]
                                   (str echo "|float(" dump ")\n"))))]
    (is (= expected (h/run-walker src)))))


(deftest refusals-are-named-test
  (doseq [[source construct]
          [["<?php $x = 1;" :assignment]
           ["<?php echo $x;" :variable]
           ["<?php function f() {}" :function-declaration]
           ["<?php if (1) {}" :if]
           ["<?php echo [1];" :array]
           ["<?php echo 2 ** 0.5;" nil]
           ["<?php echo FOO;" :constant]
           ["<?php echo @1;" :silence]
           ["<?php echo \"a$b\";" :interpolation]
           ["<?php echo (array)1;" :cast/array]
           ["<p class=\"a\">x</p>" :inline-html-markup]]
          :when construct]
    (let [{:keys [diagnostics]} (h/run-php source)
          d (first diagnostics)]
      (is (= :yang.php/unsupported (:yang.php/diagnostic d)) source)
      (is (= construct (:construct d)) source))))


(deftest leading-numeric-string-warns-test
  (let [r (h/run-raw "<?php echo \"5 apples\" + 1;")]
    (is (= "6" (:php/out r)))
    (is (= [{:php.diag/warning true,
             :errno 2,
             :message "A non-numeric value encountered"}]
           (:php/diag r)))
    (is (nil? (:php/uncaught r))))
  (testing "a well formed numeric string does not warn"
    (is (= [] (:php/diag (h/run-raw "<?php echo \" 5 \" + 1;"))))))
