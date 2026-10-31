[claude-code:unrecognized_model] {"model":"deepseek-v4-pro","query_source":"sdk"}
We're changing auto mode to no longer charge for classifier requests in Claude Code. However, this session isn't eligible because your requests go through api.deepseek.com, which isn't compatible with this update. Nothing breaks: auto mode keeps working, and its classifier requests are billed as before. To fix it and access the new version of auto mode, ask your gateway to implement: https://code.claude.com/docs/en/auto-mode-classifier-billing
[claude-code:unrecognized_model] {"model":"deepseek-flash","query_source":"auto_mode"}
I've completed a thorough read of the codec, the Boring adapter, the tests, the spec, and the frozen corpus README. Here is the complete adversarial review.

---

Completed-GMT: 2026-09-21 21:49:37 GMT
Completed-Local: 2026-09-22 04:49:37 +07 (Indochina Time)
Coding-Agent: deepseek
Session-ID: pending

## Verdict: READY WITH CHANGES

The codec is correct on its core contract — I could not construct a counterexample against byte-level output, numeric identity, or refusal classification, and the test suite is mutation-strong. Two findings need an architect-aware decision (already surfaced by the implementer as 3a/3b), and I found one P2 robustness gap in the hand-rolled reader plus a few P3s.

---

## Findings

### P2 — Unbounded recursion depth in the hand-rolled reader (DoS at the ingress boundary)

`src/cljc/dao/jing/cbor.cljc:911-991` (`read-item`) recurses on every nested array (`:970`), map key/value (`:974-976`), and tag (`:981`); `item->value` (`:1088-1139`) recurses again at `:1101` (array), `:1102-1104` (map), `:1105`→`frame->value` (tag 27), and `:1131` (set). Nesting depth is bounded only by input length. A ~10 KB payload of `[[[[…` overflows the JVM stack (`StackOverflowError`) or Node stack (`RangeError`) as an *uncaught* throw, not a clean refusal.

- **Plan sentence:** "Verify every retrieved payload against its requested address before decoding exactly one supported value … before storage, caching, or exposure" (`docs/design/dao.jing.cbor.md:117-120`), i.e. `decode` is the ingress validator for untrusted bytes (remote/DHT receipt and file replay).
- **Why P2 not P1:** this corrupts no bytes and accepts/rejects nothing wrongly; it is a robustness/availability defect. I flag it as the most serious item because the prompt lists "an unsafe reader" at P1 and deep-nesting is trivially craftable; the architect may wish to rate it P1 under a hostile-ingress threat model.
- **Smallest fix:** thread a depth counter through `read-item` and `item->value`, and `(refuse :malformed-cbor "nesting too deep")` past a fixed limit (e.g. 512). Both entry points share the same recursion, so both need the parameter.

### P3 — Keyword metadata is silently dropped, not preserved and not refused

`src/cljc/dao/jing/cbor.cljc:819` — `(keyword? x) (identifier-wire 'dao.jing/keyword x)` calls `identifier-wire` directly with **no** `meta-wire`, unlike symbols (`:820`) and all collections. A keyword carrying metadata encodes as if it had none, so `decode` re-encodes to the same bytes (self-consistent, no `:non-canonical`), but the metadata is lost with no refusal.

- **Plan sentence:** "Preserve collection and symbol metadata using Boring's `clojure/with-meta` mapping" (`:172`). The plan names *collection and symbol* metadata; keyword metadata is unaddressed, so this is a spec gap rather than a direct violation.
- **Smallest fix:** decide and pin it — either wrap keywords in `meta-wire` too (symmetry), or document keyword metadata as out-of-domain and `(refuse :unsupported-value)` when `(some? (meta x))` on a keyword, so it is never silently discarded.

### P3 — `host-integer?` admits unsafe integral JS numbers

`src/cljc/dao/jing/cbor.cljc:380-381` — `(and (number? x) (js/Number.isInteger x) (not (neg-zero? x)))`. `Number.isInteger` is true for values like `9007199254740993` (an integral double that is not a safe integer). The encode path is protected (`:745-747` refuses before `int-wire`), but `numeric?`/`exact` would route such a value into `(big x)` = `(js/BigInt x)`, which silently rounds to the nearest representable double before conversion. Not exercised by the corpus (`num/js-unsafe-integer` is encode-only), and no wrong *bytes* result, so P3.

