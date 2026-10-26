(ns yang.typescript.antlr.object-parity-test
  "Stage 0 (the T2 remainder), steps S3 and S4: constructibility by `:kind`
   and the eager `prototype`/`constructor` pair, `call`/`apply`/`bind`, the
   `Object.prototype` methods, the own-key helpers (F6) and the `Object`
   statics. Tiny UAST programs run on every VM through the parity harness."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [dao.jing.cbor :as cbor]
            [yang.typescript.antlr.prelude :as prelude]
            [yang.typescript.antlr.prelude-parity-test :as parity]))


(defn- result-vector?
  [x]
  (and (vector? x) (seq x) (not (symbol? (first x))) (some seq? x)))


(defn- unqualify
  [form]
  (walk/postwalk
    (fn [x]
      (cond (and (symbol? x)
                 (contains? #{"clojure.core" "cljs.core" "cljd.core"}
                            (namespace x)))
            (symbol (name x))
            (result-vector? x) (reduce (fn [acc a] (list 'js/vconj acc a)) [] x)
            :else x))
    form))


(def ^:private object-ctor '(js/global-get {:js/str "Object"}))


(defn- static-caller
  [arity]
  (let [ps (take arity '[a b c d])]
    (list 'fn
          (vec (cons 'm ps))
          (list 'js/call
                (list 'js/get-prop object-ctor 'm)
                :js/undefined
                (reduce (fn [acc p] (list 'js/vconj acc p)) [] ps)))))


(defn- with-statics
  "`form` with S1..S4 bound to `Object.<name>(a ...)` callers."
  [form]
  (list 'let
        (vec (mapcat (fn [n] [(symbol (str "S" n)) (static-caller n)])
                     [1 2 3 4]))
        form))


(defn- check
  "Run `cases` ([form expected]) as one vector program on every VM."
  [cases]
  (let [form (parity/program-form
               (reduce (fn [acc [f _]] (list 'js/vconj acc (with-statics (unqualify f))))
                       []
                       cases))
        expected (mapv (fn [[_ e]] (parity/expected-value e)) cases)]
    (doseq [[k result] (parity/run-with-prelude prelude/uast form)]
      (testing (str k)
        (is (= (count expected) (count result)) (pr-str result))
        (when (and (vector? result) (= (count expected) (count result)))
          (doseq [[[f _] exp act] (map vector cases expected result)]
            (is (cbor/content= exp act)
                (str (pr-str f) " => " (pr-str act) ", expected "
                     (pr-str exp)))))))))


(defn- k
  [x]
  {:js/str x})


(defn- vf
  "A form building the vector of the forms `xs`."
  [& xs]
  (reduce (fn [acc a] (list 'js/vconj acc a)) [] xs))


(defn- throws
  [expr]
  (list 'js/try (list 'fn [] expr) '(fn [e] (js/get-prop e {:js/str "name"}))))


(def ^:private new-obj '(js/make-object js.rt/obj-proto))


(defn- ostatic
  "A form calling `Object.<m>(args...)`."
  [m & args]
  (list 'js/call (list 'js/get-prop object-ctor (k m)) :js/undefined (apply vf args)))


(defn- items
  "The element vector of the array `form`."
  [form]
  (list 'get (list 'cell/get form) :items))


(def ^:private tuple-fn
  "A normal function answering [this arg0 arg1]."
  '(js/make-function {:js/str "f"}
                     2
                     :strict
                     :normal
                     (fn [this args]
                       (js/vconj (js/vconj (js/vconj [] this) (js/arg args 0))
                                 (js/arg args 1)))))


;; ------------------------------------------------------------------ S3
(def ^:private constructor-cases
  [;; an instance links to F.prototype, which links back to F
   ['(let [f (js/make-function {:js/str "F"} 0 :strict :normal (fn [this args] this))
           o (js/construct f [])
           p (js/get-prop f {:js/str "prototype"})
           ps (js/own-slot f {:js/str "prototype"})
           cs (js/own-slot p {:js/str "constructor"})]
       [(= (get (cell/get o) :proto) p)
        (js/strict-eq (js/get-prop o {:js/str "constructor"}) f)
        (get ps :writable) (get ps :enumerable) (get ps :configurable)
        (get cs :writable) (get cs :enumerable) (get cs :configurable)])
    [true true true false false true false true]]
   ;; prototype is the third own key, after length and name
   ['(js/own-keys (js/make-function {:js/str "F"} 0 :strict :normal (fn [this args] this)))
    [(k "length") (k "name") (k "prototype")]]
   ;; no other kind has a prototype or a [[Construct]]
   ['(js/own-keys (js/make-function {:js/str "a"} 0 :lexical :arrow (fn [this args] this)))
    [(k "length") (k "name")]]
   [(throws '(js/construct (js/make-function {:js/str "a"} 0 :lexical :arrow
                                             (fn [this args] this))
                           []))
    (k "TypeError")]
   [(throws '(js/construct (js/make-function {:js/str "m"} 0 :strict :method
                                             (fn [this args] this))
                           []))
    (k "TypeError")]
   [(throws '(js/construct (js/make-function {:js/str "g"} 0 :strict :getter
                                             (fn [this args] this))
                           []))
    (k "TypeError")]
   ;; builtins are not constructors unless flagged: BigInt, Symbol, String
   [(throws '(js/construct (js/global-get {:js/str "BigInt"}) [])) (k "TypeError")]
   [(throws '(js/construct (js/global-get {:js/str "Symbol"}) [])) (k "TypeError")]
   [(throws '(js/construct (js/global-get {:js/str "String"}) [])) (k "TypeError")]
   [(throws '(js/construct (js/get-prop js.rt/obj-proto {:js/str "toString"}) []))
    (k "TypeError")]
   ;; Error and Object are
   ['(let [e (js/construct (js/global-get {:js/str "Error"}) (js/vconj [] {:js/str "boom"}))]
       [(js/get-prop e {:js/str "message"})
        (= (get (cell/get e) :proto) js.rt/error-proto)])
    [(k "boom") true]]
   ['(js/object? (js/construct (js/global-get {:js/str "Object"}) [])) true]
   ;; an object return wins over the allocated this; a primitive does not
   ['(let [r (js/make-object js.rt/obj-proto)
           f (js/make-function {:js/str "F"} 0 :strict :normal (fn [this args] r))]
       (= (js/construct f []) r))
    true]
   ['(let [f (js/make-function {:js/str "F"} 0 :strict :normal
                               (fn [this args] {:js/str "x"}))]
       (js/object? (js/construct f [])))
    true]
   ;; Object.create(F.prototype) and a prototype method through new
   ['(let [f (js/make-function {:js/str "F"} 0 :strict :normal (fn [this args] this))
           p (js/get-prop f {:js/str "prototype"})
           m (js/make-function {:js/str "m"} 0 :strict :normal
                               (fn [this args] {:js/str "hi"}))]
       (do (js/put-value p {:js/str "m"} m)
           (js/call (js/get-prop (js/construct f []) {:js/str "m"})
                    :js/undefined
                    [])))
    (k "hi")]])


(defn- with-f
  "`body` with `f` bound to `tuple-fn` and `m` to a method-call helper:
   `(m name this args)` is `f.<name>.call`-style dispatch on `f`."
  [body]
  (list 'let ['f tuple-fn] body))


(def ^:private function-method-cases
  [;; call keeps a primitive this (strict) and forwards the arguments
   [(with-f '(js/call (js/get-prop f {:js/str "call"})
                      f
                      (js/vconj (js/vconj [] {:n 5}) {:n 7})))
    [{:n 5} {:n 7} :js/undefined]]
   ;; apply with an array, with null, and with an array-like object
   [(with-f '(js/call (js/get-prop f {:js/str "apply"})
                      f
                      (js/vconj (js/vconj [] {:n 1})
                                (js/list->array (js/vconj [] {:n 2})))))
    [{:n 1} {:n 2} :js/undefined]]
   [(with-f '(js/call (js/get-prop f {:js/str "apply"})
                      f
                      (js/vconj (js/vconj [] {:n 1}) :js/null)))
    [{:n 1} :js/undefined :js/undefined]]
   [(with-f '(let [al (js/make-object js.rt/obj-proto)]
               (do (js/create-data-prop! al {:js/str "length"} {:n 2})
                   (js/create-data-prop! al {:js/str "1"} {:n 9})
                   (js/call (js/get-prop f {:js/str "apply"})
                            f
                            (js/vconj (js/vconj [] {:n 1}) al)))))
    [{:n 1} :js/undefined {:n 9}]]
   [(with-f (throws '(js/call (js/get-prop f {:js/str "apply"})
                              f
                              (js/vconj (js/vconj [] {:n 1}) {:n 3}))))
    (k "TypeError")]
   [(with-f (throws '(js/call (js/get-prop f {:js/str "call"})
                              (js/make-object js.rt/obj-proto)
                              [])))
    (k "TypeError")]
   ;; bind: partial application, name, length
   [(with-f '(let [b (js/call (js/get-prop f {:js/str "bind"})
                              f
                              (js/vconj (js/vconj [] {:n 9}) {:n 1}))]
               [(js/call b :js/undefined (js/vconj [] {:n 2}))
                (js/get-prop b {:js/str "name"})
                (js/get-prop b {:js/str "length"})]))
    [[{:n 9} {:n 1} {:n 2}] (k "bound f") {:n 1}]]
   ;; bind of a bound function nests the names and the arguments
   [(with-f '(let [b1 (js/call (js/get-prop f {:js/str "bind"})
                               f
                               (js/vconj (js/vconj [] {:n 9}) {:n 1}))
                   b2 (js/call (js/get-prop b1 {:js/str "bind"})
                               b1
                               (js/vconj (js/vconj [] {:n 8}) {:n 2}))]
               [(js/call b2 :js/undefined []) (js/get-prop b2 {:js/str "name"})
                (js/get-prop b2 {:js/str "length"})]))
    [[{:n 9} {:n 1} {:n 2}] (k "bound bound f") {:n 0}]]
   ;; new on a bound constructor constructs the target with the bound args
   ['(let [g (js/make-function {:js/str "G"}
                               1
                               :strict
                               :normal
                               (fn [this args]
                                 (do (js/create-data-prop! this
                                                           {:js/str "x"}
                                                           (js/arg args 0))
                                     this)))
           b (js/call (js/get-prop g {:js/str "bind"})
                      g
                      (js/vconj (js/vconj [] :js/null) {:n 3}))
           o (js/construct b [])]
       [(js/get-prop o {:js/str "x"})
        (= (get (cell/get o) :proto) (js/get-prop g {:js/str "prototype"}))])
    [{:n 3} true]]
   ;; a bound arrow is not a constructor
   [(throws '(js/construct
               (js/call (js/get-prop (js/make-function {:js/str "a"}
                                                       0
                                                       :lexical
                                                       :arrow
                                                       (fn [this args] this))
                                     {:js/str "bind"})
                        (js/make-function {:js/str "a"}
                                          0
                                          :lexical
                                          :arrow
                                          (fn [this args] this))
                        [])
               []))
    (k "TypeError")]])


