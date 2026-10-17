# Findings: yang.python Phase C2 Slice S5 (claude-sonnet-5-5)

Worktree: `yang-python-c2-s5`. Nothing committed; changes are in the working tree.

## Implemented
- `prelude.cljc`
  - User-defined iterator classes (ruling 8). New prelude functions: `py/guarded`, `py/user-adv`, `py/user-step`, `py/user-iterator?`, `py/user-iter`, `py/user-forward`, `py/user-delegate-step`.
  - Wiring: `py/iter-at`, `py/iterable`, `py/iter`, `py/next`, and `py/delegate-step` (the builtin path is unchanged; instances route to `py/user-delegate-step`).
  - `__iter__` and `__next__` are looked up on the type via `py/class-lookup`, so an instance attribute of that name is ignored.
  - The `__iter__` result is validated: `iter() returned non-iterator` is a `TypeError`.
  - `StopIteration` (and subclasses) is caught only around the one advancement, with its `.value` kept for `yield from`. Other exceptions propagate, and a `StopIteration` raised in a loop body is not swallowed.
  - `yield from` forwards optional `send`, `throw` and `close` of the delegate's type (PEP 380).
  - `py/snapshot-obj` now renders `:generator` as `{:py/generator name}` and `:iterator` as `{:py/iterator "iterator"}`. Before, they snapshotted as `:py/object`.
- `render.cljc`: `<generator object NAME>` and `<iterator object>`.
- Tests in `e2e_c2_test.clj`, all four VMs, expected output checked against CPython 3.9.6:
  - `user-iterator-class-test`: shared iterator, break, unpacking, `next` default.
  - `user-iterator-special-lookup-test`: generator-returning `__iter__`, bad `__iter__`, missing `__iter__`, instance-only `__next__`.
  - `user-iterator-exceptions-test`: subclassed `StopIteration`, body `StopIteration`, other errors.
  - `user-iterator-yield-from-test`: return value, `send`/`throw`/`close` forwarding.
  - `generator-snapshot-render-test`.
  - `dropped-generators-are-collected-test`: live heap after a forced collection is identical for 5 vs 60 dropped suspended generators.
- `float_address_test.cljc`: five prelude-address goldens moved, as the spec expects ("prelude-profile bump", section 8.5.3). The prelude root is now `blake3-b4845a80...`.

## Verification
- JVM `clojure -M:test -n yang.python.antlr.e2e-c2-test` (includes the ^:slow tests): 38 tests, 273 assertions, 0 failures.
- JVM `clojure -M:test -r 'yang.python.*' -e :slow`: the first run had 5 failures, all of them the address goldens. After updating them, `float-address-test` is 12 tests, 0 failures. I did not re-run the whole `yang.python.*` set after the golden update, so I have not seen it fully green in one run.
- Node `bb test:cljs`: 3807 tests, 0 failures. `e2e_c2_test.clj` is JVM-only, so Node does not exercise the new tests; it only confirms the prelude-parity and address tests there.
- kondo: no new findings. The 3 existing findings are in untouched code (`module/host-export-profile`, `data/float-value`, and `register-data-module` arity at `e2e_c2_test.clj` `opts`).
- Dart (`bb test:cljd`) was NOT run.

## Deviations and gaps
- The generator renders without CPython's `at 0x...` address, since a snapshot has none (the spec calls for deterministic diagnostic data). Exact CPython text is therefore not matched. Tests assert the deterministic form directly.
- `repr(gen)` and `str(gen)` in guest code still raise `NotImplementedError` (`py/str-conv`: container and object text is out of scope, as before). Only `print` goes through the snapshot renderer.
- `next(user_iter)` re-raises a fresh `StopIteration(value)` rather than the original exception object.
- Lift refusal for suspended generators: no code change and no new test. I did not verify that existing tests cover it.
- Unchecked:
  - Close-on-collection stays unimplemented (ruling 6).
  - The retention test shows constant live heap across dropped generators but does not separately inspect cell references inside captured environments.

## Leftover
- `collab/node.out` is the Node log, untracked. `collab/` is never committed.
