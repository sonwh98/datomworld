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
     set             a cell holding {:py/type :set :index {nkey slot}
                     :keys [...]}: insertion order, keys normalized as for
                     dicts
     function        a cell holding {:py/type :function :name s :spec spec
                     :defaults [...] :kwdefaults [[name v] ...]
                     :code (fn [args] ...)}; identity is the cell ref
     class           a cell holding {:py/type :class :name s :base cls
                     :attrs {name value}}
     instance        a cell holding {:py/type :instance :class cls
                     :attrs {name value}}
     bound method    {:py/type :method :self obj :fn function}
     tuple           {:py/type :tuple :items [...]}: a value; hashable when
                     its elements are
     slice           {:py/type :slice :start a :stop b :step c}
     range           {:py/type :range :start a :stop b :step s}
     module globals  a dict (owner decision 1): the module body receives it
                     as `%globals` and every function closes over it, which
                     is Python's own `__globals__`; a miss is NameError

   Calling convention: a function's `:code` takes one vector of arguments
   laid out as the parameters are written. `py/call-kw` (and `py/call`,
   its positional-only form) binds positional arguments, keywords, defaults,
   `*args` and `**kwargs` from the function's static spec; every binding
   failure is a Python TypeError.

   Runtime state is two cells, reached through the store (`yin/def` is
   reserved for the prelude and builtins):
     py.rt/handlers  the handler stack: frames [kind payload rest depth];
                     a :handler frame holds the continuation `raise`
                     delivers to, a :finally frame a thunk every exit
                     through it runs (raise passes the exception, escapes
                     and normal completion pass None); nil when empty
     py.rt/out       the values `print` collected, one vector per call

   Escapes and handlers tell the first pass through a capture point from a
   re-entry with a flag cell allocated before the capture (`:first`, then
   `:re-entered`): heap writes survive continuation invocation, so the flag
   answers without inspecting the continuation's representation. An escape
   unwinds to the depth it captured, running finally thunks on the way.

   Integer `//`, `%`, `**`, bitwise operators and float floor have no
   portable host primitive; they are exact algorithms here over + - * <
   (doubling division, binary-descent floor, two's-complement recursion),
   valid within 2^53.

   Host names the prelude depends on and does not define: `host-names`
   (the cell module from yin.vm.module, the rest from yin.vm.data)."
  (:require
    [yang.python.antlr.uast :as u]))


