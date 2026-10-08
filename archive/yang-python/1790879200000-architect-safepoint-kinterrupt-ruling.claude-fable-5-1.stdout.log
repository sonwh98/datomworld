Completed-GMT: 2026-10-01 19:13:29 GMT
Completed-Local: 2026-10-02 02:13:29 +0700

# Architect ruling: KeyboardInterrupt placement in safepoint slice 1

Read-only; nothing edited or executed. Evidence is the uncommitted tree at `/Users/sto/workspace/datomworld-py-safepoint1`.

## 1. Is the lazy factory acceptable?

No. It breaks two of the three invariants, and one of them is my own error to correct.

- **Naive is no longer correct.** `KeyboardInterrupt` is a Python builtin whether or not anything can deliver a signal. `except KeyboardInterrupt:` and `except (KeyboardInterrupt, SystemExit):` are common in ordinary programs. Under the factory, any naive program that reads the name dies with a host-level "Unable to resolve symbol" (`lower.cljc:392-396`).
- **Shadowing is broken too.** The thunk application is an operand of `py/global-or`, so it is evaluated before the module dict is consulted. A module that defines its own `KeyboardInterrupt` still fails.
- **The canonical program is no longer closed.** The naive lowering now requires the hook namespace (`lower.cljc:45`) and emits a name only the hook prelude defines. The canonical tree's meaning then depends on which attached interpreter's runtime is loaded, which is the coupling the attached-interpreter decision exists to prevent.
- **The transparency law fails for these programs.** `noop-uast` defines only `py.sp/loop` and `py.sp/call` (`safepoint.cljc:95-98`), so a program reading the name fails under no-op hooks as well.

Scope correction to the brief: the name is not resolved in every program, only in programs that read `KeyboardInterrupt`. That does not change the ruling.

My slice-1 finding said to add the class "in the hook prelude". That was wrong. The class is language surface; only delivery is a safepoint concern.

## 2. The correct alternative

**Candidate (b): `KeyboardInterrupt` is a plain builtin class in the base prelude.**

- Add `["KeyboardInterrupt" 'py.b/KeyboardInterrupt 'py.b/BaseException]` to `builtin-classes` (`prelude.cljc:1478-1494`). `builtin-names` picks it up automatically, so the lowering reads it like every other builtin, with the module dict winning.
- Delete `builtin-thunks`, the factory, the `py.sp/keyboard-interrupt` cell, and the `safepoint` require from `lower.cljc`.
- `py.sp/deliver` raises `(py/call py.b/KeyboardInterrupt [])`. The reference sits inside a function body, so it resolves at call time, after the bundled base prelude has loaded. The engineer's load-order constraint is still satisfied without a factory.

Cost: one table entry and one class cell at load. The prelude rows are identical in every program and shared by content address, so the cost does not scale with programs.

Why not the others:

- **(a) load after the base prelude** does not fix naive correctness, because the naive run has no hook prelude at all. It becomes available when the prelude is a linked module, and is fine then, but it is not this fix.
- **(c) scoping the name** hides the symptom; a naive program still needs the builtin.

The same rule will apply to `RecursionError`, `SystemExit` and `GeneratorExit`: the class goes in the base prelude, the raising mechanism goes wherever it belongs.

## 3. Invariant for `yang.antlr.md` §8.5.2

> The canonical program is closed over the base prelude and the host modules: every free name in it resolves in a composition that loads nothing else, and the lowering emits the same tree whether or not any attached interpreter exists. Language-visible surface (builtin names, exception classes) lives in the base prelude even when only a hook can raise it. A hook prelude defines names only in its own namespace (`py.sp/`), which the lowering never emits and guest source cannot spell. Because it loads before the bundled base prelude, it may allocate only its own cells and cursors at load, and may reference base names only inside function bodies.

## Regression tests

- **Naive builtin:** `try: raise KeyboardInterrupt` / `except KeyboardInterrupt: print('k')` prints on four VMs with no hook prelude; `except Exception` does not catch it.
- **Shadowing:** `KeyboardInterrupt = 1; print(KeyboardInterrupt)` prints `1`, naive and derived.
- **Closure:** for the e2e corpus, the canonical tree's free names are a subset of base-prelude definitions, host names and primitives, and no `py.sp/` symbol appears. This replaces the second half of `fail-closed-test` (`safepoint_test.cljc:422-426`), which currently pins the factory and must be inverted.
- **Hook namespace:** every key the hook prelude defines is in `py.sp`.
- **Transparency:** add a program that reads `KeyboardInterrupt` to the `every-vm=` corpus, so it runs naive and under `noop-uast`.
- **Identity:** keep the existing delivered-signal tests (`e2e_test.clj:525-553`); they now prove the class `py.sp/deliver` raises is the one the name reads.
- **Load order:** the hook prelude alone loads on a VM that has only the signal stream and no base prelude.

Ruling: The lazy factory is rejected. `KeyboardInterrupt` is Python language surface, so it becomes a plain entry in the base prelude's `builtin-classes` under `BaseException`, read through `builtin-names` like every other builtin. The hook prelude keeps only `py.sp/*` names and raises the base class from inside `py.sp/deliver`. `builtin-thunks`, the factory cell and the lowering's dependency on the safepoint namespace are removed, and the closure, shadowing, naive-builtin and transparency tests above pin it. Slice 1 should not be committed until that change is made and the lanes are green.
