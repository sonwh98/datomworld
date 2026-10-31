Completed-GMT: 2026-09-25 23:06:00 GMT
Completed-Local: 2026-09-26 06:06:00 +0700

# M2 Final Gate — Review (Rule R commit two)

I read `git diff HEAD` in full (linker.cljc, content.cljc, content_test.cljc, linker_test.cljc, linker.md), the four earlier findings files, the Rule R final design (`1790345200000`), and the implementer report (`1790354100000`). I traced the fetch pipeline, all three scanners per format, the join, the required-contract admission, and the four reserved-name fixtures against the actual validators. I did not rerun suites.

## 1. Status of every earlier finding (rounds 1–4)

| Round | Finding | Status | Evidence |
|---|---|---|---|
| 1 | records omit `:definitions-fn`/`:applications-fn` | **CLOSED** | all four records carry `:obligations-fn`, `:definitions-fn`, `:applications-fn` (linker.cljc:618–691) |
| 1 | Dart `typed/Uint8List` missing import | **CLOSED** | `#?@(:cljd [["dart:typed_data" :as typed]])` (linker.cljc:41); `byte-count` `:cljd` branch uses `^typed/Uint8List` (linker.cljc:885) |
| 1 | unbounded defaults; byte cap after decode | **CLOSED** | `default-bounds` finite (linker.cljc:959–964); `fetch-one` checks size before hash/decode (linker.cljc:942) |
| 2 | definition query only `:store-put`, missing `yin/def` | **CLOSED** | `tree-definition-query` matches `yin/def` two-operand/literal applications (linker.cljc:269–286) |
| 2 | prefix ordering puts application before operand defs | **CLOSED** | invocation position `(conj path [3 n])` / `[3 2]` (linker.cljc:393–416) |
| 2 | oversized+mismatched decoded for evidence | **CLOSED** | byte cap precedes `segment-bytes-match?`; `:address-mismatch` no longer carries `:value` (linker.cljc:936–956) |
| 2 | `:max-parts 0` admits root | **CLOSED** | `(if-not (pos? max-parts) (refused :parts-limit …))` refuses root unread (linker.cljc:1016–1017) |
| 3 | `yin/def` binding at entry path, value operand runs first | **CLOSED** | definition recorded at `[3 2]`, value operand `[3 0]` precedes it (linker.cljc:392–398) |
| 3 | syntactic `yin/def` treated as store under module-store shadow | **CLOSED** | shadow filter deleted; Rule R makes the name unshadowable |
| 4 | direct `:vm/store-put` of `yin/def` / computed-key write | **CLOSED** | refused `:reserved-name` by `rows-reserved-defect`/`ast-reserved-defect` |
| 4 | aliased `yin/def` passed as a value | **CLOSED** | `:variable yin/def` legal only as definition operator; alias fixture refused |

All six prior blockers are closed, the fetch-bound fixes intact.

## 2. Deletions removed only the shadow guards; recognition is sound and complete

The deleted items — `tree-yin-def-application-query`, `computed-yin-def-write?`, the `tree-definition-occurrences` shadow filter, the `:yin-def?` bookkeeping/dissoc, and the shadow-proof docstring prose — are **all** shadow-guard machinery. The report's "kept" list (constant-key recognition, invocation position `[3 2]`, dominance in `undischarged`, the fetch-bound fixes, vector/register scanners reading `:define`) is confirmed present.

Rule R makes recognition sound **and** complete for constant keys, because the admission validators each refuse the reserved name before the scanners ever run:
- AST → `vm/validate-rows` → `rows-reserved-defect` (vm.cljc:1270–1343);
- semantic → `code/well-formed-vector?` → `reserved-name` (code.cljc:390–410);
- stack → `debruijn-code/image-defect` → `reserved-defect` (debruijn_code.cljc:847–868);
- register → `register-image-defect` reserved checks (debruijn_register_code.cljc:656+).

A `:variable yin/def` that passes validation is *by construction* a definition operator, so `tree-definition-query`'s `[?op :variable yin/def]` + two operands + literal key is both sound (no false positives — every store-put/computed-key/alias/rebind of `yin/def` is already refused) and complete (no false negatives — every definition is a `yin/def` form, a `:store-put`, or a `:define`). The definition operator is stripped from obligations by `(remove (fn [[name _]] (vm/reserved-name? name)))` in `tree-free-name-occurrences` (linker.cljc:364). I see **no residual case** worth guarding: the shadowing class is eliminated at the root rather than detected downstream.

