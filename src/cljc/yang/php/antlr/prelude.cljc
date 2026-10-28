(ns ^{:clj-kondo/config '{:lint-as {yang.php.antlr.quoted/defquoted clojure.core/def}}}
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
     array           {:php/type :array :index {key value} :order [key ...]
                     :next-index n}: a persistent ordered map; keys are
                     ints or strings (`php/array-key`); `:next-index` is
                     absent until the first integer key is written
     error object    {:php/type :obj :class name :ord n :props {\"message\"
                     \"code\" \"previous\"}}
     function        a cell {:php/type :function :name s :code (fn [args])
                             :yang.call/entry (fn [invocation])}

   Runtime state is allocated by `php/init!`, once per task, under the slot
   `php.rt/state` (the literal `:php/uninit` until `php/init!` flips it to
   `:php/ready` as its last write):
     php.rt/ctx            the dynamic context {:handlers :depth :base :frame}
     php.rt/out            a cell holding the buffer stack, a vector of
                           buffers, each a vector of byte-string chunks;
                           `php/echo` conjs onto the last buffer, the
                           bottom buffer is never popped
     php.rt/globals        a cell holding the plain array of section 10.1
                           (`$GLOBALS`), `(php/empty-array)` initially
     php.rt/functions      a cell holding a map of lower-cased name to
                           function cell; builtins are pre-seeded
     php.rt/classes        a cell holding a map of class name to class record
                           (the Throwable hierarchy, data only)
     php.rt/next-ord       a cell, the per-task object ordinal
     php.rt/next-cell      a cell, the per-task counted-cell id (`:id`)
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
  (:require
    [clojure.string :as str]
    [clojure.walk :as walk]
    [yang.core.prelude :as core]
    [yang.core.uast :as u]
    [yang.php.antlr.builtins :as builtins]
    [yang.php.antlr.prelude-module :as module-defs]
    [yang.php.antlr.quoted :refer [defquoted]]
    [yin.vm.integer :as integer]))


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
                 (if (not (nil? (get x :php/arr)))
                   :array
                   (if (not (nil? (get x :php/obj)))
                     :obj
                     (if (not (nil? (get x :php/ref)))
                       (php/kind (php/ref-value x))
                       (if (data/content= x (data/float64 (data/float-value x)))
                         :float
                         :int)))))
               (if (= (get x :php/type) :array) :array :obj))))))]
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
                       (if (= k :null) "null"
                           (if (= k :array) "array" "mixed")))))))]

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
       (let [c (php/class-lookup cls)]
         (if (if (nil? c) true (= (data/count (get c :methods)) 0))
           (php/make-error-map cls msg)
           (let [o (php/instantiate c)]
             (do (php/obj-set-props! o (php/table-set (get (php/obj-state o) :props) "message" msg))
                 o)))))]
    [php/make-error-map
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
       (if (nil? (get e :php/obj))
         (assoc (assoc {} :class (get e :class))
                :message (get (get e :props) "message"))
         (assoc (assoc {} :class (get (php/obj-state e) :class))
                :message (php/prop-raw e "message"))))]
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
           (fn []
             (do (cell/set! php.rt/error-reporting (integer/bit-and saved 4437))
                 (thunk)))
           ;; ZEND_END_SILENCE: leave a mask the guarded code raised itself
           (fn [x]
             (if (= (integer/bit-and (cell/get php.rt/error-reporting) -4438) 0)
               (cell/set! php.rt/error-reporting saved)
               :php/null)))))]

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
                             (if (= k :array) (not (= (php/array-count v) 0)) true))))))))]
    [php/to-string
     (fn [v]
       (let [k (php/kind v)]
         (if (= k :string) v
             (if (= k :int) (integer/format v)
                 (if (= k :null) ""
                     (if (= k :bool) (if v "1" "")
                         (if (= k :float) (php/float-text v 14)
                             (if (= k :array)
                               (do (php/warn 2 "Array to string conversion") "Array")
                               (if (nil? (get v :php/obj))
                                 (php/throw-error "Error" "Object could not be converted to string")
                                 (php/obj-to-string v))))))))))]
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
                           (if (= k :array)
                             (if (= (php/array-count v) 0) 0 1)
                             (php/throw-error "TypeError" "Unsupported operand types")))))))))]
    [php/to-float
     (fn [v]
       (let [k (php/kind v)]
         (if (= k :float) v
             (if (= k :int) (php/f (integer/to-float v))
                 (if (= k :null) (php/f (php/zero-h))
                     (if (= k :bool) (php/f (data/float-value (if v 1 0)))
                         (if (= k :string) (php/to-float (php/str->number v))
                             (if (= k :array)
                               (php/f (data/float-value (if (= (php/array-count v) 0) 0 1)))
                               (php/throw-error "TypeError" "Unsupported operand types")))))))))]
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
       (if (php/array? a)
         (if (php/array? b) (php/array-union a b) (php/operand-error a b "+"))
         (let [xy (php/num2 a b "+") x (get xy 0) y (get xy 1)]
           (if (php/int? x)
             (if (php/int? y)
               (php/int-result (integer/add x y))
               (php/f (+ (php/fl x) (php/fl y))))
             (php/f (+ (php/fl x) (php/fl y)))))))]
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
    [php/arg-null
     ;; null to a scalar parameter: deprecated, and coerces to the zero
     (fn [fname idx aname tname]
       (do (php/warn 8192
                     (data/str-concat fname "(): Passing null to parameter #"
                                      (integer/format idx) " ($" aname
                                      ") of type " tname " is deprecated"))
           0))]
    [php/arg-int-float
     ;; zend_parse_arg_long_weak on a float h: NaN, INF and out of range are
     ;; a TypeError (Zend's message names the type given, not the value; v is
     ;; only classified); a fractional one truncates after a deprecation
     ;; (`shown` is the number as PHP prints it)
     (fn [h v shown fname idx aname]
       (if (php/nan? h)
         (php/arg-type-error fname idx aname "int" v)
         (if (= (php/abs-h h) (php/inf))
           (php/arg-type-error fname idx aname "int" v)
           (let [n (integer/from-float h)]
             (if (php/in-range? n)
               (do (if (= (integer/to-float n) h)
                     :php/null
                     (php/warn 8192
                               (data/str-concat "Implicit conversion from "
                                                shown " to int loses precision")))
                   n)
               (php/arg-type-error fname idx aname "int" v))))))]
    [php/arg-int
     (fn [v fname idx aname]
       (let [k (php/kind v)]
         (if (= k :int) v
             (if (= k :null) (php/arg-null fname idx aname "int")
                 (if (= k :bool) (if v 1 0)
                     (if (= k :float)
                       (php/arg-int-float (php/h v) v
                                          (data/str-concat "float " (php/float-text v 17))
                                          fname idx aname)
                       (if (= k :string)
                         (let [r (php/numeric-string v)]
                           (if (php/full-num? r)
                             (if (= (get r 0) :int)
                               (get r 1)
                               (php/arg-int-float
                                 (php/h (get r 1)) v
                                 (data/str-concat "float-string \"" v "\"")
                                 fname idx aname))
                             (php/arg-type-error fname idx aname "int" v)))
                         (php/arg-type-error fname idx aname "int" v))))))))]
    [php/arg-float
     (fn [v fname idx aname]
       (if (= (php/kind v) :null)
         (do (php/arg-null fname idx aname "float") (php/f (php/zero-h)))
         (php/to-float (php/arg-num v fname idx aname "float"))))]
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
     ;; zend_compare: an array against null or bool compares as bool, against
     ;; any other scalar the array is greater
     (fn [a b]
       (let [ka (php/kind a) kb (php/kind b)]
         (if (= ka :array)
           (if (= kb :array)
             (php/cmp-arrays a b)
             (if (if (= kb :null) true (= kb :bool)) (php/cmp-bool a b) 1))
           (if (= kb :array)
             (if (if (= ka :null) true (= ka :bool)) (php/cmp-bool a b) -1)
             (php/cmp-scalar a b)))))]
    [php/nest-enter!
     ;; one array level deeper; a reference cycle makes the depth unbounded
     (fn []
       (let [d (+ (cell/get php.rt/cmp-depth) 1)]
         (if (> d 100)
           (do (cell/set! php.rt/cmp-depth 0)
               (php/throw-error "Error" "Nesting level too deep - recursive dependency?"))
           (cell/set! php.rt/cmp-depth d))))]
    [php/nest-leave!
     (fn [v] (do (cell/set! php.rt/cmp-depth (- (cell/get php.rt/cmp-depth) 1)) v))]
    [php/cmp-arrays
     (fn [a b]
       (let [na (php/array-count a) nb (php/array-count b)]
         (if (= na nb)
           (do (php/nest-enter!) (php/nest-leave! (php/cmp-pairs a b 0 na)))
           (if (< na nb) -1 1))))]
    [php/cmp-pairs
     ;; per key of a; a key b lacks makes the pair uncomparable (1)
     (fn [a b i n]
       (if (< i n)
         (let [k (get (php/array-order a) i)
               bv (php/array-get b k)]
           (if (nil? bv)
             1
             (let [c (php/cmp (php/array-get a k) bv)]
               (if (= c 0) (php/cmp-pairs a b (+ i 1) n) c))))
         0))]
    [php/cmp-scalar
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
               (if (= ka :array)
                 (if (= (php/array-count a) (php/array-count b))
                   (do (php/nest-enter!)
                       (php/nest-leave! (php/identical-pairs a b 0 (php/array-count a))))
                   false)
                 (= a b))))
           false)))]
    [php/identical-pairs
     (fn [a b i n]
       (if (< i n)
         (let [ka (get (php/array-order a) i)
               kb (get (php/array-order b) i)]
           (if (= ka kb)
             (if (php/identical (php/array-get a ka) (php/array-get b kb))
               (php/identical-pairs a b (+ i 1) n)
               false)
             false))
         true))]
    [php/not-identical (fn [a b] (not (php/identical a b)))]

    ;; ---------------------------------------------------------------- var_dump
    [php/var-dump (fn [v] (php/var-dump-at v "" []))]
    [php/scalar-dump
     ;; the var_dump text of a scalar of kind k
     (fn [v k]
       (if (= k :null)
         "NULL\n"
         (if (= k :bool)
           (if v "bool(true)\n" "bool(false)\n")
           (if (= k :int)
             (data/str-concat "int(" (integer/format v) ")\n")
             (if (= k :float)
               (data/str-concat "float(" (php/float-text v 17) ")\n")
               (if (= k :string)
                 (data/str-concat "string("
                                  (integer/format (data/str-length v))
                                  ") \""
                                  v
                                  "\"\n")
                 (php/throw-error "Error" "var_dump: this value kind is not supported yet")))))))]
    [php/var-dump-at
     ;; `ind` is the indent of the value's own lines; `path` holds the cell ids
     ;; of the instance arrays being dumped, so a re-entry prints *RECURSION*
     (fn [slot ind path]
       (let [amp (if (= (php/slot-kind slot) :ref-marker)
                   (if (> (php/rc-of slot) 1) "&" "")
                   "")
             v (php/deref slot)
             k (php/kind v)]
         (if (if (= k :obj) (not (nil? (get v :php/obj))) false)
           (php/var-dump-obj v ind amp path)
           (if (= k :array)
             (let [id (php/cell-id v)]
               (if (php/path-has? path id 0)
                 (php/echo (data/str-concat ind "*RECURSION*\n"))
                 (do (php/echo (data/str-concat ind amp "array("
                                                (integer/format (php/array-count v))
                                                ") {\n"))
                     (php/var-dump-items v ind 0 (php/array-count v)
                                         (if (nil? id) path (php/vconj path id)))
                     (php/echo (data/str-concat ind "}\n")))))
             (php/echo (data/str-concat ind amp (php/scalar-dump v k)))))))]
    [php/var-dump-obj
     (fn [v ind amp path]
       (let [st (php/obj-state v)
             id (get st :id)
             props (get st :props)]
         (if (php/path-has? path id 0)
           (php/echo (data/str-concat ind "*RECURSION*\n"))
           (do (php/echo (data/str-concat ind amp "object(" (get st :class) ")#"
                                          (integer/format (get st :ord)) " ("
                                          (integer/format (php/array-count props)) ") {\n"))
               (php/var-dump-obj-items v props ind 0 (php/array-count props) (php/vconj path id))
               (php/echo (data/str-concat ind "}\n"))))))]
    [php/var-dump-obj-items
     (fn [v props ind i n path]
       (if (< i n)
         (let [k (get (php/array-order props) i)
               e (php/vfind (get (php/obj-class v) :props-decl) :id k 0)
               label (if (nil? e)
                       (data/str-concat "\"" k "\"")
                       (if (= (get e :vis) :protected)
                         (data/str-concat "\"" (get e :name) "\":protected")
                         (if (= (get e :vis) :private)
                           (data/str-concat "\"" (get e :name) "\":\"" (get e :declaring) "\":private")
                           (data/str-concat "\"" (get e :name) "\""))))]
           (do (php/echo (data/str-concat ind "  [" label "]=>\n"))
               (php/var-dump-at (php/slot-get props k) (data/str-concat ind "  ") path)
               (php/var-dump-obj-items v props ind (+ i 1) n path)))
         :php/null))]
    [php/path-has?
     (fn [path id i]
       (if (nil? id)
         false
         (if (< i (data/count path))
           (if (= (get path i) id) true (php/path-has? path id (+ i 1)))
           false)))]
    [php/var-dump-items
     (fn [v ind i n path]
       (if (< i n)
         (let [k (get (php/array-order v) i)]
           (do (php/echo (data/str-concat ind "  ["
                                          (if (php/int? k)
                                            (integer/format k)
                                            (data/str-concat "\"" k "\""))
                                          "]=>\n"))
               (php/var-dump-at (php/slot-get v k) (data/str-concat ind "  ") path)
               (php/var-dump-items v ind (+ i 1) n path)))
         :php/null))]

    ;; ---------------------------------------------------------------- arrays
    ;; The plain array of section 10.1: {:php/type :array :index {key value}
    ;; :order [key ...] :next-index n}. Keys are already coerced
    ;; (`php/array-key`); `:next-index` is absent until the first integer key
    ;; is written (Zend's ZEND_LONG_MIN sentinel). Every read and write of an
    ;; array goes through these names (P3 adds the handle branches here).
    [php/empty-array (fn [] {:php/type :array, :index {}, :order []})]
    [php/array? (fn [v] (= (php/kind v) :array))]
    [php/succ-key
     ;; k + 1, saturating at PHP_INT_MAX
     (fn [k] (if (= (integer/compare k php.rt/int-max) 0) k (integer/add k 1)))]
    [php/bump-next
     ;; :next-index after integer key k is written
     (fn [arr k]
       (let [next (get arr :next-index)]
         (if (nil? next)
           (assoc arr :next-index (php/succ-key k))
           (if (>= (integer/compare k next) 0)
             (assoc arr :next-index (php/succ-key k))
             arr))))]
    [php/int-key-str
     ;; the int a canonical decimal integer string spells (-?(0|[1-9][0-9]*),
     ;; in int64 range), else nil
     (fn [s]
       (let [cs (data/str->code-points s)
             n (data/count cs)
             i (if (> n 0) (if (= (get cs 0) 45) 1 0) 0)]
         (if (> n i)
           (if (<= (- n i) 19)
             (if (= (php/skip-digits cs i n) n)
               (if (= (get cs i) 48)
                 (if (= n 1) 0 nil)
                 (let [v (integer/parse s)] (if (php/in-range? v) v nil)))
               nil)
             nil)
           nil)))]
    [php/float-key
     ;; a float key: the P1 cast, plus the deprecation unless it was exact
     (fn [x]
       (let [h (php/h x)
             n (php/float->int h)]
         (do (if (= (integer/to-float n) h)
               :php/null
               (php/warn 8192
                         (data/str-concat "Implicit conversion from float "
                                          (php/float-text x 17)
                                          " to int loses precision")))
             n)))]
    [php/array-key2
     ;; `prefix` and `suffix` frame the TypeError text of the calling operation
     (fn [k prefix suffix]
       (let [kind (php/kind k)]
         (if (= kind :int) k
             (if (= kind :string)
               (let [r (php/int-key-str k)] (if (nil? r) k r))
               (if (= kind :float) (php/float-key k)
                   (if (= kind :bool) (if k 1 0)
                       (if (= kind :null) ""
                           (php/throw-error
                             "TypeError"
                             (data/str-concat prefix (php/type-name kind) suffix)))))))))]
    [php/array-key
     (fn [k] (php/array-key2 k "Cannot access offset of type " " on array"))]
    ;; ------------------------------------------------------- counted cells
    ;; Section 4.2/4.3. A counted thing is held through a tag: `{:php/arr h}`
    ;; (instance array), `{:php/ref c}` (reference marker), `{:php/obj o}`
    ;; (object); the cells behind them carry `:id` (per task, `php.rt/next-cell`)
    ;; and `:rc`. A plain array transitively holding nothing counted stays a
    ;; plain value. Table functions take a plain array or a handle; value
    ;; functions (`array-get`) dereference markers, raw ones (`slot-get`) do not.
    [php/next-cell!
     (fn []
       (let [n (cell/get php.rt/next-cell)]
         (do (cell/set! php.rt/next-cell (+ n 1)) n)))]
    [php/slot-kind
     ;; :scalar | :plain | :arr-handle | :obj-handle | :ref-marker
     (fn [x]
       (if (= x :php/null)
         :scalar
         (if (= x :php/undef)
           :scalar
           (if (= x true)
             :scalar
             (if (= x false)
               :scalar
               (if (nil? (get x :php/ref))
                 (if (nil? (get x :php/arr))
                   (if (nil? (get x :php/obj))
                     (if (= (get x :php/type) :array) :plain :scalar)
                     :obj-handle)
                   :arr-handle)
                 :ref-marker))))))]
    [php/counted?
     (fn [x] (let [k (php/slot-kind x)] (if (= k :scalar) false (not (= k :plain)))))]
    [php/cell-of
     (fn [x]
       (let [k (php/slot-kind x)]
         (if (= k :arr-handle)
           (get x :php/arr)
           (if (= k :ref-marker)
             (get x :php/ref)
             (if (= k :obj-handle) (get x :php/obj) nil)))))]
    [php/cell-id
     (fn [x] (let [c (php/cell-of x)] (if (nil? c) nil (get (cell/get c) :id))))]
    [php/rc-of
     ;; test introspection: the count of a counted thing, else 0
     (fn [x] (let [c (php/cell-of x)] (if (nil? c) 0 (get (cell/get c) :rc))))]
    [php/ref-value (fn [m] (get (cell/get (get m :php/ref)) :value))]
    [php/deref (fn [s] (if (= (php/slot-kind s) :ref-marker) (php/ref-value s) s))]
    [php/arr-table
     (fn [x]
       (let [d (php/deref x)]
         (if (= (php/slot-kind d) :arr-handle)
           (get (cell/get (get d :php/arr)) :value)
           d)))]
    [php/slot-get (fn [arr k] (get (get (php/arr-table arr) :index) k))]
    [php/new-ref
     (fn [v]
       (assoc {} :php/ref
              (cell/new (assoc (assoc (assoc (assoc (assoc {} :php/type :ref)
                                                    :id (php/next-cell!))
                                             :rc 1)
                                      :value v)
                               :sources []))))]
    [php/new-arr
     ;; an instance array from a plain table and its counted-key map, rc 1
     (fn [tbl deps]
       (assoc {} :php/arr
              (cell/new (assoc (assoc (assoc (assoc (assoc (assoc (assoc {} :php/type :arr)
                                                                  :id (php/next-cell!))
                                                           :rc 1)
                                                    :value tbl)
                                             :deps deps)
                                      :iterators {})
                               :ptr 0))))]
    [php/new-obj
     ;; test-only object cell (P4 owns `new`): props is a plain table
     (fn [cls props]
       (let [n (cell/get php.rt/next-ord)]
         (do (cell/set! php.rt/next-ord (+ n 1))
             (assoc {} :php/obj
                    (cell/new (assoc (assoc (assoc (assoc (assoc (assoc (assoc {} :php/type :obj)
                                                                        :id (php/next-cell!))
                                                                 :rc 1)
                                                          :class cls)
                                                   :props props)
                                            :ord n)
                                     :destructed? false))))))]
    [php/new-iter
     (fn []
       (cell/new (assoc (assoc (assoc (assoc {} :php/type :iter)
                                      :id (php/next-cell!))
                               :current :php/null)
                        :tables {})))]
    [php/released?
     ;; a cell `destroy` has entered: its count is frozen (cycle-safe teardown)
     (fn [x]
       (let [c (php/cell-of x)]
         (if (nil? c) false (= (get (cell/get c) :released?) true))))]
    [php/addref
     (fn [x]
       (let [c (php/cell-of x)]
         (if (nil? c)
           x
           (if (php/released? x)
             x
             (do (cell/set! c (assoc (cell/get c) :rc (+ (get (cell/get c) :rc) 1))) x)))))]
    [php/delref
     (fn [x]
       (let [c (php/cell-of x)]
         (if (nil? c)
           :php/null
           (if (php/released? x)
             :php/null
             (let [n (- (get (cell/get c) :rc) 1)]
               (do (cell/set! c (assoc (cell/get c) :rc n))
                   (if (= n 0) (php/destroy x) :php/null)))))))]
    [php/release-slots
     ;; DELREF the slots of `tbl` named by `deps` (a key map, or :all)
     (fn [tbl deps i n]
       (if (< i n)
         (let [kk (get (get tbl :order) i)]
           (do (if (= deps :all)
                 (php/delref (get (get tbl :index) kk))
                 (if (nil? (get deps kk))
                   :php/null
                   (php/delref (get (get tbl :index) kk))))
               (php/release-slots tbl deps (+ i 1) n)))
         :php/null))]
    [php/destroy
     ;; the cell is marked released BEFORE its slots are, so a cycle back to it
     ;; (row 18) finds it released and stops
     (fn [x]
       (let [k (php/slot-kind x)]
         (if (= k :arr-handle)
           (let [c (get x :php/arr)
                 st (cell/get c)]
             (do (cell/set! c (assoc st :released? true))
                 (php/release-slots (get st :value) (get st :deps) 0
                                    (php/array-count (get st :value)))))
           (if (= k :ref-marker)
             (let [c (get x :php/ref)]
               (do (cell/set! c (assoc (cell/get c) :released? true))
                   (php/delref (php/ref-value x))))
             (if (= k :obj-handle) (php/destroy-obj x) :php/null)))))]
    [php/destroy-obj
     ;; DESTROY: run the destructor once (pinned), then free unless resurrected
     (fn [x]
       (let [st (cell/get (get x :php/obj))]
         (if (get st :released?)
           :php/null
           (if (if (get st :destructed?)
                 false
                 (not (nil? (php/class-method (php/class-lookup (get st :class)) "__destruct"))))
             (php/finalize! x)
             (php/free-obj x)))))]
    [php/free-obj
     (fn [x]
       (let [c (get x :php/obj)
             st (cell/get c)
             props (get st :props)]
         (if (get st :released?)
           :php/null
           (do (cell/set! c (assoc (assoc st :destructed? true) :released? true))
               (php/drop-sources props (get st :id) 0 (php/array-count props))
               (php/release-slots props :all 0 (php/array-count props))))))]
    [php/drop-sources
     ;; object free (row 44): every property's reference loses `[o p]`
     (fn [props obj i n]
       (if (< i n)
         (let [k (get (php/array-order props) i)
               x (php/slot-get props k)]
           (do (if (= (php/slot-kind x) :ref-marker) (php/ref-remove-source x obj k) :php/null)
               (php/drop-sources props obj (+ i 1) n)))
         :php/null))]
    [php/dup-slot
     ;; the slot the copy of table `self` gets for source slot `x`
     (fn [self x]
       (if (= (php/slot-kind x) :ref-marker)
         (let [st (cell/get (get x :php/ref))]
           (if (= (get st :rc) 1)
             (if (= (php/cell-id (get st :value)) self)
               (php/addref x)
               (php/addref (get st :value)))
             (php/addref x)))
         (php/addref x)))]
    [php/dup-loop
     (fn [self deps tbl i n acc accd]
       (if (< i n)
         (let [kk (get (get tbl :order) i)]
           (if (nil? (get deps kk))
             (php/dup-loop self deps tbl (+ i 1) n acc accd)
             (let [y (php/dup-slot self (get (get tbl :index) kk))]
               (php/dup-loop self deps tbl (+ i 1) n
                             (assoc acc :index (assoc (get acc :index) kk y))
                             (if (php/counted? y) (assoc accd kk true) accd)))))
         (php/pair acc accd)))]
    [php/dup
     ;; DUP(h): a new instance, rc 1; never changes the source. A marker with
     ;; rc 1 is unwrapped unless it holds the table itself (row 18). Registered
     ;; iterators (row 60) are filled by the iteration step.
     (fn [h]
       (let [c (get h :php/arr)
             st (cell/get c)
             tbl (get st :value)
             r (php/dup-loop (get st :id) (get st :deps) tbl 0 (php/array-count tbl) tbl {})
             n (php/new-arr (get r 0) (get r 1))]
         (do (cell/set! (get n :php/arr) (assoc (cell/get (get n :php/arr)) :ptr (get st :ptr)))
             n)))]
    [php/separate
     ;; SEPARATE: an instance shared by more than one slot is replaced by a copy
     (fn [x]
       (if (= (php/slot-kind x) :arr-handle)
         (if (> (php/rc-of x) 1)
           (do (php/delref x) (php/dup x))
           x)
         x))]
    [php/acquire
     ;; transfer mode: :borrowed ADDREFs, :owned moves
     (fn [v mode] (if (= mode :borrowed) (php/addref v) v))]
    [php/store
     ;; STORE(container, k, v): the container after the write; a plain
     ;; container receiving a counted value is upgraded to an instance (rc 1).
     ;; Counts of v and of the old slot are the caller's (`assign-slot`).
     (fn [cont k v]
       (let [counted (php/counted? v)]
         (if (= (php/slot-kind cont) :arr-handle)
           (let [c (get cont :php/arr)
                 st (cell/get c)]
             (do (cell/set! c (assoc (assoc st :value (php/table-set (get st :value) k v))
                                     :deps
                                     (if counted
                                       (assoc (get st :deps) k true)
                                       (data/dissoc (get st :deps) k))))
                 cont))
           (if counted
             (php/new-arr (php/table-set cont k v) (assoc {} k true))
             (php/table-set cont k v)))))]
    [php/next-key
     (fn [cont] (let [nx (get (php/arr-table cont) :next-index)] (if (nil? nx) 0 nx)))]
    [php/make-ref
     ;; MAKE_REF(slot k of cont): [cont' marker]; an existing marker is reused
     (fn [cont k]
       (let [cur (php/slot-get cont k)]
         (if (= (php/slot-kind cur) :ref-marker)
           (php/pair cont cur)
           (let [m (php/new-ref (if (nil? cur) :php/null cur))]
             (php/pair (php/store cont k m) m)))))]
    [php/assign-slot
     ;; ASSIGN into slot k: write through a marker, else acquire, store, DELREF old
     (fn [cont k v mode]
       (let [cur (php/slot-get cont k)]
         (if (= (php/slot-kind cur) :ref-marker)
           (let [rc (get cur :php/ref)
                 st (cell/get rc)
                 v2 (php/acquire v mode)]
             (do (cell/set! rc (assoc st :value v2))
                 (php/delref (get st :value))
                 cont))
           (let [v2 (php/acquire v mode)
                 c2 (php/store cont k v2)]
             (do (php/delref cur) c2)))))]
    [php/unset-dim
     ;; UNSET_DIM: delete the bucket, DELREF its content, drop the dep
     (fn [cont k]
       (if (= (php/slot-kind cont) :arr-handle)
         (let [c (get cont :php/arr)
               st (cell/get c)
               old (php/slot-get cont k)]
           (do (cell/set! c (assoc (assoc st :value (php/array-unset (get st :value) k))
                                   :deps (data/dissoc (get st :deps) k)))
               (php/delref old)
               cont))
         (php/array-unset cont k)))]
    ;; variables: a CV is a cell holding :php/undef or a slot value
    [php/holder-get
     (fn [cv]
       (let [cur (cell/get cv)]
         (if (= (php/slot-kind cur) :ref-marker) (php/ref-value cur) cur)))]
    [php/holder-set!
     (fn [cv v]
       (let [cur (cell/get cv)]
         (if (= (php/slot-kind cur) :ref-marker)
           (let [rc (get cur :php/ref)]
             (cell/set! rc (assoc (cell/get rc) :value v)))
           (cell/set! cv v))))]
    [php/assign
     ;; ASSIGN(cv, v): acquire, store (through a marker), DELREF the old content
     (fn [cv v mode]
       (let [garbage (php/holder-get cv)
             v2 (php/acquire v mode)]
         (do (php/holder-set! cv v2)
             (php/delref (if (= garbage :php/undef) :php/null garbage)))))]
    [php/holder-table
     ;; the array a write into `cv` lands in: separated, or a fresh one
     (fn [cv]
       (let [cur (php/holder-get cv)]
         (if (= (php/slot-kind cur) :scalar) (php/empty-array) (php/separate cur))))]
    [php/assign-dim
     (fn [cv k v mode]
       (let [t (php/holder-table cv)]
         (php/holder-set! cv (php/assign-slot t k v mode))))]
    [php/make-ref-dim
     ;; $r = &$cv[k]: the marker; the table is separated and upgraded
     (fn [cv k]
       (let [r (php/make-ref (php/holder-table cv) k)]
         (do (php/holder-set! cv (get r 0)) (get r 1))))]
    [php/make-ref-cv
     (fn [cv]
       (let [cur (cell/get cv)]
         (if (= (php/slot-kind cur) :ref-marker)
           cur
           (let [m (php/new-ref (if (= cur :php/undef) :php/null cur))]
             (do (cell/set! cv m) m)))))]
    [php/assign-ref
     ;; ASSIGN_REF(dst, src) on CVs: the source's marker is acquired first,
     ;; then stored, then the old content is released; the same marker is a no-op
     (fn [dst src]
       (let [m (php/make-ref-cv src)
             garbage (cell/get dst)]
         (if (= (php/cell-id m) (php/cell-id garbage))
           :php/null
           (do (php/addref m)
               (cell/set! dst m)
               (php/delref (if (= garbage :php/undef) :php/null garbage))))))]
    [php/bind-marker
     ;; $cv =& <marker> for a marker made elsewhere (array element, literal)
     (fn [dst m]
       (let [garbage (cell/get dst)]
         (do (php/addref m)
             (cell/set! dst m)
             (php/delref (if (= garbage :php/undef) :php/null garbage)))))]
    [php/unset-cv
     (fn [cv]
       (let [cur (cell/get cv)]
         (do (cell/set! cv :php/undef)
             (php/delref (if (= cur :php/undef) :php/null cur)))))]
    [php/release-cvs
     (fn [cvs i n]
       (if (< i n)
         (do (php/unset-cv (get cvs i)) (php/release-cvs cvs (+ i 1) n))
         (php/flush-pending!)))]
    ;; test-only invariant checker: a vector of violation messages
    [php/check-value
     (fn [v errs seen]
       (let [k (php/slot-kind v)]
         (if (= k :plain)
           (php/check-slots v :plain errs seen 0 (php/array-count v))
           (if (= k :scalar)
             :php/null
             (let [id (php/cell-id v)]
               (if (nil? (get (cell/get seen) id))
                 (do (cell/set! seen (assoc (cell/get seen) id true))
                     (if (if (php/released? v) false (< (php/rc-of v) 0))
                       (cell/set! errs (php/vconj (cell/get errs) "negative count"))
                       :php/null)
                     (if (= k :ref-marker)
                       (do (if (= (php/slot-kind (php/ref-value v)) :ref-marker)
                             (cell/set! errs (php/vconj (cell/get errs) "nested marker"))
                             :php/null)
                           (php/check-value (php/ref-value v) errs seen))
                       (if (= k :arr-handle)
                         (let [st (cell/get (get v :php/arr))]
                           (php/check-slots (get st :value) (get st :deps) errs seen 0
                                            (php/array-count (get st :value))))
                         (let [props (get (cell/get (get v :php/obj)) :props)]
                           (php/check-slots props :obj errs seen 0 (php/array-count props))))))
                 :php/null))))))]
    [php/check-slots
     (fn [tbl deps errs seen i n]
       (if (< i n)
         (let [kk (get (get tbl :order) i)
               x (get (get tbl :index) kk)]
           (do (if (= deps :plain)
                 (if (php/counted? x)
                   (cell/set! errs (php/vconj (cell/get errs) "plain array holds a counted thing"))
                   :php/null)
                 (if (= deps :obj)
                   :php/null
                   (if (= (php/counted? x) (not (nil? (get deps kk))))
                     :php/null
                     (cell/set! errs (php/vconj (cell/get errs) "deps mismatch")))))
               (php/check-value x errs seen)
               (php/check-slots tbl deps errs seen (+ i 1) n)))
         :php/null))]
    [php/check-invariants
     (fn [v]
       (let [errs (cell/new [])
             seen (cell/new {})]
         (do (php/check-value v errs seen) (cell/get errs))))]
    [php/array-has? (fn [arr k] (not (nil? (php/slot-get arr k))))]
    [php/array-get (fn [arr k] (php/deref (php/slot-get arr k)))]
    [php/array-order (fn [arr] (get (php/arr-table arr) :order))]
    [php/array-count (fn [arr] (data/count (php/array-order arr)))]
    [php/table-set
     ;; raw write into a plain table; the caller owns every count
     (fn [arr k v]
       (let [idx (get arr :index)
             a (assoc (assoc arr :index (assoc idx k v))
                      :order
                      (if (nil? (get idx k))
                        (php/vconj (get arr :order) k)
                        (get arr :order)))]
         (if (php/int? k) (php/bump-next a k) a)))]
    [php/array-set
     ;; a borrowed value goes in: a counted one is ADDREFed and upgrades a
     ;; plain table, an instance takes the write through `store`
     (fn [arr k v]
       (if (php/counted? v)
         (php/store arr k (php/addref v))
         (if (= (php/slot-kind arr) :arr-handle) (php/store arr k v) (php/table-set arr k v))))]
    [php/array-store-borrowed (fn [arr k v] (php/store arr k (php/addref v)))]
    [php/append-key
     ;; the key `[]` writes to, or the engine's occupied-slot error
     (fn [arr]
       (let [nx (get (php/arr-table arr) :next-index)
             h (if (nil? nx) 0 nx)]
         (if (php/array-has? arr h)
           (php/throw-error
             "Error"
             "Cannot add element to the array as the next element is already occupied")
           h)))]
    [php/array-append (fn [arr v] (php/array-store-borrowed arr (php/append-key arr) v))]
    [php/array-put-ref
     ;; `[k => &$x]`: the marker is stored and counted by the slot
     (fn [arr k m] (php/store arr (php/array-key k) (php/addref m)))]
    [php/array-append-ref
     (fn [arr m] (php/store arr (php/append-key arr) (php/addref m)))]
    [php/spread-loop
     (fn [acc v i n]
       (if (< i n)
         (let [k (get (php/array-order v) i)
               x (php/array-get v k)]
           (php/spread-loop (if (php/int? k) (php/array-append acc x) (php/array-set acc k x))
                            v
                            (+ i 1)
                            n))
         acc))]
    [php/array-spread
     (fn [acc v]
       (if (= (php/kind v) :array)
         (php/spread-loop acc v 0 (php/array-count v))
         (php/throw-error "Error" "Only arrays and Traversables can be unpacked")))]
    [php/array-unset
     ;; :next-index is untouched
     (fn [arr k]
       (if (php/array-has? arr k)
         (assoc (assoc arr :index (data/dissoc (get arr :index) k))
                :order
                (php/vec-remove (get arr :order) k 0 (data/count (get arr :order)) []))
         arr))]
    [php/array-put (fn [arr k v] (php/array-set arr (php/array-key k) v))]
    [php/array-keys (fn [arr] (php/array-order arr))]
    [php/values-loop
     (fn [arr ord i n acc]
       (if (< i n)
         (php/values-loop arr ord (+ i 1) n (php/vconj acc (php/array-get arr (get ord i))))
         acc))]
    [php/array-values
     (fn [arr]
       (php/values-loop arr (php/array-order arr) 0 (php/array-count arr) []))]
    [php/union-loop
     (fn [a b i n]
       (if (< i n)
         (let [k (get (php/array-order b) i)]
           (php/union-loop (if (php/array-has? a k)
                             a
                             (php/array-set a k (php/array-get b k)))
                           b
                           (+ i 1)
                           n))
         a))]
    [php/array-union
     ;; array + array: left wins, the right's missing keys follow in order
     (fn [a b] (php/union-loop a b 0 (php/array-count b)))]

    ;; dimension reads
    [php/undef-key-msg
     (fn [key]
       (data/str-concat "Undefined array key "
                        (if (php/int? key)
                          (integer/format key)
                          (data/str-concat "\"" key "\""))))]
    [php/string-offset-int
     ;; the int an offset key stands for on a string, nil when it is none
     (fn [k]
       (let [kind (php/kind k)]
         (if (= kind :int) k
             (if (= kind :string)
               (let [r (php/numeric-string k)]
                 (if (php/full-num? r) (if (= (get r 0) :int) (get r 1) nil) nil))
               nil))))]
    [php/string-peek-int
     ;; the int an isset/empty offset stands for on a string, nil when none;
     ;; bool and null coerce silently, float uses the float-key seam
     (fn [k]
       (let [kind (php/kind k)]
         (if (= kind :bool) (php/to-int k)
             (if (= kind :null) (php/to-int k)
                 (if (= kind :float) (php/float-key k)
                     (php/string-offset-int k))))))]
    [php/string-char
     ;; the one-byte string of s at offset i (negative counts from the end),
     ;; nil out of range
     (fn [s i]
       (let [n (data/str-length s)
             j (if (< i 0) (integer/add n i) i)]
         (if (< j 0)
           nil
           (if (< j n) (data/substring s j (+ j 1)) nil))))]
    [php/string-dim-get
     (fn [s k]
       (let [kind (php/kind k)
             cast (if (= kind :float) true (if (= kind :bool) true (= kind :null)))
             i (if cast
                 (do (php/warn 2 "String offset cast occurred") (php/to-int k))
                 (php/string-offset-int k))]
         (if (nil? i)
           (php/throw-error "TypeError"
                            (data/str-concat "Cannot access offset of type "
                                             (php/type-name kind)
                                             " on string"))
           (let [ch (php/string-char s i)]
             (if (nil? ch)
               (do (php/warn 2 (data/str-concat "Uninitialized string offset "
                                                (integer/format i)))
                   "")
               ch)))))]
    [php/dim-get
     (fn [c k]
       (let [kind (php/kind c)]
         (if (php/prop-key? k)
           (if (= kind :obj)
             (php/prop-read c (get k :php/prop) (get k :ctx))
             (php/prop-read-nonobj c (get k :php/prop)))
           (if (= kind :array)
             (let [key (php/array-key k)
                   v (php/array-get c key)]
               (if (nil? v)
                 (do (php/warn 2 (php/undef-key-msg key)) :php/null)
                 v))
             (if (= kind :string)
               (php/string-dim-get c k)
               (if (= kind :obj)
                 (php/throw-error "Error" "Cannot use object as array")
                 (do (php/warn 2 (data/str-concat
                                   "Trying to access array offset on "
                                   (if (= kind :bool)
                                     (if c "true" "false")
                                     (php/type-name kind))))
                     :php/null)))))))]
    [php/dim-peek
     ;; no diagnostic for a missing key or a non-array: null
     (fn [c k]
       (let [kind (php/kind c)]
         (if (php/prop-key? k)
           (if (= kind :obj) (php/prop-peek c (get k :php/prop) (get k :ctx)) :php/null)
           (if (= kind :array)
             (let [v (php/array-get c
                                    (php/array-key2 k "Cannot access offset of type " " in isset or empty"))]
               (if (nil? v) :php/null v))
             (if (= kind :string)
               (let [i (php/string-peek-int k)]
                 (if (nil? i)
                   :php/null
                   (let [ch (php/string-char c i)] (if (nil? ch) :php/null ch))))
               :php/null)))))]

    ;; dimension writes: the holder's value is read, updated purely, stored back
    [php/str-write-error
     (fn []
       (php/throw-error
         "Error"
         "string offset writes are not supported yet (yang.php known gap G-str-offset-write)"))]
    [php/assign-into
     ;; the container after `$c[ks...] = v`: a shared instance is separated,
     ;; a marker on the path is written through, a counted value upgrades a
     ;; plain container (store)
     (fn [arr ks i v]
       (let [a (php/separate arr)
             k (get ks i)
             key (if (= k :php/append) (php/append-key a) (php/array-key k))]
         (if (= (+ i 1) (data/count ks))
           (php/assign-slot a key v :owned)
           (let [raw (php/slot-get a key)]
             (if (= (php/slot-kind raw) :ref-marker)
               (let [nv (php/path-assign-at (php/ref-value raw) ks (+ i 1) v)]
                 (do (php/ref-set! raw nv) a))
               (php/store a key (php/path-assign-at (if (nil? raw) :php/null raw)
                                                    ks
                                                    (+ i 1)
                                                    v)))))))]
    [php/ref-set!
     (fn [m v] (let [rc (get m :php/ref)] (cell/set! rc (assoc (cell/get rc) :value v))))]
    [php/path-assign-at
     (fn [c ks i v]
       (let [kind (php/kind c)]
         (if (php/prop-key? (get ks i))
           (php/assign-prop-path c ks i v)
           (if (= kind :array)
             (php/assign-into c ks i v)
             (if (= kind :null)
               (php/assign-into (php/empty-array) ks i v)
               (if (= kind :bool)
                 (if c
                   (php/throw-error "Error" "Cannot use a scalar value as an array")
                   (do (php/warn 8192 "Automatic conversion of false to array is deprecated")
                       (php/assign-into (php/empty-array) ks i v)))
                 (if (= kind :string)
                   (php/str-write-error)
                   (if (= kind :obj)
                     (php/throw-error "Error" "Cannot use object as array")
                     (php/throw-error "Error" "Cannot use a scalar value as an array")))))))))]
    [php/path-assign (fn [c ks v] (php/path-assign-at c ks 0 v))]
    [php/path-unset-at
     ;; never vivifies: a missing key or null at any level changes nothing
     (fn [c ks i]
       (let [kind (php/kind c)]
         (if (php/prop-key? (get ks i))
           (php/unset-prop-path c ks i)
           (if (= kind :array)
             (let [key (php/array-key2 (get ks i) "Cannot unset offset of type " " on array")]
               (if (= (+ i 1) (data/count ks))
                 (php/unset-dim (php/separate c) key)
                 (let [raw (php/slot-get c key)]
                   (if (nil? raw)
                     c
                     (let [a (php/separate c)]
                       (if (= (php/slot-kind raw) :ref-marker)
                         (do (php/ref-set! raw (php/path-unset-at (php/ref-value raw) ks (+ i 1)))
                             a)
                         (php/store a key (php/path-unset-at raw ks (+ i 1)))))))))
             (if (= kind :null)
               c
               (if (= kind :bool)
                 (if c
                   (php/throw-error "Error" "Cannot unset offset in a non-array variable")
                   (do (php/warn 8192 "Automatic conversion of false to array is deprecated")
                       c))
                 (if (= kind :string)
                   (php/throw-error "Error" "Cannot unset string offsets")
                   (php/throw-error "Error" "Cannot unset offset in a non-array variable"))))))))]
    [php/path-unset (fn [c ks] (php/path-unset-at c ks 0))]
    [php/path-ref-at
     ;; [container' marker] for `&$c[ks...]`; vivifies, separates, reuses a marker
     (fn [c ks i]
       (let [kind (php/kind c)]
         (if (php/prop-key? (get ks i))
           (php/ref-prop-path c ks i)
           (if (= kind :array)
             (php/ref-into c ks i)
             (if (= kind :null)
               (php/ref-into (php/empty-array) ks i)
               (php/throw-error "Error" "Cannot use a scalar value as an array"))))))]
    [php/ref-into
     (fn [arr ks i]
       (let [a (php/separate arr)
             k (get ks i)
             key (if (= k :php/append) (php/append-key a) (php/array-key k))]
         (if (= (+ i 1) (data/count ks))
           (php/make-ref a key)
           (let [raw (php/slot-get a key)]
             (if (= (php/slot-kind raw) :ref-marker)
               (let [r (php/path-ref-at (php/ref-value raw) ks (+ i 1))]
                 (do (php/ref-set! raw (get r 0)) (php/pair a (get r 1))))
               (let [r (php/path-ref-at (if (nil? raw) :php/null raw) ks (+ i 1))]
                 (php/pair (php/store a key (get r 0)) (get r 1))))))))]
    [php/path-ref (fn [c ks] (php/path-ref-at c ks 0))]
    [php/path-bind-at
     ;; the container after `$c[ks...] =& <marker m>`
     (fn [c ks i m]
       (let [kind (php/kind c)]
         (if (php/prop-key? (get ks i))
           (php/bind-prop-path c ks i m)
           (if (= kind :array)
             (php/bind-into c ks i m)
             (if (= kind :null)
               (php/bind-into (php/empty-array) ks i m)
               (php/throw-error "Error" "Cannot use a scalar value as an array"))))))]
    [php/bind-prop-path
     ;; `$o->prop =& m`: a typed property becomes a source of the reference
     ;; (rows 41, 45), the slot holds the marker, the old content is released
     (fn [c ks i m]
       (let [pk (get ks i)
             name (get pk :php/prop)
             ctx (get pk :ctx)]
         (if (= (php/kind c) :obj)
           (let [r (php/prop-find (php/obj-class c) name ctx)
                 kind (get r :kind)]
             (if (= kind :denied)
               (php/denied-error c (get r :entry) name)
               (let [e (get r :entry)
                     id (if (= kind :ok) (get e :id) name)
                     st (php/obj-state c)]
                 (if (if (= kind :ok) (= (get e :readonly?) true) false)
                   (php/throw-error "Error" (data/str-concat "Cannot modify readonly property "
                                                             (get e :declaring) "::$" (get e :name)))
                   (if (= (+ i 1) (data/count ks))
                     (let [old (php/slot-get (get st :props) id)]
                       (do (if (if (= kind :ok) (not (nil? (get e :type))) false)
                             (php/ref-add-source
                               m (assoc {} :obj (get st :id) :class (get e :declaring) :prop id
                                        :type (get e :type)))
                             :php/null)
                           (php/obj-set-props! c (php/store (get st :props) id (php/addref m)))
                           (if (= (php/slot-kind old) :ref-marker)
                             (php/ref-remove-source old (get st :id) id)
                             :php/null)
                           (php/delref old)
                           c))
                     (php/throw-error "Error" "Cannot bind a reference below a property path yet"))))))
           (php/throw-error "Error" (data/str-concat "Attempt to assign property \"" name "\" on "
                                                     (php/type-name (php/kind c)))))))]
    [php/bind-into
     (fn [arr ks i m]
       (let [a (php/separate arr)
             k (get ks i)
             key (if (= k :php/append) (php/append-key a) (php/array-key k))
             raw (php/slot-get a key)]
         (if (= (+ i 1) (data/count ks))
           (let [c2 (php/store a key (php/addref m))]
             (do (php/delref raw) c2))
           (if (= (php/slot-kind raw) :ref-marker)
             (do (php/ref-set! raw (php/path-bind-at (php/ref-value raw) ks (+ i 1) m)) a)
             (php/store a key (php/path-bind-at (if (nil? raw) :php/null raw) ks (+ i 1) m))))))]
    [php/path-bind (fn [c ks m] (php/path-bind-at c ks 0 m))]

    ;; ---------------------------------------------------------------- variables
    ;; Top-level variables live in `php.rt/globals` (the `$GLOBALS` array
    ;; shape); a function's variables are cells holding :php/undef until set.
    [php/undef-warning
     (fn [name]
       (php/warn 2 (data/str-concat "Undefined variable $" name)))]
    [php/global-slot (fn [name] (php/slot-get (cell/get php.rt/globals) name))]
    [php/global-peek
     (fn [name]
       (let [v (php/global-slot name)]
         (if (nil? v) :php/null (php/deref v))))]
    [php/global-get
     (fn [name]
       (let [v (php/global-slot name)]
         (if (nil? v)
           (do (php/undef-warning name) :php/null)
           (php/deref v))))]
    [php/global-store!
     ;; raw slot write, no counting
     (fn [name x]
       (let [g (cell/get php.rt/globals)
             idx (get g :index)]
         (cell/set! php.rt/globals
                    (assoc (assoc g :index (assoc idx name x))
                           :order
                           (if (nil? (get idx name))
                             (php/vconj (get g :order) name)
                             (get g :order))))))]
    [php/global-put!
     ;; write back a container the variable already owns (through a marker)
     (fn [name v]
       (let [cur (php/global-slot name)]
         (do (if (= (php/slot-kind cur) :ref-marker)
               (php/ref-set! cur v)
               (php/global-store! name v))
             v)))]
    [php/global-set!
     ;; ASSIGN: write through a marker, DELREF the old content
     (fn [name v]
       (let [cur (php/global-slot name)]
         (if (= (php/slot-kind cur) :ref-marker)
           (php/ref-write! cur v)
           (do (php/global-store! name v)
               (php/release-unless-same cur v)
               (php/flush-pending!)
               v))))]
    [php/release-unless-same
     ;; DELREF `old` after a store of `v`, except when `v` is that very cell
     ;; (a builtin that mutated the array in place and answered it)
     (fn [old v]
       (let [id (php/cell-id old)]
         (if (nil? id) :php/null (if (= id (php/cell-id v)) :php/null (php/delref old)))))]
    [php/global-make-ref!
     (fn [name]
       (let [cur (php/global-slot name)]
         (if (= (php/slot-kind cur) :ref-marker)
           cur
           (let [m (php/new-ref (if (nil? cur) :php/null cur))]
             (do (php/global-store! name m) m)))))]
    [php/global-bind!
     (fn [name m]
       (let [cur (php/global-slot name)]
         (do (php/addref m) (php/global-store! name m) (php/delref cur) :php/null)))]
    [php/global-isset? (fn [name] (not (= (php/global-peek name) :php/null)))]
    [php/vec-remove
     (fn [xs x i n acc]
       (if (< i n)
         (php/vec-remove xs x (+ i 1) n
                         (if (= (get xs i) x) acc (php/vconj acc (get xs i))))
         acc))]
    [php/global-unset!
     (fn [name]
       (let [g (cell/get php.rt/globals)
             cur (php/global-slot name)]
         (do (cell/set! php.rt/globals
                        (assoc (assoc g :index (data/dissoc (get g :index) name))
                               :order
                               (php/vec-remove (get g :order) name 0
                                               (data/count (get g :order)) [])))
             (php/delref cur)
             (php/flush-pending!)
             :php/null)))]
    [php/local-peek
     (fn [c] (let [v (cell/get c)] (if (= v :php/undef) :php/null (php/deref v))))]
    [php/local-get
     (fn [c name]
       (let [v (cell/get c)]
         (if (= v :php/undef)
           (do (php/undef-warning name) :php/null)
           (php/deref v))))]
    [php/local-set!
     ;; ASSIGN: write through a marker, DELREF the old content
     (fn [c v]
       (let [cur (cell/get c)]
         (if (= (php/slot-kind cur) :ref-marker)
           (php/ref-write! cur v)
           (do (cell/set! c v)
               (if (= cur :php/undef) :php/null (php/release-unless-same cur v))
               (php/flush-pending!)
               v))))]
    [php/local-put!
     (fn [c v]
       (let [cur (cell/get c)]
         (do (if (= (php/slot-kind cur) :ref-marker) (php/ref-set! cur v) (cell/set! c v)) v)))]
    [php/local-isset? (fn [c] (not (= (php/local-peek c) :php/null)))]
    [php/local-unset! (fn [c] (do (php/unset-cv c) (php/flush-pending!) :php/null))]

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
             (if (= k :null)
               (do (php/warn
                     2
                     "Decrement on type null has no effect, this will change in the next major version of PHP")
                   :php/null)
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
     ;; The function cell keeps its source body and publishes the common
     ;; unary entry. `:yang.call/callee` supplies self, preserving cell identity.
     (fn [name spec code statics]
       (let [f (cell/new (assoc (assoc (assoc (assoc (assoc {} :php/type :function) :name name)
                                              :spec spec)
                                       :code code)
                                :statics
                                (php/make-statics statics 0 (data/count statics) {})))]
         (do
           (cell/set! f (assoc (cell/get f) :yang.call/entry
                               (fn [invocation]
                                 (let [callee (get invocation :yang.call/callee)
                                       positional (get invocation :yang.call/positional)
                                       named (get invocation :yang.call/named)
                                       binding-profile (get invocation :yang.call/binding-profile)
                                       args (if (> (data/count named) 0)
                                              (php/place-named callee (get (cell/get callee) :spec)
                                                               positional named 0 (data/count named)
                                                               (php/empty-array))
                                              positional)]
                                   (if (if (nil? binding-profile)
                                         true
                                         (= binding-profile
                                            "sha256:171656777d777865312132fe18cbc7a057a4721844a4dc450c0800381d2223eb"))
                                     ((get (cell/get callee) :code)
                                      callee args binding-profile)
                                     ((get invocation :yang.call/raise)
                                      {:yang.call/reason :yang.call/unsupported-binding
                                       :yang.call/data
                                       {:yang.call/binding-profile binding-profile}}))))))
           f)))]
    [php/register!
     (fn [name code]
       (cell/set! php.rt/functions
                  (assoc (cell/get php.rt/functions)
                         name
                         (php/make-function name :php/null
                                            (fn [f args binding-profile] (code args)) []))))]
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
    [php/static-bind!
     ;; `static $n = e;`: the persistent slot holds a marker made on first
     ;; reach; the activation's CV is bound to it (row 17)
     (fn [f name cv thunk]
       (let [slot (php/static-slot f name)]
         (do (if (= (cell/get slot) :php/undef)
               (cell/set! slot (php/new-ref (thunk)))
               :php/null)
             (php/bind-marker cv (cell/get slot)))))]
    [php/check-param-cell!
     ;; a passed argument against its declared type, once the parameter cell
     ;; exists: coerced in place, or `TypeError`. A reference with typed
     ;; sources must already satisfy the type without conversion.
     (fn [cell type fname idx pname]
       (let [cur0 (cell/get cell)
             marker? (= (php/slot-kind cur0) :ref-marker)
             cur (if marker? (php/ref-value cur0) cur0)
             srcs (if marker? (php/ref-sources cur0) [])
             r (if (> (data/count srcs) 0)
                 (if (php/type-exact? type cur) cur :php/undef)
                 (php/type-accepts type cur (cell/get php.rt/strict)))]
         (if (= r :php/undef)
           (php/throw-error
             "TypeError"
             (data/str-concat fname "(): Argument #" (php/to-string idx) " ($" pname ") must be of type "
                              (php/type-str type) ", " (php/given-name cur) " given"))
           (if (= r cur) :php/null (if marker? (php/ref-set! cur0 r) (cell/set! cell r))))))]
    [php/param-ref-typed
     ;; the marker as `param-ref` makes it; the value it holds is checked, and
     ;; written back coerced, as PHP coerces the caller's variable in place
     (fn [arg type fname idx pname]
       (let [marker? (= (php/slot-kind arg) :ref-marker)
             cur (if marker? (php/ref-value arg) arg)
             srcs (if marker? (php/ref-sources arg) [])
             r (if (> (data/count srcs) 0)
                 (if (php/type-exact? type cur) cur :php/undef)
                 (php/type-accepts type cur (cell/get php.rt/strict)))]
         (if (= r :php/undef)
           (php/throw-error
             "TypeError"
             (data/str-concat fname "(): Argument #" (php/to-string idx) " ($" pname ") must be of type "
                              (php/type-str type) ", " (php/given-name cur) " given"))
           (let [m (php/param-ref arg)]
             (do (if (= r cur) :php/null (php/ref-set! m r)) m)))))]
    [php/param-ref
     ;; a by-reference parameter: the caller's marker (counted by the CV), or
     ;; a fresh one around a value that is not a variable. The marker is
     ;; binding-authorized only by the pinned invocation profile.
     (fn [arg binding-profile]
       (if (= binding-profile "sha256:171656777d777865312132fe18cbc7a057a4721844a4dc450c0800381d2223eb")
         (if (= (php/slot-kind arg) :ref-marker) (php/addref arg) (php/new-ref arg))
         (php/new-ref (php/addref (php/deref arg)))))]
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
     (fn [f args]
       ((get (cell/get f) :yang.call/entry)
        (assoc (assoc (assoc (assoc {} :yang.call/callee f)
                             :yang.call/positional args)
                      :yang.call/named [])
               :yang.call/raise (fn [condition] (php/raise condition)))))]
    [php/call-with-binding-profile
     (fn [f args named binding-profile]
       ((get (cell/get f) :yang.call/entry)
        (assoc (assoc (assoc (assoc (assoc {} :yang.call/callee f)
                                    :yang.call/positional args)
                             :yang.call/named named)
                      :yang.call/raise (fn [condition] (php/raise condition)))
               :yang.call/binding-profile binding-profile)))]
    ;; the internal array pointer (row 55): an ordinal in the table's `:ptr`,
    ;; so it travels with the value (copy, DUP, upgrade); absent means 0
    [php/ptr-of
     (fn [arr] (let [p (get (php/arr-table arr) :ptr)] (if (nil? p) 0 p)))]
    [php/ptr-set
     (fn [arr p]
       (if (= (php/slot-kind arr) :arr-handle)
         (let [c (get arr :php/arr)
               st (cell/get c)]
           (do (cell/set! c (assoc st :value (assoc (get st :value) :ptr p))) arr))
         (assoc arr :ptr p)))]
    [php/ptr-valid? (fn [arr p] (if (>= p 0) (< p (php/array-count arr)) false))]
    [php/ptr-elem
     (fn [arr p] (if (php/ptr-valid? arr p) (php/array-get arr (get (php/array-order arr) p)) false))]
    [php/ptr-move
     (fn [arr p]
       (let [q (if (< p 0) (php/array-count arr) p)
             a2 (php/ptr-set arr q)]
         (php/out1 (php/ptr-elem a2 q) a2)))]
    [php/bi-current
     (fn [args]
       (do (php/arity-between "current" args 1 1 "exactly 1")
           (let [arr (php/arg-array (get args 0) "current" 1 "array")]
             (php/ptr-elem arr (php/ptr-of arr)))))]
    [php/bi-key
     (fn [args]
       (do (php/arity-between "key" args 1 1 "exactly 1")
           (let [arr (php/arg-array (get args 0) "key" 1 "array")
                 p (php/ptr-of arr)]
             (if (php/ptr-valid? arr p) (get (php/array-order arr) p) :php/null))))]
    [php/bi-next
     (fn [args]
       (do (php/arity-between "next" args 1 1 "exactly 1")
           (let [arr (php/arg-array (get args 0) "next" 1 "array")]
             (php/ptr-move arr (+ (php/ptr-of arr) 1)))))]
    [php/bi-prev
     (fn [args]
       (do (php/arity-between "prev" args 1 1 "exactly 1")
           (let [arr (php/arg-array (get args 0) "prev" 1 "array")]
             (php/ptr-move arr (- (php/ptr-of arr) 1)))))]
    [php/bi-reset
     (fn [args]
       (do (php/arity-between "reset" args 1 1 "exactly 1")
           (let [arr (php/arg-array (get args 0) "reset" 1 "array")]
             (php/ptr-move arr 0))))]
    [php/bi-end
     (fn [args]
       (do (php/arity-between "end" args 1 1 "exactly 1")
           (let [arr (php/arg-array (get args 0) "end" 1 "array")]
             (php/ptr-move arr (if (> (php/array-count arr) 0) (- (php/array-count arr) 1) 0)))))]
    [php/fe-has-next
     ;; by-reference foreach: is there an element at ordinal `i` of the array now?
     (fn [tbl i] (if (php/array? tbl) (< i (php/array-count tbl)) false))]
    [php/fe-key (fn [tbl i] (get (php/array-order tbl) i))]
    [php/fe-next
     ;; the ordinal after `key` in the array as the body left it; an element
     ;; the body unset leaves the ordinal where its successor now sits
     (fn [tbl key i]
       (if (php/array? tbl)
         (let [order (php/array-order tbl)
               j (php/name-index order key 0 (data/count order))]
           (if (nil? j) i (+ j 1)))
         i))]
    [php/list-get
     ;; one destructuring read: a missing key warns, a non-array answers null
     (fn [src k]
       (if (= (php/kind src) :array)
         (let [key (php/array-key k)
               v (php/array-get src key)]
           (if (nil? v)
             (do (php/warn 2 (php/undef-key-msg key)) :php/null)
             v))
         :php/null))]
    ;; ---------------------------------------------------------------- typed references (rows 41 to 47)
    ;; Prelude level only (P3 plan R1): a type is data, `{:t :int}` and so on,
    ;; `{:t :union :of [T ...]}` for unions and nullables; a source is
    ;; `{:obj id :class "C" :prop "p" :type T}` held in the reference's
    ;; `:sources` vector (a stable object-and-property identity). P4 adds the
    ;; syntax that calls these functions.
    [php/type-str
     (fn [t]
       (let [k (get t :t)]
         (if (= k :union)
           (if (= (data/count (get t :of)) 2)
             (if (= (get (get (get t :of) 1) :t) :null)
               (data/str-concat "?" (php/type-str (get (get t :of) 0)))
               (php/type-union-str (get t :of) 0 2 ""))
             (php/type-union-str (get t :of) 0 (data/count (get t :of)) ""))
           (if (= k :class) (get t :name)
               (if (= k :obj) "object"
                   (if (= k :callable) "callable"
                       (if (= k :iterable) "iterable"
                           (if (= k :static) "static"
                               (if (= k :never) "never"
                                   (if (= k :void) "void"
                                       (if (= k :false) "false"
                                           (if (= k :true) "true" (php/type-name k)))))))))))))]
    [php/type-union-str
     (fn [ts i n acc]
       (if (< i n)
         (php/type-union-str ts (+ i 1) n
                             (data/str-concat acc (if (= i 0) "" "|") (php/type-str (get ts i))))
         acc))]
    [php/coerce-one
     ;; `v` as type `t`, :php/undef when it does not fit; the object-like kinds
     ;; never coerce, the scalar kinds go to `coerce-simple`
     (fn [t v strict]
       (let [k (get t :t)
             vk (php/kind v)]
         (if (= k :false)
           (if (= v false) v :php/undef)
           (if (= k :true)
             (if (= v true) v :php/undef)
             (if (= k :class)
               (if (if (= vk :obj) (php/instance-of v (php/lc (get t :name))) false) v :php/undef)
               (if (= k :obj)
                 (if (= vk :obj) v :php/undef)
                 (if (= k :callable)
                   (if (php/callable? v) v :php/undef)
                   (if (= k :iterable)
                     (if (if (= vk :array) true (if (= vk :obj) (php/instance-of v "traversable") false))
                       v
                       :php/undef)
                     (php/coerce-simple t v strict)))))))))]
    [php/callable?
     ;; callability from the global scope: public methods only
     (fn [v]
       (let [k (php/kind v)]
         (if (= k :string)
           (let [i (php/dcolon-at v 0 (data/str-length v))]
             (if (< i 0)
               (not (nil? (get (cell/get php.rt/functions) (php/lc v))))
               (php/public-method? (php/class-lookup (data/substring v 0 i))
                                   (data/substring v (+ i 2) (data/str-length v)))))
           (if (= k :array)
             (if (= (php/array-count v) 2)
               (let [t (php/array-get v 0)
                     m (php/array-get v 1)]
                 (if (= (php/kind m) :string)
                   (if (= (php/kind t) :obj)
                     (if (nil? (get t :php/obj)) false (php/public-method? (php/obj-class t) m))
                     (if (= (php/kind t) :string) (php/public-method? (php/class-lookup t) m) false))
                   false))
               false)
             (if (= k :obj)
               (if (php/closure? v)
                 true
                 (if (nil? (get v :php/obj))
                   false
                   (not (nil? (php/class-method (php/obj-class v) "__invoke")))))
               false)))))]
    [php/public-method?
     (fn [c name]
       (if (nil? c)
         false
         (let [m (php/class-method c (php/lc name))]
           (if (nil? m) false (= (get m :vis) :public)))))]
    [php/coerce-simple
     (fn [t v strict]
       (let [k (get t :t)
             vk (php/kind v)]
         (if (= k :mixed) v
             (if (= k :null) (if (= vk :null) v :php/undef)
                 (if (= k vk) v
                     (if (= k :float)
                       (if (= vk :int) (php/to-float v)
                           (if strict :php/undef (php/coerce-scalar k v vk)))
                       (if strict :php/undef (php/coerce-scalar k v vk))))))))]
    [php/float-in-int-range?
     ;; -2^63 <= v < 2^63 (false for NaN and infinity)
     (fn [v]
       (let [h (php/h v)
             lim (php/h (php/to-float php.rt/int-max))]
         (if (< h 0) (>= h (php/neg-h lim)) (< h lim))))]
    [php/float-to-int-weak
     ;; a float for an int parameter: integral values in int64 range convert; a
     ;; fractional one below 2^52 truncates with the "loses precision"
     ;; deprecation; NaN, infinity and anything larger refuse
     (fn [v src]
       (if (if (php/float-in-int-range? v) (= (php/to-float (php/to-int v)) v) false)
         (php/to-int v)
         (if (< (php/abs-h (php/h v)) (php/h (php/to-float 4503599627370496)))
           (do (php/warn 8192 (data/str-concat "Implicit conversion from "
                                               (if (nil? src)
                                                 (data/str-concat "float " (php/float-text v 17))
                                                 (data/str-concat "float-string \"" src "\""))
                                               " to int loses precision"))
               (php/to-int v))
           :php/undef)))]
    [php/coerce-scalar
     ;; coercive mode: scalars convert among int, float, string and bool
     (fn [k v vk]
       (if (if (= vk :array) true (if (= vk :null) true (= vk :obj)))
         :php/undef
         (if (= k :int)
           (if (= vk :bool) (php/to-int v)
               (if (= vk :float)
                 (php/float-to-int-weak v nil)
                 (let [r (php/numeric-string v)]
                   (if (php/full-num? r)
                     (if (php/int? (get r 1)) (get r 1)
                         (php/float-to-int-weak (get r 1) v))
                     :php/undef))))
           (if (= k :float)
             (if (= vk :bool) (php/to-float v)
                 (let [r (php/numeric-string v)]
                   (if (php/full-num? r) (php/to-float (get r 1)) :php/undef)))
             (if (= k :string) (php/to-string v)
                 (if (= k :bool) (php/to-bool v) :php/undef))))))]
    [php/type-accepts
     ;; the value `v` becomes under type `t`, or :php/undef; a union keeps an
     ;; exact member, else the first member that coerces
     (fn [t v strict]
       (if (= (get t :t) :union)
         (let [exact (php/union-exact (get t :of) v 0 (data/count (get t :of)))]
           (if (= exact :php/undef)
             (let [ns (if (if strict false (php/string? v)) (php/union-numeric-string (get t :of) v) :php/undef)]
               (if (= ns :php/undef)
                 (php/union-coerce (get t :of) v strict 0 (data/count (get t :of)))
                 ns))
             exact))
         (php/coerce-one t v strict)))]
    [php/member-exact?
     ;; `v` is the value type member `m` names, without coercion
     (fn [m v]
       (let [k (get m :t)]
         (if (= k :mixed) true
             (if (= k :false) (= v false)
                 (if (= k :true) (= v true)
                     (if (= k :class)
                       (if (= (php/kind v) :obj) (php/instance-of v (php/lc (get m :name))) false)
                       (if (= k :callable)
                         (php/callable? v)
                         (if (= k :iterable)
                           (if (= (php/kind v) :array)
                             true
                             (if (= (php/kind v) :obj) (php/instance-of v "traversable") false))
                           (= k (php/kind v))))))))))]
    [php/union-exact
     (fn [ts v i n]
       (if (< i n)
         (let [m (get ts i)]
           (if (php/member-exact? m v) v (php/union-exact ts v (+ i 1) n)))
         :php/undef))]
    [php/union-has?
     (fn [ts k i]
       (if (< i (data/count ts))
         (if (= (get (get ts i) :t) k) true (php/union-has? ts k (+ i 1)))
         false))]
    [php/union-numeric-string
     ;; PHP 8.3 weak mode: a numeric string picks the int or the float member by
     ;; its own form, whatever the declaration order; :php/undef when no rule fits
     (fn [ts v]
       (let [r (php/numeric-string v)]
         (if (php/full-num? r)
           ;; an integer literal past int64 parses as a float: classify by the value
           (if (php/int? (get r 1))
             (if (php/union-has? ts :int 0)
               (get r 1)
               (if (php/union-has? ts :float 0) (php/to-float (get r 1)) :php/undef))
             (if (php/union-has? ts :float 0)
               (get r 1)
               (if (php/union-has? ts :int 0) (php/float-to-int-weak (get r 1) v) :php/undef)))
           :php/undef)))]
    [php/union-coerce
     (fn [ts v strict i n]
       (if (< i n)
         (let [r (php/coerce-one (get ts i) v strict)]
           (if (= r :php/undef) (php/union-coerce ts v strict (+ i 1) n) r))
         :php/undef))]
    [php/source-key
     (fn [obj prop] (data/str-concat (integer/format obj) "::" prop))]
    [php/ref-sources (fn [m] (get (cell/get (get m :php/ref)) :sources))]
    [php/type-exact?
     ;; `v` is a member of `t` without coercion
     (fn [t v]
       (if (= (get t :t) :mixed) true
           (if (= (get t :t) :union)
             (not (= (php/union-exact (get t :of) v 0 (data/count (get t :of))) :php/undef))
             (php/member-exact? t v))))]
    [php/ref-add-source
     ;; rows 41 and 45. The value must be assignable to the new source's type
     ;; (else "Cannot assign"); a reference that already has sources then
     ;; needs the value to be an exact member of the new type, else the
     ;; "not compatible" TypeError naming the first source; a reference
     ;; without sources takes the coerced value.
     (fn [m src]
       (let [cur (php/ref-value m)
             ok (php/type-accepts (get src :type) cur false)
             srcs (php/ref-sources m)]
         (if (= ok :php/undef)
           (php/throw-error
             "TypeError"
             (data/str-concat "Cannot assign " (php/type-name (php/kind cur))
                              " to property " (get src :class) "::$" (get src :prop)
                              " of type " (php/type-str (get src :type))))
           (if (if (> (data/count srcs) 0) (not (php/type-exact? (get src :type) cur)) false)
             (let [first-src (get srcs 0)]
               (php/throw-error
                 "TypeError"
                 (data/str-concat "Reference with value of type " (php/type-name (php/kind cur))
                                  " held by property " (get first-src :class) "::$"
                                  (get first-src :prop) " of type "
                                  (php/type-str (get first-src :type))
                                  " is not compatible with property " (get src :class) "::$"
                                  (get src :prop) " of type " (php/type-str (get src :type)))))
             (let [c (get m :php/ref)
                   st (cell/get c)]
               (do (if (= (data/count srcs) 0) (php/ref-set! m ok) :php/null)
                   (cell/set! c (assoc (cell/get c) :sources (php/vconj (get st :sources) src)))
                   m))))))]
    [php/sources-without
     (fn [srcs obj prop i n acc]
       (if (< i n)
         (let [x (get srcs i)]
           (php/sources-without srcs obj prop (+ i 1) n
                                (if (if (= (get x :obj) obj) (= (get x :prop) prop) false)
                                  acc
                                  (php/vconj acc x))))
         acc))]
    [php/ref-remove-source
     ;; row 44
     (fn [m obj prop]
       (let [c (get m :php/ref)
             st (cell/get c)
             srcs (get st :sources)]
         (cell/set! c (assoc st :sources
                             (php/sources-without srcs obj prop 0 (data/count srcs) [])))))]
    [php/verify-loop
     (fn [srcs v strict i n]
       (if (< i n)
         (let [src (get srcs i)
               r (php/type-accepts (get src :type) v strict)]
           (if (= r :php/undef)
             (php/throw-error
               "TypeError"
               (data/str-concat "Cannot assign " (php/type-name (php/kind v))
                                " to reference held by property " (get src :class) "::$"
                                (get src :prop) " of type " (php/type-str (get src :type))))
             (php/verify-loop srcs r strict (+ i 1) n)))
         v))]
    [php/verify-ref-assign
     ;; row 42: the coerced value, which must satisfy every source in turn
     (fn [m v strict]
       (let [srcs (php/ref-sources m)]
         (if (= (data/count srcs) 0) v (php/verify-loop srcs v strict 0 (data/count srcs)))))]
    [php/ref-write!
     ;; ASSIGN through marker `m` (rows 2 and 42): verify, store, DELREF the old
     (fn [m v]
       (let [v2 (php/verify-ref-assign m v (cell/get php.rt/strict))
             old (php/ref-value m)]
         (do (php/ref-set! m v2) (php/release-unless-same old v2) v2)))]
    [php/typed-inc
     ;; row 43: `$r++` through a sourced reference; an int property at the maximum
     (fn [m]
       (let [old (php/ref-value m)
             nv (php/inc old)
             srcs (php/ref-sources m)]
         (do (if (if (php/int? old) (php/float? nv) false)
               (php/inc-overflow srcs 0 (data/count srcs))
               :php/null)
             (php/ref-write! m nv))))]
    [php/inc-overflow
     (fn [srcs i n]
       (if (< i n)
         (let [x (get srcs i)]
           (if (= (get (get x :type) :t) :int)
             (php/throw-error
               "TypeError"
               (data/str-concat "Cannot increment a reference held by property " (get x :class) "::$" (get x :prop)
                                " of type int past its maximal value"))
             (php/inc-overflow srcs (+ i 1) n)))
         :php/null))]
    ;; a test-only object with property declarations (P4 owns `new`)
    [php/new-typed-obj
     (fn [cls props decls]
       (let [o (php/new-obj cls props)]
         (do (cell/set! (get o :php/obj) (assoc (cell/get (get o :php/obj)) :decls decls))
             o)))]
    [php/obj-decl
     (fn [o prop] (get (get (cell/get (get o :php/obj)) :decls) prop))]
    [php/obj-set-props!
     (fn [o props] (cell/set! (get o :php/obj) (assoc (cell/get (get o :php/obj)) :props props)))]
    [php/obj-prop-ref
     ;; row 41: `&$o->prop` on a typed property
     (fn [o prop]
       (let [st (cell/get (get o :php/obj))
             decl (php/obj-decl o prop)
             props (get st :props)
             cur (php/slot-get props prop)
             nullable (php/type-accepts (get decl :type) :php/null false)]
         (if (get decl :readonly)
           (php/throw-error
             "Error"
             (data/str-concat (if (nil? cur) "Cannot indirectly modify readonly property "
                                  "Cannot modify readonly property ")
                              (get st :class) "::$" prop))
           (if (if (nil? cur) (= nullable :php/undef) false)
             (php/throw-error
               "Error"
               (data/str-concat "Cannot access uninitialized non-nullable property "
                                (get st :class) "::$" prop " by reference"))
             (let [r (php/make-ref props prop)]
               (do (php/obj-set-props! o (get r 0))
                   (php/ref-add-source (get r 1)
                                       (assoc (assoc (assoc (assoc {} :obj (get st :id))
                                                            :class (get st :class))
                                                     :prop prop)
                                              :type (get decl :type)))))))))]
    [php/obj-unset-prop
     ;; row 44: `unset($o->prop)`: the source goes, the property is uninitialized
     (fn [o prop]
       (let [st (cell/get (get o :php/obj))
             cur (php/slot-get (get st :props) prop)]
         (do (if (= (php/slot-kind cur) :ref-marker)
               (php/ref-remove-source cur (get st :id) prop)
               :php/null)
             (php/obj-set-props! o (php/unset-dim (get st :props) prop))
             :php/null)))]
    ;; ---------------------------------------------------------------- class model (P4 S1)
    ;; A descriptor is a class cell (section 3.7): vectors keep declaration order,
    ;; entries are looked up by a key field. Property identity: a private
    ;; property's `:id` is "Declaring::name", every other one is its name, so a
    ;; subclass's `$p` never collides with a parent's private `$p`.
    [php/vhas
     (fn [v x i]
       (if (< i (data/count v)) (if (= (get v i) x) true (php/vhas v x (+ i 1))) false))]
    [php/vunion
     (fn [a b i]
       (if (< i (data/count b))
         (php/vunion (if (php/vhas a (get b i) 0) a (php/vconj a (get b i))) b (+ i 1))
         a))]
    [php/vindex
     (fn [v k val i]
       (if (< i (data/count v))
         (if (= (get (get v i) k) val) i (php/vindex v k val (+ i 1)))
         -1))]
    [php/vfind
     (fn [v k val i]
       (let [j (php/vindex v k val i)] (if (< j 0) nil (get v j))))]
    [php/vput
     (fn [v k entry]
       (let [i (php/vindex v k (get entry k) 0)]
         (if (< i 0) (php/vconj v entry) (assoc v i entry))))]
    [php/vfold
     (fn [f acc v i]
       (if (< i (data/count v)) (php/vfold f (f acc (get v i)) v (+ i 1)) acc))]
    [php/or-default (fn [x d] (if (nil? x) d x))]
    [php/kind-word
     (fn [k]
       (if (= k :interface) "interface" (if (= k :trait) "trait" (if (= k :enum) "enum" "class"))))]
    [php/class-lookup
     (fn [name]
       (let [n (get (cell/get php.rt/class-index) (php/lc name))]
         (if (nil? n) nil (get (cell/get php.rt/classes) n))))]
    [php/need-class
     (fn [name what]
       (let [c (php/class-lookup name)]
         (if (nil? c)
           (php/throw-error "Error" (data/str-concat what " \"" name "\" not found"))
           c)))]
    [php/class-declarable?
     ;; hoisting: true when the parent, interfaces and traits of `spec` exist
     (fn [spec]
       (let [names (data/into (data/into (if (nil? (get spec :parent)) [] (php/vconj [] (get spec :parent)))
                                         (php/or-default (get spec :interfaces) []))
                              (php/or-default (get spec :traits) []))]
         (php/vfold (fn [ok n] (if ok (not (nil? (php/class-lookup n))) false)) true names 0)))]
    [php/prop-id
     (fn [vis declaring name]
       (if (= vis :private) (data/str-concat declaring "::" name) name))]
    [php/make-const
     (fn [declaring s]
       (assoc {} :name (get s :name) :vis (php/or-default (get s :vis) :public)
              :declaring declaring :final? (php/or-default (get s :final?) false)
              :cell (cell/new (if (nil? (get s :thunk))
                                (assoc {} :done true :value (get s :value))
                                (assoc {} :done false :thunk (get s :thunk))))))]
    [php/class-const
     (fn [c name]
       (let [e (php/vfind (get c :constants) :name name 0)]
         (if (nil? e)
           (php/throw-error "Error" (data/str-concat "Undefined constant " (get c :name) "::" name))
           (let [st (cell/get (get e :cell))]
             (if (= (get st :done) true)
               (get st :value)
               (let [v ((get st :thunk))]
                 (do (cell/set! (get e :cell) (assoc {} :done true :value v)) v)))))))]
    [php/make-prop
     (fn [declaring s]
       (let [vis (php/or-default (get s :vis) :public)]
         (assoc s :vis vis :declaring declaring :id (php/prop-id vis declaring (get s :name)))))]
    [php/make-static
     (fn [declaring s]
       (let [e (assoc s :vis (php/or-default (get s :vis) :public) :declaring declaring)]
         (assoc e :cell
                (cell/new (if (nil? (get s :thunk))
                            (if (nil? (get s :default))
                              :php/null
                              (php/default-for e (get s :default)))
                            (php/default-for e ((get s :thunk))))))))]
    [php/has-source?
     (fn [m obj prop]
       (not (nil? (php/vfind (php/ref-sources m) :prop prop 0))))]
    [php/sprop-ref
     (fn [c name ctx]
       (let [cell (php/sprop-cell c name ctx)
             m (php/make-ref-cv cell)
             e (php/vfind (get c :static-props) :name name 0)]
         (if (if (nil? (get e :type)) true (php/has-source? m (get e :declaring) name))
           m
           (php/ref-add-source
             m (assoc {} :obj (get e :declaring) :class (get e :declaring) :prop name
                      :type (get e :type))))))]
    [php/marker-of-cv
     (fn [c] (let [cur (cell/get c)] (if (= (php/slot-kind cur) :ref-marker) cur nil)))]
    [php/marker-of-global
     (fn [name] (let [cur (php/global-slot name)] (if (= (php/slot-kind cur) :ref-marker) cur nil)))]
    [php/class-static-cell
     (fn [c name]
       (let [e (php/vfind (get c :static-props) :name name 0)]
         (if (nil? e)
           (php/throw-error "Error"
                            (data/str-concat "Access to undeclared static property "
                                             (get c :name) "::$" name))
           (get e :cell))))]
    [php/class-method
     (fn [c lc] (php/vfind (get c :methods) :lc lc 0))]
    [php/make-method
     (fn [declaring s]
       (assoc s :declaring declaring :lc (php/lc (get s :name))
              :vis (php/or-default (get s :vis) :public)
              :code (if (nil? (get s :make)) (get s :code) ((get s :make) declaring))))]
    [php/put-method
     ;; own and trait methods override inherited ones; a final one refuses; an
     ;; abstract method never displaces a concrete one
     (fn [methods m]
       (let [e (php/vfind methods :lc (get m :lc) 0)]
         (if (nil? e)
           (php/vconj methods m)
           (if (= (get e :final?) true)
             (php/throw-error "Error"
                              (data/str-concat "Cannot override final method "
                                               (get e :declaring) "::" (get e :name) "()"))
             (if (if (= (get m :abstract?) true) (not (= (get e :abstract?) true)) false)
               methods
               (php/vput methods :lc m))))))]
    [php/decl-parent
     (fn [spec]
       (let [pn (get spec :parent)]
         (if (nil? pn)
           nil
           (let [p (php/need-class pn "Class")
                 k (php/or-default (get p :kind) :class)]
             (if (= k :class)
               (if (= (get p :final?) true)
                 (php/throw-error "Error" (data/str-concat "Class " (get spec :name)
                                                           " cannot extend final class " (get p :name)))
                 p)
               (php/throw-error "Error" (data/str-concat "Class " (get spec :name) " cannot extend "
                                                         (php/kind-word k) " " (get p :name))))))))]
    [php/decl-interfaces
     (fn [spec]
       (php/vfold
         (fn [acc n]
           (let [i (php/need-class n "Interface")]
             (if (= (php/or-default (get i :kind) :class) :interface)
               (php/vconj acc i)
               (php/throw-error "Error" (data/str-concat (get spec :name) " cannot implement "
                                                         (get i :name) " - it is not an interface")))))
         []
         (php/or-default (get spec :interfaces) [])
         0))]
    [php/decl-traits
     (fn [spec]
       (php/vfold
         (fn [acc n]
           (let [t (php/need-class n "Trait")]
             (if (= (get t :kind) :trait)
               (php/vconj acc t)
               (php/throw-error "Error" (data/str-concat (get spec :name) " cannot use "
                                                         (get t :name) " - it is not a trait")))))
         []
         (php/or-default (get spec :traits) [])
         0))]
    [php/inherit-interface
     (fn [st i]
       (assoc st
              :consts (php/vfold (fn [acc e] (php/vput acc :name e)) (get st :consts) (get i :constants) 0)
              :methods (php/vfold (fn [acc m]
                                    (if (nil? (php/vfind acc :lc (get m :lc) 0)) (php/vconj acc m) acc))
                                  (get st :methods) (get i :methods) 0)))]
    [php/inherit-state
     (fn [parent ifaces]
       (php/vfold php/inherit-interface
                  (if (nil? parent)
                    (assoc {} :consts [] :props [] :statics [] :methods [])
                    (assoc {} :consts (php/or-default (get parent :constants) [])
                           :props (php/or-default (get parent :props-decl) [])
                           :statics (php/or-default (get parent :static-props) [])
                           :methods (php/or-default (get parent :methods) [])))
                  ifaces 0))]
    ;; ---- traits: copied into the class, methods specialized to the using class
    [php/rule-excludes?
     ;; an insteadof rule that drops `tlc::mlc`
     (fn [rules tlc mlc]
       (php/vfold
         (fn [hit r]
           (if hit
             true
             (if (= (php/lc (get r :method)) mlc)
               (php/vfold (fn [h x] (if h true (= (php/lc x) tlc))) false (get r :excluded) 0)
               false)))
         false rules 0))]
    [php/rule-vis
     ;; the visibility an `as` rule without alias gives `tlc::mlc`, or nil
     (fn [rules tlc mlc]
       (php/vfold
         (fn [v r]
           (if (nil? v)
             (if (nil? (get r :alias))
               (if (= (php/lc (get r :method)) mlc)
                 (if (if (nil? (get r :trait)) true (= (php/lc (get r :trait)) tlc)) (get r :vis) nil)
                 nil)
               nil)
             v))
         nil rules 0))]
    [php/trait-method-add
     ;; add trait method `tm` (from trait `t`) to the staged vector `tm-list`
     (fn [tm-list cls t tm name]
       (let [m (assoc (php/make-method cls tm) :from-trait (get t :name) :name name :lc (php/lc name))
             prior (php/vfind tm-list :lc (get m :lc) 0)]
         (if (nil? prior)
           (php/vconj tm-list m)
           (if (= (get prior :abstract?) true)
             (php/vput tm-list :lc m)
             (if (= (get m :abstract?) true)
               tm-list
               (php/throw-error
                 "Error"
                 (data/str-concat "Trait method " (get t :name) "::" (get tm :name)
                                  " has not been applied as " cls "::" (get tm :name)
                                  ", because of collision with " (get prior :from-trait) "::"
                                  (get prior :name))))))))]
    [php/apply-trait
     (fn [cls rules]
       (fn [tm-list t]
         (php/vfold
           (fn [acc tm]
             (let [tlc (php/lc (get t :name))
                   mlc (get tm :lc)
                   dropped (php/rule-excludes? (php/or-default (get rules :insteadof) []) tlc mlc)
                   acc1 (if dropped
                          acc
                          (let [vis (php/rule-vis (php/or-default (get rules :as) []) tlc mlc)
                                tm2 (if (nil? vis) tm (assoc tm :vis vis))]
                            (php/trait-method-add acc cls t tm2 (get tm :name))))]
               (php/vfold
                 (fn [a r]
                   (if (if (nil? (get r :alias)) false (= (php/lc (get r :method)) mlc))
                     (if (if (nil? (get r :trait)) true (= (php/lc (get r :trait)) tlc))
                       (php/trait-method-add a cls t
                                             (if (nil? (get r :vis)) tm (assoc tm :vis (get r :vis)))
                                             (get r :alias))
                       a)
                     a))
                 acc1 (php/or-default (get rules :as) []) 0)))
           tm-list (get t :methods) 0)))]
    [php/apply-traits
     (fn [st cls traits rules]
       (let [tms (php/vfold (php/apply-trait cls rules) [] traits 0)
             consts (php/vfold
                      (fn [acc t]
                        (php/vfold (fn [a e] (php/vput a :name e)) acc (get t :constants) 0))
                      (get st :consts) traits 0)
             props (php/vfold
                     (fn [acc t]
                       (php/vfold (fn [a p] (php/vput a :id (php/make-prop cls p)))
                                  acc (get t :props-decl) 0))
                     (get st :props) traits 0)
             statics (php/vfold
                       (fn [acc t]
                         (php/vfold (fn [a s] (php/vput a :name (php/make-static cls s)))
                                    acc (get t :static-props) 0))
                       (get st :statics) traits 0)]
         (assoc st :consts consts :props props :statics statics
                :methods (php/vfold php/put-method (get st :methods) tms 0))))]
    [php/apply-own
     (fn [st cls spec kind]
       (let [consts (php/vfold (fn [acc s] (php/vput acc :name (php/make-const cls s)))
                               (get st :consts) (php/or-default (get spec :consts) []) 0)
             props (php/vfold
                     (fn [acc p]
                       (if (= (get p :static?) true) acc (php/vput acc :id (php/make-prop cls p))))
                     (get st :props) (php/or-default (get spec :props) []) 0)
             statics (php/vfold
                       (fn [acc p]
                         (if (= (get p :static?) true)
                           (php/vput acc :name (php/make-static cls p))
                           acc))
                       (get st :statics) (php/or-default (get spec :props) []) 0)
             methods (php/vfold
                       (fn [acc s]
                         (php/put-method
                           acc
                           (php/make-method cls (if (= kind :interface) (assoc s :abstract? true) s))))
                       (get st :methods) (php/or-default (get spec :methods) []) 0)]
         (assoc st :consts consts :props props :statics statics :methods methods)))]
    [php/abstract-list
     (fn [methods i acc]
       (if (< i (data/count methods))
         (php/abstract-list
           methods (+ i 1)
           (if (= (get (get methods i) :abstract?) true)
             (php/vconj acc (data/str-concat (get (get methods i) :declaring) "::"
                                             (get (get methods i) :name)))
             acc))
         acc))]
    [php/check-abstract
     (fn [name kind abstract? methods]
       (let [abs (php/abstract-list methods 0 [])
             n (data/count abs)]
         (if (if (= n 0) true (if (= kind :class) abstract? (not (= kind :enum))))
           :php/null
           (php/throw-error
             "Error"
             (data/str-concat "Class " name " contains " (php/to-string n)
                              (if (= n 1) " abstract method" " abstract methods")
                              " and must therefore be declared abstract or implement the remaining methods ("
                              (data/str-join ", " abs) ")")))))]
    [php/make-ancestors
     (fn [name parent ifaces kind backing]
       (let [a1 (php/vconj [] (php/lc name))
             a2 (if (nil? parent) a1 (php/vunion a1 (get parent :ancestors) 0))
             a3 (php/vfold (fn [acc i] (php/vunion acc (get i :ancestors) 0)) a2 ifaces 0)
             a4 (if (= kind :enum) (php/vconj a3 "unitenum") a3)]
         (if (nil? backing) a4 (php/vconj a4 "backedenum"))))]
    [php/enum-case-obj
     (fn [cls case backing]
       (php/new-obj cls
                    (if (nil? backing)
                      (php/table-set (php/empty-array) "name" (get case :name))
                      (php/table-set (php/table-set (php/empty-array) "name" (get case :name))
                                     "value" (get case :value)))))]
    [php/enum-state
     (fn [st name spec]
       (php/enum-members
         (php/vfold
           (fn [s case]
             (assoc s :consts
                    (php/vput (get s :consts) :name
                              (php/make-const name (assoc {} :name (get case :name)
                                                          :value (php/enum-case-obj name case (get spec :backing)))))))
           st (php/or-default (get spec :cases) []) 0)
         name spec))]
    [php/enum-members
     ;; the `name`/`value` properties and the static `cases()` of an enum
     (fn [st name spec]
       (if (= (get spec :kind) :enum)
         (let [p1 (php/vput (get st :props) :id
                            (php/make-prop name (assoc {} :name "name" :vis :public :typed? true)))
               p2 (if (nil? (get spec :backing))
                    p1
                    (php/vput p1 :id (php/make-prop name (assoc {} :name "value" :vis :public :typed? true))))]
           (assoc st :props p2
                  :methods (php/put-method
                             (get st :methods)
                             (assoc {} :name "cases" :lc "cases" :vis :public :static? true :final? true
                                    :declaring name :native :cases))))
         st))]
    [php/finish-class
     (fn [spec name kind parent ifaces traits st]
       (assoc {} :php/type :class :name name :kind kind
              :parent (if (nil? parent) :php/null (get parent :name))
              :interfaces (php/vfold (fn [acc i] (php/vconj acc (get i :name))) [] ifaces 0)
              :traits (php/vfold (fn [acc t] (php/vconj acc (get t :name))) [] traits 0)
              :ancestors (php/make-ancestors name parent ifaces kind (get spec :backing))
              :abstract? (php/or-default (get spec :abstract?) false)
              :final? (if (= kind :enum) true (php/or-default (get spec :final?) false))
              :readonly? (php/or-default (get spec :readonly?) false)
              :constants (get st :consts) :props-decl (get st :props)
              :static-props (get st :statics) :methods (get st :methods)
              :backing (get spec :backing)
              :cases (php/vfold (fn [acc c] (php/vconj acc (get c :name))) []
                                (php/or-default (get spec :cases) []) 0)
              :attrs (php/or-default (get spec :attrs) [])))]
    [php/declare-class!
     ;; registers the descriptor `spec` describes and answers it. A spec:
     ;; {:name :kind (:class :interface :trait :enum) :parent name :interfaces
     ;;  [name] :traits [name] :trait-rules {:insteadof [{:trait :method :excluded}]
     ;;  :as [{:trait :method :alias :vis}]} :abstract? :final? :consts [{:name
     ;;  :value | :thunk}] :props [{:name :vis :static? :default :type :readonly?}]
     ;;  :methods [{:name :code | :make (fn [class] code) :vis :static? :abstract?
     ;;  :final?}] :cases [{:name :value}] :backing nil|:int|:string :attrs}
     (fn [spec]
       (let [name (get spec :name)
             kind (php/or-default (get spec :kind) :class)]
         (if (nil? (php/class-lookup name))
           (let [parent (php/decl-parent spec)
                 ifaces (php/decl-interfaces spec)
                 traits (php/decl-traits spec)
                 st0 (php/inherit-state parent ifaces)
                 st1 (php/apply-traits st0 name traits (php/or-default (get spec :trait-rules) {}))
                 st2 (php/apply-own st1 name spec kind)
                 st3 (php/enum-state st2 name spec)
                 chk (php/check-abstract name kind (php/or-default (get spec :abstract?) false)
                                         (get st3 :methods))
                 c (php/finish-class spec name kind parent ifaces traits st3)]
             (do (cell/set! php.rt/classes (assoc (cell/get php.rt/classes) name c))
                 (cell/set! php.rt/class-order (php/vconj (cell/get php.rt/class-order) name))
                 (cell/set! php.rt/class-index (assoc (cell/get php.rt/class-index) (php/lc name) name))
                 c))
           (php/throw-error "Error"
                            (data/str-concat "Cannot declare " (php/kind-word kind) " " name
                                             ", because the name is already in use")))))]
    ;; ---------------------------------------------------------------- objects (P4 S2)
    ;; An object is `{:php/obj cell}`; the cell holds {:class name :props table
    ;; :id :rc :ord}. The props table is keyed by property identity (`:id` of the
    ;; descriptor entry; a dynamic property is keyed by its name). `ctx` is the
    ;; calling class name, or :php/null outside a class (lexical, threaded by
    ;; the lowering as `%cc`).
    [php/set-strict! (fn [on] (do (cell/set! php.rt/strict on) :php/null))]
    [php/given-name
     (fn [v]
       (let [k (php/kind v)]
         (if (= k :obj)
           (if (nil? (get v :php/obj))
             (if (php/closure? v) "Closure" (get v :class))
             (get (php/obj-state v) :class))
           (php/type-name k))))]
    [php/coerce-arg
     ;; a passed argument against its parameter type: coerced (coercive mode) or TypeError
     (fn [v type fname idx pname]
       (let [r (php/type-accepts type v (cell/get php.rt/strict))]
         (if (= r :php/undef)
           (php/throw-error
             "TypeError"
             (data/str-concat fname "(): Argument #" (php/to-string idx) " ($" pname ") must be of type "
                              (php/type-str type) ", " (php/given-name v) " given"))
           r)))]
    [php/param-default
     ;; a declared default is held to the type like a property default: exact,
     ;; with int widening to float
     (fn [v type fname idx pname]
       (let [r (php/type-accepts type v true)]
         (if (= r :php/undef)
           (php/throw-error
             "Error"
             (data/str-concat "Cannot use " (php/given-name v) " as default value for parameter $"
                              pname " of type " (php/type-str type)))
           r)))]
    [php/coerce-return
     (fn [v type fname]
       (let [r (php/type-accepts type v (cell/get php.rt/strict))]
         (if (= r :php/undef)
           (php/throw-error
             "TypeError"
             (data/str-concat fname "(): Return value must be of type " (php/type-str type) ", "
                              (php/given-name v) " returned"))
           r)))]
    [php/subst-static
     ;; the `static` atoms of a return type become the called class
     (fn [type cls]
       (if (= (get type :t) :union)
         (assoc type :of (php/subst-atoms (get type :of) cls 0 []))
         (if (= (get type :t) :static) (assoc {} :t :class :name cls) type)))]
    [php/subst-atoms
     (fn [ts cls i acc]
       (if (< i (data/count ts))
         (php/subst-atoms ts cls (+ i 1) (php/vconj acc (php/subst-static (get ts i) cls)))
         acc))]
    [php/coerce-return-s
     (fn [v type fname static]
       (let [r (php/type-accepts (if (nil? static) type (php/subst-static type static)) v
                                 (cell/get php.rt/strict))]
         (if (= r :php/undef)
           (php/throw-error
             "TypeError"
             (data/str-concat fname "(): Return value must be of type " (php/type-str type) ", "
                              (php/given-name v) " returned"))
           r)))]
    [php/never-returned
     (fn [fname]
       (php/throw-error
         "TypeError"
         (data/str-concat fname "(): never-returning function must not implicitly return")))]
    [php/return-none
     (fn [type fname]
       (php/throw-error
         "TypeError"
         (data/str-concat fname "(): Return value must be of type " (php/type-str type)
                          ", none returned")))]
    [php/coerce-prop
     ;; a value written to a typed property: coerced or TypeError
     (fn [e v]
       (if (nil? (get e :type))
         v
         (let [r (php/type-accepts (get e :type) v (cell/get php.rt/strict))]
           (if (= r :php/undef)
             (php/throw-error
               "TypeError"
               (data/str-concat "Cannot assign " (php/given-name v) " to property " (get e :declaring)
                                "::$" (get e :name) " of type " (php/type-str (get e :type))))
             r))))]
    [php/prop-key (fn [name ctx] (assoc {} :php/prop name :ctx ctx))]
    [php/prop-key? (fn [k] (not (nil? (get k :php/prop))))]
    [php/this-of
     (fn [f]
       (let [t (get (cell/get f) :this)]
         (if (nil? t) (php/throw-error "Error" "Using $this when not in object context") t)))]
    [php/this-or-nil (fn [f] (get (cell/get f) :this))]
    [php/has-this? (fn [f] (not (nil? (get (cell/get f) :this))))]
    [php/this-or-null
     (fn [f] (let [t (get (cell/get f) :this)] (if (nil? t) :php/null t)))]
    [php/static-or-nil (fn [f] (get (cell/get f) :static))]
    [php/hoist-class!
     ;; declared before the unit runs when everything it needs already exists
     (fn [thunk]
       (let [spec (thunk)]
         (if (php/class-declarable? spec)
           (if (nil? (php/class-lookup (get spec :name))) (php/declare-class! spec) :php/null)
           :php/null)))]
    [php/declare-late!
     ;; the declaration statement itself: a no-op when the class was hoisted
     (fn [thunk]
       (let [spec (thunk)]
         (if (nil? (php/class-lookup (get spec :name))) (php/declare-class! spec) :php/null)))]
    [php/static-of
     (fn [f]
       (let [s (get (cell/get f) :static)]
         (if (nil? s)
           (php/throw-error "Error" "Cannot use \"static\" when no class scope is active")
           s)))]
    [php/parent-of
     (fn [cc]
       (let [p (get (php/class-lookup cc) :parent)]
         (if (= p :php/null)
           (php/throw-error "Error" "Cannot use \"parent\" when current class scope has no parent")
           p)))]
    [php/class-by-name (fn [name] (php/need-class name "Class"))]
    [php/obj-state (fn [o] (cell/get (get o :php/obj)))]
    [php/obj-class (fn [o] (php/class-lookup (get (php/obj-state o) :class)))]
    [php/related-class?
     ;; protected access: `ctx` and `declaring` share a line of inheritance
     (fn [ctx declaring]
       (if (= ctx :php/null)
         false
         (if (php/class-is? ctx (php/lc declaring)) true (php/class-is? declaring (php/lc ctx)))))]
    [php/visible?
     (fn [vis declaring ctx]
       (if (= vis :public)
         true
         (if (= ctx :php/null)
           false
           (if (= vis :private)
             (= (php/lc ctx) (php/lc declaring))
             (php/related-class? ctx declaring)))))]
    [php/scope-text
     (fn [ctx]
       (if (= ctx :php/null) "global scope" (data/str-concat "scope " ctx)))]
    [php/vis-word (fn [vis] (if (= vis :private) "private" (if (= vis :protected) "protected" "public")))]
    [php/prop-find
     ;; {:kind :ok | :denied | :none :entry e} for property `name` of class `c`
     ;; seen from `ctx`
     (fn [c name ctx]
       (let [priv (if (= ctx :php/null)
                    nil
                    (php/vfind (get c :props-decl) :id (data/str-concat ctx "::" name) 0))]
         (if (nil? priv)
           (let [e (php/vfind (get c :props-decl) :id name 0)]
             (if (nil? e)
               (let [hidden (php/vfind (get c :props-decl) :name name 0)]
                 (if (nil? hidden)
                   (assoc {} :kind :none)
                   (assoc {} :kind :denied :entry hidden)))
               (if (php/visible? (get e :vis) (get e :declaring) ctx)
                 (assoc {} :kind :ok :entry e)
                 (assoc {} :kind :denied :entry e))))
           (assoc {} :kind :ok :entry priv))))]
    [php/denied-error
     (fn [o e name]
       (php/throw-error "Error"
                        (data/str-concat "Cannot access " (php/vis-word (get e :vis)) " property "
                                         (get (php/obj-state o) :class) "::$" name)))]
    [php/arr-from-vec
     (fn [v i acc]
       (if (< i (data/count v)) (php/arr-from-vec v (+ i 1) (php/array-append acc (get v i))) acc))]
    [php/bind-method
     (fn [m this static]
       (let [st (cell/get (if (nil? (get m :native)) (get m :code) (php/native-cell m)))]
         (cell/new (if (nil? this) (assoc st :static static) (assoc st :this this :static static)))))]
    [php/invoke
     ;; named arguments travel as a last element `{:php/named [[name v] ...]}`
     (fn [m this static args]
       (let [n (data/count args)
             tail (if (> n 0) (get args (- n 1)) nil)]
         (if (nil? (get tail :php/named))
           (php/call (php/bind-method m this static) args)
           (php/call-named (php/bind-method m this static)
                           (php/args-prefix args (- n 1) 0 [])
                           (get tail :php/named)))))]
    [php/args-prefix
     (fn [args n i acc]
       (if (< i n) (php/args-prefix args n (+ i 1) (php/vconj acc (get args i))) acc))]
    [php/magic-call
     ;; user method `lc` of object `o` with arguments `v`
     (fn [o c lc v]
       (php/invoke (php/class-method c lc) o (get (php/obj-state o) :class) v))]
    [php/prop-fetch
     (fn [o st e]
       (let [v (php/array-get (get st :props) (get e :id))]
         (if (nil? v)
           (php/throw-error
             "Error"
             (data/str-concat "Typed property " (get e :declaring) "::$" (get e :name)
                              " must not be accessed before initialization"))
           v)))]
    [php/prop-read
     (fn [o name ctx]
       (let [st (php/obj-state o)
             c (php/class-lookup (get st :class))
             r (php/prop-find c name ctx)
             kind (get r :kind)]
         (if (= kind :ok)
           (php/prop-fetch o st (get r :entry))
           (if (= kind :denied)
             (if (nil? (php/class-method c "__get"))
               (php/denied-error o (get r :entry) name)
               (php/magic-call o c "__get" (php/vconj [] name)))
             (let [v (php/array-get (get st :props) name)]
               (if (nil? v)
                 (if (nil? (php/class-method c "__get"))
                   (do (php/warn 2 (data/str-concat "Undefined property: " (get st :class) "::$" name))
                       :php/null)
                   (php/magic-call o c "__get" (php/vconj [] name)))
                 v))))))]
    [php/prop-peek
     (fn [o name ctx]
       (let [st (php/obj-state o)
             c (php/class-lookup (get st :class))
             r (php/prop-find c name ctx)
             kind (get r :kind)
             v (if (= kind :ok)
                 (php/array-get (get st :props) (get (get r :entry) :id))
                 (if (= kind :none) (php/array-get (get st :props) name) nil))]
         (if (nil? v)
           (if (if (= kind :ok) false (not (nil? (php/class-method c "__isset"))))
             (if (php/to-bool (php/magic-call o c "__isset" (php/vconj [] name)))
               (if (nil? (php/class-method c "__get"))
                 :php/null
                 (php/magic-call o c "__get" (php/vconj [] name)))
               :php/null)
             :php/null)
           v)))]
    [php/prop-store!
     (fn [o id v]
       (php/obj-set-props! o (php/assign-slot (get (php/obj-state o) :props) id v :owned)))]
    [php/prop-write!
     ;; `$o->name = v`; `v` is owned
     (fn [o name ctx v]
       (let [st (php/obj-state o)
             c (php/class-lookup (get st :class))
             r (php/prop-find c name ctx)
             kind (get r :kind)]
         (if (= kind :ok)
           (if (= (get (get r :entry) :readonly?) true)
             (php/ro-write o (get r :entry) ctx v)
             (php/prop-store! o (get (get r :entry) :id) (php/coerce-prop (get r :entry) v)))
           (if (if (= kind :none) (not (nil? (php/slot-get (get st :props) name))) false)
             (php/prop-store! o name v)
             (if (nil? (php/class-method c "__set"))
               (if (= kind :denied)
                 (php/denied-error o (get r :entry) name)
                 (php/prop-store! o name v))
               (php/magic-call o c "__set" (php/vconj (php/vconj [] name) v)))))))]
    [php/prop-nested-slot
     ;; [id current-value] of a property about to be written through a path
     (fn [o name ctx]
       (let [st (php/obj-state o)
             c (php/class-lookup (get st :class))
             r (php/prop-find c name ctx)
             kind (get r :kind)]
         (if (= kind :denied)
           (php/denied-error o (get r :entry) name)
           (let [id (if (= kind :ok) (get (get r :entry) :id) name)
                 cur (php/array-get (get st :props) id)]
             (if (if (= kind :ok)
                   (if (= (get (get r :entry) :readonly?) true)
                     (if (nil? cur) false (nil? (get cur :php/obj)))
                     false)
                   false)
               (php/throw-error "Error"
                                (data/str-concat "Cannot modify readonly property "
                                                 (get (get r :entry) :declaring) "::$" name))
               (php/pair id (if (nil? cur) :php/null cur)))))))]
    [php/check-auto-init
     ;; `$o->p[] = v` on a null or uninitialized typed property: only a type that
     ;; admits an array may be auto-initialized
     (fn [o name ctx cur next-key]
       (if (if (= cur :php/null) (not (php/prop-key? next-key)) false)
         (let [r (php/prop-find (php/obj-class o) name ctx)
               e (get r :entry)]
           (if (if (= (get r :kind) :ok) (not (nil? (get e :type))) false)
             (if (= (php/type-accepts (get e :type) (php/empty-array) true) :php/undef)
               (php/throw-error "Error"
                                (data/str-concat "Cannot auto-initialize an array inside property "
                                                 (get e :declaring) "::$" (get e :name) " of type "
                                                 (php/type-str (get e :type))))
               :php/null)
             :php/null))
         :php/null))]
    [php/assign-prop-path
     (fn [c ks i v]
       (let [pk (get ks i)
             name (get pk :php/prop)
             ctx (get pk :ctx)]
         (if (= (php/kind c) :obj)
           (if (= (+ i 1) (data/count ks))
             (do (php/prop-write! c name ctx v) c)
             (let [slot (php/prop-nested-slot c name ctx)
                   cur (get slot 1)
                   chk (php/check-auto-init c name ctx cur (get ks (+ i 1)))
                   nv (php/path-assign-at cur ks (+ i 1) v)]
               (do (if (= (php/kind nv) :obj)
                     (if (= nv cur) :php/null (php/obj-set-props! c (php/store (get (php/obj-state c) :props) (get slot 0) nv)))
                     (php/obj-set-props! c (php/store (get (php/obj-state c) :props) (get slot 0) nv)))
                   c)))
           (php/throw-error "Error"
                            (data/str-concat "Attempt to assign property \"" name "\" on "
                                             (php/type-name (php/kind c)))))))]
    [php/prop-unset!
     (fn [o name ctx]
       (let [st (php/obj-state o)
             c (php/class-lookup (get st :class))
             r (php/prop-find c name ctx)
             kind (get r :kind)]
         (if (= kind :ok)
           (if (= (get (get r :entry) :readonly?) true)
             (php/ro-unset o (get r :entry) ctx)
             (php/obj-unset-prop o (get (get r :entry) :id)))
           (if (if (= kind :none) (not (nil? (php/slot-get (get st :props) name))) false)
             (php/obj-unset-prop o name)
             (if (nil? (php/class-method c "__unset"))
               (if (= kind :denied) (php/denied-error o (get r :entry) name) :php/null)
               (php/magic-call o c "__unset" (php/vconj [] name)))))))]
    [php/unset-prop-path
     (fn [c ks i]
       (let [pk (get ks i)
             name (get pk :php/prop)
             ctx (get pk :ctx)]
         (if (= (php/kind c) :obj)
           (if (= (+ i 1) (data/count ks))
             (do (php/prop-unset! c name ctx) c)
             (let [slot (php/prop-nested-slot c name ctx)
                   cur (get slot 1)]
               (if (= cur :php/null)
                 c
                 (let [nv (php/path-unset-at cur ks (+ i 1))]
                   (do (if (= (php/kind nv) :obj)
                         :php/null
                         (php/obj-set-props! c (php/store (get (php/obj-state c) :props) (get slot 0) nv)))
                       c)))))
           c)))]
    [php/prop-read-nonobj
     (fn [c name]
       (do (php/warn 2 (data/str-concat "Attempt to read property \"" name "\" on "
                                        (php/type-name (php/kind c))))
           :php/null))]
    ;; ---- construction (row 53): resolve and refuse, create, then run the constructor
    [php/default-for
     ;; a typed property's default must already be of the type (an int may
     ;; widen to float); no weak coercion, as PHP checks it at compile time
     (fn [e v]
       (if (nil? (get e :type))
         v
         (let [r (php/type-accepts (get e :type) v true)]
           (if (= r :php/undef)
             (php/throw-error
               "Error"
               (data/str-concat "Cannot use " (php/given-name v) " as default value for property "
                                (get e :declaring) "::$" (get e :name) " of type "
                                (php/type-str (get e :type))))
             r))))]
    [php/init-props
     (fn [props decls i n]
       (if (< i n)
         (let [e (get decls i)]
           (php/init-props
             (if (nil? (get e :thunk))
               (if (nil? (get e :default))
                 (if (= (get e :typed?) true) props (php/table-set props (get e :id) :php/null))
                 (php/table-set props (get e :id) (php/default-for e (get e :default))))
               (php/table-set props (get e :id) (php/default-for e ((get e :thunk)))))
             decls (+ i 1) n))
         props))]
    [php/instantiate
     (fn [c]
       (php/register-final!
         (php/new-obj (get c :name)
                      (php/init-props (php/empty-array) (get c :props-decl) 0
                                      (data/count (get c :props-decl))))
         c))]
    [php/new-check
     (fn [c]
       (let [k (php/or-default (get c :kind) :class)]
         (if (= k :class)
           (if (= (get c :abstract?) true)
             (php/throw-error "Error" (data/str-concat "Cannot instantiate abstract class " (get c :name)))
             c)
           (php/throw-error "Error" (data/str-concat "Cannot instantiate " (php/kind-word k) " " (get c :name))))))]
    [php/new-begin
     ;; the object, rc 1, held by the statement's temporary list
     (fn [c line]
       (let [o (php/instantiate (php/new-check c))]
         (do (cell/set! (get o :php/obj) (assoc (php/obj-state o) :destructed? true))
             (if (php/class-is? (get c :name) "throwable")
               (php/obj-set-props! o (php/table-set (get (php/obj-state o) :props) "line" line))
               :php/null)
             (php/tmp! o))))]
    [php/ctor-done!
     ;; the constructor returned: the object may be destructed from now on
     (fn [o]
       (do (cell/set! (get o :php/obj) (assoc (php/obj-state o) :destructed? false)) o))]
    [php/new-finish
     (fn [o args ctx]
       (let [c (php/obj-class o)
             m (php/class-method c "__construct")]
         (if (nil? m)
           (php/ctor-done! o)
           (if (php/visible? (get m :vis) (get m :declaring) ctx)
             (do (php/invoke m o (get c :name) args) (php/ctor-done! o))
             (php/throw-error
               "Error"
               (data/str-concat "Call to " (php/vis-word (get m :vis)) " " (get c :name)
                                "::__construct() from " (php/scope-text ctx)))))))]
    ;; ---- methods
    [php/method-denied
     (fn [cname m ctx]
       (php/throw-error
         "Error"
         (data/str-concat "Call to " (php/vis-word (get m :vis)) " method " cname "::" (get m :name)
                          "() from " (php/scope-text ctx))))]
    [php/private-override
     ;; a private method of the calling class wins over the object's own
     (fn [m0 o ctx lc]
       (if (= ctx :php/null)
         m0
         (let [cm (php/class-method (php/class-lookup ctx) lc)]
           (if (nil? cm)
             m0
             (if (= (get cm :vis) :private)
               (if (= (php/lc (get cm :declaring)) (php/lc ctx))
                 (if (php/class-is? (get (php/obj-state o) :class) (php/lc ctx)) cm m0)
                 m0)
               m0)))))]
    [php/call-obj-method
     (fn [o name args ctx]
       (let [cname (get (php/obj-state o) :class)
             c (php/class-lookup cname)
             lc (php/lc name)
             m (php/private-override (php/class-method c lc) o ctx lc)]
         (if (nil? m)
           (if (nil? (php/class-method c "__call"))
             (php/throw-error "Error" (data/str-concat "Call to undefined method " cname "::" name "()"))
             (php/magic-call o c "__call" (php/vconj (php/vconj [] name) (php/arr-from-vec args 0 (php/empty-array)))))
           (if (php/visible? (get m :vis) (get m :declaring) ctx)
             (php/invoke m (if (= (get m :static?) true) nil o) cname args)
             (if (nil? (php/class-method c "__call"))
               (php/method-denied cname m ctx)
               (php/magic-call o c "__call" (php/vconj (php/vconj [] name) (php/arr-from-vec args 0 (php/empty-array)))))))))]
    [php/static-call
     ;; `C::name(args)`: `this` is the calling method's object or nil; `fwd` the
     ;; forwarded static class (self::, parent::, static::) or nil
     (fn [c name args ctx this fwd]
       (let [cname (get c :name)
             lc (php/lc name)
             m (php/class-method c lc)]
         (if (nil? m)
           (if (nil? (php/class-method c "__callstatic"))
             (php/throw-error "Error" (data/str-concat "Call to undefined method " cname "::" name "()"))
             (php/invoke (php/class-method c "__callstatic") nil cname
                         (php/vconj (php/vconj [] name) (php/arr-from-vec args 0 (php/empty-array)))))
           (if (php/visible? (get m :vis) (get m :declaring) ctx)
             (if (= (get m :static?) true)
               (php/invoke m nil (php/or-default fwd cname) args)
               (if (if (nil? this) false (php/class-is? (get (php/obj-state this) :class) (php/lc cname)))
                 (php/invoke m this (get (php/obj-state this) :class) args)
                 (php/throw-error "Error" (data/str-concat "Non-static method " (get m :declaring) "::"
                                                           (get m :name) "() cannot be called statically"))))
             (php/method-denied cname m ctx)))))]
    [php/sprop-cell
     (fn [c name ctx]
       (let [e (php/vfind (get c :static-props) :name name 0)]
         (if (nil? e)
           (php/throw-error "Error"
                            (data/str-concat "Access to undeclared static property " (get c :name) "::$" name))
           (if (php/visible? (get e :vis) (get e :declaring) ctx)
             (get e :cell)
             (php/throw-error "Error"
                              (data/str-concat "Cannot access " (php/vis-word (get e :vis))
                                               " property " (get c :name) "::$" name))))))]
    [php/class-const-in
     (fn [c name ctx]
       (let [e (php/vfind (get c :constants) :name name 0)]
         (if (nil? e)
           (php/throw-error "Error" (data/str-concat "Undefined constant " (get c :name) "::" name))
           (if (php/visible? (get e :vis) (get e :declaring) ctx)
             (php/class-const c name)
             (php/throw-error "Error"
                              (data/str-concat "Cannot access " (php/vis-word (get e :vis))
                                               " constant " (get c :name) "::" name))))))]
    [php/obj-to-string
     (fn [o]
       (let [c (php/obj-class o)]
         (if (nil? (php/class-method c "__tostring"))
           (php/throw-error "Error" (data/str-concat "Object of class " (get c :name)
                                                     " could not be converted to string"))
           (php/magic-call o c "__tostring" []))))]
    [php/bi-get-class
     (fn [args]
       (if (= (php/kind (get args 0)) :obj)
         (get (php/obj-state (get args 0)) :class)
         (php/throw-error "TypeError" "get_class(): Argument #1 ($object) must be of type object")))]
    ;; ---- finalization (rows 48 to 51), clone (row 52), shutdown (row 22)
    [php/fin-remove
     (fn [x]
       (cell/set! php.rt/finalizable
                  (php/fin-without (cell/get php.rt/finalizable) (get (php/obj-state x) :id) 0 [])))]
    [php/fin-without
     (fn [v id i acc]
       (if (< i (data/count v))
         (php/fin-without v id (+ i 1)
                          (if (= (get (php/obj-state (get v i)) :id) id) acc (php/vconj acc (get v i))))
         acc))]
    [php/register-final!
     ;; a class with `__destruct` registers its objects in creation order
     (fn [o c]
       (if (nil? (php/class-method c "__destruct"))
         o
         (do (cell/set! php.rt/finalizable (php/vconj (cell/get php.rt/finalizable) o)) o)))]
    [php/pending-add!
     ;; the chaining rule: a destructor exception replaces the pending one,
     ;; which becomes its `previous`
     (fn [e]
       (let [p (cell/get php.rt/pending-exc)]
         (do (if (= p :php/null)
               :php/null
               (php/obj-set-props!
                 e
                 (php/table-set (get (php/obj-state e) :props)
                                (if (php/class-is? (get (php/obj-state e) :class) "exception")
                                  "Exception::previous"
                                  "Error::previous")
                                p)))
             (cell/set! php.rt/pending-exc e))))]
    [php/flush-pending!
     ;; the releasing operation has finished its releases: propagate
     (fn []
       (let [p (cell/get php.rt/pending-exc)]
         (if (= p :php/null)
           :php/null
           (do (cell/set! php.rt/pending-exc :php/null) (php/raise p)))))]
    [php/run-destructor
     (fn [x]
       (let [m (php/class-method (php/obj-class x) "__destruct")
             e (php/try (fn [] (do (php/invoke m x (get (php/obj-state x) :class) []) :php/null))
                        (fn [e] e)
                        (fn [] :php/null))]
         (if (= e :php/null) :php/null (php/pending-add! e))))]
    [php/finalize!
     ;; DESTROY: pin, run the destructor, unpin; resurrected when still held
     (fn [x]
       (let [c (get x :php/obj)]
         (do (cell/set! c (assoc (assoc (cell/get c) :destructed? true) :rc 1))
             (php/fin-remove x)
             (php/run-destructor x)
             (let [st (cell/get c)
                   n (- (get st :rc) 1)]
               (do (cell/set! c (assoc st :rc n))
                   (if (> n 0) :php/null (php/free-obj x)))))))]
    [php/shutdown-pass
     ;; one reverse walk of the globals: an object held only there is released
     (fn [order i]
       (if (< i 0)
         :php/null
         (let [k (get order i)
               cur (php/global-slot k)]
           (do (if (nil? cur)
                 :php/null
                 (if (= (php/slot-kind cur) :obj-handle)
                   (if (= (php/rc-of cur) 1) (php/global-unset! k) :php/null)
                   :php/null))
               (php/shutdown-pass order (- i 1))))))]
    [php/shutdown-globals
     (fn []
       (let [before (data/count (get (cell/get php.rt/globals) :order))]
         (do (php/shutdown-pass (get (cell/get php.rt/globals) :order) (- before 1))
             (if (= before (data/count (get (cell/get php.rt/globals) :order)))
               :php/null
               (php/shutdown-globals)))))]
    [php/shutdown-finalizable
     (fn [objs i]
       (if (< i (data/count objs))
         (let [x (get objs i)]
           (do (if (get (php/obj-state x) :destructed?)
                 :php/null
                 (do (cell/set! (get x :php/obj) (assoc (php/obj-state x) :destructed? true))
                     (php/fin-remove x)
                     (php/run-destructor x)))
               (php/shutdown-finalizable objs (+ i 1))))
         :php/null))]
    [php/run-shutdown-fns
     ;; callbacks in registration order; one may register more
     (fn [i]
       (let [fns (cell/get php.rt/shutdown-fns)]
         (if (< i (data/count fns))
           (do (php/call-value (get (get fns i) 0) (get (get fns i) 1))
               (php/run-shutdown-fns (+ i 1)))
           :php/null)))]
    [php/args-tail
     (fn [args i acc]
       (if (< i (data/count args)) (php/args-tail args (+ i 1) (php/vconj acc (get args i))) acc))]
    [php/bi-register-shutdown-function
     (fn [args]
       (do (cell/set! php.rt/shutdown-fns
                      (php/vconj (cell/get php.rt/shutdown-fns)
                                 (php/pair (get args 0) (php/args-tail args 1 []))))
           :php/null))]
    [php/freeze-finalizable
     ;; step 4 frees without destructors: whatever is left counts as destructed
     (fn [objs i]
       (if (< i (data/count objs))
         (do (cell/set! (get (get objs i) :php/obj) (assoc (php/obj-state (get objs i)) :destructed? true))
             (php/freeze-finalizable objs (+ i 1)))
         :php/null))]
    [php/release-globals
     (fn [order i]
       (if (< i 0)
         :php/null
         (do (php/global-unset! (get order i)) (php/release-globals order (- i 1)))))]
    [php/release-statics
     (fn [names i]
       (if (< i (data/count names))
         (do (php/release-static-cells (get (php/class-lookup (get names i)) :static-props) 0)
             (php/release-statics names (+ i 1)))
         :php/null))]
    [php/release-static-cells
     (fn [entries i]
       (if (< i (data/count entries))
         (do (php/unset-cv (get (get entries i) :cell)) (php/release-static-cells entries (+ i 1)))
         :php/null))]
    [php/shutdown!
     ;; row 22: shutdown functions, the destructor phase, then the release of
     ;; the globals and the static tables without destructors; an exception
     ;; from a callback or destructor ends the sequence
     (fn []
       (do (php/run-shutdown-fns 0)
           (php/shutdown-globals)
           (php/shutdown-finalizable (cell/get php.rt/finalizable) 0)
           (php/flush-pending!)
           (php/freeze-finalizable (cell/get php.rt/finalizable) 0)
           (php/release-globals (get (cell/get php.rt/globals) :order)
                                (- (data/count (get (cell/get php.rt/globals) :order)) 1))
           (php/release-statics (cell/get php.rt/class-order) 0)
           (php/flush-pending!)))]
    [php/clone-members
     ;; the properties of the copy: DUP semantics for an instance table
     (fn [props]
       (if (= (php/slot-kind props) :arr-handle) (php/dup props) props))]
    [php/readonly-ids
     (fn [decls i acc]
       (if (< i (data/count decls))
         (php/readonly-ids decls (+ i 1)
                           (if (= (get (get decls i) :readonly?) true)
                             (php/vconj acc (get (get decls i) :id))
                             acc))
         acc))]
    [php/clone-obj
     (fn [o ctx]
       (if (= (php/kind o) :obj)
         (if (nil? (get o :php/obj))
           (php/throw-error "Error" "Trying to clone an uncloneable object")
           (let [c (php/obj-class o)
                 m (php/class-method c "__clone")]
             (if (if (nil? m) false (not (php/visible? (get m :vis) (get m :declaring) ctx)))
               (php/throw-error "Error"
                                (data/str-concat "Call to " (php/vis-word (get m :vis)) " " (get c :name)
                                                 "::__clone() from " (php/scope-text ctx)))
               (let [n (php/register-final! (php/new-obj (get c :name)
                                                         (php/clone-members (get (php/obj-state o) :props)))
                                            c)]
                 (do (if (nil? m)
                       :php/null
                       (php/try-finally
                         (fn []
                           (do (cell/set! (get n :php/obj)
                                          (assoc (php/obj-state n) :reinit
                                                 (php/readonly-ids (get c :props-decl) 0 [])))
                               (php/invoke m n (get c :name) [])))
                         (fn [x] (cell/set! (get n :php/obj) (assoc (php/obj-state n) :reinit [])))))
                     (php/tmp! n))))))
         (php/throw-error "Error" "__clone method called on non-object")))]
    [php/reinit?
     (fn [st id] (php/vhas (php/or-default (get st :reinit) []) id 0))]
    [php/reinit-clear!
     (fn [o id]
       (let [st (php/obj-state o)]
         (cell/set! (get o :php/obj)
                    (assoc st :reinit (php/vec-remove (php/or-default (get st :reinit) []) id 0
                                                      (data/count (php/or-default (get st :reinit) []))
                                                      [])))))]
    [php/ro-in-scope?
     (fn [e ctx] (if (= ctx :php/null) false (= (php/lc ctx) (php/lc (get e :declaring)))))]
    [php/ro-write
     (fn [o e ctx v]
       (let [st (php/obj-state o)
             id (get e :id)
             cur (php/array-get (get st :props) id)]
         (if (nil? cur)
           (if (php/ro-in-scope? e ctx)
             (do (php/reinit-clear! o id) (php/prop-store! o id (php/coerce-prop e v)))
             (php/throw-error "Error"
                              (data/str-concat "Cannot initialize readonly property " (get e :declaring)
                                               "::$" (get e :name) " from " (php/scope-text ctx))))
           (if (php/reinit? st id)
             (do (php/reinit-clear! o id) (php/prop-store! o id (php/coerce-prop e v)))
             (php/throw-error "Error"
                              (data/str-concat "Cannot modify readonly property " (get e :declaring)
                                               "::$" (get e :name)))))))]
    [php/ro-unset
     (fn [o e ctx]
       (let [st (php/obj-state o)
             id (get e :id)
             cur (php/array-get (get st :props) id)
             what (data/str-concat "Cannot unset readonly property " (get e :declaring) "::$" (get e :name))]
         (if (php/ro-in-scope? e ctx)
           (if (nil? cur)
             (do (php/reinit-clear! o id) :php/null)
             (if (php/reinit? st id)
               (do (php/reinit-clear! o id) (php/obj-unset-prop o id))
               (php/throw-error "Error" what)))
           (php/throw-error "Error" (data/str-concat what " from " (php/scope-text ctx))))))]
    [php/ref-prop-path
     (fn [c ks i]
       (let [pk (get ks i)
             name (get pk :php/prop)
             ctx (get pk :ctx)]
         (if (= (php/kind c) :obj)
           (let [r (php/prop-find (php/obj-class c) name ctx)
                 kind (get r :kind)]
             (if (= kind :denied)
               (php/denied-error c (get r :entry) name)
               (let [e (get r :entry)
                     id (if (= kind :ok) (get e :id) name)
                     cur (php/array-get (get (php/obj-state c) :props) id)]
                 (if (if (= kind :ok) (= (get e :readonly?) true) false)
                   (php/throw-error
                     "Error"
                     (data/str-concat (if (nil? cur)
                                        "Cannot indirectly modify readonly property "
                                        "Cannot modify readonly property ")
                                      (get e :declaring) "::$" (get e :name)))
                   (if (= (+ i 1) (data/count ks))
                     (if (if (= kind :ok)
                           (if (nil? cur)
                             (if (nil? (get e :type)) false (= (php/type-accepts (get e :type) :php/null false) :php/undef))
                             false)
                           false)
                       (php/throw-error
                         "Error"
                         (data/str-concat "Cannot access uninitialized non-nullable property "
                                          (get e :declaring) "::$" (get e :name) " by reference"))
                       (let [m (php/make-ref (get (php/obj-state c) :props) id)]
                         (do (php/obj-set-props! c (get m 0))
                             (php/pair c (if (if (= kind :ok) (not (nil? (get e :type))) false)
                                           (php/ref-add-source
                                             (get m 1)
                                             (assoc {} :obj (get (php/obj-state c) :id)
                                                    :class (get e :declaring) :prop id
                                                    :type (get e :type)))
                                           (get m 1))))))
                     (let [rr (php/path-ref-at (if (nil? cur) :php/null cur) ks (+ i 1))]
                       (do (if (= (php/kind (get rr 0)) :obj)
                             :php/null
                             (php/obj-set-props! c (php/store (get (php/obj-state c) :props) id (get rr 0))))
                           (php/pair c (get rr 1)))))))))
           (php/throw-error "Error"
                            (data/str-concat "Attempt to assign property \"" name "\" on "
                                             (php/type-name (php/kind c)))))))]
    ;; ---- Throwable natives: seeded method entries carry `:native`, bound lazily
    [php/prop-raw
     (fn [o id] (let [v (php/array-get (get (php/obj-state o) :props) id)] (if (nil? v) :php/null v)))]
    [php/native-run
     (fn [kind decl f args]
       (if (= kind :cases)
         (php/enum-cases (php/class-lookup (get (cell/get f) :static)) 0 (php/empty-array))
         (let [o (php/this-of f)
               n (data/count args)]
           (if (= kind :ee-ctor)
             (do (if (> n 0) (php/prop-store! o "message" (php/to-string (get args 0))) :php/null)
                 (if (> n 1) (php/prop-store! o "code" (get args 1)) :php/null)
                 (if (> n 2) (php/prop-store! o "severity" (get args 2)) :php/null)
                 (if (> n 3)
                   (if (= (get args 3) :php/null) :php/null (php/prop-store! o "file" (get args 3)))
                   :php/null)
                 (if (> n 4)
                   (if (= (get args 4) :php/null) :php/null (php/prop-store! o "line" (get args 4)))
                   :php/null)
                 (if (> n 5)
                   (php/prop-store! o "Exception::previous" (php/addref (get args 5)))
                   :php/null)
                 :php/null)
             (if (= kind :ctor)
               (do (if (> n 0) (php/prop-store! o "message" (php/to-string (get args 0))) :php/null)
                   (if (> n 1) (php/prop-store! o "code" (get args 1)) :php/null)
                   (if (> n 2)
                     (php/prop-store! o (data/str-concat decl "::previous") (php/addref (get args 2)))
                     :php/null)
                   :php/null)
               (if (= kind :message) (php/prop-raw o "message")
                   (if (= kind :code) (php/prop-raw o "code")
                       (if (= kind :previous) (php/prop-raw o (data/str-concat decl "::previous"))
                           (if (= kind :file) (php/prop-raw o "file")
                               (if (= kind :line) (php/prop-raw o "line")
                                   (if (= kind :severity) (php/prop-raw o "severity")
                                       (if (= kind :trace) (php/empty-array) "#0 {main}"))))))))))))]
    [php/enum-cases
     (fn [c i acc]
       (if (< i (data/count (get c :cases)))
         (php/enum-cases c (+ i 1) (php/array-append acc (php/class-const c (get (get c :cases) i))))
         acc))]
    [php/native-cell
     (fn [m]
       (php/make-function (get m :name) :php/null
                          (fn [f args] (php/native-run (get m :native) (get m :declaring) f args))
                          []))]
    ;; ---------------------------------------------------------------- throwables, match, methods
    [php/class-is?
     (fn [name lc]
       (if (nil? name)
         false
         (if (= (php/lc name) lc)
           true
           (let [c (get (cell/get php.rt/classes) name)]
             (if (nil? c)
               false
               (if (nil? (get c :ancestors))
                 (if (= (get c :parent) :php/null) false (php/class-is? (get c :parent) lc))
                 (php/vhas (get c :ancestors) lc 0)))))))]
    [php/instance-of
     (fn [v lc]
       (if (= (php/kind v) :obj)
         (if (= (get v :php/type) :closure)
           (= lc "closure")
           (php/class-is? (if (nil? (get v :php/obj))
                            (get v :class)
                            (get (cell/get (get v :php/obj)) :class))
                          lc))
         false))]
    [php/throwable? (fn [v] (php/instance-of v "throwable"))]
    [php/throw-value
     (fn [v]
       (if (php/throwable? v)
         (php/raise (php/addref v))
         (php/throw-error "Error" "Can only throw objects")))]
    [php/match-repr
     (fn [v]
       (let [k (php/kind v)]
         (if (= k :int) (integer/format v)
             (if (= k :string) (data/str-concat "'" v "'")
                 (if (= k :bool) (if v "true" "false")
                     (if (= k :null) "NULL"
                         (if (= k :float) (php/to-string v)
                             (data/str-concat "of type "
                                              (if (nil? (get v :php/obj))
                                                (php/type-name k)
                                                (get (php/obj-state v) :class))))))))))]
    [php/unhandled-match
     (fn [v]
       (php/throw-error "UnhandledMatchError"
                        (data/str-concat "Unhandled match case " (php/match-repr v))))]
    [php/throwable-method
     ;; the Throwable accessors until P4 supplies real classes
     (fn [o lc name]
       (let [props (get o :props)]
         (if (= lc "getmessage") (get props "message")
             (if (= lc "getcode") (get props "code")
                 (if (= lc "getprevious") (get props "previous")
                     (php/throw-error "Error"
                                      (data/str-concat "Call to undefined method "
                                                       (get o :class) "::" name "()")))))))]
    [php/closure?
     (fn [v] (if (= (php/kind v) :obj) (= (get v :php/type) :closure) false))]
    [php/closure-copy
     (fn [v] (php/make-closure (cell/new (cell/get (get v :php/closure)))))]
    [php/closure-env
     ;; a closure made inside a method keeps the method's `$this` and static class
     (fn [f this static]
       (do (if (nil? this) :php/null (cell/set! f (assoc (cell/get f) :this this)))
           (if (nil? static) :php/null (cell/set! f (assoc (cell/get f) :static static)))
           f))]
    [php/scope-of
     ;; the class scope a closure runs in: a rebinding's, else the enclosing one
     (fn [f outer]
       (let [sc (get (cell/get f) :scope)] (if (nil? sc) outer sc)))]
    [php/arg-or (fn [args i d] (if (< i (data/count args)) (get args i) d))]
    [php/closure-bind
     ;; `Closure::bind` / `bindTo`: a copy with `$this` replaced; `scope` is an
     ;; object (its class), a class name, or "static" (the scope stays)
     (fn [c new-this scope]
       (let [st (cell/get (get c :php/closure))
             this2 (if (= new-this :php/null) nil new-this)
             sc (if (= (php/kind scope) :obj)
                  (php/scope-name scope)
                  (if (= (php/kind scope) :string)
                    (if (= scope "static") (get st :scope) (get (php/need-class scope "Class") :name))
                    (get st :scope)))
             st2 (assoc (assoc st :this this2) :scope sc)
             st3 (if (nil? this2)
                   st2
                   (assoc st2 :static (get (php/obj-state this2) :class)))]
         (php/make-closure (cell/new st3))))]
    [php/scope-name
     (fn [o] (if (nil? (get o :php/obj)) (get o :class) (get (php/obj-state o) :class)))]
    [php/closure-call
     ;; `$f->call($newThis, ...$args)`: bound to `$newThis` and its class for one call
     (fn [c args]
       (let [new-this (php/arg-or args 0 :php/null)
             bound (php/closure-bind c new-this new-this)]
         (php/call (get bound :php/closure) (php/args-prefix-from args 1 []))))]
    [php/args-prefix-from
     (fn [args i acc]
       (if (< i (data/count args)) (php/args-prefix-from args (+ i 1) (php/vconj acc (get args i))) acc))]
    [php/closure-method
     (fn [o lc name args]
       (if (= lc "bindto") (php/closure-bind o (php/arg-or args 0 :php/null) (php/arg-or args 1 "static"))
           (if (= lc "call") (php/closure-call o args)
               (if (= lc "__invoke") (php/call-value o args)
                   (php/throw-error "Error"
                                    (data/str-concat "Call to undefined method Closure::" name "()"))))))]
    [php/method-call
     ;; `$o->name(args)` from class scope `ctx`
     (fn [o name args ctx]
       (let [lc (php/lc name)]
         (if (nil? (get o :php/obj))
           (if (php/throwable? o)
             (php/throwable-method o lc name)
             (if (php/closure? o)
               (php/closure-method o lc name args)
               (php/throw-error "Error"
                                (data/str-concat "Call to a member function " name "() on "
                                                 (php/type-name (php/kind o))))))
           (php/call-obj-method o name args ctx))))]
    [php/closure-static
     ;; `Closure::bind($f, $newThis, $scope)` and `Closure::fromCallable($c)`
     (fn [lc name args ctx]
       (if (= lc "bind")
         (php/closure-bind (get args 0) (php/arg-or args 1 :php/null) (php/arg-or args 2 "static"))
         (if (= lc "fromcallable")
           (if (php/closure? (get args 0))
             (get args 0)
             (php/make-closure (php/callable-in (get args 0) ctx)))
           (php/throw-error "Error"
                            (data/str-concat "Call to undefined method Closure::" name "()")))))]
    ;; the temporary list (rows 32 to 40): an owned counted value is
    ;; registered, a statement releases what it registered
    [php/tmp-mark (fn [] (data/count (cell/get php.rt/tmps)))]
    [php/tmp!
     (fn [v]
       (do (if (php/counted? v)
             (cell/set! php.rt/tmps (php/vconj (cell/get php.rt/tmps) v))
             :php/null)
           v))]
    [php/tmp-prefix
     (fn [ts i n acc] (if (< i n) (php/tmp-prefix ts (+ i 1) n (php/vconj acc (get ts i))) acc))]
    [php/release-tmps
     (fn [mark]
       (let [ts (cell/get php.rt/tmps)
             n (data/count ts)]
         (if (> n mark)
           (do (cell/set! php.rt/tmps (php/tmp-prefix ts 0 mark []))
               (php/release-tmp-range ts n mark)
               (php/flush-pending!))
           :php/null)))]
    [php/release-tmp-range
     (fn [ts i mark]
       (if (> i mark)
         (do (php/delref (get ts (- i 1))) (php/release-tmp-range ts (- i 1) mark))
         :php/null))]
    [php/make-closure
     ;; a Closure value (an object, `kind` :obj) around the function cell
     (fn [f] (assoc (assoc {} :php/type :closure) :php/closure f))]
    [php/callable-of (fn [v] (php/callable-in v :php/null))]
    [php/callable-fn (fn [h] (php/callable-of h))]
    [php/callable-in
     ;; the function cell of a callable value seen from class scope `ctx`: a
     ;; function name, `"Class::method"`, `[object-or-class, "method"]`, a
     ;; Closure or an object with `__invoke`
     (fn [v ctx]
       (let [k (php/kind v)]
         (if (= k :string)
           (let [i (php/dcolon-at v 0 (data/str-length v))]
             (if (< i 0)
               (php/resolve-fn (php/lc v) v)
               (php/callable-method (php/class-by-name (data/substring v 0 i))
                                    nil (data/substring v (+ i 2) (data/str-length v)) ctx)))
           (if (= k :array)
             (if (= (php/array-count v) 2)
               (let [t (php/array-get v 0)
                     m (php/array-get v 1)]
                 (if (= (php/kind t) :obj)
                   (php/callable-method (php/obj-class t) t m ctx)
                   (if (= (php/kind t) :string)
                     (php/callable-method (php/class-by-name t) nil m ctx)
                     (php/throw-error "Error" "Array callback must have exactly two elements"))))
               (php/throw-error "Error" "Array callback must have exactly two elements"))
             (if (= (get v :php/type) :closure)
               (get v :php/closure)
               (if (nil? (get v :php/obj))
                 (php/throw-error "Error" "Value not callable")
                 (let [m (php/class-method (php/obj-class v) "__invoke")]
                   (if (nil? m)
                     (php/throw-error "Error" "Object of type is not callable")
                     (php/bind-method m v (get (php/obj-state v) :class))))))))))]
    [php/dcolon-at
     (fn [s i n]
       (if (< (+ i 1) n)
         (if (= (data/substring s i (+ i 2)) "::") i (php/dcolon-at s (+ i 1) n))
         -1))]
    [php/callable-method
     ;; the bound method `name` of class `c` for object `this` (nil for a static call)
     (fn [c this name ctx]
       (let [m (php/class-method c (php/lc name))]
         (if (nil? m)
           (php/throw-error "Error" (data/str-concat "Call to undefined method " (get c :name) "::" name "()"))
           (if (php/visible? (get m :vis) (get m :declaring) ctx)
             (if (= (get m :static?) true)
               (php/bind-method m nil (if (nil? this) (get c :name) (get (php/obj-state this) :class)))
               (if (nil? this)
                 (php/throw-error "Error" (data/str-concat "Non-static method " (get m :declaring) "::"
                                                           (get m :name) "() cannot be called statically"))
                 (php/bind-method m this (get (php/obj-state this) :class))))
             (php/method-denied (get c :name) m ctx)))))]
    [php/call-value (fn [v args] (php/call (php/callable-of v) args))]
    [php/args-spread
     ;; `f(...$xs)`: the values of `xs` appended to the argument vector; a
     ;; string key would be a named argument, which is a known gap (C25)
     (fn [acc xs]
       (if (= (php/kind xs) :array)
         (php/spread-args acc xs 0 (php/array-count xs))
         (php/throw-error "Error" "Only arrays and Traversables can be unpacked")))]
    [php/spread-args
     (fn [acc xs i n]
       (if (< i n)
         (let [k (get (php/array-order xs) i)]
           (if (php/int? k)
             (php/spread-args (php/vconj acc (php/array-get xs k)) xs (+ i 1) n)
             (php/throw-error
               "Error"
               "spread with string keys is not supported yet (yang.php known gap G-spread-string-keys)")))
         acc))]
    [php/name-index
     (fn [names name i n]
       (if (< i n)
         (if (= (get names i) name) i (php/name-index names name (+ i 1) n))
         nil))]
    [php/pad-args
     (fn [args n]
       (if (< (data/count args) n) (php/pad-args (php/vconj args :php/undef) n) args))]
    [php/call-named
     ;; `f(pos..., name: v ...)`: named values land at their parameter's
     ;; position, holes stay :php/undef; unknown names go to a variadic
     ;; parameter's string keys, else they are an Error
     (fn [f pos named]
       (let [spec (get (cell/get f) :spec)]
         (if (nil? (get spec :names))
           (php/throw-error "Error" "Named arguments are not supported for this function")
           ((get (cell/get f) :yang.call/entry)
            (assoc (assoc (assoc (assoc {} :yang.call/callee f)
                                 :yang.call/positional pos)
                          :yang.call/named named)
                   :yang.call/raise (fn [condition] (php/raise condition)))))))]
    [php/place-named
     (fn [f spec pos named i n extras]
       (if (< i n)
         (let [nm (get (get named i) 0)
               v (get (get named i) 1)
               names (get spec :names)
               idx (php/name-index names nm 0 (data/count names))]
           (if (nil? idx)
             (if (get spec :variadic)
               (php/place-named f spec pos named (+ i 1) n (php/array-put extras nm v))
               (php/throw-error "Error" (data/str-concat "Unknown named parameter $" nm)))
             (if (if (< idx (data/count pos)) (not (= (get pos idx) :php/undef)) false)
               (php/throw-error "Error"
                                (data/str-concat "Named parameter $" nm
                                                 " overwrites previous argument"))
               (php/place-named f spec (assoc (php/pad-args pos (+ idx 1)) idx v) named (+ i 1) n
                                extras))))
         (if (> (php/array-count extras) 0)
           (php/vconj (php/pad-args pos (data/count (get spec :names)))
                      (assoc (assoc {} :php/type :extra) :php/pairs extras))
           pos)))]
    [php/gather-variadic
     (fn [args i]
       (let [n (data/count args)
             last (if (> n 0) (get args (- n 1)) :php/null)
             extra (= (get last :php/type) :extra)
             m (if extra (- n 1) n)]
         (php/gather-loop args i m
                          (if extra (get last :php/pairs) (php/empty-array))
                          extra)))]
    [php/gather-loop
     (fn [args i m extras extra?]
       (php/gather-fill args i m (php/empty-array) extras extra? 0 (php/array-count extras)))]
    [php/gather-fill
     (fn [args i m acc extras extra? j nx]
       (if (< i m)
         (php/gather-fill args (+ i 1) m (php/array-append acc (get args i)) extras extra? j nx)
         (if (< j nx)
           (let [k (get (php/array-order extras) j)]
             (php/gather-fill args i m (php/array-put acc k (php/array-get extras k))
                              extras extra? (+ j 1) nx))
           acc)))]
    [php/not-passed
     (fn [written idx pname passed required exact]
       (if (< (- idx 1) passed)
         (php/throw-error "ArgumentCountError"
                          (data/str-concat written "(): Argument #" (integer/format idx) " ($"
                                           pname ") not passed"))
         (php/too-few written passed required exact)))]
    [php/copy-capture
     (fn [v] (if (= v :php/undef) v (php/addref v)))]
    [php/local-capture
     ;; an arrow function's by-value capture: an unset variable stays unset
     (fn [c] (let [v (cell/get c)] (if (= v :php/undef) v (php/deref v))))]
    [php/global-capture
     (fn [name] (let [v (php/global-slot name)] (if (nil? v) :php/undef (php/deref v))))]
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

    ;; ---------------------------------------------------------------- foreach
    [php/foreach-array
     ;; the owned temporary of a by-value foreach; a non-array warns and is empty
     (fn [v]
       (if (php/array? v)
         v
         (do (php/warn 2 (data/str-concat
                           "foreach() argument must be of type array|object, "
                           (php/type-name (php/kind v)) " given"))
             (php/empty-array))))]

    ;; ---------------------------------------------------------------- array builtins
    ;; A by-reference builtin answers [ret outs]: `outs` holds the new value of
    ;; each by-reference position in ascending order; the call site writes them back.
    [php/arg-array
     (fn [v name i pname]
       (if (php/array? v)
         v
         (php/throw-error
           "TypeError"
           (data/str-concat name "(): Argument #" (integer/format i) " ($" pname
                            ") must be of type array, " (php/type-name (php/kind v)) " given"))))]
    [php/arity-between
     (fn [name args lo hi want]
       (if (< (data/count args) lo)
         (php/arity-error name want args)
         (if (> (data/count args) hi) (php/arity-error name want args) :php/null)))]
    [php/vec->array
     (fn [xs i n acc]
       (if (< i n)
         (php/vec->array xs (+ i 1) n (php/array-append acc (get xs i)))
         acc))]
    [php/count-deep
     (fn [arr i n acc]
       (if (< i n)
         (let [x (php/array-get arr (get (php/array-order arr) i))]
           (php/count-deep arr (+ i 1) n
                           (if (php/array? x)
                             (integer/add acc (php/count-deep x 0 (php/array-count x) (php/array-count x)))
                             acc)))
         acc))]
    [php/bi-count
     (fn [args]
       (do (php/arity-between "count" args 1 2 "at least 1")
           (let [v (get args 0)]
             (if (php/array? v)
               (if (> (data/count args) 1)
                 (if (= (php/to-int (get args 1)) 1)
                   (php/count-deep v 0 (php/array-count v) (php/array-count v))
                   (php/array-count v))
                 (php/array-count v))
               (php/throw-error
                 "TypeError"
                 (data/str-concat
                   "count(): Argument #1 ($value) must be of type Countable|array, "
                   (php/type-name (php/kind v)) " given"))))))]
    [php/keys-search
     (fn [arr search strict i n acc]
       (if (< i n)
         (let [k (get (php/array-order arr) i)
               v (php/array-get arr k)]
           (php/keys-search arr search strict (+ i 1) n
                            (if (if strict (php/identical v search) (php/loose-eq v search))
                              (php/array-append acc k)
                              acc)))
         acc))]
    [php/bi-array-keys
     (fn [args]
       (do (php/arity-between "array_keys" args 1 3 "at least 1")
           (let [arr (php/arg-array (get args 0) "array_keys" 1 "array")]
             (if (> (data/count args) 1)
               (php/keys-search arr (get args 1)
                                (if (> (data/count args) 2) (php/to-bool (get args 2)) false)
                                0 (php/array-count arr) (php/empty-array))
               (php/vec->array (php/array-order arr) 0 (php/array-count arr) (php/empty-array))))))]
    [php/implode-loop
     (fn [sep arr i n acc]
       (if (< i n)
         (php/implode-loop sep arr (+ i 1) n
                           (data/str-concat acc (if (= i 0) "" sep)
                                            (php/to-string (php/array-get arr (get (php/array-order arr) i)))))
         acc))]
    [php/bi-implode
     (fn [args]
       (do (php/arity-between "implode" args 1 2 "at least 1")
           (if (= (data/count args) 1)
             (let [arr (php/arg-array (get args 0) "implode" 1 "array")]
               (php/implode-loop "" arr 0 (php/array-count arr) ""))
             (let [arr (php/arg-array (get args 1) "implode" 2 "array")]
               (php/implode-loop (php/to-string (get args 0)) arr 0 (php/array-count arr) "")))))]
    [php/sum-loop
     (fn [arr i n acc]
       (if (< i n)
         (php/sum-loop arr (+ i 1) n
                       (php/add acc (php/array-get arr (get (php/array-order arr) i))))
         acc))]
    [php/bi-array-sum
     (fn [args]
       (do (php/arity-between "array_sum" args 1 1 "exactly 1")
           (let [arr (php/arg-array (get args 0) "array_sum" 1 "array")]
             (php/sum-loop arr 0 (php/array-count arr) 0))))]
    [php/bi-array-values
     (fn [args]
       (do (php/arity-between "array_values" args 1 1 "exactly 1")
           (let [arr (php/arg-array (get args 0) "array_values" 1 "array")]
             (php/vec->array (php/array-values arr) 0 (php/array-count arr) (php/empty-array)))))]
    [php/merge-loop
     (fn [args i n acc]
       (if (< i n)
         (let [v (get args i)]
           (if (php/array? v)
             (php/merge-loop args (+ i 1) n (php/spread-loop acc v 0 (php/array-count v)))
             (php/throw-error
               "TypeError"
               (data/str-concat "array_merge(): Argument #" (integer/format (+ i 1))
                                " must be of type array, " (php/type-name (php/kind v)) " given"))))
         acc))]
    [php/bi-array-merge
     (fn [args] (php/merge-loop args 0 (data/count args) (php/empty-array)))]
    [php/bi-array-key-exists
     (fn [args]
       (do (php/arity-between "array_key_exists" args 2 2 "exactly 2")
           (let [arr (php/arg-array (get args 1) "array_key_exists" 2 "array")]
             (php/array-has? arr (php/array-key (get args 0))))))]
    [php/in-loop
     (fn [arr needle strict i n]
       (if (< i n)
         (let [v (php/array-get arr (get (php/array-order arr) i))]
           (if (if strict (php/identical v needle) (php/loose-eq v needle))
             true
             (php/in-loop arr needle strict (+ i 1) n)))
         false))]
    [php/bi-in-array
     (fn [args]
       (do (php/arity-between "in_array" args 2 3 "at least 2")
           (let [arr (php/arg-array (get args 1) "in_array" 2 "haystack")]
             (php/in-loop arr (get args 0)
                          (if (> (data/count args) 2) (php/to-bool (get args 2)) false)
                          0 (php/array-count arr)))))]
    [php/bi-is-array
     (fn [args]
       (do (php/arity-between "is_array" args 1 1 "exactly 1")
           (php/array? (get args 0))))]
    [php/bi-array-key-first
     (fn [args]
       (do (php/arity-between "array_key_first" args 1 1 "exactly 1")
           (let [arr (php/arg-array (get args 0) "array_key_first" 1 "array")]
             (if (= (php/array-count arr) 0) :php/null (get (php/array-order arr) 0)))))]
    [php/bi-array-key-last
     (fn [args]
       (do (php/arity-between "array_key_last" args 1 1 "exactly 1")
           (let [arr (php/arg-array (get args 0) "array_key_last" 1 "array")
                 n (php/array-count arr)]
             (if (= n 0) :php/null (get (php/array-order arr) (- n 1))))))]
    [php/out1 (fn [ret arr] (php/pair ret (php/vconj [] arr)))]
    [php/push-loop
     (fn [arr args i n]
       (if (< i n)
         (php/push-loop (php/array-append arr (get args i)) args (+ i 1) n)
         arr))]
    [php/bi-array-push
     (fn [args]
       (do (php/arity-between "array_push" args 1 1000000 "at least 1")
           (let [arr (php/push-loop (php/arg-array (get args 0) "array_push" 1 "array")
                                    args 1 (data/count args))]
             (php/out1 (php/array-count arr) arr))))]
    [php/bi-array-pop
     (fn [args]
       (do (php/arity-between "array_pop" args 1 1 "exactly 1")
           (let [arr (php/arg-array (get args 0) "array_pop" 1 "array")
                 n (php/array-count arr)]
             (if (= n 0)
               (php/out1 :php/null arr)
               (let [k (get (php/array-order arr) (- n 1))
                     v (php/array-get arr k)
                     rest (php/array-unset arr k)]
                 (php/out1 v
                           (if (php/int? k)
                             (if (= (integer/compare (php/succ-key k) (get arr :next-index)) 0)
                               (assoc rest :next-index k)
                               rest)
                             rest)))))))]
    [php/bi-array-shift
     (fn [args]
       (do (php/arity-between "array_shift" args 1 1 "exactly 1")
           (let [arr (php/arg-array (get args 0) "array_shift" 1 "array")
                 n (php/array-count arr)]
             (if (= n 0)
               (php/out1 :php/null arr)
               (php/out1 (php/array-get arr (get (php/array-order arr) 0))
                         (php/spread-loop (php/empty-array) arr 1 n))))))]
    [php/bi-array-unshift
     (fn [args]
       (do (php/arity-between "array_unshift" args 1 1000000 "at least 1")
           (let [arr (php/arg-array (get args 0) "array_unshift" 1 "array")
                 head (php/push-loop (php/empty-array) args 1 (data/count args))
                 res (php/spread-loop head arr 0 (php/array-count arr))]
             (php/out1 (php/array-count res) res))))]

    ;; ---------------------------------------------------------------- sorting
    ;; One stable top-down merge sort over vectors of [key value] pairs; the
    ;; merge is tail-recursive. `cmp` answers a negative, zero or positive int.
    [php/pairs
     (fn [arr i n acc]
       (if (< i n)
         (let [k (get (php/array-order arr) i)]
           (php/pairs arr (+ i 1) n (php/vconj acc (php/triple k (php/array-get arr k) (php/slot-get arr k)))))
         acc))]
    [php/take-rest
     (fn [acc xs i n]
       (if (< i n) (php/take-rest (php/vconj acc (get xs i)) xs (+ i 1) n) acc))]
    [php/merge
     (fn [l r i j nl nr cmp acc]
       (if (>= i nl)
         (php/take-rest acc r j nr)
         (if (>= j nr)
           (php/take-rest acc l i nl)
           (if (<= (cmp (get l i) (get r j)) 0)
             (php/merge l r (+ i 1) j nl nr cmp (php/vconj acc (get l i)))
             (php/merge l r i (+ j 1) nl nr cmp (php/vconj acc (get r j)))))))]
    [php/msort
     (fn [xs cmp]
       (let [n (data/count xs)]
         (if (< n 2)
           xs
           (let [mid (get (integer/quot-rem n 2) 0)
                 l (php/msort (data/subvec xs 0 mid) cmp)
                 r (php/msort (data/subvec xs mid n) cmp)]
             (php/merge l r 0 0 (data/count l) (data/count r) cmp [])))))]
    [php/flag-cmp
     (fn [flag]
       (if (= flag 0)
         (fn [a b] (php/cmp a b))
         (if (= flag 1)
           (fn [a b] (php/cmp (php/to-float a) (php/to-float b)))
           (if (= flag 2)
             (fn [a b] (php/cmp-h (data/str-compare (php/to-string a) (php/to-string b)) 0))
             (php/throw-error
               "Error"
               (data/str-concat "sort flag " (integer/format flag)
                                " is not supported yet (yang.php known gap G-sort-flags)"))))))]
    [php/field-cmp
     ;; compare pairs on field `by` (0 key, 1 value), sign flips for r* variants
     (fn [by sign base]
       (fn [a b] (* sign (base (get a by) (get b by)))))]
    [php/user-cmp
     (fn [f]
       (let [warned (cell/new false)]
         (fn [a b]
           (let [r (php/call f (php/pair a b))]
             (if (= (php/kind r) :bool)
               (do (if (cell/get warned)
                     :php/null
                     (do (cell/set! warned true)
                         (php/warn 8192 "usort(): Returning bool from comparison function is deprecated, return an integer less than, equal to, or greater than zero")))
                   (if r
                     1
                     (if (> (php/to-int (php/call f (php/pair b a))) 0) -1 0)))
               (php/to-int r))))))]
    [php/rebuild
     (fn [sorted i n acc keep]
       (if (< i n)
         (let [it (get sorted i)]
           (php/rebuild sorted (+ i 1) n
                        (if keep
                          (php/array-set acc (get it 0) (get it 2))
                          (php/array-append acc (get it 2)))
                        keep))
         acc))]
    [php/sort-finish
     (fn [arr sorted keep]
       (let [out (php/rebuild sorted 0 (data/count sorted) (php/empty-array) keep)
             nx (get (php/arr-table arr) :next-index)]
         (php/out1 true
                   (if keep (if (nil? nx) out (php/with-next-index out nx)) out))))]
    [php/with-next-index
     (fn [out nx]
       (if (= (php/slot-kind out) :arr-handle)
         (let [c (get out :php/arr)
               st (cell/get c)]
           (do (cell/set! c (assoc st :value (assoc (get st :value) :next-index nx))) out))
         (assoc out :next-index nx)))]
    [php/triple (fn [a b c] (php/vconj (php/vconj (php/vconj [] a) b) c))]
    [php/do-sort
     (fn [name args by keep sign]
       (do (php/arity-between name args 1 2 "at least 1")
           (let [arr (php/arg-array (get args 0) name 1 "array")
                 flag (if (> (data/count args) 1) (php/to-int (get args 1)) 0)
                 cmp (php/field-cmp by sign (php/flag-cmp flag))]
             (php/sort-finish arr
                              (php/msort (php/pairs arr 0 (php/array-count arr) []) cmp)
                              keep))))]
    [php/do-usort
     (fn [name args by keep]
       (do (php/arity-between name args 2 2 "exactly 2")
           (let [arr (php/arg-array (get args 0) name 1 "array")
                 cmp (php/field-cmp by 1 (php/user-cmp (php/callable-fn (get args 1))))]
             (php/sort-finish arr
                              (php/msort (php/pairs arr 0 (php/array-count arr) []) cmp)
                              keep))))]
    [php/bi-sort (fn [args] (php/do-sort "sort" args 1 false 1))]
    [php/bi-rsort (fn [args] (php/do-sort "rsort" args 1 false -1))]
    [php/bi-asort (fn [args] (php/do-sort "asort" args 1 true 1))]
    [php/bi-arsort (fn [args] (php/do-sort "arsort" args 1 true -1))]
    [php/bi-ksort (fn [args] (php/do-sort "ksort" args 0 true 1))]
    [php/bi-krsort (fn [args] (php/do-sort "krsort" args 0 true -1))]
    [php/bi-usort (fn [args] (php/do-usort "usort" args 1 false))]
    [php/bi-uasort (fn [args] (php/do-usort "uasort" args 1 true))]
    [php/bi-uksort (fn [args] (php/do-usort "uksort" args 0 true))]

    ;; ---------------------------------------------------------------- unit
    [php/bi-set-exception-handler
     (fn [args]
       (let [old (cell/get php.rt/exception-handler)]
         (do (cell/set! php.rt/exception-handlers
                        (php/vconj (cell/get php.rt/exception-handlers) old))
             (cell/set! php.rt/exception-handler (get args 0))
             old)))]
    [php/bi-restore-exception-handler
     ;; back to the handler in force before the last `set_exception_handler`
     (fn [args]
       (let [stack (cell/get php.rt/exception-handlers)
             n (data/count stack)]
         (do (if (= n 0)
               (cell/set! php.rt/exception-handler :php/null)
               (do (cell/set! php.rt/exception-handler (get stack (- n 1)))
                   (cell/set! php.rt/exception-handlers (php/args-prefix stack (- n 1) 0 []))))
             true)))]
    [php/handle-uncaught
     ;; the user exception handler takes an uncaught throwable; the script then ends
     (fn [exc]
       (let [h (cell/get php.rt/exception-handler)]
         (if (= exc :php/null)
           exc
           (if (= h :php/null)
             exc
             (do (cell/set! php.rt/exception-handler :php/null)
                 (php/try (fn [] (do (php/call-value h (php/vconj [] exc)) :php/null))
                          (fn [e] e)
                          (fn [] :php/null)))))))]
    [php/run-main
     ;; the unit root: output, diagnostics and the uncaught error, if any
     (fn [body]
       (do (cell/set! php.rt/out [[]])
           (cell/set! php.rt/ctx (php/ctx nil 0 0 nil))
           (let [exc0 (php/try (fn [] (do (body) :php/null))
                               (fn [e] e)
                               (fn [] :php/null))
                 exc1 (php/handle-uncaught exc0)
                 exc2 (php/try (fn [] (do (php/shutdown!) :php/null))
                               (fn [e] e)
                               (fn [] :php/null))
                 exc (if (= exc1 :php/null) exc2 exc1)]
             (assoc (assoc (assoc {} :php/out
                                  (data/str-join "" (get (cell/get php.rt/out) 0)))
                           :php/diag (cell/get php.rt/diag))
                    :php/uncaught
                    (if (= exc :php/null) nil (php/snapshot-exc exc))))))]])


