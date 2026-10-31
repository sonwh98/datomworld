Created-GMT: 2026-09-09 11:36:39 GMT
Created-Local: 2026-09-09 18:36:39 +0700 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: 6a2bae02-ee7f-47b6-ae80-e905b0ca657f
# Task: Phase 2 of the transactor+index v2 migration — the swap
Role: Storage & Indexing Engineer
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-09 18:36:39 +0700 | Status: active | Rationale: Storage & Indexing primary; built dao.stream.memory-log and Phase 1

**Implementation task with write authority, bounded to Phase 2.** Repo
`/Users/sto/workspace/datomworld`, branch `dao.stream-redesign-v2`, clean at
`e467687` (Phase 1).

`bb` is now permitted — run `bb test:clj`, `bb test:cljs`, `bb test:cljd`
directly rather than substituting underlying commands.

## Read first

- `collab/1788950282826-architect-space-transactor-v2-plan-r5.claude-fable-5-1.findings.md`
  — the plan. **Phase 2 is your scope.** Five architect rounds, five reviews,
  and an adversarial review that found two blockers the others missed.
- `collab/1788950643300-adversarial-space-transactor-index-plan-r2.deepseek-v4-pro.findings.md`
  — the adversarial confirmation. Its verification detail is worth reading:
  it traced the re-wrap mapping and the residue counts itself.
- `src/cljc/dao/stream/memory_log.cljc` — the transport you are wiring in.

## This phase cannot be partial

Phase 2 is **the swap**. The tree does not compile in the middle of it: the
`:transactor` `defopen` and `DaoStreamLog` are deleted in the same phase that
introduces `transactor/create!`, and every caller moves with them. Plan for
one coherent change, not an incremental sequence.

If the host kills you for low memory — it has once — **write partial findings
first**, then resume. Do not leave a half-applied edit without a record.

## What the plan requires, and the three things most easily got wrong

1. **`schema/transact!` must re-wrap** the transactor receipt back to
   `{:result :ok :t t :datoms ds}` on `ok` (D10/T19/T20). This is the
   adversarial F1: schema returns the receipt **verbatim** today
   (`schema.cljc:1112`), and **22 assertions** across `schema_test` read
   `:result`/`:t`/`:datoms` — including `:1657` and `:1789`, which are
   `(is (= :ok (:result` split across lines. Non-ok returns are *new*, not
   changed, because v1 threw.
2. **`snapshot-datoms`'s v2 loop must validate result shape before
   interpreting it** — `stream/validate-outcome` (`v2.cljc:245`) on the mint
   and on every read, throwing a malformed-result exception carrying the raw
   result. Without it, a malformed `ok` with no cursor makes the loop recur on
   `nil` and **spin forever**; `index_test:395` passes today and would hang
   the suite. Keep the totality throw for a well-formed unexpected outcome.
   Keep flattening **incremental**, as Phase 1 established.
3. **`schema/publish!`'s closedness guard moves under the wrapper lock**, and
   `SchemaWrapper.close!` keeps returning `{:woke []}`. The plan states the
   cost honestly: the lock is then held across an index build.

## Closure criteria — these are greps, not lists

- `ds/` in `test/dao/space/transactor_test.cljc`: **135 → 0**
- `ds/` in `test/dao/space/stigmergy_test.clj`: **6 → 3** after this phase
  (`:103`, `:106`, `:118` move now; `:176-178` `sources` is Phase 3)
- `test/dao/space/stigmergy_test.clj:32`'s bare `[dao.stream.ringbuffer]`
  require becomes unused **this phase** — remove it with the `:103` migration.
- `dao.space.schema` keeps its own v1 surface: this phase edits only what the
  transactor change forces.

## Verification — run in full, report counts

`bb test:clj`, `bb test:cljs` (confirm `Testing dao.space.transactor-test`
and `…schema-test` in the Node output), `bb test:cljd`,
`clj -M:cljs -m shadow.cljs.devtools.cli compile demo`, and
`clj -M:kondo --lint` on every file you touch.

**Format before you report**: `mise exec -- cljstyle fix <files>`.

Reader-conditional trap: `#?(:clj …)` alone does **not** exclude from the cljd
build; use `#?(:cljd nil :clj …)` with `:cljd` first.

## Report

`collab/1788953799216-storage-space-transactor-phase2.glm-5.3.findings.md` with
Completed-GMT/Local, Coding-Agent: glm, Session-ID: 6a2bae02-ee7f-47b6-ae80-e905b0ca657f. Exact commands and
counts, both residue greps, any plan invariant you could not honour and why,
and anything left owing. Do not stage or commit.
