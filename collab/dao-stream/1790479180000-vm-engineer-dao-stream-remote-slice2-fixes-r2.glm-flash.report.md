The fix (residual P1 — a full-refused attach probe was never re-sent)** — only `/Users/sto/workspace/datomworld/src/cljc/dao/stream/remote.cljc` and `/Users/sto/workspace/datomworld/test/dao/stream/remote_test.cljc` touched; both remain untracked working-tree files, no tracked file modified, no commit/stage/checkout/reset/stash.

- `remote.cljc:233` — `new-link` holds a new `:pending` map (docstring updated, remote.cljc:217-226): the attach descriptor probes the writer refused with full, kept unsent with the reflection each was sent for.
- `remote.cljc:281-300` — `send-request!`: on `:dao.stream/ok` it registers outstanding (registered-sends-only preserved) and clears the id from `:pending`; on a `:dao.stream/full` refusal of a descriptor probe it keeps the request in `:pending` instead of dropping it. Cursor/next/append! full refusals still leave nothing outstanding and nothing pending, exactly as in fix round 1.
- `remote.cljc:458-464` — new `retry-pending!`: one more try of each kept probe per call.
- `remote.cljc:467-481` — `drain!` calls `retry-pending!` once per drain (same cadence as the outstanding-probe ask counting), so after writer recovery the kept probe is re-sent; on acceptance it becomes the outstanding probe and the normal filing path (`absorb!` → `learn!`/`emit!`) confirms it.
- `remote.cljc:659-678` — `refl-close` also forgets this reflection's kept probe, so nothing crosses for a closed reflection (preserving round-1 close semantics).

**Tests** (`/Users/sto/workspace/datomworld/test/dao/stream/remote_test.cljc`)
- New gate test `a-refused-probe-is-kept-and-retried-on-a-later-drain` (:616): attach through a writer refusing 2 sends — the probe is refused and kept; the first drain's one retry is refused too (0 descriptor requests on the wire, no event); a later drain retries once and it crosses (= 1, proving once-per-drain); after one `serve!` the answer is filed the normal way (confirmation event with identity, ok, surface #{:reader}) and the learned surface answers `append!` as no-surface locally with nothing crossing.
- Round-1 test `a-full-refused-send-leaves-nothing-outstanding` (:584): its `full-then-forward-writer` budget is 2 → 4 with a comment (:590-592) because the kept probe legitimately consumes the first three refusals (attach send + one retry on each of the first two drains); all its assertions are unchanged and still prove cursor-refusal recovery. ns docstring extended.
- Bite check: with the fix reverted (via /tmp copies, restored byte-identical), exactly the new test fails (9 assertion failures); the other 20 tests of the namespace pass. With the fix, all 21 pass.

**Lanes (sequential, solo, exact counts)** — note: I measured the true pre-change JVM baseline at 2,254/183,209 by a diagnostic full run on a behaviorally pre-change tree; the brief's 183,208 was one assertion low. My delta is exactly +1 test/+13 assertions on every lane, verified by namespace runs with/without the new test (158 vs 145 assertions).
- JVM `mise exec -- clojure -M:test`: Ran 2,255 tests containing 183,222 assertions. 0 failures, 0 errors. (baseline 2,254/183,209; +1/+13)
- Node `mise exec -- bb test:cljs`: Ran 2,167 tests containing 49,827 assertions. 0 failures, 0 errors. (baseline 2,166/49,814; +1/+13)
- Dart `mise exec -- bb test:cljd`: All tests passed! — 2,129 passed. (baseline 2,128; +1; new test present in the fresh `test/cljd-out/dao/stream/remote-test_test.dart`)

Hygiene: `cljstyle check` clean on both files; `clj-kondo` 0 errors, 0 warnings; pure ASCII; no line over 80 columns; no leftover diagnostics.

Status: COMPLETE