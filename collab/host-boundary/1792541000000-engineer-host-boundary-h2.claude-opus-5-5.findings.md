Created-GMT: 2026-10-21
Coding-Agent: claude (claude-opus-5-5), Implementation Engineer
Worktree: /Users/sto/workspace/datomworld-host-h2, branch host-h2 (base 6b21521c, H1)
Governing: docs/design/dao.stream.host-boundary.md r10, §2.4, §3.1, §4.2 table B, §8 H2, §8.1, §8.2

# Slice H2: consumers migrated, `yin.vm.integer.host` deleted, library-mode gate (uncommitted, awaiting review)

Nothing is committed or staged except the `git rm` of the shim. Commit
waits for Architect sign-off and the non-Claude confirmation (§14).

## Verdict

H2 is implemented and green on the JVM, on Node (with one pre-existing
crashing namespace excluded, F1) and on Dart (full lane: 4105 passed, 1
failed in a new test whose oracle I then fixed and re-ran green, F8).
Four findings need an Architect ruling before commit: F2 (five
migration-source files keep arms outside §8 H2's line list, so they stay
on list 1), F3 (`dao.jing.cbor/refusal` now reads the profile's refusal
key), F4 (`safe-native?` did not fit the two safe-integer sites), F5
(debruijn_code now encodes `:bigint` on JS/Dart instead of refusing it).

## What changed

**Consumers of `yin.vm.integer.host`** (require swap only): `yin/vm.cljc`,
`yin/vm/engine.cljc`, `yin/vm/values.cljc`, `yin/vm/data.cljc`,
`yin/vm/integer.cljc`, and the three `int_{heap,literal,ops}_test.cljc`.
`src/cljc/yin/vm/integer/host.cljc` is deleted (`git rm`). The shim
mention in `yin/vm/integer.cljc:14` and the `dao.host.integer` ns doc are
reworded.

