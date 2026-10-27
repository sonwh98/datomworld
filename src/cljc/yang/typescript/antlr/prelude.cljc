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
    [js/symbol?
     (fn [x]
       (if (js/cell? x) (= (get (cell/get x) :js/type) :symbol) false))]
    [js/object? (fn [x] (if (js/cell? x) (not (js/symbol? x)) false))]
    [js/make-symbol
     (fn [description]
       (cell/new (assoc (assoc {} :js/type :symbol) :description description)))]
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
                       (if (= (get (cell/get x) :js/type) :symbol)
                         :symbol
                         :object))
                     :number))))))))]
    [js/ty (fn [k] (if (= k :function) :object k))]
    [js/prim-type?
     (fn [t]
       (if (= t :string)
         true
         (if (= t :number) true (if (= t :symbol) true (= t :bigint)))))]
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
             :string {:js/str "string"},
             :symbol {:js/str "symbol"}}
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
                     (if (= k :symbol)
                       (js/throw-type-error
                         {:js/str "Cannot convert a Symbol value to a string"})
                       (js/to-string (js/to-primitive v :string)))))))))))]
    [js/to-property-key
     (fn [v]
       (let [p (js/to-primitive v :string)]
         (if (js/symbol? p) p (js/to-string p))))]
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
     (fn [v hint] (if (js/object? v) (js/ordinary-to-primitive v hint) v))]
    [js/prim-try
     (fn [o name k]
       (let [m (js/get-prop o name)]
         (if (js/callable? m)
           (let [r (js/call m o [])] (if (js/object? r) (k) r))
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
       (if (if (js/symbol? key) true (nil? (get key :js/str)))
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
             (if (js/symbol? key)
               (assoc om2 :syms (conj (get om2 :syms) key))
               (if (<= 0 i)
                 (assoc om2 :ints (js/ints-insert (get om2 :ints) key i 0))
                 (assoc om2 :strs (conj (get om2 :strs) key)))))
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
    [js/get-own (fn [o key] (js/own-slot o key))]
    [js/get-prop-r
     ;; [[Get]] over the prototype chain with an explicit receiver. An
     ;; accessor slot is told by the presence of :get (R1); the walk is a
     ;; tail call, so a long chain runs in constant stack
     (fn [o key receiver]
       (let [sl (js/get-own o key)]
         (if (nil? sl)
           (let [p (get (cell/get o) :proto)]
             (if (= p :js/null) :js/undefined (js/get-prop-r p key receiver)))
           (if (nil? (get sl :get))
             (get sl :value)
             (let [g (get sl :get)]
               (if (= g :js/undefined) :js/undefined (js/call g receiver [])))))))]
    [js/get-prop (fn [o key] (js/get-prop-r o key o))]
    [js/has-own-field?
     (fn [d k] (not (nil? (get d k))))]
    [js/accessor-desc?
     (fn [d]
       (if (js/has-own-field? d :get) true (js/has-own-field? d :set)))]
    [js/data-desc?
     (fn [d]
       (if (js/has-own-field? d :value) true (js/has-own-field? d :writable)))]
    [js/validate-descriptor
     ;; ValidateAndApplyPropertyDescriptor's checks: may `desc` (a map with
     ;; any of :value :writable :get :set :enumerable :configurable) replace
     ;; the slot `cur` (nil when absent) of an object that is `extensible?`
     (fn [extensible? cur desc]
       (if (nil? cur)
         extensible?
         (if (get cur :configurable)
           true
           (if (= (get desc :configurable) true)
             false
             (if (if (js/has-own-field? desc :enumerable)
                   (not (= (get desc :enumerable) (get cur :enumerable)))
                   false)
               false
               (if (if (js/accessor-desc? desc) false (not (js/data-desc? desc)))
                 true
                 (if (nil? (get cur :get))
                   (if (js/accessor-desc? desc)
                     false
                     (if (get cur :writable)
                       true
                       (if (= (get desc :writable) true)
                         false
                         (if (js/has-own-field? desc :value)
                           (js/same-value (get desc :value) (get cur :value))
                           true))))
                   (if (js/data-desc? desc)
                     false
                     (if (if (js/has-own-field? desc :get)
                           (not (js/same-value (get desc :get) (get cur :get)))
                           false)
                       false
                       (if (js/has-own-field? desc :set)
                         (js/same-value (get desc :set) (get cur :set))
                         true))))))))))]
    [js/pick
     (fn [d k dflt] (if (js/has-own-field? d k) (get d k) dflt))]
    [js/mk-accessor-slot
     (fn [g s e c]
       (assoc (assoc (assoc (assoc {} :get g) :set s) :enumerable e)
              :configurable c))]
    [js/accessor-slot? (fn [sl] (not (nil? (get sl :get))))]
    [js/vec-index-of
     (fn [v key i]
       (if (< i (data/count v))
         (if (= (get v i) key) i (js/vec-index-of v key (+ i 1)))
         -1))]
    [js/vec-remove
     ;; `v` without its first element equal to `key`, order kept
     (fn [v key]
       (let [i (js/vec-index-of v key 0)]
         (if (< i 0)
           v
           (data/into (data/subvec v 0 i) (data/subvec v (+ i 1))))))]
    [js/om-remove
     ;; drop an own key: the rest keep their order and a later re-add goes
     ;; last in its partition (a removed index entry is a nil tombstone)
     (fn [om key]
       (if (nil? (get (get om :index) key))
         om
         (let [om2 (assoc om :index (assoc (get om :index) key nil))]
           (if (js/symbol? key)
             (assoc om2 :syms (js/vec-remove (get om2 :syms) key))
             (if (<= 0 (js/index-key-value key))
               (assoc om2 :ints (js/vec-remove (get om2 :ints) key))
               (assoc om2 :strs (js/vec-remove (get om2 :strs) key)))))))]

    ;; ------------------------------------------------------------- arrays
    ;; {:js/type :array :proto :props om :items [v|:js/hole ..]
    ;; :extensible? :items-mode :open|:sealed|:frozen :length-writable?};
    ;; index keys and "length" are virtual slots over :items, and the item
    ;; view is always a default data slot (per-element descriptors are the
    ;; ledger gap L-T2-1)
    [js/make-array
     (fn [proto items]
       (cell/new (assoc (assoc (assoc (assoc (assoc (assoc {} :js/type :array)
                                                    :proto proto)
                                             :props (js/om-new))
                                      :items items)
                               :extensible? true)
                        :items-mode :open)))]
    [js/array?
     (fn [x]
       (if (js/cell? x) (= (get (cell/get x) :js/type) :array) false))]
    [js/array-item-slot
     (fn [c i]
       (let [items (get c :items)]
         (if (< i (data/count items))
           (let [it (get items i)]
             (if (= it :js/hole)
               nil
               (js/slot it
                        (not (= (get c :items-mode) :frozen))
                        true
                        (= (get c :items-mode) :open))))
           nil)))]
    [js/array-length-slot
     (fn [c]
       (js/slot (data/float64 (data/count (get c :items)))
                (not (= (get c :length-writable?) false))
                false
                false))]
    [js/own-slot
     (fn [o key]
       (let [c (cell/get o)]
         (if (= (get c :js/type) :array)
           (let [i (js/index-key-value key)]
             (if (<= 0 i)
               (js/array-item-slot c i)
               (if (= key {:js/str "length"})
                 (js/array-length-slot c)
                 (js/om-get (get c :props) key))))
           (js/om-get (get c :props) key))))]
    [js/items-pad
     (fn [items n]
       (if (< (data/count items) n)
         (js/items-pad (conj items :js/hole) n)
         items))]
    [js/items-put
     ;; `items` with slot i set to v, holes filling any gap
     (fn [items i v]
       (if (< i (data/count items))
         (data/into (conj (data/subvec items 0 i) v)
                    (data/subvec items (+ i 1)))
         (conj (js/items-pad items i) v)))]
    [js/array-set-items!
     (fn [o items]
       (cell/set! o (assoc (cell/get o) :items items)))]
    [js/check-sparse
     (fn [cnt n]
       (if (< 1048576 (- n cnt))
         (js/throw-range-error {:js/str "Invalid array length"})
         true))]
    [js/shrink-items
     ;; the length a shrink to n stops at: holes drop, an element of a
     ;; non-open array does not
     (fn [items len n mode]
       (if (<= len n)
         len
         (if (if (= (get items (- len 1)) :js/hole) true (= mode :open))
           (js/shrink-items items (- len 1) n mode)
           len)))]
    [js/array-set-length!
     ;; ArraySetLength's truncate/extend half: false when a kept element stops it
     (fn [o n]
       (let [c (cell/get o)
             items (get c :items)
             cnt (data/count items)]
         (if (<= cnt n)
           (do (js/check-sparse cnt n)
               (js/array-set-items! o (js/items-pad items n))
               true)
           (let [m (js/shrink-items items cnt n (get c :items-mode))]
             (do (js/array-set-items! o (data/subvec items 0 m)) (= m n))))))]
    [js/array-length-value
     (fn [v]
       (let [n (js/to-number v)
             u (js/uint32-bare (data/float-value n))]
         (if (js/same-value-zero (data/float64 u) n)
           u
           (js/throw-range-error {:js/str "Invalid array length"}))))]
    [js/array-define-length
     (fn [o desc]
       (let [c (cell/get o)
             cnt (data/count (get c :items))
             w? (not (= (get c :length-writable?) false))]
         (if (if (js/accessor-desc? desc)
               true
               (if (= (get desc :configurable) true)
                 true
                 (= (get desc :enumerable) true)))
           false
           (let [n (if (js/has-own-field? desc :value)
                     (js/array-length-value (get desc :value))
                     cnt)]
             (if (if w? false (= (get desc :writable) true))
               false
               (if (if w? false (not (= n cnt)))
                 false
                 (let [ok (js/array-set-length! o n)]
                   (do (if (= (get desc :writable) false)
                         (cell/set! o
                                    (assoc (cell/get o) :length-writable? false))
                         :js/undefined)
                       ok))))))))]
    [js/attr-agrees?
     ;; a present attribute of `desc` equals the one of the item view `cur`
     (fn [desc cur k]
       (if (js/has-own-field? desc k) (= (get desc k) (get cur k)) true))]
    [js/array-define-index
     (fn [o i desc]
       (let [c (cell/get o)
             cnt (data/count (get c :items))
             cur (js/array-item-slot c i)
             w? (not (= (get c :length-writable?) false))]
         (if (nil? cur)
           (if (if (<= cnt i) (not w?) false)
             false
             (if (not (get c :extensible?))
               false
               (if (if (js/accessor-desc? desc)
                     true
                     (if (= (get desc :writable) true)
                       (if (= (get desc :enumerable) true)
                         (not (= (get desc :configurable) true))
                         true)
                       true))
                 false
                 (do (js/check-sparse cnt i)
                     (js/array-set-items!
                       o
                       (js/items-put (get c :items)
                                     i
                                     (js/pick desc :value :js/undefined)))
                     true))))
           (if (js/validate-descriptor (get c :extensible?) cur desc)
             (if (if (js/accessor-desc? desc)
                   true
                   (if (js/attr-agrees? desc cur :enumerable)
                     (if (js/attr-agrees? desc cur :configurable)
                       (not (js/attr-agrees? desc cur :writable))
                       true)
                     true))
               false
               (do (if (js/has-own-field? desc :value)
                     (js/array-set-items! o
                                          (js/items-put (get c :items)
                                                        i
                                                        (get desc :value)))
                     :js/undefined)
                   true))
             false))))]

    ;; ------------------------------------------------------------ defining
    [js/merge-slot
     ;; the slot `desc` makes of `cur` (nil when absent): absent attributes
     ;; default to false/undefined on a new slot and keep on an existing
     ;; one; a kind switch keeps enumerable and configurable only
     (fn [cur desc]
       (let [acc? (if (nil? cur) false (js/accessor-slot? cur))
             dat? (if (nil? cur) false (not acc?))
             e (js/pick desc :enumerable (if (nil? cur) false (get cur :enumerable)))
             c (js/pick desc
                        :configurable
                        (if (nil? cur) false (get cur :configurable)))]
         (if (js/accessor-desc? desc)
           (js/mk-accessor-slot
             (js/pick desc :get (if acc? (get cur :get) :js/undefined))
             (js/pick desc :set (if acc? (get cur :set) :js/undefined))
             e
             c)
           (if (js/data-desc? desc)
             (js/slot (js/pick desc :value (if dat? (get cur :value) :js/undefined))
                      (js/pick desc :writable (if dat? (get cur :writable) false))
                      e
                      c)
             (if acc?
               (js/mk-accessor-slot (get cur :get) (get cur :set) e c)
               (js/slot (if dat? (get cur :value) :js/undefined)
                        (if dat? (get cur :writable) false)
                        e
                        c))))))]
    [js/ordinary-define
     (fn [o key desc]
       (let [cur (js/own-slot o key)]
         (if (js/validate-descriptor (get (cell/get o) :extensible?) cur desc)
           (do (js/define-own! o key (js/merge-slot cur desc)) true)
           false)))]
    [js/define-own-property
     ;; [[DefineOwnProperty]]: ValidateAndApplyPropertyDescriptor, with the
     ;; array exotic arms; a boolean
     (fn [o key desc]
       (if (js/array? o)
         (let [i (js/index-key-value key)]
           (if (<= 0 i)
             (js/array-define-index o i desc)
             (if (= key {:js/str "length"})
               (js/array-define-length o desc)
               (js/ordinary-define o key desc))))
         (js/ordinary-define o key desc)))]
    [js/create-data-prop!
     (fn [o key v]
       (js/define-own-property
         o
         key
         (assoc (assoc (assoc (assoc {} :value v) :writable true)
                       :enumerable
                       true)
                :configurable
                true)))]
    [js/define-property-or-throw
     (fn [o key desc]
       (if (js/define-own-property o key desc)
         true
         (js/throw-type-error
           (js/str-concat {:js/str "Cannot redefine property: "}
                          (js/key-text key)))))]
    [js/desc-field
     (fn [d obj name k bool?]
       (if (js/has-property obj name)
         (let [v (js/get-prop obj name)]
           (assoc d k (if bool? (js/to-boolean v) v)))
         d))]
    [js/callable-or-undefined?
     (fn [f] (if (= f :js/undefined) true (js/callable? f)))]
    [js/to-property-descriptor
     ;; a guest object's partial descriptor: only the fields it supplies
     (fn [obj]
       (if (js/object? obj)
         (let [d (js/desc-field
                   (js/desc-field
                     (js/desc-field
                       (js/desc-field
                         (js/desc-field
                           (js/desc-field {} obj {:js/str "enumerable"} :enumerable true)
                           obj
                           {:js/str "configurable"}
                           :configurable
                           true)
                         obj
                         {:js/str "value"}
                         :value
                         false)
                       obj
                       {:js/str "writable"}
                       :writable
                       true)
                     obj
                     {:js/str "get"}
                     :get
                     false)
                   obj
                   {:js/str "set"}
                   :set
                   false)]
           (if (if (js/has-own-field? d :get)
                 (not (js/callable-or-undefined? (get d :get)))
                 false)
             (js/throw-type-error {:js/str "Getter must be a function"})
             (if (if (js/has-own-field? d :set)
                   (not (js/callable-or-undefined? (get d :set)))
                   false)
               (js/throw-type-error {:js/str "Setter must be a function"})
               (if (if (js/accessor-desc? d) (js/data-desc? d) false)
                 (js/throw-type-error
                   {:js/str
                    "Invalid property descriptor. Cannot both specify accessors and a value or writable attribute"})
                 d))))
         (js/throw-type-error
           {:js/str "Property description must be an object"})))]
    [js/from-property-descriptor
     (fn [sl]
       (if (nil? sl)
         :js/undefined
         (let [o (js/make-object js.rt/obj-proto)
               dp (fn [k v] (js/create-data-prop! o (assoc {} :js/str k) v))]
           (do (if (js/accessor-slot? sl)
                 (do (dp "get" (get sl :get)) (dp "set" (get sl :set)))
                 (do (dp "value" (get sl :value))
                     (dp "writable" (get sl :writable))))
               (dp "enumerable" (get sl :enumerable))
               (dp "configurable" (get sl :configurable))
               o))))]

    ;; ------------------------------------------------- [[Set]] and friends
    [js/set-on-receiver
     ;; OrdinarySet's tail once an inherited or absent data slot allows it
     (fn [receiver key v]
       (if (js/object? receiver)
         (let [ex (js/get-own receiver key)]
           (if (nil? ex)
             (js/create-data-prop! receiver key v)
             (if (js/accessor-slot? ex)
               false
               (if (get ex :writable)
                 (js/define-own-property receiver key (assoc {} :value v))
                 false))))
         false))]
    [js/set-prop
     ;; [[Set]] with an explicit receiver; a boolean, the chain walk a tail call
     (fn [o key v receiver]
       (let [sl (js/get-own o key)]
         (if (nil? sl)
           (let [p (get (cell/get o) :proto)]
             (if (= p :js/null)
               (js/set-on-receiver receiver key v)
               (js/set-prop p key v receiver)))
           (if (js/accessor-slot? sl)
             (let [s (get sl :set)]
               (if (= s :js/undefined)
                 false
                 (do (js/call s receiver (js/vconj [] v)) true)))
             (if (get sl :writable) (js/set-on-receiver receiver key v) false)))))]
    [js/symbol-descriptive-string
     (fn [s]
       (let [d (get (cell/get s) :description)]
         (js/str-concat
           {:js/str "Symbol("}
           (js/str-concat (if (= d :js/undefined) {:js/str ""} d)
                          {:js/str ")"}))))]
    [js/key-text
     (fn [key]
       (if (js/symbol? key) (js/symbol-descriptive-string key) key))]
    [js/proto-of-primitive
     (fn [v]
       (let [k (js/kind-of v)]
         (if (= k :string)
           js.rt/str-proto
           (if (= k :number)
             js.rt/num-proto
             (if (= k :boolean)
               js.rt/bool-proto
               (if (= k :bigint) js.rt/bigint-proto js.rt/symbol-proto))))))]
    [js/put-value
     ;; the strict assignment front door: a false [[Set]] is a TypeError
     (fn [base key v]
       (let [k (js/kind-of base)]
         (if (if (= k :undefined) true (= k :null))
           (js/throw-type-error
             (js/str-concat {:js/str "Cannot set properties of "}
                            (js/str-concat (js/to-string base)
                                           (js/str-concat {:js/str " (setting '"}
                                                          (js/str-concat (js/key-text key)
                                                                         {:js/str "')"})))))
           (if (if (js/callable? base) true (js/object? base))
             (if (js/set-prop base key v base)
               v
               (js/throw-type-error
                 (js/str-concat {:js/str "Cannot assign to read only property '"}
                                (js/str-concat (js/key-text key) {:js/str "'"}))))
             (if (js/set-prop (js/proto-of-primitive base) key v base)
               v
               (js/throw-type-error
                 (js/str-concat {:js/str "Cannot create property '"}
                                (js/str-concat (js/key-text key)
                                               {:js/str "' on a primitive"}))))))))]
    [js/in-op
     (fn [key o]
       (if (js/object? o)
         (js/has-property o (js/to-property-key key))
         (js/throw-type-error
           {:js/str "Cannot use 'in' operator to search for a key in a non-object"})))]
    [js/delete-prop
     ;; [[Delete]]: absent and removed are true, non-configurable is false
     (fn [o key]
       (let [sl (js/get-own o key)]
         (if (nil? sl)
           true
           (if (get sl :configurable)
             (if (if (js/array? o) (<= 0 (js/index-key-value key)) false)
               (do (js/array-set-items!
                     o
                     (js/items-put (get (cell/get o) :items)
                                   (js/index-key-value key)
                                   :js/hole))
                   true)
               (do (cell/set! o
                              (assoc (cell/get o)
                                     :props
                                     (js/om-remove (get (cell/get o) :props) key)))
                   true))
             false))))]
    [js/delete-value
     (fn [base key]
       (let [k (js/kind-of base)]
         (if (if (= k :undefined) true (= k :null))
           (js/throw-type-error
             {:js/str "Cannot convert undefined or null to object"})
           (if (if (js/callable? base) true (js/object? base))
             (if (js/delete-prop base key)
               true
               (js/throw-type-error
                 (js/str-concat {:js/str "Cannot delete property '"}
                                (js/str-concat (js/key-text key) {:js/str "'"}))))
             (if (= k :string)
               (if (if (= key {:js/str "length"})
                     true
                     (let [i (js/index-key-value key)]
                       (if (<= 0 i) (< i (js/str-unit-length base)) false)))
                 (js/throw-type-error
                   (js/str-concat {:js/str "Cannot delete property '"}
                                  (js/str-concat (js/key-text key) {:js/str "'"})))
                 true)
               true)))))]
    [js/own-keys
     ;; [[OwnKeys]]: array indices, "length", then ints, strs, syms
     (fn [o]
       (let [c (cell/get o)]
         (if (= (get c :js/type) :array)
           (data/into (js/array-index-keys (get c :items) 0 [])
                      (data/into [{:js/str "length"}]
                                 (js/om-keys (get c :props))))
           (js/om-keys (get c :props)))))]
    [js/array-index-keys
     (fn [items i acc]
       (if (< i (data/count items))
         (js/array-index-keys
           items
           (+ i 1)
           (if (= (get items i) :js/hole)
             acc
             (conj acc (js/number->string (data/float64 i)))))
         acc))]
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
             (js/get-prop-r js.rt/str-proto key s)))))]
    [js/get-value
     ;; a member read: primitive receivers read their intrinsic prototype
     (fn [v key]
       (let [k (js/kind-of v)]
         (if (if (= k :object) true (= k :function))
           (js/get-prop v key)
           (if (= k :string)
             (js/string-get v key)
             (if (if (= k :number) true (if (= k :boolean) true (if (= k :bigint) true (= k :symbol))))
               (js/get-prop-r (js/proto-of-primitive v) key v)
               (js/throw-type-error
                 (js/str-concat
                   {:js/str "Cannot read properties of "}
                   (js/str-concat
                     (js/to-string v)
                     (js/str-concat
                       {:js/str " (reading '"}
                       (js/str-concat (js/to-string key)
                                      {:js/str "')"}))))))))))]


    ;; ------------------------------------------------------------ functions
    ;; A function cell holds {:js/type :function :code (fn [this args] ..)
    ;; :this-mode :strict|:lexical :name s :length n :props om :proto ref
    ;; :extensible? true}; every call is (js/call f this args).
    ;; `nt?` true means the code takes a third argument, the new.target
    ;; (undefined under a plain call); `js/make-function` keeps the landed
    ;; five-argument shape for the callers that never construct through
    ;; new.target.
    [js/make-function
     (fn [name length this-mode kind code]
       (js/make-function-nt name length this-mode kind code false))]
    [js/make-function-nt
     (fn [name length this-mode kind code nt?]
       (let [f (cell/new
                 (assoc (assoc (assoc (assoc (assoc (assoc (assoc (assoc (assoc (assoc {}
                                                                                       :js/type
                                                                                       :function)
                                                                                :code code)
                                                                         :this-mode this-mode)
                                                                  :name name)
                                                           :length length)
                                                    :props (js/om-new))
                                             :proto js.rt/fn-proto)
                                      :extensible? true)
                               :kind
                               kind)
                        :nt?
                        nt?))]
         (do (js/define-own! f
                             {:js/str "length"}
                             (js/slot (data/float64 length) false false true))
             (js/define-own! f {:js/str "name"} (js/slot name false false true))
             (if (= kind :normal)
               (let [p (js/make-object js.rt/obj-proto)]
                 (do (js/define-own! f
                                     {:js/str "prototype"}
                                     (js/slot p true false false))
                     (js/define-own! p
                                     {:js/str "constructor"}
                                     (js/slot f true false true))))
               :js/undefined)
             f)))]
    [js/call
     (fn [f this args]
       (if (js/callable? f)
         (let [c (cell/get f)
               k (get c :kind)]
           (if (if (= k :class) true (= k :derived))
             (js/throw-type-error
               {:js/str "Class constructor cannot be invoked without 'new'"})
             (let [t (if (= (get c :this-mode) :lexical) :js/undefined this)]
               (if (= (get c :nt?) true)
                 ((get c :code) t args :js/undefined)
                 ((get c :code) t args)))))
         (js/throw-type-error {:js/str "value is not a function"})))]
    [js/is-constructor?
     ;; [[Construct]] exists on :normal functions, class constructors and the
     ;; builtins flagged :ctor?; arrows, methods, accessors and the rest have
     ;; none
     (fn [v]
       (if (js/callable? v)
         (let [c (cell/get v)
               k (get c :kind)]
           (if (= k :normal)
             true
             (if (= k :class)
               true
               (if (= k :derived)
                 true
                 (if (= k :builtin) (= (get c :ctor?) true) false)))))
         false))]
    [js/construct-with-nt
     ;; [[Construct]](args, nt): a derived constructor allocates nothing (its
     ;; `this` is bound by super()); every other kind allocates from
     ;; nt.prototype. A bound function constructs its target, passing the
     ;; target as new.target when nt is the bound function itself.
     (fn [f args nt]
       (if (js/is-constructor? f)
         (let [c (cell/get f)]
           (if (nil? (get c :bound-target))
             (if (= (get c :kind) :derived)
               ((get c :code) :js/undefined args nt)
               (let [pr (js/get-prop nt {:js/str "prototype"})
                     o (js/make-object (if (js/object? pr) pr js.rt/obj-proto))
                     _ (if (= (get c :kind) :class)
                         (do (js/brand! o f) (js/run-fields! f o))
                         :js/undefined)
                     r (if (= (get c :nt?) true)
                         ((get c :code) o args nt)
                         ((get c :code) o args))]
                 (if (js/object? r) r o)))
             (js/construct-with-nt (get c :bound-target)
                                   (data/into (get c :bound-args) args)
                                   (if (= nt f) (get c :bound-target) nt))))
         (js/throw-type-error {:js/str "value is not a constructor"})))]
    [js/construct (fn [f args] (js/construct-with-nt f args f))]

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
       (if (js/object? this)
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
       (if (js/object? e)
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
    [js/object-value-of (fn [this args] this)]
    [js/console-log (fn [this args] (js/log! args))]
    [js/string-fn
     (fn [this args]
       (if (= (data/count args) 0)
         {:js/str ""}
         (if (js/symbol? (get args 0))
           (js/symbol-descriptive-string (get args 0))
           (js/to-string (get args 0)))))]
    [js/symbol-fn
     (fn [this args]
       (let [d (js/arg args 0)]
         (js/make-symbol (if (= d :js/undefined) d (js/to-string d)))))]
    [js/this-symbol
     (fn [this]
       (if (js/symbol? this)
         this
         (js/throw-type-error {:js/str "not a symbol"})))]
    [js/symbol-to-string
     (fn [this args] (js/symbol-descriptive-string (js/this-symbol this)))]
    [js/symbol-value-of (fn [this args] (js/this-symbol this))]
    [js/symbol-description
     (fn [this args] (get (cell/get (js/this-symbol this)) :description))]
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
         (js/slot (js/make-function (assoc {} :js/str name) length :strict :builtin code)
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
     (fn [name length code proto ctor?]
       (let [f (js/make-function (assoc {} :js/str name) length :strict :builtin code)]
         (do (cell/set! f (assoc (cell/get f) :ctor? ctor?))
             (js/define-own! f
                             {:js/str "prototype"}
                             (js/slot proto false false false))
             (js/define-own! proto
                             {:js/str "constructor"}
                             (js/slot f true false true))
             (js/define-global! name f)
             f)))]])


