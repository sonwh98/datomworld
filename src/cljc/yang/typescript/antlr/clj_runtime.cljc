(ns yang.typescript.antlr.clj-runtime
  "TypeScript syntax adapters over the persistent Clojure runtime."
  (:require [yang.clojure.core-module :as library]
            [yang.clojure.prelude :as clj]
            [yang.core.prelude :as core]
            [yang.core.prelude-admission :as admission]
            [yang.core.uast :as u]
            [yang.rt.int :as int-rt]))


(def module-name 'clj.ts)


(def ^:private numeric
  '[[clj.ts/_error
     (fn [reason evidence] (clj/raise (assoc evidence :clj.ts/error reason)))]
    [clj.ts/num-kind
     (fn [x] (if (integer/integer? x) :bigint (if (data/float64? x) :float :other)))]
    [clj.ts/_int-result (fn [x] (yrt.int/check-limit (clj/rt-ctx) x))]
    [clj.ts/int-lit
     (fn [s]
       (let [prefix (data/substring s 0 2)
             radix (get {"0x" 16 "0o" 8 "0b" 2} prefix 10)]
         (clj.ts/_int-result
           (integer/parse (if (= radix 10) s (data/substring s 2 (data/str-length s))) radix))))]
    [clj.ts/_float
     (fn [x]
       (if (integer/integer? x)
         (clj.ts/_int-result (integer/to-float-boxed x))
         x))]
    [clj.ts/_math-error
     (fn [tag a b] (clj/perform-policy tag (assoc {} :a a :b b)))]
    [clj.ts/_float-result
     (fn [x a b]
       (if (= x :yin.vm.float/div-zero) (clj.ts/_math-error :math/div-zero a b)
           (if (= x :yin.vm.float/underflow) (clj.ts/_math-error :math/underflow a b)
               x)))]
    [clj.ts/_arithmetic
     (fn [iop fop a b]
       (let [ak (clj.ts/num-kind a) bk (clj.ts/num-kind b)]
         (if (if (= ak :other) true (= bk :other))
           (clj.ts/_error :type-error (assoc {} :a a :b b))
           (if (if (= ak :bigint) (= bk :bigint) false)
             (clj.ts/_int-result (iop a b))
             (clj.ts/_float-result (fop (clj.ts/_float a) (clj.ts/_float b)) a b)))))]
    [clj.ts/add
     (fn [a b]
       (if (if (data/string? a) (data/string? b) false)
         (data/str-concat a b)
         (clj.ts/_arithmetic integer/add float/add a b)))]
    [clj.ts/sub (fn [a b] (clj.ts/_arithmetic integer/sub float/sub a b))]
    [clj.ts/mul (fn [a b] (clj.ts/_arithmetic integer/mul float/mul a b))]
    [clj.ts/_int-div
     (fn [a b]
       (if (= 0 (integer/compare b 0))
         (clj.ts/_math-error :math/div-zero a b)
         (integer/true-div-boxed a b)))]
    [clj.ts/div (fn [a b] (clj.ts/_arithmetic clj.ts/_int-div float/div-checked a b))]
    [clj.ts/_int-mod
     (fn [a b]
       (if (= 0 (integer/compare b 0))
         (clj.ts/_math-error :math/div-zero a b)
         (data/nth (clj.ts/_int-result (integer/floor-div-mod a b)) 1)))]
    [clj.ts/_float-mod
     (fn [a b]
       (let [result (float/floordiv-mod a b)]
         (if (= result :yin.vm.float/div-zero) result (data/nth result 1))))]
    [clj.ts/mod (fn [a b] (clj.ts/_arithmetic clj.ts/_int-mod clj.ts/_float-mod a b))]
    [clj.ts/_float-pow
     (fn [a b]
       (if (< (integer/compare b 0) 0)
         (if (float/zero? a)
           (clj.ts/_math-error :math/div-zero a b)
           (clj.ts/_float-result
             (float/neg-pow a (clj.ts/_int-result (integer/neg b)) :half) a b))
         (float/fpow-half a b)))]
    [clj.ts/pow
     (fn [a b]
       (let [ak (clj.ts/num-kind a) bk (clj.ts/num-kind b)
             exponent (if (= bk :float) (float/floor-int b) b)]
         (if (if (= ak :other) true (= bk :other))
           (clj.ts/_error :type-error (assoc {} :a a :b b))
           (if (if (integer/integer? exponent)
                 (if (= bk :float) (= 0 (integer/compare-float-boxed exponent b)) true)
                 false)
             (if (if (= ak :bigint) (if (= bk :bigint) (<= 0 (integer/compare b 0)) false) false)
               (clj.ts/_int-result (integer/pow a b))
               (clj.ts/_float-pow (clj.ts/_float a) exponent))
             (clj.ts/_math-error :math/unsupported-exponent a b)))))]
    [clj.ts/to-numeric
     (fn [x]
       (if (= :other (clj.ts/num-kind x))
         (clj.ts/_error :type-error (assoc {} :value x))
         x))]
    [clj.ts/neg
     (fn [x]
       (if (integer/integer? (clj.ts/to-numeric x))
         (clj.ts/_int-result (integer/neg x))
         (float/neg x)))]
    [clj.ts/equiv (fn [a b] (data/equiv a b))]
    [clj.ts/_compare
     (fn [a b]
       (let [ak (clj.ts/num-kind a) bk (clj.ts/num-kind b)]
         (if (= ak :bigint)
           (if (= bk :bigint) (integer/compare a b)
               (if (= bk :float) (integer/compare-float-boxed a b)
                   (clj.ts/_error :type-error (assoc {} :a a :b b))))
           (if (= ak :float)
             (if (= bk :float) (float/compare a b)
                 (if (= bk :bigint)
                   (let [order (integer/compare-float-boxed b a)]
                     (if (integer/integer? order) (- 0 order) order))
                   (clj.ts/_error :type-error (assoc {} :a a :b b))))
             (if (if (data/string? a) (data/string? b) false)
               (data/str-compare a b)
               (clj.ts/_error :type-error (assoc {} :a a :b b)))))))]
    [clj.ts/lt (fn [a b] (= -1 (clj.ts/_compare a b)))]
    [clj.ts/le (fn [a b] (let [c (clj.ts/_compare a b)] (if (= c -1) true (= c 0))))]
    [clj.ts/gt (fn [a b] (= 1 (clj.ts/_compare a b)))]
    [clj.ts/ge (fn [a b] (let [c (clj.ts/_compare a b)] (if (= c 1) true (= c 0))))]
    [clj.ts/truthy (fn [value] (if value true false))]
    [clj.ts/str (fn [value] (clj/invoke-callable (clojure.core/str) (conj [] value) []))]
    [clj.ts/str-concat (fn [a b] (data/str-concat a b))]])


