Created-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Created-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: deepseek (deepseek-flash)
Session-ID: pending (provider-generated)

# Task: UCF D16-entry F1 — rename the `v1?` flag to tell the truth about what it gates
Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: deepseek-flash | Assigned: <local timestamp> | Status: active | Rationale: a bounded readability cleanup owed pre-D16 by the D10b-B sign-off's condition 3

Implement in /Users/sto/workspace/datomworld-d16f1 (worktree, branch
ucf-d16-f1-rename off master). The D10b-B architect sign-off's condition
F1 (collab/1791363075116-architect-ucf-d10b-b-signoff.deepseek-v4-pro
.findings.md in the main tree's collab/, and the gate's finding F1 in
collab/1791362024950-reviewer-ucf-d10b-b-deepseek-v4-pro.findings.md):
`handoff.cljc`'s `v1?` flag no longer means "version 1" — after the
version-2 work it is `(boolean (or (some? header) (::v1 opts) v2?))`,
i.e. "use the jing codec + exclusive-style lift", and it silently
includes version 2. §10 of the v2 amendment warns against exactly this
conflation ("Do not replace version == 1 with >= 1"). Behavior is
correct because the version-2 dispatch is computed separately, but the
name invites a future regression.

The contract: rename `v1?` to a truthful name (the sign-off suggests
`jing-codec?` or `exclusive-lift?` — pick the one that reads best at
every call site) and keep the version-2 dispatch visibly separate from
the renamed flag. NOT a naive mechanical change: the flag is
load-bearing (the v2 lift relies on `v1?` being true for v2 to select
`jing.cbor/encode` and the non-portable catch — the D10b-B gate's
non-blocking note documents this). Every call site must be audited:
grep the whole src/cljc/yin/vm/ucf/ tree and tests for `v1?` and
`v2?` uses, rename only the flag and its locals, and leave the
version-number logic (`:yin.k/version` checks, `emitted-version`,
`handoff-version`) untouched. `v2?`'s own name may stay if it is
accurate.

Acceptance criteria:
- The rename is complete and consistent; no behavior change (the full
  JVM fast lane and the Node v2 shard must stay green exactly as
  before).
- The existing handoff v0/v1/v2 suites pass unchanged.
- Permitted diff: src/cljc/yin/vm/ucf/handoff.cljc and the test files
  that reference the flag. Anything else: stop and report.

Constraints:
- No git writes. kondo/cljstyle may be sandbox-blocked; note it.
- The D15 slice is in flight in a SIBLING worktree touching
  compose.cljc and yin/repl/* — your diff must stay within
  handoff.cljc and its direct tests.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact test/check outcomes with counts, and any
unresolved concerns. Do not claim edits or tests that did not occur.
