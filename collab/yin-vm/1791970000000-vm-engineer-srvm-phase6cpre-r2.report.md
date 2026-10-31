Completed-GMT: 2026-10-11 09:13:11 GMT
Completed-Local: 2026-10-11 16:13:11 Asia/Bangkok

# 6c-pre round 2: close the reviewer's test gaps

Engineer: claude-opus-5-5 (fresh session; the earlier codex thread ran out of
credits). Worktree `/Users/sto/workspace/datomworld-srvm-p6cpre`, branch
`srvm-phase6cpre`, HEAD 7f1cf08e. Nothing staged or committed.

Timestamp note: the machine clock (`date -u`) read 09:13:11 GMT when this was
written, seven minutes before the brief's own Created-GMT 09:20:00. The header
records the clock as read.

Provenance. On arrival the test edits for F2 and F3 were already on disk (from
the earlier codex/other r2 attempts) together with a mutation driver
`collab/6cpre-r2-mutations.py` and its logs. I read the diff, checked it
against this brief, found it complete, and then re-ran every mutant and every
lane below myself in this session. The only change I made to the driver was its
per-run timeout (55 s -> 600 s). All logs named below are from my runs and
overwrote the earlier ones.

Source untouched: `git diff --quiet HEAD -- src/` passes; `cmp
collab/6cpre-r2-stack.backup.cljc src/cljc/yin/vm/debruijn/stack.cljc` identical.
`handoff.cljc` not edited. Only tracked change:
`test/yin/vm/debruijn/stack_tail_completion_test.cljc` (+12 / -2).

## Known limitation (orchestrator ruling on F1)

F1 accepted as-is. The v2 handoff stack lane does not carry `:resume-mode`.
The v2 carrier is the old carrier, frozen until the canonical carrier of phase
6 supersedes it and phase 8 deletes it. After a v2 handoff a stack tail park
resumes by the old push-at-pc path, exactly as on master today, so there is no
regression relative to master. `handoff.cljc` was not edited.

## Items

### F2: empty-K halt with a non-nil value and a non-nil store context

Change: new test `empty-k-halts-with-non-nil-result-and-clears-store`. It takes
`frame-entry` (which sets `:store-of :dead-module` and a non-empty dead stack),
overrides `:continuation []`, and calls `stack/stack-restore` with 7. It asserts
`(:stack done)` = `[7]`, `(vm/value done)` = 7, `(:halted? done)` true,
`(:store-of done)` nil. The nil-delivery case `empty-k-halts-with-result` is
kept unchanged.

Mutants (driver `collab/6cpre-r2-mutations.py`; byte backup taken and `cmp`
verified before the first mutant; restored and `cmp` + `git diff --quiet HEAD
-- src/` checked after each; each run `clojure -M:test -n
yin.vm.debruijn.stack-tail-completion-test`, exit 1):

| Mutant | Mutation | Killed by | Counts | Log |
|---|---|---|---|---|
| M08 | halt value from `(peek (:stack vm))` instead of `val` | `empty-k-halts-with-non-nil-result-and-clears-store` (lines 54, 55) | 10 tests / 29 assertions, 2 failures, 0 errors | `6cpre-r2-mutant-M08.log` |
| N04 | seam-only: empty-K delivers constant nil | `empty-k-halts-with-non-nil-result-and-clears-store` (lines 54, 55) | 10 / 29, 2 failures, 0 errors | `6cpre-r2-mutant-N04.log` |
| M09 | empty-K halt keeps `:store-of` | `empty-k-halts-with-non-nil-result-and-clears-store` (line 57) | 10 / 29, 1 failure, 0 errors | `6cpre-r2-mutant-M09.log` |
| N05 | seam-only: empty-K halt keeps the entry's `:store-of` | `empty-k-halts-with-non-nil-result-and-clears-store` (line 57) | 10 / 29, 1 failure, 0 errors | `6cpre-r2-mutant-N05.log` |

### F3: inner frame's return pc differs from the parked pc

Change: in `frame-entry` the inner frame's `:return-pc` is now 2 (was 4, equal
to the parked pc). `return-pops-only-inner-frame` now asserts `(= 2 (:pc
done))`. No other test asserted on that pc.

| Mutant | Mutation | Killed by | Counts | Log |
|---|---|---|---|---|
| N08 | seam-only: pc left at the entry pc after the transition | `return-pops-only-inner-frame` (FAIL at line 64); also `tail-in-operand-position-skips-layout` ERROR (as before) | 10 tests / 28 assertions, 1 failure, 1 error | `6cpre-r2-mutant-N08.log` |

Summary `collab/6cpre-r2-mutations.log`. Final state after the driver: src
`cmp` identical to backup and to HEAD.

### F4: no change

Pre-existing `:halted? false` line (reviewer's M18) left as is, per the brief.

### F5: correction of the round-1 mutant claim

Round 1 (`collab/1791920000000-vm-engineer-srvm-phase6cpre.report.md`) says
"No mutant survives" and "No mutation survived". Correct statement: none of the
eight mutants the author ran survived (logs `collab/6cpre-mutation-1..8.log`).
That claim did not hold for mutants in general. Of the reviewer's 26 mutants,
five survived (M08, M09, M18, N04, N05). They showed three distinct weaknesses:
the wrong halt value, the kept store context (both F2), and the pre-existing
`:halted? false` line (F4). After this round, M08, N04, M09 and N05 are killed
(table above). M18 still survives, by decision (F4). I did not edit the
round-1 report; this paragraph is the correction.

## Lanes (all foreground, this session)

| Lane | Command | Outcome | Log |
|---|---|---|---|
| JVM, six suites (includes the new namespace) | `clojure -M:test -n yin.vm.debruijn.stack-tail-completion-test -n yin.vm.debruijn.stack-test -n yin.vm.debruijn.stack-effects-test -n yin.vm.debruijn.stack-parity-test -n yin.vm.semantic-ffi-test -n yin.vm.continuation-invoke-test` | PASS: 6 namespaces, 88 tests, 825 assertions, 0 failures, 0 errors (reviewer baseline 87 / 821; +1 test, +4 assertions = the new F2 test) | `collab/6cpre-r2-jvm.log` |
| Node, new namespace | `clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^yin\\.vm\\.debruijn\\.stack\\-tail\\-completion\\-test$"}'` | PASS: build 0 warnings; "Testing yin.vm.debruijn.stack-tail-completion-test"; 10 tests, 29 assertions, 0 failures, 0 errors | `collab/6cpre-r2-node.log` |
| kondo | `clj -M:kondo --lint test/yin/vm/debruijn/stack_tail_completion_test.cljc src/cljc/yin/vm/debruijn/stack.cljc` | errors 0, warnings 0 (one pre-existing info: `stack.cljc:52:29 Unused excluded var: eval`) | `collab/6cpre-r2-kondo.log` |
| cljstyle | `cljstyle check /Users/sto/workspace/datomworld-srvm-p6cpre/test/yin/vm/debruijn/stack_tail_completion_test.cljc` | NOT RUN: the harness refused the command, error "This command requires approval" (an earlier attempt with output redirection was refused with "The following part requires approval: cljstyle check test/yin/vm/debruijn/stack_tail_completion_test.cljc") | none |
| Dart | (not attempted, per brief: orchestrator runs it) | NOT RUN | none |

## Open

- cljstyle on the test file must be run by the orchestrator, because the
  harness refused it here.
- Dart lane: orchestrator.
- M18 survivor (F4) accepted by brief.
- F1 recorded above as a known limitation.