(def ^:private prototype-method-cases
  [;; hasOwnProperty / isPrototypeOf / propertyIsEnumerable
   ['(let [p (js/make-object js.rt/obj-proto)
           o (js/make-object p)
           hop (js/get-prop js.rt/obj-proto {:js/str "hasOwnProperty"})
           ipo (js/get-prop js.rt/obj-proto {:js/str "isPrototypeOf"})
           pie (js/get-prop js.rt/obj-proto {:js/str "propertyIsEnumerable"})]
       (do (js/create-data-prop! p {:js/str "a"} {:n 1})
           (js/create-data-prop! o {:js/str "b"} {:n 2})
           (js/define-own! o {:js/str "h"} (js/slot {:n 3} true false true))
           [(js/call hop o (js/vconj [] {:js/str "a"}))
            (js/call hop o (js/vconj [] {:js/str "b"}))
            (js/call ipo p (js/vconj [] o))
            (js/call ipo o (js/vconj [] p))
            (js/call ipo p (js/vconj [] {:n 1}))
            (js/call pie o (js/vconj [] {:js/str "b"}))
            (js/call pie o (js/vconj [] {:js/str "h"}))
            (js/call pie o (js/vconj [] {:js/str "a"}))]))
    [false true true false false true false false]]
   [(throws '(js/call (js/get-prop js.rt/obj-proto {:js/str "hasOwnProperty"})
                      :js/undefined
                      (js/vconj [] {:js/str "a"})))
    (k "TypeError")]
   ;; toString tags
   ['(let [ts (js/get-prop js.rt/obj-proto {:js/str "toString"})
           tag (fn [v] (js/call ts v []))]
       [(tag (js/list->array [])) (tag (js/make-object js.rt/obj-proto))
        (tag :js/null) (tag :js/undefined) (tag {:n 1}) (tag {:js/str "s"})
        (tag true) (tag (js/global-get {:js/str "Object"}))
        (tag (js/make-error js.rt/error-proto {:js/str "m"}))
        (tag (js/make-symbol :js/undefined)) (tag 12345678901234567890)])
    [(k "[object Array]") (k "[object Object]") (k "[object Null]")
     (k "[object Undefined]") (k "[object Number]") (k "[object String]")
     (k "[object Boolean]") (k "[object Function]") (k "[object Error]")
     (k "[object Symbol]") (k "[object BigInt]")]]
   ;; String.prototype.toString answers the string (closes ledger row 88)
   ['(js/call (js/get-value {:js/str "abc"} {:js/str "toString"})
              {:js/str "abc"}
              [])
    (k "abc")]
   ['(js/call (js/get-value {:js/str "abc"} {:js/str "valueOf"})
              {:js/str "abc"}
              [])
    (k "abc")]
   ;; the __proto__ accessor
   ['(let [p (js/make-object js.rt/obj-proto)
           o (js/make-object p)
           q (js/make-object js.rt/obj-proto)]
       [(= (js/get-value o {:js/str "__proto__"}) p)
        (do (js/put-value o {:js/str "__proto__"} q) (= (js/get-value o {:js/str "__proto__"}) q))
        (do (js/put-value o {:js/str "__proto__"} {:n 1})
            (= (js/get-value o {:js/str "__proto__"}) q))
        (js/kind-of (js/get-value o {:js/str "__proto__"}))
        (= (js/get-value {:js/str "s"} {:js/str "__proto__"}) js.rt/str-proto)])
    [true true true :object true]]
   [(throws '(let [p (js/make-object js.rt/obj-proto)
                   c (js/make-object p)]
               (js/put-value p {:js/str "__proto__"} c)))
    (k "TypeError")]])


