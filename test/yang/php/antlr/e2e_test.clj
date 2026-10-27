(ns yang.php.antlr.e2e-test
  "End to end on the JVM: source -> CST packet -> lowering stage -> program
   envelope -> the bundled program on each of the four VMs (the AST walker,
   the semantic, stack and register VMs). Every fixture of
   `yang.php.antlr.programs` prints the same text on all four."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [yang.php.antlr.cst :as cst]
    [yang.php.antlr.e2e-harness :as h]
    [yang.php.antlr.packets :as packets]
    [yang.php.antlr.packets-p3b :as packets-p3b]
    [yang.php.antlr.permutations :as perms]
    [yang.php.antlr.phpt :as phpt]
    [yang.php.antlr.programs :as programs]
    [yang.php.antlr.programs-p3b :as p3b]))


(def ^:private vms [:ast-walker :semantic :stack :register])


(defn- rendered
  "The text `fixture` prints: `:out`, then the fatal line of an `:uncaught`."
  [{:keys [out uncaught]}]
  (str out
       (when uncaught
         (str "PHP Fatal error:  Uncaught " (:class uncaught) ": " (:message uncaught)))))


(deftest ^:slow fixtures-on-every-vm-test
  (doseq [{:keys [id source] :as fixture} programs/fixtures
          :let [out (rendered fixture)]]
    (testing (name id)
      (let [results (h/run-php source)]
        (is (not (contains? results :diagnostics)) (pr-str results))
        (doseq [k vms]
          (is (= out (get results k)) (str (name id) " on " k)))))))


(deftest fixtures-on-the-walker-test
  (doseq [{:keys [id source out diag uncaught oracle]} programs/fixtures
          :let [r (h/run-raw source)]]
    (is (not= :pending oracle) (name id))
    (is (= out (h/latin1->text (:php/out r))) (name id))
    (is (= (vec diag) (mapv (juxt :errno :message) (:php/diag r))) (name id))
    (is (= uncaught (some-> (:php/uncaught r) (select-keys [:class :message])))
        (name id))))


