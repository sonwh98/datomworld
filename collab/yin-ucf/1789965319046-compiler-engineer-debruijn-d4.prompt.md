Created-GMT: 2026-09-21 04:35:19 GMT
Created-Local: 2026-09-21 11:35:19 +07 (Indochina Time)
Coding-Agent: glm
Session-ID: 923b8885-4549-4b46-ad11-0731ebb614ef (resumed — your D0-D3 session)
# Task: implement D4 of the de Bruijn projection — stream adapter
Role: Yang Compiler and Universal AST Engineer
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-21 11:35:19 +07 | Status: active | Rationale: same implementer; D3 signed off (gpt-5.6-sol granted proceeding to D4); your 5-hour cap has reset

You are in the git worktree at /Users/sto/workspace/worktree-debruijn-impl
(branch debruijn-impl, your D3 committed as df3a15e5). Same rules as before:
work only in this worktree; do not stage, commit, merge, or push; one simple
command per step; the mise environment block from your build brief applies.
If an API error interrupts you, just resume where you left off on retry.

Read: docs/design/yin.vm.debruijn-projection.md §6 and §7's D4 (every sentence
a rule), §2's framing rules, §8's stream rows, your own frame-datoms /
project-datoms / forward-step surface, and dao.stream's forward-step
precedents (how existing consumers shape outcomes and pending writes).

Deliverable — D4 only, in src/cljc/yin/vm/debruijn.cljc and
test/yin/vm/debruijn_test.cljc:
- forward-step per §6: ordinary dao.stream forward interpretation — frames at
  the root marker, projects there, emits the projected tuples, reports a
  partial frame at end-of-stream as diagnostic. Pending writes remain
  explicit and are retried only by host cadence (no callback, timer, or
  scheduler — §1's invariant).
- Outcome coverage per §7-D4 and §8: blocked, transport-error, invalid input,
  pending output, root framing, adjacent graphs (including graphs that reuse
  temporary eids), and terminal diagnostic outcomes — partial frame at
  end-of-stream covered on every host.
- The D4-owned reader obligation from the D2 sign-off: diagnostic ORDERING in
  datoms->projected — slot-type validation must precede (or be folded into)
  hash recomputation so a wrongly-typed slot yields :unsupported-value or
  :hash-mismatch deterministically, never a host exception. Tests: the
  reviewer's examples (:yin.debruijn/bound 5; a numeric child ref) now
  diagnose on all three hosts.
- Do not alter dao.stream itself or any existing consumer — this namespace
  stays a pure consumer of the dao.stream protocol.

Completion (§7-D4): blocked, transport-error, invalid input, pending output,
root framing, adjacent graphs (reusing tempids), and terminal diagnostics
covered; partial frame at end covered on every host.

Verification (report exact counts): focused JVM, kondo, cljstyle, and the
CLJS lane if convenient. The orchestrator reruns everything including CLJD.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact outcomes, unresolved concerns, incomplete work.
