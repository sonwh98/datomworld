(ns yang.php.antlr.clj-runtime
  "PHP operand adapters over the persistent Clojure-family runtime."
  (:require [yang.clojure.core-module :as library]
            [yang.clojure.prelude :as clj]
            [yang.core.prelude :as core]
            [yang.core.prelude-admission :as admission]
            [yang.core.uast :as u]
            [yang.rt.int :as int-rt]))

(def module-name 'clj.php)

(def ^:private numeric
  '[[clj.php/_error
     (fn [reason data] (clj/raise (assoc data :clj.php/error reason)))]
    [clj.php/_int (fn [x] (yrt.int/check-limit (clj/rt-ctx) x))]
    [clj.php/_number?
     (fn [x] (if (integer/integer? x) true (data/float64? x)))]
    [clj.php/_float
     (fn [x] (if (integer/integer? x) (clj.php/_int (integer/to-float-boxed x)) x))]
    [clj.php/_math-error
     (fn [tag a b] (clj/perform-policy tag (assoc {} :a a :b b)))]
    [clj.php/_checked
     (fn [x a b]
       (if (= x :yin.vm.float/div-zero) (clj.php/_math-error :math/div-zero a b)
         (if (= x :yin.vm.float/underflow) (clj.php/_math-error :math/underflow a b)
           (clj.php/_int x))))]
    [clj.php/_arithmetic
     (fn [iop fop a b]
       (if (if (clj.php/_number? a) (clj.php/_number? b) false)
         (clj.php/_checked
           (if (if (integer/integer? a) (integer/integer? b) false)
             (iop a b)
             (fop (clj.php/_float a) (clj.php/_float b)))
           a b)
         (clj.php/_error :type-error (assoc {} :a a :b b))))]
    [clj.php/add (fn [a b] (clj.php/_arithmetic integer/add float/add a b))]
    [clj.php/sub (fn [a b] (clj.php/_arithmetic integer/sub float/sub a b))]
    [clj.php/mul (fn [a b] (clj.php/_arithmetic integer/mul float/mul a b))]
    [clj.php/div
     (fn [a b]
       (clj.php/_arithmetic
         (fn [a b] (if (= 0 (integer/compare b 0)) :yin.vm.float/div-zero
                       (integer/true-div-boxed a b)))
         float/div-checked a b))]
    [clj.php/mod
     (fn [a b]
       (if (if (integer/integer? a) (integer/integer? b) false)
         (if (= 0 (integer/compare b 0)) (clj.php/_math-error :math/div-zero a b)
           (data/nth (clj.php/_int (integer/quot-rem a b)) 1))
         (clj.php/_math-error :math/rem-type a b)))]
    [clj.php/_float-pow
     (fn [a n]
       (if (< (integer/compare n 0) 0)
         (if (float/zero? a) :yin.vm.float/div-zero
           (float/neg-pow a (clj.php/_int (integer/neg n)) :half))
         (float/fpow-half a n)))]
    [clj.php/_pow
     (fn [a n]
       (if (if (integer/integer? a) (<= 0 (integer/compare n 0)) false)
         (integer/pow a n)
         (clj.php/_float-pow (clj.php/_float a) n)))]
    [clj.php/pow
     (fn [a b]
       (if (if (clj.php/_number? a) (clj.php/_number? b) false)
         (clj.php/_checked
           (if (integer/integer? b)
             (clj.php/_pow a b)
             (if (float/finite? b)
               (let [n (float/floor-int b)]
                 (if (= n :yin.vm.float/out-of-range)
                   (clj.php/_math-error :math/overflow a b)
                   (if (= 0 (integer/compare-float-boxed n b))
                     (clj.php/_float-pow (clj.php/_float a) n)
                     (clj.php/_math-error :math/pow-fractional a b))))
               (clj.php/_math-error :math/pow-infinite a b)))
           a b)
         (clj.php/_error :type-error (assoc {} :a a :b b))))]
    [clj.php/neg
     (fn [x]
       (if (integer/integer? x) (clj.php/_int (integer/neg x))
         (if (data/float64? x) (float/neg x)
           (clj.php/_error :type-error (assoc {} :value x)))))]
    [clj.php/pos
     (fn [x] (if (clj.php/_number? x) x (clj.php/_error :type-error (assoc {} :value x))))]
    [clj.php/eq (fn [a b] (data/equiv a b))]
    [clj.php/ne (fn [a b] (not (data/equiv a b)))]
    [clj.php/_compare
     (fn [a b]
       (if (integer/integer? a)
         (if (integer/integer? b) (integer/compare a b)
           (if (data/float64? b) (integer/compare-float-boxed a b)
             (clj.php/_error :type-error (assoc {} :a a :b b))))
         (if (data/float64? a)
           (if (data/float64? b) (float/compare a b)
             (if (integer/integer? b)
               (let [order (integer/compare-float-boxed b a)]
                 (if (integer/integer? order) (- 0 order) order))
               (clj.php/_error :type-error (assoc {} :a a :b b))))
           (if (if (data/string? a) (data/string? b) false)
             (data/str-compare a b)
             (clj.php/_error :type-error (assoc {} :a a :b b))))))]
    [clj.php/lt (fn [a b] (= -1 (clj.php/_compare a b)))]
    [clj.php/le (fn [a b] (let [c (clj.php/_compare a b)] (if (= c -1) true (= c 0))))]
    [clj.php/gt (fn [a b] (= 1 (clj.php/_compare a b)))]
    [clj.php/ge (fn [a b] (let [c (clj.php/_compare a b)] (if (= c 1) true (= c 0))))]
    [clj.php/truthy (fn [x] (if x true false))]
    [clj.php/nan-const (fn [] (float/nan))]
    [clj.php/inf-const (fn [] (float/inf))]
    [clj.php/int-lit-radix (fn [digits radix] (clj.php/_int (integer/parse digits radix)))]
    [clj.php/int-lit (fn [digits] (clj.php/int-lit-radix digits 10))]])

