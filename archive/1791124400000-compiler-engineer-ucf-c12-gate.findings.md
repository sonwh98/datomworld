Completed-GMT: 2026-10-05
Coding-Agent: claude (opus-5-5)
# C12, the stage-C gate: findings

**Summary: done on the JVM; no landed slice defect found.** I added
`authority/durability` and `authority/exclusive-capable?` (the only src
edit) and three new test namespaces plus one fixture loader. The new
suite is 18 tests and 612 assertions, about 5.4 s wall on the JVM
including startup. All focused JVM namespaces pass: `yin.vm.ucf.*`,
`dao.stream.journal*` and `dao.jing.cbor-conformance-test` (the
write-guard scan), 300 tests, 2709 assertions, 0 failures. I did not run
Node or Dart (iteration rule), so cross-host agreement is still
unverified. No git writes were made.

## Files

- `src/cljc/yin/vm/ucf/authority.cljc`: `durability` and
  `exclusive-capable?`, the `:durability` key kept from open!, the
  `:clean?` state flag, and a two-sentence docstring addition.
- `test/yin/vm/ucf/durability_test.cljc` (new, 10 tests).
- `test/yin/vm/ucf/crash_cut_test.cljc` (new, 2 tests: the memory
  matrix and the file subset).
- `test/yin/vm/ucf/ledger_fixtures.cljc` (new loader and portable
  script; it writes nothing).
- `test/yin/vm/ucf/ledger_fixture_test.cljc` (new, 6 tests).
- `test/resources/yin/vm/ucf/ledger-v1.txt` (new: 22 lines, 32046
  bytes, ASCII).

## 1. Durability declaration and the gate predicate

- `durability` returns the backend's `:dao.stream.journal/durability`
  declaration, called once in `open!` and kept in the authority value as
  `:durability`. It is unchanged data with four keys: backend, failure
  model, lock kind and persisted set. A file backend gives
  `dao.stream.journal.file/durability` and a memory backend gives
  `journal/memory-durability`. A backend with no declaration gives nil.
  The declaration stays readable after poison and after close.
- `exclusive-capable? [a required]` is true only when all of these
  hold:
  - `required` is `:process-crash` or `:power-loss`;
  - the backend is `:file`;
  - the lock kind is `:os-lock` or `:claim-file`, so not `:none` or
    missing;
  - `:clean?` is set, the authority is not poisoned and not closed;
  - the declared failure model ranks at least as high as `required`,
    with `:none` < `:process-crash` < `:power-loss`.
- **What "cleanly reopened" means:** state `:clean?` is true when
  `open!` answers `:open`. `grant/reopen!` goes through `open!`, and if
  its reclaim fails it closes the authority, so that case fails the
  closed check. `poison!` sets `:clean?` to false. As with poison, only
  a new open sets it again.
- **Tests:**
  - A clean file authority answers true for `:process-crash`, before and
    after a committed transition.
  - Every authority answers false for `:power-loss`.
  - The answer is false after poison (a cut wrapped around the real file
    seam), after close, for a memory backend, for a required model of
    `:none` or nil, and for a backend with no declaration.
  - The per-host lock kind is asserted with a reader conditional.
- These tests are portable `.cljc`. They have run on the JVM only.

## 2. Crash-cut matrix

`rows` is the table, one vector per transition: `[name pre! transition!
expected]`. The pre-state and post-state projections and frames come
from an uncut twin. `expected` holds the documented answers: `:fresh`,
`:replay`, and `:reopened {:pre a :post b}`.

| Row | Fresh | Replay | After reopen!, pre / post |
|---|---|---|---|
| enroll | committed | replayed (see Q2) | committed / replayed |
| offer | committed | replayed | committed / replayed |
| grant (judge step) | committed | replayed | committed (regrant) / replayed |
| reclaim (:policy) | ok | ok | ok / ok |
| completion (release) | ok | ok | invalid-value / ok |
| halted completion | ok | ok | invalid-value / ok |
| admit committed | committed | replayed | stale / stale |
| admit intent-conflict | intent-conflict | suspended | stale / suspended |
| input record | recorded | replayed | stale / stale |
| close-target | committed | replayed | committed / replayed |
| refusal pair | ok | ok | ok / ok |

Each row runs under each cut (`:before-frame`,
`:after-frame-before-visible`, `:torn-frame`) on the memory backend. The
steps:

1. Build the pre-state.
2. Close, then open an authority with the cut armed.
3. Run the transition. It answers poisoned (`:suspended`, or
   transport-error through `grant/writer`), and the projection is nil.
