(ns yang.typescript.antlr.scope-test
  "T1 scope analysis (S3): hoisting, block scopes, TDZ classification,
   resolve, and the early errors of the T1 plan (D10), from source."
  (:require
    [clojure.test :refer [deftest is testing]]
    [yang.antlr.packet :as p]
    [yang.typescript.antlr.cst :as cst]
    [yang.typescript.antlr.scope :as scope]))


(def ^:private worker (cst/make-worker :ts))


(defn- analysis
  [src]
  (let [pk (cst/parse-source worker [:unit 0] src)]
    (is (= :yang.cst/ok (:yang.cst/outcome pk)) src)
    (let [pk (p/validate! pk)]
      [pk (scope/analyze pk)])))


(defn- scopes-of
  [a kind]
  (filterv #(= kind (:kind %)) (vals (:scopes a))))


(defn- early
  [src]
  (try (analysis src)
       nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))


(deftest hoisting-test
  (let [[_ a] (analysis "var a = 1; function f(x) { var y; let z = 2; return x; }
                         let b = 3; const c = 4; { let d; }")
        module (get (:scopes a) (:module a))
        [f] (scopes-of a :function)]
    (is (= ["a"] (:vars module)))
    (is (= ["f"] (:functions module)))
    (is (= {"b" :let, "c" :const} (:lexical module)))
    (is (= ["x"] (:params f)))
    (is (= ["y"] (:vars f)))
    (is (= {"z" :let} (:lexical f)))
    (is (= 1 (count (scopes-of a :block))))
    (testing "a var in a nested block hoists to the function"
      (let [[_ a] (analysis "function g() { { var q = 1; } if (1) { var r; } }")
            [g] (scopes-of a :function)]
        (is (= ["q" "r"] (:vars g)))))))


(deftest resolve-test
  (let [[_ a] (analysis "let t = 1; var v; function f(p) { return p; } const k = 2;")
        m (:module a)
        [f] (scopes-of a :function)]
    (is (= {:kind :cell, :tdz? true, :const? false} (scope/resolve a m "t")))
    (is (= {:kind :cell, :tdz? true, :const? true} (scope/resolve a m "k")))
    (is (= {:kind :cell, :tdz? false, :const? false} (scope/resolve a m "v")))
    (is (= {:kind :cell, :tdz? false, :const? false} (scope/resolve a m "f")))
    (is (= {:kind :cell, :tdz? false, :const? false}
           (scope/resolve a (:id f) "p")))
    (is (= {:kind :cell, :tdz? true, :const? false}
           (scope/resolve a (:id f) "t")))
    (is (= {:kind :global} (scope/resolve a (:id f) "console")))))


(deftest scope-kinds-test
  (let [[_ a] (analysis "for (let i = 0; i < 2; i++) { } try { } catch (e) { }
                         const h = (x) => x; const g = function (y) { return this; };")]
    (is (= 1 (count (scopes-of a :for))))
    (is (= 1 (count (scopes-of a :catch))))
    (is (= 1 (count (scopes-of a :arrow))))
    (is (= ["e"] (:params (first (scopes-of a :catch)))))
    (let [[g] (scopes-of a :function)]
      (is (= ["y"] (:params g)))
      (is (:uses-this? g)))
    (is (not (:uses-this? (first (scopes-of a :arrow)))))))


(deftest named-function-expression-test
  (let [[_ a] (analysis "const f = function g(n) { return g; };")
        [g] (scopes-of a :function)]
    (is (= "g" (:self g)))
    (is (= {:kind :cell, :tdz? false, :const? true}
           (scope/resolve a (:id g) "g")))
    (is (= {:kind :global} (scope/resolve a (:module a) "g"))))
  (testing "a parameter of the same name shadows the self binding"
    (let [[_ a] (analysis "const f = function g(g) { return g; };")
          [g] (scopes-of a :function)]
      (is (= {:kind :cell, :tdz? false, :const? false}
             (scope/resolve a (:id g) "g"))))))


(deftest arrow-this-test
  (let [[_ a] (analysis "function f() { return () => this; }")
        [f] (scopes-of a :function)]
    (is (:uses-this? f) "an arrow's this is the enclosing function's")))


(defn- find-scope
  [a pred]
  (first (filter pred (vals (:scopes a)))))


(deftest class-scopes-test
  (let [[_ a] (analysis "class A { #p = 1; static #q() {} get #g() { return 1; }
                         set #g(v) {} f = 1; static s = 2; m(x) { return x; }
                         constructor(y) {} static { } }")
        cls (first (scopes-of a :class))
        m (:module a)]
    (testing "the class scope"
      (is (= "A" (:class-self cls)))
      (is (false? (:derived? cls)))
      (is (= {"#p" {:kind :field, :static? false}
              "#q" {:kind :method, :static? true}
              "#g" {:kind :accessor, :static? false}}
             (:privates cls))))
    (testing "the outer name is a TDZ binding, the inner one const-like"
      (is (= {:kind :cell, :tdz? true, :const? false}
             (scope/resolve a m "A")))
      (is (= {:kind :cell, :tdz? true, :const? true}
             (scope/resolve a (:id cls) "A"))))
    (testing "element scopes"
      (let [fns (scopes-of a :function)]
        (is (= 9 (count fns))
            "constructor, two accessors, two methods, three initializers, a static block")
        (is (= [:base] (vec (keep :ctor fns))))
        (is (= 3 (count (filter :initializer? fns))))
        (is (= 1 (count (filter :static-block? fns))))
        (is (every? :home? fns))
        (is (= ["x"] (:params (find-scope a #(= ["x"] (:params %))))))
        (is (= ["y"] (:params (find-scope a #(= ["y"] (:params %))))))))
    (testing "a class expression has no outer binding"
      (let [[_ a2] (analysis "const C = class N { m() { return N; } };")
            cls2 (first (scopes-of a2 :class))]
        (is (= "N" (:class-self cls2)))
        (is (= {:kind :global} (scope/resolve a2 (:module a2) "N")))))
    (testing "heritage makes the class derived"
      (let [[_ a3] (analysis "class B extends A { constructor() { super(); } }")]
        (is (true? (:derived? (first (scopes-of a3 :class)))))
        (is (= [:derived]
               (vec (keep :ctor (scopes-of a3 :function)))))))))


(deftest capture-propagation-test
  (testing "an arrow carries this/super/new.target to its owner"
    (let [[_ a] (analysis "class A extends B { constructor() { super();
                           const f = () => { const g = () => [super(), this, new.target]; }; } }")
          ctor (find-scope a :ctor)
          arrows (scopes-of a :arrow)]
      (is (= 2 (count arrows)))
      (is (every? #(= #{:this :super-call :new-target} (:captures %)) arrows))
      (is (= #{:this :super-call :new-target} (:captures ctor)))
      (is (:uses-super-call? ctor))
      (is (:uses-new-target? ctor))))
  (testing "new.target alone, with no this, still marks the owner"
    (let [[_ a] (analysis "function f() { return () => () => new.target; }")
          f (first (scopes-of a :function))]
      (is (:uses-new-target? f))
      (is (every? #(= #{:new-target} (:captures %)) (scopes-of a :arrow)))
      (is (false? (:uses-this? f)))))
  (testing "super properties from an arrow in a method"
    (let [[_ a] (analysis "class A extends B { m() { return () => super.x; } }")
          m (find-scope a :method?)]
      (is (:uses-super-prop? m))
      (is (= #{:super-prop} (:captures (first (scopes-of a :arrow)))))))
  (testing "an unrelated function does not inherit the method's super"
    (is (= :yang.ts/early-error
           (:yang.ts/diagnostic
             (early "class A extends B { m() { function g() { return super.x; } } }")))))
  (testing "object-literal methods have a home"
    (is (nil? (early "const o = { m() { return super.x; } };")))))


(deftest class-early-errors-test
  (doseq [[src msg-part]
          [["class A { constructor() {} constructor() {} }" "one constructor"]
           ["class A { constructor() { super(); } }" "'super' keyword"]
           ["class A { m() { super(); } }" "'super' keyword"]
           ["class A extends B { m() { super(); } }" "'super' keyword"]
           ["function f() { super(); }" "'super' keyword"]
           ["function f() { return super.x; }" "'super' keyword"]
           ["class A { static f = function () { return super.x; }; }" "'super' keyword"]
           ["class A { m() { return super; } }" "'super' keyword"]
           ["class A { m() { return this.#x; } }" "must be declared"]
           ["class A { #x; m() { return this.#y; } }" "must be declared"]
           ["class A { m(o) { return #x in o; } }" "must be declared"]
           ["this.#x;" "must be declared"]
           ["class A { #x; #x; }" "already been declared"]
           ["class A { #x; static #x() {} }" "already been declared"]
           ["class A { get #g() {} get #g() {} }" "already been declared"]
           ["class A { get #g() {} static set #g(v) {} }" "already been declared"]
           ["class A { #x; get #x() {} }" "already been declared"]
           ["class A { #constructor; }" "#constructor"]
           ["class A { #x; m() { delete this.#x; } }" "can not be deleted"]
           ["class A { #x; m(o) { delete o.#x; } }" "can not be deleted"]
           ["class A { #x; m() { delete (this.#x); } }" "can not be deleted"]
           ["class A { #x; m() { delete ((this.#x)); } }" "can not be deleted"]
           ["class A { #x; static make() { return new A(); } m() { delete A.make().#x; } }"
            "can not be deleted"]
           ["class A { constructor = 1; }" "named 'constructor'"]
           ["class A { get constructor() {} }" "named 'constructor'"]
           ["class A { static prototype() {} }" "'prototype'"]
           ["class A { static prototype = 1; }" "'prototype'"]
           ["class A { static get prototype() {} }" "'prototype'"]
           ["class A { x = arguments; }" "arguments"]
           ["class A { static { arguments; } }" "arguments"]
           ["class A { x = () => arguments; }" "arguments"]
           ["new.target;" "new.target"]
           ["const f = () => new.target;" "new.target"]
           ["class A { static { return 1; } }" "Illegal return"]
           ["class A {} class A {}" "already been declared"]
           ["let A; class A {}" "already been declared"]]]
    (testing src
      (let [d (early src)]
        (is (= :yang.ts/early-error (:yang.ts/diagnostic d)))
        (is (vector? (:span d)))
        (is (re-find (re-pattern msg-part)
                     (try (analysis src)
                          (catch clojure.lang.ExceptionInfo e (ex-message e))))))))
  (testing "spans point at the offending node"
    (let [src "class A { #x; m(o) { delete o.#x; } }"
          d (early src)
          [a b] (:span d)]
      (is (= "delete o.#x" (subs src a b))))
    (let [src "class A { constructor() {} constructor() {} }"
          [a b] (:span (early src))]
      (is (= "constructor() {}" (subs src a b)))
      (is (= 27 a)))))


(deftest class-valid-programs-test
  (doseq [src ["class A { #x = 1; get #g() { return 1; } set #g(v) {} m() { return this.#x + this.#g; } }"
               "class A { static get #g() { return 1; } static set #g(v) {} }"
               "class A { #x; static has(o) { return #x in o; } }"
               "class A { #x; m() { class B { n(o) { return o.#x; } } } }"
               "class A extends B { constructor() { super(); const f = () => super(); } }"
               "class A extends B { m() { return super.m(); } static s() { return super.s(); } }"
               "class A extends B { x = super.y; static z = super.w; static { super.q; } }"
               "class A { get x() { return super.x; } set x(v) { super.x = v; } }"
               "class A { x = new.target; static { new.target; } m() { return new.target; } }"
               "function F() { return new.target; } const g = function () { () => new.target; };"
               "class A { static constructor() {} static prototype2 = 1; }"
               "class A { 'constructor2'() {} ['prototype']() {} }"
               "class A { static #prototype = 1; }"
               "class A { m() { A = 1; } }"
               "class A { f = () => this; g = function () { return arguments; }; }"
               "class A { m() { return (function () { return arguments; })(); } }"
               "const o = { m() { return () => super.x; }, get p() { return super.p; } };"
               "class A { x = 1\n y = 2\n static z }"
               "{ class A {} } { class A {} }"]]
    (is (nil? (early src)) src)))


(deftest early-errors-test
  (doseq [[src msg-part]
          [["let a; let a;" "already been declared"]
           ["const a = 1; var a;" "already been declared"]
           ["var a; let a;" "already been declared"]
           ["function f(x) { let x; }" "already been declared"]
           ["const c;" "Missing initializer"]
           ["break;" "Illegal break"]
           ["continue;" "Illegal continue"]
           ["while (1) { break nope; }" "Undefined label"]
           ["a: { continue a; }" "for continue"]
           ["return 1;" "Illegal return"]
           ["a: a: while (1) { }" "already been declared"]
           ["a: b: a: while (1) { }" "already been declared"]
           ["{ var x; let x; }" "already been declared"]
           ["{ let x; var x; }" "already been declared"]
           ["var x; let x;" "already been declared"]
           ["let x; var x;" "already been declared"]
           ["function f() { { let x; { var x; } } }" "already been declared"]
           ["for (const x; false;) {}" "Missing initializer"]
           ["function f(a, a) {}" "Duplicate parameter"]
           ["(function (a, b, a) {});" "Duplicate parameter"]
           ["((a, a) => a);" "Duplicate parameter"]
           ["function f() { while (1) { function g() { break; } } }"
            "Illegal break"]]]
    (testing src
      (let [d (early src)]
        (is (= :yang.ts/early-error (:yang.ts/diagnostic d)))
        (is (vector? (:span d)))
        (is (some? d))
        (is (re-find (re-pattern msg-part)
                     (try (analysis src)
                          (catch clojure.lang.ExceptionInfo e (ex-message e))))))))
  (testing "valid programs raise nothing"
    (doseq [src ["while (1) { break; }" "a: while (1) { continue a; }"
                 "switch (1) { case 1: break; }" "a: { break a; }"
                 "function f() { return 1; }" "{ let a; } { let a; }"
                 "var a; var a;" "function f() {} function f() {}"
                 "a: { b: while (1) { continue b; } }"
                 "a: b: while (1) { continue a; }"
                 "a: b: while (1) { continue b; }"
                 "a: b: { break a; }"
                 "var x; var x; function x() {}"
                 "for (const x = 0; false;) {}"
                 "function f(a, b) {}"]]
      (is (nil? (early src)) src))))
