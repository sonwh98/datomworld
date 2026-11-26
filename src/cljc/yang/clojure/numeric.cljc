(ns yang.clojure.numeric
  "Carrier-aware Clojure numeric operations and REPL value formatting
   (architect ruling 1795693415000, RULING 2).

   Scoped to the repaired `:yang/clojure` frontend profile; does not modify
   the global legacy `yin.vm/primitives` table or `Float64`'s coercion
   refusal policy. Routes floating-point arithmetic and comparisons through
   the existing `yin.vm.float`, `yin.vm.integer`, and `yin.vm.data` kernels,
   reboxing every floating-point result as Jing `cbor/float64` content before
   it becomes guest Data."
  (:require [dao.host.integer :as host-int]
            [dao.jing.cbor :as cbor]
            [yin.vm :as vm]
            [yin.vm.data :as data]
            [yin.vm.float :as float-mod]
            [yin.vm.integer :as int-mod]))


(def ^:private data-equiv (get data/data-module 'equiv))
(def ^:private float-add (get float-mod/float-module 'add))
(def ^:private float-sub (get float-mod/float-module 'sub))
(def ^:private float-mul (get float-mod/float-module 'mul))
(def ^:private float-div (get float-mod/float-module 'div))
(def ^:private float-neg (get float-mod/float-module 'neg))
(def ^:private float-compare (get float-mod/float-module 'compare))
(def ^:private float-format-fn (get float-mod/float-module 'format))

(def ^:private default-int-mod
  (int-mod/integer-module {::int-mod/max-bits 100000
                           ::int-mod/max-digits 4300}))

(def ^:private int-to-float-boxed (get default-int-mod 'to-float-boxed))
(def ^:private int-compare-float-boxed (get default-int-mod 'compare-float-boxed))
(def ^:private legacy-div (vm/primitive-function (get vm/primitives '/)))


(defn- as-float64
  [x]
  (cond
    (cbor/float64? x) (cbor/float64 (data/float-value x))
    (host-int/exact-integer? x) (int-to-float-boxed x)
    :else (throw (ex-info "Expected numeric operand" {:value x}))))


(defn- add-2
  [a b]
  (if (or (cbor/float64? a) (cbor/float64? b))
    (float-add (as-float64 a) (as-float64 b))
    (if (and (host-int/exact-integer? a) (host-int/exact-integer? b))
      (host-int/demote (host-int/add (host-int/promote a) (host-int/promote b)))
      (+ a b))))


(defn- sub-1
  [a]
  (if (cbor/float64? a)
    (float-neg (as-float64 a))
    (if (host-int/exact-integer? a)
      (host-int/demote (host-int/sub (host-int/from-native 0) (host-int/promote a)))
      (- a))))


(defn- sub-2
  [a b]
  (if (or (cbor/float64? a) (cbor/float64? b))
    (float-sub (as-float64 a) (as-float64 b))
    (if (and (host-int/exact-integer? a) (host-int/exact-integer? b))
      (host-int/demote (host-int/sub (host-int/promote a) (host-int/promote b)))
      (- a b))))


(defn- mul-2
  [a b]
  (if (or (cbor/float64? a) (cbor/float64? b))
    (float-mul (as-float64 a) (as-float64 b))
    (if (and (host-int/exact-integer? a) (host-int/exact-integer? b))
      (host-int/demote (host-int/mul (host-int/promote a) (host-int/promote b)))
      (* a b))))


(defn- div-2
  [a b]
  (if (or (cbor/float64? a) (cbor/float64? b))
    (float-div (as-float64 a) (as-float64 b))
    (legacy-div a b)))


(defn clj-+
  ([] 0)
  ([x] (if (cbor/float64? x) (as-float64 x) x))
  ([a b] (add-2 a b))
  ([a b & more] (reduce add-2 (add-2 a b) more)))


(defn clj--
  ([x] (sub-1 x))
  ([a b] (sub-2 a b))
  ([a b & more] (reduce sub-2 (sub-2 a b) more)))


(defn clj-*
  ([] 1)
  ([x] (if (cbor/float64? x) (as-float64 x) x))
  ([a b] (mul-2 a b))
  ([a b & more] (reduce mul-2 (mul-2 a b) more)))


(defn clj-div
  ([x] (if (cbor/float64? x)
         (float-div (cbor/float64 1.0) (as-float64 x))
         (legacy-div x)))
  ([a b] (div-2 a b))
  ([a b & more] (reduce div-2 (div-2 a b) more)))


(defn- compare-pair
  "Return -1, 0, 1 for ordered numeric values `a` and `b`, or nil when
   unordered (NaN)."
  [a b]
  (cond
    (and (host-int/exact-integer? a) (host-int/exact-integer? b))
    (host-int/compare-big (host-int/promote a) (host-int/promote b))

    (and (cbor/float64? a) (cbor/float64? b))
    (let [c (float-compare (as-float64 a) (as-float64 b))]
      (when (number? c) c))

    (and (host-int/exact-integer? a) (cbor/float64? b))
    (let [c (int-compare-float-boxed a (as-float64 b))]
      (when (number? c) c))

    (and (cbor/float64? a) (host-int/exact-integer? b))
    (let [c (int-compare-float-boxed b (as-float64 a))]
      (when (number? c) (- c)))

    :else
    (compare a b)))


(defn- chain-cmp
  [pred args]
  (if (<= (count args) 1)
    true
    (loop [xs args]
      (if (next xs)
        (let [c (compare-pair (first xs) (second xs))]
          (if (and (some? c) (pred c))
            (recur (next xs))
            false))
        true))))


(defn clj-<
  ([_] true)
  ([a b] (let [c (compare-pair a b)] (boolean (and (some? c) (neg? c)))))
  ([a b & more] (chain-cmp neg? (list* a b more))))


(defn clj-<=
  ([_] true)
  ([a b] (let [c (compare-pair a b)] (boolean (and (some? c) (<= c 0)))))
  ([a b & more] (chain-cmp #(<= % 0) (list* a b more))))


(defn clj->
  ([_] true)
  ([a b] (let [c (compare-pair a b)] (boolean (and (some? c) (pos? c)))))
  ([a b & more] (chain-cmp pos? (list* a b more))))


(defn clj->=
  ([_] true)
  ([a b] (let [c (compare-pair a b)] (boolean (and (some? c) (>= c 0)))))
  ([a b & more] (chain-cmp #(>= % 0) (list* a b more))))


(defn clj-=
  "Kind-strict Clojure/Jing equality (`data/equiv`)."
  ([] true)
  ([_] true)
  ([a b] (boolean (data-equiv a b)))
  ([a b & more]
   (if (data-equiv a b)
     (loop [prev b xs more]
       (if-let [nxt (first xs)]
         (if (data-equiv prev nxt)
           (recur nxt (next xs))
           false)
         true))
     false)))


(defn clj-==
  "Cross-kind Clojure numeric equality (`==`): 1.0 == 1 is true, NaN == NaN
   is false, exact for large integers vs float64."
  ([] true)
  ([_] true)
  ([a b] (let [c (compare-pair a b)] (boolean (and (some? c) (zero? c)))))
  ([a b & more] (chain-cmp zero? (list* a b more))))


(defn clj-!=
  ([_] false)
  ([a b] (not (clj-= a b)))
  ([a b & more] (not (apply clj-= a b more))))


(defn format-float64
  "Portable display text of float64 content `x` (e.g. `1.0`, `-0.0`, `1.5`)."
  [x]
  (float-format-fn (as-float64 x)))


(def primitives
  "Carrier-aware numeric primitives scoped to the repaired Clojure frontend
   profile (`c1-3`). Merged over `yin.vm/primitives` by the Clojure frontend
   binding; never mutates `yin.vm/primitives`."
  (let [pure (fn [name f arities]
               [name (assoc (vm/primitive-profile name :pure arities #{} :none)
                            :yin.k/function f)])]
    (into {}
          [(pure '+ clj-+ [0 1 2 :variadic])
           (pure '- clj-- [1 2 :variadic])
           (pure '* clj-* [0 1 2 :variadic])
           (pure '/ clj-div [1 2 :variadic])
           (pure '= clj-= [0 1 2 :variadic])
           (pure '== clj-== [0 1 2 :variadic])
           (pure '!= clj-!= [1 2 :variadic])
           (pure '< clj-< [1 2 :variadic])
           (pure '> clj-> [1 2 :variadic])
           (pure '<= clj-<= [1 2 :variadic])
           (pure '>= clj->= [1 2 :variadic])])))
