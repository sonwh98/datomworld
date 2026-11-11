(ns yang.php.antlr.programs-p9
  "P9 builtin surfaces, dynamic scopes, and engine-fidelity corner fixtures.
   Outputs are hand-derived from PHP 8.3; all remain available to the oracle.")


(defn- fx
  [id group source out & {:as more}]
  (merge {:id id, :group group, :source source, :out out, :oracle :pending}
         more))


(def strings-ii
  [(fx :substring-and-search :strings-ii
       "<?php echo substr('abcdef', 1, 3), strpos('abca', 'c'), strrpos('abca', 'a'), stripos('AbC', 'b');"
       "bcd231")
   (fx :replace-and-contains :strings-ii
       "<?php echo str_replace('a', 'x', 'abca'), str_contains('abc', 'b'), str_starts_with('abc', 'a'), str_ends_with('abc', 'c');"
       "xbcx111")
   (fx :trim-case-repeat :strings-ii
       "<?php echo trim(\" \\tA\\n\"), strtolower('AbC'), strtoupper('aBc'), str_repeat('x', 3), strrev('abc');"
       "AabcABCxxxcba")
   (fx :pad-compare-and-count :strings-ii
       "<?php echo str_pad('x', 3, '-'), strcmp('a', 'b'), strncmp('abcd', 'abzz', 2), substr_count('aaaa', 'aa');"
       "x---102")
   (fx :html-slashes-and-bytes :strings-ii
       "<?php echo bin2hex('Az'), hex2bin('417a'), ord('A'), chr(66), addslashes(\"a'b\"), stripslashes(\"a\\\\'b\");"
       "417aAz65Ba\\'ba'b")
   (fx :string-offset-write :strings-ii
       "<?php $s = 'abc'; $s[1] = 'XYZ'; $s[-1] = 'Z'; echo $s;"
       "aXZ"
       :diag [[2 "Only the first byte will be assigned to the string offset"]])])


(def arrays-ii
  [(fx :explode-split-reverse :arrays-ii
       "<?php echo implode('|', explode(',', 'a,b')), implode('', array_reverse(str_split('abc'))), array_product([2, 3, 4]);"
       "a|bcba24")
   (fx :search-fill-combine :arrays-ii
       "<?php echo array_search(2, [1, 2], true), implode(',', array_fill(0, 2, 'x')), implode(',', array_combine(['a', 'b'], [1, 2]));"
       "1x,x1,2")
   (fx :flip-pad-chunk :arrays-ii
       "<?php $f = array_flip(['a' => 3]); echo $f[3], implode(',', array_pad([1], 3, 0)), count(array_chunk([1, 2, 3], 2));"
       "a1,0,02")
   (fx :slice-splice :arrays-ii
       "<?php $a = [1, 2, 3, 4]; $r = array_splice($a, 1, 2, [8, 9]); echo implode(',', $a), '|', implode(',', $r), '|', implode(',', array_slice($a, 1));"
       "1,8,9,4|2,3|8,9,4")
   (fx :map-filter-reduce :arrays-ii
       "<?php $a = array_map(fn($x) => $x * $x, [1, 2, 3]); $a = array_filter($a, fn($x) => $x > 1); echo implode(',', $a), array_reduce($a, fn($x, $y) => $x + $y, 0);"
       "4,913")
   (fx :unique-diff-intersect :arrays-ii
       "<?php echo implode(',', array_unique([1, 1, 2])), '|', implode(',', array_diff([1, 2, 3], [2])), '|', implode(',', array_intersect([1, 2, 3], [2, 3]));"
       "1,2|1,3|2,3")
   (fx :range-and-list :arrays-ii
       "<?php echo implode(',', range(3, 1)), implode(',', range('a', 'c')), array_is_list([1, 2]), array_is_list([1 => 1]);"
       "3,2,1a,b,c1")])


(def json
  [(fx :encode-scalars-and-arrays :json
       "<?php echo json_encode(['a', true, null, 2]), json_encode(['a' => 4]);"
       "[\"a\",true,null,2]{\"a\":4}")
   (fx :encode-escapes-and-pretty :json
       "<?php echo json_encode(\"a\\nb\\\"c\"), json_encode([1], JSON_PRETTY_PRINT);"
       "\"a\\nb\\\"c\"[\n    1\n]")
   (fx :decode-scalars-and-array :json
       "<?php echo json_decode('1'), json_decode('\"x\"'), implode(',', json_decode('[1,2,3]', true));"
       "1x1,2,3")
   (fx :decode-object-and-escape :json
       "<?php $a = json_decode('{\"a\":4,\"s\":\"x\\ny\"}', true); echo $a['a'], json_encode($a['s']);"
       "4\"x\\ny\"")])


(def math-ii
  [(fx :rounding-and-absolute :math-ii
       "<?php echo abs(-3), ceil(1.2), floor(1.8), round(1.5), round(-1.5);"
       "3212-2")
   (fx :sqrt-pow-minmax :math-ii
       "<?php echo sqrt(9), pow(2, 3), min(3, 1, 2), max(3, 1, 2), is_finite(1.0), is_infinite(INF);"
       "381311")
   ;; round's precision and the four PHP 8.3 modes (docs examples #1-#4)
   (fx :round-precision :math-ii
       "<?php echo round(3.4), ' ', round(3.5), ' ', round(5.045, 2), ' ', round(5.055, 2), ' ', round(345, -2), ' ', round(678, -2), ' ', round(678, -3), ' ', round(135.79, 1), ' ', round(135.79, -1), ' ', round(135.79, -3);"
       "3 4 5.05 5.06 300 700 1000 135.8 140 0")
   (fx :round-modes :math-ii
       "<?php echo round(9.5, 0, 1), round(9.5, 0, 2), round(9.5, 0, 3), round(9.5, 0, 4), round(8.5, 0, 1), round(8.5, 0, 2), round(8.5, 0, 3), round(8.5, 0, 4), round(1.55, 1, 1), round(-1.55, 1, 1), round(1.55, 1, 2), round(-1.55, 1, 2), round(1.55, 1, 3), round(1.55, 1, 4);"
       "10910998891.6-1.61.5-1.51.61.5")
   ;; abs saturates the integer range per PHP 8.3: only PHP_INT_MIN crosses
   ;; to float(-(double)PHP_INT_MIN)
   (fx :abs-int-min :math-ii
       "<?php var_dump(abs(PHP_INT_MIN), abs(PHP_INT_MIN - 1));"
       "float(9.223372036854776E+18)\nfloat(9.223372036854776E+18)\n")
   ;; sqrt is correctly rounded over the whole double range, NAN on negatives
   (fx :sqrt-accuracy :math-ii
       "<?php var_dump(sqrt(2), sqrt(3), sqrt(1e16), sqrt(4e300), sqrt(pow(2.0, -200)));"
       "float(1.4142135623730951)\nfloat(1.7320508075688772)\nfloat(100000000)\nfloat(2.0E+150)\nfloat(7.888609052210118E-31)\n")
   (fx :sqrt-domain :math-ii
       "<?php echo sqrt(-1), sqrt(INF), sqrt(0.25), sqrt(1e-16);"
       "NANINF0.51.0E-8")
   ;; a single array argument is scanned; standard comparison, first of
   ;; equals wins; empty is a ValueError, a lone scalar a TypeError
   (fx :minmax-array-scan :math-ii
       "<?php echo min([3, 1, 2]), max([3, 1, 2]), min(['x' => 5, 'y' => 1, 'z' => 9]), max(range(1, 5)), min(2, 3, 1, 6, 7), max(1, '1');"
       "131511")
   (fx :minmax-comparison-and-errors :math-ii
       (str "<?php var_dump(min(-10, false, 10), min(0, true), min('hello', -1), max(1, '1')); "
            "try { min([]); } catch (ValueError $e) { echo $e->getMessage(); } echo '|'; "
            "try { max([]); } catch (ValueError $e) { echo $e->getMessage(); } echo '|'; "
            "try { min(5); } catch (TypeError $e) { echo $e->getMessage(); }")
       (str "bool(false)\nint(0)\nint(-1)\nint(1)\n"
            "min(): Argument #1 ($value) must contain at least one element"
            "|max(): Argument #1 ($value) must contain at least one element"
            "|min(): Argument #1 ($value) must be of type array, int given"))])


(def dynamic-scope
  [(fx :variable-variable-global :dynamic-scope
       "<?php $n = 'x'; $$n = 22; echo $x, $$n;"
       "2222")
   (fx :variable-variable-function :dynamic-scope
       "<?php function f($n) { $$n = 3; return $x; } echo f('x');"
       "3")
   (fx :extract-overwrite :dynamic-scope
       "<?php $x = 1; $n = extract(['x' => 4, 'y' => 5]); echo $n, $x, $y;"
       "245")
   (fx :extract-skip-and-prefix :dynamic-scope
       "<?php $x = 1; extract(['x' => 4, 'y' => 5], EXTR_SKIP); extract(['z' => 6], EXTR_PREFIX_ALL, 'p_'); echo $x, $y, $p_z;"
       "15"
       :diag [[2 "Undefined variable $p_z"]])
   (fx :compact-nested :dynamic-scope
       "<?php $x = 3; $y = 4; $a = compact('x', ['y', 'missing']); echo implode(',', $a);"
       "3,4"
       :diag [[2 "compact(): Undefined variable $missing"]])
   (fx :globals-element-write :dynamic-scope
       "<?php $GLOBALS['x'] = 7; echo $x, $GLOBALS['x'];"
       "77")])


(def corners
  [(fx :extract-refs-refusal :corners
       "<?php try { extract(['x' => 1], EXTR_REFS); } catch (Error $e) { echo $e->getMessage(); }"
       "extract(EXTR_REFS) is not supported (corner C11)")
   (fx :array-multisort-refusal :corners
       "<?php $a = [1]; try { array_multisort($a); } catch (Error $e) { echo $e->getMessage(); }"
       "array_multisort is a prefer-ref builtin and is not supported (corner C6)")
   (fx :string-append-refusal :corners
       "<?php $s = 'a'; try { $s[] = 'b'; } catch (Error $e) { echo $e->getMessage(); }"
       "[] operator not supported for strings")])


(def fixtures
  (vec (concat strings-ii arrays-ii json math-ii dynamic-scope corners)))
