# Engineer report: linker over dao.jing.dht, slice L1 (claude-opus-5-5)

Worktree `/Users/sto/workspace/datomworld-linker-l1`, branch `linker-l1`,
from `a1f41db3`. **Nothing staged or committed.**

Contract: `docs/design/yin.vm.linker.dht.md` §12 L1, plus §4.3, §5.5, §9,
§10 and §11. Owner decisions are in
`collab/1790808000000-orchestrator-linker-over-dht-owner-decisions.md`
(decision 5 is the automatic retry).

## Files touched (exactly the seven L1 files)

| File | Change |
|---|---|
| `src/cljc/dao/space/dht.cljc` | `load`/`forget`/`index-walk`, with reasons as data. The put handle no longer issues a replicate request. Adds the publication ledger, the fresh and repair queues, pacing, automatic repair, `retry!`, `cancel!`, `backlog`/`publications`/`publication` reads, `ack-peers`, and the new `join` bounds. |
| `src/cljc/yin/repl/dht.cljc` | Renders `:published`/`:republished` results and load-failure reasons as text. |
| `src/cljc/yin/repl/query.cljc` | `dao.space.dht` host module: `retry` and `cancel` ops. The host `load-status` shape drops `:value` and adds `:datoms` for index loads. |
| `test/dao/space/dht_test.cljc` | 36 tests (6 existing ones rewritten for data reasons and results, 30 new). |
| `test/yin/repl/dht_test.cljc` | 2 new tests, plus assertions added to 2 existing ones. |
| `test/yin/repl/dht_process_test.clj` | The not-acknowledged line regex now asserts the new result line. |
| `docs/design/dao.jing.dht.md` | §10's event list and the store, `load` and `retry!`/`cancel!` bullets. |

No other file was needed.

## Checks (all run in the foreground, each polled to its verdict)

| Check | Verdict |
|---|---|
| `clj -M:kondo --lint` on the 6 changed source/test files | errors 0, warnings 0 |
| `bb test:clj` | **2615 tests, 210146 assertions, 0 failures, 0 errors**. `yin.repl.dht-process-test` ran (JVM↔Node over UDP). |
| `bb test:cljs` | **2529 tests, 76348 assertions, 0 failures, 0 errors**; build has 0 warnings. `dao.space.dht-test` and `yin.repl.dht-test` both ran. |
| `bb build:yin-repl-peer` | built `build/yin-repl-peer`. It printed 13 DYNAMIC WARNINGs, none in a file this slice touched. |
| `rm -rf test/cljd-out` then `bb test:cljd` | **`+2484: All tests passed`**, with no failure count. |

Caveat on the Dart lane:
- The compact reporter redraws only about once a second, so fast tests never appear by name in the log.
- To confirm coverage, I checked the compiled `test/cljd-out/dao/space/dht-test_test.dart` and `test/cljd-out/yin/repl/dht-test_test.dart`: they contain all 36 and all 17 deftests.
- I could not run `dart test -r expanded` on those two files alone because the sandbox asked for approval, so I have no per-test Dart lines.

## Red before green

- **`dao.space.dht-test` before any implementation:** compile error `No such var: dht/backlog`. The S5 namespace had no L1 API.
- **First green attempt:** 6 failures and 2 errors. Most were test defects, fixed in the tests:
  - a too-strict expectation of `:republished`;
  - a config with `:repair-ticks` above `:repair-max-ticks`;
  - reading publications before the first step;
  - too few failing rows to fill the repair queue;
  - a phase-staggered idle check.
- **One real bug was found:** an overflowing (`:publications-full`) publication kept its own fresh queue entries, so its own row was sent anyway. The failure was `expected :partial, actual :acknowledged`. The fix passes the new publication's id when releasing its queue entries.
- **`yin.repl.dht-test` before the REPL changes:** 22 failures, because the old rendering read `:acknowledged?`.

