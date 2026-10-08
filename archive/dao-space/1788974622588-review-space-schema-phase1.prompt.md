Created-GMT: 2026-09-09 17:23:42 GMT
Created-Local: 2026-09-10 00:23:42 +0700 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: 01a0868e-e9f2-7242-92e8-58d63e7f9574 (resumed)
# Task: review dao.space.schema Phase 1 — the write side
Role: Routine Review

**Read-only. Print to stdout; write no file.** You reviewed this plan through
r1 and r2 and cleared it as implementable. **Phase 1 is now implemented.**
Review `git diff` — four files, +310/-284.

Implementer: `glm-5.3`, independent of you and of the plan's author.
Plan (promoted, r3): `docs/design/dao.space.schema.implementation-plan.md` **§4**
Implementer's report: `collab/1788972941480-storage-space-schema-phase1.glm-5.3.findings.md`

## Verified by me — attack past these, do not re-run the suite

All three lanes, my runs on this working tree:
- clj **1436 tests / 165344 assertions / 0 failures 0 errors**
- cljs **1346 / 34917 / 0 failures, 1 error** — the pre-existing `wasm/create-vm`
  undeclared-var, unrelated and present before this diff.
  `Testing dao.space.schema-test` **is** in the Node output.
- cljd **All tests passed (+1299)**, 69 `dao.space.schema-test` entries
- demo build: 212 files, **0 warnings**
- `grep -c "ds/"`: `schema.cljc` **14**, `schema_test.cljc` **6** — both on
  target; the six remaining are the read-side inventory Phase 2 owns
- deftests **70 → 72**; only the four owned files are modified

Your budget is for **static analysis**, not for reproducing green.

## The implementer disclosed two deviations — judge them

1. **24 receipt sites rewritten, not §4's 22.** The plan's §0.4 list missed
   two multi-line `(:result` openers (W45, W50); leaving them would have
   failed the suite. Is that the whole story, or did a receipt site change
   meaning rather than shape?
2. **T19's test took a third change** beyond `:1062`/`:1071`: an assertion
   *message string* whose "keeps schema's v1 public shape (D10)" prose went
   false when the D10 paragraph was deleted. I read the diff and no assertion
   form or value moved. Confirm or refute.

## What to judge

1. **T19** — the invariant most at risk from the `deftype`→map rewrite:
   `transact!` plans the complete next state before one inner append and
   installs it only on `ok`. Is the atomic install still atomic under the
   map form and the same lock? Can a caller now reach or corrupt the state
   atom in a way the `deftype` prevented?
2. **The closed-precedence rule** (D1/L10) — empty `tx-data` throws above
   the lock, everything else answers `closed` first, matching
   `tx/transact!:241-243`. Does the code do that, and does the new test
   actually pin it on all four legs?
3. **`close!`** — does the new idempotence test still fail if `tx/close!`
   were never called? That direct inner assertion exists because a
   wrapper-only assertion would pass vacuously; check the pin survived
   implementation.
4. **`publish!`** — guard and lock deleted per D7. Any use-after-close or
   lost serialization the diff introduces?
5. **The design edits** — `dao.space.schema.md` §3.1 and §7,
   `dao.space.transactor.md`'s T20 retirement and open-item deletion. Do
   they say what the code now does? A design that describes the old behavior
   is the defect this phase is most likely to leave behind.
6. **Phase boundary** — `current`, both `defopen`s, `published`,
   `PublishedSchemaRows` and `schema_fixtures` must be untouched, and the
   `dao.stream` require must still stand. Confirm nothing of Phase 2 leaked
   in, and nothing Phase 2 needs was removed early.

## Report

Print to stdout, ordered by severity, each finding with the concrete failure
it would cause. Distinguish blocking from improvement. If the phase is clean,
say so plainly and say it is ready to commit — do not manufacture a finding.
