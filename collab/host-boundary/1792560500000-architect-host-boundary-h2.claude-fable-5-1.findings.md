# Slice H2 architectural review: consumer migration and shim deletion

Date: 2026-10-21
Reviewer: Claude Fable 5.1, System Architect & Reviewer
Branch: host-h2, worktree /Users/sto/workspace/datomworld-host-h2
Reviewed: HEAD 6b21521c (H1) plus the uncommitted tree: 34 modified files, `git rm` of `src/cljc/yin/vm/integer/host.cljc`, 3 new test files.
Authority: docs/design/dao.stream.host-boundary.md r10, §2.4, §3.1, §3.3, §4.2 (tables A, B, C), §4.3 (table D), §8 H2 row, §8.1, §8.2, §8.3, §10, §13 OQ11, §14.
Engineer report: collab/1792541000000-engineer-host-boundary-h2.claude-opus-5-5.findings.md

**Verdict: SIGNED OFF WITH CHANGES.**

H2 does what the §8 row commissions: every named consumer now reaches the host through `dao.host.*`, the float-text kernel is consumed from `dao.host.float` and the module's private copy is gone, the eight H0 portability corrections are in with their table D tests, `dao.data.arrays` has the stable sort and prevalidation, the shim is deleted under the closure-tool gate, and the §8.2 library mode exists with its two negative tests. Corpus bytes are identical. The five engineer findings are ruled below: F1 is pre-existing and goes to the codec track with a ledger row; F2 is accepted as a design under-enumeration and resolved by one classification ruling plus a small follow-up slice (H2b); F3, F4 and F5 are accepted as implemented.

The required changes are gate data edits (slice values and reason text in `gate_test.clj`) and documentation (one ledger row, r11 amendments to the design doc). None touches runtime behaviour; the JVM gate lane is re-run after the data edit and no further review round is needed. Standing rule (§14): this is a Claude review of Claude-authored code; the orchestrator obtains the non-Claude confirmation before commit, as for H0 and H1.

## 1. What I verified myself

| Check | Result |
|---|---|
| Closure tool `--required-by yin.vm.integer.host` (post-deletion) | read 573 files under `src test`, printed `none`, exit 0 |
| Positive control `--required-by dao.host.bytes` | 14 requirers, exit 1 |
| Closure tool `--check` | 146 / 144 / 145, 137 shared, nothing stale, exit 0 |
| `bb test:sub:clj dao.host` | 5 namespaces, 45 tests, 0 failures, 12 s (gate test included) |
| JVM: `dao.data.arrays-test`, `yang.php-literal-test`, `yin.vm.bytes-to-str-test`, `dao.space.index-test`, `yin.vm.debruijn-code-test`, `yin.vm.integer-test`, `dao.jing.cbor-test`, `dao.jing.cbor-conformance-test` | 147 tests, 1709 assertions, 0 failures |
| Textual mentions of `yin.vm.integer.host` in `src`, `test`, closure edn | only the `dao.host.integer` docstring (history) and the gate's totals label, as §8 H2 permits |
| Direct host-array mutation outside `dao/host/` and `dao/data/` (`System/arraycopy`, `.setRange`, `.set`, `aset`, `.nextBytes`) | only the two entropy fills: `sign.cljc:122` (`generate`, the named §9.1 exception) and `space/dht.cljc:149` (deferred §9.1, not an H2 file) |
| Codec-track files (§8.3) | none touched; `yin/vm/data.cljc` is an H0-H4 file, not the codec track's `dao/stream/data.cljc` |
| §9 scope | nothing started; `sign/generate` untouched |

The three-lane subsystem run, the Node run with the F1 exclusion, and the Dart full lane are taken from the engineer's and orchestrator's records (jupiter: CLJ 45 / 1048, CLJS 31 / 876, CLJD 31, all 0 failures; new suites 7 / 70 / 0). I did not re-run Node or Dart.

## 2. Review of the changes

