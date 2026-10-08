Completed-Local: 2026-10-05 +07
Coding-Agent: claude (opus-5-5)

# C7, admission and result delivery: findings

**Summary: every C7 scope item is done test-first. The JVM focused run
over every `yin.vm.ucf.*` and `dao.stream.journal*` namespace is green
(193 tests, 1472 assertions), and kondo is clean. I did NOT run the
Node or Dart lanes. My one Node attempt passed the 600 s foreground
cap, was moved to the background and died with the session, so it
produced no result. I could not run cljstyle either: the permission
gate blocked the binary under both its bare and full path, as in C5
and C6.**

## Changed files

New:
- `src/cljc/yin/vm/ucf/authority/admission.cljc`: `admit!` (the one
  admission entry point), `outcome-reader` (the outcome projection),
  and the closed sets `admissions` and `defects`. Also `outcomes-type`
  and `max-seq`.
- `test/yin/vm/ucf/authority/admission_test.cljc`: 23 tests, one of
  them JVM-only.

Shared files (all edits additive; nothing reordered or reformatted).
Line numbers are post-edit, in this worktree:
- `src/cljc/yin/vm/ucf/ledger.cljc`
  - 35-39: ns docstring, the two C7 kinds listed.
  - 61-64: ns docstring, the C7 projection keys.
  - 106-110: `attribute-order`, two new kinds at the END of the map
    under `;; slice C7`:
    - `:yin.k/fenced` `[custody op-id incarnation epoch]`
    - `:yin.k/quarantined` `[custody occurrence op-id]`
  - 267: one line inserted in `grant-defect`:
    `(:yin.k/quarantined known) :quarantined-occurrence`.
  - 348-393: a self-contained block before `fold-fact`, headed
    `;; Slice C7`: public `op-id?`, private `fenced-defect` and
    `quarantine-defect`.
  - 505-523: two fold arms appended at the END of `fold-fact`'s
    `case`, after the `:dao.lease/rejected` arm. The only change to
    the old arm is its closing parens (`))))` became `))`).
- `src/cljc/yin/vm/ucf/authority/grant.cljc`
  - 166: one line in `grant-decision`'s `or` (the writer refuses a
    grant on a quarantined occurrence).
  - 293: one line in the `answer` hook's refusal `or`.
  - 242-245 and 274-276: docstrings of `writer` and `answer` name
    quarantine.
- `src/cljc/yin/vm/ucf/authority.cljc` 30-31: ns docstring only (the
  only append is now `admit!`).
- `src/cljc/yin/vm/ucf/authority/seam.cljc` 6-9: ns docstring only
  (it said there is no `admit!`). The seam code is unchanged and stays
  a seam.

I did not change `empty-projection`. `:outcomes` appears only after the
first fenced admission (`fnil conj []`), so no existing test that
compares whole projections changed.

## Design

`(admit! a i author envelope diagnostics)`. `i` is the enrolled target
the envelope arrived at. `author` is the composition's resolved
attribution (nil when there is none). `diagnostics` is a plain
DaoStream writer.

1. **Well-formedness, before any state read.** These are pure checks:
   - all five keys present;
   - dispatch `:yin.k/fenced-v1`;
   - a non-nil incarnation;
   - an exact epoch;
   - an op id of exactly two keys, a UUID occurrence, and an exact
     seq of at most 2^52-2;
   - an intent that canonically encodes.

   Any failure gives a `:malformed` diagnostic, and the authority is
   not touched.
2. **One `authority/transition!`** runs the pure decision. The checks
   run in order, and the first to fail decides:

   | Check | Fails when | Answer |
   |---|---|---|
   | (unenrolled) | the boundary is not enrolled | `{::unenrolled i}`: no outcome, no diagnostic |
   | authority | the bound occurrence is exhausted or quarantined | `:suspended` |
   | binding | no binding for L | `:unbound-lease` diagnostic |
   | binding | the author is not L's holder (nil included) | `:wrong-author` diagnostic |
   | tenure | L is not the occurrence's active lease, or the epoch differs | `:stale`, decided before dedup |
   | scope | the op id's occurrence is not the bound occurrence | `:foreign-op-id` diagnostic |
   | dedup | a record with equal intent | `:replayed` |
   | dedup | a record with other intent | `:intent-conflict`; commits `:yin.k/quarantined` |
   | (none) | no record | `:committed` |

   A `:committed` admission is one transaction:
   `[admitted (C3 shape) fenced]`. A closed target records the closed
   result, as the seam does.
