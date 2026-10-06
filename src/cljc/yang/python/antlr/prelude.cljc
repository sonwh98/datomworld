(ns yang.python.antlr.prelude
  "The Python runtime profile, written as Universal AST
   (docs/design/yang.antlr.md §8.1, §8.2, §9.3). Python semantics for
   operators, truthiness, equality, objects, exceptions and escapes live
   here as guest code, not in Clojure: the lowering only emits applications
   of these names.

   Value encoding:
     int             host integer (untagged)
     float           {:py/float x}: tagged on every host, because JS cannot
                     tell 2 from 2.0 (owner decision 3); x is float64
                     content (`data/float64`), never a bare host number,
                     so 2.0 has one address on every host. Arithmetic
                     unwraps with `data/float-value` and computes on bare
                     host numbers. The prelude holds no integral float
                     literal (JS reads 1.0 as 1), and no float literal
                     reaches host arithmetic except through
                     `data/float-value`, which also bridges a float64
                     carrier decoded from a row back to a host number:
                     1.0 is written (data/float-value 1)
     bool            host true / false (numeric as 1 / 0)
     None            :py/None
     str             {:py/str \"...\"}
     list            a cell holding {:py/type :list :items [...]}
     dict            a cell holding {:py/type :dict :index {nkey slot}
                     :keys [...] :vals [...]}: insertion order is the keys
                     vector, never a host map's order; keys are normalized
                     so 1, 1.0 and True are one key (see `py/key`)
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
     generator       a cell holding {:py/type :generator :name s :state st}
                     and the slots of its state (see `py/gen-switch`)
     iterator        a cell holding {:py/type :iterator :src s :i n}: what
                     iter() makes of a sequence, advancing `py/iter-at` over
                     src once per next; src None once exhausted
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

   Runtime state is three cells, reached through the store (`yin/def` is
   reserved for the prelude and builtins):
     py.rt/ctx       the dynamic context, one record
                     {:handlers :depth :base :frame}:
                     :handlers  the handler stack: frames
                                [kind payload rest n depth]; a :handler
                                frame holds the continuation `raise`
                                delivers to, a :finally frame a thunk every
                                exit through it runs (raise passes the
                                exception, escapes and normal completion
                                pass None), a :generator frame the bottom
                                of a running generator's own stack (raise
                                passes it the exception); nil when empty
                     :depth     the call depth within the current
                                activation: the safepoint hook prelude
                                counts it (RecursionError); the base
                                prelude only saves and restores it
                     :base      the absolute depth the activation runs
                                on: 0 outside generators; every crossing
                                into a generator sets it to the
                                resumer's absolute depth plus one (the
                                generator's own frame), so depth always
                                measures the current continuation
                     :frame     the current frame; nil, reserved for
                                tracing
     py.rt/limit     the recursion limit, 1000 by default; outside the
                     escape-restored record, so a change persists. A
                     generator start or resume is admitted only while its
                     frame fits; the hook prelude checks function entry
                     against it and sets it
     py.rt/out       the values `print` collected, one vector per call

   Escapes and handlers tell the first pass through a capture point from a
   re-entry with a flag cell allocated before the capture (`:first`, then
   `:re-entered`): heap writes survive continuation invocation, so the flag
   answers without inspecting the continuation's representation. An escape
   unwinds the handler stack to the frame it captured, running finally
   thunks on the way, and restores the dynamic context saved at capture
   with the current `:base` left alone: an exception may unwind many
   calls, so the call depth is restored, never decremented, and it is
   restored relative to the activation it runs in. Only crossings rebase.

   Integer `//`, `%`, `**`, bitwise operators and float floor have no
   portable host primitive; they are exact algorithms here over + - * <
   (doubling division, binary-descent floor, two's-complement recursion),
   valid within 2^53.

   Numeric dict and set keys and `hash()` are exact (C3 rulings 6 and 7):
   a finite number keys as its reduced rational in lowercase hex text,
   to which no digit limit applies, and hashes modulo P = 2^61 - 1 on
   every host, both computed through the `integer` module's kernels. A
   float key or hash can need integers of 1075 bits (2^-1074), so the
   composition's `integer` limits must admit at least that.

   The `integer` module (version 3) answers a limit breach as a reason,
   not a result, and `py/int-result` wraps every call that can breach:
   `:yin.vm.integer/bit-limit` raises MemoryError, as CPython 3.9.6 does
   when an integer cannot be allocated, and `:yin.vm.integer/digit-limit`
   raises ValueError. CPython 3.9.6 has no digit limit (it arrived in
   3.9.14 and 3.11), so the digit limit is this support profile's
   restriction, not a 3.9.6 match. Float overflow raises OverflowError
   with \"int too large to convert to float\". The guest
   message is the prelude's, never host text. Every other refusal stays
   a host failure of the run: the prelude checks its causes before the
   call, so reaching one is a prelude defect, not a guest error.

   Host names the prelude depends on and does not define: `host-names`
   (the cell module from yin.vm.module, the integer module from
   yin.vm.integer, the rest from yin.vm.data)."
  (:require
    [yang.python.antlr.uast :as u]
    [yin.vm.integer :as integer]))


(def host-names
  "Module exports the prelude calls."
  '#{cell/new cell/get cell/set! data/count data/into data/subvec
     data/number? data/dissoc data/str-concat data/str-length
     data/str-index-of data/str->code-points data/code-points->str
     data/float64 data/float-value data/content=
     integer/add integer/sub integer/neg integer/mul integer/compare
     integer/to-float integer/compare-float integer/true-div
     integer/pow integer/shift-right integer/bit-and
     integer/floor-div-mod integer/shift-left integer/format integer/parse})


