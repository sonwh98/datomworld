Completed-GMT: 2026-10-06 14:42:01 GMT (round 1: 13:30:14 GMT; round 2: 14:03:25 GMT)
Completed-Local: 2026-10-06 21:42:01 +07 (round 1: 20:30:14 +07; round 2: 21:03:25 +07)
Coding-Agent: glm (glm-5.3)

# D12 — the recorded reader and replay: findings

D12 is implemented and green on all three hosts, including the round-2
sign-off fixes (starvation, cursor-failure outcomes) and the round-3
refinement (the scan advances across distinct source groups), all
appended below. Two new files, nothing else changed: **`engine.cljc`
needed no addition** — every public apply the reader owes already
exists from D4 to D6 (`apply-mint`, `apply-observation`, `apply-next`,
`apply-ffi-read`, `apply-link-cursor`, `apply-link-read`), and the
reader adds no new transition, so the "engine.cljc only if a public
apply needs a small addition" branch was not taken. No git writes were
made.

## Changed files

- `src/cljc/yin/vm/ucf/holder/reader.cljc` (new) — the reader.
- `test/yin/vm/ucf/holder/reader_test.cljc` (new) — its tests.

## The API, as the contract pins it

Four entry points over a task under custody, mirroring the writer's
`emit`/`discharge`/`drain` seam:

- **`(reader/step machine append-input! observe!)`** — the live half.
  `append-input!` is the composition-supplied appender of the holder's
  inbound stream (the writer's `append-request!` seam, for
  `:yin.k/input` requests). `observe!` is the composition-supplied
  handle observer, `(fn [handle op arg] outcome)` with `op` `:next`
  (arg the cursor) or `:cursor` (arg the origin anchor), called over
  the machine's own resource handles — no transport vocabulary in the
  namespace. One observation is in flight at a time: the scan's
  candidate — the first, or (round 2) the one after the last applied
  observation's recorded source — from the candidate order (task path,
  then wait-set order; unminted cells by creation `:seq` before reads
  on them) is observed once, held under `:yin.k/held` on its entry —
  or on its cell for a mint, beside `:yin.k/unminted` — and its
  request sent; a held observation in states 1 or 2 is resent as the
  identical request and nothing new is observed. Below the frontier
  the live step observes nothing and answers `::replay-pending`.
- **`(reader/settle machine author record)`** — one acknowledgment
  record, matched as the writer matches (authentication by
  `front/reply-evidence`, then the `:yin.k/input` kind, the request id
  `[:yin.k/input lease k]`, and the answer's input sequence). Only
  `:recorded` or `:replayed` applies — through the engine's public
  applies — after which the held state is gone (state 4) and
  `:input :next` has advanced. `:input-conflict` and `:stale` gate the
  task tree `:ended`; the input conflict quarantines nothing and is
  never an `:intent-conflict`. A late acknowledgment under a closed
  gate retains in state 3 (`:acknowledged`).
- **`(reader/drain machine read-ack!)`** — `settle` folded over the
  composition's acknowledgment reader, the writer's `drain` exactly.
- **`(reader/replay machine)` / `(replay machine {:control-outstanding? bool})`**
  — the pure prefix fold: while k is below the frontier nothing is
  observed live (the function takes no observer at all); record k goes
  to the first candidate whose source matches by `cbor/content=`, so
  the first applied read advances a shared cell before the next
  entry's source is computed, and a `:cursor` record's position is
  applied with `apply-mint` / `apply-link-cursor` and never minted. A
  record matching nothing stops at `:unmatched k` unless the machine
  is quiet — every ready queue empty, no waiting `:op-id` (a fenced
  write awaiting its outcome), no `:yin.k/held` — and no control
  request is outstanding (the one divergence fact the machine cannot
  see, supplied by the driver through `opts`); then, and only then,
  `{:cause :divergence ...}` ends the run.

The four retained states are data on the entry or cell:
`{:yin.k/input-seq k :yin.k/source s :yin.k/observed v :yin.k/state}`
with state `:observed` (1 — the request never left, a `full` inbound),
`:requested` (2 — acceptance unknown, `transport-error` included),
`:acknowledged` (3), and applied (4 — the map is gone and the sequence
advanced). `candidates` and `input-request` are public for D13; the
source grammar is exactly r3 1.4 as amended: read sources carry
`{:yin.k/op :next|:poll, :yin.k/task, :yin.k/stream, :yin.k/position}`,
`:cursor` sources carry the requested origin (`:dao.stream/oldest` for
cells, `:dao.stream/newest` for link cursors) and never a position,
`:ffi-result` carries the call id and `:link-result` the link id — all
inside `:yin.k/name` of `{:yin.k/kind ... :yin.k/name ...}`, with no
cell id, host cursor or sealed reference in any source or observed
value.

Two obligations from the rulings beyond r3 1.4 are carried: the driver
mints every unminted cell in `:seq` order before reads on them, and a
read on a stream with a close pending defers to the step after the
driver's writer resolves it (the D5 close ruling's D12 half). A custody
prefix with no exact `:yin.k/frontier` — absent, status-bearing, or
shorter than its own frontier — fails closed as
`{:yin.k/unsatisfied :yin.k/reason :no-prefix}` in both halves, never
as an empty prefix (missing evidence is never frontier zero).

