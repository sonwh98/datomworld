I’ll inspect the harness changes and repo rules, verify file capture with a small failing command, then check the remote runs before attempting a concurrent repro.


===

Separate `File` targets captured both small streams and exit code 7 correctly. I also found `:err :append` still in the Dart harness and the buffered-writer pattern in `nightly.clj`. I’ll verify a merged-stream form for those sites. Shiva has active Node shards, so the repro will use a separate clone.


===

The buffering loss is reproduced on Shiva. The capture check also exposed a second issue: two `File` targets pointing to the same log overwrite each other; the experiment retained only stderr. I’ve changed shared logs to the verified `:err :out` form and kept separate files for shard stdout/stderr. The isolated three-shard repro is starting.


===

The isolated run is using the same Node/Babashka versions and compiled bundle as the live lane. `dao.gui` and `dao.jing` passed with nonempty logs; `dao.stream` is in its Node/JVM websocket tests. The journal fixture uses UUID paths, and the socket fixtures I checked bind ephemeral ports, so there’s no evidence yet for a fixture-collision fix.


===

The requested three-shard sample passed: `dao.stream` ran 528 tests / 4,695 assertions with zero failures in 90 seconds. I’ll now overlap three copies of `dao.stream` in the scratch clone to check shared fixtures and concurrent JVM peer startup.

Testing the nightly capture helper also exposed an existing launch bug: it passed the command vector as one argument. I changed it to `apply`; the helper now captures `outerr` and returns exit 7.


===

The three overlapping stream runs have reached JVM peer startup without a failure so far. If they stay green, I’ll make a bounded PAR=12 replay in the scratch clone to better match the original failure conditions. That replay will cap long-running sibling shards at three minutes.


===

The overlap test reproduced a red: `dao.stream.ws-project-cross-node-test/corpus-jvm-serves-node-decodes-over-transit` failed with “the corpus read never completed”; expected `(some? received)`, actual `nil`. That process exited 1 after 122 seconds; the other two passed.

This identifies the failing protocol read, but doesn’t establish a port or file collision. I’ll rerun it alone for comparison. I’m leaving product code untouched.


===

The failed run completed with 528 tests, 4,660 assertions, and one failure; its stderr file is empty and its stdout retained the assertion. The two concurrent peers passed with 4,695 assertions each.

The failing test learns an ephemeral port and reaches the corpus read. Its current diagnostics don’t distinguish an unsettled request from another non-success outcome. I’ll hand that distinction off for investigation rather than change product code or impose speculative serialization.


===

A is fixed and verified. B reproduced under concurrency; its underlying cause remains a handoff. No commits or product-source edits were made.

1. **Output capture**

   On Shiva, Babashka 1.13.225 executed:

   ```clojure
   (require '[babashka.process :as p] '[clojure.java.io :as io])
   (prn (select-keys (p/shell {:out (io/file "/tmp/node-shard-capture.out") :err (io/file "/tmp/node-shard-capture.err") :continue true} "sh" "-c" "printf stdout-small; printf stderr-small >&2; exit 7") [:exit]))
   (prn {:out (slurp "/tmp/node-shard-capture.out") :err (slurp "/tmp/node-shard-capture.err")})
   ```

   Observed:

   ```text
   {:exit 7}
   {:out "stdout-small", :err "stderr-small"}
   ```

   Two concurrent commands also preserved exact stdout/stderr contents of 131,073/131,075 bytes each, despite exit 9. Files **truncate**, which is appropriate for these per-command logs.

   The old writers reproduced empty files. Two `File` targets sharing one path also lost stdout; verified `:err :out` retained both streams.

2. **Parallel reproduction**

   Used independent clone `~/ci/datomworld-codex-node-shard-b600` on Shiva, at `e3704fd9caced2fcca6e250ff3acb9f14843be86`, with copied compiled artifacts and matching tool versions. Existing runs were untouched.

   `bb repro/run.clj` ran `dao.stream + dao.jing + dao.gui`, PAR=3: all passed.

   `bb repro/overlap.clj` ran three concurrent copies of the original 37-namespace stream selector, with `DATOM_SLOW_TESTS=1` and separate File capture. Results: **rc=1/122s**, rc=0/105s, rc=0/104s.

   [Captured failure](/private/tmp/datomworld-node-shard-b600/repro/overlap-0.log):

   ```text
   FAIL in (corpus-jvm-serves-node-decodes-over-transit)
   (dao/stream/ws_project_cross_node_test.cljs:428:9)
   JVM serves, Node decodes: the corpus read never completed
   expected: (some? received)
     actual: (not (some? nil))

   Ran 528 tests containing 4660 assertions.
   1 failures, 0 errors.
   ```

   `bb repro/solo.clj` subsequently passed alone: **528 tests, 4,695 assertions, rc=0, 83s**.

3. **Changes and handoff**

   - [node_shard.clj](/Users/sto/workspace/datomworld/src/dev/node_shard.clj:87): File capture; shared prerequisite logs merge stderr.
   - [dart_shard.clj](/Users/sto/workspace/datomworld/src/dev/dart_shard.clj:81): File capture and `:err :out`; removed invalid `:err :append`.
   - [nightly.clj](/Users/sto/workspace/datomworld/src/dev/nightly.clj:50): same capture fix; corrected command-vector expansion with `apply`. Its actual helper captured `outerr` and returned 7.

   **B handoff:** the failing test uses an ephemeral listener and reaches the corpus read. Existing diagnostics collapse an unsettled request or another non-success outcome into `nil`; they do not establish a fixture collision or deeper product cause. No speculative serialization or product patch was applied.

4. **Other shards at risk**

   The capture defect affected every caller using these writers. The extent of the protocol failure beyond this test is unverified.

5. **Limits and validation**

   The original empty logs cannot establish whether its failure was this same assertion. No PAR=12 replay or full Dart lane was run. Formatting, file loading, and diff checks passed; lint reported zero errors and three pre-existing warnings.

   [Reproduction runner](/private/tmp/datomworld-node-shard-b600/repro/overlap.clj) · [Evidence archive](/private/tmp/datomworld-node-shard-b600/evidence.tgz)