Created-GMT: 2026-10-16 22:05:00 GMT
Created-Local: 2026-10-17 05:05:00 +07 (+0700)
Coding-Agent: claude
Session-ID: d28a0bbe-7efb-472b-95c0-97af90b8e4d4

# Task: Safepoint slice 3 — tracing (sys.settrace/setprofile via the interpreter)

Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: claude sonnet-5.5 | Assigned: 2026-10-17 05:05:00 +07 (+0700) | Status: active | Rationale: you built safepoint slices 1-2; tracing is the third slice of your design's sequence

WORK TREE: /Users/sto/workspace/datomworld-safepoint3 (branch safepoint-s3, based on origin/master). Do not
touch other worktrees (datomworld-c4fix, -antlr-multi, -c3key1 are in use). Do not stage or commit.

DESIGN (binding): the safepoint design's tracing slice (collab/1790849347715-architect-safepoint-
interpreter.claude-fable-5-1.findings.md — the tracing rows; the owner-ruled semantics: sys.settrace
without a tracing profile raises an explicit unsupported error) and the landed slices 1-2 (signals,
recursion with the dynamic-context record, generator admission — all on master).

SCOPE (the design's tracing slice — the slice order the owner accepted: signals, recursion, TRACING,
threads; this is tracing):
1. sys.settrace/setprofile: with a tracing profile, the guest installs a Python trace function; the
   interpreter observes call/line/return events at the safepoint marks (the profile keys the hook set).
2. sys.settrace without a tracing profile: the explicit unsupported error (the landed slice-1 behavior).
3. The trace function is guest code (a Python callable) — the interpreter invokes it through the
   standard call machinery at the marks; the dynamic-context record's :frame carries what the trace
   events need.
4. The conformance corpus: the tracing fixtures (a trace function recording call/line/return for a
   sample program; setprofile counting; the recursion interplay) pinned and green on every host.

OUT OF SCOPE: threads (slice 4), the signal-delivery journalling, the profile store beyond what the
tracing needs, the float64-carrier/strict-repr features (the parallel value-display round's).

ACCEPTANCE:
- The tracing fixtures green on every host (the trace events recorded for a sample program; setprofile
  counting; the recursion interplay per the design).
- sys.settrace without the profile: the explicit unsupported error (the landed behavior).
- The slices-1/2 regressions stay green (signals, recursion, generator admission).
- Lanes: the focused namespaces, yin.repl tests, kondo + cljstyle. No background runs; complete the
  turn. mise is trusted; run gen/build if builds are stale.

Begin the final response exactly with:
Completed-GMT / Completed-Local

Report the mechanism, exact counts, anything behavioral, incomplete work.
