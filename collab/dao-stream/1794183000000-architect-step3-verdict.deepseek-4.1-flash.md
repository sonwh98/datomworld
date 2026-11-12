Created-GMT: 2026-11-10 22:31:43 GMT
Created-Local: 2026-11-11 05:31:43 +07
Model: deepseek-4.1-flash
Role: Lead System Architect
Session-Type: read-only
Worktree: /Users/sto/workspace/datomworld-yin-repl-dht
Branch: yin-repl-dht-composition
Review-Target: Step 3 — Generic byte-budget enforcement across bulk, incremental, restore, and reuse (§5)

# Architectural Review: Step 3 — Generic Byte-Budget Enforcement

## Exit Condition (spec §9)

> "Generic byte-budget enforcement across bulk, incremental, restore, and reuse. Oversize refusal, terminating branch construction, policy compatibility and unchanged manifest proved."

## Findings

### 1. Bulk publication — `bounded-build` ✅
`dao.space.index/bounded-build` (line 690) halving-loops the count cap until every recorded blob and the manifest fit the declared ceiling. Terminates at `minimum-page-cut` (4) with an explicit `oversize-refusal` throw. A `nil` ceiling declares no budget: one build at the declared cap, measured against nothing. The manifest's `:branching-factor` records the cap the successful build actually cut at.

### 2. Incremental publication — `publish!` ✅
`publish!` (line 1340) checks two policy refusals before staging anything:
- `::uncertified-reuse` — byte ceiling declared over a closure nobody certified against it.
- `::oversize-payload` — a recorded blob or the manifest exceeds the ceiling.
Refusal returns state unchanged, nothing staged, nothing appended. Retry refuses identically — **terminating, not looping**. An incremental session cannot recut pages its trees were folded at, so it refuses and the caller rebuilds through the bounded bulk path (R2).

### 3. Restore — `restore` ✅
`restore` (line 1620) accepts `:max-payload-bytes` and `:published-policy` as explicit inputs. Refuses when the declared count cap differs from the manifest's. When `same-policy?` returns false (changed or unknown policy), calls `certify-closure!` which walks every blob reachable from the manifest's four roots, re-encodes via `jing/canonical-bytes` (the same encoding the publishing path measures at), and throws on the first blob the budget cannot carry. Restored session carries `:publication-policy` and `:policy-certified true`.

### 4. Reuse — `suffix-state` / `restoreSession` ✅
`suffix-state` (line 463) checks **both** `:branching-factor` and `:max-payload-bytes` match before folding the suffix. A mismatch forces full reconstruction. `restoreSession` (line 448) passes the retained frontier's `:materialization :policy` as `:published-policy` to `index/restore`, so a frontier published under a higher ceiling — or before any ceiling was declared — reconstructs instead of publishing an uncertified suffix.

### 5. Terminating branch construction — `from-sorted-array` ✅
`btree.cljc` line 2064: `(when-not (< (count ranges) n) (throw ...))`. A fill policy whose average group holds a single node (branching factor 2, or any byte-derived grouping a large separator datom will not split) refuses explicitly rather than recurring forever. Tested at branching factor 2 over [2 3 4 17] elements.

### 6. Policy compatibility — `same-policy?` ✅
`same-policy?` (line 597) compares both `:branching-factor` and `:max-payload-bytes`, nil ceilings included. `publication-policy` always normalizes both keys, so no comparison against an absent key. `restore` refuses a foreign count cap; `suffix-state` refuses a foreign byte ceiling. An unknown policy (`nil`) is never the same as a declared one.

### 7. Unchanged manifest ✅
Both `build-at` (line 679) and `publish!` (line 1393) construct the manifest as exactly `{:indexes … :count … :branching-factor …}`. No `:max-payload-bytes` enters the manifest — budget compatibility is tracked in live composition state only. Test explicitly asserts `(= #{:indexes :count :branching-factor} (set (keys manifest)))`.

### 8. Engine-level threading ✅
- `make-indexer` accepts `:publication-policy` (legacy `:publish-opts` still accepted, `publication-policy` wins).
- `publication-policy` derived at each publication from current opts, not a retained snapshot.
- `create-state` → `make-session` → `make-indexer` threads the policy through.
- DHT composition declares `:publication-policy {:branching-factor 64}` with documented R3/R5/R6 retirement conditions — the hardcoded cap is preserved as a transitional measure with explicit retirement criteria, not relocated as permanent policy.

### 9. Test coverage ✅
- `dao.data.btree-test`: non-reduction guard, single-element trees, smallest reducing cap.
- `dao.space.index-test`: policy normalization, `same-policy?`, validation, bounded build with page cutting, manifest schema invariance, oversize refusal (clean intake), incremental refusal (state unchanged, terminating), restore certification (foreign cap, lowered ceiling, fitting ceiling).
- `yin.repl.index-test`: same-policy suffix fold (no reopening), changed-cap reconstruction, newly-declared byte ceiling reconstruction and terminal refusal, frontier preservation on refusal.

---

## Determination

All eight sub-conditions of the Step 3 exit condition are satisfied. The generic publication policy vocabulary (`publication-policy`, `same-policy?`, `build-at`, `bounded-build`, `certify-closure!`) is correctly threaded through bulk, incremental, restore, and reuse paths. Oversize refusal terminates. Branch construction refuses rather than looping. Policy compatibility protects reuse. The manifest schema is unchanged.

**STEP 3 SIGN-OFF: APPROVED**