3. **Any non-admission status from `transition!` becomes
   `:suspended`.** That covers a poisoned or closed authority and a
   transaction past a bound. So `:yin.k/status` never leaks into an
   answer, and every answer is an admission, a diagnostic, or
   `::unenrolled`.
4. **The diagnostic is appended after the lock is released.** The
   answer is `{::diagnostic d ::appended r}`, where `r` is the stream's
   own result. A throw becomes transport-error data. The diagnostic
   carries neither `:yin.k/admission` nor `:yin.k/status`. Its
   `:yin.k/claimed` nests whichever of incarnation, epoch and op id
   were present. The payload is never echoed.
5. **The outcome projection.** The `:yin.k/fenced` fold arm stamps the
   `:admitted` entry with its incarnation and appends the op id to
   `:outcomes`.
   - `outcome-reader` serves `{:yin.k/admission :committed op-id
     incarnation effect-result}` at dense positions in ledger t order.
   - Its identity is `"<arb>/outcomes"`, and cursors are
     `{::outcomes id ::position n}`.
   - It has no writer and is not closable. It answers blocked at the
     tail and never ends.
   - On a poisoned or closed authority it answers transport-error.
   - The direct `:committed` reply equals the projected one
     (`:dao.space/t` is stripped).

## Red before, green after

| Test(s) | Red (before) | Green |
|---|---|---|
| all 21 first-written tests | against a stub `admission` ns (`admit!` delegating to the seam, `admissions #{}`, no reader): 125 failures, 8 errors; every test red | yes, first implementation |
| `the-admission-arms-fail-closed` (written after the arms) | not red-first; covered by mutations L and R below | yes |
| `the-hook-refuses-a-quarantined-occurrence` (written after the hook line) | red at first through a missing tick wire (a test bug, fixed); its real red is mutation N | yes |

Mutations, each applied with Edit, run, and reverted. A `cmp` against
a backup confirmed every file was restored. I ran them with Edit
because a scripted `bash run.sh` driver was blocked by the gate.

| # | Mutation | Result |
|---|---|---|
| A | authority step removed (no exhausted/quarantined check) | 7 failures, 3 errors |
| B | a record beats tenure (stale checked only without a record) | 7 failures (`stale-wins-over-a-record` 4) |
| C | author check removed | 14 failures |
| D | scope check removed | 3 failures |
| E | intent conflict commits no quarantine | 15 failures |
| F | commit without the `:yin.k/fenced` fact | 14 failures |
| G | diagnostic not appended | 38 failures |
| H | a throwing diagnostic append propagates | 1 error |
| I | outcome reader ignores poison | 2 failures |
| J | per-target dedup (a record of another target ignored) | 3 failures (cross-target test) |
| K | decision made on a projection read outside the lock | 15 failures (JVM serialization test) |
| L | ledger `grant-defect` quarantine line removed | 1 failure |
| M | writer quarantine line removed | 2 failures. Without it the grant frame is persisted, and then the fold refuses it and poisons, so the writer guard is required and not just defense in depth |
| N | hook quarantine line removed | 1 failure (the new hook test) |
| O | tenure ignores the epoch | 7 failures, 1 error |
| R | fold accepts a second fenced stamp | 1 failure |
| S | seq bound raised to 2^52-1 | 3 failures |

No mutation survived.

## Results

- **JVM focused, final tree:** one foreground `clojure -M:test` with
  `-n` for these 12 namespaces:
  - `yin.vm.ucf-test`, `yin.vm.ucf.authority-test`,
    `yin.vm.ucf.checkpoint-test`, `yin.vm.ucf.custody-test`,
    `yin.vm.ucf.handoff-test`, `yin.vm.ucf.ledger-test`,
    `yin.vm.ucf.remote-test`;
  - `yin.vm.ucf.authority.admission-test`, `.grant-test`,
    `.reclaim-test`;
  - `dao.stream.journal-test`, `dao.stream.journal.file-test`.

  Result: 193 tests, 1472 assertions, 0 failures, 0 errors. An earlier
  run over the authority set plus `dao.lease-test` gave 191 tests, 0
  failures. No test needs `^:slow`.
