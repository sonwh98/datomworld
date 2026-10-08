Completed-GMT: 2026-10-05 21:50:47 GMT
Completed-Local: 2026-10-06 04:50:47 +0700
Coding-Agent: claude (opus-5-5)
# Python C3 slice S2: literals and boundary integration

Status: done and green on the focused JVM namespaces. I made no git writes.
I did not run the Node or Dart lanes (the brief says the orchestrator runs
`bb test:changed` at landing). cljstyle was BLOCKED: both `cljstyle fix` and
`cljstyle check` need a permission this session does not have. I checked
line lengths and ASCII by hand instead (details below). I ran
`bb gen:python-antlr` once, because this worktree had no generated parser.

## Changed files

- `src/cljc/yang/python/antlr/lower.cljc`
  - Deleted `max-exact-int`, `parse-radix` and the "integer literal beyond
    2^53" refusal.
  - New `int-digits` checks Python's integer token grammar and returns
    `[radix digits]` with the prefix and underscores removed.
  - New `int-literal` converts the token through the public
    `yin.vm.integer/integer-module`. Each literal gets its own instance
    with `::max-bits` = 4 x the digit count and `::max-digits` = the
    budget. It does `parse` in the token's radix, `compare`s against
    2^53-1, and `format`s in 16. The result is `(u/lit n)` up to 2^53-1,
    else `(py/int-lit "<hex>")`.
  - `escape-value` is the small native accumulator, now used only for
    string escapes. The code-point bounds are unchanged (`hex-value` still
    refuses anything over 0x10FFFF).
  - `parse-number` now takes ctx and returns the node.
  - New `digit-budget` reads `:yang.python.antlr/max-digits` from the
    packet into ctx `:max-digits`. A value that is not a positive integer
    throws a non-diagnostic ex-info, so the stage rethrows it.
- `src/cljc/yang/python/antlr/prelude.cljc`
  - Added the one new definition, `[py/int-lit (fn [s] (py/int-result
    (integer/parse s 16)))]`, right after `py/int-refusal?`.
  - Added `integer/parse` to `host-names`.
  - Added `min-integer-bits` (53) and `register-integer-module`. These are
    host-side Clojure, not prelude UAST, so they do not move any address.
    See the Admission section.
- `src/cljc/yang/python/antlr/render.cljc`: a new exact-integer arm in
  `repr`, placed before `(number? v)`. It uses the module's `integer?` and
  then its `format` in radix 10. The module instance (`exact`) has limits
  of 2^53-1 bits and digits, so the renderer has no digit limit.
- `docs/design/yang.antlr.md`
  - In the 8.5.4 bullet, "canonical decimal string" is now "canonical
    radix-16 string".
  - The S2 table row now ends with "Landed."
  - Nothing else changed.
- `test/yang/python/antlr/int_literal_test.cljc` is new and portable (14
  deftests); details below.
- `test/yang/python/antlr/e2e_test.clj`
  - The shared registrar now goes through `prelude/register-integer-module`.
  - `run-python` opens the lowering stage with the budget
    `(::integer/max-digits integer-limits)`.
  - `canonical-is-closed` puts the same budget on its packet.
- `test/yang/python/antlr/lower_test.clj`: added a pin that malformed
  underscores never reach the lowering through the parser.
- `test/yang/python/antlr/float_address_test.cljc`: re-minted the 5
  prelude and program goldens, once, at the end. Nothing else moved:
  `safepoint_test`, `lower_test` and `lower_portable_test` stayed green
  with the slow tests included.

## Decisions to review

1. **The budget can also come from the stage (an addition beyond the
   brief).**
   - The e2e topology is parser stage, then lowering stage. Nothing there
     could put a field on the packet, and the existing C1 programs use the
     decimal literal `9007199254740992`. That literal used to be native
     (the old bound was 2^53 inclusive) and is now the call form. With no
     budget, `integer-bound-test` and `gate-round3-test` in `e2e_c1` fail
     with a diagnostic.
   - What I added: a 5-arity `lower/open-stage ... medium max-digits`. It
     stores `:max-digits` in the stage state, and `lower-transform` copies
     it onto any packet that does not declare `:yang.python.antlr/max-digits`
     itself. A packet's own field wins.
   - There is still no default: with neither a packet field nor a stage
     budget, a decimal literal above 2^53-1 is a diagnostic. This is pinned
     in `decimal-digit-budget-test`.
