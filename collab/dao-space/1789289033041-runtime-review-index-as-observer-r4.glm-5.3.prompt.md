Created-GMT: 2026-09-13 09:40:00 GMT
Created-Local: 2026-09-13 16:40:00 +0700 (+07)
Coding-Agent: glm
Session-ID: 8fc82a93-61d0-4e6e-b53e-e8e9b90eba14 (resumed)

# Task: Round-4 Re-confirmation — dao.space.index as a dao.stream Observer (material changes after your approval)
Role: VM Runtime & Invariant Review

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-13 16:40:00 +0700 | Status: active | Rationale: You approved `e101932`; the independent architecture reviewer (gpt-6-astra) then found three P1s and six P2s, and the note changed materially. Your approval must be re-established on the current text, with your code-verification angle on the new mechanisms.

**Read-only review. Print your complete structured report to stdout as your FINAL message; write no files.**
Repository `/Users/sto/workspace/datomworld`, branch `dao.stream-redesign-v2`. Run `git log --oneline -2` for HEAD. Target `docs/design/dao.space.index.as-observer.md` (now ~750 lines); Appendix A's last table (A1–A11) records what changed and why.

## What is new since `e101932`, and what to verify against code
1. **§2.2 step 0, outer batch grammar.** Verify against `transactor.cljc` (`append-packet!`: what exactly is appended per call) and against how a program medium's batches are appended (`yin.repl.core/eval-datoms`, `test_utils/queue-ast!`) that the three-rule grammar (record map → one element; 5-vector with integer first slot and keyword second → one row; other sequential → elements) classifies every real batch shape correctly and cannot misclassify a five-row batch as a row.
2. **§2.2 step 2 / §3.1 matrix — `m` as an identity slot.** Verify against `dao.space.transact` (`:133–150, 168–181` per astra) that the transactor resolves `m` tempids, and against `datom.cljc` what "reserved" (below `first-user-id`) contains, so the matrix's `m` row is consistent with real `m` values (`default-op`, retraction op, user metadata entities).
3. **§4.1 publication state machine** `{:payloads :next}` — trace `flush-staged` under `ok/full/full/ok`, a terminal outcome mid-list, and resumption, against `observer.cljc`'s `run` placement; confirm the recording handle is drained only at the end and that nothing re-appends an accepted blob.
4. **§4.2 checkpoint promotion** — is "manifest present and every `bt/walk-addresses`-reachable blob resolves in the durable store" implementable with `jing/get` and `walk-addresses` as they exist, and does it correctly detect an unmaterialized publication given `dao.jing`'s materialization is asynchronous?
5. **§5 `index/db-value`** — verify against `query.cljc` (`value?`, `db-source`, `realize-db-value!`, `open-published!`) that an in-process covered value on the `open-published!` shape can be recognised by the existing covered-index realization path, and that this is a `query`-side addition of one source kind with `index` still below `query`.
6. **`:max-t`** — confirm `transactor.cljc`'s `derive-next-t` semantics match "`max-t` over checkpoint plus folded suffix, plus one".
7. Re-check your previously verified claims (admission, `conj`, dirty tracking, `:strong` on both session kinds, `current`/`history`) still hold after the rewrite — especially that the mode matrix's `:resolved` column for `e` ("reserved → defect") does not reject rows the transactor legitimately writes.

## Output Format
Begin your FINAL message exactly with:
```text
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: glm
Session-ID: 8fc82a93-61d0-4e6e-b53e-e8e9b90eba14
Role: VM Runtime Review | Model: glm-5.3
```
Then per item 1–7: **holds / does not hold / partially** with file:line evidence; then any findings by severity; then an explicit verdict: APPROVE or REQUEST CHANGES.
