(ns yang.typescript.antlr.class-prelude-test
  "T3 step S2: constructor kinds and `new.target` dispatch in the `js`
   prelude. `make-function-nt`'s `nt?` flag, `call` refusing class kinds,
   `construct-with-nt` allocating from `new.target.prototype`, derived
   constructors, bound functions and `is-constructor?`. Tiny UAST programs
   run on every VM through the parity harness."
  (:require
    [clojure.test :refer [deftest is testing]]
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


(def ^:private nt-recorder
  "A constructor body that records its new.target on `this`."
  '(fn [this args nt]
     (do (js/define-own! this {:js/str "nt"} (js/slot nt true true true))
         :js/undefined)))


(defn- check
  "Run `cases` ([form expected]) as one vector program on every VM."
  [cases]
  (let [form (parity/program-form
               (reduce (fn [acc [f _]]
                         (list 'js/vconj
                               acc
                               (unqualify (walk/postwalk-replace
                                            {'NT-REC nt-recorder}
                                            f))))
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


(defn- throws
  [expr]
  (list 'js/try (list 'fn [] expr) '(fn [e] (js/get-prop e {:js/str "name"}))))


(def ^:private kind-cases
  [;; class kinds refuse an ordinary call; construct reaches the code
   [(throws '(js/call (js/make-function-nt {:js/str "A"} 0 :strict :class
                                           (fn [this args nt] this)
                                           true)
                      :js/undefined
                      []))
    (k "TypeError")]
   [(throws '(js/call (js/make-function-nt {:js/str "B"} 0 :strict :derived
                                           (fn [this args nt] this)
                                           true)
                      :js/undefined
                      []))
    (k "TypeError")]
   ;; constructibility by :kind
   ['(let [mk (fn [kind]
                (js/is-constructor?
                  (js/make-function-nt {:js/str "f"} 0 :strict kind
                                       (fn [this args] this)
                                       false)))]
       [(mk :normal) (mk :class) (mk :derived) (mk :method) (mk :arrow)
        (mk :getter) (mk :accessor) (mk :builtin)])
    [true true true false false false false false]]
   ;; the five-argument make-function is the nt?-false shape
   ['(js/call (js/make-function {:js/str "f"} 1 :strict :normal
                                (fn [this args] (js/arg args 0)))
              :js/undefined
              (js/vconj [] {:n 5}))
    {:n 5}]])


(def ^:private new-target-cases
  [;; a plain call passes undefined as new.target
   ['(js/call (js/make-function-nt {:js/str "f"} 0 :strict :normal
                                   (fn [this args nt] nt)
                                   true)
              :js/undefined
              [])
    :js/undefined]
   ;; construct passes the constructor itself
   ['(let [f (js/make-function-nt {:js/str "F"} 0 :strict :class
                                  (fn [this args nt] nt)
                                  true)]
       (= (js/construct f []) f))
    true]
   ['(let [f (js/make-function-nt {:js/str "F"} 0 :strict :class
                                  NT-REC
                                  true)
           o (js/construct f [])]
       (= (js/get-prop o {:js/str "nt"}) f))
    true]
   ;; construct-with-nt allocates from nt.prototype
   ['(let [f (js/make-function-nt {:js/str "F"} 0 :strict :class
                                  NT-REC
                                  true)
           g (js/make-function-nt {:js/str "G"} 0 :strict :class
                                  (fn [this args nt] this)
                                  true)
           proto (js/make-object :js/null)]
       (do (js/define-own! g {:js/str "prototype"} (js/slot proto false false false))
           (let [o (js/construct-with-nt f [] g)]
             [(= (get (cell/get o) :proto) proto)
              (= (js/get-prop o {:js/str "nt"}) g)])))
    [true true]]
   ;; a non-object nt.prototype falls back to Object.prototype
   ['(let [f (js/make-function-nt {:js/str "F"} 0 :strict :class
                                  (fn [this args nt] this)
                                  true)
           g (js/make-function-nt {:js/str "G"} 0 :strict :class
                                  (fn [this args nt] this)
                                  true)
           o (js/construct-with-nt f [] g)]
       (= (get (cell/get o) :proto) js.rt/obj-proto))
    true]
   ;; a derived constructor allocates nothing: it gets undefined for this and
   ;; returns whatever its body returns
   ['(let [r (js/make-object :js/null)
           d (js/make-function-nt {:js/str "D"} 0 :strict :derived
                                  (fn [this args nt]
                                    (do (js/define-own! r {:js/str "t"}
                                                        (js/slot this true true true))
                                        (js/define-own! r {:js/str "nt"}
                                                        (js/slot nt true true true))
                                        r))
                                  true)
           o (js/construct d [])]
       [(= o r)
        (= (js/get-prop o {:js/str "t"}) :js/undefined)
        (= (js/get-prop o {:js/str "nt"}) d)])
    [true true true]]
   ;; an ordinary function with nt? false is unchanged by construct-with-nt
   ['(let [f (js/make-function {:js/str "F"} 0 :strict :normal
                               (fn [this args] this))
           o (js/construct f [])]
       (= (get (cell/get o) :proto) (js/get-prop f {:js/str "prototype"})))
    true]
   [(throws '(js/construct-with-nt (js/make-function {:js/str "m"} 0 :strict :method
                                                     (fn [this args] this))
                                   []
                                   :js/undefined))
    (k "TypeError")]])


