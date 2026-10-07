Completed-GMT: 2026-10-01 17:27:00 GMT
Completed-Local: 2026-10-02 00:27:00 +0700

# Architect design: Python phase C2, generators over the D7 continuations

Read-only; no file edited, nothing executed, so every claim is from reading master `111a9823`. The uncommitted work in the tree is DHT/REPL only; every file this design depends on is clean, so committed state is what I judged.

## Recommended design

A generator is one heap cell holding a suspended `Continuation` plus its own handler stack. `yield` and resume are two explicit continuation invocations that swap control and handler stack together. Everything is prelude code plus four lowering arms; no VM change, no new AST node, no wire or ledger change.

The one load-bearing decision is that **a generator owns its handler stack**. C1's `py/try`, `py/try-finally` and `py/call-ec` restore absolute snapshots (`saved`) of `py.rt/handlers` (`prelude.cljc:135-169`). If generator frames sat on the caller's stack, a resume from a different caller depth would restore a stale stack. With a per-generator stack, every `saved` taken inside a generator body is a snapshot of that generator's own stack, so the C1 machinery works unchanged.

## Q1. Representation

**Object.** A cell (identity is the ref, per §8.11) holding:

| Key | Present when | Content |
|---|---|---|
| `:py/type` `:generator`, `:name` | always | tag and function name |
| `:state` | always | `:created`, `:suspended`, `:running`, `:closed` |
| `:body` | `:created` | closure `(fn [%gen] ...)` |
| `:resume` | `:suspended` | the `Continuation` captured at the yield |
| `:ctx` | `:suspended` | the generator's own handler stack |
| `:return`, `:caller-ctx` | `:running` | the active resume call's continuation and the caller's handler stack |

- **Frame state.** Parameters and locals are already cells, allocated when the generator function is called (arguments bind at call, as in Python). The body does not run until the first resume.
- **Clearing.** Slots are cleared on every transition, so a suspended generator never retains its last caller and a closed one retains nothing.
- **Internal protocol.** `py/gen-switch g msg` takes `[:send v]` or `[:throw e]` and answers `[:yield v]`, `[:return v]` or `[:raise e]`. It never raises from the generator's side. Loops consume the outcome directly, with no exception per item.
- **Iterator protocol.** `py/iterable` returns a generator as itself. `py/iter-at` gains a `:generator` arm that steps it and maps `[:return _]` to `:py/stop`; the index is ignored. The `for` lowering and its goldens do not change.
- **`iter-at` invariant.** It is called once per index, in increasing order. I checked the three callers (`lower-for`, `py/collect`, `py/for-each-at`); all comply.
- **Guest surface.** Builtins `next(it[, default])` and `iter(x)`; methods `__next__`, `__iter__`, `send`, `throw`, `close` through a `:generator` arm in `py/getattr`, like the list and dict arms.
- **StopIteration.** A new builtin class under `Exception`, with a prelude `__init__` that sets `args` and `value`. `return v` becomes `[:return v]`; only `next`, `send` and `throw` turn that into `raise StopIteration(v)`.
- **Exhaustion.** A `:closed` generator answers `[:return None]` forever.
- **PEP 479.** A `StopIteration` escaping the body becomes `RuntimeError("generator raised StopIteration")`.

## Q2. `yield` as a crossing

Both mechanisms are used, for different jobs: the flag cell tells the two passes through a capture apart, and the crossing itself is an explicit invoke of a continuation stored in the generator cell.

Sketch in prelude notation (vector literals stand for `py/conj` chains):

