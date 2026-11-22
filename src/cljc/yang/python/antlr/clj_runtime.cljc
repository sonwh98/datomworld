(ns yang.python.antlr.clj-runtime
  "Python syntax adapters over the persistent Clojure-family runtime."
  (:require [yang.clojure.core-module :as library]
            [yang.clojure.prelude :as clj]
            [yang.core.prelude :as core]
            [yang.core.prelude-admission :as admission]
            [yang.core.uast :as u]
            [yang.rt.int :as int-rt]))


(def module-name 'clj.py)

(def ^:private numeric
  '[[clj.py/_error
     (fn [reason evidence] (clj/raise (assoc evidence :clj.py/error reason)))]
    [clj.py/_num-kind
     (fn [x] (if (integer/integer? x) :int (if (data/float64? x) :float :other)))]
    [clj.py/_int-result (fn [x] (yrt.int/check-limit (clj/rt-ctx) x))]
    [clj.py/_float
     (fn [x] (if (integer/integer? x)
               (clj.py/_int-result (integer/to-float-boxed x))
               x))]
    [clj.py/_math-error
     (fn [tag a b] (clj/perform-policy tag (assoc {} :a a :b b)))]
    [clj.py/_div-nonzero
     (fn [operation a b]
       (if (= 0 (integer/compare b 0))
         (clj.py/_math-error :math/div-zero a b)
         (clj.py/_int-result (operation a b))))]
    [clj.py/_float-result
     (fn [x a b]
       (if (= x :yin.vm.float/div-zero) (clj.py/_math-error :math/div-zero a b)
         (if (= x :yin.vm.float/underflow) (clj.py/_math-error :math/underflow a b)
           x)))]
    [clj.py/_arithmetic
     (fn [iop fop a b]
       (let [ak (clj.py/_num-kind a) bk (clj.py/_num-kind b)]
         (if (if (= ak :other) true (= bk :other))
           (clj.py/_error :type-error (assoc {} :a a :b b))
           (if (if (= ak :int) (= bk :int) false)
             (clj.py/_int-result (iop a b))
             (clj.py/_float-result (fop (clj.py/_float a) (clj.py/_float b)) a b)))))]
    [clj.py/add
     (fn [a b]
       (if (if (data/string? a) (data/string? b) false)
         (data/str-concat a b)
         (clj.py/_arithmetic integer/add float/add a b)))]
    [clj.py/sub (fn [a b] (clj.py/_arithmetic integer/sub float/sub a b))]
    [clj.py/mul (fn [a b] (clj.py/_arithmetic integer/mul float/mul a b))]
    [clj.py/truediv
     (fn [a b]
       (clj.py/_arithmetic
         (fn [a b] (clj.py/_div-nonzero integer/true-div-boxed a b))
         float/div-checked a b))]
    [clj.py/_divmod
     (fn [a b i]
       (data/nth (clj.py/_arithmetic
                   (fn [a b] (clj.py/_div-nonzero integer/floor-div-mod a b))
                   float/floordiv-mod a b)
                 i))]
    [clj.py/floordiv (fn [a b] (clj.py/_divmod a b 0))]
    [clj.py/mod (fn [a b] (clj.py/_divmod a b 1))]
    [clj.py/_float-pow
     (fn [a b]
       (if (< (integer/compare b 0) 0)
         (if (float/zero? a)
           (clj.py/_math-error :math/div-zero a b)
           (clj.py/_float-result
             (float/neg-pow a (clj.py/_int-result (integer/neg b)) :half) a b))
         (float/fpow-half a b)))]
    [clj.py/pow
     (fn [a b]
       (let [ak (clj.py/_num-kind a) bk (clj.py/_num-kind b)]
         (if (if (= ak :other) true (= bk :other))
           (clj.py/_error :type-error (assoc {} :a a :b b))
           (if (= bk :float)
             (if (float/finite? b)
               (let [n (float/floor-int b)]
                 (if (= n :yin.vm.float/out-of-range)
                   (clj.py/_math-error :math/overflow a b)
                   (if (= 0 (integer/compare-float-boxed n b))
                     (clj.py/_float-pow (clj.py/_float a) n)
                     (clj.py/_math-error :math/pow-fractional a b))))
               (clj.py/_math-error :math/pow-infinite a b))
             (if (if (= ak :int) (<= 0 (integer/compare b 0)) false)
               (clj.py/_int-result (integer/pow a b))
               (clj.py/_float-pow (clj.py/_float a) b))))))]
    [clj.py/neg
     (fn [x]
       (if (integer/integer? x) (clj.py/_int-result (integer/neg x))
         (if (data/float64? x) (float/neg x)
           (clj.py/_error :type-error (assoc {} :value x)))))]
    [clj.py/pos
     (fn [x] (if (= :other (clj.py/_num-kind x))
               (clj.py/_error :type-error (assoc {} :value x))
               x))]
    [clj.py/eq (fn [a b] (data/equiv a b))]
    [clj.py/ne (fn [a b] (not (data/equiv a b)))]
    [clj.py/_compare
     (fn [a b]
       (let [ak (clj.py/_num-kind a) bk (clj.py/_num-kind b)]
         (if (= ak :int)
           (if (= bk :int) (integer/compare a b)
             (if (= bk :float) (integer/compare-float-boxed a b)
               (clj.py/_error :type-error (assoc {} :a a :b b))))
           (if (= ak :float)
             (if (= bk :float) (float/compare a b)
               (if (= bk :int)
                 (let [order (integer/compare-float-boxed b a)]
                   (if (integer/integer? order) (- 0 order) order))
                 (clj.py/_error :type-error (assoc {} :a a :b b))))
             (if (if (data/string? a) (data/string? b) false)
               (data/str-compare a b)
               (clj.py/_error :type-error (assoc {} :a a :b b)))))))]
    [clj.py/lt (fn [a b] (= -1 (clj.py/_compare a b)))]
    [clj.py/le (fn [a b] (let [c (clj.py/_compare a b)] (if (= c -1) true (= c 0))))]
    [clj.py/gt (fn [a b] (= 1 (clj.py/_compare a b)))]
    [clj.py/ge (fn [a b] (let [c (clj.py/_compare a b)] (if (= c 1) true (= c 0))))]
    [clj.py/lnot (fn [x] (not x))]
    [clj.py/truthy (fn [x] (if x true false))]
    [clj.py/int-lit (fn [hex] (clj.py/_int-result (integer/parse hex 16)))]])


