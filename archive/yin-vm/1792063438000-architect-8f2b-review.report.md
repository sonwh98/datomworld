Completed-GMT: 2026-10-15 11:23:58 GMT
Completed-Local: 2026-10-15 18:23:58 +07
Coding-Agent: claude (claude-fable-5-1)
Session-ID: not exposed to this session (see the harness stdout log beside the prompt)
# 8f-2b Architect review: the uncommitted keyword-rename diff against my own edit list

Reviewed: worktree `datomworld-srvm-p8f2`, branch `srvm-phase8f2` at `b682f42b`, 15 modified tracked files, uncommitted. Inputs: `collab/1792063220000-engineer-8f2b-glm.report.md` (glm verification of codex's partial run) and my edit list in `collab/1792061458000-architect-8f2-review.report.md`. Read-only except this report; I ran one JVM test selection, which writes nothing.

## Diff matches the edit list exactly and does nothing else

I read every hunk of `git diff` (71 insertions, 92 deletions). Every change is one of: the two production keyword edits, the two telemetry-test edits, the four backend-table deletions with their two consumer-line edits, the 34 lane-label keyword substitutions, the two deftest renames, the `RegisterVM` to `SemanticVM` record rename (five sites plus the one diagnostic string), and the prose pass over the 23 listed lines plus the two `layout.cljc` ambiguities. No hunk touches a hash literal, a golden fixture, `docs/design`, or `test/yang`. No hunk contains any change outside those categories. `git grep -F ':semantic-register' -- src test` and the wider `semantic[-_]register` grep over `src test bin bb.edn deps.edn shadow-cljs.edn .clj-kondo .github package.json` return only `continuation_handoff.cljc:178,207` (`semantic-registers`, the register file, explicitly out of scope) and the nine `yin.vm.semantic-register-vm*` design-doc citations reserved for 8g.

## The four questions the brief asked

**1. Deleted backend-table entries: is any assertion lost?** No. In each table the kept `:semantic` entry is equivalent to or a strict superset of the deleted `:semantic-register` entry:

- `rule_r_test`: `semantic-run` (lines 95-101) is byte-for-byte the deleted lambda (`semantic/load-ast` over `semantic/create-vm` with `vm/ast-contract`, then `vm/run`).
- `ffi_test` and `continuation_invoke_test`: the kept entries go through `vm/fresh-code-loader semantic/load-ast vm/ast-contract`, which `vm.cljc:298-307` defines as exactly `(load vm code contract)`; same call, same stamp.
- `linker_require_test`: the kept `:semantic` entry (lines 158-169) has `:image`, `:vm`, `:continue` and real `linker/semantic-format` obligations; the deleted entry had the same `:vm`/`:continue` and `(constantly [])` obligations. The 24 `(doseq [backend (keys backends)] ...)` sites now all include `:semantic` where the stub used to be excluded at one of them; the lane is green so the real obligations pass that test too, which is more coverage, not less.

**The `vm/step` question specifically.** The deleted `continuation_invoke_test` runner was the only entry in that file driving the call/cc programs through a bounded `vm/step` loop rather than `vm/run`. Those are different outer loops over the same `vm-hot` (`semantic.cljc:331-333`: `step` is `vm-hot` with budget 1, `run` is the scheduler over `vm-hot` with no budget), so the step loop additionally proves register round-tripping through machine fields at every instruction boundary. That fact is still asserted, with the same loop shape and the same four facts, in `test/yin/vm/semantic/effects_test.cljc`: `bounded-run` (lines 294-301) is the identical 10000-step `vm/step` loop over `parity/register-machine` (which is `sr/create-vm` + `sr/load-vector`, `parity_test.cljc:41-45`, so the same kernel), and it drives `escape-discards-pending-frames-test` (abortive), `tail-position-invocation-test`, `re-entry-after-return-test` (multi-shot, three re-entries) and `store-not-rolled-back-test` (store survives invocation) plus `continuation-restores-captured-environment` with a full per-step trace. The only thing dropped is the `load-ast` spelling of the load on that path, and `load-ast` is `project-datoms` then `load-vector` (`semantic.cljc:322-326`), still exercised by every kept `:semantic` entry. Nothing is lost.

**2. `SemanticVM` record rename: any reader of the record name?** None that this diff can break. Repo grep (excluding `archive/`): the only live references to `RegisterVM` are `DebruijnRegisterVM` (the de Bruijn backend, correctly untouched) and the historical `src/cljc/yin/vm/docs/bytecode-vm-optimization.md`, which is marked historical and not in scope. The string `"SemanticVM"` already appears in `yin/repl.cljc:149` (`:semantic "SemanticVM"`) and is pinned by `repl_test.cljc:86,143,198,284`, `ast_index_test.cljc:210`, `index_test.cljc:427`, `dht_process_test.clj:529`; those are the REPL's own model label, independent of the record symbol, and they were already saying `SemanticVM` before this diff, so the rename makes the record agree with the label rather than diverging from it. No `pr-str` of the record is persisted (UCF rows carry fields); no fixture under `test/` or `resources/` contains `RegisterVM` or `yin.vm.semantic.RegisterVM`. The diagnostic string at `semantic.cljc:335` is pinned by no test (string exists only at its definition). `jfr_benchmark_analysis.md` cites `yin.vm.semantic.SemanticVM` for the old stack-shaped record; that is a dated profiling note and now coincidentally correct for the new record's class name.

**3. Telemetry model keyword: any consumer or fixture expecting `:semantic-register`?** None. Consumers of `:vm-model` / `:vm/model` are `telemetry.cljc:111,258,267` (writes whatever model was installed), `ast_walker.cljc:138`, `ffi.cljc:327` (copies it into ex-data), `yin/repl/query.cljc:279` (tests only `= :ast-walker`), `vm.cljc:1983,2145`, and `continuation_handoff.cljc:33-44` (`vm-key->model`, returns `:ast-walker`, out of scope). The one test reading the datom, `telemetry_test.cljc:216-218`, compares `[model]` symbolically from the table key, so the renamed key drives it. No `.edn`/`.json`/`.txt` fixture anywhere outside `archive/` and `docs/` contains `:semantic-register`.

**4. Prose pass.** All 23 listed lines plus `layout.cljc:4,20` are done and say "semantic kernel/machine/VM/evaluator"; no prose line now ambiguously calls this kernel "the register machine". `continuation_invoke_test`'s docstring ("all four VMs") was already written for four and is now accurate.

## Verification I ran

- `clojure -M:test -e :slow -n yin.vm.telemetry-test -n yin.vm.rule-r-test -n yin.vm.continuation-invoke-test -n yin.vm.ffi-test -n yin.vm.linker-require-test -n yin.vm.linker-test -n yin.vm.semantic.safepoint-harness-test -n yin.vm.semantic.layout-test -n yin.vm.semantic.effects-test -n yin.vm.semantic.vm-test`: **262 tests, 6055 assertions, 0 failures, 0 errors** (the engineer's eight namespaces plus the two that carry the `vm/step` coverage).
- Engineer's whole JVM quick tier (4005 tests, 249625 assertions, green) and kondo (0 new diagnostics) accepted as reported; the 242-assertion drop versus 8f-2 is the deleted duplicate doseq bodies, test count unchanged, as the engineer explained.
- Node and Dart lanes: not run here; this diff is keyword, symbol and prose only, with no reader-conditional or host-specific code, so I do not require them before this commit. The landing rule (three hosts per slice) still applies at the 8f merge.

## Findings

- P1: none.
- P2: none.
- P3 (not blocking, for 8g): `src/cljc/yin/vm/docs/bytecode-vm-optimization.md` and `jfr_benchmark_analysis.md` still talk about `RegisterVM`/`SemanticVM` in their historical sense; the nine `yin.vm.semantic-register-vm*` doc citations remain for 8g as already recorded.

## Verdict

The diff is exactly the 8f-2b edit list, nothing more, with no identity moved and no assertion lost. Commit it as the second 8f-2 commit on `srvm-phase8f2`.

SIGN-OFF
