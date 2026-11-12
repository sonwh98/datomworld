Created-GMT: 2026-11-10 20:33:20 GMT
Created-Local: 2026-11-11 03:33:20 +0700 (+0700, Asia/Ho_Chi_Minh)
Coding-Agent: pending (dispatch-lane decided at dispatch time per routing-status.md)
Session-ID: pending (provider-generated)

# Task: PHP R1+R2+R3 — the §8.10.7 boundary round (ONE coherent round)

Role: Engineer (implementation, yang.php)

Implementers:
- Model: TBD at dispatch | Assigned: TBD | Status: staged (NOT yet dispatched — gated on JS R1+R3 landing)

## Status: STAGED BRIEF — dispatch is gated on the JS R1+R3 round landing on master
This brief is written by the yang.php orchestrator seat so the round can start with one
dispatch when the gate opens. Do NOT act on it until it is referenced by a live dispatch
prompt naming you, a worktree, and a session.

## Read first (authority chain)
- docs/design/yang.antlr.md §8.10.7 — the call convention and the boundary contract
- collab/exception-track/1794960000000-owner-ruling-exception-uniformity-v2.md — the OWNER RULING OF RECORD (v2), reviewed and accepted
- collab/exception-track/1794970001000-to-tsphp-your-js-and-php-rounds.md — the steward's brief of record for THIS round (§PHP section is the defect list)
- collab/exception-track/1794341000000-continuation-orchestrator-unification-ruling-of-record.md — ruling (A): boundary transfer only; the continuation-handler prelude STAYS
- collab/exception-track/1794343000000-exception-track-surface-registry.md — the exclusive surface map (prelude.cljc bridge + protocol paths are this round's; do not touch other tracks' regions)

## The defects (steward's audit; verify each against current master before fixing)
1. Function entry (prelude.cljc ~:2264) and Closure entry (~:4217) lack the bridge — neither
   reads the supplied :yang.call/raise; escapes follow the guest handler stack.
2. raise with an empty handler stack RETURNS normally (~:194-197) instead of delivering to the
   caller's supplied exit.
3. php/call, call-with-binding-profile, call-named raise the whole condition ENVELOPE instead of
   extracting the value (~:2384) — reception defect.
4. not-callable becomes a fresh Error — the original condition is discarded.
5. ForeignException does not exist.

## The governing caution (the reviews' mandate, verbatim intent)
Landing the bridge BEFORE reception repair would route local Throwables into the
envelope-raising receiver and BREAK existing local typed catches — this is WHY the round is
one coherent round covering the function entry, the Closure entry, and all three helpers.
Do not split it.

## Contract
- Every admitted guest-call boundary delivers :yang.call/thrown with the thrown VALUE (not the
  envelope) to the caller's supplied raise exit, exactly once.
- Local typed catches keep working unchanged (regression test required).
- Protocol reception retains the complete original condition (reason + evidence); an unchanged
  rethrow forwards with no new wrapper.
- not-callable preserves the original condition.
- TDD per docs/agents/build-n-test.md: the failing tests first, then the minimum implementation.
- G0 gates: yang.php focused namespaces green + yang.call.exception-contract-test (kit) — any
  ledger-row flip you did not author STOPS the round for diagnosis (six clojure R3 rows on
  exception-r3-impl are IN FLIGHT; current master ledger is the baseline).
- Suite-scale runs remote-only (shiva/jupiter) per build-n-test.md; individual tests local.

## Deliverables
- Branch exception-php-r1r2r3 cut from CURRENT master at round start (one writer).
- Findings to collab/exception-track/<dispatch-ts>-engineer-php-r1r2r3.<model>.findings.md
- No push without the orchestrator; step-7 review applies (argon/other architect lane per routing-status.md).
