Created-GMT: 2026-09-09 12:26:03 GMT
Created-Local: 2026-09-09 19:26:03 +0700 (Asia/Bangkok)
Coding-Agent: deepseek
Session-ID: 8bc96ab3-be06-4493-82d4-355a52e8dcb2
# Task: adversarial review of Phase 2 — the swap
Role: Adversarial Review

**Read-only. Print to stdout; write nothing.** Review `git diff`.

You reviewed this plan and found the two blockers eleven rounds of the other
reviewer missed. **Phase 2 is the phase both of your findings were about**, now
implemented by `glm-5.3`. A routine review by `gpt-5.6-sol` runs in parallel;
do not coordinate with it — your value is being a different mind.

Plan: `collab/1788950282826-architect-space-transactor-v2-plan-r5.claude-fable-5-1.findings.md` (Phase 2)
Implementer's report: `collab/1788953799216-storage-space-transactor-phase2.glm-5.3.findings.md`

## Verified by me already — attack past these, not at them

- `ds/` residue: `transactor_test` **135 → 0**, `stigmergy_test` **6 → 3**
  (exactly `sources`), `query_test` 2 → 0.
- `transactor.cljc`: zero `DaoStreamLog` / `ds/defopen` / v1 require.
- `schema.cljc:1124` re-wraps (your F1). `index.cljc:453-484` validates via
  `checked` on the mint **and** every read (the hang).
- clj 1433/165353 0 failures **under `timeout 900`, exit 0 not 124**;
  cljs 1343/34920 0 failures + 1 pre-existing; demo 0 warnings; cljd running.

## Hunt for these shapes, not for a checklist

1. **A rule generalized from one instance.** Your F1 was exactly that — the
   plan preserved `close!`'s v1 shape but not `transact!`'s. D10 now claims
   the enumeration is closed. **This diff is where D10 is actually applied.**
   Is every forwarding surface really re-wrapped, or only the two that were
   named?
2. **A value trusted before it is checked.** The plan's loop would have hung;
   `checked` fixes `snapshot-datoms`. Where else does this diff consume
   `:dao.stream/value`, `:dao.stream/cursor`, or an outcome without
   validating first — in `transactor.cljc`, in `schema.cljc`, or in a test
   double?
3. **Owed and unnamed.** You found the eleventh read path this way. What does
   this diff leave that no one has written down? The implementer names one
   already: D3's non-outcome→`transport-error` fold has no test, since no
   double answers a non-map from `append!`.
4. **A test that no longer tests what it claims.** The implementer found one:
   `ThrowingAppendStream` bound `_val` while its body used `val`, so the
   retry appended `clojure.core/val` — invisible because nothing read the
   stream back. **Five doubles and 22 opens were migrated in this diff.** Are
   there others whose assertions now pass vacuously?
5. **Concurrency.** `SchemaWrapper`'s lock now spans an index build, and the
   transactor's watermark advances only on `ok`. Find an interleaving that
   breaks a stated invariant.

Rank by severity, name file and line, say for each whether it blocks the
commit.
