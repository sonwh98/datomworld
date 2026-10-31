Completed-GMT: 2026-10-10 15:44:17 GMT
Completed-Local: 2026-10-10 22:44:17 Asia/Bangkok

# Round-2 confirmation: slice 4a fixes (commits 2877a493, efcd653f on 9a8e7915)

Reviewer: claude-fable-5-1. Read-only. What was read: `git log`, `git diff
9a8e7915 HEAD --stat`, the full test diff, the full doc diff, `git diff
9a8e7915 HEAD -- src/` (empty, 0 lines), `git status --short` (nothing
outside `collab/`), the codex r2 report, and `semantic_register.cljc:144-153`
again. No lane was run; the orchestrator's three-host run and the M6/M7
mutation runs are taken as given. No file other than this one was written.

## Findings

severity | file:line | evidence | correction
---|---|---|---
GAP (4b, or a cheap follow-up now) | src/cljc/yin/vm/semantic_register.cljc:147-149 and test/yin/vm/semantic_register/vm_test.cljc | The pop-and-deliver transition exists three times: the `:return` arm (`:130-132`), the tail host-fn completion (`:147-149`) and `register-restore`'s `:return` arm (`:64-67`). The strengthened item 8 gates the first; `restore-delivery` gates the third non-vacuously (frame window `{1 nil}`, rd 2, exact map). The second copy is gated only by programs whose saved window is empty (`:lambda-application`, `:define-then-call`, B0 `lambda application`), so M6 applied at line 149 (`(assoc W (:rd frame) value)`) survives the whole suite by the same argument that held in round 1. The codex report states the mutants were applied to line 132 only. | Either factor the three pops into one `return-to [frame v]` helper so one gate covers all, or add a sibling test with the same program and the inner body call in tail position: `(app (v 'f) (app (lam '[z] (tail (app (v 'g) (v 'z)))) (v 'x)) (v 'y))`, step to the tail `:call`, assert `(= {1 vector 2 nil} (:window after))` and `[nil 2]`. Add as §5.3 item 7.
NOTE (survivor) | src/cljc/yin/vm/semantic_register.cljc:132 | A `:return` that keeps the callee's `E` instead of restoring `(:env frame)` survives every test: in all test programs the callee's env is a superset of the caller's (closures capture the caller's env), and nothing reads a name after a return whose binding differs. Observable only by `((fn [x] (list ((fn [x] 2) 9) x)) 1)` → `(2 1)` (mutant gives `(2 9)`). | Add that row to `slice-4a-extra-rows` in 4b. Low risk; the walker's `:next k` frame restore is the same shape and agrees.
NOTE | test/yin/vm/semantic_register/vm_test.cljc:159-167 | With `x` = nil both candidate source registers of the callee (`2` = z and `0` = v) hold nil, so this test cannot tell a `:return` reading the wrong register from the right one; the delivered-value half of exactly-once is carried by the value tests elsewhere (`lambda application` 11, `higher order` 15, `tail-countdown` "done"). Acceptable; recording it so nobody reads item 8 as proving the value. | Optional: `x` = 7 would make `v` 7 and the final value `[7 2]`.
NOTE (doc correction, mine) | docs/design/yin.vm.semantic-register-vm.evaluator.md §5.3 preamble | "15 of 19 mutants caught" repeats my round-1 summary line, which miscounted my own table: it listed five SURVIVES rows (M1, M6, M7, M16, M17), i.e. 14 of 19 caught. M6, M7, M16, M17 are now closed; M1 is equivalent by design. | Change to "14 of 19 caught, five survived: one equivalent (M1), four closed in the fix round".
NOTE (doc) | docs/design/yin.vm.semantic-register-vm.evaluator.md §5.3 item 5 | "test the subtraction where it bites: foreign-engine `live` operands (phase 6) and `κ` capture" overstates: at a `κ` capture `rd` is equally absent from `W` by definite assignment (my round-1 G.5 said so), and invocation writes into a copy of the captured window, never the capture. The only observable place is a static `L` published on the wire (phase 6). | Drop "and `κ` capture", or say "κ capture is likewise unobservable; the wire `live` operands are the place".
NOTE (doc) | §5.3 | Not carried: the parameter-shadowing row (closed, but §5.3 item 6 mentions only the tail-primitive row); the round-1 item-12 note (`[5 7 8 9 6]` is a static read of the golden, codex's r2 report acknowledges it and the test is unchanged); the two perf notes (tail call builds a discarded frame; `image` re-fetched per instruction). None affects 4b correctness. | Add one line each so they are not lost.

## 1. Item 8 can now fail

Yes. The test asserts every precondition literally against the program, so a
silent re-vacuation is impossible without failing an assertion: saved window
`{1 vector}` (non-empty), frame `rd` 2, result register 0, `(not= rd
result-reg)`, callee window `{1 identity 2 nil 0 nil}` with `(> count 1)`. It
asserts the exact post-return map `(= (assoc (:window frame) (:rd frame) v)
(:window after))`, keeps the three older assertions, and runs to `[nil 2]`.
I derived the program: main `[:var 1 f] [:closure 3 [z] B] [:var 4 x] [:call
2 3 [4] false] [:var 5 y] [:call 0 1 [2 5] false] [:halt 0]`, L(4) = {1,2},
saved(4,2) = {1}; body `[:var 1 g] [:var 2 z] [:call 0 1 [2] false] [:return
0]`. M6 gives `{1 identity 2 nil 0 nil}`, M7 gives `{1 vector 2 nil 0 nil}`;
both differ from `{1 vector 2 nil}`, matching the orchestrator's and codex's
observed failures at lines 167-168. Resume-pc mutants are caught by the final
value (pc 5 → `[nil nil]`; pc 3 → `:not-applicable`); not popping is caught by
`(nil? (:k after))`.

Further mutations it still misses: (a) restoring the callee's `E` instead of
`(:env frame)` (survives the whole suite, NOTE above); (b) the same M6 on the
tail host-fn copy of the pop at line 149 (GAP above); (c) reading the wrong
source register, only because `x` is nil here (covered elsewhere).

## 2. The new trace row

Vector: `[:var 1 +] [:const 2 1] [:const 3 2] [:call 0 1 [2 3] true] [:halt 0]`.
Register: `:var` → `(get W 1)` = `+` → `[:value :host-fn]`; `[:value 1]`;
`[:value 2]`; `:call` tail with `(get W0 1)` a fn and `K0` nil → `(peek nil)`
nil → the empty-K branch → the machine halts with `:value 3` → `[:value 3]`;
next iteration `halted-with-empty-queue?` → `[:halt 3]`. Walker: `:application`
sets control to the operator; `+` resolves → control nil → `:host-fn`; operands
1 and 2 each → one event; application of `+` → control nil, k nil → `[:value
3]`; then `[:halt 3]`. Both `[[:value :host-fn] [:value 1] [:value 2] [:value 3]
[:halt 3]]`. The row runs under `trace-oracle-pinned-values` on both machines,
so M17 (`extract` reads `(get (:window after) 0)` for the empty-K0 branch) now
yields `[:value nil]` at index 3 on the register side, which differs from the
pinned `[:value 3]`: caught. The count assertion 3 → 4 is correct.

## 3. The shadowing row

Register: outer closure env `{}` → `E₁ = (merge {} {x 1}) = {x 1}`; inner closure
captures `{x 1}`; inner call `E₂ = (merge {x 1} {x 2}) = {x 2}` → `x` → 2.
Reversed order: `(merge {x 2} {x 1}) = {x 1}` → 1. The row pins 2, compares
raw and normalised values on both machines and the two machines to each other,
so M16 fails on three assertions (the walker merges closure-env then params,
`ast_walker.cljc:285-287`, and returns 2). Placement is right: a separate
`slice-4a-extra-rows` in `parity_test`, `corpus.cljc` and its pinned addresses
untouched, the 25/21/5/51 counts unchanged. It is not traced; nothing is lost
by that (the mutant changes the value, which the value lane sees).

## 4. The design amendments

- §1.1 `yin.vm.ucf` note: faithful.
- §5.1 "step 3 of §2.2 only ... steps 1 and 2 ... land in 4b": faithful and
  now consistent with §2.2.
- §5.1 item 8: faithful to my first finding, program and assertion as I
  specified, with the history recorded.
- §5.3: items 1 (restore steps), 2 (extract arms, pre-state read), 3
  (`active-continuation?`, `scheduler-round`), 4 (materialization rule), 5
  (M1 equivalence), 6 (tail-primitive row) represent the round-1 findings.
  Corrections needed: the "15 of 19" count (my own error, NOTE above); item 5's
  "and `κ` capture" (NOTE above); the omissions listed in the last NOTE; and a
  new item 7 for the ungated tail host-fn pop (GAP above). Nothing in §5.3 is
  wrong for 4b beyond those.

## 5. Anything new / scope

The test diff only adds assertions and rows; no existing assertion is removed
or loosened (the three original `return-writes-rd-once` assertions remain, the
pinned-row count moves 3 → 4 to match). `git diff 9a8e7915 HEAD -- src/` is
empty; the stat lists only the three test files and the evaluator design.
Working tree clean outside `collab/`. The codex r2 report's claims (windows,
mutant outputs, byte-identical evaluator, Dart not run) are consistent with the
diff and with the orchestrator's independent run; its self-correction of the
round-1 item 8 and item 12 wording is accurate. Host portability of the new
assertions (`{1 vector}` compares a var's function object by identity on all
three hosts) is confirmed by the orchestrator's Dart pass.

