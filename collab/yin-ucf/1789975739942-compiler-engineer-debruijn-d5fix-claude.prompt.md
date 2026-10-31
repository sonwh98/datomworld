Created-GMT: 2026-09-21 07:28:59 GMT
Created-Local: 2026-09-21 14:28:59 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 69f82e15-85c9-4754-a273-a5f4ad68d932 (resumed — your epic-fix session, after its r2 turn completes)
# Task: debruijn-d5fix-claude — apply the accepted D5 review findings to pipeline.cljc
Role: Yang Compiler and Universal AST Engineer
Implementers:
- Model: claude-opus-5 | Assigned: 2026-09-21 14:19:09 +07 | Status: active | Rationale: same implementer; the D5 author (glm-5.3) is rate-capped until 17:26 +07 and the owner routed implementation to Claude
- Status-Event: 2026-09-21 14:28:59 +07 | Model: glm-5.3 | Status: reassigned | Rationale: D5 fix round only — the D5 files now fall in your box; glm returns as the independent reviewer of your work after its reset

Work only in /Users/sto/workspace/worktree-debruijn-impl. Your r2 turn (the
epic-fix F1-F4/F6/F7 round) must be complete and reported before you start
this; if the tree state shows it is not, finish r2 first and say so.

## Input

The independent D5 review by claude-sonnet-5 (NOT READY, 2 P2 + 8 P3):
collab/1789975523112-reviewer-debruijn-d5.claude-sonnet-5.findings.md
(inside the worktree). It reviewed pipeline.cljc and pipeline_test.cljc
against the gpt-5.6-sol D5 pre-clearance summarised in
collab/1789967942071-compiler-engineer-debruijn-d5.prompt.md. The current
design doc is
collab/1789975149204-compiler-engineer-debruijn-epicfix-claude.ref-design-master-37dfbf54.md.
Verify each finding against the code yourself before applying it.

## Box (widened for this round only)

Edit ONLY: src/cljc/yin/vm/pipeline.cljc, test/yin/vm/pipeline_test.cljc, and
(only if a fix strictly needs it) src/cljc/yin/vm/debruijn.cljc. No staging,
committing, docs/, or collab/ edits.

## Accepted findings — apply

- **P2-1 (gate)**: replace the root-marker presence check with the real
  framing gate: call `debruijn/frame-datoms` (public, committed API; re-read
  its current shape after your r2 edits) before ANY persistence, require
  exactly one complete frame, and otherwise return
  `:named {:outcome :rejected :rule <rule from ex-data>}` and
  `:projected :not-attempted`. This also rejects malformed datoms before the
  named write. Tests: a complete graph followed by trailing datoms of an
  unfinished second graph; two concatenated complete graphs; a batch
  containing a retract datom — each rejects before either side persists and
  the writer is never called.
- **P2-2 (dedupe test)**: make the test store instrumentable — one shared
  event log recording each put result and each get. After two equal writes
  assert the exact sequence (put :inserted, put :present, then materialize!'s
  read-back get) and that no get precedes the first put. This must fail a
  pre-write lookup and an overwriting put.
- **P3-1**: on success, assert the writer call precedes the store put (same
  shared event log).
- **P3-2**: a writer answer with a missing/non-keyword outcome maps to
  `:invalid-writer-answer`, raw answer kept under `:receipt`.
- **P3-3**: throw `:bad-request` when BOTH `:ast` and `:datoms` are given;
  test both-given and neither-given.
- **P3-4**: an internal defect keeps its `:status` and surfaces as
  `:projected {:outcome :internal-error}`, distinct from an invalid-input
  `:diagnostic`. Consistent with the D4 rule and your F6 work.
- **P3-6**: wrap store-failure diagnostics as
  `{:rule :projected-write-failed :cause (select-keys ex-data [:result :address])}`;
  never embed the full envelope payload. Update the existing test that locks
  in the raw shape.
- **P3-8**: one sentence in the namespace docstring: the physical address
  comes from jing's transitional print-based hash and is portable only
  between implementations sharing that print rule; the Merkle fingerprint is
  the cross-host identity.

## Declined — no action

P3-5 (result/diagnostic discriminator style — works, stays minimal) and P3-7
(materialize! returns only the address; the event log in P2-2 makes
:inserted/:present observable in tests without widening jing).

## Verification (report exact commands and counts)

Same as the epic fix: focused JVM
`clojure -M:test -n yin.vm.debruijn-test -n yin.vm.pipeline-test`, kondo and
`cljstyle check` on every touched file, and the CLJS lane (`bb test:cljs`,
Java 21) since pipeline.cljc is a `.cljc` file. The mise environment block
and the one-simple-command-per-step rule from your brief apply. The
orchestrator reruns everything including CLJD; delegated counts are untrusted.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 69f82e15-85c9-4754-a273-a5f4ad68d932