2. **Diagnostics.**
   - A budget breach is `:yang.python.antlr/syntax` with the message
     "Exceeds the limit (N digits) for integer string conversion: value has
     M digits; consider hexadecimal for huge integer literals". It follows
     3.11's wording, shortened.
   - A missing budget is `:yang.python.antlr/unsupported`, construct
     "decimal integer literal beyond 2^53 - 1 with no digit budget
     declared".
   - The budget counts the digits after removing underscores, leading
     zeros included.
3. **The lowering now checks the integer grammar itself, using CPython
   3.9.6's messages.**
   - The messages: "invalid decimal/hexadecimal/octal/binary literal" and
     the existing leading-zeros text (now also for `0_7`).
   - This replaces the old `:unsupported "number literal"` digit refusal.
     No test expected that refusal.
   - It uses flat character classes plus a `__` check and a trailing-`_`
     check. A regex that repeats a group recursed per digit on the JVM and
     overflowed the stack on the 5000-digit token.
   - A finding: the pinned grammars-v4 lexer rejects every underscore in a
     number (`1_000` is a syntax-error packet), not just malformed ones. So
     the underscore spellings are reachable only from hand-built packets,
     and that is how the spelling groups test them.
4. **Admission.**
   - No src admission point existed: the integer module is registered only
     by test compositions, and the `integer` symbol was not referenced
     anywhere under `src/cljc/yang/python`.
   - I added `prelude/register-integer-module`. It refuses `::max-bits`
     below 53 with ex-data `{:yang.python.antlr/refusal
     :yang.python.antlr/max-bits}`, then delegates.
   - Only the shared e2e registrar and the new test use it. The 60-bit
     `small` compositions in other tests still call `integer/...` directly,
     and 60 passes the check anyway.
   - Whether the other test compositions should switch is the
     orchestrator's call.
5. **Fast-lane tagging.** No new test is `^:slow`. The JVM times were:
   - `huge-hex-test` 2.7 s
   - `containers-test` 2.0 s
   - `evaluated-literals-test` 1.4 s
   - `integral-float-is-not-an-int-test` 1.2 s
   - everything else under 0.2 s

   I kept the run tests in the fast lanes on purpose, because the
   Node/Dart BigInt paths are the point of S2. If any of them goes past
   about 5 s on Dart, tag it and guard it.

## Test coverage (`int_literal_test.cljc`; brief items 4 and 6)

- **Spelling groups.**
  - Values: 2^53-1, 2^53, 2^63, 2^64 and 2^100.
  - Spellings: decimal, decimal with `_`, `0x`, `0X` with mixed-case
    digits, `0x_` grouped, `0o`, `0O` grouped, `0b`, `0B` grouped.
  - Each group gives one row set, one canonical byte string and one root
    address. The JVM goldens are asserted on every host.
- **Native and call forms.**
  - 2^53-1 lowers to `(u/lit 9007199254740991)` from both decimal and hex.
  - 2^53, 2^64 and 2^64-1 lower to `py/int-lit` over canonical lowercase
    hex, with leading zeros dropped.
- **Walk.** Over the lowered rows of every spelling, every non-negative S0
  value and the containers program: no integer outside +/-(2^53-1) and no
  big carrier.
- **Budget.**
  - A 5000-digit hex literal lowers under a 4300-digit budget.
  - The same value in decimal (6021 digits) is a syntax diagnostic.
  - The 4300/4301 edge holds with and without underscores.
  - With no budget, a decimal above 2^53-1 is a diagnostic; hex, octal
    and binary are not.
  - A stage budget fills in for a packet without one, and the packet's own
    field wins.
  - A budget of 0 is refused.
- **Malformed tokens.** `0755`, `0_7`, `1__0`, `1_`, `0x`, `0x_`, `0xg`,
  `0x1__f`, `0o8`, `0b12` and `0b_` each refuse with CPython's message.
- **Four VMs** (ast-walker, semantic, stack, register):
  - Every non-negative S0 value as a decimal literal, printed, reproduces
    the S0 `dec`, `hex` and `cbor` columns, in the canonical carrier.
  - 2^64 from a hex literal sits in a cell, a list, a tuple, a dict value
    and a dict key. It is returned, printed exactly, and keeps the S0
    bytes at every position.
  - The 5000-digit hex literal runs and prints its exact decimal.
  - `0x1` followed by 1100 zeros, under max-bits 4096, raises a
    `MemoryError` that `except` catches.
- **Renderer.** Every S0 value renders its decimal, nested inside
  containers too.
