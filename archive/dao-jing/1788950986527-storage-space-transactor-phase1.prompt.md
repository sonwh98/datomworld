Created-GMT: 2026-09-09 10:49:46 GMT
Created-Local: 2026-09-09 17:49:46 +0700 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: db17b4be-bb49-4dce-a31e-583a854ade64
# Task: Phase 1 of the transactor+index v2 migration
Role: Storage & Indexing Engineer
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-09 17:49:46 +0700 | Status: active | Rationale: Storage & Indexing primary per team.md; implemented dao.stream.memory-log, which this migration is built on

**Implementation task with write authority, bounded to Phase 1 only.**
Repo `/Users/sto/workspace/datomworld`, branch `dao.stream-redesign-v2`,
clean at `ba90b3a`.

## Read first

`collab/1788950282826-architect-space-transactor-v2-plan-r5.claude-fable-5-1.findings.md`
— the plan. Five architect rounds, five review rounds by `gpt-5.6-sol`, and
an adversarial review by `deepseek-v4-pro` which found two blockers the others
missed. It is now verdict **ready to implement**.

**Do only Phase 1.** Phases 2 and 3 are separate briefs. Phase 2 is "the
swap" and cannot be partial; do not start it, and do not make Phase 1 edits
that only compile once Phase 2 lands.

## What Phase 1 is

Splitting `dao.space.index`'s payload vocabulary from its reading, in
`index.cljc` and `index_test.cljc`. It is **behaviour-neutral**, and that is
its acceptance criterion: **every existing test stays unchanged and green.**

The plan's §4 has the exact edits. Two things it stresses that a reader can
miss:

- `snapshot-datoms` **keeps calling `element-datoms` incrementally inside its
  read loop.** Do not restructure it into drain-then-flatten: that changes
  which failure wins when both a malformed element and a later malformed
  signal exist, and performs reads past where the old code stopped. An
  earlier draft of this plan made that change while claiming neutrality; the
  review caught it. Invariant S8 is kept and promoted, not dropped.
- `datoms-from-elements` is **public and documented in Phase 1**, with the
  note that it has no `src` caller by design so nobody later "cleans it up".

## Two corrections from the adversarial confirmation, not yet in the plan text

- The plan says "20 assertion sites" in `schema_test` reach into
  `:result`/`:t`/`:datoms`. It is **22** — `:1657` and `:1789` are
  `(is (= :ok (:result` split across lines. Immaterial to Phase 1; noted so
  you do not propagate the wrong figure.
- `stigmergy_test.clj:32`'s bare `[dao.stream.ringbuffer]` require becomes
  unused after **Phase 2** and should be removed then. Not Phase 1.

## Verification — run in full, report counts

`bb test:clj`, `bb test:cljs` (confirm `Testing dao.space.index-test` in the
Node output), `bb test:cljd`, `clj -M:cljs -m shadow.cljs.devtools.cli compile
demo`, and `clj -M:kondo --lint` on the files you touch.

**Format before you report**: `mise exec -- cljstyle fix <files>`, so the tree
you tested is the tree that gets committed.

Reader-conditional trap: `#?(:clj …)` alone does **not** exclude from the cljd
build; use `#?(:cljd nil :clj …)` with `:cljd` first.

## Report

`collab/1788950986527-storage-space-transactor-phase1.glm-5.3.findings.md` with
Completed-GMT/Local, Coding-Agent: glm, Session-ID: db17b4be-bb49-4dce-a31e-583a854ade64. Exact commands and
counts; state explicitly that no existing test was modified, or name every one
that was and why. Do not stage or commit.

If the host kills you for memory — it did once before, and that was not your
fault — write whatever you have reached into the findings file before doing
anything expensive.
