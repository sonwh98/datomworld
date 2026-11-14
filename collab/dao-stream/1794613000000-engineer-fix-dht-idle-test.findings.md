# DHT idle cadence findings

The failing test reproduced: 1 test, 25 assertions, 1 failure, 0 errors. The composed node had two failed entries in `:window`, retained for a future announcement, while fresh and repair queues and inflight requests were empty. `dao.space.dht/busy?` counted any window entry as active, so `main/moved?` never returned false. The window is a publication ledger, not a work queue; queued and inflight work, unread ledger entries and owed reports remain busy.

## Diff

```diff
diff --git a/src/cljc/dao/space/dht.cljc b/src/cljc/dao/space/dht.cljc
index 8fbd02d2..98e69e10 100644
--- a/src/cljc/dao/space/dht.cljc
+++ b/src/cljc/dao/space/dht.cljc
@@ -1496,8 +1496,8 @@
 (defn busy?
   "True while the node owes its owner an event or holds work: binding, a
    load, unread puts, a first report owed, or an address queued or
-   outstanding.  A publication waiting for its next repair cycle does
-   not make the node busy."
+   outstanding. Settled window entries await a future announcement; like
+   publications waiting for their next repair cycle, they are not work."
   [node]
   (boolean
     (and (nil? (:refusal node))
@@ -1506,7 +1506,6 @@
              (= :dao.stream/ok (:dao.stream/outcome
                                  (stream/next (:ledger node) (:ledger-cursor
                                                                node))))
-             (seq (:order (:window node)))
              (some #(or (not (:reported? %)) (:cancelled? %)) (:pubs node))
              (seq (:fresh node))
              (seq (:repair node))
```

## Verification

- Focused test: `Ran 1 tests containing 25 assertions.
0 failures, 0 errors.`
- `timeout 300 clj -M:test -n yin.repl.dht-test`: `Ran 46 tests containing 473 assertions.
0 failures, 0 errors.`
- `timeout 300 env DATOM_SLOW_TESTS=1 clj -M:test -n yin.repl.dht-test`: `Ran 46 tests containing 473 assertions.
0 failures, 0 errors.`
- `timeout 180 clj -M:test -n dao.host.gate-test`: `Ran 14 tests containing 178 assertions.
0 failures, 0 errors.`
- `timeout 180 clj -M:test -n dao.space.dht-test`: `Ran 46 tests containing 36764 assertions.
0 failures, 0 errors.`
- `clj -M:kondo --lint src/cljc/dao/space/dht.cljc`: `linting took 695ms, errors: 0, warnings: 0`.
- Gate A rg: no output, exit 1 (zero matches).
- `bb build:yin-repl-node` with installed Babashka and Node on PATH: `[:yin-repl] Build completed. (258 files, 257 compiled, 0 warnings, 10.02s)`.
- Broad `clj -M:test -r '^yin\.repl\..*-test$'` timed out after 300 seconds, having reached `yin.repl.index-test`; no final summary. `yin.repl.dht-head-test` produced multiple failures and an error whose cause is uninvestigated. Cross-process and JVM socket tests failed with `java.net.SocketException: Operation not permitted` under this sandbox. The remaining namespaces were not verified. No `test/yin/shell/` directory exists. Full log: `/tmp/repl-verify.log` (ephemeral).

No commit or push was made. Existing untracked dispatch files were left untouched.