## Mutations (one or more per core property; all KILLED on the JVM)

Each mutation was applied to `dao.space.dht`, the guarding tests were run, and the original was restored (verified with `cmp`).

| Property | Mutation | Result |
|---|---|---|
| Pacing | `max-pending-writes` × 4 | killed (9 failures) |
| Fairness | repair not bounded by `:repair-slots` | killed (46) |
| Repair admission independent of fresh | repair admitted only when the fresh queue is empty | killed (2 failures, 1 error) |
| Shared address | outcome written to the first holder only | killed (3) |
| Cancel settles every entry | waiting entries left waiting | killed (1) |
| Terminal reasons | `/absent` treated as retryable | killed (3) |
| Time is ticks | a cycle opens per step, not per reading | killed (1) |
| Delay doubling | no doubling | killed (2) |
| `:max-repairing` | no displacement | killed (3157) |
| Overflow | own queue entries kept (the real bug) | killed (2) |
| `:max-backlog` | unbounded fresh queue | killed (8) |
| `:max-open` | unbounded open publications | killed (4) |
| Walk shapes | shape unchecked | killed (1 error) |
| `:busy` client | `:busy` fails the load | killed (2) |
| Miss cause | cause dropped | killed (5) |
| A sent blob is never asked again | offer every blob, and drop the batch's sent check | killed (5) |

The first versions of three mutations were weak: `fresh-bound` still capped pacing, one mutation had unbalanced parens, and a single guard of the sent-blob property is redundant with the other. They were strengthened and re-run, and are recorded above as killed.

## Acceptance bullets → evidence

Test names are in `test/dao/space/dht_test.cljc` (prefix `D/`) and `test/yin/repl/dht_test.cljc` (prefix `R/`). Both are cljc and pass on JVM, Node and Dart.

1. **`load-index` is `load`, reasons as data.**
   - `load-index` = `load` with `:kind :dao.space.dht/index` and `index-walk`.
   - `D/plain-clojure-joins-…` asserts the exact `:loading` status, the `:loaded` keys `[:fetched :kind :status :value]`, and exactly one terminal event.
   - `D/a-manifest-no-peer-holds-fails-its-load` asserts the reason as data: `{::failure :miss :address m :cause ::jing.dht/exhausted}`, on both the status and the event.
   - `R/a-manifest-no-peer-holds-refuses-the-reader` checks the REPL renders it.
   - All the old index-load tests pass (all three lanes).
2. **Walks.**
   - `:missing` fetches: `D/a-walk-answering-missing-fetches-from-a-peer` shows one `:jing/get` and `:fetched 1`.
   - `:complete` loads with no fetch: `D/a-walk-answering-complete-…`.
   - `:invalid` fails with no fetch: `D/a-walk-answering-invalid-…`.
   - A throwing walk gives `:dao.space.dht/walk-threw`; five other shapes give `:dao.space.dht/walk-shape`: `D/a-throwing-walk-and-a-misshapen-walk-fail-with-closed-codes`.
   - The codes are asserted on all three hosts, and no test compares `:text`.
3. **`:busy` and `:unaskable`.**
   - `D/a-busy-client-leaves-the-load-loading-and-asks-again`: a request writer that answers `:full` once leaves one load holding the owed request and answers the other `:busy`. That load stays `:loading` with `:fetching nil`, then completes on a later step with cause `/solo`, never `:unaskable`.
   - `D/a-client-that-cannot-submit-fails-the-load-unaskable`: `:outcome :request-undeliverable`, the same value on all hosts.
   - The writer substitution reaches into `(:client node)`; this is test-only white-box access.