- **Node: NOT RUN by me.** The full-suite shadow compile went past the
  harness's 600 s foreground cap and died with the session, with no
  counts. Node and Dart are yours on the finished tree. Check that
  `Testing yin.vm.ucf.authority.admission-test` appears in the Node
  output. The admission test uses only `.cljc` code: the
  serialization test is in `#?(:clj ...)`, and the payload outside the
  canonical domain is `(atom 1)`.
- **Dart: NOT RUN.**
- **kondo** over the six touched files: 0 errors, 0 warnings.
- **ASCII and width:** ASCII only, no line over 80 columns (awk
  checked).
- **C3 cross-host digest fixture:** still passes, so the bytes of the
  existing kinds did not move.
- **Not run:**
  - `cljstyle fix` / `check`: the gate blocked it. Please run both on
    the six files:
    - `src/cljc/yin/vm/ucf/authority/admission.cljc`
    - `src/cljc/yin/vm/ucf/ledger.cljc`
    - `src/cljc/yin/vm/ucf/authority/grant.cljc`
    - `src/cljc/yin/vm/ucf/authority/seam.cljc`
    - `src/cljc/yin/vm/ucf/authority.cljc`
    - `test/yin/vm/ucf/authority/admission_test.cljc`
  - Node, Dart, and the full lanes: yours.
  - One note for Dart: the `#?(:clj ...)` serialization deftest
    follows C6's pattern (`reclaim-test` uses the same form). If the
    CLJD host-eval trap bites, change it to `#?(:cljd nil :clj ...)`.

## Smallest choices and deviations

1. **The two intents are digests, not vectors.** On `:intent-conflict`,
   `:yin.k/recorded-intent` and `:yin.k/observed-intent` are the C3
   canonical intent digests (BLAKE3 of the canonical bytes of
   `[:yin.k/append target value]`). UCF 7.9 says "each an intent
   vector". The C3 ledger records only the digest, and a closed-target
   record drops the value, so the recorded vector cannot always be
   rebuilt. Equality of digests is equality of canonical bytes.
2. **The incarnation lives in a new fact, `:yin.k/fenced`.** I did not
   add `:yin.k/incarnation` to C3's `:yin.k/admitted`. Each admission
   commits `[admitted fenced]`, and the fenced fact also records the
   epoch for after-the-fact audit. This keeps the C3 kind, its arm and
   its fixture bytes untouched, per the concurrency notice.
   - The fold checks the shape, the pairing with a recorded and
     unfenced op, a bound lease, and the epoch against the binding.
   - It does not check that the admitted fact is in the same record:
     the C3 arm keeps no t. A `fold-record*` post-check like C5's
     `::unbound` would add that, but it edits shared code. I left it
     out; see Q2.
3. **Inherited ids fail closed as `:foreign-op-id`.** Until C9, any op
   id whose occurrence is not the bound occurrence is refused. C9
   replaces that branch with membership and ancestry.
