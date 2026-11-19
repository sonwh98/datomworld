Created-GMT: 2026-11-19 04:03:03 GMT
Created-Local: 2026-11-19 11:03:03 ICT (+07)
Session-ID: pending (provider-generated; capture thread.started.thread_id)
# Task: P1b-stage1 continuation — resume the half-built Gambit FFI tier by state

Role: VM Runtime Engineer (implementation)
Implementers:
- Model: gpt-6.1-sol | Assigned: see Created-Local | Status: active | Rationale: glm lane exhausted quota mid-round; owner standing rule reroutes stuck lanes to codex/agy

## Situation

A prior agent began P1b-stage1 (implement jolt's basic FFI tier on the Gambit
target) and died at a quota limit mid-round. Its partial work is UNCOMMITTED in
the worktree /Users/sto/workspace/jolt-ffi-p1b (branch gambit-ffi-p1b, base
a6852ef8): modified Makefile, host/gambit/{boot.ss,scheme-adapter-runtime.ss,
seed/image.ss}, host/chez/seed/image.ss, jolt-core/jolt/backend_scheme/{ffi.clj,
session.clj}; NEW untracked directory host/gambit/ffi/. Its last stated position:
"the capture works — the preserve-errno rows need a deterministic pre-set errno".
Resume BY STATE: read the partial diff (git diff + host/gambit/ffi/), judge each
piece against the governing rulings, finish or discard it, and complete the
brief's deliverables. Nothing is committed yet — you own staging and commit
messages from here.

## Governing rulings (READ FIRST, binding)

1. Your task brief (the full P1b-stage1 contract: scope, gate family, must-not-
   assert list, deliverables, rules):
   /Users/sto/workspace/datomworld/collab/jolt/1795042000000-engineer-p1b-stage1.prompt.md
2. Astra design: /Users/sto/workspace/datomworld/collab/jolt/1794976827310-architect-gambit-ffi-plan.gpt-6-astra.findings.md
3. Codex review: /Users/sto/workspace/datomworld/collab/jolt/1795030000000-reviewer-p1a-audit-codex.gpt-6.1-sol.findings.md
4. Codex confirmation: /Users/sto/workspace/datomworld/collab/jolt/1795040000000-reviewer-p1a-confirm-codex.gpt-6.1-sol.findings.md
5. Inventory: /Users/sto/workspace/jolt-ffi-inv/p1b-inv/ (read-only; citations
   current for fork SHA a6852ef8)

## Deliverables (unchanged from the brief)

Commits on gambit-ffi-p1b reaching: bridge builds (pinned inputs, digests
recorded); stage-1 adapter rows implemented (sa-load-shared-object + resolution,
memory ops, ordinary sa-foreign-procedure, native-error two-value return, real
sa-gc-collect; :gambit target-prims routing); gambitcheck green with qualified
rows flipped AND refusal rows retained (incl. the Q1 :ffi-layout/named-binding
refusal guards); new Makefile targets gambitffi, gambitffi-abi,
gambitffi-errno-check, gambitffi-load-failure-smoke green (assertive
state-derived verdicts, watchdogged); kernel/eval gates still green; findings
file /Users/sto/workspace/datomworld/collab/jolt/1795042000000-engineer-p1b-stage1-codex-gpt-6.1-sol.findings.md
read-only for writes — if the sandbox refuses that path, return the findings as
your final agent message and the orchestrator promotes them.

Standard of proof unchanged: target-identified public jolt execution where
reachable (document precisely what is and is not Gambit-reachable), assertive
verdicts, never fake a qualification. The prior agent's errno-capture work was
reported as working with the preserve-errno rows incomplete — re-verify every
partial piece yourself before building on it; discard anything that violates
the rulings.

## Rules

Foreground commands with explicit timeouts; watchdog every gate; keep working
(no idle >5 min — poll long builds with short reads). Do not touch
~/workspace/jolt, ~/workspace/jolt-ffi-p1/-inv/-mt, ~/workspace/gambit-v4.9.8-src.
You MAY commit on gambit-ffi-p1b; never push.

Begin the final response exactly with:
Completed-GMT / Completed-Local / Coding-Agent / Session-ID, then the
per-capability status table and gate summary line.
