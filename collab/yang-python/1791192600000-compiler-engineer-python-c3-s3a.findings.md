Created-GMT: 2026-10-05
Coding-Agent: claude (opus-5-5)
# Python C3 slice S3a: the guest-exception seam for integer refusals

Status: implemented, green on the focused JVM namespaces. No git writes.
The Node and Dart lanes have not been run (per the iteration rule); see
"Cross-host reasoning" below.

## What changed

1. `src/cljc/yin/vm/integer.cljc`, module version 1 -> 2.
   - `out` returns `:yin.vm.integer/bit-limit` instead of throwing.
     `mul`, `shift-left` and `pow` return it from their pre-checks.
     `parse` and `format` return `:yin.vm.integer/digit-limit`.
   - New private `pair`: `quot-rem` and `floor-div-mod` answer the first
     limit reason instead of a half-refused pair.
   - The other seven reasons still throw `ex-info` unchanged: `:arity`,
     `:wrong-type`, `:out-of-range`, `:syntax`, `:zero-division`,
     `:negative-count` and `:negative-exponent`.
   - The contract paragraph of the docstring is rewritten. The limit
     ex-data key `::limit` is gone, because a returned keyword carries
     no data.
   - `bit-limit!` and `digit-limit!` are removed.
   - Version check: nothing in src or test reads `module-version`. The
     registry has no version gate, so the bump is the constant plus a
     test. The explicit-install rule is unchanged.
   - Other callers: `values.cljc`, `data.cljc` and `engine.cljc` use only
     `yin.vm.integer.host`, never the module, so nothing to adapt. Of the
     tests, only `prelude_parity_test` relied on a thrown limit (its
     digit-limit row), and it is adapted.
2. `src/cljc/yang/python/antlr/prelude.cljc`
   - `py/int-result` is the one translator:
     - `bit-limit` raises a bare `MemoryError()` with args `()`, as
       CPython does.
     - `digit-limit` raises `ValueError("Exceeds the limit for integer
       string conversion")`.
     - The seven reasons version 2 never returns (`py/int-refusal?`)
       fail the run as `(:py/int-defect r)`. This uses the same idiom as
       `(:py/no-handler e)`, and the VM refuses it with "Cannot apply
       non-function", which is the VM's own text and the same on every
       host.
     - Any other result passes through.
   - Every `integer/sub`, `neg`, `mul`, `shift-left`, `format` and
     `floor-div-mod` call is wrapped, in `py/float-parts`,
     `py/float-key`, `py/key`, `py/hash-modulus`, `py/mod-p`,
     `py/hash-signed`, `py/hash-int` and `py/hash-float`. `integer/compare`
     is not wrapped: it never reaches `out`. `py/int-canon` makes no
     module call, so it has nothing to wrap.
   - `["MemoryError" 'py.b/MemoryError 'py.b/Exception]` is added to
     `builtin-classes`.
   - The namespace docstring gains the mapping paragraph, including the
     3.9.6 / 3.9.14 support-profile note.
   - The `py/checked-*` 2^53 OverflowError path is untouched.
3. `floor-div-mod` CAN breach, so it is wrapped too, contrary to the
   brief's expectation. When an operand already exceeds `::max-bits`,
   the remainder or quotient breaches. Example: in `py/hash-float` under
   a limit below 6 bits, `floor-div-mod(-1, 61)` gives r = 60. Unwrapped,
   `(get :yin.vm.integer/bit-limit 1)` is a silent nil. In `py/mod-p` it
   cannot breach once `py/hash-modulus` (wrapped) has succeeded.
   `compare` cannot breach.
4. Docs: one S3a row in the 8.5.4 slice table of
   `docs/design/yang.antlr.md` ("Landed."). Nothing else was touched, and
   UCF text was not edited.
5. Goldens re-minted once, last, in `float_address_test.cljc`:
   - prelude root
   - A root
   - A' root
   - record address
   - the prelude subtree id

   The hook prelude address is unchanged.

## Tests (red before, green after; each key guarantee broken once)

- `integer_test`:
  - Limit rows now expect `[:returned ::integer/bit-limit]` or
    `digit-limit`.
  - New rows:
    - `neg`, `normalize`, `bit-not`, `quot-rem`, and `floor-div-mod`
      with the quotient fitting and the remainder not
    - the other reasons still throw under the same small limits
    - a reason fed back in is `:wrong-type`
    - `module-version-test`
    - limit breaches as the program value on all four VMs
  - Red: 18 failures, 8 errors.
  - Mutation `pair` -> `[q r]`: 2 failures.
