Completed-GMT: 2026-10-07
Coding-Agent: claude (opus-5-5), continuing codex (gpt-6.1-sol)

# Python C3 slice S4: conversions, the 11 builtins, ledger a-d f-i

Worktree /Users/sto/workspace/datomworld-s4, branch yang-python-c3-s4,
nothing committed (no git writes). Binding design:
collab/1791222000000-architect-python-c3-s4-design.claude-fable-5-1.findings.md.

## State found

Codex left an uncommitted, unreported diff: `prelude.cljc` (~550 lines),
`int_ops_fixtures.cljc`, `int_ops_test.cljc`, and new untracked
`int-conv-v1.{txt,generate.py}` and `int_conv_test.cljc`. Its own focused
fast lane was green (9 assertions); its slow lane was red (4 failures,
1 error). What I did with each part:

- **Kept, logic reviewed line by line:** the 11 builtins and
  `builtin-names`/`host-names` entries; `py/int-text` (prefix,
  underscore, base-0 leading-zero rules); `py/float-text` /
  `py/float-decimal` syntax; `py/space?` (int and float share one set,
  28..31 excluded, confirmed by both fixtures); `py/str-repr`;
  `py/digits-repr`/`py/float-repr`; round/abs/radix; (f) per-operator
  messages through `py/division-operands`; (g) `py/exc-matches` fix and
  the lint test; (h) `py/same?`; (i) float in range; the digit-limit
  message; `py/check-print`; the fixture, its generator and loader
  extension (`s:` tokens, astral code points, `conversion-row`).