(def ^:private bound-cases
  [;; construct on a bound function passes the target as new.target
   ['(let [f (js/make-function-nt {:js/str "F"} 0 :strict :normal
                                  NT-REC
                                  true)
           b (js/call (js/get-prop f {:js/str "bind"})
                      f
                      (js/vconj [] :js/undefined))
           o (js/construct b [])]
       (= (js/get-prop o {:js/str "nt"}) f))
    true]
   ;; with a different new.target, that one flows through
   ['(let [f (js/make-function-nt {:js/str "F"} 0 :strict :normal
                                  NT-REC
                                  true)
           g (js/make-function-nt {:js/str "G"} 0 :strict :normal
                                  (fn [this args nt] this)
                                  true)
           b (js/call (js/get-prop f {:js/str "bind"})
                      f
                      (js/vconj [] :js/undefined))
           o (js/construct-with-nt b [] g)]
       (= (js/get-prop o {:js/str "nt"}) g))
    true]])


;; ------------------------------------------------------------------- S3

(def ^:private heritage-cases
  [['(let [h (js/class-heritage :js/none)]
       [(= (get h :proto-parent) js.rt/obj-proto)
        (= (get h :ctor-parent) js.rt/fn-proto)])
    [true true]]
   ['(let [h (js/class-heritage :js/null)]
       [(= (get h :proto-parent) :js/null)
        (= (get h :ctor-parent) js.rt/fn-proto)])
    [true true]]
   [(throws '(js/class-heritage {:n 1})) (k "TypeError")]
   [(throws '(js/class-heritage (js/make-function-nt {:js/str "a"} 0 :lexical :arrow
                                                     (fn [t a] t)
                                                     false)))
    (k "TypeError")]
   ['(let [p (js/make-function {:js/str "P"} 0 :strict :normal (fn [t a] t))
           h (js/class-heritage p)]
       [(= (get h :ctor-parent) p)
        (= (get h :proto-parent) (js/get-prop p {:js/str "prototype"}))])
    [true true]]
   [(throws '(let [p (js/make-function {:js/str "P"} 0 :strict :normal (fn [t a] t))]
               (do (js/define-own! p
                                   {:js/str "prototype"}
                                   (js/slot {:n 1} true false false))
                   (js/class-heritage p))))
    (k "TypeError")]])


