# Value-display landing gate — review report (read-only)

## Context

`collab/1792306000000-reviewer-value-display-gate.prompt.md` asks for an adversarial-lite landing
gate on the uncommitted value-display diff (branch `yang-python-value-display`, 3 files,
`git diff HEAD`). The feature: `lower-module-body` takes `display?`; a marked bare expression
statement lowers to a `None`-suppressing `py/print` of `[marker value]`; `repl.cljc/report` renders a
marker-prefixed entry with `render/repr`. The task is review-only; no code changes were made. Only
the already-run evidence was used, plus read-only CPython probes to adjudicate the semantics question.

## Findings

### P2 — "last statement only" is not CPython's semantics

`last-expr-stmt` (lower.cljc:2022-2032) returns only the last top-level bare `expr_stmt`; the guard at
lower.cljc:1390 displays only that node. CPython's `single` mode (what `InteractiveConsole` uses)
displays **every** top-level expression statement in the submitted input, in order. Verified with
CPython's `code.InteractiveConsole`:

| input | CPython | this diff |
|---|---|---|
| `1; x = 2` | `1` | nothing |
| `y; z = 1` (y=5) | `5` | nothing |
| `1; 2` | `1`,`2` | `2` |
| `2; print(1)` | `2`,`1` | `1` |

So the gate's example (`y; z = 1` prints nothing) is the wrong reading, and it contradicts the gate's
own premise ("every expression statement's value goes through sys.displayhook"). The implementation
matches the brief's explicit last-only wording, but the brief's CPython claim is factually wrong.
Fix: mark every bare `expr_stmt` among the top-level `simple_stmt`s of the final `simple_stmts`, and
check set membership in `lower-expr-stmt`; add the semicolon cases to the test. If the owner ratifies
last-only as an accepted divergence, record it explicitly instead — then this drops to P3.

### P3 — the in-band marker is reachable; the "practically unreachable" claim is false

`report` checks only `(first items)` (repl.cljc:35-39). String literals support `\x00`, `\0` and
`\u0000` (lower.cljc:374-377, 393-406), so a user can write `print("\x00display")` (renders `None`,
because `(second items)` is nil) or `print("\x00display", 42)` (renders `42`, silently dropping the
marker arg). `chr` is not implemented, but no builtin is needed. An out-of-band channel is not cheap
at this layer (`run-session` returns only `:py/out`/`:py/exception`, prelude.cljc:3264-3287); the
cheapest hardening is a Python-unforgeable sentinel (a non-string literal that `snapshot` passes
through) or a per-session nonce, plus a collision regression test.

### P3 — cross-host display path unverified

The new test is `#?(:clj ...)` (test:129-137) and uses the JVM-only installed frontend; no display
case exists in the shared precompiled-packet fixtures (test:44-50), so cljs/cljd never see a display
line even though `lower-session` passes `display? true` on every host (repl.cljc:88). By inspection
the mechanism is portable (cljc marker, portable lowering, cljc `render/repr`, `identical?` used
throughout cljc code), but it was not run on Node/Dart. Add a packet-based display case so the shared
`langs` matrix covers it.

### P3 — display repr is not fully CPython-faithful

`render/string-repr` escapes only code units < 32 and 127 (render.cljc:99-119). CPython's repr also
escapes non-printable Unicode (e.g. `repr("\x80") == "'\\x80'"`), so display can emit raw C1 control
characters to the terminal. This pre-existed but display now makes it load-bearing for every value.

### P3 — stale docs

`frontends.cljc:56-61` and the test namespace docstring (test:4) still say the frontend shows only
what a submission printed. Update for the ANTLR value display.

## Confirmed good

- **prompt-owed?**: the display text is one `:repl/output` element in the same tick (repl.cljc:61-64
  → `yin.repl` `println` → main.cljc:1046-1060/906-908); `prompt-owed?` fires on `(seq lines)` or
  cursor advance (main.cljc:1078-1084), so no missing prompt, and one prompt call per tick means no
  double prompt (1279-1280, 1421-1422, 1558-1560). Landed prompt-suppress code is untouched.
- **Claimed reading implemented correctly**: the last `simple_stmt` of the last top-level
  `simple_stmts` (lower.cljc:2022-2032; grammar `simple_stmts: simple_stmt (';' simple_stmt)* ';'?
  NEWLINE`), bare-expression test `(= 1 (count (kids expr_stmt)))` is right for this grammar (no
  TYPE_COMMENT, comments skipped); `y = 2; y` displays.
- **Identity**: `identical?` is sound — `p/node` returns the stored map, `p/children` preserves the
  same objects, `validate!` returns its argument unchanged.
- **None suppression / ordering**: `display-stmt`'s `= :py/None` check works; explicit prints stay
  separate entries joined in order; `print(7)` prints once.
- **Scope/goldens**: `display?` defaults false; `lower-packet`, `module-packet` and the composer use
  the 1/2-arity (lower.cljc:2074, 2133, 2279); only `repl.cljc:88` passes true. Diff is three files;
  no golden movement.

## Verification performed

Read-only: the three-file diff; `lower.cljc` (helpers, escape handling, dispatcher, packet entry
points), `repl.cljc`, `render.cljc`, `prelude.cljc` (print/snapshot/run-session), `frontends.cljc`,
`main.cljc` (prompt-owed?), grammar and CST surface, the test file. Ran only CPython
`code.InteractiveConsole` probes (no repo tests re-run, as instructed).