(def host-names
  "Module exports the prelude calls."
  '#{cell/new cell/get cell/set! data/count data/into data/subvec
     data/str-concat data/str-length data/str-index-of data/str->code-points
     data/code-points->str})


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
         (if (= x false) true (if (py/float? x) true (data/number? x)))))]
    [py/num
     (fn [x]
       (if (= x true) 1 (if (= x false) 0 (if (py/float? x) (get x :py/float) x))))]
    [py/zero? (fn [x] (let [v (py/num x)] (if (= v 0) true (= v 0.0))))]
    [py/content-type (fn [x] (if (py/cell? x) (get (cell/get x) :py/type) nil))]
    [py/function? (fn [x] (= (py/content-type x) :function))]

    ;; ---------------------------------------------------------- escapes
    ;; The handler stack is a chain of frames [kind payload rest depth]:
    ;; :handler frames hold the continuation `raise` delivers to, :finally
    ;; frames hold a thunk of one argument (the exception, or None) that
    ;; every exit through them runs. Depth makes "unwind to here" an integer
    ;; comparison.
    [py/frame-depth (fn [hs] (if (nil? hs) 0 (get hs 3)))]
    [py/frame
     (fn [kind payload rest]
       (py/conj (py/conj (py/conj (py/conj [] kind) payload) rest)
                (+ 1 (py/frame-depth rest))))]
    [py/raise
     (fn [e]
       (let [hs (cell/get py.rt/handlers)]
         (if (nil? hs)
           (:py/no-handler e)
           (if (= (get hs 0) :finally)
             (do (cell/set! py.rt/handlers (get hs 2))
                 ((get hs 1) e)
                 (py/raise e))
             ((get hs 1) e)))))]
    [py/unwind-to
     ;; pop frames above depth d, running each finally thunk with None
     (fn [d]
       (let [hs (cell/get py.rt/handlers)]
         (if (nil? hs)
           :py/None
           (if (<= (get hs 3) d)
             :py/None
             (do (cell/set! py.rt/handlers (get hs 2))
                 (if (= (get hs 0) :finally) ((get hs 1) :py/None) :py/None)
                 (py/unwind-to d))))))]
    [py/try
     (fn [body handler orelse]
       (let [saved (cell/get py.rt/handlers)
             flag (cell/new :first)]
         ((fn [r]
            (if (= (cell/get flag) :first)
              (do (cell/set! flag :re-entered)
                  (cell/set! py.rt/handlers (py/frame :handler r saved))
                  (body)
                  (cell/set! py.rt/handlers saved)
                  (orelse))
              (do (cell/set! py.rt/handlers saved)
                  (handler r))))
          (%capture))))]
    [py/try-finally
     ;; normal exit runs `fin` here; raise and escapes run it as they
     ;; unwind through the :finally frame
     (fn [body fin]
       (let [saved (cell/get py.rt/handlers)]
         (do (cell/set! py.rt/handlers (py/frame :finally fin saved))
             (let [v (body)]
               (do (cell/set! py.rt/handlers saved)
                   (fin :py/None)
                   v)))))]
    [py/call-ec
     (fn [f]
       (let [saved (cell/get py.rt/handlers)
             flag (cell/new :first)]
         ((fn [r]
            (if (= (cell/get flag) :first)
              (do (cell/set! flag :re-entered)
                  (f (fn [x]
                       (do (py/unwind-to (py/frame-depth saved))
                           (cell/set! py.rt/handlers saved)
                           (r x)))))
              r))
          (%capture))))]
    [py/type-of
     (fn [x] (if (= (py/content-type x) :instance) (get (cell/get x) :class) :py/None))]
    [py/with
     ;; the language reference's expansion: __exit__ is looked up before
     ;; __enter__ runs; an exception calls __exit__(type, value, None) and
     ;; is suppressed when that is truthy; every other exit calls
     ;; __exit__(None, None, None)
     ;; Both dunders are looked up on the manager's type, as implicit
     ;; invocations are; an instance attribute of that name is not used.
     (fn [mgr suite]
       (let [cls (py/type-of mgr)
             enter-fn (if (= cls :py/None) :py/missing (py/class-lookup cls "__enter__"))
             exit-fn (if (= cls :py/None) :py/missing (py/class-lookup cls "__exit__"))]
         (if (if (= enter-fn :py/missing) true (= exit-fn :py/missing))
           (py/type-error
             {:py/str "object does not support the context manager protocol"})
           (py/with-bound mgr (py/bind mgr enter-fn) (py/bind mgr exit-fn) suite))))]
    [py/with-bound
     (fn [mgr enter exit suite]
       (let [value (py/call enter [])
             hit (cell/new false)]
         (py/try-finally
           (fn []
             (py/try (fn [] (suite value))
                     (fn [e]
                       (do (cell/set! hit true)
                           (if (py/truthy
                                 (py/call exit
                                          (py/conj (py/conj (py/conj [] (py/type-of e)) e)
                                                   :py/None)))
                             :py/None
                             (py/raise e))))
                     (fn [] :py/None)))
           (fn [x]
             (if (cell/get hit)
               :py/None
               (py/call exit (py/conj (py/conj (py/conj [] :py/None) :py/None)
                                      :py/None)))))))]

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
     ;; `raise X`: a BaseException subclass is instantiated, an instance of
     ;; one is raised as is, anything else is a TypeError
     (fn [x]
       (let [t (py/content-type x)]
         (if (if (= t :class) (py/subclass? x py.b/BaseException) false)
           (py/call x [])
           (if (if (= t :instance)
                 (py/subclass? (get (cell/get x) :class) py.b/BaseException)
                 false)
             x
             (py/type-error {:py/str "exceptions must derive from BaseException"})))))]
    [py/subclass?
     (fn [c target]
       (if (= c :py/None)
         false
         (if (= c target) true (py/subclass? (get (cell/get c) :base) target))))]
    [py/class-match?
     ;; c (a class, or None) against target: a class, or a tuple of classes
     ;; and tuples, matched recursively. A target that is neither is a
     ;; TypeError `msg`; with `exc?` a class must also derive from
     ;; BaseException, as an except clause requires.
     (fn [c target exc? msg]
       (if (= (get target :py/type) :tuple)
         (py/any-class-match? c (get target :items) 0 exc? msg)
         (if (= (py/content-type target) :class)
           (if (if exc? (not (py/subclass? target py.b/BaseException)) false)
             (py/type-error msg)
             (py/subclass? c target))
           (py/type-error msg))))]
    [py/any-class-match?
     (fn [c ts i exc? msg]
       (let [t (get ts i :py/stop)]
         (if (= t :py/stop)
           false
           (if (py/class-match? c t exc? msg)
             true
             (py/any-class-match? c ts (+ i 1) exc? msg)))))]
    [py/isinstance
     (fn [o cls]
       (py/class-match? (py/type-of o) cls false
                        {:py/str "isinstance() arg 2 must be a type or tuple of types"}))]
    [py/exc-class?
     (fn [x] (if (= (py/content-type x) :class) (py/subclass? x py.b/BaseException) false))]
    [py/all-exc-classes?
     (fn [ts i]
       (let [t (get ts i :py/stop)]
         (if (= t :py/stop) true (if (py/exc-class? t) (py/all-exc-classes? ts (+ i 1)) false))))]
    [py/exc-matches
     ;; an except clause's test of the exception in flight, as CPython does
     ;; it: a BaseException subclass, or a flat tuple of them, every element
     ;; checked before any is matched; anything else (a nested tuple
     ;; included) is a TypeError
     (fn [e cls]
       (if (if (= (get cls :py/type) :tuple)
             (py/all-exc-classes? (get cls :items) 0)
             (py/exc-class? cls))
         (py/class-match? (py/type-of e) cls true {:py/str ""})
         (py/type-error {:py/str (data/str-concat
                                   "catching classes that do not inherit from "
                                   "BaseException is not allowed")})))]

    ;; ---------------------------------------------------------- names
    [py/local-get
     (fn [c n]
       (let [x (cell/get c)]
         (if (= x :py/unbound)
           (py/raise-new py.b/UnboundLocalError
                         (py/str (data/str-concat
                                   "cannot access local variable '" (get n :py/str)
                                   "' where it is not associated with a value")))
           x)))]
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
    ;; A function's `spec` is static data from the lowering:
    ;;   {:params [positional names] :star? bool :kwonly [names] :kwstar? bool}
    ;; `defaults` holds the trailing positional defaults and `kwdefaults`
    ;; [[name value] ...] the keyword-only ones, both evaluated at
    ;; definition time. `code` takes one vector laid out as the parameters
    ;; are written: positional, the *args tuple, keyword-only, the **kwargs
    ;; dict.
    [py/make-function
     (fn [name spec defaults kwdefaults code]
       (cell/new (assoc (assoc (assoc (assoc (assoc (assoc {} :py/type :function)
                                                    :name name)
                                             :spec spec)
                                      :defaults defaults)
                               :kwdefaults kwdefaults)
                        :code code)))]
    [py/fn-error
     (fn [fc msg] (py/type-error (py/str (data/str-concat (get fc :name) msg))))]
    [py/index-of
     (fn [xs x i]
       (let [y (get xs i :py/stop)]
         (if (= y :py/stop) -1 (if (= y x) i (py/index-of xs x (+ i 1))))))]
    [py/pad
     ;; the first n of xs, :py/missing where xs is shorter
     (fn [xs n i acc]
       (if (< i n) (py/pad xs n (+ i 1) (conj acc (get xs i :py/missing))) acc))]
    [py/bind-keyword
     ;; state is [positional-slots kwonly-slots kwargs-dict-or-nil]
     (fn [fc state name x]
       (let [spec (get fc :spec)
             pi (py/index-of (get spec :params) name 0)
             ki (py/index-of (get spec :kwonly) name 0)
             dup (py/str (data/str-concat "() got multiple values for argument '"
                                          name "'"))]
         (if (< -1 pi)
           (if (= (get (get state 0) pi) :py/missing)
             (assoc state 0 (assoc (get state 0) pi x))
             (py/fn-error fc (get dup :py/str)))
           (if (< -1 ki)
             (if (= (get (get state 1) ki) :py/missing)
               (assoc state 1 (assoc (get state 1) ki x))
               (py/fn-error fc (get dup :py/str)))
             (if (nil? (get state 2))
               (py/fn-error fc (data/str-concat
                                 "() got an unexpected keyword argument '" name "'"))
               (if (py/dict-has? (get state 2) (py/str name))
                 (py/fn-error fc (get dup :py/str))
                 (do (py/dict-set (get state 2) (py/str name) x) state)))))))]
    [py/bind-keywords
     (fn [fc state kwargs i]
       (let [pair (get kwargs i :py/stop)]
         (if (= pair :py/stop)
           state
           (py/bind-keywords fc (py/bind-keyword fc state (get pair 0) (get pair 1))
                             kwargs (+ i 1)))))]
    [py/fill-defaults
     (fn [fc slots defaults n i]
       (if (< i n)
         (if (= (get slots i) :py/missing)
           (let [j (- i (- n (data/count defaults)))]
             (if (< j 0)
               (py/fn-error fc (data/str-concat
                                 "() missing required positional argument '"
                                 (get (get (get fc :spec) :params) i) "'"))
               (py/fill-defaults fc (assoc slots i (get defaults j)) defaults n
                                 (+ i 1))))
           (py/fill-defaults fc slots defaults n (+ i 1)))
         slots))]
    [py/lookup-pair
     (fn [pairs name i]
       (let [pair (get pairs i :py/stop)]
         (if (= pair :py/stop)
           :py/missing
           (if (= (get pair 0) name) (get pair 1) (py/lookup-pair pairs name (+ i 1))))))]
    [py/fill-kwdefaults
     (fn [fc slots names i]
       (let [nm (get names i :py/stop)]
         (if (= nm :py/stop)
           slots
           (if (= (get slots i) :py/missing)
             (let [d (py/lookup-pair (get fc :kwdefaults) nm 0)]
               (if (= d :py/missing)
                 (py/fn-error fc (data/str-concat
                                   "() missing required keyword-only argument '"
                                   nm "'"))
                 (py/fill-kwdefaults fc (assoc slots i d) names (+ i 1))))
             (py/fill-kwdefaults fc slots names (+ i 1))))))]
    [py/bind-args
     ;; positional, then keywords, then defaults; every failure a TypeError
     (fn [fc args kwargs]
       (let [spec (get fc :spec)
             n (data/count (get spec :params))
             given (data/count args)
             star (get spec :star?)]
         (if (if (get spec :no-kw) (< 0 (data/count kwargs)) false)
           (py/fn-error fc "() takes no keyword arguments")
           (if (if (< n given) (not star) false)
             (py/fn-error fc "() takes too many positional arguments")
             (let [state (py/bind-keywords
                           fc
                           (py/conj (py/conj (py/conj [] (py/pad args n 0 []))
                                             (py/pad [] (data/count (get spec :kwonly)) 0 []))
                                    (if (get spec :kwstar?) (py/dict-new) nil))
                           kwargs
                           0)
                   slots (py/fill-defaults fc (get state 0) (get fc :defaults) n 0)
                   ko (py/fill-kwdefaults fc (get state 1) (get spec :kwonly) 0)
                   with-star (if star
                               (py/conj slots
                                        (py/tuple (if (< n given) (data/subvec args n) [])))
                               slots)
                   with-ko (data/into with-star ko)]
               (if (get spec :kwstar?) (py/conj with-ko (get state 2)) with-ko))))))]
    [py/call (fn [f args] (py/call-kw f args []))]
    [py/call-kw
     (fn [f args kwargs]
       (if (= (get f :py/type) :method)
         (py/call-kw (get f :fn) (data/into (py/conj [] (get f :self)) args) kwargs)
         (let [t (py/content-type f)]
           (if (= t :function)
             (let [fc (cell/get f)] ((get fc :code) (py/bind-args fc args kwargs)))
             (if (= t :class)
               (py/instantiate f args kwargs)
               (py/type-error {:py/str "object is not callable"}))))))]
    [py/instantiate
     (fn [cls args kwargs]
       (let [inst (py/make-instance cls)
             init (py/class-lookup cls "__init__")]
         ;; without __init__ only exception classes take arguments (they
         ;; become `args`); any other class takes none
         (do (if (= init :py/missing)
               (if (py/subclass? cls py.b/BaseException)
                 (py/setattr inst "args" (py/tuple args))
                 (if (if (< 0 (data/count args)) true (< 0 (data/count kwargs)))
                   (py/type-error
                     (py/str (data/str-concat (get (cell/get cls) :name)
                                              "() takes no arguments")))
                   :py/None))
               (py/call-kw init (data/into (py/conj [] inst) args) kwargs))
             inst)))]
    [py/extend
     ;; call-site *iterable and display *iterable
     (fn [acc it] (data/into acc (py/to-vector it)))]
    [py/kw-extend
     ;; call-site **mapping: its string keys become keyword pairs
     (fn [acc d]
       (if (= (py/content-type d) :dict)
         (py/kw-pairs acc (get (cell/get d) :keys) (get (cell/get d) :vals) 0)
         (py/type-error {:py/str "argument after ** must be a mapping"})))]
    [py/kw-pairs
     (fn [acc ks vs i]
       (let [k (get ks i :py/stop)]
         (if (= k :py/stop)
           acc
           (if (py/str? k)
             (py/kw-pairs (conj acc (conj (conj [] (get k :py/str)) (get vs i))) ks vs
                          (+ i 1))
             (py/type-error {:py/str "keywords must be strings"})))))]

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
                 (if (= m :py/missing)
                   (if (= name "__name__") (py/str (get c :name)) (py/attr-error name))
                   m))
               (if (= t :list)
                 (if (= name "append")
                   (py/method o py.b/list-append)
                   (py/attr-error name))
                 (if (= t :function)
                   (if (= name "__name__")
                     (py/str (get c :name))
                     (py/attr-error name))
                   (if (= t :set)
                     (if (= name "add") (py/method o py.b/set-add) (py/attr-error name))
                     (if (= t :dict)
                       (if (= name "items")
                         (py/method o py.b/dict-items)
                         (if (= name "keys")
                           (py/method o py.b/dict-keys)
                           (if (= name "values")
                             (py/method o py.b/dict-values)
                             (if (= name "get")
                               (py/method o py.b/dict-get)
                               (py/attr-error name)))))
                       (py/attr-error name))))))))
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
    ;; Integers are bounded to [-2^53, 2^53], the range every host holds
    ;; exactly: a JVM long would throw past 2^63, a JS double rounds past
    ;; 2^53, a Dart int wraps. Each check runs before the host operation,
    ;; so an out-of-range result is a guest OverflowError on every host.
    [py/overflow
     (fn []
       (py/raise-new py.b/OverflowError
                     {:py/str "integer result outside the supported range (2**53)"}))]
    [py/checked-add
     (fn [a b]
       (if (< 0 a)
         (if (< 0 b) (if (< (- 9007199254740992 b) a) (py/overflow) (+ a b)) (+ a b))
         (if (< b 0) (if (< a (- -9007199254740992 b)) (py/overflow) (+ a b)) (+ a b))))]
    [py/checked-sub (fn [a b] (py/checked-add a (- 0 b)))]
    [py/checked-mul
     ;; |a * b| <= 2^53 exactly when |b| <= floor(2^53 / |a|)
     (fn [a b]
       (let [x (py/abs a)
             y (py/abs b)]
         (if (if (< x 67108864) (< y 67108864) false)
           (* a b)
           (if (= x 0)
             0
             (if (< (get (py/divmod-pos 9007199254740992 x) 0) y)
               (py/overflow)
               (* a b))))))]
    [py/arith
     ;; op is :add, :sub or :mul; ints are checked, floats are not
     (fn [op a b]
       (if (py/numeric? a)
         (if (py/numeric? b)
           (let [x (py/num a)
                 y (py/num b)]
             (if (if (py/float? a) true (py/float? b))
               (py/float (if (= op :add) (+ x y) (if (= op :sub) (- x y) (* x y))))
               (if (= op :add)
                 (py/checked-add x y)
                 (if (= op :sub) (py/checked-sub x y) (py/checked-mul x y)))))
           (py/type-error {:py/str "unsupported operand type"}))
         (py/type-error {:py/str "unsupported operand type"})))]
    [py/compare
     (fn [op a b]
       (if (py/numeric? a)
         (if (py/numeric? b)
           (op (py/num a) (py/num b))
           (py/type-error {:py/str "comparison not supported"}))
         (py/type-error {:py/str "comparison not supported"})))]
    [py/kind
     ;; the type tag of any guest value that carries one
     (fn [x] (if (py/cell? x) (get (cell/get x) :py/type) (get x :py/type)))]
    [py/add
     (fn [a b]
       (if (py/str? a)
         (if (py/str? b)
           (py/str (data/str-concat (get a :py/str) (get b :py/str)))
           (py/type-error {:py/str "can only concatenate str to str"}))
         (if (= (py/kind a) :list)
           (if (= (py/kind b) :list)
             (py/list (data/into (get (cell/get a) :items) (get (cell/get b) :items)))
             (py/type-error {:py/str "can only concatenate list to list"}))
           (if (= (py/kind a) :tuple)
             (if (= (py/kind b) :tuple)
               (py/tuple (data/into (get a :items) (get b :items)))
               (py/type-error {:py/str "can only concatenate tuple to tuple"}))
             (py/arith :add a b)))))]
    [py/iadd
     ;; `+=` extends a list in place (list.__iadd__); everything else is +
     (fn [a b]
       (if (= (py/kind a) :list)
         (let [c (cell/get a)]
           (do (cell/set! a (assoc c :items (data/into (get c :items) (py/to-vector b))))
               a))
         (py/add a b)))]
    [py/sub (fn [a b] (py/arith :sub a b))]
    [py/repeat-items
     (fn [items n acc] (if (< 0 n) (py/repeat-items items (- n 1) (data/into acc items)) acc))]
    [py/repeat-str
     (fn [s n acc] (if (< 0 n) (py/repeat-str s (- n 1) (data/str-concat acc s)) acc))]
    [py/repeat
     ;; sequence * int, int counted as Python does (negative is empty)
     (fn [s n]
       (if (py/int? n)
         (let [k (py/num n)]
           (if (py/str? s)
             (py/str (py/repeat-str (get s :py/str) k ""))
             (if (= (py/kind s) :list)
               (py/list (py/repeat-items (get (cell/get s) :items) k []))
               (py/tuple (py/repeat-items (get s :items) k [])))))
         (py/type-error {:py/str "can't multiply sequence by non-int"})))]
    [py/sequence?
     (fn [x]
       (if (py/str? x)
         true
         (let [k (py/kind x)] (if (= k :list) true (= k :tuple)))))]
    [py/mul
     (fn [a b]
       (if (py/sequence? a)
         (py/repeat a b)
         (if (py/sequence? b) (py/repeat b a) (py/arith :mul a b))))]
    [py/imul
     (fn [a b]
       (if (= (py/kind a) :list)
         (let [c (cell/get a)]
           (do (cell/set! a (assoc c :items (get (cell/get (py/repeat a b)) :items)))
               a))
         (py/mul a b)))]

    ;; ---------------------------------------------------------- integer ops
    ;; No host primitive floors or takes remainders portably, so these are
    ;; exact algorithms over + - * <, valid for integers within 2^53.
    [py/int? (fn [x] (if (py/numeric? x) (not (py/float? x)) false))]
    [py/abs (fn [x] (if (< x 0) (- 0 x) x))]
    [py/divmod-pos
     ;; [q r] for a >= 0, b > 0, by doubling the divisor
     (fn [a b]
       (if (< a b)
         (py/conj (py/conj [] 0) a)
         (let [qr (py/divmod-pos a (+ b b))
               q (+ (get qr 0) (get qr 0))
               r (get qr 1)]
           (if (< r b)
             (py/conj (py/conj [] q) r)
             (py/conj (py/conj [] (+ q 1)) (- r b))))))]
    [py/int-floordiv
     ;; floor(a / b), Python's sign rule
     (fn [a b]
       (let [qr (py/divmod-pos (py/abs a) (py/abs b))
             q (get qr 0)]
         (if (= (< a 0) (< b 0))
           q
           (if (= (get qr 1) 0) (- 0 q) (- (- 0 q) 1)))))]
    [py/int-mod
     ;; the remainder with the divisor's sign, from the exact |a| mod |b|
     (fn [a b]
       (let [r (get (py/divmod-pos (py/abs a) (py/abs b)) 1)]
         (if (= r 0)
           0
           (if (= (< a 0) (< b 0))
             (if (< a 0) (- 0 r) r)
             (if (< b 0) (- r (py/abs b)) (- (py/abs b) r))))))]
    [py/finite? (fn [x] (<= (py/abs x) 1.7976931348623157E308))]
    [py/fmod-pos
     ;; fmod for doubles x >= 0, y > 0, exactly: each subtraction is of
     ;; values within a factor of two (Sterbenz), as in long division
     (fn [x y]
       (if (< x y)
         x
         (let [r (py/fmod-pos x (+ y y))]
           (if (< r y) r (- r y)))))]
    [py/float-divmod
     ;; [floor-quotient remainder] as CPython's float_divmod computes them:
     ;; the remainder from the exact fmod, then sign-corrected
     (fn [x y]
       (if (if (py/finite? x) (= y y) false)
         (let [m0 (py/fmod-pos (py/abs x) (py/abs y))
               m (if (< x 0) (- 0 m0) m0)
               adjust (if (= m 0) false (not (= (< y 0) (< m 0))))
               mod (if (= m 0) (* 0.0 y) (if adjust (+ m y) m))
               div (- (/ (- x m) y) (if adjust 1.0 0.0))
               fd (if (= div 0)
                    (* 0.0 (/ x y))
                    (let [f (py/floor div)] (if (< 0.5 (- div f)) (+ f 1) f)))]
           (py/conj (py/conj [] fd) mod))
         (py/conj (py/conj [] (- x x)) (- x x))))]
    [py/pow2-above (fn [x k] (if (> k x) k (py/pow2-above x (+ k k))))]
    [py/floor-descend
     (fn [x n p]
       (if (< p 1)
         n
         (if (<= (+ n p) x)
           (py/floor-descend x (+ n p) (/ p 2))
           (py/floor-descend x n (/ p 2)))))]
    [py/floor
     ;; floor of a finite real within 2^53, as an integer
     (fn [x]
       (if (if (< x 9007199254740992) (> x -9007199254740992) false)
         (if (< x 0)
           (let [y (- 0 x)
                 f (py/floor-descend y 0 (/ (py/pow2-above y 1) 2))]
             (if (<= y f) (- 0 f) (- (- 0 f) 1)))
           (py/floor-descend x 0 (/ (py/pow2-above x 1) 2)))
         (py/raise-new py.b/OverflowError
                       {:py/str "float floor outside the supported range"})))]
    [py/division-check
     (fn [a b]
       (if (py/numeric? a)
         (if (py/numeric? b)
           (if (py/zero? b)
             (py/raise-new py.b/ZeroDivisionError
                           {:py/str "integer division or modulo by zero"})
             true)
           (py/type-error {:py/str "unsupported operand type"}))
         (py/type-error {:py/str "unsupported operand type"})))]
    [py/floordiv
     (fn [a b]
       (do (py/division-check a b)
           (if (if (py/int? a) (py/int? b) false)
             (py/int-floordiv (py/num a) (py/num b))
             (py/float (get (py/float-divmod (* 1.0 (py/num a)) (* 1.0 (py/num b))) 0)))))]
    [py/mod
     (fn [a b]
       (do (py/division-check a b)
           (if (if (py/int? a) (py/int? b) false)
             (py/int-mod (py/num a) (py/num b))
             (py/float (get (py/float-divmod (* 1.0 (py/num a)) (* 1.0 (py/num b))) 1)))))]
    [py/ipow
     ;; int base ** e for an integer e >= 0, by squaring, bound-checked
     (fn [base e]
       (if (= e 0)
         1
         (let [h (py/ipow base (py/int-floordiv e 2))
               hh (py/checked-mul h h)]
           (if (= (py/int-mod e 2) 0) hh (py/checked-mul hh base)))))]
    [py/fpow
     ;; float base ** e for an integer e >= 0
     (fn [base e]
       (if (= e 0)
         1.0
         (let [h (py/fpow base (py/int-floordiv e 2))
               hh (* h h)]
           (if (= (py/int-mod e 2) 0) hh (* hh base)))))]
    [py/pow
     (fn [a b]
       (if (if (py/numeric? a) (py/numeric? b) false)
         (let [x (py/num a)
               y (py/num b)
               floaty (if (py/float? a) true (py/float? b))]
           (if (if (py/float? b) (< (py/floor y) y) false)
             (py/raise-new py.b/NotImplementedError
                           {:py/str "non-integer exponents are not supported"})
             (let [e (if (py/float? b) (py/floor y) y)]
               (if (< e 0)
                 (if (py/zero? a)
                   (py/raise-new py.b/ZeroDivisionError
                                 {:py/str "0.0 cannot be raised to a negative power"})
                   (py/float (/ 1.0 (py/fpow (* 1.0 x) (- 0 e)))))
                 (if floaty (py/float (py/fpow (* 1.0 x) e)) (py/ipow x e))))))
         (py/type-error {:py/str "unsupported operand type for **"})))]
    [py/bit1
     (fn [op x y]
       (if (= op :and)
         (if (= x 1) (if (= y 1) 1 0) 0)
         (if (= op :or) (if (= x 1) 1 (if (= y 1) 1 0)) (if (= x y) 0 1))))]
    [py/bit-op
     ;; two's complement of unbounded width: once both operands are 0 or -1
     ;; the remaining bits are their sign bits
     (fn [op a b]
       (if (if (if (= a 0) true (= a -1)) (if (= b 0) true (= b -1)) false)
         (- 0 (py/bit1 op (if (= a -1) 1 0) (if (= b -1) 1 0)))
         (py/checked-add (py/bit1 op (py/int-mod a 2) (py/int-mod b 2))
                         (py/checked-mul 2 (py/bit-op op (py/int-floordiv a 2)
                                                      (py/int-floordiv b 2))))))]
    [py/int-op
     (fn [op a b]
       (if (if (py/int? a) (py/int? b) false)
         (py/bit-op op (py/num a) (py/num b))
         (py/type-error {:py/str "unsupported operand type for a bitwise operator"})))]
    [py/bitand (fn [a b] (py/int-op :and a b))]
    [py/bitor (fn [a b] (py/int-op :or a b))]
    [py/bitxor (fn [a b] (py/int-op :xor a b))]
    [py/invert
     (fn [a]
       (if (py/int? a)
         (py/checked-sub (- 0 (py/num a)) 1)
         (py/type-error {:py/str "bad operand type for unary ~"})))]
    [py/shift-check
     (fn [a n]
       (if (if (py/int? a) (py/int? n) false)
         (if (< (py/num n) 0)
           (py/raise-new py.b/ValueError {:py/str "negative shift count"})
           true)
         (py/type-error {:py/str "unsupported operand type for a shift"})))]
    [py/lshift
     (fn [a n]
       (do (py/shift-check a n)
           (if (= (py/num a) 0) 0 (py/checked-mul (py/num a) (py/ipow 2 (py/num n))))))]
    [py/rshift
     ;; past 53 places every in-range int is 0 or -1
     (fn [a n]
       (do (py/shift-check a n)
           (if (< 53 (py/num n))
             (if (< (py/num a) 0) -1 0)
             (py/int-floordiv (py/num a) (py/ipow 2 (py/num n))))))]
    [py/truediv
     (fn [a b]
       (if (py/numeric? a)
         (if (py/numeric? b)
           (if (py/zero? b)
             (py/raise-new py.b/ZeroDivisionError {:py/str "division by zero"})
             (py/float (/ (* 1.0 (py/num a)) (py/num b))))
           (py/type-error {:py/str "unsupported operand type"}))
         (py/type-error {:py/str "unsupported operand type"})))]
    [py/neg (fn [a] (py/arith :sub 0 a))]
    [py/pos (fn [a] (py/arith :add 0 a))]
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
           (if (= (py/kind a) :tuple)
             (if (= (py/kind b) :tuple)
               (py/eq-items (get a :items) (get b :items) 0)
               false)
             (= a b)))))]
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
             (if (= (get ca :py/type) :set)
               (if (= (get cb :py/type) :set)
                 (if (= (data/count (get ca :keys)) (data/count (get cb :keys)))
                   (py/all-in? b (get ca :keys) 0)
                   false)
                 false)
               false)))))]
    [py/all-in?
     (fn [c xs i]
       (let [x (get xs i :py/stop)]
         (if (= x :py/stop) true (if (py/contains c x) (py/all-in? c xs (+ i 1)) false))))]
    [py/seq-contains?
     (fn [xs x i]
       (let [y (get xs i :py/stop)]
         (if (= y :py/stop) false (if (py/eq y x) true (py/seq-contains? xs x (+ i 1))))))]
    [py/contains
     ;; `x in c`
     (fn [c x]
       (if (py/str? c)
         (if (py/str? x)
           (not (nil? (data/str-index-of (get c :py/str) (get x :py/str))))
           (py/type-error {:py/str "'in <string>' requires string as left operand"}))
         (let [k (py/kind c)]
           (if (if (= k :dict) true (= k :set))
             (py/dict-has? c x)
             (if (if (= k :range) (py/int? x) false)
               (py/range-has? c (py/num x))
               (if (if (= k :list) true (if (= k :tuple) true (= k :range)))
                 (py/seq-contains? (py/to-vector c) x 0)
                 (py/type-error {:py/str "argument of type is not iterable"})))))))]
    [py/range-has?
     ;; O(1): within the bounds and on the step
     (fn [r x]
       (let [start (get r :start)
             stop (get r :stop)
             step (get r :step)]
         (if (if (< 0 step)
               (if (<= start x) (< x stop) false)
               (if (< stop x) (<= x start) false))
           (= 0 (py/int-mod (py/checked-sub x start) step))
           false)))]
    [py/in (fn [x c] (py/contains c x))]
    [py/not-in (fn [x c] (not (py/contains c x)))]
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
                 (if (= (get x :py/type) :tuple)
                   (< 0 (data/count (get x :items)))
                   (if (py/numeric? x) (not (py/zero? x)) true))))))))]
    [py/obj-truthy
     (fn [c]
       (let [t (get c :py/type)]
         (if (= t :list)
           (< 0 (data/count (get c :items)))
           (if (if (= t :dict) true (= t :set)) (< 0 (data/count (get c :keys))) true))))]
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
    [py/key
     ;; the normalized index key: 1, 1.0 and True are one key; a tuple is
     ;; hashable when its elements are; lists, dicts and sets are not
     (fn [k]
       (if (py/numeric? k)
         (* 1.0 (py/num k))
         (if (= (get k :py/type) :tuple)
           (assoc {} :py/tuple-key (py/keys-of (get k :items) 0 []))
           (let [t (py/content-type k)]
             (if (if (= t :list) true (if (= t :dict) true (= t :set)))
               (py/type-error {:py/str "unhashable type"})
               k)))))]
    [py/keys-of
     (fn [xs i acc]
       (let [x (get xs i :py/stop)]
         (if (= x :py/stop) acc (py/keys-of xs (+ i 1) (conj acc (py/key x))))))]
    [py/dict-has?
     (fn [d k] (not (= (get (get (cell/get d) :index) (py/key k) :py/missing) :py/missing)))]
    [py/dict-new (fn [] (cell/new {:py/type :dict, :index {}, :keys [], :vals []}))]
    [py/set-new (fn [] (cell/new {:py/type :set, :index {}, :keys []}))]
    [py/set-add
     (fn [s x]
       (let [c (cell/get s)
             nk (py/key x)]
         (if (= (get (get c :index) nk :py/missing) :py/missing)
           (do (cell/set! s
                          (assoc (assoc c
                                        :index
                                        (assoc (get c :index) nk (data/count (get c :keys))))
                                 :keys
                                 (conj (get c :keys) x)))
               :py/None)
           :py/None)))]
    [py/set-fill
     (fn [s xs i]
       (let [x (get xs i :py/stop)]
         (if (= x :py/stop) s (do (py/set-add s x) (py/set-fill s xs (+ i 1))))))]
    [py/set-from (fn [xs] (py/set-fill (py/set-new) xs 0))]
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
    [py/reindex
     (fn [ks i acc]
       (let [k (get ks i :py/stop)]
         (if (= k :py/stop) acc (py/reindex ks (+ i 1) (assoc acc (py/key k) i)))))]
    [py/dict-del-quiet
     ;; remove key k if present, keeping insertion order
     (fn [d k]
       (let [c (cell/get d)
             slot (get (get c :index) (py/key k) :py/missing)]
         (if (= slot :py/missing)
           :py/None
           (let [ks (get c :keys)
                 vs (get c :vals)
                 n (data/count ks)
                 ks2 (data/into (data/subvec ks 0 slot) (data/subvec ks (+ slot 1) n))]
             (do (cell/set! d
                            (assoc (assoc (assoc c :keys ks2)
                                          :vals
                                          (data/into (data/subvec vs 0 slot)
                                                     (data/subvec vs (+ slot 1) n)))
                                   :index
                                   (py/reindex ks2 0 {})))
                 :py/None)))))]
    [py/global-del-quiet (fn [g n] (py/dict-del-quiet g n))]
    [py/delattr-quiet
     (fn [o name]
       (let [c (cell/get o)]
         (do (cell/set! o (assoc c :attrs (data/dissoc (get c :attrs) name)))
             :py/None)))]
    [py/dict-fill
     (fn [d pairs i]
       (let [pair (get pairs i :py/stop)]
         (if (= pair :py/stop)
           d
           (do (py/dict-set d (get pair 0) (get pair 1))
               (py/dict-fill d pairs (+ i 1))))))]
    [py/dict-from (fn [pairs] (py/dict-fill (py/dict-new) pairs 0))]

    ;; ---------------------------------------------------------- slices
    [py/slice
     (fn [a b c]
       (assoc (assoc (assoc (assoc {} :py/type :slice) :start a) :stop b) :step c))]
    [py/slice-int
     (fn [x]
       (if (py/int? x)
         (py/num x)
         (py/type-error {:py/str "slice indices must be integers or None"})))]
    [py/slice-bound
     ;; one bound clamped as slice.indices does
     (fn [x n lower upper default]
       (if (= x :py/None)
         default
         (let [i (py/slice-int x)]
           (if (< i 0)
             (let [j (+ i n)] (if (< j lower) lower j))
             (if (> i upper) upper i)))))]
    [py/slice-walk
     (fn [i stop step acc]
       (if (if (< 0 step) (< i stop) (> i stop))
         (py/slice-walk (+ i step) stop step (conj acc i))
         acc))]
    [py/slice-positions
     ;; the indices a slice selects from a sequence of length n
     (fn [s n]
       (let [step (if (= (get s :step) :py/None) 1 (py/slice-int (get s :step)))]
         (if (= step 0)
           (py/raise-new py.b/ValueError {:py/str "slice step cannot be zero"})
           (let [lower (if (< step 0) -1 0)
                 upper (if (< step 0) (- n 1) n)
                 start (py/slice-bound (get s :start) n lower upper
                                       (if (< step 0) upper lower))
                 stop (py/slice-bound (get s :stop) n lower upper
                                      (if (< step 0) lower upper))]
             (py/slice-walk start stop step [])))))]
    [py/pick
     (fn [xs idxs i acc]
       (let [k (get idxs i :py/stop)]
         (if (= k :py/stop) acc (py/pick xs idxs (+ i 1) (conj acc (get xs k))))))]
    [py/slice-of
     (fn [items s] (py/pick items (py/slice-positions s (data/count items)) 0 []))]
    [py/set-slice
     ;; list[a:b] = iterable, step absent or 1 (the lowering refuses others)
     (fn [l s x]
       (if (if (= (get s :step) :py/None) true (= (get s :step) 1))
         (let [c (cell/get l)
               items (get c :items)
               n (data/count items)
               start (py/slice-bound (get s :start) n 0 n 0)
               stop0 (py/slice-bound (get s :stop) n 0 n n)
               stop (if (< stop0 start) start stop0)]
           (do (cell/set! l
                          (assoc c
                                 :items
                                 (data/into (data/into (data/subvec items 0 start)
                                                       (py/to-vector x))
                                            (data/subvec items stop n))))
               :py/None))
         (py/raise-new py.b/NotImplementedError
                       {:py/str "extended slice assignment is not supported"})))]

    ;; ---------------------------------------------------------- subscripts
    [py/getitem
     (fn [o k]
       (let [t (py/kind o)
             slice? (= (get k :py/type) :slice)]
         (if (py/str? o)
           (let [cps (data/str->code-points (get o :py/str))]
             (if slice?
               (py/str (data/code-points->str (py/slice-of cps k)))
               (py/str (data/code-points->str
                         (py/conj [] (get cps (py/index k (data/count cps))))))))
           (if (= t :list)
             (let [items (get (cell/get o) :items)]
               (if slice?
                 (py/list (py/slice-of items k))
                 (get items (py/index k (data/count items)))))
             (if (= t :tuple)
               (if slice?
                 (py/tuple (py/slice-of (get o :items) k))
                 (get (get o :items) (py/index k (data/count (get o :items)))))
               (if (= t :dict)
                 (let [c (cell/get o)
                       slot (get (get c :index) (py/key k) :py/missing)]
                   (if (= slot :py/missing)
                     (py/raise-new py.b/KeyError k)
                     (get (get c :vals) slot)))
                 (py/type-error {:py/str "object is not subscriptable"})))))))]
    [py/setitem
     (fn [o k x]
       (let [t (py/kind o)]
         (if (if (= t :list) (= (get k :py/type) :slice) false)
           (py/set-slice o k x)
           (if (= t :list)
             (let [c (cell/get o)]
               (do (cell/set! o
                              (assoc c
                                     :items
                                     (assoc (get c :items)
                                            (py/index k (data/count (get c :items)))
                                            x)))
                   :py/None))
             (if (= t :dict)
               (do (py/dict-set o k x) :py/None)
               (py/type-error {:py/str "object does not support item assignment"}))))))]

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
     ;; O(1): (|stop - start| - 1) // |step| + 1 when non-empty
     (fn [r _i]
       (let [start (get r :start)
             stop (get r :stop)
             step (get r :step)]
         (if (< 0 step)
           (if (< start stop)
             (+ 1 (py/int-floordiv (- (py/checked-sub stop start) 1) step))
             0)
           (if (< stop start)
             (+ 1 (py/int-floordiv (- (py/checked-sub start stop) 1) (- 0 step)))
             0))))]
    [py/iter-at
     (fn [it i]
       (if (py/cell? it)
         (let [c (cell/get it)
               t (get c :py/type)]
           (if (= t :list)
             (get (get c :items) i :py/stop)
             (if (if (= t :dict) true (= t :set))
               (get (get c :keys) i :py/stop)
               (py/type-error {:py/str "object is not iterable"}))))
         (if (= (get it :py/type) :range)
           (py/range-at it i)
           (if (= (get it :py/type) :tuple)
             (get (get it :items) i :py/stop)
             (if (= (get it :py/type) :keys-iter)
               (let [keys (get (cell/get (get it :obj)) :keys)]
                 (if (= (data/count keys) (get it :size))
                   (get keys i :py/stop)
                   (py/raise-new py.b/RuntimeError
                                 (py/str (data/str-concat
                                           (get it :what)
                                           " changed size during iteration")))))
               (py/type-error {:py/str "object is not iterable"}))))))]
    [py/chars
     (fn [cps i acc]
       (let [cp (get cps i :py/stop)]
         (if (= cp :py/stop)
           acc
           (py/chars cps (+ i 1) (conj acc (py/str (data/code-points->str (conj [] cp))))))))]
    [py/iterable
     ;; what a loop walks by index: a string becomes the tuple of its
     ;; characters, decoded once; anything else is walked as it is
     ;; a dict or set is walked through its keys with the size it had when
     ;; the loop began, so growing or shrinking it during the loop is a
     ;; RuntimeError, as in CPython; a list is walked live
     (fn [x]
       (if (py/str? x)
         (py/tuple (py/chars (data/str->code-points (get x :py/str)) 0 []))
         (let [t (py/content-type x)]
           (if (if (= t :dict) true (= t :set))
             (assoc (assoc (assoc (assoc {} :py/type :keys-iter) :obj x)
                           :size (data/count (get (cell/get x) :keys)))
                    :what (if (= t :dict) "dictionary" "Set"))
             x))))]
    [py/collect
     (fn [it i acc]
       (let [x (py/iter-at it i)]
         (if (= x :py/stop) acc (py/collect it (+ i 1) (conj acc x)))))]
    [py/to-vector (fn [x] (py/collect (py/iterable x) 0 []))]
    [py/for-each-at
     (fn [it f i]
       (let [x (py/iter-at it i)]
         (if (= x :py/stop) :py/None (do (f x) (py/for-each-at it f (+ i 1))))))]
    [py/for-each (fn [x f] (py/for-each-at (py/iterable x) f 0))]
    [py/unpack
     ;; exactly n items, else ValueError
     (fn [x n]
       (let [xs (py/to-vector x)
             k (data/count xs)]
         (if (< k n)
           (py/raise-new py.b/ValueError {:py/str "not enough values to unpack"})
           (if (< n k)
             (py/raise-new py.b/ValueError {:py/str "too many values to unpack"})
             xs))))]
    [py/unpack-star
     ;; `before` items, a list of the middle, `after` items
     (fn [x before after]
       (let [xs (py/to-vector x)
             k (data/count xs)]
         (if (< k (+ before after))
           (py/raise-new py.b/ValueError {:py/str "not enough values to unpack"})
           (data/into (py/conj (data/subvec xs 0 before)
                               (py/list (data/subvec xs before (- k after))))
                      (data/subvec xs (- k after) k)))))]
    [py/len
     (fn [o]
       (if (py/str? o)
         (data/str-length (get o :py/str))
         (if (py/cell? o)
           (let [c (cell/get o)
                 t (get c :py/type)]
             (if (= t :list)
               (data/count (get c :items))
               (if (if (= t :dict) true (= t :set))
                 (data/count (get c :keys))
                 (py/type-error {:py/str "object has no len()"}))))
           (if (= (get o :py/type) :range)
             (py/range-len o 0)
             (if (= (get o :py/type) :tuple)
               (data/count (get o :items))
               (py/type-error {:py/str "object has no len()"}))))))]
    [py/sum-from
     (fn [xs i acc]
       (let [x (get xs i :py/stop)]
         (if (= x :py/stop) acc (py/sum-from xs (+ i 1) (py/add acc x)))))]
    [py/any-of
     (fn [xs i]
       (let [x (get xs i :py/stop)]
         (if (= x :py/stop) false (if (py/truthy x) true (py/any-of xs (+ i 1))))))]
    [py/all-of
     (fn [xs i]
       (let [x (get xs i :py/stop)]
         (if (= x :py/stop) true (if (py/truthy x) (py/all-of xs (+ i 1)) false))))]
    [py/dict-items
     ;; a list of (key, value) tuples, a snapshot rather than a live view
     (fn [d]
       (let [c (cell/get d)]
         (py/list (py/zip-pairs (get c :keys) (get c :vals) 0 []))))]
    [py/zip-pairs
     (fn [ks vs i acc]
       (let [k (get ks i :py/stop)]
         (if (= k :py/stop)
           acc
           (py/zip-pairs ks vs (+ i 1)
                         (conj acc (py/tuple (conj (conj [] k) (get vs i))))))))]
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
                   (if (= t :set)
                     (assoc {} :py/set (py/snapshot-all (get c :keys) 0 []))
                     :py/object))))))))]
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
     ;; the body receives the module namespace dict and the module's
     ;; `globals` builtin, a function object returning that dict
     (fn [body]
       (do (cell/set! py.rt/out [])
           (cell/set! py.rt/handlers nil)
           (let [g (py/dict-new)
                 gf (py/make-function "globals" {:params [], :no-kw true} [] []
                                      (fn [args] g))
                 exc (py/try (fn [] (do (body g gf) :py/None))
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
   ["RuntimeError" 'py.b/RuntimeError 'py.b/Exception]
   ["NotImplementedError" 'py.b/NotImplementedError 'py.b/RuntimeError]
   ["OverflowError" 'py.b/OverflowError 'py.b/ArithmeticError]])


(def ^:private builtin-function-definitions
  "Builtin functions are function objects like any guest function, so they
   can be passed, stored, called with keywords and checked for arity
   through `py/call-kw`."
  '[[py.b/len
     (py/make-function "len" {:params ["obj"], :no-kw true} [] []
                       (fn [args] (py/len (py/arg args 0))))]
    [py.b/isinstance
     (py/make-function "isinstance" {:params ["obj" "cls"], :no-kw true} [] []
                       (fn [args] (py/isinstance (py/arg args 0) (py/arg args 1))))]
    [py.b/print
     (py/make-function "print" {:params [], :star? true, :no-kw true} [] []
                       (fn [args] (py/print (get (py/arg args 0) :items))))]
    [py.b/range
     (py/make-function "range" {:params ["start"], :star? true, :no-kw true} [] []
                       (fn [args] (py/range-args (py/arg args 0) (py/arg args 1))))]
    [py.b/list
     (py/make-function "list" {:params ["iterable"], :no-kw true} [(py/tuple [])] []
                       (fn [args] (py/list (py/to-vector (py/arg args 0)))))]
    [py.b/tuple
     (py/make-function "tuple" {:params ["iterable"], :no-kw true} [(py/tuple [])] []
                       (fn [args] (py/tuple (py/to-vector (py/arg args 0)))))]
    [py.b/set
     (py/make-function "set" {:params ["iterable"], :no-kw true} [(py/tuple [])] []
                       (fn [args] (py/set-from (py/to-vector (py/arg args 0)))))]
    [py.b/sum
     (py/make-function "sum" {:params ["iterable" "start"]} [0] []
                       (fn [args]
                         (py/sum-from (py/to-vector (py/arg args 0)) 0
                                      (py/arg args 1))))]
    [py.b/any
     (py/make-function "any" {:params ["iterable"], :no-kw true} [] []
                       (fn [args] (py/any-of (py/to-vector (py/arg args 0)) 0)))]
    [py.b/all
     (py/make-function "all" {:params ["iterable"], :no-kw true} [] []
                       (fn [args] (py/all-of (py/to-vector (py/arg args 0)) 0)))]
    [py.b/list-append
     (py/make-function "append" {:params ["self" "x"], :no-kw true} [] []
                       (fn [args] (py/list-append (py/arg args 0) (py/arg args 1))))]
    [py.b/set-add
     (py/make-function "add" {:params ["self" "x"], :no-kw true} [] []
                       (fn [args] (py/set-add (py/arg args 0) (py/arg args 1))))]
    [py.b/dict-items
     (py/make-function "items" {:params ["self"], :no-kw true} [] []
                       (fn [args] (py/dict-items (py/arg args 0))))]
    [py.b/dict-keys
     (py/make-function "keys" {:params ["self"], :no-kw true} [] []
                       (fn [args] (py/list (get (cell/get (py/arg args 0)) :keys))))]
    [py.b/dict-values
     (py/make-function "values" {:params ["self"], :no-kw true} [] []
                       (fn [args] (py/list (get (cell/get (py/arg args 0)) :vals))))]
    [py.b/dict-get
     (py/make-function "get" {:params ["self" "key" "default"], :no-kw true} [:py/None] []
                       (fn [args]
                         (if (py/dict-has? (py/arg args 0) (py/arg args 1))
                           (py/getitem (py/arg args 0) (py/arg args 1))
                           (py/arg args 2))))]])


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
         "range" 'py.b/range,
         "list" 'py.b/list,
         "tuple" 'py.b/tuple,
         "set" 'py.b/set,
         "sum" 'py.b/sum,
         "any" 'py.b/any,
         "all" 'py.b/all}
        (map (fn [[nm key _]] [nm key]))
        builtin-classes))
