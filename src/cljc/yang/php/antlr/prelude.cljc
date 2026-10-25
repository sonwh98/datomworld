(ns ^{:clj-kondo/config '{:lint-as {yang.php.antlr.prelude/defquoted clojure.core/def}}}
 yang.php.antlr.prelude
  "The PHP runtime profile, written as Universal AST
   (docs/design/yang.php.md sections 3.5, 7, 9). PHP semantics for operators,
   juggling, comparison, output and errors live here as guest code, not in
   Clojure: the lowering only emits applications of these names.

   Value encoding (P1):
     int             host integer in [-2^63, 2^63-1] (a BigInt carrier on JS
                     past 2^53); every operator computes the exact result
                     through the `integer` module and demotes to float when
                     it leaves the range
     float           untagged Jing float64 content (`data/float64`), the
                     shared ruling of yang.typescript.md section 2; the kind
                     test is `php/kind`, never a second tag. The prelude
                     holds no integral float literal (JS reads 1.0 as 1):
                     floats are built with `data/float64` or computed, and
                     host arithmetic only ever sees `data/float-value`
     string          a host string whose every char is one byte (a latin-1
                     carrier); the lowering resolves literal text to bytes
     bool            host true / false
     null            :php/null
     error object    {:php/type :obj :class name :ord n :props {\"message\"
                     \"code\" \"previous\"}}
     function        a cell {:php/type :function :name s :code (fn [args])}

   Runtime state is allocated by `php/init!`, once per task, under the slot
   `php.rt/state` (the literal `:php/uninit` until `php/init!` flips it to
   `:php/ready` as its last write):
     php.rt/ctx            the dynamic context {:handlers :depth :base :frame}
     php.rt/out            a cell holding the buffer stack, a vector of
                           buffers, each a vector of byte-string chunks;
                           `php/echo` conjs onto the last buffer, the
                           bottom buffer is never popped
     php.rt/globals        a cell holding the plain array shape of section
                           10.1 (`$GLOBALS`)
     php.rt/functions      a cell holding a map of lower-cased name to
                           function cell; builtins are pre-seeded
     php.rt/classes        a cell holding a map of class name to class record
                           (the Throwable hierarchy, data only)
     php.rt/next-ord       a cell, the per-task object ordinal
     php.rt/error-handler  a cell, :php/null or a callable
     php.rt/error-reporting a cell, the mask (E_ALL initially)
     php.rt/diag           a cell, the vector of diagnostic records
     php.rt/int-max, php.rt/int-min   exact integers computed in `init!`,
                           never literals (literals past 2^53 are not
                           portable)

   Errors are raised with `php/raise`; with no handler frame above them they
   reach the unit root, `php/run-main`, which answers
   `{:php/out s :php/diag [...] :php/uncaught nil-or-{:class :message}}`.

   Float text: `php/float-text x ndigit` is `zend_gcvt`; `ndigit` 14 is
   `echo` (precision), 17 is `var_dump` (serialize_precision -1, the
   shortest round trip from `integer/float-digits`).

   Two emitters, one source: `definitions` is the only definition list.
   `uast` is the bundled prelude; `module-uast` is the same list as the
   linker module `php`. The module emitter strips only the module's own
   namespace `php/`; `php.rt/*` stays qualified.

   Host names the prelude depends on and does not define: `host-names`."
  #?(:cljs (:require-macros [yang.php.antlr.prelude :refer [defquoted]]))
  (:require
    #?(:cljd [clojure.edn :as edn])
    [yang.core.prelude :as core]
    [yang.core.uast :as u]
    [yin.vm.integer :as integer]))


#?(:clj
   (defmacro defquoted
     "`def` of the quoted data `form`; ClojureDart reads it back from one EDN
      string (its quoted data compiles to straight-line Dart)."
     [name doc form]
     (if (find-ns 'cljd.compiler)
       (list 'def name doc (list 'edn/read-string (pr-str (second form))))
       (list 'def name doc form))))


(def host-names
  "Module exports the prelude calls."
  '#{cell/new cell/get cell/set! data/count data/subvec data/str-concat
     data/str-length data/str-join data/str-compare data/str->code-points
     data/code-points->str data/substring data/float64 data/float-value
     data/content= data/number? data/dissoc data/into
     integer/add integer/sub integer/neg integer/mul integer/compare
     integer/to-float integer/compare-float integer/true-div integer/pow
     integer/shift-left integer/shift-right integer/bit-and integer/bit-or
     integer/bit-xor integer/bit-not integer/from-float integer/quot-rem
     integer/floor-div-mod integer/format integer/parse
     integer/float-digits integer/decimal->float})


(defquoted ^:private core-definitions
  "Pure definitions: lambdas only, so defining them runs nothing."
  '[;; ---------------------------------------------------------------- basics
    [php/vconj (fn [xs x] (conj xs x))]
    [php/pair (fn [a b] (php/vconj (php/vconj [] a) b))]
    [php/f (fn [h] (data/float64 h))]
    [php/h (fn [x] (data/float-value x))]
    [php/inf (fn [] (data/float-value ##Inf))]
    [php/nan (fn [] (- (php/inf) (php/inf)))]
    [php/nan? (fn [h] (not (<= h h)))]
    [php/zero-h (fn [] (data/float-value 0))]
    ;; -0.0 is built as (* -1.0 0.0): ClojureDart compiles one-argument `-`
    ;; to (0 - x), and 0 - 0.0 is +0.0
    [php/neg-zero-h (fn [] (* (data/float-value -1) (data/float-value 0)))]
    [php/abs-h (fn [h] (if (< h 0) (- 0 h) h))]
    [php/neg-h (fn [h] (* (data/float-value -1) h))]
    [php/nan-const (fn [] (php/f (php/nan)))]
    [php/inf-const (fn [] (php/f (php/inf)))]

    ;; ---------------------------------------------------------------- kinds
    ;; A string is the only value `get` answers a char (or, empty, equals
    ;; "") for; typed maps answer :php/type; what is left is a number.
    [php/string?
     (fn [x] (if (= x "") true (not (nil? (get x 0)))))]
    [php/kind
     (fn [x]
       (if (= x :php/null)
         :null
         (if (= x true)
           :bool
           (if (= x false)
             :bool
             (if (nil? (get x :php/type))
               (if (php/string? x)
                 :string
                 (if (data/content= x (data/float64 (data/float-value x)))
                   :float
                   :int))
               :obj)))))]
    [php/int? (fn [x] (= (php/kind x) :int))]
    [php/float? (fn [x] (= (php/kind x) :float))]
    [php/numk? (fn [k] (if (= k :int) true (= k :float)))]
    [php/fl
     ;; the host double of an int or float
     (fn [x] (if (php/int? x) (integer/to-float x) (data/float-value x)))]
    [php/type-name
     (fn [k]
       (if (= k :string) "string"
           (if (= k :int) "int"
               (if (= k :float) "float"
                   (if (= k :bool) "bool"
                       (if (= k :null) "null" "mixed"))))))]

    ;; ---------------------------------------------------------------- escapes
    ;; The dynamic context is one record in one cell: every capture point
    ;; saves it and every escape restores it with :base left alone. The
    ;; handler stack is a chain of frames [kind payload rest n depth].
    [php/ctx
     (fn [handlers depth base frame]
       (assoc (assoc (assoc (assoc {} :handlers handlers) :depth depth)
                     :base base)
              :frame frame))]
    [php/restore!
     (fn [saved]
       (cell/set! php.rt/ctx
                  (assoc saved :base (get (cell/get php.rt/ctx) :base))))]
    [php/handlers (fn [] (get (cell/get php.rt/ctx) :handlers))]
    [php/set-handlers!
     (fn [hs] (cell/set! php.rt/ctx (assoc (cell/get php.rt/ctx) :handlers hs)))]
    [php/frame-depth (fn [hs] (if (nil? hs) 0 (get hs 3)))]
    [php/frame
     (fn [kind payload rest]
       (php/vconj (php/vconj (php/vconj (php/vconj (php/vconj [] kind) payload) rest)
                             (+ 1 (php/frame-depth rest)))
                  (get (cell/get php.rt/ctx) :depth)))]
    [php/pop-frame
     ;; the handler stack below frame hs, at the call depth hs was pushed at
     (fn [hs]
       (cell/set! php.rt/ctx
                  (assoc (assoc (cell/get php.rt/ctx) :handlers (get hs 2))
                         :depth (get hs 4))))]
    [php/raise
     ;; deliver the error to the innermost handler, running each :finally
     ;; thunk it unwinds through; the unit root always installs a handler,
     ;; so an empty stack is a defect and answers the error
     (fn [e]
       (let [hs (php/handlers)]
         (if (nil? hs)
           e
           (if (= (get hs 0) :finally)
             (do (php/pop-frame hs)
                 ((get hs 1) e)
                 (php/raise e))
             ((get hs 1) e)))))]
    [php/unwind-to
     ;; pop frames above frame count n, running each finally thunk with null
     (fn [n]
       (let [hs (php/handlers)]
         (if (nil? hs)
           :php/null
           (if (<= (get hs 3) n)
             :php/null
             (do (php/pop-frame hs)
                 (if (= (get hs 0) :finally) ((get hs 1) :php/null) :php/null)
                 (php/unwind-to n))))))]
    [php/try
     (fn [body handler orelse]
       (let [saved (cell/get php.rt/ctx)
             flag (cell/new :first)]
         ((fn [r]
            (if (= (cell/get flag) :first)
              (do (cell/set! flag :re-entered)
                  (php/set-handlers! (php/frame :handler r (get saved :handlers)))
                  (body)
                  (php/restore! saved)
                  (orelse))
              (do (php/restore! saved)
                  (handler r))))
          (%capture))))]
    [php/try-finally
     ;; normal exit runs `fin` here; raise and escapes run it as they
     ;; unwind through the :finally frame
     (fn [body fin]
       (let [saved (cell/get php.rt/ctx)]
         (do (php/set-handlers! (php/frame :finally fin (get saved :handlers)))
             (let [v (body)]
               (do (php/restore! saved)
                   (fin :php/null)
                   v)))))]
    [php/call-ec
     ;; `f` receives an escape: calling it unwinds to here and answers its
     ;; argument as this call's value (break, continue, return)
     (fn [f]
       (let [saved (cell/get php.rt/ctx)
             flag (cell/new :first)]
         ((fn [r]
            (if (= (cell/get flag) :first)
              (do (cell/set! flag :re-entered)
                  (f (fn [x]
                       (do (php/unwind-to (php/frame-depth (get saved :handlers)))
                           (php/restore! saved)
                           (r x)))))
              r))
          (%capture))))]

    ;; ---------------------------------------------------------------- errors
    [php/make-error
     (fn [cls msg]
       (let [n (cell/get php.rt/next-ord)]
         (do (cell/set! php.rt/next-ord (+ n 1))
             (assoc (assoc (assoc (assoc {} :php/type :obj) :class cls) :ord n)
                    :props
                    (assoc (assoc (assoc {} "message" msg) "code" 0)
                           "previous" :php/null)))))]
    [php/throw-error (fn [cls msg] (php/raise (php/make-error cls msg)))]
    [php/snapshot-exc
     (fn [e]
       (assoc (assoc {} :class (get e :class))
              :message (get (get e :props) "message")))]
    [php/warn-record
     (fn [errno msg]
       (assoc (assoc (assoc {} :php.diag/warning true) :errno errno)
              :message msg))]
    [php/warn-sink
     ;; the P1 sink: a record on php.rt/diag when the mask admits it; P5
     ;; replaces the cell with the composition's stream
     (fn [rec]
       (if (= (integer/bit-and (get rec :errno) (cell/get php.rt/error-reporting))
              0)
         :php/null
         (cell/set! php.rt/diag (php/vconj (cell/get php.rt/diag) rec))))]
    [php/invoke-handler
     ;; the user handler is unset while it runs, so its own warnings go to
     ;; the sink, and restored when it returns or an escape unwinds through it
     (fn [h errno msg]
       (php/try-finally
         (fn []
           (do (cell/set! php.rt/error-handler :php/null)
               (php/call (php/callable-fn h)
                         (php/vconj (php/vconj (php/vconj (php/vconj [] errno) msg) "")
                                    0))))
         (fn [x] (cell/set! php.rt/error-handler h))))]
    [php/warn
     ;; a handler is called whatever error_reporting says; returning false
     ;; falls through to the default sink
     (fn [errno msg]
       (let [rec (php/warn-record errno msg)
             h (cell/get php.rt/error-handler)]
         (if (= h :php/null)
           (php/warn-sink rec)
           (if (= (php/invoke-handler h errno msg) false)
             (php/warn-sink rec)
             :php/null))))]
    [php/silence
     ;; `@e`: error_reporting is the PHP 8 mask while `thunk` runs, restored
     ;; by an escape too; the handler is still invoked and observes the mask
     (fn [thunk]
       (let [saved (cell/get php.rt/error-reporting)]
         (php/try-finally
           (fn [] (do (cell/set! php.rt/error-reporting 4437) (thunk)))
           (fn [x] (cell/set! php.rt/error-reporting saved)))))]

    ;; ---------------------------------------------------------------- output
    [php/echo
     (fn [s]
       (let [bufs (cell/get php.rt/out)
             i (- (data/count bufs) 1)]
         (cell/set! php.rt/out (assoc bufs i (conj (get bufs i) s)))))]
    [php/zeros
     (fn [n acc] (if (> n 0) (php/zeros (- n 1) (data/str-concat acc "0")) acc))]
    [php/strip-zeros
     (fn [s]
       (let [n (data/str-length s)]
         (if (> n 1)
           (if (= (data/substring s (- n 1)) "0")
             (php/strip-zeros (data/substring s 0 (- n 1)))
             s)
           s)))]

    ;; ---------------------------------------------------------------- integers
    [php/in-range?
     (fn [n]
       (if (< (integer/compare n php.rt/int-max) 1)
         (> (integer/compare n php.rt/int-min) -1)
         false))]
    [php/int-result
     ;; an exact result: an int in range, else the nearest float
     (fn [n] (if (php/in-range? n) n (php/f (integer/to-float n))))]
    [php/int-lit
     ;; a decimal literal; past PHP_INT_MAX it is a float (PHP's rule)
     (fn [digits]
       (if (> (data/str-length digits) 19)
         (php/f (integer/decimal->float digits 0))
         (php/int-result (integer/parse digits))))]
    [php/int-lit-radix
     (fn [digits radix]
       (php/int-result (integer/parse digits radix)))]
    [php/wrap64
     ;; two's complement wrap into [-2^63, 2^63-1]
     (fn [n]
       (let [two64 (integer/shift-left 1 64)
             r (get (integer/floor-div-mod n two64) 1)]
         (if (> (integer/compare r php.rt/int-max) 0) (integer/sub r two64) r)))]
    [php/float->int
     (fn [h]
       (if (php/nan? h)
         0
         (if (= (php/abs-h h) (php/inf))
           0
           (php/wrap64 (integer/from-float h)))))]

    ;; ---------------------------------------------------------------- float text
    [php/gcvt
     ;; zend_gcvt: value = 0.digits x 10^decpt; exponential when decpt < -3
     ;; or decpt > ndigit, fixed otherwise
     (fn [digits decpt neg ndigit]
       (let [n (data/str-length digits)
             body
             (if (if (< decpt 0) (< decpt -3) (> decpt ndigit))
               (let [e (- decpt 1)
                     ae (if (< e 0) (- 0 e) e)]
                 (data/str-concat
                   (data/substring digits 0 1)
                   "."
                   (if (> n 1) (data/substring digits 1) "0")
                   "E"
                   (if (< e 0) "-" "+")
                   (integer/format ae)))
               (if (<= decpt 0)
                 (data/str-concat "0." (php/zeros (- 0 decpt) "") digits)
                 (if (>= decpt n)
                   (data/str-concat digits (php/zeros (- decpt n) ""))
                   (data/str-concat (data/substring digits 0 decpt)
                                    "."
                                    (data/substring digits decpt)))))]
         (if neg (data/str-concat "-" body) body)))]
    [php/scale-int
     ;; [m k] with x * 2^k = m exactly; doubling is exact
     (fn [x k]
       (let [t (integer/from-float x)]
         (if (= (integer/compare-float t x) 0)
           (php/pair t k)
           (php/scale-int (+ x x) (+ k 1)))))]
    [php/exact-digits
     ;; [digits decpt] of the exact decimal expansion of x > 0
     (fn [x]
       (let [mk (php/scale-int x 0)
             k (get mk 1)
             s (integer/format (integer/mul (get mk 0) (integer/pow 5 k)))]
         (php/pair s (- (data/str-length s) k))))]
    [php/incr-digits
     (fn [head decpt]
       (let [t (integer/format (integer/add (integer/parse head) 1))]
         (php/pair (php/strip-zeros t)
                   (if (> (data/str-length t) (data/str-length head))
                     (+ decpt 1)
                     decpt))))]
    [php/last-odd?
     (fn [head]
       (let [c (get (data/str->code-points
                      (data/substring head (- (data/str-length head) 1)))
                    0)]
         (= (get (integer/floor-div-mod (- c 48) 2) 1) 1)))]
    [php/round-digits
     ;; round the digit string s to ndigit digits, half to even on an exact
     ;; tie (zend_dtoa mode 2)
     (fn [s decpt ndigit]
       (let [n (data/str-length s)]
         (if (<= n ndigit)
           (php/pair (php/strip-zeros s) decpt)
           (let [head (data/substring s 0 ndigit)
                 rest (data/substring s ndigit)
                 c (data/str-compare
                     rest
                     (data/str-concat "5"
                                      (php/zeros (- (data/str-length rest) 1) "")))
                 up (if (> c 0) true (if (= c 0) (php/last-odd? head) false))]
             (if up
               (php/incr-digits head decpt)
               (php/pair (php/strip-zeros head) decpt))))))]
    [php/digits-prec
     ;; [digits decpt] of x > 0 rounded to ndigit digits: the shortest digits
     ;; when they fit (a grid point of the coarser grid, valid for normal
     ;; numbers, whose ulp is far finer than 14 digits), else the exact
     ;; expansion
     (fn [x ndigit]
       (let [de (integer/float-digits x)
             d (get de 0)]
         (if (if (<= (data/str-length d) ndigit)
               (>= x (data/float-value 2.2250738585072014E-308))
               false)
           (php/pair d (+ (get de 1) 1))
           (let [ex (php/exact-digits x)]
             (php/round-digits (get ex 0) (get ex 1) ndigit)))))]
    [php/float-text
     (fn [x ndigit]
       (let [h (data/float-value x)]
         (if (php/nan? h)
           "NAN"
           (if (= h (php/inf))
             "INF"
             (if (= h (php/neg-h (php/inf)))
               "-INF"
               (if (= h (php/zero-h))
                 (if (data/content= (data/float64 h) (data/float64 (php/neg-zero-h)))
                   "-0"
                   "0")
                 (let [neg (< h 0)
                       pos (php/abs-h h)
                       dd (if (= ndigit 17)
                            (let [de (integer/float-digits pos)]
                              (php/pair (get de 0) (+ (get de 1) 1)))
                            (php/digits-prec pos ndigit))]
                   (php/gcvt (get dd 0) (get dd 1) neg ndigit))))))))]

    ;; ---------------------------------------------------------------- numeric strings
    [php/ws?
     (fn [c]
       (if (= c 32) true
           (if (= c 9) true
               (if (= c 10) true
                   (if (= c 13) true
                       (if (= c 11) true (= c 12)))))))]
    [php/digit? (fn [c] (if (>= c 48) (<= c 57) false))]
    [php/skip-ws
     (fn [cs i n]
       (if (< i n) (if (php/ws? (get cs i)) (php/skip-ws cs (+ i 1) n) i) i))]
    [php/skip-digits
     (fn [cs i n]
       (if (< i n) (if (php/digit? (get cs i)) (php/skip-digits cs (+ i 1) n) i) i))]
    [php/cp-slice
     (fn [cs a b] (data/code-points->str (data/subvec cs a b)))]
    [php/skip-zeros
     (fn [cs i n]
       (if (< i n) (if (= (get cs i) 48) (php/skip-zeros cs (+ i 1) n) i) i))]
    [php/exp-value
     ;; the exponent digits [j1, j2) as an integer, capped so a huge exponent
     ;; saturates instead of asking `integer/parse` for a long digit string
     (fn [cs j1 j2 eneg]
       (let [k (php/skip-zeros cs j1 j2)
             v (if (> (- j2 k) 9)
                 999999999
                 (if (= k j2) 0 (integer/parse (php/cp-slice cs k j2))))]
         (if eneg (- 0 v) v)))]
    [php/num-value
     ;; the value of the number spelled by cs[i1, i2) . cs[i2+1, i3) e exp
     (fn [cs i1 i2 i3 dot neg isfloat exp]
       (let [idig (php/cp-slice cs i1 i2)
             fdig (if dot (php/cp-slice cs (+ i2 1) i3) "")
             isig (php/cp-slice cs (php/skip-zeros cs i1 i2) i2)]
         (if isfloat
           (let [v (integer/decimal->float (data/str-concat idig fdig)
                                           (- exp (data/str-length fdig)))]
             (php/f (if neg (php/neg-h v) v)))
           (if (> (data/str-length isig) 19)
             (let [v (integer/decimal->float isig 0)]
               (php/f (if neg (php/neg-h v) v)))
             (let [v (integer/parse (if (= isig "") "0" isig))]
               (php/int-result (if neg (integer/neg v) v)))))))]
    [php/num-prefix
     ;; [value end isfloat] for the longest number prefix at cs[i], or nil
     (fn [cs i n]
       (let [c0 (if (< i n) (get cs i) 0)
             neg (= c0 45)
             i1 (if (if neg true (= c0 43)) (+ i 1) i)
             i2 (php/skip-digits cs i1 n)
             dot (if (< i2 n) (= (get cs i2) 46) false)
             i3 (if dot (php/skip-digits cs (+ i2 1) n) i2)
             nfrac (if dot (- i3 (+ i2 1)) 0)]
         (if (= (+ (- i2 i1) nfrac) 0)
           nil
           (let [ec (if (< i3 n) (get cs i3) 0)
                 e? (if (= ec 101) true (= ec 69))
                 j0 (+ i3 1)
                 sc (if e? (if (< j0 n) (get cs j0) 0) 0)
                 eneg (= sc 45)
                 j1 (if (if eneg true (= sc 43)) (+ j0 1) j0)
                 j2 (if e? (php/skip-digits cs j1 n) j1)
                 hasexp (if e? (> j2 j1) false)
                 isfloat (if dot true hasexp)
                 exp (if hasexp (php/exp-value cs j1 j2 eneg) 0)]
             (php/vconj
               (php/pair (php/num-value cs i1 i2 i3 dot neg isfloat exp)
                         (if hasexp j2 i3))
               isfloat)))))]
    [php/numeric-string
     ;; nil when s has no number prefix; [:int v] / [:float v] when the whole
     ;; string is numeric (leading and trailing whitespace allowed);
     ;; [:leading v] when a number is followed by other text
     (fn [s]
       (let [cs (data/str->code-points s)
             n (data/count cs)
             r (php/num-prefix cs (php/skip-ws cs 0 n) n)]
         (if (nil? r)
           nil
           (if (= (php/skip-ws cs (get r 1) n) n)
             (php/pair (if (get r 2) :float :int) (get r 0))
             (php/pair :leading (get r 0))))))]
    [php/full-num? (fn [r] (if (nil? r) false (not (= (get r 0) :leading))))]

    ;; ---------------------------------------------------------------- juggling
    [php/to-bool
     (fn [v]
       (let [k (php/kind v)]
         (if (= k :bool) v
             (if (= k :null) false
                 (if (= k :int) (not (= v 0))
                     (if (= k :float) (not (= (php/h v) (php/zero-h)))
                         (if (= k :string) (if (= v "") false (not (= v "0")))
                             true)))))))]
    [php/to-string
     (fn [v]
       (let [k (php/kind v)]
         (if (= k :string) v
             (if (= k :int) (integer/format v)
                 (if (= k :null) ""
                     (if (= k :bool) (if v "1" "")
                         (if (= k :float) (php/float-text v 14)
                             (php/throw-error "Error" "Object could not be converted to string"))))))))]
    [php/str->number
     ;; the number a string casts to: its prefix, else 0
     (fn [s]
       (let [r (php/numeric-string s)] (if (nil? r) 0 (get r 1))))]
    [php/to-int
     (fn [v]
       (let [k (php/kind v)]
         (if (= k :int) v
             (if (= k :float) (php/float->int (php/h v))
                 (if (= k :null) 0
                     (if (= k :bool) (if v 1 0)
                         (if (= k :string)
                           (let [x (php/str->number v)]
                             (if (php/int? x) x (php/float->int (php/h x))))
                           (php/throw-error "TypeError" "Unsupported operand types"))))))))]
    [php/to-float
     (fn [v]
       (let [k (php/kind v)]
         (if (= k :float) v
             (if (= k :int) (php/f (integer/to-float v))
                 (if (= k :null) (php/f (php/zero-h))
                     (if (= k :bool) (php/f (data/float-value (if v 1 0)))
                         (if (= k :string) (php/to-float (php/str->number v))
                             (php/throw-error "TypeError" "Unsupported operand types"))))))))]
    [php/operand-error
     (fn [a b op]
       (php/throw-error
         "TypeError"
         (data/str-concat "Unsupported operand types: "
                          (php/type-name (php/kind a)) " " op " "
                          (php/type-name (php/kind b)))))]
    [php/num1
     ;; an arithmetic operand as an int or a float; a non-numeric string is a
     ;; TypeError, a leading-numeric one warns and uses its prefix
     (fn [x a b op]
       (let [k (php/kind x)]
         (if (php/numk? k) x
             (if (= k :null) 0
                 (if (= k :bool) (if x 1 0)
                     (if (= k :string)
                       (let [r (php/numeric-string x)]
                         (if (nil? r)
                           (php/operand-error a b op)
                           (do (if (= (get r 0) :leading)
                                 (php/warn 2 "A non-numeric value encountered")
                                 :php/null)
                               (get r 1))))
                       (php/operand-error a b op)))))))]
    [php/num2
     (fn [a b op]
       (php/pair (php/num1 a a b op) (php/num1 b a b op)))]

    ;; ---------------------------------------------------------------- arithmetic
    [php/add
     (fn [a b]
       (let [xy (php/num2 a b "+") x (get xy 0) y (get xy 1)]
         (if (php/int? x)
           (if (php/int? y)
             (php/int-result (integer/add x y))
             (php/f (+ (php/fl x) (php/fl y))))
           (php/f (+ (php/fl x) (php/fl y))))))]
    [php/sub
     (fn [a b]
       (let [xy (php/num2 a b "-") x (get xy 0) y (get xy 1)]
         (if (php/int? x)
           (if (php/int? y)
             (php/int-result (integer/sub x y))
             (php/f (- (php/fl x) (php/fl y))))
           (php/f (- (php/fl x) (php/fl y))))))]
    [php/mul
     (fn [a b]
       (let [xy (php/num2 a b "*") x (get xy 0) y (get xy 1)]
         (if (php/int? x)
           (if (php/int? y)
             (php/int-result (integer/mul x y))
             (php/f (* (php/fl x) (php/fl y))))
           (php/f (* (php/fl x) (php/fl y))))))]
    [php/zero-num?
     (fn [y] (if (php/int? y) (= y 0) (= (php/h y) (php/zero-h))))]
    [php/div
     (fn [a b]
       (let [xy (php/num2 a b "/") x (get xy 0) y (get xy 1)]
         (if (php/zero-num? y)
           (php/throw-error "DivisionByZeroError" "Division by zero")
           (if (php/int? x)
             (if (php/int? y)
               (let [qr (integer/quot-rem x y)]
                 (if (= (get qr 1) 0)
                   (php/int-result (get qr 0))
                   (php/f (integer/true-div x y))))
               (php/f (/ (php/fl x) (php/fl y))))
             (php/f (/ (php/fl x) (php/fl y)))))))]
    [php/mod
     (fn [a b]
       (let [xy (php/num2 a b "%")
             x (php/to-int (get xy 0))
             y (php/to-int (get xy 1))]
         (if (= y 0)
           (php/throw-error "DivisionByZeroError" "Modulo by zero")
           (get (integer/quot-rem x y) 1))))]
    [php/arg-type-error
     (fn [fname idx aname tname v]
       (php/throw-error
         "TypeError"
         (data/str-concat fname "(): Argument #" (integer/format idx) " ($"
                          aname ") must be of type " tname ", "
                          (php/type-name (php/kind v)) " given")))]
    [php/arg-num
     ;; the parameter-coercion seam for a typed numeric builtin parameter:
     ;; a numeric string coerces, a non-numeric or leading-numeric one is a
     ;; TypeError; null and bool coerce (null is deprecated in PHP, not here)
     (fn [v fname idx aname tname]
       (let [k (php/kind v)]
         (if (php/numk? k) v
             (if (= k :null) 0
                 (if (= k :bool) (if v 1 0)
                     (if (= k :string)
                       (let [r (php/numeric-string v)]
                         (if (php/full-num? r)
                           (get r 1)
                           (php/arg-type-error fname idx aname tname v)))
                       (php/arg-type-error fname idx aname tname v)))))))]
    [php/arg-int
     (fn [v fname idx aname]
       (php/to-int (php/arg-num v fname idx aname "int")))]
    [php/arg-float
     (fn [v fname idx aname]
       (php/to-float (php/arg-num v fname idx aname "float")))]
    [php/intdiv
     (fn [a b]
       (let [x (php/to-int a) y (php/to-int b)]
         (if (= y 0)
           (php/throw-error "DivisionByZeroError" "Division by zero")
           (let [q (get (integer/quot-rem x y) 0)]
             (if (php/in-range? q)
               q
               (php/throw-error
                 "ArithmeticError"
                 "Division of PHP_INT_MIN by -1 is not an integer"))))))]
    [php/neg
     (fn [a]
       (let [x (php/num1 a a 0 "*")]
         (if (php/int? x)
           (php/int-result (integer/neg x))
           (php/f (php/neg-h (php/h x))))))]
    [php/plus (fn [a] (php/num1 a a 0 "*"))]
    [php/fmod-pos
     ;; fmod for doubles x >= 0, y > 0, exactly: each subtraction is of
     ;; values within a factor of two (Sterbenz), as in long division
     (fn [x y]
       (if (< x y)
         x
         (let [r (php/fmod-pos x (+ y y))]
           (if (< r y) r (- r y)))))]
    [php/fmod
     (fn [a b]
       (let [x (php/h (php/to-float a))
             y (php/h (php/to-float b))
             ax (php/abs-h x)
             ay (php/abs-h y)]
         (if (php/nan? x) (php/f x)
             (if (php/nan? y) (php/f y)
                 (if (= ax (php/inf)) (php/nan-const)
                     (if (= y (php/zero-h)) (php/nan-const)
                         (if (= ay (php/inf)) (php/f x)
                             (let [m (php/fmod-pos ax ay)]
                               (php/f (if (< x 0) (php/neg-h m) m))))))))))]
    [php/pow-sq
     ;; x^n for an exact integer n >= 0 by squaring on doubles
     (fn [x n]
       (if (= n 0)
         (data/float-value 1)
         (let [half (get (integer/floor-div-mod n 2) 0)
               r (php/pow-sq (* x x) half)]
           (if (= (get (integer/floor-div-mod n 2) 1) 1) (* r x) r))))]
    [php/pow-neg
     ;; x ** -n for an integer n > 0: 1 / x^n while x^n is finite, so every
     ;; exact case is exact; when x^n overflows, the reciprocals of its two
     ;; halves, so 2 ** -1074 is 5e-324. Underflow of x^n is INF.
     (fn [x n]
       (let [p (php/pow-sq x n)]
         (if (= (php/abs-h p) (php/inf))
           (let [h (get (integer/floor-div-mod n 2) 0)]
             (* (/ (data/float-value 1) (php/pow-sq x h))
                (/ (data/float-value 1) (php/pow-sq x (integer/sub n h)))))
           (if (= p (php/zero-h))
             (if (< x 0)
               (if (= (get (integer/floor-div-mod n 2) 1) 1)
                 (php/neg-h (php/inf))
                 (php/inf))
               (php/inf))
             (/ (data/float-value 1) p)))))]
    [php/pow-float
     (fn [x y]
       (if (= y (php/zero-h))
         (php/f (data/float-value 1))
         (if (php/nan? x) (php/f x)
             (if (php/nan? y) (php/f y)
                 (if (= (php/abs-h y) (php/inf))
                   (php/throw-error "Error" "php/pow: infinite exponent is not supported yet")
                   (let [n (integer/from-float y)]
                     (if (= (integer/compare-float n y) 0)
                       (if (< n 0)
                         (if (= x (php/zero-h))
                           (php/inf-const)
                           (php/f (php/pow-neg x (integer/neg n))))
                         (php/f (php/pow-sq x n)))
                       (php/throw-error
                         "Error"
                         "** with a fractional exponent is not supported yet (yang.php known gap G-pow-frac)"))))))))]
    [php/pow-int
     (fn [x y]
       (if (= x 0)
         (if (= y 0) 1 0)
         (if (= x 1)
           1
           (if (= x -1)
             (if (= (get (integer/floor-div-mod y 2) 1) 0) 1 -1)
             (if (>= y 64)
               (php/pow-float (integer/to-float x) (integer/to-float y))
               (php/int-result (integer/pow x y)))))))]
    [php/pow
     (fn [a b]
       (let [xy (php/num2 a b "**") x (get xy 0) y (get xy 1)]
         (if (php/int? x)
           (if (php/int? y)
             (if (< y 0)
               (php/pow-float (integer/to-float x) (integer/to-float y))
               (php/pow-int x y))
             (php/pow-float (integer/to-float x) (php/h y)))
           (php/pow-float (php/h x) (php/fl y)))))]
    [php/concat
     (fn [a b]
       (let [x (php/to-string a)] (data/str-concat x (php/to-string b))))]

    ;; ---------------------------------------------------------------- bitwise
    [php/bit-operands
     (fn [a b op]
       (if (if (php/string? a) (php/string? b) false)
         (php/throw-error "Error" "php/bitwise: byte-string operands are not supported yet")
         (php/pair (php/to-int (php/num1 a a b op)) (php/to-int (php/num1 b a b op)))))]
    [php/bit-and
     (fn [a b]
       (let [xy (php/bit-operands a b "&")]
         (integer/bit-and (get xy 0) (get xy 1))))]
    [php/bit-or
     (fn [a b]
       (let [xy (php/bit-operands a b "|")]
         (integer/bit-or (get xy 0) (get xy 1))))]
    [php/bit-xor
     (fn [a b]
       (let [xy (php/bit-operands a b "^")]
         (integer/bit-xor (get xy 0) (get xy 1))))]
    [php/bit-not (fn [a] (integer/bit-not (php/to-int (php/num1 a a 0 "~"))))]
    [php/shl
     (fn [a b]
       (let [xy (php/bit-operands a b "<<") x (get xy 0) n (get xy 1)]
         (if (< n 0)
           (php/throw-error "ArithmeticError" "Bit shift by negative number")
           (if (>= n 64) 0 (php/wrap64 (integer/shift-left x n))))))]
    [php/shr
     (fn [a b]
       (let [xy (php/bit-operands a b ">>") x (get xy 0) n (get xy 1)]
         (if (< n 0)
           (php/throw-error "ArithmeticError" "Bit shift by negative number")
           (if (>= n 64)
             (if (< x 0) -1 0)
             (integer/shift-right x n)))))]

    ;; ---------------------------------------------------------------- comparison
    [php/cmp-h (fn [a b] (if (= a b) 0 (if (< a b) -1 1)))]
    [php/cmp-num
     (fn [x y]
       (if (php/int? x)
         (if (php/int? y)
           (integer/compare x y)
           (php/cmp-h (php/fl x) (php/fl y)))
         (php/cmp-h (php/fl x) (php/fl y))))]
    [php/nan-value?
     (fn [x] (if (php/float? x) (php/nan? (php/h x)) false))]
    [php/cmp-num-str
     ;; a number against a string: numerically when the string is numeric,
     ;; else as strings; NaN orders after everything
     (fn [n s]
       (if (php/nan-value? n)
         1
         (let [r (php/numeric-string s)]
           (if (php/full-num? r)
             (php/cmp-num n (get r 1))
             (data/str-compare (php/to-string n) s)))))]
    [php/cmp-strings
     (fn [a b]
       (let [ra (php/numeric-string a)]
         (if (php/full-num? ra)
           (let [rb (php/numeric-string b)]
             (if (php/full-num? rb)
               (php/cmp-num (get ra 1) (get rb 1))
               (data/str-compare a b)))
           (data/str-compare a b))))]
    [php/cmp-bool
     (fn [a b]
       (let [x (php/to-bool a) y (php/to-bool b)]
         (if (= x y) 0 (if x 1 -1))))]
    [php/cmp
     ;; zend_compare over the scalar kinds
     (fn [a b]
       (let [ka (php/kind a) kb (php/kind b)]
         (if (php/numk? ka)
           (if (php/numk? kb)
             (php/cmp-num a b)
             (if (= kb :string)
               (php/cmp-num-str a b)
               (php/cmp-bool a b)))
           (if (= ka :string)
             (if (= kb :string)
               (php/cmp-strings a b)
               (if (php/numk? kb)
                 (if (php/nan-value? b) 1 (- 0 (php/cmp-num-str b a)))
                 (if (= kb :null)
                   (if (= a "") 0 1)
                   (php/cmp-bool a b))))
             (if (= ka :null)
               (if (= kb :string)
                 (if (= b "") 0 -1)
                 (php/cmp-bool a b))
               (php/cmp-bool a b))))))]
    [php/loose-eq (fn [a b] (= (php/cmp a b) 0))]
    [php/loose-ne (fn [a b] (not (= (php/cmp a b) 0)))]
    [php/lt (fn [a b] (< (php/cmp a b) 0))]
    [php/le (fn [a b] (<= (php/cmp a b) 0))]
    ;; `a > b` is `b < a`: the swap decides NaN
    [php/gt (fn [a b] (< (php/cmp b a) 0))]
    [php/ge (fn [a b] (<= (php/cmp b a) 0))]
    [php/spaceship (fn [a b] (php/cmp a b))]
    [php/identical
     (fn [a b]
       (let [ka (php/kind a)]
         (if (= ka (php/kind b))
           (if (= ka :int)
             (= (integer/compare a b) 0)
             (if (= ka :float)
               (= (php/h a) (php/h b))
               (= a b)))
           false)))]
    [php/not-identical (fn [a b] (not (php/identical a b)))]

    ;; ---------------------------------------------------------------- var_dump
    [php/var-dump
     (fn [v]
       (let [k (php/kind v)]
         (if (= k :null)
           (php/echo "NULL\n")
           (if (= k :bool)
             (php/echo (if v "bool(true)\n" "bool(false)\n"))
             (if (= k :int)
               (php/echo (data/str-concat "int(" (integer/format v) ")\n"))
               (if (= k :float)
                 (php/echo (data/str-concat "float(" (php/float-text v 17) ")\n"))
                 (if (= k :string)
                   (php/echo (data/str-concat "string("
                                              (integer/format (data/str-length v))
                                              ") \""
                                              v
                                              "\"\n"))
                   (php/throw-error "Error" "var_dump: this value kind is not supported yet"))))))))]

    ;; ---------------------------------------------------------------- variables
    ;; Top-level variables live in `php.rt/globals` (the `$GLOBALS` array
    ;; shape); a function's variables are cells holding :php/undef until set.
    [php/undef-warning
     (fn [name]
       (php/warn 2 (data/str-concat "Undefined variable $" name)))]
    [php/global-peek
     (fn [name]
       (let [v (get (get (cell/get php.rt/globals) :index) name)]
         (if (nil? v) :php/null v)))]
    [php/global-get
     (fn [name]
       (let [v (get (get (cell/get php.rt/globals) :index) name)]
         (if (nil? v)
           (do (php/undef-warning name) :php/null)
           v)))]
    [php/global-set!
     (fn [name v]
       (let [g (cell/get php.rt/globals)
             idx (get g :index)]
         (do (cell/set! php.rt/globals
                        (assoc (assoc g :index (assoc idx name v))
                               :order
                               (if (nil? (get idx name))
                                 (php/vconj (get g :order) name)
                                 (get g :order))))
             v)))]
    [php/global-isset? (fn [name] (not (= (php/global-peek name) :php/null)))]
    [php/vec-remove
     (fn [xs x i n acc]
       (if (< i n)
         (php/vec-remove xs x (+ i 1) n
                         (if (= (get xs i) x) acc (php/vconj acc (get xs i))))
         acc))]
    [php/global-unset!
     (fn [name]
       (let [g (cell/get php.rt/globals)]
         (do (cell/set! php.rt/globals
                        (assoc (assoc g :index (data/dissoc (get g :index) name))
                               :order
                               (php/vec-remove (get g :order) name 0
                                               (data/count (get g :order)) [])))
             :php/null)))]
    [php/local-peek
     (fn [c] (let [v (cell/get c)] (if (= v :php/undef) :php/null v)))]
    [php/local-get
     (fn [c name]
       (let [v (cell/get c)]
         (if (= v :php/undef)
           (do (php/undef-warning name) :php/null)
           v)))]
    [php/local-set! (fn [c v] (do (cell/set! c v) v))]
    [php/local-isset? (fn [c] (not (= (php/local-peek c) :php/null)))]
    [php/local-unset! (fn [c] (do (cell/set! c :php/undef) :php/null))]

    ;; ---------------------------------------------------------------- ++ and --
    [php/lower-cp (fn [c] (if (>= c 65) (if (<= c 90) (+ c 32) c) c))]
    [php/lc-loop
     (fn [cs i n acc]
       (if (< i n)
         (php/lc-loop cs (+ i 1) n (php/vconj acc (php/lower-cp (get cs i))))
         acc))]
    [php/lc
     ;; ASCII lower case, as PHP 8 does for function names
     (fn [s]
       (let [cs (data/str->code-points s)]
         (data/code-points->str (php/lc-loop cs 0 (data/count cs) []))))]
    [php/carry
     (fn [cs i wrap prefix]
       (let [cs2 (assoc cs i wrap)]
         (if (= i 0)
           (data/into (php/vconj [] prefix) cs2)
           (php/inc-at cs2 (- i 1)))))]
    [php/inc-at
     ;; Perl-style string increment from position i; a non-alphanumeric
     ;; character stops the carry
     (fn [cs i]
       (let [c (get cs i)]
         (if (if (>= c 97) (<= c 122) false)
           (if (= c 122) (php/carry cs i 97 97) (assoc cs i (+ c 1)))
           (if (if (>= c 65) (<= c 90) false)
             (if (= c 90) (php/carry cs i 65 65) (assoc cs i (+ c 1)))
             (if (if (>= c 48) (<= c 57) false)
               (if (= c 57) (php/carry cs i 48 49) (assoc cs i (+ c 1)))
               cs)))))]
    [php/inc-string
     (fn [s]
       (if (= s "")
         "1"
         (let [r (php/numeric-string s)]
           (if (php/full-num? r)
             (php/add (get r 1) 1)
             (let [cs (data/str->code-points s)]
               (data/code-points->str (php/inc-at cs (- (data/count cs) 1))))))))]
    [php/inc
     (fn [v]
       (let [k (php/kind v)]
         (if (php/numk? k) (php/add v 1)
             (if (= k :null) 1
                 (if (= k :bool) v
                     (if (= k :string) (php/inc-string v)
                         (php/throw-error "TypeError" "Cannot increment this value")))))))]
    [php/dec
     (fn [v]
       (let [k (php/kind v)]
         (if (php/numk? k) (php/sub v 1)
             (if (= k :null) :php/null
                 (if (= k :bool) v
                     (if (= k :string)
                       (if (= v "")
                         -1
                         (let [r (php/numeric-string v)]
                           (if (php/full-num? r) (php/sub (get r 1) 1) v)))
                       (php/throw-error "TypeError" "Cannot decrement this value")))))))]

    ;; ---------------------------------------------------------------- functions
    [php/make-statics
     ;; one slot per `static` name, :php/undef until its statement first runs
     (fn [names i n acc]
       (if (< i n)
         (php/make-statics names (+ i 1) n (assoc acc (get names i) (cell/new :php/undef)))
         acc))]
    [php/make-function
     ;; the function cell; `code` is (fn [fn-cell args]) so the body can
     ;; reach its own statics
     (fn [name spec code statics]
       (cell/new (assoc (assoc (assoc (assoc (assoc {} :php/type :function) :name name)
                                      :spec spec)
                               :code code)
                        :statics
                        (php/make-statics statics 0 (data/count statics) {}))))]
    [php/register!
     (fn [name code]
       (cell/set! php.rt/functions
                  (assoc (cell/get php.rt/functions)
                         name
                         (php/make-function name :php/null (fn [f args] (code args)) []))))]
    [php/declare-function!
     (fn [lc f]
       (if (nil? (get (cell/get php.rt/functions) lc))
         (do (cell/set! php.rt/functions (assoc (cell/get php.rt/functions) lc f))
             :php/null)
         (php/throw-error "Error"
                          (data/str-concat "Cannot redeclare "
                                           (get (cell/get f) :name)
                                           "()"))))]
    [php/static-slot (fn [f name] (get (get (cell/get f) :statics) name))]
    [php/static-init!
     ;; runs the initializer when execution first reaches the statement,
     ;; once per function: the slot survives calls and recursion
     (fn [f name thunk]
       (let [slot (php/static-slot f name)]
         (if (= (cell/get slot) :php/undef)
           (do (cell/set! slot (thunk)) :php/null)
           :php/null)))]
    [php/too-few
     (fn [name passed required exact]
       (php/throw-error
         "ArgumentCountError"
         (data/str-concat "Too few arguments to function " name "(), "
                          (integer/format passed) " passed and "
                          (if exact "exactly " "at least ")
                          (integer/format required) " expected")))]
    [php/resolve-fn
     ;; the function cell of lower-cased `lc`, before any argument runs
     (fn [lc written]
       (let [f (get (cell/get php.rt/functions) lc)]
         (if (nil? f)
           (php/throw-error "Error"
                            (data/str-concat "Call to undefined function "
                                             written "()"))
           f)))]
    [php/call
     (fn [f args] ((get (cell/get f) :code) f args))]
    [php/callable-fn
     ;; the function cell of a string callable (closures are P3)
     (fn [h] (php/resolve-fn (php/lc h) h))]
    [php/arg (fn [args i] (get args i))]
    [php/arity-error
     (fn [name want args]
       (php/throw-error
         "ArgumentCountError"
         (data/str-concat name "() expects " want " argument"
                          (if (= want "exactly 1") "" "s") ", "
                          (integer/format (data/count args)) " given")))]
    [php/bi-var-dump
     (fn [args]
       (if (= (data/count args) 0)
         (php/throw-error "ArgumentCountError"
                          "var_dump() expects at least 1 argument, 0 given")
         (php/var-dump-each args 0)))]
    [php/var-dump-each
     (fn [args i]
       (if (< i (data/count args))
         (do (php/var-dump (get args i))
             (php/var-dump-each args (+ i 1)))
         :php/null))]
    [php/bi-intdiv
     (fn [args]
       (if (= (data/count args) 2)
         (php/intdiv (php/arg-int (get args 0) "intdiv" 1 "num1")
                     (php/arg-int (get args 1) "intdiv" 2 "num2"))
         (php/arity-error "intdiv" "exactly 2" args)))]
    [php/bi-fmod
     (fn [args]
       (if (= (data/count args) 2)
         (php/fmod (php/arg-float (get args 0) "fmod" 1 "num1")
                   (php/arg-float (get args 1) "fmod" 2 "num2"))
         (php/arity-error "fmod" "exactly 2" args)))]
    [php/bi-strlen
     (fn [args]
       (if (= (data/count args) 1)
         (data/str-length (php/to-string (get args 0)))
         (php/arity-error "strlen" "exactly 1" args)))]
    [php/bi-intval
     (fn [args]
       (if (= (data/count args) 1)
         (php/to-int (get args 0))
         (php/arity-error "intval" "exactly 1" args)))]
    [php/bi-floatval
     (fn [args]
       (if (= (data/count args) 1)
         (php/to-float (get args 0))
         (php/arity-error "floatval" "exactly 1" args)))]
    [php/bi-strval
     (fn [args]
       (if (= (data/count args) 1)
         (php/to-string (get args 0))
         (php/arity-error "strval" "exactly 1" args)))]
    [php/bi-boolval
     (fn [args]
       (if (= (data/count args) 1)
         (php/to-bool (get args 0))
         (php/arity-error "boolval" "exactly 1" args)))]
    [php/bi-set-error-handler
     (fn [args]
       (if (< (data/count args) 1)
         (php/throw-error "ArgumentCountError"
                          "set_error_handler() expects at least 1 argument, 0 given")
         (let [old (cell/get php.rt/error-handler)]
           (do (cell/set! php.rt/error-handler (get args 0))
               old))))]
    [php/bi-restore-error-handler
     ;; one level in P1: the previous handler is not remembered (P5)
     (fn [args] (do (cell/set! php.rt/error-handler :php/null) true))]
    [php/bi-error-reporting
     (fn [args]
       (let [old (cell/get php.rt/error-reporting)]
         (do (if (> (data/count args) 0)
               (if (= (get args 0) :php/null)
                 :php/null
                 (cell/set! php.rt/error-reporting (php/to-int (get args 0))))
               :php/null)
             old)))]
    [php/bi-is-nan
     (fn [args]
       (if (= (data/count args) 1)
         (php/nan-value? (php/to-float (get args 0)))
         (php/arity-error "is_nan" "exactly 1" args)))]

    ;; ---------------------------------------------------------------- unit
    [php/run-main
     ;; the unit root: output, diagnostics and the uncaught error, if any
     (fn [body]
       (do (cell/set! php.rt/out [[]])
           (cell/set! php.rt/ctx (php/ctx nil 0 0 nil))
           (let [exc (php/try (fn [] (do (body) :php/null))
                              (fn [e] e)
                              (fn [] :php/null))]
             (assoc (assoc (assoc {} :php/out
                                  (data/str-join "" (get (cell/get php.rt/out) 0)))
                           :php/diag (cell/get php.rt/diag))
                    :php/uncaught
                    (if (= exc :php/null) nil (php/snapshot-exc exc))))))]])


(def ^:private builtins
  "The functions `php/init!` registers: PHP name to prelude definition."
  [["var_dump" 'php/bi-var-dump] ["intdiv" 'php/bi-intdiv] ["fmod" 'php/bi-fmod]
   ["strlen" 'php/bi-strlen] ["intval" 'php/bi-intval]
   ["floatval" 'php/bi-floatval] ["strval" 'php/bi-strval]
   ["boolval" 'php/bi-boolval] ["is_nan" 'php/bi-is-nan]
   ["set_error_handler" 'php/bi-set-error-handler]
   ["restore_error_handler" 'php/bi-restore-error-handler]
   ["error_reporting" 'php/bi-error-reporting]])


(def builtin-names
  "The lower-cased names of the functions `php/init!` registers."
  (set (map first builtins)))


(def ^:private error-classes
  "The Throwable hierarchy P1 raises, data only (P4 owns the class model)."
  [["Throwable" nil] ["Exception" "Throwable"] ["Error" "Throwable"]
   ["TypeError" "Error"] ["ValueError" "Error"] ["ArithmeticError" "Error"]
   ["DivisionByZeroError" "ArithmeticError"] ["ArgumentCountError" "TypeError"]])


(def ^:private define core/define)


(defn- init-form
  "`php/init!`'s body: the runtime cells, the builtins, the ready flag, under
   the uninit guard."
  []
  (list 'fn []
        (list 'if (list '= 'php.rt/state :php/uninit)
              (concat
                ['do
                 (define 'php.rt/ctx '(cell/new (php/ctx nil 0 0 nil)))
                 (define 'php.rt/out '(cell/new [[]]))
                 (define 'php.rt/globals
                   '(cell/new {:php/type :array,
                               :index {},
                               :order [],
                               :next-index 0}))
                 (define 'php.rt/functions '(cell/new {}))
                 (define 'php.rt/classes
                   (list 'cell/new
                         (into {}
                               (map (fn [[nm parent]]
                                      [nm {:php/type :class,
                                           :name nm,
                                           :parent (or parent :php/null)}]))
                               error-classes)))
                 (define 'php.rt/next-ord '(cell/new 1))
                 (define 'php.rt/error-handler '(cell/new :php/null))
                 ;; E_ALL
                 (define 'php.rt/error-reporting '(cell/new 32767))
                 (define 'php.rt/diag '(cell/new []))
                 (define 'php.rt/int-max
                   '(integer/sub (integer/shift-left 1 63) 1))
                 (define 'php.rt/int-min
                   '(integer/neg (integer/shift-left 1 63)))]
                [(cons 'do
                       (mapv (fn [[nm sym]] (list 'php/register! nm sym))
                             builtins))]
                ;; last, so a failed init never reports ready
                [(define 'php.rt/state :php/ready) :php/null])
              :php/null)))


(def definitions
  "The single definition list, `[store-key form]` in definition order: every
   prelude function, the literal state slot, then `php/init!`. Defining them
   runs nothing."
  (into core-definitions
        [['php.rt/state :php/uninit]
         ['php/init! (init-form)]]))


(def functions-uast
  "Every definition: lambdas and the literal state slot."
  (u/seq-nodes (map (fn [[k form]] (u/def! k (u/sexp->uast form))) definitions)))


(def uast
  "The bundled prelude: every definition, then the task's runtime state
   allocated by `php/init!`."
  (u/then functions-uast (u/sexp->uast '(php/init!))))


;; =============================================================================
;; The module emitter: the same definition list as the linker module `php`
;; =============================================================================

(def module-name
  "The linker module the PHP runtime profile is published as."
  'php)


(def runtime-keys
  "Every key `php/init!` allocates, the ready flag aside, sorted."
  '[php.rt/classes php.rt/ctx php.rt/diag php.rt/error-handler
    php.rt/error-reporting php.rt/functions php.rt/globals php.rt/int-max
    php.rt/int-min php.rt/next-ord php.rt/out])


(defn strip
  "`sym` without the module's own namespace `php`; `php.rt/*` stays
   qualified."
  [sym]
  (core/strip "php" sym))


(def ^:private module-definitions
  (core/module-definitions "php" :php/uninit runtime-keys definitions))


(def module-uast
  "The module tree: one application whose operands are the definitions, in
   order. Defining runs nothing."
  (core/module-tree module-definitions []))


(def module-exports
  "The module's exports: every `php/` key of the definition list, stripped."
  (core/exports "php" definitions))


(def free-names core/free-names)


(def defined-keys core/defined-keys)


(def ^:private module-free-names
  (core/free-name-vec module-uast))


(defn module-spec
  "`yin.vm.linker.publish/publish-module!`'s spec for the module `php` over
   the host registry `registry`; a free name nothing supplies is refused
   before anything is published. This namespace emits; it never publishes."
  [registry]
  (core/module-spec {:module-name module-name,
                     :ast module-uast,
                     :exports module-exports,
                     :requires {},
                     :free-names module-free-names,
                     :registry registry,
                     :message "the php module reads an undeclared name",
                     :refusal-ns "yang.php"}))


(def min-integer-bits
  "The smallest `::integer/max-bits` this profile admits: the exact float
   expansion `m * 5^k` needs about 2200 bits."
  4096)


(def min-integer-digits
  "The smallest `::integer/max-digits` this profile admits: the exact float
   expansion has up to about 770 digits."
  1000)


(defn register-integer-module
  "Return `registry` with the `integer` module installed under `limits`,
   refused when they are under this profile's minimum."
  [registry limits]
  (let [b (::integer/max-bits limits)
        d (::integer/max-digits limits)]
    (when-not (and (int? b) (<= min-integer-bits b) (int? d) (<= min-integer-digits d))
      (throw (ex-info "the PHP profile needs integer max-bits >= 4096 and max-digits >= 1000"
                      {:yang.php/refusal :yang.php/limits,
                       ::integer/max-bits b,
                       ::integer/max-digits d}))))
  (integer/register-integer-module registry limits))


(defn admit
  "Return `registry` when it supplies every one of `host-names`, else refuse
   it before any program runs, naming each missing export in
   `:yang.php/missing`."
  [registry]
  (core/admit registry host-names
              "the composition lacks host names the prelude calls"
              "yang.php"))
