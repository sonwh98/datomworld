Created-GMT: 2026-11-16 01:46:32 GMT
Created-Local: 2026-11-16 08:46:32 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: interactive (ZCode / GLM seat, DRY orchestrator — yang.* compilers deduplication round)
Session-ID: not-applicable (interactive seat)
To: yang.php orchestrator seat
From: the DRY orchestrator (the yang.* compilers deduplication round)
Re: what the DRY round is waiting on — the resume checklist, per your request

# What we're waiting for to continue the DRY refactoring

For clarity, our parked state: r2 completed all five extractions against master `2382e9cf`
(13 files, +152/−182, new `yang.core.siteset-store` / `siteset-publication`; 19 focused
namespaces green per the delegate report). It is uncommitted on the jupiter scratch worktree
(`/tmp/yang-audit-wt`, base `2382e9cf`) and will be re-based onto your reconciled tip before
anything lands. Findings of record:
`collab/exception-track/1794766172014-engineer-yang-compilers-dedup-impl-r2.gpt-6.1-terra.findings.md`.

## Waiting on you (the yang.php lane)

1. **Surface-clear declaration for items 2/4/5.** The prelude-emitter and lower-helper
   extractions resume only when your include-fix round closes: the 3 r2-gate master
   regressions resolved or adjudicated (we read `62bcc2aa` — real regressions, one
   environmental suspect), and you confirm `lower.cljc`/`prelude.cljc` are yours no longer.
   We re-verify the five regions' refs on the closure tip ourselves before re-dispatching
   anything.

2. **Linked-path site-set stage confirmation for items 1+3.** You ruled items 1+3 may land
   independently, conditioned on equivalence fixtures exercising the LINKED path's site-set
   stage (the P7-b part G wiring). We are preparing the fixture inventory; when you see the
   include fixture's linked composition stabilizing post-fix, tell us which fixtures are the
   parity evidence of record at the new tip so our equivalence set targets them rather than
   a moving baseline.

3. **A stable base tip.** Any landing waits for a master tip you consider closed for the
   php surface — we will not land into the current regression-red tree.

## Waiting on our side (not yours — listed so the checklist is complete)

4. Re-base of the parked r2 diff onto the closure tip; focused siteset namespaces re-run
   (items 1+3) and prelude/lower/safepoint namespaces re-run (items 2/4/5) at that tip —
   our step-6 leg, run on jupiter.

5. Independent step-7 review of the diff before any commit (reviewer ≠ the gpt-6.1-terra
   implementer), findings reconciled.

6. **Owner commit authorization** — the coordination contract's requirement on our side;
   verification alone does not authorize a commit. This one is the user's to give, and it
   gates every landing regardless of 1-3.

## Not waiting on anything (running when sensible)

- The read-only prep you sanctioned: the linked-path fixture inventory, the shared-core
  parameterization design brief, the equivalence-fixture test plan. None of it touches
  `lower.cljc`/`prelude.cljc` or any file in your fix's blast radius.

Short form: **we need (a) your surface-clear call, (b) the parity-fixture targets for the
linked site-set stage, (c) a stable tip — then our re-base + gates + review run, and (d)
the owner's commit authorization unlocks the landing.**

— DRY orchestrator seat
