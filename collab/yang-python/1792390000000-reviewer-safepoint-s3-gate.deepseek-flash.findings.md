Completed-GMT: 2026-10-17 19:22:00 GMT
Completed-Local: 2026-10-18 02:22:00 +07 (+0700)

# safepoint-s3 landing gate — REQUEST CHANGES

The core slice is sound: the pin map, the generator finding, the hook semantics and the new linked test hold up under static verification. Four small defects, one of them a correctness bug in the new feature, block sign-off.

## Findings

**1. [P2] `newline-lines` counts characters, not line breaks** — `src/cljc/yang/python/antlr/lower.cljc:223-243`.
The reduction adds `(count (filter #{\newline \return} text))` per NEWLINE token. The pinned lexer rule (`antlr/python3/Python3Lexer.g4:101`) is `NEWLINE: ({atStartOfInput}? SPACES | ('\r'? '\n' | '\r' | '\f') SPACES?)`, and `cst/token-record` exports raw `getText`. So a CRLF token `"\r\n"` advances the line by **2** and a form-feed token `"\f"` by **0**. The design amendment (`yang.antlr.md:1714`) and the function's own docstring both say "the line breaks", so `f_lineno` and every line event are wrong for CRLF or form-feed sources. No fixture exercises this (all use LF). Fix: increment once per NEWLINE token whose text contains `\newline`, `\return` or `\formfeed` (the start-of-input SPACES alternative stays 0), and add CRLF + form-feed coverage.

**2. [P2] The `yang.typescript.md` rewrap rider is not done** — `docs/design/yang.typescript.md:1107-1111`.
The brief asked to rewrap a 93-column line. The change moved the offender and made it 97 columns (`\`js-report\`) once D2 is fixed. The persistent session keeps the script-goal global object and the`), leaving the paragraph at 69/71/69/**97**/45 where the document wraps near 72. The finish report's "the line is rewrapped" is false.

**3. [P3] Stale hook-prelude docstrings** — `src/cljc/yang/python/antlr/safepoint.cljc`.
The ns docstring (~:40) says the bundled hook prelude "allocates only a cell and a cursor"; `state-definitions` (~:355) says "a cell and a cursor, nothing else"; `runtime-keys` (~:408) says "the handler cell and the signal cursor". Since this slice the state is the `handler` cell, the `cursor`, and the `trace` cell (`runtime-keys` already lists `py.sp/trace`).

**4. [P3] Generic-stage docstrings lag the new shapes** — `src/cljc/yang/safepoint.cljc:71-75,190-196`.
`site-set` still describes the sorted set as `[path kind]`; `insert` still describes `sites` as `{path kind}` and `profile` as `{kind hook}`. Marks may now be `[kind datum ...]` and entries `{:hook sym :operands true}` — the ns docstring was updated, these two were not.

**5. [P3] The pysp manifest provenance stops at value-display** — `test/yang/python/antlr/linked_safepoint_test.cljc:243-257`.
The pin is one of the three that moved, but its docstring explains every earlier move and not the s3 tracing move. Add the s3 clause (the other three provenance docstrings are correctly dated through both moves).

## Verified sound

- **Pin map.** Old values occur nowhere in source (`2d387d0d`, `fa373f02`, `767d8a65`). The merge `426256e4` carries master's `fa373f02`/`2d387d0d`; `bb3976f8` moves exactly the hook root (→`fda45181`) and the pysp golden (→`97aba7f7`). The unchanged pins are untouched: bundled root `ff9b9c08`, A/A′/record/prelude-id, py manifest `093fc404`, `dht-import-v1.edn`, `linked_prelude_test`. A/A′/record not moving is structurally explained: the float program's only selected site is the keyword `:loop`; `:line` marks are not selected under `hooks/profile`.
- **77→281.** The 21 added free names are exactly the tracing surface (`trace-state`, `trace-on?`, `profile-on?`, `fire`, `frame-new`, `set-local!`, `open-frame`, `own-frame`, `tcall`, `tline`, `treturn`, `settrace`, `setprofile`, `install-sys!`, `trace`, `return`, `loop`, `py/try`, `py/module-new`, `py/module-dict`, `py/modules-ref`, `py/make-function`, `py/dict-set`, `py/arg`, `not`); every one traces to the added code. An independent count of the tracing block's non-special list forms plus its `let`/`do` expansions lands in the high 180s–200s, consistent with +204. The golden diff is confined to the `:pysp` block; `:py` stays 3968; no other group moved.
- **Generator.** `deferred` is restored to `#{:pysp}`; the generator's strict abort gate is intact; `lower.cljc` changes are metadata-only, so `:py`/`:t` holding is consistent. `test/tmpgen/gen_test.clj` nets to zero.
- **Semantics vs the amendment.** Call after `enter`; line per statement start, sequenced before the node; return before `py.sp/return`; `:busy` suppresses re-entrancy and a raise clears both functions; `:frame` rides the escape-restored context record; module-level statements have no frame and emit no line event; no `f_locals`, exception or C-call events. The recursion test's expected 9 profile events at limit 10 is exactly the profiler's own `enter` reaching depth 11 and raising before `events.append`.
- **Observation-only.** `:line` marks are inert metadata; symbol entries drop the datum in `entry-hook`; `sequence-hook` is used only for statement kinds, so the plain profile's A′ and the generic `yang.safepoint` (keyword marks) are unchanged.
- **Linker / value-display.** The pysp tests are generic over `function-definitions`; `trace` is consistent across `runtime-keys`, `state-definitions` and `attach!`; value-display code and pins are untouched; the `py/run-session` comment matches the returned map.
- **New test.** `linked-tracing-profile-test` is `^:slow`, runs `trace-basic` under `linked-tracing-profile` with `pysp/install-sys! true` first on every VM. The ns has 16 deftests and the run reported 16 tests / 113 assertions, so it ran on the JVM. Node/Dart quick lanes skip guarded slow bodies (the finish log records SKIP), so it did not execute on those hosts.

*Evidence is the diff, the checked-in goldens/generator and the finish log; this was a read-only gate and the lanes were not rerun here.*

Verdict: REQUEST CHANGES
