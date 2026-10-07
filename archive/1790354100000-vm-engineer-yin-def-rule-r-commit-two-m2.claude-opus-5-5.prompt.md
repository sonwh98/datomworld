Created-GMT: 2026-09-26 01:40:00 GMT
Created-Local: 2026-09-26 08:40:00 +0700
Coding-Agent: claude
Session-ID: 95dfac20-b589-4457-a3b8-1e3019c8e120

# Task: Rule R commit two (M2 cleanup on top of Rule R) and merge-conflict resolution

Role: VM Runtime Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-09-26 08:40 +0700 | Status: active | Rationale: owner directive "go with opus"

Repository: /Users/sto/workspace/datomworld-ucf-phase2 (branch ucf-phase2). Its
HEAD is 0fc931fc, Rule R commit one (already committed, gated READY by codex).
The tree holds the UNCOMMITTED M2 work (from 96657a4f) merged on top of it: three
files merged cleanly and are STAGED (src/cljc/yin/vm/content.cljc,
src/cljc/yin/vm/linker.cljc, test/yin/vm/linker_test.cljc), and one file is
UNMERGED: test/yin/vm/content_test.cljc (one conflict hunk at lines 255-263).
Collab files: /Users/sto/workspace/datomworld/collab/ (absolute paths; this
worktree has no collab/). A backup of the M2 work exists at
/Users/sto/workspace/datomworld/archive/m2-uncommitted-backup-1790353500/ and a
git stash entry "m2-uncommitted-before-rule-r-ff" is kept: do NOT drop the stash.
Do NOT touch /Users/sto/workspace/datomworld or ../datomworld-ucf-rule-r.

## Owner statements (verbatim quotes)

"But i like Rule R: yin/def is syntax never a name"
"let's stick with B because that is clojure's convention to keep require as an ordinary function"
"go with opus"

## Specification (authoritative)
- The final design, "Commit two, M2" paragraph:
  /Users/sto/workspace/datomworld/collab/1790345200000-architect-yin-def-rule-r-final.claude-fable-5-1.findings.md
- Your own Rule R report for context (a different session did that work; read it):
  /Users/sto/workspace/datomworld/collab/1790345800000-vm-engineer-yin-def-rule-r-commit-one.claude-opus-5-5.report.md
  and the fix reports beside it (fix1, fix2, fix3, fix4), and the codex gate
  findings for commit one (impl-gate, impl-regate, impl-regate2, impl-regate3).
- The M2 gate cycle that produced the guards you will delete, so you understand
  what stays: /Users/sto/workspace/datomworld/collab/*linker-m2-* findings and
  reports (rounds 1-4).
- docs/design/yin.vm.linker.md (M2 = section 9, and sections 4.1, 4.2, 5, 6.3, 6.4, 8.1, 11).

## What to do (commit two)
Step 0. Resolve the one conflict in test/yin/vm/content_test.cljc (lines
255-263): keep M2's linker/fetch version, and give semantic/load-vector the
correct required contract argument. Rule R made load-vector require a stamp.
Pass the contract that the fetch/format record carries for that content, NOT an
automatic stamp of the current constant on external input (that is the pattern
codex rejected in commit one). Explain the choice in your report. Resolve by
editing the file only; do NOT run git add.
Step 1. Get a merged baseline: run all three lanes on the resolved tree and
report the counts before changing anything else.
Step 2. Implement exactly the design's commit two:
- the four linker format records take the new contract names ("v3" AST and
  semantic, "b2" stack, "r2" register) and the fetch REQUIRES a contract: an
  omitted contract is :invalid-request, a differing one stays :contract-mismatch;
  callers pass the record's contract explicitly;
- DELETE the round 3 and round 4 shadow filters: the filter that drops
  yin/def-derived definitions when the footprint binds yin/def, the round 4
  computed-yin-def-write? and tree-yin-def-application-query, and the :yin-def?
  bookkeeping;
- KEEP constant-key recognition (round 2), the invocation position [3 2]
  (round 3), dominance, and the earlier fetch-bound fixes (byte cap before
  hashing, :max-parts 0, and the rest);
- the vector definitions scanner reads :define beside :store-put; obligations
  exclude the definition operator (yin/def is no longer a free name);
- the rebinding, direct :vm/store-put, computed-key and aliased-call fixtures now
  refuse :reserved-name (the tests a-direct-store-put-of-yin-def-discharges-
  nothing-from-it and a-computed-key-yin-def-write-discharges-nothing-from-it
  become refusal tests; flip the round 3 shadow fixtures likewise);
- a definition whose value is the quoted symbol, (yin/def 'x 'yin/def), links and
  defines x as that symbol.
Do NOT do anything from the design's M4 bucket. Reserved set is only yin/def;
require stays an ordinary primitive.

## How to work
Same rules as commit one: TDD (failing test first), mise for everything, kondo via
mise exec -- clojure -M:kondo --lint <files>, cljstyle check, ASCII and <= 80
columns on every line you add or edit (Markdown grid rows excepted), no em
dashes, the cross-host traps (#?(:cljd nil :clj ...) with :cljd FIRST; private
var access; cljd ExceptionInfo; cljs keyword identity). Run the lanes
sequentially and solo: JVM mise exec -- clojure -M:test; Node mise exec -- clj
-M:cljs -m shadow.cljs.devtools.cli compile slice-peer test; Dart rm -rf
test/cljd-out && mise exec -- bb test:cljd. Do NOT commit, stage, checkout, reset,
stash, or merge. If the design conflicts with the tree, STOP and report BLOCKED
with the exact conflict; do not improvise. The orchestrator reruns every lane and
sends the diff to codex for the M2 gate.

## Report
Write your final report to
/Users/sto/workspace/datomworld/collab/1790354100000-vm-engineer-yin-def-rule-r-commit-two-m2.claude-opus-5-5.report.md
(same header fields) and return it as your final response, with: the conflict
resolution and why, the merged baseline counts, final lane counts, the list of
deleted guards and kept pieces, tests added or flipped, deviations, unrun checks.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED - <reason>
