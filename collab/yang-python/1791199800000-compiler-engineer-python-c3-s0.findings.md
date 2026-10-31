Completed-GMT: 2026-10-06
Coding-Agent: claude (opus-5-5)
# Python C3 slice S0: the exact-integer contracts, frozen as fixtures

Status: done, green on the focused JVM namespaces. No git writes. No
product code changed (src/ is byte-identical; `git diff --stat` shows only
the doc line). Node and Dart were not run (iteration rule); see
"Cross-host reasoning".

## Files

- NEW `test/resources/yang/python/int-contract-v1.txt` (306 lines). Written
  by a one-off CPython 3.9.6 script kept out of the repo
  (`build/s0/gen_int_contract.py`, gitignored). The script computes every
  column from CPython and from the documented rules of `integer` v2 and
  Jing's `int-wire`, independently of the Clojure code.
- NEW `test/yang/python/antlr/int_contract_fixtures.cljc`, the loader. It
  makes no writes. It holds the fixed value list, the profiles and the
  boundary rows. Its `render` recomputes the whole file on the host from
  the integer module and Jing. Its `read-file` drops blank lines.
- NEW `test/yang/python/antlr/int_contract_test.cljc`, the tests.
- `docs/design/yang.antlr.md`: the S0 row gains "Landed." Nothing else
  changed.

## The fixture

Line format: `<name> <column> <text>`. Names use the expression language
`[-]B[^K][(+|-)D]` with ordinary precedence, so `-2^63-1` is -(2^63)-1.
`f:2^K` names a binary64.

- Header:
  - `int-contract-v1`
  - `generator CPython 3.9.6`
  - `profile wide max-bits 4096 max-digits 4300`
  - `profile small max-bits 60 max-digits 5`
- Integer values (31): 0, 1, -1, 2^31-1, 2^31, 2^31+1, 2^32. Then the
  full neighbor set n-1, n, n+1, -n+1, -n, -n-1 for n = 2^53, 2^63 and
  2^64, plus 2^62. Then 2^100, 10^30, 2^1074, 2^1075 and 2^2000.
- Integer columns:
  - `bits`
  - `dec` (CPython `str`)
  - `hex` (`format(n,'x')`)
  - `hash` (CPython `hash`)
  - `key` (S2b `py/key`: the numerator in hex, then `1`)
  - `cbor` (canonical Jing bytes)
  - `wide` and `small`: the outcome of each of `normalize`, `inc`,
    `dec`, `square`, `format`, `format16`, plus `shl`, the largest
    left-shift count that is still a value (`any` for 0, `none` when n
    alone breaches).
- Float values: `f:2^53` and `f:2^63`.
- Float columns: `dec` (`int(x)`), `hash`, `key`, `cbor` (the float64
  frame), `is-int false`, `eq-int true`.
- 42 boundary rows, each `op <profile> <op> <args> <outcome>`, with the
  first breach and the last fit on each side:
  - shift-left of 1 and -1 by 4095/4096 (wide) and 59/60 (small)
  - `mul` with the pre-check edge: (2^2048-1)^2 and 2^2048*(2^2048-1)
    are values, 2^2048*2^2048 breaches
  - `add`/`sub` to 2^4096
  - `pow 2 4095/4096`
  - `parse` 10^1233 (a value) / 10^1234 (bit-limit) / 10^4299
    (bit-limit: digits pass, bits fail) / 10^4300 (digit-limit)
  - `parse16` 2^4096-1 / 2^4096
  - the small profile's 99999/100000 for `format` and `parse`, signed
  - `format16` exempt from the digit limit

Sample pins:
- 2^53 encodes as `1b0020000000000000`.
- 2^64-1 encodes as `1bffffffffffffffff`, and 2^64 as
  `c249010000000000000000`.
- -2^64 encodes as `3bffffffffffffffff`, and -2^64-1 as
  `c349010000000000000000` (tag 3 over -1-n).
- `hash(-1)` is -2. `hash(2^63)` is 4. `hash(10^30)` is
  465258685558744706.

## Tests (`yang.python.antlr.int-contract-test`)

Fast tests (all hosts, no VM):
1. `the-file-holds-exactly-the-rendered-text`: `(= (f/render)
   (f/read-text))`. The rendered side is computed portably: dec/hex
   through `integer/format`; the hash through ruling 7's formula on the
   module (P = 2^61-1, -1 -> -2); bytes through `jing/canonical-bytes`;
   outcomes by calling the module under each profile, with `shl` checked
   at k and k+1; the floats through the host's own exact float->integer
   conversion.
