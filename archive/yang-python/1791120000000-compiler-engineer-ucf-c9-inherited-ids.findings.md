Completed-GMT: 2026-10-05 05:24:32 GMT
Completed-Local: 2026-10-05 12:24:32 +0700

# C9: inherited-id scope (admission step 4)

**Summary: C9 is implemented test-first. Inherited ids are admitted only
when the id's occurrence is an ancestor through completion edges and the
granted checkpoint carries the id. An unreadable checkpoint answers
`:suspended` after tenure and writes nothing. `:stale` now carries
`:yin.k/closed true`. The completion replay coverage test is added. The
JVM focused lanes are green, kondo is clean, and all 11 mutations were
caught. cljstyle could not run because the permission gate blocked it.**

No git writes. Nothing under collab/ was touched apart from this report.
Node and Dart lanes were not run, as the brief asked.

## Files

| File | Change |
|---|---|
| `src/cljc/yin/vm/ucf/authority/admission.cljc` | scope step for inherited ids; `ancestor?`, `member?` (public); `admit!` takes `store`; `:yin.k/closed true` on `:stale` |
| `test/yin/vm/ucf/authority/inherited_test.cljc` | new, 18 deftests, portable, memory backend |
| `test/yin/vm/ucf/authority/admission_test.cljc` | follows the new `admit!` signature (the world holds its content store) |
| `test/yin/vm/ucf/authority/completion_test.cljc` | the C8 gate's coverage test (finding 2) |

## What was built

- **`admit!` signature.** It is now `(admit! a store i author envelope
  diagnostics)`. `store` is the dao.jing byte store that `grant/offer!`
  puts accepted bodies in. It is read only for an inherited id. This
  follows `offer!`'s precedent: the authority value holds no store. No
  compatibility arity was kept.
- **Scope, step 4.** If the id names the bound occurrence, it is in
  scope, with no lookup and no store read. Otherwise:
  1. `ancestor?` over the projection. A non-ancestor is
     `:foreign-op-id`.
  2. The accepted checkpoint is read from the store and verified.
     Failure answers `:suspended`.
  3. `member?` over its baseline. A non-member is `:foreign-op-id`.

  The step still runs after tenure and before dedup. The scope verdict
  is a `delay`, so the store is never read for an envelope decided
  earlier (unenrolled, suspended, defective binding, stale).
- **`ancestor? [p a o]`.** It walks back from o through
  `:yin.k/closed :yin.k/successor` edges. A terminal edge
  (`:yin.k/result`, no successor) is no one's predecessor, so no
  ancestry runs through it. An orphan has no edge naming it.
  O(chain length x occurrences); the C12 cost note is in the docstring.
  The walk is bounded by the occurrence count only as a guard, because
  the fold already keeps the chain acyclic. I did not reuse C8's
  `successor-seen?`: it answers a yes/no for one edge, and ancestry
  needs the predecessor itself.
- **`member? [b op]`.** True when the op id is a key of the baseline's
  `:yin.k/ops`, the C4 inspector's map, which includes install children
  and grandchildren. Ids compare by `cbor/content-key`, the same way
  the dedup namespace keys them.
- **Reading the accepted checkpoint (`accepted-baseline`).** It reads
  the occurrence's offered variants (`:yin.k/variants`, the ledger's
  accepted record; never anything from the envelope) in a fixed `str`
  order. For each, it gets the bytes, runs `checkpoint/inspect`, and
  requires the result to equal the recorded baseline. These all count
  as unreadable: bytes missing, bytes that fail the hash or inspection,
  a store that throws or is closed, and a nil store. Membership then
  uses that verified baseline.
- **Dedup.** No new code. An inherited id reaches the same
  `:admitted`/`content-key` arm as a current one. These cases are now
  tested: replay after regrant, replay by a successor, an intent
  conflict that quarantines the *current* occurrence, and a cross-target
  conflict.
