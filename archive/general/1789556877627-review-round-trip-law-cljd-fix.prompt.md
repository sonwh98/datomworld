Created-GMT: 2026-09-16 11:07:57 GMT
Created-Local: 2026-09-16 18:07:57 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: pending (provider-generated)

# Task: Independent review of the ClojureDart round-trip-law content-addressing fix

Role: Adversarial Review

Implementers:
- Model: gpt-6-astra | Assigned: 2026-09-16 18:07:57 +07 | Status: active | Rationale: cross-family review of a Claude-family (claude-opus-5) fix touching the core content-addressing model; GPT review capacity is very low tonight, spending it deliberately on this one because it's foundational

## Context

`test/yin/vm_test.cljc`'s `semantic-bytecode-round-trip-law` deftest
passed on the JVM but failed on ClojureDart — the full CLJD test suite ran
to completion for the first time all session (an unrelated bench-file bug
had blocked it) and surfaced this as a real, reproducible failure. Root
cause, found and fixed: ClojureDart's `(list ...)`/`(apply list ...)`
returns a list carrying `cljd.core`'s own reader metadata
(`{:line … :tag PersistentList …}`) — something JVM Clojure's `list` does
not do. Two independent call sites both construct lists this way and both
leaked that host-specific metadata into `dao.jing`'s content-address hash:
`dao.jing.cljc`'s `order-normalize` (the canonical encoder itself) and
`yin.vm.cljc`'s `strip-reader-positions` (used when projecting an AST
to semantic-bytecode rows). The fix adds `(with-meta ... nil)` after the
`apply list` call at both sites.

Read the actual diff yourself:
```
git diff src/cljc/dao/jing.cljc src/cljc/yin/vm.cljc \
         test/dao/jing_test.cljc test/yin/vm_test.cljc \
         docs/design/dao.jing.md
```
All five files are modified, working-tree, uncommitted.

Verified locally by the orchestrator (not just trusted from the delegate's
report): `clj -M:test -n dao.jing-test -n yin.vm-test` → 0 failures,
405 assertions. `clj -M:kondo --lint` clean on all four touched source/test
files. `bb test:cljd` (full suite) → the round-trip-law failure is gone;
exactly one unrelated pre-existing failure remains
(`yin.repl.core-test/a-failed-input-is-consumed-exactly-once`, being
investigated separately, out of scope here).

## Task

This is architectural/implementation review, read-only. Evaluate:

1. **Is the root cause actually correct?** Does ClojureDart's `list`/
   `apply list` really mint reader/tag metadata the way the diff's comments
   claim? If you can reason about or verify this independently (read
   ClojureDart's own `list` implementation if accessible, or reason from
   the actual pinned test hash and metadata assertions added), confirm or
   refute it. Don't just accept the stated explanation.
2. **Is `(with-meta ... nil)` the right fix, at the right layer, in both
   places it was applied?** Consider: is there a THIRD site anywhere in
   the codebase that also does `(apply list ...)`/`(list ...)` on a value
   that might flow into content-addressed hashing, that this fix missed?
   Search for it. Is stripping metadata after `apply list` safe — could
   any *real*, meaningful metadata attached to the original list elements'
   own individual items get lost, as opposed to just the newly-minted
   wrapper list's own spurious metadata? (The fix strips the meta of the
   *result* list, not its elements — confirm this distinction is preserved
   correctly by reading the actual diff context, not just the hunk.)
3. **`dao.jing.md`'s new Open Items entry** ("ClojureDart's `list` mints
   metadata") — is it accurate, appropriately scoped, and does it correctly
   describe what's fixed versus what residual risk (if any) remains? It
   claims "no producer is known to hit this today" for the *unfixed*
   residual case it describes (a Dart producer building a list value
   itself, not through `order-normalize`/`strip-reader-positions`) — is
   that claim actually true, or does something in the live pipeline
   construct lists via `(list ...)` directly without going through either
   fixed function?
4. **Test coverage.** Does `content-hash-of-a-list-is-host-independent` and
   `semantic-bytecode-list-payloads-mint-no-metadata` actually exercise the
   failure mode, or could they pass for the wrong reason (e.g. because the
   JVM test runner never reproduces the ClojureDart-specific metadata in
   the first place, so these tests are only meaningful when actually run
   under `bb test:cljd`)? Is the pinned hash literal in `jing_test.cljc`
   (`"e5bab3450d860af30befedbf9a650a761af5b35663e00cc1a126d15cf9199cb5"`) a
   real, load-bearing regression guard, or could it silently mask a future
   change to the encoder if nobody re-derives it correctly?
5. **Anything else** — does this fix interact with the `0cafb2d` list/seq
   canonical-address fix from earlier tonight in any way worth flagging?
   Any portability risk on CLJS specifically (untested by `bb test:cljd`,
   which only covers CLJD)?

Do not edit any file.

## Deliverable

A findings list, most severe first: what's wrong (or confirmed correct),
file:line evidence, and severity (blocking / real gap / minor-nonblocking /
already correct). End with an overall verdict: ready to proceed toward
Architect sign-off as-is, or does something need fixing first.
