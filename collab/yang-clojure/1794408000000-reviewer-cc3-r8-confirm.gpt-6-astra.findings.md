Completed-GMT: 2026-11-11 14:24:39 GMT
Completed-Local: 2026-11-11 21:24:39 ICT

F1: OBJECTION: **P1 — recovery can fabricate retention failures from unrelated exception data.**

Normal retention failures now reach session state and an additive Warning, including when indexing succeeds. However, [repl.cljc:1620](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:1620) preserves the original exception’s data and overwrites the failure keys **only when actual failures exist**. Recovery then trusts those keys at [repl.cljc:334](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:334) and [347](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:347).

A loader or host function can throw an `ex-info` containing `:yin.repl/call-provenance-failures` and `:yin.repl/call-provenance-round-failures`. With healthy retention, those fabricated entries survive wrapping, become session diagnostics, and generate a false Warning. Host functions execute directly at [semantic.cljc:201](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/vm/semantic.cljc:201); observer recovery preserves their exception data at [observer.cljc:212](/Users/sto/workspace/datomworld-cc3/src/cljc/dao/stream/observer.cljc:212). The conditional overwrite in [link-raise:1697](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:1697) leaves the same opening when session failures are empty.

**Required fix:** make shell-owned recovery snapshots authoritative, including empty snapshots; do not treat arbitrary exception keys as retention evidence. Cover immediate and resumed failures with conflicting exception data, asserting that no fabricated diagnostics appear and genuine diagnostics and completed handles survive exactly once.

F4: CONFIRMED-FIXED **for the requested selective-write regression coverage**. Both relation and record refusal are exercised. Tests assert successful indexing, observable failure, no incomplete handle, preservation of earlier handles, and no additional failure or Warning for the unclaimed literal round. See [index_test.cljc:554](/Users/sto/workspace/datomworld-cc3/test/yin/repl/index_test.cljc:554), [query_test.cljc:422](/Users/sto/workspace/datomworld-cc3/test/yin/repl/query_test.cljc:422), and [clojure_callsite_test.cljc:2201](/Users/sto/workspace/datomworld-cc3/test/yang/clojure_callsite_test.cljc:2201). These tests do not cover the recovery defect above.

F3: CONFIRMED-FIXED — **stage tagging remains outside canonical content**. Stage, admission, and root are supplied by the retention operation; stored diagnostics select plain-data fields. Relation and record payloads remain unchanged. See [repl.cljc:218](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:218), [250](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:250), and [1598](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:1598).

The Warning is appended after existing notices and its round marker is consumed; existing test assertions were not modified. No additional contract break found in that formatting path. [repl.cljc:1406](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:1406).

The earlier completed-handle confirmation stands. Cross-node acceptance remains conditional on Shiva. Static review only; no suites run or files edited.

CONFIRMATION VERDICT: REQUEST CHANGES