(def ^:private builtins
  "The functions `php/init!` registers: PHP name to prelude definition,
   derived from `builtins/supported`, the one table."
  (mapv (fn [[nm {:keys [prelude]}]] [nm prelude]) (sort-by key builtins/supported)))


(def builtin-names
  "The lower-cased names of the functions `php/init!` registers."
  (set (map first builtins)))


(def ^:private seed-classes
  "The builtin classes and interfaces `php/init!` seeds: [name kind parent
   interfaces final?], parents before children (P4 owns the rest of the
   builtin descriptor skeletons)."
  [["Traversable" :interface nil []] ["Iterator" :interface nil ["Traversable"]]
   ["IteratorAggregate" :interface nil ["Traversable"]] ["ArrayAccess" :interface nil []]
   ["Countable" :interface nil []] ["Stringable" :interface nil []]
   ["JsonSerializable" :interface nil []] ["UnitEnum" :interface nil []]
   ["BackedEnum" :interface nil ["UnitEnum"]] ["Throwable" :interface nil ["Stringable"]]
   ["stdClass" :class nil []] ["Closure" :class nil [] true]
   ["Generator" :class nil ["Iterator"] true]
   ["Exception" :class nil ["Throwable"]] ["Error" :class nil ["Throwable"]]
   ["ErrorException" :class "Exception" []] ["JsonException" :class "Exception" []]
   ["RuntimeException" :class "Exception" []] ["LogicException" :class "Exception" []]
   ["TypeError" :class "Error" []] ["ValueError" :class "Error" []]
   ["ArithmeticError" :class "Error" []] ["DivisionByZeroError" :class "ArithmeticError" []]
   ["ArgumentCountError" :class "TypeError" []] ["UnhandledMatchError" :class "Error" []]
   ["InvalidArgumentException" :class "LogicException" []]
   ["DomainException" :class "LogicException" []] ["LengthException" :class "LogicException" []]
   ["OutOfRangeException" :class "LogicException" []]
   ["BadFunctionCallException" :class "LogicException" []]
   ["BadMethodCallException" :class "BadFunctionCallException" []]
   ["OutOfBoundsException" :class "RuntimeException" []]
   ["RangeException" :class "RuntimeException" []]
   ["OverflowException" :class "RuntimeException" []]
   ["UnderflowException" :class "RuntimeException" []]
   ["UnexpectedValueException" :class "RuntimeException" []]])


