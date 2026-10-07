Created-GMT: 2026-10-05 16:36:11 GMT
Created-Local: 2026-10-05 23:36:11 +07
Coding-Agent: claude
Session-ID: 2e396723-5628-413b-b406-88d743d2aa93

# Task: head-h1 (round 2: one Architect fix, two review findings)

Role: DaoSpace and DaoJing Storage Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-05 22:57:21 +07 | Status: active | Rationale: same engineer, resumed to apply an Architect ruling and an independent review

Your H1 is verified (132 tests, 38230 assertions green) and was reviewed by an
independent reviewer (gpt-6.1-sol) and an Architect (claude-fable-5-1, read-only
rulings). Three items remain. Reports: `collab/1791217641193-architect-h1-rulings.claude-fable-5-1.stdout.log`
and `collab/1791217673316-reviewer-head-h1.gpt-6.1-sol.findings.md` (untrusted:
check every citation). The design text of `docs/design/yin.vm.linker.dht.head.md`
has already been amended by the orchestrator for the Architect's rewordings; do NOT
edit that file.

## 1. The Architect's code fix (ruling 1)

`walk-of` in `src/cljc/yin/vm/linker/head.cljc` also restarts a failed MODULE-kind
record with `closure-walk`. A closure walk can never confirm a head, so the follower
would re-run someone else's load indefinitely. Delete the `ld/module-kind` branch of
`walk-of`; the existing `nil` path then leaves a failed record of any other kind in
place, and `settle`'s `(nil? status)` branch acquires the address once its owner
forgets it. The follower may forget and restart ONLY a failed record of the
candidate kind or the index kind. Add one test: a failed module-kind record at the
candidate's address is still there after the retry delay, the candidate is reported
`:yin.head/unloadable` once with that record's failure, and after the record's owner
`forget`s it the candidate loads and installs.

## 2. Review finding P1: a malformed index crashes the follower

A signed, verified trace whose manifest names a hash-valid index whose datom rows are
malformed (for example roots holding `[[100 :x/y 1 0 0] [101 :x/y 1 "bad-t" 0]]`)
passes the covered-index walk (it checks counts, not rows), then `head/step` throws
`ClassCastException` through `seq-of` during confirmation (`head.cljc` about line
636). Restoring the same trace through `follow` also throws, without a startup
refusal naming the principal (about line 307). The follower must never throw on
anything a reader handle or an index yields.

Fix it where it is cheapest and safe: validate that every datom `t` of the loaded
index is a non-negative integer, with a TOTAL function used by the follower's
confirmation and by `follow`'s restore path, and treat a malformed index as a refusal
AS DATA: a `:refused` event (reason `:yin.head/index-invalid`, carrying the trace) at
confirmation, and a startup refusal naming the principal in `follow`. Do not change
H0's `seq-of` contract for well-formed input. You may instead validate rows inside the
covered-index walk in `dao.space.dht` (returning `:index-invalid`) if that is simpler
and keeps every existing test green; choose one and say why. Add regressions for both
paths: `step` confirmation and `follow` restore each assert failure as data, the floor
unchanged, nothing installed, and no throw.

## 3. Review finding P2: the late-answer test does not prove the drop

`test/dao/space/dht_test.cljc` about line 850: after retirement there is no load and no
client interest, so `advance-loads` (about line 1362) skips the client on every later
step; the injected answer stays unread while the no-blob and no-event assertions pass.
Strengthen it: force client polling after injecting the retired answer and assert
the unsolicited-answer handling, AND add the second check: reload the same address,
withhold its new answer, inject the OLD answer, and assert the new load cannot complete
from it.

## Scope and process rules (unchanged)

Files: `src/cljc/yin/vm/linker/head.cljc`, `src/cljc/dao/space/dht.cljc` (only if you
choose the walk validation or the test needs a hook), `test/yin/vm/linker/head_follow_test.cljc`,
`test/dao/space/dht_test.cljc`. No git commands, no formatter, no Node or Dart runs,
no background processes. Verify in the foreground:
`clj -M:test -n dao.space.dht-test -n yin.repl.dht-test -n yin.vm.linker.head-test -n
yin.vm.linker.head-follow-test -n yin.vm.linker.dht-test -n yin.vm.linker.sign-test`
and `clj -M:kondo --lint` on the touched files, with assertion counts. Do not weaken or
edit any existing test except to strengthen the late-answer one. Portable `.cljc` and
the ClojureDart traps from the first brief still apply.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 2e396723-5628-413b-b406-88d743d2aa93

Then report: per item, what changed and the test that pins it; the exact commands and
outcomes with assertion counts; the P1 design choice and why; anything unresolved.