**Float-text kernel (table B, R7-1).** `yin.vm.integer` delegates to
`dao.host.float`: `ratio->float` (a wrapper maps
`:dao.host.float/float-overflow` to `:yin.vm.integer/float-overflow`, per
H1 F4), `trunc-big`, `signed`, `pow2`, `digits->big` (also used by
`int-parse`), `float-digits`, `decimal->float`. `int-float-digits` and
`int-decimal->float` keep their own type, syntax and digit refusals. The
private copies (`pow2`, `compare-scaled`, the old `ratio->float`, `trunc-big`,
`chunk-digits`, `digits->big`, `ten`, `small-powers-of-ten`, `pow10`,
`scaled`, `float-ratio`, `decimal-exponent`, `strip-trailing-zeros`,
`max-significant-digits` and the two kernels' arithmetic) are deleted
(-272 lines). The design's oracle passes unchanged through the rewired
module: `integer_test` and the three Python int tests plus
`int_contract_test` give 62 tests / 1530 assertions / 0 failures (JVM, slow
excluded).

**Profile consumers (§8 H2).**
- `dao.jing.cbor` (-555/+151): the big-integer helpers, the
  `Float64`/`Rational`/`Decimal` carriers and print-method, the
  BigDecimal/Ratio construction and access, the float bits and fields, the
  byte-array helpers and the strict UTF-8 decode are now `dao.host.*` calls.
  A `host` wrapper turns a `:dao.host/refusal` into cbor's `::refusal` of the
  same class. Mantissa strings go through `integer/parse-decimal` (H1 F4).
  `cbor/->Rational` is kept as an alias because two tests call it. The corpus
  is byte-identical: the frozen hash set, `cbor_conformance_test` (367 run, 5
  documented skips) and the fixtures all pass. The JVM digests were refusal
  `915515bc…` and canonical `8268b4d3…`; I had no earlier digest to compare
  them with.
- `debruijn_code`: the little-endian float hex is now a byte reversal of
  `float64->bits`; `:315` uses `big-carrier?`; code units come from `text`.
  `debruijn`: `:167` uses `bytes?`, `:241` `code-unit`, `:310-321`
  `text/nfc`, and `:855-885` int64/double hex are portable. The unorm require
  and the typed-data imports are gone.
- `yin.vm.data`: `integral` uses `safe-native?`, `exact-integer?` and
  `float/integral?`. The private `code-points` and code-unit copies are
  deleted in favour of `text/code-points` and `text/units->string`.
- `chunks`, `jing.dht`, `linker:1229`, `datagram:63-66`, `sign` `host-bytes`
  and `jvm-pair`'s copy now use `dao.host.bytes`. `sign`'s `utf8` is
  deliberately left for H3, because switching it is H3's named behaviour
  change (§8 H3).
- The eight H0 portability corrections:
  - `bytes->str` decodes with `text/utf8-decode`; the JVM's two-arity
    encoding form is kept behind one `:clj` arm (`vm.cljc:409`, OQ11).
  - PHP literals go through `parse-decimal`+`demote` and
    `decimal-text->float` (new public `yang.php/php-number`; the lexer's
    `"1."` is read as `"1.0"`).
  - The index comparator takes the native path only for two native exact
    integers; every float goes to `cbor/num-compare`.
  - The packet uses `text/code-points`.
  - The two safe-integer checks use the profile (see F4).
  - The three byte lengths use `bytes/length`.

**Arrays (§2.4).** `asort` is stable on every host: the JVM's
`parallelSort` is replaced by `Arrays/sort` (TimSort), JS keeps `.sort`
(stable since ES2019), and Dart gets a merge sort, because `List.sort` is
not stable. `acopy` checks both ranges before any write on JS and Dart
(the JVM's `System/arraycopy` already checks first). `aset` on JS refuses
an invalid index. New `test/dao/data/arrays_test.cljc` covers stability,
overlap in both directions and the no-partial-write cases for `acopy` and
`aset`.

**Gate and tool.**
- `closure.clj` has a new `--required-by NS` mode. It reads the ns form of
  every source file under `src/` and `test/` on each lane that reads it, and
  exits 0 for none, 1 for found, 2 for a scan failure. Its `class-rows` drop
  `yin.vm.integer.host` and add the five `dao.host.*` namespaces as
  `:profile-internal-pure`.
- The closure edn files are regenerated. They now hold **146/144/145 files,
  137 shared, 159 in the union**: H0's totals minus the shim plus the five
  profile files. `--check` passes.
- `gate_test.clj`:
  - The totals assertions are updated.
  - `profile-additions` is retired to `{}`, because the closure reaches all
    five files.
  - List 1 loses the shim and the **twelve** files that now hold no arm.
    These are the eight H0 profile consumers (all but `yin/vm.cljc`, which
    stays `:pending` for OQ11) plus `chunks`, `jing/dht`, `linker` and
    `datagram`.
  - List 2 gains the `:m-entry` callers `chunks` 1, `cbor` 1 and `sign` 1,
    each with its freeze-rule reason.
  - **Library mode (§8.2)** is new. It scans the closure minus `dao/host/`
    and minus the codec-track files. It reads each file per lane feature
    set and counts symbols, type hints, string libspecs and imported class
    names, but not keywords, strings, comments, quoted forms or
    `(comment ...)`. It looks for `BigInteger`, `BigDecimal`,
    `clojure.lang.Ratio`, `java.text` and `unorm`, and reports a scan
    failure distinctly from "no hits".
  - Library-mode tests: the current tree passes with 0 hits, while the
    profile files on their own fail (which shows the scope exclusion is
    what passes them). The negative fixtures are six executable references
    that fail (a call, an import, a type hint, a reader-arm constructor, the
    `unorm` string require, `java.text.Normalizer`), plus one fixture naming
    the libraries only in a docstring, comment, string, keyword and quoted
    form, which passes. Scan-failure cases: no files, a nil file, an
    unreadable file.

**Pre- and post-deletion gate (§8 H2).**
- Pre-deletion, with the shim restored from HEAD: `--required-by
  yin.vm.integer.host` read 573 files, printed `none` and exited 0. A grep
  found the name only in the shim itself, the `dao.host.integer` docstring
  and one `gate_test` string.
- Positive control: `--required-by dao.host.integer` lists its 20
  requirers.
- Post-deletion: the file is absent, 572 files were read, the tool printed
  `none` and exited 0.
- Scan-failure control: with an injected truncated ns file, the tool
  printed `SCAN ERROR …` and `SCAN FAILURE` and exited 2.
- After deletion, the name is mentioned only as history in the
  `dao.host.integer` docstring and the gate's totals label.

## Behaviour changes (name them in the slice report)

1. `bytes->str` is strict UTF-8 on all three hosts. Invalid input is refused
   with `:dao.host/refusal :invalid-utf8`; before, JS and Dart read each byte
   as a char code and the JVM substituted U+FFFD. On JS the argument must now
   be a `Uint8Array`. The JVM encoding arity is unchanged (OQ11).
2. PHP integer literals are exact on every lane: a JS BigInt above 2^53, and
   a big integer where the JVM and Dart used to throw above 2^63.
3. `acopy` on JS and Dart checks before writing (§2.4); `aset` on JS refuses
   an invalid index; `asort` is stable on Dart, and the JVM no longer uses
   `parallelSort`.
4. Index comparator: JS and the JVM lose the double fast path. The order is
   the same, but it is slower.
5. Tightenings at the profile edge, none hit by any test or corpus case:
   - `float64-from-bits` takes only 16 lowercase hex digits.
   - Mantissa strings follow `parse-decimal`'s grammar.
   - JS `decimal`/`ratio` refuse an unsafe integral Number.
   - `bytes/length` refuses a non-byte value.
   - `chunks/absorb` ignores a non-bytes piece instead of throwing.
   - `dht` `ints->bytes` refuses values outside 0-255 instead of wrapping
     them.
6. On JS, implicit numeric coercion of a `Float64` carrier now throws the
   profile's ex-info (F3).

## Findings for the Architect

- **F1 (pre-existing, not H2).** On Node, `yin.repl.host.node-test` kills the
  test process ("An invalid or duplicated subprotocol was specified",
  `ws/node.cljs:203`). The test passes `:ws/codec` on the descriptor, but
  `connect!` reads it from the adapter map, so `codec-name` of nil goes to
  `ws`. Both files were last changed by C3 (`a1308a6c`); H2 touches neither.
  Shadow's autorun exits 0, so **`bb test:cljs` exits 0 and runs no
  namespace after it**: every `yin.vm.*` and later namespace is silently
  skipped. This needs a codec-track fix and a ledger row. I ran Node with only
  that namespace excluded (below). I did not check master, to avoid writing
  to another session's checkout; this diagnosis is R, from reading the code.
- **F2 (list 1).** Five `:migration-source` files kept non-exempt arms that
  §8 H2's line list does not name, so they stay on list 1. I gave them
  `:stays` with a reason; `"H2"` would mean removed by this slice. Please
  rule on whether each should be reclassified (e.g. `:dialect-arm`, which
  needs a closure class change), ported in a follow-up, or ruled on by an
  amendment.
  - `debruijn` `:174,191,207,232,1242,1244,1374`: JS/Dart number
    classification (one JS number type).
  - `debruijn_code` `:213` JS int64 hex (it could take debruijn's portable
    version), and number classification at `:276,288,305,323,373,412,620`.
  - `data` `:388` `float-value` host double conversion.
  - `values` `:116,122` `#?(:cljd nil :clj (defmethod print-method …))`.
    These are table C print laws, but a nil arm is not a §3.3 pattern.
  - `cbor` (now `"H4"`): the writer, plus
    - `:91` Dart `runtimeType` in refusal text;
    - `:217` JS unsafe-integral Number;
    - `:227` `float64?` (`dao.host.number` has no `float64?` predicate, a
      table A gap if you want one);
    - `:1026` Dart `with-meta nil`.

  So list 1 shrinks by 13 (the shim plus twelve), not by every
  `:migration-source` H2 file.
- **F3.** `dao.jing.cbor/refusal` now also reads `:dao.host/refusal`.
  Without it, the JS `Float64` carrier (now `dao.host.number`'s) refused
  coercion in a shape cbor callers could not read. `cbor_test`
  (`float64-carrier-refuses-coercion-test`, 27 assertions) and
  `float_address_test` (`decoded-float-under-bare-plus-test`, 4) failed on
  Node before the change. One test line now calls `cbor/refusal` instead of
  reading `::cbor/refusal` directly. The class is unchanged.
- **F4.** I did not use `integer/safe-native?` at
  `debruijn_register_code:437` and `debruijn_register_effects:128`. On the
  JVM it accepts only `Long`, and `count` returns an `Integer`, which failed
  59 assertions. The check is now `exact-integer?`, not `big-carrier?`, and
  `0 <= x <= 2^53-1`, the same bound on every host. That narrows the JVM and
  Dart to 2^53-1, which no test or caller reaches. You may prefer
  `safe-native?` to accept every native exact carrier: that is a profile
  change, and I did not edit `dao.host/*`.
- **F5 (unplanned widening).** With `host-bigint?` now `big-carrier?` on
  every host, `debruijn_code` classifies and encodes `:bigint` on JS and Dart
  where it used to refuse `:unsupported-value`. Its test was updated. JVM
  bytes are unchanged, and no address was ever produced on JS/Dart for these
  values. Accept, or I restore the refusal.
- **F6 (§2.4 audit).** Direct host-array mutations before H2:
  - `chunks` concat (setRange, arraycopy, `.set`);
  - `cbor` indefinite byte-string join;
  - `sign` `jvm-pair` `System/arraycopy`.

  All three now call `bytes/copy!` into an array that is fresh or owned by
  the provider, and all three are on list 2. Named exception, kept:
  `sign/generate`'s `.nextBytes` into a fresh `(byte-array 32)`. It is
  entropy fill, not a copy, and is deferred under §9.1. No other direct
  mutation is left in the touched files. `arrays.cljc` calls its own
  `aset`/`acopy` unqualified, which the gate does not count, as designed.
- **F7.** Arrays carry over the old asymmetry: JVM refusals are host
  exceptions (`ArrayIndexOutOfBounds`), while JS and Dart refusals are
  `:dao.host/refusal :out-of-range` ex-infos. The tests assert only that the
  call throws and that the target is unchanged.
- **F8.** The new stability test first failed on Dart because its oracle
  used `sort-by`, which on ClojureDart is the unstable `List.sort`. The
  oracle now builds the expected order by filtering on each key, with no
  host sort, and the test's `for` loops became `mapv` (the cljd chunked-seq
  trap). The sort itself was correct.

## Verification

- JVM:
  - `bb test:sub:clj dao.host`: 5 namespaces, 45 tests, 1048 assertions,
    0 failures.
  - `bb test:sub:clj dao.stream`: 35 namespaces, 503 tests, 3905
    assertions, 0 failures.
  - `bb test:clj` (fast, whole repo): 4226 tests, 287565 assertions,
    0 failures, 0 errors. That run predates F3 and F8.
  - After F3: cbor, cbor-conformance, float-address and gate re-run gave 57
    tests, 815 assertions, 0 failures.
  - After F8: arrays-test gave 3 tests, 49 assertions, 0 failures.
- Node: shadow `:test` with `--config-merge '{:ns-regexp
  "^(?!yin\\.repl\\.host\\.node-test$).*-test$"}'`, run after F3: 239
  namespaces, 4204 tests, 152124 assertions, 0 failures, 0 errors. That
  covers `dao.host.*`, arrays, index, php-literal and bytes-to-str. The
  plain `bb test:cljs` crashes as F1 describes. The F8 test-only change was
  not re-run on Node, but the earlier Node run passed with the old oracle.
- Dart:
  - `bb test:cljd`: 4105 passed, 1 failed (F8).
  - After the fix, `bb test:sub:cljd dao.data dao.host`: 11 namespaces,
    116 tests, all passed.
- `clj -M:kondo --lint` on all 37 touched files: 0 errors, 0 warnings.
- Not run: `bb test:changed` (the change to `src/dev/` makes it exit 2,
  "wide"; the full lanes above stand in for it) and the slow tier (§8:
  once after H4).
- Counts versus H1: the gate has 14 tests (H1: 12; +2 library-mode tests)
  and 178 assertions (H1: 180; the per-addition checks are vacuous now that
  `profile-additions` is `{}`). New test namespaces: `dao.data.arrays-test`
  (3), `yang.php-literal-test` (2), `yin.vm.bytes-to-str-test`. One new
  deftest in `dao.space.index-test`. I did not record a whole-repo baseline
  count at H1, so I cannot give "counts unchanged" as an exact delta.

## For the reviewer

- `git -C /Users/sto/workspace/datomworld-host-h2 status`: 34 modified
  files, 1 deletion, 3 new test files.
- Ask: rule on F2-F5, then sign off. F1 needs a codec-track owner.
