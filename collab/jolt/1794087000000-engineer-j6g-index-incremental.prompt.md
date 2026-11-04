Session-ID: 0d3f8e24-7a51-4c93-8b26-1f9e6a3d5c80

# J6g implementation round: incremental code-index materialization (ruled fix (a))

Role: implementation (agy gemini-3.8-flash or codex per the routing).
Read-only on the adapter/parser: src/jolt/** is FROZEN (A1-A7 hold). Your
surface: src/cljc/yin/repl/index.cljc, src/cljc/dao/space/transactor.cljc,
src/cljc/yin/repl.cljc (the round boundary), and their tests. Edits only;
the orchestrator verifies remotely and gates with kimi.

Worktree: this one (jolt-j6g; the workload W harness and the watchdog
runner are on the branch, merged to master as 1cddaa93 — `git pull --rebase`
first).

## Governing authorities

1. **The J6g ruling** (codex gpt-6.1-sol, architect-review — filed:
   collab/jolt/1794006000000-architect-j6g-ruling.gpt-6.1-sol.ruling.md in
   the orch worktree's collab/jolt/): the scheduling principle is RULED —
   A1's transfer-count bounds do not bound an owner round's synchronous
   interpretation; (a) incremental index materialization is selected as the
   primary remedy for the confirmed history-growth mechanism (the traced
   run confirms: :code-index max-ms grew 7.6 s → 10.1 s per call over 51
   rounds; :ast-index negligible; B starved at 51 samples; A's transfer
   never completed).
2. **The diagnostic** (collab/jolt/1793990000000-architect-j6g-diagnose.findings.md):
   the full causal chain and the preservation obligations.

## The ruled design (from the ruling's §(a); the preservation obligations are binding)

Carry the publication cursor, covered roots, and allocator successor as
explicit **composition-owned derived state**. Consume the committed suffix
rather than reopening and reconstructing the entire history each
evaluation. Retaining an allocator successor must preserve transactor
authority and derive/validate it from authoritative history on recovery.
Publish covered roots and materialize/verify their referenced content
before declaring the round published, as today. The accepted
per-evaluation publication cadence in `yin.repl.index:21` is preserved —
not silently changed to periodic publish.

If bounded owner service also requires yielding within publication, make
the pending work an explicit successor value driven by the owner. That
requires a ruling about what queries observe while a publication is
pending — so DO NOT implement yielding-within-publication in this round;
if the design needs it, note it as a follow-up ruling request. Do not
silently make a query read an older coverage frontier or bypass an
indexer.

## Deliverables

1. The composition-owned incremental materialization in
   src/cljc/yin/repl/index.cljc (+ transactor.cljc where the watermark
   scan lives): the fresh evaluation publishes only the appended
   transaction suffix against the retained cursor.
2. Recovery validation: after a worker restart, the cursor is derived from
   the authoritative history and validated (a stale/mismatched cursor
   fails closed to full reconstruction — today's path — never to a wrong
   one).
3. Gates: the existing yin.repl index/transactor tests stay green; add the
   incremental-vs-full equivalence test (the same session history through
   both paths produces identical covered roots); the workload W harness
   unchanged except it now observes the cursor (no harness criterion
   change).
4. cljstyle clean; no collab/ path in comments.

## Reporting

Write your findings to collab/jolt/1794087000000-engineer-j6g-index-incremental.findings.md
in this worktree: the cursor state shape, the recovery derivation, the
equivalence-test evidence, and any place the ruled design needed
adjustment.
