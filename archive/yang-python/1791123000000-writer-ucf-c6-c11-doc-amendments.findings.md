Completed-Local: 2026-10-05 +07
Coding-Agent: claude (opus-5-5)

# Writer: UCF doc amendments forced by C6 to C11

Docs only. No code, test or collab file was edited other than this
report, and no git write was run. Every added line is ASCII. Prose is
at most 80 columns; only Markdown table rows are longer. Each claim
was checked against master 9352001f (`ledger.cljc`, `custody.cljc`,
`authority/grant.cljc`, `admission.cljc`, `completion.cljc`,
`input.cljc`). C11 was checked against
`/Users/sto/workspace/datomworld-c11front/.../authority/front.cljc`.

## Amendments, by file and line (line = first line of the addition)

`docs/design/yin.vm.universal-continuation-format.md`
- 1619, 7.7.2. **C6/C7/C10.** A table of the grantor's further facts,
  each in its `attribute-order` key order: `:yin.k/reclaimed`,
  `:yin.k/refused`, `:yin.k/fenced`, `:yin.k/quarantined` and
  `:yin.k/input`. It is followed by the transaction-pairing sentence.
- 1634, 7.7.2. **C8.** The "as recorded by the authority" table: the
  recorded `:yin.k/resumed` (with the added `:yin.k/successor`, for a
  continuation only), `:yin.k/completed`, and `:yin.k/succeeded` in
  both forms. Then the sentences "still evidence; completion is the
  closure", "names no author" and "between the lapse and its epoch
  change".
- 1856, 7.7.6. **C8.** A halted result completes through a terminal
  edge, no ancestry runs through a result, and the chain ends there.
- 1872, 7.7.7. **C6/C7.** The reopen steps in the executed order:
  1. open and fold;
  2. reclaim each live tenure (`:policy`, in grant order);
  3. rebuild the judge after the reclaims, including the C8
     parenthetical that a `:release` lapse is the release;
  4. wire the proposal media from oldest.

  Then: a reclaim that fails refuses the open, and reopen is retried;
  a pointer to dao.lease *Composition duties*; and the C7
  reopen-recovery sentence (`:stale` after reclaim, recovery from the
  outcome projection, and a plain open that replays).
- 2002, 7.7.8 *Defective envelopes*. **C7.** A seq of 2^52-1 is
  `:malformed`.
- 2087, 7.7.8 binding bullets. **C6.** The `:yin.k/reclaimed` fact,
  committed in the lapse's transaction, paired both ways, and written
  at the bound with the unchanged epoch.
- 2100, the same bullets. **C6.** The refusal pair: the plain
  `:dao.lease/rejected` fact plus `:yin.k/refused`, the fold refusing
  either half alone. The same bullet carries the D rule: a refused or
  `:not-holder` candidate re-proposes with a fresh proposal id.
- 2124, *Restart* bullet. **C6.** Reopen runs 7.7.7's steps with
  rebuild after reclaim; a reclaim that fails refuses the open, and
  reopen is retried.
- 2137, *Exhaustion* bullet. **C6/C8.** The exhaustion derivation
  from the epoch-change fact. A report at the bound is evidence whose
  release exhausts without completing.
- 2182, admission step 4. **C9.** Ancestry is checked before the
  checkpoint read, and a non-ancestor is a permanent `:foreign-op-id`.
  Any verifying variant is the checkpoint. An absent, closed or
  throwing store is unreadable.
- 2224, *Snapshot variants*. **C9.** "An unavailable comparison
  suspends" is realized by the existing mechanics.
- 2254, *Completion*. **C8.** The offer rule: `:awaiting-completion`,
  `:orphan` (including an origin naming an unknown predecessor), an
  unconstrained first export, and a pointer to the terminal edge.
- 2270, *Quarantine*. **C8.** A quarantined occurrence cannot
  complete.