(def ^:private collections
  '[[clj.py/vconj (fn [xs x] (conj xs x))]
    [clj.py/list (fn [xs] xs)]
    [clj.py/tuple (fn [xs] xs)]
    [clj.py/_build
     (fn [acc xs i n step]
       (if (< i n)
         (clj.py/_build (step acc (data/nth xs i)) xs (+ i 1) n step)
         acc))]
    [clj.py/set-from
     (fn [xs] (clj.py/_build #{} xs 0 (data/count xs)
                             (fn [acc x] (conj acc (clj/collection-key x)))))]
    [clj.py/map-from
     (fn [xs] (clj.py/_build {} xs 0 (data/count xs)
                             (fn [acc pair]
                               (assoc acc (clj/collection-key (data/nth pair 0))
                                      (data/nth pair 1)))))]
    [clj.py/get-item
     (fn [xs k]
       (if (data/vector? xs)
         (if (integer/integer? k)
           (let [i (if (< (integer/compare k 0) 0) (integer/add (data/count xs) k) k)]
             (if (data/contains? xs i) (data/nth xs i)
               (clj.py/_error :index-error (assoc {} :index k))))
           (clj.py/_error :type-error (assoc {} :index k)))
         (if (data/map? xs)
           (if (cell/authentic? xs)
             (clj.py/_error :type-error (assoc {} :value xs))
             (let [key (clj/collection-key k)]
               (if (data/contains? xs key) (clj/prim-get xs key)
                 (clj.py/_error :key-error (assoc {} :key k)))))
           (clj.py/_error :type-error (assoc {} :value xs)))))]
    [clj.py/comp-list-new (fn [xs] (cell/new xs))]
    [clj.py/comp-list-append (fn [acc x] (do (cell/set! acc (conj (cell/get acc) x)) nil))]
    [clj.py/comp-set-new (fn [] (cell/new #{}))]
    [clj.py/comp-set-add
     (fn [acc x] (do (cell/set! acc (conj (cell/get acc) (clj/collection-key x))) nil))]
    [clj.py/comp-dict-new (fn [] (cell/new {}))]
    [clj.py/comp-dict-set
     (fn [acc k v] (do (cell/set! acc (assoc (cell/get acc) (clj/collection-key k) v)) nil))]
    [clj.py/comp-finish (fn [acc] (cell/get acc))]
    [clj.py/_for-each
     (fn [xs step]
       (if (empty? xs) nil
         (do (step (clojure.core.impl/impl-first (conj [] xs)))
             (clj.py/_for-each (clojure.core.impl/impl-rest (conj [] xs)) step))))]
    [clj.py/for-each
     (fn [xs step]
       (if (if (kind/coll? xs) (not (cell/authentic? xs)) false)
         (clj.py/_for-each (clojure.core.impl/impl-seq (conj [] xs)) step)
         (clj.py/_error :type-error (assoc {} :value xs))))]])


(def ^:private bindings
  '[[clj.py/local-get
     (fn [cell name]
       (let [binding (cell/get cell)]
         (if (data/contains? binding :value) (get binding :value)
           (clj.py/_error :unbound-local (assoc {} :name name)))))]
    [clj.py/global-get
     (fn [globals name]
       (let [g (cell/get globals) vars (get g :vars)]
         (if (data/contains? vars name) (get vars name)
           (let [binding ((get g :ambient) name)]
             (if (data/contains? binding :value) (get binding :value)
               (clj.py/_error :name-error (assoc {} :name name)))))))]
    [clj.py/global-set
     (fn [globals name value]
       (let [g (cell/get globals)]
         (do (cell/set! globals (assoc g :vars (assoc (get g :vars) name value)))
             value)))]
    [clj.py/arg (fn [args i] (data/nth args i))]
    [clj.py/make-fn
     (fn [fname spec defaults kwdefaults code]
       (clj/make-callable (assoc {} :clj.py/type :function :name fname :spec spec)
         (fn [args]
           (let [n (data/count (get spec :params)) argc (data/count args)
                 star? (get spec :star?)]
             (if (if (< argc n) true (if star? false (< n argc)))
               (clj.py/_error :arity (assoc {} :name fname :expected n :actual argc))
               (code (if star?
                       (conj (data/subvec args 0 n) (data/subvec args n argc))
                       args)))))))]
    [clj.py/call (fn [callee args] (clj/invoke-callable callee args []))]])


