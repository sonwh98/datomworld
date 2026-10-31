Created-GMT: 2026-10-18 05:55:00 GMT
Created-Local: 2026-10-18 12:55:00 +07 (+0700)
Coding-Agent: claude-sonnet-5-5

# Task: antlr-multi m012e — add the NEWLINE/DEDENT disagreement fixtures (the last re-gate item)

WORK TREE: /Users/sto/workspace/datomworld-antlr-multi (branch antlr-multi, tip 25a908fb). The codex
re-gate resolved P1-1, P1-2 and P2 and accepted the per-host coordinate pinning and the token-ordering
debt. ONE item remains (its words): the corpus pins INDENT/EOF at cst_export_test.cljc:129, but the
READY'd plan review required NEWLINE/DEDENT disagreement fixtures, which are absent.

READ: collab/1792400000000-reviewer-antlr-multi-m012-regate.gpt-6.1-sol.stdout.log (the re-gate log —
its last agent message has the exact requirement and a probe).

DO:
1. Add the NEWLINE and DEDENT helper-token error-coordinate disagreement fixtures to
   test/yang/python/antlr/cst_export_test.cljc alongside the INDENT/EOF pins. The re-gate's own probe
   source `"if x:\n    x =\n"` reproduces the NEWLINE discrepancy (its JVM probe: line/column [3 0],
   span [13 14] — verify on your run; do not copy blindly). Design the DEDENT case the same way (a
   source whose bad dedent triggers the helper-token disagreement).
2. Pin PER HOST (the established pattern): each host's actual line, column and byte span, from the
   failing assertions on that host — never copied from another host or hand-invented. Also assert the
   error packets CONFORM on each host (the strict oracle).
3. Run: the JVM focused namespaces (cst-export-test, python-antlr-test) and the Node + Dart quick
   lanes (mise exec -- bb test:cljs / test:cljd; on "Patch does not apply", rm -rf build/antlr
   lib/antlr and rerun — known). The fixtures must exercise on all three hosts.
4. kondo 0/0 on the changed file; cljstyle check it (if denied, say so).

CONSTRAINTS: single turn, no approvals. One commit to antlr-multi ("test(yang.antlr): the
NEWLINE/DEDENT disagreement fixtures on three hosts" or similar). Do NOT push, do NOT touch master.
End with: the fixture sources, the per-host pinned coordinates (old-school table), the lane results,
and anything open.
