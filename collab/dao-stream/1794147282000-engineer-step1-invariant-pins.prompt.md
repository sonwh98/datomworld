Created-GMT: 2026-11-08 14:14:42 GMT
Created-Local: 2026-11-08 21:14:42 +07
Coding-Agent: muse
Session-ID: e8c6e6a5-b58b-45ac-914b-a696f4512f9e

# Task: Pin baseline contracts and parameterize non-DHT fixtures (Step 1)

Role: DaoSpace and DaoJing Storage Engineer

Implementers:
- Model: muse-wonder-1.0 | Assigned: 2026-11-08 21:14:42 +07 | Status: active | Rationale: Step 1 of spec §9 commit sequence; dedicated scratch worktree datomworld-yin-repl-dht

## Governing Specification & Instructions
Read first:
- `docs/design/yin.repl.dht-composition.md` (specifically §8 Invariants to pin before relocation, and §9 Commit sequence Step 1).
- Existing fixtures referenced in spec §8:
  - `test/yin/repl/index_test.cljc` (specifically lines around 385, 421, 470, 501, 553, 734)
  - `test/yin/repl/require_test.cljc` (specifically lines around 244–766)
  - `test/yin/repl/query_test.cljc` (specifically lines around 283, 314, 663)
  - `test/yin/repl/store_test.cljc` (specifically lines around 833–1425)

## Scope & Acceptance Criteria (Step 1 Exit Condition)
Exit condition: Existing invariants green; new publication-result expectations explicit.

1. **Parameterize non-DHT fixtures:**
   - For index, require, query, and store test fixtures that test engine behavior, ensure they can run against non-DHT storage handles (e.g. in-memory or generic file-backed `dao.jing` store) without requiring `dao.jing.dht`.
2. **Make publication-result expectations explicit:**
   - Establish explicit assertions for local publication request and correlated publication result records per spec §3 and §8 (Frontier gate: manifest identity, published transaction count, count-cap compatibility, cursor ownership, and expected suffix length; failed HEAD persistence must not advance the frontier).
3. **Verify invariant baseline:**
   - Keep changes strictly contained to `test/yin/repl/` (and any minimal non-DHT fixture helpers if required under `test/yin/`). Do NOT refactor the engine implementation in `src/` yet (that belongs to Step 2).
   - Ensure existing tests pass or are cleanly parameterized.

## Execution Rules
- Work in `/Users/sto/workspace/datomworld-yin-repl-dht` on branch `yin-repl-dht-composition`.
- Do NOT commit your changes; leave them in the working tree for verification and review.
- Run focused tests individually (e.g. `clojure -M:test -v ...`). Do not run full test suites locally.
- Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
- Report modified files, diff summary, and focused test outcomes.
