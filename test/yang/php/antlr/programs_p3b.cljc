(ns yang.php.antlr.programs-p3b
  "Stage 0 (P3 remainder, branch yang-php-p3b) fixture data: sources, expected
   output pinned from the normative rows of docs/design/yang.php.md section 4,
   the registry and the activation manifest (P4 plan v2, correction 7).

   Schema (extends `yang.php.antlr.programs`):
     :id        keyword
     :rows      design rows (section 4) the fixture pins
     :group     :ref-lifecycle-v1 | :iteration-v1 | :temps-v1 | :calls-v1 |
                :closures-v1 | :destructure-v1 | :oracle-only
     :level     :program (lowered and run) | :prelude (cell-level, no source run)
     :requires  implementation steps (P3 plan S2..S8) that must be complete
     :source    PHP source
     :out       expected stdout pinned from the rows, or absent for oracle-only
     :oracle    `:pending` until the shiva recording pass has run; `pin` then
                folds the recorded result. A recorded result is authoritative
                and replaces any hand-authored output pin.

   `all-fixtures` is unconditional (the oracle iterates it). `enabled` is the
   subset whose :requires are all in `completed-steps`; implementation runners
   execute only that subset. Activation is monotonic: a step is added to
   `completed-steps` when its slice lands, never removed.")


(def completed-steps
  "Implementation steps of the P3 plan that have landed. S1 is the counted-cell
   substrate (b317c139); S2 to S5 (marker-aware variables, write/unset fetches, `=&`, `global`,
   `static` binding, by-reference parameters, marker-preserving sorts) are
   confirmed by the parser run."
  #{:s1 :s2 :s3 :s4 :s5 :s6 :s7 :s8})


(defn- fx
  [id group rows requires source out]
  (cond-> {:id id,
           :group group,
           :rows rows,
           :level :program,
           :requires requires,
           :source source,
           :oracle :pending}
    out (assoc :out out)))


(def ref-lifecycle-v1
  [(fx :ref-alias-write :ref-lifecycle-v1 [2 4] #{:s4}
       "<?php $x = 1; $y = &$x; $y = 2; echo $x;" "2")
   (fx :ref-unset-breaks-binding :ref-lifecycle-v1 [8] #{:s4}
       "<?php $x = 1; $y = &$x; unset($y); $y = 5; echo $x, $y;" "15")
   (fx :ref-self-assign :ref-lifecycle-v1 [4] #{:s4}
       "<?php $x = 1; $x = &$x; $x = 3; echo $x;" "3")
   (fx :ref-element-survives-copy :ref-lifecycle-v1 [1 6] #{:s4}
       "<?php $a = [1, 2]; $r = &$a[0]; $b = $a; $b[0] = 9; echo $a[0];" "9")
   (fx :ref-element-unwrapped-after-unset :ref-lifecycle-v1 [1 6 8] #{:s4}
       "<?php $a = [1]; $r = &$a[0]; unset($r); $b = $a; $b[0] = 9; echo $a[0];" "1")
   (fx :ref-element-target :ref-lifecycle-v1 [5] #{:s4}
       "<?php $x = 1; $a = []; $a[0] = &$x; $a[0] = 4; echo $x;" "4")
   (fx :ref-array-literal-element :ref-lifecycle-v1 [3 5] #{:s3}
       "<?php $x = 1; $a = [&$x]; $a[0] = 2; echo $x;" "2")
   (fx :ref-array-literal-nested-path :ref-lifecycle-v1 [3 10] #{:s3}
       "<?php $a = ['x' => [1]]; $r = &$a['x'][0]; $r = 7; echo $a['x'][0];" "7")
   (fx :ref-vivify-by-reference :ref-lifecycle-v1 [3] #{:s4}
       "<?php $r = &$a['k']; $r = 5; echo $a['k'];" "5")
   (fx :ref-global-binds-local :ref-lifecycle-v1 [4 17] #{:s5}
       "<?php $g = 1; function f() { global $g; $g = 2; } f(); echo $g;" "2")
   (fx :ref-global-unset-keeps-global :ref-lifecycle-v1 [8] #{:s5}
       "<?php $g = 1; function f() { global $g; unset($g); $g = 5; } f(); echo $g;" "1")
   (fx :ref-global-at-root-noop :ref-lifecycle-v1 [4] #{:s5}
       "<?php $g = 1; global $g; $g = 2; echo $g;" "2")
   (fx :ref-static-binds-persistent-slot :ref-lifecycle-v1 [17] #{:s5}
       "<?php function c() { static $n = 0; return ++$n; } c(); c(); echo c();" "3")
   (fx :ref-static-rebind-leaves-persistent :ref-lifecycle-v1 [17] #{:s5}
       (str "<?php function c() { static $n = 0; $y = 100; $n++; $n = &$y; return $n; }"
            " echo c(), c();")
       "100100")
   (fx :ref-param-by-reference :ref-lifecycle-v1 [13 14] #{:s5}
       "<?php function inc(&$n) { $n++; } $v = 1; inc($v); echo $v;" "2")
   (fx :ref-param-array-element :ref-lifecycle-v1 [14 35] #{:s5}
       "<?php function f(&$n) { $n = 7; } $a = [1]; f($a[0]); echo $a[0];" "7")
   (fx :ref-param-by-value-is-a-copy :ref-lifecycle-v1 [13] #{:s5}
       "<?php function f($n) { $n = 7; } $v = 1; f($v); echo $v;" "1")
   (fx :ref-builtin-sort-over-reference-slot :ref-lifecycle-v1 [21] #{:s5}
       "<?php $x = 3; $a = [&$x, 1, 2]; sort($a); $x = 9; echo implode(',', $a);"
       "1,2,9")
   (fx :ref-function-release-cvs :ref-lifecycle-v1 [16] #{:s2}
       "<?php function f() { $a = [1, 2]; $b = $a; $b[] = 3; return count($b); } echo f(), f();"
       "33")])


(def iteration-v1
  [(fx :foreach-ref-doubles :iteration-v1 [24 25 27] #{:s7}
       "<?php $a = [1, 2, 3]; foreach ($a as &$v) { $v *= 2; } unset($v); echo implode(',', $a);"
       "2,4,6")
   (fx :foreach-ref-leftover-binding :iteration-v1 [24 27] #{:s7}
       "<?php $a = [1, 2, 3]; foreach ($a as &$v) {} foreach ($a as $v) {} echo implode(',', $a);"
       "1,2,2")
   (fx :foreach-ref-leftover-unset-fixes :iteration-v1 [24 27] #{:s7}
       (str "<?php $a = [1, 2, 3]; foreach ($a as &$v) {} unset($v);"
            " foreach ($a as $v) {} echo implode(',', $a);")
       "1,2,3")
   (fx :foreach-ref-append-while-iterating :iteration-v1 [25 26] #{:s7}
       (str "<?php $a = [1, 2]; foreach ($a as &$v) { if ($v < 3) { $a[] = $v + 2; } echo $v; }"
            " unset($v);")
       "1234")
   (fx :foreach-ref-unset-current :iteration-v1 [26] #{:s7}
       (str "<?php $a = [1, 2, 3]; foreach ($a as $k => &$v) { if ($k == 0) { unset($a[1]); }"
            " echo $v; } unset($v);")
       "13")
   (fx :foreach-ref-break-frees-iterator :iteration-v1 [27] #{:s7}
       (str "<?php $a = [1, 2, 3]; foreach ($a as &$v) { break; } unset($v);"
            " $b = $a; $b[] = 4; echo count($a), count($b);")
       "34")
   (fx :foreach-by-value-snapshot :iteration-v1 [28] #{:s7}
       "<?php $a = [1, 2]; foreach ($a as $v) { $a[] = 9; echo $v; } echo count($a);"
       "124")
   (fx :foreach-key-and-destructure :iteration-v1 [28] #{:s7 :s8 :grammar-foreach-key-destructure}
       ;; oracle-vs-design: the ANTLR grammar has no `as $k => [$x, $y]`
       ;; (the key form takes only a chain after `=>`); the source is a parse
       ;; error here. Escalated; `:foreach-destructure` is the supported form.
       "<?php foreach ([[1, 2], [3, 4]] as $k => [$x, $y]) { echo $k, $x, $y; }"
       "012134")
   (fx :foreach-destructure :iteration-v1 [28] #{:s7 :s8}
       "<?php foreach ([[1, 2], [3, 4]] as [$x, $y]) { echo $x, $y; }"
       "1234")
   (fx :array-pointer-reset-end :iteration-v1 [55] #{:s7}
       "<?php $a = [1, 2, 3]; next($a); echo current($a); end($a); echo current($a); reset($a); echo current($a);"
       "231")
   (fx :array-pointer-copied-with-value :iteration-v1 [55] #{:s7}
       "<?php $a = [1, 2, 3]; next($a); $b = $a; echo current($b);"
       "2")])


(def calls-v1
  [(fx :call-dynamic-variable :calls-v1 [53] #{:s6}
       "<?php $f = 'strlen'; echo $f('abc');" "3")
   (fx :call-dynamic-dim :calls-v1 [53] #{:s6 :grammar-arrow-in-array}
       ;; oracle-vs-design: the ANTLR grammar reads `[fn($x) => $x + 1]` as the
       ;; pair `fn($x) => ($x + 1)` (a call as key); PHP parses an arrow
       ;; function. Escalated to the architect; the parenthesised variant below
       ;; is what runs.
       "<?php $a = [fn($x) => $x + 1]; echo $a[0](1);" "2")
   (fx :call-dynamic-dim-paren :calls-v1 [53] #{:s6}
       "<?php $a = [(fn($x) => $x + 1)]; echo $a[0](1);" "2")
   (fx :call-dynamic-chained :calls-v1 [53] #{:s6}
       "<?php $mk = fn($x) => fn($y) => $x + $y; echo $mk(1)(2);" "3")
   (fx :call-named-arguments-reorder :calls-v1 [57 63] #{:s6}
       "<?php function f($a, $b = 2) { return $a . '-' . $b; } echo f(b: 5, a: 1);" "1-5")
   (fx :call-named-default-skipped :calls-v1 [63] #{:s6}
       "<?php function f($a = 1, $b = 2, $c = 3) { return $a . $b . $c; } echo f(c: 9);" "129")
   (fx :call-spread-positional :calls-v1 [56] #{:s6}
       "<?php function f($a, $b) { return $a + $b; } echo f(...[1, 2]);" "3")
   (fx :call-variadic-collects :calls-v1 [56 33] #{:s6}
       "<?php function s(...$xs) { return count($xs) . array_sum($xs); } echo s(1, 2, 3);" "36")
   (fx :call-variadic-named-collected :calls-v1 [57 63] #{:s6}
       "<?php function s(...$xs) { return implode(',', array_keys($xs)); } echo s(1, k: 2);"
       "0,k")
   (fx :call-too-few-named-not-passed :calls-v1 [63] #{:s6}
       (str "<?php function f($a, $b) { return $a; } try { f(b: 1); }"
            " catch (ArgumentCountError $e) { echo $e->getMessage(); }")
       "f(): Argument #1 ($a) not passed")
   (fx :call-unknown-named-argument :calls-v1 [57] #{:s6}
       (str "<?php function f($a) { return $a; } try { f(1, z: 2); }"
            " catch (Error $e) { echo $e->getMessage(); }")
       "Unknown named parameter $z")
   (assoc (fx :call-too-few-named-uncaught :calls-v1 [63] #{:s6}
              "<?php function f($a, $b) { return $a; } f(b: 1);" "")
          :uncaught {:class "ArgumentCountError", :message "f(): Argument #1 ($a) not passed"})
   (assoc (fx :call-unknown-named-uncaught :calls-v1 [57] #{:s6}
              "<?php function f($a) { return $a; } f(1, z: 2);" "")
          :uncaught {:class "Error", :message "Unknown named parameter $z"})
   (fx :call-return-owned-temp-released :calls-v1 [36 37] #{:s6}
       "<?php function mk() { return [1, 2, 3]; } echo count(mk()), count(mk());" "33")])


(def closures-v1
  [(fx :closure-use-by-value :closures-v1 [1] #{:s6}
       "<?php $x = 1; $f = function () use ($x) { return $x; }; $x = 2; echo $f();" "1")
   (fx :closure-use-by-reference :closures-v1 [4 5] #{:s6}
       "<?php $x = 1; $f = function () use (&$x) { return $x; }; $x = 2; echo $f();" "2")
   (fx :closure-use-by-reference-writes :closures-v1 [5] #{:s6}
       "<?php $x = 1; $f = function () use (&$x) { $x++; }; $f(); $f(); echo $x;" "3")
   (fx :closure-counter-factory :closures-v1 [4 16 40] #{:s6}
       (str "<?php $mk = function () { $n = 0; return function () use (&$n) { return ++$n; }; };"
            " $c = $mk(); $c(); $c(); echo $c();")
       "3")
   (fx :closure-static-per-object :closures-v1 [17] #{:s5 :s6}
       "<?php $f = function () { static $n = 0; return ++$n; }; $f(); echo $f();" "2")
   (fx :arrow-captures-by-value-at-creation :closures-v1 [1] #{:s6}
       "<?php $y = 3; $f = fn($x) => $x + $y; $y = 10; echo $f(1);" "4")
   (fx :arrow-nested-transitive-capture :closures-v1 [1] #{:s6}
       "<?php $a = 1; $f = fn($x) => fn($y) => $x + $y + $a; echo $f(2)(3);" "6")
   (fx :closure-bind-class-seam :closures-v1 [] #{:s6}
       "<?php $f = function () { return 1; }; $g = Closure::bind($f, null, null); echo $g();"
       "1")])


(def destructure-v1
  [(fx :list-short-positional :destructure-v1 [1] #{:s8}
       "<?php [$a, $b] = [1, 2]; echo $a, $b;" "12")
   (fx :list-swap :destructure-v1 [1] #{:s8}
       "<?php $a = 1; $b = 2; [$a, $b] = [$b, $a]; echo $a, $b;" "21")
   (fx :list-skip :destructure-v1 [1] #{:s8}
       "<?php list($a, , $c) = [1, 2, 3]; echo $a, $c;" "13")
   (fx :list-keyed :destructure-v1 [1] #{:s8}
       "<?php ['x' => $p, 'y' => $q] = ['x' => 1, 'y' => 2]; echo $p, $q;" "12")
   (fx :list-nested :destructure-v1 [1] #{:s8}
       "<?php [[$a, $b], $c] = [[1, 2], 3]; echo $a, $b, $c;" "123")
   (fx :list-writes-through-fetches :destructure-v1 [3] #{:s8}
       "<?php $t = []; [$t[0], $t[1]] = [7, 8]; echo $t[0], $t[1];" "78")
   (fx :list-by-reference-element :destructure-v1 [5] #{:s8}
       "<?php $arr = [1, 2]; [$a, &$b] = $arr; $b = 9; echo $arr[1];" "9")
   (fx :list-null-source-assigns-null :destructure-v1 [1] #{:s8}
       "<?php [$a, $b] = null; var_dump($a, $b);" "NULL\nNULL\n")])


(def oracle-only
  "Fixtures that exist to be recorded on shiva. Release of a reference cycle
   is not observable from PHP source without a destructor (a P4 construct) or
   `gc_collect_cycles()`, which yang.php does not provide; R2 condition 1 is
   therefore split: the oracle records the collector's observation, and the
   `:ref-cycle-unset-terminates` program-level fixture pins that the engine
   terminates and prints the same text. The architect adjudicates the missing
   release observation as a known gap (P4 supplies a destructor probe)."
  [{:id :oracle-cycle-release-gc,
    :group :oracle-only,
    :rows [18],
    :level :oracle-only,
    :requires #{},
    :source (str "<?php $a = [1]; $a[0] = &$a; unset($a);"
                 " $n = gc_collect_cycles(); echo $n > 0 ? 'collected' : 'none';"),
    :oracle :pending,
    :note "recorded only; yang.php has no gc_collect_cycles"}
   {:id :oracle-cycle-release-memory,
    :group :oracle-only,
    :rows [18],
    :level :oracle-only,
    :requires #{},
    :source (str "<?php gc_disable(); $m = memory_get_usage();"
                 " for ($i = 0; $i < 2000; $i++) { $a = [1]; $a[0] = &$a; unset($a); }"
                 " echo memory_get_usage() > $m + 100000 ? 'leaks' : 'flat';"),
    :oracle :pending,
    :note "recorded only; shows cycles are not freed without the collector"}
   (fx :ref-cycle-unset-terminates :ref-lifecycle-v1 [18] #{:s4}
       "<?php $a = [1]; $a[0] = &$a; unset($a); echo 'done';" "done")
   (fx :ref-cycle-count :ref-lifecycle-v1 [18] #{:s4}
       "<?php $a = [1]; $a[0] = &$a; echo count($a), count($a[0]);" "11")])


(def recorded
  "Oracle RECORD values from the shiva pass (php-8.3.35 shiva 2026-10-25,
   docker php:8.3-cli), copied exactly from the pass log. A fixture listed
   here has its :oracle set to the banner by `pin`."
  {:ref-alias-write {:out "2", :diag [], :uncaught nil}
   :ref-unset-breaks-binding {:out "15", :diag [], :uncaught nil}
   :ref-self-assign {:out "3", :diag [], :uncaught nil}
   :ref-element-survives-copy {:out "9", :diag [], :uncaught nil}
   :ref-element-unwrapped-after-unset {:out "1", :diag [], :uncaught nil}
   :ref-element-target {:out "4", :diag [], :uncaught nil}
   :ref-array-literal-element {:out "2", :diag [], :uncaught nil}
   :ref-array-literal-nested-path {:out "7", :diag [], :uncaught nil}
   :ref-vivify-by-reference {:out "5", :diag [], :uncaught nil}
   :ref-global-binds-local {:out "2", :diag [], :uncaught nil}
   :ref-global-unset-keeps-global {:out "1", :diag [], :uncaught nil}
   :ref-global-at-root-noop {:out "2", :diag [], :uncaught nil}
   :ref-static-binds-persistent-slot {:out "3", :diag [], :uncaught nil}
   :ref-static-rebind-leaves-persistent {:out "100100", :diag [], :uncaught nil}
   :ref-param-by-reference {:out "2", :diag [], :uncaught nil}
   :ref-param-array-element {:out "7", :diag [], :uncaught nil}
   :ref-param-by-value-is-a-copy {:out "1", :diag [], :uncaught nil}
   :ref-builtin-sort-over-reference-slot {:out "1,2,9", :diag [], :uncaught nil}
   :ref-function-release-cvs {:out "33", :diag [], :uncaught nil}
   :foreach-ref-doubles {:out "2,4,6", :diag [], :uncaught nil}
   :foreach-ref-leftover-binding {:out "1,2,2", :diag [], :uncaught nil}
   :foreach-ref-leftover-unset-fixes {:out "1,2,3", :diag [], :uncaught nil}
   :foreach-ref-append-while-iterating {:out "1234", :diag [], :uncaught nil}
   :foreach-ref-unset-current {:out "13", :diag [], :uncaught nil}
   :foreach-ref-break-frees-iterator {:out "34", :diag [], :uncaught nil}
   :foreach-by-value-snapshot {:out "124", :diag [], :uncaught nil}
   :foreach-key-and-destructure {:out "012134", :diag [], :uncaught nil}
   :array-pointer-reset-end {:out "231", :diag [], :uncaught nil}
   :array-pointer-copied-with-value {:out "2", :diag [], :uncaught nil}
   :call-dynamic-variable {:out "3", :diag [], :uncaught nil}
   :call-dynamic-dim {:out "2", :diag [], :uncaught nil}
   :call-dynamic-chained {:out "3", :diag [], :uncaught nil}
   :call-named-arguments-reorder {:out "1-5", :diag [], :uncaught nil}
   :call-named-default-skipped {:out "129", :diag [], :uncaught nil}
   :call-spread-positional {:out "3", :diag [], :uncaught nil}
   :call-variadic-collects {:out "36", :diag [], :uncaught nil}
   :call-variadic-named-collected {:out "0,k", :diag [], :uncaught nil}
   :call-too-few-named-not-passed {:out "f(): Argument #1 ($a) not passed", :diag [], :uncaught nil}
   :call-unknown-named-argument {:out "Unknown named parameter $z", :diag [], :uncaught nil}
   :call-return-owned-temp-released {:out "33", :diag [], :uncaught nil}
   :closure-use-by-value {:out "1", :diag [], :uncaught nil}
   :closure-use-by-reference {:out "2", :diag [], :uncaught nil}
   :closure-use-by-reference-writes {:out "3", :diag [], :uncaught nil}
   :closure-counter-factory {:out "3", :diag [], :uncaught nil}
   :closure-static-per-object {:out "2", :diag [], :uncaught nil}
   :arrow-captures-by-value-at-creation {:out "4", :diag [], :uncaught nil}
   :arrow-nested-transitive-capture {:out "6", :diag [], :uncaught nil}
   :closure-bind-class-seam {:out "1", :diag [], :uncaught nil}
   :list-short-positional {:out "12", :diag [], :uncaught nil}
   :list-swap {:out "21", :diag [], :uncaught nil}
   :list-skip {:out "13", :diag [], :uncaught nil}
   :list-keyed {:out "12", :diag [], :uncaught nil}
   :list-nested {:out "123", :diag [], :uncaught nil}
   :list-writes-through-fetches {:out "78", :diag [], :uncaught nil}
   :list-by-reference-element {:out "9", :diag [], :uncaught nil}
   :list-null-source-assigns-null {:out "NULL\nNULL\n", :diag [], :uncaught nil}
   :oracle-cycle-release-gc {:out "collected", :diag [], :uncaught nil}
   :oracle-cycle-release-memory {:out "leaks", :diag [], :uncaught nil}
   :ref-cycle-unset-terminates {:out "done", :diag [], :uncaught nil}
   :call-dynamic-dim-paren {:out "2", :diag [], :uncaught nil}
   :call-too-few-named-uncaught {:out "", :diag [], :uncaught {:class "ArgumentCountError", :message "f(): Argument #1 ($a) not passed"}}
   :call-unknown-named-uncaught {:out "", :diag [], :uncaught {:class "Error", :message "Unknown named parameter $z"}}
   :typed-ref-row43-increment-past-max {:out "Cannot increment a reference held by property C::$p of type int past its maximal value", :diag [], :uncaught nil}
   :closure-call-stdclass {:out "", :diag [[2 "Cannot bind closure to scope of internal class stdClass"]], :uncaught nil}
   :ref-cycle-count {:out "11", :diag [], :uncaught nil}
   :foreach-destructure {:out "1234", :diag [], :uncaught nil}
   :try-catch-message {:out "Call to undefined function nope()", :diag [], :uncaught nil}
   :try-finally-runs-on-return {:out "F1", :diag [], :uncaught nil}
   :try-unmatched-catch-propagates {:out "outer", :diag [], :uncaught nil}
   :try-finally-runs-on-error {:out "FC", :diag [], :uncaught nil}
   :throw-rethrows-caught {:out "re", :diag [], :uncaught nil}
   :catch-multi-type {:out "multi", :diag [], :uncaught nil}
   :match-first-arm {:out "a", :diag [], :uncaught nil}
   :match-default {:out "d", :diag [], :uncaught nil}
   :match-is-strict {:out "str", :diag [], :uncaught nil}
   :match-unhandled-message {:out "Unhandled match case 5", :diag [], :uncaught nil}
   :closure-bind-to-null {:out "7", :diag [], :uncaught nil}
   :typed-ref-row42-assign-string {:out "Cannot assign string to reference held by property C::$p of type int", :diag [], :uncaught nil}
   :typed-ref-row42-assign-null {:out "Cannot assign null to reference held by property C::$p of type int", :diag [], :uncaught nil}
   :typed-ref-row42-coerces-numeric-string {:out "int(7)\n", :diag [], :uncaught nil}
   :typed-ref-row45-second-source-mismatch {:out "Cannot assign int to property D::$q of type array", :diag [], :uncaught nil}
   :typed-ref-row45-first-failing-source {:out "", :diag [], :uncaught {:class "TypeError", :message "Reference with value of type int held by property D::$q of type int is not compatible with property E::$b of type bool"}}
   :typed-ref-row41-uninitialized-non-nullable {:out "Cannot access uninitialized non-nullable property C::$u by reference", :diag [], :uncaught nil}
   :typed-ref-row41-uninitialized-nullable {:out "NULL\n", :diag [], :uncaught nil}
   :typed-ref-row41-readonly-initialized {:out "Cannot modify readonly property C::$ro", :diag [], :uncaught nil}
   :typed-ref-row44-unset-uninitializes {:out "Typed property C::$p must not be accessed before initialization", :diag [], :uncaught nil}
   :ref-cycle-var-dump {:out "array(1) {\n  [0]=>\n  *RECURSION*\n}\n", :diag [], :uncaught nil}
   :ref-cycle-compare {:out "", :diag [], :uncaught {:class "Error", :message "Nesting level too deep - recursive dependency?"}}
   :call-spread-string-key-gap {:out "1", :diag [], :uncaught nil}
   :foreach-ref-sort-inside-loop {:out "323", :diag [], :uncaught nil}
   :call-unknown-named-later-arg-runs {:out "Unknown named parameter $z", :diag [], :uncaught nil}
   :closure-call-user-class {:out "8", :diag [], :uncaught nil}
   :foreach-ref-array-reassigned-in-loop {:out "177777", :diag [], :uncaught nil}})


(def banner "php-8.3.35 shiva 2026-10-25")


(defn- pin
  "The fixture with its recorded oracle result folded in: `:oracle` becomes
   the banner, a fixture without a pinned `:out` takes the recorded one, and
   non-empty `:diag`/`:uncaught` are kept. Unrecorded fixtures stay pending."
  [fixture]
  (if-let [r (get recorded (:id fixture))]
    (assoc fixture
           :oracle banner
           :out (:out r)
           :diag (:diag r)
           :uncaught (:uncaught r))
    fixture))


(def control-v1
  "try/catch/finally, throw and match (docs/design/yang.php.md section 4 row 39
   unwinding; the language semantics). Sources and expectations are written
   from PHP's documented behaviour; every one waits for the shiva recording
   (`:oracle-record`) before it is enabled."
  [(fx :try-catch-message :control-v1 [39] #{:s6}
       "<?php try { nope(); } catch (Error $e) { echo $e->getMessage(); }"
       "Call to undefined function nope()")
   (fx :try-finally-runs-on-return :control-v1 [16 39] #{:s6}
       "<?php function f() { try { return 1; } finally { echo 'F'; } } echo f();"
       "F1")
   (fx :try-unmatched-catch-propagates :control-v1 [39] #{:s6}
       (str "<?php try { try { nope(); } catch (TypeError $e) { echo 'inner'; } }"
            " catch (Error $e) { echo 'outer'; }")
       "outer")
   (fx :try-finally-runs-on-error :control-v1 [39] #{:s6}
       (str "<?php try { try { nope(); } finally { echo 'F'; } }"
            " catch (Error $e) { echo 'C'; }")
       "FC")
   (fx :throw-rethrows-caught :control-v1 [39] #{:s6}
       (str "<?php try { try { nope(); } catch (Error $e) { throw $e; } }"
            " catch (Error $x) { echo 're'; }")
       "re")
   (fx :catch-multi-type :control-v1 [39] #{:s6}
       "<?php try { nope(); } catch (TypeError | Error $e) { echo 'multi'; }"
       "multi")
   (fx :match-first-arm :control-v1 [] #{:s6}
       "<?php echo match (2) { 1, 2 => 'a', default => 'b' };" "a")
   (fx :match-default :control-v1 [] #{:s6}
       "<?php echo match (5) { 1 => 'x', default => 'd' };" "d")
   (fx :match-is-strict :control-v1 [] #{:s6}
       "<?php echo match ('1') { 1 => 'int', '1' => 'str' };" "str")
   (fx :match-unhandled-message :control-v1 [] #{:s6}
       (str "<?php try { echo match (5) { 1 => 'x' }; }"
            " catch (UnhandledMatchError $e) { echo $e->getMessage(); }")
       "Unhandled match case 5")
   (fx :closure-bind-to-null :control-v1 [] #{:s6}
       "<?php $f = function () { return 7; }; $g = $f->bindTo(null); echo $g();" "7")])


