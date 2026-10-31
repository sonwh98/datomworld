Created-GMT: 2026-09-08 14:21:31 GMT
Created-Local: 2026-09-08 21:21:31 +0700 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: 01a080de-1a15-7d23-9a35-4106b127e4f0
# Task: confirm the three corrections (r2)
Role: Architect review

**Read-only. Print to stdout; write nothing.** Review
`git diff -- docs/design/dao.stream.md docs/design/dao.stream.ws.md`.

All three accepted.

1. **P1, the `full`/`gap` rule.** Rewritten around the obligation you
   identified as missing, which is now the lead sentence: *a value whose append
   answered `ok` remains observable for as long as that logical stream
   exists.* Everything else derives from it. A complete-history transport need
   not be writable; a writable one with a declared finite capacity returns
   `full` without appending or evicting; a logically unbounded one may exclude
   `full`; and logically unbounded is explicitly not physically infinite —
   unexpected exhaustion is `transport-error`, never eviction of acknowledged
   history.
2. **P2a, the origin-cursor bullet.** It was self-contradictory against the
   section below it. Now scoped to a transport that *can evict*, for
   *detection*, with a closing sentence that on a declared complete-retention
   transport it is unnecessary because a fresh `:oldest` is the origin there
   however late the consumer arrives.
3. **P2b, the surviving reader-level promises.** All five sites moved to
   cursor-level: `dao.stream.md` :665 ("a cursor that falls behind receives a
   `gap`"), :734 ("a cursor that falls behind is evicted past ... the reader
   holding it decides"), and the outcome-exclusion example at :445 ("pressure
   surfaces as a `gap` to a cursor that spans the eviction"); plus
   `dao.stream.ws.md` :403, :429, :437.

Confirm each closes its finding, and say whether anything in the rewrite
newly contradicts the rest of the contract. **We are about to build a
transport against your checklist**, so if the obligation list is still
incomplete, now is the moment.

State plainly whether this is ready to commit.
