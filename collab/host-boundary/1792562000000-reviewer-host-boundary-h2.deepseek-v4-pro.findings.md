# Slice H2 independent architectural review: consumer migration and shim deletion

Date: 2026-10-21
Reviewer: deepseek-v4-pro, Independent Architect & Reviewer (non-Claude, per §14)
Branch: host-h2, worktree /Users/sto/workspace/datomworld-host-h2
Reviewed: HEAD 6b21521c (H1) plus the uncommitted tree (34 modified files, `git rm` of `src/cljc/yin/vm/integer/host.cljc`, 3 new test files).
Authority: docs/design/dao.stream.host-boundary.md r10, §2.4, §3.1, §4.2 (tables A, B, C), §8 H2 row, §8.1, §8.2, §10, §13 OQ11, §14.
Engineer report: collab/1792541000000-engineer-host-boundary-h2.claude-opus-5-5.findings.md
Architect review: collab/1792560500000-architect-host-boundary-h2.claude-fable-5-1.findings.md

**Verdict: SIGNED OFF WITH CHANGES.**

H2 does what the §8 H2 row commissions. I independently confirmed, by reading the diff and by running the gate and the new test suites on the JVM, that every named consumer now reaches the host through `dao.host.*`, the float-text kernel is consumed from `dao.host.float` with the module's private copy deleted, the eight H0 portability corrections are in with their table D tests, `dao.data.arrays` carries the stable sort and prevalidation, the shim is deleted under a working closure-tool gate (573 files read, `none` require it, exit 0), and the §8.2 library mode exists with its two negative tests. The corpus is byte-identical (conformance and fixtures pass). I confirm the Architect's rulings F1–F8 and the required changes C1–C3, and I found no additional required change.

