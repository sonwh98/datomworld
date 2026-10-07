Created-GMT: 2026-09-09 13:53:04 GMT
Created-Local: 2026-09-09 20:53:04 +0700 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: 01a080de-1a15-7d23-9a35-4106b127e4f0
# Task: review Phase 3 — index closes
Role: Routine Review

**Read-only. Print to stdout; write nothing.** Review `git diff`.

Phase 3 — index closes — implemented by `glm-5.3`, uncommitted.

Plan: `collab/1788950282826-architect-space-transactor-v2-plan-r5.claude-fable-5-1.findings.md` **§5**
Implementer's report: `collab/1788959908204-storage-space-index-phase3.glm-5.3.findings.md`

## Verified by me structurally

- `ds/` residue: `index_test` **35 → 0**, `stigmergy_test` **3 → 0**.
- `index.cljc` requires **exactly** `dao.data.btree`,
  `dao.data.btree.storage`, `dao.datom`, `dao.jing`, `dao.stream`. Zero
  occurrences of `ds/`, `jing-coordinate/`, `observe/`,
  `PublishedIndexStream`, or `require-macros`.
- `dao.space.schema` is now the **only** `dao.space` namespace on v1.

## Lanes I ran on this exact tree

- `bb test:clj` — 1434 tests, **165341** assertions, 0 failures, exit 0 under
  `timeout 900`
- `bb test:cljs` — 1344 tests, 34908 assertions, 0 failures, 1 pre-existing
  wasm error; `index-test` and `schema-test` present in Node
- `compile demo` — 212 files, 0 warnings
- `bb test:cljd` — running

Do not rerun them.

## The number I want checked hardest

**Assertions went DOWN** — 165341 here versus 165356 at Phase 2. That is
expected, because this phase deletes three deftests whose properties are
supposed to be covered elsewhere. But "fewer assertions, still green" is
exactly what a silently dropped property looks like.

## Judge

1. **§5.4's eleven rows, one at a time.** For each, does the named survivor
   actually pin the property, or does it merely touch the same code? The
   three *moves* (#1 coordinate rejection, #5 open-time fetch count, #7/#8)
   exist because their properties were **not** covered elsewhere.
2. **P5** — the store-closed-on-failed-open test the adapter never had. Does
   the new test actually fail if the `finally` is removed?
3. **`PublishedSchemaRows`** — is it a faithful replacement for what schema
   used to get from the adapter, and does W38–W41 still pass unmodified?
4. **§5.3's do-not-touch list** — anything in it disturbed?
5. Anything owed and unnamed, given `dao.space.schema` is next.

State plainly whether Phase 3 is ready to commit.
