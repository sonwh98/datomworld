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
      (is (= "3" (out "<?php echo strrpos('hello', 'l', -2);")))
      (is (= "ValueError" (out "<?php try { strrpos('hello', 'l', 6); } catch (ValueError $e) { echo 'ValueError'; }")))
      (is (= "ValueError" (out "<?php try { strrpos('hello', 'l', -6); } catch (ValueError $e) { echo 'ValueError'; }"))))
    (testing "trim character masks with ranges"
      (is (= "a" (out "<?php echo trim('xxaxx', 'x');")))
      (is (= "def" (out "<?php echo trim('abcdef', 'a..c');"))))
    (testing "single-array array_map keeps keys; multi-array is variadic"
      (is (= "a,b" (out "<?php echo implode(',', array_keys(array_map(fn($x) => $x * 2, ['a' => 1, 'b' => 2])));")))
      (is (= "11,22" (out "<?php echo implode(',', array_map(fn($a, $b) => $a + $b, [1, 2], [10, 20]));")))
      (is (= "b" (out "<?php $r = array_map(null, [1, 2], ['a', 'b']); echo $r[1][1];"))))
    (testing "array_unique defaults to SORT_STRING: '1.0' and '1' are distinct strings"
      (is (= "1.0,1" (out "<?php echo implode(',', array_unique(['1.0', '1']));")))
      (is (= "1.0,1" (out "<?php echo implode(',', array_unique(['1.0', '1'], SORT_STRING));")))
      (is (= "1.0" (out "<?php echo implode(',', array_unique(['1.0', '1'], SORT_NUMERIC));")))
      (is (= "1.0" (out "<?php echo implode(',', array_unique(['1.0', '1'], SORT_REGULAR));"))))
    (testing "diff/intersect take further arrays"
      (is (= "1,4" (out "<?php echo implode(',', array_diff([1, 2, 3, 4], [2], [3]));")))
      (is (= "3" (out "<?php echo implode(',', array_intersect([1, 2, 3], [2, 3], [3, 4]));"))))
    (testing "diff/intersect compare as strings"
      (is (= "1.0" (out "<?php echo implode(',', array_diff(['1.0'], ['1']));")))
      (is (= "" (out "<?php echo implode(',', array_intersect(['1.0'], ['1']));"))))
    (testing "array_splice normalizes bounds; omitted replacement removes"
      (is (= "1,3|2" (out "<?php $a = [1, 2, 3]; $r = array_splice($a, 1, 1); echo implode(',', $a), '|', implode(',', $r);")))
      (is (= "1,2|3" (out "<?php $a = [1, 2, 3]; $r = array_splice($a, -1); echo implode(',', $a), '|', implode(',', $r);"))))
    (testing "array_splice drops replacement string keys; null replaces with nothing"
      (is (= "1,8,9,3|2" (out "<?php $a = [1, 2, 3]; $r = array_splice($a, 1, 1, ['x' => 8, 9]); echo implode(',', $a), '|', implode(',', $r);")))
      (is (= "1,3|2" (out "<?php $a = [1, 2, 3]; $r = array_splice($a, 1, 1, null); echo implode(',', $a), '|', implode(',', $r);"))))
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
      (is (= "NULL\n" (out "<?php var_dump(json_decode('[1]', true, 1));")))
      (is (= "threw" (out "<?php try { json_decode('[1,]', null, 512, JSON_THROW_ON_ERROR); } catch (JsonException $e) { echo 'threw'; }")))
      (is (= "threw" (out "<?php try { json_encode(INF, JSON_THROW_ON_ERROR); } catch (JsonException $e) { echo 'threw'; }")))
      (is (= "bool(false)\n" (out "<?php var_dump(json_encode(INF));"))))
    (testing "JSON keeps plus in exponents; rejects leading plus, leading zeros, raw controls, bad depth and bad flags"
      (is (= "float(100)\n" (out "<?php var_dump(json_decode('1e+2'));")))
      (is (= "float(100)\n" (out "<?php var_dump(json_decode('1E+2'));")))
      (is (= "NULL\n" (out "<?php var_dump(json_decode('.1'));")))
      (is (= "NULL\n" (out "<?php var_dump(json_decode('1.'));")))
      (is (= "NULL\n" (out "<?php var_dump(json_decode('01'));")))
      (is (= "NULL\n" (out "<?php var_dump(json_decode('-01'));")))
      (is (= "NULL\n" (out "<?php var_dump(json_decode('+1'));")))
      (is (= "NULL\n" (out "<?php var_dump(json_decode(\"\\\"\\x01\\\"\"));")))
      (is (= "refused" (out "<?php try { json_encode([1], 64); } catch (Error $e) { echo 'refused'; }")))
      (is (= "refused" (out "<?php try { json_decode('[1]', true, 512, 64); } catch (Error $e) { echo 'refused'; }")))
      (is (= "ValueError" (out "<?php try { json_encode([1], 0, 0); } catch (ValueError $e) { echo 'ValueError'; }"))))
    (testing "an encoding failure answers false with no warning, never a host error"
      (is (= "bool(false)\n" (out "<?php var_dump(json_encode([INF]));")))
      (is (= [] (diag "<?php $x = json_encode([INF]);")))
      (is (= [] (diag "<?php $x = json_encode([1], 0, 1);")))
      (is (= "string(3) \"[1]\"\n" (out "<?php var_dump(json_encode([1], 0, 1));")))
      (is (= "bool(false)\n" (out "<?php var_dump(json_encode([[1]], 0, 1));")))
      (is (= "threw" (out "<?php try { json_encode([INF], JSON_THROW_ON_ERROR); } catch (JsonException $e) { echo 'threw'; }"))))
    (testing "EXTR constants match PHP and every mode imports per PHP"
      (is (= "4,5,6" (out "<?php echo EXTR_PREFIX_INVALID, ',', EXTR_PREFIX_IF_EXISTS, ',', EXTR_IF_EXISTS;")))
      (is (= "0" (out "<?php echo defined('EXTR_PREFIX_ONLY_IF_EXISTS') ? '1' : '0';")))
      (is (= "ValueError" (out "<?php try { extract(['x' => 1], 7); } catch (ValueError $e) { echo 'ValueError'; }")))
      (is (= "6" (out "<?php extract(['z' => 6], EXTR_PREFIX_ALL, 'p_'); echo $p__z;")))
      (is (= "12" (out "<?php $x = 1; extract(['x' => 2], EXTR_PREFIX_SAME, 'p'); echo $x, $p_x;")))
      (is (= "34" (out "<?php $x = 3; extract(['x' => 4], EXTR_PREFIX_IF_EXISTS, 'p'); echo $x, $p_x;")))
      (is (= "5" (out "<?php $n = extract(['0' => 5], EXTR_PREFIX_INVALID, 'p'); echo $p_0;")))
      (is (= "5" (out "<?php $n = extract(['0' => 5], EXTR_PREFIX_ALL, 'p'); echo $p_0;")))
      (is (= "6" (out "<?php $n = extract(['z' => 6], EXTR_PREFIX_ALL, ''); echo $_z;")))
      (is (= "ValueError" (out "<?php try { extract(['x' => 1], 8); } catch (ValueError $e) { echo 'ValueError'; }")))
      (is (= "ValueError" (out "<?php try { extract(['x' => 1], EXTR_PREFIX_SAME); } catch (ValueError $e) { echo 'ValueError'; }")))
      (is (= "ValueError" (out "<?php try { extract(['x' => 1], EXTR_PREFIX_SAME, '9x'); } catch (ValueError $e) { echo 'ValueError'; }"))))
    (testing "extract uses binding existence and compact warns on missing names"
      (is (= "null-kept" (out "<?php $x = null; extract(['x' => 4], EXTR_SKIP); echo $x === null ? 'null-kept' : $x;")))
      (is (= [[2 "compact(): Undefined variable $nope"]]
             (diag "<?php $a = compact('nope');")))
      (is (= [[2 "compact(): Each argument must be a string or an array of strings"]]
             (diag "<?php $a = compact(1);"))))
    (testing "namespaced user functions shadow extract/compact builtins"
      (is (= "7" (out "<?php namespace N; function extract($a) { return 7; } echo extract([1]);")))
      (is (= "9" (out "<?php namespace N; function compact($a) { return 9; } echo compact('x');"))))))


