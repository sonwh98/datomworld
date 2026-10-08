Completed-GMT: 2026-09-30 19:55:42 GMT
Completed-Local: 2026-10-01 02:55:42 +07
Coding-Agent: claude
Session-ID: f8eef849-bc12-4f36-87ee-4ae5da8aaa8c

# Architect review: Python 3 mappability to the Universal AST

Read-only; no file edited, nothing executed. Evidence is the spike report, the spike worktree's `prelude.cljc` and `lower.cljc`, and master `5e790683`.

## Verdict: CONFIRMED WITH CORRECTIONS

The headline holds: with multi-shot continuations, cells, closures and effects, the Universal AST is a complete target for the Python 3 language reference. Every "cannot" is one of: cost, host coupling, a datom.world invariant, or a CPython implementation detail the language reference does not promise. The corrections are to bucket placement, plus one design defect in the spike's own choices that the analysis missed (module globals in the store).

## Corrected bucket table

| Construct | Orchestrator's bucket | Corrected | Reason |
|---|---|---|---|
| Frame introspection: `sys._getframe`, live `locals()`, `f_back`, tracebacks | 3 (needs VM infra) | **1** (cost) | The lowering can maintain an explicit frame record (a cell stack of frames whose locals are already cells), which is Python's own model. VM-exposed continuations are only needed to get it for free, and D7 is moving the other way. |
| Line numbers in tracebacks | 3 | **1 with an owner decision** | Either embed line literals in frame records (guest-visible, but whitespace changes code addresses) or derive at the boundary from the §2.5 position side tables (derive-don't-persist, not guest-visible). |
| `RecursionError`, `setrecursionlimit` | 3 | **1** | Continuations are heap data on all four VMs, so nothing overflows; a depth counter in a cell, decremented by escapes, is prelude code. |
| `weakref`, `__del__`, `gc` | 3 | **1 conforming / 3 faithful** | The reference says `__del__` is not guaranteed to run and a weakref may stay alive; never collecting conforms. Faithful behaviour needs reclamation. |
| `KeyboardInterrupt`, signals | 1 (implicit) | **2 + safepoints; arbitrary-point delivery is 4** | Delivery at an arbitrary point is implicit control flow and nondeterministic. The mapping is: the signal is an event on a stream, polled at safepoints the lowering inserts. CPython itself delivers only between bytecodes. |
| `sys.settrace`, `setprofile` | omitted | **1, via safepoints** | A trace hook calls back into guest state, so it cannot be a stream observer. Same safepoint mechanism as signals and thread switches. |
| GIL atomicity | 4 | **1** | Green threads switch only at safepoints, so every operation between them is atomic, which is stronger than the GIL. Preemptive shared-memory threads stay 4. |
| Code objects, `__code__`, `compile()` results | 4 | **1** for code objects; **4** only for CPython bytecode fidelity | Code is content-addressed rows; a closure already carries its lambda row id (`ast_walker.cljc:137-146`). Programs reading `co_code` or `dis` output want CPython bytes, which are an implementation detail. |
| `globals()` writes, `del` of a global, `exec` with a namespace, `setattr(module, …)` | 2 | **1, but not with the spike's store-based globals** | See "the store-globals defect" below. |
| Hash randomization | omitted | **4 by invariant, harmless** | Nondeterministic by design; the reference makes no ordering promise that depends on it. Deterministic set order (insertion) is within spec. |
| Generators incl. `send`/`throw`/`close`, `try/finally` inside | 1 | 1, with **3** for close-on-collection | `throw` resumes the saved continuation with a tagged "raise here" value the yield site checks. `close()` triggered by garbage collection needs reclamation. |
| `match`, walrus, comprehension scopes, class body execution, `__set_name__`, `__init_subclass__`, `__class_getitem__`, zero-arg `super`/`__class__`, descriptors, `__slots__`, `__radd__` fallback, `except*`, `__context__`/`__cause__` | omitted | **1** | Desugaring, scope analysis, or prelude. `__class__` is literally a cell captured by methods. `sys.exc_info` is a handler-stack cell. |
| `async`/`await`, async generators, `async with`/`for` | 1 | 1 for the machinery, 2 for what is awaited | Job scheduler is prelude over continuations reading a completion stream. |
| `importlib` hooks, `sys.modules`, `reload` | 2 | 2, with **a design ruling below** | Python module objects must be heap objects; the yin.vm linker delivers code, not namespaces. |
| Float semantics, IEEE, NaN, `-0.0` | 1 | 1, **with a tag requirement** | All three hosts are binary64. But JS cannot distinguish `2` from `2.0`, so the value encoding must tag floats (or ints) explicitly on every host. |
| `str` as code points, normalization | omitted | 1 | Hosts are UTF-16; the data module's code-point operations are required, and normalization tables are Layer 1 data. |
| C extensions, `ctypes`, refcount timing, `sys.getrefcount`, interning seen by `is` | 4 | 4 | Agreed. |

## Q1. Anything genuinely inexpressible?

Nothing in the language reference. The residue is exactly:

- **Nondeterminism**: hash randomization, `id()` layout, `random`, time. These lower to effects or are forbidden by determinism, and the reference never promises their values.
- **Arbitrary-point asynchrony**: signals delivered anywhere. Forbidden by "no implicit control flow"; safepoints are the faithful-enough form.
- **CPython internals**: refcounts, C API, bytecode bytes.
- **Preemptive shared-memory threads**: forbidden by "no shared mutable state"; green threads within one task are the mapping.

One correction to the analysis's own framing: multi-shot continuations are not what Python needs. Python has no re-entry; everything it does is one-shot escape or generator resume. Multi-shot is the VM's generality, not a requirement.

## Q2. Misplacements and omissions

Covered by the table. Two items deserve emphasis.

**The store-globals defect (design, not scope).** The spike lowers module names to `(yin/def py.g/x v)` and reads them as `:variable` (`lower.cljc:15`, `:337-373`). Under Rule R a definition key is a literal symbol, and no store operation accepts a runtime key or deletes. So `globals()[name] = v`, `del x` at module level, `exec(src, ns)`, `setattr(module, name, v)`, `vars(module)` and `sys.modules` manipulation are inexpressible against the store, by design of the store. **Ruling:** a Python module namespace is a dict object in a heap cell (Python's own `__dict__` model); global reads and writes go through it; `yin/def` is reserved for the prelude and builtins. `exec` with a namespace then becomes a compilation request whose lowering targets a given dict object. Cost: a global read becomes `cell/get` plus dict lookup instead of a `:variable` resolve. An un-boxing interpreter can later recover static reads where no dynamic write is reachable.

**`py/kont?` couples the prelude to the continuation representation** (`prelude.cljc:120`, `:171`, `:183`). D7 will break it. The representation-independent form is a per-capture flag cell: allocate `(cell/new :first)` before `%capture`; on the first pass set it to `:re-entered`; an escape delivers its value and the flag tells the passes apart. That uses exactly the box semantics cells were ruled to have and needs no type test. One cell per capture point; reclamation pressure noted.

## Q3. Interactions with decisions and invariants

| Item | Interaction |
|---|---|
| Continuation portability | A suspended generator, a green thread, or a frame record holds a reified continuation in a cell; the encoder refuses those (`engine.cljc:658-659`), so any task with one reachable is non-migratable until reified continuations encode. Slice 1 already refuses every cell-bearing lift. |
| D7 host-typed continuations | `py/kont?` must go (above). Frame introspection must not depend on reading `:env` out of a continuation map; the explicit frame record avoids it. |
| Cells and copy-on-lift | Python threads share one heap and module objects are shared identities, so one Python "process" is one task. `multiprocessing` maps to tasks and streams, which is what it already copies. |
| Reclamation | Exceptions carrying tracebacks retain frames and continuations; generators retain their whole activation. Python programs will leak faster than the counter examples in the cell ruling. |
| Peer observers, derive-don't-persist | Tracebacks derived at the boundary from position side tables are the invariant-respecting default. `sys.settrace` cannot be an observer because it calls back into the guest. Code objects are row addresses, not new tags. |
| Rule R, store | The store-globals defect above. |
| Linker §7.3 | Python module identity lives in the heap; the linker delivers content-addressed code images (prelude, stdlib), never Python namespaces. |
| Owner's pipeline direction | Safepoint insertion (signals, tracing, thread switch, recursion accounting) is a separately attached transformation over the row stream; the naive lowering omits it. |

## Q4. Spike deviations that are mapping limits, not phase-A scope

| Deviation (report §"Known deviations") | Ruling |
|---|---|
| Undefined global surfaces as "Unable to resolve symbol", not `NameError` | **Consequence of store-based globals.** With a dict namespace, `NameError` is the natural miss. |
| Wrong arity is a host error | **Consequence of bare closures as function values.** `bind-params` nil-fills (`engine.cljc:52-57`). Defaults, `*args`, `**kwargs`, `__name__` and function identity all need a function-object wrapper in a cell; arity checking comes with it. Phase B, but it is a representation decision, not scope. |
| Integer overflow past 64 bits on the JVM | Scope: bignum path in the numeric encoding. |
| Not listed: `4/2` on Node | **A hidden deviation.** `py/truediv` is `(/ (* 1.0 a) b)` (`prelude.cljc:269-278`); on JS `(* 1.0 4)` is `4`, so the result is indistinguishable from an int and `print(4/2)` gives `2`. The parity test checks only the equal direction (`1 == 1.0`). The value encoding must tag floats or ints on every host. Add `print(4/2)` to the Node parity set. |
| `isinstance(1, int)` false; dict `==` identity; self-containing list printing loops | Scope. |
| Prelude bundled per unit, so each unit has its own builtin class identities | Scope for phase A, but `isinstance` across units will fail until the prelude is a linked module; note that a linked prelude's class objects are copied per receiving task (D1), which is fine within one Python task. |

## Owner decisions

1. **Module namespaces as heap dicts, not `yin/def py.g/*`.** I recommend it; it costs a dict lookup per global read and unlocks `globals()`, `del`, `exec`, module attributes and `sys.modules`.
2. **Traceback line numbers:** embed literals in frame records (guest-visible, address-sensitive to whitespace) or derive at the boundary from side tables (not guest-visible). I recommend derive by default, embed only under a profile that demands guest-visible `tb_lineno`.
3. **Float tagging:** tag floats or tag ints in the Python value encoding. I recommend tagging floats, since ints are the common case.
4. **Safepoints as an attached interpreter** rather than part of the naive lowering, per the pipeline direction. I recommend attached.
