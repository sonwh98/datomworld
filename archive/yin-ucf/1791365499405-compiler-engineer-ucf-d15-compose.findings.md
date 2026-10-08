Completed-GMT: 2026-10-07 20:26:16 GMT
Completed-Local: 2026-10-08 03:26:16 +07
Coding-Agent: glm (glm-5.3)

# D15 — the composition and the REPL wiring

## Disposition

D15 is implemented over the landed D13/D14/D15a holder namespaces and
the D15a seam: `yin.vm.ucf.compose` composes the authority side (the
authority, the judge, one front per holder, the target and outcome
readers, the diagnostic stream), the holder side (the driver over
composition-supplied seams: the inbound appender, the durable
positional reply and outcome inboxes, the ledger reader, the lease
clock, the progress journal, the protection declarations), and the
exclusivity gate; the REPL wiring lands the two custody steps in
`yin.repl.main/step-all`, the custody-aware `moved?`, and the custody
control step on every tick of all three hosts' bounded shutdown drains.
No landed holder, engine, authority or front namespace was modified.

Worktree `/Users/sto/workspace/datomworld-d10b`, branch
`ucf-d15-compose`, HEAD `2cf99c13` (D15a included). No git writes.

## Files changed

- `src/cljc/yin/vm/ucf/compose.cljc` — NEW. The composition: `open!`
  (the exclusivity gate), `holder`/`source`/`restart`/`hand-off`/
  `enroll`/`abort`, `enroll!`/`target-reader`/`outcome-reader`,
  `control-step`/`program-step`/`step`/`stop`/`owed-control-write?`,
  `status`, `close!`, and the D15a production inbox adapters
  (`inbox-over-journal`, `inbox-over-outcomes`).
- `src/cljc/yin/repl/main.cljc` — the two custody steps inside
  `step-all` (the control plane immediately after `yin.repl.dht/step`,
  before the refusal and admission branches; the program step after
  `driver/repl-step`, before `serve/step`, replaced by the stop latch
  once `:running?` turns false), `custody-owed?` in `moved?`, the new
  `shutdown-tick` (the drain tick all three host loops call: the stop
  latch at drain entry, the custody control step, never a program
  step, and the endpoint's `stop-tick`), the JVM `drain-server!`
  threading state, and the Node and Dart stopping branches threading
  state through their boxes. `boot` passes a `:custody` composition
  through. No new thread, timer, or step owner.
- `src/cljc/yin/repl.cljc` — the `:custody` seam on
  `create-state` (an optional composition value the shell carries as
  data; nil, the default, is exactly today's behaviour).
- `test/yin/vm/store_write_audit_test.clj` — one registry entry,
  outside the named diff and reported as such: the audit flagged
  compose.cljc's three `:store`-keyed config maps (`bare-composition`,
  `driver-config`, `front-of`); they join the allowlist with the same
  reason and precedent as `yin.repl.link/composition`'s entry —
  `:store` names the node's dao.jing content store (the driver's own
  assembly key), never a VM store.
- `test/yin/vm/ucf/compose_test.cljc` — NEW. The composition rows.
- `test/yin/repl/main_test.cljc` — the REPL wiring rows.

## The composition

`open!` answers `{:yin.k/status :open ::composition c ...}` or a data
refusal. The gate: an exclusive composition is opened on the caller's
journal backend and `authority/exclusive-capable?` is read for the
required failure model; incapable (a memory backend, a weaker model)
closes the authority and answers `:yin.k/unsatisfied` with
`:yin.k/reason :exclusive-uncapable`, the durability declaration beside
it — nothing is composed and fork is not offered. Fork is offered only
when the caller selects `:mode :fork` (a backend with fork is an
assembly defect), composes no authority, judge or fronts, and every
composition is labelled with the policy it runs (`:yin.k/exclusive` /
`:yin.k/fork`, in the value and in `status`).

The holder side mints the per-holder media: the inbound ring (capacity
64, overridable per holder with any stream — the bounded medium the
drain rows supply), the reply journal (a `dao.stream.journal` over a
kept frames atom — the complete-retention substrate D15a's assembly
requires and the reply-transport research selected; rings and bare
memory logs are disqualified as reply sources), the lease-fact memory
log, and the progress journal with its backend (kept, so `restart`
reopens the same history). The driver config is the holder's own keys
(bytes/address or machine/arbitration; protection, receiver, attach,
observe, serve; an optional interval override) over the composition's
seams. The D15a positional inbox adapters read the reply journal and
the outcome projection by direct cursor construction — positionally,
non-destructively, journal-identity-stable.

