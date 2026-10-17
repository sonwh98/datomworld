Created-GMT: 2026-10-10 13:10:00 GMT
Created-Local: 2026-10-10 20:10:00 Asia/Bangkok

# Task: Revise the phase-4 evaluator design against the codex Architect review

Role: Lead System Architect (continuing session 07b93752-3d80-4d06-b564-07394c529fed)

Same worktree `/Users/sto/workspace/datomworld-srvm-p4-design`, branch
`srvm-phase4-design`. Docs only. Edit ONLY
`docs/design/yin.vm.semantic-register-vm.evaluator.md` and write your
revision report. Do NOT edit the frozen design
`docs/design/yin.vm.semantic-register-vm.md`: the accepted amendments are
applied to it by the orchestrator after the review closes, from the text
you propose in §6. Do not `git add`/`git commit`.

The independent review (codex gpt-6.1-sol, non-Claude) is at
`collab/1791540000000-architect-srvm-phase4-design-review.gpt-6.1-sol.findings.md`
in this worktree. Read it in full first. Verdict CHANGES_REQUESTED: the
architecture is sound, the F1 restore-seam correction holds, more than
fifteen references grounded; the defects are in the trace oracle, the
slice gates and two guarantees. Its rulings on your findings:

- F1 ACCEPT WITH CHANGES (frozen §2.4 and §11 item 4)
- F2 ACCEPT WITH CHANGES (frozen §2.4)
- F3 ACCEPT (frozen §2.1 and dependent §4.2 commentary)
- F4 ACCEPT (frozen §4.1)
- F5 ACCEPT WITH CHANGES (frozen §2.5 clarification and companion §4)
- F6 ACCEPT (frozen §10 phase 4): spelling `:yin.semantic-register/code`,
  contract "v4", retired at phase 8, phase 5 supplies production scanners
- F7 ACCEPT (frozen §2.4)

Both of your questions are approved: the F6 spelling, and additive
registration in the four existing backend suites as an explicit, narrow
exception to the untouched-tests constraint (existing entries and
assertions keep their behavior; the Rule R backend-selection change is
part of the exception because four map insertions do not cover that row;
empty obligations stay a marked install-mechanics fixture, not evidence of
dependency-scanner correctness).

## Fix every item below; re-ground each from the code, do not trust the
## review's numbers or mine without recounting

1. **Trace event definition (High).** "Value not already in a register"
   is wrong: it miscounts `:define`, repeated literals and primitives that
   return an existing value. Define events by SEMANTIC PRODUCTION or
   DELIVERY, independent of value equality; exclude only administrative
   relocation such as `:return r` (F5: state this as production versus
   relocation). Rework §4's walker criterion and register criterion
   together so both sides use one definition.
2. **Trace extraction (High).** Window differences do not identify one
   write: continuation invocation can replace several entries, grow or
   discard K, and cross bodies. Specify extraction by: instruction
   destination for ordinary results; the captured delivery record for
   continuation invocation; the popped frame or halt value for tail
   primitive results. Never infer a destination from map differences or K
   shrinkage alone. Add a fuel bound so divergence is a diagnostic, not a
   hanging test. Keep whole-trace equality with first-index diagnostics.
   Blocked programs stay outside the step trace; say run-to-completion
   parity covers their effects and resumption.
3. **4a gate contradiction (Medium).** 4a omits `:resume`, yet its
   non-deferred corpus includes `:resume-body` and `:resume-lambda-body`.
   Explicitly defer EVERY reachable unsupported operation (these included)
   with one SHARED eligibility predicate used by the value gate and the
   trace gate; or implement the semantics in 4a. Say which, and why.
   Also qualify item 12: arm expressions may write intermediate
   registers; their RESULT definitions share rd.
4. **Counts and normalization (Medium).** The B0 corpus has 26 rows (not
   28); the register corpus has 32 (not 33); so 4a has 25 B0 rows after
   excluding "stream make", not 27. Recount from the files and cite the
   files. Pinned closure expectations contain `:body`; the normalization
   must use the same comparison representation for expected and actual.
5. **FFI cleanup claim (Medium).** `register-restore` dissociates a LOCAL
   immutable `base`; if `ffi/call-result` throws, no updated machine is
   returned, and the existing FFI test asserts a throw, not an observable
   cleaned machine. Keep the removal-before-decoding ORDER, remove the
   claim that callers receive cleaned state after a throw, restate 4b
   item 17. Do not introduce recoverable error-state delivery (separate
   decision). Add a sentence that the throw-cleanup wording must not
   imply mutation of a machine the caller already holds.
6. **4b item 22 (Medium).** Vectors carry no stamp; `spawn-module`
   supplies "v4" itself; the stub response supplies only `:image :value`.
   Separate malformed/old-shape VECTOR refusal from explicit STAMP
   refusal; test the stamp mismatch through the public loader and do not
   attribute that check to the install response path.
7. **Effect-boundary materialization (Medium).** State explicitly, for
   EVERY engine effect boundary, the step that materializes the current
   hot-loop registers into the machine before engine dispatch (engine
   store context, GC roots and F2 depend on it). Correct every
   `act-builders` call to pass its `analysis` argument. Pin public loader
   arities and default options, and reset's window clearing.
8. **F1/F2 text.** F1: qualify terminal checking as the run-queue path's
   behavior; `resume-continuation` performs no terminal-outcome check and
   can be called from an active `:resume` instruction, and its base is
   not necessarily blocked; say neither restore path REQUIRES an
   activation. Explicit resume must handle the returned halted state.
   Exactly-once means one delivery per consumed entry or per continuation
   invocation, not single-use reified continuations. F2: for an empty-K
   deferred completion the machine's `:env` is `(without-store-of (:env
   base))`; for an immediate tail completion the current E is used; do
   NOT equate scheduler `base.env` with the tail site's E universally.
9. **Editorial (Low).** Fix §1.6's abbreviated delivery record to include
   `:rd r` (the abbreviated kappa record must not become an alternative
   accepted shape); fix §1.8's nonexistent "§2.3 step 6" reference; note
   that the de Bruijn register kernel's `write-back` precedent supports
   return-mode restoration but is not an identical VALIDATION precedent
   (it also checks format, payload and resume-value constraints).
10. **§6 (findings).** Rewrite F1-F7 as the exact amendment text for the
   frozen design, per the rulings above (which section each amends),
   ready for the orchestrator to apply verbatim. Record the two approvals.

## Report

Write `collab/1791540000000-architect-srvm-phase4-design-r2.report.md`.
Begin it exactly with:

Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>

Then a table: each codex finding (and each numbered item above) -> the
section you changed -> what you recounted or re-grounded and the result.
Say plainly where you disagreed with the review and why (with code
evidence), and anything you could not ground. Do not claim reads or
edits that did not occur.