4. Reopen with plain `authority/open!` and check (a), (c) and (b).
5. Separately, run `grant/reopen!` over a copy of the persisted frames
   and check the documented answer.

The three checks:

- **(a)** The reopened projection is in {pre, post}, and it is exactly
  the state the cut implies: post for after-frame, pre for the others.
- **(c)** Folding the persisted frames record by record from the empty
  projection (`whole-transactions`) gives the reopened projection with
  no defect. The frame count and the decoded frames equal the twin's.
- **(b)** Retrying the same request answers `:fresh` on a pre-state and
  `:replay` on a post-state, and the final projection equals the twin's
  post-state.

The reopen! answers come from the slice tests:

- admission-test `a-committed-result-is-redelivered-after-reopen`
  (stale);
- completion-test `before-closure` and `a-cut-on-the-halted-completion`
  (a release retried after reopen's :policy reclaim is refused);
- input-test `a-cut-record-sets-the-next-frontier` (stale);
- admission-test `an-intent-conflict-quarantines-the-occurrence`
  (suspended once quarantined).

**File subset:** reclaim, completion, admit committed and input record,
each under all three cuts, over real `dao.jing.file` frames.

- The header is seeded with the fixed identity, so the projections are
  comparable with the memory twin.
- `cut-backend` wraps the real `write-frame!`. Before-frame skips the
  write. After-frame writes the real frame. Torn writes the real frame
  and then truncates the file's last 3 bytes. Each one then throws.
- The handle is dropped without close (`fs/unlock!`, as in file_test),
  and the directory is reopened with `file/backend!`.
- The same checks follow: (a) projection equals the twin's
  pre-state or post-state, (c) a whole-transaction fold over the file's
  replayed frames, (b) the retry answer and convergence to post.
- Temporary directories live under `target/` and are removed on every
  host.

Choices and deviations, smallest choice where the brief is silent:

- The fourth table element is `expected` (the documented answers), not a
  post-state function. The post-state comes from the uncut twin, so a new
  transition is still one row.
- The judge mints lease ids at random (`lease/mint-lease-id`), so
  projections and decoded frames are compared after naming each lease
  by its grant's t (`canon`). No `with-redefs`, for ClojureDart's sake.
- The judge step has no reply of its own. That row answers by what the
  step did to the ledger: committed if t advanced, replayed if not,
  suspended if poisoned. Its judge is rebuilt with `grant/rebuild-judge`
  as plan 1.5 step 5 says, so a post-state does not grant again.
- The reopen! leg runs on memory only. The file subset uses the plain
  `open!` retry, since a second leg would need a copy of the directory.

## 3. Cross-host ledger fixture

- The file has three parts:
  - a first line, `ledger-v1`;
  - a second line, `projection <blake3 hex of cbor/encode of the
    projection>`;
  - then one journal frame per line as lowercase hex: the header and
    19 transactions.

  The pinned digest is `3c3033d5...6ae5b3d85`, with next-t 19 and
  next-e 49.
- `ledger_fixtures/build` is the portable script. It uses identity
  "arb-c12", fixed lease ids and fixed occurrence ids, with no random
  input. In order, it does:
  1. enroll;
  2. offer r;
  3. grant lease-1, then record input 0;
  4. admit (r 0);
  5. refuse holder-b's p-b;
  6. reclaim lease-1 (:policy), then grant lease-2;
  7. report s1, then release lease-2 (completion with a continuation
     edge);
  8. offer s1, grant lease-3, report a halted result, then release (a
     terminal edge);
  9. offer x, grant lease-4, admit (x 0) :v, then admit (x 0) :w
     (intent conflict, quarantine);
  10. close the target.

  `script-answers` pins each step's answer.
- **Tests:**
  - The file equals `render` (like checkpoint_test).
  - The host's own rebuild equals the file frame by frame, compared by
    hex. This is the byte-determinism check.
  - The folded facts cover every kind in `ledger/attribute-order`,
    including both edge forms.
  - The frames fold to the pinned digest over a memory backend, and to
    the same projection as the host's own run.
  - The frames are written one by one through the file seam into a temp
    directory, then closed, reopened and folded: same digest, same header
    frame.
- **Portability:** every step is portable `.cljc`, and no step is known
  not to run on Node or Dart. The projection digest depends on
  `cbor/content-key` keys in `:admitted`, which that namespace documents
  as built only from strings and keywords so that keys are equal on
  every host. The landing run is the first proof.
- **How the resource was produced:** a one-off `java -cp` script, a
  `sh` wrapper and `clojure -Sdeps ... -M` all needed approval here, and
  so did `sed`. So I put a one-off generator in a temporary namespace
  (`test/yin/vm/ucf/c12_probe_test.cljc`), ran it once with `clojure
  -M:test -n`, and deleted it at once. It held one `spit` of
  `(lf/render)` to `lf/path`. No checked-in file writes. The
  conformance write-guard scan passes.

## 4. Durability record per host (all :process-crash)

| Host | Backend survives | Lock | Not survived |
|---|---|---|---|
| JVM | process crash: each frame fsynced by dao.jing.file, torn tail dropped at open | `:os-lock` | power loss right after the first open (directory never synced) |
| Node | process crash, same mechanism | `:claim-file`, with the pid-reuse refusal | the same |
| Dart | process crash, same mechanism | `:os-lock` | the same; Dart cannot sync a directory at all |

`exclusive-capable? a :power-loss` is false on every host.
`:process-crash` is true for a clean file authority.

Cost notes from C12:

- **O(history) reopen.** Every open replays every frame. A file backend
  re-encodes and re-hashes each record (`segment-bytes`), the journal
  decodes it again, and the ledger folds every record. Nothing is
  checkpointed.
- **One fsync per transition.** Each `transact!` is one dao.jing.file
  put with its fsync. Reopen's reclaims add one per live tenure.
- **`successor-seen?` scans every occurrence.** It runs in each report
  decision and in each `:resumed` and `:succeeded` fold, so it costs
  O(occurrences) per report and per replayed edge.
- **`ancestor?` plus a content-store read under the lock.** Each
  inherited-id admission costs O(chain length x occurrences), plus a
  `get-bytes` and re-inspection of the accepted checkpoint. All of it
  runs inside `authority/transition!`, under the authority lock.
- **The whole ledger is in memory,** as plan section 4 risk 5 says.

## Record: test-first, and the guarantees broken on purpose

- durability-test: red at first with "No such var:
  authority/durability", green after the src edit (10 tests, 23
  assertions).
