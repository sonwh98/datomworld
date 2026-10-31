Created-GMT: 2026-10-10 16:20:00 GMT
Created-Local: 2026-10-10 23:20:00 Asia/Bangkok

# Task: slice 4a, round 2 -- close the two surviving mutants (test-only)

Role: VM Engineer (yin.vm), continuing codex thread 01a125f8-74e6-7163-939d-34915d2cd1be

Implementers:
- Model: gpt-6.1-sol (codex) | Assigned: 2026-10-10 23:20 +07 | Status: active | Rationale: same implementer, round 2 of an owner-authorized exception (owner, verbatim: "dispatch the phase 4a brief to a codex model"). The reviewer was claude-fable-5-1 (a different family).

Same worktree `/Users/sto/workspace/datomworld-srvm-p3`, branch `srvm-phase4a`,
now at the orchestrator's doc commit on top of `9a8e7915`. Work ONLY there.

Fable's adversarial review is
`collab/1791560000000-reviewer-srvm-phase4a.claude-fable-5-1.findings.md`
(read it in full). Verdict CHANGES_REQUESTED, but: **no defect in
`semantic_register.cljc` was found**, 15 of 19 mutants were caught, every
4a arm matches the frozen equation, your restore-steps reading was ruled
correct, and your report was judged honest except two sentences. The one
real problem is in the TESTS, and it came from the design, not from you:
acceptance item 8 as specified used a program that could not fail, so
mutants M6 and M7 (a `:return` that writes into the callee's window; a
`:return` that writes twice) survive the whole suite. The orchestrator has
amended the design (evaluator §5.1 item 8, now the strengthened version;
§5.3 lists what carries to 4b).

## Do exactly this -- TEST FILES ONLY, no source change

1. **Strengthen `vm-test/return-writes-rd-once`** per the amended §5.1
   item 8. Use the program `(app (v 'f) (app (lam '[z] (app (v 'g) (v
   'z))) (v 'x)) (v 'y))` with `f` = `vector`, `g` = `identity`, `x` = nil,
   `y` = 2. Step to the point where the inner call has returned and assert
   the EXACT post-return window `(= (assoc (:window frame) (:rd frame) v)
   (:window after))`, with the frame window non-empty, `rd` different from
   the body's result register, and the callee window having more than one
   key (the review computed: frame window `{1 f}`, `rd` 2, callee window
   `{1 g 2 z 0 v}`; CONFIRM these on your first run and report them; do
   not adjust a number silently). Then assert the final value `[nil 2]`.
2. **Add the main-level tail primitive trace row** to
   `walker_trace_test` as a 4a trace-only pinned row:
   `(tail (app (v '+) (lit 1) (lit 2)))` with whole trace
   `[[:value :host-fn] [:value 1] [:value 2] [:value 3] [:halt 3]]`
   (derive it from both machines; if it differs, report the actual trace
   and why). This makes the oracle's empty-`K0` tail branch run.
3. **Add the parameter-shadowing program** `((fn [x] ((fn [x] x) 2)) 1)`
   -> 2 as a **4a-only extra row inside `parity_test.cljc`** compared on
   walker and register machine (value and normalized result). Do NOT add
   it to `test/yin/vm/semantic_register/corpus.cljc` (that is a phase-3
   file with pinned goldens and addresses; it must not change).
4. **Prove the fix with the two mutants.** After the changes, temporarily
   apply M6 (`:return` writes `rd` into the callee's window instead of
   the frame's) and M7 (`:return` also writes the body's own result
   register into the restored window) to `semantic_register.cljc` ONE AT A
   TIME, run `vm-test`, confirm the strengthened test FAILS for each, and
   RESTORE the file byte for byte afterwards (`git diff -- src/` must be
   empty at the end; say so in the report). If a mutant still survives,
   say so and strengthen further.

## Rules

- Do not edit `semantic_register.cljc` (except the two temporary mutants,
  restored), any design document, any phase-3 file, or any existing test.
- Foreground only; never put a namespace list in an unquoted shell
  variable (zsh does not word-split it and the runner then runs the
  ENTIRE suite for over an hour: write each `-n ns` literally).
- Do not `git add`/`git commit`; the orchestrator commits and re-runs all
  three lanes independently. If a lane cannot run in your sandbox (Dart),
  say NOT RUN with the exact error.

## Verification

JVM: `clojure -M:test -n yin.vm.semantic-register.vm-test -n
yin.vm.semantic-register.walker-trace-test -n
yin.vm.semantic-register.parity-test` plus the four phase-3 namespaces
(code, linearize, analysis, load-paths): all must still pass, phase 3
unchanged at 33 tests / 1133 assertions. Node: compile and run the seven
namespaces. `clj -M:kondo --lint` and `cljstyle check` on the changed
test paths; trailing-whitespace scan.

## Report

Write `collab/1791580000000-vm-engineer-srvm-phase4a-r2.report.md`.
Begin it exactly with:

Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>

Then: files changed; the confirmed window numbers from item 1; the trace
derived in item 2; the mutant results (M6, M7: which test failed, with the
assertion text); `git diff -- src/` evidence; exact lane outcomes; anything
not run. Do not claim edits or runs that did not occur.