```clojure
[py/gen-switch                       ; caller side
 (fn [g msg]
   (let [c (cell/get g) st (get c :state)]
     ;; :running -> ValueError "generator already executing"
     ;; :closed  -> [:return None], or [:raise e] for a throw
     (let [flag (cell/new :first)]
       ((fn [r]
          (if (= (cell/get flag) :first)
            (do (cell/set! flag :re-entered)
                (cell/set! g <c with :state :running, :return r,
                              :caller-ctx (cell/get py.rt/handlers),
                              :resume/:body/:ctx cleared>)
                (if (= st :created)
                  (py/gen-start g (get c :body) msg)
                  (do (cell/set! py.rt/handlers (get c :ctx))
                      ((get c :resume) msg))))
            r))                      ; re-entered: r is the outcome
        (%capture)))))]

[py/yield-raw                        ; generator side; answers the next message
 (fn [g v]
   (let [flag (cell/new :first)]
     ((fn [r]
        (if (= (cell/get flag) :first)
          (let [c (cell/get g)]
            (do (cell/set! flag :re-entered)
                (cell/set! g <c with :state :suspended, :resume r,
                              :ctx (cell/get py.rt/handlers),
                              :return/:caller-ctx cleared>)
                (cell/set! py.rt/handlers (get c :caller-ctx))
                ((get c :return) [:yield v])))
          r))
      (%capture))))]

[py/yield (fn [g v] (let [m (py/yield-raw g v)]
                      (if (= (get m 0) :throw) (py/raise (get m 1)) (get m 1))))]
```

- **Start.** `py/gen-start` installs a fresh stack whose only frame is a boundary, `(py/frame :generator (fn [e] (py/gen-fail g e)) nil)`, then runs `(py/gen-exit g [:return (body g)])`.
- **Exit.** `py/gen-exit` is the single way out of a finishing generator: set `:closed`, restore `:caller-ctx`, invoke `:return` with the outcome. The body never returns normally.
- **Boundary frame.** `py/raise` already calls any non-`:finally` frame's payload with the exception (`prelude.cljc:117-121`), so the boundary needs no change to `py/raise` or `py/unwind-to`. `py/gen-fail` applies PEP 479 and exits with `[:raise e]`; the caller side re-raises in its own control and handler context.
- **No escape crosses the boundary.** `function-value` resets `:loop` and `:ret` (`lower.cljc:1009-1013`), so only exceptions and yields cross, both explicitly.
- **Dynamic context.** It is guest heap data, swapped whole at each crossing in O(1). The VM and UCF know nothing of it.
- **Environment rewind.** Not a risk: guest locals are cells, generated temporaries are never reassigned, and each captured continuation is invoked at most once (the §8.5 rule holds).
- **Evaluation order.** In `f(a(), (yield x), b())` the evaluated `a()` rides in the captured operand frame. This is why continuations beat a state-machine transform here.

## Q3. `yield from`, send/throw/close, finally/with

- **send.** `gen-switch [:send v]`. A non-None send to a `:created` generator is a `TypeError` raised in the caller before any switch.
- **throw.** `gen-switch [:throw (py/as-exception x)]`; the yield site raises it on the generator's stack, so an enclosing `try` in the generator catches it. On a `:created` generator it closes it and raises in the caller.
- **close.** No-op on `:created` (marks closed) and `:closed`. Otherwise throw `GeneratorExit` (new class under `BaseException`, so `except Exception` does not catch it). The outcome decides the result:
  - `[:return _]`, or `[:raise e]` with `e` a `GeneratorExit`: `None`.
  - `[:yield _]`: `RuntimeError("generator ignored GeneratorExit")`.
  - Any other raise propagates.
- **finally and with around a yield.** No change to `py/try`, `py/try-finally`, `py/with`, `py/call-ec`.
  - The frames are saved in `:ctx` at suspend and reinstalled at resume.
  - A thrown exception, `GeneratorExit` or `return` unwinds them through the existing code, running finally thunks and `__exit__`.
  - A `yield` inside a finally thunk suspends inside `py/raise` or `py/unwind-to`'s own frames and resumes there correctly.
