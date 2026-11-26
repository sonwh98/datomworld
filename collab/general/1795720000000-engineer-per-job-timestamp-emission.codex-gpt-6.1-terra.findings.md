# Per-job lane timing findings

Implementation head: `596ee47c` (the findings commit follows it). Branch: `fix/lane-job-timestamps`, based on `9bd665fe`. No production or test files changed. Pin publishing was not changed.

Each completed child appends one JSON object to `runs-ndjson.txt` in the Node/Dart shard output directory or the nightly results directory for JVM slow children. `job-id`, `namespaces`, `vars`, `worker-id`, `start-wall` and `end-wall` (UTC ISO-8601), `start-mono-ns`, `end-mono-ns`, `rc`, `wall-seconds`, and `timing-scope` are common fields. Monotonic values are meaningful only within the same driver process; duration is calculated from those values. Worker identifiers are pool slot IDs. Node's existing runs and schedules and all lane verdict checks remain in place.

Dart records additionally have `emitted-file` and one-based `queue-position`. Flutter exposes only one process to this driver: `worker-id` is `flutter-process`, `timing-scope` is `flutter-process`, and every file receives the same process interval and process rc. These are bounds, not per-file elapsed times, worker assignments, or individual file verdicts, and must not be used as per-file weights. Namespace lists are extracted from generated shard imports.

Example actual Node record:

```json
{"end-mono-ns":549265162079255,"rc":0,"namespaces":["dao.data-test"],"job-id":"dao.data","wall-seconds":47.853460884,"start-mono-ns":549217308618371,"end-wall":"2026-11-26T02:32:28.595320568Z","worker-id":"node-0","start-wall":"2026-11-26T02:31:40.741522945Z","timing-scope":"job","vars":[]}
```

Report with `bb src/dev/lane_timing.clj <path>/runs-ndjson.txt`. It prints start-relative interval endpoints in start order, then descending wall ranking, and labels Flutter process-scope rows.

Verification:

- Synthetic three-job report: rc=0; intervals alpha 0–10, beta 2–5, gamma 4–16; ranking gamma 12, alpha 10, beta 3 seconds. An initial run returned rc=1 because integer durations were passed to float formatting; fixed and rerun successfully.
- Real Node `dao.data-test` via the driver's `run-job!`: rc=0, 12 tests / 72 assertions / 0 failures / 0 errors; record above. The existing precompiled bundle was from `/tmp/node-dart-res` at `4eb48aef`, not a build of the new head; the timing driver itself was loaded from this branch.
- `clj -M:kondo --lint src/dev/node_shard.clj src/dev/dart_shard.clj src/dev/nightly.clj src/dev/lane_timing.clj`: rc=0, 0 errors, 0 warnings.
- `git diff --check`: rc=0.
- First JVM slow invocation: `yang.call.exception-source-test` was deliberately terminated after 297.858 seconds to avoid continuing an unexpectedly large check during the nightly. Child rc=143, no test summary; the driver emitted a record with `job-id` and `namespaces` set to `yang.call.exception-source-test`, `worker-id` `jvm-0`, `timing-scope` `job`, and `wall-seconds` 297.858095482. The outer exploratory bb expression printed the result and exited 0; it did not claim the test passed.
- Real slow JVM `dao.star.driver-test` via `run-slow-job!`: outer rc=0, child rc=0, 1 test / 402 assertions / 0 failures / 0 errors; result `{:item {:ns "dao.star.driver-test"}, :exit 0, :tests 1}`. Its source and dependencies were the same existing checkout at `4eb48aef`; the timing driver was loaded from this branch. No full nightly or Flutter process was run in this scoped check.

```json
{"end-mono-ns":549564515185748,"rc":0,"namespaces":["dao.star.driver-test"],"job-id":"dao.star.driver-test","wall-seconds":38.935511455,"start-mono-ns":549525579674293,"end-wall":"2026-11-26T02:37:27.948417733Z","worker-id":"jvm-0","start-wall":"2026-11-26T02:36:49.012647437Z","timing-scope":"job","vars":[]}
```