(def typed-ref-candidates
  "Oracle-only sources for the S9 texts the prelude tests do not pin: the
   engine cannot run them before P4 (they use classes and typed properties),
   so shiva records the PHP texts and the next prelude test pins them. Each
   `:note` names the text the prelude currently produces."
  (let [c (fn [id rows source note]
            {:id id, :group :typed-ref-candidates, :rows rows, :level :oracle-only,
             :requires #{}, :source source, :oracle :pending, :note note})
        cls "class C { public int $p = 1; public ?int $n = null; public readonly int $ro; public int $u; function __construct() { $this->ro = 1; } } "]
    [(c :typed-ref-row42-assign-string [42]
        (str "<?php " cls "$c = new C; $r = &$c->p; try { $r = 'abc'; } catch (TypeError $e) { echo $e->getMessage(); }")
        "design text: Cannot assign string to reference held by property C::$p of type int")
     (c :typed-ref-row42-assign-null [42]
        (str "<?php " cls "$c = new C; $r = &$c->p; try { $r = null; } catch (TypeError $e) { echo $e->getMessage(); }")
        "prelude: Cannot assign null to reference held by property C::$p of type int")
     (c :typed-ref-row42-coerces-numeric-string [42]
        (str "<?php " cls "$c = new C; $r = &$c->p; $r = '7'; var_dump($c->p);")
        "prelude: int(7)")
     (c :typed-ref-row43-increment-past-max [43]
        (str "<?php " cls "$c = new C; $c->p = PHP_INT_MAX; $r = &$c->p; try { $r++; } catch (TypeError $e) { echo $e->getMessage(); }")
        "recorded: Cannot increment a reference held by property C::$p of type int past its maximal value (design row 43 amended)")
     (c :typed-ref-row45-second-source-mismatch [45]
        (str "<?php class D { public array $q = []; } " cls
             "$c = new C; $d = new D; $r = &$c->p; try { $d->q = &$c->p; } catch (TypeError $e) { echo $e->getMessage(); }")
        "prelude: Cannot assign int to property D::$q of type array")
     (c :typed-ref-row45-first-failing-source [45]
        (str "<?php class D { public int $q = 1; } class E { public bool $b = true; } "
             "$d = new D; $e = new E; $r = &$d->q; $e->b = &$d->q; try { $r = [1]; } catch (TypeError $x) { echo $x->getMessage(); }")
        "recorded: the `=&` acquisition itself throws (\"Reference with value of type int held by property D::$q of type int is not compatible with property E::$b of type bool\"); the prelude's ref-add-source text differs and PHP does not coerce int to bool across sources (row 45 follow-up)")
     (c :typed-ref-row41-uninitialized-non-nullable [41]
        (str "<?php " cls "$c = new C; try { $r = &$c->u; } catch (Error $e) { echo $e->getMessage(); }")
        "design text: Cannot access uninitialized non-nullable property C::$u by reference")
     (c :typed-ref-row41-uninitialized-nullable [41]
        "<?php class C { public ?int $n; } $c = new C; $r = &$c->n; var_dump($c->n);"
        "design: the property becomes null")
     (c :typed-ref-row41-readonly-initialized [41]
        (str "<?php " cls "$c = new C; try { $r = &$c->ro; } catch (Error $e) { echo $e->getMessage(); }")
        "design text: Cannot modify readonly property C::$ro")
     (c :typed-ref-row44-unset-uninitializes [44]
        (str "<?php " cls "$c = new C; $r = &$c->p; unset($c->p); try { echo $c->p; } catch (Error $e) { echo $e->getMessage(); }")
        "design: Typed property C::$p must not be accessed before initialization")]))


(def gate-v1
  "Fixtures authored for the Stage 0 gate (collab/1793020431000-architect-
   php-p3b-gate): the cycle guard, the departure sources of the ratified
   ledger rows (C25 spread, C26 iterator, C28 call validation order) and the
   user-class closure call held for P4. All wait for the next shiva pass."
  [(fx :ref-cycle-var-dump :ref-lifecycle-v1 [18] #{:s4}
       "<?php $a = [1]; $a[0] = &$a; var_dump($a);" nil)
   (fx :ref-cycle-compare :ref-lifecycle-v1 [18] #{:s4 :nesting-is-fatal}
       ;; recorded: PHP raises "Nesting level too deep - recursive dependency?"
       ;; as an uncatchable fatal (uncaught, empty output). The engine's nesting
       ;; error carries the same text but is a catchable Error, so the source's
       ;; catch prints it where PHP prints nothing: held (C29) until the error
       ;; can be raised as a fatal.
       (str "<?php $a = [1]; $a[0] = &$a; $b = [1]; $b[0] = &$b;"
            " try { var_dump($a == $b); } catch (Error $e) { echo $e->getMessage(); }")
       nil)
   {:id :call-spread-string-key-gap,
    :group :calls-v1,
    :rows [65],
    :level :oracle-only,
    :requires #{},
    :source "<?php function f($a, $b) { return $a - $b; } echo f(...['b' => 1, 'a' => 2]);",
    :oracle :pending,
    :note "C25: PHP binds the string keys as named arguments (prints 1); here the spread is refused (G-spread-string-keys)"}
   {:id :foreach-ref-sort-inside-loop,
    :group :iteration-v1,
    :rows [26],
    :level :oracle-only,
    :requires #{},
    :source "<?php $a = [3, 1, 2]; foreach ($a as &$v) { if ($v == 3) { sort($a); } echo $v; }",
    :oracle :pending,
    :note "C26: the rebuild clause of row 26 (iterator repositioned by ordinal) is not implemented; closes with the P3-c iterator registry"}
   {:id :foreach-ref-array-reassigned-in-loop,
    :group :iteration-v1,
    :rows [25],
    :level :oracle-only,
    :requires #{},
    :source "<?php $a = [1, 2]; $n = 0; foreach ($a as $k => &$v) { if ($k == 0) { $a = [7, 8]; } echo $v; if (++$n > 5) { break; } }",
    :oracle :pending,
    :note "C26: the unrelated-table clause of row 25 is not implemented; closes with the P3-c iterator registry. Bounded (counter and break) after pass 5 recorded about 1.8 GB from the unbounded source; awaiting the pass-7 record"}
   {:id :call-unknown-named-later-arg-runs,
    :group :calls-v1,
    :rows [57],
    :level :oracle-only,
    :requires #{},
    :source (str "<?php function f($a) { return $a; } function side() { echo 'S'; return 1; }"
                 " try { f(1, z: 2, w: side()); } catch (Error $e) { echo $e->getMessage(); }"),
    :oracle :pending,
    :zend "Unknown named parameter $z",
    :note "C28: PHP stops at the send of z (side() never runs); here php/call-named validates at entry, so side() runs first"}
   {:id :closure-call-stdclass,
    :group :closures-v1,
    :rows [],
    :level :oracle-only,
    :requires #{},
    :source "<?php $f = fn($x) => $x * 2; echo $f->call(new stdClass, 4);",
    :oracle :pending,
    :note "recorded warning: PHP refuses to bind a closure to an internal class; P4's bind rules pin it"}
   (assoc (fx :closure-call-user-class :closures-v1 [] #{:p4}
              "<?php class P { public $v = 2; } $f = fn($x) => $x * $this->v; echo $f->call(new P, 4);"
              "8")
          :level :oracle-only)])


(def all-fixtures
  "Unconditional registry; the oracle iterates it regardless of activation."
  (mapv pin (concat ref-lifecycle-v1
                    iteration-v1
                    calls-v1
                    closures-v1
                    destructure-v1
                    control-v1
                    oracle-only
                    typed-ref-candidates
                    gate-v1)))


(defn enabled?
  "True when every step the fixture requires has landed (in `steps`, default
   this slice's `completed-steps`). Oracle-only fixtures never run on the
   implementation."
  ([fixture] (enabled? fixture completed-steps))
  ([fixture steps]
   (and (not= :oracle-only (:level fixture))
        (every? steps (:requires fixture)))))


(def enabled
  "The subset implementation runners execute."
  (vec (filter enabled? all-fixtures)))
