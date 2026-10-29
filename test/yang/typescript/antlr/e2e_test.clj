(ns yang.typescript.antlr.e2e-test
  "End to end on the JVM (T1 step S6), as a stream topology: source events ->
   parser stage -> CST stream -> lowering stage -> program stream (source
   envelopes) -> an evaluator on each of the four VMs (the AST walker
   observes a program medium carrying the envelope's run member; the
   semantic, stack and register VMs are built from the same member).

   Every program runs three ways (`every-vm=`): naive on the four VMs,
   safepointed under no-op hooks on the four VMs (transparency: the derived
   program prints what the naive one prints), and linked on the three vector
   VMs (`js` served from `yang.typescript.antlr.linked-harness`; the AST
   walker's link is refused until L-b). The composition installs the real
   `cell`, `data` and `integer` modules; nothing is stubbed. Printed output
   is compared as JavaScript text rendered at the boundary
   (`yang.typescript.antlr.render`)."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.test-slow :as slow]
            [yang.safepoint :as safepoint]
            [yang.typescript.antlr.cst :as cst]
            [yang.typescript.antlr.linked-harness :as linked]
            [yang.typescript.antlr.lower :as lower]
            [yang.typescript.antlr.prelude-parity-test :as parity]
            [yang.typescript.antlr.render :as render]
            [yang.typescript.antlr.safepoint :as hooks]
            [yin.vm :as vm]
            [yin.vm.encoder :as encoder]
            [yin.vm.test-utils :as tu]))


;; =============================================================================
;; The topology
;; =============================================================================

(defn- source-events
  [unit text]
  [{:yang.source/event :yang.source/chunk,
    :yang.source/unit unit,
    :yang.source/ordinal 0,
    :yang.source/text text}
   {:yang.source/event :yang.source/seal,
    :yang.source/unit unit,
    :yang.source/chunk-count 1}])


(defn- run-member
  "The run member of a source envelope: the map AST the lowering emitted."
  [{:yin/keys [batch root]}]
  (nth batch root))


(defn lower-source
  "Source text through the parser and lowering stages: `{:program envelope}`
   or `{:diagnostics [...]}`. `prelude` is `:bundled` or `:linked`."
  [source prelude]
  (let [program (tu/new-memory-log)
        src (tu/new-memory-log)
        cst-log (tu/new-memory-log)
        diagnostics (tu/new-memory-log)]
    (doseq [e (source-events [:e2e 0] source)] (stream/append! src e))
    (cst/step-stage (cst/open-stage src cst-log))
    (lower/step-stage (lower/open-stage cst-log program diagnostics
                                        {:medium lower/program-medium,
                                         :prelude prelude}))
    (let [problems (tu/drain diagnostics)]
      (if (seq problems)
        {:diagnostics problems}
        {:program (first (tu/drain program))}))))


(defn derive-program
  "The safepointed composition's stages after lowering: the envelope is
   projected by the encoder onto a row stream, the `yang.safepoint` stage
   rewrites it under `profile` onto a derived stream and writes its record
   to a ledger stream. Returns the derived envelope."
  [envelope profile]
  (let [rows (tu/new-memory-log)
        derived (tu/new-memory-log)
        ledger (tu/new-memory-log)]
    (encoder/load rows envelope)
    (safepoint/step-stage
      (safepoint/open-stage rows derived ledger profile :e2e/derived))
    (first (tu/drain derived))))


(defn- attempt
  [f]
  (try (render/output (f))
       (catch Exception e [:thrown (ex-message e)])))


