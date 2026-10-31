Completed-Local: 2026-10-05 +07
Coding-Agent: claude (opus-5-5)

# C6, reclaim, epochs and judge reconstruction: findings

**Summary: all six scope items are done test-first. JVM focused (168
tests, 1239 assertions) and Node (whole suite, 2875 tests, 92846
assertions) are green, and kondo is clean. I could not run cljstyle
(the permission gate blocked the binary again, as in C5) or Dart.**

## Changed files

New:
- `test/yin/vm/ucf/authority/reclaim_test.cljc` (21 tests; one is
  JVM-only)

Modified:
- `src/cljc/yin/vm/ucf/ledger.cljc`
  - Three new kinds in `attribute-order`:
    - `:dao.lease/lapsed` `[status lease cause]`
    - `:yin.k/reclaimed` `[custody occurrence lease epoch]`, the epoch
      change
    - `:dao.lease/rejected` `[status proposal :yin.k/proposer]`
  - New fold arms, each failing closed:
    - lapse: `:malformed-fact`, `:unknown-lease`, `:duplicate-lapse`,
      `:unbound-grant`
    - epoch change: `:malformed-fact` (not exact, so 2^52 and -1),
      `:unpaired-epoch`, `:binding-mismatch`, `:epoch-mismatch`
    - refusal: `:malformed-fact` (no proposer), `:answered-proposal`
  - A post-record `:lapse-without-epoch` check, the twin of C5's
    `:unbound-grant`.
  - The grant arm also refuses `:exhausted-occurrence` and
    `:answered-proposal`.
  - New public `next-epoch`: e+1, or e at the bound.
  - Projection changes:
    - new `:max-epoch` and `:answered {[proposer pid] status}`;
    - lease entries keep the grant's terms (`:dao.lease/duration`, and
      `:dao.lease/proposal` and `:dao.lease/max` when present) and,
      once lapsed, `:dao.lease/cause`;
    - an occurrence gains `:yin.k/exhausted true` once exhausted.
  - `empty-projection` takes an optional `max-epoch`.
- `src/cljc/yin/vm/ucf/custody.cljc`: the `reclaimed` constructor.
- `src/cljc/yin/vm/ucf/authority.cljc`: the `open!` option
  `::max-epoch`, a lower epoch bound for tests, like `::max-exact`.
- `src/cljc/yin/vm/ucf/authority/grant.cljc`
  - The writer records grants, lapses (each with its epoch change, in
    one transaction) and refusals.
  - Grant replay compares the recorded terms.
  - The hook refuses losing candidates.
  - New `reopen!` and `rebuild-judge`.
  - The namespace docstring states the reclaim adapter contract
    (plan 1.3).
- `test/yin/vm/ucf/ledger_test.cljc`: 6 new tests. One C5 expectation
  changed: lease entries now carry their terms, and the projection
  has two more keys.
- `test/yin/vm/ucf/authority/grant_test.cljc`: C5's assertion "a lapse
  is slice C6's" became "a lapse of a lease never granted" (still
  invalid-value).

## Red before, green after

| Test | Red (before) | Green |
|---|---|---|
| `ledger-test`, 6 new tests and the changed C5 test | first a compile error (no `custody/reclaimed`); with only the constructor added: 3 failures and 17 errors across all 7 | yes |
| `reclaim-test`, 16 of the 20 written first | compile error (no `reopen!`); with stubs (`reopen!` = plain open, `rebuild-judge` = identity): 61 failures, 0 errors | yes |
| `reclaim-test/step-holds-the-lock-across-the-whole-pass` (JVM) | green before: C5's `step!` already holds the lock, as the gate said. The mutant `step!` without the lock fails it twice. | yes |
| `reclaim-test/a-non-ok-writer-leaves-the-lease-pending` | green before: C5's writer refused every lapse. The mutant "writer answers ok on a non-committing transition" fails it 3 times. | yes |
| `reclaim-test/a-proposal-whose-grant-failed-stays-answerable` | green before: nothing in C5 recorded a failed grant. It pins that the rebuilt judge's `:answered` comes from the ledger alone. | yes |
| `reclaim-test/the-ledger-epoch-bound-is-the-published-one` | green before (constant check, written after the ledger change) | yes |
| `reclaim-test/an-old-unsolicited-grant-reads-inadmissible` | added after a mutation survived (below); fails under that mutant | yes |
| `grant-test/the-writer-refuses-what-it-cannot-record` | 2 failures once lapses became writable; the assertion was updated | yes |

I ran eight mutations on `grant.cljc` against the reclaim and grant
tests, each reverted afterwards. In the first run, one mutation
survived and two were broken mutations (arity errors), so I fixed the
mutations and strengthened the tests:

| Mutation | Result |
|---|---|
| `step!` without the lock | 2 failures |
| lapse committed without its epoch fact | 32 failures, 12 errors (the fold poisons) |
| writer answers ok when nothing committed | 15 failures |
| writer ignores the answered proposal on a grant | 2 failures |
| hook refuses no loser | 4 failures |
| hook grants proposals already answered | **survived at first**: `deliver-authored` drops the grant anyway. Added an assertion that an answered proposal must not take the occurrence from a fresh candidate in the same pass: 1 failure |
| `rebuild-judge` leaves `:seen` empty | **survived the old-media test at first**: `:answered` alone blocks a grant that carries a proposal. Added `an-old-unsolicited-grant-reads-inadmissible`: 3 failures |

## Scope items

1. **Lapse and epoch in one transaction.**
   - The writer turns an appended `:dao.lease/lapsed` into
     `[lapse, (custody/reclaimed o l e')]`, one transition.
   - The fold refuses a lapse whose record has no epoch change
     (`:lapse-without-epoch`), one whose epoch change comes in a later
     record, and an epoch change with no lapse or before it
     (`:unpaired-epoch`).
   - The plan's two adapter tests:
     - `no-revocation-leaves-the-authority-before-the-lapse-commits`:
       the readiness check and the writer, at the moment the lapse
       is appended, both see the lease live, epoch 0 and no lapse in
       the journal.
     - `a-non-ok-writer-leaves-the-lease-pending`: the lease stays
       `:pending` with cause `:policy` across two passes, with the
       projection and the frames unchanged.
   - `:reclaim` is still `ready?`, which writes nothing.
2. **Epoch rules.**
   - Grants bind 0, then 1, then 2 across reclaims.
   - A second occurrence offered later starts at 0 (ledger test).
   - At the bound (real 2^52-1 in the pure fold test; `::max-epoch 1`
     through the writer and reopen):
     - the grant is valid and its binding evidence reads the bound;
     - the next reclaim records the lapse with an unchanged epoch and
       sets `:yin.k/exhausted`;
     - after that, the writer refuses a grant and the hook refuses
       the proposal.
   - 2^52 and -1 are refused on the bytes by the fold (binding and
     epoch change). `binding-evidence` already refused both in C5
     (`custody-test`).
3. **Judge reconstruction.**
   - `rebuild-judge` sets `:seen` from every recorded grant and lapse,
     `:answered` from every recorded grant and refusal, and empties
     `:ledger` and `:queue`.
   - The refusal fact carries `:yin.k/proposer`.
   - The hook now refuses losing candidates, so `:answered`
     reconstructs completely: the test checks that the judge's and the
     ledger's `:answered` are equal after a pass.
4. **Reopen reclaims.**
   - `reopen!` opens, then appends `(lease/lapsed l :policy)` through
     the same writer for every live lease, in grant-`t` order (so the
     bytes are deterministic). Each is one transaction, done before it
     returns the authority. Nothing is regranted, and
     `dao.lease/restart` is not used.
   - A reclaim that does not commit closes the authority and answers
     `{:yin.k/status :refused :yin.k/defect :unreclaimed
     :dao.lease/lease l}`.
   - The tests cover the old self-authored `:accepted` (dropped), the
     old answered proposal (no second grant) and the failed grant
     append (granted on retry, at epoch 0).
5. **Crash cuts.**
   - A lapse cut at each of the three cuts gives 0, 1 or 0 lapses,
     always with its epoch change.
   - Reopen after each cut reclaims exactly once in total.
   - A cut during reopen's own reclaim refuses the open; the next
     reopen reclaims.
   - The epoch is 0, 1, 2 across two reopens and is never reused.
6. **Gate follow-ups.**
   - (a) The JVM-only lock test uses a test-authored `:writer` that
     blocks on a promise, `step!` on a future, and a concurrent
     `enroll!` that does not finish within 200 ms and commits at t=2,
     after the grant.
   - (b) Replay compares duration, proposal and max:
     `grant-replay-compares-the-recorded-terms`.

## Results

- JVM focused: `clojure -M:test` with `-n` for
  `yin.vm.ucf.authority.reclaim-test`, `yin.vm.ucf.ledger-test`,
  `yin.vm.ucf.authority.grant-test`, `yin.vm.ucf.authority-test`,
  `yin.vm.ucf.custody-test`, `dao.stream.journal-test`,
  `dao.stream.journal.file-test`, `yin.vm.ucf.checkpoint-test` and
  `dao.lease-test`: 168 tests, 1239 assertions, 0 failures, 0 errors,
  9 s wall. No test needs `^:slow`.