4. **Pacing.** `D/one-round-of-500-puts-…`: 500 blobs are `:acknowledged`. On every step, node outstanding ≤ 64 and the DHT's own `:writes` ≤ 64. Zero `/busy` facts, counted from the node's facts ring every step.
5. **Sustained rounds.** `D/sustained-rounds-…`: 8 × 500 are all admitted (fresh + outstanding = 4000). Each is reported once, in announcement order, all `:acknowledged`. The backlog then empties, `busy?` is false, and there are no `/busy` facts.
6. **The ninth and tenth rounds.** `D/rounds-beyond-the-backlog-bound-…`: the excess is `:backlog-full` in first reports that are not acknowledged and are repairing. Repair then ends each in `:acknowledged`.
   - **Deviation from the literal bullet:** a round can pass through `:republished :partial` first, because §5.5.3 makes every result change an event. The test asserts that the last event is `:acknowledged`, that each change is reported once, and that only `:partial`/`:acknowledged` occur.
7. **The result.**
   - `D/a-refused-row-is-partial-and-a-refused-manifest-unacknowledged`: exactly one chosen row gives `:partial` with only that address; a refused manifest gives `:unacknowledged`.
   - HEAD moving is a REPL fact. `R/a-partial-publication-…` and `R/a-refused-manifest-…` assert HEAD = the manifest.
   - The REPL partial case refuses every non-manifest store, not one chosen row. REPL index blobs go out as `:chunk` frames, which carry no address.
8. **The invariant.** `close-world!` asserts `:sent + |failed| = :blobs`, with `:blobs` constant per manifest, over every event of every world-based D test. The solo test asserts it directly. Load-only tests produce no publication events. REPL tests render events as lines and do not re-check it.
9. **Repair from both originals.**
   - From `:partial`: `D/repair-acknowledges-a-partial-publication-once-healed` gets exactly one `:republished :acknowledged` with `:sent = :blobs`. The manifest is requested once and the sent rows once each.
   - From `:unacknowledged`: `D/repair-takes-an-unacknowledged-publication-through-partial` heals the manifest, then the rows, and gets `[:partial :acknowledged]` and nothing after. Sent blobs are requested exactly once.
10. **A peer that never accepts.** `D/a-peer-that-never-accepts-…` checks, on every step:
    - fresh ≤ `:max-backlog`;
    - repair ≤ `:max-repairing × :repair-batch`;
    - outstanding ≤ 64;
    - repairing ≤ `:max-repairing`;
    - open ≤ `:max-open`;
    - live ≤ `:max-open + :max-repairing`.

    And over the run:
    - every first report is `:unacknowledged` and repairing;
    - no event is ever `:acknowledged`;
    - only `:displaced` `:republished` events occur, one per publication beyond `:max-repairing`;
    - the delay goes 1000 → 2000 → 4000 → 8000 and stays at 8000;
    - `busy?` is false at every step between cycles, and a one-publication case observes idle steps.

    It uses 6 rounds of 10 blobs with small bounds as composition data. "No put ever waits" holds by construction (puts are synchronous) and is not separately asserted.
11. **Retained ledgers.** `D/open-publications-are-bounded-and-the-overflow-reported-at-once`:
    - the overflow is reported in the step that reads it, every blob `:publications-full`, repairing, and nothing open is displaced;
    - open = 3 and live = 4;
    - ledger entries ≤ (max-open + max-repairing) × `ring-capacity`;
    - in the shared-address case, 16 open publications hold 16 ledger entries and exactly one queue-or-outstanding entry.
12. **Fairness.** `D/fresh-and-repair-requests-share-the-bound-fairly`: with the repair queue full (32 of 4 × 8), the new round reaches ≥ 48 fresh outstanding, repair outstanding is ≤ 16 on every step, and the round is reported while repair continues. With no repair queued, the fresh peak is 64.
13. **Repair under sustained fresh writes.** `D/repair-is-admitted-while-fresh-writes-keep-the-queue-full`: every step's top-up overfills the fresh queue (its excess is `:backlog-full`).
    - The early row is admitted in the step its cycle opens, with the fresh queue still ≥ 64 after that step's release.
    - It ends `:republished :acknowledged` while writes continue.
    - The "admitted only after drain" mutation is killed.