(def ^:private collections
  '[[clj.php/vconj (fn [xs x] (conj xs x))]
    [clj.php/empty-array (fn [] [])]
    [clj.php/_vector->map
     (fn [xs i acc]
       (if (< i (data/count xs))
         (clj.php/_vector->map xs (+ i 1) (assoc acc i (data/nth xs i)))
         acc))]
    [clj.php/array-put
     (fn [xs k v]
       (let [key (clj/collection-key k)]
         (assoc (if (data/vector? xs) (clj.php/_vector->map xs 0 {}) xs) key v)))]
    [clj.php/_next-index
     (fn [entries i next]
       (if (< i (data/count entries))
         (let [k (data/nth (data/nth entries i) 0)]
           (clj.php/_next-index entries (+ i 1)
             (if (if (integer/integer? k) (<= 0 (integer/compare k next)) false)
               (clj.php/_int (integer/add k 1))
               next)))
         next))]
    [clj.php/array-append
     (fn [xs x]
       (if (data/vector? xs)
         (conj xs x)
         (assoc xs (clj.php/_next-index (data/canonical-entries xs) 0 0) x)))]
    [clj.php/get-item (fn [xs key] (clj/prim-get xs (clj/collection-key key)))]
    [clj.php/string (fn [x] (clj/invoke-callable (clojure.core/str) (conj [] x) []))]
    [clj.php/concat (fn [a b] (data/str-concat (clj.php/string a) (clj.php/string b)))]])

(def ^:private bindings
  '[[clj.php/global-get (fn [state key] (get (get (cell/get state) :vars) key))]
    [clj.php/global-set
     (fn [state key value]
       (let [s (cell/get state)]
         (do (cell/set! state (assoc s :vars (assoc (get s :vars) key value))) value)))]
    [clj.php/declare-fn
     (fn [state name callable]
       (let [s (cell/get state) fs (get s :functions)]
         (if (data/contains? fs name)
           (clj.php/_error :duplicate-function (assoc {} :name name))
           (cell/set! state (assoc s :functions (assoc fs name callable))))))]
    [clj.php/resolve-fn
     (fn [state name written]
       (let [s (cell/get state) fs (get s :functions)]
         (if (data/contains? fs name) (get fs name)
           (let [binding ((get s :ambient) name)]
             (if (data/contains? binding :value) (get binding :value)
               (clj.php/_error :undefined-function (assoc {} :name written)))))))]
    [clj.php/arg (fn [args i] (data/nth args i))]
    [clj.php/rest-args (fn [args i] (data/subvec args i (data/count args)))]
    [clj.php/make-fn
     (fn [name spec body statics]
       (clj/make-callable (assoc {} :clj.php/type :function :name name)
         (fn [args]
           (let [n (get spec :required) argc (data/count args)]
             (if (if (< argc n) true (if (get spec :variadic) false (< n argc)))
               (clj.php/_error :arity (assoc {} :name name :expected n :actual argc))
               (body args))))))]
    [clj.php/call (fn [f args] (clj/invoke-callable f args []))]])

