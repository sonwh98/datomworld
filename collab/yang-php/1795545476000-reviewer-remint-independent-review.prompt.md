Created-GMT: 2026-11-25 (dispatch time)
Coding-Agent: agy (gemini-4-neon-high, jupiter, review mode)
Session-ID: 6f48bf01-fc69-4f0e-8c51-61ad3ecd8c38 (agy conversation_id, jupiter, gemini-4-neon-high plan+sandbox)

# Task: independent review — the py-manifest-remint branch (Assignment B of the master-reds brief)

Role: independent reviewer. You are NOT the implementer (the branch was implemented by codex
gpt-6.1-terra on the same host); your review is the independence the landing needs while the
coordinating seat's own countersign gate is stalled.

## What to review
Worktree: ~/ci/dw-remint (branch fix/py-manifest-remint, HEAD 4bdde6f4; the branch already
merged master at b825db65). The diff vs origin/master:
  cd ~/ci/dw-remint && git diff origin/master...HEAD
Focus files: src/cljc/yang/python/antlr/profiles.cljc (the module-lock + dependent locks),
test/yang/python/antlr/float_address_test.cljc (5 pins), test/yang/python/antlr/linked_prelude_test.cljc
(manifest golden + count), test/yin/vm/data_test.cljc (MUST keep the zipmap form — the dart lane
depends on it).

## The claims to verify (from the implementer's findings + the countersign)
1. The 5 float-address pins match the fresh-generation values (verdict 48a2f9e2 + ADDENDUM 2
   778ca8b2 — collab/yang-php/1795460000000-to-yangphp-remint-countersign-reconcile-note.md on master).
2. The profiles.cljc module-lock (blake3-6e60da0c...) matches the linked-manifest golden, and
   the dependent descriptor/runtime-revision locks are consistent with it.
3. The linked-prelude manifest golden (6e60da0c...) and the count literal (72, with
   py.b/AssertionError present) are consistent with the runtime keys at this tip.
4. data_test.cljc: the zipmap form intact.
5. No other behavior change hides in the diff (review the WHOLE diff, not just the pin lines).

## Rules
- Read-only: you may run read-only git/grep commands and read files; do NOT edit, do NOT run
  the test suites (the seat runs the gates; a reviewer summary is not a gate).
- If a claim fails or something unexplained appears in the diff, record it as a finding with
  file:line evidence — findings, not fixes.

## Deliverable
Print a findings report (severity | file:line | evidence | correction) and an overall verdict
(APPROVE / REQUEST CHANGES with the finding list). The seat transcribes it to
collab/yang-php/<ts>-reviewer-remint-independent-review.gemini-4-neon-high.findings.md.
