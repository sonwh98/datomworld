Completed-GMT: 2026-09-09 18:00:15 GMT
Coding-Agent: codex
Session-ID: 01a0868e-e9f2-7242-92e8-58d63e7f9574
Role: Routine Review (Phase 2 implementation) | Model: gpt-6-astra

I’ll use the code-review-pr skill to check Phase 2’s code, ownership tests, deletion accounting, and design edits. I’ll assess the telemetry deletion separately and inspect the flaky-test paths for a possible connection, without rerunning suites or writing files.

The read-side implementation matches the settled contract, including V15’s deliberate acceptance of an evicted suffix. V14 would catch an early store close before the owner closes it. I found no direct connection from the deleted fixture to either flaky test, though the driver test’s global timer replacement makes indirect timing interference plausible.

**Phase 2 is ready to commit. No blocking findings.** The separate telemetry cleanup is also justified.

One nonblocking documentation improvement: [V13a’s comment](/Users/sto/workspace/datomworld/test/dao/space/schema_test.cljc:574) says a real transport cannot produce a values-then-hole gap during a snapshot. That is too broad: concurrent eviction can. Say that **overflowing a quiescent buffer before snapshotting cannot produce the intended gap**. The scripted test itself is correct.

The substantive checks pass:

- **D4/V15:** `current` accepts `:blocked`/`:ended`, rejects `:gap`/`:defect`, and documents prior prefix loss as undetectable. V15 asserts both the accepted status and visibility of both name values. It pins the limit rather than merely describing it.
- **V14:** An early close through the supplied store would increment the counter before the zero-close assertion, failing the test. The owner’s subsequent close and two post-close queries separately pin ownership and self-containment.
- **V13a/V13b:** The scripted readers establish their expected statuses before checking schema’s rejection messages. Both memory-log cases compare the resulting fact relation against interpretation of the same rows.
- **Deletion accounting:** Route-equivalence and descriptor-opening obligations disappear with those routes. Borrowed-source non-closure survives in V14. All three constructor validations and extra-key rejection survive in the named index/query tests; query’s ownership tests cover the surviving opener. No retained property was silently dropped.
- **Design and scope:** The value-source contract, publication ownership, D4 limit, consumer-list update, and D10-reference removal match the implementation. The three disclosed deviations are justified; W12’s regex now requires the relevant diagnostic rather than accepting the obsolete alternative.

**On the cljs flakes:** I found no direct semantic connection. The deleted fixture contributed a v1 multimethod registration and fixture atoms; it scheduled no timers and opened no sockets at namespace load.

An indirect timing mechanism remains plausible: bundle/test-work changes can shift execution and GC timing. The driver test globally replaces `setTimeout`/`clearTimeout`, so an unrelated outstanding callback can contaminate its timer registry; the preceding test’s cleanup comment already recognizes this hazard. The WebSocket harness uses real intervals and wall-clock deadlines. These are plausible ways an existing flake could be exposed, **not evidence that this deletion caused either failure**. Three subsequent greens establish intermittency, not causal independence. Tracking these separately remains appropriate.

**Telemetry deletion:** `78b5262` explicitly intended to remove the wasm test but removed only its require. This deletion completes that cleanup; it does not remove a functioning test from the current lane. Keep its separate commit.

Static review only; no suites rerun or files written.
