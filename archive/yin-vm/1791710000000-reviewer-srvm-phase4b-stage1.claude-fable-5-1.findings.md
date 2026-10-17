Completed-GMT: 2026-10-10 18:26:16 GMT
Completed-Local: 2026-10-11 01:26:16 Asia/Bangkok

# Adversarial review: slice 4b Stage 1 (commit e47fcbd6 on srvm-phase4b)

Reviewer: claude-fable-5-1 (non-author family; author codex gpt-6.1-sol).

What was read: the Stage 1 brief, the author's report, `git diff
master...HEAD --stat` and the full diffs of `semantic_register.cljc` and the
three modified test files, the whole of `effects_test.cljc` and
`semantic_register.cljc` at HEAD, evaluator design §1.8-1.9, §2.2, §4,
§5.2, §5.3, `engine/handle-effect`, `engine/gc-roots`, `engine/collect`,
`engine/check-callee-effect!`, `module/IModuleKernel`, the walker's park /
current-continuation / resume transitions, `semantic.cljc`'s GC methods.

What was run (JVM only, foreground, literal `-n` lists): one baseline of
`effects-test vm-test walker-trace-test parity-test` (46 tests / 570
assertions, 0 failures, matching the orchestrator), then fourteen mutant
runs of the same four namespaces, one mutant at a time, each applied with
a single textual edit to `semantic_register.cljc` and reverted by the exact
reverse edit. After every revert `git diff --quiet HEAD --
src/cljc/yin/vm/semantic_register.cljc` returned 0 (byte-identical to the
committed blob `32c550b3`); `git status` shows no tracked file modified.
Node and Dart were not run by me. No file other than this one was created.

## Findings

severity | file:line | evidence | correction
---|---|---|---
GAP, close before merge (test-only) | src/cljc/yin/vm/semantic_register.cljc:208-213; test/yin/vm/semantic_register/effects_test.cljc | Mutant M-G (continuation invoke restores κ's window and K but keeps the invoker's `E` instead of `(:env restored)`) passes all 570 assertions. Frozen §2.4 and evaluator §1.9 say the caller's `W E K` are discarded. Every current program either reads no variable in the restored activation before its next frame pop, or reads it from a closure's own captured env. The M20 row closed exactly this class for `:return`; the continuation arm has no such row. | Add to `effects-test` (or `slice-4a-extra-rows`, walker-compared): `((fn [x] (list ((fn [r] (if (= r 7) r ((fn [x] (r 7)) 9))) (current-continuation)) x)) 1)` → `(7 1)`. The κ is captured in the outer body with `x`=1 and invoked from an inner activation with `x`=9; after delivery the outer body reads `x` from `E` (`[:var 5 x]`) before any pop. The mutant yields `(7 9)`; the walker yields `(7 1)`.
GAP (same pass) | src/cljc/yin/vm/semantic_register.cljc:233-235; effects_test.cljc:205-220 | Mutant M-I (`:park` leaves `:control` set, clearing only `:k`) survives. §1.8 and the stack template (`semantic.cljc:373-379`) require `:control nil :k nil`; `park-resume` asserts the record, the table and the resume value, never the parked machine's control. | In `park-resume`, assert `(nil? (:control parked))` and `(nil? (:k parked))` on both parked machines.
GAP (same pass) | src/cljc/yin/vm/semantic_register.cljc:92-93; effects_test.cljc:178-192 | Mutant M-O (`run-effect`'s blocked stop clears `:control` but not `:k`) survives. `blocked-read-entry-shape` asserts `(nil? (:k blocked))` but its `K` is empty, so `(when (seq K) K)` already yields nil; the only blocked state with a non-empty `K` (`tail-effect-returns-through-k`, non-main case) does not assert it. §1.9: "stop with `(assoc state :control nil :k nil)`". | In `tail-effect-returns-through-k`, assert `(nil? (:control blocked))` and `(nil? (:k blocked))` in both cases.
NOTE (single gate) | effects_test.cljc:314-340 | Mutant M-F (continuation invoke keeps the invoker's `K`) is killed by exactly one assertion, `re-entry-after-return-test` line 286. The escape, tail-invoke, store, arity tests and the pinned `:continuation-delivery` trace all pass with it, because in those programs the stale frames are never popped before the halt. | The M-G row above does not close this either; keep `re-entry-after-return-test` as is and record that it is the sole K gate.
NOTE (hang, not fail) | effects_test.cljc:280-287 | Mutant M-K (κ resumes at its own capture pc) makes `escape-discards-pending-frames-test` loop forever in an unbounded `vm/run`; the four-namespace run produced no summary before the 580 s timeout. Only the fuel-bounded `trace-oracle-pinned-values` (`:continuation-delivery`) fails it cleanly (`[:fuel-exhausted …]` versus the pinned trace). | Run the five continuation programs under a step bound (`vm/step` with a counter, as `tail-countdown` does) so a looping mutant fails instead of hanging the lane.
NOTE (Stage 2) | src/cljc/yin/vm/semantic_register.cljc:191-192 | Mutant M-P (`engine/check-callee-effect!` dropped from the host-fn effect path) survives: no test applies a callable whose declared profile lacks the effect it returns (D4 `:yin.k/undeclared-effect`). The stack VM's `semantic_test` corpus may have one; none is respelled. | Add a declared-profile refusal row in Stage 2 (template: wherever `semantic_test`/`semantic_ffi_test` exercise `:yin.k/undeclared-effect`).
NOTE (naming) | src/cljc/yin/vm/semantic_register.cljc:116-119; vm_test.cljc `stage2-deferred-arms` | `not-in-slice!` still throws `{:reason :not-in-slice-4a}` with the message "Instruction is not in slice 4a" for `:ffi-call`, while `require` throws `:not-in-stage1`. Both pinned. | Rename to one reason (`:not-in-stage1`) in Stage 2 and delete both with the deferrals.
NOTE (Dart) | src/cljc/yin/vm/semantic_register.cljc:380-386 | `IModuleKernel` is extended with two of nine methods. On the JVM `satisfies?` is true and the seven missing methods throw only when called; the engine calls them only on link paths, which Stage 1 refuses before dispatch, so JVM/Node are safe. Dart's partial `extend-type` semantics are not verified by me. | The orchestrator's Dart lane decides; if it fails, stub the seven with a `:not-in-stage1` throw rather than leave the extension partial.
NOTE | src/cljc/yin/vm/semantic_register.cljc:191-193 | The host-fn effect path materializes twice (`check-callee-effect!` on `put-registers …`, then `run-effect` calls `put-registers` again). Harmless. | Pass the checked state into `run-effect` without a second `put-registers`, or leave.
NOTE | effects_test.cljc:280-287 | `every-vm=` runs the register machine only; the name suggests a cross-VM comparison. | Rename (`register-value=`) or make it compare with the walker now, which the Stage 2 registration will do anyway.

No DEFECT in `semantic_register.cljc`: every Stage 1 arm matches §1.8-1.9
and §2.2 step 3 (details under 3 below), and all design-named Stage 1
mutants are killed.

## 1. Can each Stage 1 acceptance item fail?

- Item 1 `non-parking-stream-ops`: yes, asserts sealed `:stream-ref`/`:cursor-ref` and nil close.
- Item 2 `blocked-read-entry-shape`: yes, exact nine-key map with `:pc (inc pc)`, EDN round-trip, control/k nil. Killed M-B.
- Item 3 `saved-window-membership`: yes, literal `{1 vector 2 nil}` with dead registers 4, 5 excluded. Killed M-B and M-C.
- Item 4 `wake-delivers-to-rd`: yes, exact post-delivery window, control, final value. Killed M-A and M-E.
- Item 5 `blocked-put`: yes, real `full` transport, `:datom`, `{}` window, exact delivered window.
- Item 6 `terminal-wake-raises`: yes (closed stream on wake and on immediate put).
- Item 7 `tail-effect-returns-through-k`: yes, exact tail key set, non-empty saved window, both K cases. Killed M-D.
- Item 8 `tail-primitive-pops-frame`: yes, exact window with saved register, `x`=7. Killed M21.
- Item 9 `park-resume`: yes for the record, delivery and "not found"; no for the parked machine's control (M-I survives).
- Item 10 `resume-return-mode`: yes. Killed M-J (nil-control re-entry throws).
- Item 11 `reified-shape`: yes, exact payload and EDN round-trip.
- Item 12 five programs: yes for values; one assertion gates K discard (M-F); env discard ungated (M-G).
- Item 13 `foreign-continuation-refused`: yes.
- Item 18 `store-and-gensym-test`: yes (`:id-0`, `:stream-1`, `:g-2`, counter 3; verified by hand: `gensym` and `stream/make` share `:id-counter`).
- Item 24 `window-is-a-gc-root`: yes. Killed M-H.
- §5.3 item 3: `engine/active-continuation?` and `engine/scheduler-round` are used (`:124`, `:247`); no test distinguishes them from the 4a forms (equivalent in every reachable state, as my round-1 note said). Acceptable.
- §5.3 item 4: the four materializations are gone (`:158`, `:161`, `:166`, `:177`); §1.8 already says the pure arms do not materialize, so the design is satisfied, not amended. The effect, park and resume arms still materialize, as §1.8 requires.
- §5.3 item 5: no gate written for the subtraction at a call frame or capture. Correct.

Author's mutation table versus mine: all eight of theirs are confirmed
killed by my runs (wrong-rd = M-A, save-too-much = M-B, save-too-little =
M-C, tail-restores-window = M-D, deliver-extra = M-E, non-abortive = their
variant; mine split it into K-kept M-F killed and E-kept M-G survived,
window-not-root = M-H, tail-pop = M21). Their table does not include M20;
I ran it: killed by the `(2 1)` row. Their deferral of the retained-request
and link-phase mutants is legitimate (no Stage 1 code to mutate).

## 2. Trace oracle

Tagged results only (`{:event? true :value v}` / `{:event? false}`), park by
the executed instruction (`(= :park (first inst))`) or node, destinations
explicit: productions by `rd`, κ invoke by `(:rd (:deliver payload))` read
from the pre-state window, `:resume` by the parked record read from the
pre-state `:parked` (`:rd` mode → window; `:return` mode → popped frame's
`rd` or `(:value after)`). Fuel 100000, equal exhaustion still fails.
Matches §4.3.

I derived the three new rows independently from the projected vectors and
the walker transitions before comparing with the pinned columns:
`:park-then-halt` `[[:park :parked-0]]` (register `[:park 0]`, walker
`:vm/park` node, id from the engine's counter); `resume-delivery-trace`
`[[:value 9] [:value 9] [:halt 9]]` (operand production, delivery into the
consumed park's rd 0 at the park segment's pc 1, then that segment's halt;
the resume segment's own `:halt` is never reached); `:continuation-delivery`
fourteen value events then `[:halt 7]`, the ninth being the abortive
delivery of 7 into the captured rd 2 at main pc 2 (my derivation is in the
review transcript; it agrees event for event with the pinned row on both
machines). The rows are derived, not copied: the design pins only
`:park-then-halt`.

Gap in the oracle's coverage: the `:resume` `:return`-mode branch of
`extract` is exercised by no traced row (`resume-return-mode` is a value
test). Acceptable; Stage 2's FFI tail record is the only producer.

## 3. `return-to`, the materializations, the `(2 1)` row

`return-to [frame value]` is the single saved-window write for the three
pops (`:173`, `:204`, `:108`); each site reads `(:window (return-to …))` and
restores `(:env frame)` and `(pop K)` itself. Sound; one gate
(`tail-primitive-pops-frame`, `return-writes-rd-once`, `restore-delivery`)
now covers each call site and M21 is killed. Returning a whole frame only to
read its `:window` is a small oddity, not a defect.

Dropping the four materializations is consistent with §1.8 as written
(`put-active`, `gensym`, `active-store`, `operator-kind` read only `:store`,
`:module-stores`, `:id-counter`, `:owner`); the 4a code was the deviation.
The design is neither amended nor contradicted. The row
`((fn [x] (list ((fn [x] 2) 9) x)) 1)` → `(2 1)` is in
`slice-4a-extra-rows`, walker-compared with `list` bound on both sides, and
kills M20 on three assertions.

## 4. Scope and existing assertions

`git diff master...HEAD --stat`: `semantic_register.cljc`, new
`effects_test.cljc`, and the three 4a test files. Nothing else. The four
exception suites are untouched (Stage 2), no `link_test`, no design edit,
no phase-3 file. In the three modified tests no assertion is weakened: the
eligibility lift changes the asserted counts (25→26, 21→30, 51→61, 4→6)
while every prior row and pinned trace is retained; `return-writes-rd-once`,
`restore-delivery` and the 4a extras are unchanged. The `deferred-arms`
deletion is legitimate: its four `:not-in-slice-4a` expectations describe
arms that now exist and are tested in `effects-test`; the replacement pins
the two Stage 2 deferrals. `stage1-eligible?` is a temporary predicate to
delete in Stage 2 along with `stage2-deferred-arms`.

## 5. Stage 2 deferral list

Restore steps 1-2, the `:module/require` builder, the seven link methods,
the four registrations and items 14-17, 19-23 are FFI/link and belong to
Stage 2. Three things Stage 1 should already have: the M-G row, the two
state-shape assertions (M-I, M-O), and a step bound on the continuation
value tests (M-K hangs the lane). The declared-profile refusal (M-P) can go
with Stage 2's `semantic_ffi_test` respelling.

## Mutant table

id | mutation (one edit, reverted byte for byte) | result
---|---|---
M-A | restore `:rd` arm writes register 999 | KILLED: 14 assertions (`wake-delivers-to-rd` 106/109, `blocked-put` 155/156, `park-resume` 215, `restore-delivery` vm_test 255, `resume-delivery-trace`, `trace-oracle-pinned-values`, four continuation tests, `stream-outcomes-and-edn-wake`)
M-B | `act-payload` saves all of `W` | KILLED: `saved-window-membership` 92/93, `blocked-read-entry-shape` 78, `blocked-put` 152/155
M-C | `act-payload` saves `{}` | KILLED: 8 failures + 2 errors (`saved-window-membership` 92-94, `park-resume` 211, pinned trace, three continuation tests; `wake-delivers-to-rd` errors)
M-D | tail effect parks an act entry (`tail-builders` never used) | KILLED: `tail-effect-returns-through-k` 183/184/189/191 (both cases)
M-E | restore `:rd` arm writes rd and an extra key | KILLED: `wake-delivers-to-rd` 106, `blocked-put` 155, `restore-delivery` 255
M-F | κ invoke keeps the invoker's `K` | KILLED by one assertion: `re-entry-after-return-test` 286
M-G | κ invoke keeps the invoker's `E` | SURVIVES (0 failures / 570). Finding 1.
M-H | `gc-roots` omits `:window` | KILLED: `window-is-a-gc-root` 262
M21 | tail host-fn pop writes into the callee's window | KILLED: `tail-primitive-pops-frame` 201 + arity error on the run
M20 | `:return` keeps the callee's `E` | KILLED: `extra-row-values` 140/141/143 (`:caller-env-after-return`)
M-I | `:park` leaves `:control` set | SURVIVES (0 / 570). Finding 2.
M-O | blocked effect leaves `:k` set | SURVIVES (0 / 570). Finding 3.
M-J | `:resume` re-enters the loop after a halted restore | KILLED: `resume-return-mode` error (nil control)
M-K | κ payload `:pc pc` (resume at the capture) | KILLED by `trace-oracle-pinned-values` (fuel-exhausted trace ≠ pinned); the effects lane hangs. NOTE above.
M-P | `check-callee-effect!` dropped | SURVIVES (0 / 570). Stage 2 note.

Ten of fourteen killed (plus the author's eight, confirmed). Four survive:
M-G is a semantic gate on the central Stage 1 transition and must be
closed; M-I and M-O are design-mandated state shapes with no assertion;
M-P is a policy check deferred with the FFI respelling.

VERDICT: CHANGES_REQUESTED — (1) add the M-G row `((fn [x] (list ((fn [r] (if (= r 7) r ((fn [x] (r 7)) 9))) (current-continuation)) x)) 1)` → `(7 1)`, walker-compared; (2) assert `:control nil :k nil` on the parked machines in `park-resume` and on both blocked machines in `tail-effect-returns-through-k`; (3) bound the five continuation value tests by steps; test-only, no source change, rerun JVM/Node/Dart; carry M-P and the naming note into the Stage 2 brief.