(defn run-ts
  "Every VM's rendered output for `source`, as `{vm-key output}`; a throw
   becomes `[:thrown message]`. Options: `:hooks`, a hook prelude: the
   program is safepointed under `hooks/profile` and the VMs run the hook
   prelude, then the derived program. `:prelude :linked` lowers the linked
   program and runs it on the three vector VMs."
  ([source] (run-ts source {}))
  ([source {:keys [hooks prelude], :or {prelude :bundled}}]
   (let [lowered (lower-source source prelude)]
     (if (:diagnostics lowered)
       lowered
       (let [envelope (:program lowered)]
         (if (= :linked prelude)
           (let [ast (run-member envelope)]
             (into {}
                   (map (fn [k]
                          [k (attempt #(vm/value (linked/run-linked k ast)))]))
                   (keys linked/backends)))
           (let [ast (if hooks
                       (hooks/program
                         hooks
                         (vm/semantic-bytecode->ast
                           (run-member (derive-program envelope
                                                       hooks/profile))))
                       (run-member envelope))]
             (into {}
                   (map (fn [[k run]]
                          [k (attempt #(vm/value (run ast)))]))
                   parity/vm-runners))))))))


(defn prints
  "The rendered value of a run that printed `lines` and raised nothing."
  [& lines]
  {:js/out (vec lines), :js/uncaught nil})


(defn uncaught
  "The rendered value of a run that printed `lines`, then died on `text`."
  [text & lines]
  {:js/out (vec lines), :js/uncaught text})


(defn every-vm=
  "`expected` from `source` on every VM: naive, safepointed under no-op
   hooks, and linked. The linked leg runs the three vector VMs: the walker's
   link of `js` is refused until L-b."
  [expected source]
  (doseq [[label opts vms] [["naive" {}
                             [:ast-walker :semantic :stack :register]]
                            ["no-op hooks" {:hooks hooks/noop-uast}
                             [:ast-walker :semantic :stack :register]]
                            ["linked" {:prelude :linked}
                             [:semantic :stack :register]]]]
    (let [results (run-ts source opts)]
      (is (not (contains? results :diagnostics)) (pr-str results))
      (doseq [k vms]
        (is (= expected (get results k)) (str label " " k " " (pr-str source)))))))


;; =============================================================================
;; Programs
;; =============================================================================

(defn- program-test
  "A slow test running `cases`, `[[expected source] ...]`, through
   `every-vm=` under the slow guard named `nm`."
  [nm cases]
  (slow/guard nm
              (fn []
                (doseq [[expected source] cases]
                  (testing source (every-vm= expected source))))))


(deftest ^:slow console-and-literals-test
  (program-test
    "console-and-literals"
    [[(prints "1") "console.log(1);"]
     [(prints "0.30000000000000004") "console.log(0.1 + 0.2);"]
     [(prints "a b 3 true null undefined")
      "console.log('a', \"b\", 3, true, null, undefined);"]
     [(prints "5 3") "console.log(2 + 3, 7 - 4);"]
     [(prints "-0 0") "console.log(-0, String(-0));"]
     [(prints "9007199254740992") "console.log(2 ** 53 + 1);"]
     [(prints "x7y") "const n = 7; console.log(`x${n}y`);"]]))


(deftest ^:slow operators-and-coercion-test
  (program-test
    "operators-and-coercion"
    [[(prints "7 3.5" "-4")
      "console.log(1 + 2 * 3, 7 / 2); console.log(-(2 + 2));"]
     [(prints "1.5 -1.5 1") "console.log(5.5 % 2, -5.5 % 2, 5 % -2);"]
     [(prints "-2 4294967295 -2147483648")
      "console.log(-8 >> 2, -1 >>> 0, 1 << 31);"]
     [(prints "12 0 16 1000 Infinity NaN")
      "console.log(Number(' 12 '), Number(''), Number('0x10'), Number('1e3'), Number('Infinity'), Number('12px'));"]
     [(prints "true true false true")
      "console.log(1 == '1', null == undefined, null == 0, 0 == '');"]
     [(prints "true false") "console.log('a' === 'a', 1 === '1');"]
     [(prints "number string undefined object boolean function bigint")
      "console.log(typeof 1, typeof 'a', typeof undefined, typeof null, typeof true, typeof function () {}, typeof 1n);"]
     [(prints "undefined") "console.log(typeof notDeclared);"]
     [(prints "3n" "TypeError")
      "console.log(1n + 2n); try { 1n + 1; } catch (e) { console.log(e.name); }"]]))


(deftest ^:slow scoping-and-closures-test
  (program-test
    "scoping-and-closures"
    [[(prints "undefined" "5") "console.log(v); var v = 5; console.log(v);"]
     [(prints "ok") "console.log(f()); function f() { return 'ok'; }"]
     [(uncaught "ReferenceError: Cannot access 'x' before initialization")
      "console.log(x); let x = 1;"]
     [(prints "ReferenceError")
      "try { c; } catch (e) { console.log(e.name); } const c = 1;"]
     [(prints "1" "2")
      "let x = 1; { let x = 2; console.log(x === 2 ? 1 : 0); } console.log(x + 1);"]
     [(prints "0 1 2")
      "var a, b, c; for (let i = 0; i < 3; i++) { if (i === 0) a = () => i; if (i === 1) b = () => i; if (i === 2) c = () => i; } console.log(a(), b(), c());"]
     [(prints "3")
      "function mk() { let n = 0; return function () { n = n + 1; return n; }; } const c = mk(); c(); c(); console.log(c());"]]))


(deftest ^:slow control-flow-test
  (program-test
    "control-flow"
    [[(prints "10") "let s = 0; for (let i = 1; i <= 4; i++) { s += i; } console.log(s);"]
     [(prints "3") "let i = 0; while (true) { if (i === 3) break; i++; } console.log(i);"]
     [(prints "1") "let i = 0; do { i++; } while (i < 1); console.log(i);"]
     [(prints "b" "c")
      "switch (2) { case 1: console.log('a'); case 2: console.log('b'); case 3: console.log('c'); break; default: console.log('d'); }"]
     [(prints "d")
      "switch (9) { case 1: console.log('a'); break; default: console.log('d'); }"]
     [(prints "2")
      "var n = 0; outer: for (var a = 0; a < 3; a++) { for (var b = 0; b < 3; b++) { if (b === 1) continue outer; if (a === 2) break outer; n++; } } console.log(n);"]]))


(deftest ^:slow functions-test
  (program-test
    "functions"
    [[(prints "120")
      "function fact(n) { return n <= 1 ? 1 : n * fact(n - 1); } console.log(fact(5));"]
     [(prints "7") "const add = (a, b) => a + b; console.log(add(3, 4));"]
     [(prints "undefined") "function f() {} console.log(f());"]
     [(prints "[Function: f]") "function f() {} console.log(f);"]
     [(prints "true") "console.log((function () { return this === undefined; })());"]]))


(deftest ^:slow shared-call-convention-test
  (program-test
    "shared-call-convention"
    [[(prints "42")
      "function f(x) { return x + 1; } console.log(f(41));"]
     [(prints "true 8")
      "function apply(f, x) { return f(x); } const add = n => n + 5; console.log(apply(add, 3) === 8, apply(add, 3));"]
     [(prints "true 5 6")
      "function identity(x) { return x; } function make(n) { return () => n; } const f = make(5); const g = identity(f); console.log(f === g, f(), g() + 1);"]
     [(prints "true true")
      "function receiver() { return this; } function relay(f) { return f; } const original = receiver; const returned = relay(original); console.log(original === returned, returned() === undefined);"]]))


(deftest ^:slow exceptions-test
  (program-test
    "exceptions"
    [[(prints "caught boom") "try { throw new Error('boom'); } catch (e) { console.log('caught', e.message); }"]
     [(prints "TypeError m") "try { throw new TypeError('m'); } catch (e) { console.log(e.name, e.message); }"]
     [(prints "try" "finally") "try { console.log('try'); } finally { console.log('finally'); }"]
     [(prints "f" "r")
      "function g() { try { return 'r'; } finally { console.log('f'); } } console.log(g());"]
     [(prints "re") "try { try { throw 're'; } catch (e) { throw e; } } catch (e2) { console.log(e2); }"]
     [(uncaught "Error: boom" "before") "console.log('before'); throw new Error('boom');"]
     [(uncaught "7") "throw 7;"]
     [(prints "ReferenceError") "try { nope; } catch (e) { console.log(e.name); }"]]))


(deftest ^:slow strings-test
  (program-test
    "strings"
    [[(prints "2 55357 56832")
      "const s = '\\uD83D\\uDE00'; console.log(s.length, s.charCodeAt(0), s.charCodeAt(1));"]
     [(prints "true") "console.log('\\uD800' === '\\uD800');"]
     [(prints "ell") "console.log('hello'.slice(1, 4));"]
     [(prints "true")
      "const s = '\\uD83D\\uDE00'; console.log(s.slice(0, 1) + s.slice(1) === s);"]]))


(deftest ^:slow known-gap-refusals-test
  (slow/guard
    "known-gap-refusals"
    (fn []
      (testing "G-pow-frac: a fractional exponent throws a TypeError naming the gap"
        (every-vm= (prints "TypeError ** with a fractional exponent is not supported yet (yang.typescript known gap G-pow-frac)")
                   "try { 4 ** 0.5; } catch (e) { console.log(e.name, e.message); }"))
      (testing "G-radix-frac: a fractional radix conversion throws a TypeError naming the gap"
        (every-vm= (prints "TypeError toString with a fractional value and a radix other than 10 is not supported yet (yang.typescript known gap G-radix-frac)")
                   "try { (0.5).toString(2); } catch (e) { console.log(e.name, e.message); }")))))