(defquoted ^:private object-definitions
  "Stage 0 (the T2 remainder): Function.prototype call/apply/bind, the
   Object.prototype methods, the own-key helpers and the Object statics."
  '[;; ------------------------------------------------- Function.prototype
    [js/args-rest
     (fn [args i] (if (< i (data/count args)) (data/subvec args i) []))]
    [js/length-of
     (fn [a]
       (let [n (js/to-integer (js/get-prop a {:js/str "length"}))]
         (if (< n 0) 0 n)))]
    [js/array-like-args
     (fn [a i n acc]
       (if (< i n)
         (js/array-like-args
           a
           (+ i 1)
           n
           (conj acc (js/get-prop a (js/number->string (data/float64 i)))))
         acc))]
    [js/list-from-array-like
     (fn [a]
       (if (if (= a :js/undefined) true (= a :js/null))
         []
         (if (js/object? a)
           (js/array-like-args a 0 (js/length-of a) [])
           (js/throw-type-error
             {:js/str "CreateListFromArrayLike called on non-object"}))))]
    [js/this-callable
     (fn [this nm]
       (if (js/callable? this)
         this
         (js/throw-type-error
           (js/str-concat {:js/str "Function.prototype."}
                          (js/str-concat nm {:js/str " called on a non-function"})))))]
    [js/function-call
     (fn [this args]
       (js/call (js/this-callable this {:js/str "call"})
                (js/arg args 0)
                (js/args-rest args 1)))]
    [js/function-apply
     (fn [this args]
       (js/call (js/this-callable this {:js/str "apply"})
                (js/arg args 0)
                (js/list-from-array-like (js/arg args 1))))]
    [js/function-bind
     ;; a bound function is a :builtin cell holding :bound-target, :bound-this
     ;; and :bound-args; construct delegates to the target
     (fn [this args]
       (let [target (js/this-callable this {:js/str "bind"})
             bthis (js/arg args 0)
             bargs (js/args-rest args 1)
             nm0 (js/get-prop target {:js/str "name"})
             tl (js/get-prop target {:js/str "length"})
             len (if (= (js/kind-of tl) :number)
                   (js/imax 0 (- (js/to-integer tl) (data/count bargs)))
                   0)
             b (js/make-function
                 (js/str-concat {:js/str "bound "}
                                (if (js/str? nm0) nm0 {:js/str ""}))
                 len
                 :strict
                 :builtin
                 (fn [this2 args2]
                   (js/call target bthis (data/into bargs args2))))]
         (do (cell/set! b
                        (assoc (assoc (assoc (assoc (assoc (cell/get b)
                                                           :bound-target
                                                           target)
                                                    :bound-this
                                                    bthis)
                                             :bound-args
                                             bargs)
                                      :ctor?
                                      (js/is-constructor? target))
                               :proto
                               (get (cell/get target) :proto)))
             b)))]
    [js/string-to-string
     (fn [this args]
       (if (= (js/kind-of this) :string)
         this
         (js/throw-type-error
           {:js/str "String.prototype.toString requires that 'this' be a String"})))]

    ;; ---------------------------------------------------------- own keys
    [js/require-object-coercible
     (fn [v]
       (let [k (js/kind-of v)]
         (if (if (= k :undefined) true (= k :null))
           (js/throw-type-error
             {:js/str "Cannot convert undefined or null to object"})
           v)))]
    [js/own-slot-any
     ;; the own slot of an object, or the virtual view of a string (R9c)
     (fn [v key]
       (if (js/object? v)
         (js/own-slot v key)
         (if (= (js/kind-of v) :string)
           (if (= key {:js/str "length"})
             (js/slot (data/float64 (js/str-unit-length v)) false false false)
             (let [i (js/index-key-value key)]
               (if (if (<= 0 i) (< i (js/str-unit-length v)) false)
                 (js/slot (js/str-slice v i (+ i 1)) false true false)
                 nil)))
           nil)))]
    [js/index-keys-upto
     (fn [i n acc]
       (if (< i n)
         (js/index-keys-upto
           (+ i 1)
           n
           (conj acc (js/number->string (data/float64 i))))
         acc))]
    [js/own-keys-any
     (fn [v]
       (if (js/object? v)
         (js/own-keys v)
         (if (= (js/kind-of v) :string)
           (data/into (js/index-keys-upto 0 (js/str-unit-length v) [])
                      [{:js/str "length"}])
           [])))]
    [js/keys-select
     ;; mode :strs (string keys), :enum (enumerable string keys), :syms
     (fn [v keys mode i acc]
       (if (< i (data/count keys))
         (let [k (get keys i)
               ok (if (js/symbol? k)
                    (= mode :syms)
                    (if (= mode :syms)
                      false
                      (if (= mode :enum)
                        (get (js/own-slot-any v k) :enumerable)
                        true)))]
           (js/keys-select v keys mode (+ i 1) (if ok (conj acc k) acc)))
         acc))]
    [js/own-string-keys
     (fn [v] (js/keys-select v (js/own-keys-any v) :strs 0 []))]
    [js/own-enumerable-string-keys
     (fn [v] (js/keys-select v (js/own-keys-any v) :enum 0 []))]
    [js/own-symbol-keys
     (fn [v] (js/keys-select v (js/own-keys-any v) :syms 0 []))]
    [js/define-accessor-half!
     ;; merge a getter (kind :get) or setter (kind :set) into the accessor
     ;; slot at `key`, replacing a data slot of the same key
     (fn [o key kind f enumerable]
       (let [cur (js/own-slot o key)
             acc? (if (nil? cur) false (js/accessor-slot? cur))
             g (if (= kind :get) f (if acc? (get cur :get) :js/undefined))
             s (if (= kind :set) f (if acc? (get cur :set) :js/undefined))]
         (js/define-own! o key (js/mk-accessor-slot g s enumerable true))))]
    [js/list->array (fn [v] (js/make-array js.rt/array-proto v))]

    ;; -------------------------------------------------- Object.prototype
    [js/object-has-own-property
     (fn [this args]
       (let [k (js/to-property-key (js/arg args 0))]
         (not (nil? (js/own-slot-any (js/require-object-coercible this) k)))))]
    [js/proto-chain-has?
     (fn [p o]
       (if (= p :js/null)
         false
         (if (= p o) true (js/proto-chain-has? (get (cell/get p) :proto) o))))]
    [js/object-is-prototype-of
     (fn [this args]
       (let [v (js/arg args 0)]
         (if (js/object? v)
           (js/proto-chain-has? (get (cell/get v) :proto)
                                (js/require-object-coercible this))
           false)))]
    [js/object-property-is-enumerable
     (fn [this args]
       (let [k (js/to-property-key (js/arg args 0))
             sl (js/own-slot-any (js/require-object-coercible this) k)]
         (if (nil? sl) false (get sl :enumerable))))]
    [js/object-tag
     (fn [v k]
       (if (= k :undefined)
         {:js/str "Undefined"}
         (if (= k :null)
           {:js/str "Null"}
           (if (= k :function)
             {:js/str "Function"}
             (if (= k :boolean)
               {:js/str "Boolean"}
               (if (= k :number)
                 {:js/str "Number"}
                 (if (= k :string)
                   {:js/str "String"}
                   (if (= k :symbol)
                     {:js/str "Symbol"}
                     (if (= k :bigint)
                       {:js/str "BigInt"}
                       (if (js/array? v)
                         {:js/str "Array"}
                         (if (= (get (cell/get v) :js/class) :error)
                           {:js/str "Error"}
                           {:js/str "Object"})))))))))))]
    [js/object-to-string
     (fn [this args]
       (js/str-concat {:js/str "[object "}
                      (js/str-concat (js/object-tag this (js/kind-of this))
                                     {:js/str "]"})))]
    [js/proto-cycle?
     (fn [o p]
       (if (= p :js/null)
         false
         (if (= p o) true (js/proto-cycle? o (get (cell/get p) :proto)))))]
    [js/set-proto!
     ;; OrdinarySetPrototypeOf: a boolean, the cycle walk iterative
     (fn [o p]
       (let [c (cell/get o)]
         (if (= (get c :proto) p)
           true
           (if (get c :extensible?)
             (if (js/proto-cycle? o p)
               false
               (do (cell/set! o (assoc c :proto p)) true))
             false))))]
    [js/proto-of-any
     (fn [v]
       (do (js/require-object-coercible v)
           (if (js/object? v)
             (get (cell/get v) :proto)
             (js/proto-of-primitive v))))]
    ;; for-in (R4): a state cell {:obj :keys :i :visited}; the walk takes each
    ;; object's own-key snapshot when it reaches that object, looks the slot
    ;; up at visit time, and adds every present key (enumerable or not) to
    ;; `visited`. `visited` is a host map used for membership only.
    [js/for-in-start
     (fn [v]
       (let [k (js/kind-of v)
             live (if (js/object? v) true (= k :string))]
         (cell/new
           (assoc (assoc (assoc (assoc {} :obj (if live v :js/null))
                                :keys
                                (if live (js/own-keys-any v) []))
                         :i
                         0)
                  :visited
                  {}))))]
    [js/for-in-next
     (fn [st]
       (let [s (cell/get st)
             o (get s :obj)
             keys (get s :keys)
             i (get s :i)]
         (if (= o :js/null)
           :js/done
           (if (< i (data/count keys))
             (let [key (get keys i)
                   sl (js/own-slot-any o key)]
               (do (cell/set! st (assoc s :i (+ i 1)))
                   (if (if (nil? sl) true (js/symbol? key))
                     (js/for-in-next st)
                     (if (nil? (get (get s :visited) key))
                       (do (cell/set! st
                                      (assoc (cell/get st)
                                             :visited
                                             (assoc (get s :visited) key true)))
                           (if (get sl :enumerable) key (js/for-in-next st)))
                       (js/for-in-next st)))))
             (let [p (js/proto-of-any o)]
               (if (= p :js/null)
                 (do (cell/set! st (assoc s :obj :js/null)) :js/done)
                 (do (cell/set! st
                                (assoc (assoc (assoc s :obj p)
                                              :keys
                                              (js/own-keys-any p))
                                       :i
                                       0))
                     (js/for-in-next st))))))))]
    [js/new-object (fn [] (js/make-object js.rt/obj-proto))]
    [js/new-array (fn [items] (js/make-array js.rt/array-proto items))]
    [js/literal-proto!
     ;; `{__proto__: v}`: sets [[Prototype]] for an object or null, else ignored
     (fn [o v]
       (do (if (if (js/object? v) true (= v :js/null))
             (js/set-proto! o v)
             false)
           :js/undefined))]
    [js/proto-getter (fn [this args] (js/proto-of-any this))]
    [js/proto-setter
     (fn [this args]
       (let [p (js/arg args 0)]
         (do (js/require-object-coercible this)
             (if (if (js/object? p) true (= p :js/null))
               (if (js/object? this)
                 (if (js/set-proto! this p)
                   :js/undefined
                   (js/throw-type-error {:js/str "Cyclic __proto__ value"}))
                 :js/undefined)
               :js/undefined))))]

    ;; ------------------------------------------------------------ Object
    [js/object-fn
     (fn [this args]
       (let [v (js/arg args 0)]
         (if (js/object? v)
           v
           (if (if (= v :js/undefined) true (= v :js/null))
             (js/make-object js.rt/obj-proto)
             (js/throw-type-error
               {:js/str "Object(value) of a primitive is not supported (no wrapper objects)"})))))]
    [js/object-keys
     (fn [this args]
       (js/list->array
         (js/own-enumerable-string-keys
           (js/require-object-coercible (js/arg args 0)))))]
    [js/enum-collect
     (fn [v keys mode i acc]
       (if (< i (data/count keys))
         (let [k (get keys i)
               sl (js/own-slot-any v k)]
           (js/enum-collect
             v
             keys
             mode
             (+ i 1)
             (if (nil? sl)
               acc
               (if (get sl :enumerable)
                 (conj acc
                       (if (= mode :values)
                         (js/get-value v k)
                         (js/list->array (js/pair k (js/get-value v k)))))
                 acc))))
         acc))]
    [js/object-values
     (fn [this args]
       (let [v (js/require-object-coercible (js/arg args 0))]
         (js/list->array
           (js/enum-collect v (js/own-string-keys v) :values 0 []))))]
    [js/object-entries
     (fn [this args]
       (let [v (js/require-object-coercible (js/arg args 0))]
         (js/list->array
           (js/enum-collect v (js/own-string-keys v) :entries 0 []))))]
    [js/assign-from
     (fn [target src keys i]
       (if (< i (data/count keys))
         (let [k (get keys i)
               sl (js/own-slot-any src k)]
           (do (if (nil? sl)
                 :js/undefined
                 (if (get sl :enumerable)
                   (js/put-value target k (js/get-value src k))
                   :js/undefined))
               (js/assign-from target src keys (+ i 1))))
         target))]
    [js/assign-sources
     (fn [target args i]
       (if (< i (data/count args))
         (let [src (get args i)]
           (do (if (if (= src :js/undefined) true (= src :js/null))
                 :js/undefined
                 (js/assign-from target src (js/own-keys-any src) 0))
               (js/assign-sources target args (+ i 1))))
         target))]
    [js/object-assign
     (fn [this args]
       (let [t (js/arg args 0)]
         (if (js/object? t)
           (js/assign-sources t args 1)
           (js/throw-type-error
             {:js/str "Object.assign target must be an object (no wrapper objects)"}))))]
    [js/collect-descs
     (fn [props keys i acc]
       (if (< i (data/count keys))
         (let [k (get keys i)
               sl (js/own-slot-any props k)]
           (js/collect-descs
             props
             keys
             (+ i 1)
             (if (nil? sl)
               acc
               (if (get sl :enumerable)
                 (conj acc
                       (js/pair k
                                (js/to-property-descriptor (js/get-value props k))))
                 acc))))
         acc))]
    [js/apply-descs
     (fn [o descs i]
       (if (< i (data/count descs))
         (do (js/define-property-or-throw o
                                          (get (get descs i) 0)
                                          (get (get descs i) 1))
             (js/apply-descs o descs (+ i 1)))
         o))]
    [js/define-properties
     (fn [o props]
       (js/apply-descs
         o
         (js/collect-descs (js/require-object-coercible props)
                           (js/own-keys-any props)
                           0
                           [])
         0))]
    [js/object-create
     (fn [this args]
       (let [p (js/arg args 0)]
         (if (if (js/object? p) true (= p :js/null))
           (let [o (js/make-object p)]
             (if (= (js/arg args 1) :js/undefined)
               o
               (js/define-properties o (js/arg args 1))))
           (js/throw-type-error
             {:js/str "Object prototype may only be an Object or null"}))))]
    [js/object-define-property
     (fn [this args]
       (let [o (js/arg args 0)]
         (if (js/object? o)
           (let [key (js/to-property-key (js/arg args 1))
                 d (js/to-property-descriptor (js/arg args 2))]
             (do (js/define-property-or-throw o key d) o))
           (js/throw-type-error
             {:js/str "Object.defineProperty called on non-object"}))))]
    [js/object-define-properties
     (fn [this args]
       (let [o (js/arg args 0)]
         (if (js/object? o)
           (js/define-properties o (js/arg args 1))
           (js/throw-type-error
             {:js/str "Object.defineProperties called on non-object"}))))]
    [js/object-get-own-property-descriptor
     (fn [this args]
       (let [o (js/require-object-coercible (js/arg args 0))
             key (js/to-property-key (js/arg args 1))]
         (js/from-property-descriptor (js/own-slot-any o key))))]
    [js/descs-into
     (fn [res o keys i]
       (if (< i (data/count keys))
         (let [k (get keys i)
               d (js/from-property-descriptor (js/own-slot-any o k))]
           (do (if (= d :js/undefined)
                 :js/undefined
                 (js/create-data-prop! res k d))
               (js/descs-into res o keys (+ i 1))))
         res))]
    [js/object-get-own-property-descriptors
     (fn [this args]
       (let [o (js/require-object-coercible (js/arg args 0))]
         (js/descs-into (js/make-object js.rt/obj-proto)
                        o
                        (js/own-keys-any o)
                        0)))]
    [js/object-get-own-property-names
     (fn [this args]
       (js/list->array
         (js/own-string-keys (js/require-object-coercible (js/arg args 0)))))]
    [js/object-get-own-property-symbols
     (fn [this args]
       (js/list->array
         (js/own-symbol-keys (js/require-object-coercible (js/arg args 0)))))]
    [js/object-get-prototype-of
     (fn [this args] (js/proto-of-any (js/arg args 0)))]
    [js/object-set-prototype-of
     (fn [this args]
       (let [o (js/require-object-coercible (js/arg args 0))
             p (js/arg args 1)]
         (if (if (js/object? p) true (= p :js/null))
           (if (js/object? o)
             (if (js/set-proto! o p)
               o
               (js/throw-type-error
                 {:js/str "Cyclic __proto__ value or object is not extensible"}))
             o)
           (js/throw-type-error
             {:js/str "Object prototype may only be an Object or null"}))))]
    [js/object-prevent-extensions
     (fn [this args]
       (let [o (js/arg args 0)]
         (do (if (js/object? o)
               (cell/set! o (assoc (cell/get o) :extensible? false))
               :js/undefined)
             o)))]
    [js/object-is-extensible
     (fn [this args]
       (let [o (js/arg args 0)]
         (if (js/object? o) (get (cell/get o) :extensible?) false)))]
    [js/lock-slots!
     (fn [o keys i frozen?]
       (if (< i (data/count keys))
         (let [k (get keys i)
               sl (js/om-get (get (cell/get o) :props) k)]
           (do (if (nil? sl)
                 :js/undefined
                 (js/define-own!
                   o
                   k
                   (if (if frozen? (not (js/accessor-slot? sl)) false)
                     (assoc (assoc sl :configurable false) :writable false)
                     (assoc sl :configurable false))))
               (js/lock-slots! o keys (+ i 1) frozen?)))
         :js/undefined))]
    [js/lock-object!
     (fn [o frozen?]
       (let [c (cell/get o)
             c2 (assoc c :extensible? false)]
         (do (cell/set!
               o
               (if (js/array? o)
                 (assoc (assoc c2
                               :items-mode
                               (if frozen?
                                 :frozen
                                 (if (= (get c :items-mode) :frozen)
                                   :frozen
                                   :sealed)))
                        :length-writable?
                        (if frozen?
                          false
                          (not (= (get c :length-writable?) false))))
                 c2))
             (js/lock-slots! o (js/om-keys (get c :props)) 0 frozen?)
             o)))]
    [js/object-freeze
     (fn [this args]
       (let [o (js/arg args 0)]
         (if (js/object? o) (js/lock-object! o true) o)))]
    [js/object-seal
     (fn [this args]
       (let [o (js/arg args 0)]
         (if (js/object? o) (js/lock-object! o false) o)))]
    [js/locked?
     (fn [o keys i frozen?]
       (if (< i (data/count keys))
         (let [sl (js/own-slot-any o (get keys i))]
           (if (nil? sl)
             (js/locked? o keys (+ i 1) frozen?)
             (if (get sl :configurable)
               false
               (if (if frozen?
                     (if (js/accessor-slot? sl) false (get sl :writable))
                     false)
                 false
                 (js/locked? o keys (+ i 1) frozen?)))))
         true))]
    [js/object-is-frozen
     (fn [this args]
       (let [o (js/arg args 0)]
         (if (js/object? o)
           (if (get (cell/get o) :extensible?)
             false
             (js/locked? o (js/own-keys o) 0 true))
           true)))]
    [js/object-is-sealed
     (fn [this args]
       (let [o (js/arg args 0)]
         (if (js/object? o)
           (if (get (cell/get o) :extensible?)
             false
             (js/locked? o (js/own-keys o) 0 false))
           true)))]
    [js/object-is
     (fn [this args] (js/same-value (js/arg args 0) (js/arg args 1)))]
    [js/object-has-own
     (fn [this args]
       (let [o (js/require-object-coercible (js/arg args 0))
             key (js/to-property-key (js/arg args 1))]
         (not (nil? (js/own-slot-any o key)))))]
    [js/entries-into
     (fn [res items i]
       (if (< i (data/count items))
         (let [e (get items i)]
           (if (js/object? e)
             (let [k (js/get-value e {:js/str "0"})
                   v (js/get-value e {:js/str "1"})]
               (do (js/create-data-prop! res (js/to-property-key k) v)
                   (js/entries-into res items (+ i 1))))
             (js/throw-type-error
               {:js/str "Iterator value is not an entry object"})))
         res))]
    [js/object-from-entries
     (fn [this args]
       (let [a (js/arg args 0)]
         (if (js/array? a)
           (js/entries-into (js/make-object js.rt/obj-proto)
                            (get (cell/get a) :items)
                            0)
           (js/throw-type-error
             {:js/str "Object.fromEntries accepts arrays only (iterables are T5)"}))))]
    [js/install-object!
     (fn []
       (let [f (js/install-ctor! "Object" 1 js/object-fn js.rt/obj-proto true)]
         (do (js/def-method! f "keys" 1 js/object-keys)
             (js/def-method! f "values" 1 js/object-values)
             (js/def-method! f "entries" 1 js/object-entries)
             (js/def-method! f "assign" 2 js/object-assign)
             (js/def-method! f "create" 2 js/object-create)
             (js/def-method! f "defineProperty" 3 js/object-define-property)
             (js/def-method! f "defineProperties" 2 js/object-define-properties)
             (js/def-method! f
                             "getOwnPropertyDescriptor"
                             2
                             js/object-get-own-property-descriptor)
             (js/def-method! f
                             "getOwnPropertyDescriptors"
                             1
                             js/object-get-own-property-descriptors)
             (js/def-method! f
                             "getOwnPropertyNames"
                             1
                             js/object-get-own-property-names)
             (js/def-method! f
                             "getOwnPropertySymbols"
                             1
                             js/object-get-own-property-symbols)
             (js/def-method! f "getPrototypeOf" 1 js/object-get-prototype-of)
             (js/def-method! f "setPrototypeOf" 2 js/object-set-prototype-of)
             (js/def-method! f "preventExtensions" 1 js/object-prevent-extensions)
             (js/def-method! f "isExtensible" 1 js/object-is-extensible)
             (js/def-method! f "freeze" 1 js/object-freeze)
             (js/def-method! f "isFrozen" 1 js/object-is-frozen)
             (js/def-method! f "seal" 1 js/object-seal)
             (js/def-method! f "isSealed" 1 js/object-is-sealed)
             (js/def-method! f "is" 2 js/object-is)
             (js/def-method! f "hasOwn" 2 js/object-has-own)
             (js/def-method! f "fromEntries" 1 js/object-from-entries)
             (js/define-own!
               js.rt/obj-proto
               {:js/str "__proto__"}
               (js/mk-accessor-slot
                 (js/make-function {:js/str "get __proto__"}
                                   0
                                   :strict
                                   :accessor
                                   js/proto-getter)
                 (js/make-function {:js/str "set __proto__"}
                                   1
                                   :strict
                                   :accessor
                                   js/proto-setter)
                 false
                 true))
             :js/undefined)))]])


