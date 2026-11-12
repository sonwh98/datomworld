Completed-GMT: 2026-11-11 14:04:06 GMT
Completed-Local: 2026-11-11 21:04:06 ICT

F1: OBJECTION: **P1 — the blanket catch silently loses claimed provenance.** It fixes the reported always-refusing-store case, but catches every retention exception and discards it at [repl.cljc:1510](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:1510).

The indexer runs **before** retention ([repl.cljc:2021](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:2021)). A store can therefore publish the index successfully, then refuse the provenance relation or record write ([repl.cljc:239](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:239)). The new catch produces an ordinary successful value, no provenance handle, and **no Warning**: index notices consult only the indexer’s failure state ([index.cljc:549](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl/index.cljc:549)). Provenance validation refusals are hidden too.

**Required fix:** preserve program execution while retaining an admission-scoped failure diagnostic and making retention-only failures observable. Distinguish failed retention from an unclaimed admission; the indexer’s Warning cannot report failures it never encountered.

F2: CONDITIONAL-ON-SHIVA. The direct no-coupling analysis is sound. The JVM child runs the stream peer, and the served corpus contains fixed values plus a fixed Jing payload—not lowered images or provenance tables. See [child launch:187](/Users/sto/workspace/datomworld-cc3/test/dao/stream/ws_project_cross_node_test.cljs:187), [peer dependencies:33](/Users/sto/workspace/datomworld-cc3/test/dao/stream/ws_project_peer.cljc:33), and [corpus construction:76](/Users/sto/workspace/datomworld-cc3/test/dao/stream/ws_project_corpus.cljc:76).

The ten-file cc3 change does not alter dependency configuration or this corpus. I found no route for derive’s side tables to enter its served payloads. Build/resource effects remain possible; contention is a hypothesis, not an established cause. The measured wrapper cost does not resolve this timeout. The Shiva Node rerun remains required.

F3: CONFIRMED-FIXED **for completed retention and successful paths**. Retention is still attempted for each claimed packet before loading. Successfully completed handles remain in the accumulator, survive evaluator exceptions, and follow the existing link-resume recovery merges. The real loader is outside the new catch. See [repl.cljc:1500](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:1500), [1521](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:1521), [1592](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:1592), and [1960](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:1960). F1 concerns failed retention becoming invisible, not loss of already completed handles.

F4: OBJECTION: **P2 — the standing net misses selective retention failures.** The added index/query cases use stores that reject **every** write, so the indexer independently supplies the Warning. They cannot detect F1. See [index_test.cljc:553](/Users/sto/workspace/datomworld-cc3/test/yin/repl/index_test.cljc:553) and [query_test.cljc:380](/Users/sto/workspace/datomworld-cc3/test/yin/repl/query_test.cljc:380).

Add a targeted regression where indexing succeeds but provenance persistence fails, including the second, record write. Assert that evaluation completes, the failure remains observable, no incomplete handle is advertised, and earlier completed handles survive. Keep the existing durable/recovery tests alongside index/query coverage; merely adding more namespaces would not close this gap.

Static review only; no suites run or files edited.

CONFIRMATION VERDICT: REQUEST CHANGES