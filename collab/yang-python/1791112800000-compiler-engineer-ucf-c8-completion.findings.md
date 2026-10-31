Completed-Local: 2026-10-05 02:45 +07
Amended-Local: 2026-10-05 +07 (rebase onto master and the architect's
rulings; see the last section, which supersedes the halted-successor,
unknown-predecessor and quarantine statements above)
Coding-Agent: claude (opus-5-5)

# C8, completion: findings

**Summary: every C8 scope item is done test-first, in a new namespace
`yin.vm.ucf.authority.completion` and two new test namespaces. JVM
focused (189 tests, 1444 assertions) and Node over every UCF namespace
(256 tests, 1934 assertions) are green, and kondo is clean. All 22
mutations I ran turned the suite red. I could not run cljstyle (the
permission gate blocked it, as in C5 and C6), Dart, or the whole Node
suite to its end.**

## Changed files

New:
- `src/cljc/yin/vm/ucf/authority/completion.cljc`
  - constructors `resumed`, `completed`, `succeeded`;
  - `report!`, the resumed report;
  - `closure`, the facts that join the release lapse's transaction;
  - `offer-refusal`, the successor-offer precondition.
- `test/yin/vm/ucf/authority/completion_test.cljc`: 16 tests, through
  the authority and the judge.
- `test/yin/vm/ucf/authority/completion_fold_test.cljc`: 5 tests, the
  fold arms directly.

Shared files. Every edit is additive; line numbers are after the edit.

`src/cljc/yin/vm/ucf/ledger.cljc`:
- 35-43: the ns docstring gains an "and of slice C8" block, after
  C6's kinds and before "The projection is plain data".
- 106-111: `attribute-order` gains three kinds after
  `:dao.lease/rejected`, under the comment `;; slice C8, completion`.
  Line 106 is C6's `:yin.k/proposer]` line, which lost its closing
  `})`.
  - `:yin.k/resumed [custody occurrence lease result successor]`
  - `:yin.k/completed [custody occurrence lease]`
  - `:yin.k/succeeded [custody occurrence successor]`
- 268: `grant-defect` gains one clause, `(:yin.k/closed known)
  :closed-occurrence`, after `:exhausted-occurrence`.
- 349-419: a new section, "Slice C8: completion", placed after
  `grant-terms` and before `fold-fact`. It holds the public
  `successor-seen?` and the private `report-defect`, `closure-defect`
  and `edge-defect`.
- 529-556: three arms at the end of `fold-fact`'s `case` (resumed,
  completed, succeeded). Line 529 is the end of C6's rejected arm,
  which lost one closing paren.
- 583-586 in `fold-record*`: a post-record `:closure-without-edge`
  check after C6's `:lapse-without-epoch` check, and `::unedged` added
  to the `dissoc`.

`src/cljc/yin/vm/ucf/authority/grant.cljc`:
- 38-43: one ns docstring paragraph.
- 47: requires `completion` (completion requires no grant, so there is
  no cycle).
- 70-71 and 88, `offer-decision`: a `succession` binding, and one cond
  clause before `:else`.
- 174, `grant-decision`: `(:yin.k/closed known)` in the invalid `or`.
- 201-205, `lapse-decision`: the facts are now `(-> [f] (into
  (completion/closure p f)) (conj (custody/reclaimed ...)))`.
- 302, the answer hook: `(:yin.k/closed known)` in the refusal `or`.
- 384-386 and 396-398, `rebuild-judge`: docstring, and
  `:dao.lease/released` joins `:seen` for a lapse of cause `:release`
  (C6 Q3).

No other file is touched. `custody.cljc` and `authority.cljc` are
unchanged.

## Design

