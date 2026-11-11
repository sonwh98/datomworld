Created-GMT: 2026-11-11 18:37:42 GMT
Created-Local: 2026-11-12 01:37:42 +0700 (+0700)
Coding-Agent: glm
Session-ID: (binds to the running r2 session 1a2f7c30's successor; the orchestrator will deliver this via --resume when the r2 turn exits)

# ADDENDUM (BINDING, from the steward — arrived after your r2 brief): the provenance primitive

Steward relay 1794471000000-to-yangphp-adoption-spec-and-dispatch-confirmed.md (copied into this
worktree) adds the VM provenance primitive (master 8c10c5b7, sol-approved) as BINDING for this
round. sol's php-specific ruling, binding:

1. Mint generated retained exceptions through the reserved door cell/dsg__new-provenance
   [value provenance] — atomic fresh id + seal + value + provenance; nil provenance refused
   {:reason :nil-provenance}.
2. Provenance rides the CELL backing the generated Throwable's IDENTITY — not a transient
   holder, not a copied facade. The map-only error fallback CANNOT authenticate retention
   (sol verified at php/antlr/prelude.cljc:299) — that path is REPLACED, not extended.
3. Authenticate via cell/dsg__provenance [candidate] — authenticated live marked cell -> stored
   condition; else nil; never fatal, never mutating.
4. Guest-visible mutation stays legal and cannot touch provenance (heap-write preserves the
   sibling field). Do NOT reimplement a refusal on cell/set!.
5. Helpers that mint wrappers use protected reserved names, outside guest exports/projections/
   aliases (the exported-helper bypass class).

## Sequencing (orchestrator)
- Finish your current r2 turn as briefed (verify 31d2388c, kit-parity pin, local gates).
- The orchestrator will follow with a resume turn carrying this addendum's implementation
  order; master will be rebased under the branch first so the primitive (8c10c5b7) is IN your
  tree. Do not rebase yourself; do not implement the primitive before the resume turn.
