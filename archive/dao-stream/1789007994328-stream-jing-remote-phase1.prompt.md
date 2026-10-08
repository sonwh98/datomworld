Created-GMT: 2026-09-10 02:39:54 GMT
Created-Local: 2026-09-10 09:39:54 +0700 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: 655d88ba-ab4f-4a94-b813-a980ef9e674f
# Task: dao.jing.remote Phase 1 — the portable core
Role: Stream & Network Engineer
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-10 09:39:54 +0700 | Status: active | Rationale: Stream & Network fallback per team.md (the primary is the orchestrator seat, which implemented Phase 0 and should not also implement Phase 1); implemented every dao.space phase of this sweep

**Implementation task with write authority**, bounded to two files.
Repository `/Users/sto/workspace/datomworld`, branch `dao.stream-redesign-v2`,
at `3228d0e`. The only uncommitted change is a docstring note in
`test/dao/stream/ws/jvm_test.clj` — **do not touch it**.

## Your specification

**`docs/design/dao.jing.remote.implementation-plan.md` §4.1 (Phase 1).**
Its Build / Delete / Prove lists are exact. Read §2 (the invariants, J1-J6 and
N1-N11), §3's D2, D3 and D7, and §4.0 (Phase 0, already committed) before
touching code. Phase 2 is **not yours** — do not touch `connect-content!`,
`content-client`, `default-handlers`, or the v1 requires.

The plan was revised through five rounds against two reviewers of different
families. Where it is specific, it is specific because a reviewer made it so.

## What Phase 1 is

**Behaviour-neutral.** You add a portable core beside the existing code and
change nothing that runs today: constants, `content-descriptor`,
`call-step`, `drain-outboxes`, `retire-call`, `completion-value`, and
`await-established-step`. Nothing is deleted. All three lanes stay green by
construction, because nothing existing calls any of it yet. Phase 2 wires it
up.

Every new function is `.cljc` and portable — no host branches, no reader
conditionals. `call-step` performs stream operations but never waits: it is
the seed of the **blocking driver's loop** that Phase 2's JVM
`connect-content!` will turn, not of a stepped client (that distinction cost
a review round; keep it).

## The five things most at risk, named so you check them

1. **N11 is the invariant with the most history.** Every exit of `call!` must
   leave `:completed` and `:diagnostics` empty and no retired id in
   `:outstanding`. Four refusal exits never reached `call-step`, the only
   drain, and `allocation-failure` loses every outstanding request into
   `:completed` (`rpc.cljc:160-166`). Test 5's assertion is on the **stored
   state**: `(count (:completed state))` is `0` after every exit, never `n`.
2. **`retire-call` must drain its own abandonment completion.** Test 3
   asserts `:completed` is empty *on return* from `retire-call` when the
   entry was still `:unsent`.
3. **Test 3 is the late-correlation pin** — scripted media, no clock. Id 0
   retired, its response appended anyway, id 1 requested and completed: id 1
   gets id 1's value and id 0's response never surfaces.
4. **`await-established-step` observes lifecycle only.** A response element
   arriving before `/established` is consumed as a diagnostic and does not
   establish.
5. **Do not add a `send!` or transport change.** A previous draft claimed
   `send!` answers `closed` with no socket; it does not — the no-socket
   branch returns `false` (`ws/jvm.clj:156-167`). Phase 1 touches no
   transport.

## Ownership

Write only:
- `src/cljc/dao/jing/remote.cljc` — §4.1 Build (additions only)
- `test/dao/jing/remote_test.cljc` — §4.1 Prove (new deftests only; the
  thirteen existing contract deftests and six network deftests **do not
  change** in Phase 1)

Touch nothing else: not `dao.stream.*`, not `coordinate.cljc`, not
`stigmergy_test.clj`, not the plan, not `docs/orchestrator-log.md`.

## Verification you must run and report

- `clojure -M:test`, `bb test:cljs`, `bb test:cljd` — full, unfiltered,
  with **assertion counts**. Confirm `Testing dao.jing.remote-test` appears
  in the Node output.
- `clj -M:cljs -m shadow.cljs.devtools.cli compile demo`.
- `clj -M:kondo --lint src/cljc/dao/jing/remote.cljc`.
- Because Phase 1 is behaviour-neutral, the existing counts should **rise
  only by your new assertions**. Report the before/after and say so
  explicitly. If an existing test changes behaviour, stop and report — that
  means Phase 1 was not neutral.

Reader-conditional trap, since you are adding to a `.cljc` that the cljd
build compiles: `#?(:clj …)` **requires** reach the Dart compiler even though
`#?(:clj …)` bodies do not. Phase 1 should need no conditionals at all; if
you think you need one, say why in the report rather than adding it quietly.

## Report

Write to `collab/1789007994328-stream-jing-remote-phase1.glm-5.3.findings.md`,
starting with Completed-GMT, Completed-Local, Coding-Agent: glm,
Session-ID: 655d88ba-ab4f-4a94-b813-a980ef9e674f. State what you built, the exact commands with outcomes and
counts, any invariant you could not honor and why, and anything left owing.
**Do not stage or commit.** If your budget runs out mid-phase, leave the tree
readable and say precisely what remains.
