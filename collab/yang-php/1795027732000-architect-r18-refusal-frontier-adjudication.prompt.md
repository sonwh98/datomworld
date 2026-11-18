Created-GMT: 2026-11-19 (dispatch time)
Coding-Agent: codex (gpt-6-astra, jupiter POOL 2, read-only)
Session-ID: 01a1effe-d75d-7b80-8495-9e6a5e6d836a (codex astra thread, jupiter)

# Task: two adjudications — the r18 refusal-representation contract question + the frontier diagnosis dispute

Role: Architect (read-only consult).

## Question 1: r18's structured refusal payload (yang.php eval-bridge)
evalb-r18 (branch feat/evalb-r17 in ~/ci/dw-evalb, gate 188/5066/0 GREEN, findings
collab/yang-php/1795017060060-engineer-evalb-r18.gpt-6.1-terra.findings.md) implements the
stream-service eval path: lower-eval -> php/eval-run (prelude.cljc, beside php/throw-error) ->
the harness compiler effect appends a sealed correlated request -> eval-coordinator/serve ->
publish for linker admission. Parser syntax refusals become guest ParseError; OTHER compiler
refusals use the EXISTING operational terminal escape with the complete compiler answer as its
:php/fatal payload — but that terminal normally carries a TEXTUAL fatal message.
The round flags: "this use of a structured value needs owner review against the
operational-refusal outcome contract."
Rule with:
1. May an operational-refusal terminal's :php/fatal payload carry a structured compiler answer
   (e.g. the :yang.compile/* map) where guest fatals carry text? Check the operational-refusal
   outcome contract in the design docs (the §8.10.7 boundary, the §8.1 refusal categories, and
   any prior ruling on terminal payload shapes). Is the distinction (guest-fatal = text,
   operational-refusal = structured) contract-consistent, or does it break consumers that
   pattern-match :php/fatal as a string?
2. If structured is acceptable: authorize as-is with the exact guard consumers need (or name
   the discriminator field). If not: name the correct shape (a sibling field? an envelope?
   re-map to text?) so the engineer round is mechanical.
3. Confirm the rest of the round is landable regardless: the service path, the collision
   matrix (JVM semantic path, 3+1 fixtures asserting the landed class-fatal channels), the
   disclosure that the matrix is not on every VM this round.

## Question 2: the frontier diagnosis dispute (python→php finally-replacement)
The dispatch (my brief, collab/exception-track/1795027072000-...frontier...prompt.md lineage)
inherited the steward's spec diagnosis: the identity loss is a provenance-carrier mint/retention
defect (spec 1794588002000r2). frontier-r1 (thread 01a1efed, ~/ci/dw-frontier, findings
collab/exception-track/1795027072000-engineer-frontier-python-php-finally-r1.gpt-6.1-terra.findings.md)
read the source and DISPUTES it: "the finally leg throws a PHP-created replacement, and PHP
releases that global before the observation. The existing carrier already stores the incoming
condition on a provenance-bearing PHP cell." The round proposes a fixture repair + runtime
seeding preserving the identity assertion (no mint/retention changes, no production edits).
Rule with:
1. Whose diagnosis does the source support? Read the finally-replacement leg in
   test/yang/call/exception_legs.clj (the round's fixture-repair edit is in the worktree) and
   the provenance carrier paths in prelude.cljc. Is the observed payload-identity loss explained
   by a global-release sequencing artifact (fixture-level) or by carrier mint/retention
   (engine-level)?
2. If the round is right: authorize the fixture repair + runtime seeding as the frontier fix
   (the acceptance row [:python :php :finally-replacement] x 3 backends green remains the bar;
   confirm the seeding does not weaken R5 shape-never-evidence or R2 rule 3). If the spec is
   right: name the carrier defect precisely so a corrected engineer round is mechanical.
3. Either way: this adjudication ends the dispute for the unit (per the coordination contract)
   — the round proceeds on your authority without re-litigating.

Evidence: ~/ci/dw-evalb and ~/ci/dw-frontier worktrees on jupiter (read-only for you); master
1f4f1e80; the two findings files; the design docs in docs/design/ (the §8.10.7 boundary,
malleability, the yin.vm register/continuation docs as needed).
Deliverable: inline (read-only session; the seat files both rulings).