(defquoted ^:private array-definitions
  "Stage 0 S5 (partial): the Array constructor, isArray/of, and the
   Array.prototype methods that need an array receiver (ledger L-T2-5)."
  '[[js/this-items
     (fn [this]
       (if (js/array? this)
         (get (cell/get this) :items)
         (js/throw-type-error
           {:js/str "Array.prototype method requires an array receiver"})))]
    [js/item-val (fn [it] (if (= it :js/hole) :js/undefined it))]
    [js/check-open!
     (fn [a]
       (let [c (cell/get a)]
         (if (if (= (get c :items-mode) :open) (get c :extensible?) false)
           a
           (js/throw-type-error
             {:js/str "Cannot modify a frozen, sealed or non-extensible array"}))))]
    [js/this-fn
     (fn [f]
       (if (js/callable? f)
         f
         (js/throw-type-error {:js/str "callback is not a function"})))]
    [js/items-length (fn [items] (data/float64 (data/count items)))]
    [js/array-push
     (fn [this args]
       (let [items (js/this-items this)]
         (do (js/check-open! this)
             (js/array-set-items! this (data/into items args))
             (js/items-length (js/this-items this)))))]
    [js/array-pop
     (fn [this args]
       (let [items (js/this-items this)
             n (data/count items)]
         (if (= n 0)
           :js/undefined
           (do (js/check-open! this)
               (js/array-set-items! this (data/subvec items 0 (- n 1)))
               (js/item-val (get items (- n 1)))))))]
    [js/array-shift
     (fn [this args]
       (let [items (js/this-items this)]
         (if (= (data/count items) 0)
           :js/undefined
           (do (js/check-open! this)
               (js/array-set-items! this (data/subvec items 1))
               (js/item-val (get items 0))))))]
    [js/array-unshift
     (fn [this args]
       (let [items (js/this-items this)]
         (do (js/check-open! this)
             (js/array-set-items! this (data/into (data/into [] args) items))
             (js/items-length (js/this-items this)))))]
    [js/join-text
     (fn [it]
       (if (= it :js/hole)
         {:js/str ""}
         (if (if (= it :js/undefined) true (= it :js/null))
           {:js/str ""}
           (js/to-string it))))]
    [js/join-items
     (fn [items sep i acc]
       (if (< i (data/count items))
         (js/join-items
           items
           sep
           (+ i 1)
           (js/str-concat (if (< 0 i) (js/str-concat acc sep) acc)
                          (js/join-text (get items i))))
         acc))]
    [js/array-join
     (fn [this args]
       (let [items (js/this-items this)
             s (js/arg args 0)]
         (js/join-items items
                        (if (= s :js/undefined) {:js/str ","} (js/to-string s))
                        0
                        {:js/str ""})))]
    [js/array-to-string (fn [this args] (js/array-join this []))]
    [js/array-slice
     (fn [this args]
       (let [items (js/this-items this)
             n (data/count items)
             from (js/rel-clamp (js/to-integer (js/arg args 0)) n)
             e (js/arg args 1)
             to (if (= e :js/undefined) n (js/rel-clamp (js/to-integer e) n))]
         (js/list->array (if (< from to) (data/subvec items from to) []))))]
    [js/array-concat
     (fn [this args]
       (js/list->array (js/concat-into (js/this-items this) args 0)))]
    [js/concat-into
     (fn [acc args i]
       (if (< i (data/count args))
         (let [a (get args i)]
           (js/concat-into
             (if (js/array? a)
               (data/into acc (get (cell/get a) :items))
               (conj acc a))
             args
             (+ i 1)))
         acc))]
    [js/index-search
     ;; the first index >= i whose element satisfies `same?` against x, or -1
     (fn [items x i same? skip-holes?]
       (if (< i (data/count items))
         (let [it (get items i)]
           (if (if (= it :js/hole) skip-holes? false)
             (js/index-search items x (+ i 1) same? skip-holes?)
             (if (same? (js/item-val it) x)
               i
               (js/index-search items x (+ i 1) same? skip-holes?))))
         -1))]
    [js/array-index-of
     (fn [this args]
       (let [items (js/this-items this)
             n (data/count items)]
         (data/float64
           (js/index-search items
                            (js/arg args 0)
                            (js/rel-clamp (js/to-integer (js/arg args 1)) n)
                            js/strict-eq
                            true))))]
    [js/array-includes
     (fn [this args]
       (let [items (js/this-items this)
             n (data/count items)]
         (<= 0
             (js/index-search items
                              (js/arg args 0)
                              (js/rel-clamp (js/to-integer (js/arg args 1)) n)
                              js/same-value-zero
                              false))))]
    [js/array-at
     (fn [this args]
       (let [items (js/this-items this)
             n (data/count items)
             k (js/to-integer (js/arg args 0))
             i (if (< k 0) (+ n k) k)]
         (if (if (<= 0 i) (< i n) false) (js/item-val (get items i)) :js/undefined)))]
    [js/array-reverse
     (fn [this args]
       (let [items (js/this-items this)]
         (do (js/check-open! this)
             (js/array-set-items! this (js/reverse-items items (data/count items) []))
             this)))]
    [js/reverse-items
     (fn [items i acc]
       (if (< 0 i) (js/reverse-items items (- i 1) (conj acc (get items (- i 1)))) acc))]
    [js/callback-args
     (fn [it i a] (js/vconj (js/vconj (js/vconj [] it) (js/mknum i)) a))]
    [js/array-for-each
     (fn [this args]
       (let [f (js/this-fn (js/arg args 0))]
         (js/each-step this f (js/arg args 1) 0 (data/count (js/this-items this)))))]
    [js/each-step
     (fn [a f t i n]
       (if (< i n)
         (let [items (get (cell/get a) :items)]
           (do (if (if (< i (data/count items)) (not (= (get items i) :js/hole)) false)
                 (js/call f t (js/callback-args (get items i) i a))
                 :js/undefined)
               (js/each-step a f t (+ i 1) n)))
         :js/undefined))]
    [js/array-map
     (fn [this args]
       (let [f (js/this-fn (js/arg args 0))]
         (js/list->array
           (js/map-step this f (js/arg args 1) 0 (data/count (js/this-items this)) []))))]
    [js/map-step
     (fn [a f t i n acc]
       (if (< i n)
         (let [items (get (cell/get a) :items)]
           (js/map-step
             a f t (+ i 1) n
             (conj acc
                   (if (if (< i (data/count items)) (not (= (get items i) :js/hole)) false)
                     (js/call f t (js/callback-args (get items i) i a))
                     :js/hole))))
         acc))]
    [js/array-filter
     (fn [this args]
       (let [f (js/this-fn (js/arg args 0))]
         (js/list->array
           (js/filter-step this f (js/arg args 1) 0 (data/count (js/this-items this)) []))))]
    [js/filter-step
     (fn [a f t i n acc]
       (if (< i n)
         (let [items (get (cell/get a) :items)
               ok (if (< i (data/count items)) (not (= (get items i) :js/hole)) false)]
           (js/filter-step
             a f t (+ i 1) n
             (if ok
               (if (js/to-boolean (js/call f t (js/callback-args (get items i) i a)))
                 (conj acc (get items i))
                 acc)
               acc)))
         acc))]
    [js/array-reduce
     (fn [this args]
       (let [f (js/this-fn (js/arg args 0))
             items (js/this-items this)
             n (data/count items)]
         (if (< 1 (data/count args))
           (js/reduce-step this f (js/arg args 1) 0 n)
           (let [i (js/first-present items 0)]
             (if (< i 0)
               (js/throw-type-error {:js/str "Reduce of empty array with no initial value"})
               (js/reduce-step this f (get items i) (+ i 1) n))))))]
    [js/first-present
     (fn [items i]
       (if (< i (data/count items))
         (if (= (get items i) :js/hole) (js/first-present items (+ i 1)) i)
         -1))]
    [js/reduce-step
     (fn [a f acc i n]
       (if (< i n)
         (let [items (get (cell/get a) :items)]
           (js/reduce-step
             a f
             (if (if (< i (data/count items)) (not (= (get items i) :js/hole)) false)
               (js/call f
                        :js/undefined
                        (js/vconj (js/vconj (js/vconj (js/vconj [] acc) (get items i))
                                            (js/mknum i))
                                  a))
               acc)
             (+ i 1)
             n))
         acc))]
    [js/find-step
     ;; mode :value, :index; holes read as undefined
     (fn [a f t i n mode]
       (if (< i n)
         (let [items (get (cell/get a) :items)
               it (if (< i (data/count items)) (js/item-val (get items i)) :js/undefined)]
           (if (js/to-boolean (js/call f t (js/callback-args it i a)))
             (if (= mode :index) (js/mknum i) it)
             (js/find-step a f t (+ i 1) n mode)))
         (if (= mode :index) (js/mknum -1) :js/undefined)))]
    [js/array-find
     (fn [this args]
       (js/find-step this (js/this-fn (js/arg args 0)) (js/arg args 1) 0
                     (data/count (js/this-items this)) :value))]
    [js/array-find-index
     (fn [this args]
       (js/find-step this (js/this-fn (js/arg args 0)) (js/arg args 1) 0
                     (data/count (js/this-items this)) :index))]
    [js/array-some
     (fn [this args]
       (let [f (js/this-fn (js/arg args 0))]
         (js/some-step this f (js/arg args 1) 0 (data/count (js/this-items this)) true)))]
    [js/array-every
     (fn [this args]
       (let [f (js/this-fn (js/arg args 0))]
         (js/some-step this f (js/arg args 1) 0 (data/count (js/this-items this)) false)))]
    [js/some-step
     ;; some (want true) stops at the first truthy; every (want false) stops
     ;; at the first falsy
     (fn [a f t i n want]
       (if (< i n)
         (let [items (get (cell/get a) :items)]
           (if (if (< i (data/count items)) (not (= (get items i) :js/hole)) false)
             (if (= (js/to-boolean (js/call f t (js/callback-args (get items i) i a))) want)
               want
               (js/some-step a f t (+ i 1) n want))
             (js/some-step a f t (+ i 1) n want)))
         (not want)))]
    [js/array-fn
     (fn [this args]
       (let [n (data/count args)
             a0 (js/arg args 0)]
         (if (if (= n 1) (= (js/kind-of a0) :number) false)
           (let [len (js/array-length-value a0)]
             (do (js/check-sparse 0 len)
                 (js/list->array (js/items-pad [] len))))
           (js/list->array args))))]
    [js/array-is-array
     (fn [this args] (js/array? (js/arg args 0)))]
    [js/array-of (fn [this args] (js/list->array args))]
    ;; --- S5 leftovers: sort (stable merge), splice, fill, flat, lastIndexOf,
    ;; reduceRight, Array.from
    [js/sort-compare
     (fn [cmp a b]
       (if (= a :js/undefined)
         (if (= b :js/undefined) 0 1)
         (if (= b :js/undefined)
           -1
           (if (= cmp :js/undefined)
             (js/str-compare (js/to-string a) (js/to-string b))
             (let [x (data/float-value
                       (js/to-number (js/call cmp :js/undefined (js/pair a b))))]
               (if (<= x x) (if (< x 0) -1 (if (> x 0) 1 0)) 0))))))]
    [js/merge-runs
     (fn [cmp l r i j acc]
       (if (< i (data/count l))
         (if (< j (data/count r))
           (if (<= (js/sort-compare cmp (get l i) (get r j)) 0)
             (js/merge-runs cmp l r (+ i 1) j (conj acc (get l i)))
             (js/merge-runs cmp l r i (+ j 1) (conj acc (get r j))))
           (js/merge-runs cmp l r (+ i 1) j (conj acc (get l i))))
         (if (< j (data/count r))
           (js/merge-runs cmp l r i (+ j 1) (conj acc (get r j)))
           acc)))]
    [js/msort
     (fn [cmp v]
       (let [n (data/count v)]
         (if (< n 2)
           v
           (let [h (get (integer/floor-div-mod n 2) 0)]
             (js/merge-runs cmp
                            (js/msort cmp (data/subvec v 0 h))
                            (js/msort cmp (data/subvec v h))
                            0
                            0
                            [])))))]
    [js/present-items
     (fn [items i acc]
       (if (< i (data/count items))
         (js/present-items items
                           (+ i 1)
                           (if (= (get items i) :js/hole) acc (conj acc (get items i))))
         acc))]
    [js/holes-pad
     (fn [v n] (if (< (data/count v) n) (js/holes-pad (conj v :js/hole) n) v))]
    [js/array-sort
     (fn [this args]
       (let [items (js/this-items this)
             cmp (js/arg args 0)]
         (do (if (js/callable-or-undefined? cmp)
               :js/undefined
               (js/throw-type-error
                 {:js/str "The comparison function must be either a function or undefined"}))
             (js/check-open! this)
             (js/array-set-items!
               this
               (js/holes-pad (js/msort cmp (js/present-items items 0 []))
                             (data/count items)))
             this)))]
    [js/array-splice
     (fn [this args]
       (let [items (js/this-items this)
             n (data/count items)
             start (js/rel-clamp (js/to-integer (js/arg args 0)) n)
             dc (if (= (data/count args) 0)
                  0
                  (if (= (data/count args) 1)
                    (- n start)
                    (js/imin (js/imax (js/to-integer (js/arg args 1)) 0)
                             (- n start))))]
         (do (js/check-open! this)
             (js/array-set-items!
               this
               (data/into (data/into (data/subvec items 0 start)
                                     (js/args-rest args 2))
                          (data/subvec items (+ start dc))))
             (js/list->array (data/subvec items start (+ start dc))))))]
    [js/fill-range
     (fn [items v i end]
       (if (< i end)
         (js/fill-range (js/items-put items i v) v (+ i 1) end)
         items))]
    [js/array-fill
     (fn [this args]
       (let [items (js/this-items this)
             n (data/count items)
             from (js/rel-clamp (js/to-integer (js/arg args 1)) n)
             e (js/arg args 2)
             to (if (= e :js/undefined) n (js/rel-clamp (js/to-integer e) n))]
         (do (js/check-open! this)
             (js/array-set-items! this (js/fill-range items (js/arg args 0) from to))
             this)))]
    [js/flat-into
     (fn [acc items d i]
       (if (< i (data/count items))
         (let [it (get items i)]
           (js/flat-into
             (if (= it :js/hole)
               acc
               (if (if (js/array? it) (< 0 d) false)
                 (js/flat-into acc (get (cell/get it) :items) (- d 1) 0)
                 (conj acc it)))
             items
             d
             (+ i 1)))
         acc))]
    [js/array-flat
     (fn [this args]
       (let [items (js/this-items this)
             d (js/arg args 0)]
         (js/list->array
           (js/flat-into [] items (if (= d :js/undefined) 1 (js/to-integer d)) 0))))]
    [js/last-index-search
     (fn [items x i]
       (if (< i 0)
         -1
         (let [it (get items i)]
           (if (if (= it :js/hole) false (js/strict-eq it x))
             i
             (js/last-index-search items x (- i 1))))))]
    [js/array-last-index-of
     (fn [this args]
       (let [items (js/this-items this)
             n (data/count items)
             k (if (< 1 (data/count args)) (js/to-integer (js/arg args 1)) (- n 1))
             start (if (< k 0) (+ n k) (js/imin k (- n 1)))]
         (data/float64 (js/last-index-search items (js/arg args 0) start))))]
    [js/reduce-right-step
     (fn [a f acc i]
       (if (<= 0 i)
         (let [items (get (cell/get a) :items)]
           (js/reduce-right-step
             a
             f
             (if (if (< i (data/count items)) (not (= (get items i) :js/hole)) false)
               (js/call f
                        :js/undefined
                        (js/vconj (js/vconj (js/vconj (js/vconj [] acc) (get items i))
                                            (js/mknum i))
                                  a))
               acc)
             (- i 1)))
         acc))]
    [js/last-present
     (fn [items i]
       (if (< i 0)
         -1
         (if (= (get items i) :js/hole) (js/last-present items (- i 1)) i)))]
    [js/array-reduce-right
     (fn [this args]
       (let [f (js/this-fn (js/arg args 0))
             items (js/this-items this)
             n (data/count items)]
         (if (< 1 (data/count args))
           (js/reduce-right-step this f (js/arg args 1) (- n 1))
           (let [i (js/last-present items (- n 1))]
             (if (< i 0)
               (js/throw-type-error {:js/str "Reduce of empty array with no initial value"})
               (js/reduce-right-step this f (get items i) (- i 1)))))))]
    [js/string-chars
     ;; the elements Array.from makes of a string: code points, a lone
     ;; surrogate on its own
     (fn [s]
       (if (nil? (get s :js/str))
         (js/units-chars (get s :js/units) 0 [])
         (js/cps-chars (data/str->code-points (get s :js/str)) 0 [])))]
    [js/cps-chars
     (fn [cps i acc]
       (if (< i (data/count cps))
         (js/cps-chars cps
                       (+ i 1)
                       (conj acc
                             (assoc {} :js/str (data/code-points->str (conj [] (get cps i))))))
         acc))]
    [js/units-chars
     (fn [us i acc]
       (if (< i (data/count us))
         (js/units-chars us (+ i 1) (conj acc (js/mk-str (conj [] (get us i)))))
         acc))]
    [js/from-map
     (fn [f t items i acc]
       (if (< i (data/count items))
         (js/from-map f
                      t
                      items
                      (+ i 1)
                      (conj acc
                            (js/call f
                                     t
                                     (js/pair (js/item-val (get items i))
                                              (js/mknum i)))))
         acc))]
    [js/array-from
     (fn [this args]
       (let [src (js/arg args 0)
             f (js/arg args 1)
             items (if (= (js/kind-of src) :string)
                     (js/string-chars src)
                     (if (js/object? src)
                       (js/array-like-args src 0 (js/length-of src) [])
                       (if (if (= src :js/undefined) true (= src :js/null))
                         (js/throw-type-error
                           {:js/str "undefined is not iterable"})
                         [])))]
         (js/list->array
           (if (= f :js/undefined)
             items
             (js/from-map (js/this-fn f) (js/arg args 2) items 0 [])))))]
    [js/install-array!
     (fn []
       (let [f (js/install-ctor! "Array" 1 js/array-fn js.rt/array-proto true)]
         (do (js/def-method! f "isArray" 1 js/array-is-array)
             (js/def-method! f "of" 0 js/array-of)
             (js/def-method! f "from" 1 js/array-from)
             :js/undefined)))]])


