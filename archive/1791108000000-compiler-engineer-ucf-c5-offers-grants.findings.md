Completed-Local: 2026-10-04 +07
Coding-Agent: claude (opus-5-5)

# C5, offers, grants and bindings: findings

**Summary: C5 and both C3 follow-ups are done test-first. JVM focused
(74 tests, 579 assertions) and Node (whole suite, 2849 tests, 92686
assertions) are green. Kondo is clean. I could not run cljstyle (the
session's permission gate blocked it) or Dart.**

## Changed files

New:
- `src/cljc/yin/vm/ucf/custody.cljc`: the occurrence form
  (`mint-occurrence`, `occurrence?`, a lowercase UUID string), `exact?`,
  the constructors `offer`, `bound`, `subject` and `subject-occurrence`,
  and the pure `binding-evidence`.
- `src/cljc/yin/vm/ucf/authority/grant.cljc`: `offer!`, `writer` (the
  judge's `:writer` adapter), `ready?`, `judge-config`
  (`:writer :reclaim :answer`) and `step!` (a whole judge-step under the
  authority lock).
- `test/yin/vm/ucf/custody_test.cljc`
- `test/yin/vm/ucf/authority/grant_test.cljc`

Modified:
- `src/cljc/yin/vm/ucf/ledger.cljc`:
  - `fact-kind` dispatches on `:yin.k/custody` or `:dao.lease/status`.
    A fact carrying both, or neither, has no kind.
  - Authoring guard (follow-up b): `fact-datoms` / `facts->datoms`
    throw `{::defect :unknown-kind | :unpublished-attribute}` before
    any write.
  - Three new kinds in `attribute-order`: `:yin.k/offered`,
    `:dao.lease/accepted`, `:yin.k/bound`.
  - Fold arms for the three kinds, each failing closed, and a
    post-record `:unbound-grant` check.
  - Two new projection keys, `:occurrences` and `:leases`.
- `src/cljc/yin/vm/ucf/authority.cljc`: a public `locked`, and a
  `transition!` docstring line on the argument defect.
- `src/cljc/dao/stream/journal.cljc` (follow-up a): `memory-durability`
  `{:backend :memory :failure-model :none :lock-kind :none
  :persisted #{}}` (all keys under `:dao.stream.journal/`), carried by
  `memory-backend` under `::durability`. No C3 code reads the
  declaration (grep found none), so no reader needed adjusting.
- `docs/design/dao.stream.journal.md`: one sentence on the memory
  declaration.
- Tests: `test/dao/stream/journal_test.cljc`,
  `test/yin/vm/ucf/authority_test.cljc` and
  `test/yin/vm/ucf/ledger_test.cljc`.

## Red before, green after

Each test was written and run red before its code existed.

| Test | Red (before) | Green |
|---|---|---|
| `dao.stream.journal-test/the-memory-backend-declares-its-durability` | compile error, no `memory-durability` | yes |
| `ledger-test/authoring-refuses-a-fact-outside-the-published-order` | 4 failures: `facts->datoms` returned datoms, no defect | yes |
| `authority-test/a-fact-outside-the-published-order-is-an-argument-defect` | 3 failures: no throw, the extra attribute was silently dropped and a frame committed | yes |
| `custody-test/*` (4 tests) | namespace missing | yes |
| `ledger-test/the-fold-records-offers-grants-and-bindings` | 3 failures (no `:occurrences` or `:leases`) | yes |
| `ledger-test/the-fold-refuses-malformed-custody-facts` | 22 of 23 assertions failed. The both-dispatch-keys assertion already passed, through `fact-kind` from the guard step. | yes |
| `grant-test/*` (16 tests) | namespace missing | yes; one test bug fixed (the halted fixture needed no arbitration key) |

The custody and grant tests went red only because the namespace was
missing, which is weak evidence. So I also ran seven mutations against
the finished code, each reverted afterwards. Every one turned the
suite red:

| Mutation | Failures |
|---|---|
| writer drops the held-occurrence check | grant-test: 7 |
| offer stores the body before the admission decision | grant-test: 5 |
| writer commits the grant without its binding | grant-test: 18 and 1 error |
| evidence skips the duplicate-binding check | custody-test: 2 |
| evidence ignores the attributed author | custody-test: 2 |
| `exact?` accepts floats | custody-test: 1 |
| fold skips the unbound-grant check | ledger-test: 2 |

What the tests cover, against brief item 5:
- **Two candidates, one grant.** Through the judge with
  `step!`, and with 8 racing writer appends (JVM threads). Both give
  exactly one `:accepted` and one `:bound`.
- **The four negative evidence cases:**
  - another author: no evidence, and a forgery beside a valid
    binding changes nothing;
  - another record: `:not-in-grant-transaction`;
  - duplicated, in one record or across two: `:duplicate-binding`;
  - an inexact epoch: `:inexact-epoch` for a float 0, -1, 2^52,
    "0" and nil, while 2^52-1 passes.
  - Also covered: `:grant-mismatch` and `:malformed-record`.
- **Evidence survives:** the canonical CBOR round trip, and a
  `dao.stream.remote` reflection of the authority's journal over two
  ring buffers. The same records attributed to "mallory" prove
  nothing.
- **Offers:**
  - a duplicate is `:replayed` with zero writes, even from another
    carrier and after reopen;
  - an equal-baseline variant joins its occurrence;
  - `variant-different-intent` and `variant-counter` are refused
    with no write and no content stored;
  - a known occurrence with another origin is refused;
  - three uninspectable bodies commit nothing and store nothing;
  - a halted root, a foreign arbitration and an occurrence outside
    the form are refused;
  - a throwing store gives `:suspended :content-unavailable`: nothing
    is committed and nothing is poisoned.
- **Crash cuts on the grant**, at each of the three cuts:
  - the poisoned authority grants nothing;
  - reopen shows 0, 1 or 0 grants;
  - a retry after reopen commits the grant or replays it;
  - the binding is always in the grant's record.
- **Fold fails closed** on every new arm (23 assertions).
- **Readiness `:reclaim`:** true while open, false after close, and
  it writes nothing.
- **`judge-config`** refuses a zero duration at assembly.

## Results

- JVM focused: `clojure -M:test` over `-n yin.vm.ucf.authority.grant-test
  -n yin.vm.ucf.authority-test -n yin.vm.ucf.ledger-test
  -n yin.vm.ucf.custody-test -n dao.stream.journal-test
  -n dao.stream.journal.file-test -n yin.vm.ucf.checkpoint-test`:
  74 tests, 579 assertions, 0 failures, 0 errors.
- Node: `clj -M:cljs -m shadow.cljs.devtools.cli compile test`.
  Shadow ignored my `--config-merge :ns-regexp`, so the autorun covered
  the whole Node suite: 2849 tests, 92686 assertions, 0 failures,
  0 errors. Every touched namespace printed `Testing <ns>`.
- kondo over all ten touched `.cljc` files: 0 errors, 0 warnings.
- ASCII only, and no line over 80 columns in touched files (awk
  checked).
- **Not run:**
  - cljstyle fix/check: the bare `cljstyle` binary needs approval
    in this non-interactive session, and I could not get it. Please
    run `cljstyle fix` then `check` on the ten files.
  - Dart lane: yours.
  - Full JVM lane: yours.

## Deviations

1. **The store write happens under the authority lock, after a pure
   pre-decision.** `offer!` runs `decide` on the projection under
   `authority/locked`. It puts the body only when the decision would
   commit, then calls `transition!` (which re-enters the lock) with
   the same `decide`. So the body is in the store before the ledger
   references it, and a refused or replayed offer stores nothing. The
   brief asked only that an uninspectable body store nothing.
2. **The answer hook issues no refusals.** It grants the first drained
   proposal per offered, unheld occurrence. A losing candidate learns
   from the ledger, through `binding-evidence`, that another holder
   is bound. Refusals need a ledger fact kind that the brief did not
   list (see Q3).
3. **The writer rejects every fact other than a grant.** A lapse
   (C6), a refusal, or a grant with unpublished attributes answers
   `invalid-value`, so a due reclaim stays pending in the judge. A
   poisoned or closed authority answers `transport-error`. Replies
   carry only `:dao.stream/outcome`.
4. **No test proves that `step!` holds the lock across the whole
   pass.** Only the composition exists. A JVM-only blocking test could
   pin it.
5. **The offer fact adds `:yin.k/baseline`** to UCF 7.7.2's offer keys.
   The authority authors the fact, as plan section 5 anticipates
   ("admitted offer").

## Open questions

1. **Memory durability values.** `:failure-model :none`,
   `:lock-kind :none` and `:persisted #{}` are my smallest choices.
   Plan 1.8 enumerates only `:process-crash` and `:power-loss`.
2. **The occurrence form and C4.** The authority refuses an
   occurrence that is not a lowercase UUID (`:malformed-occurrence`),
   but `checkpoint/inspect` accepts any non-nil occurrence. Should the
   inspector check the form too, so a body that passes C4 is not
   refused at offer? UCF 7.2.1 ("the concrete form is fixed by M-next
   C") needs the form written in.
3. **Refusals in the ledger.** Plan 1.5 step 5 rebuilds `:answered`
   from "every recorded grant and refusal". C5 records no refusal, so
   C6 needs a refusal fact kind, or the plan text changes.
4. **What makes a grant replay a match.** A re-appended grant replays
   when its lease, occurrence and holder match the recorded one. The
   projection keeps neither duration nor proposal, so those are not
   compared. Is that enough?
5. **"The only binding for L" (UCF 7.7.8) for a partial reader.**
   `binding-evidence` checks uniqueness only over the records it was
   given. A partial view cannot prove that no other binding exists;
   the guarantee rests on the authority's fold refusing a second
   binding. The UCF sentence should say which view counts.
6. **The author model.** Evidence takes `[author record]` pairs and
   identifies the authority by its journal identity, which is the
   `:dao.stream/identity` of the body's `:yin.k/arbitration`. UCF 7.7.8
   says "the transactor of the space the body's `:yin.k/arbitration`
   names". Is identity equality the intended rule? The descriptor is
   never compared, in the evidence or in the offer's foreign-arbitration
   check.
7. **A different park with equal origin and baseline.** UCF 7.2.1 says
   "an offer of a known occurrence for a different park establishes
   nothing". The authority can only detect a different origin
   (`:occurrence-conflict`) or a different baseline
   (`:variant-conflict`). Equal content is indistinguishable from a
   snapshot variant and is admitted as one.
8. **The carrier is recorded once.** A duplicate offer from another
   medium replays and does not record the new carrier. 14.2.2 makes
   duplicate evidence idempotent by occurrence and address; confirm
   that the medium does not matter.
9. **Evidence checks the grant's shape only partly.** It checks the
   grant's lease, holder and subject, but does not run
   `lease/defective?` on it. Should a reader also gate the grant
   structurally?
10. **No cross-host byte fixture for the new kinds.** The C3 digest
    fixture still passes unchanged. Fixtures for the new kinds are
    left to C12.
11. **UCF 7.7.8, "an unavailable comparison suspends variant
    admission".** This cannot arise here, because the baseline lives
    in the projection. It becomes reachable only if C6 or C12 makes the
    projection lazy.