- **Malformed encodings** (pins only; no code was needed).
  - These refuse as `:non-canonical`: `c24105`, tag 2 over 2^64-1, tag 3
    over -2^64, a leading zero byte (`c24a0001...`), an empty bignum
    (`c240`), and the non-shortest head `1b0000000000000005`.
  - Tag 2 over an int (`c201`) refuses as `:malformed-number`.
  - `0101` refuses as `:trailing-data`.
  - Decoding each S0 `cbor` row gives the carrier `integer/normalize`
    gives, as `=` and with the same big-or-native carrier.
- **Wrong carriers.** A double 2^53 (on JS an unsafe Number) and a -0
  reaching `add` or `normalize` get the module's `:wrong-type`, and
  `integer?` is false for both.
- **Integral float.** `py/int?` of `{:py/float 2}` is false, and `~2.0`
  raises a guest `TypeError` on all four VMs.
- **Admission.** 52 bits is refused; 53 is admitted.

## Red, green and the mutations

Red first. Before the implementation the new tests failed for the
expected reasons:

- the "beyond 2^53" refusal
- the `unsupported "number literal"` refusal
- the missing registrar
- the missing address goldens

Each mutation was applied, run against the namespace, and reverted:

- **Decimal reconstruction** (format 10 in the lowerer, parse 10 in
  `py/int-lit`): 52 failures, including the huge-hex row, the containers
  test and the spelling goldens.
- **A native 2^53** (`max-safe-int` set to 2^53): 4 failures, in the form
  test, the walk, the 2^53 golden and the no-budget rule.
- **Budget check removed:** 3 errors in `decimal-digit-budget-test`.
- **Admission floor dropped to 1:** the admission test fails.
- **Renderer arm removed:** this cannot be caught on the JVM. A JVM
  `BigInt` already prints through `(number? v) (str v)`. It shows up only
  on Node and Dart, in `renderer-prints-exact-integers-test` and the
  run tests, so the landing lanes are its first real check.

## Verification

- `clojure -M:test -e :slow` over 13 namespaces: 164 tests, 6395
  assertions, 0 failures. The namespaces are lower, lower-portable,
  float-address, safepoint, int-contract, int-literal, prelude-parity,
  e2e, e2e-c1, e2e-c2, cbor-conformance, hash-registry-contract and
  store-write-audit.
- `clojure -M:test -i :slow` over lower, lower-portable, float-address,
  safepoint, int-contract and prelude-parity: 24 tests, 1947 assertions, 0
  failures.
- `e2e-c1` and `e2e-c2` in full: 0 failures.
- Every `^:slow` test in `e2e_test` except `long-loops-test` (28 tests, 352
  assertions): 0 failures.
- `long-loops-test` was NOT run: about 14 minutes, beyond the 10-minute
  foreground cap.
- kondo is clean on every changed file.
- Line lengths and ASCII, checked by hand: every changed line is ASCII and
  at most 80 columns, except the `:segment/blake3-...` golden lines. They
  are 84 to 93 columns, as the existing golden lines in
  `float_address_test` already are.
- `bb test:changed:list` selects 13 namespaces on clj and 8 each on cljs
  and cljd. `int-literal-test` is in all three.

## Open items for the orchestrator

- **A window that S3 closes, on Node and Dart.** A literal at or above
  2^53 is now a big carrier on JS, and on Dart from 2^63. The C1 operators
  in the prelude are still bounded host `+ - *`. On Node, `2**53`-sized
  literal arithmetic such as `9007199254740992 + 0` will throw a host
  `TypeError` (BigInt mixed with Number) until S3-A lands. Before this
  slice, exactly 2^53 was native and computed.
  - The C1 programs that use that literal (`e2e_c1` `integer-bound-test`
    and the range tests) are JVM-only, where `py/int-lit` yields a long, so
    they stay green.
  - I found no portable test lowering a decimal 2^53 literal and then doing
    arithmetic on it.
  - This matches fable's order (S3-A follows S2). Flag it if S2 is to ship
    alone.
- **Node and Dart are the real check** for the renderer arm, the
  JS/Dart carriers in the decode and canonical pins, and the four-VM run
  tests. All of them are fast tests in `int-literal-test`.
- **cljstyle** needs running by someone with the permission.

## Follow-up round (after the gate, 2026-10-06)

The gate is collab/1791205200000-reviewer-s2-gate.gpt-6.1-sol.findings.md.
S2 does NOT land alone: it stays on this branch, and S3-A/B/C stack on
it. Every edit below is uncommitted. I made no git writes and ran only the
JVM.

