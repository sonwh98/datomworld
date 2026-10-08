Coding-Agent: codex
Session-ID: 01a0f1b1-5191-7572-aa2e-a53482288c3c
Model: gpt-6-sol

Completed-GMT: 2026-09-30 09:43:53 GMT
Completed-Local: 2026-09-30 16:43:53 Asia/Ho_Chi_Minh

| Severity | File:line | Invariant and evidence | Recommended correction |
|---|---|---|---|
| High | [fs.cljc](/Users/sto/workspace/datomworld-durable-index/src/cljc/yin/repl/store/fs.cljc:69) | Two Node processes can both read a dead owner’s PID. One can remove the stale file and acquire the lock; the other can then remove the **new owner’s** file and acquire it too. Exclusive ownership is lost. | Make stale takeover conditional on the identity of the file inspected, or use an OS lock primitive. Add a concurrent takeover test. |
| High | [fs.cljc](/Users/sto/workspace/datomworld-durable-index/src/cljc/yin/repl/store/fs.cljc:195) | Node and JVM directory sync failures are swallowed. Publication can report success after HEAD is renamed without confirming that its directory entry is durable. | Propagate sync failures when the host supports directory sync, so the round reports a publication failure. |
| Medium | [index.cljc](/Users/sto/workspace/datomworld-durable-index/src/cljc/yin/repl/index.cljc:332) | Startup walks only the EAVT tree. A corrupt node in another manifest index can pass validation and fail later during a query. | Validate every index root named by the manifest, or narrow the documented startup guarantee and add validation before those roots are used. |
| Medium | [store.cljc](/Users/sto/workspace/datomworld-durable-index/src/cljc/yin/repl/store.cljc:344) | If closing the content handle throws, the following unlock is skipped. A failed close can retain the directory lock in a live process. | Release the lock in `finally`. |

**Passed review:** HEAD publication follows manifest read-back; each host writes a temp before rename; the before-rename test seam has no production caller; absent HEAD opens empty; malformed HEAD and the tested missing or corrupt EAVT content refuse startup without retaining the lock; normal close is idempotent. The in-process registry is necessary for the POSIX lock behavior, but its process-global mutable state should be made an explicit, documented host ownership exception to the architectural invariant.

Slice 3 must land before this branch ships: a restarted session’s first publication currently moves HEAD away from the recovered snapshot, as the brief and implementation report state. The supplied JVM, Node, Dart, lint, and formatting results were treated as verified and were not rerun.

Verdict: REQUEST CHANGES  
Architect Sign-off: WITHHELD
