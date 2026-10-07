The build is done and green on the JVM and Node; Dart, kondo and cljstyle could not be run (all three were denied). Nothing is staged or committed.

Completed-GMT: 2026-09-22 05:32:33 GMT
Completed-Local: 2026-09-22 12:32:33 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 51cbe9c5-542b-4efc-99ce-082ca4a1beb8

## Files
- **Changed** `src/cljc/dao/jing/cbor.cljc`. Additive only: `numeric-kind`, `content-key`, `content=`, `content-hash`, `encoded-compare`, and a private `numeric-content-key`. `equiv`, `num=`, `num-hash`, `num-compare` and `equiv-hash` are untouched; a test pins that `equiv` stays loose.
- **Changed** `src/cljc/dao/space/index.cljc`: `type-rank`, a new private `compare-numbers`, and `compare-vals`.
- **Changed** `src/cljc/dao/space/query.cljc`:
  - The builtins (`=`, `not=`, the ordering builtins, `min`, `max`, and the arithmetic builtins).
  - The `min`, `max`, `sum` and `avg` aggregates, and `unify`.
  - `slots-match?`, `select-by-index`, `datoms` and `select-probe`.
  - `current-state-seq`, plus new private helpers.
- **New** `test/dao/jing/cbor_content_equality_test.cljc`, `test/dao/space/index_numeric_test.cljc`, `test/dao/space/query_numeric_test.cljc`.
- **Not edited:** `dao.data.btree`, the frozen J0-J3 files, and the existing test files. I read the btree's generic comparator; it just delegates to the comparator it is given, so it needs no change.
- My additions are pure ASCII. The em dashes and Greek letters in the two dao.space files were already there.