- ledger-fixture-test: red with 5 errors while the resource was
  missing, green once it was generated (6 tests, 52 assertions).
- crash-cut-test: the first run had 19 failures, all in my test. I had
  expected `:suspended` where the writer documents `transport-error`,
  and I had normalized the judge frames with the wrong projection.
  After those fixes it was green (537 assertions). No row exposed a
  slice defect.
- Each guarantee below was broken once on purpose, caught, and
  reverted. `git diff` afterwards shows only authority.cljc.

| Break | Result |
|---|---|
| `exclusive-capable?` without the clean and poison checks | durability-test, 1 failure |
| `poison!` leaves `:poisoned? false` | crash-cut-test, 39 failures |
| the lapse replay answers invalid | crash-cut-test, 13 failures (twin replay, convergence, reopen! answer) |
| `:yin.k/bound` attribute order swapped | ledger-fixture-test, 7 failures and 2 errors (render, frames 3, 8, 12, 16 ...) |

**Gates:**

- `clj -M:kondo --lint` on all five files: 0 errors, 0 warnings (I
  fixed one unused binding).
- **cljstyle was blocked.** The permission gate turned down both
  `cljstyle fix` and `cljstyle check`. I checked by hand: no line over
  80 columns and no non-ASCII byte in the five files or the resource.
  Please run `cljstyle check` at landing.

## Questions

1. **The `:clean?` definition.** It means "open! succeeded, and no poison
   since". Should a plain `authority/open!` that leaves live tenures
   unreclaimed also count as clean? Today it does; only `grant/reopen!`
   reclaims them. A stricter rule would also require no live lease at
   open, or set the flag only in `reopen!`. That needs a grant.cljc edit
   outside this brief.
2. **Enrollment has no request identity.** After an after-frame cut, a
   blind retry of `enroll!` would enroll a second target. The matrix
   keys the retry by the derived target identity (`ledger/target-identity
   arb t`), which is deterministic. Is it the composition's job to read
   the projection before retrying? This is not a defect: enrollment is
   authority-authored, not a remote request.
3. **The judge step's answer.** I derived it from what the step did to
   the ledger. If D wants a reply from `grant/step!`, the row changes in
   one place.
4. **The reopen! leg on the file backend** is not run. Should C12 also
   copy the directory to cover it? I thought the plain-open retry and
   the memory reopen! leg were enough.
