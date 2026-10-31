Created-GMT: 2026-10-31 00:30:00 GMT
Created-Local: 2026-10-31 07:30:00 +07 (+0700)
Coding-Agent: codex (fresh session)

# Task: S3 linked-path fix — the yang.call injection for ordinary linked packets + the post-J6e totals re-mint

WORK CHECKOUT: /Users/sto/.codex/worktrees/yang-common-call/datomworld (branch python-s3-wiring, tip
e1e91e2f — rebased onto master 63674368 with the J6e merge). CLEAN tree.

THE EVIDENCE (shiva, fresh clone + bb clean + regenerated parser — the stale-clone hypothesis is
DEAD): yang.python.antlr.import-test = 84 failures / 7 errors, ALL in the linked-path bodies:
- `[:thrown "Module link refused: absent"]` where `[true false #:py{:out ["1 1"]}]` is expected
  (dotted-import-runs-each-body-once-in-order-test, cyclic-from-import-present-name-test, …) —
  the linked module sources require [py] but lowering requires [yang.call py].
- `[:thrown "Unable to resolve symbol: yang.call/eligible?"]` — linked sources referencing the
  convention without the runtime loaded (i6-runtime-test-test).

THE FIX CLASS (established by resume-2 for the pysp wrapper — extend it):
1. The linked packet composition (the lowerer's linked-source path / publication.cljc's linked
   emission — wherever linked module sources get their require lists) must inject/require
   `yang.call` BEFORE `py` for EVERY linked source that references the convention — the same rule
   the safepoint wrapper already implements. Resume-2's report claimed "ordinary linked packets
   already require yang.call before py in the lowerer" — that claim is FALSE for these bodies;
   find the gap (which composition path skips the injection) and close it. The EXISTING linked
   test sources in the corpus stay untouched if the injection fixes them at the composition layer
   — that is the point of the wrapper design.
2. THE POST-J6E TOTALS RE-MINT (the jolt seat's heads-up): their merge's ws/jolt files join the
   closure — re-derive the per-lane closure totals + the shared count + the union (gate_test.clj's
   constants and the committed ledgers if the files list changed) mechanically from the failing
   assertions at the post-fix head. Their commit message flagged this exact expectation.
3. THE GENERATOR RULE: rerun with deferred=#{:py :pysp}; byte-identical double-run; restore #{:pysp}.

VERIFY: the import-test namespace (fast AND slow bodies — run WITHOUT -e :slow selection so the
slow bodies run; this is where every previous round's verification gap hid), then
float-address-test (both), call-entry, call-test, main-test. LOCAL single namespace runs OK for
iteration (check uptime first; defer if loaded). The full lanes are the orchestrator's (shiva).
kondo 0/0 + cljstyle.

CONSTRAINTS: single turn; no approvals; commit to python-s3-wiring; do NOT push; do NOT touch
master. End with: the injection fix summary (which path was skipping it), the totals re-mint map,
the generator finding, the local namespace results (fast AND slow bodies), and anything open.