(deftest p9-math-contracts-test
  (let [out (fn [source] (:php/out (h/run-raw source)))]
    (testing "round keeps PHP's pre-round scale so decimal ties hold"
      (is (= "5.05" (out "<?php echo round(5.045, 2);")))
      (is (= "5.06" (out "<?php echo round(5.055, 2);")))
      (is (= "1.5" (out "<?php echo round(1.4999999999999998, 1);")))
      (is (= "0.29" (out "<?php echo round(0.285, 2);"))))
    (testing "round modes are HALF_UP, HALF_DOWN, HALF_EVEN, HALF_ODD"
      (is (= "109109" (out "<?php echo round(9.5, 0, 1), round(9.5, 0, 2), round(9.5, 0, 3), round(9.5, 0, 4);")))
      (is (= "9889" (out "<?php echo round(8.5, 0, 1), round(8.5, 0, 2), round(8.5, 0, 3), round(8.5, 0, 4);")))
      (is (= "2" (out "<?php echo round(2.5, 0, 3);")))
      (is (= "4" (out "<?php echo round(3.5, 0, 3);")))
      (is (= "3" (out "<?php echo round(2.5, 0, 4);")))
      (is (= "3" (out "<?php echo round(3.5, 0, 4);")))
      (is (= "-8-10" (out "<?php echo round(-8.5, 0, 3), round(-9.5, 0, 3);")))
      (is (= "-9-9" (out "<?php echo round(-8.5, 0, 4), round(-9.5, 0, 4);"))))
    (testing "an invalid mode silently rounds half up in 8.3, never throwing"
      (is (= "3" (out "<?php echo round(2.5, 0, 0);")))
      (is (= "3" (out "<?php echo round(2.5, 0, 99);"))))
    (testing "round answers a float and passes specials through"
      (is (= "float(5)\n" (out "<?php var_dump(round(5));")))
      (is (= "float(1.6)\n" (out "<?php var_dump(round(1.55, 1));")))
      (is (= "float(-0)\n" (out "<?php var_dump(round(-0.4));")))
      (is (= "NAN" (out "<?php echo round(NAN);")))
      (is (= "INF" (out "<?php echo round(INF, 2);"))))
    (testing "round handles deep and out-of-range precision per PHP"
      (is (= "1.5E-8" (out "<?php echo round(1.5e-8, 12);")))
      (is (= "2.5E-13" (out "<?php echo round(2.5e-13, 25);")))
      (is (= "1.5" (out "<?php echo round(1.5, 400);"))))
    (testing "abs keeps int for ints, float for floats and PHP_INT_MIN's edge"
      (is (= "int(5)\n" (out "<?php var_dump(abs(-5));")))
      (is (= "float(4.2)\n" (out "<?php var_dump(abs(-4.2));")))
      (is (= "bool(true)\n" (out "<?php var_dump(abs(PHP_INT_MIN) == 9223372036854775808);")))
      (is (= "int(9223372036854775807)\n" (out "<?php var_dump(abs(PHP_INT_MAX));"))))
    (testing "sqrt is exact on perfect squares across magnitudes"
      (is (= "bool(true)\n" (out "<?php var_dump(sqrt(1e16) == 1e8);")))
      (is (= "bool(true)\n" (out "<?php var_dump(sqrt(4e300) == 2e150);")))
      (is (= "bool(true)\n" (out "<?php var_dump(sqrt(pow(2.0, -200)) == pow(2.0, -100));")))
      (is (= "bool(true)\n" (out "<?php var_dump(sqrt(pow(2.0, 600)) == pow(2.0, 300));"))))
    (testing "min/max scan a single array and keep the first of equals"
      (is (= "13" (out "<?php echo min([3, 1, 2]), max([3, 1, 2]);")))
      (is (= "int(2)\n" (out "<?php var_dump(max(2, 2.0));")))
      (is (= "float(2)\n" (out "<?php var_dump(min(2.0, 2));")))
      (is (= "int(42)\n" (out "<?php var_dump(min('string', [2, 5, 7], 42));"))))))


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
