Created-GMT: 2026-10-11 02:00:00 GMT
Created-Local: 2026-10-11 09:00:00 Asia/Bangkok

# Task: semantic-register-vm phase 4b -- effects, waits, continuations, link

Role: VM Engineer (yin.vm)

Implementers:
- Model: gpt-6.1-sol (codex), resuming thread 01a125f8-74e6-7163-939d-34915d2cd1be; owner authorized 2026-10-11 (it wrote slice 4a and holds the codebase and the design in context; owner exception to team.md: "dispatch the phase 4a brief to a codex model"). If codex implements, review and sign-off go to claude-fable-5-1 (a different family), never to codex.

Work ONLY in the git worktree `/Users/sto/workspace/datomworld-srvm-p3`, on a new
branch `srvm-phase4b` created by the orchestrator from master (which contains the merged slice-4a). Do not `cd` to another checkout. `mise trust` and `npm ci` are done.

## Governing design -- read in this order, do not amend it

1. `docs/design/yin.vm.semantic-register-vm.md` -- the FROZEN design as
   amended (F1-F7): §2.1-2.5, §3.4, §4.1, §4.2, §8.1, §10 phase 4.
2. `docs/design/yin.vm.semantic-register-vm.evaluator.md` -- the reviewed
   specification (three codex rounds, then amended once after the 4a
   review). Your sections:
   - §1 (shapes: every state, entry, record and transition as a code path),
     especially **§1.8-1.9** (the effect arms, `act-builders` /
     `tail-builders`, the materialization rule);
   - **§2.2** (`register-restore`, ALL THREE steps: step 1 retained-request
     re-park, step 2 `:call-id` removal and `ffi/call-result` decoding, step
     3 delivery -- 4a implemented step 3 only);
   - **§3** (link and install interplay, the `IModuleKernel` port);
   - **§4** (the walker trace procedure; the tagged event results; park by
     the EXECUTED node/instruction, never by result shape);
   - **§5.2 slice 4b** -- the files, the approved exception, the named
     golden and parity rows, and the **26-item numbered acceptance list**.
     That list is your definition of done;
   - **§5.3 -- carried into 4b from the slice-4a review.** Nine items; read
     all nine. Items 7 (gate the tail host-fn pop with a non-empty saved
     window, or factor the three pops into one `return-to`), 8 (the
     `((fn [x] (list ((fn [x] 2) 9) x)) 1)` -> `(2 1)` row) and 9 (notes) are
     in addition to the six below. They
     are REQUIREMENTS of this task, not suggestions: (1) restore steps 1-2;
     (2) the trace `extract` arms for `:continuation` and `:resume`, reading
     the pre-state, each with a pinned trace row; (3) `engine/active-
     continuation?` as the run-loop predicate and `engine/scheduler-round`
     as the composition; (4) decide the materialization rule (drop the four
     redundant materializations or amend §1.8 -- if you amend, STOP and
     report, do not edit the design); (5) do NOT write a gate for the saved-
     window subtraction at a call frame (an equivalent mutant); (6) the
     main-level tail trace row exists from 4a.
3. `docs/agents/build-n-test.md` -- verification and the Gate Ladder.
4. The landed 4a code and tests you extend:
   `src/cljc/yin/vm/semantic_register.cljc`,
   `test/yin/vm/semantic_register/{vm_test,walker_trace_test,parity_test}.cljc`;
   the stack machine you mirror, `src/cljc/yin/vm/semantic.cljc` (cite the
   template line ranges the design gives); `src/cljc/yin/vm/engine.cljc`,
   `ffi.cljc`, `module.cljc`, `linker.cljc`, `values.cljc`; and the
   independent review `collab/1791560000000-reviewer-srvm-phase4a.claude-fable-5-1.findings.md`
   (the 19-mutant table shows what a strong test looks like here).

Owner context, verbatim: "The AST is the source of truth but the semantic
vm is the canonical projection of it. the ast-walker and the semantic-vm
are isopmorphic. we just need to rebuild the smantic-vm as register-shaped
vm instead of a stack-shaped vm".

## Scope: slice 4b

Everything in evaluator §5.2 "Files to change / create": the remaining arms
(`:stream-make :stream-cursor :stream-close :stream-put :stream-next
:ffi-call :current-continuation :park :resume`), the `:host-fn` effect arms
and the `:continuation` arm of `apply-call`, `act-builders` / `tail-builders`,
`register-restore` steps 1-2, the `module/IModuleKernel` extension; the new
test namespaces `effects_test.cljc` and `link_test.cljc`; lifting the 4a
deferrals in `walker_trace_test.cljc` and `parity_test.cljc`; and the
**approved exception**: additive registration of the fifth kernel in exactly
four existing test files (`continuation_invoke_test`, `ffi_test`,
`rule_r_test` -- with the backend-selection change at its
`a-parked-read-in-a-definition-resumes-and-writes` -- and
`linker_require_test`). **Existing entries and assertions in those four
files stay untouched.** No other existing source or test file changes. Do
not edit `yin.vm.semantic`, the walker, the phase-3 namespaces, any design
document, or the shared phase-3 `corpus.cljc`.

