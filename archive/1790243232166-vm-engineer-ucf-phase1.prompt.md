Created-GMT: 2026-09-24 09:47:12 GMT
Created-Local: 2026-09-24 16:47:12 +0700
Coding-Agent: claude
Session-ID: 5703a677-1239-493a-b3cc-67468505a580

# Task: UCF Phase 1 — Canonical Instruction Vector & Safepoint Reconstruction Metadata

Role: Yin.VM Runtime Engineer

Implementers:
- Model: claude-sonnet-5 | Assigned: 2026-09-24 16:47:12 +0700 | Status: active | Rationale: Phase 1 implementation of Universal Continuation Format (UCF) per yin.vm.universal-continuation-format.md and team.md

Implement UCF Phase 1 in `/Users/sto/workspace/datomworld-ucf`.

Read first:
- `docs/design/yin.vm.universal-continuation-format.md` (specifically §7.1, §7.2, §7.3, §7.4, and §7.11)
- `docs/design/yin.vm.semantic.md` (§1–§4, opcode table, well-formedness rules)
- `docs/design/datom.world.md`
- `src/cljc/yin/vm/semantic.cljc`
- `src/cljc/dao/jing.cljc`

Context & Layering:
CBOR encoding is an implementation detail inside `dao.jing`. UCF operates over pure data structures and content addresses via `(dao.jing/segment-key vector)` and must not touch or depend on CBOR serialization specifics.

Scope & Acceptance Criteria for Phase 1:
1. §7.3 Canonical Instruction Vector:
   - Implement `batch->canonical-instruction-vector`:
     Accepts a well-formed semantic code batch, applies the loader's resolved interpretation (index-batch collapse, last-value-wins), and produces the positional instruction tuple vector `[[:closure [x] 6] [:push] [:const 10] ...]`.
   - PC is index; no entity IDs or attribute keywords in tuples.
   - Saturation: default operands are materialized per §7.3.2.
   - Non-canonicalizable check: refuse with `:yin.k/non-portable` and `:yin.k/kind :non-canonicalizable` on unknown opcodes or invalid tuple shapes.
   - Code identity: compute `:yin.code/hash` via `(dao.jing/segment-key vector)`.
   - Contract stamp: include the execution contract stamp identifying the tuple grammar and execution semantics per §7.3.3.
2. §7.4 Safepoint Reconstruction Metadata:
   - Identify parking transitions across the 12 semantic VM transitions (including effect-producing `:call`s).
   - Implement static safepoint metadata derivation:
     Separate static properties derivable from code (stack effect, lexically required environment names) from dynamic frame activation state (absolute depth, captured bindings).
3. Test Coverage:
   - Add comprehensive unit tests in `test/yin/vm/ucf_test.cljc` covering:
     - Canonical instruction vector generation across semantic test batches.
     - Equivalence laws: alpha-equivalent and defaulted variants produce identical instruction vectors and identical addresses.
     - Distinction laws: semantically differing programs produce distinct vectors and addresses.
     - Safepoint metadata derivation and stack/env requirement accuracy.
4. Invariants & Code Hygiene:
   - Pure ASCII only.
   - Lines strictly <= 80 columns.
   - Zero linter errors or warnings (`clj -M:kondo`).
   - Clean `cljstyle check`.
   - Pass `bb test:clj`, `bb test:cljs`, `bb test:cljd` in `/Users/sto/workspace/datomworld-ucf`.

Work only in named files. If a required dependency demands expansion, stop and report before editing. Preserve unrelated changes, do not weaken tests, and preserve CESK and execution-parity invariants.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact test/check outcomes, unresolved concerns, and any incomplete work. Do not claim edits or tests that did not occur.
