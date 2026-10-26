Created-GMT: 2026-10-27 01:00:16 GMT
Created-Local: 2026-10-27 04:00:16 +07
Coding-Agent: codex
Session-ID: pending (provider-generated; confirmation of your round-1 review)
# Task: dao.star cart — confirm the seven fix resolutions
Role: Reviewer (adversarial, confirmation round)
Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-10-27 04:00:16 +07 | Status: active | Rationale: same reviewer family; your findings were addressed and need your confirmation

Read-only static confirmation in
`/Users/sto/workspace/datomworld/.claude/worktrees/dao-star-demo`. Do not
edit, stage, commit, or rerun suites. Your seven findings were addressed in
one fix round (`/Users/sto/workspace/datomworld/collab/1793037435251-stream-engineer-dao-star-cart-fixes.claude-opus-5-5.findings.md`);
verify each statically, check for new defects and interactions, and issue a
verdict.

The resolutions:
1. Symlink escape → `static-file` resolves real paths of root and target
   and requires real containment; missing paths answer nil. Tests refuse an
   intermediate-directory symlink and a symlinked file, still serve a plain
   file.
2. Tempid collisions → tempids from a name-to-position map. The `"Aa"`/`"BB"`
   pair stays distinct across save/reload; removing one leaves the other.
3. Reconnect → both begin pages subscribe with `@get('…/s/ROOT',{retry:'always'})`;
   rendered-attribute tests added. (Orchestrator confirmed the attribute in
   the redeployed container's live HTML.)
4. Snapshot race → update and snapshot are one critical section under a
   store lock; test forces slow-first-write with a second save behind it;
   the reopened file carries both edits.
5. Image URLs → validation decodes once and stores the canonical decoded
   path; `enc-path` encodes once; duplicate spellings rejected; malformed
   `%zz` rejected; an encoded spelling of an existing filename round-trips.
6. Line terminators → `js-lit` escapes CR/LF/U+2028/U+2029; `esc` emits
   `&#13;`/`&#10;`; `sse-elements` folds any CR/LF. Tests cover LF, CRLF,
   bare CR through page, signals JSON, transact, written file, and grid
   projection. Implementer notes bare CR is additionally folded as a safety
   net though no current projection sends one.
7. Default path → unset `CATALOG_PATH` now saves to `target/cart-catalog.edn`
   (parent dirs created); "MEMORY ONLY" is logged loudly when no path at
   all; README documents both.

Orchestrator evidence since your review (not rerun): focused
`clojure -M:test` over five demo namespaces → 36 tests / 524 assertions /
0 failures; kondo clean; redeployed on shiva (image rebuilt, container
restarted, localhost 200, `retry:'always'` present in served HTML).

Begin the final response exactly with:
Completed-GMT: <actual GMT timestamp>
Completed-Local: <actual local timestamp and timezone>
Then per-finding resolution lines (resolved / partially / unresolved, with
file:line evidence), any new defects, and a final verdict line: SIGNED OFF /
SIGNED OFF WITH CHANGES / NOT SIGNED OFF.
