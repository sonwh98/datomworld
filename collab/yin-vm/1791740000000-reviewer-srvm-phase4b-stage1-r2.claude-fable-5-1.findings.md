Completed-GMT: 2026-10-10 18:36:36 GMT
Completed-Local: 2026-10-11 01:36:36 Asia/Bangkok

# Stage 1 round-2 confirmation (commit 0f4522b9 on e47fcbd6)

Reviewer: claude-fable-5-1. What was read: `git log`, `git diff e47fcbd6
HEAD --stat` (one file, `effects_test.cljc`, +74/-29), the full test diff,
`git diff e47fcbd6 HEAD -- src/` (empty, 0 lines), the codex r2 report.
What was run (JVM, foreground, literal `-n` lists): four mutant runs of
`effects-test vm-test walker-trace-test parity-test`, one mutant at a time,
each a single edit to `semantic_register.cljc` reverted by the exact reverse
edit; after each revert `git diff --quiet HEAD --
src/cljc/yin/vm/semantic_register.cljc` returned 0, and `git status` shows
no tracked file modified at the end. No baseline run of my own (the
orchestrator's 47 / 583 is taken as given and is the count the mutant runs
show). Node and Dart not run by me. No file other than this one written.

## Findings

severity | file:line | evidence | correction
---|---|---|---
NOTE | test/yin/vm/semantic_register/effects_test.cljc:289-295 | `bounded-run` stops on `vm/halted?` only; a program that blocks would spin to the 10000-step bound and throw "Test step bound exhausted". Every program it runs halts, so this is latent, but the message would mislead if a continuation test ever blocked. | Optional: also stop on `(vm/blocked? machine)` and fail with a distinct message.
NOTE | effects_test.cljc:6-8 | `effects-test` now requires `walker-trace-test` for `trace-machine`, a test-to-test dependency (as `vm-test` already has on `parity-test`). Fine on all three hosts' discovery; just noting the coupling. | None.
NOTE (unchanged from round 1) | src/cljc/yin/vm/semantic_register.cljc:116-119, 191-192 | `not-in-slice!` naming and the ungated `check-callee-effect!` (M-P) remain deferred to Stage 2 as agreed. | Stage 2 brief.

No defect. No prior assertion weakened.

## The four mutants

id | mutation | result at 0f4522b9
---|---|---
M-G | continuation invoke keeps the invoker's `E` (`(:env restored)` → `E`) | KILLED: `continuation-restores-captured-environment` lines 329, 330, 332 (register value `(7 9)`, normalized parity, register trace). 47 / 583, 3 failures. Walker unaffected, as expected.
M-I | `:park` leaves `:control` set (`(assoc :k nil)`) | KILLED: `park-resume` lines 214 and 225 (both parked machines). 47 / 583, 2 failures.
M-O | blocked stop clears `:control` only | KILLED: `tail-effect-returns-through-k` line 187 (`(nil? (:k blocked))`, non-main case). 47 / 583, 1 failure.
M-K | κ payload `:pc pc` (resumes at its own capture) | KILLED, fast: wall clock 01:36:23 → 01:36:30 (7 s including JVM start), 47 tests / 576 assertions, 6 failures + 1 error: the three bounded value tests (line 305, thrown "Test step bound exhausted"), `re-entry-after-return-test` 304, `reified-shape` 248 (pc), `trace-oracle-pinned-values` 130 (fuel-exhausted trace), and the new env test erroring on the bound (294). No hang.

Author's table agrees with mine on all four (their counts are for
`effects-test` alone; mine include the other three namespaces).

## The M-G row

`continuation-restores-captured-environment` builds the walker with
`walker/vm-load-rows (tu/create-vm {:env {'list list}})` and the register
machine with the same env, runs both under `bounded-run`, asserts `(7 1)`
on each, their normalized equality, and the whole trace on each through
`trace/trace-machine`. It is walker-compared.

I derived the trace independently from the projected vectors before
reading the pinned column. Main: `[:closure 1 [x] B1] [:const 2 1] [:call 0
1 [2] false] [:halt 0]`. B1: `[:var 1 list] [:closure 3 [r] B2]
[:current-continuation 4] [:call 2 3 [4] false] [:var 5 x] [:call 0 1 [2 5]
false] [:return 0]`; κ captured at B1 pc 2 with resume pc 3, `E` = `{x 1}`,
saved(3, 4) = L(3) − {4} = {1, 3} (`list`, the `[r]` closure), K = [main
frame], deliver rd 4. B2: `[:var 2 =] [:var 3 r] [:const 4 7] [:call 1 2
[3 4] false] [:branch-false 1 L] [:var 0 r] [:jump END] L: [:closure 5 [x]
B3] [:const 6 9] [:call 0 5 [6] false] END: [:return 0]`. B3: `[:var 1 r]
[:const 2 7] [:call 0 1 [2] false] [:return 0]`.

Register events in order: closure `[x]`; 1; (closure call, none); `list` →
`:host-fn`; closure `[r]`; κ → `:continuation`; (closure call, none); `=` →
`:host-fn`; `r` → `:continuation`; 7; `(= κ 7)` → false; (branch); closure
`[x]`; 9; (closure call, none); `r` → `:continuation`; 7; κ invoke → restore
B1 pc 3 with `W` `{1 list 3 clo 4 7}`, `E` `{x 1}`, event `(get W 4)` = 7;
(closure call, none); `=` → `:host-fn`; `r` → 7; 7; true; `[:var 0 r]` → 7;
(jump, return, none); `[:var 5 x]` → 1 from the restored `E`; `list` → `(7
1)`, normalized `[7 1]`; (return, halt, none); `[:halt [7 1]]`. Twenty-one
value events then the halt. Walker: the same sequence by the production
rule (`list`, the lambda, the capture, `=`, `r`, 7, false, the inner lambda,
9, `r`, 7, the abortive delivery 7, `=`, 7, 7, true, `r` → 7, `x` → 1 from
the frame's env, `(7 1)`, halt). Both agree with the pinned column event
for event; under M-G the register side reads `x` = 9 at `[:var 5 x]` and
diverges there, which is what the run shows.

## Rename and step bound

`every-vm=` → `register-value=`: the five call sites keep their programs
and expected values (9, 8, `[30 3]`, `[1 :written]`, `[:thrown …]` for 0 and
2 args); the helper now builds through `parity/register-machine` with the
same `{'vector vector}` env `run-ast` supplied, and runs under a 10000-step
bound whose exhaustion throws and so fails the expected-value assertion.
The `bounded-run` loop steps with `vm/step` and stops on `vm/halted?`, the
same observable the old `vm/run` ended on; `vm/halted?` is
`halted-with-empty-queue?`, so a program that parks is still "halted" to it,
which matches `vm/run`. Nothing weakened; six assertions added (two
`:control`/`:k` pairs in `park-resume`, one pair in
`tail-effect-returns-through-k`) plus the five of the new test.

## Anything new in the diff

Only `effects_test.cljc`: three requires, the six nil-shape assertions, the
new test, `bounded-run`, the rename. No source change (`git diff e47fcbd6
HEAD -- src/` empty), no other test, no design, no phase-3 file.

VERDICT: READY_TO_MERGE — all four mutants are killed at 0f4522b9 (M-K within 7 s), the M-G row is walker-compared and its pinned trace is correct by independent derivation, no prior assertion is weakened, the source is byte-identical to the reviewed Stage 1 evaluator; Node and Dart remain the orchestrator's lanes, and M-P plus the `not-in-slice!` rename go to the Stage 2 brief.