## Exact test and check outcomes

- **D12 suite, JVM, final code**: `clojure -M:test -n
  yin.vm.ucf.holder.reader-test -e :slow` — **17 tests, 149
  assertions, 0 failures, 0 errors.**
- **JVM full fast lane** (`clojure -M:test -e :slow`): **3436 tests,
  233599 assertions, 0 failures, 0 errors**, with
  `Testing yin.vm.ucf.holder.reader-test` in the stream. (An earlier
  full-lane run at 3435/233585 also green predates the last two test
  rows; the numbers above are the final code's.)
- **Node lane** (shadow `test` build, Java 21, runs the tests):
  **3287 tests, 98243 assertions, 0 failures, 0 errors**, with
  `Testing yin.vm.ucf.holder.reader-test` in the run.
- **Dart lane** (`bb src/dev/cljd_agg.clj`, the yin.repl peer built,
  flutter test over the aggregated shards): **All tests passed!
  +3242**, with the reader's test names visible in the passing stream
  (a first Dart run had failed compiling while the source was
  mid-edit; the re-run on final code is the one reported, and both
  namespaces compile standalone on Dart).
- **kondo**: 0 errors, 0 warnings over both files. Directly invoked it
  is sandbox-blocked in this worktree; run through a spawned
  `/opt/homebrew/bin/clojure -M:kondo --lint ...` (ProcessBuilder), as
  the constraint anticipated.
- **cljstyle**: `check` exits 0 over both files (same spawn route;
  `fix` was applied once mid-work for indentation and protocol-method
  spacing).
- **Baseline before any edit**: the sibling `writer-test` on this
  branch was run first — 25 tests, 202 assertions, 0 failures, 0
  errors — so the lanes below show no regression from D11.

## Red and green evidence

- **Red (test-first)**: the test file was written and run before the
  namespace existed —
  `Execution error (FileNotFoundException) ... Could not locate
  yin/vm/ucf/holder/reader__init.class ... on classpath.`
- **Red (first implementation)**: `Ran 15 tests containing 74
  assertions. 3 failures, 7 errors.` — the observer resolved the read
  handle through a mis-keyed `cell-of` result (nil handle), plus two
  fixture defects (a sealed stream reference where the resource id
  belongs; a ring whose oldest never moves without eviction).
- **Red (second)**: `Ran 15 tests containing 136 assertions. 12
  failures, 0 errors.` — the real defect: `rewrite-held` reached
  `update-in` with the root's empty assoc path, which associates under
  a nil key instead of transforming the machine, so no held state ever
  advanced to `:requested`; also the reply stream was being re-drained
  from the oldest each round, re-discharging tenure 1's committed
  reply against a later machine's equal id (fixed by keeping one
  cursor reader per reply stream, the writer test's own pattern).
- **Green**: `Ran 15 tests containing 137 assertions. 0 failures, 0
  errors.`, then 16/144 after the link-result row was added and 17/149
  after the close-deferral row and the short-prefix guard — all on the
  final code.

## The nine contract rows, and where each is pinned

1. Nothing applied before its acknowledgment (counted) —
   `nothing-is-applied-before-its-acknowledgment-test`: one
   observation, one request, empty ready queue and unchanged k before
   the ack; the wrong author retains; the ack applies exactly once and
   a second ack of the same k retains `:no-held`.
2. A held poll survives ten failed recording attempts with one
   observation and one k —
   `a-held-poll-survives-ten-failed-recording-attempts-test`
   (transport-error ten times; the exact held map is asserted after
   every round, the observer called once, and the eleventh attempt
   that lands still sends the same request).
3. Unknown recording acceptance resends the identical request —
   `unknown-recording-acceptance-resends-the-identical-request-test`
   (two byte-identical copies land; the ledger's dedup answers both;
   applied once, retained `:no-held` once). The `full` variant keeps
   state 1 in `a-full-append-leaves-the-request-unsent-test`.
4. Two waiters on one cell replay successive positions —
   `two-waiters-on-one-cell-replay-successive-positions-test` (a, then
   b; the shared cell advances twice).
5. Root and child with equal call ids replay to the right task —
   `root-and-child-with-equal-call-ids-replay-to-the-right-task-test`
   (two real FFI machines whose park ids are asserted equal; the task
   path in each record's source is what selects).
6. Divergence is not declared while a fenced write awaits its outcome
   — `divergence-is-not-declared-while-a-fenced-write-awaits-test`
   (a waiting `:op-id` write holds it back, `:control-outstanding?`
   holds it back, and only the quiescent unmatched record ends the
   run).
7. `:input-conflict` ends the run with no quarantine —
   `an-input-conflict-ends-the-run-with-no-quarantine-test` (the real
   authority refuses a different record at k 0 through the real front;
   the occurrence is neither quarantined nor closed; the cause is
   never `:intent-conflict`; a late ack reaches state 3).
8. 14.2.4 row 5, an evicted value —
   `an-evicted-value-recovers-with-records-and-without-test`: with
   durable inputs, replay delivers the old value and the re-executed
   write at the same op id gets `:replayed` (one effect total);
   without usable evidence the reader fails closed (nothing observed,
   nothing recorded, no write exists to emit); a divergent re-read at
   the same id is `:intent-conflict` and commits nothing.
9. Cursor replay without live minting (residual 1) —
   `cursor-replay-applies-the-recorded-position-without-minting-test`
   (zero calls on a counting proxy of the handle; the cell seeded at
   the recorded position while the live oldest has moved; the source
   carries the origin and no position; a follow-on read observes from
   the recorded position) and
   `a-live-mint-is-a-recorded-cursor-observation-test`,
   `a-link-cursor-mint-is-recorded-at-newest-and-replays-test`
   (the link mint is recorded at `:dao.stream/newest` live, and
   replay installs the recorded position with zero calls), plus
   `a-link-result-read-replays-to-its-own-entry-test`.

Beyond the nine: `a-poll-and-a-next-at-one-position-do-not-match-each-other-test`
(the op discriminates records at one position; the next entry waits
first so a position-only match would strand the second record),
`below-the-frontier-the-live-step-observes-nothing-test`,
`a-read-defers-to-a-close-pending-on-its-stream-test`, and
`a-custody-map-without-a-frontier-fails-closed-test`.

## Judgment calls, disclosed

- **`observe!`'s shape.** The acceptance criteria name "the handle
  observer" as composition-supplied; it is `(fn [handle op arg])` over
  the machine's own resource handles, with `op` `:next`/`:cursor`. The
  reader itself never calls a stream function except
  `stream/descriptor` for source identities, as the writer does for
  its targets.
- **The input request id** is `[:yin.k/input lease k]` — the writer's
  tagged-vector discipline: canonical data, correlation only, changed
  by a regrant (the lease), unchanged across recording retries.
- **State 3 is reachable data**: it appears when an authenticated
  `:recorded`/`:replayed` arrives under a closed gate — acknowledged,
  awaiting an application that can no longer come. In a healthy run
  apply is immediate within `settle`, so state 3 persists only on an
  ended run.
- **The row-5 divergent leg is test-authored**: a same-id divergent
  write with a shared input sequence is unreachable by an honest
  driver (the authority answers `:input-conflict` at the recorded k
  before any write exists), so the test hand-applies the gap re-read —
  the writer test's own row-5 fixture discipline — and lets the real
  authority judge the intent. The comment in the test says so.
- **The input-conflict fixture's pre-record** carries its own
  holder-chosen request id (`"r-prev"`): request ids are holder-chosen
  and opaque to the front, and under one lease an earlier reply with
  the same id would legitimately ack a held request by the writer's
  own correlation rules.
- **A blocked read is a recorded observation** (the outcome as data,
  blocked included), faithful to "the observed outcome"; a driver that
  re-observes blocked reads burns k, which is D13's cadence decision,
  not the reader's.

## Unresolved concerns

- `replay` never runs the machine: after a record wakes a
  continuation the fold stops at `:unmatched` when the next record
  needs an entry the continuation has not parked yet. D13 must run
  `vm/run` between replay stops, as its driver loop owns the machine.
- The export-refusal halves of the held-observation rule (D8/D9's
  `:yin.k/non-portable` for a task holding states 1 to 3, children
  included) are the lifter's obligation, already covered by their
  existing refusal paths over `:yin.k/held`-bearing entries; D12 only
  produces the state.
- `settle` ignores the projected-outcome arm the writer has: input
  acknowledgments are replies only in this protocol (the input
  protocol has no projection), so none is missed.
- kondo and cljstyle could not be invoked directly (sandbox); both ran
  through a spawned process and are clean, but a maintainer running
  them under trusted mise should see the same.

## Incomplete work

None for D12's scope. D13 (the candidate driver) is the next slice and
will compose `replay`/`step`/`settle`/`drain` with the writer's
`emit`/`drain` and the machine's own `vm/run`.

---

# Round 2 — the D12 sign-off defects (astra CHANGES, both fixed)

The sign-off returned two defects; both are fixed, test-first, in the
same two files. The findings file itself is in the main tree, outside
this worktree's permission boundary, so the fixes follow the defects
as quoted in the tasking brief.

## Defect 1, starvation: the live scan now advances

`step` observed the first candidate unconditionally. After a
`:dao.stream/blocked` acknowledgment `apply-next` retains the waiter
unchanged — its source is unchanged too, since a blocked read advances
no cell — so the next step selected the same entry again and an empty
first stream starved every later stream, children included.

The fix is a deterministic round-robin that reorders nothing: every
applied observation — live acknowledgment or replayed record, failed
mint included — records its source as `:yin.k/scan` inside the
custody map's `:input` state (machine-only state; the lower installs
`:input {:next 0 :prefix ...}` fresh, so the marker never travels).
The live half then observes from the candidate **after the first
current candidate whose source equals the marker**, wrapping; index 0
when the marker matches nothing (its candidate has left) or is absent.
The wait set's order is never touched, and replay's
first-matching-source selection is unchanged — the marker only says
where the live scan resumes, and replay sets it so that, when the
prefix ends, the first live observation lands where the recorded
run's next one did.

## Defect 2, cursor failures: one disposition, live and replay

`apply-candidate` extracted `:dao.stream/cursor` unconditionally.
Acknowledging a `:closed`, `:refused`, `:invalid-anchor` or
`:transport-error` mint installed a nil cursor — `apply-mint` replaced
the `:yin.k/unminted` marker with a cursor-less cell, `apply-link-cursor`
added a `:cursor nil` the writer's sendability check would have
accepted — and advanced the input state as if minting had succeeded.

The disposition, implemented once in `mint-position` and used by both
halves: a mint observation is applied only when
`stream/valid-outcome? :cursor` accepts it **and** its outcome is
`:dao.stream/ok` (the contract's own table already requires the
position on `:ok`, so a malformed success — `:ok` with no
`:dao.stream/cursor` — is rejected by the same check). A failed mint
is still durably recorded and acknowledged — the observation happened,
k advances, the held state clears, the scan marker advances — but it
installs nothing: the cell keeps its `:yin.k/unminted` marker and the
link entry keeps no `:cursor`, so no invalid cell becomes readable (a
read through an unminted cell is not a candidate) and no link becomes
sendable (the writer emits nothing for a cursorless entry). The
candidate remains a candidate, so the driver's next step observes it
again **as a fresh observation at a new k** — immediately when it is
the only candidate, or on the next round when later candidates
progress first, which is the same round-robin that fixed defect 1 and
keeps a permanently failing mint from starving the rest.

## New tests and new assertions (this round only)

Four new deftests, 68 new assertions (149 before, 217 after; the 17
round-1 rows are unchanged and still pass):

- `an-empty-first-stream-must-not-starve-a-later-one-test` — the pin
  the brief names: an empty first stream in the root and a ready
  second stream in an install child. Live: the blocked read is
  recorded, acknowledged and applied (asserted), one observation is in
  flight (a pre-ack step resends and observes nothing — asserted by
  call count), `:yin.k/scan` equals the blocked read's source
  (asserted), the second step observes the child (call count and
  `:task` of the applied record), the root waiter still waits first
  with its cell unchanged and the child's reader wakes with its value
  (all asserted). Replay: the same two records deliver through
  first-match with **zero** calls on counting proxies of both streams
  (asserted), and leave the scan at the child's source (asserted).
- `a-failed-mint-is-recorded-installs-nothing-and-is-retried-test` —
  one doseq over all four failure outcomes plus the malformed
  success: each is durably recorded (the request's observed equals the
  outcome), acknowledged and applied, the cell is byte-identical to
  its pre-observation unminted self, k advanced, and the next step
  observes the mint again at k=1 with the same source.
- `a-failed-mint-replays-without-installing-test` — replay of a
  recorded `:closed` mint applies the record, advances k, and leaves
  the cell unminted: the same disposition as live.
- `a-failed-link-mint-installs-no-cursor-and-the-link-stays-unsendable-test`
  — a `:closed` link-cursor mint leaves the entry a `:link-request`
  with no `:cursor` key and its envelope verbatim, takes no op id, and
  `writer/emit` over the same machine sends nothing (empty
  `:appended`, no new value on the inbound stream).

## Red and green, this round

- Red (both defects, exact): the four tests were written first and
  failed on the unfixed code — `:yin.k/scan` nil and the second
  observation going to the root again (`:task []` where `['host.mod]`
  was owed), the child never waking, the failed mint's cell reading
  `{:stream-id :stream-0, :cursor nil}` against its unminted marker,
  and the writer actually sending the cursorless link request.
- Green: `Ran 21 tests containing 217 assertions. 0 failures, 0
  errors.` — the full D12 suite on the fixed code.

## Checks and lanes, this round

- kondo: 0 errors, 0 warnings (spawned, as before). cljstyle: one
  `fix` pass (indentation, blank lines between deftests), `check`
  exits 0.
- Focused suites (reader + writer + engine gate), JVM: **83 tests,
  989 assertions, 0 failures, 0 errors.**
- Full JVM fast lane: **3440 tests, 233669 assertions, 0 failures,
  0 errors** (round 1: 3436/233599 — the four new tests among the
  difference; the reader suite itself went from 149 assertions to
  217, the exact +68).
- Node lane re-run on the fixed code: **3291 tests, 98311 assertions,
  0 failures, 0 errors** — exactly round 1's 3287/98243 plus the four
  new tests and 68 new assertions. Dart lane re-run on the fixed code:
  **All tests passed! +3246** (flutter's cumulative shard count;
  round 1's was +3242, the four new tests included).

---

# Round 3 — the r2 sign-off refinement: the scan advances across distinct source groups

Round 2's scan advanced past the FIRST candidate matching the saved
source. With two waiters sharing one cursor — equal sources — an
acknowledged blocked read leaves BOTH candidates with that source, so
live next selected the second alias while replay's first matching
source selects the first: a subsequent successful read wakes different
continuations, and repeated blocked observations of the alias pair
recapture the scan, starving a later distinct source.

The fix is confined to `scan-start`: the candidates' distinct sources
(compared by content, as records are) form the scan's groups in
canonical candidate order, the marker names a group, and the live half
observes from the FIRST candidate of the NEXT distinct group,
wrapping — so an alias group's turn is one observation, and within the
selected group the first candidate in canonical order is always the
one taken, which is exactly the candidate replay's first-matching-
source rule selects for a record of that group's source.  Replay
itself is untouched.  Equal-source mint candidates — two unminted
cells on one stream share one `:cursor` source — form a group like any
other: the first cell by creation `:seq` mints first, a failed first
mint moves the scan to the next distinct group rather than to the
second cell, and the group's next turn retries the first cell.

## New tests and new assertions (this round only)

Three new deftests, 30 new assertions (217 before, 247 after; every
earlier row unchanged and still passing):

- `an-alias-group-does-not-capture-the-scan-test` — two waiters on one
  shared cursor over an empty stream, followed by a ready reader in an
  install child.  The alias group's turn is one acknowledged blocked
  observation; the NEXT observation is the distinct child stream's
  (its reader wakes with its value) while both aliases stay parked,
  unreordered, with the shared cell unadvanced; the rotation then
  returns to the alias group's turn.
- `blocked-then-successful-delivery-selects-the-same-continuation-live-and-replay-test`
  — two waiters on one cursor over a stream that gains a value after
  the blocked observation.  Live (the value appended between the two
  steps) wakes the FIRST alias `:k1` and leaves `:k2` parked with the
  cell advanced; replay of the two records [blocked, ok at the same
  source] wakes the same `:k1` — identical continuation selection,
  which round 2's scan could not promise.
- `equal-source-mint-candidates-form-one-scan-group-test` — two
  unminted cursor cells on one stream plus a ready reader on another:
  live, the first cell by `:seq` mints first, the distinct group's
  reader progresses between the two mints, and the second cell mints
  on the group's next turn (both at the recorded position); with a
  scripted `:closed` mint, the failure is recorded and acknowledged
  (k advances, nothing installed — round 2's disposition), the second
  observation goes to the DISTINCT stream rather than the second
  cell, and the group's next turn retries the FIRST cell, both cells
  still unminted.

## Red and green, this round

- Red: the three tests were written first and failed on round 2's
  scan — the second observation going to the second alias (`:task []`
  where `['host.mod]` was owed, the child never waking), live waking
  `:k2` where replay wakes `:k1`, and the failed-mint leg observing
  the second cell instead of the distinct stream.  (Two of the red
  failures were first masked by fixture defects — the value appended
  before the blocked observation, and both legs sharing one world so
  the second leg's k 0 conflicted with the first leg's records; both
  fixture defects were fixed before the scan change, leaving five
  failures, all the defect's own.)
- Green: `Ran 24 tests containing 247 assertions. 0 failures, 0
  errors.` — the full D12 suite.

## Checks and lanes, this round

- kondo 0/0, cljstyle check exits 0 (both spawned, as before).
- Focused reader suite, JVM: **24 tests, 247 assertions, 0 failures,
  0 errors.**
- Full JVM fast lane: **3443 tests, 233700 assertions, 0 failures,
  0 errors** (round 2: 3440/233669 — the three new tests among the
  difference; the reader suite itself went from 217 assertions to 247,
  the exact +30).
- Node lane re-run on the changed code: **3294 tests, 98341 assertions,
  0 failures, 0 errors** — exactly round 2's 3291/98311 plus the three
  new tests and 30 new assertions. Dart lane re-run on the changed
  code: **All tests passed! +3249** (round 2: +3246, the three new
  tests included; the reader suite appears in the passing stream).