**Consumers of the shim (require swap).** `vm`, `engine`, `values`, `data`, `integer` and the three Python integer tests: alias kept, only the namespace changes. Confirmed by diff. Two require forms are now out of alphabetical order (`engine.cljc`, `debruijn_code.cljc`); nit, R4.

**Float-text kernel (table B, R7-1).** `yin.vm.integer` keeps exactly the refusal preambles the design assigns to it (`wrong-type!`, `refuse!`, `digit-values!`, `float!`, `big!`) and delegates `ratio->float`, `trunc-big`, `signed`, `pow2`, `digits->big`, `float-digits`, `decimal->float` to `dao.host.float`; the overflow sentinel is mapped `:dao.host.float/float-overflow` to `::float-overflow` as H1 F4 required. 272 lines of private copy deleted. The unchanged oracle (`integer_test`, `int_contract_test`, the three Python int tests) passes through the rewired module on three lanes: table D's H2 condition for this row is met.

**`dao.jing.cbor` (-555 / +151).** The big-integer helpers, the three carriers, their construction and access, float bits and fields, byte helpers and the strict UTF-8 decode are now profile calls. Every number refusal that used to be a cbor refusal still is: the `host` wrapper re-throws a `:dao.host/refusal` as `::refusal` of the same class, and the two refusals the profile throws outside any codec call (JS carrier coercion) are read by `cbor/refusal` (F3). The corpus is byte-identical and the conformance test runs 367 cases with the 5 documented skips. Indefinite byte-string assembly is now `bytes/alloc` plus `bytes/copy!` into the fresh array (list 2 `:m-entry 1`). `float-wire` goes hex text to bytes, which is correct but round-about; H4 rewrites the writer and may give `dao.host.float` a bytes form then. `->Rational` is kept as an alias of the profile constructor for two test callers (R5).

**`debruijn` and `debruijn_code`.** The lines §8 H2 names are migrated: bytes predicate, code units, NFC through `text/nfc`, the little-endian double hex as a byte-pair reversal of `float64->bits` over `number/float-value`, `big-carrier?` at `:315`. `debruijn`'s `int64-le-hex` is now one portable path over `promote`, `add 2^64` for negatives and `magnitude-bytes` read low byte first; I checked the edge cases (0 gives eight `00`, -1 gives eight `ff`, the sign-byte strip at 128 and 2^63) against the old `long` arm and they agree; the 80-deftest golden suite pins the rest. `debruijn_code`'s `:bigint` content is `to-radix-string` of the promoted value, equal to the old `(str v)` on the JVM (no `N` suffix on either carrier), so JVM addresses are unchanged; see F5 for JS and Dart.

**The eight H0 portability corrections (§3.1).**
- `bytes->str`: one arity over `text/utf8-decode` on every host; the JVM two-arity encoding form is spliced in with `#?@(:cljd [] :clj [...])`, `:cljd` first. The published arity list stays `[1 2]`; `bytes_to_str_test` covers table D (3) including the JVM arity. One pre-existing fact worth the owner's attention for OQ11: the arity list was `[1 2]` on JS and Dart before H2 too, where only the one-arity function exists, so a guest `(bytes->str b "UTF-8")` on JS silently ignores the second argument (JavaScript drops extra arguments) and on Dart fails at call time. Not an H2 change; recorded under OQ11 in §5.
- PHP literals: `yang.php/php-number` over `parse-decimal` plus `demote` and `decimal-text->float`; `"1."` is read as `"1.0"`; the lexer never produces a leading sign or a bare `.5`, and hex or underscore forms failed the old host parsers too, so no other grammar changes. `php_literal_test` covers table D (4) including the canonical-carrier law.
- Index comparator: native `compare` only for two native exact integers (`exact-integer?` and not `big-carrier?`), every float through `cbor/num-compare` on every host; the new `index_test` deftest pins signed zero, NaN last, the infinities, and `1` tying `1.0` (R7-3). JS `-0` is excluded from `exact-integer?` by the profile and so takes `num-compare`, where it ties `0.0`: correct.
- Packet code points: `text/code-points`, the table B function. The three byte lengths: `bytes/length`. The two safe-integer checks: see F4.