(defn- throwable-members
  "The properties and native methods Exception and Error declare, which every
   subclass inherits."
  [nm]
  {:props-decl (mapv (fn [[pn vis default]]
                       {:name pn,
                        :vis vis,
                        :declaring nm,
                        :id (if (= vis :private) (str nm "::" pn) pn),
                        :default default})
                     [["message" :protected ""] ["code" :protected 0] ["file" :protected ""]
                      ["line" :protected 0] ["previous" :private :php/null]]),
   :methods (mapv (fn [[mn kind]]
                    {:name mn,
                     :lc (str/lower-case mn),
                     :vis :public,
                     :declaring nm,
                     :final? (not= kind :ctor),
                     :native kind})
                  [["__construct" :ctor] ["getMessage" :message] ["getCode" :code]
                   ["getPrevious" :previous] ["getFile" :file] ["getLine" :line]
                   ["getTrace" :trace] ["getTraceAsString" :trace-string]])})


(defn- error-exception-members
  "ErrorException: Exception's members, the `severity` property, its own
   constructor and `getSeverity`."
  []
  (let [m (throwable-members "Exception")
        replace-method (fn [methods e] (conj (vec (remove #(= (:lc %) (:lc e)) methods)) e))
        entry (fn [mn kind]
                {:name mn, :lc (str/lower-case mn), :vis :public, :declaring "ErrorException",
                 :final? (not= kind :ee-ctor), :native kind})]
    (assoc m
           :props-decl (conj (:props-decl m)
                             {:name "severity", :vis :protected, :declaring "ErrorException",
                              :id "severity", :default 1})
           :methods (-> (:methods m)
                        (replace-method (entry "__construct" :ee-ctor))
                        (replace-method (entry "getSeverity" :severity))))))


(def ^:private seed-table
  "Name to descriptor, with the lower-cased `:ancestors` the declaration code
   reads. The Throwable family carries its inherited members."
  (reduce (fn [table [nm kind parent ifaces final?]]
            (let [lc (str/lower-case nm)
                  anc (reduce (fn [acc x] (into acc (remove (set acc)) (:ancestors (get table x))))
                              [lc]
                              (concat (when parent [parent]) ifaces))
                  members (if (contains? #{"Exception" "Error"} nm)
                            (throwable-members nm)
                            (if (= nm "ErrorException")
                              (error-exception-members)
                              (select-keys (get table parent) [:props-decl :methods])))]
              (assoc table
                     nm (merge {:php/type :class,
                                :name nm,
                                :kind kind,
                                :parent (or parent :php/null),
                                :interfaces ifaces,
                                :traits [],
                                :ancestors anc,
                                :abstract? false,
                                :final? (boolean final?),
                                :constants [],
                                :props-decl [],
                                :static-props [],
                                :methods [],
                                :cases [],
                                :attrs []}
                               members))))
          {}
          seed-classes))


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
                   '(cell/new (php/empty-array)))
                 (define 'php.rt/functions '(cell/new {}))
                 (define 'php.rt/classes (list 'cell/new seed-table))
                 (define 'php.rt/class-index
                   (list 'cell/new
                         (into {} (map (fn [nm] [(str/lower-case nm) nm])) (keys seed-table))))
                 (define 'php.rt/next-ord '(cell/new 1))
                 (define 'php.rt/finalizable '(cell/new []))
                 (define 'php.rt/strict '(cell/new false))
                 (define 'php.rt/exception-handler '(cell/new :php/null))
                 (define 'php.rt/exception-handlers '(cell/new []))
                 (define 'php.rt/class-order '(cell/new []))
                 (define 'php.rt/shutdown-fns '(cell/new []))
                 (define 'php.rt/pending-exc '(cell/new :php/null))
                 (define 'php.rt/cmp-depth '(cell/new 0))
                 (define 'php.rt/tmps '(cell/new []))
                 (define 'php.rt/next-cell '(cell/new 1))
                 (define 'php.rt/error-handler '(cell/new :php/null))
                 ;; E_ALL
                 (define 'php.rt/error-reporting '(cell/new 32767))
                 (define 'php.rt/diag '(cell/new []))
                 (define 'php.rt/included '(cell/new {}))
                 (define 'php.rt/unit '(cell/new :php/null))
                 (define 'php.rt/path-map '(cell/new {}))
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
  (into (into core-definitions module-defs/definitions)
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
  '[php.rt/class-index php.rt/classes php.rt/cmp-depth php.rt/ctx php.rt/diag php.rt/error-handler
    php.rt/class-order php.rt/exception-handler php.rt/exception-handlers php.rt/finalizable php.rt/pending-exc
    php.rt/shutdown-fns php.rt/strict php.rt/included php.rt/path-map php.rt/unit
    php.rt/error-reporting php.rt/functions php.rt/globals php.rt/int-max
    php.rt/int-min php.rt/next-cell php.rt/next-ord
    php.rt/out php.rt/tmps])


(defn strip
  "`sym` without the module's own namespace `php`; `php.rt/*` stays
   qualified."
  [sym]
  (core/strip "php" sym))


(defn- binder-symbols
  "Every symbol a `fn` parameter list or `let` binding vector in `form`
   binds."
  [form]
  (cond
    (and (seq? form) (= 'fn (first form)))
    (into (set (second form)) (mapcat binder-symbols) (drop 2 form))
    (and (seq? form) (= 'let (first form)))
    (into (set (take-nth 2 (second form)))
          (mapcat binder-symbols)
          (concat (take-nth 2 (rest (second form))) (drop 2 form)))
    (coll? form) (into #{} (mapcat binder-symbols) (seq form))
    :else #{}))


(defn- unshadow
  "`form` with every local binder named like a module export renamed (`h` to
   `h%l`): the module emitter strips `php/h` to the bare `h`, which a local
   `h` would otherwise capture. Globals are qualified before stripping, so
   every bare occurrence is the local."
  [exports [k form]]
  (let [clash (set (filter exports (binder-symbols form)))]
    [k
     (if (empty? clash)
       form
       (walk/postwalk (fn [x] (if (contains? clash x) (symbol (str x "%l")) x)) form))]))


(def ^:private module-definitions
  (core/module-definitions
    "php" :php/uninit runtime-keys
    (mapv (partial unshadow (into #{} (map (fn [[k _]] (strip k))) definitions)) definitions)))


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
