Completed-GMT: 2026-10-08 12:54:15 GMT
Completed-Local: 2026-10-08 19:54:15 +07
Coding-Agent: deepseek (deepseek-v4-pro)

# D15 fix round — completed (continue of the interrupted round)

## Disposition

The five fixes the round must complete were already coded into the worktree
by the prior (glm-5.3) session before it died; this session read the code and
tests, confirmed each fix is implemented and pinned, ran the lanes the prior
session left unrun, fixed three cljstyle formatting defects, and did not
modify any landed holder/authority/front/engine namespace. The five fixes are
all present and green, as are the eight D15 contract rows; the only out-of-scope
artifact is the untracked `handoff-v2.txt` fixture (reported below).

Worktree `/Users/sto/workspace/datomworld-d10b`, branch `ucf-d15-compose`,
HEAD `2cf99c13`. No git writes. The permitted-diff files are exactly those in
the worktree's status, plus one untracked fixture outside the permitted diff.

## The five fixes (all complete, each pinned by its own row)

1. **Durable holder storage** — `compose.cljc` supplies reopenable per-holder
   progress and reply journals through a `:journals` substrate
   (`file-journals` for production over one directory; `memory-journals` over
   one kept atom for tests; no substrate mints in-process-only memory
   journals). `restart`/`add-holder` reopen by identity via `driver/reopen`
   over a journal that already stands, so a restarted process never mints the
   fresh reply journal the D15a assembly refuses (`:inbox-identity`). Pinned by
   `a-memory-substrate-composition-reopens-its-journals-and-recovers-its-holder-test`,
   `a-file-substrate-composition-recovers-from-a-read-before-retention-crash-test`
   (the read-before-retention crash, exactly once, pending release sent), and
   `a-journal-substrate-is-checked-at-open-test`.
2. **Shutdown drain independent of the endpoint** — `main.cljc` adds the
   shared predicates `shutdown-enter?` (endpoint OR owed custody write) and
   `shutdown-drained?` (endpoint stopped AND custody owing nothing), the
   `shutdown-tick` all three host loops call, and rewrites the JVM
   `drain-server!` and the Node/Dart owners to enter the drain on custody
   alone and keep stepping while custody owes, within `stop-ticks`. Pinned
   cross-host by `the-drain-enters-on-an-endpoint-or-owed-custody-...-test`,
   `the-bounded-drain-calls-custody-control-every-tick-...-test`,
   `a-release-held-back-for-the-whole-drain-...-test`, and per host by
   `the-jvm-loop-...-test`, `the-node-loop-...-test` (×2) and
   `the-dart-loop-...-test` (×2), each driving the host's real exit condition.
3. **Journal close before exit** — `close-index-store!` calls `compose/close!`
   on the custody composition before the exit on every host's exit path;
   `compose/close!` releases the authority and every journal backend it
   opened (never throwing, idempotent). Pinned by
   `the-exit-path-closes-the-custody-journals-before-the-host-exits-test`,
   `close!-releases-the-journal-backends-the-composition-opened-test`, and the
   JVM loop row.
4. **Composition stop latch** — `add-holder` stops a holder/source added after
   `stop`; `program-step` is a no-op while the composition latch stands.
   Pinned by `a-stopped-composition-receives-its-holders-stopped-and-runs-no-program-test`.
