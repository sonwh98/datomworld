Completed-GMT: 2026-09-27 22:55:34 GMT
Completed-Local: 2026-09-28 05:55:34 Asia/Ho_Chi_Minh

The implementation carries both retained-call endpoints and the response cell, allocates private keys against occupied and fixed keys, and routes the restored response wait through the carried keys. Ordinary restores retain the fixed call-out keys ([remote.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ucf/remote.cljc:328), [remote.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ucf/remote.cljc:703), [semantic.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/semantic.cljc:106)).

The required adversarial pins are incomplete:

- The lower refusal test rejects only the **response** attachment. It does not assert that a failed request attachment returns `:yin.k/unsatisfied` naming the request identity ([remote_test.cljc](/Users/sto/workspace/datomworld/test/yin/vm/ucf/remote_test.cljc:1157)).
- The shared-cell test asserts a common cursor key and one cursor resource, but does not assert that co-waiters advance in order ([remote_test.cljc](/Users/sto/workspace/datomworld/test/yin/vm/ucf/remote_test.cljc:753)).

The added remote files have no lines over 80 columns; the long lines found in the semantic files are outside this diff. I did not rerun suites or edit files.

Verdict: REQUEST CHANGES
Sign-off: DENIED