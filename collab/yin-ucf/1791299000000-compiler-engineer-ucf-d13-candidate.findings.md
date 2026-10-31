Completed-GMT: 2026-10-06 16:37:50 GMT
Completed-Local: 2026-10-06 23:37:50 +07
Coding-Agent: glm (glm-5.3)

# UCF M-next D13 — the driver's candidate half

Worktree `/Users/sto/workspace/datomworld-d13`, branch `ucf-d13-candidate`
(master + D12). Two new files, nothing landed touched (`git status`:
only the collab prompts and the two files below).

## Changed files

- `src/cljc/yin/vm/ucf/holder/driver.cljc` — new, 946 lines: the
  candidate driver, one explicit step state (`:phase` one of
  `:validating`, `:proposing`, `:running`, `:safepoint`, `:releasing`,
  `:failed`), plain data, `initial` (assembly-checked seams) and `step`.
- `test/yin/vm/ucf/holder/driver_test.cljc` — new, 1020 lines: 12 deftests over a
  real in-process world (real authority over the memory journal, real
  front per holder, real lease judge, real offers/grants/bindings/
  inputs/admissions; every checkpoint a real lift of a real machine the
  engine parked).

## Exact outcomes (JVM; `clojure -M:test -n …`)

- `yin.vm.ucf.holder.driver-test` — **12 tests, 153 assertions, 0
  failures, 0 errors.**
- The holder cluster plus the landed reader suites
  (`driver writer reader evidence export handoff handoff-v1 custody
  ledger checkpoint`) — **163 tests, 1621 assertions, 0 failures, 0
  errors.**
- The remaining UCF suites (`authority remote crash-cut durability
  lift-v1 ledger-fixture`) — **76 tests, 1180 assertions, 0 failures, 0
  errors.**
- clj-kondo (`clojure -M:kondo`) and cljstyle were **sandbox-blocked**
  in this environment (command approval refused), as the constraints
  anticipated; not run. Node and Dart lanes not run: JVM is the
  iteration lane per the acceptance criteria; the three-lane run
  belongs to the slice gate.

### Red → green evidence

Test-first: the driver's contract rows were written against the empty
namespace and failed red before the behaviour existed (the first runs
were syntax/arity errors, then, with the file loading: the winner's
activation answered `:unsatisfied :undersized-grant` because dao.lease
readings must be positive — the clock seeded 0 was not a duration; the
invalid-binding cases answered `:fold-defect` because hand-built
records were not journal-wrapped and used a non-`datom/default-op`
op slot; the renewal never fired because the interval was measured
from a zero reading). Each fix moved exactly its row green; the final
state is all green as above. Two genuine driver defects the red runs
exposed and fixed: `(second {:s 1})` is nil on a map entry (the
tenure-to-number conversion now reads the entry's key/val), and the
release used to leave one step after it became owed (it is now sent at
the moment the failure acquires the lease and retained on a full
append).

## The contract, as built

1. **Fetch and validate** — `:validating` runs `checkpoint/inspect` and
   a `resume-task` that supplies no `:grant`: a valid version-1 blocked
   or parked root answers `:yin.k/awaiting-grant` at the custody gate
   with zero attach calls; anything else is a data refusal of the D7
   pipeline with no cleanup owed. A halted version-1 root is no lease
   subject: it lowers once, gated `:ended`, and is published with no
   custody (tested).
2. **Authority attachment** — `read-ledger!` answering nil (no
   attachment) is `:yin.k/unsatisfied` naming the arbitration; nothing
   is proposed (tested).
3. **Propose** — fresh proposal ids (`p-<me>-<n>`, re-minted after a
   refusal, resent unchanged while uncarried); unanswered answers
   `:yin.k/awaiting-grant`; the occurrence held by another (or never
   grantable again) answers `:yin.k/not-holder` carrying the lease
   state observed, releasing nothing (tested, row 1: `awaiting-grant`
   then `not-holder` over two encodings of one occurrence, same host).
4. **Accept only with binding evidence** — the grant is observed by a
   raw scan of the arbitration's records (a history too corrupt to fold
   cannot hide the holder's own grant), then authenticated by
   `custody/binding-evidence` over the same records. A history that
   does not read whole (gap, start past origin, transport error, fold
   defect) is refused before any evidence is derived, `:yin.k/unsatisfied`,
   never a release (all four D3 modes tested; never an empty prefix,
   never a machine). An invalid binding — none (another author's),
   duplicated, inexact (float) epoch, outside the grant's transaction,
   mismatched grant — is `:yin.k/not-holder` followed by a release (all
   four tested; the binding check necessarily precedes the evidence
   fold, because every one of these corruptions also breaks the fold).
5. **Lower with the D10 grant inputs** — `:checkpoint` (the fetched
   address), `:protection` (the composition's declaration, passed
   through), and `:grant` with lease, holder, evidence `E`
   (`read-evidence`'s ready answer) and tenure as numbers (`now` the
   clock reading, `bound` the grant duration over the observation
   basis, `:live` from the fold's liveness at the binding's epoch).
   Lower runs only on the accepted grant; its failure is after the
   grant and releases first (tested via a declaration the evidence's
   enrollment contradicts).
6. **Run under the gate, driving writer and reader** — each `:running`
   step: `vm/run`, `reader/replay` below the frontier (with
   `:control-outstanding?` supplied), `writer/emit`, `reader/step`,
   then every attributed record the composition's reply reader and
   outcome projection answer routed to `writer/discharge`,
   `reader/settle` or the driver's own control arm (proposal/renewal/
   release carriage, matched by request id under `front/reply-evidence`).
   The at-least-once writer arm is driven end to end in its own test
   (bare append lands, machine runs on to halt); see concerns for the
   enrolled arm.
7. **Renew before half; all IO stops at the bound** — dao.lease's own
   holder discipline (`observe-grant`/`holding?`/`due-to-renew?`/
   `observe-renewal`/`stop`) over the composition's clock and the
   composition's renewal interval (shape-checked at assembly,
   sizing-checked at the grant: an undersized grant is held but never
   runnable, released as `:undersized-grant`). Tested: the renewal
   request (D2's `:yin.k/renewal`, request id `[:yin.k/renewal l n]`)
   lands at elapsed 10 < half of 30, the basis advances to the
   pre-append reading, the bound moves with it, and at the bound the
   step appends exactly one value — the release — plus one run-end
   diagnostic on the composition's stream, the machine gated `:ended`,
   and nothing more afterwards.
8. **Post-grant failure releases first; a pending release is retried** —
   `releasing` gates any machine `:ended`, stops the dao.lease holder,
   sends the release request at once and retains it verbatim on a full
   append; the next step retries the identical request (tested against
   a bounded inbound that frees as the front reads). After the release
   the judge lapses the lease and the occurrence is regranted — the
   residual-2 wording is honoured: a release clears no quarantine,
   completes nothing without accepted completion evidence, and the
   ordinary failed run's occurrence stays regrantable (tested at the
   bound end and after the lower failure; in the invalid-binding world
   the regrant is asserted at the authority level, because the
   duplicated-binding corruption permanently defects every successor's
   evidence fold).
