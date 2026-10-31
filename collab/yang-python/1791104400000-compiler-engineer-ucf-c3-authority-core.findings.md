Completed-GMT: 2026-10-04
Coding-Agent: claude (opus-5-5)

# C3, authority core: findings

**Summary:** the authority core is in place: ledger fold, `transition!`,
whole-authority poison, bounds, enrollment, the target reader and the seam.
JVM focused and Node focused runs are green. cljstyle could not run
(permission gate), Dart was not run, and no git writes were made.

## Changed files

New:
- `src/cljc/yin/vm/ucf/ledger.cljc`: a pure fold that answers data and
  never throws. Holds the published `attribute-order`, `facts->datoms`,
  `target-identity`, `intent`, `max-exact`, and `fold-record`, which
  answers a projection or `{::defect d}`.
- `src/cljc/yin/vm/ucf/authority.cljc`: `open!`, `close!`, `projection`,
  `transition!`, `enroll!`, `close-target!`, `target-reader` (deftype
  `TargetReader`, reader surface only) and `target-type`.
- `src/cljc/yin/vm/ucf/authority/seam.cljc`: `commit-effect!`, documented
  as a substrate test seam and not an admission entry point. No `admit!`.
- `test/yin/vm/ucf/ledger_test.cljc` (3 tests)
- `test/yin/vm/ucf/authority_test.cljc` (8 tests)

Docs:
- `docs/design/dao.space.transactor.md`: one sentence added to T18 (a
  journal handle declares the same complete retention). One sentence added
  at the end of *Where durability lives*. New subsection *The arbitration
  ledger exception* with the 1.1 text verbatim, plus the checkpoint-content
  paragraph. No other text changed.
- `docs/design/yin.vm.linker.dht.md` 14.2.1: the 1.9 wording, verbatim,
  after "This is an admission guarantee, not a promise that unprotected
  external IO becomes transactional." This was the obvious spot.

Nothing under `src/cljc/dao/stream/journal/` was touched; only C1's memory
backend is used. No `yin.vm` engine namespace is required, and no
cbor-fixtures corpus is referenced.

## Shape

- **The ledger is a journal.** The authority is a `dao.space.transactor`
  over a `dao.stream.journal` handle. Record p is
  `{:dao.space/transaction {:t p :datoms [[e a v p 1] ...]}}`, so each
  decision is one `transact!` and one frame. The fold reads committed
  records from a kept tail cursor on the journal handle, so the live
  projection is built from the decoded, persisted values exactly as a
  reopen builds it.
- **The order inside `transition!`.** It runs under one lock: closed,
  then poisoned, then `decide` on the projection, then bounds, then one
  `transact!`, then fold the next journal record, then reply.
  - A non-ok or throwing transact poisons with `:uncertain-append`.
  - A failed read-back or fold poisons with `:uninstalled`.
  - Poison makes every transition answer `{:yin.k/status :suspended
    :yin.k/reason :poisoned}`. `projection` answers nil, and the reader's
    `cursor` and `next` answer `:dao.stream/transport-error`.
- **Fact kinds and attribute order:**
  - `:yin.k/enrolled`: custody, target, effect-kinds.
  - `:yin.k/target-closed`: custody, target.
  - `:yin.k/admitted`: custody, target, op-id, intent, value, result.
    `value` is present only when the result is ok.

  Entity ids start at `dao.datom/first-user-id` (16). Each next id is
  one above the largest seen.
- **Fold checks:** t equals position, every datom is an assertion stamped
  with that t, entity ids are fresh, attributes follow the published
  order, an enrolled target equals `target-identity(arb, t)`, op ids are
  never duplicated, and the result matches the target's state (open or
  closed). Unknown kinds fail closed.

## Tests: red before, green after

**Deviation:** I wrote the implementation before the tests, not
test-first. The red evidence below was gathered afterwards.

- **`the-same-decisions-make-the-same-bytes`:** red on the first JVM run,
  with the pin empty (`[]` against six digests). Green once pinned, and
  green on Node independently with the same JVM-computed digests: Node
  makes byte-identical frames.
- **Mutation 1, the transition lock removed on the JVM:**
  `two-decisions-cannot-both-act-on-one-projection` went red, with 3
  FAILs and 1 ERROR (two decisions minted from one projection, and the
  fold rejected the record).
- **Mutation 2, the poisoned check removed from `transition!`:**
  `crash-at-each-cut-commits-zero-or-one` went red, with 3 FAILs, one per
  cut.
- **Mutation 3, `open!`'s transactor-t check removed:** nothing went red.
  The fold already refuses t different from position, so that check
  repeats the fold's check and cannot be observed separately (question 9).
- **Mutation 4, the seam's target-position bound removed:** nothing went
  red. The check could never fire, so I deleted it (question 8).
- **Not shown red:** `open-enroll-and-reopen`, `a-retry-finds-the-recorded-result`,
  `reopen-refuses-a-gap-and-a-t-mismatch`,
  `a-transition-past-a-bound-commits-nothing`,
  `the-target-reader-contract`, and the three ledger tests. They have been
  green since the first run.

What each authority test covers:
- **`open-enroll-and-reopen`:** identity, a target derived from arb and t,
  one frame per decision, close, a reopen that folds to an equal
  projection, and t continuing after reopen.
- **`a-retry-finds-the-recorded-result`:** committed, replayed (also after
  reopen), intent-conflict, an unknown target refused, and one record per
  op id.