The required changes are the same set the Architect named: gate data edits (C1), one ledger row (C2), and r11 design-document amendments (C3). None touches runtime behaviour. The current tree does **not yet** reflect C1 (the gate still carries the engineer's original `:stays` / "Architect to classify" text for the four disputed files); that edit, C2, and C3 must land before commit. Standing rule §14: the engineer, Architect and I have each confirmed the slice; the orchestrator records the non-Claude confirmation and applies C1–C3 before the commit.

## 1. What I verified myself

I ran these on the JVM in the worktree (the Architect read the code and ran the JVM gate lane; the three-lane counts are the engineer's/orchestrator's records, which I did not re-run on Node or Dart):

| Check | Result |
|---|---|
| `clojure -M:test -n dao.host.gate-test` | 14 tests, 178 assertions, 0 failures |
| `clojure -M:test -n dao.data.arrays-test -n yang.php-literal-test -n yin.vm.bytes-to-str-test -n dao.space.index-test -n dao.host.float-test` | 67 tests, 664 assertions, 0 failures |
| Closure tool `--required-by yin.vm.integer.host` | read 573 files under `src test`, printed `none`, exit 0 |
| Closure tool `--required-by dao.host.integer` (positive control) | 20 requirers listed |
| Closure tool `--check` | 146 / 144 / 145, 137 shared — matches the r11 post-H2 totals |
| Namespace graph (`dep-graph`, 218 namespaces) | `yin.vm.integer.host` absent; the five `dao.host.*` namespaces present |
| `grep` for `yin.vm.integer.host` over `src`, `test`, closure edn | only the `dao.host.integer` docstring (history) and the gate's totals label, as §8 H2 permits |

Diff reading confirmed: the consumer require swaps (`vm`, `engine`, `values`, `data`, `integer`, the three Python int tests); the `-272`-line float-text kernel deletion in `yin.vm.integer` delegating to `dao.host.float` with the `::float-overflow` sentinel mapped; `dao.host.float` requiring only `dao.host.integer` (layering intact: no `yin` require, so no `yin -> cbor -> float` cycle); the cbor migration (`refusal` reading `:dao.host/refusal`, the `host` wrapper re-throwing profile refusals as codec refusals, indefinite byte-string assembly now `bytes/alloc` + `bytes/copy!`); the arrays stable `asort` (JVM TimSort, JS `.sort`, Dart merge sort) and pre-validation-before-write `acopy`/`aset`; and all eight portability corrections (strict `bytes->str` with the JVM encoding arity retained behind `#?@(:cljd [] :clj [...])`, PHP literals over `parse-decimal`/`demote` and `decimal-text->float`, the index comparator taking the native path only for two native exact integers, packet `text/code-points`, the two safe-integer checks over `exact-integer?` with an explicit 2^53−1 bound, and the three byte lengths).

## 2. Confirmation of the Architect's rulings (F1–F8)

I agree with each, and add the independent reasoning where it differs in emphasis.

- **F1** (pre-existing Node lane crash, `ws/node.cljs:203`): correctly attributed to the codec track (C3 files, `a1308a6c`); H2 touches neither file. The severity is rightly the lane-integrity gap (shadow autorun exits 0 after the crash), not the one failing namespace. C2's ledger row is the correct disposition, and I concur with P1.
- **F2** (five `:migration-source` files keep arms outside §8 H2's line list): the design under-enumerated; the engineer stayed in scope correctly. The three-way split is sound. *Kind 1* (`debruijn`/`debruijn_code` number-classification arms) encode which classes a host *can carry* (JS has one number type, no char/ratio), not a host-library call — retained arms, `:stays`, matching the dialect-arm row's logic. *Kind 2* (`debruijn_code.cljc:213`, `data.cljc:388`) are portable but not in the H2 line list; deferring them to H2b rather than widening a slice already at its risk budget is the right call. *Kind 3* (`values.cljc:116,122` print-method arms) are table C print laws misfiled as `:migration-source`; a `defmethod` under an arm is a real arm, not a §3.3 exemption, so the category move to `:runtime-prereq` (not a pattern widening) is correct. The `cbor.cljc:227` `float64?` point is correctly *not* a table A gap (codec classification policy, H4's to decide).
- **F3** (`cbor/refusal` also reads `:dao.host/refusal`): correct. The JS `Float64` carrier's `valueOf` refusal is thrown by the host on implicit coercion, outside any codec call, so cbor cannot wrap it; the profile reuses the corpus refusal vocabulary by design. Layering is intact (`dao.jing.cbor` reads a `dao.host` key; `dao.host` knows nothing of cbor). I second the Architect's request to write down, in the slice report, that `:dao.host/refusal` and `:dao.jing.cbor/refusal` share one class vocabulary.
- **F4** (safe-integer checks use `exact-integer?` + not `big-carrier?` + explicit 2^53−1, not `safe-native?`): correct and preferable. `safe-native?` admits only JVM `Long` by its docstring, while the checked operand is a boxed `Integer` from `count`. The explicit bound states on every host the same ceiling the de Bruijn image must carry identically everywhere. I agree with not widening `safe-native?` (which means "native and exact under native arithmetic on every host"; admitting `Integer`/`Short`/`Byte` would blur it for one caller).
- **F5** (`debruijn_code` now encodes `:bigint` on JS/Dart): accepted as a named behaviour change. JVM bytes unchanged (the `:bigint` content is `to-radix-string` of the promoted value, equal to the old `(str v)`); no JS/Dart address ever existed for these values because the encoder refused them.
- **F6–F8**: confirmed. The three direct host-array mutations are now `copy!` into fresh/provider-owned arrays and are on list 2; the one retained direct mutation is `sign/generate`'s `.nextBytes` entropy fill (the §9.1 exception). F7's refusal-shape asymmetry (JVM host exception vs JS/Dart profile refusal) is pre-existing and acceptable under "refused before any write". F8's oracle fix (no host sort; `mapv` for the ClojureDart chunked-seq trap) is correct.

## 3. The library-mode gate (§8.2)

I read the implementation closely and ran the gate test. The gate reads each closure file (minus `dao/host/`, minus codec-track files) per lane feature set with the Clojure reader, collecting symbols, `:tag` hints, string libspecs and imported class names; keywords, strings (outside the ns form), comments, quoted forms and `(comment ...)` never count. The patterns are anchored (`BigInteger` matches `java.math.BigInteger.` and `BigInteger/valueOf`, not `:rgba8unorm`). The six executable negatives (call, import, type hint, reader-arm constructor, `unorm` string require, `java.text.Normalizer`) fail; the docstring/keyword/quote/comment fixture passes; scan failure (empty set, nil file, unreadable form) is distinct from a pass; and the profile files alone fail, proving the scope exclusion is what passes the tree. This is the §8.2 deliverable with both negative tests the H2 row names.

## 4. Required changes (before commit; gate lane re-run on the JVM; no review round)

I confirm the Architect's C1–C3 are the correct and complete set, and that none touches runtime behaviour. For the orchestrator, the concrete state:

- **C1** `test/dao/host/gate_test.clj`, list 1 data. The tree currently holds the engineer's original values — `yin/vm/data.cljc` and `yin/vm/values.cljc` are `:stays` (not `:pending`), and `debruijn.cljc`/`debruijn_code.cljc` reason text still reads "Architect to classify (H2 report)". Apply the Architect's text: `data` → `:pending` ("H2b: :388 float-value over the profile"), `values` → `:pending` ("H2b: category move to :runtime-prereq (table C print laws at :116,122)"), `debruijn`/`debruijn_code` → `:stays` with reason "retained host number-model arms (Architect H2 review, §8.1 r11 row)", `debruijn_code` adding ":213 int64 hex ported in H2b". Then `bb test:sub:clj dao.host`.
- **C2** `docs/agents/known-gaps.md`, one row for F1, owner codec track, severity P1 (lane blindness), evidence H2 F1 + this review (R).
- **C3** Design doc r11: the post-H2 closure totals (146/144/145, 137 shared, 159 union); the §8.1 "Host number-model arms (H2 finding, retained)" row; the H2b follow-up note; the F4 composition and F5 `:bigint` widening named in the §8 H2 row; the §2.4 (d) retained direct mutation (`sign/generate` entropy fill).
- **Landing conditions**: slice report names behaviour changes 1–6 and the F1 Node exclusion; per-lane counts before/after; the non-Claude confirmation per §14.

## 5. Recommended, non-blocking (concurring with the Architect)

- H2b follow-up slice: port `debruijn_code.cljc:213` to the `debruijn` int64 path, `data.cljc:388` over the profile, and move `values.cljc` to `:runtime-prereq`. Land R7 (the JS `-0.0` → `:long` vs JVM `:double` classification) together with H2b so the port does not change it accidentally.
- Require ordering in `engine.cljc` and `debruijn_code.cljc` (cosmetic).
- Retire `cbor/->Rational` at H4.
- OQ11 input: the `bytes->str` arity list has been `[1 2]` on all three hosts since before H2 while only the JVM has the two-arity function; the owner's OQ11 decision should settle the declared arities too.

## 6. Reviewer checklist of §14, in order

Table A entries unchanged by H2 (only `dao.host.integer`'s docstring edited): n/a. Closure output after H2 matches the tool's `--check` (146/144/145, 137 shared) and list 1 matches §8.1 by category once the C1 edit and the r11 row land. The two negative tests and the scan-failure tests pass; the library mode's negatives are in. The closure-tool gate holds before and after the deletion (my `--required-by` run: 573 files, `none`, exit 0; the file is absent). The no-partial-write tests exist and pass on the JVM (I ran `arrays-test`). Corpus byte-identical with errata preserved (conformance 367 run / 5 skips per the engineer's record). The behaviour changes are named with their table D tests. The `bytes->str` encoding arity is preserved (`#?@(:cljd [] :clj [...])`, arity list `[1 2]`) and tested. The index comparator keeps signed zero and NaN order, tested in `index-test`. No codec-track source or test file is touched. Nothing from §9 is started.