(deftest packets-match-source-test
  (doseq [[id form] packets/forms
          :let [{:keys [source]} (first (filter #(= id (:id %)) programs/fixtures))]]
    (is (= form (h/packet->form (cst/parse-source source))) (name id))))


(defn- matrix-rows
  [out]
  (let [[eq sp] (str/split-lines out)
        w (count programs/matrix-values)]
    {:eq (mapv #(apply str %) (partition w eq)),
     :sp (mapv #(apply str (map {\0 "<", \1 "=", \2 ">"} %)) (partition w sp))}))


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
          [["<?php $a = &f();" :reference-source]
           ["<?php $a->b = 1;" :assignment-target]
           ["<?php $$a[0] = 1;" :assignment-target]
           ["<?php function f(int $a) {}" :type-declaration]
           ["<?php function f(): int {}" :return-type]
           ["<?php function f(&...$a) {}" :variadic-reference]
           ["<?php $a = &new B;" :reference]
           ["<?php static $a = 1;" :static]
           ["<?php return 1;" :return]
           ["<?php echo FOO;" :constant]
           ["<?php echo \"a$b[0]\";" :interpolation]
           ["<?php echo \"a$b->c\";" :interpolation]
           ["<?php echo isset($a->b);" :isset-target]
           ["<?php echo (array)1;" :cast/array]
           ["<?php $o = new class { public $x = 1; };" :anonymous-class]
           ["<?php $o = new A;" :new]]
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
            "Switch statements may only contain one default clause"]
           ["<?php echo $a[];" "Cannot use [] for reading"]
           ["<?php $a[] += 1;" "Cannot use [] for reading"]
           ["<?php $a[]++;" "Cannot use [] for reading"]
           ["<?php $a[] ??= 1;" "Cannot use [] for reading"]
           ["<?php echo $a[0][] ?? 1;" "Cannot use [] for reading"]
           ["<?php echo isset($a[]);" "Cannot use [] for reading"]
           ["<?php unset($a[]);" "Cannot use [] for unsetting"]]]
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


(deftest stage0-enabled-fixtures-on-the-walker-test
  (doseq [{:keys [id source out diag uncaught level]} p3b/enabled
          :when (= :program level)
          :let [r (h/run-raw source)]]
    (is (= out (h/latin1->text (:php/out r))) (name id))
    (is (= (vec diag) (mapv (juxt :errno :message) (:php/diag r))) (name id))
    (is (= uncaught (some-> (:php/uncaught r) (select-keys [:class :message])))
        (name id))))


(deftest ^:slow stage0-enabled-fixtures-on-every-vm-test
  (doseq [{:keys [id source level] :as fixture} p3b/enabled
          :when (= :program level)
          :let [out (rendered fixture)]]
    (let [results (h/run-php source)]
      (is (not (contains? results :diagnostics)) (pr-str results))
      (doseq [k vms]
        (is (= out (get results k)) (str (name id) " on " k))))))


(deftest stage0-packets-match-source-test
  (doseq [[id form] packets-p3b/forms
          :let [{:keys [source]} (first (filter #(= id (:id %)) p3b/all-fixtures))]]
    (is (= form (h/packet->form (cst/parse-source source))) (name id))))


;; -----------------------------------------------------------------------------
;; S10: permutation corpus and php-src selection
;; -----------------------------------------------------------------------------

(defn- read-edn-file
  [path]
  (let [f (io/file path)]
    (when (.exists f) (edn/read-string (slurp f)))))


(defn- engine-result
  "`{:out :diag :uncaught}` of `source` on the walker, in the oracle's shape, or
   `{:refused diagnostics}` / `{:threw message}`."
  [source]
  (try
    (let [r (h/run-raw source)]
      (if (:diagnostics r)
        {:refused (mapv :message (:diagnostics r))}
        {:out (h/latin1->text (:php/out r)),
         :diag (mapv (juxt :errno :message) (:php/diag r)),
         :uncaught (some-> (:php/uncaught r) (select-keys [:class :message]))}))
    (catch Throwable t {:threw (str t)})))


(defn- run-corpus
  "Run `programs` against the recorded oracle map: `{:total :compared :mismatches}`
   where a host throw or a refusal is always a mismatch."
  [programs oracle]
  (reduce (fn [acc {:keys [id source]}]
            (let [r (engine-result source)
                  want (get oracle id)
                  bad (or (contains? r :threw) (contains? r :refused)
                          (and want (not= (update want :diag vec) (update r :diag vec))))]
              (cond-> (update acc :total inc)
                want (update :compared inc)
                bad (update :mismatches conj [id r]))))
          {:total 0, :compared 0, :mismatches []}
          programs))


(deftest permutation-corpus-quick-test
  (let [oracle (read-edn-file "test/yang/php/antlr/permutation_oracle_quick.edn")
        {:keys [total compared mismatches]} (run-corpus (perms/quick) oracle)]
    (is (= (* 2 (count perms/quick-ids)) total))
    (is (empty? mismatches) (pr-str (take 3 mismatches)))
    (when-not oracle
      (println "permutation corpus: no recorded oracle yet, ran" total "programs without comparison"))
    (when oracle
      (is (= total compared) "every quick program has a recorded oracle result"))))


(deftest ^:slow permutation-corpus-full-test
  (let [oracle (read-edn-file "build/yang-php/permutation_oracle_full.edn")
        {:keys [total mismatches]} (run-corpus (perms/full) oracle)]
    (is (= (* 2 (count perms/full-ids)) total))
    (is (empty? mismatches) (pr-str (take 3 mismatches)))))


(deftest phpt-parsing-and-matching-test
  (let [text "--TEST--\nt\n--FILE--\n<?php echo 1;\n--EXPECTF--\nint(%d) %s\n"
        sections (phpt/parse-phpt text)]
    (is (= "t" (get sections "TEST")))
    (is (= "<?php echo 1;" (get sections "FILE")))
    (is (phpt/matches-expect? sections "int(42) abc\n"))
    (is (not (phpt/matches-expect? sections "int(x) abc")))
    (is (phpt/matches-expect? (phpt/parse-phpt "--FILE--\nx\n--EXPECT--\nhi\n") "hi\n")))
  (testing "classification"
    (is (= :select (:status (phpt/classify {"FILE" "<?php function f(&$x){} f($a); echo count([]);",
                                            "EXPECT" "x"}))))
    (is (= :p4 (:needs (phpt/classify {"FILE" "<?php class A {} new A;", "EXPECT" "x"}))))
    (is (= :unsupported-surface
           (:reason (phpt/classify {"FILE" "<?php preg_match('/a/', 'a');", "EXPECT" "x"}))))
    (is (= :unknown-functions
           (:reason (phpt/classify {"FILE" "<?php mystery_fn(1);", "EXPECT" "x"}))))
    (is (= :no-expectation (:reason (phpt/classify {"FILE" "<?php echo 1;"})))))
  (testing "path predicates"
    (is (phpt/selected-path? "Zend/tests/list/list_001.phpt"))
    (is (phpt/selected-path? "ext/standard/tests/array/current_variation1.phpt"))
    (is (not (phpt/selected-path? "ext/standard/tests/strings/sprintf_basic1.phpt")))))


(deftest phpt-selection-runs-test
  (if-let [sel (read-edn-file "test/yang/php/antlr/phpt_selection.edn")]
    (let [entries (remove :needs (:entries sel))
          results (for [{:keys [id source expect expectf]} entries
                        :let [r (engine-result source)]]
                    (cond
                      (:refused r) [:unsupported id]
                      (:threw r) [:fail id]
                      (phpt/matches-expect?
                        (cond-> {} expect (assoc "EXPECT" expect) expectf (assoc "EXPECTF" expectf))
                        (str (:out r)
                             (when (:uncaught r)
                               (str "Fatal error: Uncaught " (:class (:uncaught r)) ": "
                                    (:message (:uncaught r))))))
                      [:pass id]
                      :else [:fail id]))
          by (group-by first results)]
      (println "php-src selection:" (count (:entries sel)) "entries;"
               "pass" (count (:pass by)) "unsupported" (count (:unsupported by))
               "fail" (count (:fail by)))
      (is (empty? (:fail by)) (pr-str (take 5 (:fail by)))))
    (println "php-src selection: phpt_selection.edn not generated yet (run yang.php.phpt on shiva)")))


(deftest gate-fix-programs-test
  (testing "var_dump of a self-referencing array terminates with *RECURSION*"
    (let [r (engine-result "<?php $a = [1]; $a[0] = &$a; var_dump($a);")]
      (is (str/includes? (:out r) "*RECURSION*") (pr-str r))
      (is (nil? (:uncaught r)))))
  (testing "comparing two cyclic arrays is a nesting Error"
    (is (= "Nesting level too deep - recursive dependency?"
           (:out (engine-result
                   (str "<?php $a = [1]; $a[0] = &$a; $b = [1]; $b[0] = &$b;"
                        " try { var_dump($a == $b); } catch (Error $e) { echo $e->getMessage(); }"))))))
  (testing "C25: a string-key spread is refused with the named gap"
    (let [r (engine-result (:source (first (filter #(= :call-spread-string-key-gap (:id %))
                                                   p3b/all-fixtures))))]
      (is (= {:class "Error",
              :message "spread with string keys is not supported yet (yang.php known gap G-spread-string-keys)"}
             (:uncaught r)))))
  (testing "C28: a later named argument is evaluated before the unknown name is rejected"
    (is (= "SUnknown named parameter $z"
           (:out (engine-result
                   (:source (first (filter #(= :call-unknown-named-later-arg-runs (:id %))
                                           p3b/all-fixtures)))))))))