;; ------------------------------------------------------------------ F6
(def ^:private key-helper-cases
  [['(let [o (js/make-object js.rt/obj-proto)
           s (js/make-symbol {:js/str "s"})]
       (do (js/create-data-prop! o {:js/str "b"} {:n 1})
           (js/create-data-prop! o {:js/str "2"} {:n 1})
           (js/define-own! o {:js/str "h"} (js/slot {:n 1} true false true))
           (js/define-own! o s (js/slot {:n 1} true true true))
           [(js/own-string-keys o) (js/own-enumerable-string-keys o)
            (data/count (js/own-symbol-keys o))
            (= (get (js/own-symbol-keys o) 0) s) (data/count (js/own-keys o))]))
    [[(k "2") (k "b") (k "h")] [(k "2") (k "b")] 1 true 4]]
   ;; strings expose their virtual indices and length
   ['[(js/own-string-keys {:js/str "ab"}) (js/own-enumerable-string-keys {:js/str "ab"})]
    [[(k "0") (k "1") (k "length")] [(k "0") (k "1")]]]
   ;; define-accessor-half! merges a getter and a setter into one slot
   ['(let [o (js/make-object js.rt/obj-proto)
           g (js/make-function {:js/str "get x"} 0 :strict :accessor (fn [this args] {:n 1}))
           st (js/make-function {:js/str "set x"} 1 :strict :accessor (fn [this args] :js/undefined))]
       (do (js/define-accessor-half! o {:js/str "x"} :get g true)
           (js/define-accessor-half! o {:js/str "x"} :set st true)
           (let [sl (js/own-slot o {:js/str "x"})]
             [(= (get sl :get) g) (= (get sl :set) st) (get sl :enumerable)
              (get sl :configurable) (data/count (js/om-keys (get (cell/get o) :props)))])))
    [true true true true 1]]
   ;; a half replaces a data slot of the same key and keeps its position
   ['(let [o (js/make-object js.rt/obj-proto)
           g (js/make-function {:js/str "get a"} 0 :strict :accessor (fn [this args] {:n 7}))]
       (do (js/create-data-prop! o {:js/str "a"} {:n 1})
           (js/create-data-prop! o {:js/str "b"} {:n 1})
           (js/define-accessor-half! o {:js/str "a"} :get g true)
           [(js/own-string-keys o) (js/get-prop o {:js/str "a"})
            (= (get (js/own-slot o {:js/str "a"}) :set) :js/undefined)]))
    [[(k "a") (k "b")] {:n 7} true]]])