(def ^:private descriptor-cases
  [;; own keys, prototype and constructor descriptors
   ['(let [proto (js/make-object js.rt/obj-proto)
           f (js/make-class {:js/str "A"} 2 false js.rt/fn-proto proto
                            (fn [this args nt] this))
           sp (js/get-own f {:js/str "prototype"})
           sc (js/get-own proto {:js/str "constructor"})]
       [(js/own-string-keys f)
        [(get sp :writable) (get sp :enumerable) (get sp :configurable)]
        [(get sc :writable) (get sc :enumerable) (get sc :configurable)]
        (= (get sc :value) f)
        (js/get-prop f {:js/str "name"})
        (js/get-prop f {:js/str "length"})
        (= (get (cell/get f) :kind) :class)])
    [[(k "length") (k "name") (k "prototype")]
     [false false false]
     [true false true]
     true
     (k "A")
     {:n 2}
     true]]
   ;; the class prototype is the constructor-parent for a derived class
   ['(let [p (js/make-class {:js/str "P"} 0 false js.rt/fn-proto
                            (js/make-object js.rt/obj-proto)
                            (fn [this args nt] this))
           d (js/make-class {:js/str "D"} 0 true p
                            (js/make-object (js/get-prop p {:js/str "prototype"}))
                            (fn [this args nt] this))]
       [(= (get (cell/get d) :proto) p)
        (= (get (cell/get d) :kind) :derived)])
    [true true]]
   ;; methods are non-enumerable and writable; accessor halves merge
   ['(let [proto (js/make-object js.rt/obj-proto)
           m (js/make-function-nt {:js/str "m"} 0 :strict :method (fn [t a] t) false)
           g (js/make-function-nt {:js/str "get x"} 0 :strict :method (fn [t a] 1) false)
           s (js/make-function-nt {:js/str "set x"} 1 :strict :method (fn [t a] t) false)]
       (do (js/def-class-method! proto {:js/str "m"} m)
           (js/def-class-accessor! proto {:js/str "x"} :get g)
           (js/def-class-accessor! proto {:js/str "x"} :set s)
           (let [sm (js/get-own proto {:js/str "m"})
                 sx (js/get-own proto {:js/str "x"})]
             [(get sm :enumerable) (get sm :writable) (get sm :configurable)
              (= (get sx :get) g) (= (get sx :set) s)
              (get sx :enumerable) (get sx :configurable)])))
    [false true true true true false true]]])


(def ^:private field-cases
  [;; define semantics: a prototype setter is shadowed, not invoked
   ['(let [hit (cell/new false)
           proto (js/make-object js.rt/obj-proto)
           f (js/make-class {:js/str "A"} 0 false js.rt/fn-proto proto
                            (fn [this args nt] this))]
       (do (js/def-class-accessor!
             proto
             {:js/str "x"}
             :set
             (js/make-function-nt {:js/str "set x"} 1 :strict :method
                                  (fn [t a] (do (cell/set! hit true) :js/undefined))
                                  false))
           (js/add-field! f {:js/str "x"} (fn [this] {:n 7}))
           (let [o (js/construct f [])
                 sl (js/get-own o {:js/str "x"})]
             [(cell/get hit) (get sl :value) (get sl :writable)
              (get sl :enumerable) (get sl :configurable)])))
    [false {:n 7} true true true]]
   ;; fields run in definition order, each seeing the earlier ones, with
   ;; a missing initializer defining undefined
   ['(let [f (js/make-class {:js/str "A"} 0 false js.rt/fn-proto
                            (js/make-object js.rt/obj-proto)
                            (fn [this args nt] this))]
       (do (js/add-field! f {:js/str "a"} (fn [this] {:n 1}))
           (js/add-field! f {:js/str "b"}
                          (fn [this] (js/add (js/get-prop this {:js/str "a"}) {:n 1})))
           (js/add-field! f {:js/str "c"} :js/undefined)
           (let [o (js/construct f [])]
             [(js/own-string-keys o)
              (js/get-prop o {:js/str "b"})
              (js/has-property o {:js/str "c"})])))
    [[(k "a") (k "b") (k "c")] {:n 2} true]]
   ;; static initializers run in order with this = the class
   ['(let [lg (cell/new [])
           f (js/make-class {:js/str "A"} 0 false js.rt/fn-proto
                            (js/make-object js.rt/obj-proto)
                            (fn [this args nt] this))]
       (do (js/static-init!
             f
             (js/vconj
               (js/vconj [] (fn [this] (cell/set! lg (conj (cell/get lg) (= this f)))))
               (fn [this] (cell/set! lg (conj (cell/get lg) {:js/str "two"})))))
           (cell/get lg)))
    [true (k "two")]]])