4. **The seq bound.** A seq of 2^52-1 is `:malformed` (UCF: "the
   largest assignable sequence is 2^52-2"). A current-occurrence seq
   below the checkpoint's `next-op-seq` is not refused; see Q4.
5. **Unenrolled boundary.** `{::unenrolled i}` is not an outcome, and
   no diagnostic is appended (UCF 7.7.8: such a boundary "produces
   neither outcomes nor these diagnostics").
6. **A closed authority value answers `:suspended`, like a poisoned
   one.** The plan says an unreachable authority yields no outcome.
   A closed in-process value is reachable but cannot decide.
7. **`:yin.k/arbitration` on `:suspended`.** It is
   `{:dao.stream/identity arb}`. The authority knows its journal
   identity but not the body's descriptor.
8. **`:stale` never carries `:yin.k/closed`.** No closure exists before
   C8.
9. **One outcome projection per authority**, not one per target or per
   holder. A driver filters on the `[op-id incarnation]` pair.
10. **Quarantine blocks grants in three places:**
    - the writer (`invalid-value`);
    - the hook (refuses the proposal);
    - the fold (`:quarantined-occurrence`).

    UCF says only "never regranted automatically". A reclaim of a
    quarantined occurrence's lease is still allowed.
11. **After a crash and `grant/reopen!`, a retry is `:stale`.** Reopen
    reclaims every live tenure, so a retry with the old envelope
    cannot replay. A committed result is recovered only from the
    outcome projection (tested). A retry that finds the recorded
    result (`:replayed`) is shown over a plain `authority/open!`.

## Coverage against the brief

- One fixture per check, each failing alone: `each-check-fails-alone`.
  - authority: poisoned, exhausted, quarantined;
  - binding: unbound lease, other author, no attribution;
  - tenure: stale epoch, stale lease after regrant, reclaimed with no
    active lease;
  - scope: foreign occurrence;
  - dedup: replay, conflict.

  `the-first-failing-check-decides` covers the adjacent pairs.
- Stale wins over a record: `stale-wins-over-a-record`.
- Five outcomes closed: `the-five-outcomes-are-closed`. A scripted run
  reaches all five, and a combinatorial run (24 envelopes x 4 authors
  x 2 phases) yields only members of `admissions`, each with op id
  and incarnation, and never `:yin.k/status` or `:dao.space/t`.
- Intent conflict quarantines, and later admissions answer
  `:suspended` (UCF 7.7.8 step 1), including a stale one. Other tests
  for this case:
  - reopen keeps the quarantine;
  - no regrant through the writer or the hook;
  - an unauthenticated conflict quarantines nothing.
- Cross-target conflict through the one namespace:
  `a-second-target-conflicts-through-the-one-namespace`.
- Diagnostics:
  - 17 malformed forms each commit nothing and append exactly one
    diagnostic;
  - the exact diagnostic shape;
  - an envelope-shaped payload is payload;
  - a closed ring or a throwing writer is returned as data and admits
    nothing.
- Outcome projection: identity, surfaces, blocked tail, only committed
  outcomes in ledger order, stable cursors across reopen and canonical
  CBOR, transport-error when poisoned.
- Redelivery after reopen: `a-committed-result-is-redelivered-after-reopen`
  (cut after frame, `grant/reopen!`, kept cursor).
- Crash cuts: `a-cut-admission-commits-zero-or-one-times`. The three
  cuts give 0, 1 and 0 commits, and each retry gives `:committed`,
  `:replayed` and `:committed`: always exactly one record in total.
- Bounds:
  - `a-transition-past-a-bound-suspends`;
  - `an-epoch-at-the-bound-admits-until-exhausted`, the effect half
    of 7.11.1 clause 9 that C6's Q4 left to C7.
- Serialization (JVM): `admission-and-reclaim-serialize`.
  - reclaim holding the lock, then the admission is `:stale`;
  - admission holding the lock, then it commits before the lapse;
  - 20 races, each commit-before-reclaim or refusal-after, never both.

## Open questions

1. **Intent vectors (choice 1).** Should the ledger record the intent
   vector (or the value on closed-target records too), so
   `:intent-conflict` can carry vectors as UCF 7.9 says? That changes
   C3's admitted bytes and its digest fixture.
2. **The same-record pairing of `:yin.k/fenced`.** Should a
   `fold-record*` post-check enforce it, like `::unbound`? That is a
   shared-code edit for the merge.
3. **A closed authority value (choice 6).** `:suspended`, or no
   outcome (an error value) as for an unreachable one?
4. **Current-occurrence ids below `next-op-seq`.** UCF 7.7.8 step 4
   asks only that a current id "names that occurrence". Should a seq
   below the granted checkpoint's counter also be `:foreign-op-id`?
5. **`:yin.k/arbitration` on `:suspended` (choice 7).** Identity only,
   or the body's full `{identity descriptor}`, which the authority does
   not hold?
6. **Outcome projection granularity (choice 9).** One stream per
   authority, or per target? UCF 7.9 says "the composition stream the
   holder's driver reads". C11's per-holder reply stream may want a
   per-holder view.
7. **Plan and UCF text to write:**
   - the `:yin.k/fenced` and `:yin.k/quarantined` facts (UCF 7.7.2
     authority-authored facts);
   - the outcome projection identity `"<arb>/outcomes"` (UCF 7.9 and
     plan 1.10);
   - "after reopen, recovery of a committed result is by the outcome
     projection, since reopen reclaims" (choice 11).
