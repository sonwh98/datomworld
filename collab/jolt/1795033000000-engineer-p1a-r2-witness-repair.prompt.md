Created-GMT: 2026-11-18 17:03:19 GMT
Created-Local: 2026-11-19 00:03:19 ICT (+07)
Coding-Agent: glm
Session-ID: zcode-subagent-p1a-r2
# Task: P1a-r2 — repair the P1a witnesses per the codex review (F1-F3, F5)

Role: VM Runtime Engineer
Implementers:
- Model: glm-5.3-flash | Assigned: 2026-11-19 00:3x:xx +07 | Status: active | Rationale: continuation of the same lane that authored P1a; reviewer-confirmed defects

## Context

P1a (commit dc6176dc on gambit-ffi-p1) was reviewed by codex gpt-6.1-sol
(findings: /Users/sto/workspace/datomworld/collab/jolt/1795030000000-reviewer-p1a-audit-codex.gpt-6.1-sol.findings.md).
All findings are ACCEPTED. Your job is to repair the witnesses and AUDIT.md so the
reviewer can confirm. READ the review findings first — they contain exact file:line
evidence for every defect.

Work dir: ~/workspace/jolt-ffi-p1 (branch gambit-ffi-p1). Commit only p1a/ changes.
Never push. The reviewer's rerun artifacts live in p1a/witnesses/build/results/ —
do not delete them.

## Repairs (each maps to a review finding)

F1 (W3): the c-define exports declare (int) but the Scheme procedures require
(x y) — fix to (int int) on BOTH w3-server.scm export sites, re-generate with
gsc -keep-temp and VERIFY the generated C shows ___NARGS 2 before running; add a
passing owner-thread control (a Gambit-thread callback through the same c-define)
that must succeed BEFORE any foreign-thread phase runs; then serialized and
concurrent foreign-entry phases. Keep explicit nondeterminism caveats (exit 70 vs
139 both mean unsafe entry — treat either as the documented refusal-failure, never
as success).

F2 (W1): every shim must execute the exact astra sequence — target call, then
`int saved = errno;` IMMEDIATELY, before ANY malloc/conversion/cleanup; Shapes A/C
must preserve the target's real errno, not manufacture it after the fact; add an
intervening-clobber negative control (a deliberate errno write between target and
capture that the test EXPECTS to defeat — demonstrating capture order is load-bearing).

F3 (W2): start and synchronize BOTH participants before the foreign call blocks
(B must be provably executing on the runtime before A enters C); the C release
handshake must actually be called from Scheme, and only after B completes a REAL
collection (##gc) that mutates live rooted objects; verdict derived from observed
state (completion flags + GC witness), never unconditional prints; independent
timeout detection (watchdog exit 124 = recorded failure). Keep the current build
as a documented "starvation demonstration" variant if useful, but the
qualification witness is the new one.

Makefile/hygiene: all build artifacts under p1a/witnesses/build/; never overwrite
tracked results/*.out (record new runs as results/*.out only after a full PASS
run; reviewer comparison copies live in build/results/). Every verdict assertive:
wrong values, absent handshakes, timeouts, unexpected exits are FAILURES.

F5 (AUDIT.md): apply every citation correction in the review's F5 list (manual
19305-19307, 19328-19332; c_intf.c:6530; setup.c:1169-1170 and :1196; the Threads
chapter DOES discuss OS threads/processors — fix AUDIT.md:47-49); withdraw the
categorical all-configuration claims (F4) — restate them as configuration-scoped
with the Q3 barrier analysis cited; amend the verdict section to the reviewer's
amended verdict wording (NO-GO for P2/P3 on the provisioned build confirmed;
foreign-entry-impossible claim withdrawn; PARTIAL-GO per-capability).

## Deliverables

1. Commits on gambit-ffi-p1 (clear messages, e.g. "p1a-r2: repair W1/W2/W3 per
   codex review F1-F3").
2. Fresh full-run recorded outputs for every repaired witness.
3. Findings file:
   /Users/sto/workspace/datomworld/collab/jolt/1795033000000-engineer-p1a-r2-witness-repair.glm-5.3-flash.findings.md
   (standard header; per-finding "what changed" mapping to F1/F2/F3/F5; verbatim
   new results; ends DONE).

Rules: foreground commands with timeouts; watchdog everything; do not modify
~/workspace/gambit-v4.9.8-src or the multithreaded lane's worktree
(~/workspace/jolt-ffi-mt — another agent owns it). Final response starts with the
Completed-* header lines and lists each review finding with fixed/not-fixed.
