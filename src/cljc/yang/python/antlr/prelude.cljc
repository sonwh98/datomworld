(ns yang.python.antlr.prelude
  "The Python runtime profile, written as Universal AST
   (docs/design/yang.antlr.md §8.1, §8.2, §9.3). Python semantics for
   operators, truthiness, equality, objects, exceptions and escapes live
   here as guest code, not in Clojure: the lowering only emits applications
   of these names.

   Value encoding:
     int             host integer (untagged)
     float           {:py/float x}: tagged on every host, because JS cannot
                     tell 2 from 2.0 (owner decision 3)
     bool            host true / false (numeric as 1 / 0)
     None            :py/None
     str             {:py/str \"...\"}
     list            a cell holding {:py/type :list :items [...]}
     dict            a cell holding {:py/type :dict :index {nkey slot}
                     :keys [...] :vals [...]}: insertion order is the keys
                     vector, never a host map's order; keys are normalized
                     so 1, 1.0 and True are one key
     function        a cell holding {:py/type :function :name s :nparams n
                     :defaults [...] :star? bool :code (fn [args] ...)};
                     identity is the cell ref
     class           a cell holding {:py/type :class :name s :base cls
                     :attrs {name value}}
     instance        a cell holding {:py/type :instance :class cls
                     :attrs {name value}}
     bound method    {:py/type :method :self obj :fn function}
     tuple           {:py/type :tuple :items [...]}
     range           {:py/type :range :start a :stop b :step s}
     module globals  a dict (owner decision 1): the module body receives it
                     as `%globals` and every function closes over it, which
                     is Python's own `__globals__`; a miss is NameError

   Calling convention: a function's `:code` takes one vector of arguments.
   `py/call` checks the count against `:nparams`, fills defaults and packs
   `*args`, so arity errors are Python TypeErrors, not host errors.

   Runtime state is two cells, reached through the store (`yin/def` is
   reserved for the prelude and builtins):
     py.rt/handlers  the exception-handler stack, a chain of
                     [continuation rest] pairs, nil when empty
     py.rt/out       the values `print` collected, one vector per call

   Escapes and handlers tell the first pass through a capture point from a
   re-entry with a flag cell allocated before the capture (`:first`, then
   `:re-entered`): heap writes survive continuation invocation, so the flag
   answers without inspecting the continuation's representation.

   Host names the prelude depends on and does not define:
     cell/new cell/get cell/set!                 yin.vm.module cell module
     data/count data/into data/subvec
     data/str-concat data/str-length             yin.vm.data"
  (:require
    [yang.python.antlr.uast :as u]))


(def host-names
  "Module exports the prelude calls."
  '#{cell/new cell/get cell/set! data/count data/into data/subvec
     data/str-concat data/str-length})