## Updated survivor list

mutation | status
---|---
M1 saved window includes `rd` | survives, equivalent mutant by definite assignment; recorded in §5.3 item 5 (no gate wanted)
M6 `:return` writes into the callee's window (line 132) | CAUGHT by `return-writes-rd-once` line 167-168 (orchestrator and codex runs)
M7 `:return` writes `rd` and the body's result register (line 132) | CAUGHT, same assertions
M16 reversed callee-env merge order | CAUGHT by `extra-row-values` (`:parameter-shadowing`)
M17 oracle reads register 0 for an empty-K0 tail primitive | CAUGHT by `trace-oracle-pinned-values` (`:main-tail-primitive`)
M20 (new) `:return` keeps the callee's `E` | SURVIVES; low risk; row proposed above
M21 (new) M6 applied to the tail host-fn pop (line 149) | SURVIVES; GAP above, cheap to close by a sibling test or by factoring the pop
M22 (new) `:return` reads the wrong source register | survives `return-writes-rd-once` only because `x` is nil; caught by the value tests elsewhere

VERDICT: READY_TO_MERGE — the required item-8 fix is real and cannot silently regress, the two corpus rows close M16/M17, source is byte-identical and correct by reading; carry M21 (tail host-fn pop gate or a shared `return-to`), M20's row, the "14 of 19" and "κ capture" doc corrections, and the omitted notes into §5.3 / the 4b brief.
