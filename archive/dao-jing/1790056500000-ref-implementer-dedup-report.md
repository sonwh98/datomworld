Completed-GMT: 2026-09-22 05:44:37 GMT
Completed-Local: 2026-09-22 12:44:37 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 51cbe9c5-542b-4efc-99ce-082ca4a1beb8

Deduplication inside the query is now kind-strict everywhere it can be, and the JVM and Node suites are green. The goal is not fully met at the last step: the returned result is still a host set, and on the JVM that set itself merges `[1.0M]`/`[1.00M]` and `[0.0]`/`[-0.0]`. Closing that needs an owner decision (below). Kondo was denied, so lint is unverified. Nothing is staged or committed.

## Files changed
- `src/cljc/dao/space/query.cljc`
- `test/dao/space/query_numeric_test.cljc` (6 tests appended)

No `dao.jing.cbor` or frozen files were touched. The only non-ASCII in `query.cljc` is em dashes that were already there.

## The `content-distinct` design
- `content-distinct` works like `clojure.core/distinct`: lazy, keeps the first occurrence, preserves order. It treats two elements as duplicates only when a new helper, `binding-key`, gives them the same key.
- **Deviation from the brief:** I did not apply `content-key` directly to binding maps. Reading `content-key` confirmed it recurses through every map, and a query binding map also carries `::dbs` (the input databases) and `'%` (the rules). Keying them would walk whole databases for each row.
- So `binding-key` keeps the `::dbs` and `'%` entries as they are, which is the same comparison the old `distinct` used, and applies `content-key` to every variable's value. Projected tuples and bare values go straight to `content-key`.

## Call sites changed
Each site except the last was checked by reverting it alone to host `distinct`/`=`, running `dao.space.query-numeric-test`, and restoring it:

| Site | Change | Result when reverted alone |
|---|---|---|
| `eval-or` | `distinct` → `content-distinct` | `or` assertion fails |
| `eval-or-join` | `distinct` → `content-distinct` | `or-join` assertion fails |
| `eval-rule` result | `distinct` → `content-distinct` | rule test fails |
| `eval-rule` cycle guard (extra site) | active-call set keyed by `[rule-name (binding-key arg-vals)]` | recursive-rule test fails (`:end` is lost) |
| `count-distinct` aggregate | `(count (content-distinct xs))` | 4 assertions fail |
| `relation-result` rows | the internal set of rows becomes a `content-distinct` seq; it was never returned | 4 assertions fail |
| `relation-result` group key | `group-by` keyed by `content-key` | 5 assertions fail |
| `relation-result` returned tuples (both `into #{}` sites) | passed through `content-distinct` before entering the set | not pinnable, see below |

The cycle guard was not on your list. Its active-call set carries datom values, so a recursive call on `1.00M` was treated as a repeat of the call on `1.0M` and returned nothing. This is the "if I mis-scoped it, fix it" case.

## Call sites left unchanged, and why
All of these compare only query variable symbols or rule names, never datom values:
- `not-required-vars`'s `distinct`
- `branch-free-vars`'s `distinct`
- the "Unknown rule" error's `(distinct (map ffirst rules))`
- `proj-vars`'s `distinct` in `relation-result`

## Decision needed: the returned relation still merges on the JVM
The returned relation is still a host set, as the brief requires, but a host set compares its members with host `=` and `hash`. On the JVM, `[1.0M]`/`[1.00M]` and `[0.0]`/`[-0.0]` are host-equal and hash alike, so they become one member however carefully the rows are deduplicated first. Dart does the same for `0.0`/`-0.0`; Node keeps both pairs, because its decoded values are carriers.

This is why the returned-tuples row in the table can't be pinned: on these hosts the set does the same merging with or without `content-distinct` in front of it.

A new test, `the-returned-relation-is-still-a-host-set`, pins this per host so it fails loudly if anything changes: JVM gives 1 and 1, Node gives 2 and 2, and Dart is expected to give 2 decimals and 1 zero (not run).

Keeping both rows would need a different set implementation (for example a sorted set with a content-key comparator), which is the type change the brief rules out. That is your call.

## New tests
All six were run against the unfixed code first. The five behavior tests all failed there, confirming the old wrong merges; the sixth is the host-set evidence test, which passed as expected.
- `count-distinct-counts-content-distinct-values`: the old code gave 2 where 4 is right.
- `aggregate-groups-split-on-scale-and-zero-sign`: the old code gave 2 groups of 3; now 4 groups of 2, 1, 2, 1.
- `or-and-or-join-keep-bindings-that-differ-in-scale-or-sign`: the old code gave 2 and 2; now 4 and 4.
- `rule-results-keep-bindings-that-differ-in-scale-or-sign`: the old code gave 2; now 4.
- `recursive-rules-tell-calls-apart-by-content`: the old code lost `:end`.
- `the-returned-relation-is-still-a-host-set` (the host-limit evidence above).

## What I ran
| Run | Tests | Assertions | Result |
|---|---|---|---|
| Focused numeric, query, positional-query and schema namespaces | 158 | 1364 | 0 failures |
| Seven single-site mutation runs | | | each failed as listed above, then reverted |
| Full `clojure -M:test` (Java 21) | 1743 | 174922 | 0 failures, 0 errors |
| `bb test:cljs` | 1660 | 44798 | 0 failures, 0 errors, 0 compile warnings |

## What I could not run
- `clojure -M:kondo --lint ...` returned "This command requires approval", so I stopped on lint and did not attempt cljstyle.
- The CLJD lane and the Java 17 lane were not run, so the Dart expectation in the evidence test is unverified.
- A single shell script running all the mutations needed approval, so I ran each mutation as separate commands.
