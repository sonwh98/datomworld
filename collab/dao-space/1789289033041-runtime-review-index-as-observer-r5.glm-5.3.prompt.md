Created-GMT: 2026-09-13 09:33:00 GMT
Created-Local: 2026-09-13 16:33:00 +0700 (+07)
Coding-Agent: glm
Session-ID: 8fc82a93-61d0-4e6e-b53e-e8e9b90eba14 (resumed)

# Task: Round-5 Re-confirmation — dao.space.index as a dao.stream Observer (your R4-1–R4-4 and astra's R1–R5 folded in)
Role: VM Runtime & Invariant Review

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-13 16:33:00 +0700 | Status: active | Rationale: You approved `3d32eb4` with four P3s; the same pass that folds them also folds five P2s from the architecture reviewer, so your approval is re-established on the current text.

**Read-only review. Print your complete structured report to stdout as your FINAL message; write no files.**
Repository `/Users/sto/workspace/datomworld`, branch `dao.stream-redesign-v2`; run `git log --oneline -1`. Target `docs/design/dao.space.index.as-observer.md` (~840 lines); Appendix A ends with "Architecture round 3" and "Runtime round 4" entries.

## What changed and what to verify
1. **Your R4-1/R4-4** — §3.1's partition is now tempid `<0` / reserved `[0, first-user-id)` / user-positive `≥ first-user-id`, and reserved `e` passes on `:resolved`. Verify against `datom.cljc` (`first-user-id`, `local-datom?`, `reserved`) that this is exactly what the existing paths admit.
2. **Your R4-2** — `:max-t` is `nil` until a row folds; watermark `0` when `nil` else `max-t + 1`. Verify against `transactor.cljc` `derive-next-t`.
3. **Your R4-3 / astra R1** — `index/checkpoint` and `index/coverage` take the `{:observer :consumer}` session; `restore` returns the consumer and the composition re-attaches at `c`, which requires `dao.stream.observer/attach` to accept a kept cursor. Verify against `observer.cljc` `attach` and `dao.stream`'s cursor contract (`stream/cursor`, kept cursors, first `next` validating) that this is a small, sound addition and that `index → observer` is the only dependency direction introduced.
4. **astra R2** — monotonic `:rejected` beside drainable `:defects`; confirm the §2.2 atomic rule's state transition is now fully specified (trees, `:ids`, `:max-t`, `:rejected`, `:defects`, `:batch`) for admitted, empty, and rejected batches.
5. **astra R5** — `restore` refuses a `:shared` `:ids`. Anything in the code that makes this insufficient or unnecessary?
6. **Promotion walk** — §4.2 now says: all four roots, every address checked in the `walk-addresses` visitor including leaves, via `jing/get`. Verify against `btree.cljc` `walk-addresses` (does the visitor see leaf addresses?) and `jing.cljc` `get`.
7. Re-check items 1–7 of your round-4 report still hold.

## Output Format
Begin your FINAL message exactly with:
```text
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: glm
Session-ID: 8fc82a93-61d0-4e6e-b53e-e8e9b90eba14
Role: VM Runtime Review | Model: glm-5.3
```
Then per item: **holds / does not hold / partially** with file:line evidence; any findings by severity; an explicit verdict: APPROVE or REQUEST CHANGES.
