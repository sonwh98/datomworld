Created-GMT: 2026-11-09 08:0x GMT (real header below)
Coding-Agent: codex
Session-ID: 8b45fac7-4520-4edb-ba86-b5391c976a86

# Task: confirm the P1-1 ambiguity correction (third and final review of this item)

Role: Lead System Architect

Implementers:
- Model: gpt-6-astra | Assigned: 2026-11-09 | Status: active

## Context

Your last confirmation (collab/yang-clojure/1794210000000-architect-cc1-p1-confirm.findings.md)
held P1-1 open: lineage-origin-for matched by rows-vector OR elementwise row
identity, so with shared row objects among distinct occurrences of identical
content, an earlier candidate could donate its origin. Your required correction:
"Prefer exact container lineage and eliminate first-match selection among
ambiguous row-level matches; propagate occurrence-specific lineage or explicitly
refuse ambiguity."

## The fix (uncommitted over fa70516f, worktree /tmp/cc1-p1)

macro.cljc:684 lineage-origin-for (the single recovery point used by construct,
slot-of, the prelude's recover-packet, and invoke*):
- exact container lineage checked FIRST (rows vector identical?);
- row-level recovery returns a function ONLY when exactly one distinct origin
  function matches; ambiguous cases recover NOTHING;
- content equality alone still recovers nothing (U1).
New pin: clojure_callsite_test.cljc:1096, your exact pick-second/feed/f repro,
plus the three earlier P1-1/P1-2 blocks retained.

Files: macro.cljc (392 changed lines total over fa70516f) and
clojure_callsite_test.cljc (+109).

## Verified by the orchestrator (both lanes, 4 namespaces incl. yin.vm.macro-test)

node 53 tests / 450 assertions / 0 failures
JVM  53 tests / 450 assertions / 0 failures

## Rule

Does the implementation meet your required correction — specifically: is
first-match eliminated, is ambiguity refused rather than guessed, and does the
exact-container path take precedence so the shared-row-objects case recovers the
CORRECT occurrence's origin? Then scan the delta for regressions (the refusal
semantics must not silently drop origins that SHOULD be recoverable).

Deliver: APPROVE (merge-ready) or REQUEST CHANGES with file:line. This terminates
the P1-1 item; on approval the codex re-gate follows and cc1 merges.

Constraints: read-only; no test runs. Begin with Completed-GMT / Completed-Local.
