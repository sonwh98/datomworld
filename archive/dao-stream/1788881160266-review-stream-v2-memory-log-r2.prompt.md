Created-GMT: 2026-09-08 15:26:00 GMT
Created-Local: 2026-09-08 22:26:00 +0700 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: 01a080de-1a15-7d23-9a35-4106b127e4f0
# Task: confirm the creation-spec fix (r2)
Role: Routine Review

**Read-only. Print to stdout; write nothing.**
Review `git diff` on `src/cljc/dao/stream/memory_log.cljc` and
`test/dao/stream/memory_log_test.cljc` — untracked, so read the files.

Your P1 is accepted. I verified the contract citation first:
`dao.stream.md:248` says "Every other key is transport-owned, qualified
under the transport's namespace", so an unqualified or non-keyword key is a
malformed specification, not a foreign extension the open-map rule covers.

`valid-spec?` now requires every key to satisfy `permitted-key?`:
`:dao.stream/type` is permitted; any other key must be a keyword **with a
namespace** that is not this transport's own. Unqualified keywords,
non-keyword keys, and own-namespace keys are all `invalid-spec`; foreign
qualified keys are still ignored. The now-unused `own-namespace-key?` helper
was removed rather than left dangling.

`creation-spec-rule` gained the two cases you named:
`(assoc spec :capacity 3)` and `(assoc spec "capacity" 3)`, both asserting
`invalid-spec`.

I re-ran the lanes on the corrected tree; `clj -M:test -n
dao.stream.memory-log-test` is 7 tests / **62** assertions (was 60), 0
failures. Full-lane results follow when they finish; I will not report
readiness without them.

Confirm the fix closes the finding and introduces nothing new — in
particular that `permitted-key?` does not now reject a spec the contract
permits, and that the conformance `invalid-spec` fixture still induces the
outcome it claims.

Also: your note that the implementer's report overstated the tested value
variety — is that worth correcting in the record, or immaterial?

State plainly whether this is ready to commit.
