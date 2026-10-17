I’ll read the project’s review rules, trace the shutdown paths, and check whether the new test would catch the reported hang.

The join change applies only to the JVM stdin path; headless mode and its timed signal hook are unchanged. The drain has a 201-tick limit once the shell stops. I’m checking what can delay the queued `(quit)` before that limit starts, and how the test collects the child’s output.

I confirmed a race in the new test helper: waiting for the child to exit does not wait for the output collector. With a controlled delay in that collector, the helper returned `{:exited true, :lines [], :exit-code 0}`; joining the reader then revealed the output. The helper needs to join the reader before checking the printed value.

Completed-GMT: 2026-10-17 11:03:00 GMT
Completed-Local: 2026-10-17 18:03:00 +0700

- **[P2] Output collection races process exit.** [dht_process_test.clj:1195](/Users/sto/workspace/datomworld-c4hang/test/yin/repl/dht_process_test.clj:1195) waits for the child, then snapshots `:lines` without joining `:reader`. A passing child can fail the value assertion. Confirmed with the actual helpers and a delayed collector: the helper returned exit 0 with empty lines; joining the reader revealed the output. Join the collector with a bounded wait before taking the snapshot.

The production change preserves queued work correctly. Headless mode and its timed shutdown hook remain unchanged ([main.cljc:1331](/Users/sto/workspace/datomworld-c4hang/src/cljc/yin/repl/main.cljc:1331)). Once shutdown begins, the drain performs at most 201 ticks ([main.cljc:1228](/Users/sto/workspace/datomworld-c4hang/src/cljc/yin/repl/main.cljc:1228)), covering served endpoints, DHT boards, and custody writes before cleanup and halt.

That is **not a global shutdown deadline**: nonreturning evaluation before the queued `(quit)`, a blocking drain step, or blocking cleanup can keep the poller alive indefinitely. `stop-ticks` bounds completed drain iterations only.

The test includes the exact language switch and comprehension. Its escaped regex asserts the literal printed list. It catches noncompletion within 90 seconds, but does not verify prompt restoration or reproduce the reported linker spin. Production scope is limited to the JVM join change.

Full regression execution was omitted because it writes under `target/`, contrary to this review’s read-only scope. The isolated helper probe ran successfully.

Verdict: REQUEST CHANGES