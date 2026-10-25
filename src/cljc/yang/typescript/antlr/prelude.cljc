(ns ^{:clj-kondo/config '{:lint-as {yang.typescript.antlr.prelude/defquoted clojure.core/def}}}
 yang.typescript.antlr.prelude
  "The `js` runtime profile, written as Universal AST
   (docs/design/yang.typescript.md sections 3, 9, 10; the T1 plan's
   section 6). JavaScript semantics for operators, coercion, equality,
   strings, objects, functions and exceptions live here as guest code,
   not in Clojure: the lowering only emits applications of these names.

   Value encoding:
     undefined, null  :js/undefined, :js/null (and the never guest-visible
                      :js/tdz of an uninitialized binding): never host nil
     boolean          host true / false
     number           float64 content (`data/float64`) at the producer, on
                      every host (JS cannot tell 2 from 2.0 as a bare
                      Number). Arithmetic unwraps with `data/float-value`,
                      computes on bare host doubles and rewraps with
                      `data/float64`. The prelude holds no integral float
                      literal: 1 is written (data/float64 1)
     bigint           the bare exact integer, through `integer/*`; a number
                      and a bigint are told apart by content equality with
                      the same value as float64 (`js/kind-of`)
     string           {:js/str s} when the code units are well-formed
                      UTF-16, {:js/units [u ...]} otherwise; `js/mk-str` is
                      the one normalizing door, so strict equality of
                      strings is host `=`
     object           a cell holding {:js/type :object :proto <ref|:js/null>
                      :props <om> :extensible? true}; the guest reference
                      is the cell ref, identity is `=` on refs
     function         a cell holding {:js/type :function :code
                      (fn [this args] ..) :this-mode :strict|:lexical
                      :name s :length n :props <om> :proto ref}
     property slot    {:value v :writable b :enumerable b :configurable b}
     own-key map      `om`: {:index {key slot} :ints [..sorted..] :strs
                      [..insertion..] :syms []}; guest-visible order is the
                      vectors, never a host map's

   Calling convention, fixed: every guest call is (js/call f this args)
   with `args` a vector built by `js/vconj`; every construct is
   (js/construct F args); a function's code is (fn [this args] ..).

   Control flow is continuations: `js/throw`, `js/try`, `js/try-finally`
   and `js/call-ec` copy the Python prelude's escape machinery with JS
   payloads: `js/throw` takes any value, the handler stack lives in the one
   record cell `js.rt/ctx` ({:handlers} only), and a flag cell tells a
   first pass through a capture point from a re-entry.

   Runtime state is allocated by `js/init!`, once per task, under
   `js.rt/*` keys (`js.rt/state` is the literal :js/uninit until `js/init!`
   flips it to :js/ready as its last write; a second call allocates
   nothing): the context and out cells, the global object and the intrinsic
   prototypes. The definitions are one list; `uast` is the bundled prelude
   and `module-uast` the same list as the linker module `js`. The module
   emitter strips only the namespace `js/`, so no definition is named like
   a primitive (`js/get-prop`, not `js/get`), and no binder inside the
   prelude is named like a definition.

   Host names the prelude depends on and does not define: `host-names`
   (the cell module from yin.vm.module, the integer module from
   yin.vm.integer, the rest from yin.vm.data)."
  #?(:cljs (:require-macros [yang.typescript.antlr.prelude :refer [defquoted]]))
  (:require
    #?(:cljd [clojure.edn :as edn])
    [yang.core.prelude :as core]
    [yang.core.uast :as u]
    [yin.vm.integer :as integer]))


#?(:clj
   (defmacro defquoted
     "`def` of the quoted data `form`. ClojureDart compiles quoted data to
      straight-line Dart, which the Dart AOT compiler cannot take as one
      initializer, so there the data is one EDN string read back at load."
     [name doc form]
     (if (find-ns 'cljd.compiler)
       (list 'def name doc (list 'edn/read-string (pr-str (second form))))
       (list 'def name doc form))))


(def host-names
  "Module exports the prelude calls."
  '#{cell/new cell/get cell/set! data/count data/into data/subvec
     data/number? data/str-concat data/str-length data/substring
     data/str->code-points data/code-points->str data/float64
     data/float-value data/content= data/str-unit-length data/str-unit-at
     data/str-units data/units->str integer/add integer/sub integer/neg
     integer/mul integer/compare integer/quot-rem integer/to-float
     integer/compare-float integer/pow integer/shift-right
     integer/shift-left integer/bit-and integer/bit-or integer/bit-xor
     integer/bit-not integer/from-float integer/floor-div-mod
     integer/format integer/parse integer/float-digits
     integer/decimal->float})


(defquoted ^:private core-definitions
  "Pure definitions: lambdas only, so defining them runs nothing."
  '[;; ----------------------------------------------------------------- kinds
    [js/cell? (fn [x] (= (get x :type) :cell-ref))]
    [js/str?
     (fn [x]
       (if (nil? (get x :js/str)) (not (nil? (get x :js/units))) true))]
    [js/pair (fn [a b] (conj (conj [] a) b))]
    [js/vconj (fn [xs x] (conj xs x))]
    [js/arg (fn [args i] (get args i :js/undefined))]
    [js/kind-of
     ;; undefined null boolean number bigint string function object. A
     ;; number is float64 content, a bigint a bare exact integer: content
     ;; equality with the same value as float64 tells them apart on every
     ;; host (the JS carrier is not a host number at all)
     (fn [x]
       (if (= x :js/undefined)
         :undefined
         (if (= x :js/null)
           :null
           (if (= x true)
             :boolean
             (if (= x false)
               :boolean
               (if (data/number? x)
                 (if (data/content= x (data/float64 x)) :number :bigint)
                 (if (js/str? x)
                   :string
                   (if (js/cell? x)
                     (if (= (get (cell/get x) :js/type) :function)
                       :function
                       :object)
                     :number))))))))]
    [js/ty (fn [k] (if (= k :function) :object k))]
    [js/prim-type?
     (fn [t] (if (= t :string) true (if (= t :number) true (= t :bigint))))]
    [js/callable?
     (fn [x]
       (if (js/cell? x) (= (get (cell/get x) :js/type) :function) false))]
    [js/mknum (fn [x] (data/float64 x))]
    [js/fval (fn [x] (data/float-value x))]
    [js/nan-raw (fn [] (let [i (data/float-value ##Inf)] (- i i)))]
    [js/nan (fn [] (data/float64 (js/nan-raw)))]
    [js/habs (fn [x] (if (< x 0) (- 0 x) x))]
    [js/finite? (fn [x] (< (js/habs x) (data/float-value ##Inf)))]
    [js/zero-d? (fn [x] (= x (data/float-value 0)))]
    [js/neg-zero-d?
     (fn [x]
       (if (js/zero-d? x)
         (data/content= (data/float64 x)
                        (data/float64 (* (data/float-value -1)
                                         (data/float-value 0))))
         false))]
    [js/neg-zero
     (fn []
       (data/float64 (* (data/float-value -1) (data/float-value 0))))]
    [js/int-result
     ;; the `integer` module answers a limit breach as a reason
     (fn [r]
       (if (= r :yin.vm.integer/bit-limit)
         (js/throw-range-error {:js/str "Maximum BigInt size exceeded"})
         (if (= r :yin.vm.integer/digit-limit)
           (js/throw-range-error {:js/str "Maximum BigInt size exceeded"})
           r)))]

    ;; --------------------------------------------------------------- strings
    ;; A string is {:js/str host-string} when its code units are well-formed
    ;; UTF-16 and {:js/units [u ...]} otherwise; `js/mk-str` is the one door
    ;; that normalizes (it takes a vector of code units; a host string is
    ;; well-formed and is wrapped as {:js/str s}), so strict equality is host `=`.
    [js/mk-str
     (fn [us]
       (let [r (data/units->str us)]
         (if (nil? (get r :yin.vm.data/refusal))
           (assoc {} :js/str r)
           (assoc {} :js/units us))))]
    [js/units-of
     (fn [s]
       (if (nil? (get s :js/str))
         (get s :js/units)
         (data/str-units (get s :js/str))))]
    [js/str-unit-length
     (fn [s]
       (if (nil? (get s :js/str))
         (data/count (get s :js/units))
         (data/str-unit-length (get s :js/str))))]
    [js/str-unit-at
     (fn [s i]
       (if (nil? (get s :js/str))
         (get (get s :js/units) i)
         (data/str-unit-at (get s :js/str) i)))]
    [js/str-concat
     (fn [a b]
       (if (nil? (get a :js/str))
         (js/mk-str (data/into (get a :js/units) (js/units-of b)))
         (if (nil? (get b :js/str))
           (js/mk-str (data/into (js/units-of a) (get b :js/units)))
           (assoc {} :js/str (data/str-concat (get a :js/str) (get b :js/str))))))]
    [js/str-slice
     ;; the units [from, to) of s, 0 <= from <= to <= length
     (fn [s from to]
       (js/mk-str (data/subvec (js/units-of s) from to)))]
    [js/units-compare
     (fn [x y i]
       (let [nx (data/count x)
             ny (data/count y)]
         (if (if (<= nx i) true (<= ny i))
           (if (< nx ny) -1 (if (> nx ny) 1 0))
           (let [p (get x i)
                 q (get y i)]
             (if (< p q) -1 (if (> p q) 1 (js/units-compare x y (+ i 1))))))))]
    [js/str-compare
     ;; -1, 0 or 1 by UTF-16 code unit, as the relational operators order
     (fn [a b]
       (if (= a b) 0 (js/units-compare (js/units-of a) (js/units-of b) 0)))]
    [js/units-match?
     (fn [xs ys i j]
       (if (<= (data/count ys) j)
         true
         (if (= (get xs (+ i j)) (get ys j))
           (js/units-match? xs ys i (+ j 1))
           false)))]
    [js/units-index
     (fn [xs ys i]
       (if (< (- (data/count xs) (data/count ys)) i)
         -1
         (if (js/units-match? xs ys i 0) i (js/units-index xs ys (+ i 1)))))]

    ;; ---------------------------------------------------- number to string
    [js/zeros
     (fn [n acc]
       (if (<= n 0) acc (js/zeros (- n 1) (data/str-concat acc "0"))))]
    [js/exp-text
     (fn [e]
       (data/str-concat (if (< e 0) "-" "+")
                        (integer/format (if (< e 0) (- 0 e) e))))]
    [js/layout
     ;; Number::toString(10) of d1d2...dk x 10^e, with n = e + 1
     (fn [digits e]
       (let [k (data/str-length digits)
             n (+ e 1)]
         (if (if (<= k n) (<= n 21) false)
           (data/str-concat digits (js/zeros (- n k) ""))
           (if (if (< 0 n) (<= n 21) false)
             (data/str-concat (data/substring digits 0 n)
                              (data/str-concat "." (data/substring digits n)))
             (if (if (< -6 n) (<= n 0) false)
               (data/str-concat "0."
                                (data/str-concat (js/zeros (- 0 n) "") digits))
               (data/str-concat
                 (data/substring digits 0 1)
                 (data/str-concat
                   (if (> k 1) (data/str-concat "." (data/substring digits 1)) "")
                   (data/str-concat "e" (js/exp-text (- n 1))))))))))]
    [js/number-text
     (fn [a]
       (let [x (data/float-value a)]
         (if (not (<= x x))
           "NaN"
           (if (= x (data/float-value ##Inf))
             "Infinity"
             (if (= x (data/float-value ##-Inf))
               "-Infinity"
               (if (js/zero-d? x)
                 "0"
                 (let [de (integer/float-digits x)]
                   (data/str-concat (if (< x 0) "-" "")
                                    (js/layout (get de 0) (get de 1))))))))))]
    [js/number->string (fn [a] (assoc {} :js/str (js/number-text a)))]
    [js/bigint->string
     (fn [a] (assoc {} :js/str (js/int-result (integer/format a))))]
    [js/kind-name
     (fn [k]
       (get {:undefined {:js/str "undefined"},
             :boolean {:js/str "boolean"},
             :number {:js/str "number"},
             :bigint {:js/str "bigint"},
             :string {:js/str "string"}}
            k))]
    [js/typeof
     (fn [v]
       (let [k (js/kind-of v)]
         (if (= k :function)
           {:js/str "function"}
           (if (= k :null)
             {:js/str "object"}
             (if (= k :object) {:js/str "object"} (js/kind-name k))))))]
    [js/to-string
     (fn [v]
       (let [k (js/kind-of v)]
         (if (= k :string)
           v
           (if (= k :number)
             (js/number->string v)
             (if (= k :bigint)
               (js/bigint->string v)
               (if (= k :boolean)
                 (if v {:js/str "true"} {:js/str "false"})
                 (if (= k :undefined)
                   {:js/str "undefined"}
                   (if (= k :null)
                     {:js/str "null"}
                     (js/to-string (js/to-primitive v :string))))))))))]
    [js/to-property-key (fn [v] (js/to-string (js/to-primitive v :string)))]
    [js/to-boolean
     (fn [v]
       (let [k (js/kind-of v)]
         (if (= k :boolean)
           v
           (if (= k :number)
             (let [x (data/float-value v)]
               (if (<= x x) (not (js/zero-d? x)) false))
             (if (= k :string)
               (if (nil? (get v :js/str)) true (not (= (get v :js/str) "")))
               (if (= k :bigint)
                 (not (= (integer/compare v 0) 0))
                 (if (= k :undefined) false (if (= k :null) false true))))))))]

    ;; ------------------------------------------------------- string to number
    [js/ws?
     (fn [c]
       (if (if (<= 9 c) (<= c 13) false)
         true
         (if (= c 32)
           true
           (if (= c 160)
             true
             (if (= c 5760)
               true
               (if (if (<= 8192 c) (<= c 8202) false)
                 true
                 (if (= c 8232)
                   true
                   (if (= c 8233)
                     true
                     (if (= c 8239)
                       true
                       (if (= c 8287)
                         true
                         (if (= c 12288) true (= c 65279))))))))))))]
    [js/trim-start
     (fn [cs i]
       (if (< i (data/count cs))
         (if (js/ws? (get cs i)) (js/trim-start cs (+ i 1)) i)
         i))]
    [js/trim-end
     (fn [cs j]
       (if (< 0 j) (if (js/ws? (get cs (- j 1))) (js/trim-end cs (- j 1)) j) j))]
    [js/digit-value
     (fn [c]
       (if (if (<= 48 c) (<= c 57) false)
         (- c 48)
         (if (if (<= 65 c) (<= c 90) false)
           (- c 55)
           (if (if (<= 97 c) (<= c 122) false) (- c 87) -1))))]
    [js/all-digits?
     (fn [cs i j radix]
       (if (< i j)
         (let [d (js/digit-value (get cs i))]
           (if (if (<= 0 d) (< d radix) false)
             (js/all-digits? cs (+ i 1) j radix)
             false))
         true))]
    [js/scan-digits
     (fn [cs i j]
       (if (< i j)
         (let [c (get cs i)]
           (if (if (<= 48 c) (<= c 57) false) (js/scan-digits cs (+ i 1) j) i))
         i))]
    [js/parse-radix
     ;; the exact integer of the digits [i, j) in radix, nil unless all valid
     (fn [cs i j radix]
       (if (< i j)
         (if (js/all-digits? cs i j radix)
           (js/int-result
             (integer/parse (data/code-points->str (data/subvec cs i j)) radix))
           nil)
         nil))]
    [js/radix-of
     (fn [c0 c1]
       (if (= c0 48)
         (if (if (= c1 120) true (= c1 88))
           16
           (if (if (= c1 111) true (= c1 79))
             8
             (if (if (= c1 98) true (= c1 66)) 2 0)))
         0))]
    [js/int->float
     ;; a non-negative or negative exact integer as float64 content
     (fn [n]
       (let [f (integer/to-float n)]
         (if (= f :yin.vm.integer/float-overflow)
           (if (< (integer/compare n 0) 0)
             (data/float64 (data/float-value ##-Inf))
             (data/float64 (data/float-value ##Inf)))
           (data/float64 f))))]
    [js/build-decimal
     (fn [cs s a f0 b ds e-end negative has-exp eneg]
       (let [digits (data/code-points->str
                      (data/into (data/subvec cs s a) (data/subvec cs f0 b)))
             e (if has-exp
                 (let [m (integer/parse
                           (data/code-points->str (data/subvec cs ds e-end))
                           10)]
                   (if eneg (integer/neg m) m))
                 0)
             r (integer/decimal->float digits (integer/sub e (- b f0)))]
         (data/float64 (if negative (* (data/float-value -1) r) r))))]
    [js/decimal-literal
     ;; StrDecimalLiteral over [i, j): float64 content, nil on a syntax error
     (fn [cs i j]
       (let [c0 (get cs i -1)
             negative (= c0 45)
             s (if (if negative true (= c0 43)) (+ i 1) i)
             a (js/scan-digits cs s j)
             has-dot (if (< a j) (= (get cs a) 46) false)
             f0 (if has-dot (+ a 1) a)
             b (if has-dot (js/scan-digits cs f0 j) a)
             c (if (< b j) (get cs b) -1)
             has-exp (if (= c 101) true (= c 69))
             es (if has-exp (+ b 1) b)
             ec (if has-exp (get cs es -1) -1)
             eneg (= ec 45)
             ds (if (if eneg true (= ec 43)) (+ es 1) es)
             e-end (if has-exp (js/scan-digits cs ds j) b)
             ok (if (< 0 (+ (- a s) (- b f0)))
                  (if has-exp (if (< ds e-end) (= e-end j) false) (= b j))
                  false)]
         (if ok (js/build-decimal cs s a f0 b ds e-end negative has-exp eneg) nil)))]
    [js/number-literal
     (fn [cs i j]
       (let [radix (if (< (+ i 1) j) (js/radix-of (get cs i) (get cs (+ i 1))) 0)
             t (data/code-points->str (data/subvec cs i j))]
         (if (< 0 radix)
           (let [n (js/parse-radix cs (+ i 2) j radix)]
             (if (nil? n) (js/nan) (js/int->float n)))
           (if (= t "Infinity")
             (data/float64 (data/float-value ##Inf))
             (if (= t "+Infinity")
               (data/float64 (data/float-value ##Inf))
               (if (= t "-Infinity")
                 (data/float64 (data/float-value ##-Inf))
                 (let [r (js/decimal-literal cs i j)]
                   (if (nil? r) (js/nan) r))))))))]
    [js/string->number
     (fn [s]
       (if (nil? (get s :js/str))
         (js/nan)
         (let [cs (data/str->code-points (get s :js/str))
               i (js/trim-start cs 0)
               j (js/trim-end cs (data/count cs))]
           (if (<= j i) (data/float64 0) (js/number-literal cs i j)))))]
    [js/bigint-literal
     (fn [cs i j]
       (let [c0 (get cs i -1)
             radix (if (< (+ i 1) j) (js/radix-of c0 (get cs (+ i 1))) 0)]
         (if (< 0 radix)
           (js/parse-radix cs (+ i 2) j radix)
           (let [negative (= c0 45)
                 s (if (if negative true (= c0 43)) (+ i 1) i)
                 r (js/parse-radix cs s j 10)]
             (if (nil? r) nil (if negative (integer/neg r) r))))))]
    [js/string->bigint
     ;; StringToBigInt: the exact integer, nil when the text is not one
     (fn [s]
       (if (nil? (get s :js/str))
         nil
         (let [cs (data/str->code-points (get s :js/str))
               i (js/trim-start cs 0)
               j (js/trim-end cs (data/count cs))]
           (if (<= j i) 0 (js/bigint-literal cs i j)))))]
    [js/to-number
     (fn [v]
       (let [k (js/kind-of v)]
         (if (= k :number)
           v
           (if (= k :string)
             (js/string->number v)
             (if (= k :undefined)
               (js/nan)
               (if (= k :null)
                 (data/float64 0)
                 (if (= k :boolean)
                   (if v (data/float64 1) (data/float64 0))
                   (if (= k :bigint)
                     (js/throw-type-error
                       {:js/str "Cannot convert a BigInt value to a number"})
                     (js/to-number (js/to-primitive v :number))))))))))]
    [js/to-numeric-prim
     (fn [p] (if (= (js/kind-of p) :bigint) p (js/to-number p)))]
    [js/to-numeric
     (fn [v] (js/to-numeric-prim (js/to-primitive v :number)))]
    [js/to-integer
     ;; ToIntegerOrInfinity clamped to +-2^32: no string index reaches
     ;; farther, and the result is a bare host integer on every host
     (fn [v]
       (let [x (data/float-value (js/to-number v))]
         (if (<= x x)
           (if (< x (data/float-value -4294967296))
             -4294967296
             (if (> x (data/float-value 4294967296))
               4294967296
               (js/int-result (integer/from-float x))))
           0)))]
    [js/wrap32
     (fn [n]
       (let [m (get (integer/floor-div-mod n 4294967296) 1)]
         (if (<= 2147483648 m) (- m 4294967296) m)))]
    [js/uint32-bare
     (fn [x]
       (if (js/finite? x)
         (get (integer/floor-div-mod (integer/from-float x) 4294967296) 1)
         0))]
    [js/int32-bare
     (fn [x]
       (let [m (js/uint32-bare x)]
         (if (<= 2147483648 m) (- m 4294967296) m)))]
    [js/to-int32
     (fn [v] (data/float64 (js/int32-bare (data/float-value (js/to-number v)))))]
    [js/to-uint32
     (fn [v]
       (data/float64 (js/uint32-bare (data/float-value (js/to-number v)))))]

    ;; ---------------------------------------------------------- arithmetic
    ;; Number arms unwrap float64 content with `data/float-value`, compute on
    ;; bare host doubles and rewrap with `data/float64`, no yield between.
    [js/mix-error
     (fn []
       (js/throw-type-error
         {:js/str "Cannot mix BigInt and other types, use explicit conversions"}))]
    [js/numeric-op
     (fn [numop bigop x y]
       (if (= (js/kind-of x) :number)
         (if (= (js/kind-of y) :number) (numop x y) (js/mix-error))
         (if (= (js/kind-of y) :number) (js/mix-error) (bigop x y))))]
    [js/binary
     (fn [numop bigop a b]
       (let [x (js/to-numeric a)
             y (js/to-numeric b)]
         (js/numeric-op numop bigop x y)))]
    [js/fdiv
     ;; x / y on host doubles with IEEE results, a zero divisor included
     (fn [x y]
       (if (js/zero-d? y)
         (if (if (<= x x) (not (js/zero-d? x)) false)
           (if (= (< x 0) (js/neg-zero-d? y))
             (data/float-value ##Inf)
             (data/float-value ##-Inf))
           (js/nan-raw))
         (/ x y)))]
    [js/fmod-pos
     ;; fmod for doubles x >= 0, y > 0, exactly: each subtraction is of
     ;; values within a factor of two (Sterbenz), as in long division
     (fn [x y]
       (if (< x y)
         x
         (let [r (js/fmod-pos x (+ y y))]
           (if (< r y) r (- r y)))))]
    [js/num-add
     (fn [a b] (data/float64 (+ (data/float-value a) (data/float-value b))))]
    [js/num-sub
     (fn [a b] (data/float64 (- (data/float-value a) (data/float-value b))))]
    [js/num-mul
     (fn [a b] (data/float64 (* (data/float-value a) (data/float-value b))))]
    [js/num-div
     (fn [a b]
       (data/float64 (js/fdiv (data/float-value a) (data/float-value b))))]
    [js/float-mod
     ;; x % y as ECMAScript computes it: the exact fmod, the sign of the
     ;; dividend (a zero result keeps it); no quotient is formed
     (fn [a b]
       (let [x (data/float-value a)
             y (data/float-value b)]
         (if (if (<= x x) (<= y y) false)
           (if (js/finite? x)
             (if (js/zero-d? y)
               (js/nan)
               (if (js/finite? y)
                 (if (js/zero-d? x)
                   a
                   (let [m (js/fmod-pos (js/habs x) (js/habs y))]
                     (data/float64
                       (if (< x 0) (* (data/float-value -1) m) m))))
                 a))
             (js/nan))
           (js/nan))))]
    [js/fpow
     ;; float base ** e for an exact integer e >= 0, by squaring
     (fn [base e]
       (if (= e 0)
         (data/float-value 1)
         (let [h (js/fpow base (integer/shift-right e 1))
               hh (* h h)]
           (if (= (integer/bit-and e 1) 0) hh (* hh base)))))]
    [js/neg-power
     ;; x ** -n for an exact integer n > 0: 1 / x^n while x^n is finite, so
     ;; every exact case is exact; when x^n overflows, the reciprocals of its
     ;; two halves, so 2 ** -1074 is 5e-324
     (fn [x n]
       (let [p (js/fpow x n)]
         (if (js/finite? p)
           (js/fdiv (data/float-value 1) p)
           (let [h (integer/shift-right n 1)]
             (* (js/fdiv (data/float-value 1) (js/fpow x h))
                (js/fdiv (data/float-value 1)
                         (js/fpow x (integer/sub n h))))))))]
    [js/pow-fractional
     ;; x ** y for a finite y that is not an integer
     (fn [x y]
       (if (= x (data/float-value 1))
         (data/float64 1)
         (if (js/zero-d? x)
           (if (> y 0) (data/float64 0) (data/float64 (data/float-value ##Inf)))
           (if (< x 0)
             (if (= x (data/float-value ##-Inf))
               (if (> y 0) (data/float64 (data/float-value ##Inf)) (data/float64 0))
               (js/nan))
             (if (= x (data/float-value ##Inf))
               (if (> y 0) (data/float64 (data/float-value ##Inf)) (data/float64 0))
               (js/throw-type-error
                 {:js/str "** with a fractional exponent is not supported yet (yang.typescript known gap G-pow-frac)"}))))))]
    [js/pow-finite
     (fn [x y]
       (let [e (js/int-result (integer/from-float y))]
         (if (= (integer/compare-float e y) 0)
           (data/float64 (if (< (integer/compare e 0) 0)
                           (js/neg-power x (integer/neg e))
                           (js/fpow x e)))
           (js/pow-fractional x y))))]
    [js/num-pow
     (fn [a b]
       (let [x (data/float-value a)
             y (data/float-value b)]
         (if (not (<= y y))
           (js/nan)
           (if (js/zero-d? y)
             (data/float64 1)
             (if (not (<= x x))
               a
               (if (js/finite? y)
                 (js/pow-finite x y)
                 (if (= (js/habs x) (data/float-value 1))
                   (js/nan)
                   (if (if (> (js/habs x) (data/float-value 1)) (> y 0) (< y 0))
                     (data/float64 (data/float-value ##Inf))
                     (data/float64 0)))))))))]
    [js/big-add (fn [a b] (js/int-result (integer/add a b)))]
    [js/big-sub (fn [a b] (js/int-result (integer/sub a b)))]
    [js/big-mul (fn [a b] (js/int-result (integer/mul a b)))]
    [js/big-div
     (fn [a b]
       (if (= (integer/compare b 0) 0)
         (js/throw-range-error {:js/str "Division by zero"})
         (get (js/int-result (integer/quot-rem a b)) 0)))]
    [js/big-mod
     (fn [a b]
       (if (= (integer/compare b 0) 0)
         (js/throw-range-error {:js/str "Division by zero"})
         (get (js/int-result (integer/quot-rem a b)) 1)))]
    [js/big-pow
     (fn [a b]
       (if (< (integer/compare b 0) 0)
         (js/throw-range-error {:js/str "Exponent must be non-negative"})
         (js/int-result (integer/pow a b))))]
    [js/add
     (fn [a b]
       (let [pa (js/to-primitive a :default)
             pb (js/to-primitive b :default)]
         (if (if (js/str? pa) true (js/str? pb))
           (js/str-concat (js/to-string pa) (js/to-string pb))
           (js/numeric-op js/num-add
                          js/big-add
                          (js/to-numeric-prim pa)
                          (js/to-numeric-prim pb)))))]
    [js/sub (fn [a b] (js/binary js/num-sub js/big-sub a b))]
    [js/mul (fn [a b] (js/binary js/num-mul js/big-mul a b))]
    [js/div (fn [a b] (js/binary js/num-div js/big-div a b))]
    [js/mod (fn [a b] (js/binary js/float-mod js/big-mod a b))]
    [js/pow (fn [a b] (js/binary js/num-pow js/big-pow a b))]
    ;; unary - negates a float by (* -1.0 x), not one-argument `-`, which is
    ;; (0 - x) on ClojureDart and turns -(0) into +0
    [js/neg
     (fn [v]
       (let [x (js/to-numeric v)]
         (if (= (js/kind-of x) :number)
           (data/float64 (* (data/float-value -1) (data/float-value x)))
           (js/int-result (integer/neg x)))))]
    [js/pos (fn [v] (js/to-number v))]
    [js/num-bit-and
     (fn [x y]
       (data/float64 (integer/bit-and (js/int32-bare (data/float-value x))
                                      (js/int32-bare (data/float-value y)))))]
    [js/num-bit-or
     (fn [x y]
       (data/float64 (integer/bit-or (js/int32-bare (data/float-value x))
                                     (js/int32-bare (data/float-value y)))))]
    [js/num-bit-xor
     (fn [x y]
       (data/float64 (integer/bit-xor (js/int32-bare (data/float-value x))
                                      (js/int32-bare (data/float-value y)))))]
    [js/big-bit-and (fn [a b] (js/int-result (integer/bit-and a b)))]
    [js/big-bit-or (fn [a b] (js/int-result (integer/bit-or a b)))]
    [js/big-bit-xor (fn [a b] (js/int-result (integer/bit-xor a b)))]
    [js/bit-and (fn [a b] (js/binary js/num-bit-and js/big-bit-and a b))]
    [js/bit-or (fn [a b] (js/binary js/num-bit-or js/big-bit-or a b))]
    [js/bit-xor (fn [a b] (js/binary js/num-bit-xor js/big-bit-xor a b))]
    [js/bit-not
     (fn [v]
       (let [x (js/to-numeric v)]
         (if (= (js/kind-of x) :number)
           (data/float64 (integer/bit-not (js/int32-bare (data/float-value x))))
           (js/int-result (integer/bit-not x)))))]
    [js/shift-count
     (fn [y] (get (integer/floor-div-mod (js/uint32-bare (data/float-value y)) 32) 1))]
    [js/num-shl
     (fn [x y]
       (data/float64
         (js/wrap32 (integer/shift-left (js/int32-bare (data/float-value x))
                                        (js/shift-count y)))))]
    [js/num-sar
     (fn [x y]
       (data/float64
         (integer/shift-right (js/int32-bare (data/float-value x))
                              (js/shift-count y))))]
    [js/num-shr
     (fn [x y]
       (data/float64
         (integer/shift-right (js/uint32-bare (data/float-value x))
                              (js/shift-count y))))]
    [js/big-shl
     (fn [a b]
       (if (< (integer/compare b 0) 0)
         (js/int-result (integer/shift-right a (integer/neg b)))
         (js/int-result (integer/shift-left a b))))]
    [js/big-sar
     (fn [a b]
       (if (< (integer/compare b 0) 0)
         (js/int-result (integer/shift-left a (integer/neg b)))
         (js/int-result (integer/shift-right a b))))]
    [js/big-shr
     (fn [a b]
       (js/throw-type-error
         {:js/str "BigInts have no unsigned right shift, use >> instead"}))]
    [js/shl (fn [a b] (js/binary js/num-shl js/big-shl a b))]
    [js/sar (fn [a b] (js/binary js/num-sar js/big-sar a b))]
    [js/shr (fn [a b] (js/binary js/num-shr js/big-shr a b))]

    ;; ---------------------------------------------------- equality and order
    [js/strict-eq
     (fn [a b]
       (let [k (js/kind-of a)]
         (if (= k (js/kind-of b))
           (if (= k :number)
             (= (data/float-value a) (data/float-value b))
             (if (= k :bigint) (= (integer/compare a b) 0) (= a b)))
           false)))]
    [js/same-value
     (fn [a b]
       (let [k (js/kind-of a)]
         (if (= k (js/kind-of b))
           (if (= k :number)
             (data/content= a b)
             (if (= k :bigint) (= (integer/compare a b) 0) (= a b)))
           false)))]
    [js/same-value-zero
     (fn [a b]
       (let [k (js/kind-of a)]
         (if (= k (js/kind-of b))
           (if (= k :number)
             (let [x (data/float-value a)
                   y (data/float-value b)]
               (if (<= x x) (= x y) (not (<= y y))))
             (if (= k :bigint) (= (integer/compare a b) 0) (= a b)))
           false)))]
    [js/big-str-eq
     (fn [big s]
       (let [n (js/string->bigint s)]
         (if (nil? n) false (= (integer/compare big n) 0))))]
    [js/big-num-eq
     (fn [big num]
       (let [x (data/float-value num)]
         (if (js/finite? x) (= (integer/compare-float big x) 0) false)))]
    [js/loose-eq
     (fn [a b]
       (let [ta (js/ty (js/kind-of a))
             tb (js/ty (js/kind-of b))]
         (if (= ta tb)
           (js/strict-eq a b)
           (if (if (= ta :undefined)
                 (= tb :null)
                 (if (= ta :null) (= tb :undefined) false))
             true
             (if (if (= ta :number) (= tb :string) false)
               (js/strict-eq a (js/to-number b))
               (if (if (= ta :string) (= tb :number) false)
                 (js/strict-eq (js/to-number a) b)
                 (if (if (= ta :bigint) (= tb :string) false)
                   (js/big-str-eq a b)
                   (if (if (= ta :string) (= tb :bigint) false)
                     (js/big-str-eq b a)
                     (if (= ta :boolean)
                       (js/loose-eq (js/to-number a) b)
                       (if (= tb :boolean)
                         (js/loose-eq a (js/to-number b))
                         (if (if (= tb :object) (js/prim-type? ta) false)
                           (js/loose-eq a (js/to-primitive b :default))
                           (if (if (= ta :object) (js/prim-type? tb) false)
                             (js/loose-eq (js/to-primitive a :default) b)
                             (if (if (= ta :bigint) (= tb :number) false)
                               (js/big-num-eq a b)
                               (if (if (= ta :number) (= tb :bigint) false)
                                 (js/big-num-eq b a)
                                 false))))))))))))))]
    [js/less-big-num
     ;; the bigint b < the number x: :true :false :undef
     (fn [b x]
       (if (not (<= x x))
         :undef
         (if (= x (data/float-value ##Inf))
           :true
           (if (= x (data/float-value ##-Inf))
             :false
             (if (= (integer/compare-float b x) -1) :true :false)))))]
    [js/less-num-big
     ;; the number x < the bigint b
     (fn [x b]
       (if (not (<= x x))
         :undef
         (if (= x (data/float-value ##Inf))
           :false
           (if (= x (data/float-value ##-Inf))
             :true
             (if (= (integer/compare-float b x) 1) :true :false)))))]
    [js/less-num
     (fn [nx ny]
       (let [kx (js/kind-of nx)
             ky (js/kind-of ny)]
         (if (= kx :number)
           (if (= ky :number)
             (let [x (data/float-value nx)
                   y (data/float-value ny)]
               (if (<= x x) (if (<= y y) (if (< x y) :true :false) :undef) :undef))
             (js/less-num-big (data/float-value nx) ny))
           (if (= ky :number)
             (js/less-big-num nx (data/float-value ny))
             (if (= (integer/compare nx ny) -1) :true :false)))))]
    [js/less-prim
     (fn [px py]
       (if (if (js/str? px) (js/str? py) false)
         (if (= (js/str-compare px py) -1) :true :false)
         (if (if (= (js/kind-of px) :bigint) (js/str? py) false)
           (let [ny (js/string->bigint py)]
             (if (nil? ny)
               :undef
               (if (= (integer/compare px ny) -1) :true :false)))
           (if (if (js/str? px) (= (js/kind-of py) :bigint) false)
             (let [nx (js/string->bigint px)]
               (if (nil? nx)
                 :undef
                 (if (= (integer/compare nx py) -1) :true :false)))
             (js/less-num (js/to-numeric-prim px) (js/to-numeric-prim py))))))]
    [js/less
     ;; IsLessThan(x, y, leftFirst): :true :false or :undef
     (fn [x y left-first]
       (if left-first
         (let [px (js/to-primitive x :number)
               py (js/to-primitive y :number)]
           (js/less-prim px py))
         (let [py (js/to-primitive y :number)
               px (js/to-primitive x :number)]
           (js/less-prim px py))))]
    [js/lt (fn [a b] (= (js/less a b true) :true))]
    [js/gt (fn [a b] (= (js/less b a false) :true))]
    [js/le (fn [a b] (= (js/less b a false) :false))]
    [js/ge (fn [a b] (= (js/less a b true) :false))]

    ;; ------------------------------------------------------------ coercion
    [js/to-primitive
     (fn [v hint] (if (js/cell? v) (js/ordinary-to-primitive v hint) v))]
    [js/prim-try
     (fn [o name k]
       (let [m (js/get-prop o name)]
         (if (js/callable? m)
           (let [r (js/call m o [])] (if (js/cell? r) (k) r))
           (k))))]
    [js/ordinary-to-primitive
     (fn [o hint]
       (if (= hint :string)
         (js/prim-try
           o
           {:js/str "toString"}
           (fn []
             (js/prim-try
               o
               {:js/str "valueOf"}
               (fn []
                 (js/throw-type-error
                   {:js/str "Cannot convert object to primitive value"})))))
         (js/prim-try
           o
           {:js/str "valueOf"}
           (fn []
             (js/prim-try
               o
               {:js/str "toString"}
               (fn []
                 (js/throw-type-error
                   {:js/str "Cannot convert object to primitive value"})))))))]

    ;; ---------------------------------------------------- ordered own keys
    ;; {:index {key sl} :ints [..sorted..] :strs [..insertion..] :syms []}
    ;; Own-key order is the vectors, never a host map's order: integer-index
    ;; keys ascending, then string keys in insertion order, then symbols.
    [js/om-new (fn [] {:index {}, :ints [], :strs [], :syms []})]
    [js/om-get (fn [om key] (get (get om :index) key))]
    [js/all-digits-text?
     (fn [cs] (js/all-digits? cs 0 (data/count cs) 10))]
    [js/index-text
     ;; the array index a canonical decimal text names, else -1
     (fn [cs]
       (let [n (data/count cs)]
         (if (if (< 0 n) (<= n 10) false)
           (if (js/all-digits-text? cs)
             (if (if (= (get cs 0) 48) (< 1 n) false)
               -1
               (let [v (integer/parse (data/code-points->str cs) 10)]
                 (if (< v 4294967295) v -1)))
             -1)
           -1)))]
    [js/index-key-value
     (fn [key]
       (if (nil? (get key :js/str))
         -1
         (js/index-text (data/str->code-points (get key :js/str)))))]
    [js/ints-splice
     (fn [ints key i]
       (data/into (conj (data/subvec ints 0 i) key) (data/subvec ints i)))]
    [js/ints-insert
     (fn [ints key v i]
       (if (< i (data/count ints))
         (if (< v (js/index-key-value (get ints i)))
           (js/ints-splice ints key i)
           (js/ints-insert ints key v (+ i 1)))
         (conj ints key)))]
    [js/om-put
     (fn [om key sl]
       (let [om2 (assoc om :index (assoc (get om :index) key sl))]
         (if (nil? (get (get om :index) key))
           (let [i (js/index-key-value key)]
             (if (<= 0 i)
               (assoc om2 :ints (js/ints-insert (get om2 :ints) key i 0))
               (assoc om2 :strs (conj (get om2 :strs) key))))
           om2)))]
    [js/om-keys
     (fn [om]
       (data/into (data/into (get om :ints) (get om :strs)) (get om :syms)))]
    [js/slot
     (fn [v w e c]
       (assoc (assoc (assoc (assoc {} :value v) :writable w) :enumerable e)
              :configurable c))]

    ;; --------------------------------------------------- objects and reads
    [js/make-object
     (fn [proto]
       (cell/new (assoc (assoc (assoc (assoc {} :js/type :object) :proto proto)
                               :props (js/om-new))
                        :extensible? true)))]
    [js/define-own!
     (fn [o key sl]
       (let [c (cell/get o)]
         (cell/set! o (assoc c :props (js/om-put (get c :props) key sl)))))]
    [js/get-own
     (fn [o key] (js/om-get (get (cell/get o) :props) key))]
    [js/get-prop
     ;; [[Get]] over data descriptors and the prototype chain
     (fn [o key]
       (let [sl (js/get-own o key)]
         (if (nil? sl)
           (let [p (get (cell/get o) :proto)]
             (if (= p :js/null) :js/undefined (js/get-prop p key)))
           (get sl :value))))]
    [js/has-property
     (fn [o key]
       (if (nil? (js/get-own o key))
         (let [p (get (cell/get o) :proto)]
           (if (= p :js/null) false (js/has-property p key)))
         true))]
    [js/string-get
     (fn [s key]
       (if (= key {:js/str "length"})
         (data/float64 (js/str-unit-length s))
         (let [i (js/index-key-value key)]
           (if (if (<= 0 i) (< i (js/str-unit-length s)) false)
             (js/str-slice s i (+ i 1))
             (js/get-prop js.rt/str-proto key)))))]
    [js/get-value
     ;; a member read: primitive receivers read their intrinsic prototype
     (fn [v key]
       (let [k (js/kind-of v)]
         (if (if (= k :object) true (= k :function))
           (js/get-prop v key)
           (if (= k :string)
             (js/string-get v key)
             (if (= k :number)
               (js/get-prop js.rt/num-proto key)
               (if (= k :boolean)
                 (js/get-prop js.rt/bool-proto key)
                 (if (= k :bigint)
                   (js/get-prop js.rt/bigint-proto key)
                   (js/throw-type-error
                     (js/str-concat
                       {:js/str "Cannot read properties of "}
                       (js/str-concat
                         (js/to-string v)
                         (js/str-concat
                           {:js/str " (reading '"}
                           (js/str-concat (js/to-string key)
                                          {:js/str "')"}))))))))))))]

    ;; ------------------------------------------------------------ functions
    ;; A function cell holds {:js/type :function :code (fn [this args] ..)
    ;; :this-mode :strict|:lexical :name s :length n :props om :proto ref
    ;; :extensible? true}; every call is (js/call f this args).
    [js/make-function
     (fn [name length this-mode code]
       (let [f (cell/new
                 (assoc (assoc (assoc (assoc (assoc (assoc (assoc (assoc {}
                                                                         :js/type
                                                                         :function)
                                                                  :code code)
                                                           :this-mode this-mode)
                                                    :name name)
                                             :length length)
                                      :props (js/om-new))
                               :proto js.rt/fn-proto)
                        :extensible? true))]
         (do (js/define-own! f
                             {:js/str "length"}
                             (js/slot (data/float64 length) false false true))
             (js/define-own! f {:js/str "name"} (js/slot name false false true))
             f)))]
    [js/call
     (fn [f this args]
       (if (js/callable? f)
         (let [c (cell/get f)]
           ((get c :code)
            (if (= (get c :this-mode) :lexical) :js/undefined this)
            args))
         (js/throw-type-error {:js/str "value is not a function"})))]
    [js/construct
     (fn [f args]
       (if (js/callable? f)
         (let [c (cell/get f)]
           (if (= (get c :this-mode) :lexical)
             (js/throw-type-error {:js/str "value is not a constructor"})
             (let [pr (js/get-prop f {:js/str "prototype"})
                   o (js/make-object (if (js/cell? pr) pr js.rt/obj-proto))
                   r ((get c :code) o args)]
               (if (js/cell? r) r o))))
         (js/throw-type-error {:js/str "value is not a constructor"})))]

    ;; ---------------------------------------------------------- escapes
    ;; The dynamic context is one record in one cell, {:handlers}: every
    ;; capture point saves it and every escape restores it. The handler stack
    ;; is a chain of frames [tag payload rest n]: :handler frames hold the
    ;; continuation `throw` delivers to, :finally frames a thunk of one
    ;; argument (the thrown value, or undefined) that every exit through
    ;; them runs; n, the frame count, makes "unwind to here" an integer
    ;; comparison. A first pass through a capture point is told from a
    ;; re-entry by a flag cell, never by inspecting the continuation.
    [js/ctx (fn [hs] (assoc {} :handlers hs))]
    [js/rt-ctx (fn [] js.rt/ctx)]
    [js/restore! (fn [saved] (cell/set! js.rt/ctx saved))]
    [js/handlers (fn [] (get (cell/get js.rt/ctx) :handlers))]
    [js/set-handlers!
     (fn [hs] (cell/set! js.rt/ctx (assoc (cell/get js.rt/ctx) :handlers hs)))]
    [js/frame-depth (fn [hs] (if (nil? hs) 0 (get hs 3)))]
    [js/frame
     (fn [tag payload rest]
       (conj (conj (conj (conj [] tag) payload) rest)
             (+ 1 (js/frame-depth rest))))]
    [js/pop-frame
     (fn [hs]
       (cell/set! js.rt/ctx (assoc (cell/get js.rt/ctx) :handlers (get hs 2))))]
    [js/throw
     (fn [e]
       (let [hs (js/handlers)]
         (if (nil? hs)
           (:js/no-handler e)
           (if (= (get hs 0) :finally)
             (do (js/pop-frame hs) ((get hs 1) e) (js/throw e))
             ((get hs 1) e)))))]
    [js/unwind-to
     ;; pop frames above frame count n, running each finally thunk
     (fn [n]
       (let [hs (js/handlers)]
         (if (nil? hs)
           :js/undefined
           (if (<= (get hs 3) n)
             :js/undefined
             (do (js/pop-frame hs)
                 (if (= (get hs 0) :finally)
                   ((get hs 1) :js/undefined)
                   :js/undefined)
                 (js/unwind-to n))))))]
    [js/try
     (fn [body handler]
       (let [saved (cell/get js.rt/ctx)
             flag (cell/new :first)]
         ((fn [r]
            (if (= (cell/get flag) :first)
              (do (cell/set! flag :re-entered)
                  (js/set-handlers! (js/frame :handler r (get saved :handlers)))
                  (let [v (body)] (do (js/restore! saved) v)))
              (do (js/restore! saved) (handler r))))
          (%capture))))]
    [js/try-finally
     ;; normal exit runs `fin` here; throw and escapes run it as they
     ;; unwind through the :finally frame
     (fn [body fin]
       (let [saved (cell/get js.rt/ctx)]
         (do (js/set-handlers! (js/frame :finally fin (get saved :handlers)))
             (let [v (body)]
               (do (js/restore! saved) (fin :js/undefined) v)))))]
    [js/call-ec
     (fn [f]
       (let [saved (cell/get js.rt/ctx)
             flag (cell/new :first)]
         ((fn [r]
            (if (= (cell/get flag) :first)
              (do (cell/set! flag :re-entered)
                  (f (fn [x]
                       (do (js/unwind-to (js/frame-depth (get saved :handlers)))
                           (js/restore! saved)
                           (r x)))))
              r))
          (%capture))))]

    ;; ----------------------------------------------------------------- errors
    [js/make-error
     (fn [proto msg]
       (let [e (cell/new (assoc (assoc (assoc (assoc (assoc {} :js/type :object)
                                                     :js/class :error)
                                              :proto proto)
                                       :props (js/om-new))
                                :extensible? true))]
         (do (if (= msg :js/undefined)
               :js/undefined
               (js/define-own! e
                               {:js/str "message"}
                               (js/slot (js/to-string msg) true false true)))
             e)))]
    [js/throw-error (fn [proto msg] (js/throw (js/make-error proto msg)))]
    [js/throw-type-error (fn [msg] (js/throw-error js.rt/type-error-proto msg))]
    [js/throw-reference-error
     (fn [msg] (js/throw-error js.rt/reference-error-proto msg))]
    [js/throw-range-error
     (fn [msg] (js/throw-error js.rt/range-error-proto msg))]
    [js/throw-syntax-error
     (fn [msg] (js/throw-error js.rt/syntax-error-proto msg))]
    [js/error-to-string
     (fn [this _args]
       (if (js/cell? this)
         (let [n (js/get-prop this {:js/str "name"})
               m (js/get-prop this {:js/str "message"})
               nm (if (= n :js/undefined) {:js/str "Error"} (js/to-string n))
               ms (if (= m :js/undefined) {:js/str ""} (js/to-string m))]
           (if (= nm {:js/str ""})
             ms
             (if (= ms {:js/str ""})
               nm
               (js/str-concat nm (js/str-concat {:js/str ": "} ms)))))
         (js/throw-type-error
           {:js/str "Error.prototype.toString called on a non-object"})))]

    ;; --------------------------------------------- bindings and the global
    [js/lexical-get
     (fn [x nm]
       (let [v (cell/get x)]
         (if (= v :js/tdz)
           (js/throw-reference-error
             (js/str-concat
               {:js/str "Cannot access '"}
               (js/str-concat nm {:js/str "' before initialization"})))
           v)))]
    [js/const-assign!
     (fn []
       (js/throw-type-error {:js/str "Assignment to constant variable."}))]
    [js/global-get
     (fn [name]
       (if (js/has-property js.rt/global name)
         (js/get-prop js.rt/global name)
         (js/throw-reference-error
           (js/str-concat name {:js/str " is not defined"}))))]
    [js/global-typeof
     (fn [name]
       (if (js/has-property js.rt/global name)
         (js/typeof (js/get-prop js.rt/global name))
         {:js/str "undefined"}))]

    ;; ------------------------------------------------------------ boundary
    ;; A console.log call appends one vector of snapshots: primitives as
    ;; they are, a function as {:js/snap :function :name n}, any other cell
    ;; as {:js/snap :object} (objects are T2).
    [js/snapshot
     (fn [v]
       (let [k (js/kind-of v)]
         (if (= k :function)
           (assoc (assoc {} :js/snap :function)
                  :name (js/get-prop v {:js/str "name"}))
           (if (= k :object) {:js/snap :object} v))))]
    [js/snapshots
     (fn [args i acc]
       (if (< i (data/count args))
         (js/snapshots args (+ i 1) (conj acc (js/snapshot (get args i))))
         acc))]
    [js/log!
     (fn [args]
       (do (cell/set! js.rt/out
                      (conj (cell/get js.rt/out) (js/snapshots args 0 [])))
           :js/undefined))]
    [js/uncaught
     (fn [e]
       (if (js/cell? e)
         (if (= (get (cell/get e) :js/class) :error)
           (assoc (assoc {} :name (js/to-string (js/get-prop e {:js/str "name"})))
                  :message
                  (js/to-string (js/get-prop e {:js/str "message"})))
           (assoc {} :value (js/snapshot e)))
         (assoc {} :value e)))]
    [js/run-main
     ;; resets the out cell and the context, runs `body` (a function of the
     ;; module `this`) under the outermost try, and answers
     ;; {:js/out [[snapshot ...] ...] :js/uncaught snapshot-or-nil}
     (fn [body]
       (do (cell/set! js.rt/out [])
           (cell/set! js.rt/ctx (js/ctx nil))
           (let [u (js/try (fn [] (do (body :js/undefined) nil))
                           (fn [e] (js/uncaught e)))]
             (assoc (assoc {} :js/out (cell/get js.rt/out)) :js/uncaught u))))]

    ;; ------------------------------------------------------------ intrinsics
    ;; Function code of the intrinsic methods: (fn [this args] ..). `init!`
    ;; wraps each in a function cell on its prototype.
    [js/this-string
     (fn [this]
       (if (= this :js/undefined)
         (js/throw-type-error
           {:js/str "String.prototype method called on null or undefined"})
         (if (= this :js/null)
           (js/throw-type-error
             {:js/str "String.prototype method called on null or undefined"})
           (js/to-string this))))]
    [js/imin (fn [a b] (if (< a b) a b))]
    [js/imax (fn [a b] (if (< a b) b a))]
    [js/rel-clamp
     ;; a relative index (negative counts from the end) clamped into [0, len]
     (fn [i len] (if (< i 0) (js/imax 0 (+ len i)) (js/imin i len)))]
    [js/string-char-code-at
     (fn [this args]
       (let [s (js/this-string this)
             idx (js/to-integer (js/arg args 0))]
         (if (if (< idx 0) true (<= (js/str-unit-length s) idx))
           (js/nan)
           (data/float64 (js/str-unit-at s idx)))))]
    [js/string-code-point-at
     (fn [this args]
       (let [s (js/this-string this)
             idx (js/to-integer (js/arg args 0))
             n (js/str-unit-length s)]
         (if (if (< idx 0) true (<= n idx))
           :js/undefined
           (let [u (js/str-unit-at s idx)]
             (if (if (<= 55296 u) (<= u 56319) false)
               (if (< (+ idx 1) n)
                 (let [w (js/str-unit-at s (+ idx 1))]
                   (if (if (<= 56320 w) (<= w 57343) false)
                     (data/float64
                       (+ (+ (* (- u 55296) 1024) (- w 56320)) 65536))
                     (data/float64 u)))
                 (data/float64 u))
               (data/float64 u))))))]
    [js/string-at
     (fn [this args]
       (let [s (js/this-string this)
             n (js/str-unit-length s)
             i (js/to-integer (js/arg args 0))
             k (if (< i 0) (+ n i) i)]
         (if (if (< k 0) true (<= n k))
           :js/undefined
           (js/str-slice s k (+ k 1)))))]
    [js/string-slice
     (fn [this args]
       (let [s (js/this-string this)
             n (js/str-unit-length s)
             from (js/rel-clamp (js/to-integer (js/arg args 0)) n)
             e (js/arg args 1)
             to (if (= e :js/undefined) n (js/rel-clamp (js/to-integer e) n))]
         (if (< from to) (js/str-slice s from to) {:js/str ""})))]
    [js/string-substring
     (fn [this args]
       (let [s (js/this-string this)
             n (js/str-unit-length s)
             a (js/imin (js/imax (js/to-integer (js/arg args 0)) 0) n)
             e (js/arg args 1)
             b (if (= e :js/undefined)
                 n
                 (js/imin (js/imax (js/to-integer e) 0) n))]
         (js/str-slice s (js/imin a b) (js/imax a b))))]
    [js/string-index-of
     (fn [this args]
       (let [s (js/this-string this)
             t (js/to-string (js/arg args 0))
             n (js/str-unit-length s)
             idx (js/imin (js/imax (js/to-integer (js/arg args 1)) 0) n)]
         (data/float64 (js/units-index (js/units-of s) (js/units-of t) idx))))]
    [js/concat-args
     (fn [acc args i]
       (if (< i (data/count args))
         (js/concat-args (js/str-concat acc (js/to-string (get args i)))
                         args
                         (+ i 1))
         acc))]
    [js/string-concat
     (fn [this args] (js/concat-args (js/this-string this) args 0))]
    [js/this-number
     (fn [this]
       (if (= (js/kind-of this) :number)
         this
         (js/throw-type-error
           {:js/str "Number.prototype.toString requires that 'this' be a Number"})))]
    [js/radix-arg
     (fn [r]
       (if (= r :js/undefined)
         10
         (let [n (js/to-integer r)]
           (if (if (< n 2) true (< 36 n))
             (js/throw-range-error
               {:js/str "toString() radix must be between 2 and 36"})
             n))))]
    [js/number-to-string
     (fn [this args]
       (let [x (js/this-number this)
             radix (js/radix-arg (js/arg args 0))
             d (data/float-value x)]
         (if (= radix 10)
           (js/number->string x)
           (if (js/finite? d)
             (let [e (integer/from-float d)]
               (if (= (integer/compare-float e d) 0)
                 (assoc {} :js/str (integer/format e radix))
                 (js/throw-type-error
                   {:js/str "toString with a fractional value and a radix other than 10 is not supported yet (yang.typescript known gap G-radix-frac)"})))
             (js/number->string x)))))]
    [js/number-value-of (fn [this args] (js/this-number this))]
    [js/this-boolean
     (fn [this]
       (if (= (js/kind-of this) :boolean)
         this
         (js/throw-type-error
           {:js/str "Boolean.prototype method requires that 'this' be a Boolean"})))]
    [js/boolean-to-string
     (fn [this args]
       (if (js/this-boolean this) {:js/str "true"} {:js/str "false"}))]
    [js/boolean-value-of (fn [this args] (js/this-boolean this))]
    [js/this-bigint
     (fn [this]
       (if (= (js/kind-of this) :bigint)
         this
         (js/throw-type-error
           {:js/str "BigInt.prototype method requires that 'this' be a BigInt"})))]
    [js/bigint-to-string
     (fn [this args]
       (let [b (js/this-bigint this)
             radix (js/radix-arg (js/arg args 0))]
         (assoc {} :js/str (js/int-result (integer/format b radix)))))]
    [js/bigint-value-of (fn [this args] (js/this-bigint this))]
    [js/object-to-string (fn [this args] {:js/str "[object Object]"})]
    [js/object-value-of (fn [this args] this)]
    [js/console-log (fn [this args] (js/log! args))]
    [js/string-fn
     (fn [this args]
       (if (= (data/count args) 0) {:js/str ""} (js/to-string (get args 0))))]
    [js/big->number
     (fn [b] (js/int->float b))]
    [js/number-fn
     (fn [this args]
       (if (= (data/count args) 0)
         (data/float64 0)
         (let [p (js/to-numeric (get args 0))]
           (if (= (js/kind-of p) :bigint) (js/big->number p) p))))]
    [js/boolean-fn (fn [this args] (js/to-boolean (js/arg args 0)))]
    [js/number->bigint
     (fn [v]
       (let [x (data/float-value v)]
         (if (js/finite? x)
           (let [e (integer/from-float x)]
             (if (= (integer/compare-float e x) 0)
               e
               (js/throw-range-error
                 (js/str-concat
                   {:js/str "The number "}
                   (js/str-concat
                     (js/number->string v)
                     {:js/str " cannot be converted to a BigInt because it is not an integer"})))))
           (js/throw-range-error
             (js/str-concat
               {:js/str "The number "}
               (js/str-concat
                 (js/number->string v)
                 {:js/str " cannot be converted to a BigInt because it is not an integer"}))))))]
    [js/bigint-fn
     (fn [this args]
       (let [v (js/to-primitive (js/arg args 0) :number)
             k (js/kind-of v)]
         (if (= k :number)
           (js/number->bigint v)
           (if (= k :bigint)
             v
             (if (= k :boolean)
               (if v 1 0)
               (if (= k :string)
                 (let [n (js/string->bigint v)]
                   (if (nil? n)
                     (js/throw-syntax-error
                       (js/str-concat
                         {:js/str "Cannot convert "}
                         (js/str-concat v {:js/str " to a BigInt"})))
                     n))
                 (js/throw-type-error
                   (js/str-concat
                     {:js/str "Cannot convert "}
                     (js/str-concat (js/to-string v)
                                    {:js/str " to a BigInt"})))))))))]
    [js/def-method!
     (fn [o name length code]
       (js/define-own!
         o
         (assoc {} :js/str name)
         (js/slot (js/make-function (assoc {} :js/str name) length :strict code)
                  true
                  false
                  true)))]
    [js/define-global!
     (fn [name value]
       (js/define-own! js.rt/global
                       (assoc {} :js/str name)
                       (js/slot value true false true)))]
    [js/install-ctor!
     ;; a constructor function cell wired to its prototype both ways
     (fn [name length code proto]
       (let [f (js/make-function (assoc {} :js/str name) length :strict code)]
         (do (js/define-own! f
                             {:js/str "prototype"}
                             (js/slot proto false false false))
             (js/define-own! proto
                             {:js/str "constructor"}
                             (js/slot f true false true))
             (js/define-global! name f)
             f)))]])


(def ^:private define core/define)


(def ^:private error-classes
  "[name proto-key base-proto-key]: the Error family, base first."
  [["Error" 'js.rt/error-proto 'js.rt/obj-proto]
   ["TypeError" 'js.rt/type-error-proto 'js.rt/error-proto]
   ["ReferenceError" 'js.rt/reference-error-proto 'js.rt/error-proto]
   ["RangeError" 'js.rt/range-error-proto 'js.rt/error-proto]
   ["SyntaxError" 'js.rt/syntax-error-proto 'js.rt/error-proto]])


(def ^:private primitive-protos
  "[proto-key converter-name converter-code]"
  [['js.rt/str-proto "String" 'js/string-fn]
   ['js.rt/num-proto "Number" 'js/number-fn]
   ['js.rt/bool-proto "Boolean" 'js/boolean-fn]
   ['js.rt/bigint-proto "BigInt" 'js/bigint-fn]])


(def ^:private methods-of
  "[proto-key [[name length code] ...]]: the intrinsic methods."
  [['js.rt/obj-proto
    [["toString" 0 'js/object-to-string] ["valueOf" 0 'js/object-value-of]]]
   ['js.rt/str-proto
    [["charCodeAt" 1 'js/string-char-code-at]
     ["codePointAt" 1 'js/string-code-point-at] ["at" 1 'js/string-at]
     ["slice" 2 'js/string-slice] ["substring" 2 'js/string-substring]
     ["indexOf" 1 'js/string-index-of] ["concat" 1 'js/string-concat]]]
   ['js.rt/num-proto
    [["toString" 1 'js/number-to-string] ["valueOf" 0 'js/number-value-of]]]
   ['js.rt/bool-proto
    [["toString" 0 'js/boolean-to-string] ["valueOf" 0 'js/boolean-value-of]]]
   ['js.rt/bigint-proto
    [["toString" 0 'js/bigint-to-string] ["valueOf" 0 'js/bigint-value-of]]]
   ['js.rt/error-proto [["toString" 0 'js/error-to-string]]]])


(defn- init-form
  "`js/init!`'s body: the context and out cells, the prototypes in
   dependency order (`%Object.prototype%`, `%Function.prototype%`, then the
   rest), the intrinsic methods, the Error family, the global object and
   its bindings, then the ready flag, under the uninit guard."
  []
  (list
    'fn
    []
    (list
      'if
      (list '= 'js.rt/state :js/uninit)
      (concat
        ['do
         (define 'js.rt/ctx '(cell/new (js/ctx nil)))
         (define 'js.rt/out '(cell/new []))
         (define 'js.rt/obj-proto '(js/make-object :js/null))
         (define 'js.rt/fn-proto '(js/make-object js.rt/obj-proto))
         (define 'js.rt/global '(js/make-object js.rt/obj-proto))]
        [(define 'js.rt/error-proto '(js/make-object js.rt/obj-proto))]
        (mapv (fn [[_ key base]] (define key (list 'js/make-object base)))
              (rest error-classes))
        (mapv (fn [[key _ _]] (define key '(js/make-object js.rt/obj-proto)))
              primitive-protos)
        (mapcat (fn [[proto ms]]
                  (mapv (fn [[nm n code]]
                          (list 'js/def-method! proto nm n code))
                        ms))
                methods-of)
        (mapcat (fn [[nm key _]]
                  [(list 'js/define-own!
                         key
                         {:js/str "name"}
                         (list 'js/slot {:js/str nm} true false true))
                   (list 'js/define-own!
                         key
                         {:js/str "message"}
                         (list 'js/slot {:js/str ""} true false true))
                   (list 'js/install-ctor!
                         nm
                         1
                         (list 'fn
                               '[this args]
                               (list 'js/make-error key '(js/arg args 0)))
                         key)])
                error-classes)
        (mapv (fn [[key nm code]]
                (list 'js/install-ctor! nm 1 code key))
              primitive-protos)
        ['(js/define-own! js.rt/global
                          {:js/str "undefined"}
                          (js/slot :js/undefined false false false))
         '(js/define-own! js.rt/global
                          {:js/str "NaN"}
                          (js/slot (js/nan) false false false))
         '(js/define-own! js.rt/global
                          {:js/str "Infinity"}
                          (js/slot (data/float64 (data/float-value ##Inf)) false false false))
         '(js/define-global! "globalThis" js.rt/global)
         '(let [c (js/make-object js.rt/obj-proto)]
            (do (js/def-method! c "log" 0 js/console-log)
                (js/define-global! "console" c)))]
        ;; last, so a failed init never reports ready
        [(define 'js.rt/state :js/ready) :js/undefined])
      :js/undefined)))


(def definitions
  "The single definition list, `[store-key form]` in definition order:
   every prelude function, the literal state slot, then `js/init!`.
   Defining them runs nothing."
  (into core-definitions
        [['js.rt/state :js/uninit] ['js/init! (init-form)]]))


(defn- definitions->uast
  [defs]
  (u/seq-nodes (map (fn [[k form]] (u/def! k (u/sexp->uast form))) defs)))


(def functions-uast
  "Every definition: lambdas and the literal state slot. Defining them
   runs nothing, so this loads on any composition, with or without cells."
  (definitions->uast definitions))


(def uast
  "The bundled prelude: every definition, then the task's runtime state
   allocated by `js/init!`."
  (u/then functions-uast (u/sexp->uast '(js/init!))))


;; =============================================================================
;; The module emitter: the same definition list as the linker module `js`
;; =============================================================================

(def module-name
  "The linker module the JavaScript runtime profile is published as."
  'js)


(def runtime-keys
  "Every key `js/init!` allocates, the ready flag aside, sorted."
  (vec (sort-by str
                (concat '[js.rt/ctx js.rt/out js.rt/global js.rt/obj-proto
                          js.rt/fn-proto]
                        (map second error-classes)
                        (map first primitive-protos)))))


(defn strip
  "`sym` without the module's own namespace `js`; any other value as it
   is. `js.rt/*` stays qualified: an internal store key."
  [sym]
  (core/strip "js" sym))


(def ^:private module-definitions
  "The module's definition list: a `:js/uninit` placeholder for every
   runtime key, then every definition, stripped."
  (core/module-definitions "js" :js/uninit runtime-keys definitions))


(def module-uast
  "The module tree: one application whose operands are the definitions, in
   order, applied to a lambda that returns nil."
  (core/module-tree module-definitions []))


(def module-exports
  "The module's exports: every `js/` key of the definition list, stripped."
  (core/exports "js" definitions))


(def free-names
  "Every variable name some occurrence of the map AST reads unbound by an
   enclosing lambda."
  core/free-names)


(def defined-keys
  "Every literal key the map AST defines with `yin/def`, at any depth."
  core/defined-keys)


(def ^:private module-free-names
  "The names the module tree reads and does not define, sorted: the
   primitives and the host exports its manifest must declare."
  (core/free-name-vec module-uast))


(defn module-spec
  "`yin.vm.linker.publish/publish-module!`'s spec for the module `js` over
   the host registry `registry`. A free name nothing supplies is refused
   before anything is published. This namespace emits; it never publishes."
  [registry]
  (core/module-spec {:module-name module-name,
                     :ast module-uast,
                     :exports module-exports,
                     :requires {},
                     :free-names module-free-names,
                     :registry registry,
                     :message "the js module reads an undeclared name",
                     :refusal-ns "yang.typescript.antlr"}))


(def min-integer-bits
  "The smallest `::integer/max-bits` this profile admits: every inline
   literal (up to 2^53 - 1) must fit."
  53)


(defn register-integer-module
  "Return `registry` with the `integer` module installed under `limits`
   for this profile: refused when `::integer/max-bits` is under
   `min-integer-bits`, otherwise `integer/register-integer-module`."
  [registry limits]
  (let [b (::integer/max-bits limits)]
    (when-not (and (int? b) (<= min-integer-bits b))
      (throw (ex-info "the JavaScript profile needs integer max-bits >= 53"
                      {:yang.typescript.antlr/refusal
                       :yang.typescript.antlr/max-bits,
                       ::integer/max-bits b}))))
  (integer/register-integer-module registry limits))


(defn admit
  "Return `registry` when it supplies every one of `host-names`, else
   refuse it before any program runs, naming each missing export in
   `:yang.typescript.antlr/missing`."
  [registry]
  (core/admit registry host-names
              "the composition lacks host names the prelude calls"
              "yang.typescript.antlr"))