- **Plan sentence:** "Reject unsafe integral JavaScript Number inputs" (`:315-316`).
- **Smallest fix:** add `(js/Number.isSafeInteger x)` to the CLJS branch of `host-integer?` so the guard is uniform at the type boundary, not only in `number-wire`.

### P3 — Tag-number `-1` sentinel leaks into the refusal message

`src/cljc/dao/jing/cbor.cljc:982` maps an eight-byte (ai=27) tag number to the sentinel `-1`, so `item->value` (`:1139`) reports `"tag -1"` for a genuinely huge tag. Correct class (`:unknown-tag`), wrong text only. Cosmetic.

### P3 — `collapse?` is worst-case O(n²) on collection size

`src/cljc/dao/jing/cbor.cljc:668-677` groups by `equiv-hash` then scans pairwise within a group; a map/set with many members that hash-collide degenerates to O(n²) per `check-members` (`:1013-1021`) and `members-wire` (`:762-771`). Bounded by input size and requires crafted collisions, so minor, but it sits on the same ingress path as the P2 above.

---

## 3a — Decoding does not go through Boring: **my recommendation is to ratify it**

This is the **correct** reading of the plan, not a violation. The governing clause is "Validate tag/name/payload shapes before any lossy host conversion" (`:220-221`) and "successful generic decoding is not profile acceptance" (`:222`). The implementer's Boring probes are dispositive: tag-30 `3/1` → `3N` (ratio kind lost), negative denominators accepted, stringrefs/dates/UUIDs decoded as ordinary values, `clojure/with-meta` mapped by Boring itself, `decode-seq` dropping a trailing index frame, and JS floats indistinguishable from integers. None of those may decide a Jing byte.

The soundness argument holds because the split is asymmetric: **Boring remains the sole byte *writer*,** and every decoded value must re-encode through Boring to *byte-identical* output (`cbor.cljc:1154-1155`). That re-encode gate is itself a strong correctness check — a bug in the Jing reader that produced a wrong value would surface as a spurious `:non-canonical`, and the 209 canonical round-trips plus 132 decode-refusals all passing means the reader parses the whole frozen profile correctly. The hand-rolled reader never *decides* bytes; it only refuses what is not canonical.

The one real residual risk is the reader's *robustness*, and that is exactly my P2 (recursion depth). Allocation-bomb and large-length concerns I checked and found handled: `length-of` caps at `max-length` (`:851`, `:877-885`) and `need` (`:845-848`) refuses any length exceeding the actual buffer before a single byte is copied; indefinite-length items are bounded by input size (`:929-953`); eight-byte tag heads become the `-1` sentinel and refuse as `:unknown-tag`. Ratify 3a, and add the depth limit as part of doing so.

## 3b — `coll/map-both-slash-keywords` / `:host-collapse`: **my recommendation is to accept a new class, documented as a J1 host-capability extension, not part of the frozen 16**

The refusal is the **right** behavior — silently losing an entry would violate the stronger invariant "losing an entry is never acceptable" (`cbor.cljc:1024-1027`). The class is semantically distinct from all 16: `:equality-collapse` would be *wrong* here, because portable `equiv` keeps the two slash-crossed identifiers apart (`identifier-key` compares fields, `cbor.cljc:606-607`; the corpus notes "no collapse"). This is a *host* merge (CLJS keyword `=` compares the joined name) that portable equality does **not** perform — the unique case where host-`=` merges but `equiv` does not. That is precisely what `built`/`:host-collapse` (`:1024-1031`) detects, and it fires only after `check-members` has already passed, so it can only ever be the slash-crossed-identifier case.

- **Scope is maps and sets, not keyword *values*:** `built` wraps `(into {} …)` (`:1104`) and `(into #{} …)` (`:1132`); a slash-crossed keyword held as a vector/list element does not merge. This matches the actual limitation.
- **On "do not let the accepting host determine this boundary" (`:163`):** there is no contradiction in spirit. The codec does not let the host *silently* determine bytes — it refuses deterministically. What it does concede is that Node cannot round-trip a value the JVM can, a genuine cross-host asymmetry. The alternative (exclude slash-crossed identifiers from the domain everywhere) would rewrite a frozen canonical case, so it is not available at J1.
- **Recommendation:** keep `:host-collapse`; document it (a) as a decode-time host-capability refusal, (b) as lying outside the frozen 16-class v1 vocabulary (it is never produced by any fixture, so nothing is regenerated), and (c) as applying to maps and sets on any host whose identifier equality is joined-name-based. Do **not** fold it into `:unsupported-value` (that means "outside the supported domain," which this value is not).

