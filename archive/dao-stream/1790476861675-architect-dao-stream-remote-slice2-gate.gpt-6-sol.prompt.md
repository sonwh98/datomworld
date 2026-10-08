Created-GMT: 2026-09-27 10:00:00 GMT
Created-Local: 2026-09-27 17:00:00 +0700
Coding-Agent: codex
Session-ID: pending

# Task: Slice 2 Gate — dao.stream.remote core (mirror, link, reflection)

Role: Lead System Architect (review + sign-off)

Slice 2 of the accepted dao.stream.remote implementation is in the
uncommitted working tree of /Users/sto/workspace/datomworld, implemented
by a GLM-5.3-Flash subagent. This is the slice that makes the protocol
real: mirror-step, link, reflection, remote descriptor dispatch, the
protocol errors.

New files (2): src/cljc/dao/stream/remote.cljc (767 lines) and
test/dao/stream/remote_test.cljc (946 lines, 18 deftests). Implementer
report (treat as untrusted):
collab/1790471994616-vm-engineer-dao-stream-remote-slice2.glm-flash.report.md
— it documents TEN minimal-reading ambiguities (channel context derived
from the chan-reader's descriptor identity; budget minted by the link
as composition data; oversize replaces one channel value; channel-reader
gap adopts the reader's recovery cursor; filing scope per
(reflection, op, arg) with :more as link-level positional truth; probe
resend counts one per drain; append! answers emitted on the event
writer never filed; precedence close > gone > learned-no-surface >
channel-gone with filed lookups first; verbatim return strips
correlation keys; the reflection implements all four protocols with the
learned surface reproducing the translated no-surface answer). Judge
each: acceptable minimal reading or contract deviation.

Adversarial focus:
1. Spec fidelity to 2.1-2.5: the mirror's four-step order (table ->
   no-surface -> descriptor-direct + apply-request-with-channel-ctx ->
   answer), the mirror never touching a handle outside apply-request
   and never constructing/parsing/rewriting cursors, the budget chase
   ending at and including the first non-ok, oversize placement.
2. The reflection's answer rules per operation (descriptor local;
   cursor retry-read; next filed-forgotten-then-blocked; append!
   writer's outcome; close! local semantics), drain filing, protocol-
   error translation, gone semantics (descriptor still ok), refused
   verbatim, declared nature + :closable learning, excluded outcomes.
3. Channel loss: abandonment, append-unknown reporting, channel-gone,
   filed-answers-still-returned.
4. The ten ambiguity readings for contract fidelity and for defects at
   their edges (shared :more vs per-refusal filing; precedence
   ordering; probe resend).
5. Invariant compliance (P2P no-privilege: is there any server/client
   role asymmetry hiding in the implementation?) and hygiene.

Orchestrator evidence (do not rerun suites): JVM 2,252/183,193/0;
Node 2,164/49,799/0; Dart 2,126 passed — implementer counts
independently reproduced identically by the orchestrator.

Do not edit files. Cite file:line evidence.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report findings as:
P0-P3 | file:line | evidence | concrete fix

End with exactly two lines:
Verdict: READY
Sign-off: GRANTED
or
Verdict: REQUEST CHANGES
Sign-off: DENIED