- Node: `clj -M:cljs -m shadow.cljs.devtools.cli compile test` ran the
  whole suite: 2875 tests, 92846 assertions, 0 failures, 0 errors, 0
  warnings. Every `yin.vm.ucf.*` namespace printed `Testing <ns>`,
  `reclaim-test` included.
- kondo over the seven touched files: 0 errors, 0 warnings.
- ASCII only; no line over 80 columns (awk checked).
- C3's cross-host digest fixture still passes, so the bytes of the
  existing kinds have not moved.
- **Not run:**
  - `cljstyle fix` / `check`: the binary needs approval in this
    session. Please run both on the seven files.
  - Dart and the full lanes: yours.

## Deviations and smallest choices

1. **The epoch change is its own custody fact**, `:yin.k/reclaimed`,
   rather than a key on the lapse. This keeps the lease fact
   unchanged, as UCF 7.7.2 requires, and the pairing mirrors
   grant/bound. At exhaustion it is still written, carrying the
   unchanged epoch, so every lapse has exactly one paired fact and
   exhaustion is derived from the ledger (a lapse of the lease bound
   at the maximum).
2. **The refusal fact adds `:yin.k/proposer` to the
   `:dao.lease/rejected` fact itself.** It is not a separate custody
   fact. The hook must put it there, because `deliver-authored`
   hands the writer only the fact. `dao.lease/defective?` ignores the
   extra key, so the judge still delivers it.
3. **Reopen order.** `reopen!` reclaims (step 6) before
   `rebuild-judge` (step 5) runs. The judge is then built from the
   post-reclaim ledger, so its `:seen` already holds the new lapses.
   The result equals the plan's order. "Service disabled" means that
   no caller holds the authority until `reopen!` returns.
4. **`::max-epoch`, a test bound.** It is threaded into the projection
   as `:max-epoch`. A ledger must be reopened with the bound it was
   written under, or the fold refuses `:epoch-mismatch`.
5. **What the hook leaves unanswered.** It does not answer a proposal
   for an occurrence that was never offered, a proposal with no
   author, or one its proposer already had answered. The first may be
   answered on a later re-drain, for example after reopen.
6. **A refusal can land when its pass's grant append failed.** The
   hook delivers grants, then refusals. If the winner's grant append
   fails, the losers are still refused for that proposal id, and the
   winner's proposal stays answerable.
7. **`:seen` gets no releases.** The ledger records no release until
   C8.

## Open questions

1. **UCF 7.7.8 says "raises it by exactly one in the transaction that
   records the `:dao.lease/lapsed` fact"** but does not name the
   epoch datom's form. Should 7.7.2 or 7.7.8 publish
   `{:yin.k/custody :yin.k/reclaimed :yin.k/occurrence O
   :dao.lease/lease L :yin.k/epoch e}`? It should also say that at
   exhaustion the fact is written with the unchanged epoch.
2. **UCF 7.7.2 says lease facts keep "dao.lease.md's required keys,
   unchanged"**, and 7.7.8 says "nothing here adds a key to the lease
   vocabulary". The authority's refusal adds `:yin.k/proposer`. Is a
   UCF-namespaced key on the grantor's own ledger copy of a lease fact
   allowed, or should the proposer ride in a separate custody fact in
   the same transaction (which needs a writer protocol change)?
3. **Plan 1.5 step 5 says ":seen from every recorded grant, release
   and lapse"**, but no release is recorded before C8. Confirm that C8
   adds releases to `rebuild-judge`.
4. **UCF 7.11.1 clause 9 says "its grant activates and its effects
   commit"** at epoch 2^52-1. Admission is C7, so C6 shows only the
   grant and a valid binding at the bound, not a committed effect.
   C7 should add the effect half.
5. **UCF 7.7.8 Restart: "A grantor that cannot recover the epoch
   grants nothing for that occurrence."** Reopen refuses the whole
   open when any reclaim fails (`:unreclaimed`), not only that one
   occurrence. Is the whole-authority refusal intended? Poison is
   whole-authority (plan 1.4), so per-occurrence service would need a
   different failure model.
6. **Losing-candidate refusals and later grants.** A loser refused
   while another holds the occurrence must re-propose with a fresh
   proposal id after the reclaim. UCF 7.8 lower step 5 says
   `:yin.k/not-holder` but not that the candidate re-proposes. D's
   driver needs that rule.
7. **The dao.lease doc amendment for the adapter contract (plan
   section 5)** is not written. The contract is stated in `grant.cljc`'s
   namespace docstring only. Which doc should carry it?
8. **Dropped proposals are lost to a live judge.** A proposal drained
   while its occurrence is not yet offered is never seen again by that
   judge (its cursor moved). Only a rebuilt judge re-reading from the
   oldest position sees it again. Should the composition wire
   proposal cursors from the oldest position at reopen? The tests do.