**`yin.vm.data/integral`.** The rewrite over `safe-native?`, `exact-integer?`, `promote`/`compare-big`/`demote`, `host-float?`, `float/integral?` and `trunc-native` is value-for-value the old arms, and strictly better in two corners: a JVM boxed `Integer` now answers a `Long` instead of passing through as is, and a JS `-0` answers `0` instead of `-0`. `data_test` and the engine suites pin it.

**`chunks`, `jing.dht`, `linker`, `closure`, `sign`, `datagram`, `query`, `rows`.** Mechanical; no arm left in `chunks`, `dht`, `linker`, `datagram`, `closure`, `query`, `rows`. `chunks/absorb` renamed its `bytes` parameter to `piece` to avoid shadowing the alias and now requires a byte array (named tightening 5). `sign/host-bytes` copies a byte array through `bytes/slice` and builds from a seq through `from-vector` after masking to 0-255 (the JVM's signed bytes from `concat`); `jvm-pair` fills the provider's `nextBytes` buffer with `bytes/copy!` from a fresh seed copy (list 2 `:m-entry 1`). `sign`'s `utf8` is correctly left for H3, whose named behaviour change it is.

**Arrays (§2.4, table D).** `asort`: JVM `Arrays/sort` (TimSort, stable), JS `.sort` (stable since ES2019), Dart a bottom-up merge sort taking the left run on ties (stable); the test's oracle builds the expected order by filtering per key with no host sort, which is the right fix for F8. `acopy` on JS and Dart checks both ranges before any write; the JVM's `arraycopy` already did. `aset` on JS refuses an out-of-range index, where an unchecked write would have grown the array. Return values are per §2.4 (`aset` returns the value, `acopy` nil, `asort` the array) and tested. The no-partial-write cases cover six invalid `acopy` shapes, an overflowing same-array overlap, and four `aset` indices, on three lanes. F7's asymmetry (JVM throws the host exception, JS and Dart the profile refusal) is pre-existing and acceptable: `arrays` is "in place (OQ2)" and the law is "refused before any write", which both shapes satisfy. The obligations (ii) of §2.4 are now asserted for arrays as the design requires.

**Gate, tool, allowlists.**
- `--required-by NS` reads ns forms with `tools.namespace` per lane, exits 0 / 1 / 2 for none / found / scan failure. The design's requirement that a tool failure be distinct from "none found" is met (engineer's injected-truncation control; the code path is a `try` per file with errors forcing exit 2).
- `class-rows` drops the shim and adds the five profile namespaces as `:profile-internal-pure`; the edn files are regenerated and `--check` passes. Totals 146 / 144 / 145, 137 shared, 159 in the union: H0's totals minus one plus five. The design doc states the H0 totals as fixed; an r11 note must record the post-H2 totals (C3).
- `profile-additions` is `{}`, as my H1 note required when the closure reaches the files.
- List 1: minus the shim and the twelve files with no arm left. List 2: plus the three `:m-entry` callers with a freeze-rule reason each. This completes §2.4 (iv) and the H2 audit items (a)-(d); the one kept direct mutation is the `generate` entropy fill, named (F6).
- Library mode (§8.2): scans the closure minus `dao/host/` and minus codec-track files, reads per lane feature set, counts symbols, `:tag` hints, string libspecs and imported class names; keywords, strings, comments, quoted forms and `(comment ...)` do not count. The H2 name patterns are anchored so `BigInteger` matches `java.math.BigInteger.` and `BigInteger/valueOf` but a keyword `:rgba8unorm` is never seen (keywords are not walked). Six executable negatives fail, the docstring/keyword/quote fixture passes, three scan-failure shapes are distinct from a pass, and the profile files alone fail (which proves the scope exclusion is what passes the tree). This is the §8.2 deliverable with both negative tests the H2 row names.