## The two known gaps, as found (JVM probe first, then tests)
1. **`type-rank`: partly right.**
   - On the JVM, `BigDecimal` and `clojure.lang.Ratio` are Java `Number`s and already sorted with numbers. The Jing `Rational` carrier (denominator 1) is not a `Number`, so `(compare-vals 1 (ratio 1 1))` was -1 when it should be 0.
   - On Node the `Float64`, `Decimal` and `Rational` carriers are not `number?` either. The Node tests pass after the fix; I did not probe Node before it.
   - The larger JVM problem: Clojure's own `compare` goes through double across kinds. Both `(compare-vals 0.1 1/10)` and `2^53+1` against the double `2^53` returned 0 (both should be 1), and NaN compared 0 against everything.
   - Fixed: `type-rank` uses `cbor/numeric?`, and the numeric bucket uses `cbor/num-compare`. Host `compare` is kept only where its answer is identical:
     - two JVM `Long`s;
     - two non-NaN doubles on the JVM or Node;
     - two ints on Dart (Dart's double compare orders -0.0 below 0.0, so doubles take the slow path there).
2. **Host `=` misses zero sign: confirmed, and worse than stated.** On the JVM `(= 0.0 -0.0)` and `(= 1.0M 1.00M)` are both true and hash equal, so host sets and maps merge them. `(= (ratio 1 1) 1)` was already false. Fixed by routing `=`, `not=`, `unify` and `slots-match?` through `content=`.

## Other gaps found and fixed
- **`current-state-seq` merged kind-distinct facts.** A fact about `1.00M` superseded one about `1.0M`, and retracting `1.00M` removed `1.0M` (both probed). Facts are now keyed by `cbor/content-key`, so they stay distinct.
- **Index range scans missed rows.** With value ordering, `1` and `1.0` tie and interleave by entity. The `take-while (= ...)` scans in `select-by-index` stopped at the first row of the other kind.
  - I proved this with a mutation: restoring host `=` made `[?e :n/v 1]` return `#{}` instead of `#{3 5}`, and 6 assertions failed. Reverted.
  - Scans now continue while `compare-vals` ties, then match with `content=`.
- **Bound collection values missed on Node.** A vector holding `1` and one holding BigInt `1` are `content=`, but the index orders collections by host `compare` with a string fallback, which disagrees. The same can happen on the JVM with map values.
  - `select-by-index` now range-scans only scalar bound values. Any other bound value is routed by the remaining slots, and every bound slot is then filtered by `content=` inside the function.
- **Arithmetic did not refuse carriers loudly.** On the JVM a `Rational` carrier gave an opaque `ClassCastException`, and `+` would also accept native `BigDecimal`. On Node the carriers (`Float64`, `Decimal`, `Rational`) and BigInt would hit JavaScript `+` (string concatenation or a TypeError); I reasoned this, I did not probe it.
  - All eleven arithmetic builtins and the `sum`/`avg` aggregates now throw an `ex-info` naming the operator and the carrier type. The operands refused are those `numeric?` but not host `number?`.
  - Host numbers still compute, including JVM `BigDecimal` and `Ratio`.
- **The ordering builtins now refuse non-numbers explicitly** ("requires numeric operands"). On the JVM this replaces a `ClassCastException`; on Node, `<` over strings used to work silently and now throws.

## `content=` / `content-hash` design
`content-key` builds a host value whose host `=` and `hash` agree with kind-strict content identity. Then `content=` is `(or (identical? a b) (= key-a key-b))` and `content-hash` is `(hash key)`.

It follows `equiv`'s traversal, reusing its `identifier-key`, `sequential-value?`, `bytes-hex`, `float-fields` and `exact`:
- byte strings compare by content;
- identifiers compare by field;
- lists equal vectors (ruling A6);
- maps and sets compare by members;
- metadata is ignored.

Numbers are keyed by kind first, then:
- integers by value (integer width is not significant);
- float64 by exact bits (so the zero sign counts);
- decimals by exponent and mantissa (so scale counts);
- rationals by reduced numerator and denominator.

I chose a key rather than a pairwise function because it gives hash consistency for free and lets host collections be keyed kind-strictly (`current-state-seq` uses this).

**NaN is `content=` to NaN:** every NaN encodes to the same canonical bytes, so content addressing gives them one identity. A cross-check test asserts `content=` holds exactly when the canonical bytes match, over every pair of a 32-value sample. `num-hash` and `equiv-hash` are unchanged.

## `min` / `max` tie-break
- One binary rule each (`portable-min`, `portable-max`). The builtin folds it variadically and the aggregates reduce with the same function, so they always agree.
- On a numeric tie (`num-compare` is 0):
  - content-identical operands return the first;
  - otherwise an exact operand beats a float64 one;
  - otherwise the shorter canonical encoding wins;
  - otherwise the lower canonical bytes win.
- **Encoding cost:** I used `encode` via the new `cbor/encoded-compare`, and it runs only after numeric order has already tied. An ordinary `min`/`max` fold never encodes, so I did not add a separate length function.
- Tests check every case in both argument orders, and that the aggregate's result matches the builtin fold in both row orders.

## What I ran
| Run | Tests | Assertions | Result |
|---|---|---|---|
| Focused JVM (the three new namespaces) | 26 | 2358 | 0 failures, 0 errors |
| JVM, new plus existing dao.space namespaces | 245 | 3438 | 0 failures, 0 errors |
| Full `clojure -M:test`, Java 21 (run twice, second after the last source change) | 1737 | 174900 | 0 failures, 0 errors |
| `bb test:cljs`, final (the three new namespaces ran) | 1654 | 44781 | 0 failures, 0 errors, 0 compile warnings |

The first Node run had 1 failure, the collection-value unification gap above; it was fixed before the final run. The temporary JVM probe namespace used to confirm the gaps is deleted.

## What I could not run
- `clojure -M:kondo --lint ...`, `cljstyle check ...` and `clojure -M:cljd test ...` each returned "This command requires approval". I stopped there, so lint and Dart are unverified.
- The shared `.cljc` changes do carry `:cljd` branches (`dart/is?` in `compare-numbers`, `.-runtimeType` in the query's type label), and the new test files have Dart imports. None of it has been compiled for Dart.
- I also could not run `git diff` (approval required).
- I did not run the Java 17 lane.

## Deviations and residual items for review
- **Design wording.** The design text says `=`/`not=`/unification stay "host-native and kind-strict"; I bound them to `cbor/content=` as the brief directed. Host `=` fails the ruling's own intent on the JVM (zero sign, decimal scale). Owner check needed.
- **New public `cbor` functions** beyond those requested: `numeric-kind`, `content-key` and `encoded-compare`.
- **New test file name.** The index tests are in a new `index_numeric_test.cljc` rather than an extended `index_test.cljc`.
- **Not changed, still host-equality-based on the JVM:**
  - `q` result relations are host sets, so result rows `[0.0]`/`[-0.0]` or `[1.0M]`/`[1.00M]` still merge.
  - `group-by` for aggregates, `count-distinct`, and the `distinct` in or-join also still dedupe by host `=`.

  Fixing these would change the result value's type. I found no use of `equiv` inside `dao.space.*`.
- **Unsafe JavaScript integers.** On Node, comparing one against a carrier now throws `:unsupported-value` from `num-compare`. Two host numbers still take the host-compare fast path.
- **Slower paths.**
  - Double comparisons on Dart, and any comparison involving a carrier, go through `num-compare`'s exact big-integer arithmetic.
  - Bound non-scalar slot values now scan by the other slots instead of an index range.
