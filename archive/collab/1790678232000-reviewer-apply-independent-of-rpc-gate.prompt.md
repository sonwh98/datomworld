Created-GMT: 2026-09-29 10:37:12 GMT
Created-Local: 2026-09-29 17:37:12 +07 (+0700)
Coding-Agent: codex
Session-ID: 01a0ecbd-959c-7de1-af34-1feb70855f89 (captured)
# Task: Gate — dao.stream.apply independent of rpc (one breaking change)

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Model: gpt-6-sol | Assigned: 2026-09-29 17:37:12 +07 (+0700) | Status: active | Rationale: standing gate route; Claude-authored (claude-opus-5-5); gpt-6-sol also wrote the ruling — review the code against the OWNER INVARIANT first and the ruling second; do not defend the ruling where it falls short of the invariant

Read-only review in /Users/sto/workspace/datomworld (master 643b1ba6, uncommitted). Do not edit. Treat reports as
untrusted; cite evidence. Change under review: git diff -- src test docs/design (18 files; docs/orchestrator-log.md is
orchestrator bookkeeping, out of scope).

OWNER INVARIANT (verbatim): "dao.stream.apply needs to be independent of the concept of rpc because it can use a
framebuffer".
Ruling: collab/1790675432000-architect-apply-independent-of-rpc.gpt-6-sol.findings.md (verdict + sections 1-5).
Brief: collab/1790676940000-stream-engineer-apply-independent-of-rpc.prompt.md. Report (untrusted):
collab/1790676940000-stream-engineer-apply-independent-of-rpc.claude-opus-5-5.report.md.

Orchestrator-verified on this exact tree (do not rerun): kondo 0/0; cljstyle clean (orchestrator reformatted one file);
grep for :dao.stream.apply/{not-found,detached,ended,no-surface,oversize,transport-error} in src/test/docs finds nothing
outside the orchestrator log; apply.cljc's only remaining "transport" text is the raw :dao.stream/transport-error handle
outcome (the ruling keeps it); build/yin-repl-peer rebuilt from the changed driver; full JVM 2362 / 184307 / 0
(main-test passed via the rpc gate); Node 2267 / 50789 / 0; CLJD +2229: All tests passed!.

Check: the invariant holds for apply's code, docstrings and keyword namespace; each reason maps correctly and
distinctly; only detached rebinds; the cursor-pending gate (no id, no allocation, no append, identical state; also
before unsent retry) and that removing the driver's own guard loses nothing (queue order, cadence, pending-write?);
:yin.vm.ffi/response-lost with distinct loss facts; the replaced rpc test (old one sent before mint) is a legitimate
replacement, not a weakening; the new apply vocabulary test and framebuffer-like medium test really pin the invariant.
Rule explicitly on the implementer's open points:
Q1. docs/design/dao.stream.apply.md still says "Transport Independence" / "Transport Examples" (not in the file scope).
    Does the invariant require fixing it in this change?
Q2. The ruling's "terminal mint completes outstanding work" path is now reachable only from a hand-built state because
    request! can no longer create pre-mint outstanding work. Keep that code, simplify it, or is anything missing?
Q3. Pre-existing: a line typed in the tick a held queue becomes sendable is sent ahead of the queued lines (ordering
    unchanged by this change). Defect to fix now, or separate?

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Findings as P0-P3 | file:line | evidence | concrete fix, or "No actionable findings". Answer Q1-Q3; mark owner decisions.
End with Verdict: READY / REQUEST CHANGES and Sign-off: GRANTED / WITHHELD.
