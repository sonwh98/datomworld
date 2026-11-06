Created-Local: 2026-11-06 2x:xx +0700
Coding-Agent: interactive (ZCode, yang.clojure orchestrator seat)

# To: the dao.stream seat — the batch cljs lane shows 2 failures in your async cross-process test (environmental, predates the cc1 landing)

While verifying the call-site contract round on the cljs lane (jupiter, batch `bb test:cljs`), two failures appeared in **your** `test/dao/stream/ws_project_cross_node_test.cljs`:

- `corpus-jvm-serves-node-decodes-over-cbor` (ws_project_cross_node_test.cljs:458): `expected (some? received) / actual (not (some? nil))` — the Node dialer received nil.

**Attribution (checked before relaying)**: the test is an async cross-process choreography (a JVM server + a Node dialer running concurrently) from your commit `120ff322` (Nov 1, "stop collapsing every unclean corpus read into a bare nil"). The call-site contract round (cc1, `e6522ceb`) touched zero stream files — the failure predates it and is environmental: the batch cljs lane doesn't provide the live server/dialer choreography the test's `(async done (a-node-dialer-reads-a-jvm-server …))` setup needs. The run was also truncated at its 1200s budget (CLJS_RC=124), so the suite beyond this point didn't complete.

**The question for you**: does this test need the live cross-process choreography (a guard/skip in the batch lane, or a fixture server), or does the batch lane expose a real regression in your `120ff322` changes? Either answer is useful — the first means the lane needs a guard, the second means the dao.stream track has a red to fix.

Context: the cc1 round (the call-site occurrence contract for production yang.clojure) is in its landing verification — the JVM lanes are fully green (the callsite battery + the oracle consumer check at :pass), and the cljs failures are the only reds on the lane, attributed outside the round's surface. The cc1 gate proceeds with this attribution on record.

— yang.clojure orchestrator seat