- `prelude_parity_test` (parserless, so all hosts):
  - The runners are now `runners-under` a composition. `small-runners`
    is 60 bits and 5 digits.
  - `int-limits-are-catchable-on-every-host-test`: MemoryError from
    the keys of 5e-324 and 2^80, `hash(1)` and `hash(1.5)`, caught. A
    ValueError for `int-result(format 123456)`. An in-limit key still
    works. `finally` runs. MemoryError passes `isinstance` and
    `exc-matches` for Exception. The run continues (1 + 2 = 3).
  - `failed-key-normalization-leaves-containers-unchanged-test`:
    - dict insert, getitem, contains, dict-del-quiet, tuple key and
      set add each raise MemoryError
    - the dict and set contents are `=` before and after
  - `int-defects-stay-host-failures-on-every-host-test`: integer
    `:wrong-type`, `:arity`, and stub results `:zero-division` and
    `:wrong-type` fail the run under `py/try` (`[:thrown ...]`), and
    the guest handler never runs.
  - Adapted digit-limit row: the module answers the reason, and
    `py/int-result` raises ValueError.
  - Red: 28 failures, all "integer primitive refused" or an unresolved
    `py/int-result`.
  - Mutations, each failing tests:
    - unwrapped float-key denominator: 8
    - MemoryError -> OverflowError: 8
    - fail-closed removed: 8
    - unwrapped P: 4
    - MemoryError under BaseException: 4
- `e2e_test` (JVM, from source, naive and under no-op hooks, four VMs):
  - Under a 60-bit/5-digit registry, the program catches MemoryError for
    `d[5e-324] = ...` with `finally`, for lookup (`e.args == ()`), for
    `s.add`, which `except Exception` catches with
    `isinstance(e, MemoryError)`, and for a tuple key and `hash(1)`.
  - It prints `{1: 'a'} {1, 2} a`.
  - It ends with an uncaught `{:type "MemoryError", :args []}`.
  - This test was written after the implementation. Its mutation check
    (unwrapped float-key denominator) fails it 8 times.

Verification (JVM only):
- `clojure -M:test -e :slow` on the ten focused namespaces below:
  214 tests, 2253 assertions, 0 failures.
  - yin.vm.integer-test
  - prelude-parity
  - float-address
  - e2e
  - e2e-c1
  - e2e-c2
  - safepoint
  - lower-portable
  - lower
  - yin.vm.data-test
- kondo: 0 errors, 0 warnings on all touched files.
- `cljstyle check`: clean. `cljstyle fix` was blocked by the permission
  sandbox, so I applied its one suggested hunk by hand. `mise exec --
  cljstyle check` ran.
- ASCII only. Every new line is at most 80 columns, except the re-minted
  golden lines, which keep the existing golden format.
- The fresh worktree needed `bb gen:python-antlr` before `e2e_test`
  could load.

## Cross-host reasoning (Node, Dart not run)

- `keyword?` in `pair` and `=` between a BigInt carrier and a keyword are
  false on every host, with no hashing of the carrier.
- I chose `=`-chains over a map lookup in `py/int-refusal?`. A lookup
  would hash a JS BigInt key.
- The test forms use `{:py/float 5.0E-324}` through `with-float64`, and
  2^80 is built by multiplication, as the existing rows do. There are no
  integral float literals.
- `"Cannot apply non-function"` and `integer/refusal-message` are
  ex-info messages built in cljc, so they are the same on every host.

## Questions (smallest choice taken)

1. Fail-closed covers the module's documented reason vocabulary only.
   The guest has no keyword predicate: `data` has `number?` and
   `callable?` but no `keyword?`. So a `:yin.vm.integer/*` keyword
   outside the nine documented reasons would pass `py/int-result`
   unchanged. At integer-shaped sites the next integer call refuses it as
   `:wrong-type`. At string and pair sites it would not be caught.
   - Two remedies, both larger than S3a: an additive `data/keyword?`
     export, or per-shape translators.
   - Do you want either?
2. The MemoryError message: bare, with args `()`, as CPython raises it.
   A descriptive message is the alternative.
3. ValueError text: "Exceeds the limit for integer string conversion",
   with no N. The module no longer returns the limit value, so N would
   need the prelude to know `::max-digits`.
4. `py/int-refusal?` costs up to nine `=` checks per wrapped call. If
   that matters, a `data/number?` fast path is one module call instead.

## Observed, not fixed (out of scope)

`py/exc-matches` (prelude, around line 635) passes
`{:py/str (data/str-concat ...)}` to `py/type-error`. Map literal values
are not evaluated in prelude notation, as I found while writing
`py/int-result`. So that TypeError's message is the unevaluated form,
not the string. It needs a `(py/str (data/str-concat ...))` fix in some
slice; I did not change it here.
