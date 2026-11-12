Completed-GMT: 2026-11-11 11:19:27 GMT
Completed-Local: 2026-11-11 18:19:27 ICT

F7: CONFIRMED-FIXED. Both wrappers allocate a fresh admission run and return the scoped compilation; no shared default identity is restored. All five missed callers are updated. See [min-repro:52](/Users/sto/workspace/datomworld-cc3/test/d2_wedge_min_repro.clj:52), [probe2:144](/Users/sto/workspace/datomworld-cc3/test/d2_wedge_probe2.clj:144), and [retained coordinates:2522](/Users/sto/workspace/datomworld-cc3/test/yang/latte_oracle_test.clj:2522).

The execution logs corroborate the reported outcomes:

- Min-repro reaches **6 assertions, 0 failures/errors**; arm A reproduces the false negative and arm E finds no cycle. The no-cycle finding belongs to **min-repro**, not probe2. [Log:7](/tmp/r6-min-repro.log:7), [log:37](/tmp/r6-min-repro.log:37).
- Probe2 passes the former arity failures: stage B returns successfully, D reaches its intended budget exhaustion, and F completes. [B log:8](/tmp/r6-probe2.log:8), [D log:5](/tmp/r6-probe2-stage-d.log:5), [F log:6](/tmp/r6-probe2-stage-f.log:6).

V2 DECLARATION: COMPLIANT.

- **Settings:** The command explicitly supplies `-Xss64m` through `:f6`; the declaration distinguishes the original default-stack measurement and labels the added option as parity/headroom. [Declaration:85](/Users/sto/workspace/datomworld-cc3/collab/yang-clojure/1794390804000-cc3-bounded-run-declaration-v2.md:85).
- **Measurement:** Recorded v2.1 maxima satisfy the unchanged ceilings: retained **8,924,640 B**, peak **272,026,880 B**, elapsed **772 ms**. The original measurements and failed superseded run remain distinguished. [Declaration:185](/Users/sto/workspace/datomworld-cc3/collab/yang-clojure/1794390804000-cc3-bounded-run-declaration-v2.md:185).
- **Exact pin:** I independently recomputed both SHA-256 digests. The tracked diff matches `05076f51…50887d`; the measurement script matches `345b432d…2b37c8`. Reproduction commands and full digests are recorded. [Declaration:26](/Users/sto/workspace/datomworld-cc3/collab/yang-clojure/1794390804000-cc3-bounded-run-declaration-v2.md:26).

No new defects found in the r6 changes. Earlier confirmations stand. This pass inspected code, records, logs, and hashes; no suites or measurements were rerun, and no files were edited.

CONFIRMATION VERDICT: APPROVE