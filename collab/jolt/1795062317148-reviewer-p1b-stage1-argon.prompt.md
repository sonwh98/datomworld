Created-GMT: 2026-11-19 04:25:17 GMT
Created-Local: 2026-11-19 11:25:17 ICT (+07)
Coding-Agent: agy
Session-ID: pending (capture conversation_id)
# Task: review P1b-stage1 — the Gambit basic FFI tier (b717f57d + 8f9047aa)

Role: Review (adversarial flavor; reviewer family independent of the codex author)
Implementers:
- Model: gemini-4-argon-high | Assigned: 2026-11-19 11:25:17 +07 | Status: active | Rationale: author was codex gpt-6.1-sol; reviewer independence requires a different family; argon is the design-review strength on the flat lane

## Review inputs

- Author findings: /Users/sto/workspace/jolt-ffi-p1b/p1b-stage1/1795042000000-engineer-p1b-stage1-codex-gpt-6.1-sol.findings.md (+ evidence/ dir)
- The diff: branch gambit-ffi-p1b, worktree /Users/sto/workspace/jolt-ffi-p1b, commits b717f57d (implementation + gates) and 8f9047aa (evidence) on base a6852ef8.
- Governing contract (check the implementation AGAINST these, not against the author's own claims):
  /Users/sto/workspace/datomworld/collab/jolt/1794976827310-architect-gambit-ffi-plan.gpt-6-astra.findings.md
  /Users/sto/workspace/datomworld/collab/jolt/1795040000000-reviewer-p1a-confirm-codex.gpt-6.1-sol.findings.md (C4 must-not-assert list is binding)
  /Users/sto/workspace/jolt-ffi-inv/p1b-inv/ (inventory: 44 shapes, Q1-Q6)
- Gambit source (read-only): ~/workspace/gambit-v4.9.8-src; provisioned binaries
  /Users/sto/.local/share/mise/installs/gambit/4.9.8/bin/{gsi,gsc} (never bare).

## Review priorities

P1 Contracts before claims: does every qualified row have a state-derived,
watchdogged, target-identified gate — and do the MUST-NOT-ASSERT rows
(GC-safe blocking, generic foreign-pthread callbacks incl. serialized mode,
dynamic closure callables per F6, Linux, AOT/FASL/subprocess, P2/P3 parity)
remain honest message-carrying refusals? Any gate whose verdict is printed
unconditionally or derived from the author's own expectations is a finding.
P2 The errno sequence: capture immediately after the target call, BEFORE any
allocation/cleanup; invocation-local storage (no global/TLS last-errno cell);
preserve the target's errno on success AND failure; nested calls. The author
rebuilt this after the prior agent's partial work — audit the final C shims
line by line.
P3 Shim identity: the author added the variadic boundary to shim identity
(finding from their round: socket(int,int,int) selecting the fcntl(int,int,...)
shim). Verify the manifest-generated wrappers cannot mismatch an ABI shape
(arg types, result, convention, variadic boundary, native-error variant).
P4 Refusal-guard gaps from inventory Q1-Q6: :ffi-layout and named-binding
scoped emission must raise message-carrying errors (not unbound-variable);
worker_free blocking mark (Q3) deferred — confirm it is recorded, not silently
dropped.
P5 Seeds/provenance: the author changed the emitter and reminted seeds
(remined convergence claimed). Verify seed determinism: regenerate and compare
digests; confirm nothing in host/chez changed behavior (their Chez errno suite
shows 2 pre-existing socket failures reproduced on untouched base seeds —
verify that reproduction claim).
P6 Scope discipline: nothing outside the declared files; bridge inputs pinned
with digests; no ambient artifacts.

You are READ-ONLY: run gates read-only-ish where they write only under build/
dirs (the Makefile targets under the worktree), never modify sources, never
commit or push. Deliverable: findings returned as your final agent message
(read-only sandbox) beginning with Completed-GMT/Local/Coding-Agent/Session-ID,
each finding P0-P3 with file:line evidence, explicit statement of which gates
you re-ran and their outcomes, and a closing RECOMMENDATION line:
ACCEPT / ACCEPT-WITH-FIXES (list) / REJECT (reasons). The orchestrator promotes
it to /Users/sto/workspace/datomworld/collab/jolt/1795062317148-reviewer-p1b-stage1-argon.gemini-4-argon.findings.md