- 2421, 7.9 `:intent-conflict`. **C7.** Intents are canonical digests
  (the gate's Q1 text verbatim).
- 2433, 7.9 `:suspended`. **C7.** `{:dao.stream/identity arb}` (Q5
  text, trimmed; see below).
- 2448, 7.9. **C7.** The outcome projection: `"<arb>/outcomes"`,
  reader-only, dense, stable across reopen, blocked tail, never ends,
  attributed because derived from the ledger.
- 2457, 7.9. **C11.** The trusted-identities sentence (Q9 text), and
  that a front reply's `:yin.k/answer` is the landed answer
  unchanged.

`docs/design/dao.lease.md`
- 246, *Composition duties*. **C6.** A new bullet beside "A reclaim
  procedure per subject" with the reclaim adapter contract:
  - readiness-only reclaim;
  - the lapse transaction is the linearization point;
  - nothing claiming revocation leaves the boundary before the
    commit;
  - a non-ok writer leaves the lease `pending` and the state
    unchanged;
  - proposal media are drained from oldest at restart.

`docs/design/yin.vm.linker.dht.md`
- 3186, 14.2.2. **C8/C9.** The cost note: O(chain length x
  occurrences) plus content-store reads, the O(occurrences) never-seen
  scan, all under the authority lock.
- 3209, 14.2.2. **C10.** The input protocol:
  - the request and fact shapes;
  - one dense sequence per root occurrence, with install-child
    ordering;
  - the tenure-first refusals;
  - `:recorded`, `:replayed`, `:stale`, `:suspended`,
    `:input-conflict` and `:input-gap`, in the `:yin.k/status` family;
  - no quarantine on an input conflict, and a quarantined occurrence
    still records;
  - the frontier rule.
- 3393, 14.2.4 row 6. **C11.** The partition row is reworded to cover
  a remote holder through the front: resend, replay, and an
  unattributed reply that discharges nothing.
- 3414, 14.2.4. **C11.** The front:
  - the closed request table with required keys;
  - the request-id echo (Q3 text);
  - attribution in, and extra keys ignored;
  - the `:yin.k/defective-request` diagnostic shape;
  - the throw sentence (Q7 text) and the threw-datum note (Q8);
  - the reply shape;
  - the carriage statuses, with the judge's answer learned from the
    ledger;
  - the closed/poisoned carriage sentence (Q10);
  - resend recovery;
  - the one-attribution-rule duty (Q1 text);
  - the holder-side `reply-evidence` rule, pointing to UCF 7.9.

`docs/design/yin.vm.ucf-revisions.md`
- 368. A new dated status sentence below the C1 to C5 one. It records
  C1 to C11 as implemented, with the given hashes and "C11 (landing
  next)" for you to fill. It lists the amendments landed and says
  that C12, D and E remain, with `handoff-version` still 0. I left the
  earlier C1 to C5 sentence as published.

`docs/design/dao.stream.journal.md`: untouched. Nothing in C6 to C11
required it.

## Where the code contradicted a reviewer's text (code wins)

1. **C7 gate Q5** ends "C11's fronts add reach data if their drivers
   need it". `front.cljc` adds nothing: it replies with admission's
   answer unchanged. I dropped that clause.
2. **C9 gate obs. 3 and the brief** say "one content-store read per
   inherited admission". `accepted-baseline` (admission.cljc:200-211)
   reads variants in address order until one verifies. I wrote "one
   per variant tried, until one verifies".
3. **C6 ruling (d) and UCF's "a lapse recorded for the lease bound at
   2^52-1".** The fold sets `:yin.k/exhausted` when the epoch change
   equals the lapsed lease's binding epoch (ledger.cljc:636-637).
   That happens only at `:max-epoch`, which tests may lower. I worded
   the derivation that way ("carries the lease's own binding epoch,
   which only a lease bound at 2^52-1 can do").
4. **C6 ruling (a)** says "a UCF reader reconstructs `:answered` from
   `:yin.k/refused` alone". In code, `:answered` also takes every
   grant (ledger.cljc:602-605); only a refusal's half comes from the
   refused fact. I wrote "learns which proposal a refusal answered
   ... from the refused fact alone".
5. **C11 gate Q8** writes `{::threw true}`. The real keyword is
   `:yin.vm.ucf.authority.front/threw`, and I wrote it fully
   qualified.
6. **C11 gate finding 3.** A throwing `lease-media` is now `:suspended
   :uncarried`, not a `:malformed` diagnostic (front.cljc:207-215),
   and the text says so. Only a throwing landed function is
   `:malformed`.
7. **C6 gate finding 3.** `reopen!`'s result key is now
   `:yin.k/reclaimed-leases` (grant.cljc:391). This needs no doc
   change, and nothing names the old key.
8. **C10.** The code also answers `:refused :malformed-request`,
   `:refused :unknown-occurrence`, `:suspended :exhausted` and
   `:suspended :bound`. The text covers them in one line each rather
   than as a full list. The frontier counts records with
   `t < grant t` (input.cljc:219), which I wrote as "before its
   grant".

## Not anchored, or anchored somewhere other than named

- **Plan 1.3, 1.5, 1.10, section 2 (slice C8 bullets), section 4
  risk 5 and section 5.** The plan is outside the five editable
  files, and I could not read it (its collab path is in the main
  checkout). The plan-side content went into UCF instead:
  - the 1.5 step order is in 7.7.7;
  - the 1.10 outcome projection is in 7.9;
  - the 1.3 adapter contract is in dao.lease.md.

  C8 gate debt items 6 to 8 (plan text) are not written in the plan
  itself.
- **The re-propose rule (C6 Q6)** is in the 7.7.8 refusal bullet,
  with a pointer to 7.8. 7.8 lower step 5 itself is unchanged.
- **"Row 6" of 14.2.4.** I could not read plan section 5. I took row 6
  to be the sixth test bullet (partition), reworded it minimally, and
  put the front contract right after the bullet list. If plan
  section 5 means a different row, the front text still stands; only
  the row-6 rewording would move.
- **The C11 gate's optional dao.lease.md cross-reference** to the
  one-attribution rule was not added. Your brief places that rule in
  14.2.4 only.
- **The revisions status.** I added a new dated sentence instead of
  editing the published C1 to C5 sentence. The older sentence still
  says "C6 to C12 ... remain", which is now superseded.