(def ^:private entry
  '[[clj.php/call-ec (fn [body] (yrt.effect/call-ec (clj/rt-ctx) body nil))]
    [clj.php/try-finally (fn [body fin] (clj/try-finally body fin))]
    [clj.php/raise (fn [value] (clj/raise value))]
    [clj.php/attach-output
     (fn [state response diagnostics]
       (cell/set! state (assoc (cell/get state) :response-stream response :diag-stream diagnostics)))]
    [clj.php/echo
     (fn [state text]
       (let [s (cell/get state)] (cell/set! state (assoc s :out (conj (get s :out) text)))))]
    [clj.php/_policy
     (fn [tag payload]
       (let [reason (get {:coll/unsupported-key :unsupported-key
                          :math/div-zero :zero-division
                          :math/underflow :underflow
                          :math/overflow :overflow
                          :math/pow-fractional :pow-fractional
                          :math/pow-infinite :pow-infinite
                          :math/rem-type :type-error
                          :int/bit-limit :bit-limit
                          :int/digit-limit :digit-limit} tag :unhandled-policy)]
         (clj.php/_error reason
           (if (= tag :coll/unsupported-key) (assoc {} :key payload)
             (assoc {} :policy tag :detail payload)))))]
    [clj.php/_drain
     (fn [cursor records]
       (let [record (stream/poll! cursor)]
         (if (= record :dao.stream/blocked) records
           (clj.php/_drain cursor (conj records record)))))]
    [clj.php/_result
     (fn [state outcome]
       (let [out (data/str-join "" (get state :out))
             response (get state :response-stream)
             diagnostics (get state :diag-stream)
             record (assoc {} :php.response/body out)]
         (do (if (if (= out "") false (not (nil? response)))
               (stream/put! response record)
               nil)
             (assoc {} :php/out out
                    :php/diag (if (nil? diagnostics) [] (clj.php/_drain (stream/cursor diagnostics) []))
                    :php/response (if (nil? response)
                                    (if (= out "") [] (conj [] record))
                                    (clj.php/_drain (stream/cursor response) []))
                    :php/uncaught (if (= (data/nth outcome 0) :yin.k/ok)
                                    nil (data/nth outcome 1))))))]
    [clj.php/run-main
     (fn [body-fn ambient-resolver]
       (do (clj/init!) (clojure.core/init!)
           (let [ctx (clj/rt-ctx) saved (cell/get ctx)
                 state (cell/new (assoc {} :vars {} :functions {} :out [] :ambient ambient-resolver))
                 outcome (clj/try-finally
                           (fn []
                             (do (cell/set! ctx (assoc (cell/get ctx)
                                                      :on-unhandled-policy clj.php/_policy))
                                 (clj/run-unit (fn [] (body-fn state)))))
                           (fn [e] (yrt.effect/restore! ctx saved)))]
             (clj.php/_result (cell/get state) outcome))))]])

(def ^:private definitions (into [] cat [numeric collections bindings entry]))

(def ^:private dependency-names '[clj yrt.effect yrt.int clojure.core])

(def ^:private public-names
  (into #{} (remove #(= \_ (first (name %)))) (core/exports (str module-name) definitions)))

(defn bundled [main]
  (u/annotate-call-sites
    (clj/with-bundled-runtime
      int-rt/uast
      (library/bundled-uast library/module-exports)
      (core/balanced-definitions->uast definitions)
      main)))

(def module-uast
  (u/annotate-call-sites
    (core/module-tree (core/module-definitions (str module-name) nil [] definitions) [])))

(def ^:private free-names (core/free-name-vec module-uast))

(defn module-spec [registry addresses]
  (let [requires (select-keys addresses dependency-names)]
    (admission/module-spec
      {:module-name module-name :ast module-uast :exports public-names
       :requires requires :free-names free-names
       :skip? #(contains? requires (some-> % namespace symbol))
       :registry registry :message "clj.php reads an undeclared name"
       :refusal-ns "yang.php"})))
