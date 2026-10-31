Created-GMT: 2026-10-17 12:50:00 GMT
Created-Local: 2026-10-17 19:50:00 +07 (+0700)
Coding-Agent: claude
Session-ID: 559b30ff-9f3e-49b5-839d-b4283b402f53 (fix round 2; fresh session — the prior value-display session was 362cd171-92e6-4789-9f1a-2daef2ce47a1)

# Task: Value-display fix round 2 — the deepseek gate findings

Role: Yang Compiler and Universal AST Engineer (fix round; the same session as the original value-display implementation)

WORK TREE: /Users/sto/workspace/datomworld-c4fix (branch yang-python-value-display; your uncommitted diff on
top of 123c12eb). Do not stage or commit.

THE GATE (deepseek-flash): REQUEST CHANGES. Findings:
/Users/sto/workspace/datomworld/collab/1792306000000-reviewer-value-display-gate.deepseek-flash.findings.md
(the full report, with its CPython InteractiveConsole probe table).

THE SEMANTICS RULING (the orchestrator, per the design's own CPython-differential-parity principle):
**match CPython's InteractiveConsole** — EVERY top-level bare expression statement in a submission
displays its value, in order. Not Jupyter's last-only. Your current last-only implementation is the
defect the gate caught.

FIXES:
1. **[P2→core] Mark every bare expr_stmt** among the top-level simple_stmts of the final simple_stmts
   (not just the last), per CPython's `single`-mode displayhook semantics. Semicolon-separated
   expressions each display, in order. Add the gate's test rows: `1; x = 2` displays `1`; `y; z = 1`
   displays `5`; `1; 2` displays `1`,`2`; `2; print(1)` displays `2`,`1`.
2. **[P3] Harden the in-band marker**: a Python-unforgeable sentinel (a non-string value that
   snapshot/`repr` passes through verbatim — e.g. a unique object or namespaced keyword the string
   path cannot produce) or a per-session nonce, plus a collision regression test
   (`print("\0display")` must NOT be rendered as a display).
3. **[P3] Cross-host display case**: add a packet-based display case to the shared precompiled-packet
   fixtures (the langs matrix) so cljs/cljd exercise a display line; run bb test:cljs to verify.
4. **[P3] repr escaping** (pre-existing, now load-bearing): render/string-repr should escape
   non-printable Unicode per CPython's repr (`repr("\x80") == "'\\x80'"`) — check the scope; fix if
   small, else report.
5. **[P3] Stale docstrings**: frontends.cljc:56-61 and the test namespace docstring still say the
   frontend shows only what a submission printed — update for the value display.

LANES (foreground, one at a time, waiting; complete the turn): the yin.repl.python-antlr-test focused
run; yin.repl.main-test; kondo + cljstyle on touched files; bb test:cljs for the packet-based display
case. Report exact counts.

Begin the final response exactly with:
Completed-GMT / Completed-Local

Report the mechanism changes, exact counts, anything behavioral, incomplete work.
