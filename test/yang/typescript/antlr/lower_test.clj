(ns yang.typescript.antlr.lower-test
  "T1 lowering from source (S4), JVM: goldens of `lower-module-body` for the
   construct groups, the semantics table on all four VMs against what Node
   prints (the expectations were produced by Node and are checked in), the
   typed refusals, the early errors, and the renaming law that comments and
   blank lines move no row."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [dao.jing.cbor :as cbor]
    [dao.test-slow :as slow]
    [yang.core.names :as names]
    [yang.typescript.antlr.cst-test]
    [yang.core.uast :as u]
    [yang.typescript.antlr.cst :as cst]
    [yang.typescript.antlr.lower :as lower]
    [yang.typescript.antlr.prelude-parity-test :as parity]
    [yang.typescript.antlr.render :as render]))


(def ^:private worker (cst/make-worker :ts))


(defn- parse
  [src]
  (let [pk (cst/parse-source worker [:unit 0] src)]
    (is (= :yang.cst/ok (:yang.cst/outcome pk)) src)
    pk))


(defn- body
  [src]
  (lower/lower-module-body (parse src)))


(defn- diagnostic
  "The ex-data of the diagnostic lowering `src` throws, or nil."
  [src]
  (try (lower/lower-packet (parse src))
       nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))


(defn- lines
  "What `run` prints: the logged lines, then an `Uncaught ...` line."
  [{:js/keys [out uncaught]}]
  (vec (concat out (when uncaught [(str "Uncaught " uncaught)]))))


(defn- run-all
  "`src` lowered (bundled) and run on every VM: VM -> printed lines."
  [src]
  (let [ast (lower/lower-packet (parse src))]
    (into {}
          (map (fn [[k run]] [k (lines (render/output (run ast)))]))
          parity/runners)))


;; =============================================================================
;; Goldens
;; =============================================================================

(defn- nodes
  [tree]
  (tree-seq #(or (map? %) (sequential? %))
            #(if (map? %) (vals %) %)
            tree))