## 3. Required-contract fetch is sound

- Step 0 refuses a nil/absent `:contract` as `:invalid-request` and a differing one as `:contract-mismatch`, both before any read (linker.cljc:1128–1135); the test asserts zero reads (`counting-store`).
- `fallback-fetch` (the R→H trusted/verifying fallback) passes `{:contract (:contract stack-format)}` (linker.cljc:1268).
- The linker never *assigns* a stamp to fetched content; it only compares the requested contract to the record's. Stamping stays in the loaders (`semantic/load-vector`, `ast-walker/vm-load-rows`, `dvm/create-vm`, `rvm/create-vm`), which the caller invokes with the record's contract — so external datoms/rows are never relabeled.
- Identity-directed matching is sound: step 2 verifies bytes against the requested address, step 3 (`:identity-matches-fn`) re-checks the decoded value against the carried identity (I5). An index entry pointing a storage-derived identity at a different valid payload is caught as `:hash-mismatch` (covered by `a-vector-index-entry-at-another-vector-is-a-hash-mismatch`).

**Ruling on the shorter arities (linker.cljc:1145–1148): keep them.** The 4- and 5-arity forms delegate to the 6-arity with nil opts and return `:invalid-request`. This is fail-closed (a stale caller that forgets the contract gets a clear refusal, not a silent wrong-contract load), it matches the spec's "an omission is `:invalid-request`", and it is documented in the docstring and covered by `a-request-omitting-the-contract-is-invalid-request` (all six shapes). Removing them would also be defensible; this is API taste, not correctness.

## 4. content_test conflict resolution and flipped fixtures are correct

- The `UU` marker is resolved in the file; no conflict markers remain (`git diff HEAD --check` clean, orchestrator-confirmed).
- The `fetched-vectors-carry-their-address-into-the-vm` resolution loads `(:value res)` under `(:contract linker/semantic-format)` (content_test.cljc:265–269), not the loader's own constant. This is correct: it stamps the admitted content with the contract the fetch already verified at step 0, and `format-records-name-their-contract` pins `(:contract semantic-format) = vm/semantic-contract`, so it is runtime-equivalent while honoring the "don't stamp external input with the loader's own constant" rule.
- The refusal tests were **flipped, not weakened**: thrown-exception assertions became precise `:reason`/`:rule` checks (`:absent`, `:descriptor-defect` with `:arity`/`:mnemonic`/`:target-bounds`). The four renamed `*-is-reserved-name` fixtures now also assert the mint side throws before the write (materialize-tree! throws) and the fetch returns `:descriptor-defect`/`:reserved-name` — strictly stronger than the old "discharges nothing" assertions.
- I compared removed vs. changed assertions in the diff: every change is a mechanical migration from `content/load-rows`/`fetch-vector` to `linker/fetch`, with equivalent-or-stronger coverage. Nothing was deleted to make a test pass.

## 5. Cross-host correctness and docs

- JVM 2089/181424/0, Node 2002/48286/0, Dart 1964 all passed (orchestrator, independently run). clj-kondo 0/0, cljstyle exit 0. The Dart `typed_data` alias resolves the round-1 P1. `byte-count` and the `catch #?(:cljd Object :clj Throwable :cljs :default)` guards are the only host-conditionals and are correct.
- The three linker.md edits (4.2 step 0 now "required", 6.3 closed key set drops "optional", criterion 24 now "omission is `:invalid-request`") are accurate and match the implemented behavior.

## 6. New findings — defects vs. deferred

No new **defects** found. Two non-blocking notes:

- **P3 | src/cljc/yin/vm/linker.cljc:1145–1148 | dead-but-fail-closed arities** | The 4- and 5-arity forms can never return `:ok`. | Either leave as-is (documented fail-closed boundary) or delete and force the 6-arity. Non-blocking; I recommend keep.
- **P3 | src/cljc/yin/vm/completion.cljc:299 | stale docstring** | Still names the retired `yin.vm.content/fetch-vector`/`load-rows`. | Update the docstring in a later cleanup pass. Deferred, pre-existing, out of this diff's box.

Both are deferred work, not blockers.

## 7. Verdict

The whole M2 diff is sound: every prior gate finding is closed, the deletions are exactly the shadow guards that Rule R made unnecessary, definition recognition is sound and complete for constant keys, the required-contract admission and identity-directed match are correct, the content_test conflict resolution and flipped fixtures preserve and strengthen coverage, and all three hosts are green with consistent docs.

Verdict: READY
Sign-off: GRANTED
