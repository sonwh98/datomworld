Created-GMT: 2026-09-27 16:35:00 GMT
Created-Local: 2026-09-27 23:35:00 +0700
Coding-Agent: codex
Session-ID: pending

# Task: Slice 4 Gate — dao.jing.content (the dao.jing.remote succession)

Role: Lead System Architect (review + sign-off)

Slice 4 of the accepted dao.stream.remote implementation is in the
uncommitted working tree of /Users/sto/workspace/datomworld. History:
the first implementer (a ZCode subagent) died mid-flight on a model
error leaving partial work; glm-5.3 (round 2) audited that work, fixed
seven defects in it, completed the port, migrated every consumer, and
deleted the old module. Implementer report (treat as untrusted):
collab/1790499500000-vm-engineer-dao-stream-remote-slice4-r2.glm-5.3.findings.md

The plan: docs/design/dao.stream.remote.implementation-plan.md section 1
(the dao.jing.content design: vocabulary, serve-step with the one
ingress check, accept-bytes! unification, stepped client, clj driver,
async facade, two-descriptor coordinate) and the slice-4 row (proof:
"dao.jing.remote tests ported and passing over the new module;
dao.space.index, btree hydration, the linker M3 and M4 tests unchanged
in outcome; no require of dao.jing.remote remains").

Adversarial focus:
1. The seven audit fixes: are they all real defects correctly fixed
   (odd-form cond, nil-sentinel ambiguity with the ported cljs
   sentinel, diagnostics-take-once, terminal submit check, UUID-vs-
   issue-order sort, driver nil-vs-absent, coordinate default branch)?
2. The linker's M3 rewrite onto the content vocabulary: :max-bytes
   firing on raw Base64 before decode; ingress reason mapping
   (:base64 -> :absent, hash/non-canonical -> :address-mismatch); lost
   filing and gap semantics; the linker NOT embedding
   dao.jing.content.step (plan section 5: "the linker's stepped core is
   unchanged").
3. The deletion: grep proves no [dao.jing.remote require in src or
   test; the ported tests keep outcomes (ids are UUIDs now -- pinned
   against the minted :id); the deleted-network-test ledger is honest
   (successor behavior lands in slice 5).
4. The two files outside slice-4 ownership still citing the old module
   in comments (boring.cljc:23, lease_composition_test.cljc:556) --
   confirm both are comments only.
5. The coordinate's single-arity open! and the cljs default branch;
   the driver's move to src/clj/ (kondo/cljd rationale).
6. Invariants (P2P no-privilege; dao.stream boundary) and hygiene.

Orchestrator evidence (do not rerun suites): JVM 2,254/183,121/0;
Node 2,163/49,781/0; Dart 2,123 passed — implementer counts
independently reproduced identically. Ledger: net -24 tests from the
deletions/port (baseline 2,272/183,316 at HEAD measured by the
implementer via git-archive).

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
