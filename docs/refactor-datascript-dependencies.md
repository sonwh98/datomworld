# DaoDB Design: Abstracting DataScript Dependencies

## Context

Yin VM (`yin.vm.*`) currently depends on **DataScript** for two purposes:

1. **`yin.vm.cljc`** – defines a schema and `datoms‑>tx‑data` conversion (used only by UI/debugging tools).  
   The private `transact‑db!` function is never called and can be removed.

2. **`yin.vm.semantic.cljc`** – uses `d/q` for three helper functions (`find‑by‑type`, `find‑by‑attr`, `get‑node‑attrs`).  
   These helpers are **not required for the CESK machine**; the semantic VM already maintains an `index` map (`{eid [datom…]}`) that supplies all necessary node attributes during execution. The DataScript queries are only used by debugging UIs.

This DataScript dependency prevents the VM from running on **ClojureDart**, which has no DataScript port. Meanwhile, the project already has two independent database implementations:

* `dao.db` – DataScript‑backed `IDaoDb` protocol (used by UI components).
* `dao.db.core` – newer index‑based implementation (`DaoDB` with temporal/structural indexes), designed to be the canonical storage layer.

## Current State

| Component | DataScript dependency | Purpose | Portable? |
|-----------|-----------------------|---------|-----------|
| `yin.vm` | `schema`, `datoms‑>tx‑data`, `transact‑db!` (unused) | UI/debugging | No (requires DataScript) |
| `yin.vm.semantic` | `d/q` in three helper functions | Debugging/UI only | No |
| `dao.db` | Full DataScript backend | Provides `IDaoDb` protocol | No |
| `dao.db.core` | None | Temporal/structural indexes; pure Clojure | **Yes** |

The semantic VM’s execution loop already works with raw datom vectors and an entity‑index map; the DataScript queries are **superfluous** for correct operation.

## Goals

1. **Enable Yin VM to run on ClojureDart** – remove hard DataScript requirement.
2. **Keep UI/debugging tools functional** on JVM/ClojureScript (where DataScript is available).
3. **Align datom storage with DaoDB core** – eventually replace DataScript with the project’s own index‑based database.
4. **Maintain feature parity** – all existing VM tests must pass.

## Non‑Goals

* Rewriting DataScript’s full Datalog engine.
* Optimizing query performance (the semantic VM’s index map is sufficient for AST‑sized workloads).
* Changing the AST‑datom format or the CESK machine model.

## Proposed Design

### Phase 1 – Remove DataScript from Yin VM (immediate, low‑risk)

**Objective**: Make `yin.vm.*` loadable on ClojureDart without breaking existing JVM/JS UI.

**Changes**:

1. **Delete unused code** – remove `transact‑db!` from `yin.vm.cljc`.
2. **Move DataScript‑dependent utilities to a new namespace** – extract `schema` and `datoms‑>tx‑data` into `yin.vm.datascript` (conditionally required only on `:clj`/`:cljs`).
3. **Replace DataScript queries in semantic VM with linear scans** – implement `find‑by‑type`, `find‑by‑attr`, `get‑node‑attrs` to operate directly on the datom vector or the existing `index` map.
4. **Conditional DataScript loading** – wrap `(:require [datascript.core …])` in `#?(:clj :cljs)` reader conditionals; for `:cljd` provide stub functions that return empty results (or implement simple in‑memory indexing).
5. **Update UI components** – ensure demo and structural‑editor pages require `yin.vm.datascript` (or conditionally load DataScript) instead of pulling it through `yin.vm`.

**Result**: Yin VM can be loaded on ClojureDart; UI tools continue to work on JVM/JS because DataScript remains present there.

### Phase 2 – Integrate with DaoDB Core (medium‑term)

**Objective**: Replace DataScript entirely with DaoDB as the canonical datom store, unifying storage across the project.

**Changes**:

1. **Extend DaoDB with an EAV index** – add a new index type (`dao.db.index.eav`) that supports lookups by entity, attribute, value, and simple Datalog‑style queries. Can be built on top of the existing `temporal` index’s `range‑seek` capability.
2. **Enhance `IDaoDb` protocol** (in `dao.db`) with methods needed by the semantic VM:
   * `entity‑attrs` – return map of attributes for a given entity.
   * `find‑by‑attr` – return all entities having a given attribute (optionally with a specific value).
   * `find‑by‑type` – convenience wrapper over `find‑by‑attr` for `:yin/type`.
