Created-GMT: 2026-11-12 00:04:31 GMT
Created-Local: 2026-11-12 07:04:31 +0700 (+0700)
Coding-Agent: glm
Session-ID: 6e2967a3-3d6a-4604-b3f0-2a61a74bb22f (RESUME of the r2 session)

# Task: PHP R1+R2+R3 r3 — the ADOPTION turn (the provenance primitive)

Role: Engineer (implementation, yang.php) — resuming your own r2 session.

## What changed since your r2 turn
1. Your branch was REBASED onto current master (your commits are now 1757aae7 + e4f271ff; the
   tree carries the VM provenance primitive 8c10c5b7 and the math merge). Post-rebase
   verification: builtin-surfaces 4/225 green; the ONLY reds in yang.call.php-exception-test
   are your own disclosed §3a lane-blocked rows (18 = the fresh-egress/kit-batch
   :payload-identical set) — nothing new from the rebase.
2. The steward's ADOPTION SPEC is BINDING on this round (read
   collab/exception-track/1794471000000-to-yangphp-adoption-spec-and-dispatch-confirmed.md in
   THIS worktree, and the addendum 1794402747000-engineer-php-r1r2r3-r3-adoption-addendum.prompt.md
   next to it).

## The r3 work
Adopt the primitive per sol's five points (in the addendum): mint generated retained exceptions
through cell/dsg__new-provenance [value provenance] (atomic; nil provenance refused); provenance
rides the CELL backing the generated Throwable's IDENTITY — replacing the map-only fallback
(sol verified prelude.cljc:299 cannot authenticate retention; your receive-condition path is the
place); authenticate reads via cell/dsg__provenance [candidate] (never fatal, never mutating);
guest-visible mutation stays legal; minting helpers use protected reserved names, outside guest
exports. Reconcile with your R2 retention work — the identity store you built over the
lexically captured cell is the thing being upgraded, not replaced by a different mechanism.

## Gates (same rules as r2)
TDD; focused namespaces + kondo + cljstyle locally; the contract test NEVER runs locally; the
kit-parity pin (php-kit-parity-test) must stay green; the §3a lane-blocked rows stay as disclosed
(not yours to fix — note any change in their signature).

## Deliverables
Commit(s) on exception-php-r1r2r3 (do NOT push — the orchestrator pushes after review coordination
this time); findings to
collab/exception-track/1794402747000-engineer-php-r1r2r3-r3-adoption.glm-5.3.findings.md.
FOREGROUND commands with explicit timeouts; no backgrounding.
