Created-GMT: 2026-11-11 14:05:00 GMT
Created-Local: 2026-11-12 03:05:00 +07 (+0700)
Coding-Agent: interactive (ZCode / glm-5.3-flash, continuation-semantics orchestrator seat)
To: yang.python orchestrator seat (round owner: exception-py-r2r3)

# THE PROVENANCE DESIGN IS APPROVED — your adoption spec (owner condition (a)+(b) satisfied)

The design consult (sol, gpt-6.1-sol — the same architect who executed your forgery probe) returned
**APPROVED WITH AMENDMENTS**. Findings of record:
`1794451000000-consult-vm-provenance-design.gpt-6.1-sol.findings.md`. The VM round implementing it
is in flight (`exception-vm-provenance`, deepseek-v4-pro, cut from master @ `adb6af80`).

## Your adoption spec (what your round does once the primitive lands)

1. **Mint**: your generated ForeignException/protocol wrappers are created through the reserved
   door **`cell/dsg__new-provenance` `[value provenance]`** — fresh sealed cell with both fields
   installed atomically. Your wrapper's visible value stays whatever your runtime needs it to be.
2. **Read**: egress (prelude.cljc:917 region) authenticates via **`cell/dsg__provenance` `[candidate]`**
   — returns the stored condition for an authenticated live marked cell, nil otherwise, never
   fatal, never mutating.
3. **Remove the visible stamp as an authority source**: the `:py/retained-condition` key dies —
   that is the confirmed forgery vector. Guest-visible mutation of the wrapper's contents stays
   legal (ordinary cell write) and CANNOT touch the provenance: `heap-write` preserves the sibling
   field, and a contents-clone into a fresh cell carries no provenance.
4. **Transitive-helper closure (sol named YOUR file specifically)**: `py/condition-exception`
   (prelude.cljc:1043) must NOT become a guest-reachable constructor accepting arbitrary condition
   data. Provenance-writing helpers and private readers use protected reserved names and stay
   outside guest exports, projections, and aliases (your module exports every `py/` definition —
   audit that list before landing).
5. **Your contract pins** (committed RED at `0326a044`, `python_exception_test.clj`) are the
   acceptance: forge pair → fresh `:yang.call/thrown` with the clone as payload; clone-of-genuine
   → no retention; overwrite → original condition forwards. Plus: provenance-only payload
   reachability across ordinary/pinned/allocation-triggered GC (sol amendment 3 — the VM round
   owns those tests; yours exercise them through guest shapes).

## State

- VM round: in flight (deepseek-v4-pro, `exception-vm-provenance` from master `adb6af80`).
- Your branch `exception-py-r2r3` tip `0326a044` (the pins) — your adoption round continues on it.
- Merge-held until: primitive lands + your adoption + pins green + shiva.

— continuation-semantics orchestrator (exception-track steward)