2. `every-value-has-every-column`: the coverage test. It checks:
   - each value has each column of its kind exactly once, and nothing
     extra
   - the neighbors of 2^53, 2^63 and 2^64 are present, signed
   - every profile column names every op
   - the boundary rows are exactly the declared ones
   - the header (generator and profiles)
3. `integer-rows-recompute-on-this-host` and
   `float-rows-recompute-on-this-host`: per row and per column, each with
   its own failure label.
4. `text-round-trips-through-the-module`: `parse` of the frozen decimal
   and hex text gives the frozen bytes.
5. `canonical-widths-at-the-boundaries`: eight literal pins of the codec
   widths: the last 64-bit head and the first tag 2 and tag 3, each
   signed.
6. `module-outcomes-under-the-named-profiles`: each per-value outcome.
   `shift-left` at k is a value and at k+1 is `::bit-limit`. Every
   boundary row is checked.

Slow tests (`^:slow` plus `slow/guard`; about 8 s together on the JVM),
parserless, on all four VMs:
7. `guest-values-on-every-vm-test` (wide):
   - The guest builds each value from small literals (`integer/pow`,
     `neg`, `add`, `sub`) and returns its decimal and hex text,
     `integer/format (py/hash n)`, `py/key` and the value itself. The
     host compares these with the fixture, and the value through its
     canonical bytes.
   - For `f:2^53` and `f:2^63` the guest returns the hash, the key and
     the float, whose bytes are compared, plus three checks:
     - `py/is x n`: false
     - `py/eq x n`: true
     - `py/is` on n built twice: true
   - This is (g), the S2b float `is` table for integer-valued floats.
8. `guest-limit-reasons-on-every-vm-test` (wide and small): every frozen
   outcome is run as a guest call through `py/int-result` inside
   `py/try`:
   - each per-value outcome, for the values that fit the profile
   - both sides of the shift boundary
   - every boundary row

   Expected results: `value` gives `:value`; `bit-limit` gives
   `["MemoryError"]`; `digit-limit` gives `["ValueError" {:py/str
   "Exceeds the limit for integer string conversion"}]`. This is (f).

JVM only (`#?(:cljd nil :clj ...)`):
9. `cpython-recomputes-the-pinned-columns`: runs `python3 -c` and
   compares `str`, hex, `hash` and the key text of every value. When
   python3 is missing or is not exactly 3.9.6, it prints `SKIP ... the
   CPython pins were NOT rechecked` with the exit code, version and
   error. This was verified by renaming the binary to `python3-absent`.

## Red before, green after, and mutations (JVM)

- Red before the fixture existed: 10 tests, 535 errors.
- Green: `yang.python.antlr.int-contract-test` ran 10 tests with 2447
  assertions, 0 failures. python3 3.9.6 was present, so test 9 ran (no
  SKIP).
- Each mutation was applied once and then reverted. "Fixture" means the
  file was regenerated afterwards.

| Mutation | What failed |
|---|---|
| Fixture pin drift: `2^64 hash 8` -> `9` | render-equality, the row test, guest values on all 4 VMs, CPython (7 failures) |
| Tag 3 over abs(n): `-2^64-1` cbor -> `...0001` | render-equality, the row test, the width pins, both round trips, guest bytes on 4 VMs (9 failures) |
| Shift boundary: `op small shift-left 1 60` -> `value` | render-equality, module outcomes, guest mapping on 4 VMs (6 failures) |
| Coordinated drift: the renderer drops the -1 -> -2 rule AND the fixture says `-1 hash -1` | render-equality and the row tests stay green. Guest values on 4 VMs and the CPython check fail (5 failures). This is why the pins cannot drift silently. |
| Module off-by-one: `integer/out` `>` -> `>=` (src, reverted) | 166 failures across render-equality, rows, module outcomes and guest mapping |
| Blank lines: two empty lines appended to the fixture | only `the-file-holds-exactly-the-rendered-text` fails; every `read-file` test stays green, so the loader ignores blank lines |

## Untouched canonical fixtures

- `git status`: under `test/resources` the only new path is
  `yang/python/int-contract-v1.txt`. Nothing under `dao/` or `yin/`
  changed, and src/ is unchanged.