9. **Current tenure before scheduling execution and IO** — every
   `:running`/`:safepoint` step refolds the ledger (the lease must
   still be the occurrence's live lease at the binding's epoch — else
   `:stale` ends the run) and re-reads the clock (`holding?`); unknown
   tenure (a broken fold) schedules nothing and answers
   `:yin.k/unsatisfied`.
10. **The checkpoint is an admitted variant** — the address must be
    among the fold's admitted variants of the granted occurrence; a
    candidate holding the second encoding of an offered-only-first
    occurrence answers `:yin.k/not-holder` `:variant-not-admitted` and
    releases (tested).

Also tested beyond the six rows: the regrant replays from the
checkpoint (tenure 1's recorded input is replayed by tenure 2 with zero
live observations, the machine running on to its halt); the source
itself resumes only after its own grant (the source's local machine
stays `:exporting` through `awaiting-grant` and a grant to another, and
its own grant lowers fresh bytes, never restoring the old local
machine); a halted result lowers with no grant and no custody.

## Unresolved concerns and honest boundaries

- **The enrolled writer arm is not driven end to end through the
  candidate path.** An enrolled retained write cannot reach a D13
  activation without the exit half: a body carrying an operation id is
  a successor, and the authority refuses a successor's offer as an
  orphan until its predecessor's closure is recorded (D14 owns
  publication, report and the successor chain). The writer's emit is
  driven every run step (and its enrolled behaviour is D11's own tested
  surface); what D13 adds — that the driver calls it under tenure and
  routes its replies — is covered by the at-least-once test and the
  input/admit routing. A full enrolled write through the driver belongs
  to D14's or the composition tests, where a completed predecessor
  exists.
- **The judge's release-lapse is only exercised where the judge granted
  the lease.** A lease granted through the ledger writer directly (the
  invalid-binding worlds) is not in the judge's ledger, so it will not
  drain that release; those tests lapse through the writer (as
  `reopen!` would) after asserting the driver's release request left.
  The renewal test does show the judge itself lapsing a driver-released
  lease (asserted nil-held after one judge pass).
- **`vm/run` is run once per step before replay** — a machine whose
  ready queue needs several internal steps to reach the next park
  advances one `vm/run` per driver step. That matches the D12 reader's
  own discipline ("the driver steps the machine and calls again") but
  is a scheduling choice, not a ruled one.
- **Two tenure readings per accepting step** (one for `observe-grant`,
  one for the lower's `now`): both are taken inside one step; a clock
  that advances between them could make `now` the later reading. The
  bound still holds (`now < basis+duration` only tightens), so the
  lower's check is conservative, but a single reading would be cleaner.
- kondo/cljstyle sandbox-blocked (noted above); the files were
  hand-formatted to the surrounding style.

## Incomplete work

None for D13's scope. Not done, by design of the slice: the source and
exit half (successor publication, resumed report, completion, the
journal) is D14; composition and REPL wiring is D15; the safepoint
handoff (phase `:safepoint`, the machine published with tenure still
renewed) is the seam D14 picks up.
