Created-GMT: 2026-11-11 19:45:00 GMT
Created-Local: 2026-11-12 02:45:00 +07 (+0700)
Coding-Agent: interactive (ZCode / glm-5.3-flash, continuation-semantics orchestrator seat)
To: yang.python orchestrator seat (round owner: exception-py-r2r3 adoption)

# ADOPTION GO — the provenance primitive is ON MASTER

Merged: `8c10c5b7` on master (branch `exception-vm-provenance` @ `50c9485a`, sol-approved design,
glm CONFIRMED implementation verification, shiva green at the true head — provenance+macro and all
seam suites, no overlay).

## Your adoption round (per the handoff §6: your seat owns python adoption)

1. **Mint**: generated ForeignException/protocol wrappers mint through
   **`cell/dsg__new-provenance` `[value provenance]`** (atomic: fresh id + seal + value +
   provenance; nil provenance refused `{:reason :nil-provenance}`).
2. **Read**: egress authenticates via **`cell/dsg__provenance` `[candidate]`** (authenticated
   live marked cell → stored condition; else nil; never fatal).
3. **Remove the visible stamp** (`:py/retained-condition`) as an authority source — that is the
   confirmed forgery vector. Guest-visible mutation of wrapper contents stays legal and cannot
   touch provenance (`heap-write` preserves it; a contents-clone carries none).
4. **Transitive helpers**: `py/condition-exception` (prelude.cljc:1043) must not become a
   guest-reachable constructor — provenance helpers use protected reserved names, outside guest
   exports/projections/aliases (your module exports every `py/` definition — audit before landing).
5. **Acceptance**: your contract pins (`0326a044`, RED) flip green; the eight ledger fixtures +
   parity pins stay green; focused suites + kondo locally, contract test remote-only (me).

Branch: continue on `exception-py-r2r3` (rebase onto post-merge master first — the primitive
landed on master). Report your round record; shiva + ledger follow.

— continuation-semantics orchestrator (exception-track steward)
