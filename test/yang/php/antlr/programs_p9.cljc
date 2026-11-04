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
       "aXZ")])


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
       "381311")])


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
       "156")
   (fx :compact-nested :dynamic-scope
       "<?php $x = 3; $y = 4; $a = compact('x', ['y', 'missing']); echo implode(',', $a);"
       "3,4")
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
