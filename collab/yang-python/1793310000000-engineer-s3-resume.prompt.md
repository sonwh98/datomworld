Created-GMT: 2026-10-30 03:10:00 GMT
Created-Local: 2026-10-30 10:10:00 +07 (+0700)
Coding-Agent: codex (fresh session)

# Task: S3 resume — resolve the rebase, reconcile with the FINAL shared runtime, complete the wiring

WORK CHECKOUT: /Users/sto/workspace/yang-common-call → /Users/sto/.codex/worktrees/yang-common-call/datomworld.
STATE: a rebase of the S3 WIP (3723365b) onto master 64f2885a (the yang-call merge — the FINAL
shared runtime is now your base) is MID-FLIGHT with two conflicts:
1. AA test/yang/python/antlr/call_entry_test.cljc — your S1 binder fixtures (85 lines) vs the
   yang-call branch's own call_entry_test (28 lines, testing "Python's source call helper resolves
   Python forms then uses the shared runtime"). RESOLUTION: keep BOTH bodies in one file — the
   binder fixtures AND the source-call-helper test (rename one ns suffix if needed, or merge into
   one coherent test ns; the tests are complementary, not competing).
2. UU test/yin/vm/semantic/scanner_goldens.cljc — both sides moved :py (your S1 prelude edits; the
   runtime's bundled-prelude emission). RESOLUTION: take either side now, then run
   test/resources/yin/vm/semantic/scanner-golden-c4.generate.clj with deferred=#{:py :pysp} AFTER
   resolving everything else — the generator is the true resolver on the merged surface; an
   unpredicted abort is a FINDING. Double-run byte-identical; restore #{:pysp}.
3. src/cljc/yang/core/call.cljc auto-merged (M) — REVIEW the combination: your WIP's speculative
   edits vs the FINAL runtime. The shared runtime file belongs to the TS/PHP orchestrator: prefer
   THEIR side for any semantic difference; your WIP's call.cljc edits were pre-publication probes
   and are superseded unless they are additive fixes the final code still needs (justify each kept
   hunk in the report).

THEN COMPLETE S3 per the original brief (collab/1793300000000-engineer-python-s3-wiring.prompt.md,
in the MAIN tree's collab/): the Python :call routing through the shared convention, both emitters
carrying the yang.call runtime, the cross-frontend identity fixture's Python legs (the S1 fixture
98390529's pattern), the source-call-helper path the yang-call side anticipated (their 28-line
test shows the intended shape — reconcile your implementation with it).

VERIFY (laptop-unload rule — shiva for the full lanes; a fresh clone
~/ci/datomworld-python-s3 from ~/ci/datomworld-python-s1, checkout YOUR branch): the JVM focused
namespaces (lower-portable, cst-export, python-antlr + the reconciled call-entry ns), the JVM quick
tier, bb test:cljs, bb test:cljd. kondo 0/0 + cljstyle on changed files. Local single focused runs
allowed for iteration, one at a time.

CONSTRAINTS: single turn; no approvals; commit to python-s3-wiring (the rebase completes it); do
NOT push; do NOT touch master (master 64f2885a is the owner's local push state — the landing
push happens after your gate, coordinated with the other seat's push of 64f2885a). End with: the
conflict resolutions, the call.cljc kept/superseded hunk map, the routing flow, the generator
finding, the lane table, and anything open.
