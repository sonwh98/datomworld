Created-GMT: 2026-09-04 07:48:06 GMT
Created-Local: 2026-09-04 14:48:06 Asia/Ho_Chi_Minh
Coding-Agent: claude
Session-ID: fde8b517-9c3f-48c6-a4d9-c455489fbebc

# Task: Phase 5 R3/R4 follow-on LOW corrections verification round 4

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-04 14:48:06 Asia/Ho_Chi_Minh | Status: active | Rationale: resume the exact architect conversation and verify corrections to its two round-3 follow-on LOW findings

Resume your Phase 5 R3/R4 review in `/Users/sto/workspace/datomworld` and
perform a read-only review of only the latest corrections to the two LOW
findings in
`collab/architect-phase5-r3-r4-low-fixes-verification-r3.fable.findings.md`.
Do not edit, stage, or commit.

Inspect the real diff and relevant tests. Verify:

1. Before a fresh connection replaces an old adapter, every already-unpublished
   completion is promoted RPC -> adapter -> driver outbox even when `:unsent` is
   nil. Abandonment remains conditional on an actual unsent envelope, and
   publication remains exactly once.
2. When a detached binding owns queued remote lines and the operator connects
   to a different URL, the fresh-connection path reports the exact discarded
   line count before `attach-remote` clears the queue. Same-URL reattachment
   behavior must remain unchanged.
3. The new tests genuinely drive both seams rather than passing through an
   impossible state or merely asserting implementation details.

Verified locally after these edits:
- Focused JVM: 45 tests, 228 assertions, 0 failures/errors.
- Full v2 JVM: 115 tests, 531 assertions, 0 failures/errors.
- Full Node/CLJS: 1207 tests, 34191 assertions, 0 failures/errors, 0 warnings.
- Kondo on the changed source/test: 0 errors, 0 warnings.
- `git diff --check`: clean.
- CLJD source generation completed. The all-repository Dart test compile then
  failed on two unchanged baseline reader-conditionals in
  `test/dao/stream/ws_test.cljc:77,139`; both omit a CLJD branch and generate
  a one-argument `append!`. This delta does not touch that file or those forms.

Do not rerun suites; spend the review budget on static analysis of this delta.
Begin the final response exactly with:
Completed-GMT: <actual timestamp>
Completed-Local: <actual timestamp and timezone>
Coding-Agent: claude
Session-ID: fde8b517-9c3f-48c6-a4d9-c455489fbebc

Report severity | file:line | evidence | correction, disposition of both LOW
findings, any effect of the noted baseline CLJD test compile failure on this
delta's sign-off, and finish with exactly one of:
SIGN-OFF: GRANTED
SIGN-OFF: WITHHELD