1. **Finding 2: lowering pins** (`int_literal_test`,
   `native-and-call-forms-test`).
   - A new helper `assigned-node` returns the node `x = <expr>` assigns.
   - `-5` lowers to `(py/neg (u/lit 5))`.
   - `-18446744073709551616` lowers to
     `(py/neg (py/int-lit "10000000000000000"))`.
   - `00` joins `0` and `0_0` in lowering to `(u/lit 0)`. All three come
     from hand-built packets.
   - These pin existing behavior, so they passed at once. To show they can
     fail, I mapped unary `-` to `py/pos` in `lower.cljc`: both sign pins
     failed. Then I reverted it.
2. **Ruling (a): one sentence in `yang.antlr.md` 8.5.4**, after the literal
   bullet. It says:
   - `lower/open-stage` takes an explicit composition digit budget.
   - That budget fills only a packet that declares no
     `:yang.python.antlr/max-digits` of its own, and the packet's
     declaration wins.
   - With neither, a decimal literal above 2^53-1 is a diagnostic.
   - Hex, octal and binary literals are exempt.
3. **Finding 3: compositions rerouted through
   `prelude/register-integer-module`.** Every site is at or above the
   53-bit floor:
   - `prelude_parity_test.cljc:39` (`opts-under`). It serves two runner
     sets: `runners` at 100000 bits / 4300 digits, and `small-runners` at
     60 bits / 5 digits.
   - `safepoint_test.cljc:172`, at 100000 / 4300. I added the
     `yang.python.antlr.prelude :as prelude` require.
   - `float_address_test.cljc:275`, at 100000 / 4300.
   - Still direct, and not in scope this round:
     - `e2e_test.clj:586`: the 60-bit `integer-limits-are-guest-exceptions`
       composition.
     - `e2e_c2_test.clj:1133`: 100000 bits.
     - `int_contract_test.cljc:152`: the S0 contract runners, 4096 and 60
       bits. That is arguably a module-contract use and can stay direct.
     - `test/yin/vm/integer_test.cljc`: stays direct, as instructed.
     - All of these are 60 bits or more.
4. **Verification.**
   - kondo is clean on the four touched test files.
   - cljstyle is BLOCKED: `cljstyle check` needs approval.
   - `clojure -M:test -e :slow` over int-literal, prelude-parity,
     safepoint and float-address: 52 tests, 848 assertions, 0 failures.
   - `-i :slow` over prelude-parity, safepoint and float-address: 22
     tests, 235 assertions, 0 failures.

## Interim-state hazards

**Why these sites matter:**
- A literal at or above 2^53 lowers to `py/int-lit`, whose value is a JS
  `BigInt` on Node. On Dart it is a `BigInt` from 2^63; the JVM is safe,
  because Clojure arithmetic promotes.
- `data/number?` admits big carriers (`src/cljc/yin/vm/data.cljc:414`), so
  `py/numeric?` and `py/int?` are true for a BigInt. The value then reaches
  the host operators below. You do not get a guest `TypeError`; you get a
  host failure.

**How the hosts behave:**
- **Node.** `+ - * /` mixing a BigInt and a Number throw a host
  `TypeError`. Mixed `< <= > >=` are exact and do not throw. Clojure `=`
  between `2n` and `2` is false.
- **Dart.** Any arithmetic or comparison between a `BigInt` and an `int`
  throws.
- **JVM.** Nothing throws. The only problem is the C1 2^53 bound, which
  raises `OverflowError` where CPython is exact.

All lines are `src/cljc/yang/python/antlr/prelude.cljc` as of this
branch.

**Arithmetic core:**
- `py/overflow` (930): the C1 bound; delete it.
- `py/checked-add` (934), `py/checked-sub` (943) and `py/checked-mul`
  (944) use native `+ - * <` and the 2^53 bound.
- `py/abs` (1041) is `(- 0 x)`.
- `py/divmod-pos` (1042) doubles natively. `checked-mul` uses it, and so
  does `py/int-of` (1449, float decomposition, doubles only: safe).
- `py/arith` (956)
  - Its int arm goes through the checked ops.
  - Its float arm does `(+ x y)` with a raw int operand: a BigInt meeting
    a double throws on Node and Dart. S3 needs `integer/to-float`.
- `py/compare` (970): `(op x y)` on the hosts. It works mixed on Node but
  throws on Dart. `py/lt`, `gt`, `le` and `ge` (1269-1272) all use it.
