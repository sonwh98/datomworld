# Independent Code Review — UCF Stage D10b-B (Four-Kernel Lift/Lower)

- **Reviewer**: DeepSeek V4 Pro (`deepseek/deepseek-v4-pro`)
- **Base commit**: `9cea60dd` (master), branch `ucf-d10b-kernel-lift-lower`
- **Normative spec**: `docs/design/yin.vm.universal-continuation-format.v2-amendment.md` (authoritative for body version 2) + parent UCF doc (authoritative for v0/v1)
- **Diff size**: 2,384 insertions / 236 deletions across 14 files (handoff.cljc alone +1,992 lines)

## Verdict: ACCEPT

The implementation faithfully realises the v2 amendment. I traced the four closed profiles, the version-2 wire grammar (closed body/register/pending/layout shapes), the structural fork/exclusive custody dispatch, the positional layout prefix-sum reconstruction, and the walker Kw chain codec against the specification, and found **no correctness defect**. All four invariant classes in the review assignment hold. The findings below are advisory (verification completeness and a maintainability/altitude concern), none of which blocks acceptance of the code, but two of which must be closed before the D16 gate.

---

## Invariant-by-invariant assessment

### 1. Four-kernel execution profile declarations — SATISFIED

- `ucf/profiles` is a **closed** registry keyed by engine (`:semantic`, `:stack`, `:register`, `:walker`), each an exact `:yin.k/contract` map `{:yin.k/engine k, :yin.code/contract c, :yin.k/version 1}`. The `:yin.code/contract` strings resolve through `vm/semantic-contract`/`stack-contract`/`register-contract`/`ast-contract`, verified as `"v3"`/`"b2"`/`"r2"`/`"v3"` respectively — matching spec §1's table exactly (walker/semantic share `"v3"`, distinguished by engine).
- `ucf/profile-engine` compares on the canonical integer kind (`cbor/numeric-kind` = `:integer`), so a float carrier never equals its integral value (§2).
- Lift emits the canonical contract directly: `:yin.k/contract (get ucf/profiles engine)` (handoff.cljc:1600), and the engine is taken from the kernel's own `link-format`, never inferred from image shape (`engine-of`, handoff.cljc:836).
- Profile mismatch reports `:yin.k/path [:yin.k/contract]` with `:yin.k/supported ucf/supported-profiles` (`profile-mismatch!`, checkpoint.cljc; confirmed by the updated `the-version-gate-checks-the-integer-kind` test).

### 2. Version-2 image layout & custody — SATISFIED

- Body is `{:yin.k/version 2, :yin.k/contract …, :yin.k/state …}`; `v2-body?` checks exact integer kind 2.
- Fork vs. exclusive is **structural** (presence of any of the five header keys), not a mode flag (§3): `fork?` in `checkpoint/inspect-v2`, `tree-fork?` in `resume-task*`, and the `version-gate!` exclusive-fork check all key off the header-key intersection. Fork trees forbid carried op-ids (`::fork ctx` in `pending-ops`); exclusive halted results carry `:yin.k/origin` only (`check-root-header!` unchanged).
- Legacy v0/v1 paths are untouched: `handoff_v1_test.cljc` and `checkpoint_test.cljc` edits only update the *supported set* literal from `#{1}` to `#{1 2}` (and split the version-2 case into its own assertion). `codec-versions` becomes `{:stream #{0} :jing #{1 2}}`, and `spawn-child-template` dispatches `(if (= 0 version) cbor jing)`, so v2 children ride the jing canonical codec — consistent with the lift encoding v2 via `jing.cbor/encode`.

### 3. Four-kernel lowering & resumption parity — SATISFIED

- **Stack/register**: `stack/compose` and `register/compose` derive offset tables by prefix sums of instruction counts, relocate exactly the `:pc` operands (and shift register body start/end), rebuild the aggregate hash, and preserve the register empty-base `nil`-hash convention. `layout-images` / `stack-payload-rows` / `register-payload-rows` enforce the captured layout is a non-empty **prefix** of the task layout and rehash to identity (§4.1, §5).
- **Register payload** is validated by the existing `reffects/continuation-defect` (sparse regs, `live` ascending/distinct, `pc = site-pc + 1`, dest/tail-mode rules) plus an `:image`-equality check and a `site-op`/`context-sites` gate (§5.3, §7.2). `site-reasons` matches the §7.2 opcode→reason table exactly.
- **Walker**: closed `Kw` chain via `kw-fields`, addressed nodes validated to the correct row tag (`kw-node-tags`), `eval-operand` recovers the original application AST by dissociating runtime fields, FFI `request-sent`/`eval-call` bookkeeping is correlated against live pending call-ids (`walker-bookkeeping!`), and `decode-kw` rebuilds frames without inventing PCs (§5.4, §7.3).
- Lower rebuilds isolated code spaces (`stack/rebuild`, `register/rebuild`, `walker/attach-rows`+`clear-code`), clears the semantic code layout before rebuild (§9), restores waits in wire order, and delivers no wait result during restoration.