(def ^:private core-definitions
  "Pure definitions: lambdas only, so defining them runs nothing."
  '[;; ---------------------------------------------------------------- kinds
    [py/cell? (fn [x] (= (get x :type) :cell-ref))]
    [py/str? (fn [x] (not (nil? (get x :py/str))))]
    [py/str (fn [s] (assoc {} :py/str s))]
    [py/float? (fn [x] (not (nil? (get x :py/float))))]
    [py/float (fn [x] (assoc {} :py/float (data/float64 x)))]
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
       (if (= x true)
         1
         (if (= x false)
           0
           (if (py/float? x) (data/float-value (get x :py/float)) x))))]
    [py/zero?
     (fn [x]
       (let [v (py/num x)] (if (= v 0) true (= v (data/float-value 0)))))]
    [py/content-type (fn [x] (if (py/cell? x) (get (cell/get x) :py/type) nil))]
    [py/function? (fn [x] (= (py/content-type x) :function))]

    ;; ---------------------------------------------------------- escapes
    ;; The dynamic context is one record in one cell, {:handlers :depth
    ;; :base :frame}: every capture point saves it and every escape
    ;; restores it with the current :base left alone (`py/restore!`), so
    ;; an escape inside a generator restores its depth relative to the
    ;; activation it runs in; only crossings set :base. The handler stack
    ;; is a chain of frames [kind payload rest n depth]: :handler frames
    ;; hold the continuation `raise` delivers to, :finally frames hold a
    ;; thunk of one argument (the exception, or None) that every exit
    ;; through them runs. n, the frame count, makes "unwind to here" an
    ;; integer comparison; depth is the (activation-relative) call depth
    ;; the frame was pushed at, which its thunk runs at.
    [py/ctx
     (fn [handlers depth base frame]
       (assoc (assoc (assoc (assoc {} :handlers handlers) :depth depth)
                     :base base)
              :frame frame))]
    [py/abs-depth (fn [c] (+ (get c :base) (get c :depth)))]
    [py/restore!
     (fn [saved]
       (cell/set! py.rt/ctx
                  (assoc saved :base (get (cell/get py.rt/ctx) :base))))]
    [py/handlers (fn [] (get (cell/get py.rt/ctx) :handlers))]
    [py/set-handlers!
     (fn [hs] (cell/set! py.rt/ctx (assoc (cell/get py.rt/ctx) :handlers hs)))]
    [py/frame-depth (fn [hs] (if (nil? hs) 0 (get hs 3)))]
    [py/frame
     (fn [kind payload rest]
       (py/conj (py/conj (py/conj (py/conj (py/conj [] kind) payload) rest)
                         (+ 1 (py/frame-depth rest)))
                (get (cell/get py.rt/ctx) :depth)))]
    [py/pop-frame
     ;; the handler stack below frame hs, at the call depth hs was pushed at
     (fn [hs]
       (cell/set! py.rt/ctx
                  (assoc (assoc (cell/get py.rt/ctx) :handlers (get hs 2))
                         :depth (get hs 4))))]
    [py/raise
     (fn [e]
       (let [hs (py/handlers)]
         (if (nil? hs)
           (:py/no-handler e)
           (if (= (get hs 0) :finally)
             (do (py/pop-frame hs)
                 ((get hs 1) e)
                 (py/raise e))
             ((get hs 1) e)))))]
    [py/unwind-to
     ;; pop frames above frame count n, running each finally thunk with None
     (fn [n]
       (let [hs (py/handlers)]
         (if (nil? hs)
           :py/None
           (if (<= (get hs 3) n)
             :py/None
             (do (py/pop-frame hs)
                 (if (= (get hs 0) :finally) ((get hs 1) :py/None) :py/None)
                 (py/unwind-to n))))))]
    [py/try
     (fn [body handler orelse]
       (let [saved (cell/get py.rt/ctx)
             flag (cell/new :first)]
         ((fn [r]
            (if (= (cell/get flag) :first)
              (do (cell/set! flag :re-entered)
                  (py/set-handlers! (py/frame :handler r (get saved :handlers)))
                  (body)
                  (py/restore! saved)
                  (orelse))
              (do (py/restore! saved)
                  (handler r))))
          (%capture))))]
    [py/try-finally
     ;; normal exit runs `fin` here; raise and escapes run it as they
     ;; unwind through the :finally frame
     (fn [body fin]
       (let [saved (cell/get py.rt/ctx)]
         (do (py/set-handlers! (py/frame :finally fin (get saved :handlers)))
             (let [v (body)]
               (do (py/restore! saved)
                   (fin :py/None)
                   v)))))]
    [py/call-ec
     (fn [f]
       (let [saved (cell/get py.rt/ctx)
             flag (cell/new :first)]
         ((fn [r]
            (if (= (cell/get flag) :first)
              (do (cell/set! flag :re-entered)
                  (f (fn [x]
                       (do (py/unwind-to (py/frame-depth (get saved :handlers)))
                           (py/restore! saved)
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

    ;; ---------------------------------------------------------- generators
    ;; A generator is a cell holding {:py/type :generator :name s :state st}
    ;; and the slots of its state, nothing else (every transition rebuilds
    ;; the content, so no slot outlives its state):
    ;;   :created    :body      (fn [g] ...), the function body
    ;;   :suspended  :resume    the continuation captured at the yield
    ;;               :ctx       the generator's own dynamic context
    ;;   :running    :return    the active switch's continuation
    ;;               :caller-ctx the switching caller's dynamic context
    ;;   :closed     nothing
    ;; The generator owns its dynamic context (handler stack and its
    ;; depth relative to :base), so every snapshot the C1 forms take inside
    ;; the body is of that record and resumes from any caller depth. Every
    ;; crossing in sets :base to the resumer's absolute depth plus one, the
    ;; generator's own frame, so depth measures the current continuation;
    ;; every way back restores the caller's record whole, so no depth leaks
    ;; across the crossing in either direction. A start or resume whose
    ;; generator frame would exceed `py.rt/limit` is refused with
    ;; RecursionError before anything is written. `py/gen-switch` sends
    ;; [:send v] or [:throw e] and answers [:yield v], [:return v] or
    ;; [:raise e]; it never raises from the generator's side. Each crossing
    ;; restores the receiving side's dynamic context, then invokes the
    ;; receiving continuation once.
    [py/gen-content
     (fn [name state]
       (assoc (assoc (assoc {} :py/type :generator) :name name) :state state))]
    [py/make-generator
     (fn [name body] (cell/new (assoc (py/gen-content name :created) :body body)))]
    [py/outcome (fn [tag v] (py/conj (py/conj [] tag) v))]
    [py/gen-switch
     (fn [g msg]
       (let [c (cell/get g)
             st (get c :state)]
         (if (= st :running)
           (py/raise-new py.b/ValueError {:py/str "generator already executing"})
           (if (= st :closed)
             (if (= (get msg 0) :throw)
               (py/outcome :raise (get msg 1))
               (py/outcome :return :py/None))
             (if (if (= st :created) (= (get msg 0) :throw) false)
               ;; thrown into a generator that never ran: it closes, and
               ;; the exception is the caller's
               (do (cell/set! g (py/gen-content (get c :name) :closed))
                   (py/outcome :raise (get msg 1)))
               ;; admission: the generator's frame must fit under the limit,
               ;; checked before anything is written, so a refusal raises on
               ;; the caller's stack and leaves the generator as it was
               (if (< (cell/get py.rt/limit)
                      (+ 1 (py/abs-depth (cell/get py.rt/ctx))))
                 (py/raise-new py.b/RecursionError
                               {:py/str "maximum recursion depth exceeded"})
                 (py/gen-enter g c st msg)))))))]
    [py/gen-enter
     ;; the crossing into a generator that start or resume admitted
     (fn [g c st msg]
       (let [flag (cell/new :first)]
         ((fn [r]
            (if (= (cell/get flag) :first)
              (do (cell/set! flag :re-entered)
                  (cell/set! g (assoc (assoc (py/gen-content (get c :name) :running)
                                             :return r)
                                      :caller-ctx (cell/get py.rt/ctx)))
                  (if (= st :created)
                    (py/gen-start g (get c :body))
                    (do (cell/set! py.rt/ctx
                                   (assoc (get c :ctx) :base (py/crossing-base g)))
                        ((get c :resume) msg))))
              r))
          (%capture))))]
    [py/crossing-base
     ;; a running generator's :base: its resumer's absolute depth, plus one
     ;; for the generator's own frame
     (fn [g] (+ 1 (py/abs-depth (get (cell/get g) :caller-ctx))))]
    [py/gen-start
     ;; a fresh handler stack whose only frame is the boundary: an exception
     ;; no handler in the body catches reaches it and leaves the generator
     (fn [g body]
       (do (cell/set! py.rt/ctx (py/ctx nil 0 (py/crossing-base g) nil))
           (py/set-handlers! (py/frame :generator (fn [e] (py/gen-fail g e)) nil))
           (py/gen-exit g (py/outcome :return (body g)))))]
    [py/gen-exit
     ;; the single way out of a finishing generator: closed, the caller's
     ;; dynamic context back, the outcome delivered to the active switch
     (fn [g outcome]
       (let [c (cell/get g)]
         (do (cell/set! g (py/gen-content (get c :name) :closed))
             (cell/set! py.rt/ctx (get c :caller-ctx))
             ((get c :return) outcome))))]
    [py/gen-fail
     ;; PEP 479: a StopIteration escaping the body is a RuntimeError
     (fn [g e]
       (py/gen-exit g (py/outcome :raise
                                  (if (py/subclass? (py/type-of e) py.b/StopIteration)
                                    (py/make-exc py.b/RuntimeError
                                                 {:py/str "generator raised StopIteration"})
                                    e))))]
    [py/yield-raw
     ;; suspend, answering the message the next switch sends
     (fn [g v]
       (let [flag (cell/new :first)]
         ((fn [r]
            (if (= (cell/get flag) :first)
              (let [c (cell/get g)]
                (do (cell/set! flag :re-entered)
                    (cell/set! g (assoc (assoc (py/gen-content (get c :name) :suspended)
                                               :resume r)
                                        :ctx (cell/get py.rt/ctx)))
                    (cell/set! py.rt/ctx (get c :caller-ctx))
                    ((get c :return) (py/outcome :yield v))))
              r))
          (%capture))))]
    [py/yield
     ;; `yield v`: the value sent, or the exception thrown raised here, on
     ;; the generator's own stack
     (fn [g v]
       (let [m (py/yield-raw g v)]
         (if (= (get m 0) :throw) (py/raise (get m 1)) (get m 1))))]
    [py/gen-result
     ;; a switch's outcome at a protocol boundary, in the caller: the value
     ;; yielded, the exception re-raised, completion as StopIteration(value)
     ;; or, when one is given, `default`
     (fn [o default]
       (let [tag (get o 0)
             v (get o 1)]
         (if (= tag :yield)
           v
           (if (= tag :raise)
             (py/raise v)
             (if (= default :py/missing)
               (py/raise (py/call py.b/StopIteration
                                  (if (= v :py/None) [] (py/conj [] v))))
               default)))))]
    [py/gen-step
     ;; one advancement for a loop: the value, or :py/stop at completion
     (fn [g]
       (let [o (py/gen-switch g (py/outcome :send :py/None))
             tag (get o 0)]
         (if (= tag :yield) (get o 1) (if (= tag :return) :py/stop (py/raise (get o 1))))))]
    [py/gen-send
     (fn [g v]
       (if (if (= (get (cell/get g) :state) :created) (not (= v :py/None)) false)
         (py/type-error {:py/str "can't send non-None value to a just-started generator"})
         (py/gen-result (py/gen-switch g (py/outcome :send v)) :py/missing)))]
    [py/gen-throw
     ;; `throw(typ, val)`: raised at the yield site on the generator's own
     ;; stack; a generator that never ran closes and the caller raises it
     (fn [g typ val tb]
       (if (= tb :py/None)
         (py/gen-result
           (py/gen-switch g (py/outcome :throw (py/throw-exc typ val)))
           :py/missing)
         (py/type-error
           {:py/str "throw() third argument must be a traceback object"})))]
    [py/throw-exc
     ;; the exception `throw` raises: a class is called with val (a tuple
     ;; spread, None no argument) unless val is already an instance of it;
     ;; an instance takes no separate value. A class not deriving from
     ;; BaseException is a TypeError before anything is called.
     (fn [typ val]
       (if (= (py/content-type typ) :class)
         (if (py/subclass? typ py.b/BaseException)
           (if (if (= val :py/None) true (py/subclass? (py/type-of val) typ))
             (py/as-exception (if (= val :py/None) typ val))
             (py/as-exception
               (py/call typ (if (= (get val :py/type) :tuple)
                              (get val :items)
                              (py/conj [] val)))))
           (py/type-error
             (py/str (data/str-concat
                       "exceptions must be classes or instances deriving "
                       "from BaseException, not type"))))
         (if (= val :py/None)
           (py/as-exception typ)
           (py/type-error
             {:py/str "instance exception may not have a separate value"}))))]
    [py/gen-close
     ;; GeneratorExit thrown in: completion, or GeneratorExit raised back,
     ;; is None; any other raise propagates; a yield is a RuntimeError and
     ;; the generator stays suspended
     (fn [g]
       (let [o (py/gen-switch
                 g (py/outcome :throw (py/call py.b/GeneratorExit [])))
             tag (get o 0)]
         (if (= tag :yield)
           (py/raise-new py.b/RuntimeError
                         {:py/str "generator ignored GeneratorExit"})
           (if (= tag :raise)
             (if (py/subclass? (py/type-of (get o 1)) py.b/GeneratorExit)
               :py/None
               (py/raise (get o 1)))
             :py/None))))]
    [py/gen-attr
     (fn [g name]
       (if (= name "send")
         (py/method g py.b/gen-send)
         (if (= name "__next__")
           (py/method g py.b/gen-next)
           (if (= name "throw")
             (py/method g py.b/gen-throw)
             (if (= name "close")
               (py/method g py.b/gen-close)
               (if (= name "__iter__")
                 (py/method g py.b/gen-iter)
                 (py/attr-error name)))))))]
    [py/next
     (fn [it default]
       (let [t (py/content-type it)]
         (if (= t :generator)
           (py/gen-result (py/gen-switch it (py/outcome :send :py/None)) default)
           (if (= t :iterator)
             (py/gen-result (py/iter-outcome it) default)
             (py/type-error {:py/str "object is not an iterator"})))))]
    [py/yield-from
     ;; `yield from x` (PEP 380): each message the outer generator receives
     ;; goes to the delegate, each value the delegate yields is the outer's;
     ;; the delegate's return value is the expression's value and its raise
     ;; is raised here, on the outer generator's stack
     (fn [g x] (py/delegate g (py/iter x) (py/outcome :send :py/None)))]
    [py/delegate
     ;; the delegation loop, a tail call per item
     (fn [g d msg]
       (let [o (py/delegate-step d msg)
             tag (get o 0)]
         (if (= tag :yield)
           (py/delegate g d (py/yield-raw g (get o 1)))
           (if (= tag :return) (get o 1) (py/raise (get o 1))))))]
    [py/delegate-step
     ;; one message to the delegate, answered as an outcome. GeneratorExit
     ;; closes a generator delegate and is then raised in the outer; any
     ;; other throw is the delegate's throw. A sequence iterator has no
     ;; send, throw or close: None advances it, another value sent is an
     ;; AttributeError and a throw is raised in the outer as is.
     (fn [d msg]
       (let [gen? (= (py/content-type d) :generator)
             v (get msg 1)]
         (if (= (get msg 0) :throw)
           (if (py/subclass? (py/type-of v) py.b/GeneratorExit)
             (do (if gen? (py/gen-close d) :py/None)
                 (py/outcome :raise v))
             (if gen? (py/stop-as-return (py/gen-switch d msg)) (py/outcome :raise v)))
           (if gen?
             (py/gen-switch d msg)
             (if (= v :py/None) (py/iter-outcome d) (py/attr-error "send"))))))]
    [py/stop-as-return
     ;; a StopIteration a generator delegate raises back from a throw is the
     ;; delegation's completion with its value, as CPython's _gen_throw
     ;; takes it. Only the thrown exception itself comes back this way (a
     ;; closed delegate passes it through); one escaping the delegate's body
     ;; is already PEP 479's RuntimeError.
     (fn [o]
       (if (if (= (get o 0) :raise)
             (py/subclass? (py/type-of (get o 1)) py.b/StopIteration)
             false)
         (py/outcome :return (get (get (cell/get (get o 1)) :attrs) "value" :py/None))
         o))]
    [py/stop-iteration-class
     ;; StopIteration(*args): args as given, value the first or None
     (fn [cls]
       (do (py/setattr cls "__init__"
                       (py/make-function
                         "__init__" {:params ["self"], :star? true, :no-kw true} [] []
                         (fn [args]
                           (let [e (py/arg args 0)
                                 xs (py/arg args 1)]
                             (do (py/setattr e "args" xs)
                                 (py/setattr e "value" (get (get xs :items) 0 :py/None)))))))
           cls))]

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
                 ;; the builtin exception constructor takes positional
                 ;; arguments only; an explicit __init__ binds keywords
                 (if (< 0 (data/count kwargs))
                   (py/type-error
                     (py/str (data/str-concat (get (cell/get cls) :name)
                                              "() takes no keyword arguments")))
                   (py/setattr inst "args" (py/tuple args)))
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
                       (if (= t :generator)
                         (py/gen-attr o name)
                         (if (= t :iterator)
                           (if (= name "__next__")
                             (py/method o py.b/gen-next)
                             (if (= name "__iter__")
                               (py/method o py.b/gen-iter)
                               (py/attr-error name)))
                           (py/attr-error name))))))))))
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
    ;; Exact integer arithmetic goes through the module's guarded kernels.
    ;; The bounded helpers below remain for the pending S3-B/S3-C slices.
    [py/overflow
     (fn []
       (py/raise-new py.b/OverflowError
                     {:py/str "integer result outside the supported range (2**53)"}))]
    [py/checked-add
     (fn [a b]
       (if (< 0 a)
         (if (< 0 b)
           (if (< (- (* 2 4503599627370496) b) a) (py/overflow) (+ a b))
           (+ a b))
         (if (< b 0)
           (if (< a (- (* -2 4503599627370496) b)) (py/overflow) (+ a b))
           (+ a b))))]
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
             (if (< (get (py/divmod-pos (* 2 4503599627370496) x) 0) y)
               (py/overflow)
               (* a b))))))]
    [py/as-float
     ;; The integer bridge can refuse before any host float operation.
     (fn [a]
       (if (py/float? a)
         (py/num a)
         (data/float-value (py/int-result (integer/to-float (py/num a))))))]
    [py/arith
     (fn [op a b]
       (if (py/numeric? a)
         (if (py/numeric? b)
           (if (if (py/float? a) true (py/float? b))
             (let [x (py/as-float a)
                   y (py/as-float b)]
               (py/float (if (= op :add) (+ x y)
                             (if (= op :sub) (- x y) (* x y)))))
             (let [x (py/num a)
                   y (py/num b)]
               (py/int-result
                 (if (= op :add) (integer/add x y)
                     (if (= op :sub) (integer/sub x y)
                         (integer/mul x y))))))
           (py/type-error {:py/str "unsupported operand type"}))
         (py/type-error {:py/str "unsupported operand type"})))]
    [py/num-compare
     ;; nil means unordered (NaN); integers are never rounded to double.
     (fn [a b]
       (let [x (py/num a)
             y (py/num b)]
         (if (if (py/float? a) (not (<= x x)) false)
           nil
           (if (if (py/float? b) (not (<= y y)) false)
             nil
             (if (py/float? a)
               (if (py/float? b)
                 (if (< x y) -1 (if (> x y) 1 0))
                 (- 0 (py/int-result (integer/compare-float y x))))
               (if (py/float? b)
                 (py/int-result (integer/compare-float x y))
                 (py/int-result (integer/compare x y))))))))]
    [py/compare
     (fn [op a b]
       (if (py/numeric? a)
         (if (py/numeric? b)
           (let [c (py/num-compare a b)]
             (if (= c nil) false (op c 0)))
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
     ;; CPython checks Py_ssize_t even for empty/negative repeats. The
     ;; portable allocation bound is 2^53-1 items, before host allocation.
     (fn [s n]
       (if (py/int? n)
         (let [k (py/num n)
               q (get (py/int-result
                        (integer/floor-div-mod k 4294967296)) 0)]
           (if (if (> (integer/compare q 2147483647) 0)
                 true (< (integer/compare q -2147483648) 0))
             (py/raise-new py.b/OverflowError
                           (py/str (data/str-concat
                                     "cannot fit 'int' into an "
                                     "index-sized integer")))
             (let [items (if (py/str? s) (get s :py/str)
                             (if (= (py/kind s) :list)
                               (get (cell/get s) :items) (get s :items)))
                   size (if (py/str? s) (data/str-length items)
                            (data/count items))]
               (if (if (= size 0) false
                       (> (integer/compare k 9007199254740991) 0))
                 (py/int-result :yin.vm.integer/bit-limit)
                 (let [count (if (if (= size 0) true
                                     (< (integer/compare k 0) 0)) 0 k)]
                   (if (py/str? s)
                     (py/str (py/repeat-str items count ""))
                     (if (= (py/kind s) :list)
                       (py/list (py/repeat-items items count []))
                       (py/tuple (py/repeat-items items count [])))))))))
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
    ;; Exact guest integer arithmetic goes through the integer module.
    ;; The bounded helpers below remain only for S3-C bitwise and shifts.
    [py/int? (fn [x] (if (py/numeric? x) (not (py/float? x)) false))]
    [py/abs
     (fn [x]
       (if (< (integer/compare x 0) 0)
         (py/int-result (integer/neg x)) x))]
    [py/float-abs (fn [x] (if (< x 0) (- 0 x) x))]
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
    ;; |x| < Inf, not |x| <= Double.MAX_VALUE: on JS that literal is an
    ;; integral number past 2^53, which dao.jing.cbor refuses to hash (see
    ;; py/overflow); NaN is false either way
    [py/finite? (fn [x] (< (py/float-abs x) (data/float-value ##Inf)))]
    [py/fmod-pos
     ;; fmod for doubles x >= 0, y > 0, exactly: each subtraction is of
     ;; values within a factor of two (Sterbenz), as in long division
     (fn [x y]
       (if (< x y)
         x
         (let [r (py/fmod-pos x (+ y y))]
           (if (< r y) r (- r y)))))]
    ;; Zero tests here go through py/zero?: host `=` tells 0.0 from 0 on the
    ;; JVM but not on JS or Dart. A zero result takes its sign from the
    ;; divisor (never zero here), chosen rather than computed, since
    ;; 0.0 * inf is NaN.
    [py/zero-like
     ;; copysign(0.0, y) for a nonzero y
     ;; -0.0 is built as (* -1.0 0.0): ClojureDart compiles one-argument
     ;; `-` to (0 - x), and 0 - 0.0 is +0.0; multiplying by -1.0 negates
     ;; exactly on every host
     (fn [y]
       (let [z (data/float-value 0)]
         (if (< y 0) (* (data/float-value -1) z) z)))]
    [py/float-mod
     ;; x % y as CPython computes it: the exact fmod, sign-corrected toward
     ;; y. No quotient is formed, so a large finite quotient is fine.
     (fn [x y]
       (if (if (py/finite? x) (<= y y) false)
         (let [m0 (py/fmod-pos (py/float-abs x) (py/float-abs y))
               m (if (< x 0) (- 0 m0) m0)]
           (if (py/zero? m)
             (py/zero-like y)
             (if (= (< y 0) (< m 0)) m (+ m y))))
         (+ (- x x) (- y y))))]
    [py/float-divmod
     ;; [floor-quotient remainder] as CPython's float_divmod computes them:
     ;; The remainder is exact fmod, sign-corrected. Above 2^53 a finite
     ;; double is already integral; a non-finite quotient passes through.
     (fn [x y]
       (if (if (py/finite? x) (<= y y) false)
         (let [m0 (py/fmod-pos (py/float-abs x) (py/float-abs y))
               m (if (< x 0) (- 0 m0) m0)
               adjust (if (py/zero? m) false (not (= (< y 0) (< m 0))))
               mod (if (py/zero? m) (py/zero-like y) (if adjust (+ m y) m))
               div (- (/ (- x m) y) (data/float-value (if adjust 1 0)))
               ;; a zero quotient is copysign(0.0, x / y); here |x| < |y|,
               ;; so x / y is finite and 0.0 * (x / y) carries its sign
               fd (if (py/zero? div)
                    (* (data/float-value 0) (/ x y))
                    (let [f (py/float-floor div)]
                      (if (< (data/float-value 0.5) (- div f)) (+ f 1) f)))]
           (py/conj (py/conj [] fd) mod))
         (let [nan (+ (- x x) (- y y))]
           (py/conj (py/conj [] nan) nan))))]
    [py/float-floor
     (fn [x]
       (if (if (< x (* 2 4503599627370496))
             (> x (* -2 4503599627370496)) false)
         (data/float-value (py/floor x)) x))]
    [py/pow2-above (fn [x k] (if (> k x) k (py/pow2-above x (+ k k))))]
    [py/floor-descend
     (fn [x n p]
       (if (< p 1)
         n
         (if (<= (+ n p) x)
           (py/floor-descend x (+ n p) (/ p 2))
           (py/floor-descend x n (/ p 2)))))]
    [py/floor
     ;; floor of a finite real within 2^53, as an integer (the bound is
     ;; built, not written: see py/overflow)
     (fn [x]
       (if (if (< x (* 2 4503599627370496)) (> x (* -2 4503599627370496)) false)
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
    [py/division-operands
     (fn [a b]
       (if (if (py/numeric? a) (py/numeric? b) false)
         (if (if (py/int? a) (py/int? b) false)
           (do (py/division-check a b)
               (py/conj (py/conj [] (py/num a)) (py/num b)))
           (let [x (py/as-float a) y (py/as-float b)]
             (do (py/division-check x y)
                 (py/conj (py/conj [] x) y))))
         (py/type-error {:py/str "unsupported operand type"})))]
    [py/divmod-values
     (fn [a b]
       (let [xy (py/division-operands a b)]
         (if (if (py/int? a) (py/int? b) false)
           (py/int-result (integer/floor-div-mod (get xy 0) (get xy 1)))
           (py/float-divmod (get xy 0) (get xy 1)))))]
    [py/floordiv
     (fn [a b]
       (let [q (get (py/divmod-values a b) 0)]
         (if (if (py/int? a) (py/int? b) false) q (py/float q))))]
    [py/mod
     (fn [a b]
       (let [xy (py/division-operands a b)]
         (if (if (py/int? a) (py/int? b) false)
           (get (py/int-result
                  (integer/floor-div-mod (get xy 0) (get xy 1))) 1)
           (py/float (py/float-mod (get xy 0) (get xy 1))))))]
    [py/divmod
     (fn [a b]
       (let [qr (py/divmod-values a b)]
         (py/tuple
           (if (if (py/int? a) (py/int? b) false)
             qr
             (py/conj (py/conj [] (py/float (get qr 0)))
                      (py/float (get qr 1)))))))]
    [py/ipow
     ;; S3-C shifts still use this bounded helper.
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
         (data/float-value 1)
         (let [h (py/fpow base (py/int-result (integer/shift-right e 1)))
               hh (* h h)]
           (if (= (integer/bit-and e 1) 0) hh (* hh base)))))]
    [py/pow
     (fn [a b]
       (if (if (py/numeric? a) (py/numeric? b) false)
         (let [x (py/num a)
               y (py/num b)
               floaty (if (py/float? a) true (py/float? b))]
           (if (if (py/float? b) (< (py/floor y) y) false)
             (py/raise-new py.b/NotImplementedError
                           {:py/str "non-integer exponents are not supported"})
             (let [e (if (py/float? b)
                       (let [f (py/floor y)]
                         (if (< f 0)
                           (py/int-result (integer/neg (py/int-of (- 0 f))))
                           (py/int-of f))) y)]
               (if (< (integer/compare e 0) 0)
                 (if (py/zero? a)
                   (py/raise-new py.b/ZeroDivisionError
                                 {:py/str "0.0 cannot be raised to a negative power"})
                   (py/float (/ (data/float-value 1)
                                (py/fpow (py/as-float a)
                                         (py/int-result (integer/neg e))))))
                 (if floaty
                   (py/float (py/fpow (py/as-float a) e))
                   (py/int-result (integer/pow x e)))))))
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
           (if (if (py/float? a) true (py/float? b))
             ;; CPython converts both operands before its float zero check.
             (let [x (py/as-float a) y (py/as-float b)]
               (if (= y (data/float-value 0))
                 (py/raise-new py.b/ZeroDivisionError
                               {:py/str "division by zero"})
                 (py/float (/ x y))))
             (if (py/zero? b)
               (py/raise-new py.b/ZeroDivisionError
                             {:py/str "division by zero"})
               (let [r (integer/true-div (py/num a) (py/num b))
                     message
                     {:py/str "integer division result too large for a float"}]
                 (if (= r :yin.vm.integer/float-overflow)
                   (py/raise-new py.b/OverflowError message)
                   (let [f (py/int-result r)]
                     (if (py/finite? f)
                       (py/float f)
                       (py/raise-new py.b/OverflowError message)))))))
           (py/type-error {:py/str "unsupported operand type"}))
         (py/type-error {:py/str "unsupported operand type"})))]
    ;; unary - and + on a float negate or keep it, so -0.0 and +(-0.0)
    ;; keep their sign (0 - 0.0 would be 0.0)
    [py/neg
     ;; a float is negated by (* -1.0 x), not one-argument `-`, which is
     ;; (0 - x) on ClojureDart and turns -(0.0) into +0.0 there
     (fn [a]
       (if (py/float? a)
         (py/float (* (data/float-value -1) (py/num a)))
         (if (py/int? a)
           (py/int-result (integer/neg (py/num a)))
           (py/type-error {:py/str "bad operand type for unary -"}))))]
    [py/pos
     (fn [a]
       (if (py/numeric? a)
         (if (py/float? a) a (py/num a))
         (py/type-error {:py/str "bad operand type for unary +"})))]
    [py/lt (fn [a b] (py/compare < a b))]
    [py/gt (fn [a b] (py/compare > a b))]
    [py/le (fn [a b] (py/compare <= a b))]
    [py/ge (fn [a b] (py/compare >= a b))]

    ;; ---------------------------------------------------------- equality
    [py/eq
     (fn [a b]
       (if (py/numeric? a)
         (if (py/numeric? b)
           (= (py/num-compare a b) 0)
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
     (fn [r x]
       (let [start (get r :start)
             stop (get r :stop)
             step (get r :step)]
         (if (if (< (integer/compare 0 step) 0)
               (if (<= (integer/compare start x) 0)
                 (< (integer/compare x stop) 0) false)
               (if (< (integer/compare stop x) 0)
                 (<= (integer/compare x start) 0) false))
           (= (get (py/int-result (integer/floor-div-mod x step)) 1)
              (get (py/int-result (integer/floor-div-mod start step)) 1))
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
    ;; `is` is content identity for every non-cell value (C3 ruling 8,
    ;; yang.antlr.md 8.5.4): equal integers are `is`-equal at any
    ;; magnitude, every NaN is one value, 0.0 is not -0.0, True is not 1;
    ;; a cell is `is` only itself
    [py/is (fn [a b] (data/content= a b))]
    [py/is-not (fn [a b] (not (data/content= a b)))]

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
       (if (py/int? k)
         (let [i (py/num k)]
           ;; Reject before addition, so even a huge negative index does
           ;; not construct a value outside the integer resource budget.
           (if (if (< (integer/compare i 0) 0)
                 (< (integer/compare i (- 0 n)) 0)
                 (>= (integer/compare i n) 0))
             (py/raise-new py.b/IndexError
                           {:py/str "list index out of range"})
             (if (< (integer/compare i 0) 0)
               (py/int-result (integer/add i n)) i)))
         (py/type-error {:py/str "list indices must be integers"})))]

    ;; ---------------------------------------------------------- exact numbers
    ;; Version 3 has ten reasons. Numbers take the cheap first arm;
    ;; resource/conversion limits raise guest exceptions, defects fail.
    [py/int-result
     (fn [r]
       (if (data/number? r)
         r
         (if (= r :yin.vm.integer/bit-limit)
           (py/raise (let [e (py/make-instance py.b/MemoryError)]
                       (do (py/setattr e "args" (py/tuple [])) e)))
           (if (= r :yin.vm.integer/digit-limit)
             (py/raise-new py.b/ValueError
                           (py/str (data/str-concat
                                     "Exceeds the limit for integer string "
                                     "conversion")))
             (if (= r :yin.vm.integer/float-overflow)
               (py/raise-new py.b/OverflowError
                             {:py/str "int too large to convert to float"})
               (if (py/int-refusal? r) (:py/int-defect r) r))))))]
    ;; an integer literal beyond 2^53 - 1, from its canonical hex
    [py/int-lit (fn [s] (py/int-result (integer/parse s 16)))]
    [py/int-refusal?
     (fn [r]
       (if (= r :yin.vm.integer/arity)
         true
         (if (= r :yin.vm.integer/wrong-type)
           true
           (if (= r :yin.vm.integer/out-of-range)
             true
             (if (= r :yin.vm.integer/syntax)
               true
               (if (= r :yin.vm.integer/zero-division)
                 true
                 (if (= r :yin.vm.integer/negative-count)
                   true
                   (if (= r :yin.vm.integer/negative-exponent)
                     true
                     (= r :yin.vm.integer/float-overflow)))))))))]

    ;; Numeric keys and hashes need a float's exact value as integers. The
    ;; decomposition uses only steps that are exact on binary64 (halving
    ;; above 2^53, doubling below it) and builds its integers from integer
    ;; additions, so the result is a canonical integer carrier on every
    ;; host, never a double. Bounds are built, not written (see
    ;; py/overflow).
    [py/int-of
     ;; the exact integer of an integral double 0 <= a < 2^53: the quotient
     ;; py/divmod-pos builds from 0, doubling and + 1
     (fn [a] (get (py/divmod-pos a 1) 0))]
    [py/float-parts-up
     ;; a >= 2^53 is integral: halve it exactly to below 2^53
     (fn [a e]
       (if (< a (* 2 4503599627370496))
         (py/conj (py/conj [] (py/int-of a)) e)
         (py/float-parts-up (/ a 2) (+ e 1))))]
    [py/float-parts-down
     ;; 0 < a < 2^53: double it exactly until integral; the integer is then
     ;; odd whenever e < 0
     (fn [a e]
       (let [qr (py/divmod-pos a 1)]
         (if (py/zero? (get qr 1))
           (py/conj (py/conj [] (get qr 0)) e)
           (py/float-parts-down (+ a a) (- e 1)))))]
    [py/float-parts
     ;; [m e] with x = m * 2^e exactly, for a finite nonzero double x
     (fn [x]
       (let [a (py/float-abs x)
             p (if (< a (* 2 4503599627370496))
                 (py/float-parts-down a 0)
                 (py/float-parts-up a 0))]
         (if (< x 0)
           (py/conj (py/conj [] (py/int-result (integer/neg (get p 0))))
                    (get p 1))
           p)))]

    ;; ---------------------------------------------------------- dicts
    [py/finite-key
     (fn [n d] (py/conj (py/conj (py/conj [] :py.numeric/finite) n) d))]
    [py/float-key
     ;; host double x: a finite float as its reduced rational, +-0.0 as
     ;; 0/1; an infinity by its sign; every NaN as one key (yang.antlr.md
     ;; 8.5.4: a float has no object identity here, and Jing float64
     ;; content already makes every NaN one value)
     (fn [x]
       (if (py/finite? x)
         (if (py/zero? x)
           (py/finite-key "0" "1")
           (let [p (py/float-parts x)
                 m (get p 0)
                 e (get p 1)]
             (if (< e 0)
               (py/finite-key
                 (py/int-result (integer/format m 16))
                 (py/int-result
                   (integer/format
                     (py/int-result (integer/shift-left 1 (- 0 e)))
                     16)))
               (py/finite-key
                 (py/int-result
                   (integer/format (py/int-result (integer/shift-left m e))
                                   16))
                 "1"))))
         ;; NaN fails <=; host = can answer true for one boxed NaN
         (if (<= x x)
           (py/conj (py/conj [] :py.numeric/infinite) (if (< x 0) "-" "+"))
           (py/conj [] :py.numeric/nan))))]
    [py/key
     ;; the normalized index key: a number is its exact value, so 1, 1.0
     ;; and True are one key and 2^53 and 2^53 + 1 are two (C3 ruling 6);
     ;; a tuple is hashable when its elements are; lists, dicts and sets
     ;; are not
     (fn [k]
       (if (py/numeric? k)
         ;; py/num reads a float's payload through data/float-value: a
         ;; float64 carrier never meets host arithmetic
         (if (py/float? k)
           (py/float-key (py/num k))
           (py/finite-key
             (py/int-result (integer/format (py/int-canon (py/num k)) 16))
             "1"))
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

    ;; ---------------------------------------------------------- hash
    ;; Python's numeric hash (C3 ruling 7) modulo P = 2^61 - 1 on every
    ;; host, through the integer module, never a host or Jing hash: equal
    ;; numbers hash equal, and since 2^61 = 1 (mod P), 2^e is 2^(e mod 61)
    ;; modulo P. P is built, not written (see py/overflow). Only int, bool
    ;; and finite float hash. An identity object (any cell) is unhashable,
    ;; since its only identity is a cell id, which is never exposed. A
    ;; tuple is a valid dict key through py/key, but hash() of it, of a
    ;; string and of +-inf is not yet supported (NotImplementedError).
    [py/hash-modulus
     (fn []
       (py/int-result
         (integer/sub (py/int-result (integer/shift-left 1 61)) 1)))]
    [py/mod-p
     (fn [n]
       (get (py/int-result (integer/floor-div-mod n (py/hash-modulus))) 1))]
    [py/hash-signed
     ;; the hash of a number of sign s whose magnitude hashes to h; -1 is
     ;; reserved, so it answers -2
     (fn [s h]
       (if (< s 0)
         (let [v (py/int-result (integer/neg h))] (if (= v -1) -2 v))
         h))]
    [py/hash-int
     (fn [n]
       (let [s (integer/compare n 0)]
         (py/hash-signed s
                         (py/mod-p (if (< s 0)
                                     (py/int-result (integer/neg n))
                                     n)))))]
    [py/int-canon
     ;; the canonical carrier of a guest integer: a JS -0 from integer
     ;; arithmetic, such as (* -1 0), is the integer 0, which the integer
     ;; module requires; host = holds -0 equal to 0 and never a big carrier
     (fn [n] (if (= n 0) 0 n))]
    [py/hash-float
     ;; a finite nonzero x = m * 2^e: |m| * 2^(e mod 61) modulo P
     (fn [x]
       (let [p (py/float-parts x)
             m (get p 0)
             s (integer/compare m 0)
             k (get (py/int-result (integer/floor-div-mod (get p 1) 61)) 1)]
         (py/hash-signed
           s
           (py/mod-p
             (py/int-result
               (integer/mul (if (< s 0) (py/int-result (integer/neg m)) m)
                            (py/int-result (integer/shift-left 1 k))))))))]
    [py/hash
     (fn [x]
       (if (py/numeric? x)
         (if (py/float? x)
           (let [f (py/num x)]
             (if (py/finite? f)
               (if (py/zero? f) 0 (py/hash-float f))
               (py/raise-new py.b/NotImplementedError
                             {:py/str "hash() of a non-finite float is not supported"})))
           (py/hash-int (py/int-canon (py/num x))))
         (if (py/cell? x)
           (py/type-error {:py/str "unhashable type"})
           (py/raise-new py.b/NotImplementedError
                         {:py/str "hash() of this type is not supported"}))))]
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
     ;; Clamp before addition: enormous negative bounds need no sum.
     (fn [x n lower upper default]
       (if (= x :py/None)
         default
         (let [i (py/slice-int x)]
           (if (< (integer/compare i 0) 0)
             (if (< (integer/compare i (- lower n)) 0)
               lower (py/int-result (integer/add i n)))
             (if (> (integer/compare i upper) 0) upper i)))))]
    [py/slice-walk
     (fn [i stop step acc]
       (if (if (< 0 step) (< i stop) (> i stop))
         (py/slice-walk (+ i step) stop step (conj acc i))
         acc))]
    [py/slice-positions
     ;; A step beyond n selects at most one item; cap it before walking.
     (fn [s n]
       (let [raw (if (= (get s :step) :py/None)
                   1 (py/slice-int (get s :step)))
             sign (integer/compare raw 0)
             step (if (> (integer/compare raw (+ n 1)) 0)
                    (+ n 1)
                    (if (< (integer/compare raw (- -1 n)) 0)
                      (- -1 n) raw))]
         (if (= sign 0)
           (py/raise-new py.b/ValueError {:py/str "slice step cannot be zero"})
           (let [lower (if (< sign 0) -1 0)
                 upper (if (< sign 0) (- n 1) n)
                 start (py/slice-bound (get s :start) n lower upper
                                       (if (< sign 0) upper lower))
                 stop (py/slice-bound (get s :stop) n lower upper
                                      (if (< sign 0) lower upper))]
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
    [py/range-small?
     ;; Established once at construction; host arithmetic in range-at
     ;; then sees only canonical small carriers, including stop.
     (fn [a b s]
       (if (<= (integer/compare -4503599627370496 a) 0)
         (if (<= (integer/compare a 4503599627370496) 0)
           (if (<= (integer/compare -67108864 s) 0)
             (if (<= (integer/compare s 67108864) 0)
               (if (<= (integer/compare -9007199254740991 b) 0)
                 (<= (integer/compare b 9007199254740991) 0) false)
               false) false) false) false))]
    [py/range3
     (fn [a b s]
       (if (if (py/int? a) (if (py/int? b) (py/int? s) false) false)
         (if (py/zero? s)
           (py/raise-new py.b/ValueError {:py/str "range() arg 3 must not be zero"})
           (let [a (py/num a) b (py/num b) s (py/num s)]
             (assoc (assoc (assoc (assoc (assoc {} :py/type :range)
                                         :start a) :stop b) :step s)
                    :small? (py/range-small? a b s))))
         (py/type-error {:py/str "range() arguments must be integers"})))]
    [py/range-count
     ;; Decompose each bound first: stop-start need not fit the budget.
     (fn [start stop step]
       (if (< (integer/compare step 0) 0)
         (py/range-count stop start (py/int-result (integer/neg step)))
         (if (< (integer/compare start stop) 0)
           (let [a (py/int-result (integer/floor-div-mod start step))
                 b (py/int-result (integer/floor-div-mod stop step))
                 extra (if (< (integer/compare (get a 1) (get b 1)) 0) 1 0)]
             (py/range-size (get a 0) (get b 0) extra))
           0)))]
    [py/range-size
     ;; Subtract in base 2^32 to check sys.maxsize before allocating the
     ;; count. Even a 53-bit profile can distinguish overflow from a
     ;; valid 64-bit length that breaches its own resource budget.
     (fn [a b extra]
       (let [a (py/int-result (integer/floor-div-mod a 4294967296))
             b (py/int-result (integer/floor-div-mod b 4294967296))
             high (integer/sub (get b 0) (get a 0))
             low (+ (- (get b 1) (get a 1)) extra)
             carry (if (< low 0) -1 (if (>= low 4294967296) 1 0))
             low (- low (* carry 4294967296))
             high (if (= high :yin.vm.integer/bit-limit)
                    high (integer/add (py/int-result high) carry))]
         (if (if (= high :yin.vm.integer/bit-limit)
               true (> (integer/compare (py/int-result high) 2147483647) 0))
           (py/raise-new py.b/OverflowError
                         (py/str (data/str-concat
                                   "Python int too large to convert "
                                   "to C ssize_t")))
           (py/int-result
             (integer/add
               (py/int-result (integer/mul (py/int-result high) 4294967296))
               low)))))]
    [py/range-elem
     ;; Fuse start+i*step without a too-large intermediate product.
     ;; Choose a quotient toward zero, so product and residual have the
     ;; same sign. Any bit breach is a never-yielded candidate past stop.
     (fn [start step i]
       (let [qr (py/int-result (integer/floor-div-mod start step))
             k (integer/add (get qr 0) i)]
         (if (= k :yin.vm.integer/bit-limit)
           k
           (let [k (py/int-result k)
                 r (get qr 1)
                 adjust (if (< (integer/compare k 0) 0)
                          (not (= r 0)) false)
                 k (if adjust (py/int-result (integer/add k 1)) k)
                 r (if adjust (py/int-result (integer/sub r step)) r)
                 product (integer/mul k step)]
             (if (= product :yin.vm.integer/bit-limit)
               product (integer/add (py/int-result product) r))))))]
    [py/range-at
     ;; The O(1) path is entirely native and small. Its result is tested
     ;; before acceptance: bare +/-2^53 never becomes a guest integer.
     (fn [r i]
       (let [start (get r :start)
             step (get r :step)
             stop (get r :stop)
             ;; Only a guard is rounded: a canonical big index cannot
             ;; round into [0, 2^26]. The accepted arithmetic uses i.
             guard-i (data/float-value i)]
         (if (if (get r :small?)
               (if (<= 0 guard-i) (<= guard-i 67108864) false) false)
           (let [x (+ start (* i step))]
             (if (if (< 0 step) (< x stop) (> x stop))
               (if (if (<= -9007199254740991 x)
                     (<= x 9007199254740991) false)
                 x (py/range-exact-at start stop step i))
               :py/stop))
           (py/range-exact-at start stop step i))))]
    [py/range-exact-at
     (fn [start stop step i]
       (let [x (py/range-elem start step i)]
         (if (= x :yin.vm.integer/bit-limit)
           :py/stop
           (let [x (py/int-result x)]
             (if (if (< (integer/compare step 0) 0)
                   (> (integer/compare x stop) 0)
                   (< (integer/compare x stop) 0)) x :py/stop)))))]
    [py/range-len
     ;; range-count checks sys.maxsize before its resource-limited result.
     (fn [r _i]
       (py/range-count (get r :start) (get r :stop) (get r :step)))]
    [py/iter-at
     (fn [it i]
       (if (py/cell? it)
         (let [c (cell/get it)
               t (get c :py/type)]
           (if (= t :list)
             (get (get c :items) i :py/stop)
             (if (if (= t :dict) true (= t :set))
               (get (get c :keys) i :py/stop)
               ;; stateful: one advancement per call, the index unused
               (if (= t :generator)
                 (py/gen-step it)
                 (if (= t :iterator)
                   (py/iter-step it)
                   (py/type-error {:py/str "object is not iterable"}))))))
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
     ;; RuntimeError, as in CPython; a list is walked live; a generator or
     ;; sequence iterator is its own iterator, walked as it is
     (fn [x]
       (if (py/str? x)
         (py/tuple (py/chars (data/str->code-points (get x :py/str)) 0 []))
         (let [t (py/content-type x)]
           (if (if (= t :dict) true (= t :set))
             (assoc (assoc (assoc (assoc {} :py/type :keys-iter) :obj x)
                           :size (data/count (get (cell/get x) :keys)))
                    :what (if (= t :dict) "dictionary" "Set"))
             x))))]
    [py/iter
     ;; iter(x): a generator or iterator is its own iterator; anything else
     ;; a loop walks gets a sequence iterator over what the loop would walk
     (fn [x]
       (let [k (py/kind x)]
         (if (if (= k :generator) true (= k :iterator))
           x
           (if (if (py/str? x)
                 true
                 (if (= k :list)
                   true
                   (if (= k :dict)
                     true
                     (if (= k :set) true (if (= k :tuple) true (= k :range))))))
             (cell/new (assoc (assoc (assoc {} :py/type :iterator) :src (py/iterable x))
                              :i 0))
             (py/type-error {:py/str "object is not iterable"})))))]
    [py/iter-step
     ;; one advancement of a sequence iterator: the next element, or
     ;; :py/stop, after which it stays exhausted (its source dropped). A
     ;; dict or set whose size changed invalidates its iterator for good, as
     ;; in CPython: the size it expects becomes -1 before the error is
     ;; raised, so restoring the size does not resume it.
     (fn [it]
       (let [c (cell/get it)
             src (get c :src)]
         (if (= src :py/None)
           :py/stop
           (do (if (if (= (get src :py/type) :keys-iter)
                     (not (= (data/count (get (cell/get (get src :obj)) :keys))
                             (get src :size)))
                     false)
                 (cell/set! it (assoc c :src (assoc src :size -1)))
                 :py/None)
               (let [x (py/iter-at src (get c :i))]
                 (do (cell/set! it (if (= x :py/stop)
                                     (assoc c :src :py/None)
                                     (assoc c :i (+ (get c :i) 1))))
                     x))))))]
    [py/iter-outcome
     ;; a sequence iterator's advancement as a switch outcome
     (fn [it]
       (let [x (py/iter-step it)]
         (if (= x :py/stop) (py/outcome :return :py/None) (py/outcome :yield x))))]
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
    ;; sum, any, all and set consume their iterable one element at a time,
    ;; as CPython's do: any and all stop at the first decisive element, and
    ;; an element that fails stops the fold before later ones are produced
    [py/sum-from
     (fn [it i acc]
       (let [x (py/iter-at it i)]
         (if (= x :py/stop) acc (py/sum-from it (+ i 1) (py/add acc x)))))]
    [py/any-of
     (fn [it i]
       (let [x (py/iter-at it i)]
         (if (= x :py/stop)
           false
           (if (py/truthy x) true (py/any-of it (+ i 1))))))]
    [py/all-of
     (fn [it i]
       (let [x (py/iter-at it i)]
         (if (= x :py/stop)
           true
           (if (py/truthy x) (py/all-of it (+ i 1)) false))))]
    [py/set-fill-at
     (fn [s it i]
       (let [x (py/iter-at it i)]
         (if (= x :py/stop)
           s
           (do (py/set-add s x) (py/set-fill-at s it (+ i 1))))))]
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
           (cell/set! py.rt/ctx (py/ctx nil 0 0 nil))
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
   ;; raised by the safepoint hook prelude's signal delivery; the class is a
   ;; builtin like any other, so naive programs read and shadow it normally
   ["KeyboardInterrupt" 'py.b/KeyboardInterrupt 'py.b/BaseException]
   ;; thrown into a generator by close()
   ["GeneratorExit" 'py.b/GeneratorExit 'py.b/BaseException]
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
   ;; raised at a generator start or resume the limit refuses (here, in
   ;; every mode) and by the safepoint hook prelude's depth accounting; a
   ;; builtin like KeyboardInterrupt, under RuntimeError as in CPython
   ["RecursionError" 'py.b/RecursionError 'py.b/RuntimeError]
   ["NotImplementedError" 'py.b/NotImplementedError 'py.b/RuntimeError]
   ["OverflowError" 'py.b/OverflowError 'py.b/ArithmeticError]
   ;; an integer past the composition's bit limit (`py/int-result`)
   ["MemoryError" 'py.b/MemoryError 'py.b/Exception]
   ["StopIteration" 'py.b/StopIteration 'py.b/Exception]])


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
    [py.b/divmod
     (py/make-function "divmod" {:params ["x" "y"], :no-kw true} [] []
                       (fn [args]
                         (py/divmod (py/arg args 0) (py/arg args 1))))]
    [py.b/hash
     (py/make-function "hash" {:params ["obj"], :no-kw true} [] []
                       (fn [args] (py/hash (py/arg args 0))))]
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
                       (fn [args]
                         (py/set-fill-at (py/set-new) (py/iterable (py/arg args 0))
                                         0)))]
    [py.b/sum
     (py/make-function "sum" {:params ["iterable" "start"]} [0] []
                       (fn [args]
                         (py/sum-from (py/iterable (py/arg args 0)) 0
                                      (py/arg args 1))))]
    [py.b/any
     (py/make-function "any" {:params ["iterable"], :no-kw true} [] []
                       (fn [args] (py/any-of (py/iterable (py/arg args 0)) 0)))]
    [py.b/all
     (py/make-function "all" {:params ["iterable"], :no-kw true} [] []
                       (fn [args] (py/all-of (py/iterable (py/arg args 0)) 0)))]
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
                           (py/arg args 2))))]
    [py.b/next
     (py/make-function "next" {:params ["iterator" "default"], :no-kw true} [:py/missing] []
                       (fn [args] (py/next (py/arg args 0) (py/arg args 1))))]
    [py.b/iter
     (py/make-function "iter" {:params ["object"], :no-kw true} [] []
                       (fn [args] (py/iter (py/arg args 0))))]
    [py.b/gen-send
     (py/make-function "send" {:params ["self" "value"], :no-kw true} [] []
                       (fn [args] (py/gen-send (py/arg args 0) (py/arg args 1))))]
    [py.b/gen-throw
     (py/make-function "throw" {:params ["self" "typ" "val" "tb"], :no-kw true}
                       [:py/None :py/None] []
                       (fn [args]
                         (py/gen-throw (py/arg args 0) (py/arg args 1)
                                       (py/arg args 2) (py/arg args 3))))]
    [py.b/gen-close
     (py/make-function "close" {:params ["self"], :no-kw true} [] []
                       (fn [args] (py/gen-close (py/arg args 0))))]
    [py.b/gen-next
     (py/make-function "__next__" {:params ["self"], :no-kw true} [] []
                       (fn [args] (py/next (py/arg args 0) :py/missing)))]
    [py.b/gen-iter
     (py/make-function "__iter__" {:params ["self"], :no-kw true} [] []
                       (fn [args] (py/arg args 0)))]])


(def ^:private state-definitions
  "Definitions that allocate: the three runtime cells, the builtin classes
   and the builtin functions. These are the only prelude forms that run at
   load."
  (-> '[[py.rt/ctx (cell/new (py/ctx nil 0 0 nil))]
        ;; CPython's default sys.getrecursionlimit()
        [py.rt/limit (cell/new 1000)]
        [py.rt/out (cell/new [])]]
      (into (map (fn [[nm key base]]
                   [key (cond->> (list 'py/make-class nm (or base :py/None))
                          (= "StopIteration" nm) (list 'py/stop-iteration-class))]))
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
         "hash" 'py.b/hash,
         "divmod" 'py.b/divmod,
         "print" 'py.b/print,
         "range" 'py.b/range,
         "list" 'py.b/list,
         "tuple" 'py.b/tuple,
         "set" 'py.b/set,
         "sum" 'py.b/sum,
         "any" 'py.b/any,
         "all" 'py.b/all,
         "next" 'py.b/next,
         "iter" 'py.b/iter}
        (map (fn [[nm key _]] [nm key]))
        builtin-classes))


(def min-integer-bits
  "The smallest `::integer/max-bits` this profile admits: every inline
   literal (up to 2^53 - 1) must fit (C3 S2)."
  53)


(defn register-integer-module
  "Return `registry` with the `integer` module installed under `limits`
   for this profile: refused when `::integer/max-bits` is under
   `min-integer-bits`, otherwise `integer/register-integer-module`."
  [registry limits]
  (let [b (::integer/max-bits limits)]
    (when-not (and (int? b) (<= min-integer-bits b))
      (throw (ex-info "the Python profile needs integer max-bits >= 53"
                      {:yang.python.antlr/refusal :yang.python.antlr/max-bits,
                       ::integer/max-bits b}))))
  (integer/register-integer-module registry limits))