- **`crash-at-each-cut-commits-zero-or-one`:** covers C1's three cuts.
  The cut answers suspended. Then poison blocks the seam, `enroll!` and
  `close-target!`, writes nothing, and makes both reader operations
  answer transport-error. Reopen shows 0 or 1 commit, as the cut
  dictates. A retry answers committed or replayed, and the kept cursor
  reads again on the reopened authority.
- **`reopen-refuses-a-gap-and-a-t-mismatch`:** a record with t not equal
  to its position, a gap in t, a gap in journal positions and a malformed
  record are each refused, and nothing is written.
- **`a-transition-past-a-bound-commits-nothing`:** uses
  `::authority/max-exact 18`. The transition is suspended, no frame is
  written, the authority is not poisoned, non-committing replies still
  answer, and the bound holds after reopen.
- **`the-target-reader-contract`:**
  - descriptor, reader-only surfaces, and nil for an unknown target;
  - blocked at the tail, and equal values kept at separate positions;
  - cursor-mismatch across targets, and invalid-cursor past the tail;
  - cursors kept across close and reopen and through the canonical codec;
  - a close commits, and a second close is replayed;
  - a later admission records `{:dao.stream/outcome :dao.stream/closed}`;
  - end after the close, including on a further reopen.
- **`two-decisions-cannot-both-act-on-one-projection`:** on the JVM, 8
  `future`s enroll concurrently, giving t values 0 to 7 and 8 distinct
  targets. Then 8 futures commit the same op id: 1 committed, 7 replayed,
  and one record. Node and Dart run the same thunks serially. The
  `concurrently` docstring documents that single-isolate case.

## Runs

- **JVM focused:** `clojure -M:test -n yin.vm.ucf.ledger-test -n
  yin.vm.ucf.authority-test -n dao.space.transactor-test -n
  dao.stream.journal-test` ran 39 tests with 285 assertions: 0 failures,
  0 errors.
- **Node focused:** `clj -M:cljs -m shadow.cljs.devtools.cli compile test
  --config-merge '{:ns-regexp "^yin\\.vm\\.ucf\\.(ledger|authority)-test$"}'`
  ran 11 tests with 109 assertions: 0 failures, 0 errors.
- **Node, accidentally full:** an earlier attempt nested the merge under
  `:builds`, so the regexp did not apply and the whole Node lane ran: 2805
  tests, 92393 assertions, 0 failures, 0 errors (about 10 min). That run
  used the code before the final edits: the seam bound removal, the
  docstrings and the pins. It also wrote `target/c3-node-tests.js`.
- **kondo:** 0 errors, 0 warnings on all five files.
- **ASCII and 80 columns:** checked by grep on all new files and the
  added doc lines.
- **Not run:** cljstyle (`fix` and `check` were both refused by the
  session's permission gate; please run them), the Dart lane, and the
  full JVM and Node lanes.

## Deviations

1. The implementation came before the tests (see above).
2. cljstyle was not run.
3. The seam has no target-position bound check of its own. Each effect's
   entity id exceeds its target position, and each transaction's ids
   exceed its t, since ids start at 16 and grow by at least one per
   transaction. So `transition!`'s entity-id bound implies the other two.
   The `ledger/max-exact` docstring says so. `transition!` still checks t
   explicitly.

## Open questions (smallest choice taken)

1. **Arbitration identity is the journal identity.** The persisted header
   identity serves as the ledger's stream identity. A target identity is
   the string `"<arb>/target/<t>"`. Is a structured value wanted instead?
2. **Fact names and shape.** The names are `:yin.k/target-closed` and
   `:yin.k/admitted`. One admitted entity holds the effect, its result and
   its dedup record together, and the payload is omitted when the result
   is closed. Should those be separate facts?
3. **The intake pool.** `transactor/create!` requires a non-empty intake
   pool, so the authority supplies a fresh memory log that nobody reads.
   C3 does not publish. Should the composition pass the pool through
   `open!` opts?
4. **`authority/close!`.** After close, transitions answer
   `{:yin.k/status :closed}`, and readers answer transport-error, because
   a closed value no longer serves its projection as authoritative. Should
   readers keep serving instead, as a memory log does after close?
5. **Every non-ok transact answer poisons, `invalid-value` included.**
   Nothing is written in that case, but the seam cannot reach it: computing
   the intent encodes the payload first, so a non-encodable payload makes
   `commit-effect!` throw as an argument defect.
6. **Seam replies beyond committed and replayed.** The seam answers
   `:intent-conflict` and commits nothing, because quarantine belongs to
   C7. It answers `:refused` for an unknown target. A second
   `close-target!` answers `:replayed`, and one on an unknown target
   answers `:refused`.
7. **Untested path.** The `:uninstalled` poison, a failure after persist
   but before projection install, has no test. There is no seam to inject
   a read-back or fold failure without adding a test hook. Should C12 add
   one?
8. **One bound option.** The single `::authority/max-exact` option means
   the bound test exercises the entity-id bound only. The t and
   target-position bounds are implied by it, as deviation 3 explains.
9. **`open!` repeats a check.** Its comparison of the transactor's
   derived t with the record count repeats the fold's t-equals-position
   check. I kept it because plan 1.5 step 3 names it.
10. **The fold fails closed on unknown fact kinds.** C5 to C10 must each
    add their arm, including to `attribute-order`, before their facts can
    be reopened.
11. **The cross-host fixture.** It is a vector of per-frame BLAKE3
    digests pinned in the test source. The header has the fixed identity
    `"c3-fixture"` and is built from data. The digests were computed on
    the JVM and confirmed on Node. Dart is unverified until your lane
    runs.
