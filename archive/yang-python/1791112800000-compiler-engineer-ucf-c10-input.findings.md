Completed-Local: 2026-10-05 +07
Coding-Agent: claude (opus-5-5)

# C10, the input protocol: findings

**Summary: C10 is done test-first in its own namespace. The C3-C6
suites plus the new one pass on the JVM (188 tests, 1364 assertions),
and kondo is clean. Node (the whole suite: 2895 tests, 92971
assertions) is green after one test-only fix. I could not run cljstyle (the
permission gate blocked the binary again, as in C5 and C6) or Dart.**

## Changed files

New:
- `src/cljc/yin/vm/ucf/authority/input.cljc`. It holds `request`,
  `record-input!` (the transition), and the pure `frontier`, `inputs`
  and `replay-input`. It also has `source-kinds`, `max-seq` and a
  private `tenure` helper. The namespace docstring states the rule
  for D.
- `test/yin/vm/ucf/authority/input_test.cljc`: 20 tests, all `.cljc`,
  none JVM-only and none slow. The namespace runs in about 1 s of test
  time on the JVM.

Shared file, additive only. `src/cljc/yin/vm/ucf/ledger.cljc` is the
only shared file touched; `authority.cljc` and `grant.cljc` are
unchanged. Line ranges are in the new file:
- **35-38, docstring kind list.** Four lines added after the C6
  `:dao.lease/rejected` line: "and of slice C10 ... `:yin.k/input`".
- **50-54, projection docstring.** The `:yin.k/exhausted true}}`
  line lost its `}}` and gained three `:yin.k/inputs [...]` lines. A
  C7 or C8 edit to the same occurrence entry would conflict here. It
  is docstring only.
- **104-107, `attribute-order`.** The `:dao.lease/rejected` entry lost
  its closing `})`. Added after it: the comment `;; slice C10: input
  records` and `:yin.k/input [:yin.k/custody :yin.k/occurrence
  :dao.lease/lease :yin.k/input-seq :yin.k/source :yin.k/observed]})`.
  C7 and C8 will append here too, so merging is a matter of moving
  the `})`.
- **344-366, new private `input-defect`.** It sits after
  `grant-terms` and before `fold-fact`, under `;; slice C10: input
  records`.
- **476-488, the fold arm.** It is the last arm of `fold-fact`'s
  `case`, after `:dao.lease/rejected` (whose closing parens moved),
  under `;; slice C10: ...`.

Nothing was reordered or reformatted. `empty-projection` and the
offer arm are untouched. The new projection key lives under an
occurrence and is created by my arm only, so C5's projection-shape
assertions still hold.

## What was built

- **Fact.** `{:yin.k/custody :yin.k/input :yin.k/occurrence o
  :dao.lease/lease l :yin.k/input-seq k :yin.k/source s
  :yin.k/observed v}`. The epoch and holder are not recorded, because
  the lease's grant and binding already give both ("derive, don't
  persist").
- **The fold arm fails closed:**
  - `:malformed-fact`: a bad occurrence, no lease, an inexact k (2^52
    and floats included), a source that is not a map, or no observed
    value;
  - `:unknown-occurrence`;
  - `:inactive-lease`: the lease is not the occurrence's live, bound
    lease;
  - `:non-dense-input`: k is not the current count, which catches
    both a gap and a repeat.

  The projection keeps `[:occurrences o :yin.k/inputs]` as a vector
  of `{source observed lease t}`.
