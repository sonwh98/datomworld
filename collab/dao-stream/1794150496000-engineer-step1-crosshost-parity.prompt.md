Created-GMT: 2026-11-08 15:08:16 GMT
Created-Local: 2026-11-08 22:08:16 +07
Coding-Agent: muse
Session-ID: b9605dff-eed1-43f7-b1c7-7ecf56d04b5a

# Task: Step 1 Final Pin — Narrow Cross-Host Parity Measurement across JVM, Node, Dart

Role: DaoSpace and DaoJing Storage Engineer

Implementers:
- Model: muse-wonder-1.0 | Assigned: 2026-11-08 22:08:16 +07 | Status: active | Rationale: Complete remaining open finding 2 from Lead System Architect gpt-6-astra (spec §8 cross-host parity)

Read first:
- `collab/dao-stream/1794150337000-architect-step1-review-r2.gpt-6-astra.findings.md`
- `docs/design/yin.repl.dht-composition.md` (§8 Cross-host parity: "Identical supported inputs, publication path, and policy yield identical bytes and addresses across JVM, Node, and Dart. Bulk and incremental paths need logical equivalence; they need not produce identical tree shapes.")
- Existing uncommitted work in `/Users/sto/workspace/datomworld-yin-repl-dht`

## Exact Requirement
Provide a narrow, deterministic publication comparison across the three runtimes (JVM, Node, Dart) to establish the §8 cross-host parity oracle before relocation:
1. Run or add a narrow deterministic test in `test/yin/repl/index_test.cljc` (or via fixture) asserting identical published bytes and manifest content addresses for an identical input program across JVM, Node, and Dart.
2. Execute the narrow test var on:
   - JVM: `clojure -M:test -n yin.repl.index-test -v ...`
   - Node: `node target/node-tests.js --test=yin.repl.index-test` (build node bundle if needed via `bb build:yin-repl-node` or shadow-cljs)
   - Dart: `bb src/dev/cljd_agg.clj --only yin.repl.index-test/<test-name>`
3. Record exact commands, runtimes, executed test identifiers, exact byte/manifest outputs, and full logs in `/tmp/step1-evidence/cross-host/`.

## Constraints
- Keep changes in `test/yin/repl/`; do NOT edit `src/`.
- Do not run wide suites; run ONLY the narrow parity test var on each runtime.
- Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