`control-step` runs, in holder order: every holder's
`driver/control-step`, then the judge (a tick at the clock's own
reading, one judge pass under the authority's lock), then every front
(`front/step`, budget 16). **The fronts follow the judge** — a lease
fact a front carries reaches the judge one tick later, by which time
the holder has already read the front's reply. The first cut ran
fronts before the judge and the whole-handoff test caught the race
(the exit went `:stale`: the program half's independent binding check
saw the release's lapse before the control half had read the carriage
reply). The reply-before-lapse observation order is documented on
`control-step` and is the reason the order is judge-then-fronts.
`program-step` runs every holder's `driver/program-step`. `stop`
latches every holder (`driver/stop`) and records the composition-level
latch `restart` reapplies. `owed-control-write?` is the aggregate of
the holders' owed control writes, the fronts that may still hold
unread requests, and the judge's undelivered queue.

The carrier for bodies and code is the composition's `:store` (the
node's existing dao.jing byte store); `compose` creates no loader and
no DHT step owner.

## The REPL wiring

`step-all`: after `repl.dht/step` and before the `refusal` /
`admitting?` branches, the custody control step — so renewals,
pending releases and request retries continue during hydration and
past a refused store. In the admitted branch, after `driver/repl-step`
and before `serve/step`, the custody program step — guarded on
`:running?`; when the shell stopped during the tick the stop latch is
set in its place, and the refusal branch latches too. `moved?` adds
`custody-owed?`. `shutdown-tick [state server now]` latches (idempotently,
at drain entry, on every host — including paths that reached the drain
without another normal `step-all`), steps the custody control plane,
and drains the endpoint via the unchanged `stop-tick`. The JVM loop's
`drain-server!` threads state; the Node and Dart stopping branches
thread state through their boxes. No custody program call exists on
any drain path.

## The rows (D15's eight, as landed)

| Row | Evidence |
|---|---|
| Exclusive on a memory authority is refused, not forked | `exclusive-on-a-memory-authority-is-refused-not-forked-test` (compose-test): `:yin.k/unsatisfied`, `:exclusive-uncapable`, the durability declaration (`:memory`/`:none`), no `::composition` in the answer, `:yin.k/policy :yin.k/exclusive` (not fork); also refused for `:power-loss`. |
| Fork runs when selected and is labelled fork | `fork-runs-when-selected-and-is-labelled-fork-test`: `:yin.k/fork` on the composition and in `status`, the source lifts to `:lifted` with bytes and address, zero journal brackets, zero requests, no fronts, no outcome reader. |
| A whole exclusive handoff on a file-backed authority, each host | `a-whole-exclusive-handoff-runs-on-a-file-backed-authority-test`: file backend (the one that passes the predicate for `:process-crash`), source→offer→grant→run→input recorded→halt→report→release→closure→`:exited`; the result body in the store, the closure's edge terminal, the occurrence closed and unheld, one report. Plus `a-parked-continuation-hands-off-and-a-candidate-runs-the-successor-test`: `hand-off`→successor exit→`holder` (the second candidate) runs the successor's occurrence on the same authority. |
| Hydrating: a renewal and a pending release still sent | `with-the-dht-store-still-hydrating-...-test` (main-test): a real solo DHT node with its hydration held (the flag mid-hydration carries, set by hand on a real node); the paused holder (control ticks only reached its paused activation) renews, the typed line still waits, then a restarted holder's release leaves — all under `step-all` with `admitting?` false. |
| No yin.repl loaded | The plain composition test requires none (the whole ns); JVM-only, a fresh `java -cp <cp> clojure.main` process loads `yin.vm.ucf.compose-test`, drives a fork composition to `:lifted`, and prints the `yin.repl*` entries of `loaded-libs` — `[]`. |
| Drain ticks the control plane; release delivered; no program | `the-bounded-drain-calls-custody-control-every-tick-...-test`: after `(quit)`, the stopping tick itself sends one release attempt and latches stop; each of 3 more `shutdown-tick`s journals exactly one more attempt (all `:full`); opening the stream delivers the identical request, carried, and the stopped holder ends `:failed` with `:dao.lease/released` — never a fresh candidacy; attaches stay zero (no program step ever ran). |
| Full stream for the whole drain: exit within budget, intent durable, restart sends | `a-release-held-back-for-the-whole-drain-...-test`: the drain exactly as the host loops run it (stop! then shutdown-ticks to stopped? or the budget) ends `stopped?` within budget with every attempt `:full`, the release intent in the journal; `compose/restart` + one control step sends the identical release (`:dao.stream/ok`). JVM-only, `the-jvm-loop-drains-custody-control-and-exits-within-budget-test` drives `poll-loop!` end-to-end over an injected endpoint: it returns (exits once), attempts ≥ 2, all held back. |
| `moved?` true while a control write is owed | `moved?-is-true-while-custody-owes-a-control-plane-write-test`: true with the owed release behind the full stream; once the stream takes it, driving through `step-all` settles the composition and `moved?` is false. |

## Test-first note

The composition implementation was written before its tests (the
tests were written against the landed driver-test world's shape); the
red/green evidence below is therefore not a per-row pre-implementation
red. What is red-verified: the whole-handoff row red on the real
ordering defect (below), and both suites red against HEAD's src
(without `compose`/the wiring the rows cannot even load), green on the
D15 src. The rows were refined against real failures during iteration.

## Validation (exact)

### JVM (fast, per-namespace)

- `clojure -M:test -n yin.vm.ucf.compose-test` — **5 tests, 49
  assertions, 0 failures, 0 errors** (`collab/logs/d15-compose-run5.log`).
- `clojure -M:test -n yin.repl.main-test` — **41 tests, 339 assertions,
  0 failures, 0 errors** (`collab/logs/d15-main-run6.log`), the full
  main-test suite, not just the new rows.
- After the lint fixes (an unused binding removed, formatting), both
  together: `clojure -M:test -n yin.vm.ucf.compose-test -n
  yin.repl.main-test` — **46 tests, 388 assertions, 0 failures, 0
  errors** (`collab/logs/d15-green-final.log`).

### Node

`clj -M:cljs -m shadow.cljs.devtools.cli compile test` (shadow
`:node-test` build, rebuilt over the final code) then
`node target/node-tests.js` — **3606 tests, 103557 assertions, 0
failures, 0 errors** (`collab/logs/d15-node-full2.log`; ~56 slow-guard
bodies print SKIP, six host-inapplicable CBOR conformance cases skip,
as on every Node run).

### Dart

`bb src/dev/cljd_agg.clj --only yin.vm.ucf.compose-test,yin.repl.main-test`
(mise: `mise exec babashka,java@21 -- bb ...`) — **All tests passed!**
(38 tests; `collab/logs/d15-dart-run1.log`, rerun on the final code in
`d15-dart-run2.log`). The JVM-only rows (the subprocess and
`poll-loop!`) are `#?(:cljd nil :clj ...)` by design; the cljd lane
compiles `main.cljc`'s rewritten Dart loop branch.

### Red evidence

- `collab/logs/d15-compose-run1.log` — the first cut (fronts before
  judge): **4 tests, 39 assertions, 6 failures, 1 error** — the exit's
  `:stale` race (the program half's binding check saw the release
  lapse before the control half read the carriage reply) and the
  subprocess `.getInputStream`-on-the-builder defect. Both fixed;
  `d15-compose-run5.log` is the green.
- `collab/logs/d15-red-compose.log`, `collab/logs/d15-red-main.log` —
  both suites against HEAD's src (compose.cljc removed, main.cljc and
  repl.cljc reverted): each fails to load — `Could not locate
  yin/vm/ucf/compose...`. The rows cannot exist without the slice.
- `collab/logs/d15-red-mutation.log` — a one-line mutation (the
  `custody-control-step` call removed from `step-all`, everything else
  in place): **41 tests, 339 assertions, 6 failures, 0 errors** — the
  hydration row (no renewal, no release sent), the `moved?` row (true
  forever, never settling) and the drain row's stopping-tick count all
  detect the wiring's absence. Restored; `d15-main-run6.log` green.

### Full lanes / checks

- Full JVM fast lane (`clojure -M:test -e :slow`, the `bb test:clj`
  body; `bb` reached through mise), first run:
  **3752 tests, 239203 assertions, 1 failure** — the store-write
  audit's allowlist did not yet know compose.cljc's three carrier
  maps (`collab/logs/d15-jvm-full2.log`). After the registry entry
  above: the audit alone — **3 tests, 129 assertions, 0 failures, 0
  errors** (`collab/logs/d15-audit-run.log`) — and the full lane
  rerun: **3752 tests, 239203 assertions, 0 failures, 0 errors**
  (`collab/logs/d15-jvm-full3.log`). As on every fast-lane run,
  `^:slow` tests are excluded, five host-inapplicable CBOR conformance
  cases and the Dart-dialer/JVM-server row (no `build/ws-project-peer`)
  skip.
- Full Node lane: **3606 tests, 103557 assertions, 0 failures, 0
  errors** (`collab/logs/d15-node-full2.log`).
- clj-kondo over the six changed files (compose.cljc, main.cljc,
  repl.cljc, compose_test.cljc, main_test.cljc,
  store_write_audit_test.clj): **0 errors, 0 warnings**
  (`collab/logs/d15-kondo2.log` and the audit file's lint). One
  earlier warning pair (an unused binding, an unused require) was
  fixed before the final runs; kondo and cljstyle were not
  sandbox-blocked in this session.
- cljstyle `check` over the same six files: clean (three files were
  `fix`ed once — indentation only). `git diff --check`: clean.

## Remaining concerns

- One file outside the dispatch's named diff was touched:
  `test/yin/vm/store_write_audit_test.clj` gained one allowlist entry
  (three sites, one reason, the `yin.repl.link/composition` precedent).
  The audit is a registry the gate requires every new `:store`-keyed
  config map to join; the driver's own assembly key could not be
  renamed. Flagging it here for the reviewer; everything else is
  inside the permitted diff.
- The plain-composition "no yin.repl loaded" row is proven on the JVM
  (a fresh process); on Node and Dart the equivalent claim rests on the
  require graph (compose.cljc requires no yin.repl namespace) and the
  lanes' own namespace sets, not on a per-host loaded-libs probe.
- The hydration row holds a real solo node's `::hydrating` open by
  hand rather than fetching a manifest through a peer mesh (the real
  hydration path is dht_test's); the flag is the only hand-set state,
  and the shell around it is the real solo DHT store.
- `compose/holder` is exercised by the successor row; `compose/enroll`,
  `compose/abort` and `compose/target-reader` are composed surface for
  stage E with no D15 row (enrollment brackets are pinned in the
  driver's own suite).
- The Node and Dart host loops' drain plumbing (state threading
  through `:stopping`) is verified by compile on both lanes and by the
  shared `shutdown-tick` rows; the private loop functions themselves
  are driven end-to-end only on the JVM (`poll-loop!`).
- The judge runs one pass per control tick at the composition's clock
  reading; a composition whose clock never advances never lapses for
  silence (correct), and a real host clock renews before half the
  duration (the driver's own rule). The tick log is in-process; a
  grantor restart rebuilds the judge through `grant/rebuild-judge`,
  which stage E composes.

---

# D15 fix round (the gate r1 NOT READY and the architect sign-off r1 CHANGES)

Completed-GMT: 2026-10-08 (this round)
Coding-Agent: glm (glm-5.3)

The gate's findings 1-4 and the architect's items 1-3 name the same
defects; this round fixes all of them.  Note on sources: the two r1
review documents (`collab/1791389000000-reviewer-d15-compose-gate.opus.stdout.log`
and `collab/1791389000000-architect-d15-signoff-astra.gpt-6-astra.findings.md`)
live in the main tree and could not be read from this worktree session
(its filesystem scope is the worktree only); the round was driven from
the dispatch's own restatement of every finding, with the line
references it carries.  If either document names anything beyond that
restatement, it is unaddressed.

## What changed

- `src/cljc/yin/vm/ucf/compose.cljc` — the durable journal substrate
  (`:journals` at open!, `memory-journals` for tests, `file-journals`
  for production; `substrate-journal` opens both the progress and the
  reply journal through it, and the reply journal's backend is kept,
  no longer thrown away), holder recovery at addition (a progress
  journal that already stands is folded by `driver/reopen` -- a
  restarted process composing the same substrate reopens by identity
  and never mints a second occurrence or reply journal), the
  composition stop latch (a holder added after `stop` arrives already
  stopped; `program-step` is a no-op while the composition's latch
  stands), the fork's arbitration refusal (`:arbitration` with
  `:mode :fork` is a refused assembly -- a caller cannot make a
  labelled fork run an exclusive export; an exclusive composition
  always names its own arbitration, never the caller's), the grantor
  reopen protocol (`open-exclusive` opens through `grant/reopen!` and
  rebuilds the judge with `grant/rebuild-judge`, so a reopened
  composition's judge knows the ledger's history and a crashed tenure
  cannot stand outside its ledger forever), and `close!` extended to
  release every journal backend the composition opened, never
  throwing, so the exit path can always run it.
- `src/cljc/yin/repl/main.cljc` — the bounded shutdown drain is now
  custody-complete on every host: `shutdown-enter?` (an endpoint OR
  owed custody writes -- a REPL without --port drains its owed
  releases, where before it got zero custody control ticks) and
  `shutdown-drained?` (the endpoint stopped AND custody owing nothing)
  are the shared, tested conditions all three host loops call;
  `stop-tick` handles an absent endpoint; the Node and Dart owners
  (`run-node!`, `run-dart!`) are public with an injectable exit so
  their real loop bodies run in their own lanes' tests; and
  `close-index-store!` closes the custody composition's journals
  before the process exit on every host's exit path.
- `test/yin/vm/ucf/compose_test.cljc` — the fix-round composition rows
  (below), plus `exclusive-world`/`reopened-world`: the world takes
  `:journals`, and `reopened-world` rebuilds a composition over the
  same stable configuration with nothing of the crashed composition --
  no composition value, no handle, no holder config.
- `test/yin/repl/main_test.cljc` — the drain rows rewritten around the
  real predicates and the real host loops (below).
- `src/cljc/yin/repl.cljc` — unchanged this round (the D15 custody
  seam stands; nothing the gate or the architect named touches it).

## The rows

| Fix | Evidence |
|---|---|
| Durable holder storage (gate 1) | `a-memory-substrate-composition-reopens-its-journals-and-recovers-its-holder-test`: drive to :running, drop the whole composition, rebuild from the same `memory-journals` atom -- the reply journal reopens **by identity** (and a substrate that mints a fresh reply journal is refused with `:inbox-identity`, the D15a assembly's own rule, pinned on the ex-data), the progress journal carries the crashed history whole, the grant reopens as :releasing with the pending release, the occurrence is never re-minted, and the recovered holder sends the release over the rebuilt composition's own front (the crashed tenure ended, a fresh one may follow).  `a-file-substrate-composition-recovers-from-a-read-before-retention-crash-test`: the production `file-journals` shape, with a crash staged exactly between the front's reply append and the holder's retention (one control tick sends the renewal and -- fronts follow the judge in the same tick -- appends its reply; the crash lands before the next tick) -- the reconstructed holder retains that reply **exactly once** and sends the release.  `a-journal-substrate-is-checked-at-open-test` pins the config gate. |
| Shutdown drain (gate 2) | `the-drain-enters-on-an-endpoint-or-owed-custody-...-test` (cross-host): entry on owed custody with **no endpoint**; an **already-stopped** endpoint with owed custody is still not done; a settled composition with no endpoint needs no drain.  `the-bounded-drain-calls-custody-control-every-tick-...-test` re-driven on the real exit condition (`drain-until` over `shutdown-drained?`): per-tick control cadence, the delivered release ending the drain inside the budget, no program work.  `a-release-held-back-for-the-whole-drain-...-test`: the endpoint stops at once, the owed release holds the drain open for the **whole budget** (exactly `stop-ticks + 2` control ticks -- the stopping tick, the budget, the budget's last tick), the intent durable, a restart resending it.  **Each host loop's real exit condition on all three hosts**: `the-jvm-loop-drains-custody-without-an-endpoint-...-test` (real `poll-loop!`, no endpoint, the gated stream opening mid-drain from another thread, and the substrate directories unlocked after the exit); `the-node-loop-...` and `the-dart-loop-...` pairs drive the **real `run-node!`/`run-dart!` tick bodies** synchronously (`pump-while` calls the owner's own tick, so the branch and exit logic run on the test's clock with no synthetic iterate): without an endpoint the owner keeps draining owed custody, the delivered release (not the budget) ends it, and with the stream held the whole budget is spent with one control tick per drain tick.  The architect's "drained-when-no-endpoint" reading was taken as the task directs: the custody-owed entry is what the residual-4 rows require and is implemented; no separate ruling was needed. |
| Journal close (gate 3 / r3 1.12) | `the-exit-path-closes-the-custody-journals-before-the-host-exits-test` (cross-host): `close-index-store!` unlocks both substrate directories.  `close!-releases-the-journal-backends-the-composition-opened-test` (cross-host): compose's own close releases and is idempotent.  The JVM loop row pins the same at the real exit. |
| Composition stop latch (astra 3) | `a-stopped-composition-receives-its-holders-stopped-and-runs-no-program-test`: after `stop`, `program-step` is `(= c ...)` a no-op; a **holder** added after the latch arrives `:stopped?` and five whole ticks later has never validated; a **source** added after the latch arrives stopped and its export never began. |
| Fork's exclusive export (gate 4) | `a-fork-refuses-a-caller-supplied-arbitration-test`: `compose/source` with `:arbitration` on a fork is a refused assembly ("a fork arbitrates nothing"); exclusive compositions overwrite any caller `:arbitration` with their own (in `add-holder`, for holder and source alike). |

## Red evidence (this round's own)

- `shutdown-enter?`/`shutdown-drained?` mutated back to endpoint-only
  semantics: **43 tests, 357 assertions, 11 failures** -- the
  predicates row, the per-tick/delivery row, the whole-budget row and
  the JVM no-endpoint loop row all detect it.  Restored.
- `add-holder`'s stop latch removed (late holders arrive unstopped):
  **11 tests, 87 assertions, 2 failures** -- both late-holder rows
  detect it.  Restored.
- The C1/C2 reconstruction rows were refined against real failures
  during iteration (the wrong completion predicate; the missing
  grantor-reopen protocol, without which the crashed tenure stood
  outside the rebuilt judge's ledger forever and the recovered
  holder's release never landed).

## Validation (exact)

- Focused: `clojure -M:test -n yin.vm.ucf.compose-test -n
  yin.repl.main-test` -- **54 tests, 444 assertions, 0 failures,
  0 errors** (compose-test alone: 11 tests, 87 assertions; main-test
  alone: 43 tests, 357 assertions).
- Full JVM fast lane (`clojure -M:test -e :slow`, the `bb test:clj`
  body): see below.
- Node lane (shadow `:node-test` build over the final code, then the
  runner): see below.
- cljd focused lane (`bb src/dev/cljd_agg.clj --only
  yin.repl.main-test,yin.vm.ucf.compose-test`) -- run for the Dart
  loop rows this round adds: see below.
- clj-kondo and cljstyle over the changed files: **not run -- both are
  permission-blocked in this session's sandbox** (the D15 round's
  session ran them clean; this round's edits follow the same style and
  were reviewed manually).  The gate may want a lint pass from an
  unrestricted session.

## Remaining concerns

- The two r1 review documents could not be read from this worktree
  (session scope); if either names more than the dispatch restated,
  that part is unaddressed.
- The plain `the-drain-predicates` pins and `drain-until`/`drain-for`
  helpers are cross-host conveniences over the real predicates; the
  loops themselves are driven directly per host (JVM `poll-loop!`
  end-to-end with real sleeps, Node/Dart `run-node!`/`run-dart!` tick
  bodies synchronously).  Driving the JVM loop through a full-budget
  drain (5 s of real sleeps) is the one combination not run; the JVM
  loop shares `shutdown-enter?`/`shutdown-drained?`/`shutdown-tick`
  with the Node and Dart loops, whose budget rows do run it.
- `compose/open!` now runs the grantor reopen protocol on every
  exclusive open.  Over a fresh ledger it is a no-op (no live
  tenures); over a standing one it reclaims with :policy lapses before
  handout.  A stage E that wanted a live-tenure-preserving grantor
  restart would need a different entry; nothing in D15's contract
  names one.
