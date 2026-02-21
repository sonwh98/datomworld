# DaoDB Design: Abstracting DataScript Dependencies

## Context

Yin VM (`yin.vm.*`) currently depends on **DataScript** for two purposes:

1. **`yin.vm.cljc`** – defines a schema and `datoms‑>tx‑data` conversion (used only by UI/debugging tools).  
   The private `transact‑db!` function is never called and can be removed.

2. **`yin.vm.semantic.cljc`** – uses `d/q` for three helper functions (`find‑by‑type`, `find‑by‑attr`, `get‑node‑attrs`).  
   These helpers are **not required for the CESK machine**; the semantic VM already maintains an `index` map (`{eid [datom…]}`) that supplies all necessary node attributes during execution. The DataScript queries are only used by debugging UIs.

This DataScript dependency prevents the VM from running on **ClojureDart**, which has no DataScript port. Meanwhile, the project already has `IDaoDb` protocol designed to abstract the database.

## Goals

1. **Enable Yin VM to run on ClojureDart** – remove hard DataScript requirement.
2. **Keep UI/debugging tools functional** on JVM/ClojureScript (where DataScript is available).
3. **Align datom storage with DaoDB core** – use `IDaoDb` protocol everywhere.
4. **Maintain feature parity** – all existing VM tests must pass.

## Non‑Goals

* Rewriting DataScript’s full Datalog engine.
* Optimizing query performance (the semantic VM’s index map is sufficient for AST‑sized workloads).

## Proposed Design

### Phase 1 – Abstract DataScript via IDaoDb (immediate, low‑risk)

**Objective**: Make `yin.vm.*` loadable on ClojureDart by using `IDaoDb` and providing a portable dummy implementation.

**Changes**:

1. **Implement `DummyDaoDb`** in `src/cljc/dao/db.cljc`. This implementation will be pure Clojure/portable and handle the minimal set of queries needed by the VM.
2. **Update `IDaoDb` protocol** if necessary to support the required lookups efficiently.
3. **Refactor `yin.vm.semantic.cljc`** to take `IDaoDb` instead of direct DataScript DBs.
4. **Refactor `yin.vm.cljc`** – move DataScript-specific schema/conversion to a separate namespace or wrap in reader conditionals.
5. **Conditional DataScript loading** – wrap `(:require [datascript.core …])` in `#?(:clj :cljs)` reader conditionals.

**Result**: Yin VM can be loaded on ClojureDart using `DummyDaoDb`; UI tools continue to work on JVM/JS using `DaoDb` (DataScript-backed).

### Phase 2 – Unified Storage and Query (medium‑term)

**Objective**: Replace DataScript entirely with the project’s own index‑based database (`dao.db.core`).

**Changes**:

1. **Integrate `DaoDB` core** with `IDaoDb` protocol.
2. **Implement minimal Datalog** in `DummyDaoDb` or `DaoDB` to support broader UI needs.
3. **Migrate all UI components** to use `IDaoDb` exclusively.

## Implementation Steps

### Phase 1

| Step | File | Change |
|------|------|--------|
| 1 | `src/cljc/dao/db.cljc` | Implement `DummyDaoDb` (portable). |
| 2 | `src/cljc/yin/vm/semantic.cljc` | Update helpers to take `IDaoDb` instead of DataScript DB. |
| 3 | `src/cljc/yin/vm.cljc` | Wrap DataScript requires and schema in reader conditionals. |
| 4 | `src/cljs/datomworld/demo.cljs` | Update to use `dao.db/create` and `IDaoDb` protocol. |
| 5 | Run tests | Ensure `clj -M:test` passes. |

## Verification

1. **All existing VM tests pass**.
2. **ClojureDart compilation succeeds**.
3. **Demo UI works**.

---

*Last updated: 2026‑02‑21*
