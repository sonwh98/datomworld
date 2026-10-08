Completed-GMT: 2026-10-07 20:48:00 GMT
Completed-Local: 2026-10-08 03:48:00 +07
Coding-Agent: claude (opus-5-5)

# UCF M-next D16-prep: the safepoint harness and the 14.2.4 rows that need no composition

Worktree `/Users/sto/workspace/datomworld-d16prep`, branch `ucf-d16-prep`. No git writes.
The diff is confined to `test/yin/vm/ucf/*`. Production files, `compose.cljc` and `yin/repl/*` are untouched.

## Summary

All three contract items have test rows, green on JVM:

1. **The safepoint harness.** It runs four corpus segments into each of the eight liftable 7.4.1 rows. The effectful-call row is covered by two callees, `require` and `stream/next!`, each in an ordinary and a tail call. Every row runs on both fork body versions: version 0 (semantic) and version 2 (semantic, stack, register, walker).
   - Each case lifts the machine, lowers it into a fresh VM, and compares the result and effect trace with a separately built reference run.
   - It also checks that `lift(lower(frame))` reproduces the canonical body.
   - Rows beside the matrix: one pc reached from two activations, handed off at each; a nonempty ready queue; a kept FFI response cursor shared by two waiters; a real held immediate (D4 `:observe`), which refuses export.
2. **14.2.4 rows 2, 4 and 5 (fencing halves).** Every tenure lowers real version-1 checkpoint bytes and runs the landed writer (D11), reader (D12) and export (D8) against the real authority and front.
3. **Clause audit (1–5, 9).** The audit found 14 partial or missing items. This round closed the test-only ones. The rest need a production seam or are production gaps, listed below.

The harness found six production defects (D0–D5) and one design question (Q1). Each defect is pinned as an explicit canary, so the suite stays green and the canary fails the day the defect is fixed (see "Open defects").

## Changed files

| File | Change |
|---|---|
| `test/yin/vm/ucf/safepoint_harness_test.cljc` | **new**. 8 deftests: the harness matrix, the tail/ordinary opcode check, two-activations-one-pc, not-quiescent, shared FFI response cursor, held immediate, writer source-acceptance canary, live-primitive canary. Also holds the `open-defects` table and the `check` helper. |
| `test/yin/vm/ucf/fencing_rows_test.cljc` | **new**. 4 deftests: row 4 (three crash cuts), row 5 (evicted kept cursor: with inputs, without, divergent), clause 4 (carried id on `:put` / `:ffi-request` / `:link-request`), row 2 (retained id through exporting and carrier retry). |
| `test/yin/vm/ucf/handoff_v1_test.cljc` | +6 deftests closing audit gaps: version 3 on a v1 body; every header key except the origin on a halted root; every header key on a live child; seq above the counter (4 and 2^52-1); next-op-seq and op-id seq of 2^52 and -1 through `resume-task`; phase {`:running`, `:parked`} × child {blocked, halted} on both versions, with the lower preserving phase and parent. |
| `test/yin/vm/ucf/v2_support.cljc` | Shared helpers: `stream-blind` and `run-beside` (moved out of `handoff_v2_test`); requires `clojure.walk`, `debruijn-code` and `debruijn-register-code`. |
| `test/yin/vm/ucf/handoff_v2_test.cljc` | Uses the moved helpers; drops the six requires they alone needed. |

## Test and check outcomes (exact)

All on JVM, `clojure -M:test`:

| Run | Tests | Assertions | Failures | Errors |
|---|---|---|---|---|
| `-n yin.vm.ucf.safepoint-harness-test` | 8 | 1981 | 0 | 0 |
| `-n yin.vm.ucf.fencing-rows-test` | 4 | 121 | 0 | 0 |
| `-n yin.vm.ucf.handoff-v1-test` | 27 | 464 | 0 | 0 |
| `-n yin.vm.ucf.handoff-v2-test` | 10 | 276 | 0 | 0 |
| `-r "yin\.vm\.ucf.*"` (every UCF namespace, all the changed files' requirers included) | 601 | 10150 | 0 | **1** |

The one error is pre-existing and not in this diff. `handoff_v2_census_test/a-version-2-body-is-the-same-bytes-on-every-host` reads `test/resources/yin/vm/ucf/handoff-v2.txt`, which commit 2cf99c13 never added (it is not in `git ls-files`).

Other checks:

- **kondo:** `clj -M:kondo --lint` over the five changed files gives 0 errors, 0 warnings. An earlier `cat` shadowing warning was fixed by renaming to `cat-of`.
- **cljstyle:** blocked by the sandbox (the command needs approval). The indentation the `cat-of` rename shifted was fixed by hand.
- **Full fast JVM suite (`clojure -M:test -e :slow`):** aborted at load with `ClassNotFoundException yang.python.antlr.gen.Python3Lexer`. This worktree has no `gen:python-antlr` output. My diff is test-only and every requirer of a changed file is in the `yin.vm.ucf.*` run above.
- **Node lane: not run.** `shadow-cljs compile test` fails with `UnsupportedClassVersionError` because the shell has Java 17. The worktree is not `mise trust`ed, and both `mise trust` and `npm ci` need approval here; there is also no `node_modules`.
- **Dart lane: not run.** No `dart` or `flutter` on PATH, for the same mise reason.
- **Three-lane landing gate: not met in this round.** It must run after `mise trust` and `npm ci` in this worktree.

## Red and green evidence

- **Harness, first full run:** 205 failures, then 47, 24, 9, then 0 as I fixed harness mistakes and found the defects below.
  - Harness mistakes fixed: v2 parked bodies carry `:yin.k/frames []`, not an absent key; the walker keeps its FFI call id in `:k`; the walker frame has no `:yin.k/pc`; response values are envelopes.
  - Each surviving red was traced to a production defect (below) before it became a canary. Each canary was red as a plain assertion first.
  - Example: `register v2 blocked-read …` threw `Corrupt or tampered continuation payload {:rule :continuation-registers}`. Also `stack v2 retained-ffi` ended `{:halted false :value :yin/blocked}` where the reference ended `"echo!"`.
- **Fencing rows:** green on the first run, so I mutated them. Three mutations gave 4 failures, and the file was restored from a backup:
  - expect `:committed` after the after-commit cut;
  - give the regrant an empty prefix;
  - expect the wrong carried seq.
- **New v1 clause rows:** green on the first run; production already behaved correctly and only coverage was missing. Four mutations gave 12 failures, and the file was restored:
  - wrong supported set;
  - swapped `:halted-header` and `:child-header` kinds;
  - seqs below the counter.
- **Clause 4 row:** 3 variants × 16 assertions; all three variants ran.

## Open defects (production, not in the permitted diff: stop-and-report)

Each one is pinned in `safepoint_harness_test/open-defects` and asserted as "still present". When it is fixed, the canary fails and its entry must be deleted.

- **D0. Register: a live host primitive breaks every resume.**
  - Running `(conj [] (next c))` on a plain register machine with no handoff, appending to the stream and running again throws `:continuation-registers`.
  - Cause: `debruijn_register_effects.cljc` (~:201 and ~:331) requires `vm/machine-data?` of every live register, and the register VM keeps the raw host fn of `conj` in one.
  - The corpus combines through a closure `cat` so the register profile still gets matrix evidence.
- **D1. Semantic: stream-module effectful calls cannot lift (v0 and v2).**
  - `export-task` refuses `:yin.k/unsatisfied` with `:discovery :incomplete` and `:missing {:footprints #{stream}}`.
  - Cause: `module-decls` maps the host-registered `stream` module to `{:yin.k/manifest nil}` (its `:address` is nil by design, `module.cljc` `register-host-module`).
  - Stack, register and walker (via `census-v2`) lift and resume these calls correctly. The ordinary and tail `require` rows do pass on semantic.
- **D2. A lowered retained FFI request relifts with the wrong response cell (semantic, stack, register).**
  - The relifted cell's `:yin.k/position` is the receiver's own `:yin/call-out-cursor` position, not the carried one, so a second migration would wait on the wrong response stream.
  - The walker is correct. `handoff.cljc` ~:3656 rebinds `call-in`, `call-out` and `call-out-cursor` to the carried resources only `(when (or fenced? (= :walker engine)))`.
- **D3. Stack and register: a lowered retained FFI request never completes.**
  - After the retry lands at the source, the response wait entry has `:cursor-ref :yin/call-out-cursor` (the receiver's pair), not the carried `:response-cursor`, so the emitter's correlated response never wakes it.
  - This violates 14.1.1, "Retry the same request, id, args, and response cell". Semantic and walker complete. Likely the same root as D2, plus `ffi/response-wait-entry` on the de Bruijn kernels (`register.cljc` ~:511).
- **D4. A lowered blocked writer completes on outbound acceptance and loses the value (all four profiles).**
  - With the source target still full, the receiver halts with `"v"`, the source still holds `:warmed`, and `"v"` never lands.
  - `dao.stream.remote` `refl-append` answers `ok` on outbound acceptance by contract, and the engine wakes the writer on it.
  - This contradicts 14.1.1: "Reflection outbound acceptance alone never wakes a writer: source acceptance is the completion evidence."
  - Consequence for the stage-1 suite: `handoff_test/a-blocked-write-retries-its-retained-value` passes only because its reference run had already put `"v"` into the shared stream, so it does not prove the retry landed. The harness uses separate builds and steps the mirror once after the run.
- **D5. Clause 5: the install response is not verified.**
  - A version-0 body whose install response has another module name, a link id that no longer matches `:yin.k/parent`, or `:status :refused` lowers `:ok`.
  - A response without `:image` throws `Cannot load vector: nonempty (pc 0)` out of `resume-task`; it is not a data outcome.
  - `validate-body` (`handoff.cljc` ~:1934) only checks that `:yin.k/response` is present. No canary is pinned for D5: there was no stage-D seam to test against, and adding verification is a production change.

**Q1 (design question, not pinned).** A `:link-response` wait whose continuation calls the required module's export refuses lift with `:incomplete`, missing obligation `host.mod/f`. Only the `:install` phase discharges it (the `installing` set in `export-task`). That is why the harness's `require` rows return the module symbol and do not call `f`. Whether a link-phase wait should carry that obligation is an Architect call.

## Clause audit (1–5, 9): what was closed, what remains

**Closed this round, in `handoff_v1_test` and `fencing_rows_test`:**

- C1: an unsupported version (3) on a v1 body, with found and supported versions.
- C2: policy, arbitration and next-op-seq on a halted root; every header key on a live child.
- C4:
  - seq above the counter;
  - ids carried through park, bytes and lower on `:put`, `:ffi-request` and `:link-request`, assigned by the real writer at the park and with a fenced retry (same id and value, new incarnation and epoch, counter not re-advanced);
  - a `:put` retry through a lowered machine (rows 4 and 5).
- C5: phase × {blocked, halted} child on both versions; the lower preserves phase and parent.
- C9: next-op-seq and op-id seq of 2^52 and -1 refused through `resume-task`, with zero attaches and no machine.

**Remaining:**

- C1 zero proposals: the D7 reader has no proposal path (its namespace docstring argues this); a count needs the D13 driver or the composition.
- C1 version-0-only reader: no seam configures a reader to speak only version 0. The stream-codec path (v1 body in stream-codec bytes refused with supported `#{0}`) is the nearest existing proof.
- C5:
  - install response verification (D5);
  - an explicitly parked child kind (no real-machine fixture);
  - a `:parked` body with frames, lowered at version 1;
  - a runnable child refused through a version-1 header lift (today only `export/enter`);
  - version-0 "no rerun / no replay" through `resume-task` (today only fenced rehydration).
- The deepseek F3 gaps A–E were not duplicated. The audit traced F3 to 2cf99c13's v2 section-11 rows, which touch none of the version-1 clauses above.

## 14.2.4 rows as pinned (no composition)

- **Row 4 (fencing half).** Each of three cuts runs in a fresh world, through two lowered tenures (`lease-1` at epoch 0, then `lease-2` at epoch 1 after reclaim). The second tenure replays the durable input and observes nothing live.

  | Cut | Mechanism | Second tenure's answer |
  |---|---|---|
  | before commit | dropping appender | `:committed` |
  | after commit | cutting appender | `:replayed` with the stored result |
  | before result delivery | the reply is never drained | `:replayed` with the stored result |

  Every case: the same op id `{occ 0}` in both tenures, the retry fenced at epoch 1, and exactly one `"A"` at the target.
- **Row 5.** The kept value is evicted after tenure 1 commits.
  - With durable inputs, the lowered regrant replays `"A"` and gets `:replayed`.
  - Without them (prefix `:suspended`), the lower refuses `:yin.k/unsatisfied :unavailable` with zero attaches and no machine.
  - A divergent re-read of the reflection's real gap at the same id gives `:intent-conflict`; the occurrence is quarantined and nothing is committed.
- **Row 2 (fencing half).** An id assigned before an unknown append survives `export/enter`. Under `:exporting`, writer and reader append nothing (zero sends; the target stays empty). Two encodes of one prepared record give one address, and the successor body carries the same op id, `next-op-seq` 1.
- **Not covered here:** rows 1, 3, 6, 7 and 8, and the through-composition halves of 2 and 4. Row 1 landed with D13; the others are D16-final or E work.

## Unresolved concerns / incomplete work

- **Node and Dart lanes not run** (environment). The new files avoid the known CLJD traps: no `for` over long seqs, no many-key assoc on nil, `:cljd` first in the one catch conditional. They compare bytes only through addresses. Unverified until the lanes run.
- **The harness matrix uses a closure combinator because of D0.** The primitive case is covered only by the D0 canary.
- **The shared-FFI-cursor row hand-builds its second waiter** from the real one, with a new call id (the stage-1 alias-row construction); no program issues two concurrent FFI calls.
- **Cross-host pairs and the two-engine early gate are not here.** They are 14.1.3/E work.
- **The canaries pin defective behavior on purpose.** Each D0–D4 fix must delete its `open-defects` entry in the same commit.