- Green: `clojure -M:test -n yang.python.antlr.int-contract-test -n
  dao.jing.cbor-conformance-test -n dao.jing.cbor-test -n
  yang.python.antlr.float-address-test -n yin.vm.integer-test` ran 74
  tests with 3368 assertions, 0 failures. The five conformance SKIPs are
  the existing errata E1 and README N/A rows.
- Green: `dao.jing.cbor-fixtures-test` ran 14 tests with 2705
  assertions, 0 failures.
- `bb test:changed:list` selects `dao.jing.cbor-conformance-test` and
  `yang.python.antlr.int-contract-test` on all three lanes.

## Checks

- kondo: 0 errors and 0 warnings on both new files. Two shadowing
  warnings (`ints`, `floats`) were fixed by renaming them to
  `int-names` and `float-names`.
- `mise exec -- cljstyle fix` (one file reformatted), then `check`: clean.
- ASCII only. Code lines are at most 80 columns. The fixture's data lines
  are longer (the 2^2000 decimal, as the ledger and checkpoint fixtures'
  hex lines are).

## Findings and deviations

1. No cross-host defect was found on the JVM. Every CPython column
   agrees with the module, the codec and the guest on all four VMs.
2. A JVM conversion trap for S4 (`int(float)`). Fixed in my helper; no
   product code has this path yet. `clojure.core/bigint` of a double goes
   through `BigDecimal/valueOf`, which uses the shortest decimal text, so
   `(bigint 9.223372036854775807E18)` is 9223372036854776000, not 2^63.
   My first render produced `f:2^63 dec 9223372036854776000 / hash 196`,
   and the CPython fixture caught it. The helper now uses `(bigint
   (BigDecimal. (double x)))`, which is exact. S4's JVM shim must not use
   `bigint` or `biginteger` of a double.
3. The profile. The brief says "max-bits 4096 ... as in the existing
   parity runners", but those runners use max-bits 100000 (prelude
   parity, float address, safepoint, e2e). I froze the brief's stated
   4096/4300, named `wide`. It admits the 1075-bit float-key minimum and
   keeps the boundary values small.
4. Hash. As asked, the hash is pinned as data, and the JVM-only CPython
   recheck exists. I ALSO render it portably through ruling 7's formula
   over the module, and check the guest `py/hash` on all four VMs, so
   the hash column is recomputed on every host as well.
5. The `small` profile's per-value columns are trivially `bit-limit` for
   everything above 60 bits. Its real boundaries are in the boundary rows
   and in the 2^31 to 2^53 rows. That is inherent in the 60/5 profile the
   brief fixed.
6. `py/try`'s third argument is Python's `else` arm. My first `caught`
   returned `:py/None` on success (S3a's helper does the same). The S0
   copy returns `:value` from `else`.

## Cross-host reasoning (Node, Dart not run)

- Every JS/Dart comparison goes through module text (`format`), module
  `compare`, or canonical bytes. There is no host `=` across carriers.
  The guest-returned values are compared through `jing/canonical-bytes`
  on the host that ran them.
- There are no integral float literals. Floats are built by exact
  doubling (`(reduce * 0.5 (repeat (inc k) 2))`) and, in the guest, by
  `(* (data/float-value 1) 4294967296 ...)`, as the S2b rows are. The
  float->integer conversion is `#?(:cljd (BigInt.from x) :clj ...
  :cljs (js/BigInt x))` with `:cljd` first, and `BigInt` is imported
  from `dart:core` as in `cbor_fixtures.cljc`.
- The JVM-only forms are `#?(:cljd nil :clj ...)`, and the shell require
  is `#?@(:cljd [] :clj [...])`. Neither namespace writes a file.
- `read-file` drops blank lines, which covers Dart's trailing empty
  line. Render-equality compares whole texts, as `checkpoint_test` does.
- The guest tests are slow-guarded, so a plain `bb test:changed` runs
  only the eight fast tests on Node and Dart; the guest legs print SKIP
  there. To run (f) and (g) on Node and Dart, use
  `DATOM_SLOW_TESTS=1` (the `bb test:slow:cljs` / `:cljd` lanes).

## Leftovers outside the repo

`build/s0/` (gitignored) holds the generator, `mutate.py`, an unused
`mutations.sh` and the run logs. Regenerate the fixture with
`python3 build/s0/gen_int_contract.py` from the repo root. It asserts
CPython 3.9.6. If the generator should be kept with the fixture, as
`cbor-v1.generate.py` is, that is a one-file move.
