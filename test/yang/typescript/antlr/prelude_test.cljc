(ns yang.typescript.antlr.prelude-test
  "T2 step S1: the core cell primitives and the F1 guardrail. A symbol is a
   cell whose `:js/type` is `:symbol`, so `js/cell?` alone no longer means
   Type(x) is Object: `js/object?` does. Programs run on every VM through
   the parity harness."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [dao.jing.cbor :as cbor]
            [yang.typescript.antlr.prelude :as prelude]
            [yang.typescript.antlr.prelude-parity-test :as parity]))


(defn- result-vector?
  "A vector literal of program forms: its first element is a form, not a
   binder or a parameter."
  [x]
  (and (vector? x)
       (seq x)
       (not (symbol? (first x)))
       (some seq? x)))


(defn- unqualify
  "`form` with the `clojure.core/` a syntax-quote adds taken off and every
   result vector literal built element by element (a vector literal in a
   program is data, not a vector of values)."
  [form]
  (walk/postwalk
    (fn [x]
      (cond (and (symbol? x) (= (namespace x) "clojure.core")) (symbol (name x))
            (result-vector? x) (reduce (fn [acc a] (list 'js/vconj acc a)) [] x)
            :else x))
    form))


(defn- check
  "Run `cases` ([form expected]) as one vector program on every VM."
  [cases]
  (let [form (parity/program-form
               (reduce (fn [acc [f _]] (list 'js/vconj acc (unqualify f)))
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


(def ^:private sym-cases
  [;; kinds
   ['(js/kind-of (js/make-symbol {:js/str "s"})) :symbol]
   ['(js/object? (js/make-symbol {:js/str "s"})) false]
   ['(js/object? (js/make-object js.rt/obj-proto)) true]
   ['(js/object? {:js/str "s"}) false]
   ['(js/cell? (js/make-symbol {:js/str "s"})) true]
   ['(js/typeof (js/make-symbol {:js/str "s"})) {:js/str "symbol"}]
   ;; a symbol key through [] is returned unchanged and addresses its own slot
   ['(let [s (js/make-symbol {:js/str "k"})
           o (js/make-object js.rt/obj-proto)]
       (do (js/define-own! o s (js/slot {:js/str "v"} true true true))
           (js/vconj (js/vconj (js/vconj [] (js/get-value o (js/to-property-key s)))
                               (js/get-value o {:js/str "k"}))
                     (data/count (get (get (cell/get o) :props) :syms)))))
    [{:js/str "v"} :js/undefined 1]]
   ['(js/to-property-key (js/make-object js.rt/obj-proto))
    {:js/str "[object Object]"}]
   ['(js/to-property-key {:n 1}) {:js/str "1"}]
   ;; sym == {valueOf() { return sym; }}
   ['(let [s (js/make-symbol {:js/str "s"})
           o (js/make-object js.rt/obj-proto)]
       (do (js/define-own! o
                           {:js/str "valueOf"}
                           (js/slot (js/make-function {:js/str "valueOf"}
                                                      0
                                                      :strict :normal
                                                      (fn [this args] s))
                                    true
                                    false
                                    true))
           (js/loose-eq s o)))
    true]
   ['(js/loose-eq (js/make-symbol {:js/str "s"}) (js/make-symbol {:js/str "s"}))
    false]
   ;; new F where F returns a symbol: the symbol is not an object, so the
   ;; fresh object wins
   ['(js/object? (js/construct (js/make-function {:js/str "F"}
                                                 0
                                                 :strict :normal
                                                 (fn [this args] (js/make-symbol :js/undefined)))
                               []))
    true]
   ;; F.prototype = Symbol(): the instance falls back to Object.prototype
   ['(let [f (js/make-function {:js/str "F"} 0 :strict :normal (fn [this args] this))]
       (do (js/define-own! f
                           {:js/str "prototype"}
                           (js/slot (js/make-symbol :js/undefined)
                                    true
                                    false
                                    false))
           (= (get (cell/get (js/construct f [])) :proto) js.rt/obj-proto)))
    true]])


(def ^:private descriptor-cases
  [;; an accessor reads with the original receiver
   ['(let [o (js/make-object js.rt/obj-proto)
           c (js/make-object o)
           g (js/make-function {:js/str "g"}
                               0
                               :strict :normal
                               (fn [this args] (js/get-prop this {:js/str "x"})))]
       (do (js/define-own! o
                           {:js/str "a"}
                           (assoc (assoc (js/slot :js/undefined false true true)
                                         :get g)
                                  :set :js/undefined))
           (js/define-own! c
                           {:js/str "x"}
                           (js/slot {:js/str "child"} true true true))
           (js/get-prop c {:js/str "a"})))
    {:js/str "child"}]
   ;; validation: absent slot follows extensibility
   ['(js/validate-descriptor true nil (assoc {} :value 1)) true]
   ['(js/validate-descriptor false nil (assoc {} :value 1)) false]
   ;; configurable slots accept anything
   ['(js/validate-descriptor false
                             (js/slot 1 false false true)
                             (assoc {} :value 2))
    true]
   ;; non-configurable: no configurable, no enumerable flip, no kind change
   ['(js/validate-descriptor false
                             (js/slot 1 true true false)
                             (assoc {} :configurable true))
    false]
   ['(js/validate-descriptor false
                             (js/slot 1 true true false)
                             (assoc {} :enumerable false))
    false]
   ['(js/validate-descriptor false
                             (js/slot 1 true true false)
                             (assoc {} :get :js/undefined))
    false]
   ;; non-configurable data: writable only goes down, value only while writable
   ['(js/validate-descriptor false
                             (js/slot 1 true true false)
                             (assoc (assoc {} :value 2) :writable false))
    true]
   ['(js/validate-descriptor false
                             (js/slot 1 false true false)
                             (assoc {} :writable true))
    false]
   ['(js/validate-descriptor false
                             (js/slot 1 false true false)
                             (assoc {} :value 2))
    false]
   ['(js/validate-descriptor false
                             (js/slot 1 false true false)
                             (assoc {} :value 1))
    true]
   ['(js/validate-descriptor false (js/slot 1 false true false) {}) true]])


(deftest symbol-and-object-kinds-test (check sym-cases))


(deftest descriptor-validation-and-receiver-reads-test
  (check descriptor-cases))


(deftest deep-prototype-chain-test
  (testing "reading through 10000 prototypes runs in constant stack"
    (check [['((fn [self n acc]
                 (if (= n 0)
                   (js/get-prop acc {:js/str "k"})
                   (self self (- n 1) (js/make-object acc))))
               (fn [self n acc]
                 (if (= n 0)
                   (js/get-prop acc {:js/str "k"})
                   (self self (- n 1) (js/make-object acc))))
               10000
               (let [root (js/make-object :js/null)]
                 (do (js/define-own! root
                                     {:js/str "k"}
                                     (js/slot {:js/str "deep"} true true true))
                     root)))
             {:js/str "deep"}]])))


;; ---------------------------------------------------------------- S1 core
(defn- k
  [x]
  {:js/str x})


(defn- d
  "A form building the partial descriptor of the key/value pairs `kvs`."
  [& kvs]
  (reduce (fn [acc [key v]] (list 'assoc acc key v)) {} (partition 2 kvs)))


(defn- throws
  [expr]
  (list 'js/try (list 'fn [] expr) '(fn [e] (js/get-prop e {:js/str "name"}))))


(def ^:private new-obj '(js/make-object js.rt/obj-proto))


(def ^:private getter
  '(js/make-function {:js/str "g"} 0 :strict :getter (fn [this args] 1)))


(def ^:private getter2
  '(js/make-function {:js/str "g2"} 0 :strict :getter (fn [this args] 2)))


(defn- redefine
  "Define key `a` as `init` (when non-nil) on a fresh object, then as `desc`:
   the boolean of the second [[DefineOwnProperty]]."
  [init desc]
  (list 'let
        ['o new-obj]
        (list 'do
              (list 'js/define-own-property 'o (k "a") init)
              (list 'js/define-own-property 'o (k "a") desc))))


(def ^:private frozen-1
  "non-configurable, non-writable, enumerable data 1"
  (d :value 1 :writable false :enumerable true :configurable false))


(def ^:private define-cases
  [;; absent key: defaults fill in false/undefined
   [`(let [~'o ~new-obj]
       (do (js/define-own-property ~'o ~(k "a") ~(d :value 1))
           (let [~'s (js/get-own ~'o ~(k "a"))]
             [(get ~'s :value) (get ~'s :writable) (get ~'s :enumerable)
              (get ~'s :configurable)])))
    [1 false false false]]
   [`(let [~'o ~new-obj]
       (do (js/define-own-property ~'o ~(k "a") ~(d :get getter))
           (let [~'s (js/get-own ~'o ~(k "a"))]
             [(js/accessor-slot? ~'s) (get ~'s :set) (get ~'s :configurable)])))
    [true :js/undefined false]]
   ;; a non-extensible object refuses new keys, still updates old ones
   [`(let [~'o ~new-obj]
       (do (js/define-own-property ~'o
                                   ~(k "a")
                                   ~(d :value 1 :writable true :configurable true))
           (cell/set! ~'o (assoc (cell/get ~'o) :extensible? false))
           [(js/define-own-property ~'o ~(k "b") ~(d :value 1))
            (js/define-own-property ~'o ~(k "a") ~(d :value 2))]))
    [false true]]
   ;; a generic descriptor updates attributes only
   [`(let [~'o ~new-obj]
       (do (js/define-own-property
             ~'o
             ~(k "a")
             ~(d :value 7 :writable true :enumerable true :configurable true))
           (js/define-own-property ~'o ~(k "a") ~(d :enumerable false))
           (let [~'s (js/get-own ~'o ~(k "a"))]
             [(get ~'s :value) (get ~'s :enumerable) (get ~'s :writable)])))
    [7 false true]]
   ;; non-configurable
   [(redefine frozen-1 (d :configurable true)) false]
   [(redefine frozen-1 (d :enumerable false)) false]
   [(redefine frozen-1 (d :get getter)) false]
   [(redefine frozen-1 (d :writable true)) false]
   [(redefine frozen-1 (d :value 2)) false]
   [(redefine frozen-1 (d :value 1)) true]
   [(redefine frozen-1 (d :enumerable true :writable false)) true]
   ;; SameValue: NaN is itself, +0 is not -0
   [(redefine (d :value '(js/nan)) (d :value '(js/nan))) true]
   [(redefine (d :value '(js/nan) :writable false) (d :value '(js/nan))) true]
   [(redefine (d :value '(js/mknum 0) :writable false :configurable false)
              (d :value '(js/neg-zero)))
    false]
   [(redefine (d :value '(js/neg-zero) :writable false :configurable false)
              (d :value '(js/neg-zero)))
    true]
   ;; a non-configurable accessor keeps get/set
   [(redefine (d :get getter :set :js/undefined :configurable false)
              (d :get getter2))
    false]
   [(redefine (d :get getter :set :js/undefined :configurable false)
              (d :enumerable false))
    true]
   [(redefine (d :get getter :set :js/undefined :configurable false)
              (d :value 1))
    false]
   ;; configurable: kind switches keep enumerable and configurable only
   [`(let [~'o ~new-obj]
       (do (js/define-own-property
             ~'o
             ~(k "a")
             ~(d :value 1 :writable true :enumerable true :configurable true))
           (js/define-own-property ~'o ~(k "a") ~(d :get getter))
           (let [~'s (js/get-own ~'o ~(k "a"))]
             [(js/accessor-slot? ~'s) (get ~'s :enumerable) (get ~'s :configurable)
              (get ~'s :set)])))
    [true true true :js/undefined]]
   [`(let [~'o ~new-obj]
       (do (js/define-own-property
             ~'o
             ~(k "a")
             ~(d :get getter :set :js/undefined :enumerable true :configurable true))
           (js/define-own-property ~'o ~(k "a") ~(d :value 5))
           (let [~'s (js/get-own ~'o ~(k "a"))]
             [(js/accessor-slot? ~'s) (get ~'s :value) (get ~'s :writable)
              (get ~'s :enumerable)])))
    [false 5 false true]]
   ;; redefining keeps the key's position
   [`(let [~'o ~new-obj]
       (do (js/define-own-property ~'o ~(k "a") ~(d :value 1 :configurable true))
           (js/define-own-property ~'o ~(k "b") ~(d :value 1 :configurable true))
           (js/define-own-property ~'o ~(k "a") ~(d :get getter))
           (js/own-keys ~'o)))
    [(k "a") (k "b")]]])


(def ^:private to-desc-cases
  [[`(let [~'o ~new-obj
           ~'x (js/to-property-descriptor
                 (let [~'p ~new-obj]
                   (do (js/create-data-prop! ~'p ~(k "value") ~(k "v"))
                       (js/create-data-prop! ~'p ~(k "enumerable") ~(k "yes"))
                       ~'p)))]
       [(get ~'x :value) (get ~'x :enumerable) (js/has-own-field? ~'x :writable)
        (js/has-own-field? ~'x :configurable)])
    [(k "v") true false false]]
   [(throws `(js/to-property-descriptor
               (let [~'p ~new-obj]
                 (do (js/create-data-prop! ~'p ~(k "get") ~getter)
                     (js/create-data-prop! ~'p ~(k "value") 1)
                     ~'p))))
    (k "TypeError")]
   [(throws `(js/to-property-descriptor
               (let [~'p ~new-obj]
                 (do (js/create-data-prop! ~'p ~(k "set") ~(k "x")) ~'p))))
    (k "TypeError")]
   [(throws '(js/to-property-descriptor 1)) (k "TypeError")]
   ;; from-property-descriptor round trips
   [`(let [~'o ~new-obj
           ~'r (do (js/define-own-property
                     ~'o
                     ~(k "a")
                     ~(d :value 3 :writable true :enumerable true))
                   (js/from-property-descriptor (js/get-own ~'o ~(k "a"))))]
       [(js/own-keys ~'r) (js/get-prop ~'r ~(k "configurable"))
        (js/from-property-descriptor nil)])
    [[(k "value") (k "writable") (k "enumerable") (k "configurable")] false
     :js/undefined]]])


(def ^:private key-order-cases
  [[`(let [~'o ~new-obj]
       (do (js/create-data-prop! ~'o ~(k "b") 1)
           (js/create-data-prop! ~'o ~(k "2") 1)
           (js/create-data-prop! ~'o ~(k "a") 1)
           (js/create-data-prop! ~'o ~(k "1") 1)
           (js/create-data-prop! ~'o ~(k "01") 1)
           (js/own-keys ~'o)))
    [(k "1") (k "2") (k "b") (k "a") (k "01")]]
   ;; delete, then re-add goes last in its partition
   [`(let [~'o ~new-obj]
       (do (js/create-data-prop! ~'o ~(k "a") 1)
           (js/create-data-prop! ~'o ~(k "b") 1)
           (js/create-data-prop! ~'o ~(k "c") 1)
           (js/delete-prop ~'o ~(k "a"))
           (js/create-data-prop! ~'o ~(k "a") 1)
           (js/own-keys ~'o)))
    [(k "b") (k "c") (k "a")]]
   [`(let [~'o ~new-obj]
       (do (js/create-data-prop! ~'o ~(k "3") 1)
           (js/create-data-prop! ~'o ~(k "1") 1)
           (js/delete-prop ~'o ~(k "3"))
           (js/create-data-prop! ~'o ~(k "0") 1)
           (js/create-data-prop! ~'o ~(k "3") 1)
           (js/own-keys ~'o)))
    [(k "0") (k "1") (k "3")]]
   ;; om-remove on its own: absent is a no-op, order of the rest is kept
   [`(let [~'om (js/om-put (js/om-put (js/om-put (js/om-new) ~(k "x") 1) ~(k "y") 2)
                           ~(k "z")
                           3)]
       [(js/om-keys (js/om-remove ~'om ~(k "y")))
        (js/om-keys (js/om-remove ~'om ~(k "q")))
        (js/om-get (js/om-remove ~'om ~(k "y")) ~(k "y"))])
    [[(k "x") (k "z")] [(k "x") (k "y") (k "z")] nil]]
   ;; symbols come after strings and stay out of the string keys
   [`(let [~'o ~new-obj
           ~'s (js/make-symbol ~(k "s"))]
       (do (js/create-data-prop! ~'o ~'s 1)
           (js/create-data-prop! ~'o ~(k "z") 1)
           (js/create-data-prop! ~'o ~(k "5") 1)
           (let [~'ks (js/own-keys ~'o)]
             [(= (get ~'ks 0) ~(k "5")) (= (get ~'ks 1) ~(k "z"))
              (= (get ~'ks 2) ~'s) (data/count ~'ks)])))
    [true true true 3]]
   ;; a :js/units key is set, read, enumerated and deleted
   [`(let [~'o ~new-obj
           ~'u {:js/units [55357]}]
       (do (js/create-data-prop! ~'o ~'u 1)
           [(js/get-prop ~'o ~'u) (data/count (js/own-keys ~'o))
            (js/delete-prop ~'o ~'u) (data/count (js/own-keys ~'o))]))
    [1 1 true 0]]])


(def ^:private op-cases
  [;; [[Set]]: absent creates, own data updates, receiver is the target
   [`(let [~'p ~new-obj
           ~'o (js/make-object ~'p)]
       (do (js/put-value ~'o ~(k "a") 1)
           [(js/get-prop ~'o ~(k "a")) (js/get-prop ~'p ~(k "a"))
            (js/has-property ~'o ~(k "a")) (js/has-property ~'p ~(k "a"))]))
    [1 :js/undefined true false]]
   [`(let [~'p ~new-obj
           ~'o (js/make-object ~'p)]
       (do (js/define-own-property ~'p ~(k "a") ~(d :value 1 :writable true))
           (js/put-value ~'o ~(k "a") 2)
           [(js/get-prop ~'o ~(k "a")) (get (js/get-own ~'p ~(k "a")) :value)
            (get (js/get-own ~'o ~(k "a")) :enumerable)]))
    [2 1 true]]
   ;; a non-writable inherited slot blocks the write: false, then TypeError
   [`(let [~'p ~new-obj
           ~'o (js/make-object ~'p)]
       (do (js/define-own-property ~'p ~(k "a") ~(d :value 1 :writable false))
           [(js/set-prop ~'o ~(k "a") 2 ~'o) (js/has-own-field? (js/get-own ~'o ~(k "a")) :value)]))
    [false false]]
   [`(let [~'o ~new-obj]
       (do (js/define-own-property ~'o ~(k "a") ~(d :value 1 :writable false))
           ~(throws `(js/put-value ~'o ~(k "a") 2))))
    (k "TypeError")]
   ;; an accessor setter runs with the receiver; no setter fails
   [`(let [~'p ~new-obj
           ~'o (js/make-object ~'p)
           ~'st (js/make-function ~(k "s") 1 :strict :setter
                                  (fn [~'this ~'args]
                                    (js/create-data-prop! ~'this ~(k "seen") (js/arg ~'args 0))))]
       (do (js/define-own-property ~'p ~(k "a") ~(d :get getter :set 'st))
           (js/put-value ~'o ~(k "a") 9)
           [(js/get-prop ~'o ~(k "seen")) (js/has-own-field? (js/get-own ~'p ~(k "seen")) :value)]))
    [9 false]]
   [`(let [~'o ~new-obj]
       (do (js/define-own-property ~'o ~(k "a") ~(d :get getter :set :js/undefined))
           [(js/set-prop ~'o ~(k "a") 1 ~'o) ~(throws `(js/put-value ~'o ~(k "a") 1))]))
    [false (k "TypeError")]]
   ;; a non-extensible receiver refuses a new key; an accessor on the receiver
   ;; blocks a write through the prototype
   [`(let [~'o ~new-obj]
       (do (cell/set! ~'o (assoc (cell/get ~'o) :extensible? false))
           (js/set-prop ~'o ~(k "a") 1 ~'o)))
    false]
   ;; null and undefined bases
   [(throws '(js/put-value :js/undefined (js/to-property-key (js/mknum 1)) 1))
    (k "TypeError")]
   [(throws '(js/put-value :js/null {:js/str "a"} 1)) (k "TypeError")]
   ;; a primitive base cannot gain an own property
   [(throws '(js/put-value {:js/str "s"} {:js/str "a"} 1)) (k "TypeError")]
   ;; [[Has]] walks the chain, [[Delete]]
   [`(let [~'p ~new-obj
           ~'o (js/make-object ~'p)]
       (do (js/create-data-prop! ~'p ~(k "a") 1)
           [(js/has-property ~'o ~(k "a")) (js/in-op ~(k "a") ~'o)
            (js/has-property ~'o ~(k "zz")) (js/delete-prop ~'o ~(k "a"))
            (js/has-property ~'o ~(k "a"))]))
    [true true false true true]]
   [`(let [~'o ~new-obj]
       (do (js/define-own-property ~'o ~(k "a") ~(d :value 1 :configurable false))
           (js/define-own-property ~'o ~(k "b") ~(d :value 1 :configurable true))
           [(js/delete-prop ~'o ~(k "a")) (js/delete-prop ~'o ~(k "b"))
            (js/delete-prop ~'o ~(k "nope")) (js/has-property ~'o ~(k "a"))
            (js/has-property ~'o ~(k "b")) ~(throws `(js/delete-value ~'o ~(k "a")))
            (js/delete-value ~'o ~(k "b"))]))
    [false true true true false (k "TypeError") true]]
   [(throws '(js/delete-value :js/undefined {:js/str "a"})) (k "TypeError")]
   [(throws '(js/in-op {:js/str "a"} {:js/str "str"})) (k "TypeError")]])


(def ^:private arr '(js/make-array js.rt/obj-proto [1 2 3]))


(defn- len-of
  [a]
  `(js/get-prop ~a ~(k "length")))


(def ^:private array-cases
  [;; reads: index, length, holes
   [`(let [~'a ~arr]
       [(js/get-prop ~'a ~(k "1")) ~(len-of 'a) (js/get-prop ~'a ~(k "7"))
        (js/has-property ~'a ~(k "2")) (js/has-property ~'a ~(k "3"))
        (js/own-keys ~'a)])
    [2 {:n 3} :js/undefined true false
     [(k "0") (k "1") (k "2") (k "length")]]]
   ;; an index write past the end grows length and leaves holes
   [`(let [~'a ~arr]
       (do (js/put-value ~'a ~(k "6") 9)
           [~(len-of 'a) (js/has-property ~'a ~(k "4")) (js/get-prop ~'a ~(k "6"))
            (js/own-keys ~'a)]))
    [{:n 7} false 9 [(k "0") (k "1") (k "2") (k "6") (k "length")]]]
   [`(let [~'a ~arr]
       (do (js/put-value ~'a ~(k "1") 20) (js/get-prop ~'a ~(k "1"))))
    20]
   ;; a sparse gap past 2^20 is a RangeError and nothing changes
   [`(let [~'a ~arr]
       [~(throws `(js/put-value ~'a ~(k "4294967294") 1)) ~(len-of 'a)])
    [(k "RangeError") {:n 3}]]
   ;; 4294967295 is not an index: an ordinary key, length untouched
   [`(let [~'a ~arr]
       (do (js/put-value ~'a ~(k "4294967295") 1) ~(len-of 'a)))
    {:n 3}]
   ;; length writes truncate and extend
   [`(let [~'a ~arr]
       (do (js/put-value ~'a ~(k "length") (js/mknum 1))
           [~(len-of 'a) (js/has-property ~'a ~(k "1"))
            (do (js/put-value ~'a ~(k "length") (js/mknum 4)) ~(len-of 'a))
            (js/has-property ~'a ~(k "3"))]))
    [{:n 1} false {:n 4} false]]
   [(throws `(js/put-value ~arr ~(k "length") (js/mknum -1))) (k "RangeError")]
   [(throws `(js/put-value ~arr ~(k "length") (js/mknum 1.5))) (k "RangeError")]
   ;; sealed array: a kept element stops the shrink one above it
   [`(let [~'a (js/make-array js.rt/obj-proto [1 :js/hole :js/hole])]
       (do (cell/set! ~'a (assoc (assoc (cell/get ~'a) :items-mode :sealed)
                                 :extensible? false))
           [(js/define-own-property ~'a ~(k "length") ~(d :value '(js/mknum 0)))
            ~(len-of 'a)]))
    [false {:n 1}]]
   [`(let [~'a ~arr]
       (do (cell/set! ~'a (assoc (assoc (cell/get ~'a) :items-mode :sealed)
                                 :extensible? false))
           [(js/define-own-property ~'a ~(k "length") ~(d :value '(js/mknum 1)))
            ~(len-of 'a) (js/delete-prop ~'a ~(k "0"))
            (js/define-own-property ~'a ~(k "5") ~(d :value 1))
            (js/put-value ~'a ~(k "0") 7) (js/get-prop ~'a ~(k "0"))]))
    [false {:n 3} false false 7 7]]
   ;; frozen array: writes fail
   [`(let [~'a ~arr]
       (do (cell/set! ~'a (assoc (assoc (cell/get ~'a) :items-mode :frozen)
                                 :extensible? false))
           [(js/set-prop ~'a ~(k "0") 7 ~'a) (js/get-prop ~'a ~(k "0"))]))
    [false 1]]
   ;; non-writable length: no growth, no truncation, same value is fine
   [`(let [~'a ~arr]
       (do (js/define-own-property ~'a ~(k "length") ~(d :writable false))
           [(js/set-prop ~'a ~(k "5") 1 ~'a) (js/set-prop ~'a ~(k "length") (js/mknum 1) ~'a)
            (js/define-own-property ~'a ~(k "length") ~(d :value '(js/mknum 3)))
            (js/define-own-property ~'a ~(k "length") ~(d :writable true))
            ~(len-of 'a) (js/set-prop ~'a ~(k "0") 5 ~'a)]))
    [false false true false {:n 3} true]]
   ;; delete leaves a hole and keeps the length
   [`(let [~'a ~arr]
       (do (js/delete-prop ~'a ~(k "2"))
           [~(len-of 'a) (js/has-property ~'a ~(k "2")) (js/own-keys ~'a)]))
    [{:n 3} false [(k "0") (k "1") (k "length")]]]
   ;; ordinary keys on an array go through the key map
   [`(let [~'a ~arr]
       (do (js/put-value ~'a ~(k "x") 1)
           [(js/get-prop ~'a ~(k "x")) (js/own-keys ~'a)]))
    [1 [(k "0") (k "1") (k "2") (k "length") (k "x")]]]
   ;; non-default descriptors on an index are the recorded departure
   [`(let [~'a ~arr]
       [(js/define-own-property ~'a ~(k "4") ~(d :value 1))
        (js/define-own-property ~'a ~(k "0") ~(d :get getter))])
    [false false]]])


(def ^:private symbol-cases
  [;; fresh identity on every call
   [`(let [~'sym (js/get-prop js.rt/global ~(k "Symbol"))
           ~'a (js/call ~'sym :js/undefined [~(k "x")])
           ~'b (js/call ~'sym :js/undefined [~(k "x")])]
       [(js/strict-eq ~'a ~'a) (js/strict-eq ~'a ~'b) (js/symbol? ~'a)])
    [true false true]]
   [(throws `(js/construct (js/get-prop js.rt/global ~(k "Symbol")) []))
    (k "TypeError")]
   ;; toString, valueOf, description through get-value on the primitive
   [`(let [~'a (js/call (js/get-prop js.rt/global ~(k "Symbol")) :js/undefined [~(k "d")])
           ~'n (js/call (js/get-prop js.rt/global ~(k "Symbol")) :js/undefined [])]
       [(js/call (js/get-value ~'a ~(k "toString")) ~'a [])
        (js/strict-eq (js/call (js/get-value ~'a ~(k "valueOf")) ~'a []) ~'a)
        (js/get-value ~'a ~(k "description")) (js/get-value ~'n ~(k "description"))
        (js/call (js/get-value ~'n ~(k "toString")) ~'n [])
        (js/call (js/get-prop js.rt/global ~(k "String")) :js/undefined (js/vconj [] ~'a))
        ~(throws `(js/to-string ~'a))])
    [(k "Symbol(d)") true (k "d") :js/undefined (k "Symbol()") (k "Symbol(d)")
     (k "TypeError")]]
   [(throws `(js/call (js/get-prop js.rt/symbol-proto ~(k "toString")) ~(k "s") []))
    (k "TypeError")]
   ;; a symbol key through set, get, has, delete
   [`(let [~'o ~new-obj
           ~'s (js/make-symbol ~(k "k"))]
       (do (js/put-value ~'o ~'s 1)
           [(js/get-prop ~'o ~'s) (js/has-property ~'o ~'s)
            (js/has-property ~'o ~(k "k")) (data/count (js/own-keys ~'o))
            (js/delete-prop ~'o ~'s) (js/has-property ~'o ~'s)
            (data/count (js/own-keys ~'o))]))
    [1 true false 1 true false 0]]])


(deftest define-own-property-matrix-test (check define-cases))


(deftest property-descriptor-conversion-test (check to-desc-cases))


(deftest own-key-order-test (check key-order-cases))


(deftest set-has-delete-test (check op-cases))


(deftest array-exotic-test (check array-cases))


(deftest symbol-core-test (check symbol-cases))
