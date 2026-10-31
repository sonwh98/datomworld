Created-GMT: 2026-10-06 15:35:00 GMT
Created-Local: 2026-10-07 22:35:00 +0700
Coding-Agent: glm (glm-5.3)
Session-ID: pending (provider-generated)

# Task: UCF M-next D13 — the driver's candidate half
Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: glm-5.3 | Assigned: 2026-10-07 22:35:00 +0700 | Status: active | Rationale: the holder-side driver; the writer/reader namespaces are its engine room

Implement D13 in /Users/sto/workspace/datomworld-d13 (worktree, branch
ucf-d13-candidate, based on master with D12 landed). Read first, in
the worktree's collab/: the D plan r3 (1791194000000-architect-m-next
-d-plan-r3.claude-fable-5-1.findings.md — sections 1.5, 1.7 (the
candidate steps), 1.11 and the D13 test contract), the astra
sign-offs' carried obligations (the D10 inputs ruling
1791240000000-...: D13 authenticates evidence, proves the checkpoint
is an admitted occurrence variant, rechecks tenure before scheduling
and IO, and handles release after post-grant failure), the residual-2
release wording (1791192700000-...r2-confirm...), and the abort rule
(UCF 7.7.4 as amended). Then the landed holder namespaces:
holder/export.cljc (enter/prepare/encode/abort), holder/evidence.cljc
(read-evidence), holder/writer.cljc, holder/reader.cljc,
authority/front.cljc (the proposal carriage, D2's :yin.k/renewal),
and UCF 7.7.3/7.7.7 (the proposal and grant lifecycle).

The contract (r3 1.7's candidate steps, as amended by the rulings):

One explicit step state, plain data, stepped by the composition; it
reaches the authority only through the front's streams and the
authenticated ledger reader (D3) — the three remote paths of r3 1.5.

1. **Fetch and validate**: the bytes decode and validate with zero
   side effects (the D7 pipeline).
2. **Check the authority attachment**: missing is `:yin.k/unsatisfied`.
3. **Propose**: unanswered is `:yin.k/awaiting-grant`; a grant to
   another is `:yin.k/not-holder`.
4. **Accept only a grant with binding evidence** (custody's
   binding-evidence over the D3 fold): an invalid binding is
   `:yin.k/not-holder`, followed by a release.
5. **Lower with the D10 grant inputs**: the checkpoint address, the
   protection declaration, and the grant evidence (checkpoint,
   lease, holder, evidence E, tenure) — lower runs only on the
   accepted grant; a regrant replays from the checkpoint.
6. **Run under the gate** and drive the writer and reader steps.
7. **Renew** through D2's front request before half the duration;
   all IO stops at the bound.
8. **Any failure after the grant releases first** (cleanup, not
   recovery: the residual-2 wording — a release cannot clear a
   quarantine, cannot complete without accepted completion evidence,
   and follows the ordinary reclaim/regrant policy for non-quarantined
   runs); a pending release is retried.
9. **D13 must additionally establish current tenure** (the D10
   ruling: the evidence's :ready answer proves historical binding,
   not present liveness — recheck through the ledger reader's fold
   and the lease bound before scheduling execution and IO).
10. **The checkpoint is an admitted variant**: equality of supplied
    addresses is not proof — the admission comes from the ledger
    fold's `:yin.k/admitted` set (the authority's own admission
    facts).

Test contract (r3's D13 row):
- Two candidates over two encodings of one occurrence: one activates,
  the other gets `:yin.k/awaiting-grant` then `:yin.k/not-holder`
  (14.2.4 row 1, same host).
- A binding by another author, in another record, duplicated, or with
  a float epoch: `:yin.k/not-holder` and a release.
- Unavailable ledger history never activates (never an empty prefix).
- Post-grant failure releases, or retains release progress.
- Renewal before half the duration; all IO stops at the bound.
- The source itself resumes only after its own grant.

Acceptance criteria:
- Test-first per behavior; portable `.cljc`; JVM during iteration.
- New namespace src/cljc/yin/vm/ucf/holder/driver.cljc and its test
  file; the landed namespaces are NOT in the permitted diff — if a
  public seam is missing, stop and report (that is a ruling).
- No transport vocabulary: the front streams, the ledger reader and
  the lease clock are composition-supplied.

Constraints:
- No git writes. kondo/cljstyle may be sandbox-blocked; note it.
- `#?(:cljd nil :clj ...)` order for JVM-only test branches (:cljd
  first); a 0.0 literal is the integer 0 on JS.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact test/check outcomes with counts, the red
and green evidence, unresolved concerns, and any incomplete work. Do
not claim edits or tests that did not occur.
