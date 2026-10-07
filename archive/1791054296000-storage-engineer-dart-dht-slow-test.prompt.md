Created-GMT: 2026-10-03 19:04:56 GMT
Created-Local: 2026-10-04 02:04:56 +07 (+0700)
Coding-Agent: claude
Session-ID: 6eacd026-dad7-42cf-bec7-687f3e392d9b

# Task: why does dao.jing.dht-test/unproven-chunks-never-amplify-or-allocate take 72 s on Dart and 3.6 s on the JVM?

Role: Storage & Indexing Engineer (investigation first; fix only if small and clear)

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-04 02:05 +07 | Status: active | Rationale: cross-host performance investigation in dao.jing.dht and the codec; needs ClojureDart and Dart VM knowledge

Work in /Users/sto/workspace/datomworld-dart-dht (branch dart-dht-profile, master a4efc99a; a NEW worktree; node_modules installed, mise trusted). You cannot run git write commands (stash, checkout,
rebase, reset, commit, stage): do not try. You own the CLJD runner for this task: nothing else runs Dart now. Do not touch collab/ in the main tree.

## The finding (measured, 2026-10-03)

Per-test timing of the Dart lane (flutter test --reporter json over the generated test/cljd-out files; full table in the orchestrator's profile, regenerated below) showed
`dao.jing.dht-test/unproven-chunks-never-amplify-or-allocate` (test/dao/jing/dht_test.cljc:1185) at **71.7 s on Dart against 3.6 s on the JVM**, 14% of ALL Dart test execution time and a 20x host gap.
Nothing else in the suite is that lopsided. The test loops n = 1..1200: for each n it builds a chunk message `{::dht/v 1 :op :chunk :q n :dir :request :part 0 :parts 2 :bytes (zeros (max 0 (- n 120)))}`,
`wire/encode`s it, skips it when `(mesh/byte-count bs)` exceeds 1200, otherwise injects it, steps the node (`dht/step`) and checks the replies. That is about 720,000 elements encoded in total.
The sibling test `every-raw-datagram-size-through-the-budget-is-silent-when-unproven` runs a similar 1200-iteration loop and is not an outlier, so the cost is probably in the chunk path
(`zeros`, `wire/encode`, `mesh/byte-count`, or the :bytes vector handling) and not the loop itself.

## What to do

1. Reproduce and profile on Dart ONLY that test. Do not run the full lane. Suggested: `mise exec -- clojure -M:clojuredart:cljd compile dao.jing.dht-test` then
   `mise exec -- flutter test test/cljd-out/dao/jing/dht-test_test.dart --plain-name "unproven-chunks-never-amplify-or-allocate"` (confirm the exact generated path; the repo's own Dart runner
   is `bb src/dev/cljd_agg.clj`, which compiles through the same CLI). Record the wall time. Then split the cost: time `zeros`, `wire/encode`, `mesh/byte-count`, `inject!`, `dht/step` and
   the replies check separately, for example with `Stopwatch` (dart:core) around each call in a temporary copy of the test, or by timing 100 calls of each in a small scratch test namespace.
   Remove any temporary instrumentation before you finish (restore the files exactly).
2. Find the cause. Likely suspects to check, not conclusions: `zeros` building a persistent vector element by element (or `repeat`/`vec` over lazy seqs) on Dart; `wire/encode` CBOR-encoding a
   vector of ints rather than a byte buffer, with per-element boxing; `mesh/byte-count` re-encoding; `(max 0 ...)` vector slicing; base64 conversion in `jing/bytes->base64`; the `:bytes` value
   crossing the stream as a vector of ints through a transit or CBOR codec in the mesh.
3. Report: the cost breakdown per call on Dart vs the JVM, the root cause with file:line, and the smallest fix. If the fix is small, local to dao.jing.dht / the codec / the test helper `zeros`, and
   cannot change any wire format, canonical byte, content address or protocol behavior, IMPLEMENT it and show the new Dart time for that single test. If the fix would change a wire format, a codec contract,
   canonical bytes or touch more than about 40 lines, do NOT implement it: describe it and stop. Never weaken the test's assertions.
4. Run the focused JVM test too (`clojure -M:test -n dao.jing.dht-test`) and, if you changed source, the other dao.jing namespaces that use the touched code. Do not run the full lanes.
5. kondo 0 errors, `cljstyle fix` then `check` via mise on changed files (run directly, not through a piped loop). NEVER run `clojure -A:test -M -e` (it launches the full runner).

Allowed files: test/dao/jing/dht_test.cljc (only if the cost is in a test helper such as `zeros`), the dao.jing.dht / codec source where the cost lives, and temporary scratch files you remove. Ask before anything else.

Write findings to /Users/sto/workspace/datomworld-dart-dht/collab/1791054296000-storage-engineer-dart-dht-slow-test.claude-opus-5-5.findings.md. Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: <exact Session-ID>

Then report the measured breakdown, the cause, what you changed (or the proposal), and the exact Dart times before and after. Do not claim edits or runs that did not occur.
