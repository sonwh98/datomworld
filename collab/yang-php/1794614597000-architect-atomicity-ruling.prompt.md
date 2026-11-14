Created-GMT: 2026-11-14 (dispatch time)
Created-Local: 2026-11-14 ~07:0x +0700
Coding-Agent: codex
Session-ID: pending

# Task: Architect ruling — the eval redeclaration ATOMICITY policy on the service path

Role: Architect (read-only design consult; you own the eval-bridge design). The implementation
stopped twice on this unit (two-strikes) because the atomicity requirement needs a design
decision: READ collab/yang-php/1794614171000-engineer-eval-bridge-r12.gpt-6.1-terra.findings.md
(the investigation + baseline 22/132) and the lifecycle oracle record
(collab/orch-lane/p7-lifecycle-probes.txt) first.

The question: an eval-unit whose declarations collide with the caller's existing tables (or an
earlier successful eval's) — does the service path (a) PREFLIGHT the entire eval-unit's
declarations against the caller's request tables before registering ANY (atomic refusal, nothing
installed), or (b) register as it goes and route the collision through the guest compile-fatal
terminal path (partial registration possible before the fatal — with shutdown/dtors/255 per the
lifecycle record)? php/declare-function! currently raises a CATCHABLE Error; the oracle requires
the UNCAUGHT guest-fatal shape for the collision.

Rule with evidence (php-src PHP-8.3 semantics for eval'd redeclare: does Zend register
incrementally or preflight the whole eval op_array? cite), the decision table for the service
path, and the fixture list that pins it. If the answer is (a), specify where the preflight lives
(coordinator? admission stage? linker admission?) given the existing P7-b site-set/linker
machinery on this branch.
Deliverable: collab/yang-php/<ts>-architect-atomicity-ruling.findings.md. Read-only.
