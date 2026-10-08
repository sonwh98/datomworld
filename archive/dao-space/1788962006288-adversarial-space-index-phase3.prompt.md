Created-GMT: 2026-09-09 13:53:26 GMT
Created-Local: 2026-09-09 20:53:26 +0700 (Asia/Bangkok)
Coding-Agent: deepseek
Session-ID: 8bc96ab3-be06-4493-82d4-355a52e8dcb2
# Task: adversarial review of Phase 3 — index closes
Role: Adversarial Review

**Read-only. Print to stdout; write nothing.** Review `git diff`.

Last round you cleared Phase 2's code outright while the routine reviewer
blocked three times on documentation — you scoped differently and both were
right. **This is the final phase of the sweep.** A routine review runs in
parallel; do not coordinate with it.

Plan: `collab/1788950282826-architect-space-transactor-v2-plan-r5.claude-fable-5-1.findings.md` **§5**
Implementer's report: `collab/1788959908204-storage-space-index-phase3.glm-5.3.findings.md`

## Verified by me — attack past these

`index_test` `ds/` 35 → 0, `stigmergy_test` 3 → 0, `index.cljc` reduced to
exactly five requires with no `ds/`, `jing-coordinate/`, `observe/`,
`PublishedIndexStream` or `require-macros`. clj 1434/165341 exit 0 under
`timeout 900`; cljs 1344/34908 + 1 pre-existing; demo 0 warnings; cljd running.

## Hunt these shapes

1. **A property deleted rather than moved.** Assertions went **down**
   (165341 vs Phase 2's 165356). §5.4 claims every deleted deftest's property
   survives in a named test. **You validated that accounting on paper; this
   diff is where it is executed.** For each of the eleven rows, does the
   survivor pin the property or merely exercise the same code path? You found
   the eleventh read path last time by asking what nobody had written down.
2. **A test that passes vacuously.** You found the `ThrowingAppendStream`
   `_val`/`val` bug via this shape. Four deftests were *moved* to
   `query_test` and rewritten against a different opener — a rewrite is
   exactly where an assertion quietly stops asserting.
3. **P5, the new test.** "A store opened during a failed open is closed
   before the error propagates." Would it actually fail if the `finally`
   were removed, or does it pass for an unrelated reason?
4. **A value trusted before it is checked**, in `PublishedSchemaRows` or the
   rewritten `sources`.
5. **Owed and unnamed**, with `dao.space.schema` next and this plan due for
   deletion once consumed. Anything Phase 3 leaves that no document records
   is lost.

Rank by severity, name file and line, say whether each blocks.
