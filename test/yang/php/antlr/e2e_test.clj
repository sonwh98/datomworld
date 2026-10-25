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
          [["<?php $a[0] = 1;" :assignment-target]
           ["<?php $a = &$b;" :reference]
           ["<?php echo $a[0];" :variable]
           ["<?php function f(int $a) {}" :type-declaration]
           ["<?php function f(): int {}" :return-type]
           ["<?php function f(&$a) {}" :reference]
           ["<?php function f(...$a) {}" :variadic]
           ["<?php foreach ($a as $b) {}" :foreach]
           ["<?php global $a;" :global]
           ["<?php static $a = 1;" :static]
           ["<?php return 1;" :return]
           ["<?php echo [1];" :array]
           ["<?php echo FOO;" :constant]
           ["<?php echo \"a$b[0]\";" :interpolation]
           ["<?php echo \"a$b->c\";" :interpolation]
           ["<?php echo isset($a[0]);" :isset-target]
           ["<?php echo (array)1;" :cast/array]]
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


(deftest pow-fractional-refusal-test
  (testing "G-pow-frac: a fractional exponent refuses at run time, with the ledger id"
    (let [r (h/run-raw "<?php echo 2 ** 0.5;")]
      (is (= "Error" (-> r :php/uncaught :class)))
      (is (= "** with a fractional exponent is not supported yet (yang.php known gap G-pow-frac)"
             (-> r :php/uncaught :message))))))


(deftest compile-errors-are-named-test
  (doseq [[source fragment]
          [["<?php break;" "'break' not in the 'loop' or 'switch' context"]
           ["<?php function f() { while (1) { } break; }"
            "'break' not in the 'loop' or 'switch' context"]
           ["<?php while (1) { break 0; }" "'break' operator accepts only positive integers"]
           ["<?php while (1) { continue 0; }" "'continue' operator accepts only positive integers"]
           ["<?php while (1) { break 2; }" "Cannot 'break' 2 levels"]
           ["<?php while (1) { while (1) { continue 3; } }" "Cannot 'continue' 3 levels"]
           ["<?php while (1) { break; } while (1) { break 2; }" "Cannot 'break' 2 levels"]
           ["<?php $n = 1; while (1) { break $n; }" "non-integer operand"]
           ["<?php function f() {} function F() {}" "Cannot redeclare F()"]
           ["<?php function strlen() {}" "Cannot redeclare strlen()"]
           ["<?php switch (1) { default: echo 1; default: echo 2; }"
            "Switch statements may only contain one default clause"]
           ["<?php switch (1): default: echo 1; case 2: break; default: echo 2; endswitch;"
            "Switch statements may only contain one default clause"]
           ["<?php function f() { switch (1) { default: break; default: break; } }"
            "Switch statements may only contain one default clause"]]]
    (let [{:keys [diagnostics]} (h/run-php source)
          d (first diagnostics)]
      (is (= :yang.php/compile-error (:yang.php/diagnostic d)) source)
      (is (str/includes? (str (:message d)) fragment) source))))