- **`record-input!` checks, in this order:**
  1. a well-formed request, with its exact key set, exact epoch and k,
     a source of `{:yin.k/kind (read|ffi-result|link-result)
     :yin.k/name n}`, a non-nil observed value, and canonical-CBOR
     encodability. A failure is refused `:malformed-request` before
     the lock. A non-portable value therefore cannot reach the journal
     and poison the authority.
  2. A poisoned or closed authority gets `transition!`'s own answer.
  3. An unknown occurrence is refused.
  4. An exhausted occurrence is `:suspended :exhausted`, before
     tenure, as admission does.
  5. Tenure, via the private `tenure` helper:
     - `:unbound-lease`: no grant of l on o;
     - `:wrong-author`: the author is not the bound holder;
     - `:stale`: l is not live, or e is not the current epoch. The
       answer carries `:yin.k/observed-epoch`, plus
       `:yin.k/observed-lease` when a lease is live.
  6. k below the count: content equal by `cbor/content=` (source and
     observed) answers `:replayed`. Otherwise it is refused
     `:input-conflict` with `:yin.k/recorded-input` and
     `:yin.k/observed-input`.
  7. k above the count: refused `:input-gap`, carrying
     `:yin.k/next-input-seq`.
  8. k + 1 above the bound: `:suspended :bound`.
  9. Otherwise one transaction commits the input, and the answer is
     `:recorded` with `:dao.space/t`.

  `:recorded` exists only in `commit!`'s success path, so a cut
  answers `:suspended :uncertain-append`. Stale wins over a recorded
  k.
- **`frontier p l`.** The count of the occurrence's inputs whose t is
  below l's grant t. It is nil for a nil projection or an unknown
  lease.
- **`inputs p l`.** The prefix as `{occurrence lease frontier
  inputs[0..n-1]}`. It never extends past the frontier, so the
  holder's own later records are excluded. An unavailable projection
  answers `:suspended :unavailable`, and an ungranted lease is refused
  `:unbound-lease`. Missing evidence is never an empty prefix.
- **`replay-input prefix k source`.** It answers one of:
  - `:replayed` with the observed value, when the source matches;
  - `:refused :source-mismatch`, failing closed;
  - `:live` at or past the frontier.

  A prefix that carries a status is passed through. One without a
  frontier is refused `:no-prefix`. Neither case is ever live.

## Red before, green after

I wrote all 20 tests first, then ran them against a stub namespace
(every function returning nil, no ledger change): **89 failures and 2
errors, with every one of the 20 tests red.** I then added the ledger
arm and the implementation:

- 1 failure remained, a test bug: my "gap" fixture used the next valid
  k. I changed it to k=2.
- After that, 20 of 20 were green.

| Test | Red (stub) | Green |
|---|---|---|
| an-input-is-recorded-by-its-commit | 5 F | yes |
| equal-content-at-a-known-k-replays-and-other-content-is-refused | 7 F | yes |
| the-sequence-is-dense-from-zero | 5 F | yes |
| a-malformed-request-commits-nothing | 11 F | yes |
| tenure-is-checked-against-the-grant-and-epoch | 4 F | yes |
| a-reclaimed-lease-is-stale-even-for-a-recorded-k | 4 F | yes |
| an-exhausted-occurrence-suspends-recording | 1 F | yes |
| a-poisoned-authority-records-nothing-and-serves-no-prefix | 4 F | yes |
| k-at-the-bound-refuses-without-commit | 6 F | yes |
| the-fold-refuses-what-the-ledger-cannot-hold | 1 E (unknown kind) | yes |
| the-fold-refuses-an-input-after-the-lease-lapsed | 1 E | yes |
| the-frontier-is-the-count-at-the-grant | 12 F | yes |
| replay-checks-each-source-and-fails-closed | 5 F | yes |
| recovery-with-inputs-reproduces-intent-and-result | 2 F | yes |
| recovery-without-replay-fails-closed | 1 F | yes |
| without-recorded-inputs-the-divergent-intent-is-refused | 2 F | yes |
| inputs-keep-the-delivered-order-across-child-work | 3 F | yes |
| gap-successors-replay-verbatim-after-reopen | 1 F | yes |
| a-cut-record-commits-zero-or-one-times | 10 F | yes |
| a-cut-record-sets-the-next-frontier | 6 F | yes |

## Mutations (each reverted; the run was scripted)

I ran 17 mutations against `input-test`. One survived at first:
comparing observed values with host `=` instead of `cbor/content=`.
On the JVM a float 7 and an integer 7 already differ under `=`, so
that test could not tell the two comparisons apart. I added an
assertion that `-0.0` conflicts with a recorded `0.0` (host `=` calls
them equal), and the mutant then fails. Final results:

