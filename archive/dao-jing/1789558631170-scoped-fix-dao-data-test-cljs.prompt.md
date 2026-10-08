Created-GMT: 2026-09-16 11:37:11 GMT
Created-Local: 2026-09-16 18:37:11 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: glm
Session-ID: 6eb34d30-a56f-45f0-9e12-3ccc9902dbf3

# Task: Fix the CLJS compile failure in dao.data's test file

Role: Scoped / Subagent

Implementers:
- Model: glm-5.3-flash | Assigned: 2026-09-16 18:37:11 +07 | Status: active | Rationale: single well-defined bug, narrow scope

## Context

`test/dao/data_test.cljc` (already committed, part of the `dao.data`
namespace landed earlier tonight) fails to compile on ClojureScript.
`bb test:cljs` (run for the first time tonight, while verifying an
unrelated fix) errors:

```
ExceptionInfo: failed compiling constant: 1/3; clojure.lang.Ratio is not
a valid ClojureScript constant.
```

Root cause, confirmed: `deftest tag-classifies-every-branch` (line 45)
uses a bare `1/3 :number` row in an `are` table (line 51), with no reader
conditional gating it. ClojureScript has no ratio type at all — not just
"can't read this literal," there's no way to construct a ratio value on
that host — so this specific test case is meaningless on CLJS, not just
broken syntax.

This file already has a separate, correctly CLJ/CLJD-only-gated ratio test
elsewhere (`data/summarize 1/3 bounds` around line 139-140) — read that
site first as your precedent for the gating style this file already uses,
if it uses reader conditionals there; if that site is inside a `deftest`
gated with `#?(:cljs nil :clj (deftest ...) :cljd (deftest ...))` or
similar, match that exact convention rather than inventing a new one.

The full `bb test:cljd` suite (which does compile and run this exact test
file) already passes cleanly with this file as-is — so ClojureDart does
support ratio literals here; this is CLJS-specific only. Do not remove
ratio coverage from CLJ/CLJD, only exclude it from CLJS.

## Task

1. Read `test/dao/data_test.cljc` in full first.
2. Fix line 51's `1/3 :number` row so it's excluded from ClojureScript
   compilation while remaining present for `:clj` and `:cljd`. The
   simplest correct approach: splice it in with an inline reader
   conditional, e.g. `#?@(:cljs [] :default [1/3 :number])` (verify this
   syntax actually reads correctly inside an `are` table's flat argument
   list — it's a less common position for `#?@` than a `:require` form;
   test it) — or match whatever gating convention the file's existing
   ratio test (line ~139-140) already uses, if that turns out to be a
   cleaner fit for this specific spot. Use your judgment on which reads
   more clearly, but the row must vanish entirely under `:cljs` while
   staying present under `:clj` and `:cljd`.
3. The classification point being tested (a ratio still tags `:number`)
   should not be silently lost for JVM/CLJD — keep it working there
   exactly as before.
4. Do not touch any other row in the `are` table, any other deftest, or
   any file other than `test/dao/data_test.cljc`.

## Verify

- `clj -M:kondo --lint test/dao/data_test.cljc`
- `clj -M:test -n dao.data-test` (JVM) — must still pass, same or higher
  assertion count than before (202 assertions currently).
- `bb test:cljs` — must now complete without the ratio compile error.
  Report the full pass/fail result, not just confirmation it compiles.
- `bb test:cljd` — confirm still passes (1341 tests, exactly one
  known-unrelated failure `yin.repl.core-test/a-failed-input-is-
  consumed-exactly-once` expected; anything else newly failing is your
  own regression to fix before reporting done).
- Do not stage or commit.

## Deliverable

Report back: the exact diff, the exact verification commands and their
output for all three hosts, and confirmation nothing else was touched.
