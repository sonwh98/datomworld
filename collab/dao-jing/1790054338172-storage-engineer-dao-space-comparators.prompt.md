Created-GMT: 2026-09-22 05:18:58 GMT
Created-Local: 2026-09-22 12:18:58 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 51cbe9c5-542b-4efc-99ce-082ca4a1beb8 (resumed: your J0-J2 DaoJing CBOR session; it already knows dao.jing.cbor's numeric internals)
# Task: dao-space-comparators — step 3 of the DaoJing CBOR epic: wire portable numeric ordering into dao.space
Role: DaoSpace & DaoJing Storage Engineer
Implementers:
- Model: claude-opus-5 | Assigned: 2026-09-22 12:18:58 +07 | Status: active | Rationale: same engineer who built dao.jing.cbor's numeric carriers and portable operations (J0-J2); both dao.space owner gates are now cleared and this is the storage-adjacent consumer wiring the design calls for next

Work ONLY in /Users/sto/workspace/worktree-dao-space-comparators (branch
dao-space-comparators, HEAD 55f435ec; your launch directory). Do NOT stage,
commit, merge or push. This is flagged in the design doc as "the widest
blast radius in this plan" and needs full test coverage and an independent
review before it is committed; be thorough rather than fast.

## Read first, in full

- docs/design/dao.jing.cbor.md sections "Numeric identity" (both owner
  rulings: query equality stays kind-strict, and the three-part min/max
  tie-break) and "Implementation sequence and validation" step 3 and its
  "Required test scenarios" bullet on Node/Dart decoded numeric carriers.
- src/cljc/dao/jing/cbor.cljc: `numeric?`, `exact`, `exact-key`, `num=`,
  `num-hash`, `num-compare`, `equiv`, `equiv-hash`, the `Rational`/`Decimal`
  deftypes' own `-equiv`/`hash` methods, and `encode`. Do not assume; read
  the actual code.
- src/cljc/dao/space/index.cljc: `type-rank`, `compare-vals`, `cmp-field`,
  the four `*-cmp` functions.
- src/cljc/dao/space/query.cljc: the `builtins` map (`=` `not=` `< > <= >=`
  `+ - * / quot rem mod inc dec abs` `min` `max`), the `aggregate-fns` map's
  `min`/`max`, and `unify`/`unify-slots` (two `=` call sites inside `unify`).

## The two already-known gaps (verify, do not assume; there are likely more)

1. `dao.space.index/type-rank` buckets values by `(number? x)`. On the JVM,
   `Rational` and `Decimal` are plain deftypes, not `Number` subclasses, so
   they are almost certainly NOT `number?` today, meaning `compare-vals`
   sorts them entirely away from native numbers instead of ordering them
   by value among numbers, contradicting "compare-vals's numeric arm must
   use the portable compare directly, with no string-ordering fallback for
   numbers." Confirm this with a REPL check or a failing test first, then
   fix `type-rank` to use `dao.jing.cbor/numeric?` instead of `number?`.
2. Host `=` conflates `0.0` and `-0.0` (`(= 0.0 -0.0)` is `true` on the
   JVM), but the design's content addressing treats zero sign as
   significant, and the owner ruled `=` in `dao.space.query` must match
   content addressing's full kind-strictness: kind, decimal scale, AND
   zero sign all matter. So `dao.jing.cbor/equiv` (which uses the
   kind-LOOSE `num=`) is the wrong function for `=`/`not=`/unification, and
   plain host `=` is also wrong (it misses the zero-sign case, and cannot
   be trusted for any other float64/decimal/rational cross-representation
   detail either). Do not assume host `=` is otherwise correct for
   decimal-scale or ratio cases; test every case explicitly.

## Do

1. **In `dao.jing.cbor.cljc` (additive only; do not change `equiv`,
   `num=`, `num-hash`, `num-compare`, or any existing exported function's
   behavior — those still serve encode/decode duplicate-collapse
   detection, which wants the loose definition):**
   - Add `content=`: portable, KIND-STRICT decoded equality. Two numeric
     values are `content=` only when they have the same numeric kind
     (integer, float64, decimal, rational) AND, within kind, the same
     scale (decimal) and the same zero sign (float64) AND the same exact
     value, with host integer width NOT significant (a native Long 5 and
     a BigInt/BigInteger 5, or their equivalents on Node/Dart, are
     `content=`). For non-numeric values, recurse the same way `equiv`
     does (byte strings by content, identifiers by namespace/name fields,
     sequential values elementwise, maps/sets by members, metadata
     ignored) but using `content=` at every nested leaf, not `equiv`'s
     loose `num=`. Reuse `equiv`'s structure/helpers where you can without
     duplicating logic wholesale; factor a shared traversal if that is
     cleaner, but do not change `equiv`'s own behavior.
   - Add `content-hash`: a hash consistent with `content=` (equal under
     `content=` implies equal hash), the kind-strict counterpart to
     `equiv-hash`, needed so `content=`-based collections/sets (used by
     any future hashed lookup) are internally consistent. Same
     host-value-only caveat as `exact-key`/`num-hash`: never compare hash
     values across hosts.
   - You will need a canonical way to compare zero sign and decimal scale
     directly rather than through `exact` (which discards both). Look at
     `float-fields`, `decimal-exponent`, `decimal-mantissa`, and the
     existing `Rational`/`Decimal` deftype `-equiv` methods, which already
     compare exponent/mantissa or numerator/denominator exactly — decide
     whether their existing behavior can be reused directly inside
     `content=`'s numeric branch, or needs a native-number counterpart
     (native Long vs BigInt/BigInteger of the same value; native double
     zero-sign via bit inspection, e.g. `float-fields`).
   - Write focused tests in a new `test/dao/jing/cbor_content_equality_test.cljc`
     (do not touch the frozen J0-J3 files) proving: `(content= 1 1.0)` is
     false; `(content= (decimal -1 10) (decimal -2 100))` is false (`1.0`
     vs `1.00`); `(content= 0.0 -0.0)` (or the JS/Dart equivalent) is
     false; `(content= 1 1N)` (or the big-integer-width equivalent on
     each host) is true; `(content= (ratio 1 1) 1)` is false (rational
     kind never equals integer kind, even at denominator 1); two canonical
     NaNs are... decide and state whether NaN should be `content=` to
     itself (content addressing gives every canonical NaN the same bytes,
     so the design's own logic suggests yes; state your reasoning either
     way); collections containing carriers recurse correctly (a vector
     `[1]` is not `content=` to `[1.0]`; a map with a decimal-1.0 value is
     not `content=` to the same map with decimal-1.00). Run this on JVM
     and Node at minimum (Dart if your lane access allows it; otherwise
     say so).

2. **In `dao.space.index.cljc`:**
   - Fix `type-rank` to recognize every Jing numeric kind as one bucket
     (use `dao.jing.cbor/numeric?`, not `number?`), so decimals, rationals,
     and float64-on-JS carriers sort among numbers by value, not away from
     them.
   - In `compare-vals`, when both values are in the numeric bucket, call
     `dao.jing.cbor/num-compare` directly (portable numeric order,
     `-1`/`0`/`1`) instead of raw host `compare` with a string-ordering
     catch fallback — the design explicitly forbids the string fallback
     for numbers. Keep the existing try/catch string-fallback behavior for
     the OTHER type-rank buckets (it is not this section's concern to
     change non-numeric heterogeneous comparison).
   - The generic `dao.data.btree` comparator needs no change (confirm by
     reading it, do not edit it).
   - Add or extend tests in `test/dao/space/index_test.cljc` (or a new
     file if that one does not exist or is unsuitable — check first)
     proving: `compare-vals` orders `1`, `1.0`, `1.5`, decimals, and
     rationals correctly among each other by value on every host you can
     run; `(compare-vals 1 1.0)` is `0` (ties are still ties for ordering,
     per the design: this is unchanged, only `=` changed); EAVT/AEVT/AVET/
     VAET datom ordering with mixed-kind values in the v-position is
     stable and by value.

3. **In `dao.space.query.cljc`:**
   - Rebind `'=` and `'not=` in `builtins` to new functions built on
     `dao.jing.cbor/content=` (and its negation), not host `=`/`not=`.
   - Rebind `'< '> '<= '>=` to new functions built on
     `dao.jing.cbor/num-compare` so they order correctly across native and
     carrier operands in either order; confirm the behavior for a
     non-numeric operand is still a clear failure (do not silently coerce
     or exempt non-numbers; keep whatever the current error behavior is
     for calling a numeric comparison builtin on a non-number, or make it
     an explicit `ex-info` if today's is an opaque host exception).
   - Rebind `'min`/`'max` in `builtins`, and the `aggregate-fns` variadic
     `min`/`max`, to the fully-specified three-part tie-break: (1) an
     exact operand (integer/decimal/rational) always beats a float64
     operand; (2) otherwise, whichever operand has the shorter canonical
     CBOR encoding (use `dao.jing.cbor/encode` to measure byte length, or
     add a lighter-weight length function if `encode`'s allocation cost
     matters for a hot aggregate loop -- your call, state which you chose
     and why); (3) if the encoded lengths are equal too, whichever sorts
     first by canonical CBOR bytes (lexicographic on the encoded hex or
     raw bytes). The binary `min`/`max` and the variadic aggregate reducer
     must agree: implement the tie-break once, share it.
   - Route `unify`'s two `(= sym val)`/`(= (get binding sym) val)` call
     sites through `content=` too, so pattern-binding consistency is
     kind-strict, matching the design's "a datom value containing a
     carrier unifies only with a value of the same kind."
   - Arithmetic builtins (`+ - * / quot rem mod inc dec abs`) must reject a
     carrier operand loudly rather than compute garbage or fail with an
     opaque host exception. Test what actually happens today when you pass
     a `Decimal`/`Rational`/JS `Float64` carrier to each; if the failure is
     already a clear, loud error, leave it and say so in the report; if it
     silently computes something wrong or fails with a confusing message,
     add an explicit guard (`ex-info`, naming the operator and the carrier
     type) before the host operation runs.
   - Add tests in a new `test/dao/space/query_numeric_test.cljc` covering
     every point in the design's "Required test scenarios" bullet on
     numeric carriers: native/carrier operands both ways, mixed kinds,
     exact large values, `=`/`not=`/ordering, signed zeros, infinities,
     NaN, preserved kind on re-encoding; collection values containing
     carriers unify (or fail to) structurally per the ruling; the
     arithmetic-rejects-loudly cases; `[e a 1 t m]` vs `[e a 1.0 t m]`
     collapsing to one covered-index entry (test against
     `dao.space.index`) while a query's `=` does not unify them.

## Never

Never touch the frozen J0-J3 CBOR fixture corpus, README, generator, or
errata. Never change `dao.data.btree`'s generic comparator. Never change
`equiv`/`num=`/`num-hash`/`num-compare`/`equiv-hash`'s existing behavior --
they are used elsewhere (encode/decode duplicate and collapse detection)
and must keep their current, loose semantics. If you find a use of `equiv`
inside `dao.space.*` that you believe should also become `content=`, name
it in the report rather than changing it silently.

## Environment

Default PATH gives Java 21 and the mise clojure and bb. Focused JVM runs:
`clojure -M:test -n dao.jing.cbor-content-equality-test -n dao.space.index-test -n dao.space.query-numeric-test`
(use the namespaces your files actually define). If you need kondo,
cljstyle, the Java 17 lane, or the CLJD lane and they are denied, say
exactly what was denied and stop; do not retry, do not use
`--dangerously-skip-permissions`. Keep files pure ASCII, no em dashes,
cljstyle-style Clojure (blank lines between top-level forms), docstrings
that say what and why.

## Final report

Begin exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 51cbe9c5-542b-4efc-99ce-082ca4a1beb8
Then: files created/changed; what the two known gaps actually turned out to
be (confirm or correct my hypotheses with what you found); every other gap
you found and fixed; the content=/content-hash design and what it reuses
from equiv; the min/max tie-break implementation and your encode-cost
choice; what you ran with exact counts; what you could not run; every
deviation. Facts only; promise nothing.