(def ^:private private-cases
  [;; field read, write, has, and the foreign-object errors
   ['(let [pn (js/private-name {:js/str "#x"})
           o (js/make-object js.rt/obj-proto)
           other (js/make-object js.rt/obj-proto)]
       (do (js/priv-define! o pn (assoc (assoc {} :kind :field) :value {:n 1}))
           (js/priv-set o pn {:n 5})
           [(js/priv-get o pn) (js/priv-has o pn) (js/priv-has other pn)]))
    [{:n 5} true false]]
   [(throws '(let [pn (js/private-name {:js/str "#x"})]
               (js/priv-get (js/make-object js.rt/obj-proto) pn)))
    (k "TypeError")]
   [(throws '(let [pn (js/private-name {:js/str "#x"})]
               (js/priv-set (js/make-object js.rt/obj-proto) pn {:n 1})))
    (k "TypeError")]
   [(throws '(js/priv-has {:n 1} (js/private-name {:js/str "#x"})))
    (k "TypeError")]
   [(throws '(let [pn (js/private-name {:js/str "#x"})
                   o (js/make-object js.rt/obj-proto)
                   e (assoc (assoc {} :kind :field) :value {:n 1})]
               (do (js/priv-define! o pn e) (js/priv-define! o pn e))))
    (k "TypeError")]
   ;; private state is invisible to reflection
   ['(let [pn (js/private-name {:js/str "#x"})
           o (js/make-object js.rt/obj-proto)]
       (do (js/priv-define! o pn (assoc (assoc {} :kind :field) :value {:n 1}))
           [(js/own-string-keys o) (= (data/count (js/own-symbol-keys o)) 0)
            (= (data/count (js/own-keys-any o)) 0)]))
    [[] true true]]
   ;; two names with one description are distinct
   ['(let [a (js/private-name {:js/str "#x"})
           b (js/private-name {:js/str "#x"})
           o (js/make-object js.rt/obj-proto)]
       (do (js/priv-define! o a (assoc (assoc {} :kind :field) :value {:n 1}))
           [(js/priv-has o a) (js/priv-has o b)]))
    [true false]]
   ;; methods and accessors: branded before fields, the field initializer
   ;; calling the private method; a setter-less write is a TypeError
   ['(let [m (js/private-name {:js/str "#m"})
           g (js/private-name {:js/str "#g"})
           f (js/make-class {:js/str "A"} 0 false js.rt/fn-proto
                            (js/make-object js.rt/obj-proto)
                            (fn [this args nt] this))
           mf (js/make-function-nt {:js/str "#m"} 0 :strict :method (fn [t a] {:n 11}) false)
           gf (js/make-function-nt {:js/str "get #g"} 0 :strict :method (fn [t a] {:n 22}) false)]
       (do (js/add-priv-method! f m :method mf)
           (js/add-priv-method! f g :get gf)
           (js/add-field! f {:js/str "v"}
                          (fn [this] (js/call (js/priv-get this m) this [])))
           (let [o (js/construct f [])]
             [(js/get-prop o {:js/str "v"})
              (= (js/priv-get o m) mf)
              (js/priv-get o g)
              (js/own-string-keys o)])))
    [{:n 11} true {:n 22} [(k "v")]]]
   [(throws '(let [g (js/private-name {:js/str "#g"})
                   f (js/make-class {:js/str "A"} 0 false js.rt/fn-proto
                                    (js/make-object js.rt/obj-proto)
                                    (fn [this args nt] this))]
               (do (js/add-priv-method! f g :get
                                        (js/make-function-nt {:js/str "get #g"} 0 :strict
                                                             :method (fn [t a] 1) false))
                   (js/priv-set (js/construct f []) g {:n 1}))))
    (k "TypeError")]
   [(throws '(let [m (js/private-name {:js/str "#m"})
                   f (js/make-class {:js/str "A"} 0 false js.rt/fn-proto
                                    (js/make-object js.rt/obj-proto)
                                    (fn [this args nt] this))]
               (do (js/add-priv-method! f m :method
                                        (js/make-function-nt {:js/str "#m"} 0 :strict
                                                             :method (fn [t a] 1) false))
                   (js/priv-set (js/construct f []) m {:n 1}))))
    (k "TypeError")]
   ;; accessor halves merge; static private methods live on the class
   ['(let [g (js/private-name {:js/str "#g"})
           s (js/private-name {:js/str "#s"})
           f (js/make-class {:js/str "A"} 0 false js.rt/fn-proto
                            (js/make-object js.rt/obj-proto)
                            (fn [this args nt] this))
           cell-v (cell/new {:n 0})
           getter (js/make-function-nt {:js/str "get"} 0 :strict :method
                                       (fn [t a] (cell/get cell-v)) false)
           setter (js/make-function-nt {:js/str "set"} 1 :strict :method
                                       (fn [t a] (cell/set! cell-v (js/arg a 0))) false)]
       (do (js/add-priv-method! f g :get getter)
           (js/add-priv-method! f g :set setter)
           (js/def-static-private! f s :method getter)
           (let [o (js/construct f [])]
             (do (js/priv-set o g {:n 9})
                 [(js/priv-get o g) (= (js/priv-get f s) getter)
                  (js/priv-has o s)]))))
    [{:n 9} true false]]])


