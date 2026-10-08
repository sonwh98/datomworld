Created-GMT: 2026-10-02 23:50:00 GMT
Created-Local: 2026-10-03 06:50:00 +07 (+0700)
Coding-Agent: claude
Session-ID: a80b326f-0efb-4ae5-ae0f-df0154076bab (resumed; slice 2 fix round 1 — the converged depth ruling)

# Task: Safepoint slice 2 fix round 1 — the generator-depth ruling

Role: Yang Compiler and Universal AST Engineer

Both architects ruled on your generator-depth divergence (fable:
/Users/sto/workspace/datomworld/collab/1790982600000-architect-generator-depth-ruling.claude-fable-5-1.findings.md;
astra: collab/1790982600000-architect-generator-depth-ruling.gpt-6-astra.findings.md — main tree; summaries
below are binding):

YOUR IMPLEMENTED BEHAVIOR IS REJECTED. Depth measures the CURRENT continuation. The mechanism (fable):
- The dynamic-context record gains a crossing-owned :base.
- A generator's depth is RELATIVE to its :base.
- Every crossing INTO the generator sets :base to the resumer's ABSOLUTE depth (so a later resume from a
  shallower frame re-bases — CPython's counting).
- Escapes restore the saved record with the CURRENT :base left alone (activation-relative restoration; the base
  is not part of what an escape restores).
- Astra's additions: intra-activation escapes restore their snapshot; only crossings rebase. A generator
  resumed explicitly by another THREAD uses that new resumer's depth; a scheduled-out thread keeps its own
  base/depth/handlers/frame whole (slice 4's swap design stands unchanged and is correct under this rule).

ALSO IN THIS ROUND (astra's setter contract):
- py.sp/set-recursion-limit! gains the current-depth check: a positive limit at or below the current effective
  depth raises RecursionError WITHOUT changing the limit (Python's sys.setrecursionlimit contract;
  safepoint.cljc:96 lacks it today). The limit stays OUTSIDE escape-restored context so valid changes persist.
- RecursionError under RuntimeError in the base prelude: confirmed. Default 1000, ValueError <=0, TypeError
  non-int: confirmed. Bool follows Python's integer-subtype treatment.

TESTS:
- The 79/79 expectation becomes 79/99 (a generator resumed from a shallower frame counts on top of the
  resumer); add the re-base-from-shallower case explicitly.
- The setter: valid change persists across escapes; a too-low limit raises RecursionError and keeps the old
  limit.
- Keep every other slice-2 test; adjust the generator-depth expectations to the ruled semantics.
- One paragraph in docs/design/yang.antlr.md 8.5.2 updating the depth sentence (the ruling's invariant: depth
  measures the current continuation; crossings rebase the generator's base; escapes restore activation-
  relatively; the limit sits outside escape-restored context).

MECHANICS: foreground, one command at a time, waiting for each; no background runs, no watchers, complete the
turn: mise exec -- bb gen:python-antlr (skip if current), bb build:yin-repl-node (skip if current), bb test:clj,
bb test:cljs, bb test:cljd; kondo + cljstyle on touched files. If a single lane exceeds any cap, run it in
namespace groups like your last round and say so. Report exact counts.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700>
