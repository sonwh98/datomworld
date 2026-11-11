Created-GMT: 2026-11-10 07:44 GMT
Created-Local: 2026-11-10 15:44 +0700
Coding-Agent: agy
Session-ID: pending (provider-generated)
# Task: exception R3 — verify the existing diff and produce the round record (resume-by-state; NO new design work)
Role: Yang Compiler and Universal AST Engineer
Implementers:
- Model: gemini-3.8-flash-high | Assigned: 2026-11-10 15:44 +0700 | Status: active | Rationale: prior gemini session wedged in its harness after writing the code; killed per owner instruction; this session verifies and reports only.

You are RESUMING a prior session's work by state. The implementation is DONE — sitting
uncommitted in your working tree. Do NOT redesign, rewrite, or expand it. Your entire job:
understand the diff, run the verification, report the round record.

Worktree: `/Users/sto/workspace/datomworld-r3-orch` (detached @ `83b52a0b` — post-fix
master; the R3 edits +184/−39 in `src/cljc/yang/clojure.cljc` and
`test/yang/clojure_test.clj` are already in the tree).

## Read first

1. Your own diff: `git diff` in the worktree (both files, in full).
2. The spec: `collab/exception-track/1794960000000-owner-ruling-exception-uniformity-v2.md` §R3, §R5.
3. The brief the prior session worked from (context for intent):
   `collab/yang-clojure/1794217310000-engineer-exception-r3-clj.prompt.md` — especially
   its decision table (5 raise-exit cases) and the three named traps.

## Verify the diff against the decision table (static read, no code changes)

For each of the 5 table rows, find where the diff handles it and confirm. If ANY row is
handled wrong, or a trap (1: thrown condition-shaped map must stay a wrapped payload;
2: evidence fields must survive; 3: entry-body tail must not re-wrap) is violated —
STOP, report the defect precisely, and fix ONLY that, minimally.

## Run (FOREGROUND, one at a time, wait for each)

```sh
cd /Users/sto/workspace/datomworld-r3-orch
eval "$(mise env -s bash)"   # already trusted
mise install >/dev/null 2>&1 || true
bb gen:antlr 2>&1 | tail -1
clj -M:test -n yang.clojure-test 2>&1 | tail -3
clj -M:test -n yang.call.exception-contract-test -n yang.python.antlr.exception-call-test 2>&1 | tail -3
clj -M:kondo --lint src/cljc/yang/clojure.cljc test/yang/clojure_test.clj 2>&1 | tail -2
```

Do NOT run anything else. Do NOT background anything. One JVM at a time; the previous
command must EXIT before the next starts.

## Expected numbers (the instrument is FIXED on this master)

- Baseline of record: **10 tests / 279 assertions, 159 failures / 39 errors** (= 81 pass).
- Your round must flip Clojure protocol rows red→green: expect **failures to DROP by 18**
  (6 rows × 3 backends) and **errors unchanged at 39** → target ≈ **141 failures / 39 errors**.
  If your numbers differ, report them exactly with the failing row IDs — do not tune the
  code to force the number.
- `yang.clojure-test`: all green including the new regressions.
- kondo: 0 errors 0 warnings.

## Commit and report

If all verification passes: `git add` the two files, commit on a NEW branch
`exception-r3` (create it: `git checkout -b exception-r3`), message:

```
fix(yang.clojure): protocol conditions retain and forward unchanged through the public escape (R3)
```

Do NOT push, do NOT merge. Then write your final response per the template below.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report: (1) the static decision-table read — row-by-row verdicts; (2) exact test outputs
(commands + counts); (3) the acceptance-run row deltas (which rows flipped); (4) kondo;
(5) whether you committed and the commit sha; (6) unresolved concerns. Do not claim
edits or tests that did not occur.