1. **The report.** `report! a author report bytes`:
   - `report` is the holder's `{:yin.k/custody :yin.k/resumed
     :yin.k/occurrence O :dao.lease/lease L :yin.k/result address}`
     (UCF 7.7.2).
   - The author must be L's holder, L live and on O.
   - The bytes go through `checkpoint/inspect`, which checks the
     address, version 1 and the root role.
   - The successor must then:
     - be a continuation;
     - have an origin naming exactly O and L;
     - name the ledger's arbitration identity;
     - be an occurrence the ledger has never seen (neither offered
       nor any closure's successor);
     - have `:yin.k/next-op-seq` not below O's baseline.
   - The authority records the report plus `:yin.k/successor S`, the
     occurrence it verified, in one transaction. The bytes are not
     stored.
   - An equal report replays. Another successor for the same lease is
     `:report-conflict`.
2. **Completion.** In the judge's writer, a `:release` lapse of a live,
   reported lease becomes one transaction, in this order:
   - the lapse;
   - `completed O L`;
   - `succeeded O S`;
   - the epoch change.

   The writer does this only when the reclaim does not exhaust (the
   lease's epoch is below the bound) and S is still unseen. Otherwise
   it records the plain `[lapse, epoch change]`.
3. **The fold fails closed.** Each defect a fold arm can answer:
   - **report:** `:malformed-fact`, `:unknown-lease`,
     `:wrong-occurrence`, `:duplicate-report`, `:ended-lease`,
     `:seen-occurrence`.
   - **closure:** `:malformed-fact`, `:unknown-lease`,
     `:wrong-occurrence`, `:unreleased` (no `:release` lapse earlier
     in the same record, still awaiting its epoch change),
     `:unreported`, `:exhausted-occurrence`, `:closed-occurrence`.
   - **edge:** `:malformed-fact`, `:unpaired-edge` (no closure earlier
     in the record, or a second edge), `:successor-mismatch`,
     `:seen-occurrence`.
   - **post-record:** `:closure-without-edge`.

   A grant on a closed occurrence is `:closed-occurrence`.
4. **Projection.** The keys of the top-level projection are unchanged.
   - A lease entry gains `:yin.k/result` and `:yin.k/successor` once
     its report is recorded.
   - An occurrence gains `:yin.k/closed {:dao.lease/lease L
     :yin.k/successor S}` once closed.
5. **Acyclic, one-to-one chain.**
   - **No cycle:** an edge's successor is never an occurrence the
     ledger has seen. Every ancestor was offered, so an edge can never
     point back.
   - **One edge per predecessor:** an occurrence closes once, and a
     closed occurrence has no lease and is never granted.
   - **One predecessor per successor:** the target of an edge counts
     as seen.
6. **Successor offers.** For a body whose origin names an occurrence P
   in this ledger, the offer is:
   - `:awaiting-completion` while the origin's lease is P's live lease;
   - `:orphan` unless P is closed under that lease with this body's
     occurrence as its successor, at the reported address.

   A refused offer is never offered, so it is never grantable. Once the
   recorded successor is admitted, later variants with an equal
   baseline join it through the C5 rule.

## Red before, green after

| Test | Red (before) | Green |
|---|---|---|
| both new namespaces, 21 tests written first | against a stub (constructors only; `report!` refuses everything, `closure` answers `[]`, `offer-refusal` nil): 19 of 21 red, 74 failures, 24 errors (the fold tests error with `:unknown-kind`) | yes, first run |
| `a-release-without-a-report-reoffers` | green against the stub: it pins C6's plain reclaim, which C8 must keep | yes |
| `a-successor-of-a-predecessor-this-ledger-never-saw-is-a-first-offer` | green against the stub: it pins C5 behavior (Q3) | yes |
| `an-orphan-is-never-grantable`, "a stale branch that reused the successor's occurrence" | written after the code, to kill the `offer-ignores-lease` mutation, which no test covered; it fails under that mutation | yes |

Mutations. Each was applied by a script, run against the completion,
completion-fold, ledger, reclaim and grant tests, and reverted. All 22
went red:

| Mutation | Result |
|---|---|
| `closure` never completes | 34 failures |
| `closure` completes on any cause | 20 failures, 10 errors |
| `closure` ignores exhaustion | 5 (`completion-is-blocked-at-exhaustion`) |
| `closure` ignores a seen successor | 3 (one-predecessor-per-successor) |
| writer puts the closure after the epoch change | 29 failures, 11 errors |
| writer records the closure without its edge | 32 failures, 11 errors |
| fold skips the `:closure-without-edge` check | 1 |
| fold accepts a closure outside its lapse's record | 2 |
| fold grants on a closed occurrence | 1 |
| fold skips `:successor-mismatch` | 1 |
| fold edge skips the seen check | 2 |
| offer skips the successor precondition | 24 |
| offer ignores the reported address | 2 |
| offer ignores the closing lease | 2 (killed only after the test above was added) |
| report skips the counter check | 2 |
| report skips the origin check | 8 |
| report skips the seen check | 12 (cycle test included) |
| report skips the holder check | 3 |
| report skips the arbitration check | 6 |
| writer grants a closed occurrence | 13 failures, 1 error |
| hook grants a closed occurrence | 1 |
| `rebuild-judge` without `:released` | 1 |

## Brief items, where each is tested

- **Release with resumed evidence and a verified successor:**
  `a-release-with-a-verified-successor-completes` checks the exact
  four facts of the one transaction, the closure, and "never grants
  again". The judge path, where the holder's `:released` arrives on its
  own medium, is
  `the-holders-release-completes-through-the-judge`.
- **Otherwise a plain reclaim:** `a-release-without-a-report-reoffers`
  (regrant at epoch 1), and `only-a-release-completes` for `:policy`,
  `:silence` and `:cap`.
- **Successor checks:**
  - in `the-report-verifies-its-successor`: wrong origin occurrence or
    lease, foreign arbitration, a seen occurrence, halted, another
    author, another occurrence's lease, an unknown lease, bytes that
    do not match the address, an origin naming the successor itself,
    a malformed report, replay, `:report-conflict` (double successor)
    and `:ended-lease`. Every refusal writes nothing.
  - `the-successor-continues-the-counter`: 2 below 3 is refused, 3
    after 3 is admitted.
- **Acyclicity:** `a-cycle-is-refused` (a successor naming its
  ancestor; one naming itself), and `the-fold-keeps-the-chain-acyclic`.
- **One edge per predecessor, one predecessor per successor:**
  `one-successor-per-predecessor-and-one-predecessor-per-successor`,
  and the fold's `:unpaired-edge` on a second edge.
- **Offer after completion; orphans:**
  `a-successor-offer-waits-for-its-predecessors-completion`, and
  `an-orphan-is-never-grantable`. The second covers a reclaimed
  predecessor, a predecessor regranted into another successor, a stale
  branch that reused the id, and an unreported variant. Each orphan's
  grant answers invalid-value.
- **Exhaustion:** `completion-is-blocked-at-exhaustion`. With
  `::max-epoch 1`, a grant at the bound and a recorded report, the
  release records `[lapse, epoch unchanged]`; the occurrence is
  exhausted and not closed, no successor is eligible and nothing is
  granted. `the-fold-refuses-completion-at-exhaustion` covers the
  real 2^52-1.
- **Crash cuts.** Each case reopens through `grant/reopen!` and asserts
  exactly one lapse of lease-1:
  - *after the successor append* (nothing reported) and *after the
    resumed report* (committed, or cut before-frame, after-frame or
    torn, with 0/1/0 reports persisted): before closure no successor
    is eligible, and the last checkpoint is regranted at epoch 1;
  - *after the release append*: the holder's release is not yet
    recorded, and the same before-closure result holds. A cut on the
    completion transaction persists 0/1/0. With 1 the closure holds:
    nothing is reclaimed, the occurrence never grants and the
    successor is eligible once. Closure and edge persist together or
    not at all;
  - *after closure*: the same after-closure assertions, and a second
    reopen replays.

## Results

- **JVM focused:** `clojure -M:test` with `-n` for the two new
  namespaces, `reclaim-test`, `ledger-test`, `grant-test`,
  `authority-test`, `custody-test`, `checkpoint-test`,
  `dao.stream.journal-test`, `dao.stream.journal.file-test` and
  `dao.lease-test`: 189 tests, 1444 assertions, 0 failures, 0 errors.
  The two new namespaces take 10 s wall, JVM start included, so
  nothing needs `^:slow`.
- **Node:** the whole suite did not finish inside my 590 s limit,
  twice. Both runs were stopped mid-suite (at `yang.python...` and
  `yin.repl.dht-test`) with no failure printed before the stop. The
  other two engineers were probably running lanes at the same time.
  - `node target/node-tests.js --test=...` was blocked by the
    permission gate.
  - So I ran `clj -M:cljs -m shadow.cljs.devtools.cli compile test
    --config-merge '{:ns-regexp "yin[.]vm[.]ucf.*-test|dao[.]lease-test|dao[.]stream[.]journal.*-test"}'`.
    That build-level form, the same one bb.edn uses, worked. Result:
    256 tests, 1934 assertions, 0 failures, 0 errors, no warnings,
    and every `yin.vm.ucf.*` namespace printed `Testing <ns>`, both
    new ones included.
  - That build left a subset `target/node-tests.js`; the next full
    compile overwrites it.
- **kondo** over the five touched files: 0 errors, 0 warnings.
- **ASCII only**, and no line over 80 columns (awk checked).
- **The C3 cross-host digest fixture** (in `authority-test`) still
  passes, so the bytes of the existing kinds have not moved.
- **Not run:**
  - `cljstyle fix` / `check` (blocked): please run both on the five
    files.
  - Dart and the full lanes: yours.
- **Scratch files**, under the ignored `target/c8mut/`: the mutation
  script and the Node logs.

## Deviations and smallest choices

1. **A third fact kind, `:yin.k/resumed`.** The brief named two kinds,
   closure and edge, but the report has to survive between the report
   and the release (the crash cut "after the resumed report" is a
   journal cut). UCF 7.7.2 already names this kind as the holder's
   fact. The ledger keeps an authority-authored copy with one more
   key, `:yin.k/successor`.
2. **Verification happens at report time.** At release, completion
   re-checks only the cause, the live lease, exhaustion and "still
   unseen". The successor's bytes are not stored; the reported address
   pins the first eligible offer.
3. **The transaction order is lapse, closure, edge, epoch change.** In
   this order the fold can prove that the closure shares its lapse's
   record, by finding the lease still awaiting its epoch change,
   without editing C6's lapse or epoch arms.
4. **Completion still records the epoch change.** UCF 7.7.8 says every
   reclaim raises the epoch, and C6's fold requires the pair. A closed
   occurrence therefore ends at e+1.
5. **A report at the epoch bound is recorded.** It is evidence; the
   block is at completion, where UCF 7.7.8 puts it ("accepts no
   completion"). I read "epoch at 2^52-1 after a reclaim" as a lease
   bound at the bound, whose release lapse exhausts.
6. **The successor-offer rule is enforced by the authority's decision,
   not re-checked by the fold's offer arm.** The fold trusts its own
   journal, as the C5 gate ruled (observation 7). Adding the check
   there would need a second edit to C5's `offer-defect`.
7. **Two predecessors may each record a report naming one successor.**
   Only the first completion records the edge; the second release is a
   plain reclaim. The authority cannot know at report time which
   release will come first.
8. **`report!` takes the author as an argument,** the composition's
   attribution, which is C11's resolver.

## Open questions

1. **Halted successors.** I refuse them (`:not-a-continuation`), so a
   computation that halts can never complete. Its occurrence returns
   to offered and could be regranted and run again. UCF 7.7.6 says the
   successor is "a `:yin.k/continuation` or a `:yin.k/result`", but
   the brief's checks (occurrence, arbitration, counter) exist only on
   a continuation. Should a halted result close the occurrence with an
   edge to its address and no successor occurrence?
2. **The extra keys on the authority's copies.** `:yin.k/successor` on
   the recorded `:resumed`, and the closure and edge kinds themselves.
   This is the same question as C6's Q2. UCF 7.7.2 needs the
   authority-authored facts (plan section 5 already lists "completed").
3. **A predecessor this ledger never saw.** A body whose origin names
   such an occurrence is admitted as a first offer. That is today's C5
   behavior, and the C5/C6 tests offer the `successor` fixture this
   way. It is no weaker than a first park, which anyone may offer, but
   it gets no ancestry (no edge). Should it be refused as an orphan?
   That would mean moving those tests to the `first-park` fixture.
4. **The reason for a successor variant at an unreported address.**
   Before the recorded successor is admitted, such a variant is
   refused as `:orphan`. Is a separate reason wanted?
5. **`successor-seen?` scans every occurrence.** It is derived, not
   indexed, so each report, completion and edge costs O(occurrences).
   This adds to plan risk 5 (cost) for C12.
6. **The plan 1.5 step 5 wording.** `rebuild-judge` now puts
   `:dao.lease/released` in `:seen` for a lapse of cause `:release`.
   The ledger records no holder release fact, only the lapse's cause.
   Confirm that this is what step 5 means.
7. **C9 ancestry** can read `:yin.k/closed` (lease and successor) from
   the projection: membership through edges, as plan C9 says, with
   nothing indexed. I built nothing for C9.


## Rebase and ruling amendments

The slice was rebased onto master (C6's refusal pair, C7 admission and
C10 input records). I resolved the conflicts by editing the files, then
applied the four rulings of
`collab/1791117600000-architect-c8-completion-rulings.claude-fable-5-1.findings.md`.
I ran no git command that changes state. `ledger.cljc` and `grant.cljc`
still show as unmerged in `git status`, waiting for your `git add`.

### Conflict resolutions

`src/cljc/yin/vm/ucf/ledger.cljc`, 7 hunks:
1. **ns docstring kinds list.** Master's C6 lines (rejected, with the
   lease fact unchanged, and `:yin.k/refused`), C7 (`fenced`,
   `quarantined`) and C10 (`input`) are kept. My C8 block follows
   them, reworded for ruling 1 (terminal edge, `:yin.k/result` on the
   closure). I dropped my side's old C6 line ("a refusal, naming its
   `:yin.k/proposer`"), which master replaced.
2. **`attribute-order`.** Every entry is kept. Master's
   `:dao.lease/rejected [status proposal]` wins over my side's old copy
   with `:yin.k/proposer`. The C8 entries come last, after C10's
   `:yin.k/input`, and `:yin.k/succeeded` is now `[custody occurrence
   successor result]` (ruling 1).
3. **`grant-defect`.** Both clauses are kept, in this order: exhausted,
   `:quarantined-occurrence` (master), then `:closed-occurrence` (C8).
4. **Defect functions.** Master's C7 `op-id?`, `fenced-defect` and
   `quarantine-defect` and C10's `input-defect` are kept unchanged. My
   C8 section follows them, with the ruling 1 delta applied:
   - `report-defect`: the successor is optional; `:duplicate-report`
     keys on `:yin.k/result`.
   - `closure-defect`: `:unreported` keys on `:yin.k/result`. It also
     refuses `:quarantined-occurrence`, a fail-closed twin of ruling 4
     (my addition).
   - `edge-defect`: requires exactly one of successor and result, and
     checks the matching result.
5. **The end of the `fold-fact` case.** Master's quarantine and input
   arms are kept. The C8 arms (resumed, completed, succeeded) are
   appended after the input arm. I dropped my side's copy of the old
   C6 rejected arm, which master replaced with the rejected/refused
   pair. The succeeded arm now merges whichever of `:yin.k/successor`
   and `:yin.k/result` the edge carries into `:yin.k/closed`.
6. **Post-record checks in `fold-record*`.** Both are kept: master's
   `:rejection-without-refusal`, then C8's `:closure-without-edge`.
   The `dissoc` covers all four transient keys (`::unbound`,
   `::unepoched`, `::unrefused`, `::unedged`).

`src/cljc/yin/vm/ucf/authority/grant.cljc`, 3 hunks:
1. **`grant-decision`'s invalid `or`.** Both `(:yin.k/quarantined
   known)` (master) and `(:yin.k/closed known)` (C8) are kept.
2. **The answer hook's refusal `or`.** Both are kept, the same way.
3. **The `rebuild-judge` docstring.** Merged: my "grant, release and
   lapse" sentence, plus master's sentences on the refused fact's
   proposer and on discarded queued grants. The code's `:released`
   clause had merged cleanly.

Everything else merged cleanly. The lapse-decision closure threading
sits beside master's refusal pair and `:yin.k/reclaimed-leases`.
I left master's refusal and reopen code untouched.

My own tests needed two updates for master's shapes:
- `after-closure` reads `:yin.k/reclaimed-leases`;
- the judge test expects the refusal pair `[(lease/refusal "p-b")]`
  plus `[(custody/refused "holder-a" "p-b")]`.

Before the rulings, the merged tree ran 236 tests with only these 3
failures, so the C6, C7 and C10 behavior came through intact.

### Rulings applied

1. **A halted result completes the occurrence.**
   - `successor-defect` checks only the origin on a halted body, and
     `:not-a-continuation` is gone.
   - The recorded report has no `:yin.k/successor` for a result.
   - `closure` completes on `:yin.k/result`, emitting `succeeded` for
     a continuation or the new `terminated` constructor (the terminal
     edge, `{:yin.k/custody :yin.k/succeeded :yin.k/occurrence o
     :yin.k/result address}`) for a result. The seen-successor test
     applies only to a continuation.
   - The ledger delta is the one described under resolution 4 and 5.
   - The docstrings are updated.
2. **The recorded `:yin.k/resumed`** is kept as built.
3. **A predecessor this ledger never saw: `:orphan`.** `offer-refusal`
   now passes only bodies with no origin. Moved tests:
   - The `offer!`/`offered` helpers in `reclaim_test`, `admission_test`,
     `input_test` and `grant_test` now offer `first-park` (same
     occurrence, no origin, so the same t and entity ids).
   - `grant_test` keeps the `successor` fixture only where its baseline
     matters. The two variant tests use a new `succeeded` helper: it
     offers the fixture's predecessor (`first-park` re-keyed to
     `fx/predecessor`), grants lease-7, reports the successor's bytes,
     releases, then offers the successor. The equal-variant test now
     counts offers of `occ` only (2), since the predecessor's offer is
     a third.
   - `an-offer-stores-...`, `a-duplicate-offer-...` and
     `an-unwritable-store-...` use `first-park`.
   - `a-known-occurrence-with-another-origin-is-refused` is unchanged.
     It now offers the origin-bearing successor against a
     `first-park`-offered occurrence, and the known-occurrence check
     still runs first.
   - `a-successor-of-a-predecessor-this-ledger-never-saw-is-a-first-offer`
     is inverted to `...-is-an-orphan`. It writes nothing, and it
     checks that a body with no origin is still a first offer.
4. **Quarantine blocks completion.** `closure` returns no facts for a
   quarantined occurrence, so the release is a plain reclaim.
   `report!` refuses `:quarantined`, after the lease checks and before
   the successor checks. The fold also refuses a closure of a
   quarantined occurrence. My tests quarantine through the C3 seam
   (`seam/commit-effect!` and then the quarantine fact via
   `transition!`), as an intent conflict would. They do not go through
   `admission/admit!`.

The rulings on questions 4, 5 and 6 need no code. Question 6
(`:released` at rebuild) was already built and is confirmed.

### Red before, green after (new tests)

| Test | Red (before) | Green |
|---|---|---|
| `a-halted-result-completes-the-occurrence` (rulings tests 1, 2, 4) | 7 failures | yes |
| `a-halted-body-must-name-this-occurrence-and-lease` (test 3) | 2 | yes |
| `a-second-report-of-another-kind-conflicts` (test 8) | 2 | yes |
| `a-halted-completion-is-blocked-at-exhaustion` (test 5) | 1 | yes |
| `a-cut-on-the-halted-completion` (test 6) | 2 | yes |
| `a-quarantined-occurrence-cannot-complete` | 3 | yes |
| `a-report-on-a-quarantined-occurrence-is-refused` | 2 | yes |
| `a-successor-of-a-predecessor-this-ledger-never-saw-is-an-orphan` (inverted) | 3 | yes |
| `the-fold-holds-a-halted-completion` (test 7, plus the halted report arm) | **green before**: I applied the ledger delta while resolving the conflicts, before this test existed. The mutations below fail it | yes |
| `the-fold-refuses-to-complete-a-quarantined-occurrence` | **green before**, for the same reason; a mutation fails it | yes |
| `the-report-verifies-its-successor` | the halted case is removed (test 9) | yes |

For the red run, the only change was a `terminated` constructor stub,
so the tests could compile: 30 tests, 22 failures, 0 errors.

Mutations of the new code, each applied by `target/c8mut/run2.py` and
reverted. All 11 went red:

| Mutation | Result |
|---|---|
| halted body takes the continuation checks | 12 failures |
| `report!` ignores quarantine | 2 |
| halted report records a successor | 16 failures, 2 errors |
| `closure` needs a successor | 7 |
| `closure` ignores quarantine | 3 |
| offer admits an unknown predecessor | 3 |
| fold `:duplicate-report` keyed on successor | 1 |
| fold `:unreported` keyed on successor | 13 failures, 1 error |
| fold closure ignores quarantine | 1 |
| fold edge allows both targets | 1 |
| fold terminal edge ignores a reported successor | 1 |

### Results

- **JVM focused**, every `yin.vm.ucf.*` and `dao.stream.journal*`
  namespace (`yin.vm.ucf-test`, ledger, remote, authority, handoff,
  checkpoint, custody, and authority.admission, completion,
  completion-fold, input, reclaim and grant, plus `journal-test` and
  `journal.file-test`): 245 tests, 1872 assertions, 0 failures,
  0 errors. The C6 lease-fact and refusal tests and the C7 outcome
  tests all pass.
- **kondo** over the nine touched files: 0 errors, 0 warnings.
- **ASCII only**, and no line over 80 columns in touched files.
- **No float literal** in the new tests, and no JVM-only test was
  added.
- **Not run:**
  - cljstyle: the permission gate blocked `cljstyle check` again.
    Please run fix and check on the nine files.
  - Node and Dart lanes: yours, as instructed.

### Not resolved, or for you

- `git add` of `ledger.cljc` and `grant.cljc` and the rebase
  continue: yours.
- UCF 7.7.2 needs the "as recorded by the authority" table from ruling
  2 (resumed with its optional successor, completed, and succeeded in
  its two forms). It is not written, since the brief forbids UCF edits.
- `successor-seen?` is still an O(occurrences) scan; it is a cost note
  for C12 (ruling on question 5).
- The fold's `:quarantined-occurrence` refusal on a closure goes beyond
  the ruling's text. It is the fail-closed twin of the writer rule.
  Drop it if the fold should trust the writer here.