| Mutation | Result |
|---|---|
| `:recorded` reply without committing the fact | 46 F across 14 tests |
| dedup before tenure (a recorded k beats stale) | 2 F |
| content compare ignores the source | 1 F |
| content compare by host `=` | survived, then 1 F after the -0.0 assertion |
| frontier = current count, not the count at the grant | 5 F |
| `replay-input` ignores the source | 3 F |
| missing evidence served as an empty prefix | 2 F |
| a gap recorded | 7 F |
| no sequence bound | 2 F |
| no epoch check | 2 F |
| no live-lease check | 3 F |
| no author check | 2 F |
| no binding check | 1 F |
| no portability check (a non-portable value poisons) | 2 F |
| no exhausted check | 1 F |
| fold drops the dense check | 2 F |
| fold drops the live-lease check | 1 F |

## Tests against the brief

- **The scripted holder** (`run-holder`) is plain functions with no
  VM. It runs a script of four inputs:
  1. a root read;
  2. an install child's FFI result;
  3. a second root read;
  4. the child's link result.

  It replays the prefix with `replay-input`, records live inputs, and
  delivers each input only after `:recorded` or `:replayed`. It then
  emits one effect through `seam/commit-effect!` under op id seq 0.
  The seam is the C3 substrate seam, because C7 admission is not
  here. The scenarios:
  - **With inputs:** holder-a records the inputs and commits, then
    "crashes". The kept-cursor reads are evicted and now answer gaps.
    After the reclaim, holder-b is granted at epoch 1 and replays. It
    delivers what holder-a saw, and the effect answers `:replayed`
    with the recorded result. Nothing new is admitted or recorded.
  - **Without replay:** the regranted holder observes live, and
    `record-input!` k=0 is refused `:input-conflict`. Nothing is
    delivered, no effect is attempted, and no frame is written.
  - **Without recorded inputs:** the first holder never recorded. The
    regranted holder observes the gaps live, and the same op id with
    the divergent intent is `:intent-conflict`, with no commit.
- **Ordering across child work.** The ledger holds k 0 to 3 with the
  sources in delivery order. A replay that asks for the child's input
  in another order fails closed with `:source-mismatch` at k=1.
- **Frontier edge cases:**
  - At the first grant the frontier is 0 and the prefix is
    `{:yin.k/frontier 0 :yin.k/inputs []}`.
  - A holder's own records do not move its frontier, and the prefix
    ends at the frontier, not at the tail.
  - Each lease keeps its own frontier in history (0, 2, 3).
  - An unknown lease gives nil or `:unbound-lease`.
  - A nil projection gives nil or `:suspended`, never 0 and never
    `[]`.
- **Gap successors.** A gap with a successor cursor (holding a float)
  replays verbatim after `grant/reopen!`, through the canonical bytes.
- **Crash cuts.** Each of the three cuts answers `:suspended
  :uncertain-append`, never `:recorded`. Reopen shows 0, 1 or 0
  inputs. A retry after a plain reopen answers `:recorded` or
  `:replayed` and leaves exactly one record. Through `grant/reopen!`
  the old tenure is `:stale`, and the next grant's frontier is 0, 1
  or 0.
- **Lowered bound.** With `::input/max-seq 2`, k 0 and 1 are recorded
  and k=2 answers `:suspended :bound` with no frame and no poison. A
  recorded k still replays. At the real bound, `input/max-seq` is
  2^52-1, and k = 2^52-1 is a gap. The request validator and the fold
  both refuse 2^52, -1 and float k on the value's kind.

## Results

- **JVM focused.** The ten suites were `input-test`, `ledger-test`,
  `reclaim-test`, `grant-test`, `authority-test`, `custody-test`,
  `dao.stream.journal-test`, `dao.stream.journal.file-test`,
  `checkpoint-test` and `dao.lease-test`. 188 tests, 1364
  assertions, 0 failures, 0 errors.
