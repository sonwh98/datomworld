Created-GMT: 2026-11-09 15:44:11 GMT
Created-Local: 2026-11-09 22:44:11 +07
Coding-Agent: qwen
Session-ID: b7fe24bd-9230-4ea5-9055-e9a44b1801f2

# Task: Step 2 — Engine/Composition Separation & Adapters Extraction

Role: DaoStream and Distributed Protocol Engineer

Implementers:
- Model: qwen3.8-max | Assigned: 2026-11-09 22:44:11 +07 | Status: active | Rationale: Step 2 of the yin.repl DHT composition refactor sequence (spec §6, §7, §9). Qwen CLI verified live and operational with HTTP 200.

## Governing Specification & Instructions
Read first:
- `docs/design/yin.repl.dht-composition.md` (specifically §2 The dependency rule, §3 Ownership split and ordering contract, §6 Coupling inventory checklist, §7 Adapter dispositions, §9 Step 2 exit conditions, and §10 Gate A & Gate B).

## Objective & Exit Condition (Step 2)
**Exit condition:** Engine owns no DHT state or dependency; local synchronous behavior preserved.

### 1. Checklist from Spec §6 (Coupling Inventory):
- `src/cljc/yin/repl.cljc`:
  - Line 22: Require `yin.repl.dht` -> **dies**.
  - Lines 775, 792–793: Linker construction / publication options -> becomes `dao.jing`-generic call; callback bridge dies.
  - Lines 922, 930–965: Store implementation selection (`:dht?`, principals, follow config) -> moves to composition (`yin.shell.*` or dedicated composition layer outside `yin.repl`). Engine construction accepts a prepared handle and service endpoints.
  - Lines 977–992: Node/spec/key ownership -> moves to composition; `:dht` and `:dht-key` engine fields die.
  - Lines 1279, 1293: Carrying DHT successor through evaluation failure -> moves to composition; engine rollback retains generic settlement identities only.
  - Lines 1357–1376: Calling linker/query with node -> moves to composition.
  - Lines 2069–2075: Advancing linker/node on ticks -> moves to composition.
  - Lines 2085–2091: Filtering `:dao.space.dht/event` and `:yin.head/event` -> moves to composition.
- `src/cljc/yin/repl/index.cljc`:
  - Lines 140–166, 419–450: `after-publish` callback bridge -> becomes generic prepare publication, emit request, promote only upon matching publication success.
  - Lines 490–525: Commits signed name-envelope data -> becomes generic call; composition supplies envelopes.
  - Line 549 (`notice`): Fix the `(name stage)` null defect uncovered in Step 1 shell retry test.
- `src/cljc/yin/repl/query.cljc`:
  - Lines 66, 73, 78–79: Imports of DHT, signed-name linker, head services -> move to composition.
  - Host module activations (`dao.space.dht`, `yin.link`, `yin.head`) -> move to composition. Local query serving retains bounded request/response semantics without node state.
- `src/cljc/yin/vm/linker/interpreter.cljc`:
  - Stop importing DHT implementation through `yin.vm.linker.interpreter` per spec §2.

### 2. Four Adapters Extraction (§7):
Extract the four stream interpreters to composition side:
- **Linker progression:** `serve-links`
- **Query services:** `serve-services`
- **Rollback state:** preserve settlement identities in engine, transport state in composition.
- **Event filtering:** `completion-events`

## Execution Constraints
- Work in `/Users/sto/workspace/datomworld-yin-repl-dht` on branch `yin-repl-dht-composition`.
- Maintain test coverage: the baseline pins created in Step 1 must remain green.
- Run focused individual tests locally (G0) or remote on `shiva` for suites; never run heavy suites on the laptop.
- Do NOT commit; leave edits staged/unstaged for review.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Report touched files, diff stat, Gate A grep audit result, and test verification outcomes.
