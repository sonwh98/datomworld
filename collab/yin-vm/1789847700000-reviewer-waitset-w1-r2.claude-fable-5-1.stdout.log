Completed-GMT: 2026-09-19 19:41:49 GMT
Completed-Local: 2026-09-20 02:41:49 +07

# W1 r2 confirmation

**W1 is ready for commit.** All five fixes hold, and I found no new defect in the touched lines or in the two new tests. Four P3 notes follow, and none of them blocks the commit. I edited nothing and did not rerun suites.

| Finding | Disposition | Evidence | Remaining action |
|---|---|---|---|
| P2 uninterpretable answers | **Fixed** | `answer-defect` uses the contract's own `stream/validate-outcome` and separates `:invalid` from `:unauthorized` (`waitset.cljc:68-83`). An invalid answer wakes as `:dao.stream.waitset/invalid-answer`. A rogue keyword outcome still wakes under its own keyword, so the plan's undeclared-outcome rule holds. Each poll is wrapped in a catch that uses the house `:cljd`-first form (129, 160). A handle that throws leaves `:waiting`, so a writer whose `append!` threw cannot be appended again. | None. |
| P2 resolver throws | **Resolved by documenting it, which was one of the two options I offered** | The `check` docstring says "Resolvers must not throw" and spells out the consequence of re-appending (234-240). Register rows 613-615 cover invalid answers, poll-time throws and resolver throws. | None. |
| P2 advance counters | **Fixed** | `check-one` returns the counter (test:161-170). The reader loop asserts 1 for `ok` and `gap` and 0 for the rest (217-221). The writer loop asserts 0 (236-237). The invalid-answer cases assert 0. | None. |
| P3 `park` totality | **Fixed** | `(fnil conj [])` (47). The test covers `{}` and `{:waiting nil}`, and that park order is preserved (test:286-296). | None. |
| P3 missing `:value` | **Fixed by the docstring note** | `poll-put` docstring (143-145). | None. |

## Notes (no action needed for this commit)

- **W2 compatibility.**
  - Validation is now stricter than the engine's. A reader `ok` without `:dao.stream/value` or `:dao.stream/cursor`, or a `gap` without its cursor, now wakes as `invalid-answer`. Before r2, an `ok` without them woke `:ok` with no advance.
  - I spot-checked the reified readers in `engine_test.cljc` and `semantic_test.cljc`. Their `next` answers carry both keys (`semantic_test.cljc:732-734`), so W2's "suite passes unchanged" condition looks safe.
  - I did not check every other fixture under `test/yin`. Confirm that when W2 lands.
- **The catch discards the exception.**
  - `invalid-answer` does not tell a malformed answer apart from a throw.
  - An `:error (ex-message e)` string on the diagnostic would keep the message.
  - It would still round-trip through `pr-str`.
- **Catching `Throwable` on clj.**
  - It matches `dao.stream`'s house idiom (`serving.cljc:42`).
  - It also swallows interrupts, which is unlikely to matter because `next` and `append!` do not block.
  - It is consistent within this tree and differs only from the choice made in the lease namespace.
- **Small test gaps:**
  - No case drives a declared outcome that is missing a required key into `invalid-answer`. The docstring claims that path.
  - No case shows the sweep continuing past an `invalid-answer` entry to a later co-waiter.
  - The code handles both.
