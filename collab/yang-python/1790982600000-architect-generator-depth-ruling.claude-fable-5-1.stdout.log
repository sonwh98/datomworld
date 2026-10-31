Completed-GMT: 2026-10-02 22:36:00 GMT
Completed-Local: 2026-10-03 05:36:00 +0700

# Architect ruling: generator depth (safepoint slice 2)

Read-only; nothing edited or executed. Evidence is the uncommitted tree at `/Users/sto/workspace/datomworld-py-safepoint2`: `prelude.cljc:47-74`, `:117-143`, `:241-315`; `safepoint.cljc:18-31`, `:80-104`; `safepoint_test.cljc:545-565`; the `yang.antlr.md` diff.

## 1. Acceptable, or must it match CPython?

It must match CPython, but not for fidelity's sake. The implemented behaviour measures the wrong thing.

The call depth exists to bound the stack of Python frames on the **current continuation**. An escape jumps back to a point on the same stack, so restoring the record saved at capture is correct there. A generator resume is not an escape: it **splices** the generator's frames onto the resumer's stack. The continuation that runs after `gen-switch` is the generator's own frames on top of whoever resumed it (`prelude.cljc:285-291` stores the resumer's record as `:caller-ctx` on every crossing, which is exactly that structure). "Depth of the first resumer" is therefore not a property of the continuation being run; it is a historical accident of which call primed the generator.

The divergence bites in both directions, and one direction is harmful:

- A generator primed 900 frames down and consumed at the top, or by a worker thread in slice 4, runs at depth 900 and raises a spurious `RecursionError` where CPython runs fine. With threads this is the ordinary case: thread A primes, thread B consumes.
- A generator primed at the top and consumed deep runs unbounded where CPython raises. Benign here, since continuations are heap data, but still wrong.

The engineer is right that the fix changes the binding rule. The corrected rule is cleaner than the current one, not a patch: the record gains a `:base` slot that **belongs to the crossing, not to the capture**.

- A generator's `:depth` and its frames' recorded depths (`prelude.cljc:121-125`) are **relative** to its base.
- Every crossing into a generator (`gen-switch`, start or resume) sets `:base` to the resumer's absolute depth (`resumer :base` + `resumer :depth`). Crossings out (`gen-exit`, `yield-raw`) restore the caller's record whole, as now.
- An escape restores the saved record **except `:base`**, which it takes from the current record: `(assoc saved :base (get current :base))`. A `try` established in resume 1 and unwound by `throw()` in resume 2 then lands at resume 2's base. The escape paths already read the current record to unwind (`prelude.cljc:197`), so this costs no extra effect.
- `py.sp/enter` compares `(+ base d)` with the limit: one addition.
- Top-level code and each thread have `:base` 0.

## 2. Invariant, test, slice 4

**Sentence for `yang.antlr.md` §8.5.2** (replacing "A generator owns its whole record … on top of the current resumer" in the diff):

> The call depth is the number of Python function frames on the current continuation. A capture point saves the dynamic context and an escape restores it, because an escape returns to a point on the same stack. A generator crossing is not an escape: it splices the generator's frames onto the resumer's, so a generator's record holds its depth relative to a base, every crossing into it sets the base to the resumer's absolute depth, and restores leave the base alone. The base is owned by the crossing, never by the capture. This matches CPython, which counts a resumed generator on top of whoever resumes it.

**Regression test**, replacing `generator-depth-test` under limit 100, with the generator frame itself counting one:

- First resumed 21 frames down: probe inside finds 79.
- Resumed from the top: probe inside finds 99; resumed 50 down: 49.
- After each crossing back, probe at the top finds 100 (unchanged).
- Splice-restore: a `try: yield` entered during resume 1 at depth 21 and unwound by `throw()` during resume 2 from the top; the handler's probe finds 99, not 79.
- Additive chain: `yield from` through k nested generators consumed at the top; probe in the innermost finds 100 − k.
- Slice 4 adds: a generator primed by one thread at depth 900 and consumed by another at depth 1 runs without `RecursionError`.

**Slice 4.** The context-swap design is unaffected and, in fact, becomes correct only under this ruling. A thread is a root stack (`:base` 0) and a switch swaps the whole record, exactly as planned. Under the implemented rule, a generator carries its priming thread's depth into any thread that consumes it; under the ruled one, the base is that thread's depth. No change to the swap, one change to what the record means.

## 3. Adjacent choices

- **`RecursionError` under `RuntimeError` in the base prelude** (`prelude.cljc:1691`): confirmed. That is Python's hierarchy, and the KeyboardInterrupt ruling puts the class in the base prelude.
- **Limit cell default 1000, `ValueError` for < 1, `TypeError` for non-int, `bool` accepted** (`safepoint.cljc:49-51`, `:96-104`): confirmed. The `ValueError` message is CPython's verbatim; the `TypeError` message differs and need not match. One omission to record, not to block on: CPython also raises `RecursionError` when the new limit is at or below the current depth. Add it when `sys` becomes reachable.

Ruling: The implemented generator depth is rejected. Depth measures the current continuation, and a generator resume splices stacks rather than escaping, so the dynamic-context record gains a crossing-owned `:base`: a generator's depth is relative to it, every crossing into the generator sets it to the resumer's absolute depth, and escapes restore the saved record with the current base left alone. The §8.5.2 sentence and the tests above pin it, with the 79/79 expectation becoming 79/99. The thread-slice swap design stands unchanged and becomes correct under this rule. `RecursionError` under `RuntimeError`, the default of 1000 and the `setrecursionlimit` validation are confirmed. Slice 2 should not be committed until the base change and its tests are in and the lanes are green.