5. **Fork cannot run an exclusive export** — `arbitration-of` refuses
   `:arbitration` with `:mode :fork` and makes an exclusive composition name
   its own arbitration (never the caller's). Pinned by
   `a-fork-refuses-a-caller-supplied-arbitration-test`.

The ordering clarification is accepted as ruled: the landed completion flow
(offer attempt, report, release/closure, retry the offer until admitted) is
the contract; nothing in the five fixes changes it, and the whole-handoff row
still exercises it end to end.

## Files changed (this round's tree state)

- `src/cljc/yin/vm/ucf/compose.cljc` — NEW (the composition; the durable
  journal substrate, reopen-by-identity recovery, close!, the stop latch, the
  fork arbitration refusal). This session only applied cljstyle blank-line
  fixes.
- `src/cljc/yin/repl/main.cljc` — the custody seam, `custody-control-step`/
  `custody-program-step`/`custody-stop`/`custody-owed?`, the three drain
  helpers, and the JVM/Node/Dart loop rewrites.
- `src/cljc/yin/repl.cljc` — the `:custody` seam on `create-state`.
- `test/yin/vm/ucf/compose_test.cljc` — NEW (the D15 rows plus the fix-round
  rows). This session applied one cljstyle indentation fix.
- `test/yin/repl/main_test.cljc` — the hydration and drain rows.
- `test/yin/vm/store_write_audit_test.clj` — one allowlist entry (three
  sites, the `:store`-keyed carrier maps of `compose.cljc`), the disclosed,
  justified registry entry.

## Out-of-scope artifact (reported, not touched)

`test/resources/yin/vm/ucf/handoff-v2.txt` is **untracked** and outside the
permitted diff. It is required by `yin.vm.ucf.handoff-v2-census-test`
(`a-version-2-body-is-the-same-bytes-on-every-host`, landed in D15a commit
`2cf99c13`) which reads it as its golden body. The D15a session already
flagged this file (and four `handoff_v2_*_test.cljc` files) as "unrelated
changes … author not established … not claimed as D15a work"; it was never
committed in any commit (`git log --all -- <file>` is empty). Its content is
correct for the current lift (the census test passes 5/163 with it present),
so this is a D15a/master staging gap — a landed test whose golden fixture is
not tracked — not a D15 defect. It needs the orchestrator's decision
(commit the fixture, or the D15a test will fail on a clean checkout). Not
modified by this session.

## Validation (exact, this session)

- Focused JVM, `clojure -M:test -n yin.vm.ucf.compose-test -n yin.repl.main-test`
  — **54 tests, 444 assertions, 0 failures, 0 errors** (compose-test 11/87;
  main-test 43/357). Re-run after the cljstyle fix; identical.
- `clojure -M:test -n yin.vm.store-write-audit-test` — **3 tests, 129
  assertions, 0 failures, 0 errors**.
- `clojure -M:test -n yin.vm.ucf.handoff-v2-census-test` — **5 tests, 163
  assertions, 0 failures, 0 errors** (confirms the untracked fixture matches
  the current lift).
- Full JVM fast lane, `clojure -M:test -e :slow` — **3760 tests, 239257
  assertions, 0 failures, 0 errors** (`collab/logs/d15fix-jvm-full.log`).
- Node lane, `node target/node-tests.js` over the Java-21 rebuild — **3616
  tests, 103624 assertions, 0 failures, 0 errors**
  (`collab/logs/d15fix-node-full.log`).
- Dart focused lane, `mise exec babashka,java@21 -- bb src/dev/cljd_agg.clj
  --only yin.repl.main-test,yin.vm.ucf.compose-test` — **All tests passed**
  (48 tests; `collab/logs/d15fix-dart-focused.log`).
- clj-kondo (via mise) over the six changed files — **0 errors, 0 warnings**.
- cljstyle (via mise) over the six changed files — **clean** after this
  session fixed three defects (two missing blank lines in `compose.cljc`, one
  `some` indentation in `compose_test.cljc`).
- `git diff --check` — clean.

### Red evidence

This session added no new mutations (the prior session's red evidence stands:
its `shutdown-enter?`/`shutdown-drained?` mutation, 11 failures, and its
`add-holder` stop-latch mutation, 2 failures — both restored). The red for the
whole slice is the prior round's: against HEAD's src (compose.cljc removed,
main.cljc/repl.cljc reverted) both suites fail to load. This session's own
reds are the two genuine lane failures it hit and recovered from:
- The first full-JVM run was stopped by the background time limit (10 min,
  buffered via `tail`); re-run with a 1-hour limit and a log file, it
  completed green.
- The Node compile failed twice (`UnsupportedClassVersionError` — the
  default `clj` runs Java 17, but shadow-cljs's closure compiler needs Java 21
  class files); re-run under `mise exec java@21`, the compile and the 3616-test
  run completed green.

## Unresolved concerns

- The `handoff-v2.txt` untracked fixture (above) is the only thing outside the
  permitted diff; it needs the orchestrator's decision.
- The Dart lane was run focused (`--only` the two changed namespaces), not the
  full `bb test:cljd` aggregate; the full three-lane gate is the
  orchestrator's.
- The JVM full lane and Node lane ran before the (whitespace-only) cljstyle
  fix; the fix is formatting-only, and the focused JVM re-run after it is
  green, so the lane counts are valid for the final tree.
- kondo/cljstyle are reachable only through `mise` (not on the default PATH);
  the prior session had them sandbox-blocked, this session reached them via
  `mise exec`.

## Incomplete work

None within the permitted diff. All five fixes are implemented and pinned, and
the focused, full-JVM, Node and Dart-focused lanes are green.

---

# Round 2 (2026-10-08 evening, glm-5.3 — the opus gate r2 + astra signoff r2 blockers)

Completed-GMT: 2026-10-08 15:40:00 GMT Completed-Local: 2026-10-08 22:40 +07

**Process note first, because it shaped the round.** The orchestrator had TWO
engineer sessions in this one worktree on the same consolidated brief: this
session (glm-5.3, dispatched 20:17) and a resumed deepseek-v4-pro session
(`claude --resume … --name ucf-d15-fix2`, dispatched 20:29, continuing the
round-1 fixer). The peer-messaging layer does not see sibling `-p` sessions,
so the two could not talk; this session left the marker
`collab/logs/d15-round2-session-collision.md`, adopted the deepseek
compose.cljc as the round's source (it complies with both consolidated
blockers), and reconciled its own test rows to that surface. Attribution
below is exact: compose.cljc's round-2 implementation is the deepseek
session's, the test rows and all validation below are this session's. If the
duplicate dispatch was accidental, one of the two sessions can be killed
safely — the artifacts are already reconciled.

