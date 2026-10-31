Created-GMT: 2026-09-09 17:31:27 GMT
Created-Local: 2026-09-10 00:31:27 +0700 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: b71838c3-58ae-434b-bbcd-73418c7af768 (resumed)
# Task: dao.space.schema Phase 2 — the read side
Role: Storage & Indexing Engineer

**Implementation task with write authority**, bounded to *Ownership* below.
This resumes your Phase 1 session, so you hold that context. Phase 1 is
**committed**: `bdbe6f9` (the code) and `32cd7c8` (the plan). Tree is clean
at `bdbe6f9`.

Your Phase 1 work was reviewed by `gpt-6-astra` and returned **clean, no
blocking findings, no improvements requested**. Both deviations you disclosed
were judged justified — and disclosing them was right. Do the same here.

## Your specification

**`docs/design/dao.space.schema.implementation-plan.md` §5 (Phase 2).**
Its Build / Delete / Tests / Prove lists are exact and were cleared by two
reviewers of different families. Read **D3, D4, D5, D6 and D9**, and §2.2's
invariants V6-V15, before touching code. §5 gives `current`'s new body and
the W38/W39/W41 `try`/`finally` nesting as literal code — use them.

## What Phase 2 is

Phase 1 took the dressing off the write side. Phase 2 does the read side, and
it is the end of `dao.space.*` on v1. Both `defopen` routes die with the
registry that v2 lists as *Explicitly Absent*; `PublishedSchemaRows` and
`published` die because query's `open-published!` already works and a
schema-typed coordinate would bake the reader's lens into the name of the
data; `schema_fixtures` dies because it existed only to hold a `defopen`;
and `current` takes values only.

## The three things most at risk, named so you check them

1. **D4's limit is a *documented limit*, not a guarantee — do not "fix" it.**
   `schema/current` rejects a snapshot whose status is `:gap` or `:defect`
   because the read is **known-incomplete**. It **accepts** `:blocked` and
   `:ended`. A prefix evicted *before* the snapshot is **indistinguishable
   from complete history** and schema does not detect it: `query/snapshot`
   mints a fresh `:oldest`, which on a ring buffer is the earliest
   *retained* position. **V15 exists to pin exactly that limit** — it asserts
   `:blocked` is accepted and that the answer keeps both card-one values
   because the vocabulary was evicted. If V15 looks like a bug to you, it is
   not; it is the contract. Do not add a retention check, do not reject
   `:blocked`, do not widen `query`'s API. Two reviewers and three architect
   rounds settled this.
2. **V14 must pin ownership, not just self-containment.** Wrap the opened
   store's `:close-fn` with a counter (`jing/close!` delegates to it,
   `jing.cljc:318-325`), assert **zero** closes after `schema/current`
   returns, then `close-published!` and assert **one**. An assertion that
   only re-queries after the close would pass even if `schema/current`
   closed the store early, because `close-published!` is idempotent. That
   vacuity is why this test is specified this way.
3. **The closure greps are the end condition, not a target.** If a number
   lands elsewhere, report the actual number and why. Never edit code to hit
   one.

## Ownership

Write only:
- `src/cljc/dao/space/schema.cljc` — §5 Build and Delete
- `test/dao/space/schema_test.cljc` — §5 Tests
- **delete** `test/dao/space/schema_fixtures.cljc`
- `docs/design/dao.space.schema.md` — the §5 in-phase edits (§4's descriptor
  block and Realization paragraph, §5's published block, §7, §8, §9, the
  status paragraph)
- `docs/design/dao.stream.md` — remove `dao.space` from the remaining-v1
  consumer list (`:803-:805`)
- `docs/design/dao.space.transactor.md` — *Related documents*: the schema
  line loses "its D10 rule governs the shapes it re-wraps" (line 16). This is
  the one residue Phase 1 deliberately left; the reviewer flagged it and
  agreed it was Phase 2's.

Touch nothing else: not `query.cljc`, not `index.cljc`, not
`transactor.cljc`, not the plan, not `docs/orchestrator-log.md` (it is
history, and untracked by design).

## Verification you must run and report

- `clojure -M:test`, `bb test:cljs`, `bb test:cljd` — full, unfiltered,
  with **assertion counts**. Confirm `Testing dao.space.schema-test` in the
  Node output. cljs carries one pre-existing `wasm/create-vm` error that is
  not yours.
- `clj -M:cljs -m shadow.cljs.devtools.cli compile demo`.
- Every §5 Prove grep, with its actual result: `ds/` → **0** in both files;
  the require/require-macros grep → nothing; the deleted-name sweep across
  `src test docs/design` → nothing; deftests 72 → **72**;
  `grep -rln "\[dao.stream :as" src/cljc/dao/space` → **nothing**.
- `clj -M:kondo --lint src/cljc/dao/space/schema.cljc`.
- **Deleting a namespace leaves stale generated Dart.** `schema_fixtures`
  goes, so remove its output under `test/cljd-out/` and `lib/cljd-out/`
  before the cljd lane, or it will run orphaned tests. This bit an earlier
  phase of this sweep.

## Report

Write to `collab/1788975087538-storage-space-schema-phase2.glm-5.3.findings.md`,
starting with Completed-GMT, Completed-Local, Coding-Agent: glm,
Session-ID: b71838c3-58ae-434b-bbcd-73418c7af768. State what you built and
deleted, exact commands with outcomes and counts, any invariant you could not
honor and why, and anything left owing. **Do not stage or commit.** If your
budget runs out mid-phase, leave the tree readable and say precisely what
remains.
