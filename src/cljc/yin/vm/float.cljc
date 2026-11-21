(ns yin.vm.float
  "Boxed binary64 kernels. Installed explicitly as the pure `float` host module."
  (:require [dao.host.float :as host-float]
            [dao.host.integer :as host-int]
            [dao.jing.cbor :as cbor]
            [yin.vm :as vm]
            [yin.vm.data :as data]
            [yin.vm.module :as module]))

(def module-name 'float)
(def module-version 1)
(def refusal-message "float primitive refused")

(defn- refuse! [op reason detail]
  (throw (ex-info refusal-message (merge {::op op ::reason reason} detail))))

(defn- value! [op i x]
  (when-not (cbor/float64? x)
    (refuse! op :wrong-type {::arg i ::expected :float64}))
  (data/float-value x))

(defn- exponent! [op n]
  (when-not (host-int/exact-integer? n)
    (refuse! op :wrong-type {::arg 1 ::expected :integer}))
  (let [n (host-int/promote n)]
    (when (neg? (host-int/compare-big n (host-int/from-native 0)))
      (refuse! op :negative-exponent {}))
    n))

(defn- negative-sign? [x] (first (host-float/fields x)))
(defn- neg-raw [x] (* -1.0 x))
(defn- abs-raw [x] (if (negative-sign? x) (neg-raw x) x))
(defn- zero-like-raw [x] (host-float/signed (negative-sign? x) 0.0))
(defn- nan-raw? [x] (host-int/nan-float? x))
(defn- floor-raw [x]
  (if (or (not (host-float/finite? x)) (zero? x)) x
      #?(:cljd (.floorToDouble ^double x)
         :clj (Math/floor (double x))
         :cljs (js/Math.floor x))))