## 1. The production durability requirement is mandatory (astra 1 + opus F1)

`compose.cljc` (deepseek): `:journals` is required for an exclusive
composition. `exclusive-journals-refusal` answers
`{:yin.k/status :yin.k/unsatisfied :yin.k/reason :journals-uncapable
:yin.k/policy :yin.k/exclusive :yin.k/failure-model …
:dao.stream.journal/durability …}` at open — before the authority is opened
and before any holder is activated — when the substrate is nil, empty,
partial (either supplier missing or not a function), or its declared
durability cannot cover the declared failure model
(`journal-substrate-capable?`: a :file backend whose failure-model rank
covers the requirement — the authority gate's own rule; a memory substrate
declares :none and covers nothing). `memory-journals` and `file-journals`
carry `:dao.stream.journal/durability` data
(journal/memory-durability / file-journal/durability), so the declaration
is the substrate's own, and `check-substrate!` keeps the fork-side shape
gate. Memory journals are confined to test compositions and fork: fork
still opens without a substrate or on `memory-journals`.

Pinned (this session, `compose_test.cljc`):
`a-journal-substrate-is-checked-at-open-test` — nil, `{}`, progress-only,
reply-only and both-suppliers-without-declaration each refuse at open with
the same `:journals-uncapable` answer and nothing composed, while
fork+memory-journals and fork+nil still open;
`an-exclusive-composition-refuses-substrate-journals-that-cannot-cover-its-failure-model-test`
— memory-on-:process-crash and file-on-:power-loss, the failing
declaration travelling with the refusal. The memory-authority gate row now
runs with a substrate in order (its :process-crash arm still refuses
`:exclusive-uncapable` at the authority), and its stronger-model arm
refuses at the substrate first — no substrate covers :power-loss, so the
authority is never asked. `exclusive-world` defaults to `file-journals`
over its own scratch root (`jroot`, cleaned by `cleanup-world!`;
`cleanup-dir!` is now recursive on the JVM), so every exclusive row runs
on durable journals; round 1's memory-substrate reopen row became
`a-file-substrate-composition-reopens-its-journals-and-recovers-its-holder-test`
(the same arms over files — identity reopen, unchanged records, releasing
recovery, minted-once, release sent, and the fresh-reply-journal
`:inbox-identity` refusal now over a fresh file root).

## 2. A holder config cannot substitute composition seams (opus F4, second half)

`compose.cljc` (deepseek): `holder-config-keys` is the documented holder
surface — :bytes :address :machine :arbitration :protection :receiver
:attach! :observe! :serve! :renewal-interval :inbound — and
`check-holder-config-keys!` refuses at add-holder any key outside it
("… :store is a composition seam", the key in the ex-data beside the
holder). `driver-config` merges the composition's seams first and then
selects only the holder's own keys, so the composition's :journal,
:reply-inbox, :outcome-inbox, :read-ledger!, :append-request!,
:append-diagnostic!, :clock, :store, :medium, :me, :units, :export-version
and :enroll! always win. The audit found no other override path:
holder-media's :inbound is the documented input-medium override,
arbitration-of names the composition's arbitration (a fork refuses one
wholesale), and `restart` reopens the composition-stored backend. The
journal substrate selector (:journals) is composition-level; a holder
config carrying it is refused with the rest.

