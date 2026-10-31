Created-GMT: 2026-10-16 19:00:00 GMT
Created-Local: 2026-10-17 02:00:00 +07 (+0700)
Coding-Agent: claude-cloud
Session-ID: 93cda4c1-ec00-4fd9-8fe6-1d63c63eac89 (resumed; round 8)

# Task: Revise both designs — round 8 (ORACLE DATA ATTACHED: PHP 8.3 ground truth for the round-7 findings)

Role: Lead Architect

The codex round-7 review: /Users/sto/workspace/datomworld/collab/1792224000000-reviewer-ts-php-r7.gpt-6.1-sol.findings.md
— read whole (4 findings: iterator-copy tracking row 54, row 53 constructor rejection, row 57 named-
argument error timing, readonly reinitialization-vs-unset). The reviewer noted PHP CLI fixtures were not
run. THE ORACLE GAP IS NOW CLOSED — first-hand PHP 8.3 ground truth (php:8.3-cli docker, run by the
orchestrator on shiva):
- F1 (iterator copy): $a=[10,20,30]; by-ref foreach echoing $v; after the first iteration $b=$a and
  $a[2]=99 — OUTPUT: "10,20,99," — the by-ref foreach keeps the original table live through the
  reference; the plain copy $b=$a snapshotted (COW) at copy time; the in-place write is visible to the
  ongoing iteration.
- F2 (named-argument error timing): f(bad: 1, x: side()) where side() echoes "late" — OUTPUT: fatal
  "Unknown named parameter $bad" IMMEDIATELY, "late" NEVER printed — the unknown-name check precedes
  argument side effects entirely.
- F3 (readonly reinit): __clone doing unset($this->x); $this->x = 2 — OUTPUT: "2", NO ERROR — unset
  followed by reinitialization in __clone is legal and yields 2.

FIX THE FOUR FINDINGS with these oracle facts as the ground truth (derive the row semantics from them,
cite them as fixtures), same zend-derived discipline for the rest. Note what round 8's reviewer should
probe. Keep everything previously confirmed closed.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700>
