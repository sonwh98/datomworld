## What was implemented

All in `/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc` and `/Users/sto/workspace/datomworld/test/yin/repl/require_test.cljc`. Nothing under `dao/jing/**`, `yin/vm/linker.cljc`, or `yin/repl/link.cljc` was touched by me.

**1. `:link-policy` on `create-state`** — `checked-link-policy` (repl.cljc:557) fails closed at assembly: `:manual` default (nil passes through), a `fn?` value kept as-is, `:lease` refused with "Yin REPL :link-policy :lease is not implemented yet", anything else refused "Unknown Yin REPL :link-policy" — both with ex-data `{:link-policy p :supported [:manual :fn]}`. Kept on the shell beside `:link-source` (repl.cljc:623); `rebuild-session` merges it through, so `(reset)` and `(vm ...)` preserve it.

**2. The view (section 3.1)** — built inside `consult-link-policy` (repl.cljc:991-996): `{:links [{:name ... :link-id ...}] :checks n :lines-retained m}`, plain data only.

**3. Consult timing (3.2)** — at park with `:checks 0` (`pending-eval`, repl.cljc:1029) and after each re-check that leaves the run pending (`recheck-pending*`, repl.cljc:1296); never on completion and never in the refusal-raise catch path.

**4. Answers (3.3)** — `:keep` keeps; `:abandon`/`{:abandon reason}` runs the same `abandon-pending` path as `(abandon)` (repl.cljc:929, now two-arity with reason + ended-message; the 1-arity `(abandon)` keeps `:yin.repl/abandoned`), reason defaulting to `:yin.repl/link-policy`; message `;; the session link policy ended the require` (`policy-abandon-text`, repl.cljc:924) in the `(abandon)` notice shape. Retained lines dropped once, reported once.

**5. Fail-safe (3.4)** — a throw or out-of-contract return is treated as `:keep` for that consult plus exactly one `Error: ...` line (repl.cljc:1002-1035); never session death.

**6. State summary (3.5)** — each `repl-state` `:pending` entry gains `:policy` (`:manual`/`:fn`) and `:checks` (repl.cljc:1440-1446); the vector shape is preserved so no existing test changes. `:checks` counts no-progress re-checks and resets on progress — `drive-links` now returns `[state vm pending progress?]` (repl.cljc:856).

**Section 4 settlement: the new step function.** Reading the drivers settles it against "already able": `driver/repl-step` only re-checks a pending run as a side effect of `drain-input` → `handle-line` → `eval-input` (driver.cljc:549-564, 170-176), and main.cljc's tickers only call `repl-step`/`step-all` — no input line, no re-check. So the shell gained the public `recheck-pending` (repl.cljc:1346): one re-check + policy consult, returns `[state text]`, text nil when nothing pending, no clock, no callback; the host decides when to call it. Drivers were not wired to it, per "the host decides".

## Tests (test/yin/repl/require_test.cljc, 11 new deftests, 532→813 lines)

`link-policy-defaults-to-manual-test` (551), `a-function-policy-abandons-after-n-no-progress-checks-test` (572, pins the view shape), `a-keep-policy-never-ends-a-pending-run-test` (603), `a-bare-abandon-uses-the-policy-reason-test` (614), `a-misbehaving-policy-is-kept-and-reported-test` (623, throw / out-of-contract / map-without-:abandon), `unknown-and-lease-link-policies-are-refused-at-create-state-test` (646), `reset-and-vm-preserve-the-link-policy-test` (657, incl. `:checks` reset), `a-policy-abandon-matches-abandon-s-retained-line-semantics-test` (676), `a-late-response-after-a-policy-abandon-is-skipped-test` (710), `a-completing-re-check-never-consults-the-policy-test` (752), `recheck-pending-steps-a-run-without-an-input-line-test` (778).

## Baselines and finals (sequential, solo, via mise)

| Lane | Baseline (before) | Final (after) |
|---|---|---|
| JVM `clojure -M:test` | 2254 tests / 183116 assertions, 0 failures | 2265 / 183197, 0 failures |
| Node `bb test:cljs` | 2163 / 49781, 0 failures | 2175 / 49865, 0 failures |
| Dart `bb test:cljd` | +2123, all passed | +2135, all passed |

Deltas = my 11 tests; the Node/Dart +1 test / few extra assertions are the cross-host peer tests the pre-Dart-build baseline Node run skipped (documented bb.edn skip behavior). No lane failure needed attribution — slice 4's in-flight tree was green at baseline and stayed green; my namespaces also proved green in a focused 8-namespace JVM run (136 tests, 0 failures). cljstyle and kondo clean on both files; all added lines pure ASCII and <= 80 cols; no diagnostics left; nothing staged or committed.

## Minimal readings noted

1. Brief item 8 says "tests in the existing yin.repl test files" while the design's file box says "New: test/yin/repl/link_policy_test.cljc" — I followed the brief and appended to the existing `require_test.cljc`, reusing its M5 pending/abandon harness instead of duplicating ~130 lines of module-minting helpers.
2. Design 3.5 "`:pending` gains the policy name and `:checks`" — implemented as per-entry `:policy`/`:checks` keys, keeping `:pending` a vector so ":manual ... No existing test changes" holds.
3. `:checks` reset-on-progress is implemented and unit-pinned only indirectly (drive-links progress flag); a direct mid-pending progress-reset test would need a contrived multi-stage content source and is not in design section 8's list.

Status: COMPLETE