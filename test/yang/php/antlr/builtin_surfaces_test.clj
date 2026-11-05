(ns yang.php.antlr.builtin-surfaces-test
  "P9 builtin surfaces, dynamic scopes, and corner closures."
  (:require
    [clojure.test :refer [deftest is testing]]
    [yang.php.antlr.e2e-harness :as h]
    [yang.php.antlr.programs-p9 :as p9]))


(deftest p9-fixtures-test
  (doseq [{:keys [id source out diag]} p9/fixtures]
    (testing (name id)
      (let [r (h/run-raw source)]
        (is (= out (:php/out r)))
        (is (= (vec diag) (mapv (juxt :errno :message) (:php/diag r))))
        (is (nil? (:php/uncaught r)))))))


(deftest p9-contracts-test
  (let [out (fn [source] (:php/out (h/run-raw source)))
        diag (fn [source] (mapv (juxt :errno :message) (:php/diag (h/run-raw source))))]
    (testing "empty search returns the subject, never empty output"
      (is (= "a" (out "<?php echo str_replace('', 'x', 'a');")))
      (is (= "a" (out "<?php echo str_ireplace('', 'x', 'a');"))))
    (testing "search/replacement/subject arrays and the count output"
      (is (= "x x x" (out "<?php echo str_replace(['a', 'b'], 'x', 'a b a');")))
      (is (= "Xb" (out "<?php echo str_ireplace('A', 'X', 'ab');")))
      (is (= "bb2" (out "<?php $c = 0; echo str_replace('a', 'b', 'aa', $c), $c;")))
      (is (= "ay|by" (out "<?php echo implode('|', str_replace('x', 'y', ['ax', 'bx']));"))))
    (testing "str_pad never truncates; empty pad with work to do is a ValueError"
      (is (= "abcd" (out "<?php echo str_pad('abcd', 2);")))
      (is (= "x  " (out "<?php echo str_pad('x', 3);")))
      (is (= "-x--" (out "<?php echo str_pad('x', 4, '-', STR_PAD_BOTH);")))
      (is (= "ValueError" (out "<?php try { str_pad('x', 3, ''); } catch (ValueError $e) { echo 'ValueError'; }"))))
    (testing "strrpos offsets bound the search"
      (is (= "3" (out "<?php echo strrpos('hello', 'l', 3);")))
      (is (= "" (out "<?php echo strrpos('hello', 'l', 4);")))
      (is (= "2" (out "<?php echo strrpos('hello', 'l', -2);"))))
    (testing "trim character masks with ranges"
      (is (= "a" (out "<?php echo trim('xxaxx', 'x');")))
      (is (= "def" (out "<?php echo trim('abcdef', 'a..c');"))))
    (testing "single-array array_map keeps keys; multi-array is variadic"
      (is (= "a,b" (out "<?php echo implode(',', array_keys(array_map(fn($x) => $x * 2, ['a' => 1, 'b' => 2])));")))
      (is (= "11,22" (out "<?php echo implode(',', array_map(fn($a, $b) => $a + $b, [1, 2], [10, 20]));")))
      (is (= "b" (out "<?php $r = array_map(null, [1, 2], ['a', 'b']); echo $r[1][1];"))))
    (testing "array_unique defaults to string comparison"
      (is (= "1.0,1" (out "<?php echo implode(',', array_unique(['1.0', '1']));"))))
    (testing "diff/intersect take further arrays"
      (is (= "1,4" (out "<?php echo implode(',', array_diff([1, 2, 3, 4], [2], [3]));")))
      (is (= "3" (out "<?php echo implode(',', array_intersect([1, 2, 3], [2, 3], [3, 4]));"))))
    (testing "array_splice normalizes bounds; omitted replacement removes"
      (is (= "1,3|2" (out "<?php $a = [1, 2, 3]; $r = array_splice($a, 1, 1); echo implode(',', $a), '|', implode(',', $r);")))
      (is (= "1,2|3" (out "<?php $a = [1, 2, 3]; $r = array_splice($a, -1); echo implode(',', $a), '|', implode(',', $r);"))))
    (testing "range with a zero step is a ValueError, not a hang"
      (is (= "ValueError" (out "<?php try { range(1, 3, 0); } catch (ValueError $e) { echo 'ValueError'; }"))))
    (testing "JSON escapes control bytes instead of replacing them"
      (is (= "\"\\u0001\"" (out "<?php echo json_encode(\"\\x01\");"))))
    (testing "JSON rejects trailing commas, bad escapes and unchecked separators"
      (is (= "NULL\n" (out "<?php var_dump(json_decode('[1,]'));")))
      (is (= "NULL\n" (out "<?php var_dump(json_decode('\"\\\\q\"'));")))
      (is (= "NULL\n" (out "<?php var_dump(json_decode('{\"a\" 4}'));"))))
    (testing "JSON enforces depth and JSON_THROW_ON_ERROR"
      (is (= "NULL\n" (out "<?php var_dump(json_decode('[[1]]', true, 1));")))
      (is (= "threw" (out "<?php try { json_decode('[1,]', null, 512, JSON_THROW_ON_ERROR); } catch (JsonException $e) { echo 'threw'; }")))
      (is (= "threw" (out "<?php try { json_encode(INF, JSON_THROW_ON_ERROR); } catch (JsonException $e) { echo 'threw'; }")))
      (is (= "bool(false)\n" (out "<?php var_dump(json_encode(INF));"))))
    (testing "EXTR constants match PHP and every mode imports per PHP"
      (is (= "4,5,6,7" (out "<?php echo EXTR_PREFIX_INVALID, ',', EXTR_PREFIX_IF_EXISTS, ',', EXTR_IF_EXISTS, ',', EXTR_PREFIX_ONLY_IF_EXISTS;")))
      (is (= "6" (out "<?php extract(['z' => 6], EXTR_PREFIX_ALL, 'p_'); echo $p__z;")))
      (is (= "12" (out "<?php $x = 1; extract(['x' => 2], EXTR_PREFIX_SAME, 'p'); echo $x, $p_x;")))
      (is (= "34" (out "<?php $x = 3; extract(['x' => 4], EXTR_PREFIX_IF_EXISTS, 'p'); echo $x, $p_x;")))
      (is (= "5" (out "<?php $n = extract(['0' => 5], EXTR_PREFIX_INVALID, 'p'); echo $p_0;"))))
    (testing "extract uses binding existence and compact warns on missing names"
      (is (= "null-kept" (out "<?php $x = null; extract(['x' => 4], EXTR_SKIP); echo $x === null ? 'null-kept' : $x;")))
      (is (= [[2 "compact(): Undefined variable $nope"]]
             (diag "<?php $a = compact('nope');"))))
    (testing "namespaced user functions shadow extract/compact builtins"
      (is (= "7" (out "<?php namespace N; function extract($a) { return 7; } echo extract([1]);")))
      (is (= "9" (out "<?php namespace N; function compact($a) { return 9; } echo compact('x');"))))))


(deftest p9-compile-refusals-test
  (doseq [[source reason]
          [["<?php eval('$x = 1;');" :eval]
           ["<?php $x = 1; $n = 'x'; $$n =& $x;" :dynamic-reference]
           ["<?php [[&$a]] = [[1]];" :yang.php/compile-error]
           ["<?php $GLOBALS = [];" :yang.php/compile-error]
           ["<?php unset($GLOBALS);" :yang.php/compile-error]]]
    (testing reason
      (let [{:keys [diagnostics]} (h/lower-program source)
            d (first diagnostics)]
        (is (seq diagnostics))
        (is (= reason (or (:construct d) (:yang.php/diagnostic d))))))))