14. **Batches.** `D/a-repair-batch-bounds-each-publication-and-all-progress`: at most `:repair-batch` (4) queued per publication on every step. Every failed blob is asked ≥ 3 times over 3 cycles, for each of the 3 publications.
15. **Overflow with a shared outstanding address, then `/sent`.**
    - `D/an-overflow-sharing-an-outstanding-address-takes-its-sent-fact`, `:only-shared`: the first report in that step is `:unacknowledged`, `a` is `:publications-full`, repairing. The `/sent` fact gives `:republished :acknowledged` well before `:repair-ticks`, with a single request for `a`.
    - `:with-own-row`: `[:partial :acknowledged]`.
    - `D/a-shared-failure-changes-no-result-and-produces-no-event` covers a shared failure.
    - That no repair cycle of that publication ever opens is inferred from the single request and its retirement on acknowledgement; it is not asserted directly.
16. **Terminal reasons.** `D/terminal-reasons-end-repair`:
    - `/absent` only: reported once, `:repairing? false`, `:ended :terminal`, never asked again.
    - `/oversize` only: `:ended :terminal`.
    - Terminal plus retryable: one `:republished :partial :ended :terminal` after the repair.
17. **`retry!`.** `D/retry-brings-the-next-cycle-forward`: the cycle runs at the next step with no event of its own. It is refused (`:dao.space.dht/not-repairing`) for an unknown manifest and for an acknowledged one.
18. **`cancel!` in each ledger state.** `D/cancel-settles-every-ledger-state`:
    - The setup holds sent, failed, outstanding and queued entries at once (all four asserted before the call).
    - The next step reports `:published :ended :cancelled`, `:repairing? false`, sent = 10, all others `:cancelled`, with `:was` kept.
    - The queue entries are dropped, and late facts change nothing (one report).
    - The late `/sent` of an outstanding blob is covered in item 19 (`x1`).
19. **Shared addresses.** `D/shared-addresses-issue-one-request-and-survive-a-cancel`:
    - The shared address is queued once.
    - Cancelling the first publication leaves it queued for the second.
    - The second is `:acknowledged`, with the queued `a` and the outstanding-at-cancel `x1` sent.
    - The first reports both as `:cancelled`.
    - There is one request each, and no further events for the first.
20. **`cancel!` before and after the first report.**
    - Before gives `:published`; that is item 18.
    - After gives `:republished`: `D/cancel-after-the-first-report-is-republished`.
    - With no live publication, `cancel!` is refused (`:dao.space.dht/not-live`): item 18, and `R/a-partial-publication-…` through the host module.
21. **`close!`.** `D/close-reports-nothing-and-a-reopened-node-repairs-nothing`: `close!` answers nil and reports nothing. The blobs stay local, and a node reopened on the directory issues zero replicate requests and reports nothing.
22. **Solo and non-publishing.** `D/a-solo-node-…` and `D/a-non-publishing-node-…`: they hold no backlog and report `/solo` and `/unpublished` (`:ended :terminal`) in the step that reads the puts.
23. **Miss causes.** `D/every-miss-cause-reaches-the-failed-reason`:
    - `/solo`.
    - `/deadline`: a silent peer, read 10000 ticks later.
    - `/busy`: the 65th concurrent get.
    - `/exhausted`: item 1.
24. **Bytes that do not hash.** `D/a-peer-serving-bytes-that-do-not-hash-never-loads`: a lying peer store produces no `:loaded`, the load fails `:miss`, and the address is absent from `:local`.
25. **One terminal event; `forget`.** `D/forget-clears-a-terminal-record-and-is-refused-while-loading`: `forget` is refused while loading (`:dao.space.dht/loading`), clears a terminal record, and a new load runs. One terminal event per load is asserted in the walk tests and the plain-path test.
26. **Time is ticks.** `D/repair-is-due-only-as-appended-ticks-advance`: 300 steps at one reading open no cycle; advancing the readings does.