- **Consumer `break`.** It unwinds only the caller's stack. The suspended generator's `finally` does not run, as in Python without collection.
- **`yield from`.** `py/yield-from g x` is the PEP 380 loop as a tail-recursive prelude function over `py/yield-raw`:
  - `[:send None]` steps the delegate; `[:send v]` calls its `send`.
  - `[:throw GeneratorExit]` closes the delegate, then re-raises.
  - Any other `[:throw e]` forwards to the delegate's `throw` if it has one, else raises in the outer generator.
  - The delegate's `[:return v]` is the expression's value; its `[:raise e]` is raised on the outer generator's stack.
  - A non-generator delegate goes through a stateful sequence iterator, the same object `iter()` returns: a cell `{:py/type :iterator :src it :i n}` over `py/iter-at`.

## Q4. Generator expressions

- **Lowering.** A genexp is an anonymous generator: `(let [%first (py/iter <first iterable, enclosing scope>)] (py/make-generator "<genexpr>" (fn [%gen] <C1 clause nest ending in (py/yield %gen elem)>)))`, with the comprehension's locals as cells.
- **First iterable.** `scope.cljc` already puts it in the enclosing scope. It is evaluated and `iter()`-checked eagerly; everything else is lazy.
- **List, set and dict comprehensions.** They stay eager, as today.
- **Remove the C1 consuming-builtin inlining** (`lower.cljc:406-411`, `:649-667`, the `:sum`/`:any`/`:all` kinds, `py/genexp-unsupported`). It was a placeholder and it deviates under PEP 479: `list(next(it) for _ in r)` leaks `StopIteration` where Python raises `RuntimeError`. A fast path guarded by a run-time identity check belongs to an attached optimizer, not the naive lowering.
- **Syntax diagnostics.** `yield` at module or class level, and `yield` inside any comprehension or genexp.

## Q5. Heap and reclamation

- **Tracing.** Already sufficient; no VM change. Cell content is traced as data, and a `Continuation` re-enters kernel mode through its payload (`engine.cljc:1948-1949`). The walker classifies it precisely (`ast_walker.cljc:1153`); the semantic kernel answers nil, so the payload is walked whole, which is conservative and complete (`semantic.cljc:1058`). C1 already keeps handler continuations in a cell, so the path is exercised.
- **Dropped mid-suspension.** The cell is swept with its continuation. Pending `finally` and `__exit__` do not run. This conforms to the language reference and matches §8.11 (finalizers out of scope). Close-on-collection stays deferred.
- **Pinning.** No new rule. A generator ref put on a stream is pinned for the task's life with everything its continuation reaches.
- **Stale base (design limit).** `%capture` is undelimited, so `:resume` contains the frames of the first `next()` call beneath the generator's own. They are never returned into, but they are traced, so the first caller's locals stay live until the generator closes or is swept. The principled fix is a VM-level delimited capture, which is outside C2.
- **Allocation.** Two flag cells per item (one per crossing), garbage after re-entry.

## Q6. Identity and wire

- **Canonical tree.** Unchanged. `yield` is an `:application` of `py/yield`; `%capture` stays inside prelude functions.
- **Ledger.** No new record. "Is this function a generator" is derivable by query (its body applies `py/yield` to its `%gen` binder), so nothing is persisted.
- **Code addresses.** The prelude grows, so every bundled unit's address changes; this is a prelude-profile bump under §11.
- **UCF.** No change. A reachable generator is non-portable today for two existing reasons: cells refuse (slice 1) and continuations refuse as `:non-canonicalizable` (`engine.cljc:710-712`). When heap-slice lift and the `:yin.k/frame` continuation encoding land, generators migrate with no generator-specific wire form, stale base included.
- **Guest identity.** `is` is `=` on the ref; `iter(g) is g`; hashable by ref. `py/snapshot-obj` gains `{:py/generator name}`, never the continuation.
- **Prelude rule.** Compare only `:state` keywords, never generator contents: `Continuation` equality and hash are structural and cost O(continuation).

## Q7. Portability