(def ^:private collections
  '[[clj.ts/vconj (fn [xs x] (conj xs x))]
    [clj.ts/vector (fn [xs] xs)]
    [clj.ts/spread
     (fn [acc xs]
       (if (data/vector? xs) (data/into acc xs)
           (clj.ts/_error :spread-type (assoc {} :value xs))))]
    [clj.ts/map-put (fn [m k v] (assoc m (clj/collection-key k) v))]
    [clj.ts/property-key (fn [k] k)]
    [clj.ts/get-item
     (fn [coll key]
       (let [index (if (if (data/vector? coll) (data/float64? key) false)
                     (let [i (float/floor-int key)]
                       (if (if (integer/integer? i) (= 0 (integer/compare-float-boxed i key)) false)
                         i key))
                     key)]
         (clj/invoke-callable (clojure.core/get) (conj [] coll index) [])))]
    [clj.ts/for-in-start
     (fn [coll]
       (if (if (data/map? coll) (not (cell/authentic? coll)) false)
         (cell/new (conj [] (data/canonical-entries coll) 0))
         (clj.ts/_error :iteration-type (assoc {} :value coll))))]
    [clj.ts/for-in-next
     (fn [cursor]
       (let [state (cell/get cursor) entries (data/nth state 0) i (data/nth state 1)]
         (if (< i (data/count entries))
           (do (cell/set! cursor (conj [] entries (+ i 1)))
               (data/nth (data/nth entries i) 0))
           :clj.ts/done)))]])


