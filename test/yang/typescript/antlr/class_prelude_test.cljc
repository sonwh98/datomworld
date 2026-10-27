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


(deftest constructor-kinds-test (check kind-cases))


(deftest new-target-dispatch-test (check new-target-cases))


(deftest bound-new-target-test (check bound-cases))