- **Hosts.** All new code is prelude Universal AST and portable lowering. No host type, no reader conditional. No `get` on a continuation (the flag cell avoids it).
- **Tail marks.** A crossing is abortive in both directions, so neither side grows. The generator's loop and `py/yield-from`'s recursion are tail calls that `mark-tails` marks. The walker reuses `k` on closure application (`ast_walker.cljc:266-271`); semantic, stack and register honour the mark. Continuation size is the constant body frames plus the fixed stale base.
- **`sys.settrace`.** Sees nothing in C2. The obligation to the safepoint design is that generator switches happen only at applications of `py/gen-switch`, `py/yield-raw` and `py/yield-from`, which an attached inserter can find by query.
- **Forward constraint.** Future per-thread dynamic state (frame-record stack, recursion counter, `exc_info`) must join `:ctx` and swap with the handler stack.

## Q8. Slices and acceptance contracts

**Lanes.**
- **JVM:** e2e from source on all four VMs via `every-vm=`; expected output is CPython 3.9.6's for the same source.
- **Node:** the same primitives as prelude-notation forms in `prelude_parity_test.cljc`, and hand-built packets in `lower_portable_test.cljc`.
- **Dart:** the prelude forms wherever the parity lane runs on CLJD.

**S1: core.** `py/make-generator`, `gen-switch`, `yield`, `gen-exit`, `gen-fail`; `StopIteration`; `next`; `iter-at`/`iterable` arms; lowering arms `yield_stmt` and `yield_expr`, a `:gen` binder in ctx reset in every nested scope, and removal of the three `yield` guards.

| Setup | Action | Assertion |
|---|---|---|
| `def g(n)` yielding `0..n-1` in a `while` | `print(list(g(3)))`; `for x in g(2): print(x)` | `[0, 1, 2]`, `0`, `1` |
| body appends `'start'`, yields 1, appends `'end'` | call; `next`; `next` under `except StopIteration as e` | log empty after call; `1 ['start']`; `stop None ['start', 'end']` |
| `yield 1; return 7` | drain with `next` | `e.value` is 7; a further `next` gives value None |
| `x = [a(), (yield 1), b()]` with logging `a`, `b` | `next`, then `send('s')` | `a` logged before suspension, `b` after; list holds `'s'` |
| `yield` at module level; `[(yield x) for x in y]` | compile | one `:yang.python.antlr/syntax` diagnostic, no program |
| generator yielding in `while True` | consume 3000 items in a `for` with `break` | completes on all four VMs; JVM-only: continuation length of `:resume` equal after 10 and after 1000 items |
| Node: prelude form building a generator from a literal body | `py/gen-switch` three times | `[:yield 1]`, `[:yield 2]`, `[:return :py/None]` on every VM |

**S2: send, throw, close, dynamic context.**

| Setup | Action | Assertion |
|---|---|---|
| accumulator `x = yield total` | `next(a), a.send(5), a.send(2)` | `0 5 7` |
| fresh generator | `send(1)` | `TypeError`; generator still startable |
| `try: yield 1 / except ValueError: yield 'caught'` | `next`; `throw(ValueError)` | `'caught'` |
| no handler in body | `throw(KeyError('k'))`; `next` | `KeyError` at caller; then `StopIteration` |
| `try: yield 1 / finally: log` | `close()` twice | finally logged once; both return None |
| `try: yield / except GeneratorExit: yield 2` | `close()` | `RuntimeError` "generator ignored GeneratorExit" |
| `with cm: yield 1` | `close()` | `__exit__` called once with the `GeneratorExit` type |
| body `try: yield 1; yield 2 / finally: log`, then `raise KeyError` | first `next` inside a caller `try/except ValueError`, later ones outside | `1`, `2`, then `KeyError` caught by the current caller's handler; a later caller `try/except` still works |
| body calls `next` on itself | `next` | `ValueError` "generator already executing" |
| body raises `StopIteration` | `next` | `RuntimeError` |
| consumer `for` with `break` over a generator with `finally` | run, then raise and catch in caller | finally not logged; caller handler works |

**S3: `yield from`, `iter`, sequence iterators.**

