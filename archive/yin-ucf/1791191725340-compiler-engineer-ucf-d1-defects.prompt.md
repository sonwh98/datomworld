Created-GMT: 2026-10-05 09:12:05 GMT
Created-Local: 2026-10-05 16:12:05 +0700

# Task: UCF M-next D1 — the version-0 codec/address mismatch (third post-A defect)
Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-05 16:12:05 +0700 | Status: active | Rationale: implementation role; same engineer family as the C slices

Implement D1 in /Users/sto/workspace/datomworld-d1 (worktree, branch
ucf-d1-defects). Read first: src/cljc/yin/vm/ucf/handoff.cljc (the export
path around lines 700-726 and the address/verification consumers), the
design docs/docs/design/yin.vm.linker.dht.md 14.3 (the two landed
version-0 defects and the third), docs/design/yin.vm.ucf-revisions.md
section 6 (what is already fixed in version 0), and
test/yin/vm/ucf/handoff_test.cljc.

The defect (C4 architect ruling, deferred to D): export returns an
`:address` minted by `(jing/content-hash body)` over the body map while
`:bytes` are `(cbor/encode body)` under `dao.stream.cbor` (handoff.cljc
about line 719 vs 725). The returned address is therefore not the digest
of the bytes export actually emits, so a receiver that verifies fetched
bytes against the address refuses a body export itself published.

Acceptance criteria:
- The export result's `:address` is the digest, under the jing address
  scheme the receivers already use, of the exact bytes export emits.
  The body's bytes do not change: the version-0 wire is frozen, this is
  a version-0 fix, not a grammar change.
- The fix is test-first: a red test asserting address and bytes agree
  (recompute the digest from `:bytes` and compare), then the fix, then
  green. The test is portable `.cljc` and runs on JVM during iteration;
  the orchestrator runs the full three lanes at landing.
- The test also covers at least one lower/receiver path that verifies
  the address against the bytes, so the pair is proven, not just the
  mint side.
- No other namespace's behavior changes; `git diff` after the round
  shows only handoff.cljc and handoff_test.cljc.

Constraints:
- Edit only src/cljc/yin/vm/ucf/handoff.cljc and
  test/yin/vm/ucf/handoff_test.cljc. If something else must change, stop
  and report instead of editing it.
- No git writes (no add/commit/checkout/stash); the orchestrator owns
  git. cljstyle and kondo are the orchestrator's at landing (kondo you
  may run; cljstyle is permission-blocked for you).
- Lessons from the C slices: a 0.0 literal is the integer 0 on JS (use
  `(cbor/float64 0)` or 0.5 for float fixtures); `#?(:cljd nil :clj
  ...)` order for JVM-only test branches (:cljd first); reader
  conditionals in tests must be `:cljd`-first.
- No float literals in new test data where a host could diverge; prefer
  integer and string payloads.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact test/check outcomes with counts, the red and
green evidence, unresolved concerns, and any incomplete work. Do not claim
edits or tests that did not occur.