Pinned: `a-holder-config-cannot-substitute-the-compositions-seams-test` —
14 smuggled seam keys each refused with the key named, no smuggled holder
added, and a clean holder running on the composition's clock, store,
units, medium and renewal-interval.

## 3. Everything else stays as round 1 left it

The drain, the close-before-exit, the stop latch, fork-refuses-arbitration
and the store-audit allowlist entry are untouched this round and
re-verified green.

## Residual (disclosed, not implemented)

The open-time gate validates the substrate's DECLARATION. A hand-rolled
substrate that declares file durability but mints memory backends would
pass open (all four pinned configurations refuse correctly, and the public
constructors never lie); `substrate-journal` does not re-verify the minted
backend's own `:dao.stream.journal/durability` at add-holder. A defensive
check there would close the lie-hole; left for the reviewer to rule, as it
is not among the pinned configurations.

## Validation (exact, this session, against the final tree)

- Red first: the new rows against the pre-round source — 13 tests, 122
  assertions, 23 failures (5 structural substrate arms, 3 capability arms,
  1 stronger-model arm, 14 seam arms).
- Focused JVM, `clojure -M:test -n yin.vm.ucf.compose-test -n
  yin.repl.main-test` — **56 tests, 482 assertions, 0 failures, 0 errors**
  (compose-test 13/125; main-test 43/357).
- `clojure -M:test -n yin.vm.store-write-audit-test` — **3 tests, 129
  assertions, 0 failures, 0 errors**.
- Full JVM fast lane, `clojure -M:test -e :slow` — **3762 tests, 239298
  assertions, 0 failures, 0 errors** (`collab/logs/d15fix2-jvm-full.log`).
- clj-kondo (mise) over the changed files — **0 errors, 0 warnings**;
  cljstyle (mise) — **clean**.
- Node lane, `node target/node-tests.js` over the Java-21 rebuild (run by the
  deepseek ucf-d15-fix2 session) — **3618 tests, 103662 assertions, 0
  failures, 0 errors** (`collab/logs/d15fix2-node-compile.log`).
- Dart focused lane, `--only yin.repl.main-test,yin.vm.ucf.compose-test` —
  **All tests passed** (50 tests; `collab/logs/d15fix2-dart-focused.log`).

## Files changed (round 2)

- `src/cljc/yin/vm/ucf/compose.cljc` — the exclusive substrate gate and the
  holder-config allowlist (authored by the concurrent ucf-d15-fix2 session;
  read, tested and verified by this one).
- `test/yin/vm/ucf/compose_test.cljc` — the round-2 rows, the file-substrate
  default world with its own scratch root, the converted reopen row, the
  recursive JVM cleanup (this session).
- `yin/repl/main.cljc`, `yin/repl.cljc`, `test/yin/repl/main_test.cljc` —
  **no round-2 change needed**: the shell carries the composition as data
  (`:custody`), the audit found no seam merge on the REPL side, and the
  main-test rows inherit the durable default through `exclusive-world`.
- `collab/logs/d15-round2-session-collision.md` — the duplicate-dispatch
  marker; `collab/logs/d15fix2-jvm-full.log` — the lane log.

---

# Round 3 (2026-10-08 late, glm-5.3 — astra signoff r2: the substrate's declaration is not the backend's)

Completed-GMT: 2026-10-08 14:50:17 GMT
Completed-Local: 2026-10-08 21:50 +07

Astra's signoff r2 found one remaining gap, exactly the residual round 2
disclosed: the open gate validates the substrate's DECLARATION
(`exclusive-journals-refusal`), but `substrate-journal` opened whatever each
supplier answered without checking the BACKEND's own durability — a
substrate declaring file/process-crash could supply memory-backed reply or
progress storage and still activate a holder.  (Process note: the signoff
r2 file lives in the main tree, outside this session's readable scope; the
dispatch restated its finding precisely — the 151-168 declaration check
against the 197-215 unchecked supplier opens — and this round implements
that ruling.  Signoff r1 was readable in this worktree and is closed by
rounds 1-2.)

## The fix (compose.cljc alone)

- `backend-durability` reads the backend's own
  `:dao.stream.journal/durability` seam as data — the declaration shape
  `journal-substrate-capable?` already consumes — and is total: a backend
  that declares nothing (or whose seam cannot be read) answers nil, and nil
  is never capable, the same rule the map-level gate runs.
