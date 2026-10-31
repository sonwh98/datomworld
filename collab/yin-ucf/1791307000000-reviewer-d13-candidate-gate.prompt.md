Created-GMT: 2026-10-06 16:25:00 GMT
Created-Local: 2026-10-07 23:25:00 +0700
Coding-Agent: claude (opus-5-5, plan review)

# Task: gate review, M-next D13 — the driver's candidate half (read-only; a verdict is the deliverable)
Role: Review (routine gate)

Review the uncommitted work in /Users/sto/workspace/datomworld-d13
(branch ucf-d13-candidate, based on master 4c4764f7 with D11/D12 and
the ClojureDart var fix landed). The engineer's report:
/Users/sto/workspace/datomworld-d13/collab/1791299000000-compiler
-engineer-ucf-d13-candidate.findings.md. Read it first, then the two
new files (src/cljc/yin/vm/ucf/holder/driver.cljc and its test).

The contract: D plan r3 sections 1.5 (the three remote paths),
1.7 (the candidate steps), 1.11, and the D13 test contract
(/Users/sto/workspace/datomworld-d13/collab/1791194000000-architect
-m-next-d-plan-r3.claude-fable-5-1.findings.md), the D10 inputs
ruling's admission-proof and tenure-recheck obligations
(1791240000000-architect-d10-lower-inputs-ruling.gpt-6-astra
.findings.md), and the residual-2 release wording
(1791192700000-...r2-confirm...). In brief: one explicit plain-data
step state reaching the authority only through the front's streams
and the D3 ledger reader; fetch/validate with zero side effects; the
attachment check; proposal (awaiting-grant / not-holder); acceptance
only on binding evidence; lower with the D10 grant inputs; renewal
before half the duration with all IO stopping at the bound; release
after post-grant failure as cleanup; tenure rechecked before
scheduling execution and IO; the checkpoint proven an admitted
variant from the ledger fold's admitted set.

## What to attack

1. The six contract rows, end to end (the engineer's report lists
   them): two candidates over two encodings; the four invalid
   bindings each yielding :yin.k/not-holder + a release; unavailable
   history never activating (all four D3 failure modes, never a
   release, never an empty prefix); post-grant failure releasing
   with the retained-release retry; renewal before half with IO
   stopping at the bound; the source resuming only after its own
   grant.
2. The admission-variant proof: does the driver check the checkpoint
   address against the ledger fold's admitted set, not just address
   equality? Where does the check sit relative to lower?
3. The tenure recheck: before scheduling execution AND IO, per the
   D10 ruling — and is the recheck through the ledger fold (current
   liveness), not just the stored :ready answer?
4. The release semantics: cleanup only — a release never clears a
   quarantine, never completes without accepted completion evidence,
   follows the ordinary reclaim/regrant policy; a pending release is
   retried.
5. The step state: plain data, no transport vocabulary, the front
   streams/ledger reader/lease clock composition-supplied; one
   vm/run per step (the engineer flags this as a scheduling choice —
   assess).
6. The engineer's honest boundaries: the enrolled writer arm is
   driven but not end-to-end (successor bodies are orphan-refused
   until D14's closure); the judge's release-lapse is only exercised
   where the judge granted the lease; two clock readings per
   accepting step. Are the deferrals correctly scoped to D14?
7. Portability and style of the new files (kondo 0/1 pre-existing,
   cljstyle clean per the orchestrator's runs).

Verdict first: READY or NOT READY (with what must change), then
numbered findings with file:line evidence. Read-only: edit nothing,
run no suite.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
