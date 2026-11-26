Created-GMT: 2026-11-24 08:0x GMT
Created-Local: 2026-11-24 15:0x +0700
Dispatched-By: master-green orchestrator seat (interactive)
Destination: codex exec -m gpt-6.1-terra on jupiter (POOL 2), fresh session, full sandbox
Task-ID: 1795560000000-r3 (comparator refinement)

# ORCHESTRATOR DECISION r3: the payload gate is SEMANTIC equivalence, not raw bytes — and the paired run must COMPLETE

Your stop was disciplined but the gate was miscalibrated by the brief. Ruling:

1. **The contract is disposition equality, not raw payload bytes.** The 16-byte delta in
   3.7MB is object identities/UUIDs/unordered-map print order — process noise, not state.
   The admissible comparator:
   - per-entry dispositions (pass/unsupported/gap COUNTS checked against the pinned
     manifest — your PHPT-alone run already showed 119/48/104 matching the oracle), and
   - for `:threw` rows: exception class + reason + message + the source hash, with
     identity/UUID tokens stripped and maps compared as sets (order-insensitive).
   Raw byte hashes are recorded as provenance, never compared.
2. **Complete the paired run.** The interrupted arm (rc=130 at 2068s, mid-PHPT) proves
   nothing. Run BOTH arms to completion uninstrumented if the wrapper perturbs timing:
   (a) aggregate + PHPT together in one fresh JVM, (b) each alone in fresh JVMs —
   semantic dispositions must match; walls recorded for the split-model only.
3. If semantic equivalence holds → implement the split exactly as your reverted draft
   (the balance table: aggregate 1-var group, PHPT 1-var group, 21-var remainder; set
   equality already proven), run the planner tests + kondo, push
   `fix/quick-rebalance-r2`, update findings with the completed A/B.
4. If semantic equivalence FAILS on any row → STOP again, findings only; that would be
   real cross-var state and the namespace stays whole.

Everything else from the prior briefs stands (no test-file edits, no other namespace
touched, never merge yourself).

— master-green orchestrator seat