## 3. Rulings on the engineer's findings

### F1: `yin.repl.host.node-test` kills the Node process; `bb test:cljs` then exits 0 having run nothing after it. Ruling: pre-existing, codec track, ledger row now.

I read `ws/node.cljs:190-207` and `node_test.cljs:55-70`: `connect!` takes the subprotocol from `(:ws/codec adapter)` and the test passes `:ws/codec` on the descriptor, so the name is nil and the `ws` package rejects the subprotocol list. Both files are C3's (`a1308a6c`); H2 touches neither. The serious part is not the one failing namespace but that shadow's autorun exits 0 after the crash, so every namespace after it in the run is silently skipped and the Node lane reports green. That is a lane-integrity gap affecting every track. Required: a `known-gaps.md` row (C2) owned by the codec track, severity P1 for the lane blindness. For H2's evidence the engineer's exclusion run (239 namespaces, 4204 tests, 0 failures) is accepted as the Node lane result and the exclusion must be stated in the slice report.

### F2: five `:migration-source` files keep arms outside §8 H2's line list. Ruling: the design under-enumerated; the engineer stayed in scope correctly. One classification ruling and one follow-up slice.

I read each line. They fall into three kinds:

1. **Host number-model arms, retained by design.** `debruijn.cljc:174,191,207` (`js-number-class`, `has-integral-number?`, `refuse-unrecoverable-integral!`), `:232` (`numeric-class`), `:1242,1244` (canonical-class checks), `:1374` (the JS-only refusal call); `debruijn_code.cljc:276,288,305,323,373,412,620` (`host-char?`, `host-ratio?`, `host-long?`, `host-double?`, `host-supported-scalar-classes`, the `:ratio` encoder arm, `nonneg-int?`). These arms exist because JavaScript has one number type and Dart and JS have no char or ratio type: they encode which classes a host can carry, not a host library call. Several could be spelled over profile predicates, but not neutrally: `host-long?` on JS is `Number.isSafeInteger`, which admits `-0`, while `safe-native?` refuses it, so a swap changes a JS address for `-0.0` (see R7). They are therefore **retained** arms, like the dialect-arm row, and `:stays` is the right slice value. §8.1 needs a row for them (C3): "Host number-model arms (H2 finding, retained): `debruijn.cljc`, `debruijn_code.cljc`; `cbor.cljc:217,227` join this row when H4 removes the writer". The gate category stays `:migration-source` until H4 so that the design's "removed as each slice lands" column can be amended once; the mapping already permits `:profile-internal-pure` under `:migration-source`.
2. **Portable in a follow-up (H2b).** `debruijn_code.cljc:213` (JS `int64-le-hex`; `debruijn.cljc` now has the portable version, and the two files must agree byte for byte, so the port is a copy with its goldens as oracle) and `data.cljc:388` (`float-value`: the three arms are `exact-integer?` to double through the kernel's `ratio->float`, `number/float-value` for the carrier, else the host double). Both are small and both change no bytes on any lane if done right; they were not in the H2 line list and I do not add them to a slice already at its risk budget. Slice value `:pending` with reason "H2b" (C1).
3. **Table C print laws misfiled.** `values.cljc:116,122` are `#?(:cljd nil :clj (defmethod print-method ...))`. §3.3 exempts "protocol and interface names in deftype bodies and print-method" by intent, but the gate's pattern sees a `defmethod` under an arm, not protocol syntax, so the file never leaves list 1 by itself. Ruling: the file is a table C runtime prerequisite and belongs in the `:runtime-prereq` category with closure class `:runtime-prereq` (a `class-rows` data edit plus regenerated edn), not an extension of the exemption pattern (a `defmethod` under an arm is a real arm). That is also H2b: slice value `:pending`, reason "H2b: category move to :runtime-prereq (table C)" (C1).

`cbor.cljc` is `"H4"` and stays regardless; the reason text the engineer wrote is right. The `:227` `float64?` gap is not a table A gap: cbor's `float64?` is codec classification policy (on JS a carrier **or** a non-integral Number) and belongs to the codec, not the profile; H4 decides its final shape. No 77th entry.

Net effect on §8.1: list 1 shrinks by 13 in H2, by 2 more in H2b (`data`, `values` leave; `debruijn`, `debruijn_code` stay under the new row). The r11 amendment records this (C3).

### F3: `cbor/refusal` also reads `:dao.host/refusal`. Ruling: accepted.

The JS `Float64` carrier's `valueOf` refusal is thrown by the host on implicit coercion, outside any codec call, so cbor cannot wrap it; the profile reuses the corpus class vocabulary by design, so reading both keys answers the same class. Layering is intact (`dao.jing.cbor` reads a `dao.host` key; `dao.host` knows nothing of cbor). Named behaviour change 6 is correctly recorded. One request for the slice report: state that `:dao.host/refusal` and `:dao.jing.cbor/refusal` share one class vocabulary, so the contract is written down somewhere other than a test.

### F4: the two safe-integer checks use `exact-integer?` and not `big-carrier?` with an explicit 2^53-1 bound, not `safe-native?`. Ruling: accepted as implemented; the design text is amended, the profile is not.

`safe-native?` on the JVM admits only `Long` by its docstring ("a JVM long"), and the operand being checked is a `count` result, a boxed `Integer`; the design's "integer/safe-native? for the two checks" did not account for that. The engineer's composition states the same bound on every host explicitly, which is what the check is for (operands the de Bruijn image must carry identically everywhere), and narrows the JVM and Dart from 2^63 to 2^53-1 for register counts and indices that no image approaches. I prefer this to widening `safe-native?`: the profile predicate means "native and exact under native arithmetic on every host", and admitting `Integer`/`Short`/`Byte` there would blur it for one caller. Amend the §3.1 H0-finding row and the §8 H2 row to name the composition (C3).

### F5: `debruijn_code` now encodes `:bigint` on JS and Dart instead of refusing. Ruling: accepted; name it as a behaviour change.

`host-supported-scalar-classes` was host-dependent before only because the host predicate did not exist on JS and Dart; `big-carrier?` exists now, so the three hosts agree, which is the design's purpose. JVM bytes are unchanged (verified above), and no JS or Dart address existed for these values because the encoder refused them, so nothing persisted changes. The updated test asserts the decimal-digit content on every lane. It is still an observable widening, so the slice report lists it beside the four named changes, and the §8 H2 row gains a dated note (C3).

### F6 to F8

F6 (M-caller audit): the three direct mutations are now `copy!` into fresh or provider-owned arrays and are on list 2; `generate`'s `.nextBytes` into a fresh 32-byte array is the named exception, and my grep finds no other in H2 files. Accepted; the exception is recorded in §2.4 (C3). F7: accepted (above). F8: the oracle fix is correct and the ClojureDart `for` trap is the known one; the comment in the test names it.

## 4. Required changes (before commit; gate lane re-run on the JVM; no review round)

- **C1** `test/dao/host/gate_test.clj`, list 1 data: `yin/vm/data.cljc` and `yin/vm/values.cljc` take slice value `:pending` with reasons "H2b: :388 float-value over the profile" and "H2b: category move to :runtime-prereq (table C print laws at :116,122)". `yin/vm/debruijn.cljc` and `yin/vm/debruijn_code.cljc` keep `:stays`; their reason text says "retained host number-model arms (Architect H2 review, §8.1 r11 row)" and `debruijn_code`'s adds ":213 int64 hex ported in H2b". Then `bb test:sub:clj dao.host`.
- **C2** `docs/agents/known-gaps.md`: one row. Gap: "`yin.repl.host.node-test` crashes the Node test process (`ws/node.cljs:203` reads `:ws/codec` from the adapter map; the test passes it on the descriptor); shadow autorun exits 0 after the crash, so `bb test:cljs` reports green having run no namespace after it". Evidence: H2 report F1; this review (R, read not run). Owner: codec track (C3 files). Closing: fix `connect!` or the test, and make the Node runner fail on a process crash. Severity P1 (lane blindness).
- **C3** Design doc r11 (orchestrator edit under the owner's standing authority for table and row text; dated notes, no doctrine change):
  - §3.1 and §8.1: after H2 the closure is 146 / 144 / 145 files, 137 shared, 159 in the union (H0's totals minus the shim plus the five profile files); the H0 totals remain the H0 acceptance.
  - §8.1: new row "Host number-model arms (H2 finding, retained)" per F2 kind 1, with `cbor.cljc:217,227` joining after H4; and the sentence that H2b moves `data.cljc` and `values.cljc` off list 1.
  - §8 H2 row and §3.1 H0-finding row: the two safe-integer checks use `exact-integer?` and not `big-carrier?` with an explicit 2^53-1 bound (F4); the `:bigint` widening on JS and Dart in `debruijn_code` is a named H2 change (F5); H2b is named as a follow-up (the two ports and the category move).
  - §2.4 (d): the one retained direct mutation after H2 is `sign/generate`'s entropy fill into a fresh array, deferred under §9.1.
- **Landing conditions (standard).** The slice report names behaviour changes 1 to 6 (the engineer's list) and the F1 Node exclusion; per-lane counts before and after; the non-Claude confirmation per §14.

## 5. Recommended, non-blocking

- **R1 / R2 / R3 (H2b).** The follow-up slice: `debruijn_code:213` ported to the `debruijn` int64 path (goldens as oracle), `data.cljc:388` over the profile, `values.cljc` reclassified to `:runtime-prereq` with regenerated edn. Small; after H3 or H4, in any order.
- **R4** Require ordering in `engine.cljc` and `debruijn_code.cljc` (`dao.host.*` after `yin.vm.ffi` / after `dao.jing.cbor`). Formatting only.
- **R5** Retire `cbor/->Rational` when the two tests call `number/ratio-of` or `number/->Rational`; H4 is the natural moment.
- **R6** `float-wire` builds bytes from hex text; if H4 wants a bytes form of the float bits, add `float64->bytes` to `dao.host.float` as a table B composition over `float64->bits`, not a new arm.
- **R7 (not H2's; for the `debruijn_code` owner, P3, R not V).** On JS, `host-long?` is `Number.isSafeInteger`, which is true for `-0`, so a `-0.0` literal classifies `:long` and encodes as `0`'s bytes, while on the JVM it is a `Double` and encodes `:double`. One image hashing differently on two hosts is what the sole-admitter validator exists to prevent; `debruijn`'s `js-number-class` already treats `-0` as `:double`. Fix as a separate change with its own goldens; the port in R1 must not change this by accident, which is why H2b should land the two together deliberately.
- **R8 (OQ11 input).** The `bytes->str` arity list has been `[1 2]` on all three hosts since before H2, while only the JVM has the two-arity function. The owner's decision on OQ11 should also settle the declared arities; the Architect's recommendation stands ("UTF-8" accepted on every host, any other name refused).

## 6. Reviewer checklist of §14, in order

Table A entries unchanged by H2 (no profile file edited except one docstring): n/a. Closure output after H2 matches the tool's `--check` and list 1 matches §8.1 by category after the r11 row (C3). The two negative tests and the scan-failure tests pass; the library mode's negatives are in. The closure-tool gate holds before (engineer's record) and after (my run) the deletion; the no-partial-write tests exist on three lanes. Corpus byte-identical with errata preserved; the behaviour changes are named with their table D tests. The `bytes->str` encoding arity is preserved and tested. The index comparator keeps signed zero and NaN order (R7-3), tested. No codec-track source or test file is touched. Nothing from §9 is started.
