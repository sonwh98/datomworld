Created-GMT: 2026-09-19 15:54:00 GMT
Created-Local: 2026-09-19 22:54:00 +07 (Indochina Time)
Coding-Agent: glm
Session-ID: d960a79e-633c-4b1c-bd90-99b60837402e (resumed — your Phase 1+2 session)
# Task: dao.lease Phase 1+2 — reconciliation round r2

An independent adversarial review (claude-fable-5-1, different family) found
your Phase 1+2 implementation good in structure but defective in 18 places:
5 P1 (invariant violated / exploitable), 8 P2, 5 P3. The orchestrator
verified every P1 and P2 against the source and accepts them all. Read:

- `collab/1789832533000-reviewer-dao-lease-p12.claude-fable-5-1.findings.md`
  — the full review (this working tree's copy; authoritative finding list)
- `docs/design/dao.lease.implementation-plan.md` — the revised plan (unchanged)

Scope: still exactly `src/cljc/dao/lease.cljc` and `test/dao/lease_test.cljc`.
Write each prescribed failing test first where the review names one, then fix.

Orchestrator dispositions (all ACCEPT unless noted):

- P1-1 forged release: else branch of `:released` returns judge unchanged —
  `mark-seen` only for a holder release on a live ledger lease. Add the
  forged-then-genuine release test.
- P1-2 truncated drain: record `:truncated?` on the cursor entry when the
  budget exhausts while still answering ok. While any fact cursor is
  truncated, suppress the `:silence` clause for leases registered on that
  medium (with P1-3's registration fix this is sound). `:release`, `:cap`,
  `:policy` and pending retries still run. Add the filler-then-renewal test.
- P1-3 registration: only authoritative facts register a lease on a medium —
  an eligible renewal or release from the holder, or a self-authored grant.
  `apply-valid-fact` returns whether the fact counted; the drain registers
  accordingly. Add the keep-alive-attack test (non-holder medium gap-flood).
- P1-4 overflow wedge: remove the stale-reading clause from `admissible?`
  (ticks on fact cursors are noise; staleness is judged only in
  `apply-tick-value` against its own stream), bound magnitudes so no host
  can overflow or silently wrap (document the bound; make
  `compare-durations` safe on all three hosts), and ensure no thrown
  exception leaves a cursor unadvanced — catch at the drain, treat as
  `:dao.stream/transport-error` abort. Add the hostile-reading test.
- P1-5 resolver: `wire-facts`/`wire-tick` take a source; the judge calls
  `(resolver source fact)` per revised-plan D4. Grantor-authored facts keep
  the `:self` path. Update the tests' attribution accordingly.
- P2-1: assoc `pending` before invoking reclaim; wrap the reclaim in
  try/catch mapping a throw to "did not report" (false).
- P2-2: `deliver-authored` becomes three-way — `:delivered`, `:rejected`
  (defective or inadmissible: leave the queue, count once), `:retry`
  (non-ok append: stay queued).
- P2-3: only `:accepted`/`:rejected` facts may be authored/delivered;
  `author-grant` throws at assembly on anything else.
- P2-4: key `:answered` by `[author proposal-id]`.
- P2-5 gap-before-first-renewal: after the fix, the scenario grant → evicted
  first renewal → medium gap must leave the lease `:unknown`. Choose the
  mechanism (grant-declared medium, or conservative unknown-marking) and
  document the choice in the docstring; add the test.
- P2-6 restart: keep `:seen` across restart (old self-authored `:accepted`
  facts then re-read as inadmissible), clear `:ledger` and the stale
  `:queue` (with the queue disposition recorded), report or return
  unreclaimed inventory items instead of skipping silently. Tests for each.
- P2-7 growth: with P1-1 fixed the attacker-driven growth is gone; document
  legitimate-use growth as owed to the persistence design (do not build
  pruning).
- P2-8 abort starvation: in `drain-facts`, abort only on an abort NEWLY set
  by that cursor's drain, so a step-1 abort still drains every fact cursor.
  Test: persistent tick error, two fact cursors, second cursor's facts still
  applied.
- P3 structural gate: shape-check `:dao.lease/duration`, `:dao.lease/max`
  and `:dao.lease/reading` for ANY fact carrying those keys, whatever its
  status; `proposal` throws on a non-map ask; drop the double
  cause-defect (missing implies invalid).
- P3 misconfig: `initial-judge` throws at assembly on non-positive
  `:drain-budget`, validates unit-table commensurability, and an `unknown`
  entry without `:resumed` cannot throw mid-pass.
- P3 purity test: the current test cannot fail. Either restate it so a
  clock/atom wired into the validator would change the outcome, or remove it
  and say why in your report.
- P3 surviving-renewal test: restate to prove the rule directly (assert the
  last observation was not advanced) instead of relying on drain truncation.
- P3 unknown-silence tolerance: NO code change — the code matches the
  contract's literal text; the question belongs to the contract owner.

After fixing: rerun your lanes (`clojure -M:test`, and the cljs lane) and
report exact counts; the orchestrator runs the CLJD lane. One single simple
command per step; no chaining. Do not stage or commit. Do not edit the plan
or any file outside the two.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

Then report: per-finding disposition (fixed / why not), new and updated
tests, exact test counts, and any finding you could not resolve.