(def ^:private super-cases
  [;; the F7 ordering: parent first, then check, bind, brand, fields, return
   ['(let [lg (cell/new [])
           push (fn [s] (cell/set! lg (conj (cell/get lg) s)))
           dcell (cell/new :js/undefined)
           p (js/make-class {:js/str "P"} 0 false js.rt/fn-proto
                            (js/make-object js.rt/obj-proto)
                            (fn [this args nt] (do (push {:js/str "P"}) this)))
           pn (js/private-name {:js/str "#m"})
           d (js/make-class {:js/str "D"} 0 true p
                            (js/make-object (js/get-prop p {:js/str "prototype"}))
                            (fn [this args nt]
                              (let [thisc (cell/new :js/tdz)]
                                (do (push {:js/str "enter"})
                                    (js/super-call (cell/get dcell) nt thisc args)
                                    (push {:js/str "after"})
                                    (js/derived-result thisc :js/undefined)))))]
       (do (cell/set! dcell d)
           (js/add-priv-method!
             d pn :method
             (js/make-function-nt {:js/str "#m"} 0 :strict :method (fn [t a] 1) false))
           (js/add-field! d {:js/str "f"}
                          (fn [this]
                            (do (push (if (js/priv-has this pn)
                                        {:js/str "branded"}
                                        {:js/str "unbranded"}))
                                {:n 1})))
           (let [o (js/construct d [])]
             [(cell/get lg)
              (= (get (cell/get o) :proto) (js/get-prop d {:js/str "prototype"}))
              (js/get-prop o {:js/str "f"})])))
    [[(k "enter") (k "P") (k "branded") (k "after")] true {:n 1}]]
   ;; duplicate super(): the parent runs again, then ReferenceError
   ['(let [lg (cell/new [])
           dcell (cell/new :js/undefined)
           p (js/make-class {:js/str "P"} 0 false js.rt/fn-proto
                            (js/make-object js.rt/obj-proto)
                            (fn [this args nt]
                              (do (cell/set! lg (conj (cell/get lg) {:js/str "P"})) this)))
           d (js/make-class {:js/str "D"} 0 true p
                            (js/make-object (js/get-prop p {:js/str "prototype"}))
                            (fn [this args nt]
                              (let [thisc (cell/new :js/tdz)]
                                (do (js/super-call (cell/get dcell) nt thisc args)
                                    (js/super-call (cell/get dcell) nt thisc args)
                                    (js/derived-result thisc :js/undefined)))))]
       (do (cell/set! dcell d)
           [(js/try (fn [] (js/construct d []))
                    (fn [e] (js/get-prop e {:js/str "name"})))
            (= (data/count (cell/get lg)) 2)]))
    [(k "ReferenceError") true]]
   ;; a throwing parent leaves the this-cell uninitialized
   ['(let [thisc (cell/new :js/tdz)
           dcell (cell/new :js/undefined)
           p (js/make-class {:js/str "P"} 0 false js.rt/fn-proto
                            (js/make-object js.rt/obj-proto)
                            (fn [this args nt] (js/throw-type-error {:js/str "boom"})))
           d (js/make-class {:js/str "D"} 0 true p
                            (js/make-object (js/get-prop p {:js/str "prototype"}))
                            (fn [this args nt] this))]
       [(js/try (fn [] (js/super-call d d thisc []))
                (fn [e] (js/get-prop e {:js/str "name"})))
        (= (cell/get thisc) :js/tdz)])
    [(k "TypeError") true]]
   ;; a non-constructor parent
   [(throws '(let [d (js/make-class {:js/str "D"} 0 true js.rt/fn-proto
                                    (js/make-object js.rt/obj-proto)
                                    (fn [this args nt] this))]
               (js/super-call d d (cell/new :js/tdz) [])))
    (k "TypeError")]
   ;; derived completion
   ['(let [o (js/make-object js.rt/obj-proto)
           bound (cell/new o)
           tdz (cell/new :js/tdz)]
       [(= (js/derived-result tdz o) o)
        (= (js/derived-result bound :js/undefined) o)])
    [true true]]
   [(throws '(js/derived-result (cell/new :js/tdz) :js/undefined)) (k "ReferenceError")]
   [(throws '(js/derived-result (cell/new (js/make-object js.rt/obj-proto)) {:n 1}))
    (k "TypeError")]
   ;; super property reads use the receiver; writes define on the receiver
   ['(let [parent (js/make-object js.rt/obj-proto)
           home (js/make-object parent)
           r (js/make-object js.rt/obj-proto)]
       (do (js/def-class-accessor!
             parent
             {:js/str "who"}
             :get
             (js/make-function-nt {:js/str "get who"} 0 :strict :method
                                  (fn [t a] (js/get-prop t {:js/str "tag"}))
                                  false))
           (js/define-own! r {:js/str "tag"} (js/slot {:n 3} true true true))
           (js/super-set home {:js/str "w"} {:n 4} r)
           [(js/super-get home {:js/str "who"} r)
            (js/get-prop r {:js/str "w"})
            (nil? (js/own-slot home {:js/str "w"}))]))
    [{:n 3} {:n 4} true]]
   [(throws '(js/super-get (js/make-object :js/null) {:js/str "x"} :js/undefined))
    (k "TypeError")]])


(deftest constructor-kinds-test (check kind-cases))


(deftest new-target-dispatch-test (check new-target-cases))


(deftest bound-new-target-test (check bound-cases))


(deftest class-heritage-test (check heritage-cases))


(deftest class-descriptor-test (check descriptor-cases))


(deftest class-field-test (check field-cases))


(deftest class-private-test (check private-cases))


(deftest class-super-test (check super-cases))
