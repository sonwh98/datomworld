Created-GMT: 2026-09-27 22:40:00 GMT
Created-Local: 2026-09-28 05:40:00 +0700
Coding-Agent: codex
Session-ID: pending

# Task: Slice 8 Confirmation, Round 9 — fresh verification of the FFI-migration ruling implementation

Role: Lead System Architect (confirmation gate)

Your FFI-migration-semantics ruling is implemented in the uncommitted
working tree of /Users/sto/workspace/datomworld. Scope of THIS gate:
exactly four files -- src/cljc/yin/vm/ucf/remote.cljc,
test/yin/vm/ucf/remote_test.cljc, src/cljc/yin/vm/semantic.cljc,
test/yin/vm/semantic_test.cljc. (Round 8 reviewed an earlier snapshot;
the tree moved with concurrent slice-5/slice-7 work and landed; the
current counts supersede.) The implementer report (treat as untrusted):
collab/1790530211570-vm-engineer-dao-stream-remote-slice8-fixes-r4.glm-flash.report.md

What was implemented (verify each against the tree):
1. Retained-call resume: the lift serves BOTH endpoints (:yin.k/request
   + :yin.k/response markers, the kept response cell
   :yin.k/response-cell, verbatim envelope); either endpoint unservable
   at lift refuses :yin.k/unsatisfied naming the identity; the lower
   refuses per endpoint at attach and stamps the carried route on the
   lowered writer (:stream-id = the request reflection's allocated
   key, :response-cursor, :response-stream). NO receiver-handler
   fallback for the migrated call.
2. Fresh-key batch allocation: one key per distinct UCF cell id in the
   batch (same cell shares per 7.5.3; equal ids across frames differ),
   stream keys in the same collision-free allocation, checked against
   the receiver's existing :resources, the fixed VM keys, and the
   batch; regenerated on collision.
3. The semantic-restore dispatch: carried-response-route
   (semantic.cljc) reads [:response-cursor :response-stream] off the
   writer and re-keys the restored wait onto the carried route;
   ordinary writers keep the fixed defaults.

Adversarial focus:
1. The ruling's test list -- each pin present and asserting the real
   behavior (the seven pins; the replaced receiver-call-in
   expectation; the refusal paths naming each endpoint).
2. The fixed-key carve-out: ordinary new calls still wait on
   vm/call-out-cursor-key; only lowered retained writers carry route
   data. No ordinary path regressed.
3. The collision checks: batch allocation vs pre-existing resources
   vs fixed VM keys; regeneration on collision.
4. The semantic-restore dispatch's shape: data-driven only when route
   data is present; no regression for ordinary restores.
5. Hygiene on all added/edited lines (ASCII, <= 80 columns).

Do not rerun suites. Orchestrator evidence: the facade namespace 21
tests / 195 assertions green on JVM, Node, and Dart; semantic-test 24 /
153 green; full JVM 2278/183274 with 0 failures on the settled union
tree (the concurrent slice-5/slice-7 work has LANDED and the counts
include it).

Do not edit files. Cite file:line evidence.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly two lines:
Verdict: READY
Sign-off: GRANTED
or
Verdict: REQUEST CHANGES
Sign-off: DENIED