(defquoted ^:private class-definitions
  "T3 S3: classes. Heritage, the class constructor cell, descriptors, fields,
   private names and brand checks, and `super`. A class constructor cell is
   a function cell of kind :class or :derived with :nt? true, :fields
   [[key init] ...] (instance fields, init a host lambda of `this` or
   undefined) and :priv-methods [[pn entry] ...] (the private methods and
   accessors branded onto each instance). Private state lives in :privs
   {pn-ref entry} on the object's own cell, outside :props, so no reflection
   path sees it; an entry is {:kind :field :value v}, {:kind :method :fn f}
   or {:kind :accessor :get g :set s}. The map is looked up only."
  '[;; ------------------------------------------------------ heritage
    [js/class-heritage
     ;; `p` is :js/none for a class without extends. Answers
     ;; {:proto-parent .. :ctor-parent ..}.
     (fn [p]
       (if (= p :js/none)
         (assoc (assoc {} :proto-parent js.rt/obj-proto)
                :ctor-parent js.rt/fn-proto)
         (if (= p :js/null)
           (assoc (assoc {} :proto-parent :js/null) :ctor-parent js.rt/fn-proto)
           (if (js/is-constructor? p)
             (let [pp (js/get-prop p {:js/str "prototype"})]
               (if (if (js/object? pp) true (= pp :js/null))
                 (assoc (assoc {} :proto-parent pp) :ctor-parent p)
                 (js/throw-type-error
                   {:js/str "Class extends value does not have valid prototype property"})))
             (js/throw-type-error
               {:js/str "Class extends value is not a constructor or null"})))))]
    [js/make-class
     ;; the constructor cell, its prototype link and the back-link
     (fn [name length derived? ctor-parent proto code]
       (let [f (js/make-function-nt name
                                    length
                                    :strict
                                    (if derived? :derived :class)
                                    code
                                    true)]
         (do (cell/set! f
                        (assoc (assoc (assoc (cell/get f) :proto ctor-parent)
                                      :fields
                                      [])
                               :priv-methods
                               []))
             (js/define-own! f
                             {:js/str "prototype"}
                             (js/slot proto false false false))
             (js/define-own! proto
                             {:js/str "constructor"}
                             (js/slot f true false true))
             f)))]

    ;; ------------------------------------------------------ elements
    [js/def-class-method!
     (fn [target key f] (js/define-own! target key (js/slot f true false true)))]
    [js/def-class-accessor!
     ;; kind :get or :set; the halves of a pair merge
     (fn [target key kind f]
       (js/define-accessor-half! target key kind f false))]
    [js/private-name
     (fn [desc]
       (cell/new (assoc (assoc {} :js/type :private-name) :description desc)))]
    [js/private-name?
     (fn [k]
       (if (js/cell? k)
         (= (get (cell/get k) :js/type) :private-name)
         false))]
    [js/private-text
     (fn [pn] (get (cell/get pn) :description))]
    [js/add-field!
     (fn [f key init]
       (let [c (cell/get f)]
         (cell/set! f (assoc c :fields (conj (get c :fields) (conj (conj [] key) init))))))]
    [js/define-field!
     ;; define semantics: a public field is CreateDataPropertyOrThrow, a
     ;; private one a new private entry
     (fn [o key v]
       (if (js/private-name? key)
         (js/priv-define! o key (assoc (assoc {} :kind :field) :value v))
         (js/define-property-or-throw
           o
           key
           (assoc (assoc (assoc (assoc {} :value v) :writable true)
                         :enumerable
                         true)
                  :configurable
                  true))))]
    [js/run-fields-from
     (fn [fields o i]
       (if (< i (data/count fields))
         (let [fd (get fields i)
               init (get fd 1)]
           (do (js/define-field! o
                                 (get fd 0)
                                 (if (= init :js/undefined) :js/undefined (init o)))
               (js/run-fields-from fields o (+ i 1))))
         :js/undefined))]
    [js/run-fields!
     (fn [f o] (js/run-fields-from (get (cell/get f) :fields) o 0))]
    [js/run-static-from
     (fn [thunks f i]
       (if (< i (data/count thunks))
         (do ((get thunks i) f) (js/run-static-from thunks f (+ i 1)))
         :js/undefined))]
    [js/static-init! (fn [f thunks] (js/run-static-from thunks f 0))]

    ;; ------------------------------------------------------ private names
    [js/priv-entry
     (fn [o pn] (get (get (cell/get o) :privs {}) pn))]
    [js/priv-put!
     (fn [o pn entry]
       (let [c (cell/get o)]
         (cell/set! o (assoc c :privs (assoc (get c :privs {}) pn entry)))))]
    [js/priv-define!
     (fn [o pn entry]
       (if (nil? (js/priv-entry o pn))
         (js/priv-put! o pn entry)
         (js/throw-type-error
           (js/str-concat {:js/str "Cannot initialize "}
                          (js/str-concat (js/private-text pn)
                                         {:js/str " twice on the same object"})))))]
    [js/priv-missing
     (fn [pn verb]
       (js/throw-type-error
         (js/str-concat
           {:js/str "Cannot "}
           (js/str-concat
             verb
             (js/str-concat
               {:js/str " private member "}
               (js/str-concat
                 (js/private-text pn)
                 {:js/str " from an object whose class did not declare it"}))))))]
    [js/priv-entry-of
     (fn [o pn verb]
       (if (js/object? o)
         (let [e (js/priv-entry o pn)]
           (if (nil? e) (js/priv-missing pn verb) e))
         (js/priv-missing pn verb)))]
    [js/priv-get
     (fn [o pn]
       (let [e (js/priv-entry-of o pn {:js/str "read"})
             k (get e :kind)]
         (if (= k :field)
           (get e :value)
           (if (= k :method)
             (get e :fn)
             (if (= (get e :get) :js/undefined)
               (js/throw-type-error
                 (js/str-concat (js/private-text pn)
                                {:js/str " was defined without a getter"}))
               (js/call (get e :get) o []))))))]
    [js/priv-set
     (fn [o pn v]
       (let [e (js/priv-entry-of o pn {:js/str "write"})
             k (get e :kind)]
         (if (= k :field)
           (js/priv-put! o pn (assoc e :value v))
           (if (= k :method)
             (js/throw-type-error
               (js/str-concat {:js/str "Private method "}
                              (js/str-concat (js/private-text pn)
                                             {:js/str " is not writable"})))
             (if (= (get e :set) :js/undefined)
               (js/throw-type-error
                 (js/str-concat (js/private-text pn)
                                {:js/str " was defined without a setter"}))
               (do (js/call (get e :set) o (js/vconj [] v)) :js/undefined))))))]
    [js/priv-has
     (fn [o pn]
       (if (js/object? o)
         (not (nil? (js/priv-entry o pn)))
         (js/throw-type-error
           {:js/str "Cannot use 'in' operator to search for a private field in a non-object"})))]
    [js/priv-merge
     ;; the entry after adding a method (:method) or an accessor half
     ;; (:get / :set) `f` to `cur`
     (fn [cur kind f]
       (if (= kind :method)
         (assoc (assoc {} :kind :method) :fn f)
         (let [acc? (if (nil? cur) false (= (get cur :kind) :accessor))
               g (if (= kind :get) f (if acc? (get cur :get) :js/undefined))
               s (if (= kind :set) f (if acc? (get cur :set) :js/undefined))]
           (assoc (assoc (assoc {} :kind :accessor) :get g) :set s))))]
    [js/def-static-private!
     ;; a static private method or accessor half, installed on `f` itself
     (fn [f pn kind fn-value]
       (js/priv-put! f pn (js/priv-merge (js/priv-entry f pn) kind fn-value)))]
    [js/priv-methods-put
     (fn [ms pn entry i]
       (if (< i (data/count ms))
         (if (= (get (get ms i) 0) pn)
           (js/priv-methods-splice ms (conj (conj [] pn) entry) i)
           (js/priv-methods-put ms pn entry (+ i 1)))
         (conj ms (conj (conj [] pn) entry))))]
    [js/priv-methods-splice
     (fn [ms new-entry at]
       (js/priv-methods-splice-from ms new-entry at 0 []))]
    [js/priv-methods-splice-from
     (fn [ms new-entry at i acc]
       (if (< i (data/count ms))
         (js/priv-methods-splice-from ms new-entry at (+ i 1)
                                      (conj acc (if (= i at) new-entry (get ms i))))
         acc))]
    [js/priv-methods-find
     (fn [ms pn i]
       (if (< i (data/count ms))
         (if (= (get (get ms i) 0) pn) (get (get ms i) 1) (js/priv-methods-find ms pn (+ i 1)))
         nil))]
    [js/add-priv-method!
     ;; an instance private method or accessor half, branded onto each
     ;; instance by `js/brand!`
     (fn [f pn kind fn-value]
       (let [c (cell/get f)
             ms (get c :priv-methods)
             entry (js/priv-merge (js/priv-methods-find ms pn 0) kind fn-value)]
         (cell/set! f (assoc c :priv-methods (js/priv-methods-put ms pn entry 0)))))]
    [js/brand-from
     (fn [o ms i]
       (if (< i (data/count ms))
         (do (js/priv-define! o (get (get ms i) 0) (get (get ms i) 1))
             (js/brand-from o ms (+ i 1)))
         :js/undefined))]
    [js/brand!
     ;; install `f`'s private methods and accessors on the instance `o`,
     ;; before any field initializer runs
     (fn [o f] (js/brand-from o (get (cell/get f) :priv-methods) 0))]

    ;; ------------------------------------------------------ super
    [js/super-call
     ;; super(...args) in the derived constructor `f` (design order, F7):
     ;; construct the parent with new.target, then check the this-cell is
     ;; still uninitialized, bind it, brand, run the field initializers
     (fn [f nt thisc args]
       (let [parent (get (cell/get f) :proto)]
         (if (js/is-constructor? parent)
           (let [o (js/construct-with-nt parent args nt)]
             (if (= (cell/get thisc) :js/tdz)
               (do (cell/set! thisc o)
                   (js/brand! o f)
                   (js/run-fields! f o)
                   o)
               (js/throw-reference-error
                 {:js/str "Super constructor may only be called once"})))
           (js/throw-type-error
             {:js/str "Super constructor is not a constructor"}))))]
    [js/this-after-super
     (fn [thisc]
       (let [v (cell/get thisc)]
         (if (= v :js/tdz)
           (js/throw-reference-error
             {:js/str "Must call super constructor in derived class before accessing 'this' or returning from derived constructor"})
           v)))]
    [js/derived-result
     ;; the completion of a derived constructor body that finished with `r`
     (fn [thisc r]
       (if (js/object? r)
         r
         (if (= r :js/undefined)
           (js/this-after-super thisc)
           (js/throw-type-error
             {:js/str "Derived constructors may only return object or undefined"}))))]
    [js/super-parent
     (fn [home]
       (let [p (get (cell/get home) :proto)]
         (if (= p :js/null)
           (js/throw-type-error
             {:js/str "Cannot read properties of null (super)"})
           p)))]
    [js/super-get
     (fn [home key receiver] (js/get-prop-r (js/super-parent home) key receiver))]
    [js/super-set
     (fn [home key v receiver]
       (if (js/set-prop (js/super-parent home) key v receiver)
         v
         (js/throw-type-error
           (js/str-concat {:js/str "Cannot assign to read only property '"}
                          (js/str-concat (js/key-text key) {:js/str "'"})))))]])