| Setup | Action | Assertion |
|---|---|---|
| `inner`: `x = yield 1; print('got', x); return 'r'`; `outer`: `v = yield from inner(); print('v', v); yield from [10, 20]` | `next`, `send('s')`, `next` | `1`, `got s`, `v r`, `10`, `20` |
| inner catches `ValueError` and yields | `outer.throw(ValueError)` | inner's handler runs; value returned |
| both with `finally` logs | `outer.close()` | inner logged before outer |
| `yield from range(3000)` | sum in a `for` | completes on four VMs |
| `it = iter([1, 2])` | `next(it), next(it), next(it, 'd')`; `iter(it) is it` | `1 2 d`; `True` |

**S4: generator expressions.**

| Setup | Action | Assertion |
|---|---|---|
| `ge = (f(x) for x in [1,2,3])`, `f` logs | print log; `next(ge)`; `list(ge)` | `[]`; `1 [1]`; `[2, 3] [1, 2, 3]` |
| `(x for x in undefined)`; `(x for x in 5)` | create | `NameError`, `TypeError` at creation |
| `(y for x in [1] for y in undefined)` | create, then `next` | no error at creation; `NameError` at `next` |
| `globals()['any'] = lambda it: list(it)` | `any(x for x in [1])` | `[1]` (replaces `generator-consumer-rebound-test`) |
| `list(next(it) for _ in range(3))`, `it` of length 1 | run | `RuntimeError` |
| `sum(x*x for x in range(4))`, `any`/`all`/`set`/`tuple` forms | run | C1 values unchanged |

**S5: heap, wire, hosts.**

| Setup | Action | Assertion |
|---|---|---|
| suspended generator held by a global; allocate past the collection threshold | resume | local state intact |
| generator dropped mid-suspension inside `try/finally` | allocate past threshold | cell gone from `:heap`; finally not logged; program completes |
| value holding a suspended generator | lift | qualified `:yin.k/non-portable` refusal, no host exception |
| run one generator program twice | compare final VM values | equal |
| `print(g())` | render | `{:py/generator "g"}` form, identical on JVM and Node |

## Defects, gaps, deferrals

| Class | Item |
|---|---|
| Architectural defect (latent, closed by this design) | C1 handler restores are absolute snapshots; correct only under stack discipline. The per-generator stack is the fix, with no C1 code change. |
| Architectural defect (small) | The C1 genexp inlining deviates from PEP 479; remove it in S4. |
| Design limit | Stale base frames retained by undelimited capture; needs a VM-level delimited capture. |
| Implementation gap | `py/contains`, `py/snapshot-obj` and `py/getattr` need `:generator` arms; `iter-at`'s index is vestigial for stateful iterators. |
| Intentionally deferred | Close-on-collection, async generators, migration of suspended generators, `settrace` events, `gi_*` introspection, the three-argument `throw`. |

## Owner decisions

1. **Per-generator handler stack with a boundary frame.** Recommended. The alternative is rebasing frames onto each caller's stack, which also requires rewriting C1's `saved` restores.
2. **Pass discriminator.** Recommended: flag cells in C2, the pattern already gated in C1. The alternative writes `:state` before each capture and tests it after, saving two cells per item but harder to review; take it later, on measurement.
3. **Single exit through `:return` with tagged outcomes**, and `StopIteration` raised only at `next`/`send`/`throw`. Recommended.
4. **Remove the consuming-builtin inlining in S4.** Recommended. The alternative keeps it as a fast path with the PEP 479 deviation documented.
5. **Keep `py/iter-at`'s index signature** so `for` goldens do not change. Recommended. The alternative is a full iterator-object protocol for every loop.
6. **Dropped generators are reclaimed without `close()`.** Recommended; documented deviation from CPython's refcount timing.
7. **Stale base retention accepted for C2**, with delimited capture raised as a separate VM question. Recommended. The alternative is a multi-shot top-level trampoline in the prelude, which I reject as clever and against the once-only rule.
8. **User-defined iterator classes (`__iter__`/`__next__`)** as an S5 addition. Recommended to include: two prelude arms plus a `StopIteration` catch in the loop driver. The alternative is to defer to C3.
9. **No persisted generator flag** on function specs. Recommended; add a literal spec key only when `inspect.isgeneratorfunction` is in scope.