(def ^:private core-definitions
  "Pure definitions: lambdas only, so defining them runs nothing."
  '[;; ---------------------------------------------------------------- kinds
    [py/cell? (fn [x] (= (get x :type) :cell-ref))]
    [py/str? (fn [x] (not (nil? (get x :py/str))))]
    [py/str (fn [s] (assoc {} :py/str s))]
    [py/float? (fn [x] (not (nil? (get x :py/float))))]
    [py/float (fn [x] (assoc {} :py/float (* 1.0 x)))]
    [py/tuple (fn [items] (assoc (assoc {} :py/type :tuple) :items items))]
    [py/conj (fn [xs x] (conj xs x))]
    [py/arg (fn [args i] (get args i))]
    [py/numeric?
     (fn [x]
       (if (= x true)
         true
         (if (= x false)
           true
           (if (py/float? x)
             true
             (if (nil? (get x :py/str))
               (if (nil? (get x :type))
                 (if (nil? (get x :py/type))
                   (if (= x :py/None) false (not (= x :py/unbound)))
                   false)
                 false)
               false)))))]
    [py/num
     (fn [x]
       (if (= x true) 1 (if (= x false) 0 (if (py/float? x) (get x :py/float) x))))]
    [py/zero? (fn [x] (let [v (py/num x)] (if (= v 0) true (= v 0.0))))]
    [py/content-type (fn [x] (if (py/cell? x) (get (cell/get x) :py/type) nil))]
    [py/function? (fn [x] (= (py/content-type x) :function))]

    ;; ---------------------------------------------------------- escapes
    [py/raise
     (fn [e]
       (let [hs (cell/get py.rt/handlers)]
         (if (nil? hs) (:py/no-handler e) ((first hs) e))))]
    [py/try
     (fn [body handler orelse]
       (let [saved (cell/get py.rt/handlers)
             flag (cell/new :first)]
         ((fn [r]
            (if (= (cell/get flag) :first)
              (do (cell/set! flag :re-entered)
                  (cell/set! py.rt/handlers (py/conj (py/conj [] r) saved))
                  (body)
                  (cell/set! py.rt/handlers saved)
                  (orelse))
              (do (cell/set! py.rt/handlers saved)
                  (handler r))))
          (%capture))))]
    [py/call-ec
     (fn [f]
       (let [saved (cell/get py.rt/handlers)
             flag (cell/new :first)]
         ((fn [r]
            (if (= (cell/get flag) :first)
              (do (cell/set! flag :re-entered)
                  (f (fn [x] (do (cell/set! py.rt/handlers saved) (r x)))))
              r))
          (%capture))))]

    ;; ---------------------------------------------------------- exceptions
    [py/make-class
     (fn [name base]
       (cell/new (assoc (assoc (assoc (assoc {} :py/type :class) :name name)
                               :base base)
                        :attrs {})))]
    [py/make-instance
     (fn [cls]
       (cell/new (assoc (assoc (assoc {} :py/type :instance) :class cls)
                        :attrs {})))]
    [py/make-exc
     (fn [cls msg]
       (let [e (py/make-instance cls)]
         (do (py/setattr e "args" (py/tuple (py/conj [] msg))) e)))]
    [py/raise-new (fn [cls msg] (py/raise (py/make-exc cls msg)))]
    [py/type-error (fn [msg] (py/raise-new py.b/TypeError msg))]
    [py/as-exception
     (fn [x]
       (if (= (py/content-type x) :class)
         (py/call x [])
         (if (py/cell? x)
           x
           (py/type-error {:py/str "exceptions must derive from BaseException"}))))]
    [py/subclass?
     (fn [c target]
       (if (= c :py/None)
         false
         (if (= c target) true (py/subclass? (get (cell/get c) :base) target))))]
    [py/isinstance
     (fn [o cls]
       (if (= (py/content-type o) :instance)
         (py/subclass? (get (cell/get o) :class) cls)
         false))]

    ;; ---------------------------------------------------------- names
    [py/local-get
     (fn [c n]
       (let [x (cell/get c)]
         (if (= x :py/unbound) (py/raise-new py.b/UnboundLocalError n) x)))]
    [py/global-get
     (fn [g n]
       (let [c (cell/get g)
             slot (get (get c :index) n :py/missing)]
         (if (= slot :py/missing)
           (py/raise-new py.b/NameError
                         (py/str (data/str-concat "name '" (get n :py/str)
                                                  "' is not defined")))
           (get (get c :vals) slot))))]
    [py/global-set (fn [g n x] (do (py/dict-set g n x) :py/None))]
    [py/global-or
     ;; a builtin name: the module's binding when present, else the builtin
     (fn [g n builtin]
       (let [c (cell/get g)
             slot (get (get c :index) n :py/missing)]
         (if (= slot :py/missing) builtin (get (get c :vals) slot))))]
    [py/class-ns-get
     ;; a name assigned in a class body, read in that body: the class
     ;; namespace as it is now, else `fallback` (globals, then builtins)
     (fn [cls name fallback]
       (let [x (get (get (cell/get cls) :attrs) name :py/missing)]
         (if (= x :py/missing) (fallback) x)))]

    ;; ---------------------------------------------------------- functions
    [py/make-function
     (fn [name nparams defaults star code]
       (cell/new (assoc (assoc (assoc (assoc (assoc (assoc {} :py/type :function)
                                                    :name name)
                                             :nparams nparams)
                                      :defaults defaults)
                               :star? star)
                        :code code)))]
    [py/bind-args
     (fn [fc args]
       (let [n (get fc :nparams)
             given (data/count args)
             defaults (get fc :defaults)
             d (data/count defaults)]
         (if (< given (- n d))
           (py/arity-error fc)
           (if (if (< n given) (not (get fc :star?)) false)
             (py/arity-error fc)
             (let [filled (if (< given n)
                            (data/into args (data/subvec defaults (- d (- n given))))
                            args)]
               (if (get fc :star?)
                 (py/conj (data/subvec filled 0 n)
                          (py/tuple (data/subvec filled n)))
                 filled))))))]
    [py/arity-error
     (fn [fc]
       (py/type-error
         (py/str (data/str-concat (get fc :name)
                                  "() got the wrong number of positional arguments"))))]
    [py/call
     (fn [f args]
       (if (= (get f :py/type) :method)
         (py/call (get f :fn) (data/into (py/conj [] (get f :self)) args))
         (let [t (py/content-type f)]
           (if (= t :function)
             (let [fc (cell/get f)] ((get fc :code) (py/bind-args fc args)))
             (if (= t :class)
               (py/instantiate f args)
               (py/type-error {:py/str "object is not callable"}))))))]
    [py/instantiate
     (fn [cls args]
       (let [inst (py/make-instance cls)
             init (py/class-lookup cls "__init__")]
         (do (if (= init :py/missing)
               (py/setattr inst "args" (py/tuple args))
               (py/call init (data/into (py/conj [] inst) args)))
             inst)))]

    ;; ---------------------------------------------------------- attributes
    [py/class-lookup
     (fn [cls name]
       (if (= cls :py/None)
         :py/missing
         (let [c (cell/get cls)
               found (get (get c :attrs) name :py/missing)]
           (if (= found :py/missing)
             (py/class-lookup (get c :base) name)
             found))))]
    [py/method
     (fn [o f] (assoc (assoc (assoc {} :py/type :method) :self o) :fn f))]
    [py/bind (fn [o m] (if (py/function? m) (py/method o m) m))]
    [py/attr-error (fn [name] (py/raise-new py.b/AttributeError (py/str name)))]
    [py/getattr
     (fn [o name]
       (if (py/cell? o)
         (let [c (cell/get o)
               t (get c :py/type)]
           (if (= t :instance)
             (let [own (get (get c :attrs) name :py/missing)]
               (if (= own :py/missing)
                 (let [m (py/class-lookup (get c :class) name)]
                   (if (= m :py/missing) (py/attr-error name) (py/bind o m)))
                 own))
             (if (= t :class)
               (let [m (py/class-lookup o name)]
                 (if (= m :py/missing) (py/attr-error name) m))
               (if (= t :list)
                 (if (= name "append")
                   (py/method o py.b/list-append)
                   (py/attr-error name))
                 (if (= t :function)
                   (if (= name "__name__")
                     (py/str (get c :name))
                     (py/attr-error name))
                   (py/attr-error name))))))
         (py/attr-error name)))]
    [py/setattr
     (fn [o name x]
       (if (py/cell? o)
         (let [c (cell/get o)
               t (get c :py/type)]
           (if (if (= t :instance) true (= t :class))
             (do (cell/set! o (assoc c :attrs (assoc (get c :attrs) name x)))
                 :py/None)
             (py/attr-error name)))
         (py/attr-error name)))]

    ;; ---------------------------------------------------------- numbers
    [py/arith
     (fn [op a b]
       (if (py/numeric? a)
         (if (py/numeric? b)
           (let [r (op (py/num a) (py/num b))]
             (if (if (py/float? a) true (py/float? b)) (py/float r) r))
           (py/type-error {:py/str "unsupported operand type"}))
         (py/type-error {:py/str "unsupported operand type"})))]
    [py/compare
     (fn [op a b]
       (if (py/numeric? a)
         (if (py/numeric? b)
           (op (py/num a) (py/num b))
           (py/type-error {:py/str "comparison not supported"}))
         (py/type-error {:py/str "comparison not supported"})))]
    [py/add
     (fn [a b]
       (if (py/str? a)
         (if (py/str? b)
           (py/str (data/str-concat (get a :py/str) (get b :py/str)))
           (py/type-error {:py/str "can only concatenate str to str"}))
         (py/arith + a b)))]
    [py/sub (fn [a b] (py/arith - a b))]
    [py/mul (fn [a b] (py/arith * a b))]
    [py/truediv
     (fn [a b]
       (if (py/numeric? a)
         (if (py/numeric? b)
           (if (py/zero? b)
             (py/raise-new py.b/ZeroDivisionError {:py/str "division by zero"})
             (py/float (/ (* 1.0 (py/num a)) (py/num b))))
           (py/type-error {:py/str "unsupported operand type"}))
         (py/type-error {:py/str "unsupported operand type"})))]
    [py/neg (fn [a] (py/arith - 0 a))]
    [py/pos (fn [a] (py/arith + 0 a))]
    [py/lt (fn [a b] (py/compare < a b))]
    [py/gt (fn [a b] (py/compare > a b))]
    [py/le (fn [a b] (py/compare <= a b))]
    [py/ge (fn [a b] (py/compare >= a b))]

    ;; ---------------------------------------------------------- equality
    [py/eq
     (fn [a b]
       (if (py/numeric? a)
         (if (py/numeric? b)
           (if (<= (py/num a) (py/num b)) (>= (py/num a) (py/num b)) false)
           false)
         (if (py/cell? a)
           (if (py/cell? b) (py/eq-objects a b) false)
           (= a b))))]
    [py/eq-objects
     (fn [a b]
       (if (= a b)
         true
         (let [ca (cell/get a)
               cb (cell/get b)]
           (if (= (get ca :py/type) :list)
             (if (= (get cb :py/type) :list)
               (py/eq-items (get ca :items) (get cb :items) 0)
               false)
             false))))]
    [py/eq-items
     (fn [xs ys i]
       (let [x (get xs i :py/stop)
             y (get ys i :py/stop)]
         (if (= x :py/stop)
           (= y :py/stop)
           (if (= y :py/stop)
             false
             (if (py/eq x y) (py/eq-items xs ys (+ i 1)) false)))))]
    [py/ne (fn [a b] (not (py/eq a b)))]
    [py/is (fn [a b] (= a b))]
    [py/is-not (fn [a b] (not (= a b)))]

    ;; ---------------------------------------------------------- truthiness
    [py/truthy
     (fn [x]
       (if (= x false)
         false
         (if (= x true)
           true
           (if (= x :py/None)
             false
             (if (py/str? x)
               (not (= x {:py/str ""}))
               (if (py/cell? x)
                 (py/obj-truthy (cell/get x))
                 (if (py/numeric? x) (not (py/zero? x)) true)))))))]
    [py/obj-truthy
     (fn [c]
       (let [t (get c :py/type)]
         (if (= t :list)
           (< 0 (data/count (get c :items)))
           (if (= t :dict) (< 0 (data/count (get c :keys))) true))))]
    [py/not (fn [x] (if (py/truthy x) false true))]

    ;; ---------------------------------------------------------- lists
    [py/list
     (fn [items]
       (cell/new (assoc (assoc {} :py/type :list) :items items)))]
    [py/list-append
     (fn [l x]
       (let [c (cell/get l)]
         (do (cell/set! l (assoc c :items (conj (get c :items) x)))
             :py/None)))]
    [py/index
     (fn [k n]
       (if (if (py/numeric? k) (not (py/float? k)) false)
         (let [i (py/num k)
               j (if (< i 0) (+ i n) i)]
           (if (< j 0)
             (py/raise-new py.b/IndexError {:py/str "list index out of range"})
             (if (< j n)
               j
               (py/raise-new py.b/IndexError
                             {:py/str "list index out of range"}))))
         (py/type-error {:py/str "list indices must be integers"})))]

    ;; ---------------------------------------------------------- dicts
    [py/key (fn [k] (if (py/numeric? k) (* 1.0 (py/num k)) k))]
    [py/dict-new (fn [] (cell/new {:py/type :dict, :index {}, :keys [], :vals []}))]
    [py/dict-set
     (fn [d k x]
       (let [c (cell/get d)
             nk (py/key k)
             slot (get (get c :index) nk :py/missing)]
         (if (= slot :py/missing)
           (cell/set! d
                      (assoc (assoc (assoc c
                                           :index
                                           (assoc (get c :index)
                                                  nk
                                                  (data/count (get c :keys))))
                                    :keys
                                    (conj (get c :keys) k))
                             :vals
                             (conj (get c :vals) x)))
           (cell/set! d (assoc c :vals (assoc (get c :vals) slot x))))))]
    [py/dict-fill
     (fn [d pairs i]
       (let [pair (get pairs i :py/stop)]
         (if (= pair :py/stop)
           d
           (do (py/dict-set d (get pair 0) (get pair 1))
               (py/dict-fill d pairs (+ i 1))))))]
    [py/dict-from (fn [pairs] (py/dict-fill (py/dict-new) pairs 0))]

    ;; ---------------------------------------------------------- subscripts
    [py/getitem
     (fn [o k]
       (if (py/cell? o)
         (let [c (cell/get o)
               t (get c :py/type)]
           (if (= t :list)
             (get (get c :items) (py/index k (data/count (get c :items))))
             (if (= t :dict)
               (let [slot (get (get c :index) (py/key k) :py/missing)]
                 (if (= slot :py/missing)
                   (py/raise-new py.b/KeyError k)
                   (get (get c :vals) slot)))
               (py/type-error {:py/str "object is not subscriptable"}))))
         (py/type-error {:py/str "object is not subscriptable"})))]
    [py/setitem
     (fn [o k x]
       (if (py/cell? o)
         (let [c (cell/get o)
               t (get c :py/type)]
           (if (= t :list)
             (do (cell/set! o
                            (assoc c
                                   :items
                                   (assoc (get c :items)
                                          (py/index k (data/count (get c :items)))
                                          x)))
                 :py/None)
             (if (= t :dict)
               (do (py/dict-set o k x) :py/None)
               (py/type-error {:py/str "object does not support item assignment"}))))
         (py/type-error {:py/str "object does not support item assignment"})))]

    ;; ---------------------------------------------------------- iteration
    [py/range3
     (fn [a b s]
       (if (if (py/float? a) true (if (py/float? b) true (py/float? s)))
         (py/type-error {:py/str "range() arguments must be integers"})
         (if (py/zero? s)
           (py/raise-new py.b/ValueError {:py/str "range() arg 3 must not be zero"})
           (assoc (assoc (assoc (assoc {} :py/type :range) :start a) :stop b)
                  :step s))))]
    [py/range-at
     (fn [r i]
       (let [x (+ (get r :start) (* i (get r :step)))]
         (if (if (< 0 (get r :step)) (< x (get r :stop)) (> x (get r :stop)))
           x
           :py/stop)))]
    [py/range-len
     (fn [r i] (if (= (py/range-at r i) :py/stop) i (py/range-len r (+ i 1))))]
    [py/iter-at
     (fn [it i]
       (if (py/cell? it)
         (let [c (cell/get it)
               t (get c :py/type)]
           (if (= t :list)
             (get (get c :items) i :py/stop)
             (if (= t :dict)
               (get (get c :keys) i :py/stop)
               (py/type-error {:py/str "object is not iterable"}))))
         (if (= (get it :py/type) :range)
           (py/range-at it i)
           (if (= (get it :py/type) :tuple)
             (get (get it :items) i :py/stop)
             (py/type-error {:py/str "object is not iterable"})))))]
    [py/len
     (fn [o]
       (if (py/str? o)
         (data/str-length (get o :py/str))
         (if (py/cell? o)
           (let [c (cell/get o)
                 t (get c :py/type)]
             (if (= t :list)
               (data/count (get c :items))
               (if (= t :dict)
                 (data/count (get c :keys))
                 (py/type-error {:py/str "object has no len()"}))))
           (if (= (get o :py/type) :range)
             (py/range-len o 0)
             (if (= (get o :py/type) :tuple)
               (data/count (get o :items))
               (py/type-error {:py/str "object has no len()"}))))))]
    [py/range-args
     (fn [a rest]
       (let [xs (get rest :items)
             n (data/count xs)]
         (if (= n 0)
           (py/range3 0 a 1)
           (if (= n 1)
             (py/range3 a (get xs 0) 1)
             (if (= n 2)
               (py/range3 a (get xs 0) (get xs 1))
               (py/type-error {:py/str "range expected at most 3 arguments"}))))))]

    ;; ---------------------------------------------------------- print
    [py/snapshot
     (fn [x]
       (if (py/str? x)
         (get x :py/str)
         (if (= x :py/None)
           nil
           (if (py/cell? x)
             (py/snapshot-obj (cell/get x))
             (if (= (get x :py/type) :method)
               :py/method
               (if (= (get x :py/type) :tuple)
                 (assoc {} :py/tuple (py/snapshot-all (get x :items) 0 []))
                 (if (= (get x :py/type) :range)
                   (assoc {}
                          :py/range
                          (py/conj (py/conj (py/conj [] (get x :start))
                                            (get x :stop))
                                   (get x :step)))
                   x)))))))]
    [py/snapshot-all
     (fn [xs i acc]
       (let [x (get xs i :py/stop)]
         (if (= x :py/stop)
           acc
           (py/snapshot-all xs (+ i 1) (conj acc (py/snapshot x))))))]
    [py/snapshot-pairs
     (fn [ks vs i acc]
       (let [k (get ks i :py/stop)]
         (if (= k :py/stop)
           acc
           (py/snapshot-pairs ks vs (+ i 1)
                              (conj acc (conj (conj [] (py/snapshot k))
                                              (py/snapshot (get vs i))))))))]
    [py/snapshot-obj
     (fn [c]
       (let [t (get c :py/type)]
         (if (= t :list)
           (py/snapshot-all (get c :items) 0 [])
           (if (= t :dict)
             (assoc {} :py/dict (py/snapshot-pairs (get c :keys) (get c :vals) 0 []))
             (if (= t :instance)
               (assoc {} :py/instance (get (cell/get (get c :class)) :name))
               (if (= t :class)
                 (assoc {} :py/class (get c :name))
                 (if (= t :function)
                   (assoc {} :py/function (get c :name))
                   :py/object)))))))]
    [py/snapshot-exc
     (fn [e]
       (if (= (py/content-type e) :instance)
         (let [c (cell/get e)]
           (assoc (assoc {} :type (get (cell/get (get c :class)) :name))
                  :args
                  (py/snapshot-all (get (get (get c :attrs) "args" (py/tuple []))
                                        :items)
                                   0
                                   [])))
         (py/snapshot e)))]
    [py/print
     (fn [items]
       (do (cell/set! py.rt/out
                      (conj (cell/get py.rt/out) (py/snapshot-all items 0 [])))
           :py/None))]

    ;; ---------------------------------------------------------- module
    [py/run-module
     (fn [body]
       (do (cell/set! py.rt/out [])
           (cell/set! py.rt/handlers nil)
           (let [exc (py/try (fn [] (do (body (py/dict-new)) :py/None))
                             (fn [e] e)
                             (fn [] :py/None))]
             (assoc (assoc {} :py/out (cell/get py.rt/out))
                    :py/exception
                    (if (= exc :py/None) nil (py/snapshot-exc exc))))))]])


