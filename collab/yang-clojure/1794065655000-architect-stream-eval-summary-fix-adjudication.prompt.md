Created-GMT: 2026-11-07 12:5x GMT
Created-Local: 2026-11-07 19:5x +07
Coding-Agent: codex
Session-ID: 836be387-69ef-4f40-b5dc-a2f470d8e964

# Task: Architect adjudication — the stream_eval manifest-address fix (one ruling terminates the unit)

Role: Lead System Architect (adjudicating an engineer-proposed fix; read-only)

Implementers:
- Model: gpt-6-astra | Assigned: 2026-11-07 19:5x +07 | Status: active | Rationale: adjudicating architect must be a different family than the proposal's author (muse); astra previously reviewed adjacent surface (dht boundary) and is free

## Dispute background (verified)

Master nightly red (both lanes): yang/clojure/stream_eval_test.cljc:444
every-vm-answers-the-same-stream-script — "repl-state agrees up to the VM's name
and the medium's identity". Four VM summaries differ ONLY in :index-publication
:manifest-address. A muse-wonder-1.0 diagnosis round (COMPLETE, evidence:
collab/jolt/1794036904000-orchestrator-stream-eval-manifest-diagnosis.muse-wonder-1.0.stdout.log)
traced it: each shell indexes a provenance datom [meta-e :yin.repl/session
<random-uuid>] BY DESIGN (repl.cljc:926 create-state), so the manifest
legitimately differs per shell; :index-publication began publishing
:manifest-address in commit 3c301b65, exposing that. Reproduction was EXECUTED
on shiva (1 failure / 309 assertions; per-VM table in the findings).

MUSE PROPOSAL (the thing you adjudicate): drop :manifest-address from
:index-publication in repl.cljc:2209 (keep :cursor :transactions); update the
pinning assertion at test/yin/repl/index_test.cljc:288. Rationale: the address
correctly hashes provenance; the summary should honor the token-independence
contract the test already grants seals and medium identities; coverage still
cross-checked via cursor+transactions.

## Your job

1. Verify the diagnosis against the code (repl.cljc:2209 area, index.cljc:407-445,
   the commit 3c301b65 diff if obtainable, the test at stream_eval_test.cljc:417-447).
2. Rule on the proposal: is dropping :manifest-address from the SUMMARY the right
   fix — vs alternatives (test-side normalization like the existing unseal/strip;
   making the summary report a content-equality witness instead of the address;
   removing provenance from the indexed datoms)? Rule per the design docs
   (yin.repl.md repl-state contract; yin.repl.dao.space-index.md; the
   token-independence line in index.cljc:536's status contract).
3. One adjudication terminates this unit: your ruling is final for it. If you
   approve the proposal, enumerate the exact edits (file:line, including every
   test pin that asserts :manifest-address's presence — grep test/ for it) and
   any consumer of :index-publication :manifest-address in src/ (jolt_workload_w.clj:190
   references :shell-index-publication-cursor — check for address consumers too).
4. State the acceptance test changes precisely (what the pinned assertion becomes).

Constraints: read-only. Do not run suites. Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