(defn- div-raw [x y]
  ;; Clojure's division checks zero even for doubles.
  (if (zero? y)
    (if (or (zero? x) (nan-raw? x)) ##NaN
        (host-float/signed (not= (negative-sign? x) (negative-sign? y)) ##Inf))
    (/ x y)))

(defn- fmod-raw [x y]
  ;; Double the divisor then subtract in descending order; each subtraction
  ;; is exact by Sterbenz. The loop also handles an infinite divisor.
  (loop [d y, ds []]
    (if (<= d x)
      (recur (+ d d) (conj ds d))
      (reduce (fn [r v] (if (>= r v) (- r v) r)) x (rseq ds)))))

(defn- divmod-raw [x y]
  (if (and (host-float/finite? x) (not (nan-raw? y)))
    (let [m0 (fmod-raw (abs-raw x) (abs-raw y))
          m (if (neg? x) (neg-raw m0) m0)
          adjust (and (not (zero? m)) (not= (neg? y) (neg? m)))
          r (if (zero? m) (zero-like-raw y) (if adjust (+ m y) m))
          d (- (div-raw (- x m) y) (if adjust 1.0 0.0))
          q (if (zero? d) (zero-like-raw (div-raw x y))
                (let [f (floor-raw d)]
                  (if (> (- d f) 0.5) (+ f 1.0) f)))]
      [(data/float64 q) (data/float64 r)])
    [(data/float64 ##NaN) (data/float64 ##NaN)]))

(defn- pow-half-raw [x n]
  (let [zero (host-int/from-native 0), one (host-int/from-native 1)]
    (loop [n n, factors []]
      (if (zero? (host-int/compare-big n zero))
        (reduce (fn [v odd-bit] (let [s (* v v)] (if odd-bit (* s x) s)))
                1.0 (rseq factors))
        (recur (host-int/shift-right-big n 1)
               (conj factors (not (zero? (host-int/compare-big
                                           (host-int/bit-and-big n one) zero)))))))))

(defn- parts-raw [x]
  (when-not (host-float/finite? x)
    (refuse! 'parts :out-of-range {}))
  (if (zero? x) [0 0]
      (let [[negative? e fraction] (host-float/fields x)
            m (if (zero? e) fraction
                  (host-int/add fraction (host-int/shift-left-big (host-int/from-native 1) 52)))
            m (if negative? (host-int/sub (host-int/from-native 0) m) m)]
        [(host-int/demote m) (if (zero? e) -1074 (- e 1075))])))

(defn- format-raw [x]
  (cond (nan-raw? x) "NaN"
        (not (host-float/finite? x)) (if (negative-sign? x) "-Infinity" "Infinity")
        (zero? x) (if (negative-sign? x) "-0.0" "0.0")
        :else
        (let [[digits e] (host-float/float-digits x)
              n (count digits)
              zeros #(apply str (repeat % "0"))
              body (cond
                     (<= 0 e 15) (if (<= n (inc e))
                                    (str digits (zeros (- (inc e) n)) ".0")
                                    (str (subs digits 0 (inc e)) "." (subs digits (inc e))))
                     (<= -4 e -1) (str "0." (zeros (dec (- e))) digits)
                     :else (let [es (str (if (neg? e) (- e) e))]
                             (str (subs digits 0 1)
                                  (when (> n 1) (str "." (subs digits 1)))
                                  (if (neg? e) "e-" "e+")
                                  (when (< (count es) 2) "0") es)))]
          (str (when (negative-sign? x) "-") body))))

(defn- unary [op f] (fn [a] (f (value! op 0 a))))
(defn- binary [op f] (fn [a b] (f (value! op 0 a) (value! op 1 b))))
(defn- boxed1 [op f] (unary op #(data/float64 (f %))))
(defn- boxed2 [op f] (binary op #(data/float64 (f %1 %2))))

(def ^:private exports
  [['inf [0] #(data/float64 ##Inf)]
   ['neg-inf [0] #(data/float64 ##-Inf)]
   ['nan [0] #(data/float64 ##NaN)]
   ['zero [0] #(data/float64 0.0)]
   ['neg-zero [0] #(data/float64 (neg-raw 0.0))]
   ['nan? [1] (unary 'nan? nan-raw?)]
   ['finite? [1] (unary 'finite? host-float/finite?)]
   ['zero? [1] (unary 'zero? zero?)]
   ['neg-zero? [1] (unary 'neg-zero? #(and (zero? %) (negative-sign? %)))]
   ['zero-like [1] (boxed1 'zero-like zero-like-raw)]
   ['add [2] (boxed2 'add +)] ['sub [2] (boxed2 'sub -)]
   ['mul [2] (boxed2 'mul *)] ['div [2] (boxed2 'div div-raw)]
   ['div-checked [2] (binary 'div-checked #(if (zero? %2) ::div-zero (data/float64 (div-raw %1 %2))))]
   ['neg [1] (boxed1 'neg neg-raw)] ['abs [1] (boxed1 'abs abs-raw)]
   ['compare [2] (binary 'compare #(cond (or (nan-raw? %1) (nan-raw? %2)) ::nan (< %1 %2) -1 (> %1 %2) 1 :else 0))]
   ['floor [1] (boxed1 'floor floor-raw)]
   ['floor-int [1] (unary 'floor-int #(if (and (host-float/finite? %) (< (abs-raw %) 9007199254740992.0)) (host-int/demote (host-float/trunc-big (floor-raw %))) ::out-of-range))]
   ['fmod-pos [2] (binary 'fmod-pos (fn [x y] (when-not (and (host-float/finite? x) (>= x 0) (> y 0)) (refuse! 'fmod-pos :out-of-range {})) (data/float64 (fmod-raw x y))))]
   ['floordiv-mod [2] (binary 'floordiv-mod #(if (zero? %2) ::div-zero (divmod-raw %1 %2)))]
   ['fpow-half [2] (fn [x n] (data/float64 (pow-half-raw (value! 'fpow-half 0 x) (exponent! 'fpow-half n))))]
   ['neg-pow [3] (fn [x n order]
                   (let [x (value! 'neg-pow 0 x), n (exponent! 'neg-pow n)]
                     (when-not (= order :half) (refuse! 'neg-pow :out-of-range {::arg 2}))
                     (let [p (pow-half-raw x n)]
                       (if (zero? p) ::underflow
                           (data/float64
                             (if (host-float/finite? p) (div-raw 1.0 p)
                                 (let [h (host-int/shift-right-big n 1)]
                                   (* (div-raw 1.0 (pow-half-raw x h))
                                      (div-raw 1.0 (pow-half-raw x (host-int/sub n h)))))))))))]
   ['parts [1] (unary 'parts parts-raw)]
   ['format [1] (unary 'format format-raw)]])

(defn- arity-checked [op arities f]
  (fn [& args]
    (when-not (contains? (set arities) (count args))
      (refuse! op :arity {::argc (count args)}))
    (apply f args)))

(def float-module
  (into {} (map (fn [[op arities f]] [op (arity-checked op arities f)])) exports))
(def float-profiles
  (into {} (map (fn [[op arities _]] [op (vm/primitive-profile op :pure arities #{} :none)])) exports))
(defn register-float-module [registry]
  (module/register-host-module registry module-name float-module float-profiles))
