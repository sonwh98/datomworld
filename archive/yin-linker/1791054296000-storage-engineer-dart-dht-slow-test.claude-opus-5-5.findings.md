Completed-GMT: 2026-10-03 19:10:18 GMT
Completed-Local: 2026-10-04 02:10:18 +07
Coding-Agent: claude
Session-ID: 6eacd026-dad7-42cf-bec7-687f3e392d9b

# Findings: dao.jing.dht-test/unproven-chunks-never-amplify-or-allocate on Dart

## Baseline (measured)
`flutter test test/cljd-out/dao/jing/dht-test_test.dart --plain-name "unproven-chunks-never-amplify-or-allocate"`:
the run finished at 01:07, with about 5 s of loading, so the test body took about 62 s. Wall time was 1:08.8.

## Breakdown (scratch namespace that copied the loop and wrapped each call in a timer; ms summed over 1200 iterations)

| phase                                   | Dart   | JVM   |
|-----------------------------------------|--------|-------|
| `mesh/sent` + `drop before`             | 59,366 | 3,127 |
| `dht/step`                              |    881 |   184 |
| `zeros`                                 |    325 |    70 |
| `inject!` (encode + base64 + append)    |    115 |    22 |
| `wire/encode`                           |    102 |    16 |
| replies check                           |     34 |     4 |
| `mesh/byte-count`                       |      1 |     0 |

Result was `[1200 true 1046]`, and the final log size was 1046 datagrams.

## Cause
`test/dao/jing/dht_test.cljc:1201` (before the fix) ran `(drop before (mesh/sent net))`. `mesh/sent`
(`test/dao/jing/dht/mesh.cljc:223`) runs `mapv` over the WHOLE log and base64-decodes and `dht/decode-message`s
every datagram. 1046 of the 1200 steps add a reply, so the loop decodes about 1046²/2 ≈ 547k datagrams to read
about 1046 new ones. That is quadratic test-harness cost. The chunk path, the codec, `zeros` and base64 are not the cause.
Each decode costs about 108 µs on Dart and about 5.7 µs on the JVM, so the ~19x per-decode gap times the quadratic count gives the 20x outlier.
The sibling test is not affected because it only calls `mesh/log-size`, which is a count.

## Fix (implemented, test-only, no wire/codec/protocol change)
Decode only this step's datagrams:
`replies (mesh/sent (atom {:log (subvec (:log @net) before)}))`
The assertions are unchanged, and every reply is still fully decoded (`:size` is still checked).

## After
The same single Dart test now passes at 00:01 (wall 4.8 s including load), down from about 62 s.

## Verification
- JVM `clojure -M:test -n dao.jing.dht-test`: 43 tests, 254 assertions, 0 failures, 0 errors.
- kondo on dht_test.cljc: 0 errors, 0 warnings. `cljstyle fix` then `check`: clean.
- The scratch namespace and its generated Dart file were removed. The only source diff is dht_test.cljc (+4/-1).

## Note (not done, needs approval: mesh.cljc is outside the allowed files)
Other `(drop before (mesh/sent net))` sites (dht_test.cljc:211, 234, 249, 322, 362) are not inside 1200-step loops,
so they are cheap. A cleaner shared fix would be a `(mesh/sent net from)` arity in the mesh helper.

# Round 2 (2026-10-03 19:15:33 GMT / 2026-10-04 02:15:33 +07)

## Change
- `test/dao/jing/dht/mesh.cljc`: `sent` now has two arities. `([net] (sent net 0))` keeps the old result: every
  logged datagram, decoded, oldest first, as a vector. `([net start] ...)` decodes only `(subvec (:log @net) start)`.
  The parameter is named `start` so it does not shadow the `from` key destructured inside.
- `test/dao/jing/dht_test.cljc:1201`: `replies (mesh/sent net before)`. This replaces the round-1 atom/subvec
  construction and its comment. No assertion changed.

## Dart times (single test, `--plain-name`)
| test | before | after |
|---|---|---|
| dao.jing.dht-test/unproven-chunks-never-amplify-or-allocate | ~62 s in the test (01:07 total, wall 1:08.8) | 00:01 (wall 5.0 s including load) |

