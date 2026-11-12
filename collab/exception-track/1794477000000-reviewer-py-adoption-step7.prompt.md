Created-GMT: 2026-11-11 20:10:00 GMT
Created-Local: 2026-11-12 03:10:00 +07 (+0700)
Coding-Agent: glm
Session-ID: b2260297-b1a3-4d07-87c5-1f6f8f3208c4 (laptop; resume on laptop per pool routing)
# Task: step-7 implementation review — the python provenance adoption (00ca2d1c + 428bbdd7)
Role: Reviewer (independent of the author chain: design consult sol, VM primitive deepseek,
adoption wiring deepseek — you are glm, independent of all three)
Implementers:
- Model: glm-5.3 | Assigned: 2026-11-12 03:10 +0700 | Status: active | Rationale: deep arc context (authored the (A) two-class ruling, verified the primitive's macro closure), independent of every author of this diff.

## The work under review

Branch `exception-py-r2r3` @ **`428bbdd7`** (pushed). The adoption diff:
`git diff a396ae28..428bb8dd` — NOTE: use the correct SHA `428bbdd7`. This spans the rebased
R2/R3+egress reception work (d9a73150/24f2f467/a396ae28 lineage, rebased) plus the provenance
adoption (`00ca2d1c`: mint/read wired onto `cell/dsg__new-provenance`/`cell/dsg__provenance`;
`:py/retained-condition` stamp removed as an authority source) and scanner-pin regeneration
(`428bbdd7`).

The acceptance measurement is DONE: shiva `xtrack-py3` at this exact SHA — all 13 python
fixture rows green (including the six protocol rows and foreign-reception/retained-foreign-
lifetime), forgery pins green, python exception-call 0F/0E (720s). Your job is NOT to re-run —
it is to find what the green tests MISS. This arc's pattern: three consecutive rounds' green
suites missed what independent review caught.

## Review questions

1. **Provenance wiring correctness**: does the wrapper mint go through
   `cell/dsg__new-provenance` with the COMPLETE condition, and does egress authenticate via
   `cell/dsg__provenance` before any forwarding decision? Any path that still consults the
   removed `:py/retained-condition` stamp, or forwards without authentication?
2. **The R2 lifetime rules** (ruling v2 §R2): identity-owned retention, outside source-writable
   attributes, no ambient registry, wrapper non-stacking, formatting never invokes foreign
   methods. Check the adoption against each.
3. **Export/alias audit** (the sol-named risk): does any python prelude definition outside the
   protected reserved names expose mint/read capability to guest source? Audit the module
   export/projection/alias list (your predecessors found an exported-helper bypass class on the
   js side — check the python twin).
4. **Test weakening**: across the full a396ae28..428bbdd7 span — was any existing assertion
   weakened or deleted? The scanner-pin regeneration (428bbdd7) claims the `:py` deferred set is
   the single mover with three-run byte-identity — verify the abort-gate mechanics hold.
5. **Tail/lane interactions**: the adoption rewires egress authentication — does anything change
   the tail structure of published entries (the `:tail?` marks the constant-depth analysis
   depends on)?

## Deliverable

Findings with severity + file:line; per-question verdicts; final line: **APPROVE** /
**APPROVE WITH FINDINGS** / **REQUEST CHANGES**. Read-only; no suites (shiva already measured at
this SHA — xtrack-py3). Do NOT run yang.call.exception-contract-test. Begin with
Completed-GMT/Completed-Local.
