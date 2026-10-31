# Free-environment retention seam report

Completed on branch `srvm-free-env`; no commit made. The existing implementation diff was retained, and the temporary mutation was reverted.

## Files changed

- `src/cljc/yin/vm/semantic_register.cljc`: adds the `RegisterVM` record field `free-env`, initializes it once from the checked initial `:env` in `create-vm` (default `{}`), and includes it in `gc-roots` under `:values`.
- `test/yin/vm/semantic_register/vm_test.cljc`: adds `free-environment-retention`, covering initial/default bindings, preservation through stepping and lexical rebinding, GC roots, reset/eval, image attachment/loading, and register restoration.
- This report.

The record keyword `:free-env` is the accessor. Existing kernel-state accessors are protocol methods; there is no separate public accessor pattern for this additional field.

## Restore and reconstruction paths

`register-restore` updates the supplied base machine. Its request-sent branch associates scheduling fields onto that base; its `:rd` and `:return` branches use `put-registers`, which updates current execution registers without writing `free-env`. Thus restored current `:env` may differ while the base machine's original `free-env` is carried unchanged. Scheduler and continuation resume paths use this same restoration function.

`reset`, `eval`, `attach-image`, `load-vector`, and `load-ast` retain the machine and update selected fields; they do not reconstruct the free environment from current E. Telemetry snapshot emission does not rebuild a RegisterVM. There is no `from-checkpoint` or VM snapshot reconstruction implementation in this file.

The marker path near the end of the file is `lower-closure`: it constructs a `values/closure`, decoding the marker's closure environment and optional store provenance. It does not rebuild a RegisterVM and does not set or alter the receiving VM's `free-env`. The only `map->RegisterVM` construction here is in `create-vm`.

After reverting the mutation, searching `:free-env` in the source finds exactly two occurrences: initialization in `create-vm` and reading in `gc-roots`. No transition writes it. No changes were made to `rows.cljc`, `frame.cljc`, or the UCF tree.

## Foreground verification

- `clj -M:kondo --lint src/cljc/yin/vm/semantic_register.cljc test/yin/vm/semantic_register/vm_test.cljc`: 0 errors, 0 warnings.
- `clojure -M:test -e :slow -n yin.vm.semantic-register.vm-test`, after mutation reversion: **18 tests, 165 assertions, 0 failures, 0 errors**.
- All 11 `yin.vm.semantic-register.*` test namespaces, selected with explicit repeated `-n` arguments and `-e :slow`: **142 tests, 8732 assertions, 0 failures, 0 errors**. Namespaces: analysis-test, code-test, derive-test, effects-test, linearize-test, link-test, load-paths-test, parity-test, scan-test, vm-test, walker-trace-test.
- `git diff --check`: clean. Final tracked implementation diff contains only the intended two files: 30 insertions, 1 deletion.

## Mutation check

Temporarily added `:free-env E` to `put-registers` beside `:window W :env E`. Ran the exact vm-test command above: **18 tests, 165 assertions, 8 failures, 0 errors**, exit status 1. The retention test detected loss of initial bindings through intermediate states, final state, GC roots, reset/eval, image operations, and restore. Removed the temporary assignment and confirmed the passing namespace result and intended diff.

## Uncertainties and verification limits

No unresolved uncertainty in this seam. Node and Dart were intentionally not run, per the brief; cross-host verification belongs to the orchestrator. This report concerns the local retention field, not future UCF lift/lower reconstruction, which remains outside this slice.