(def builtin-classes
  "Builtin class name -> [store key, base store key or nil]."
  [["object" 'py.b/object nil]
   ["BaseException" 'py.b/BaseException 'py.b/object]
   ["Exception" 'py.b/Exception 'py.b/BaseException]
   ["ArithmeticError" 'py.b/ArithmeticError 'py.b/Exception]
   ["ZeroDivisionError" 'py.b/ZeroDivisionError 'py.b/ArithmeticError]
   ["LookupError" 'py.b/LookupError 'py.b/Exception]
   ["IndexError" 'py.b/IndexError 'py.b/LookupError]
   ["KeyError" 'py.b/KeyError 'py.b/LookupError]
   ["TypeError" 'py.b/TypeError 'py.b/Exception]
   ["ValueError" 'py.b/ValueError 'py.b/Exception]
   ["AttributeError" 'py.b/AttributeError 'py.b/Exception]
   ["NameError" 'py.b/NameError 'py.b/Exception]
   ["UnboundLocalError" 'py.b/UnboundLocalError 'py.b/NameError]
   ["RuntimeError" 'py.b/RuntimeError 'py.b/Exception]])


(def ^:private builtin-function-definitions
  "Builtin functions are function objects like any guest function, so they
   can be passed, stored and called through `py/call`."
  '[[py.b/len
     (py/make-function "len" 1 [] false (fn [args] (py/len (py/arg args 0))))]
    [py.b/isinstance
     (py/make-function "isinstance" 2 [] false
                       (fn [args] (py/isinstance (py/arg args 0) (py/arg args 1))))]
    [py.b/print
     (py/make-function "print" 0 [] true
                       (fn [args] (py/print (get (py/arg args 0) :items))))]
    [py.b/range
     (py/make-function "range" 1 [] true
                       (fn [args] (py/range-args (py/arg args 0) (py/arg args 1))))]
    [py.b/list-append
     (py/make-function "append" 2 [] false
                       (fn [args] (py/list-append (py/arg args 0) (py/arg args 1))))]])


(def ^:private state-definitions
  "Definitions that allocate: the two runtime cells, the builtin classes
   and the builtin functions. These are the only prelude forms that run at
   load."
  (-> '[[py.rt/handlers (cell/new nil)]
        [py.rt/out (cell/new [])]]
      (into (map (fn [[nm key base]]
                   [key (list 'py/make-class nm (or base :py/None))]))
            builtin-classes)
      (into builtin-function-definitions)))


(def function-definitions
  "Every prelude function as `[store-key form]`, in definition order."
  core-definitions)


(defn- definitions->uast
  [defs]
  (u/seq-nodes (map (fn [[k form]] (u/def! k (u/sexp->uast form))) defs)))


(def functions-uast
  "Only the function definitions: defining lambdas runs nothing, so this
   loads on any composition, with or without cells."
  (definitions->uast function-definitions))


(def uast
  "The whole prelude: function definitions, then runtime state."
  (u/then functions-uast (definitions->uast state-definitions)))


(def builtin-names
  "Python builtin name -> the prelude store key holding it."
  (into {"len" 'py.b/len,
         "isinstance" 'py.b/isinstance,
         "print" 'py.b/print,
         "range" 'py.b/range}
        (map (fn [[nm key _]] [nm key]))
        builtin-classes))