;; ------------------------------------------------------------------ S4
(def ^:private object-static-cases
  [;; keys / values / entries: integer keys first, then strings, no symbols
   ['(let [o (js/make-object js.rt/obj-proto)
           s (js/make-symbol {:js/str "s"})]
       (do (js/create-data-prop! o {:js/str "b"} {:n 1})
           (js/create-data-prop! o {:js/str "1"} {:n 2})
           (js/create-data-prop! o s {:n 3})
           (js/define-own! o {:js/str "h"} (js/slot {:n 4} true false true))
           (js/create-data-prop! o {:js/str "0"} {:n 5})
           [(get (cell/get (S1 {:js/str "keys"} o)) :items)
            (get (cell/get (S1 {:js/str "values"} o)) :items)
            (js/array? (S1 {:js/str "keys"} o))
            (= (get (cell/get (S1 {:js/str "keys"} o)) :proto) js.rt/array-proto)]))
    [[(k "0") (k "1") (k "b")] [{:n 5} {:n 2} {:n 1}] true true]]
   ['(let [o (js/make-object js.rt/obj-proto)]
       (do (js/create-data-prop! o {:js/str "a"} {:n 1})
           (let [e (get (get (cell/get (S1 {:js/str "entries"} o)) :items) 0)]
             [(js/array? e) (get (cell/get e) :items)])))
    [true [(k "a") {:n 1}]]]
   ;; strings: Object.keys("ab"), getOwnPropertyNames includes length
   [(items (ostatic "keys" (k "ab"))) [(k "0") (k "1")]]
   [(items (ostatic "getOwnPropertyNames" (k "ab"))) [(k "0") (k "1") (k "length")]]
   [(throws (ostatic "keys" :js/null)) (k "TypeError")]
   ;; arrays: getOwnPropertyNames lists indices then length
   [(items (ostatic "getOwnPropertyNames" '(js/list->array (js/vconj [] {:n 1}))))
    [(k "0") (k "length")]]
   ;; a lone-surrogate key round-trips through Object.keys
   ['(let [o (js/make-object js.rt/obj-proto)
           key (js/mk-str [55296])]
       (do (js/create-data-prop! o key {:n 1})
           (let [ks (get (cell/get (S1 {:js/str "keys"} o)) :items)]
             [(= (get ks 0) key) (js/str-unit-length (get ks 0))])))
    [true 1]]
   ;; assign: later sources win, getters are read by value, setters run
   ['(let [t (js/make-object js.rt/obj-proto)
           a (js/make-object js.rt/obj-proto)
           b (js/make-object js.rt/obj-proto)
           log (js/make-object js.rt/obj-proto)]
       (do (js/create-data-prop! a {:js/str "x"} {:n 1})
           (js/create-data-prop! b {:js/str "x"} {:n 2})
           (js/define-own! b
                           {:js/str "g"}
                           (js/mk-accessor-slot
                             (js/make-function {:js/str "g"} 0 :strict :accessor
                                               (fn [this args] {:n 6}))
                             :js/undefined
                             true
                             true))
           (js/define-own! t
                           {:js/str "s"}
                           (js/mk-accessor-slot
                             :js/undefined
                             (js/make-function {:js/str "s"} 1 :strict :accessor
                                               (fn [this args]
                                                 (js/create-data-prop! log {:js/str "s"} (js/arg args 0))))
                             true
                             true))
           (js/create-data-prop! b {:js/str "s"} {:n 8})
           (S4 {:js/str "assign"} t a :js/undefined b)
           [(js/get-prop t {:js/str "x"})
            (js/get-prop t {:js/str "g"})
            (js/get-prop log {:js/str "s"})
            (js/own-string-keys t)]))
    [{:n 2} {:n 6} {:n 8} [(k "s") (k "x") (k "g")]]]
   [(throws (ostatic "assign" {:n 1})) (k "TypeError")]
   ;; create: null prototype, and with a property map
   ['(let [o (S1 {:js/str "create"} :js/null)]
       [(= (get (cell/get o) :proto) :js/null)
        (js/has-property o {:js/str "toString"})])
    [true false]]
   ['(let [pm (js/make-object js.rt/obj-proto)
           d (js/make-object js.rt/obj-proto)]
       (do (js/create-data-prop! d {:js/str "value"} {:n 5})
           (js/create-data-prop! pm {:js/str "k"} d)
           (let [o (S2 {:js/str "create"} js.rt/obj-proto pm)
                 sl (js/own-slot o {:js/str "k"})]
             [(get sl :value) (get sl :writable) (get sl :enumerable)
              (get sl :configurable)])))
    [{:n 5} false false false]]
   [(throws (ostatic "create" {:n 1})) (k "TypeError")]
   ;; defineProperty returns its target; descriptor round trip and order
   ['(let [o (js/make-object js.rt/obj-proto)
           d (js/make-object js.rt/obj-proto)]
       (do (js/create-data-prop! d {:js/str "value"} {:n 1})
           (js/create-data-prop! d {:js/str "enumerable"} true)
           (let [r (S3 {:js/str "defineProperty"} o {:js/str "a"} d)
                 gd (S2 {:js/str "getOwnPropertyDescriptor"} o {:js/str "a"})]
             [(= r o) (js/own-string-keys gd) (js/get-prop gd {:js/str "value"})
              (js/get-prop gd {:js/str "enumerable"})
              (js/get-prop gd {:js/str "writable"})
              (js/get-prop gd {:js/str "configurable"})
              (S2 {:js/str "getOwnPropertyDescriptor"} o {:js/str "zz"})])))
    [true [(k "value") (k "writable") (k "enumerable") (k "configurable")]
     {:n 1} true false false :js/undefined]]
   [(throws (ostatic "defineProperty" {:n 1} (k "a") new-obj)) (k "TypeError")]
   ;; non-configurable redefinition is a TypeError
   [(throws '(let [o (js/make-object js.rt/obj-proto)
                   d (js/make-object js.rt/obj-proto)]
               (do (js/create-data-prop! d {:js/str "value"} {:n 1})
                   (S3 {:js/str "defineProperty"} o {:js/str "a"} d)
                   (js/create-data-prop! d {:js/str "value"} {:n 2})
                   (S3 {:js/str "defineProperty"} o {:js/str "a"} d))))
    (k "TypeError")]
   ;; defineProperties and getOwnPropertyDescriptors
   ['(let [o (js/make-object js.rt/obj-proto)
           pm (js/make-object js.rt/obj-proto)
           da (js/make-object js.rt/obj-proto)
           db (js/make-object js.rt/obj-proto)]
       (do (js/create-data-prop! da {:js/str "value"} {:n 1})
           (js/create-data-prop! db {:js/str "value"} {:n 2})
           (js/create-data-prop! pm {:js/str "a"} da)
           (js/create-data-prop! pm {:js/str "b"} db)
           (S2 {:js/str "defineProperties"} o pm)
           (let [ds (S1 {:js/str "getOwnPropertyDescriptors"} o)]
             [(js/own-string-keys o) (js/own-string-keys ds)
              (js/get-prop (js/get-prop ds {:js/str "b"}) {:js/str "value"})])))
    [[(k "a") (k "b")] [(k "a") (k "b")] {:n 2}]]
   ;; symbols
   ['(let [o (js/make-object js.rt/obj-proto)
           s (js/make-symbol {:js/str "s"})]
       (do (js/create-data-prop! o {:js/str "a"} {:n 1})
           (js/create-data-prop! o s {:n 1})
           (let [ks (get (cell/get (S1 {:js/str "getOwnPropertySymbols"} o)) :items)]
             [(data/count ks) (= (get ks 0) s)
              (get (cell/get (S1 {:js/str "keys"} o)) :items)])))
    [1 true [(k "a")]]]
   ;; getPrototypeOf / setPrototypeOf, including cycles and primitives
   ['(let [p (js/make-object js.rt/obj-proto)
           o (js/make-object js.rt/obj-proto)]
       [(= (S1 {:js/str "getPrototypeOf"} o) js.rt/obj-proto)
        (= (S1 {:js/str "getPrototypeOf"} {:js/str "s"}) js.rt/str-proto)
        (= (S1 {:js/str "getPrototypeOf"} {:n 1}) js.rt/num-proto)
        (= (S2 {:js/str "setPrototypeOf"} o p) o)
        (= (S1 {:js/str "getPrototypeOf"} o) p)
        (= (S2 {:js/str "setPrototypeOf"} o :js/null) o)
        (= (get (cell/get o) :proto) :js/null)])
    [true true true true true true true]]
   [(throws '(S1 {:js/str "getPrototypeOf"} :js/undefined)) (k "TypeError")]
   [(throws '(let [p (js/make-object js.rt/obj-proto)
                   c (js/make-object p)]
               (S2 {:js/str "setPrototypeOf"} p c)))
    (k "TypeError")]
   [(throws '(S2 {:js/str "setPrototypeOf"} (js/make-object js.rt/obj-proto) {:n 1}))
    (k "TypeError")]
   [(throws '(let [o (js/make-object js.rt/obj-proto)]
               (do (S1 {:js/str "preventExtensions"} o)
                   (S2 {:js/str "setPrototypeOf"} o :js/null))))
    (k "TypeError")]
   ;; freeze / seal / preventExtensions on objects
   ['(let [o (js/make-object js.rt/obj-proto)
           a (js/make-object js.rt/obj-proto)
           b (js/make-object js.rt/obj-proto)
           c (js/make-object js.rt/obj-proto)]
       (do (js/create-data-prop! o {:js/str "x"} {:n 1})
           (js/create-data-prop! a {:js/str "x"} {:n 1})
           (js/create-data-prop! b {:js/str "x"} {:n 1})
           (S1 {:js/str "freeze"} a)
           (S1 {:js/str "seal"} b)
           (S1 {:js/str "preventExtensions"} c)
           [(S1 {:js/str "isFrozen"} o) (S1 {:js/str "isFrozen"} a)
            (S1 {:js/str "isSealed"} a) (S1 {:js/str "isFrozen"} b)
            (S1 {:js/str "isSealed"} b) (S1 {:js/str "isExtensible"} o)
            (S1 {:js/str "isExtensible"} a) (S1 {:js/str "isFrozen"} c)
            (S1 {:js/str "isSealed"} c) (S1 {:js/str "isFrozen"} {:n 1})
            (S1 {:js/str "isExtensible"} {:n 1})]))
    [false true true false true true false true true true false]]
   [(throws '(let [a (js/make-object js.rt/obj-proto)]
               (do (js/create-data-prop! a {:js/str "x"} {:n 1})
                   (S1 {:js/str "freeze"} a)
                   (js/put-value a {:js/str "x"} {:n 2}))))
    (k "TypeError")]
   [(throws '(let [a (js/make-object js.rt/obj-proto)]
               (do (S1 {:js/str "freeze"} a)
                   (js/put-value a {:js/str "y"} {:n 2}))))
    (k "TypeError")]
   ;; sealed objects still write existing keys but cannot delete
   ['(let [b (js/make-object js.rt/obj-proto)]
       (do (js/create-data-prop! b {:js/str "x"} {:n 1})
           (S1 {:js/str "seal"} b)
           (js/put-value b {:js/str "x"} {:n 2})
           [(js/get-prop b {:js/str "x"}) (js/delete-prop b {:js/str "x"})]))
    [{:n 2} false]]
   ;; freezing and sealing arrays
   ['(let [a (js/list->array (js/vconj [] {:n 1}))
           b (js/list->array (js/vconj [] {:n 1}))]
       (do (S1 {:js/str "freeze"} a)
           (S1 {:js/str "seal"} b)
           [(S1 {:js/str "isFrozen"} a) (S1 {:js/str "isSealed"} a)
            (S1 {:js/str "isFrozen"} b) (S1 {:js/str "isSealed"} b)
            (js/set-prop a {:js/str "0"} {:n 9} a)
            (js/set-prop b {:js/str "0"} {:n 9} b)
            (js/get-prop b {:js/str "0"})]))
    [true true false true false true {:n 9}]]
   ;; is / hasOwn / fromEntries / Object(x)
   ['[(S2 {:js/str "is"} {:n 0} (js/neg-zero))
      (S2 {:js/str "is"} (js/nan) (js/nan))
      (S2 {:js/str "is"} {:n 1} {:n 1})]
    [false true true]]
   ['(let [o (js/make-object js.rt/obj-proto)]
       [(S2 {:js/str "hasOwn"} o {:js/str "toString"})
        (S2 {:js/str "hasOwn"} {:js/str "ab"} {:js/str "1"})
        (S2 {:js/str "hasOwn"} {:js/str "ab"} {:js/str "2"})])
    [false true false]]
   ['(let [mk (fn [a b] (js/list->array (js/vconj (js/vconj [] a) b)))
           o (S1 {:js/str "fromEntries"}
                 (js/list->array (js/vconj (js/vconj [] (mk {:js/str "a"} {:n 1}))
                                           (mk {:js/str "b"} {:n 2}))))]
       [(js/own-string-keys o) (js/get-prop o {:js/str "b"})])
    [[(k "a") (k "b")] {:n 2}]]
   [(throws '(S1 {:js/str "fromEntries"} (js/list->array (js/vconj [] {:n 1}))))
    (k "TypeError")]
   ['(let [o (js/make-object js.rt/obj-proto)
           f (js/global-get {:js/str "Object"})]
       [(= (js/call f :js/undefined (js/vconj [] o)) o)
        (js/object? (js/call f :js/undefined []))
        (js/object? (js/call f :js/undefined (js/vconj [] :js/null)))])
    [true true true]]
   [(throws '(js/call (js/global-get {:js/str "Object"})
                      :js/undefined
                      (js/vconj [] {:n 1})))
    (k "TypeError")]
   ;; Object.prototype.constructor is Object; name and length
   ['[(js/strict-eq (js/get-prop js.rt/obj-proto {:js/str "constructor"})
                    (js/global-get {:js/str "Object"}))
      (js/get-prop (js/global-get {:js/str "Object"}) {:js/str "name"})
      (js/get-prop (js/global-get {:js/str "Object"}) {:js/str "length"})]
    [true (k "Object") {:n 1}]]])


(deftest constructors-test (check constructor-cases))


(deftest function-methods-test (check function-method-cases))


(deftest object-prototype-methods-test (check prototype-method-cases))


(deftest own-key-helpers-test (check key-helper-cases))


(deftest object-statics-test (check object-static-cases))
