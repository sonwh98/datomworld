Created-GMT: 2026-09-27 19:30:00 GMT
Created-Local: 2026-09-28 02:30:00 +0700
Coding-Agent: codex
Session-ID: pending

# Task: Slice 5 Gate — REPL service + the copy-path retirement (first full gate)

Role: Lead System Architect (review + sign-off)

Slice 5 of the accepted dao.stream.remote implementation is in the
uncommitted working tree of /Users/sto/workspace/datomworld. History:
the rewrite ran long (r6-r11: apply-envelope BLOCKED -> architect
ruling A -> the rewrite + lease-sketch migration -> shutdown-grace fix
-> node-test migration), with a mid-flight session death recovered by
session resume. This is the slice's FIRST full gate.

The contract: docs/design/dao.stream.remote.implementation-plan.md
slice-5 row + section 5 fate list; the ruling artifacts
(collab/1790505601000-architect-apply-envelope-ruling.gpt-6-sol.findings.md,
collab/1790505600000-architect-lease-sketch-ruling.gpt-6-sol.findings.md).
Implementer trail: collab/1790497441896-vm-engineer-dao-stream-remote-
slice5.{r6,r7,r8,r9,r10,r11}.claude.stdout.log + the node-test
migration report
(collab/1790532500000-vm-engineer-node-ws-test-migration.glm-flash.report.md).
Treat ALL as untrusted; the tree is the truth.

What the slice does: yin.repl.serve/connect/adapter reworked onto
ws-project + dao.stream.remote reflections (two identities:
yin.repl/requests, yin.repl/answers); dao.stream.serving,
dao.stream.rpc.ws, and the apply wire envelope DELETED;
dao.stream.rpc.cljc reworked (own request/answer vocabulary, random
self-minted ids, reflection-failure translation to the driver's
terminal vocabulary); dao.stream.apply.cljc KEPT as the VM's local FFI
bridge only (ruling A); the deferred ws retirements (accept frame,
disclaim, served-path table); the lease_composition sketch migrated to
the lease-governed mirror-table reclaim; the node ws tests migrated to
the new wire flow; bb.edn/shadow-cljs.edn dead slice-peer references
removed; the serve shutdown moved from tick-count to wall-clock grace.

Adversarial focus:
1. The deletions: no live consumer of dao.stream.serving/rpc.ws/the
   apply wire envelope remains (grep; check each former consumer).
2. The rpc.cljc translation: reflection failures -> the driver's
   terminal vocabulary, correlated by the self-minted random ids;
   collision retry; the reattach/rebind state intact.
3. serve/connect composition: two identities over ws-project +
   reflections; the eval/incomplete-input decisions preserved; the
   shared-queue collapse (vs the old per-attachment pool).
4. The shutdown wall-clock grace (stop-grace-ms 500): correct? bounded?
   clock-sourced from the caller's step (the shell stays clock-free)?
5. The lease_composition migration: the reclaim property preserved
   (judge ticks, lapsed on the grantor writer, gone via not-found).
6. The node_test migration: asserts the REAL 5-event sequence; r10's
   blind edits reverted or redone honestly.
7. Invariants: P2P no-privilege; dao.stream boundary; no shims.
8. Hygiene on all touched lines.

Orchestrator evidence (do not rerun suites; union tree with all three
slices present): JVM 2,266/183,166/0; Node 2,173/49,777/0; Dart 2,134
passed. Note: bb.edn's test:cljd peer-build chain was fixed by
removing the dead slice-peer task; the yin-repl-peer binary is
gitignored and rebuilt by the task.

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
