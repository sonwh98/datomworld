Created-GMT: 2026-09-19 18:45:00 GMT
Session-ID: compiled by the orchestrator seat (interactive) from the R0-R4 artifacts
Coding-Agent: interactive
Task: dao.stream.waitset.implementation-plan.md — adversarial consensus report
Status: CONSENSUS CLOSED 2026-09-20 02:33 +07 — plan unsound as written; fix is a revision, not a redesign

## Process (orchestrated by the agy seat, R0-R3; takeover seat, R4)

- R0: gpt-5.6-sol (codex thread 01a0bacb-3b91-7190-8412-3f1e85bb552a) — verdict unsound,
  5 blocking / 5 should-fix / 1 note (collab/1789840265741-…findings.md)
- R1: fable-5-1 (claude session 0d520667-20b1-42f7-83c8-9b74753438cb) — 7 agree, 2 partial,
  5 new findings incl. W2 deliverable unsatisfiable (collab/1789840989024-…findings.md)
- R2: gpt-5.6-sol (resumed) — conceded host-timer blanket prohibition, nudge severity,
  rotation remedy withdrawn; proposed the 13-item consensus list
  (collab/1789841259759-…findings.md)
- R3: fable-5-1 (resumed) — signed all 13 (10 outright; 4, 7, 9 with caveats); conceded
  Dispute A in full (external parks deleted from W3; pure cadence-step shape); proposed the
  probe-entry compromise for Dispute B (collab/1789841522905-…findings.md)
- R4 (takeover seat): gpt-5.6-sol (resumed) — DISPUTE-B: accepted
  (collab/1789842300000-…findings.md)

## The consensus

Joint verdict: the W0-W2 extraction, the dao.stream.waitset name, the refusal of :resume,
queues and tasks, and the zero-diff substrate condition are all endorsed by both architects.
The four blockers are the W2 public-function contradiction, the incomplete resolver seam,
the writer-closed-to-end mapping, and the unsettled W3 ownership/budget contract.

The authoritative items list is R2's "Proposed Consensus Findings List" (13 items, severity
and plan-line references there), as qualified by R3's per-item caveats and R4's Dispute-B
resolution. Gating: items 1-3 + the W1 tests in 7, 8, 10, 11 gate W1; 4-6 gate W3; 9 gates
W4; 12 gates W5.

## Dispute B as closed (R4)

Item 9's remedy is the NON-ADVANCING ADVISORY PROBE: a :next entry whose resolver supplies
the consumer's current cursor and whose :advance is identity — commits nothing, woken
value/cursor discarded as advisory, the compound step (forward-step / serve-once!) remains
the sole authority. Writer-side :put migration for forwarders and serve-once is removed
outright; host cadence retained wherever probing does not fit. Three qualifications:
1. "Re-parked after each step" applies after a NON-TERMINAL step; a terminal consumer
   retires its probe, and pending response writes must remain eligible for host cadence —
   source readiness never gates their retry.
2. Cursor isolation concerns underlying cursor STATE, not merely reference spelling: a
   probe must not alias cursor state advanced by another waitset entry.
3. Terminology: "non-advancing readiness probe," never "pure" — it observes a live medium.
