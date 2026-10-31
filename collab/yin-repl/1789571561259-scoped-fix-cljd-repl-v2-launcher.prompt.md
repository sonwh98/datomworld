Created-GMT: 2026-09-16 15:12:41 GMT
Created-Local: 2026-09-16 22:12:41 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: glm
Session-ID: 0785d0b9-1fb3-45c1-9972-e037365138c1

# Task: Fix the v2 ClojureDart REPL launcher's wrong function call

Role: Scoped / Subagent

Implementers:
- Model: glm-5.3-flash | Assigned: 2026-09-16 22:12:41 +07 | Status: active | Rationale: single well-defined bug, narrow scope

## Context

`bin/yin_repl_main.dart` calls `repl.run_main(args)`, but
`src/cljc/yin/repl.cljc:419` defines the function as:

```clojure
(defn ^{:dart/name main} run-main
  ...)
```

The `^{:dart/name main}` metadata means the function's generated Dart name
is `main`, not the mechanically-transliterated `run_main` ClojureDart
would otherwise produce from the hyphenated `run-main`. So the launcher
calls a function that doesn't exist under that name — confirmed
independently: `clj -M:cljd-yin-repl` compiles then fails with `Error:
Method not found: 'run_main'`.

This is a real, pre-existing bug (confirmed via `git log --all --oneline
-- bin/yin_repl_main.dart`, predates tonight's session entirely,
unrelated to any work done tonight). It was found while verifying a
separate, larger unit (deleting the v1 REPL) — deleting v1 while the only
remaining CLJD REPL entry point is confirmed broken would be a real
regression, so this must be fixed and verified first, as its own small,
separate commit, before that larger deletion lands.

## Task

Fix `bin/yin_repl_main.dart` to call the correct function name:
`repl.main(args)` instead of `repl.run_main(args)`. Do not change
`^{:dart/name main}` or anything in `v2.cljc` — the launcher's call site
is what's wrong, not the function's naming. Do not touch any other file.

## Verify

- `clj -M:kondo --lint bin/yin_repl_main.dart` (if kondo can lint
  `.dart` files at all — it likely can't meaningfully; note this if so
  rather than force a result).
- `clj -M:cljd compile yin.repl` (or the equivalent scoped compile) to
  confirm the generated Dart still names the function `main`.
- `clj -M:cljd-yin-repl` — run it and confirm it actually starts (not
  just compiles) this time. A headless start-then-immediate-stop check is
  fine; you don't need a full interactive session. Report the exact
  output you see, including confirming it doesn't fail with the
  `run_main`/`Method not found` error anymore.
- `bb test:cljd` (full suite, clear `test/cljd-out` first per the
  documented hazard in `docs/agents/build-n-test.md`) — must still pass;
  report the count.

Do not stage or commit. Do not touch any file other than
`bin/yin_repl_main.dart`.

## Deliverable

Report back: the exact one-line diff, the exact verification commands and
their output (especially confirming the launcher actually starts now),
and confirmation nothing else was touched.
