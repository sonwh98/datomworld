Created-GMT: 2026-10-05 21:05:48 GMT
Created-Local: 2026-10-06 04:05:48 +07
Coding-Agent: claude
Session-ID: 820fb1cc-b82c-4ee6-96c7-7a170107243d

# Task: head-h2 (re-dispatch: an interrupted run left a half-edited file)

Role: DaoStream and Network Engineer

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-06 01:10:05 +07 | Status: active | Rationale: owner directive to route to fable until the 04:00 reset
- Status-Event: 2026-10-06 01:15:00 +07 | Model: claude-fable-5-1 | Status: failed | Rationale: "You've hit your session limit" (session 3cb055e5-43cd-4274-9321-2f3c0542b34b); the run died mid-edit and wrote no report
- Model: claude-opus-5-5 | Assigned: 2026-10-06 04:05:48 +07 | Status: active | Rationale: owner 2026-10-06 04:00: "continue with normal routing"; engineers on opus, a different-family reviewer (gpt-6.1-sol) gates it

The task is unchanged: read and complete
`collab/1791223805044-stream-engineer-head-h2.prompt.md` in
/Users/sto/workspace/datomworld/.claude/worktrees/head-h2. All of its scope, hard
rules and process rules apply (named files only, no git, no formatter, foreground
JVM tests only, no Node or Dart, no background processes;
`docs/design/dao.stream.md` is NOT amended, stop and report if it must be).

## The state you inherit (UNTRUSTED, and it does not compile)

The previous engineer died mid-edit. The working tree has ONE modified file,
`src/cljc/dao/stream/remote.cljc` (about 210 insertions, 37 deletions), and nothing
else. The orchestrator checked it:

- It does NOT compile: `clj -M:test -n dao.stream.remote-test` fails with
  `Syntax error compiling at (dao/stream/remote.cljc:512:11). Unable to resolve
  symbol: absorb-reflected! in this context`. The edit stopped partway.
- What it appears to contain: the namespace docstring paragraph on names, a
  `named?` helper, `well-formed-request?` accepting a named request on the
  `descriptor` op only (a request with both a name and an identity, or a named
  request with another op, malformed), `well-formed-answer?` accepting a named
  `not-found` with no identity, and the start of a `links`/`resolve` restructure.
- You may keep any of it that is right, or restore the file from HEAD and start
  that file over (you may not run git: ask the orchestrator in your report if you
  want a restore, or simply rewrite the affected functions by reading the
  committed behaviour from the tests and the design). Whatever you choose, the
  result must satisfy the hard rule that EVERY existing `dao.stream.remote` and
  ws-project test passes unchanged and an identity request is answered exactly as
  before. Do not assume the inherited restructure is the right design: the
  smallest change that satisfies H2 is preferred.

Say in your report what of the inherited edit you kept, changed or discarded, and
why.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 820fb1cc-b82c-4ee6-96c7-7a170107243d

Then give the report the original brief asks for.
