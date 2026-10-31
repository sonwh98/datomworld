Created-GMT: 2026-10-20 09:54:30 GMT
Created-Local: 2026-10-20 16:54:30 +07
Coding-Agent: claude
Session-ID: fresh

# Task: Fix Review Finding P2 for Slice C1
Role: Implementation Engineer
Implementers:
- Model: claude-sonnet-5-5 | Assigned: 2026-10-20 16:54:30 +07 | Status: active | Rationale: Fix review finding P2 in isolated worktree.

Working Directory: /Users/sto/workspace/datomworld-codec-c1
Reviewer Report: /Users/sto/workspace/datomworld/collab/1792489770000-reviewer-codec-c1.gpt-6-astra.findings.md

Reviewer Finding P2:
Location: `src/cljc/dao/stream/remote_channel.cljc:354`
Problem:
`(when (seq codecs) ...)` drops an explicitly supplied `:codecs []`. Consequently `serve` silently starts a Transit endpoint and passes Transit to `:bind!`, instead of preserving the explicit configuration for validation.
Fix:
- Forward the supplied `:codecs` map entry if present, letting `ws/make-endpoint` perform validation (so `:codecs []` is rejected before bind, while omitting `:codecs` entirely defaults to Transit).
- In `test/dao/stream/remote_channel_test.cljc`, add a regression test asserting that passing an explicit empty vector `:codecs []` to `serve` is rejected, while omitting `:codecs` continues to default to Transit.

Verification:
- Run `bb test:sub dao.stream` across all 3 lanes (CLJ, CLJS, CLJD).
- Confirm all pass with 0 failures.

Write findings to /Users/sto/workspace/datomworld/collab/1792490070000-engineer-codec-c1-fixes.claude-sonnet-5-5.findings.md.