(def ^:private entry
  '[[clj.py/try (fn [body handler orelse] (yrt.effect/try-orelse (clj/rt-ctx) body handler orelse))]
    [clj.py/try-finally (fn [body fin] (yrt.effect/try-finally (clj/rt-ctx) body fin nil))]
    [clj.py/raise (fn [e] (clj/raise e))]
    [clj.py/call-ec (fn [f] (yrt.effect/call-ec (clj/rt-ctx) f nil))]
    [clj.py.rt/exports :clj.py/uninitialized]
    [clj.py.rt/session :clj.py/uninitialized]
    [clj.py/init!
     (fn []
       (if (= clj.py.rt/exports :clj.py/uninitialized)
         (yin/def (quote clj.py.rt/exports)
           (assoc {} (quote ex-info)
             (clj/make-callable {:clj.py/type :function :name "ex_info"}
               (fn [args]
                 (if (= (data/count args) 2)
                   (assoc {} :yin.k/message (data/nth args 0) :yin.k/data (data/nth args 1))
                   (clj.py/_error :arity (assoc {} :name "ex_info" :expected 2
                                                :actual (data/count args))))))))
         nil))]
    [clj.py/export (fn [name] (get clj.py.rt/exports name))]
    [clj.py/ex-info (fn [] (clj.py/export (quote ex-info)))]
    [clj.py/_policy
     (fn [tag payload]
       (let [reason (get {:coll/unsupported-key :unsupported-key
                          :math/div-zero :zero-division
                          :math/underflow :overflow
                          :math/overflow :overflow
                          :math/pow-fractional :pow-fractional
                          :math/pow-infinite :pow-infinite
                          :math/rem-type :type-error
                          :int/bit-limit :bit-limit
                          :int/digit-limit :digit-limit} tag :unhandled-policy)]
         (clj.py/_error reason
           (if (= tag :coll/unsupported-key) (assoc {} :key payload)
             (assoc {} :policy tag :detail payload)))))]
    [clj.py/_run
     (fn [body globals]
       (let [ctx (clj/rt-ctx) saved (cell/get ctx)
             globals-fn (clj/make-callable {:clj.py/type :function :name "globals"}
                          (fn [args] (get (cell/get globals) :vars)))]
         (do (cell/set! ctx (assoc saved :on-unhandled-policy clj.py/_policy))
             (clj.py/try-finally (fn [] (body globals globals-fn))
                                (fn [e] (yrt.effect/restore! ctx saved))))))]
    [clj.py/run-main
     (fn [body-fn ambient-resolver]
       (do (clj/init!) (clojure.core/init!) (clj.py/init!)
           (clj.py/_run body-fn (cell/new (assoc {} :vars {} :ambient ambient-resolver)))))]
    [clj.py/run-session
     (fn [body-fn ambient-resolver]
       (do (clj/init!) (clojure.core/init!) (clj.py/init!)
           (if (= clj.py.rt/session :clj.py/uninitialized)
             (yin/def (quote clj.py.rt/session) (cell/new {:vars {}}))
             nil)
           (cell/set! clj.py.rt/session (assoc (cell/get clj.py.rt/session) :ambient ambient-resolver))
           (clj.py/_run body-fn clj.py.rt/session)))]])


(def ^:private definitions (into [] cat [numeric collections bindings entry]))

(def ^:private dependency-names '[clj yrt.effect yrt.int clojure.core.impl clojure.core])

(def ^:private public-names
  (into #{}
        (remove #(= \_ (first (name %))))
        (core/exports (str module-name) definitions)))


(defn bundled [main]
  (u/annotate-call-sites
    (clj/with-bundled-runtime
      int-rt/uast
      (library/bundled-uast library/module-exports)
      (core/balanced-definitions->uast definitions)
      main)))


(def module-uast
  ;; The linked composition installs the pinned dependencies before this
  ;; definition batch. Keep the module's initialization tree cell-free.
  (u/annotate-call-sites
    (core/module-tree
      (core/module-definitions (str module-name) nil [] definitions)
      [])))


(def ^:private free-names (core/free-name-vec module-uast))


(defn module-spec [registry addresses]
  (let [requires (select-keys addresses dependency-names)]
    (admission/module-spec
      {:module-name module-name :ast module-uast :exports public-names
       :requires requires :free-names free-names
       :skip? #(contains? requires (some-> % namespace symbol))
       :registry registry :message "clj.py reads an undeclared name"
       :refusal-ns "yang.python.antlr"})))