### 4. Verification evidence — SATISFIED (JVM only; see Finding F2)

`collab/jvm-final.log` tail reads `Ran 1197 tests containing 13596 assertions. 0 failures, 0 errors.` — matching the review assignment's claim exactly.

---

## Findings

| # | Severity | File:Line | Description | Recommendation |
|---|---|---|---|---|
| **F1** | Low (altitude / maintainability) | `handoff.cljc:1447` | `v1?` is now `(boolean (or (some? header) (::v1 opts) v2?))` — i.e. it no longer means "version 1" but "use the jing codec + exclusive-style lift", and it silently includes version 2. This is the exact conflation §10 warns against ("Do not replace `version == 1` with `>= 1`"). Behavior is correct because `tree-fork?`/`version-one?` are computed separately from validated version + structural role, but the name invites a future regression. | Rename to something like `jing-codec?` / `exclusive-lift?`, and keep the version-2 dispatch visibly separate from the v1 flag. |
| **F2** | Medium (verification completeness) | `collab/jvm-final.log` | Only the JVM run is evidenced. Spec §11 mandates the 12 acceptance rows on **JVM, Node, and Dart** for every profile and both modes, and D16's gate explicitly includes all three hosts. Cross-host portability within a profile is a first-class requirement (§1), and canonical-byte agreement (row 10) is only provable across hosts. | Run and attach Node and Dart results before D16. No code change implied. |
| **F3** | Medium (coverage breadth) | `test/yin/vm/ucf/handoff_v2_*.cljc` | 52 `deftest`s across 10 namespaces (~1,785 lines) cover a large and subtle surface. Several acceptance rows have no obviously dedicated namespace in the diff: row 2 (blocked continuation across *every* pending reason incl. both linker phases), row 5 (image growth with a prefix-layout continuation carrying cross-image return frames, empty base, row-boundary), row 9 (each D4–D9 export hold refused in root *and* child), row 10 (canonical bytes across hosts), row 12 (isolation/poisoning). | Confirm each of the 12 §11 rows maps to a real test (or add the gaps) before D16. |

---

## Detailed notes (non-blocking observations)

- **The `v1?`/`encode-bytes` interaction is correct despite its name.** Because `v1?` is true for v2, the v2 lift uses `jing.cbor/encode` *and* the `jing.cbor/refusal` try/catch that reports `:yin.k/non-portable` on a non-canonicalizable value (handoff.cljc:1625). Had the flag been keyed only off `version == 1`, v2 would have silently fallen to the stream codec and lost the non-portable catch — so the conflation is load-bearing and must not be "simplified" away without care. This is precisely why F1 is worth a rename rather than a one-line mechanical change.
- **`exact-int?` upper bound** uses `checkpoint/max-exact` (2^52−1) with `num-compare` semantics, matching spec §2's `N` bound; counters, PCs, offsets, lengths, register indices and layout length sums all route through it or `max-layout-length` (also 2^52−1, with a deterministic `:layout-overflow` refusal in `compose`).
- **`register/compose` aggregate hash** correctly re-hashes the relocated concatenation and never promotes a component image's hash to the aggregate (§4.1); the empty-base `nil`-hash convention is preserved via `(when (seq instructions) …)`.
- **`referenced-segments-v2` / `validate-closures-v2` / cell census** enforce the fixed-point "every carried image named, every named image carried, every declared cell reached, every reached cell declared" rule (§6, §8 step 3), with `:extra-code` / `:extra-cell` refusals.
- **`reachable-parked`** now delegates the non-semantic census to `census-v2`, which deliberately over-approximates (whole store) — admissible per §6's over-approximation allowance and consistent with the holder moving exactly the explicit parks plus justified FFI bookkeeping records.
- The `holder/export` and `holder/driver` changes thread `:version`/`:export-version` through without altering the v0/v1 recovery format's outer tag/version (§10) — the v2 body is embedded, not promoted.

## Conclusion

The four-kernel version-2 lift/lower is a faithful, high-quality realisation of the amendment. All four review invariants hold on the JVM, and I found no correctness defect. **ACCEPT**, contingent on closing F2 (Node/Dart runs) and confirming F3 (12-row coverage) before the D16 gate; F1 is a recommended readability cleanup.