(def ^:private bindings
  '[[clj.ts/local-get
     (fn [slot name]
       (let [x (cell/get slot)]
         (if (= x :clj/unbound) (clj.ts/_error :unbound-local (assoc {} :name name)) x)))]
    [clj.ts/const-assign (fn [] (clj.ts/_error :constant-assignment {}))]
    [clj.ts/global-get
     (fn [globals name]
       (let [g (cell/get globals) vars (get g :vars)]
         (if (data/contains? vars name) (get vars name)
             (let [binding ((get g :ambient) name)]
               (if (data/contains? binding :value) (get binding :value)
                   (clj.ts/_error :name-error (assoc {} :name name)))))))]
    [clj.ts/arg (fn [args i] (get args i nil))]
    [clj.ts/make-fn
     (fn [name arity this-mode kind code]
       (clj/make-callable (assoc {} :clj.ts/type :function :name name :arity arity)
                          (fn [args] (code nil args))))]
    [clj.ts/call (fn [callee receiver args] (clj/invoke-callable callee args []))]])


(def ^:private entry
  '[[clj.ts/try (fn [body handler] (yrt.effect/try-value (clj/rt-ctx) body handler))]
    [clj.ts/try-finally (fn [body fin] (yrt.effect/try-finally (clj/rt-ctx) body fin nil))]
    [clj.ts/raise (fn [value] (clj/raise value))]
    [clj.ts/call-ec (fn [f] (yrt.effect/call-ec (clj/rt-ctx) f nil))]
    [clj.ts.rt/exports :clj.ts/uninitialized]
    [clj.ts/init!
     (fn []
       (if (= clj.ts.rt/exports :clj.ts/uninitialized)
         (yin/def (quote clj.ts.rt/exports)
           (assoc {} (quote ex-info)
                  (clj/make-callable {:clj.ts/type :function :name "exInfo"}
                                     (fn [args]
                                       (if (= 2 (data/count args))
                                         (assoc {} :yin.k/message (data/nth args 0) :yin.k/data (data/nth args 1))
                                         (clj.ts/_error :arity (assoc {} :name "exInfo" :expected 2
                                                                      :actual (data/count args))))))))
         nil))]
    [clj.ts/export (fn [name] (get clj.ts.rt/exports name))]
    [clj.ts/ex-info (fn [] (clj.ts/export (quote ex-info)))]
    [clj.ts/_policy
     (fn [tag payload]
       (let [reason (get {:coll/unsupported-key :unsupported-key
                          :math/div-zero :zero-division
                          :math/underflow :underflow
                          :math/overflow :overflow
                          :math/unsupported-exponent :unsupported-exponent
                          :int/bit-limit :bit-limit
                          :int/digit-limit :digit-limit} tag :unhandled-policy)]
         (clj.ts/_error reason
                        (if (= tag :coll/unsupported-key) (assoc {} :key payload)
                            (assoc {} :policy tag :detail payload)))))]
    [clj.ts/run-main
     (fn [body-fn ambient-resolver]
       (do (clj/init!) (clojure.core/init!) (clj.ts/init!)
           (let [ctx (clj/rt-ctx) saved (cell/get ctx)
                 globals (cell/new (assoc {} :vars {} :ambient ambient-resolver))]
             (do (cell/set! ctx (assoc saved :on-unhandled-policy clj.ts/_policy))
                 (clj.ts/try-finally (fn [] (body-fn globals))
                                     (fn [e] (yrt.effect/restore! ctx saved)))))))]])


(def ^:private definitions (into [] cat [numeric collections bindings entry]))

(def ^:private dependency-names '[clj yrt.effect yrt.int clojure.core.impl clojure.core])


(def ^:private public-names
  (into #{}
        (remove #(= \_ (first (name %))))
        (core/exports (str module-name) definitions)))


(defn bundled
  [main]
  (clj/with-bundled-runtime
    int-rt/uast
    (library/bundled-uast library/module-exports)
    (core/balanced-definitions->uast definitions)
    main))


(def module-uast
  (u/annotate-call-sites
    (core/module-tree (core/module-definitions (str module-name) nil [] definitions) [])))


(def ^:private free-names (core/free-name-vec module-uast))


(defn module-spec
  [registry addresses]
  (let [requires (select-keys addresses dependency-names)]
    (admission/module-spec
      {:module-name module-name :ast module-uast :exports public-names
       :requires requires :free-names free-names
       :skip? #(contains? requires (some-> % namespace symbol))
       :registry registry :message "clj.ts reads an undeclared name"
       :refusal-ns "yang.typescript.antlr"})))
