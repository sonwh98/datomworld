Created-GMT: 2026-09-13 08:43:53 GMT
Created-Local: 2026-09-13 15:43:53 +0700 (+07)
Coding-Agent: glm
Session-ID: 8fc82a93-61d0-4e6e-b53e-e8e9b90eba14

# Task: Runtime & Code-Contract Review — dao.space.index as a dao.stream Observer
Role: VM Runtime & Invariant Review

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-13 15:43:53 +0700 | Status: active | Rationale: Independent runtime review per team.md; strongest record on verifying design claims against the actual coordination and engine code (did so for run-on-stream, engine, and the walker across the macro rounds).

**Read-only review. Print your complete structured findings to stdout; write no files.**
Repository `/Users/sto/workspace/datomworld`, branch `dao.stream-redesign-v2`, HEAD `5296ee5`.

## Target
- `docs/design/dao.space.index.as-observer.md` (466 lines, committed). A design note, not yet implemented, not yet reviewed.

## Your angle
Every mechanism the note relies on is supposed to exist in code today; your job is to check that the note's claims about that code are *true*, and that the additions it specifies can be built on it without contradiction. Read the note fully, then verify each claim below against the named source.

## Read first
- `docs/design/dao.space.index.as-observer.md` — the target.
- `docs/design/datom.world.md` §Streams — the invariant (peer observers, symmetric ignorance).
- `docs/design/dao.space.index.md` — the library as documented.
- `src/cljc/dao/space/index.cljc` — `element-datoms` (:366), `datoms-from-elements`, `snapshot-datoms`, `index-datoms`, `recording-content-handle`, `publish-index!`.
- `src/cljc/dao/data/btree.cljc` — `conj` (:1742), `from-sequential` (:2054), `store-tree` (:2077), `restore-tree` (:2086), `IStorage` (:155), and whatever tracks whether a node is already stored.
- `src/cljc/dao/data/btree/storage.cljc` — `kv-storage`.
- `src/cljc/dao/stream/observer.cljc` — `run-on-stream`; `src/cljc/dao/stream/observe.cljc` — `step`.
- `src/cljc/dao/space/query.cljc` — `snapshot`, `current-state-seq`, `current`, `history`, and how a db-value is realized from trees (`realize-db-value!`).
- `src/cljc/dao/space/transactor.cljc` — `create!`/`derive-next-t`.
- `src/cljc/dao/datom.cljc` — `local-datom?`, `first-user-id`, `default-op`.
- `src/cljc/dao/jing.cljc` — `materialize!` (for the dedup claim).

## Claims to verify against code
1. **§2.2 step 1 / admission.** `element-datoms` rejects negative `e` via `datom/local-datom?`. Confirm, and confirm the note's proposed admission rule (negative `e`, negative declared-ref `v` as tempids; `t ≥ 0`, integer `m`, namespaced keyword `a`) can be expressed as a variant of the same per-element rule without changing `datoms-from-elements`' throwing contract.
2. **§2.2 step 3 / persistent fold.** `dao.data.btree/conj` is a persistent insert that shares structure. Confirm, including on a tree obtained from `restore-tree` (the checkpoint path) and on the empty set.
3. **§4.1 / dirty-tracking.** The note says `store-tree` "stores only the dirty subgraph" and that dirtiness lives in the *tree* (stored-address marks on nodes), so a recording handle can be drained after each flush without losing anything the next `store-tree` needs. Verify against `store-tree`/`-store-tree!` and the node/ref representation. Is "no-op returning the existing address when the set is already stored" true at *node* granularity or only at *root* granularity? If only root, the incremental claim is false as stated.
4. **§4.1 / blob order.** The recorded blobs since the last flush "in store order" are exactly the payloads to append, children before parents, manifest last. Verify with `recording-content-handle` and `kv-storage`.
5. **§2.3 / `current` and `history` over mixed `t`.** Resolution facts carry `t` = batch ordinal; observed rows carry the writer's `t` (or `0`). Verify against `current-state-seq` that greatest-`t`-wins per `[e a v]` cannot make a resolution fact shadow or be shadowed by an observed row, and that the "conflicting `[e a v t]` with differing `m`" rejection cannot be triggered by them.
6. **§2.1 / `run-on-stream` integration.** Trace, against `observer.cljc`, `ready? = staged nil`, `load = fold-batch`, `run = flush-staged`: a defective batch returns a state (no throw) → cursor advances; a staged-`full` publication → `ready?` false → `run` retries before the next read; `blocked`/`end`/`gap`. Does the loop's known progress-publication defect (a throw after a forwarded batch loses the session; see `yin.vm.macro.md` §5 prerequisite) affect an indexer whose `load` never throws on input?
7. **§3.1 / mode collision.** Confirm from `transactor.cljc` and `datom.cljc` that writer-allocated ids and the proposed observer allocator (from `first-user-id` upward) really would share a number line, so the two-mode rule is necessary rather than cautious.
8. **§4.2 / checkpoint resume.** `restore-tree` over a published manifest gives lazy trees; can `conj` then grow them (the note's "continues folding")? Does `restore-tree` need the manifest's `:count` and `:branching-factor`, and does the checkpoint shape carry enough to supply them?
9. **Cross-platform.** Anything in the additions that would not hold on cljs or cljd (`dao.data.btree` is one `.cljc`; the note assumes the same for the additions).

## Also
- Payload-agnosticism: does any *mechanism* in §2–§4 (not the motivation, not the tests) depend on the rows being `:yin/*` datoms?
- Anything you would block on before Phase 0′ starts.

## Output Format
Begin your output exactly with:
```text
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: glm
Session-ID: 8fc82a93-61d0-4e6e-b53e-e8e9b90eba14
Role: VM Runtime Review | Model: glm-5.3
```
Then, per claim 1–9: **holds / does not hold / partially**, with file:line evidence; then any findings by severity (`[P1 — blocking]`, `[P2 — must address]`, `[P3 — suggestion/alignment]`); then an explicit verdict: APPROVE or REQUEST CHANGES.