---

## What I checked and found clean

- **Bytewise ordering / duplicate & collapse before construction.** Map keys delegated to Boring `:canonical` (pinned by 209 frozen cases); set elements sorted by `(sort-by first wired)` where `first` is the canonical-bytes hex (`cbor.cljc:783-784`) — ASCII order on lowercase fixed-width hex equals unsigned byte order, and proper-prefix-first falls out of string comparison. Duplicate-by-raw-bytes (`span-key`, `:1017-1019`) vs portable-collapse (`collapse?`, `:1020-1021`) is correctly distinguished, matching the corpus's `duplicate-key/element` vs `equality-collapse` split (verified against `coll/set-nan-payload-duplicate` = `duplicate-element`, `coll/set-int-ratio-collapse` = `equality-collapse`).
- **Strict UTF-8 / surrogates.** `check-text` (`:694-709`) rejects unpaired surrogates (validate-before-strip, A9, including the reader-position keys `:797-799`); decode uses `CharsetDecoder` REPORT (JVM) and `TextDecoder fatal+ignoreBOM` (Node) — both reject overlongs, encoded surrogates, and >U+10FFFF.
- **Identifiers.** Built from `(namespace x)`/`(name x)` fields only (`:806-809`, `:1059-1062`); no print/parse anywhere in the codec body (the `-pr-writer` methods are display-only). Tag 39 refused (`:1134`).
- **Frames & metadata.** Four named frames are exactly `[name payload]` via `pair-items` (`:1034-1039`) with a closed dispatch and `:unknown-frame-name` fallback (`:1085`); with-meta outermost (A4), bare-metadata rule (A3, `:1078-1079`), reader-position strip (A5), float64 as eight bytes, native floats → `:native-float` (`:1093`), float32 widening and single canonical NaN (`:725-738`).
- **Numeric identity.** `num=`/`num-hash`/`num-compare` operate on exact rational triples (`exact`, `:531-553`) with cross-multiplication and no double rounding; symmetric in both operand orders; -inf < finite < +inf < NaN with rank (`:580-585`); canonical NaNs equal. I could not construct a hash-consistency or transitivity counterexample — `exact-key` (strings) makes `num=` ⇒ equal `num-hash` for every kind/scale/zero-sign, and exact cross-multiplication makes `num-compare` a total order. `equiv`/`equiv-hash` recurse correctly through collections (A6 list≡vector).
- **Boring adapter & effect proofs.** `opts` is fixed `{:profile :canonical :stringref false :shapes false}` and the lock is tested by *throwing* on a conflicting override (`cbor_test.cljc:190-197`), ordinary one-item `boring/encode` only. A Boring byte change would break the 209 frozen hex/digest assertions, so it cannot alter bytes silently.
- **Mutation-thinking on tests.** The runner would fail a wrong codec: canonical cases assert exact frozen hex + digest + re-encode + `equiv`; decode-refusals assert the exact class; `groups-hold-on-produced-bytes` recomputes equivalence/distinction groups from the codec's *actual* bytes; and the encode-refusal test probes host-merge dynamically (`(< (count v) n)`) rather than trusting the table. I confirm the implementer's **finding 3**: the README N/A table overstates N/A on several rows because the carriers are kind-strict `deftype`s (`Float64`/`Decimal`/`Rational` `-equiv` match only same-type), so the JVM/Node *do* hold both members and refuse for `coll/set-int-ratio-collapse`, `coll/set-signed-zero-collapse`, `coll/set-decimal-scale-collapse`, `coll/set-float-decimal-collapse`, `coll/map-int-float-key-collapse` (verified by reasoning from the carrier `-equiv` bodies and the `collapse?` logic). The skip set (`collapse-not-applicable`) is a correct superset plus dynamic probe, so nothing that should run is skipped.
- **Independence/safety.** No ambient print-settings dependence (proved by the `*print-*` binding test); byte inputs and outputs are fresh copies (`:821`, `copy-range`); `:cljd nil` gates the entire body and the Boring require, and the fixtures' new helpers + `:clj`/`:cljs` `dao.jing.cbor` requires are absent under `:cljd`, so Dart compiles neither the codec nor the Boring dependency; no existing entry point edited; frozen resource untouched.

No P1 wrong-bytes, wrong-acceptance, or unsound-equality defect was found. The only blocking-adjacent item is the P2 recursion-depth limit, which I recommend folding into the ratification of 3a.
