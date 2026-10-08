Created-GMT: 2026-10-06 22:50:00 GMT
Created-Local: 2026-10-07 05:50:00 +0700
Coding-Agent: codex (gpt-6-astra, resumed thread 01a0f878-281b-7253-ac44-ff2402583d35)
Session-ID: 01a0f878-281b-7253-ac44-ff2402583d35

# Task: rule on D10's lower-side inputs before implementation (read-only; the ruling is the deliverable)
Role: Architect

You signed off D8 and D9. The next slice, D10 (the version-1 lower),
has one open design question the engineer would hit and stop on —
rule it now so the round runs straight through.

D10 lowers a validated version-1 body into a receiver machine. The r3
plan (collab/1791194000000-architect-m-next-d-plan-r3.claude-fable-5-1
.findings.md, sections 1.1, 1.6, 1.7, 1.11 and the D10 test contract)
says lower installs "the custody map" only from a grant with valid
binding evidence, checks protection in both directions against the
composition's declaration, restores the counter exactly, keeps
carried ids, and does not let receiver-local store bindings change
resolution.

The question: what are lower's exact inputs and who supplies them?
Concretely:

1. `:yin.k/custody` on the root machine value — r3 1.1 names its
   fields (occurrence, lease, epoch, arbitration identity,
   next-op-seq, input state, protection classes, mode) but the epoch,
   lease and input state come from a GRANT, and lower runs before any
   grant in the reader's pipeline. Rule the split: what lower
   installs from the body/header alone (counter, protection
   expectations, the grantless parts), what is installed on grant
   acceptance (D13's), and how the machine represents "lowered but
   not yet granted" (can a lowered body run at all before the grant?
   r3 1.9's candidate step 5 says lower runs after grant acceptance —
   confirm the ordering contract and what D10 actually builds).
2. The protection declaration's input shape at lower (the
   composition's per-stream-identity classes) and the exact
   both-directions mismatch rule.
3. The restoration regression harness D10 must carry (D9's restored!
   pattern: resume-task into a fresh receiver with attachment
   support).

Reply with the machine-facing contract for lower's inputs and
outputs, what D10's test rows pin, and what stays D13's. Read-only:
edit nothing, run no suite.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
