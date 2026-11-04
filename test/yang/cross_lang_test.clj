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
                          "return stored === f ? f(20n) + 1n : -1n; };")]
                    ["ts_apply"]
                    [:python "print(ts_apply(lambda x: x + 1))\n"])))))


(deftest typescript-calls-python-closure-test
  (testing "Python stores and calls the same TS function cell it received"
    (is (= {:js/out ["22n"], :js/uncaught nil}
           (xl/call [:python
                     (str "def py_apply(f):\n"
                          "    global stored\n"
                          "    stored = f\n"
                          "    return f(20) + 1 if stored is f else -1")]
                    ["py_apply"]
                    [:typescript
                     "console.log(py_apply(x => x + 1n));"])))))


(deftest python-calls-php-closure-test
  (testing "PHP stores and calls the same Python function cell it received"
    (is (= {:py/out ["22"], :py/exception nil}
           (xl/call [:php
                     (str "<?php function php_apply($f) { global $stored; "
                          "$stored = $f; return $stored === $f ? $f(20) + 1 : -1; }")]
                    ["php_apply"]
                    [:python "print(php_apply(lambda x: x + 1))\n"])))))


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


;; yang.clojure <-> TypeScript, both directions. yang.clojure now publishes
;; its runtime module (`clj`: the run-main entry, the task-local global
;; namespace and the admission surface, yang.clojure.prelude), so the named
;; skip is closed: the helper drives a clojure donor or caller like any
;; other admitted frontend, and the same-cell identity check runs here too.


(deftest clojure-closure-called-from-typescript-test
  (testing "TS stores and calls the same clojure function cell it received"
    ;; Clojure integers are bare exact integers, the value JS spells as a
    ;; BigInt, so the TS callback adds with `1n` and logs `22n`.
    (is (= {:js/out ["true 22n"], :js/uncaught nil}
           (xl/call [:clojure "(defn clj_apply [f] (+ (f 20) 1))"]
                    ["clj_apply"]
                    [:typescript
                     (str "const stored = clj_apply; "
                          "console.log(stored === clj_apply, stored(x => x + 1n));")])))))


(deftest typescript-closure-called-from-clojure-test
  (testing "TS stores and calls the same clojure function cell it received"
    (is (= "22"
           (xl/call [:typescript
                     (str "globalThis.ts_apply = f => { globalThis.stored = f; "
                          "return stored === f ? f(20n) + 1n : -1n; };")]
                    ["ts_apply"]
                    [:clojure "(ts_apply (fn [x] (+ x 1)))"])))))


(deftest clojure-projection-through-runtime-globals-test
  (testing "the projected value is read via clj/global-get inside the caller's run"
    ;; `ts_apply` is no top-level definition of the caller, so its read
    ;; inside `apply-it` reaches the runtime global namespace the helper
    ;; projected into, not a task-store binding.
    (is (= "22"
           (xl/call [:typescript
                     (str "globalThis.ts_apply = f => { globalThis.stored = f; "
                          "return stored === f ? f(20n) + 1n : -1n; };")]
                    ["ts_apply"]
                    [:clojure
                     "(defn apply-it [] (ts_apply (fn [x] (+ x 1)))) (apply-it)"]))))
  (testing "a second clj/run-main entry still sees the earlier unit's globals"
    (is (= "42"
           (xl/call [:clojure "(def answer 42)"]
                    ["answer"]
                    [:clojure "(defn get-answer [] answer) (get-answer)"])))))


(deftest clojure-caller-shadows-projected-global-test
  (testing "a caller-defined name shadows the projected one"
    ;; The caller's own `shared` runs: its definition publishes after the
    ;; projection, so the runtime global holds the caller's function.
    (is (= "21"
           (xl/call [:clojure "(defn shared [x] (+ x 100))"]
                    ["shared"]
                    [:clojure "(defn shared [x] (+ x 1)) (shared 20)"])))))