REPL rendering (§5.5.6), which L1 owns:
- `R/a-partial-publication-is-reported-and-repaired-through-the-host-module`: the first line names `PARTIAL`, `N of M blobs not sent`, the first reason, and "retrying while the node is open". `(dao.space.dht/retry m)` answers `:retrying`, and the `republished … acknowledged: sent to 2 peers` line follows. A later retry or cancel answers its refusal code as data.
- `R/a-refused-manifest-…`: `NOT acknowledged`, `1 of M blobs not sent`, retrying. `(dao.space.dht/cancel m)` answers `:cancelled`, and the `republished … not retrying (cancelled)` line follows.
- `R/the-repl-queries-…`: the host `load-status` has `:kind :dao.space.dht/index` and `:datoms n`, with no `:value`.
- The process test asserts `(\d+ blobs) — NOT acknowledged: publication is off…; \d+ of \d+ blobs not sent; not retrying` from real processes.

## Notes for the Architect

- **Public reads added.** `dao.space.dht/backlog`, `publications`, `publication` and `ack-peers` are plain reads that the tests and REPL rendering use; the contract does not name them. `refusal` keeps its S5 meaning: the bind refusal text.
- **Plain-API refusals.** `retry!`, `cancel!` and `forget` throw `ex-info` with `{:dao.space.dht/refused code}`, built by a helper that returns the error. The host module answers that code as an FFI error. The codes are `:not-repairing`, `:not-live` and `:loading`.
- **Outstanding until `/replicated`.** A request counts as outstanding for pacing until its `/replicated` or `/unacknowledged` fact, not its `/sent`. The DHT keeps an acknowledged write in `:writes` while it replicates best-effort, so this is what keeps `/busy` impossible. The ledger outcome is still written at `/sent`.
- **The put handle.** It reuses `dao.jing.dht/store-handle`'s address and oversize checks over a capacity-1 sink ring, so the DHT's own replicate request goes nowhere; `step` issues all requests.
- **Release order.** Release skips a queued address whose earlier request is still in flight (sent, replicating) until that request ends, so the DHT never joins it silently. Each queue otherwise stays FIFO.
- **A facts-ring gap** reports every live publication `:publication-unknown` and clears the queues and in-flight table, as S5 did.
- **Invariants.** P2P is unchanged: no node role, publishing stays a `join` option. `dao.jing` is untouched and passive. No `yin.*` dependency was added to `dao.space.dht`. The host functions only check arguments and call the plain API.

## Fix round 1 (gpt-6-sol sign-off withheld: `collab/1790815000000-architect-linker-L1-signoff.gpt-6-sol.findings.md`)

Still uncommitted. One extra file is touched, as the orchestrator authorized: `docs/design/yin.vm.linker.dht.md`. `src/` is unchanged in this round: every fix is a test assertion or doc text.

### The four findings

1. **MEDIUM: ledger-entry bound on every step.**
   - `check-dead-network` (`test/dao/space/dht_test.cljc:760`) now takes `blob-max` and asserts on every step:
     - the contract form, `:ledger-entries ≤ (:max-open + :max-repairing) × dht/ring-capacity`;
     - a tighter form, `:ledger-entries ≤ (:max-open + :max-repairing) × blob-max`, where `blob-max` is the largest publication the test announces.
   - It runs on every step of the sustained dead-network run (`a-peer-that-never-accepts-…`, `blob-max` 10).
   - It also runs on every step of a new shared-address dead-network run (`open-publications-are-bounded-…`, `:873`, `blob-max` 1). That run announces one address twelve times, 500 ticks apart, then runs 60000 more ticks. On every step it also asserts the shared address holds at most one queue entry or outstanding request (fresh + repair + outstanding ≤ 1). It expects 12 first reports.
   - The single-step check of 16 open publications sharing one queued address is kept beside it.
