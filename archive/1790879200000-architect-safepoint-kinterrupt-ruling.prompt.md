Created-GMT: 2026-10-01 19:20:00 GMT
Created-Local: 2026-10-02 02:20:00 +07 (+0700)
Coding-Agent: claude
Session-ID: ba6d62ab-caeb-424c-a44e-4637d8333092 (resumed; your safepoint-interpreter design session)

# Task: Architect ruling — safepoint slice 1 implementation decision (KeyboardInterrupt placement)

Role: Lead System Architect

Read-only review of an implementation decision made during slice 1 of your safepoint design (the finding text below
quotes the engineer). The implementation tree is /Users/sto/workspace/datomworld-py-safepoint1 (branch
yang-python-safepoint-s1, uncommitted) — read the touched files as needed: src/cljc/yang/python/antlr/lower.cljc,
src/cljc/yang/python/antlr/safepoint.cljc, src/cljc/yang/safepoint.cljc, the hook-prelude section of
src/cljc/yang/python/antlr/prelude.cljc.

ENGINEER'S DECISION AND CONCERN (verbatim from its report):
"Load order. Because the base prelude is bundled inside A, the hook prelude has to load *before* it. So it may only
allocate cells and a cursor at load time. KeyboardInterrupt therefore can't be a plain class definition. It's a
function py.b/KeyboardInterrupt that creates the class under BaseException on first use, and the lowering reads the
name by calling it. The class definition itself stays out of the naive prelude, but the canonical program now
contains (py.b/KeyboardInterrupt). A naive program that reads that name fails closed instead of raising NameError,
even if the module defines its own KeyboardInterrupt. This should get Architect review."

RULE:
1. Is the lazy-factory placement acceptable, or does the canonical-program contamination (a hook-prelude name
   resolved eagerly in every program, and the changed failure mode for naive name reads) violate your design's
   invariants (identity = canonical tree; naive stays correct; hooks absent from the canonical program)?
2. If unacceptable, rule the correct alternative. Candidates: (a) the hook prelude loads AFTER the base prelude
   (derive the load order from the composition instead of the bundle); (b) KeyboardInterrupt lives in the naive
   prelude as a plain class (cost: two extra rows in every canonical program); (c) keep the factory but scope the
   name so naive programs cannot observe it (namespace discipline / binder hygiene); (d) your own.
3. Whatever you rule, state the invariant statement the doc (yang.antlr.md 8.5.2) should record, and the regression
   tests that pin it.

End with "Ruling:" one paragraph, decisive. Read-only; do not edit files.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700>