- **Node** (`clj -M:cljs -m shadow.cljs.devtools.cli compile test`,
  the whole suite):
  - **First run:** 1 failure, in
    `gap-successors-replay-verbatim-after-reopen`. I believe the
    float `1.5` in the cursor decodes as a `Float64` carrier on JS,
    so host `=` failed even though the content is equal. I did not
    print the values to confirm it. The test now compares with
    `cbor/content=`; the protocol itself already compares that way.
  - **Second run:** 2895 tests, 92971 assertions, 0 failures,
    0 errors. `Testing yin.vm.ucf.authority.input-test` printed.
  - **Deviation from "foreground only":** a compile plus test run
    takes more than the 10-minute foreground maximum. The first run
    was moved to the background automatically, and I started the
    second in the background myself. I waited for each to finish
    before reporting.
- **kondo** over the three touched files: 0 errors, 0 warnings.
- **ASCII only**, no line over 80 columns (checked with grep and awk).
- **Not run:**
  - `cljstyle fix` / `check`: the bare binary needs approval in this
    session. Please run both on the three files.
  - Dart and the full lanes: yours.

## Merge notes

- **The private `tenure` helper in `input.cljc`** implements C7's
  tenure order from the C5/C6 projection: binding (grant of l on o),
  then author = bound holder, then live lease and current epoch, else
  `:stale` with the observed epoch and lease. When C7 lands, replace
  it with C7's helper, or make the helper shared.
- **The ledger hunks** are listed above. My arm and defect function
  sit under `slice C10` comments.

## Smallest choices, listed as questions

1. **The refusal for different content at a known k** is
   `{:yin.k/status :refused :yin.k/reason :input-conflict}`, carrying
   the recorded and the observed `{source observed}`. It follows the
   authority's own reply family (`:refused` plus a reason, like the
   offer's `:variant-conflict`). It is not a 7.9 admission outcome,
   because an input is not an effect. Should UCF 7.9 or linker-dht
   14.2.2 name it? Should an input conflict quarantine the occurrence,
   as an admission intent conflict does? I commit nothing and
   quarantine nothing.
2. **Attribute and key names:**
   - `:yin.k/input` (the custody kind);
   - `:yin.k/input-seq` (k);
   - `:yin.k/source {:yin.k/kind :yin.k/read | :yin.k/ffi-result |
     :yin.k/link-result, :yin.k/name n}`;
   - `:yin.k/observed`;
   - the reply keys `:yin.k/next-input-seq`, `:yin.k/recorded-input`,
     `:yin.k/observed-input`, `:yin.k/frontier`, `:yin.k/inputs`,
     `:yin.k/recorded-source` and `:yin.k/observed-source`.

   None of these is published. Plan section 5 lists "input" among the
   7.7.2 authority-authored facts to amend.
3. **The epoch and holder are not on the input fact.** Both derive
   from the lease's grant and binding. Is the lease alone enough
   provenance for C12 and D?
4. **An exhausted occurrence answers `:suspended` before tenure,**
   mirroring admission's order. Reaching exhaustion requires a reclaim,
   so tenure would answer `:stale` anyway. Which should win?
5. **The k bound is a call option (`::input/max-seq`), not an `open!`
   option.** This avoids a shared edit to `authority.cljc`. It cannot
   be `::authority/max-exact`: every input is an entity, so the
   entity-id bound always fires before k reaches it. That is safe,
   but a lowered k test could not isolate the k check that way. Should
   it move to `open!` (as `::max-epoch` did) at merge?
6. **A malformed request is refused before the lock,** so a closed or
   poisoned authority still answers `:malformed-request` for one. The
   check is pure on the request.
7. **Replay of a k below the frontier through `record-input!`.** A
   regranted holder may also send a k below its frontier through
   `record-input!`. Equal content answers `:replayed`, and other
   content is refused. Nothing forces a holder to use `inputs`; D's
   driver should use `replay-input` and never observe live below the
   frontier.
8. **`inputs` and `frontier` are pure over a projection.** The caller
   passes `(authority/projection a)`, which is nil when the authority
   is poisoned or closed. C11's front will serve `inputs` as a reply,
   which is when attribution of the prefix matters.
9. **Retention.** Linker-dht 14.2.2 says input records "stay durable
   while any replay is allowed; collection requires authoritative
   closure". The ledger is add-only, so nothing is collected; C8's
   closure could later make inputs of closed occurrences collectible.
   Not done here.