- **Variants.** C5's `offer-decision` already refuses any baseline that
  differs (`not=` over the whole baseline, children included). So I
  added only the missing tests: a changed child payload, a changed
  grandchild payload, and the root counter, on an install-bearing body.
  All three answer `:variant-conflict` and store and commit nothing.
- **C8 gate follow-up 1.** `:stale` on a closed occurrence carries
  `:yin.k/closed true`; this is the 7.11.1 clause 8 fixture. It is
  asserted for a successor-closed occurrence and a halt-closed one. An
  open occurrence's `:stale` does not carry the key.
- **C8 gate follow-up 2.** After the `:policy` lapse, the same
  `succ-1` report answers `{:yin.k/status :replayed}`, and the frames
  are unchanged.

## Tests, red before and green after

I first added the `store` argument with no behavior change, so the new
tests could not fail on arity. Then I added the tests, with nil stubs
for `ancestor?` and `member?`. **Red run: 18 tests, 66 failing
assertions.** Green after implementing: 18 tests, 112 assertions, 0
failures.

| Test (inherited_test unless noted) | Before | After |
|---|---|---|
| the-world-is-a-chain (fixture guard) | green | green |
| a-current-id-needs-no-checkpoint (empty store) | green (C7) | green |
| an-inherited-member-of-an-ancestor-is-admitted (exact record facts, replay) | RED (`:foreign-op-id`) | green |
| an-inherited-id-the-checkpoint-never-carried-is-refused | green (C7 failed closed) | green |
| a-member-of-a-non-ancestor-is-refused (never seen; terminal edge) | green (C7 failed closed) | green |
| ancestry-runs-through-a-chain-of-two (R to S1 to S2; membership is S2's) | RED | green |
| an-unreadable-checkpoint-suspends-and-changes-nothing (missing, forged bytes, closed store, nil; frames, projection and diagnostics unchanged) | RED | green |
| stale-wins-over-an-unreadable-checkpoint (reclaimed; regranted) | partly RED (current holder's `:suspended`) | green |
| clause-4-membership-and-ancestry (never carried, carried, child and grandchild, orphan, plain-reclaim orphan) | RED | green |
| ancestry-is-derived-from-the-completion-edges (pure `ancestor?`) | RED | green |
| membership-is-the-baseline-carried-ops (pure `member?`) | RED | green |
| clause-8-closed-occurrence-and-closed-ancestor | RED (no `:yin.k/closed`) | green |
| a-cut-inherited-admission-commits-zero-or-one-times (3 cuts, retry converges) | RED | green |
| an-inherited-id-replays-after-reopen-and-regrant | RED | green |
| a-successor-meets-the-one-namespace (replay, conflict quarantines S2 not S1, quarantine before scope) | RED | green |
| an-inherited-id-to-a-second-target-conflicts | RED | green |
| a-variant-changing-a-child-intent-is-refused | green (C5 already refuses) | green |
| any-accepted-variant-is-the-checkpoint | RED | green |
| completion_test/the-report-verifies-its-successor (new replay-after-end assertions) | green (coverage) | green |

**Mutations.** Each was applied, run against its namespace, and
reverted by the script. `git diff --stat` afterwards showed only the
intended changes.

| Mutation | Caught by |
|---|---|
| M1 no ancestry check | a-member-of-a-non-ancestor (4) |
| M2 no membership check | never-carried, chain-of-two, clause-4 (5) |
| M3 immediate predecessor only | chain-of-two, derived-edges, successor namespace (10) |
| M4 any closed occurrence counts as a predecessor (ancestry through a result) | 13 tests (59) |
| M5 unreadable answers foreign instead of suspended | unreadable, stale-wins (5) |
| M6 checkpoint bytes not verified | unreadable (forged bytes) (2) |
| M7 no `:yin.k/closed` on stale | clause-8 (2) |
| M8 scope before tenure | clause-8, stale-wins (3) |
| M9 an occurrence is its own ancestor | derived-edges (1) |
| M10 membership by occurrence only | never-carried, chain-of-two, clause-4, membership (6) |
| M11 completion replay check moved after `:ended-lease` | completion_test the-report-verifies-its-successor (1) |

## Verification

- **JVM, foreground.** Ran `clojure -M:test` with `-n` for these 16
  namespaces: yin.vm.ucf-test, ucf.ledger, ucf.remote, ucf.authority,
  ucf.handoff, ucf.checkpoint, ucf.custody, dao.stream.journal,
  dao.stream.journal.file, and authority.admission, .inherited,
  .completion, .input, .reclaim, .grant, .completion-fold. **263 tests,
  1986 assertions, 0 failures, 0 errors, 19 s wall.** No test is near
  5 s, so none is tagged `^:slow`.
- **Kondo.** `clj -M:kondo --lint` on the 4 touched files: 0 errors,
  0 warnings.
- **ASCII and width.** No non-ASCII bytes, and no line over 80 columns,
  in the 4 touched files.
- **cljstyle: BLOCKED.** The harness permission gate refused both
  `cljstyle fix` and `cljstyle check` on the touched files ("requires
  approval"). Please run it before the commit. I matched the
  surrounding layout by hand.
- **Node and Dart: not run**, per the brief. Portability reasoning:
  - No float literals.
  - No JVM-only form; the new ns has no reader conditionals besides the
    codebase's catch clause, `#?(:cljd Object :clj Throwable :cljs
    :default)`, with :cljd first.
  - No file writes, `spit`, or fixture-corpus names.
  - Map/set iteration feeds only order-independent booleans, or the
    unique predecessor, or a `sort-by str` over keyword addresses.
  - The baseline equality compares two `cbor/decode`-derived values on
    the same host.
  - Calling a nil store's `:get-bytes-fn` throws on every host
    (NPE / TypeError / Dart Error), and the catch-all catches it.
  - Install names are symbols, as in the C4 fixtures.
  - `delay` is already used by test code that runs on Dart.

## Open questions (smallest choice taken; UCF text not edited)

1. **Order inside step 4: ancestry before checkpoint.** I check
   ancestry, which needs only the projection, before reading the
   checkpoint. So a non-ancestor's id is `:foreign-op-id` even when the
   checkpoint is unreadable. Reasoning: the verdict does not depend on
   the missing evidence, and 7.7.8 says "unavailable evidence is not
   evidence of an invalid id", not that it hides an invalid one. The
   other reading is that any non-current id suspends when the
   checkpoint is unreadable. That is a one-line swap.
2. **The `store` argument on `admit!`.** I put it beside `offer!`'s,
   rather than holding it in the authority value. C11's front will need
   to pass it. Should `authority/open!` hold the content store instead,
   since plan 1.1 treats it as part of the authority's durability duty?
3. **Which variant is "the accepted checkpoint".** I take any offered
   variant whose bytes verify to the recorded baseline. C5 makes all
   variants of an occurrence share one baseline, so they are
   interchangeable for membership. A missing first variant does not
   suspend while another verifies (any-accepted-variant-is-the-checkpoint).
   UCF says "the body the authority admitted for the granted
   occurrence" in the singular. Should a specific variant be required?
4. **"An unavailable comparison suspends variant admission".** This is
   not built. The comparison uses the recorded baseline in the
   projection, which is always available to an open authority. A
   poisoned or closed authority already answers `:suspended`. Is
   anything more meant?
5. **Doc debt this slice adds**, for the section 5 pass:
   - UCF 7.7.8 step 4: the ancestry-first order (Q1), and that a nil,
     closed or throwing store counts as unreadable.
   - 7.9: the `:stale` `:yin.k/closed true` key is now implemented
     (C8 gate doc debt item 5 is discharged in code).
   - Plan C9 / risk 5: the O(chain length x occurrences) `ancestor?`
     cost beside the `successor-seen?` scan.
