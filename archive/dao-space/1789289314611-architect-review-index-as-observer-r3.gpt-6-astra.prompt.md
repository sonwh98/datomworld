Created-GMT: 2026-09-13 09:40:00 GMT
Created-Local: 2026-09-13 16:40:00 +0700 (+07)
Coding-Agent: codex
Session-ID: 01a099f1-87d6-7811-8a69-b3336b956fac (resumed)

# Task: Round-3 Confirmation — dao.space.index as a dao.stream Observer (your 11 findings folded in)
Role: Lead System Architect (review)

Implementers:
- Model: gpt-6-astra | Assigned: 2026-09-13 16:40:00 +0700 | Status: active | Rationale: Same reviewer confirms its own findings were resolved.

**Read-only review. Print your complete structured findings to stdout; write no files.**
Repository `/Users/sto/workspace/datomworld`, branch `dao.stream-redesign-v2`. Run `git log --oneline -1` for HEAD (one docs-only commit after `e101932`). Target `docs/design/dao.space.index.as-observer.md`; its Appendix A now ends with an "Architecture round" table mapping each of your findings (A1–A11) to its resolution.

## What changed, by your numbering
- **1 (`m` omitted)** — §2.2 step 2 resolves `e`, declared-ref `v`, and `m`, including tempids that appear only in `v` or `m`, in first-occurrence order over the normalized batch; reserved ids pass through in every slot. §3.1's matrix has an `m` row.
- **2 (provenance vs ref schema)** — §3.2 rewritten: a declared ref *always* resolves batch-locally; cross-medium coordinates are **values** qualified by batch under an undeclared attribute; the join is value-based over resolution facts and is the composition's. A required amendment to `yin.vm.macro.md` §4.1 is recorded — `:yin/source-call` splits into a value attribute (external coordinate) and a declared-ref attribute (log-local node) — and Phase 2 is gated on it. Nothing crosses into the index's interpretation.
- **3 (checkpoint validity)** — §4.2 rewritten around a *candidate* captured as `publish!`'s return value at the one boundary where cursor and coverage coincide, with `:max-t`, `:ingress-gaps`, `:defects`, `:mode`, `:schema-hash`; promoted to a checkpoint only after the composition verifies the manifest and every reachable blob (`walk-addresses`) in the durable store; recovery from an unpromoted latest is "resume from the last verified one"; `restore` refuses mismatched mode/schema and reinstates gap/defect counts.
- **4 (per-payload progress; observer defect)** — §4.1: staged `{:payloads :next}`, advance after each `ok`, `full` resumes at `:next`, terminal outcomes throw with `:next` preserved; guarantee stated as at-most-once acceptance per staged occurrence under ordinary retries, content-address dedup a separate guarantee. Phase 0′ is gated on the `run-on-stream` partial-session fix.
- **5 (not a query value; "live")** — §5: `index/db-value` builds an in-process covered value on `open-published!`'s shape over the live trees; `query` gains that one source kind through the existing covered-index realization; dependency direction argued. "Live" defined as every batch folded up to the cursor after a round, excluding rejected batches, reporting gaps, promising nothing about unread appends or sibling evaluators; Phase 1 test drives the observer, then queries, and checks an earlier `db-value` is unchanged.
- **6 (mode matrix)** — §3.1 table over `e`, declared-ref `v`, `m` × `:resolved`/`:unresolved`, with reserved (< `first-user-id`), user-positive, and tempid columns; positive user refs on `:unresolved` media are defects (unexplained references).
- **7 (watermark)** — §4.2: `:max-t` at capture plus the folded suffix before writes; three restart cases (in-process reopen, durable medium, memory-log identity) distinguished; "one truth" argued.
- **8 (outer grammar; rejected-batch transition)** — §2.2 step 0 grammar (record map → one element; 5-vector with integer first slot → one row; other sequential → elements, no nesting; else whole-batch defect); atomic-per-batch rule: validate all, then fold; rejection leaves trees and `:ids` unchanged, one defect, `:batch` +1 exactly.
- **9 (manifest equality)** — Phase 0′ requires logical equality (rows, counts, query results, valid restore) with enough rows to force splits; the `from-sequential`/`conj` partition difference is stated.
- **10, 11** — §2.3 `history`/`as-of`/retraction wording; §3.1 even/odd alternative acknowledged, "never equate `?e`" generalized to any independent sources and stated as a composition contract, shared allocator as a serially threaded value; §6 two stale rows fixed.

## What to do
1. Per finding 1–11: resolved / partially / not, with the section or the gap.
2. Probe the two places where I made a design choice rather than a wording fix: (a) the checkpoint promotion rule — is "manifest present and every reachable blob resolves in the durable store" the right recoverability predicate, and is it checkable without the async DaoJing observer's cooperation? (b) the `m` row of the mode matrix — is "user-positive `m` on an `:unresolved` medium is a defect" right, given `m` today is almost always `default-op` (reserved)?
3. Is the required amendment to `yin.vm.macro.md` §4.1 the right shape (two attributes), or should the external coordinate be a structured value carrying its medium?
4. Anything new the revision introduced.

## Output Format
Begin your output exactly with:
```text
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a099f1-87d6-7811-8a69-b3336b956fac
Role: Lead System Architect (review) | Model: gpt-6-astra
```
Then the resolution table, any new findings by severity, and an explicit verdict: APPROVE or REQUEST CHANGES.