(defn- cells
  "`body` under one lambda over fresh cells: `[name init]` pairs."
  [pairs body]
  (apply u/app
         (u/lam (mapv (comp symbol first) pairs) body)
         (map (fn [[_ init]] (u/app (u/v 'cell/new) init)) pairs)))


(def ^:private undefined (u/lit :js/undefined))


(defn- f64
  [x]
  (u/lit (cbor/float64 x)))


(deftest var-declaration-golden-test
  (is (= (cells [["x" undefined]]
                (u/then (u/then (u/app (u/v 'cell/set!) (u/v 'x) (f64 1.0))
                                undefined)
                        undefined))
         (body "var x = 1;"))
      "a var cell starts undefined; the literal is float64 content")
  (is (= (cells [["y" (u/lit :js/tdz)]]
                (u/then (u/then (u/app (u/v 'cell/set!) (u/v 'y) (f64 2.0))
                                undefined)
                        undefined))
         (body "let y = 2;"))
      "a let cell starts in TDZ")
  (is (= (cells [["z" (u/lit :js/tdz)]]
                (u/then (u/then (u/app (u/v 'cell/set!) (u/v 'z) undefined)
                                undefined)
                        undefined))
         (body "let z;"))
      "a let without initializer leaves TDZ with undefined"))


(deftest read-golden-test
  (let [reads (fn [src]
                (->> (nodes (body src))
                     (filter #(and (= :application (:type %))
                                   (= :variable (:type (:operator %)))))
                     (map #(:name (:operator %)))
                     set))]
    (is (contains? (reads "let a = 1; a;") 'js/lexical-get) "TDZ-capable read")
    (is (contains? (reads "var a = 1; a;") 'cell/get) "var read")
    (is (contains? (reads "nope;") 'js/global-get) "free name")
    (is (contains? (reads "typeof nope;") 'js/global-typeof))
    (is (contains? (reads "const a = 1; a = 2;") 'js/const-assign!))
    (is (contains? (reads "f(1); function f(x) {}") 'js/call))
    (is (contains? (reads "function f(x) {}") 'js/make-function))
    (is (contains? (reads "a.b; var a;") 'js/get-value))
    (is (contains? (reads "1 + 2;") 'js/add))))


(deftest literal-golden-test
  (is (= (f64 1.0) (nth (filter #(= (f64 1.0) %) (nodes (body "1;"))) 0))
      "the literal 1 is float64 content, never a bare integer")
  (is (= (u/lit 7) (first (filter #(= (u/lit 7) %) (nodes (body "7n;")))))
      "a BigInt literal is the bare exact integer")
  (is (some #(= (u/lit {:js/units [0xD800]}) %) (nodes (body "'\\uD800';")))
      "a lone surrogate literal is :js/units")
  (is (some #(= (u/lit {:js/str "\ud83d\ude00"}) %)
            (nodes (body "'\\uD83D\\uDE00';")))
      "an escaped surrogate pair normalizes to :js/str")
  (is (some #(= (u/lit {:js/str "a"}) %) (nodes (body "'a';"))))
  (is (some #(= (u/app (u/v 'js/string->bigint)
                       (u/lit {:js/str "123456789012345678901234567890"}))
                %)
            (nodes (body "123456789012345678901234567890n;")))
      "a BigInt past 2^53 - 1 is built by the guest"))


(deftest site-marks-test
  (let [marks (fn [src]
                (->> (nodes (lower/lower-packet (parse src)))
                     (keep #(:yang/site (meta %)))
                     frequencies))]
    (is (= 1 (get (marks "while (false) {}") :loop)))
    (is (= 2 (get (marks "do {} while (false); for (;;) break;") :loop)))
    (is (some (fn [[k _]] (and (vector? k) (= :call (first k)) (= "f" (second k))))
              (marks "function f() {}"))
        "a function body is a :call site")))


;; =============================================================================
;; Semantics against Node
;; =============================================================================

(def ^:private node-table
  "[source printed-lines]: the lines Node prints for the source (with the
   preload that prints an uncaught throw as an `Uncaught ...` line)."
  [["console.log(1+2, 'a'+'b', 10/4, 2**10, 7%3, -7%3, 1/3)"
    ["3 ab 2.5 1024 1 -1 0.3333333333333333"]]
   ["console.log(0.1+0.2, -0, 1/-0, String(-0), -0 === 0, 2**53+1, 5n**3n, 7%-2, -5.5%2, 5.5%2)"
    ["0.30000000000000004 -0 -Infinity 0 true 9007199254740992 125n 1 -1.5 1.5"]]
   ["console.log(1<<31, -1>>>0, -8>>1, -8>>>28, 5&3, 5|3, 5^3, ~5, 1<<33)"
    ["-2147483648 4294967295 -4 15 1 7 6 -6 2"]]
   ["console.log(1=='1', null==undefined, null==0, NaN!=NaN, '1'===1, 0==='', 0=='', '0'==false, undefined==0, 1n==1, 2n=='2', 1n===1)"
    ["true true false true false false true true false true true false"]]
   ["console.log(Number(' 12 '), Number(''), Number('0x10'), Number('1e3'), Number('Infinity'), Number('12px'), Number(true), Number(null), Number(undefined))"
    ["12 0 16 1000 Infinity NaN 1 0 NaN"]]
   ["console.log(String(1e21), String(1e-7), 123456789012345680000, 0.000001, String(null), String(undefined), String(true), String(12n))"
    ["1e+21 1e-7 123456789012345680000 0.000001 null undefined true 12"]]
   ["console.log(typeof 1, typeof 1n, typeof 's', typeof undefined, typeof null, typeof console.log, typeof nope, typeof typeof 1)"
    ["number bigint string undefined object function undefined string"]]
   ["console.log(!0, !!'', !!'a', !!NaN, !!0n, !!1n, 1&&2, 0||'z', null||undefined, 'a'&&0)"
    ["true false true false false true 2 z undefined 0"]]
   ["var n = 0; while (n < 3) { n++; } console.log(n); do { n--; } while (n > 0); console.log(n);"
    ["3" "0"]]
   ["var s = 0; for (var i = 0; i < 5; i++) { if (i == 3) continue; s += i; } console.log(s, i);"
    ["7 5"]]
   ["function f(x) { return x * 2; } console.log(f(3), typeof f, f.name, f.length, hoisted()); function hoisted() { return 7; }"
    ["6 function f 1 7"]]
   ["console.log(typeof g, h); var h = 1; function g() {}"
    ["function undefined"]]
   ["try { console.log(x); } catch (e) { console.log(e.name); } let x = 1;"
    ["ReferenceError"]]
   ["try { const c = 1; c = 2; } catch (e) { console.log(e.name, e.message); }"
    ["TypeError Assignment to constant variable."]]
   ["function r() { return y; } try { r(); } catch (e) { console.log(e.name); } let y = 3; console.log(r());"
    ["ReferenceError" "3"]]
   ["var out = ''; for (let i = 0; i < 3; i++) { var f = () => i; out += f(); } console.log(out);"
    ["012"]]
   ["var fs = ''; for (let i = 0; i < 3; i++) { fs += (() => i)(); } var c = (() => { let i = 0; return () => ++i; })(); c(); console.log(fs, c(), c());"
    ["012 2 3"]]
   ["function cl() { var r = ''; for (var i = 0; i < 3; i++) { var g = () => i; r += g(); } return r; } console.log(cl());"
    ["012"]]
   ["outer: for (let i = 0; i < 3; i++) { for (let j = 0; j < 3; j++) { if (j == 1) continue outer; if (i == 2) break outer; console.log(i, j); } }"
    ["0 0" "1 0"]]
   ["switch (2) { case 1: console.log('one'); case 2: console.log('two'); case 3: console.log('three'); break; default: console.log('d'); } console.log('end');"
    ["two" "three" "end"]]
   ["switch (9) { case 1: console.log('one'); default: console.log('d'); case 3: console.log('three'); }"
    ["d" "three"]]
   ["switch (3) { default: console.log('d'); case 1: console.log('one'); break; case 2: console.log('two'); }"
    ["d" "one"]]
   ["function g() { try { return 1; } finally { console.log('fin'); } } console.log(g());"
    ["fin" "1"]]
   ["function t() { for (var i = 0; i < 3; i++) { try { if (i == 1) continue; console.log('b', i); } finally { console.log('f', i); } } } t();"
    ["b 0" "f 0" "f 1" "b 2" "f 2"]]
   ["function t() { for (var i = 0; i < 3; i++) { try { if (i == 1) break; } finally { console.log('f', i); } } return i; } console.log(t());"
    ["f 0" "f 1" "1"]]
   ["try { try { throw 1; } finally { console.log('inner'); } } catch (e) { console.log('caught', e); }"
    ["inner" "caught 1"]]
   ["try { try { throw new Error('a'); } catch (e) { throw new TypeError('b'); } } catch (e2) { console.log(e2.name, e2.message, String(e2)); }"
    ["TypeError b TypeError: b"]]
   ["try { throw 'str'; } catch (e) { console.log(typeof e, e); } try { throw null; } catch { console.log('opt'); }"
    ["string str" "opt"]]
   ["try { null.foo; } catch (e) { console.log(e.name + ': ' + e.message); } try { undefined(); } catch (e) { console.log(e.name); }"
    ["TypeError: Cannot read properties of null (reading 'foo')" "TypeError"]]
   ["try { 1n + 1; } catch (e) { console.log(e.name); } try { -1n >>> 1n } catch (e) { console.log(e.name); }"
    ["TypeError" "TypeError"]]
   ["console.log('😀'.length, '😀'[0] === '\\uD83D', '😀'.slice(0,1) === '\\uD83D', '😀'.charCodeAt(1), '😀'.codePointAt(0), '\\uD83D' + '\\uDE00' === '😀', '\\uD800' === '\\uD800')"
    ["2 true true 56832 128512 true true"]]
   ["console.log(`a${1+1}b${'c'}`, `x`, `${1}${2}`, `\\u0041\\n`.length)"
    ["a2bc x 12 2"]]
   ["console.log('abc'.slice(1), 'abc'.substring(2,0), 'abc'.indexOf('c'), 'abc'.at(-1), 'abc'.concat('d', 1), 'abc'.length, 'abc'[1])"
    ["bc ab 2 c abcd1 3 b"]]
   ["console.log(String(1), String('s'), String(true), Boolean(''), Boolean('a'), Number('7') + 1, BigInt(5) + 1n, String(BigInt(7)))"
    ["1 s true false true 8 6n 7"]]
   ["var q = 5; q += 2; q **= 2; console.log(q, q++, ++q, q--, q, q-- - --q);"
    ["49 49 51 51 50 2"]]
   ["label: { console.log('a'); break label; } console.log('c');"
    ["a" "c"]]
   ["function h() { return; } console.log(h(), (() => 5)(), (function () { return this; })(), new Error('boom').message, typeof new Error('x'));"
    ["undefined 5 undefined boom object"]]
   ["const o = function fib(n) { return n < 2 ? n : fib(n-1) + fib(n-2); }; console.log(o(15), o.name);"
    ["610 fib"]]
   ["const t2 = 1; { let t2 = 2; console.log(t2); } console.log(t2);"
    ["2" "1"]]
   ["function f() { return () => this; } console.log(f()() === undefined);"
    ["true"]]
   ["var a = 1, b = a + 1, c; console.log(a, b, c, (1, 2, 3), void 0, 1 < 2 ? 'y' : 'n');"
    ["1 2 undefined 3 undefined y"]]
   ["console.log(1n + 2n, 7n % -2n, 2n ** 64n, 1n << 70n, -5n >> 1n, 0x10n, 0b11n, 0o7n, 123456789012345678901234567890n)"
    ["3n 1n 18446744073709551616n 1180591620717411303424n -3n 16n 3n 7n 123456789012345678901234567890n"]]
   ["console.log(7n / 2n)"
    ["3n"]]
   ["console.log(-7n / 2n)"
    ["-3n"]]
   ["console.log(0x10, 0b101, 0o17, 1_000, .5, 1e3, 1E-3, 0.1e2)"
    ["16 5 15 1000 0.5 1000 0.001 10"]]
   ["console.log(NaN, Infinity, -Infinity, undefined, null, true)"
    ["NaN Infinity -Infinity undefined null true"]]
   ["console.log(10 > 9, '10' > '9', '10' > 9, 'a' < 'b', null >= 0, undefined >= 0, NaN <= NaN, 1n < 2, '3' < 4n)"
    ["true false true true true false false true true"]]
   ["throw new RangeError('over');"
    ["Uncaught RangeError: over"]]
   ["throw 42;"
    ["Uncaught 42"]]
   ["console.log(1); null.x;"
    ["1" "Uncaught TypeError: Cannot read properties of null (reading 'x')"]]])


(deftest ^:slow semantics-match-node-on-every-vm-test
  (slow/guard "semantics-match-node-on-every-vm-test"
              (fn []
                (doseq [[src expected] node-table]
                  (testing src
                    (let [result (run-all src)]
                      (doseq [[vm printed] result]
                        (is (= expected printed) (str vm " " src)))))))))


;; =============================================================================
;; Refusals and early errors
;; =============================================================================

(deftest out-of-slice-refusals-test
  (doseq [[src construct slice]
          [["var o = {*g() {}};" "generator function" :T5]
           ["for (const x of y) {}" "for-of" :T5]
           ["function* g() {}" "generator function" :T5]
           ["async function f() {}" "async function" :T6]
           ["import x from 'y';" "module syntax" :T7]
           ["export const a = 1;" "module syntax" :T7]
           ["namespace N {}" "namespace" :T9]
           ["eval('1');" "eval" :never]
           ["new Function('return 1');" "Function" :never]
           ["var [a] = [1];" "destructuring" :T4]
           ["function f(a = 1) {}" "default parameter" :T4]
           ["function f(...a) {}" "rest parameter" :T4]
           ["tag`x`;" "tagged template" :T5]
           ["/ab/;" "regular expression literal" :never]]]
    (testing src
      (let [d (diagnostic src)]
        (is (= :yang.ts/unsupported (:yang.ts/diagnostic d)) (pr-str d))
        (is (= construct (:construct d)))
        (is (= slice (:slice d)))
        (is (vector? (:span d)))))))


;; =============================================================================
;; Classes (T3)
;; =============================================================================

(deftest enum-runtime-emission-test
  (doseq [src ["enum E { A, B, C = 7, D }\nconsole.log(E.A, E.B, E.C, E.D, E[0], E[7], E[8]);"
               "const enum E { A = 2, B = 'bee' }\nconsole.log(E.A, E.B, E[2], typeof E[2]);"]]
    (testing src
      (let [result (run-all src)]
        (is (every? #(= (first (vals result)) %) (vals result)) (pr-str result))
        (is (= (if (str/starts-with? src "enum")
                 ["0 1 7 8 A C D"]
                 ["2 bee A string"])
               (first (vals result))))))))


(defn- ops
  "The operators `src` lowers to applications of."
  [src]
  (->> (nodes (body src))
       (filter #(and (= :application (:type %))
                     (= :variable (:type (:operator %)))))
       (map #(:name (:operator %)))
       set))


(deftest class-lowering-goldens-test
  (doseq [[label src expected]
          [["declaration"
            "class A {}"
            '#{js/class-heritage js/make-object js/make-class js/static-init!}]
           ["expression"
            "const C = class {};"
            '#{js/class-heritage js/make-class js/static-init!}]
           ["extends and super()"
            "class A {} class B extends A { constructor() { super(); } }"
            '#{js/super-call js/derived-result js/lexical-get}]
           ["default derived constructor"
            "class A {} class B extends A {}"
            '#{js/super-call js/this-after-super}]
           ["method"
            "class A { m() { return 1; } }"
            '#{js/def-class-method! js/make-function}]
           ["static method"
            "class A { static m() {} }"
            '#{js/def-class-method!}]
           ["accessors"
            "class A { get x() { return 1; } set x(v) {} }"
            '#{js/def-class-accessor!}]
           ["computed key"
            "class A { [k()]() {} }"
            '#{js/to-property-key js/def-class-method!}]
           ["instance field"
            "class A { x = 1; y; }"
            '#{js/add-field!}]
           ["static field and block"
            "class A { static x = 1; static { A.y = 2; } }"
            '#{js/static-init! js/define-field!}]
           ["private field"
            "class A { #x = 1; get() { return this.#x; } set(v) { this.#x = v; } inc() { this.#x++; } }"
            '#{js/private-name js/priv-get js/priv-set js/add-field!}]
           ["private method and accessor"
            "class A { #m() {} get #g() { return 1; } set #g(v) {} static #s() {} }"
            '#{js/add-priv-method! js/def-static-private!}]
           ["brand check"
            "class A { #x; static has(o) { return #x in o; } }"
            '#{js/priv-has}]
           ["private call"
            "class A { #m() {} f() { return this.#m(); } }"
            '#{js/priv-get js/call}]
           ["super property"
            "class A {} class B extends A { m() { return super.m() + super.x; } }"
            '#{js/super-get}]
           ["super assignment"
            "class A {} class B extends A { m() { super.x = 1; super.y += 2; } }"
            '#{js/super-set js/super-get}]
           ["new.target"
            "function f() { return new.target; } class A { constructor() { this.t = new.target; } }"
            '#{js/make-function-nt}]
           ["instanceof"
            "a instanceof B;"
            '#{js/instanceof}]]]
    (testing label
      (is (every? (ops src) expected) (pr-str (set (remove (ops src) expected)))))))


(deftest class-lowering-shape-test
  (testing "a derived constructor reads this through the TDZ cell"
    (let [o (ops "class A {} class B extends A { constructor() { super(); this.x = 1; } }")]
      (is (contains? o 'js/lexical-get))
      (is (contains? o 'js/derived-result))))
  (testing "an arrow inside a derived constructor keeps the cell and new.target"
    (let [o (ops "class A {} class B extends A { constructor() { const f = () => { super(); return [this, new.target]; }; f(); } }")]
      (is (contains? o 'js/super-call))
      (is (contains? o 'js/lexical-get))))
  (testing "the class name is a cell in TDZ through heritage and elements"
    (is (contains? (ops "class A { static m() { return A; } }") 'js/lexical-get)))
  (testing "assignment to the inner name is the runtime const error"
    (is (contains? (ops "class A { m() { A = 1; } }") 'js/const-assign!))))


(deftest class-renaming-law-test
  (doseq [src ["class A { #x = 1; static #s() {} get g() { return this.#x; } static { A.k = 1; } }"
               "class A {} class B extends A { constructor() { super(); } m() { return super.m(); } }"
               "const C = class { x = () => this; }; function f() { return new.target; }"]]
    (testing src
      (let [p (lower/lower-packet (parse src))]
        (is (= p (names/canonicalize p)) "idempotent on a lowered program")
        (is (every? (fn [s]
                      (or (= '%_ s) (re-matches #"%\d+" (name s))))
                    (filter #(and (symbol? %) (= \% (first (name %))))
                            (nodes p)))
            "every generated name is canonical"))
      (is (= (lower/lower-packet (parse src))
             (lower/lower-packet (parse (str "// c\n\n" src))))
          "comments and blank lines move no row"))))


(deftest class-refusals-test
  (doseq [[src construct slice]
          [["class A<T> {}" "type syntax" :T8]
           ["abstract class A {}" "type syntax" :T8]
           ["class A implements I {}" "type syntax" :T8]
           ["class A extends B<T> {}" "generic class heritage" :T8]
           ["class A { x: number; }" "type syntax" :T8]
           ["class A { x?: number; }" "type syntax" :T8]
           ["class A { m(): void {} }" "type syntax" :T8]
           ["class A { m<T>() {} }" "type syntax" :T8]
           ["class A { private x = 1; }" "type syntax" :T8]
           ["class A { readonly x = 1; }" "type syntax" :T8]
           ["class A { constructor(private x) {} }" "type syntax" :T8]
           ["class A { [k: string]: number; }" "type syntax" :T8]
           ["class A { m(); }" "type syntax" :T8]
           ["class A { async m() {} }" "async function" :T6]
           ["class A { m(a = 1) {} }" "default parameter" :T4]
           ["class A { m(...a) {} }" "rest parameter" :T4]
           ["class A { @d m() {} }" "decorator" :never]
           ["export class A {}" "module syntax" :T7]]]
    (testing src
      (let [d (diagnostic src)]
        (is (= :yang.ts/unsupported (:yang.ts/diagnostic d)) (pr-str d))
        (is (= construct (:construct d)))
        (is (= slice (:slice d)))
        (is (vector? (:span d)))))))


(deftest type-syntax-is-consumed-test
  (doseq [src ["let x: number = 1;" "function f(a: number) {}"
               "function f(): void {}" "interface I {}" "type T = number;"
               "let y = 1 as number;" "let w = 1 satisfies number;" "let z = a!;" "function f<T>(a) {}"]]
    (testing src
      (let [d (diagnostic src)]
        (is (= :yang.ts/unsupported (:yang.ts/diagnostic d)) (pr-str d))
        (is (= "type syntax" (:construct d)))
        (is (= :T8 (:slice d)))))))


(deftest early-errors-lower-test
  (doseq [src ["let a; let a;" "const a;" "break;" "continue;" "return 1;"
               "a: a: while (1) {}" "while (1) { break nope; }"
               "function f(a, a) {}" "(function (a, a) {});" "((a, a) => a);"
               "{ var x; let x; }" "{ let x; var x; }" "var x; let x;"
               "let x; var x;" "for (const x; false;) {}"
               "for (let i = 0; false;) { var i; }"]]
    (testing src
      (is (= :yang.ts/early-error (:yang.ts/diagnostic (diagnostic src)))
          src))))


(deftest global-eval-and-function-test
  (testing "a declared eval or Function is an ordinary binding"
    (doseq [src ["let eval = 1; eval;" "function Function() {} new Function();"
                 "function f(eval) { return eval('1'); }"
                 "var x; var x; function x() {}" "for (const x = 0; false;) {}"
                 "function f(a, b) {}" "outer: inner: for (;;) { break outer; }"]]
      (is (nil? (diagnostic src)) (pr-str [src (diagnostic src)])))))


(def ^:private t0-fixture-sources
  "Every source string a T0 `cst_test` fixture parses, read from that
   file's forms: the first string argument of each `(parse ...)` call."
  (delay
    (let [forms (binding [*ns* (the-ns 'yang.typescript.antlr.cst-test)]
                  (with-open [r (java.io.PushbackReader.
                                  (io/reader
                                    "test/yang/typescript/antlr/cst_test.clj"))]
                    (vec (take-while #(not= ::eof %)
                                     (repeatedly #(read {:eof ::eof} r))))))]
      (vec (distinct
             (for [f (tree-seq coll? seq forms)
                   :when (and (seq? f) (= 'parse (first f)))
                   a (rest f)
                   :when (string? a)]
               a))))))


(deftest no-unhandled-rule-fixtures-test
  (is (< 20 (count @t0-fixture-sources)))
  (doseq [src @t0-fixture-sources]
    (testing src
      (let [pk (cst/parse-source worker [:unit 0] src)]
        (when (= :yang.cst/ok (:yang.cst/outcome pk))
          (let [d (try (lower/lower-packet pk)
                       nil
                       (catch clojure.lang.ExceptionInfo e (ex-data e)))]
            (is (not= :yang.ts/unhandled-rule (:yang.ts/diagnostic d))
                (pr-str [src d]))))))))


(deftest no-unhandled-rule-test
  (doseq [src ["var a = 1, b;" "let c = 'x'; const d = 2n;"
               "function f(x, y) { return x + y * 2; }"
               "const g = (a) => a ? 1 : -a;" "const h = a => a;"
               "lbl: for (let i = 0; i < 3; i++) { if (i) continue lbl; else break; }"
               "x.y(1)[2];" "x.y(1)[2] = `a${b}c`;" "try { throw 1 } catch (e) { } finally { }"
               "switch (x) { case 1: y; default: z }" "if (a) b; else c;"
               "new Error('x');" "typeof x; void 0; !a; ~a; +a; -a;"
               "a ? b : c;" "a, b;" "debugger;" ";" "{ let a; }"
               "do x++; while (x < 3);" "while (a) { }" "(1 + 2) * 3;"
               "a = b = 3;" "a += 1; a >>>= 1; a **= 2;" "this;" "null; true; false;"
               "label: { break label; }" "function f() { return; }"
               "class A extends B {}" "let q = <T>(x) => x;" "namespace N {}"
               "with (a) {}" "yield 1;" "super.x;" "a.#b;" "new.target;"
               "if (a) function f() {}" "let [a] = b;" "export default 1;"]]
    (testing src
      (let [pk (cst/parse-source worker [:unit 0] src)]
        (when (= :yang.cst/ok (:yang.cst/outcome pk))
          (let [d (try (lower/lower-packet pk)
                       nil
                       (catch clojure.lang.ExceptionInfo e (ex-data e)))]
            (is (not= :yang.ts/unhandled-rule (:yang.ts/diagnostic d))
                (pr-str [src d]))))))))


;; =============================================================================
;; The renaming law
;; =============================================================================

(def ^:private programs
  ["var s = 0; for (let i = 0; i < 3; i++) { if (i == 1) continue; s += i; } console.log(s);"
   "function f(n) { try { return n; } finally { console.log('x'); } } f(1);"
   "outer: while (true) { switch (1) { case 1: break outer; } }"])


(defn- generated
  [tree]
  (->> (nodes tree)
       (filter symbol?)
       (filter #(str/starts-with? (name %) "%"))
       (remove #{'%_})
       set))


(deftest canonical-renaming-test
  (doseq [src programs]
    (testing src
      (let [p (lower/lower-packet (parse src))]
        (is (= p (names/canonicalize p)) "idempotent on a lowered program")
        (is (every? #(re-matches #"%\d+" (name %)) (generated p))
            "every generated name is canonical")
        (is (seq (generated p)))
        (testing "comments and blank lines move no row"
          (let [noisy (str "// lead\n\n" (str/replace src ";" "; // c\n\n"))]
            (is (= p (lower/lower-packet (parse noisy))))))))))


(deftest profile-shapes-test
  (let [pk (parse "console.log(1);")
        bundled (lower/lower-packet pk)
        linked (lower/lower-packet pk {:prelude :linked})]
    (is (< (count (pr-str linked)) (count (pr-str bundled)))
        "the linked program carries no prelude tree")
    (is (some #(= (u/lit 'js) %) (nodes linked)) "it requires the module js")
    (is (some #(= (u/lit 'yang.call) %) (nodes linked))
        "it requires the shared calling runtime")
    (is (some #(= (u/v 'js/init!) (:operator %)) (filter map? (nodes linked)))
        "and allocates the task's state")))
