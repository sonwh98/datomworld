Created-GMT: 2026-10-02 23:30:00 GMT
Created-Local: 2026-10-03 06:30:00 +07 (+0700)
Coding-Agent: claude
Session-ID: ba6d62ab-caeb-424c-a44e-4637d8333092 (resumed; your safepoint-interpreter design session)

# Task: Architect ruling — generator depth vs CPython (safepoint slice 2)

Role: Lead System Architect

Safepoint slice 2 (recursion) is implemented per your design (engineer report:
/Users/sto/workspace/datomworld/collab/1790969285000-compiler-engineer-python-safepoint-s2.claude-opus-5-5
.stdout-r3.log; implementation tree /Users/sto/workspace/datomworld-py-safepoint2, branch
yang-python-safepoint-s2, uncommitted). One semantic divergence needs your ruling, quoted from the engineer:

"Generator depth differs from CPython. A generator keeps the depth of the first frame that resumed it, even when
later resumed from somewhere shallower (the test pins 79/79). CPython counts a resumed generator on top of
whoever resumes it. This follows from the binding rule that escapes restore the whole record saved at capture.
The CPython behavior would need the record to carry a base depth that restores leave alone, which changes that
rule. That is an Architect decision."

RULE:
1. Is the implemented behavior (depth owned by the resuming context at first resume, restored whole at crossings)
   acceptable for the support profile — with the divergence from CPython recorded — or must it match CPython
   (base-depth that restores leave alone)?
2. Whichever you rule: the exact invariant sentence for yang.antlr.md 8.5.2, the regression test that pins it,
   and whether the change affects the thread slice's context-swap design (slice 4).
3. Also confirm the two adjacent engineer choices: RecursionError under RuntimeError in the base prelude; the
   limit cell default 1000 with set-recursion-limit validation (ValueError for <=0, TypeError for non-int).

End with "Ruling:" — decisive. Read-only; do not edit files. Begin the final response exactly with:
Completed-GMT / Completed-Local
