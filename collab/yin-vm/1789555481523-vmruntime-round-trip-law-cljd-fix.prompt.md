Created-GMT: 2026-09-16 10:44:41 GMT
Created-Local: 2026-09-16 17:44:41 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: claude
Session-ID: 5fa94dd8-b9b5-4bfe-b255-efdb48d00bdf

# Task: Investigate and fix the ClojureDart-only semantic-bytecode round-trip law failure

Role: VM Runtime

Implementers:
- Model: claude-opus-5 | Assigned: 2026-09-16 17:44:41 +07 | Status: active | Rationale: subtle cross-host correctness bug touching AST codec + content-addressing, needs careful multi-step diagnosis before a fix, not a mechanical patch

## Context

`test/yin/vm_test.cljc`'s `semantic-bytecode-round-trip-law` deftest
(line 222) passes cleanly on the JVM (`clj -M:test -n yin.vm-test`: 0
failures, 172 assertions) but fails on ClojureDart. `bb test:cljd` just
ran to completion for the first time all session (a prior, unrelated bench
file bug was blocking it) and surfaced this as a real, reproducible
failure — not flakiness.

Reproduce it directly:
```
flutter test test/cljd-out/yin/vm-test_test.dart --plain-name \
  "semantic-bytecode-round-trip-law" --reporter expanded
```
(you may need `clj -M:cljd compile yin.vm-test` first if the compiled
`.dart` output is stale — check whether it already reflects the current
source before recompiling)

The failure: the corpus includes the literal value `[1 (2 3) #{4}]` (a
vector containing a list and a set) as a `:literal` node's `:value`. The
test does:
```clojure
(let [bc (vm/ast->semantic-bytecode ast)]
  (is (= ast (vm/semantic-bytecode->ast bc)) "map -> rows -> map")       ; PASSES
  (is (= bc (vm/ast->semantic-bytecode (vm/semantic-bytecode->ast bc)))
      "rows -> map -> rows"))                                            ; FAILS
```
The first assertion passes — the reconstructed AST is `=` to the original.
The second fails: re-summarizing the reconstructed AST produces a
*different* `dao.jing` content-address SHA-256 hash for the same literal
value than the original summarization did. Two structurally-`=` values are
hashing to different addresses — a real defect in a system whose whole
addressing model (`yin.vm.code-as-tuples.md` §4) depends on content
address being a pure function of value.

## Two concrete leads, already traced tonight — verify, don't assume either is the answer

1. `src/cljc/yin/vm.cljc:608-636`, `strip-reader-positions` — called on
   every `:data`/`:key` payload during `ast->semantic-bytecode`. Its
   sequential-but-not-vector branch (line 626) is
   `(apply list (map strip-reader-positions x))`. On
   `semantic-bytecode->ast`'s reconstruction side, `child`'s `case` (lines
   736-759) has **no explicit `:data`/`:key` branch** — it falls through to
   the bare `v` default, meaning the row's raw value is reused unchanged
   when rebuilding the AST. So on the *second* `ast->semantic-bytecode`
   pass, `strip-reader-positions` runs again on whatever
   `(apply list (map strip-reader-positions x))` produced the first time.
   Determine whether this is actually idempotent on ClojureDart specifically
   — does `apply`/`list`/`sequential?`/`seq?` classify the result of
   `(apply list ...)` identically to a genuine list literal on Dart, the
   same way they do on the JVM? If not, that's likely the defect, here or
   in how `dao.jing` addresses the result.

2. `src/cljc/dao/jing.cljc:71,140` — `order-normalize` also uses the same
   `(apply list (map order-normalize v))` pattern for `sequential?` values,
   as part of `dao.jing`'s canonical encoder. Tonight's earlier
   `dao.jing` fix (commit `0cafb2d`, "close metadata, set-tag, and record
   collisions") specifically addressed a list-vs-seq collision — read that
   commit and `docs/design/dao.jing.md`'s current Open Items section to
   understand exactly what it covers and what it doesn't. This failure may
   be a case that fix didn't reach (e.g. a list nested inside a vector
   nested inside a set-bearing structure, versus whatever the fix's own
   test corpus covered), or it may be a genuinely new, Dart-specific gap
   in the same encoder.

Do not assume either lead is *the* answer — trace the actual value through
both `ast->semantic-bytecode` and `dao.jing`'s hashing on the ClojureDart
host specifically (add temporary `print`/instrumentation if needed to see
the concrete type and content at each step, then remove it before your
final diff) until you can state exactly where the two passes diverge and
why it's Dart-specific (confirm it does NOT diverge on JVM/CLJS for the
same value, to be sure this isn't a semantic-bytecode round-trip bug that
happens to only get *exercised* by the Dart test corpus — i.e. also check
whether the JVM/CLJS corpus even contains this exact literal shape).

## Task

1. Diagnose the root cause precisely, with evidence (not speculation).
2. Fix it at the correct layer — likely `dao.jing`'s canonical encoder
   (if the gap is in canonical hashing) or `v2.cljc`'s
   `strip-reader-positions`/reconstruction (if the gap is in how the
   semantic-bytecode codec round-trips collection types) — not both
   speculatively. If the fix belongs in `dao.jing`, treat it with the same
   care as the `0cafb2d` fix: check `docs/design/dao.jing.md`'s stated
   contract and Open Items first, and update that doc's Open Items if your
   fix closes one.
3. Add a regression test at the layer you fix, covering the exact failure
   shape (`[1 (2 3) #{4}]` or an equivalent list-inside-vector-with-a-set
   case) so this doesn't silently regress. If you fix `dao.jing`, add it
   to `test/dao/jing_test.cljc`'s style; if you fix `v2.cljc`, extend
   `semantic-bytecode-corpus` in `test/yin/vm_test.cljc` (careful: that
   corpus is shared by several other deftests in that file — check they
   still pass with your addition, don't just add a case blindly).
4. Do not touch the second, separate failure
   (`yin.repl.core-test/a-failed-input-is-consumed-exactly-once`) — out
   of scope for this task, being investigated separately.
5. Do not touch anything outside what your diagnosis actually requires
   changing. Do not stage or commit.

## Verify

- `clj -M:test -n yin.vm-test -n dao.jing-test` (or whichever test
  namespace you touched) on the JVM: must stay green, including your new
  regression case.
- `bb test:cljd`: the round-trip law test must now pass. Report the full
  pass/fail count, not just this one test — confirm you haven't newly
  broken anything else (`yin.repl.core-test/a-failed-input-is-consumed-
  exactly-once` is expected to still fail, unrelated; anything else newly
  failing is your own regression to fix before reporting done).
- `clj -M:kondo --lint` on every file you touched.

## Deliverable

Report back: the precise root cause with evidence (the actual diverging
values/types at each step, on which host), the exact diff, the new
regression test, and the exact verification commands with output. If you
determine after real investigation that this is actually two separate
bugs, or that the true fix requires touching something outside this
brief's anticipated scope, say so explicitly and explain rather than
forcing a fix that doesn't match the real cause.