3. **Provide two backends**:
   * `SimpleDaoDb` – uses the new EAV index (pure Clojure, portable to ClojureDart).
   * `DataScriptDaoDb` – existing DataScript‑backed implementation (for JVM/JS, kept for performance and full Datalog).
4. **Refactor semantic VM to use `IDaoDb`** – replace the `index` field with a `DaoDb` instance; update attribute lookups to call protocol methods.
5. **Remove DataScript dependency from the entire codebase** – delete `yin.vm.datascript` and migrate UI components to use `DaoDb` transactions.

**Result**: A single, portable datom‑store abstraction that works across all targets (JVM, JS, Dart), backed by either DataScript (performance) or a simple index (portability).

### Phase 3 – Unified Query Interface (optional, long‑term)

**Objective**: Offer a consistent Datalog‑like query API across all backends, enabling complex queries on portable implementations.

**Changes**:

* Implement a minimal Datalog evaluator in `dao.db.query` that works over the EAV index.
* Keep the query language subset small (enough to support the VM’s debugging needs).
* Use the same query syntax as DataScript for compatibility.

## Implementation Steps

### Phase 1 (estimated: 2–3 days)

| Step | File | Change |
|------|------|--------|
| 1 | `src/cljc/yin/vm.cljc` | Remove `transact‑db!`; move `schema` and `datoms‑>tx‑data` to new namespace. |
| 2 | `src/cljc/yin/vm/semantic.cljc` | Replace `d/q` calls with linear‑scan implementations. |
| 3 | `src/cljc/yin/vm/datascript.cljc` (new) | Conditionally define DataScript‑dependent helpers. |
| 4 | `src/cljc/yin/vm.cljc` `src/cljc/yin/vm/semantic.cljc` | Wrap DataScript requires in `#?(:clj :cljs)`. |
| 5 | `src/cljs/datomworld/demo.cljs` `src/cljs/datomworld/structural_editor.cljs` | Update requires to use `yin.vm.datascript` where needed. |
| 6 | Run tests | Ensure `clj -M:test` passes for all VM backends. |

### Phase 2 (estimated: 1–2 weeks)

| Step | File | Change |
|------|------|--------|
| 1 | `src/cljc/dao/db/index/eav.cljc` (new) | Implement EAV index with `entity‑attrs`, `find‑by‑attr`, `find‑by‑type`. |
| 2 | `src/cljc/dao/db/core.cljc` | Integrate EAV index into `DaoDB` record. |
| 3 | `src/cljc/dao/db.cljc` | Extend `IDaoDb` protocol with new methods; provide `SimpleDaoDb` implementation. |
| 4 | `src/cljc/yin/vm/semantic.cljc` | Replace `index` with `DaoDb` instance; update attribute lookups to use `IDaoDb`. |
| 5 | UI components | Migrate from DataScript‑specific calls to `IDaoDb` protocol. |
| 6 | Remove `yin.vm.datascript` | Delete the temporary namespace. |

## Risks & Trade‑offs

* **Performance**: Linear scans over datom vectors may be slower for large ASTs, but ASTs are small (≤ hundreds of nodes). The semantic VM’s existing `index` map already provides O(1) entity lookups.
* **Query capability**: Phase 1 loses Datalog queries on ClojureDart, but those were only used for debugging. Phase 2 restores basic attribute‑based queries via the EAV index.
* **Code duplication**: Keeping two backends (`SimpleDaoDb` and `DataScriptDaoDb`) adds maintenance overhead, but ensures compatibility with existing UI tools.

## Verification

After each phase:

1. **All existing VM tests pass** – `clj -M:test` (JVM) and `npx shadow‑cljs compile test && node target/node‑tests.js` (ClojureScript).
2. **ClojureDart compilation succeeds** – `clj -M:cljd compile` (no missing DataScript errors).
3. **Demo UI works** – both the structural editor and the VM demo page render and evaluate ASTs correctly.

---

## Decision Points

1. **Phase 1 vs Phase 2 prioritization**: Phase 1 unblocks ClojureDart immediately with minimal risk. Phase 2 is a larger architectural change that aligns with the project’s long‑term vision.
2. **Query API scope**: Should the EAV index support full Datalog, or only the three helper functions needed by the semantic VM? Starting with the minimal set keeps implementation simple.

## Next Steps

1. **Approve this plan** – confirm the approach matches the project’s direction.
2. **Start Phase 1** – implement the DataScript removal as described.
3. **Schedule Phase 2** – after Phase 1 is stable, decide whether to proceed with full DaoDB integration.

---

*Last updated: 2026‑02‑21*