(defquoted ^:private json-definitions
  "Stage 0 S8: JSON.stringify (replacer function and array, space, toJSON,
   cycles, BigInt, well-formed output for lone surrogates) and JSON.parse
   (no reviver, ledger L-T2-6)."
  '[[js/hex-digit (fn [d] (if (< d 10) (+ 48 d) (+ 87 d)))]
    [js/hex4-units
     (fn [u]
       (let [a (integer/floor-div-mod u 16)
             b (integer/floor-div-mod (get a 0) 16)
             c (integer/floor-div-mod (get b 0) 16)
             d (integer/floor-div-mod (get c 0) 16)]
         (js/vconj (js/vconj (js/vconj (js/vconj (js/vconj (js/vconj [] 92) 117)
                                                 (js/hex-digit (get d 1)))
                                       (js/hex-digit (get c 1)))
                             (js/hex-digit (get b 1)))
                   (js/hex-digit (get a 1)))))]
    [js/quote-units
     (fn [us i acc]
       (if (< i (data/count us))
         (let [u (get us i)]
           (if (= u 34)
             (js/quote-units us (+ i 1) (data/into acc [92 34]))
             (if (= u 92)
               (js/quote-units us (+ i 1) (data/into acc [92 92]))
               (if (= u 8)
                 (js/quote-units us (+ i 1) (data/into acc [92 98]))
                 (if (= u 9)
                   (js/quote-units us (+ i 1) (data/into acc [92 116]))
                   (if (= u 10)
                     (js/quote-units us (+ i 1) (data/into acc [92 110]))
                     (if (= u 12)
                       (js/quote-units us (+ i 1) (data/into acc [92 102]))
                       (if (= u 13)
                         (js/quote-units us (+ i 1) (data/into acc [92 114]))
                         (if (< u 32)
                           (js/quote-units us (+ i 1) (data/into acc (js/hex4-units u)))
                           (if (if (<= 55296 u) (<= u 56319) false)
                             (let [nx (get us (+ i 1))]
                               (if (if (nil? nx) false (if (<= 56320 nx) (<= nx 57343) false))
                                 (js/quote-units us (+ i 2) (conj (conj acc u) nx))
                                 (js/quote-units us (+ i 1) (data/into acc (js/hex4-units u)))))
                             (if (if (<= 56320 u) (<= u 57343) false)
                               (js/quote-units us (+ i 1) (data/into acc (js/hex4-units u)))
                               (js/quote-units us (+ i 1) (conj acc u)))))))))))))
         (conj acc 34)))]
    [js/json-quote (fn [s] (js/mk-str (js/quote-units (js/units-of s) 0 [34])))]
    [js/json-spaces
     (fn [n acc]
       (if (<= n 0) acc (js/json-spaces (- n 1) (js/str-concat acc {:js/str " "}))))]
    [js/json-gap
     (fn [sp]
       (let [k (js/kind-of sp)]
         (if (= k :number)
           (js/json-spaces (js/imin 10 (js/to-integer sp)) {:js/str ""})
           (if (= k :string)
             (js/str-slice sp 0 (js/imin 10 (js/str-unit-length sp)))
             {:js/str ""}))))]
    [js/json-plist
     (fn [items i acc]
       (if (< i (data/count items))
         (let [it (js/item-val (get items i))
               k (js/kind-of it)
               s (if (= k :string)
                   it
                   (if (= k :number) (js/to-string it) nil))]
           (js/json-plist items
                          (+ i 1)
                          (if (nil? s)
                            acc
                            (if (<= 0 (js/vec-index-of acc s 0)) acc (conj acc s)))))
         acc))]
    [js/json-enter!
     (fn [st v]
       (let [stack (get st :stack)]
         (if (<= 0 (js/vec-index-of (cell/get stack) v 0))
           (js/throw-type-error
             {:js/str "Converting circular structure to JSON"})
           (cell/set! stack (conj (cell/get stack) v)))))]
    [js/json-leave!
     (fn [st]
       (let [stack (get st :stack)
             cur (cell/get stack)]
         (cell/set! stack (data/subvec cur 0 (- (data/count cur) 1)))))]
    [js/json-join
     (fn [members sep] (js/join-items members sep 0 {:js/str ""}))]
    [js/json-wrap
     (fn [members open close gap indent inner]
       (if (= (data/count members) 0)
         (js/str-concat open close)
         (if (= gap {:js/str ""})
           (js/str-concat open
                          (js/str-concat (js/json-join members {:js/str ","}) close))
           (js/str-concat
             open
             (js/str-concat
               {:js/str "\n"}
               (js/str-concat
                 inner
                 (js/str-concat
                   (js/json-join members
                                 (js/str-concat {:js/str ",\n"} inner))
                   (js/str-concat {:js/str "\n"}
                                  (js/str-concat indent close)))))))))]
    [js/json-members
     (fn [st o keys i inner acc]
       (if (< i (data/count keys))
         (let [key (get keys i)
               s (js/json-prop st o key inner)]
           (js/json-members
             st
             o
             keys
             (+ i 1)
             inner
             (if (= s :js/undefined)
               acc
               (conj acc
                     (js/str-concat
                       (js/json-quote key)
                       (js/str-concat
                         {:js/str ":"}
                         (js/str-concat
                           (if (= (get st :gap) {:js/str ""}) {:js/str ""} {:js/str " "})
                           s)))))))
         acc))]
    [js/json-elements
     (fn [st a i n inner acc]
       (if (< i n)
         (let [s (js/json-prop st a (js/number->string (js/mknum i)) inner)]
           (js/json-elements st a (+ i 1) n inner
                             (conj acc (if (= s :js/undefined) {:js/str "null"} s))))
         acc))]
    [js/json-object
     (fn [st o indent]
       (do (js/json-enter! st o)
           (let [inner (js/str-concat indent (get st :gap))
                 pl (get st :plist)
                 keys (if (= pl :js/undefined) (js/own-enumerable-string-keys o) pl)
                 r (js/json-wrap (js/json-members st o keys 0 inner [])
                                 {:js/str "{"}
                                 {:js/str "}"}
                                 (get st :gap)
                                 indent
                                 inner)]
             (do (js/json-leave! st) r))))]
    [js/json-array
     (fn [st a indent]
       (do (js/json-enter! st a)
           (let [inner (js/str-concat indent (get st :gap))
                 r (js/json-wrap (js/json-elements st a 0 (js/length-of a) inner [])
                                 {:js/str "["}
                                 {:js/str "]"}
                                 (get st :gap)
                                 indent
                                 inner)]
             (do (js/json-leave! st) r))))]
    [js/json-value
     (fn [st v indent]
       (let [k (js/kind-of v)]
         (if (= k :null)
           {:js/str "null"}
           (if (= k :boolean)
             (if v {:js/str "true"} {:js/str "false"})
             (if (= k :string)
               (js/json-quote v)
               (if (= k :number)
                 (if (js/finite? (js/fval v))
                   (js/number->string v)
                   {:js/str "null"})
                 (if (= k :bigint)
                   (js/throw-type-error
                     {:js/str "Do not know how to serialize a BigInt"})
                   (if (= k :object)
                     (if (js/array? v)
                       (js/json-array st v indent)
                       (js/json-object st v indent))
                     :js/undefined))))))))]
    [js/json-prop
     (fn [st holder key indent]
       (let [v0 (js/get-value holder key)
             v1 (if (if (js/object? v0) true (= (js/kind-of v0) :bigint))
                  (let [tj (js/get-value v0 {:js/str "toJSON"})]
                    (if (js/callable? tj) (js/call tj v0 (js/vconj [] key)) v0))
                  v0)
             rep (get st :replacer)
             v2 (if (js/callable? rep) (js/call rep holder (js/pair key v1)) v1)]
         (js/json-value st v2 indent)))]
    [js/json-stringify
     (fn [this args]
       (let [rep (js/arg args 1)
             st (assoc (assoc (assoc (assoc {} :gap (js/json-gap (js/arg args 2)))
                                     :replacer
                                     rep)
                              :plist
                              (if (js/array? rep)
                                (js/json-plist (get (cell/get rep) :items) 0 [])
                                :js/undefined))
                       :stack
                       (cell/new []))
             w (js/make-object js.rt/obj-proto)]
         (do (js/create-data-prop! w {:js/str ""} (js/arg args 0))
             (js/json-prop st w {:js/str ""} {:js/str ""}))))]
    ;; ------------------------------------------------------------- parse
    [js/json-error
     (fn [] (js/throw-syntax-error {:js/str "Unexpected token in JSON"}))]
    [js/json-skip-ws
     (fn [us i]
       (if (< i (data/count us))
         (let [c (get us i)]
           (if (if (= c 32) true (if (= c 9) true (if (= c 10) true (= c 13))))
             (js/json-skip-ws us (+ i 1))
             i))
         i))]
    [js/json-hex4
     (fn [us i]
       (if (<= (+ i 4) (data/count us))
         (let [a (js/digit-value (get us i))
               b (js/digit-value (get us (+ i 1)))
               c (js/digit-value (get us (+ i 2)))
               d (js/digit-value (get us (+ i 3)))]
           (if (if (<= 0 a) (if (< a 16) (if (<= 0 b) (if (< b 16) (if (<= 0 c) (if (< c 16) (if (<= 0 d) (< d 16) false) false) false) false) false) false) false)
             (+ (* a 4096) (+ (* b 256) (+ (* c 16) d)))
             -1))
         -1))]
    [js/json-escape-unit
     (fn [e]
       (if (= e 34) 34
           (if (= e 92) 92
               (if (= e 47) 47
                   (if (= e 98) 8
                       (if (= e 102) 12
                           (if (= e 110) 10
                               (if (= e 114) 13 (if (= e 116) 9 -1)))))))))]
    [js/json-parse-string
     (fn [us i acc]
       (if (< i (data/count us))
         (let [c (get us i)]
           (if (= c 34)
             (js/pair (js/mk-str acc) (+ i 1))
             (if (= c 92)
               (let [e (get us (+ i 1))]
                 (if (= e 117)
                   (let [h (js/json-hex4 us (+ i 2))]
                     (if (< h 0)
                       (js/json-error)
                       (js/json-parse-string us (+ i 6) (conj acc h))))
                   (let [m (if (nil? e) -1 (js/json-escape-unit e))]
                     (if (< m 0)
                       (js/json-error)
                       (js/json-parse-string us (+ i 2) (conj acc m))))))
               (if (< c 32)
                 (js/json-error)
                 (js/json-parse-string us (+ i 1) (conj acc c))))))
         (js/json-error)))]
    [js/json-digits-after
     ;; one or more digits at i: the index past them, error when none
     (fn [us i]
       (let [j (js/scan-digits us i (data/count us))]
         (if (< i j) j (js/json-error))))]
    [js/json-parse-number
     (fn [us start]
       (let [n (data/count us)
             i (if (< start n) (if (= (get us start) 45) (+ start 1) start) start)
             c (if (< i n) (get us i) 0)
             j (if (= c 48)
                 (+ i 1)
                 (if (if (<= 49 c) (<= c 57) false)
                   (js/scan-digits us i n)
                   (js/json-error)))
             k (if (if (< j n) (= (get us j) 46) false)
                 (js/json-digits-after us (+ j 1))
                 j)
             m (if (if (< k n) (if (= (get us k) 101) true (= (get us k) 69)) false)
                 (let [s (if (< (+ k 1) n)
                           (if (if (= (get us (+ k 1)) 43) true (= (get us (+ k 1)) 45))
                             (+ k 2)
                             (+ k 1))
                           (+ k 1))]
                   (js/json-digits-after us s))
                 k)]
         (js/pair (js/string->number (js/mk-str (data/subvec us start m))) m)))]
    [js/json-literal
     (fn [us i word v]
       (if (js/units-match? us word i 0)
         (js/pair v (+ i (data/count word)))
         (js/json-error)))]
    [js/json-parse-value
     (fn [us i]
       (let [j (js/json-skip-ws us i)]
         (if (< j (data/count us))
           (let [c (get us j)]
             (if (= c 123)
               (js/json-parse-object us (+ j 1))
               (if (= c 91)
                 (js/json-parse-array us (+ j 1) [])
                 (if (= c 34)
                   (js/json-parse-string us (+ j 1) [])
                   (if (= c 116)
                     (js/json-literal us j [116 114 117 101] true)
                     (if (= c 102)
                       (js/json-literal us j [102 97 108 115 101] false)
                       (if (= c 110)
                         (js/json-literal us j [110 117 108 108] :js/null)
                         (js/json-parse-number us j))))))))
           (js/json-error))))]
    [js/json-parse-array
     (fn [us i acc]
       (let [j (js/json-skip-ws us i)]
         (if (if (< j (data/count us)) (= (get us j) 93) false)
           (if (= (data/count acc) 0)
             (js/pair (js/list->array acc) (+ j 1))
             (js/json-error))
           (let [r (js/json-parse-value us j)
                 k (js/json-skip-ws us (get r 1))
                 acc2 (conj acc (get r 0))
                 c (if (< k (data/count us)) (get us k) 0)]
             (if (= c 44)
               (js/json-parse-array us (+ k 1) acc2)
               (if (= c 93)
                 (js/pair (js/list->array acc2) (+ k 1))
                 (js/json-error)))))))]
    [js/json-parse-members
     (fn [us i o]
       (let [j (js/json-skip-ws us i)]
         (if (if (< j (data/count us)) (= (get us j) 34) false)
           (let [kr (js/json-parse-string us (+ j 1) [])
                 c1 (js/json-skip-ws us (get kr 1))]
             (if (if (< c1 (data/count us)) (= (get us c1) 58) false)
               (let [vr (js/json-parse-value us (+ c1 1))
                     k (js/json-skip-ws us (get vr 1))
                     c (if (< k (data/count us)) (get us k) 0)]
                 (do (js/create-data-prop! o (get kr 0) (get vr 0))
                     (if (= c 44)
                       (js/json-parse-members us (+ k 1) o)
                       (if (= c 125)
                         (js/pair o (+ k 1))
                         (js/json-error)))))
               (js/json-error)))
           (js/json-error))))]
    [js/json-parse-object
     (fn [us i]
       (let [j (js/json-skip-ws us i)
             o (js/make-object js.rt/obj-proto)]
         (if (if (< j (data/count us)) (= (get us j) 125) false)
           (js/pair o (+ j 1))
           (js/json-parse-members us j o))))]
    [js/json-parse
     (fn [this args]
       (let [us (js/units-of (js/to-string (js/arg args 0)))
             r (js/json-parse-value us 0)
             j (js/json-skip-ws us (get r 1))]
         (if (< j (data/count us)) (js/json-error) (get r 0))))]
    [js/install-json!
     (fn []
       (let [j (js/make-object js.rt/obj-proto)]
         (do (js/def-method! j "stringify" 3 js/json-stringify)
             (js/def-method! j "parse" 2 js/json-parse)
             (js/define-global! "JSON" j))))]])


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
    [["toString" 0 'js/object-to-string] ["valueOf" 0 'js/object-value-of]
     ["hasOwnProperty" 1 'js/object-has-own-property]
     ["isPrototypeOf" 1 'js/object-is-prototype-of]
     ["propertyIsEnumerable" 1 'js/object-property-is-enumerable]]]
   ['js.rt/array-proto
    [["push" 1 'js/array-push] ["pop" 0 'js/array-pop]
     ["shift" 0 'js/array-shift] ["unshift" 1 'js/array-unshift]
     ["join" 1 'js/array-join] ["toString" 0 'js/array-to-string]
     ["slice" 2 'js/array-slice] ["concat" 1 'js/array-concat]
     ["indexOf" 1 'js/array-index-of] ["includes" 1 'js/array-includes]
     ["at" 1 'js/array-at] ["reverse" 0 'js/array-reverse]
     ["forEach" 1 'js/array-for-each] ["map" 1 'js/array-map]
     ["filter" 1 'js/array-filter] ["reduce" 1 'js/array-reduce]
     ["find" 1 'js/array-find] ["findIndex" 1 'js/array-find-index]
     ["some" 1 'js/array-some] ["every" 1 'js/array-every]
     ["sort" 1 'js/array-sort] ["splice" 2 'js/array-splice]
     ["fill" 1 'js/array-fill] ["flat" 0 'js/array-flat]
     ["lastIndexOf" 1 'js/array-last-index-of]
     ["reduceRight" 1 'js/array-reduce-right]]]
   ['js.rt/fn-proto
    [["call" 1 'js/function-call] ["apply" 2 'js/function-apply]
     ["bind" 1 'js/function-bind]]]
   ['js.rt/str-proto
    [["charCodeAt" 1 'js/string-char-code-at]
     ["codePointAt" 1 'js/string-code-point-at] ["at" 1 'js/string-at]
     ["slice" 2 'js/string-slice] ["substring" 2 'js/string-substring]
     ["indexOf" 1 'js/string-index-of] ["concat" 1 'js/string-concat]
     ["toString" 0 'js/string-to-string] ["valueOf" 0 'js/string-to-string]]]
   ['js.rt/num-proto
    [["toString" 1 'js/number-to-string] ["valueOf" 0 'js/number-value-of]]]
   ['js.rt/bool-proto
    [["toString" 0 'js/boolean-to-string] ["valueOf" 0 'js/boolean-value-of]]]
   ['js.rt/bigint-proto
    [["toString" 0 'js/bigint-to-string] ["valueOf" 0 'js/bigint-value-of]]]
   ['js.rt/error-proto [["toString" 0 'js/error-to-string]]]
   ['js.rt/symbol-proto
    [["toString" 0 'js/symbol-to-string] ["valueOf" 0 'js/symbol-value-of]]]])


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
        [(define 'js.rt/symbol-proto '(js/make-object js.rt/obj-proto))
         (define 'js.rt/array-proto '(js/make-object js.rt/obj-proto))]
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
                         key
                         true)])
                error-classes)
        (mapv (fn [[key nm code]]
                (list 'js/install-ctor! nm 1 code key false))
              primitive-protos)
        ['(let [f (js/make-function {:js/str "Symbol"} 0 :lexical :builtin js/symbol-fn)]
            (do (js/define-own! f
                                {:js/str "prototype"}
                                (js/slot js.rt/symbol-proto false false false))
                (js/define-own! js.rt/symbol-proto
                                {:js/str "constructor"}
                                (js/slot f true false true))
                (js/define-own! js.rt/symbol-proto
                                {:js/str "description"}
                                (js/mk-accessor-slot
                                  (js/make-function {:js/str "get description"}
                                                    0
                                                    :strict
                                                    :getter
                                                    js/symbol-description)
                                  :js/undefined
                                  false
                                  true))
                (js/define-global! "Symbol" f)))
         '(js/install-object!)
         '(js/install-array!)
         '(js/install-json!)
         '(js/define-own! js.rt/global
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
  (into (into (into (into (into core-definitions object-definitions)
                          array-definitions)
                    json-definitions)
              class-definitions)
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
                          js.rt/fn-proto js.rt/symbol-proto js.rt/array-proto]
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
