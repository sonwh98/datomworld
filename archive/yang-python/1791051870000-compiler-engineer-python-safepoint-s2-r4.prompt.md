Created-GMT: 2026-10-03 18:24:30 GMT
Created-Local: 2026-10-04 01:24:30 +07 (+0700)
Coding-Agent: claude
Session-ID: a80b326f-0efb-4ae5-ae0f-df0154076bab (resume of the safepoint-s2 engineer session)

# Task: safepoint-s2 round 4 — the generator resume-admission hole, missing tests, rebased onto master

Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-04 01:24 +07 | Status: active | Rationale: resume of the safepoint-s2 engineer (rounds 1-3 context); applies two independent gates' findings on a rebased tree

Work in /Users/sto/workspace/datomworld-py-safepoint2 (branch yang-python-safepoint-s2). The orchestrator REBASED your worktree onto master be1f8d06 (it was 16 commits behind; master now
has float-fix, the range fast path, C3-S1/S2 numeric keys, and the ^:slow / dao.test-slow/guard test-lane changes) and re-applied your diff. Backups: ../datomworld-py-safepoint2.prerebase.patch,
../datomworld-py-safepoint2.safepoint_programs.cljc.bak, and a git stash sp2-prerebase. You cannot run git write commands (stash, checkout, rebase, reset, commit, stage): do not try. One conflict:
test/yang/python/antlr/safepoint_test.cljc (2 hunks): keep master's `^:slow` tag and `(dao.test-slow/guard "tail-preservation-test" (fn [] ...))` wrapper on `tail-preservation-test` exactly,
and re-apply your own additions around it. Edit files directly. Do NOT run the full lanes or Dart (the orchestrator runs them).

## Gate findings (two independent gates; the same defect was found by both)

Reports staged in this worktree's collab/: 1791007653000-reviewer-python-safepoint-s2-gate.gpt-6.1-sol.final.md and ...glm-5.3-flash.stdout.log. Your depth mechanism (:base, setter check,
79/99 pins) was confirmed correct by both; fix only what they found:

1. (sol P1, glm P2) `py/gen-switch` (prelude.cljc, the generator crossing, ~283-320 on the old base) never checks the recursion limit when a generator is STARTED or RESUMED; only
   function entry (`py.sp/enter`) does, and the generator body lambda is unmarked. So a call-free generator resumed while its resumer sits at the limit runs unbounded where CPython raises
   RecursionError at every resume entry, e.g. `def g(): x = 1; while True: x = x + 1; yield x`. The converged ruling (gpt-6-astra, collab/1790982600000-architect-generator-depth-ruling.gpt-6-astra.findings.md)
   requires: "Check the effective depth when resuming, even if execution reaches another yield without an intervening function call. An admission failure must leave the suspended generator and
   caller context intact." glm's fix shape: on both live paths (`py/gen-start` and resume), BEFORE any write to the generator or `py.rt/ctx`, compare the prospective effective depth with the limit
   `(if (< (cell/get py.sp/limit) (+ 1 (py/abs-depth (cell/get py.rt/ctx)))) (py/raise-new py.b/RecursionError ...) ...)` and raise on the caller's stack, leaving the generator :created/:suspended.
   First PROVE it: write the failing test (red) on the current tree, show it fails on all four VMs, then fix (green).
   Add the 8.5.2 sentence stating the generator admission rule, plus a regression: a call-free generator resumed at the limit gives RecursionError, the generator stays resumable from a shallower
   depth afterwards, and the caller's context/depth are unchanged. Delegation note: C2-S3 (not yet landed) adds `yield from`; all its generator entries funnel through `py/gen-switch`, so your check
   covers them; do not add anything about `yield from` now (it lands after C2-S3 and the orchestrator will have you add a delegation resume-admission test then).
2. (sol P2, glm P3) Missing tests, all four evaluators across hosts: shallow-first-then-deep resumption (you only had deep-then-shallow); nested ACTIVE generators each counted once (an outer
   generator that resumes an inner via `next` while itself suspended; expect the additive count from the ruling); `gen.throw` / `gen.close` with `finally` execution at the CURRENT resumption base
   (the existing test covers only an in-body raise); a `try` exited by normal return across differing resume depths; a rejected deep resume followed by a successful shallow resume with the
   generator and caller untouched. Add them as programs in safepoint_programs.cljc (the parser-check test keeps them honest) with pinned counts.
3. (glm P3) docs/design/yang.antlr.md: the pre-existing depth paragraph (~1579-1581 on the old base) says "an escape restores the whole record saved at capture", one paragraph above your slice-2
   refinement "comes back with the current `:base` kept". They contradict; trim the old clause (for example "an escape restores the record saved at capture (slice 2 refines this below)") or fold in the
   `:base` caveat.
4. (both, P3) Line widths: safepoint.cljc lines 85 and 92 (message strings), the 31 over-80-column lines in safepoint_programs.cljc (reflow the packet data to the sibling lower_portable_test.cljc's
   width; keep long literal strings only where wrapping would change them), the 105-col comment at prelude.cljc ~273, and the over-width added test line safepoint_test.cljc ~568.
5. Do NOT re-mint the content-address goldens this round. C2-S3 lands first and also changes the prelude; the orchestrator will rebase you onto it and the final re-mint happens ONCE then.
   `float-address-test` is therefore expected to fail on the prelude-derived goldens until then (your prelude edit moves them again); say so in your report, and confirm the hook-prelude golden is
   unchanged unless you changed the hook prelude.

## Lanes and rules
- FOCUSED runs only: safepoint-test, the yang safepoint test namespace (yang.safepoint-test), e2e-test and e2e-c2-test (`-e :slow`), prelude-parity-test, lower-test, and your new red-then-green
  test. Do not run `long-loops-test`. After the orchestrator's full lanes you may get a follow-up.
- kondo 0 errors; `cljstyle fix` then `check` via mise (run directly, not through a piped loop).
- Allowed files: those in your diff plus safepoint_programs.cljc, docs/design/yang.antlr.md. Ask before anything else.

Append a 'Round 4' section to your findings file (collab/1790969285000-compiler-engineer-python-safepoint-s2.* findings, or create
collab/1791051870000-compiler-engineer-python-safepoint-s2-r4.claude-opus-5-5.findings.md): per item what changed, the red-then-green evidence for item 1 (exact failing output, then passing), focused
counts. Begin the final response with Completed-GMT / Completed-Local / Coding-Agent / Session-ID as before. Do not claim edits or runs that did not occur.