(deftest clojure-source-cell-ref-map-invocation-test
  (testing "a source map {:type :cell-ref} is ordinary invocable data"
    ;; The donor's exported function invokes its own source map literal:
    ;; the map lookup answers 42, where the structural cell test used to
    ;; dereference the map as a cell and fail the VM.
    (is (= {:js/out ["42n"], :js/uncaught nil}
           (xl/call [:clojure
                     "(defn probe [] ({:type :cell-ref :marker 42} :marker))"]
                    ["probe"]
                    [:typescript "console.log(probe());"]))))
  (testing "a source map with the full forged cell-ref shape is ordinary invocable data"
    ;; The map spells every key of an issued reference among its own
    ;; entries; it is still data, so the lookup answers 42 where key
    ;; presence alone used to dereference it and fail the VM.
    (is (= {:js/out ["42n"], :js/uncaught nil}
           (xl/call [:clojure
                     "(defn probe [] ({:type :cell-ref :id :customer :seal \"wax\" :marker 42} :marker))"]
                    ["probe"]
                    [:typescript "console.log(probe());"]))))
  (testing "a full-shape three-entry map with a valid-format 64-char seal is ordinary invocable data"
    ;; The exact issued format — three entries, a seal string of the issued
    ;; length — with an :id the engine's heap never minted: the call site
    ;; asks the heap (cell/authentic?), the heap does not authenticate the
    ;; map, and the invocation is the map lookup answering the stored seal
    ;; string, where the shape gates of the previous rounds routed it to
    ;; admission and the dereference refused the VM.
    (is (= {:js/out [(str "\"" (apply str (repeat 64 "a")) "\"")], :js/uncaught nil}
           (xl/call [:clojure
                     (str "(defn probe [] ({:type :cell-ref :id :some-key "
                          ":seal \"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"} :seal))")]
                    ["probe"]
                    [:typescript "console.log(probe());"]))))
  (testing "the gate's collision literal: an :id in the heap's own language answers as data"
    ;; The r4 reviewer's literal — :id :cell-7, an id spelling the heap
    ;; itself could have minted, with a well-formed but wrong seal. The
    ;; ruling of record routes by the issuer's answer, not the id
    ;; language: the heap does not authenticate it, so the lookup answers
    ;; :cell-7 where r4's gate still handed it to the dereference and the
    ;; VM aborted it as dead-or-forged.
    (is (= {:js/out [":cell-7"], :js/uncaught nil}
           (xl/call [:clojure
                     (str "(defn probe [] ({:type :cell-ref :id :cell-7 "
                          ":seal \"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"} :id))")]
                    ["probe"]
                    [:typescript "console.log(probe());"]))))
  (testing "a runtime-built full-shape map (assoc-constructed, not a bound literal) invokes as data"
    ;; The map is assembled by assoc at runtime, so no literal ever holds
    ;; it; the shape test runs on the constructed value and the lookup
    ;; still answers the stored seal string.
    (is (= {:js/out [(str "\"" (apply str (repeat 64 "a")) "\"")], :js/uncaught nil}
           (xl/call [:clojure
                     (str "(defn probe [] (let [m (assoc (assoc (assoc {} :type :cell-ref) "
                          ":id :some-key) "
                          ":seal \"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\")] "
                          "(m :seal)))")]
                    ["probe"]
                    [:typescript "console.log(probe());"]))))
  (testing "a runtime-built map with the forged cell-ref shape is ordinary invocable data"
    (is (= {:js/out ["42n"], :js/uncaught nil}
           (xl/call [:clojure
                     "(defn probe [] (let [m {:type :cell-ref :id :customer :seal \"wax\" :marker 42}] (m :marker)))"]
                    ["probe"]
                    [:typescript "console.log(probe());"])))))


(deftest python-function-kind-of-in-typescript-test
  (testing "js/kind-of over a cell-backed foreign callable is :function"
    (is (= {:js/out ["function"], :js/uncaught nil}
           (xl/call [:python "def py_fn(x):\n    return x + 1\n"]
                    ["py_fn"]
                    [:typescript "console.log(typeof py_fn);"])))))


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
