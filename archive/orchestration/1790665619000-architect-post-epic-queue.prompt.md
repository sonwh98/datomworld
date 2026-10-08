Created-GMT: 2026-09-29 07:06:59 GMT
Created-Local: 2026-09-29 14:06:59 +07 (+0700)
Coding-Agent: codex
Session-ID: 01a0ebfd-1cf8-7c82-a5d2-700e6a293c1e (captured)
# Task: Architect rulings — post-epic queue: incremental index publish, dao.lease :self-source media, serving lease-proposals

Role: Lead System Architect

Implementers:
- Model: gpt-6-sol | Assigned: 2026-09-29 14:06:59 +07 (+0700) | Status: active | Rationale: owner chose the post-epic queue ("5"); three design items batched in one turn; fable reserved by owner; gpt-6-sol is the authorized Architect route

Read-only; no edits. You are headless; your final response is the deliverable. Master is 3cf3c6de (remote FFI
complete: slices 3a-3d; REPL code indexing fd3f0edd). Read docs/design/datom.world.md (axioms, six invariants) first.
For each item rule precisely enough to implement without a further design round, OR say plainly that it should not be
done / should wait, with reasons. Distinguish architecture from OWNER decisions.

ITEM 1 — Incremental covered-index publication (dao.space).
The REPL indexer (src/cljc/yin/repl/index.cljc) publishes every eval round through dao.space.transactor/publish! ->
dao.space.index/publish-index! (~index.cljc:564), which rebuilds the covered indexes over the whole local history each
time: per-round publish time grows with session history (owner accepted this as the cost of the every-round cadence;
memory is already bounded via a fresh intake per round). Read src/cljc/dao/space/index.cljc, transactor.cljc,
docs/design/dao.jing.md (Publication), the indexer, its gate findings
collab/1790598850000-reviewer-repl-code-index-gate*.findings.md, and docs/design/yin.repl.dao.space-index.md ("Cost and
boundary"). Rule: should publish-index! gain an incremental mode (new facts folded into the previous manifest's
covered indexes, reusing unchanged content-addressed nodes), what is its contract (inputs: previous manifest + delta?;
determinism: must an incremental result equal a full rebuild's manifest address?), which files, and acceptance tests
(incremental == full rebuild equality across randomized histories on CLJ/CLJS/CLJD). Or is it premature?

ITEM 2 — dao.lease accepts a fact medium whose :source is the judge's own :self.
dao.lease/make-judge (~lease.cljc:2114) and the new public wire-declared-facts (slice 3b, c2417899) accept a medium
whose attribution source equals the judge's :self, so facts on it are attributed to the judge itself.
yin.vm.ffi.remote-serve refuses the grantor-equal source for its own standing media (gate P1 of 3b:
collab/1790608971000-reviewer-ffi-lease-wiring-gate.gpt-6-sol.findings.md). Read docs/design/dao.lease.md and
lease.cljc. Rule: is a self-sourced medium ever legitimate (the judge reading its own ledger/facts?) or a defect that
dao.lease should refuse at assembly and at wire-declared-facts? If refusing, is it a behaviour change any existing
caller relies on (grep callers/tests)? Files and acceptance.

ITEM 3 — Serving lease-proposals.
docs/design/dao.stream.remote.md section 6 (~507-560) names a lease-proposals entry (surface #{:writer}) beside
lease-grants (#{:reader}). Slice 3c published lease-grants only (unsolicited grants per served identity); the 3c
implementer noted proposals are unserved (collab/1790610416000-vm-engineer-ffi-responder-e2e.claude-opus-5-5.report.md).
Read section 6, src/cljc/yin/vm/ffi/remote_serve.cljc and remote_serve/holder.cljc, dao.lease proposal/grant/refusal.
Rule: does the remote-FFI export binding need a lease-proposals entry (a remote party proposes a lease; the judge
grants or refuses), or is unsolicited grant-per-export the complete model for this composition and proposals belong
to a different composition? If needed: shape, files, acceptance.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Then one section per item: Ruling (DO / DO LATER / DON'T), design, files, acceptance tests, risks, file:line evidence.
Finish with an ordered list of what to implement and an OWNER-decisions list.