- **Fixed (wrong):**
  - `float('-nan')` was built as `-inf - -inf` with a comment claiming
    pinned fff8 bits. The sign of an arithmetic NaN is the CPU's
    default, so it is fff8 on x86 and 7ff8 on this arm64 Mac (the JVM
    run failed). Now it is the one NaN; see "Blocked" below.
  - JS-only bugs the Node slow lane found (my first fix had added
    them): a `##NaN` literal row is not `=` to itself on JS, so a
    prelude holding it twice failed content addressing ("Semantic
    bytecode address collision", 26 errors). Every prelude NaN is now
    `inf - inf` (HEAD's convention), and `power-special` returns the
    NaN operand. `repr(0.0)` gave `-0.0` on Node: codex's sign test
    compared bare host numbers with `content=`. It now compares
    `data/float64`-wrapped content, as `0.0 is -0.0` does. A
    reciprocal test cannot work because the VM's `/` throws on a zero
    divisor. Fast pins for `repr(0.0)` and `repr(-0.0)` now cover it on
    every lane.
  - Float literals in the prelude (`0.5`, `-1.0` five times, a bare
    `##Inf`/`##-Inf` under `if`): `prelude-float-discipline-test` was
    red. All go through `data/float-value` now.
  - `canon-ints` (codex's copy from `prelude_parity_test`) threw on
    2^63 and on infinities (`(long n)`, `(rem inf 1)`).
  - `(-1.0) ** (2^1024 - 2^970 - 1)` gave -1.0; CPython gives 1.0
    because the exponent becomes a double first (an even one). The
    fixture caught it (2 rows x 4 VMs).
  - The 25 int-conv rows and 2 float-text rows where CPython escapes a
    non-ASCII non-printable character in a message (`'​1'`)
    failed; this is a measured departure, now a labelled hand pin.
- **Rewritten:** the whole conversions section and the `pow` helpers,
  for the house style (cljstyle had pushed codex's compact `if` chains
  past 80 columns) and for the logic. `pow` is now `py/pow`, which
  chooses int or float, plus `py/float-power`, which converts the base
  and then the exponent first (CPython's order), so `from-float` of the
  exponent double gives both the parity and the guard. New helpers
  `py/type-text`, `py/index-type-error`, `py/float-to-int`,
  `py/round-float`, `py/char-escape`, `py/exponent-text`,
  `py/range-has-float?`. `int_conv_test.cljc` was largely rewritten:
  codex's 38 pins are kept and about 40 more added (hand pins labelled),
  the departures are explicit, the renderer parity law is added, and
  the 15-deep dispatch is split into three functions.
- **Deleted:** nothing of codex's. Removed the duplicate private
  `exact-literals`/`canon-ints` from `prelude_parity_test` (it now uses
  the public ones in `int_ops_test`, per "do not copy").
- **Fixture provenance:** this session cannot run Python (permission
  denied), so I could not regenerate int-conv-v1. I spot-checked rows
  against known CPython 3.9.6 behaviour (`float floor division by zero`,
  `'​'` escapes, U+0085 stripped, 28..31 kept, errno pair 34) and
  all are consistent. The generator asserts 3.9.6, so a regeneration
  is a one-line check for the orchestrator:
  `/usr/bin/python3 -I test/resources/yang/python/int-conv-v1.generate.py`
  followed by `git diff --stat` (expect no change; 1010 rows).

## Checklist

| Item | Status |
|---|---|
| (a) float `**` guard through the exponent's float conversion, OverflowError from 2^1024 - 2^970 | done |
| (b) same guard whenever the result is float, incl. negative exponent with int base; base then exponent before the zero check | done |
| (c) `2 ** -1074` split reciprocal only when `fpow` overflows | done |
| (d) finite-operand overflow -> OverflowError (34, 'Result too large'); integral float exponent via `from-float`; inf/NaN exponent table | done |
| (f) per-operator float ZeroDivisionError messages from the fixture | done |
| (g) `py/exc-matches` message + lint for `{:py/str x}` with non-string x | done (lint walks `function-definitions`, the core helpers; builtin defs use string literals only) |
| (h) `py/same?` for `in` and `==` on lists and tuples | done (no list `.index`/`.count`/`.remove` exist in the prelude yet) |
| (i) float in range by `from-float` | done |
| int, float, str, repr, bool | done |
| abs, pow, hex, oct, bin, round | done |
| print digit-limit check before snapshot, nothing appended on a breach | done |
| digit-limit message from `integer/max-digits` | done |
| float('-nan') sign bit fff8 (orchestrator ruling) | **blocked**, see below |

## Blocked: float('-nan') sign bit

The ruling asks for bits fff8000000000000 through "pinned bit
manipulation or a pinned host op, never (* -1.0 nan)". Nothing within
the slice's limits provides it:

- No `data` or `integer` export maps bits to a float or flips a sign bit.
  The design forbids new host functions, and the integer module is
  frozen at version 4.
- Arithmetic cannot do it. An operation on a NaN operand propagates
  that NaN's sign (multiplying by -1 included). A NaN produced from
  non-NaN operands takes the CPU's default sign: fff8 on x86, 7ff8 on
  arm64 (measured here: codex's `-inf - -inf` gave 7ff8 on the JVM).
  One-argument `-` is a sign flip on the JVM but `0 - x` on ClojureDart.
- Jing writes every NaN as 7ff8 (`cbor.cljc` float-wire), so an image
  VM loses the sign of any NaN literal or value that is encoded.
- No C3 guest operation can observe a NaN's sign: `repr` is `nan`, and
  there is no `math.copysign` or `struct`.

- A `##NaN` literal cannot be used either: on JS its row is not equal
  to itself, which breaks the prelude's content addressing.

So `float('-nan')` is the one NaN, built as `inf - inf` like every
other prelude NaN. Its sign bit is the host CPU's default NaN sign
(7ff8 on arm64, fff8 on x86), never read from the text. A fast pin
checks that it is a NaN on all four VMs, and the doc's one-NaN
paragraph records the rule. Corpus and `check-cases` comparisons treat
every NaN as one value (`:nan`); the bits of inf rows are still
checked. If the sign is wanted, the fix is a `data` export that flips
the sign bit (or builds a float from bits), which needs an Architect
ruling.

## Measured disagreements with the design (the fixture won)

- `//` by a float zero says "float floor division by zero" (the design
  recalled "float divmod()"). `divmod` says "float divmod()".
- `int()` of whitespace alone quotes the original text (`' '`), unlike
  `float()`, which quotes `''`.
- `int()` keeps 28..31 too, like `float()` (the design's isspace set
  included them).
- CPython's `repr` escapes non-ASCII non-printables (`\x85`, `\xa0`,
  ` `, ` `..`​`, ` `, `　`, `﻿`...).
  `render/string-repr` and `py/str-repr` escape only ASCII controls, so
  the 27 such rows are hand pins: the expected message quotes the input
  through `render/string-repr` (the "same escapes" rule). Matching
  CPython needs a Unicode printable table in both the renderer and the
  prelude; that is an Architect question.
- Two pre-existing hand pins in `int_ops_test/exact-division-power-test`
  were wrong against CPython: `(-1) ** -(2**64+1)` and
  `(-1.0) ** (2**64+1)` are 1.0, not -1.0 (2^64+1 rounds to an even
  double). I changed them; the CPython rows of int-conv-v1 measure the
  same mechanism at 2^1024 - 2^970 - 1.
- Order of errors in `pow`: the base and exponent now convert before
  the non-integer check, so `(10**400) ** 0.5` is OverflowError, as in
  CPython, not NotImplementedError.

## Tests

- New `test/yang/python/antlr/int_conv_test.cljc`. Fast tests:
  `scalar-conversions-test`, `deferred-items-test`, `digit-limit-test`
  (4300; hand pins), `small-profile-digit-limit-test` (5 digits and 60
  bits; hand pins, including the MemoryError of int(1e300)),
  `print-atomicity-test`, `departure-pins-test` (two NaNs, ASCII-digit
  divergence, the astral row, -nan bits), and `string-literal-lint-test`.
  Slow tests (`^:slow` + `slow/guard`, at most 400 rows per guest
  program): `cpython-conversion-fixture-test` (int-conv-v1, 1010 rows)
  and `guest-float-text-fixture-test` (float-text-v1 through the guest,
  plus the renderer parity law `render/float-repr` = guest `repr` on
  every repr_float row).
- `int_ops_test`: `host-floats`, `canon-ints`, `exact-literals`,
  `run-with-prelude`, `check-cases`, `caught`, `runners` and
  `small-runners` are public and required by `int_conv_test` (no copies,
  no `#'`). `check-cases` now compares floats by IEEE bits through a new
  public `float-bits` (every NaN `:nan`). Host `=` (and `canon-ints`)
  equated -0.0 with 0.0, so the `abs`-through-`py/float-abs` mutation
  stayed green until this change. Every existing int_ops pin passes
  under bit comparison.
- `float-power-exponent-limit-test` moved to the 2^1024 - 2^970 edge
  (edge - 1 and 1 - edge give 1.0; edge and -edge raise; message pinned).
- `int_literal_test/huge-hex-test`: printing a 5000-hex-digit literal
  now raises the digit-limit ValueError with nothing printed (changed
  pin; it said "no digit limit on the snapshot in S2").
- `int_ops_parser_test/conversion-builtins-source-test` (JVM, every VM,
  naive and hooked): all 11 builtins from source, an `int()` error's
  args, the print digit limit caught from source, and a module-level
  `def str` shadowing the builtin.
- Digit-limit message pins (changed pins): two in `prelude_parity_test`
  (the 4300- and 5-digit messages) and `int_contract_test`'s
  `guest-outcome`. The latter is now a function of the profile's
  `max-digits`, so the `wide` and `small` rows each expect their own N.
- Re-mints (once, against the final prelude): `float_address_test`
  prelude root, A, A', the record and the prelude subtree. The hook
  prelude address is unchanged. Final values: prelude
  460cf5b82b70e68ead45c4e06bc8800f78cfc7a3a5184db10a437dbf9a7bcb92,
  A 82164d40..., A' fef78124..., record 958a2cf2..., prelude subtree
  3c8681d9....
  `safepoint_test`, `lower_test` and `lower_portable_test` needed no
  re-mint; I found no cell-count pin.

## Mutations (each red once, then reverted)

| Mutation | Detector | Result |
|---|---|---|
| host parse for int(str) (`integer/parse` of the stripped text) | scalar-conversions-test | red, 4 VMs |
| to-float then scale for float(str) | guest-float-text-fixture-test | red, 12 assertions (e.g. `1.5000...e+00` -> 1.5000000000000002) |
| host `str` in `render/float-repr` | the parity law in guest-float-text-fixture-test | red, 92 assertions |
| no check in print | print-atomicity-test | red, 4 VMs |
| guard back at 2^1024 (shift test, exponent kept exact) | deferred-items-test, float-power-exponent-limit-test | red, 8 assertions |
| `py/eq` alone in `seq-contains?` | deferred-items-test | red, 4 VMs |
| abs through `py/float-abs` | scalar-conversions-test | red, 4 VMs (after the bit-comparison fix) |

(Nearest-only `float-digits` is M4's mutation and was not repeated.
The mutations ran before the final JS fixes, which changed only how
NaNs are made and the signed-zero test in `py/float-repr`. None of the
mutated sites changed after that.)

## Lanes run (all on the final code, foreground)

- JVM fast, `clojure -M:test -r 'yang\.python\..*' -e :slow`: 186
  tests, 2349 assertions, 0 failures.
- JVM slow, `-i :slow` over int-conv, int-ops, int-ops-parser,
  prelude-parity, int-literal, float-address, int-contract and
  safepoint: 32 tests, 2598 assertions, 0 failures. Also, before the
  final JS fixes (prelude NaN/zero change only), the e2e, e2e-c1 and
  e2e-c2 slow sets: 50 tests, 662 assertions, 0 failures.
- Node slow, `bb test:slow:cljs` (every guarded namespace, including
  int-conv, int-ops, prelude-parity, float-address, int-contract): 336
  tests, 43023 assertions, 0 failures. The first run, before the JS
  fixes, failed with 8 failures and 26 errors (above).
- Dart slow, `bb test:slow:cljd` (every guarded namespace): 240 tests,
  "All tests passed!", no SKIP.
- I used the bb tasks rather than the brief's
  `DATOM_SLOW_TESTS=1 ...` commands because setting an environment
  variable inline needed an approval this session could not get. The
  tasks cover a superset of those namespaces.
- The full three-lane `bb test:changed` is not run; that is the
  orchestrator's landing step.

- kondo over every touched file: 0 errors, 0 warnings. `cljstyle check`
  clean on prelude and `test/yang/python/antlr/`. All new and rewritten
  lines are ASCII and at most 80 columns; long lines elsewhere in the
  prelude predate S4.

## Docs

`docs/design/yang.antlr.md` 8.5.4 has these edits. The intro now says
S3 and S4 have landed. The ruling 10 power bullet gains items a to d,
and a new bullet gives the f messages. The conversions list now covers
which builtins exist, whitespace and syntax, the ASCII-digit and
non-printable departures, scalar-only `str`/`repr`, the digit-limit
message and the print check, and float in range. The one-NaN paragraph
gains item h and `float('-nan')`. The ruling 13 sentence is amended:
repr/str/print parity is claimed, format/%/round(x, n) are not. The
slice table gains M4 and S4 rows, and S3 is marked landed. The prelude
ns docstring has a conversions paragraph and the digit-limit message
source. The module table and the `integer.cljc` docstring were already
at version 4 (M4).

## Files

- src/cljc/yang/python/antlr/prelude.cljc
- test/yang/python/antlr/int_conv_test.cljc (new)
- test/resources/yang/python/int-conv-v1.txt, int-conv-v1.generate.py (new, codex)
- test/yang/python/antlr/int_ops_test.cljc, int_ops_fixtures.cljc,
  int_ops_parser_test.clj, int_literal_test.cljc,
  prelude_parity_test.cljc, float_address_test.cljc,
  int_contract_test.cljc
- docs/design/yang.antlr.md
- Scratch logs were in `tmp-s4/`, now deleted; the worktree holds only
  the files above plus the collab notes.
