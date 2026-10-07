The change is broader than the core namespace: it also adds a JVM facade and rewrites the DHT tests. I found the collab files in the parent worktree and am checking the report’s open decisions against the code.
Completed-GMT: 2026-09-30 11:12:00 GMT  
Completed-Local: 2026-09-30 18:12:00 +07

**SIGN-OFF WITHHELD.** The core demonstrates the main S2 behavior, but the legacy API contradicts §10, and the step does not enforce the contract’s work bound.

| Severity | file:line | Issue | Fix |
|---|---|---|---|
| High | [dht.cljc](/Users/sto/workspace/datomworld-dht-s2/src/cljc/dao/jing/dht.cljc:1175) | `IDhtNet`, `lookup`, and `create-content-dht` remain in S2 despite the §10 deletion requirement. | Apply ruling **(b)** below and correct the §10 table and status text. |
| High | [dht.cljc](/Users/sto/workspace/datomworld-dht-s2/src/cljc/dao/jing/dht.cljc:1042) | The advance stage walks every pending write and get, and each can issue multiple sends. It is not bounded by `step`’s `budget`, contrary to §2. | Carry an advance cursor and cap work per call. Test with more pending operations than the budget. |
| Medium | [dht.cljc](/Users/sto/workspace/datomworld-dht-s2/src/cljc/dao/jing/dht.cljc:785) | A matched `need-cookie` reply can supply any 16-byte cookie. The resend outcome is ignored and its query remains pending even if the socket refuses it. | Treat that cookie as untrusted until the full reply; handle a failed resend as a failed send. Add a refusing-writer test. |
| Medium | [dht_test.cljc](/Users/sto/workspace/datomworld-dht-s2/test/dao/jing/dht_test.cljc:411) | The unpublished test proves refusal of an outgoing put, but does not show that an unpublished node still routes, fetches, and caches on a remote miss. Other tests cover fetch and cache without asserting the default publication setting. | Add one explicit fetch-only test covering all three behaviors. |
| Medium | [facade_test.clj](/Users/sto/workspace/datomworld-dht-s2/test/dao/jing/dht/facade_test.clj:13) | Counting two distinct stepping threads for two facades does not detect concurrent steps on either *same* state. | Instrument entry and exit per facade and assert its maximum concurrent step count is one. |

The tests do catch the principal regressions reported: the warm write test checks exactly two distinct sends, their fresh reply cookies, the `/sent` fact, and the same-step answer. Gate tests check wrong or missing cookies, silence for a short datagram, and the padded first ping. Solo, oversize-before-insert, all six unacknowledged reasons, tick-driven expiry, file restart, and exact client completion shapes have direct assertions. The report also records passing JVM, CLJS, and CLJD suites and mutation checks; I did not rerun those suites in this read-only review.

**§10 contradiction — ruling (b):** Keep the legacy protocol and node until S3, then delete them together. S2’s in-memory core does not depend on that surface, while deleting it now would break `node` and `node_test`. Amend the §10 table to make the dependency and deletion slice accurate. Moving the protocol into the node adds migration work to code scheduled for removal.

**Ten open choices**

1. **Approve:** `need-cookie` stores a cookie but does **not** prove or freshen the peer. First contact takes ping → need-cookie → cookie-bearing ping → full reply.
2. **Approve:** an operation made before the first tick starts its deadline at the first observed reading.
3. **Approve for S2:** the literal UTF-8 `epoch|host|port` stand-in; S4 replaces `cookie-for`.
4. **Approve:** freshness uses `now − seen < cookie-epoch-ticks`.
5. **Approve:** send exactly the acknowledgement peers in the reading step, then begin further replication next step; retain the stated deadline.
6. **Approve:** a joining `:jing/put` gets its own local verdict immediately after the existing write is acknowledged.
7. **Approve:** `/local nil` for replicate and pre-insert oversize; `/gap` names the affected stream.
8. **Approve:** tries are per operation and peer, with one outstanding query for that pair and at most `alpha` stale-target pings.
9. **Approve:** the three cursor keys and `:dao.stream/oldest` defaults.
10. **Approve:** the handle returns the local verdict without waiting on the replicate ring append; a closed-ring outcome cannot become a network acknowledgement.
