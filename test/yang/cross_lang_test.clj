(ns yang.cross-lang-test
  "The substrate claim, empirically (yang.antlr.md 8.10/8.11): both preludes
   bottom out in `:application`, and the engine neither knows nor cares which
   frontend minted a closure. A TypeScript function value is called from
   PHP-lowered code and a PHP closure from TypeScript-lowered code, in one
   task, one run.

   The seam is explicit published-export composition (8.10.11), owned by
   `yang.call.conformance`: the donor source is published as a module and
   run unmodified, its named export is read from the donor runtime's global
   namespace (`globalThis.ts_fn`, PHP's `phpfn` function) and projected into the caller
   runtime's namespace under the same name, and the caller source runs
   unmodified. No call node is rewritten and no foreign `:code` extracted:
   the caller holds the donor's own function cell, as the identity check
   (`stored === phpfn`) shows.

   What is pinned as LIMITS (documented expectations, not failures):
   a PHP array reaching TypeScript is an opaque handle that only PHP can use;
   PHP null (`:php/null`) is neither JS null nor undefined. Each case runs
   linked on the three vector VMs, which must agree."
  (:require
    [clojure.test :refer [deftest is testing]]
    [yang.call.conformance :as xl]))


(deftest ts-closure-called-from-php-test
  (testing "a number crosses as a number: float in, float out"
    (is (= "4"
           (xl/call [:typescript "globalThis.ts_fn = (x, y) => x + y;"]
                    ["ts_fn"]
                    [:php "<?php echo ts_fn(1.5, 2.5);"]))))
  (testing "the PHP caller sees the result as an ordinary value"
    (is (= "yes"
           (xl/call [:typescript "globalThis.ts_fn = (x, y) => x + y;"]
                    ["ts_fn"]
                    [:php "<?php echo ts_fn(1.5, 2.5) == 4 ? 'yes' : 'no';"])))))


(deftest php-closure-called-from-ts-test
  (testing "TS stores and calls the same PHP function cell it received"
    (is (= {:js/out ["true 5"], :js/uncaught nil}
           (xl/call [:php "<?php function phpfn($a, $b) { return $a + $b; }"]
                    ["phpfn"]
                    [:typescript
                     "const stored = phpfn; console.log(stored === phpfn, stored(2, 3));"])))))


(deftest python-calls-typescript-closure-test
  (testing "TS stores and calls the same Python function cell it received"
    (is (= {:py/out ["22"], :py/exception nil}
           (xl/call [:typescript
                     (str "globalThis.ts_apply = f => { globalThis.stored = f; "
                          "return stored === f ? f(20) + 1 : -1; };")]
                    ["ts_apply"]
                    [:python "print(ts_apply(lambda x: x + 1))"])))))


(deftest typescript-calls-python-closure-test
  (testing "Python stores and calls the same TS function cell it received"
    (is (= {:js/out ["22"], :js/uncaught nil}
           (xl/call [:python
                     (str "def py_apply(f):\n"
                          "    global stored\n"
                          "    stored = f\n"
                          "    return f(20) + 1 if stored is f else -1")]
                    ["py_apply"]
                    [:typescript
                     "console.log(py_apply(x => x + 1));"])))))


(deftest python-calls-php-closure-test
  (testing "PHP stores and calls the same Python function cell it received"
    (is (= {:py/out ["22"], :py/exception nil}
           (xl/call [:php
                     (str "<?php function php_apply($f) { global $stored; "
                          "$stored = $f; return $stored === $f ? $f(20) + 1 : -1; }")]
                    ["php_apply"]
                    [:python "print(php_apply(lambda x: x + 1))"])))))


(deftest php-calls-python-closure-test
  (testing "Python stores and calls the same PHP function cell it received"
    (is (= "22"
           (xl/call [:python
                     (str "def py_apply(f):\n"
                          "    global stored\n"
                          "    stored = f\n"
                          "    return f(20) + 1 if stored is f else -1")]
                    ["py_apply"]
                    [:php "<?php echo py_apply(function($x) { return $x + 1; });"])))))


(deftest limits-test
  (testing "a PHP array returned to TS is an opaque handle, usable in PHP"
    (let [src "<?php function phpfn($a = 7) { return $a === 7 ? [1, 2, 3] : count($a); }"]
      ;; the PHP int `count` returns reaches TS as an integer (`3n`), not a
      ;; JS number: the two preludes keep their own number kinds
      (is (= {:js/out ["3n"], :js/uncaught nil}
             (xl/call [:php src]
                      ["phpfn"]
                      [:typescript "const h = phpfn(); console.log(phpfn(h));"])))))
  (testing "PHP null is neither JS null nor undefined"
    (let [src "<?php function phpfn() { return null; }"]
      (is (= {:js/out ["false false"], :js/uncaught nil}
             (xl/call [:php src]
                      ["phpfn"]
                      [:typescript
                       "const n = phpfn(); console.log(n === null, n === undefined);"]))))))