The whole `dao.jing.dht-test` Dart file also ran: 43 passed, wall 5.8 s.

## Sweep of the test tree (test/cljd-out excluded) for whole-log decoding per step
`mesh/sent` (and `mesh/sent-from`, which calls it) is used only in `dao.jing.dht-test` (the loop above, plus single
calls outside loops) and once in `yin.repl.dht-test:545` (a single call, not in a loop). No other test decodes the
mesh log. The other per-step readers are `mesh/values`, `mesh/facts` and `mesh/answers`. They read evicting rings
(capacity 4096), so a read is bounded and does not grow with the run.

- **dao.space.dht-test/sustained-rounds-are-each-reported-once-in-order-and-drain (18.7 s on Dart): NOT the pattern.**
  Measured with a temporary copy of its world/step loop, now removed: 315 steps, 32,384 datagrams, 9 events.
  - Dart, 15.6 s total: node `dht/step` 8,179 ms, peer `jing.dht/step` 6,507 ms, `round!` 820 ms, `new-facts` 5 ms,
    `done?` 2 ms, backlog check 10 ms.
  - JVM, 5.1 s total: node step 2,277 ms, peer steps 2,513 ms, `round!` 327 ms.
  - About 94% of the time is real protocol work: 4,000 blobs stored to two peers. The host gap is only about 3x, and
    the test helpers cost nothing measurable. `new-facts` already reads from a cursor. `event` and `done?` scan
    `:events`, which holds 9 entries.
- **dao.space.dht-test/a-peer-that-never-accepts-is-never-acknowledged-and-stays-bounded (5.2 s): NOT the pattern (by
  reading, not measured).** It runs about 3,000 steps of `run-world` at dt 100 up to 300000. Each step's check reads
  `dht/backlog` and `dht/publications`, which are bounded by the limits under test. `:events` holds only
  published/republished reports. The cost scales linearly with the step count.
- **dao.space.dht-test/fresh-and-repair-requests-share-the-bound-fairly (4.8 s): NOT the pattern (by reading).**
  `run-world` uses the same cursor-based `new-facts`, and its done? predicate is an `event` scan over a few reports.
  The cost is real repair and fresh traffic.
- **yin.repl.dht-test/with-peers-that-never-accept-twenty-rounds-report-and-the-ticker-idles (8.1 s): NOT the pattern
  (by reading).** After each step, the inner loop scans the accumulated `:lines` with `line-with` (`str/includes?`).
  That is technically O(steps × lines), but `:lines` holds only DHT report lines (a few per round over 20 rounds), so
  the scan is negligible next to `main/step-all` and the peer steps. Nothing decodes the mesh log.
- **yin.vm.linker dht end-to-end tests (dht_end_to_end_test.cljc, linker/dht_test.cljc): NOT the pattern (by reading).**
  `step-world` and `run-world` accumulate `:events` and `:lines` and scan them only in done? predicates
  (`published`, `event`), and those vectors hold a handful of reports. `last-response` and `gets` read rings once, after the runs.

No other test needed the one-line change, so no other test was re-timed. Making the remaining slow tests faster
would mean making the protocol step itself cheaper on Dart (node + peer `step`), which is out of this task's scope.

## Verification
- JVM: `clojure -M:test -n dao.jing.dht-test -n dao.jing.dht.socket-test -n dao.jing.dht.facade-test
  -n dao.space.dht-test -n yin.vm.linker.dht-test -n yin.vm.linker.dht-end-to-end-test -n yin.vm.linker-test
  -n yin.repl.dht-test` (every namespace that uses mesh.cljc): 206 tests, 38,041 assertions, 0 failures, 0 errors.
- kondo on mesh.cljc and dht_test.cljc: 0 errors, 0 warnings. `cljstyle fix` then `check`: clean.
- Scratch `test/dao/space/zz_profile_test.cljc` and its generated Dart file were removed. `git status` shows only
  mesh.cljc and dht_test.cljc modified (plus the collab files). I ran no git write commands.
