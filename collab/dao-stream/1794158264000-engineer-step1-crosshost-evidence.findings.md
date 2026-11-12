# Step 1 Invariant Evidence: Tri-Host Parity Verification (JVM, Dart, Node)

Created-GMT: 2026-11-08 17:17:44 GMT
Created-Local: 2026-11-09 00:17:44 +07
Coding-Agent: agy
Branch: `yin-repl-dht-composition` @ `b88bad9dd93d233f1d78e5a111444f6c8d86c9aa`

## Overview
This document records the exact execution evidence for the §8 Cross-host parity invariant:
"Identical supported inputs, publication path, and policy yield identical bytes and addresses across JVM, Node, and Dart. Bulk and incremental paths need logical equivalence; they need not produce identical tree shapes."

The narrow test `cross-host-parity-identical-input-publishes-identical-bytes` in `test/yin/repl/index_test.cljc` asserts identical datom sets, root addresses, and manifest addresses across all hosts:
- Root 1 address: `:segment/blake3-fbf65215980851aa6540eed999d772e9f9261012538121d6ee6f47bee6cb2449`
- Root 2 address: `:segment/blake3-e12cccd01d218f304d2520052bc57f3ccb90ff5c3fb2a78d310f81bf774e55ab`
- Bulk manifest address:
  - `:eavt` -> `:segment/blake3-c846b75553785160e29d25894ba57e67cf18493f36093b599525a931799130fd`
  - `:aevt` -> `:segment/blake3-52561f3fe45cdc05c97f819e689bc440747f0621549195c54b88155f89558409`
  - `:avet` -> `:segment/blake3-4459c7538f6a3e2d7097af4360f571fd9405bd59b49b8fb661e70afc352f9ebd`
  - `:vaet` -> `:segment/blake3-d9d15e5b074805fe4acb4da8720b71f5b24c7907dd89a5fe0cdcb36c69a0101c`

## Execution Matrix & Results

| Host / Runtime | Command | Result | Evidence Log |
|---|---|---|---|
| **JVM** | `clojure -M:test -n yin.repl.index-test -v yin.repl.index-test/cross-host-parity-identical-input-publishes-identical-bytes` | **PASS** (1 test, 21 assertions, 0 failures, 0 errors) | `/tmp/step1-evidence/cross-host/jvm.log` |
| **Dart** | `bb src/dev/cljd_agg.clj --only yin.repl.index-test/cross-host-parity-identical-input-publishes-identical-bytes` | **PASS** (00:00 +1: All tests passed!) | `/tmp/step1-evidence/cross-host/dart.log` |
| **Node.js** | `node target/node-tests.js --test=yin.repl.index-test` (run on `shiva` remote host) | **PASS on parity test** (Ran 30 tests, 650 assertions; exactly 11 failures, all matching the known `shell-retry-after-refused-publication-promotes-the-whole-suffix` defect) | `/tmp/step1-evidence/cross-host/node.log` |

All three runtimes deterministically produce and verify identical bytes and content addresses for identical inputs and publication policies under `dao.jing`.
