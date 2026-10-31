Created-GMT: 2026-10-02 19:45:00 GMT
Created-Local: 2026-10-03 02:45:00 +0700
Coding-Agent: codex
Session-ID: pending

# Task: Architect Ruling — Integral Float Literals and Cross-Host Address Divergence

Role: Lead System Architect (owner-proxy; the owner is asleep and
mandated the architect mob on questions that would go to them)

The question (from the agy seat's addendum, docs/orchestrator-log.md):
integral float literals in the Python prelude (e.g. 1.0) hash as CBOR
integers on Node but float64 on JVM/Dart, so a Python program's row
addresses (A, A') differ across hosts when floats are present. Slice
1's cross-host golden test used a float-free tree to dodge this.

This gates any slice that pins float-bearing addresses (C2-S5, C3-S2).
Candidates from the field:
(a) Fix number-wire classification on JS so integral float64 values
    encode as CBOR integers there too (an Architect ruling on the
    number-wire contract — note dao.jing's exact-number semantics:
    integer and float64 are distinct kinds, and 1.0 is a float64 that
    compares equal to the integer 1 but must not re-address as one);
(b) Float-tag row inputs (make the float-ness explicit in the row
    payload so the encoding is unambiguous).

Read first: docs/design/dao.jing.cbor.md (the number-wire contract:
integer vs float64 are distinct kinds; equal-value-different-kind
refusals), src/cljc/dao/jing/cbor.cljc (the JS number classification
path), docs/design/yang.antlr.md (the Python rulings), the agy
addendum in docs/orchestrator-log.md.

Ruling required:
1. Root cause: where exactly does the JS path classify integral
   float64 as integer (file:line), and is that a defect against
   dao.jing.cbor.md's number contract or a conforming reading?
2. The ruling: (a) or (b) or a third mechanism — with the contract
   text it would amend, the cross-host parity consequences, and the
   migration cost (re-minted addresses?).
3. What the C2-S5/C3-S2 slices may proceed with before/without the
   fix (float-free trees? the golden-test approach?).

Read-only; no file edits. Cite file:line evidence.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
