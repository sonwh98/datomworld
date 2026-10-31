Created-GMT: 2026-09-13 09:05:00 GMT
Created-Local: 2026-09-13 16:05:00 +0700 (+07)
Coding-Agent: glm
Session-ID: 8fc82a93-61d0-4e6e-b53e-e8e9b90eba14 (resumed)

# Task: Round-2 Confirmation — dao.space.index as a dao.stream Observer (your F1–F5 folded in)
Role: VM Runtime & Invariant Review

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-13 16:05:00 +0700 | Status: active | Rationale: Same reviewer confirms its own findings were resolved.

**Read-only review. Print your complete structured findings to stdout as your FINAL message — do not put the report in a plan file; your round-1 report reached stdout only as a summary and had to be recovered from the transcript.** Write no files.
Repository `/Users/sto/workspace/datomworld`, branch `dao.stream-redesign-v2`. Run `git log --oneline -1` for the HEAD; the note is `docs/design/dao.space.index.as-observer.md`, and its new **Appendix A** maps each of your findings to its resolution.

## What changed for your findings
- **F1** — Phase 0′ now round-trips the *checkpoint value* `{:manifest-address :cursor :ids :batch}` and compares `restored-indexes` over the published manifest to the live trees as sets; the live-session round trip is gone, with your reason stated.
- **F2** — §4.1 gains a paragraph stating the invariant (a tree's refaults resolve against durable content, never the recording handle), walks your exact sequence (resume → publish → flush → drain → GC → root refault → "missing index segment"), and fixes it by constructing index-session storage with ref-type `:strong`. Re-restoring from the durable store after each flush was considered and rejected because materialization is asynchronous ("append success means enqueued, not materialized"). Phase 0′ adds the forced-GC test.
- **F3** — §3.2 states the `:dao.space.index/*` reservation as a contract on media that the index does not check, with the consequence of violation.
- **F4** — §2.3 states that assert-then-retract on a `t 0` medium is folded, `history` is correct, and `current` throws loudly; not a defect because recognising a retraction is payload interpretation.
- **F5** — §4.2 "a query, not a scan" replaced with the honest O(rows)-but-no-replay statement; §2.2 step 3 now says dirtiness is the absence of an address mark on a node; §2.2 steps 1–2 state the tx-record-on-`:unresolved` and `v`-only-tempid cases; step 1 also adopts your observation that `v` needs no schema at admission.

## What to do
1. Per finding F1–F5: resolved / partially / not, with the section.
2. **Verify the F2 remedy against the code**: does constructing `kv-storage` with `:strong` actually prevent root eviction on the JVM given `make-store-ref`/`make-ref` and `resident-root`? Is there any other refault path through the wrapper's storage field that `:strong` does not cover? Is the rejection of re-restore-after-flush right given `dao.jing`'s async materialization?
3. Is the F4 disposition (fold, `current` throws) consistent with decision 11 of `yin.vm.macro.md` (bad input is data, not a throw) — or is a query-time throw a different category, as the note claims?
4. Anything new the r2 edits introduced.

## Output Format
Begin your FINAL message exactly with:
```text
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: glm
Session-ID: 8fc82a93-61d0-4e6e-b53e-e8e9b90eba14
Role: VM Runtime Review | Model: glm-5.3
```
Then the resolution table, any new findings by severity, and an explicit verdict: APPROVE or REQUEST CHANGES.
