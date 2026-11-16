Created-GMT: 2026-11-12 22:10 GMT
Created-Local: 2026-11-13 05:10 +0700
Dispatched-By: master-green orchestrator seat (interactive)
Destination: codex exec resume -m gpt-6.1-terra (thread from r1)
Task-ID: 1794521100000-engineer-python-golden-remint-r2-safepoint

# FOLLOW-UP: the safepoint attribution you flagged — it IS your surface #5, slow-guarded

You reported: "The prescribed Node safepoint invocation skipped `pysp-manifest-address-test`
under its slow guard." The orchestrator has now read the nightly log: the one node
linked-safepoint failure IS that test — `FAIL in (pysp-manifest-address-test)
(linked_safepoint_test.cljc:269)` — pinned `pysp-manifest-golden`
`blake3-b85905d8…` vs actual `blake3-d4f15bfe…` (node, at the nightly tree). Same stale-golden
family as your r1 work; the guard is why r1's fast commands couldn't see it.

## Do

1. Ownership extended to exactly one more file:
   `test/yang/python/antlr/linked_safepoint_test.cljc` — re-pin `pysp-manifest-golden`
   (define site) to the new deterministic address; honest docstring rider in r1 style.
2. Reproduce first, with the slow env (your shiva clone `~/ci/datomworld-remint` is already
   at `ce9c6fdf` with a warm bundle; the branch base is unchanged):
   `DATOM_SLOW_TESTS=1 node target/node-tests.js --test=yang.python.antlr.linked-safepoint-test`
   → expect the single FAIL; take the new value from THIS run's actual, on this tree.
3. Cross-host check BEFORE committing the new pin (family invariant — both hosts must agree):
   - jupiter own scratch clone at the same branch head:
     `clojure -M:test -i :slow -n yang.python.antlr.linked-safepoint-test` → same single FAIL,
     same new address value. If the two hosts' actuals differ, STOP and report — that would
     resurrect the parity question.
4. Then edit, commit, push to the same branch `python-golden-remint`, and re-run BOTH slow
   safepoint commands (rc 0) plus ONE quick sanity on each host that r1's greens didn't
   regress: jupiter `-n yang.python.antlr.linked-prelude-test`, shiva pins command from r1.
5. Append to your findings file (same path, new dated section) with every rc + `Ran` line and
   the new head sha.

Rules unchanged: individual tests only, foreground with timeouts, kill by exact PID, no
merge to master.
