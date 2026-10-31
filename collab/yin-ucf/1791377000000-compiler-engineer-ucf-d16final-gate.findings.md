Completed-GMT: 2026-10-08 22:14:47 GMT
Completed-Local: 2026-10-09 05:14:47 Asia/Ho_Chi_Minh
Coding-Agent: Claude (claude-opus-5-5)

# D16-final: compose-driven gate rows, final state

## Review round: changes applied (reviewer 1791380000000, Sonnet 5.5, CHANGES_REQUESTED)

The owner requested two assertion changes, recorded below. Both are in
`test/yin/vm/ucf/compose_rows_test.cljc`. There are still no production
edits and no git writes.

1. **Row 5 quarantine check tightened**
   (`row-5-a-changed-live-input-meets-the-durable-record-test`).
   - **Before:** `(not (true? (get-in … :yin.k/quarantined)))`. This also
     passed when the lookup path was wrong and returned nil.
   - **Now:** two assertions.
     - `(contains? occurrences o)` proves the path names a known occurrence.
     - `(nil? (get-in occurrences [o :yin.k/quarantined]))` proves no
       quarantine.
   - `nil` is the right expectation. The ledger fold only ever writes
     `:yin.k/quarantined true`, so an unquarantined occurrence has the key
     absent.
2. **Row 2: duplicate evidence creates no second grant**
   (`row-2-abort-is-legal-only-while-no-offer-can-have-landed-test`, the
   refused-abort branches `transport-error` and `ok`).
   - After the refused abort, the carrier is set to answer `ok`. The holder
     is then driven to `:running` and 6 more whole ticks.
   - Assertions:
     - **`transport-error`:** the identical offer was attempted more than
       once. That is the duplicate evidence.
     - **`ok`:** the offer was sent exactly once, because the first one was
       admitted.
     - The occurrence has exactly one admitted variant.
     - Exactly one lease exists for the occurrence ("duplicate evidence
       creates no second grant").
     - The journal holds one accepted-grant acknowledgment.
     - There is exactly one activation attach.
   - The first run of this assertion was red because I expected more than
     one attempt in the `ok` case too. That was an expectation error: an
     admitted first offer is never retried. I fixed the expectation, not the
     code.

**Focused validation after the changes:** `clojure -M:test -n
yin.vm.ucf.compose-test -n yin.vm.ucf.compose-rows-test -n
yin.repl.main-test` gives **97 tests / 1,303 assertions / 0 failures / 0
errors** (`build/d16f/focused-review.log`). That is 15 assertions more than
the 1,288 before review: 1 for row 5 and 14 for row 2. `git diff --check`
exits 0. The full JVM fast lane was not rerun, at the owner's direction.

### Freeze-liveness stall: disposition

This disposition was relayed by the owner in the review-fix request. No
separate Architect ruling document was seen by this agent.

- **The behaviour:** a crash during the freeze (the store puts, or anywhere
  before `:fenced`) recovers the source as `:stalled`, status
  `:yin.k/unsatisfied`, detail `{:reason :incomplete-preparation,
  :occurrence o}`. It stays there permanently: no re-mint, no fence, no
  offer, no authority entry, no program IO.
- **Disposition:** for Stage D this is the **safe, intended terminal state**.
  Liveness (re-preparation or resumption of an interrupted freeze) is
  **deferred to Stage E**.
- **Consequence:** `the-wired-compositions-content-store-cuts-test` and the
  inherited progress-journal-cut row pin this shape deliberately. A future
  liveness fix must change both rows on purpose.

### Row 5 write-ahead reading

The reviewer asked for this to be recorded as a ruling. Here is the reading
as implemented: the write-ahead durable input record stops a changed live
input from causing divergence at an op id. As a result, `:intent-conflict`
is reachable only through authored envelopes.

No Architect ruling on this reading has been received. It stands as pinned
behaviour **pending Architect confirmation**.

### Deferred to Stage E (by name)

- **Freeze liveness:** re-preparation or resumption after an interrupted
  freeze (see above).
- **Row 6 partition:** the real partition half. Same-host covers only the
  lost-request and lost-grant unit variants.
- **Row 8, remaining clause-8 and linker cases:**
  - forged outcomes;
  - overflow;
  - attachment failure;
  - store isolation;
  - an unreadable accepted checkpoint;
  - counter variants.
- **Row 2 unknown-acceptance cut:** a crash between the offer's intent and
  attempt records, and crash/reopen after each export phase. Today only the
  restart refusal covers this.
- **Row 1 variants:** wakeable writer, direct resume, install-child
  suppression.
- **Cross-host, process-kill and partition durability** of the same-host
  rows. Rows 6 and 7 currently converge because the composition's authority
  restarts with the process and reclaims `:policy` on reopen.

### Still owed before READY_TO_LAND (reviewer items 1–2, not done this round)

- A three-lane run for the slice: the full JVM fast lane (its earlier run was
  SIGKILLed, exit 137), Node (`npm ci` first in this worktree) and Dart.
- clj-kondo and cljstyle output on the two changed files, observed first-hand.
  The earlier "clean" result was relayed by the owner.
- **Carrier contract for row 2.** Abort legality treats `full` and `refused`
  as proof the offer did not land. That rests on the dao.stream rule that
  those append outcomes mean nothing was appended (`export/not-appended?`).
  A carrier that answers `full` after partially accepting an append would
  violate the contract this rule relies on.

---

*The rest of this report is the pre-review state. Its counts are superseded
by the figures above.*

Branch `ucf-d16-final` at `4d46b798` (the policy-reclaim completion repair).
No git writes. No production or landed-namespace edits. compose.cljc is untouched.
The whole diff is under `test/yin/vm/ucf/`. This report is the requested
`collab/` exception.

**Disposition.** Thirteen new acceptance rows landed in the test tree. The
focused suites are green. The D16-final gate is **not fully discharged**:
specific sub-items are still open (see "Whole-contract status"). No
same-host three-lane acceptance is claimed, because Node and Dart were not
run in this round.

## What the base already held

At `4d46b798`, `compose_rows_test.cljc` was committed with 27 tests. That
includes the two former red row-7 completion-crash rows
(`row-7-cut-after-the-resumed-report-test` and
`row-7-cut-after-the-release-append-test`). The policy-reclaim repair made
both green. The completion-recovery rows were also already present: terminal
report, historical policy without closure, unavailable history, unknown policy
transaction, abandonment, quarantine and exact-mismatch. Baseline focused run
at `4d46b798`, before this round's edits:
**84 tests / 943 assertions / 0 failures / 0 errors**.

## Changed files

- `test/yin/vm/ucf/compose_rows_test.cljc`: 13 new deftests and their fixtures.
  The `rows-world` helper gained two options:
  - `:wrap-backend` wraps the file backend over the world's own directory, so a
    crash row can reopen past a cut.
  - `:source` overrides the source stream.
- `test/yin/vm/ucf/compose_test.cljc`: `exclusive-world` gained a `:store`
  option. This is a shared helper, and the default behaviour is unchanged. The
  earlier public exposure of `log-writer`, `temp-dir` and `cleanup-dir!` is
  retained.

New fixtures in the rows file:
- `answering-inbound`: a carrier whose appends answer a chosen outcome.
- `rewritable-source`: a ring whose live reads can change under a recovery.
- `cut-backend`: an authority frame write lost once, before or after durability.
- `cut-store`: a content-store put cut before or after the bytes land.
- Small readers: `offer-attempts`, `store-records`, `one-commit?`,
  `first-index` (portable; replaces a JVM-only `.indexOf`), `control-until`.

## The 13 new acceptance rows

Every row drives `yin.vm.ucf.compose` (`open!`, `source`, `holder`, `abort`,
`step`, `control-step`, `program-step`, `enroll!`, `close!`, reopen) through
the composition's own fronts and journals. No row calls a holder namespace
directly.

1. **`row-2-abort-is-legal-only-while-no-offer-can-have-landed-test`**: covers
   carrier outcomes `full`, `refused`, `transport-error` and `ok` under the
   source's offer.
   - **`full` and `refused`:** each attempt journals the carrier's answer, and
     abort is legal. The holder state then shows:
     - phase `:aborted` and detail `{:yin.k/aborted o}`;
     - the machine handed back `:running`, still blocked, with its original
       wait-set and ids intact;
     - the `:aborted` record is durable.
   - **After an `ok` abort:** the holder never offers again, even once the
     carrier recovers. The authority never learns of the occurrence, and
     nothing is attached.
   - **`transport-error` (unknown delivery) and `ok`:** abort is refused with
     `{:yin.k/reason :offer-possibly-accepted}`. The source stays fenced, and
     the fenced record keeps its one authentic wait.
2. **`row-2-a-restarted-export-never-aborts-test`**: after a crash and reopen,
   abort is refused with `{:yin.k/reason :restarted}`, even though every
   earlier attempt was refused.
3. **`row-1-the-competitor-awaits-the-grant-then-is-refused-test`**: the
   competitor is explicitly observed as `:proposing` / `:yin.k/awaiting-grant`
   with no machine exposed. That observation comes before `:yin.k/not-holder`,
   which names `h1`. Only one lease exists.
4. **`row-3-a-commit-before-the-reclaim-replays-for-the-regrant-test`**: the
   commit-before-reclaim alternative.
   - The commit lands first, then the reclaim.
   - The regrant's write at the same id answers only `:replayed`.
   - Two authored equal replays both answer `:replayed` with the stored result.
   - The target is `["A"]` throughout.
5. **`row-4-zero-or-one-commit-at-a-stable-id-across-the-cut-test`**: cuts
   before and after the commit.
   - At the cut, the target and outcome reader hold exactly zero (before) or
     one (after) commit.
   - Every recovered admit carries the stable id `id0` and the intent `"A"`.
   - Every answer names `id0`, and the surviving reply journal holds exactly
     one `:committed` and otherwise only `:replayed`.
   - After the commit, the outcome stands at the same position across reopen.
6. **`row-5-a-changed-live-input-meets-the-durable-record-test`**: the source's
   live value changes from `"A"` to `"Z"` across the crash.
   - **Input uncarried:** the recovery re-reads live, and `"Z"` is the first
     and only commit.
   - **Write uncarried or committed:** the durable input replays, nothing is
     re-read, and the target stays `["A"]`.
   - **In all three cuts:** there is no `:intent-conflict` or
     `:input-conflict`, and no quarantine.
   - This ties row 5 to changed recovered input. The finding is that the
     write-ahead durable input record stops a changed live input from causing
     divergence at an id. So the `:intent-conflict` path is reachable only
     through authored envelopes, which the existing divergent-intent row
     covers.
7. **`row-6-an-unobserved-grant-lost-to-a-crash-is-reclaimed-test`**: a lost
   lease fact. The judge granted, but the holder never observed or
   acknowledged it before the crash.
   - The authority's reopen reclaims that lease with cause `:policy`.
   - The recovered holder proposes under a fresh id, and every retry of that
     id is the identical proposal.
   - Each proposal id is answered exactly once.
   - Exactly one live lease exists, at epoch 1, with one acknowledgment.
   - This is a unit variant with a process crash, not a partition proof.
8. **`row-7-the-authority-lost-under-a-live-exit-test`**: the authority's
   frame write is lost during a live exit, with a cut before or after
   durability.
   - The live authority closes nothing.
   - **After the cut:** the report is durable, and the reopen closes it with
     the exact policy completion transaction at epoch 1. The recovered exit
     has no machine and runs no program IO (side effect `[:side]`).
   - **Before the cut:** the report is lost, so the holder returns to
     candidacy. The regrant re-runs, repeating the at-least-once side effect
     (`[:side :side]`), and closes at epoch 2.
   - In both cases the enrolled write stays exactly-once.
9. **`row-7-a-successor-whose-lower-fails-releases-and-stays-eligible-test`**:
   failure-lower on the successor.
   - The candidate awaits, is granted, and its lower fails on an unattachable
     stream. It goes `:releasing` before reaching `:failed` with
     `{:dao.stream/identity "prog-c" :dao.lease/released true}`.
   - It sends one identical release, never exposes a machine, and attaches
     nothing.
   - The successor sits at epoch 1 with no lease and no closure, and the
     origin's closure stands.
   - A later candidate is granted the same successor at epoch 1.
10. **Closed-ancestor id (inside row 9):** an admit by the successor that
    carries the closed origin's op id is diagnosed `:foreign-op-id`. The test
    pins the exact diagnostic map, confirms there is no reply, and confirms the
    target stays empty.
11. **`row-8-the-same-id-on-another-target-conflicts-test`**: a cross-target
    conflict. Re-using `id0` with the same intent against a second enrolled
    target answers `:intent-conflict` naming `id0`. The other target receives
    nothing.
12. **`row-8-an-unknown-effect-transport-error-test`**: the admission's
    authority frame write is lost before or after it becomes durable.
    - The front answers the exact map `{:yin.k/admission :suspended,
      :yin.k/op-id id0, :yin.k/incarnation lease, :yin.k/arbitration
      {:dao.stream/identity …}}`. It carries no result.
    - The live authority serves no uncertain effect: the target is `[]` at
      the cut.
    - After the crash, the durable frame decides. A written effect replays
      (zero further commits), and an unwritten one commits exactly once.
    - The target is `["A"]`, with one outcome.
13. **`row-8-exact-outcome-maps-through-the-front-test`**: pins the exact
    closed maps for `:committed`, `:replayed`, and a fresh commit past an id
    gap (`{admission, op-id, incarnation, effect-result {:outcome ok}}`).
    `:intent-conflict` names the id and incarnation and carries no effect
    result.

Also new (counted in the 13 above as the stage-D row):
**`the-wired-compositions-content-store-cuts-test`**. It cuts the freeze's two
store objects (the prepared record, then the body), each before and after the
bytes land.
- At the cut:
  - each object's store intent is durable before its put;
  - the cut object is never acknowledged;
  - its bytes are present exactly in the after-cut case;
  - nothing is fenced or offered.
- After the crash, reopen with the uncut store recovers
  `:stalled` / `:yin.k/unsatisfied` / `{:reason :incomplete-preparation,
  :occurrence o}`. There is:
  - no machine;
  - no re-mint;
  - no fence or offer;
  - nothing on the authority;
  - zero attaches.
- The source's own machine stays blocked.

(The precise count: 12 row deftests above plus the store-cut deftest gives
13 new deftests. Item 10 is a `testing` block inside item 9.)

## Test-first evidence (honest classification)

These rows run against production code that had already landed. Every new
row's first run was red, but the reds were **fixture or expectation errors,
not production defects**. So no row here is a completed red→green cycle
driven by a production change. Specifically:

- **Row 2:** the offer is sent on the control plane, so my first fixture
  stepped only the program plane and never reached an attempt.
- **Rows 4 and 5:** the reply journal survives the reopen, so the tallies
  must span both tenures.
- **Row 6:** the old proposal was granted. An unobserved grant is reclaimed
  `:policy` on reopen, not regranted under the old id.
- **Row 7, lost authority:** a cut before durability takes the regrant path,
  so the closure lands at epoch 2.
- **Row 8, transport error:** the live authority does not serve a frame that
  was written past the cut until reopen.
- **Store cuts:** a thrown put is absorbed by an in-process retry, so the
  cut must be the crash. Recovery then stalls; it does not re-prepare.

Each red was diagnosed from the landed code's actual answer and pinned as the
contract. Probe assertions were used temporarily and are all removed.
Intermediate logs are in `build/d16f/` (git-ignored): `baseline.log`,
`row2.log`, `batch2.log` to `batch5.log`, `probe.log`, `focused-final.log`.

## Validation

| Check | Outcome |
|---|---|
| Baseline focused JVM at `4d46b798` (`-n compose-test -n compose-rows-test -n yin.repl.main-test`) | **84 tests / 943 assertions / 0 failures / 0 errors** |
| Final focused JVM (same three namespaces) | **97 tests / 1,288 assertions / 0 failures / 0 errors** (`build/d16f/focused-final.log`). `yin.repl.main-test` printed one infrastructure SKIP: `build/yin-repl-peer` absent |
| `git diff --check` | exit 0, no output |
| clj-kondo, cljstyle | Both commands were blocked by the session's permission gate, so I did not observe their output. The owner reports both clean on the changed files. |
| Full JVM fast lane (`clojure -M:test -e :slow`) | **Not completed.** The run was killed with exit 137 (SIGKILL; most likely the 600 s tool timeout), before any summary line. No full-lane count is claimed. The owner waived a rerun. |
| Node and Dart lanes | Not run. |

## Whole-contract status

| Contract | Evidence now present | Still open |
|---|---|---|
| Row 1 | Two encodings, one admitted holder. Competitor explicitly awaiting, then refused with no machine. Later regrant. | Wakeable-writer, direct-resume and install-child suppression variants. |
| Row 2 | Source silent until its own grant. Abort legality across `full`, `refused`, `transport-error` and `ok`, plus the restart refusal. Retained waits and ids after abort and after refusal. | An unknown-acceptance cut between the offer's intent and attempt records, as a distinct composition cut (the restart refusal covers its consequence). |
| Row 3 | Delayed stale effect after reclaim. Commit-before-reclaim alternative. Two authored equal replays. Never both. | None known for same-host. |
| Row 4 | Before/after-commit cuts with zero or one commit at the cut, the stable id, exactly one commit across both tenures, the outcome position stable across reopen, and an external side effect that repeats. | Cuts inside the authority's admission transaction beyond the before/after frame cut. |
| Row 5 | Kept-cursor eviction with and without inputs. A changed live input across three cuts never diverges. Authored divergence becomes an intent conflict plus quarantine. | None: divergence through a changed recovered input is shown to be impossible under the write-ahead input record. Flagged for the Architect as a contract reading. |
| Row 6 (unit) | Lost request stream gives identical retries and one answer. A lost grant observation across a crash is a policy reclaim plus one fresh proposal, answered once. | A lost reply without a crash (the reply journal is durable and the holder reads positionally, so no public seam drops a reply). This is not a partition proof; that is stage E. |
| Row 7 | All four completion cuts are green: successor append, resumed report, release append and closure. Lost authority before and after durability. Failure-lower on the successor. Recovery negatives (pre-existing). | None known for same-host. |
| Row 8 | Unavailable authority, wrong author, foreign id, closed-ancestor id, stale lease and epoch, closure, equal replay, fresh id, cross-target conflict, divergent intent plus quarantine, transport error before and after durability, exact outcome maps. | Forged outcomes, an unreadable accepted checkpoint, counter variants, and the linker row-8 attachment, forgery, overflow and store-isolation cases. |
| Stage-D compose halves | Memory-authority refusal, fork label, progress-journal cuts at positions 2–8, and content-store cuts around both freeze objects. | Broader closure-bearing, alias, module and install recovery shapes; distinct program-IO counters per seam. |

## Unresolved concerns

1. **Liveness after an interrupted freeze.** A crash during the freeze's
   store puts, or anywhere before `:fenced`, recovers the source
   `:stalled :incomplete-preparation` for good.
   - **Safe:** there is no re-mint, no offer and no IO.
   - **Not live:** the source neither re-prepares nor resumes, even though
     the reopen is given its machine.
   - The existing journal-cut row already accepts this shape. Whether it is
     the intended terminal state needs an Architect ruling. These rows pin it;
     they do not endorse it.
2. **Rows depend on a same-host authority restart.** The row 6 lost-grant row
   and the row 7 lost-authority row both rely on the composition's authority
   restarting with the process. The policy reclaim on reopen is what makes
   them converge. Stage E has to prove the cross-host equivalent.
3. **Teardown audit (carried over).** Crash rows swap the world atom, and
   reconstruction rows close their own rebuilds. Not every inherited row
   records its last composition in the outer cleanup value.
4. **Gate not fully green.** The full JVM fast lane, Node and Dart were not
   completed. A clean three-lane run is still owed before any landing claim.
