Created-GMT: 2026-09-15 22:56:40 GMT
Created-Local: 2026-09-16 05:56:40 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: deepseek
Session-ID: a73f995f-2ad1-4329-9326-7f900b81134f
# Task: Routine review of ast->semantic-bytecode r5 (narrow fix, not full re-audit)
Role: Routine Review
Implementers:
- Model: deepseek-v4-pro | Assigned: 2026-09-16 05:56:40 +07 | Status: complete | Rationale: cross-family confirmation pass on a narrow diff, after 4 adversarial rounds already validated the core logic

Note: this session ran in `--permission-mode plan`, which has no write
access outside the plan file, so it could not write this findings file
itself. The orchestrator transcribed the verdict and findings from the
session's stdout log
(`collab/1789530200000-review-ast-semantic-bytecode-r5.deepseek-v4-pro.stdout.log`)
below.

## Verdict

**PASS — safe to proceed to Architect sign-off.**

## Item 1 — the `(when-not (and (empty? m') (nil? (meta m'))) m')` fix — correct

Traced all three cases through `strip-reader-positions`, independently
confirmed the `dissoc` semantics the fix hinges on via `clojure -e`:

- **Genuinely empty, unmetadata'd map** (`(with-meta x {})`) → `m' = {}`,
  both predicates true → collapses to `nil`. Unchanged.
- **Non-empty map** (`(with-meta x {:a 1})`) → `(empty? {:a 1})` false →
  kept.
- **Empty map carrying metadata**
  (`(with-meta x (with-meta {} {:meaning 1}))`) → `dissoc` preserves the
  `{:meaning 1}` meta on absent keys (returns `this`), so `m'` is `{}`
  with meta `{:meaning 1}`; `(empty? {})` true but `(nil? {:meaning 1})`
  false → kept.

No missed case. `m'` is always a map, so the predicates are safe. A
metadata map whose own metadata is pure reader positions
(`(with-meta {} {:line 3})`) still collapses correctly — stripped before
the guard. Address is stable (`dao.jing` drops empty-collection metadata
when hashing), and distinct `{:meaning 1}`/`{:meaning 2}` still throw a
loud `same-meta?` collision, not a silent merge.

## Item 2 — docstring correction — accurate

The set branch is `(same-meta? e (some #(when (= e %) %) b))`. `some`
picks the first `=`-equal element in `b`'s traversal order, so with a
custom comparator admitting metadata-distinct `=`-equal elements, the
picked element may match (silently accepted) or mismatch (throws). The
new wording — "silent and order-dependent, not reliably fail-closed... a
metadata mismatch may throw or may be silently accepted" — describes
exactly that.

## Item 3 — regression test — genuine

`v2_test.cljc:449-455` builds
`(lit (with-meta [] (with-meta {} {:meaning 1})))` and asserts
`{:meaning 1}` at `(meta (meta v))` AND after the full round trip via
`semantic-bytecode->ast`. The `:literal` `:value` slot flows through
`strip-reader-positions`, so the first assertion fails under the old
`not-empty` code (matches the implementer's r4 reproduction). It
exercises the fixed case directly, not just the count.

## Test run

`clojure -M:test -n yin.vm-test` → 23 tests, 172 assertions, 0
failures, 0 errors.