- `substrate-journal` takes `required` (the composition's failure model;
  nil for a fork) and validates the ANSWERED backend immediately after the
  open-time `check!`, through that same `journal-substrate-capable?` rule
  the open gate uses (:file backend whose failure-model rank covers
  `required`).  A backend that fails is refused with `check!`'s assembly
  ex-info carrying :yin.k/reason :journals-uncapable, :yin.k/policy
  :yin.k/exclusive, :yin.k/failure-model and the BACKEND's own declaration
  — after `close-backend!` releases the backend the composition just
  opened (never-throw, the close! rule).
- `holder-media` passes `(:failure-model c)` for an exclusive composition
  (nil for a fork — the fork's suppliers demand nothing; the fork/memory
  rows are unchanged) and wraps the reply open: any refusal there releases
  the progress backend the substrate already opened for this holder before
  rethrowing, so a refused addition leaves no directory lock behind.  The
  release covers every refusal out of the supplier phase, not only the
  durability mismatch.
- `close-backend!` moved above the assembly it now also serves; `close!`
  itself is unchanged.  The ns docstring, `open!`'s refusal paragraph,
  `substrate-journal` and `holder-media` docstrings now state the rule:
  the declaration and the storage cannot disagree.

Both backends are validated before driver construction, progress first: a
weaker progress backend refuses before the reply supplier is even called;
a weaker reply backend refuses after the progress backend opened, and both
are released.

## The pin (compose_test.cljc alone)

`an-exclusive-composition-refuses-backends-weaker-than-their-substrates-declaration-test`
— a lying substrate (declares `file-journal/durability`) over a file-backed
authority, so both the authority gate and the open gate pass and only the
addition can see what the suppliers answer:
- a memory PROGRESS backend is a refused addition — the backend's own
  :memory/:none declaration (not the substrate's) travels with the
  refusal, the reply supplier is never called, no holder is activated;
- a memory REPLY backend beneath a real file progress backend is a refused
  addition AND the progress directory reopens :ok afterwards — the lock
  the gap leaked, pinned released, the way close! leaves it;
- a backend whose durability seam is absent is refused with the absent
  declaration carried (nil is never capable).

## Validation (exact, this round, against the final tree)

- Red first: the new row against the pre-round source — 1 test, 15
  assertions, 9 failures + 1 error (both additions sailed into driver
  assembly — "The candidate driver needs a protection declaration" — and
  the progress directory stayed locked).
- Focused JVM, `clojure -M:test -n yin.vm.ucf.compose-test -n
  yin.repl.main-test` — **57 tests, 500 assertions, 0 failures, 0 errors**
  (compose-test 14/143; main-test 43/357).  Re-run after the cljstyle fix;
  identical.
- `clojure -M:test -n yin.vm.store-write-audit-test` — **3 tests, 129
  assertions, 0 failures, 0 errors**.
- Full JVM fast lane, `clojure -M:test -e :slow` — **3763 tests, 239314
  assertions, 0 failures, 0 errors** (`collab/logs/d15fix3-jvm-full.log`).
  (Round-over-round the lane is +1 test and +16 assertions while the new
  row adds 18: the other 2 are ordinary drift in timing-dependent suites —
  both runs 0 failures.)
- Dart focused lane, `mise exec babashka,java@21 -- bb src/dev/cljd_agg.clj
  --only yin.repl.main-test,yin.vm.ucf.compose-test` — **All tests
  passed** (51 tests, up from round 2's 50 by this row;
  `collab/logs/d15fix3-dart-focused.log`).
- clj-kondo (v2026.08.04, via mise) over the two changed files — **0
  errors, 0 warnings**; cljstyle — **clean** (its own fix applied: three
  reindentations inside the new row).
- Node lane: **not run this round** — round 2's 3618-test run stands over
  the same surfaces, this round's change is confined to the host-neutral
  cljc that lane compiles, and the dispatch named the JVM lanes.  The
  orchestrator's three-lane gate remains the closure.

## Files changed (round 3)

- `src/cljc/yin/vm/ucf/compose.cljc` — `backend-durability`, the
  `substrate-journal` capability gate with release-before-refusal, the
  `holder-media` required/release coordination, `close-backend!` moved
  above the assembly, the docstring paragraphs.
- `test/yin/vm/ucf/compose_test.cljc` — the new row (and cljstyle's
  reindentation of it).

No git writes.  Rounds 1-2's tracked modifications (repl.cljc, main.cljc,
main_test.cljc, store_write_audit_test.clj) are untouched this round.