## THIS TURN IS STAGE 1 ONLY (the owner approved two stages)

Do Stage 1 now and STOP. Do not start Stage 2. Report items done, items deferred to Stage 2, and the §5.3 items. Write the report to `collab/1791690000000-vm-engineer-srvm-phase4b-stage1.report.md`. Stage 2 gets its own brief after review of Stage 1.

## Stage definitions

- **Stage 1 -- engine-free effects:** acceptance items 1-13, 18, 24, and the
  `:continuation` / `:resume` / `:park-then-halt` trace rows (§5.3 item 2),
  plus §5.3 items 3-5; no FFI, no link. Ship it green before Stage 2.
- **Stage 2 -- bridges and link:** items 14-17, 19-23, 25-26, the four
  backend registrations, and the link/install work.


## Rules

- **Do not change the design.** Ambiguity or a design/engine conflict:
  record it in the report with the evidence and the smallest reading you took.
- **Verify every hand-derived number** (the design marks them) on the FIRST
  run; report a wrong one plainly, never adjust it silently.
- **Tests that can fail.** Slice 4a's review found acceptance item 8
  vacuous: a test that passes for a broken implementation. Before you
  report, for each of the 8 behaviours you consider hardest (a woken
  entry writing the wrong register; an entry saving too much or too little
  of the window; a `tail` entry restoring into a window; delivering twice;
  a continuation invoke that is not abortive; the retained-request phase
  delivering to guest code; a `:window` missing from `gc-roots`; a link
  phase losing the delivery record), apply the corresponding mutation to
  `semantic_register.cljc` temporarily, confirm a named test FAILS, and
  restore the file byte for byte. Put the table in the report. A surviving
  mutant is a finding.
- **Trace oracle:** tagged `{:event? true :value v}` results only; no
  sentinel value; park from the executed node or instruction; explicit
  destinations; fuel 100000.
- Portability traps (Dart/Node only): `#?(:cljd nil :clj ...)` with `:cljd`
  FIRST; `(- x)` on a float is `0 - x` on Dart; `for` over a >32-element seq
  can give the body nil on Dart (use `keep`/`mapv`); `(assoc nil :a 1 :b 2)`
  is a one-element list on Dart (start from `{}`); protocol methods need
  distinct parameter names (`[_ _x]`); cljd's EDN reader throws on
  whitespace before a closer; windows are map-valued (preserve nil-valued
  keys). Evaluator §8.
- **zsh does not word-split an unquoted variable.** Never put a namespace
  list in a variable: the runner then receives one malformed name and runs
  the ENTIRE suite for over an hour. Write each `-n namespace` literally.
- Foreground only; never `&`; never background a lane.
- Sandbox honesty: if a command cannot run in your sandbox (the Dart lane
  needs a Flutter cache outside the workspace), record the exact command and
  error and say that lane was NOT RUN. The orchestrator re-runs every lane.
- Do NOT `git add` or `git commit`; do not stage `collab/`.

## Verification (record exact counts per lane)

G0: `clojure -M:test -n <ns>` per namespace while iterating. Before
reporting: the new and changed semantic-register namespaces plus the
phase-3 four and the 4a three, on JVM, Node and Dart (explicit literal
namespace lists; Dart `bb src/dev/cljd_agg.clj --only ns1,ns2,...`
comma-separated; Node shadow-cljs compile with `:ns-regexp` then
`node out/test.js`, confirming "Testing <ns>" for each). Also run, on JVM at
least, the FOUR existing suites you extended, and `yin.vm.semantic-test`,
`semantic-engine-test`, `semantic-ffi-test`, `parity-test`, to show no
regression. `clj -M:kondo --lint` and `cljstyle check` on every changed
path; trailing-whitespace scan. Do not run the full `bb test`; the
orchestrator runs G2/G3.

## Report

Write `collab/1791690000000-vm-engineer-srvm-phase4b-stage1.report.md`. Begin it
exactly with:

Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>

Then: changed files, split new versus modified-existing (the existing ones
must be exactly the four of the exception, additively); exact lane outcomes;
the 26-item acceptance list with PASS/FAIL and the evidence per item; the
mutation table; every ambiguity or finding and what you did; unresolved
concerns; incomplete work. Do not claim edits or runs that did not occur.
