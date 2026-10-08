Created-GMT: 2026-09-15 21:55:00 GMT
Created-Local: 2026-09-16 04:55:00 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: glm
Session-ID: 541aa172-7582-4698-aee7-0ca0434052b3
# Task: dao.jing canonical encoder — fold in Architect's nonblocking r1 findings
Role: Storage & Indexing
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-16 04:25:00 +07 | Status: complete | Rationale: same implementer, resumed session, follow-up fixes to own work

All three requested fold-ins applied to the uncommitted working-tree diff
(`src/cljc/dao/jing.cljc`, `test/dao/jing_test.cljc`; cumulative vs HEAD now
+218/−20). Findings 1, 3, 4 untouched per the prompt (1 and 3 are the
orchestrator's separate units/commits; 4 is a commit-message note). Nothing
staged or committed.

## Diff per finding

**Finding 2 (comparators sort by canonical-print).** Both comparators in
`order-normalize` changed from `#(compare (pr-str %1) (pr-str %2))` to
`#(compare (canonical-print %1) (canonical-print %2))` — map key sort
(`sorted-map-by`, was line ~96) and set element sort (`sorted-set-by`, was
line ~102). A sort tie now coincides exactly with final byte identity:
canonical-print is metadata-aware and ignores ambient `*print-length*`,
`*print-meta*`, `*print-namespace-maps*` for every byte it produces. The
companion comments and the order-normalize docstring ("sort by
canonically-printed key/element") were updated to match. Forward reference:
resolved by moving `canonical-print` ABOVE `order-normalize` in the file
(the safe direction for every host's analyzer — the dependency now points
backward; no `declare` needed). ClojureDart compile of the new order
confirmed ("Bravissimo! 👏", exit 0).

**Finding 5 (single-line message).** The record-rejection ex-info message is
now one line: `"dao.jing does not address records: hosts print them
differently, so their addresses would collide across hosts"` — no embedded
newlines or indentation runs.

**Finding 6 (reader-position test).** Added to
`content-hash-distinguishes-metadata`, a third testing block:
`(= (jing/content-hash (with-meta [1 2] {:line 7, :column 11}))
    (jing/content-hash [1 2]))` — the docstring claim now has its own
assertion.

## Test results

- `clojure -M:test -n dao.jing-test` → **Ran 38 tests containing 227
  assertions. 0 failures, 0 errors.** (226 before; +1 from Finding 6.)
- `bb test:clj` (full JVM suite) → **Ran 1453 tests containing 165971
  assertions. 0 failures, 0 errors.** (Test count grew vs r1's 1447/165878 —
  another session's semantic-bytecode tests landed in the tree mid-session;
  not from this change.)
- `bb test:cljs` → 1358 tests / 35490 assertions, 2 failures — the same two
  pre-existing `yin.repl.core-test` `(/ 1 0)`→`##Inf` host failures as
  r1; `dao.jing-test` itself clean.
- CLJD: `clojure -M:cljd compile dao.jing-test` → clean (above), and a full
  scoped `clojure -M:cljd test dao.jing-test` → **all 38 dao.jing-test
  deftests passed on the Dart VM** (38 progress lines, zero failure-counter
  bumps, zero `[E]` flags; global tally's failures remain the pre-existing
  stale Sep-14 cljd-out load errors, identical to r1's runs).

## Finding 2 before/after probe (JVM)

The Architect's reproduction, constructed as required:

``` clojure
(def m {[1 2] :a, [1 3] :b})
(= (jing/content-hash m)
   (binding [*print-length* 1] (jing/content-hash m)))
```

- **Before** (pr-str comparators): **false** — under `*print-length*` 1 both
  vector keys print `[1 ...]`, the comparator ties inside `sorted-map-by`,
  one entry is silently dropped, and the hash changes.
- **After** (canonical-print comparators): **true** — and the two-key map
  still hashes differently from `{[1 2] :a}` (no drop). A set probe
  (`#{[1 2] [1 3] [1 4]}` under `*print-length*` 1) is likewise stable now.

Additional after-only probes (the "before" halves were not re-run; the
before behavior follows from pr-str under those bindings):

- `*print-meta*` reorder: `{(with-meta [1 2] {:m 1}) :x, [1 3] :y}` hashes
  identically bound and unbound. (Note: a map cannot hold two keys that are
  `=`-equal-but-meta-distinct — `=` ignores metadata — so the constructible
  reorder case is a metadata-bearing key beside a distinct key; before the
  fix, pr-str would flip their order by first character `^` vs `[`.)
- `*print-readably*`: **residual, unchanged, disclosed.**
  `{"a" 1, (symbol "a") 2}` still hashes differently under
  `*print-readably*` false, because canonical-print prints SCALARS through
  pr-str by design. The comparator fix eliminates the silent entry-drop (a
  sort tie now implies byte identity), but ambient print bindings still
  reach scalar bytes. The Architect's optional correction — binding the
  print vars to defaults at the `content-hash` entry — was NOT applied: it
  is marked optional, is outside this prompt's requested scope, and needs
  its own cross-host availability check (`*print-readably*` et al. on
  cljs/cljd). Recommend it rides with Finding 1's fail-closed unit, which
  is already opened for "before any producer emits collection metadata".

## Hosts verified

`:clj` (jing namespace + full suite), `:cljs` (suite; jing clean; 2 known
pre-existing failures), `:cljd` (compile + full jing namespace on the Dart
VM). No host-specific constructs introduced: the diff uses only
`sorted-map-by`/`sorted-set-by` with a plain fn comparator and the already
portable `canonical-print`.
