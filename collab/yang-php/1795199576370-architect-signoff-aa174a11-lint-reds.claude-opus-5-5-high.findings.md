Created-GMT: 2026-11-20 18:32:56 GMT
Created-Local: 2026-11-21 01:32:56 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: agy
Session-ID: 163f30a4-72a0-4bc7-8e3b-799f88224f02

# Task: Architect sign-off on fix/aa174a11-master-lint-reds (yin.vm.module/code-entry-handler + eval_coordinator allowlist)

Role: Lead System Architect

Implementers:
- Model: gemini-4-argon-high | Assigned: 2026-11-21 01:32:56 +07 (Asia/Ho_Chi_Minh) | Status: active | Rationale: Cross-family Architect sign-off (Gemini Argon vs codex/gpt-6.2-sol author) requested by owner before landing fix/aa174a11-master-lint-reds
- Status-Event: 2026-11-21 01:35:40 +07 (Asia/Ho_Chi_Minh) | Model: gemini-4-argon-high | Status: failed | Rationale: 429 RESOURCE_EXHAUSTED (resets in 118h17m12s; conversation 697b52fb-5536-4719-9247-2ba9a18f9a45); 0 sign-off content returned
- Model: claude-opus-5-5-high | Assigned: 2026-11-21 01:35:40 +07 (Asia/Ho_Chi_Minh) | Status: active | Rationale: Re-routed to independent AGY model pool (Claude family, cross-family vs codex/gpt-6.2-sol author, frontier Architect tier per team.md)

Completed-GMT: 2026-11-20 18:37:45 GMT
Completed-Local: 2026-11-21 01:37:45 +07 (Asia/Ho_Chi_Minh)

# Architect sign-off on `fix/aa174a11-master-lint-reds`

