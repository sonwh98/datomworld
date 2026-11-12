Created-GMT: 2026-11-09 18:18:00 GMT
Created-Local: 2026-11-10 01:18:00 +07
Model: gpt-6-astra
Role: Lead System Architect
Session-Type: read-only
Worktree: /Users/sto/workspace/datomworld-yin-repl-dht
Branch: yin-repl-dht-composition
Base-Commit: 71dd717da07ce23a744aed4fe10ccbeabeae9333

# Architectural Review Request: Step 2 — Engine/Composition Separation & Adapters Extraction

## Context & Scope
Governing specification: `docs/design/yin.repl.dht-composition.md` (§2 The dependency rule, §3 Ownership split and ordering contract, §6 Coupling inventory checklist, §7 Adapter dispositions, §9 Commit sequence - Step 2, §10 Verification matrix and swappability gates).

The implementation for Step 2 has been prepared in `/Users/sto/workspace/datomworld-yin-repl-dht` (uncommitted, based on Step 1 commit `71dd717d`).

## Step 2 Completed Scope
1. **Engine/Composition Separation (§6 Coupling Inventory):**
   - `src/cljc/yin/repl.cljc`:
     - Dropped `[yin.repl.dht :as repl.dht]`.
     - Removed `:dht`, `:dht-key` from engine state map.
     - Store implementation selection relocated; engine accepts prepared handle and service endpoints (`:index-store`, `:link-source`, `:recovery`, `:publication-media`, `:host-modules`).
     - Engine rollback retains generic settlement identities only (`::query-pair`, `::linker`, `::indexer`).
     - Replaced key-bearing `link-context` with pure `service-context [state vm]` returning `{:primitives :modules :round}`.
     - Advancing linker/node on ticks converted to `round-local [linker]`.
     - Generic completion event matching (`{:op :dependency :manifest m}`, `{:op :principal :principal p}`); DHT-specific event keywords dropped from engine.
   - `src/cljc/yin/repl/index.cljc`:
     - Replaced `:after-publish` callback bridge with generic publication stream protocol (`yin.repl.publication`): `prepare-publication`, `emit!`, `accept-publication-result`, `settle-publication`, `pending-publication`.
     - Retained exact `held-keys` in `make-indexer` (`:publication-media`, `:publication-seq 0`, `:pending-publication nil`).
     - Fixed `notice` to avoid `(name stage)` throwing when stage is nil or when publication failure clears.
   - `src/cljc/yin/repl/query.cljc`:
     - Dropped direct DHT imports (`dao.space.dht`, `yin.repl.dht`, `yin.vm.linker.dht`, `yin.vm.linker.head`).
     - Relocated DHT host modules (`dao.space.dht`, `yin.link`, `yin.head`) to composition namespace `yin.shell.query`.
     - Provided generic `register` mechanism accepting composition's `registrations`.
     - Restored local query/unknown-operation evaluation semantics so non-foreign requests refuse locally and do not stall the engine.
   - `src/cljc/yin/vm/linker/interpreter.cljc`:
     - Added `signed-source?` and `round-local [linker budget]` for nodeless progression of local link sources.
2. **Four Adapters Extracted (§7):**
   - `src/cljc/yin/shell/linker.cljc`: `serve-links`
   - `src/cljc/yin/shell/query.cljc`: `serve-services`
   - Rollback state: engine retains settlement identities only; transport state stays in composition.
   - `src/cljc/yin/shell/completion.cljc`: `completion-events`
   - Neutral module helper `src/cljc/yin/shell/registry.cljc` (`linked-registry`, `session-modules`).
   - `src/cljc/yin/shell/session.cljc`: composition-level `serve`, `recheck-on-tick`, `recheck-pending`.
3. **Verification Evidence on Remote Host `shiva.stigmergy.systems`:**
   - `yin.repl.index-test`: 30 tests, 650 assertions, 0 failures, 0 errors.
   - `yin.repl.store-test`: 28 tests, 145 assertions, 0 failures, 0 errors.
   - `yin.repl.require-test`: 20 tests, 209 assertions, 0 failures, 0 errors.
   - `yin.repl.query-test`: 24 tests, 392 assertions, 0 failures, 0 errors.
   - Overall suite across all 4 parameterized packages: **102 tests, 1,396 assertions, 0 failures, 0 errors**.
   - Shell retry defect pinned in Step 1 (`shell-retry-after-refused-publication-promotes-the-whole-suffix`) is green.
4. **Gate A Grep Audit:**
   - Literal `dao.jing.dht` check across `src/cljc/yin/repl/` and `src/cljc/yin/repl.cljc`: only hits remaining are in `store.cljc` and `dht.cljc` (scheduled for Step 4 relocation).
   - Engine files (`src/cljc/yin/repl.cljc`, `src/cljc/yin/repl/index.cljc`, `src/cljc/yin/repl/query.cljc`, `src/cljc/yin/repl/publication.cljc`): ZERO imports or state fields of `dao.space.dht`, `yin.repl.dht`, `yin.vm.linker.(dht|head)`, `:dht\b`. Only hits are design doc markdown filenames.

## Review Mandate
Please inspect the changes in `/Users/sto/workspace/datomworld-yin-repl-dht` (via git diff against `71dd717d`) and evaluate against the Step 2 exit conditions of `docs/design/yin.repl.dht-composition.md` §9.
Provide your assessment, findings, and formal Step 2 Sign-Off recommendation.
