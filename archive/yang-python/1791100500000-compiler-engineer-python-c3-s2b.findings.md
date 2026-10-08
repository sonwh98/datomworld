Completed-GMT: 2026-10-04
Coding-Agent: claude (opus-5-5)

# Python C3 slice S2b: float `is` identity and hex integer keys

No git writes. Dart lane not run (orchestrator's). Prelude content-address
goldens re-minted ONCE, last, after every prelude edit (see "Goldens").
Master's C2-S4 (generator expressions) is untouched.

## Changed files

- `src/cljc/yin/vm/data.cljc`: new `:pure` export `content=` (arity 2),
  `data-content=`: `(or (identical? a b) (if (or callable/ref a b) (= a b)
  (cbor/content= a b)))`. Callable = existing `callable?` (fn, Closure,
  Continuation); ref = map whose `:type` is `:stream-ref`, `:cursor-ref` or
  `:cell-ref`. Tuples (plain maps) recurse through `cbor/content=`.
- `src/cljc/yang/python/antlr/prelude.cljc`: `py/is` =
  `(data/content= a b)`, `py/is-not` its negation (comment updated);
  `data/content=` added to `host-names` (the declared host exports; not
  part of any address); the
  four `integer/format` calls in `py/float-key` (3) and `py/key` (1) take
  radix 16; ns docstring: keys are lowercase hex, only the 1075-bit
  requirement remains (324-digit clause dropped).
- `test/yin/vm/data_test.cljc`: export-set pin gains `'content=`; new
  `content=-test` (NaN by subtraction vs decoded canonical NaN, in a map;
  signed zeros bare and in a vector; 1 vs float64 1; true vs 1; two equal
  closures and a closure vs 1 by host `=`; refs by host `=`; arity
  refusal).
- `test/yang/python/antlr/prelude_parity_test.cljc`:
  - new `is-table` + `float-is-on-every-host-test` (parserless, all four
    VMs): `0.0 is -0.0` false while `py/eq` true; NaN (inf-inf) is NaN
    (0*inf) and is decoded canonical NaN; same binding `x is x` for a NaN;
    `1.0 is 1.0` (two computations) true; `1 is 1.0` false; `True is 1`
    false; `inf is inf` true, `inf is -inf` false; 2^53+1 by parse and by
    `integer/add` true; `(nan,) is (nan2,)` true; `(0.0,) is (-0.0,)`
    false; `is-not` is the elementwise negation over all 289 pairs; the
    law `(py/is a b) = (= canonical-bytes(a) canonical-bytes(b))` over all
    17x17 pairs, bytes computed on each host from the values the VM
    returned.
  - key expectations to hex ("ccccccccccccd"/"80000000000000",
    "180000000000000000000000000", "20000000000001", "fffffffffffff");
    `two-1074`, `two-1022`, `max-finite` helpers now built as hex
    (`"4"`+268 zeros, `"4"`+255 zeros, `"fffffffffffff8"`+242 zeros);
    added rows `(py/key 10)` -> "a" and `(py/key 2^53)` ->
    "20000000000000" (fable's table).
  - new `big-integer-keys-past-the-digit-limit-on-every-host-test`:
    2^20000 (6021 decimal digits > max-digits 4300) keys as "1"+5000
    zeros, differs from the key of 2^20000+1; dict insert, replacement by
    an independently built 2^20000, lookup, deletion
    (`py/dict-del-quiet`), membership after deletion, set dedup/membership
    with big and big+1, tuple key `(1, 2^20000)` lookup; and
    `(integer/format 2^20000)` (decimal, the str/repr path) still refuses
    with `::integer/reason :digit-limit` on every VM.
- `test/yang/python/antlr/float_address_test.cljc`: `dict-keys-test` gains
  a fifth key, 0.1 -> ["ccccccccccccd" "80000000000000"] (the original four
  keys are single digits whose hex equals their decimal, so regenerating
  alone would not have moved the golden or pinned hex); the canonical-bytes
  golden regenerated once on the JVM; prelude/program address goldens
  re-minted (below).
- `docs/design/yang.antlr.md` 8.5.4: status sentence notes the S2b
  follow-up; ruling-6 shape line is `numerator-hex denominator-hex` with
  the exact form; "Known limits" bullet rewritten (digit sentence and
  324-digit clause dropped; only the 1075-bit requirement, MemoryError);
  the "Integer `is` is value-based..." paragraph replaced with fable's
  text verbatim; S5 row says it landed with its S2b follow-up. 8.11: the
  "`is` on immutable values" bullet gains a pointer to 8.5.4. No other
  S-row edited.

## Module-version rule

`yin.vm.data` has no `module-version` (only `yin.vm.integer` does, at 1,
"a change to any export's semantics is a new version"). `content=` is an
additive export with unchanged semantics for every existing export, so no
version exists to bump. The export set is pinned by `every-export-is-pure-test`,
which was updated. If the L-a host-export profile identity later hashes the
export table, adding an export changes the `data` profile address; that is
the expected effect of an additive export.

## Red before / green after

- Q1, JVM, before the prelude change (py/is = host `=`):
  `float-is-on-every-host-test` failed 24 assertions = 6 per VM x 4 VMs:
  zero/neg-zero (true, expected false), nan/nan2 and nan/nan3 (false,
  expected true), t-nan/t-nan2 (false), t-zero/t-neg-zero (true), and the
  canonical-bytes law. **Mutation evidence:** this is exactly the revert
  to `(= a b)`; the zero row and the two-NaN row fail on the JVM, checked.
  Green after.
- Q2, JVM, with Q1 in and the four format calls temporarily reverted to
  decimal: `numeric-keys-hash-and-is-on-every-host-test` (4 VMs),
  `big-integer-keys-past-the-digit-limit-on-every-host-test` (4 VMs;
  the 2^20000 key refuses `:digit-limit`), `dict-keys-test` value and
  bytes (4+4). Green after restoring hex.
- `content=-test`: new export, red by construction before (export absent);
  green after on JVM and Node.

## Results

- Node focused (shadow `compile test` with `--config-merge` ns-regexp over
  data-test, prelude-parity-test, float-address-test, safepoint-test,
  integer-test; shadow also ran lower-portable-test): **Ran 101 tests,
  1333 assertions, 0 failures, 0 errors.** The JVM-regenerated dict-keys
  golden and the re-minted prelude/program addresses match on Node.
- JVM focused (`clojure -M:test` with `-n` for data-test,
  prelude-parity-test, float-address-test, safepoint-test, e2e-test,
  e2e-c1-test, e2e-c2-test, lower-test, lower-portable-test,
  integer-test; no tag filter, so slow tests ran too): Ran 218 tests,
  2361 assertions. All 54 failures were in `e2e_test.clj:222`, the
  `canonical-is-closed` check: `data/content=` was missing from
  `prelude/host-names`. Fixed by adding it there, which changes no
  address. Rerun of `yang.python.antlr.e2e-test`: **Ran 38 tests, 461
  assertions, 0 failures, 0 errors.** Every other namespace in the run
  was already green.

## Goldens (re-minted once, last, on the JVM)

- `prelude-addresses-test` base prelude: 583cb250... -> 204fac90...
  (hook prelude unchanged: 2d601908...).
- `program-addresses-test`: A a9678aef... -> 496c1a60...; A' aff24c9f...
  -> 594b9905...; record 6d96d27e... -> 630c95b5...; prelude subtree
  535f1cbe... -> 9197b080....
- `dict-keys-test` canonical-bytes: regenerated (with the added 0.1 key).
No other test file held these addresses (grep over test/, src/, docs/).

## Deviations

- `cljstyle fix`/`check` could not run: this session's permission gate
  refused the `cljstyle` command. clj-kondo is clean (0 errors, 0
  warnings) on all five changed Clojure files. Please run
  `cljstyle fix` then `check` before commit.
- Line numbers in the brief (~686-712) had moved to ~770-800 on this
  branch (C2-S4 landed above them); edits were made by content.
- Added the 0.1 key to `dict-keys-test` (reason above) and two small key
  rows (10, 2^53) from fable's table.
- `bb gen:python-antlr` was run to build the ANTLR classes this fresh
  worktree lacked (build/, gitignored).
- Astra's "repeat with a deliberately small digit budget and finite float
  keys" was not added: the brief's list did not include it and the
  parity runners close over one composition. It is cheap to add (a second
  `opts` with e.g. max-digits 10) if wanted.

## Open items

- `in` / `list.index` NaN shortcut (CPython uses `is or ==`; with
  `py/eq` alone `x in [x]` is False for a NaN). Not in scope; S4 decision.
- `data/content=`'s ref test is structural on `:type`; a guest-built map
  `{:type :cell-ref ...}` takes the host-`=` arm, which for plain data
  agrees with `cbor/content=` except on float payloads inside it. Python
  guests cannot build such a map through the prelude, so no action taken.
- Dart lane (orchestrator): `content=-test` builds -0.0 as `(* -1.0 0.0)`
  per the CLJD unary-minus trap; the parity table builds it the existing
  way (row 787 style).