**What I reviewed:** the 3-file patch [1795163989182-qa-engineer-master-reds-aa174a11.diff.patch](file:///Users/sto/workspace/datomworld/collab/yang-php/1795163989182-qa-engineer-master-reds-aa174a11.diff.patch), against `master@9cf135c2`. This was a read-only review. `git apply --check` passes (3 files, +33/−2). I checked every claim directly in the source and did not rely on the earlier Step-7 report. I ran no tests and edited no files, as the brief required. The Step-6 test results are the orchestrator's, not mine.

## Findings

**No architectural defects found.** The items below are non-blocking. Some are follow-ups that predate this patch; the rest are notes.

| severity | file:line | invariant/evidence | recommended correction |
|---|---|---|---|
| Low (follow-up, predates patch) | `docs/design/dao.jing.call-site-classification.md` | [dao.jing.hash-registry.md:343](file:///Users/sto/workspace/datomworld/docs/design/dao.jing.hash-registry.md#L343) and line 611 make the call-site classification a maintained artifact. Two entries are missing from it: [module.cljc:709](file:///Users/sto/workspace/datomworld/src/cljc/yin/vm/module.cljc#L709) (`code-entry-handler`, now a Class-3 address-directed validation site) and [eval_coordinator.cljc:68](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/eval_coordinator.cljc#L68) (a Class-2 mint site). | In a separate docs commit, add both sites with their classes. This should not hold up this landing. |
| Info | `test/yin/vm/code_entry_test.cljc` (new deftest) | One case is not pinned directly: the stored address is SHA-256 and the request is the BLAKE3 address of the same manifest. That request is refused by the `(not= address (:address entry))` pin at line 708, and the contract test covers how the address parser treats algorithm relabels. | Optional: add one row to the new `doseq` table for this case. |
| Info | [store_write_audit_test.clj](file:///Users/sto/workspace/datomworld/test/yin/vm/store_write_audit_test.clj) (new allowlist entry) | `store` is passed into `publication/publish!` across a function boundary. The detector's docstring already lists this as an undetected residual. I checked by hand: it is a `dao.jing` byte-store handle, used only through `jing/materialize!` and the `siteset` overlay. It never touches the VM program `:store`. | None needed. The allowlist reason is accurate. |

## Properties that passed review

1. **Address-directed verification.** The change at line 709 replaces re-minting under the default algorithm with `jing/segment-matches?`. That function reads the algorithm from the address itself (`parse-segment-address`) and never consults `default-hash-algorithm` ([jing.cljc:303-317](file:///Users/sto/workspace/datomworld/src/cljc/dao/jing.cljc#L303-L317)). This matches hash-registry.md §Invariants lines 50–51 and §`segment-matches?` line 248. The old line was a genuine contract violation, not just a lint false positive: an explicitly minted SHA-256 manifest would have been refused.
2. **The fix doesn't weaken anything.** For BLAKE3 addresses the new check behaves exactly like the old one. For SHA-256 it is strictly more correct. The address-pin check `(not= address (:address entry))` and the name check are kept, so the handler still serves only the installed, pinned entry.
3. **It fails closed on untrusted guest `:code/entry` input.** Each of these is refused with `:manifest-mismatch`:
   - **Nil requested address:** caught by `nil?`.
   - **Host modules:** `register-host-module` stores `:address nil` and keeps exports in `:slice`, not `:bindings` (lines 264–266). These entries are refused whatever address is requested, so the code-entry path cannot reach host modules.
   - **Malformed, unknown-algorithm, uppercase, wrong-length or non-canonical addresses:** the parser returns nil, so `segment-matches?` returns false.
   - **Tampered manifests:** the digest doesn't match.
   - **CBOR encoder refusal:** caught on all three hosts (`:cljd Object` / `:clj Throwable` / `:cljs :default`) and returns false. Before, this threw an exception out of the effect handler, so this is a small robustness gain.
   - **Non-symbol `module`:** gives a nil entry and is refused as `:module-missing`.
4. **Minting boundary is intact.** `link-module` at [module.cljc:95](file:///Users/sto/workspace/datomworld/src/cljc/yin/vm/module.cljc#L95) is still a pure Class-2 mint with no incoming address, and is correctly left alone. Minting and verification stay separate, per hash-registry.md line 52.
5. **The architectural lint is satisfied by fixing the code, not by exempting it.** `flags-equality-validation?` no longer matches line 709. The exemption set is unchanged (`jing.cljc`, `jing/file.cljc`).
6. **Store-write audit (Rule R, datom.world.md:127-130).** The map literal at [eval_coordinator.cljc:76](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/eval_coordinator.cljc#L76) carries the composition's `dao.jing` byte-store handle, which `siteset/stage` wraps in a write-buffered overlay. It is not a VM program store write. The allowlist entry `{["compile-and-publish*!" :map] 1}` matches exactly one site and follows the existing precedent: the `siteset/stage` entries for PHP and Python and `linker.interpreter/dht-context`. `serve` changes only `:cursor` and `:pending`.
7. **State and control flow are explicit, and the handler is host-isolated.** `code-entry-handler` is still a pure function of `(state, effect)`. It returns state unchanged with `:blocked? false` and has no side effects or hidden state.
8. **Portability (CLJ/CLJS/CLJD).** The production change uses only portable `dao.jing` API. The new test uses only `clojure.test`, `doseq` destructuring, `keyword`/`str` and public `dao.jing`/`yin.vm.module` functions. The orchestrator's Node and Dart parity runs agree.
9. **Migration risk: none.** There are no address format changes, no fixture regeneration and no registry change. This is consistent with hash-registry.md's clean-break policy.
10. **Scope.** The patch is minimal (a 1-line source change plus a focused test and one allowlist entry) and touches no out-of-scope files.

## Verdict

The production change fixes a real violation of the address-directed verification invariant. The allowlist entry correctly classifies a byte-store handle, not a VM store write. The only follow-up is the classification doc update, which predates this patch and doesn't block it.

**`SIGN-OFF GRANTED (MERGE-READY)`**