- `py/eq` (1275) uses `<=` and `>=` on the hosts, so the same split.
- `py/neg` (1261) is `py/arith :sub 0 a`, and `py/pos` (1268) is
  `py/arith :add 0 a`. Both go through `checked-sub`/`checked-add`.
- `py/add` (980), `py/sub` (1003), `py/mul` (1024), `py/iadd` (995) and
  `py/imul` (1029): their int arms go through `py/arith`.

**Division and power:**
- `py/int-floordiv` (1053) and `py/int-mod` (1061) are native, through
  `divmod-pos`.
- `py/floordiv` (1154) and `py/mod` (1162) use them; their float arms go
  through `data/float-value`.
- `py/division-check` (1144) is safe: `py/zero?` (165) uses `=`.
- `py/truediv` (1250): `(/ (data/float-value a) (py/num b))`.
  - The numerator converts, but the divisor stays raw, so a BigInt divisor
    throws on Node and Dart.
  - `float-value` of a BigInt (`data.cljc:427`) also rounds, which S3's
    `integer/true-div` replaces.
- `py/ipow` (1169) uses `checked-mul`, `int-floordiv` and `int-mod`.
- `py/fpow` (1177) halves its exponent through `int-floordiv`/`int-mod`.
- `py/pow` (1185) uses `(< e 0)`, `(- 0 e)` and `py/floor` (1132) on the
  exponent.
- `py/floor` (1132) and `py/floor-descend` (1125) are float-only:
  probably safe.

**Bits:**
- `py/bit1` (1205), `py/bit-op` (1210) and `py/int-op` (1219):
  interpreted recursion over `int-mod`, `int-floordiv` and the checked
  ops.
- `py/bitand`, `py/bitor` and `py/bitxor` (1224-1226) go through them.
- `py/invert` (1227) is `(checked-sub (- 0 a) 1)`.
- `py/shift-check` (1232) has a native `(< n 0)`.
- `py/lshift` (1239) uses `checked-mul` and `ipow 2 n`.
- `py/rshift` (1243): `(< 53 n)`, `ipow` and `int-floordiv`.

**Sequences, slices and counts:**
- `py/index` (1393): native `(< i 0)`, `(+ i n)` and `(< j n)`.
  `py/getitem` (1737) and `py/setitem` (1763) reach it, for str, list and
  tuple.
- `py/slice-int` (1678) passes the carrier through. From there it reaches
  `py/slice-bound` (1683: `<`, `+`, `>`), `py/slice-walk` (1692: `+`, `<`)
  and `py/slice-positions` (1697: `(- n 1)`, `(= step 0)`). `py/slice-of`
  (1714) uses them.
- `py/repeat` (1008): native `(< 0 n)` and `(- n 1)` in
  `py/repeat-items` (1004) and `py/repeat-str` (1006).
  - A big positive count should be a guest `MemoryError`; a negative one
    gives an empty sequence.

**Ranges:**
- `py/range3` (1782) stores raw carriers. `py/range-args` (2027) feeds
  it.
- `py/range-count` (1793) uses `checked-*`, `int-floordiv` and `int-mod`.
- `py/range-elem` (1810): native `+`.
- `py/range-at` (1821)
  - Its fast arm is native `(+ start (* i step))` behind native `<=`
    guards.
  - Its index `i` is the VM-native iteration counter, from `py/iter-at`
    (1849).
- `py/range-has?` (1328): native `<` and `<=`, plus `int-mod`.
  `py/contains` (1313) reaches it.
- `py/range-len` (1845) uses `range-count`. `py/len` (1973) reaches it
  for a range.

**Already safe** (through the module, or carrier-agnostic):
- `py/key` (1510) for an int: `py/int-canon` (1565, an `=` against 0),
  then `integer/format`.
- `py/hash` (1583), then `py/hash-int` (1558), `py/mod-p` (1548) and
  `py/hash-signed` (1551). All are module calls; `<` and `=` touch only
  the native `compare` result and native small values.
- `py/hash-float` (1570) and `py/float-key` (1482) work on doubles and
  module results only.
- `py/is` and `py/is-not` (1357-1358) use `data/content=`.
- `py/int-lit` (1426) and `py/int-result` (1413).
- The renderer (`render.cljc`, the new exact-integer arm).

**Not prelude, but worth one test in S3-A:** `py/zero?` (165) compares
with `(= v (data/float-value 0))`. That is correct for a BigInt, but an
explicit pin would keep it correct.
