Created-GMT: 2026-10-18 06:25:00 GMT
Created-Local: 2026-10-18 13:25:00 +07 (+0700)
Coding-Agent: claude-sonnet-5-5

# Task: safepoint-s3 fix round — the gate's five findings

WORK TREE: /Users/sto/workspace/datomworld-safepoint3 (branch safepoint-s3, tip e2bc7592). The deepseek
gate verified the pin map, the 77→281 magnitude, the hook semantics and observation-only-ness SOUND,
but returns REQUEST CHANGES on five items.

READ: collab/1792390000000-reviewer-safepoint-s3-gate.deepseek-flash.findings.md — the full findings
with file:line.

FIX, in order:

1. [P2 — the real bug] `newline-lines` (src/cljc/yang/python/antlr/lower.cljc:223-243) counts the
   characters `\newline \return` per NEWLINE token, so a CRLF token "\r\n" advances the line by 2 and
   a form-feed "\f" by 0. The lexer rule (antlr/python3/Python3Lexer.g4:101) allows both. Fix: advance
   by ONE per NEWLINE token whose text contains \newline, \return or \formfeed (the start-of-input
   SPACES alternative advances 0 — keep that). ADD COVERAGE: a CRLF source fixture and a form-feed
   fixture exercising the line count (the tracing line events and/or f_lineno). All fixtures so far are
   LF-only, which is why this slipped through.

2. [P2] The yang.typescript.md rewrap rider was claimed done but is not (the paragraph is now
   69/71/69/97/45 where the doc wraps near 72; the `\`js-report\`` line is 97 columns). Rewrap the
   PARAGRAPH properly this time — every line ≤ the doc's wrap width.

3. [P3] safepoint.cljc stale docstrings: the ns docstring (~:40), `state-definitions` (~:355) and
   `runtime-keys` (~:408) still say the state is "only a cell and a cursor" — since this slice it is
   the handler cell, the cursor, AND the trace cell (runtime-keys already lists py.sp/trace).

4. [P3] src/cljc/yang/safepoint.cljc: `site-set` (~:71-75) still describes the sorted set as
   `[path kind]`; `insert` (~:190-196) still describes `sites` as `{path kind}` and `profile` as
   `{kind hook}` — update to the new shapes (`[kind datum ...]` marks, `{:hook sym :operands true}`
   entries).

5. [P3] linked_safepoint_test.cljc:243-257 — the pysp manifest provenance docstring stops at
   value-display; add the s3 tracing clause (the other three provenance docstrings are correct).

VERIFY: the safepoint/trace namespaces (DATOM_SLOW_TESTS=1 via `clojure -M:test -n <ns>` — inline env
prefixes were denied before; use `mise exec -- clojure` and whatever form works, or report the denial),
the focused python-antlr namespaces, the CRLF/FF fixtures green, kondo 0/0 on changed files, cljstyle
check (if denied, say so — the orchestrator runs it). No pins move: this round changes line-COUNTING
correctness for CRLF/FF sources only — all existing fixtures are LF; if any pin DOES move, that is a
finding to report, not a pin to chase.

CONSTRAINTS: single turn, no approvals. One or two commits to safepoint-s3. Do NOT push, do NOT touch
master. End with: the fix map, the new fixtures and their results, the lane results, and confirmation
of whether any pin moved.
