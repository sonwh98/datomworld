Created-GMT: 2026-10-10 03:05:00 GMT
Created-Local: 2026-10-10 10:05:00 Asia/Bangkok

# Task: Round 4 -- terminal expressions made consistent across projection, validation and lowering

Role: Lead System Architect (continuing thread 01a121d7-5ba4-71b1-a70b-f6b4cf3d056a)

All three round-3 findings and the §4.3 editorial point taken. New HEAD
is one commit on top of `4a049ea8`. Run `git diff HEAD~1 HEAD` and
re-read §3.1 (the paragraph after the opcode list and the Outcomes
paragraph), §3.3 items 2-3, §3.4 item 7, §4.3, §8.2 intervals /
definition-less ids / count.

The one model, stated in §3.1 and used everywhere after: three
distinct notions -- MINTED id (every expression mints exactly one,
resume included; §3.3 corrected), SYNTACTIC definition (instructions
whose rd slot names the id; a tail :call has one, a :resume gives its
id none), RUNTIME write (a syntactic definition reachable on some
path). Terminal = no normal result on that path, about runtime writes
only. Outcome composition per production, conditional on children
continuing; terminal test; mixed arms. Rule 7 restated over the three
notions. §8.2: intervals exist for ids with >= 1 syntactic definition
(layout-syntax definitions included so emitted operands are in
bounds); definition-less ids lower to a structural slot T_k (k = assigned
temporaries), count becomes k+1, the slot is never written/read/saved/
live. §4.3 adds segment and pc / site to the obligation.

Check, read-only, as the freeze gate: A. do minting, syntactic
definition, runtime write, outcome composition, rule 7/8/10/12 and
§8.2 now agree with one another on every case (tail call rd; resume
rd; enclosing call after a terminal operand; wholly terminal body;
mixed arms; terminal test)? B. is the structural slot rule sound and
deterministic, and does it stay out of saved windows and live
operands? C. anything else blocking READY_TO_FREEZE.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>

Then anything remaining as severity | file:line | evidence |
correction, and end with one line:
VERDICT: READY_TO_FREEZE | CHANGES_REQUESTED.
