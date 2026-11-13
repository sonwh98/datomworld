Created-GMT: 2026-11-12 22:45:00 GMT
Created-Local: 2026-11-13 05:45:00 +07 (+0700)
Coding-Agent: codex
Session-ID: 8e8dc7d4-2590-4054-8a9b-934f1feec672
# Task: architect consult — design a SAFE protocol-condition carrier for clojure's outbound rethrow
Role: Architect (design consult; you framed the closure-carrier remedy in the consult — now face
its implementation finding; you are independent of both implementer families)
Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-11-13 05:45 +0700 | Status: active | Rationale: the (B)-carrier
  expectation came from your consult (amendment 5); the implementer proved the naive closure form
  unsafe; resolving it is your line.

## The finding you must resolve

Phase 5 (terra, `f15423c2` report) attempted the closure carrier for R3's outbound half and
found: **guest code cannot inspect a kernel closure's captured environment through available
primitives, and applying an arbitrary raised closure to authenticate it could run guest code and
expose the compiler's private token.** Authentication requires recovering the condition from the
carrier WITHOUT applying it and WITHOUT registry/VM support. The 18 ledger rows
`:clojure/protocol-*` / `:clojure/saved-protocol-*` cannot be closed until this resolves.

## The constraints (all binding)

1. **No VM change** (the declined `:retained` seam; `src/cljc/yin/vm/` stays empty-diff for this
   frontend's work — the approved `:provenance` primitive EXISTS on master via `8c10c5b7` and is
   NOT forbidden — it's shared infrastructure; whether clojure should use it is YOUR call).
2. **§R2 lifetime**: identity-owned, outside source-writable attributes, no ambient registry.
3. **R5**: shape is never evidence; nothing routes on condition-map shape.
4. **No application of arbitrary guest values** in the escape path (the Phase 5 finding).
5. **Saved-rethrow**: the `saved-protocol-*` fixtures require the carrier to travel through
   `cell/set!` saves and later activations — lexical/handler-frame memory does NOT qualify.
6. Constant-depth and tail-lane properties preserved.

## Questions

1. Is the `:provenance` primitive (already on master) the RIGHT carrier mechanism for clojure
   protocol conditions — i.e. mint `[visible, condition]` via `cell/dsg__new-provenance` and
   authenticate via `cell/dsg__provenance` — noting it stores the condition in a HOST-ONLY heap
   field (guest-invisible, guest-immutable-after-write... verify what set! does to provenance —
   the python precedent shows writes preserve it). Does this close the 18 rows within constraints
   1-6? Is it consistent with the owner's NO (which declined the `:retained` MARK for the
   OUTCOME channel; the provenance field is approved shared infrastructure for the continuation
   encoding all four frontends now use — js/python already answer R3 this way)?
2. If NOT, design the carrier that satisfies all six. If NOTHING does, state which constraint
   the owner must relax — precisely, with the trade-off framed for their decision (this goes to
   the owner as-is if you conclude that).
3. Either way: give the implementer the exact shape (mint site, authentication site, recovery
   path, saved-case behavior) and the ledger-claim language for the 18 rows.

## Deliverable

Ruling with file:line evidence (read the primitive's python adoption as the worked example:
`00ca2d1c` on exception-py-r2r3; the kit's saved-protocol fixtures; the Phase 5 finding above).
End with one line: **CARRIER RESOLVED VIA PROVENANCE** / **CARRIER DESIGN: <shape>** /
**CONSTRAINT CONFLICT — OWNER DECISION REQUIRED: <framed question>**.
Read-only. Begin with Completed-GMT/Completed-Local.
