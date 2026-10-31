Created-GMT: 2026-09-13 09:12:00 GMT
Created-Local: 2026-09-13 16:12:00 +0700 (+07)
Coding-Agent: glm
Session-ID: 8fc82a93-61d0-4e6e-b53e-e8e9b90eba14 (resumed)

# Task: Round-3 Confirmation — dao.space.index as a dao.stream Observer (N1, N2 folded in)
Role: VM Runtime & Invariant Review

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-13 16:12:00 +0700 | Status: active | Rationale: Same reviewer confirms its own round-2 findings.

**Read-only review. Print your complete structured report to stdout as your FINAL message; write no files.**
Repository `/Users/sto/workspace/datomworld`, branch `dao.stream-redesign-v2`. Run `git log --oneline -1` for HEAD; target `docs/design/dao.space.index.as-observer.md`, Appendix A now has a round-2 table (N1, N2).

## What changed
- **N1** — §4.1's remedy paragraph now says explicitly that ref-pinning consults the *tree's* settings, not the storage passed to `store-tree`, and gives the mechanism per session kind: a from-`:oldest` session is safe either construction way; a **resumed** session restores its trees through a *session-constructed* `:strong` `kv-storage` over the durable content store (`bt/restore-tree` behind an index-side helper) and explicitly **not** through `query/open-published!`/`restored-indexes`, which take the host default because they are the read path. §4.2's resume sentence no longer names `open-published!`. Your "settings carry no storage" over-narrowing is fixed (both from-`:oldest` construction styles are named).
- **N2** — Phase 0′'s test now uses the `:test` ref-type plus `clear-test-refs!` to reproduce the hazard deterministically (restore through `:test` refs → publish → drain → clear → query throws), the `:strong` session under the same regime → no throw, and asserts that `restored-indexes` is the wrong construction for an index session.

## What to do
1. N1, N2: resolved / partially / not, with the section.
2. Verify against the code that a tree restored via `bt/restore-tree` over a `kv-storage` constructed with `:ref-type :strong` (a) pins its root on `resident-root`'s fault memoization and on `-store-tree!`'s post-store wrap, (b) passes `:strong` to every node `conj` creates, and (c) faults child slots through the durable store — i.e. that the resumed path is now closed with no other refault route.
3. Anything new r3 introduced.

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
