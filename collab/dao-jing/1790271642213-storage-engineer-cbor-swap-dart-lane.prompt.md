Created-GMT: 2026-09-24 18:07:00 GMT
Created-Local: 2026-09-25 01:07:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (cbor-swap dart-lane fix)

# Task: Fix the three Dart/CLJD failures in the DaoJing CBOR swap tree

Role: Storage & Indexing Engineer (ZCode subagent, GLM-5.3-Flash)

Repository: /Users/sto/workspace/datomworld, branch dao-jing-cbor-swap,
uncommitted working tree (the CBOR swap delta after two review/fix
rounds — see collab/1790245519256-reviewer-daojing-cbor-swap.*.findings.md,
collab/1790264589986-reviewer-daojing-cbor-swap-fixes-r2.gpt-6-sol.findings.md,
collab/1790267002049-reviewer-daojing-cbor-swap-fixes-r3.glm-flash.findings.md).

The r3 process finding demanded a green Dart lane before merge; the
orchestrator ran it (log preserved at target/test-cljd-verify.log) and it
failed: 1,900 passed, 3 failed, all in files the delta touches:

1. dao.jing-test/content-hash-keeps-the-set-tag-outside-the-value-domain
2. yin.vm-test/semantic-bytecode-strips-reader-positions-inside-payloads
3. yin.vm.pipeline-test/list-literals-persist-in-memory

All three pass on JVM (full suite 1,995/0) and Node (1,911/0); they fail
only on Dart, so this is Dart-host behavior in the delta's changed code
(cbor.cljc Dart branches, debruijn.cljc :cljd classifier arm, or the
decode-snapshot split are the likely suspects). The runner prints only
names with an [E] marker, not details.

Task:
1. Extract the real failure output for each of the three (the compiled
   tests live under test/cljd-out; try `dart test` with a --plain-name
   or --name filter over the compiled suite from the repository root, or
   whatever the cljd setup supports; if the runner truly hides details,
   add a temporary diagnostic print inside the failing test, run it, and
   remove it afterwards).
2. Root-cause each failure against the delta's changes. Distinguish:
   a genuine defect the delta introduced on Dart (fix the source), vs a
   test expectation that is host-wrong (fix the test, e.g. compare
   portable content rather than host shapes — the established pattern
   from the Node float64 round: cbor/content=).
3. Fix within the delta's already-touched files only (src/cljc/dao/jing*,
   src/cljc/dao/jing/cbor.cljc, src/cljc/yin/vm/{debruijn,semantic}.cljc,
   and the three test namespaces). Anything that would need a file outside
   that set: stop and report BLOCKED with the evidence instead.
4. Verify ALL THREE lanes on the final tree and report exact counts:
   - Dart: mise exec -- bb test:cljd  (must be 0 failures)
   - JVM:  mise exec -- clojure -M:test
   - Node: mise exec -- clj -M:cljs -m shadow.cljs.devtools.cli compile
     slice-peer test
   You own the CLJD lane exclusively while you work — no other process
   will run it. Run the lanes sequentially, not concurrently.

Constraints:
- Pure ASCII, <= 80 columns on every line you add or edit.
- Do NOT commit. Remove any temporary diagnostics before finishing.
- The JVM and Node suites must remain at 0 failures.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report per-failure root cause and fix with file:line evidence, then the
three lane counts. End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