2. **MEDIUM: no repair cycle opens.** `an-overflow-sharing-…` (`:1039`), `:only-shared` case:
   - `:cycles` is asserted to be 0 at the overflow's first-report step and on every step after, while the publication is live.
   - After the `/sent` fact, it asserts the publication is retired (`publication` answers nil).
3. **LOW: no put waits.** The dead-network test now completes all 6 rounds before any node or peer step, using `round-verdicts!` (`:783`), which puts through the node's store handle and announces. It asserts:
   - the node has never stepped (`:reading` is nil);
   - all 60 puts answered `:inserted`.

   The rest of the test follows, unchanged. With all 6 announced at once, 2 now take the `:max-open` overflow path; every first report is still `:unacknowledged` and repairing, and 3 are displaced.
4. **The design doc.**
   - §9 gains the node-call refusals: `ex-info` data `{:dao.space.dht/refused code}` from the closed set `:not-repairing` (`retry!`, carries `:manifest`), `:not-live` (`cancel!`, carries `:manifest`) and `:loading` (`forget`, carries `:address`). Consumers match the code, never the message, and the host module answers with the same code.
   - §10 gains the shapes of `backlog`, `publications` (including the entry shapes), `publication` and `ack-peers`.
   - The shapes and codes were checked against `dao.space.dht`.

### Mutations for the new assertions (all fail; source restored and checked with `cmp`)

| New assertion | Mutation in `dao.space.dht` | Result |
|---|---|---|
| Ledger bound, sustained run | `announce` keeps the window instead of starting a new one, so each ledger holds every earlier window | 3626 failures; the ledger assertion failed 3001 times |
| Ledger bound, shared-address run | displacement reports the publication but keeps its ledger live | 6 failures; the ledger assertion failed 3 times |
| `:cycles` stays 0 | the first cycle is due at the first report, not `:repair-ticks` after it. No second request for `a` is issued, so the old single-request assertion could not see this. | 4 failures; "no repair cycle of it opens" failed 2 times |
| Puts answer their local verdict before any step | the store handle answers `:queued` instead of the verdict | 1 failure and 1 error; "each put answered its local verdict at once" failed |

**Limit on the ledger bound:** the contract's ring-capacity form allows (3+3) × 4096 entries. No test-sized data can exceed that, so the mutation failures come from the tighter per-test form asserted beside it.

### A Dart compile failure, found and fixed in this round

- **Symptom:** the first `bb test:cljd` after these edits failed to compile `dao/space/dht-test_test.dart:8420` ("A value of type 'void' can't be returned from a function with return type 'Null'"). That broke every Dart test file in the run (`+0 -22`), and the run was stopped.
- **Cause:** the new `no-cycle` check had `is` nested inside `when`/`when-let`. ClojureDart types that function's return as `Null`, but `is` compiles to `void`.
- **Fix:** end the function with an explicit `nil`. This is a ClojureDart portability trap worth keeping in mind for test helpers.
- **After the fix:** the Dart lane is green and all four mutations above were re-run against the final test file, with the same results.

### Lanes (all in the foreground, after the final edit, each run to its verdict)

| Check | Verdict |
|---|---|
| kondo on the 6 changed source/test files | errors 0, warnings 0 |
| `bb test:clj` | 2615 tests, 223430 assertions, 0 failures, 0 errors; the DHT process test ran |
| `bb test:cljs` | 2529 tests, 89627 assertions, 0 failures, 0 errors; build has 0 warnings; both L1 namespaces ran |
| `bb build:yin-repl-peer` | built; 13 DYNAMIC WARNINGs, 0 in files this slice touched |
| `rm -rf test/cljd-out`, then `bb test:cljd` | `+2484: All tests passed`, 0 compile errors; all 53 L1 deftests are present in the compiled Dart files |