(deftest lowering-warnings-test
  (testing "a catalogue function the prelude lacks warns, and the program lowers"
    (let [{:keys [ast warnings]} (h/lower-program "<?php echo strtoupper(\"a\");")]
      (is (some? ast))
      (is (= [{:yang.php/diagnostic :yang.php/unknown-builtin,
               :severity :warning,
               :name "strtoupper"}]
             (mapv #(select-keys % [:yang.php/diagnostic :severity :name]) warnings)))
      (testing "and the call is an Error at run time"
        (is (= "PHP Fatal error:  Uncaught Error: Call to undefined function strtoupper()"
               (h/run-walker "<?php echo strtoupper(\"a\");"))))))
  (testing "a unit that declares the function, or a prelude builtin, does not"
    (is (empty? (:warnings (h/lower-program
                             "<?php function strtoupper($s) { return $s; } echo STRTOUPPER(\"a\");"))))
    (is (empty? (:warnings (h/lower-program "<?php echo strlen(\"a\");"))))
    (is (empty? (:warnings (h/lower-program "<?php echo not_a_php_function(1);")))))
  (testing "continue aimed at a switch is a break and says so"
    (let [{:keys [warnings]}
          (h/lower-program "<?php for (;;) { switch (1) { case 1: continue; } break; }")]
      (is (= [:yang.php/compile-warning] (mapv :yang.php/diagnostic warnings))))
    (is (= "ab" (h/run-walker "<?php switch (1) { case 1: echo \"a\"; continue; echo \"no\"; } echo \"b\";")))))


(deftest undefined-variable-warns-test
  (let [r (h/run-raw "<?php echo $a; function f() { return $b; } echo f() ?? \"n\";")]
    (is (= "n" (:php/out r)))
    (is (= ["Undefined variable $a" "Undefined variable $b"] (mapv :message (:php/diag r))))
    (is (= [2 2] (mapv :errno (:php/diag r)))))
  (testing "isset, empty and ?? do not warn"
    (is (= [] (:php/diag (h/run-raw "<?php isset($a); empty($a); echo $a ?? 1;")))))
  (testing "a local read of an unset local warns, a static is its own slot"
    (is (= ["Undefined variable $x"]
           (mapv :message
                 (:php/diag (h/run-raw "<?php function f() { $x = 1; unset($x); return $x; } f();")))))))


(deftest error-handler-test
  (testing "the handler sees the errno and message and may take the warning"
    (let [r (h/run-raw (str "<?php function h($n, $s) { echo $n, $s, \";\"; return true; }"
                            " set_error_handler('h'); echo $u;"))]
      (is (= "2Undefined variable $u;" (:php/out r)))
      (is (= [] (:php/diag r)))))
  (testing "a handler that returns false falls through to the default sink"
    (let [r (h/run-raw (str "<?php function h($n, $s) { return false; }"
                            " set_error_handler('h'); echo $u;"))]
      (is (= ["Undefined variable $u"] (mapv :message (:php/diag r))))))
  (testing "@ silences the default sink, and error_reporting is restored"
    (let [r (h/run-raw "<?php echo @$u; echo error_reporting(); echo $w;")]
      (is (= "32767" (:php/out r)))
      (is (= ["Undefined variable $w"] (mapv :message (:php/diag r))))))
  (testing "set_error_handler answers the previous handler"
    (is (= "NULL\nstring(1) \"h\"\n"
           (h/run-walker (str "<?php function h() {} var_dump(set_error_handler('h'));"
                              " var_dump(set_error_handler(null));"))))))


(deftest argument-coercion-weak-mode-test
  (doseq [[arg out diag uncaught]
          [["1.0" "1" [] nil]
           ["1.5" "1" [[8192 "Implicit conversion from float 1.5 to int loses precision"]] nil]
           ["-1.5" "-1" [[8192 "Implicit conversion from float -1.5 to int loses precision"]] nil]
           ["\"1.5\"" "1"
            [[8192 "Implicit conversion from float-string \"1.5\" to int loses precision"]] nil]
           ["\"1e3\"" "1000" [] nil]
           ["-9223372036854775808.0" "-9223372036854775808" [] nil]
           ["null" "0" [[8192 "intdiv(): Passing null to parameter #1 ($num1) of type int is deprecated"]]
            nil]
           ["true" "1" [] nil]
           ["INF" "" []
            "intdiv(): Argument #1 ($num1) must be of type int, float given"]
           ["NAN" "" []
            "intdiv(): Argument #1 ($num1) must be of type int, float given"]
           ["1e30" "" []
            "intdiv(): Argument #1 ($num1) must be of type int, float given"]
           ["-1e30" "" []
            "intdiv(): Argument #1 ($num1) must be of type int, float given"]
           ["\"1e30\"" "" []
            "intdiv(): Argument #1 ($num1) must be of type int, string given"]
           ["9.2233720368547758E+18" "" []
            "intdiv(): Argument #1 ($num1) must be of type int, float given"]
           ["(float)PHP_INT_MAX" "" []
            "intdiv(): Argument #1 ($num1) must be of type int, float given"]]
          :let [r (h/run-raw (str "<?php echo intdiv(" arg ", 1);"))]]
    (testing arg
      (is (= out (:php/out r)))
      (is (= diag (mapv (juxt :errno :message) (:php/diag r))))
      (is (= uncaught (-> r :php/uncaught :message)))))
  (testing "arg-float: null deprecates and is 0.0; a non-numeric string is a TypeError"
    (let [r (h/run-raw "<?php var_dump(fmod(null, 2));")]
      (is (= "float(0)\n" (:php/out r)))
      (is (= [[8192 "fmod(): Passing null to parameter #1 ($num1) of type float is deprecated"]]
             (mapv (juxt :errno :message) (:php/diag r)))))
    (is (= "fmod(): Argument #2 ($num2) must be of type float, string given"
           (-> (h/run-raw "<?php fmod(1, \"1x\");") :php/uncaught :message))))
  (testing "deprecations route through a user handler"
    (is (= "8192:Implicit conversion from float 1.5 to int loses precision;1"
           (:php/out (h/run-raw (str "<?php function h($n, $s) { echo $n, \":\", $s, \";\"; return true; }"
                                     " set_error_handler('h'); echo intdiv(1.5, 1);")))))))


(deftest typed-builtin-argument-coercion-test
  (doseq [[source message]
          [["<?php intdiv(\"abc\", 1);"
            "intdiv(): Argument #1 ($num1) must be of type int, string given"]
           ["<?php intdiv(1, \"5 apples\");"
            "intdiv(): Argument #2 ($num2) must be of type int, string given"]
           ["<?php fmod(\"x\", 1);"
            "fmod(): Argument #1 ($num1) must be of type float, string given"]]]
    (let [r (h/run-raw source)]
      (is (= "TypeError" (-> r :php/uncaught :class)) source)
      (is (= message (-> r :php/uncaught :message)) source)))
  (is (= "3" (:php/out (h/run-raw "<?php echo intdiv(\"7\", \"2\");")))))
