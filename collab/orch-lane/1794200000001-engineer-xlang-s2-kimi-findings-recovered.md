1	Created-Local: 2026-11-03 08:4x +0700
2	Coding-Agent: kimi
3	Session: fresh (engineer round — slice 2)
4	Worktree: /Users/sto/workspace/datomworld-xlang (branch feat/xlang-matrix; slice 1 is merged to master — rebase not needed, build on this branch)
5	
6	# Task: xlang slice 2 — the Python and yang.clojure legs of the conformance matrix
7	
8	Slice 1 landed: test/yang/call/conformance.clj (the shared helper: donors published as modules, run unmodified under their own entry, exports projected into the caller namespace as task-local cells; linked runs on the three vector VMs) and test/yang/cross_lang_test.clj (the TS<->PHP legs through it, rewrites deleted).
9	
10	## Scope
11	
12	1. Extend test/yang/cross_lang_test.clj through the SAME helper with the new legs:
13	   - Python <-> TS and Python <-> PHP, both directions (the Python frontend's call routing is landed: its S3, efc99cc4; the Python prelude's call-kw and the yang.call wiring are the reference).
14	   - yang.clojure <-> one other frontend, both directions (yang.clojure.cljc requires yang.core.call; its application lowering is convention-wired).
15	   - The higher-order identity leg for each new pair: pass a function INTO the foreign callee, have it stored and called back, assert same-cell identity.
16	2. The helper may need generalizing if it assumes TS/PHP entries — generalize minimally, keeping the TS<->PHP tests untouched and green.
17	3. GATE: run ONLY yang.cross-lang-test (expect all new legs green) plus kondo/cljstyle on touched files. If a frontend genuinely cannot admit a foreign callable yet (check its prelude's yang.call wiring first), record the leg as a named skip in the findings and the test file with the reason — do not force it.
18	4. COMMIT on feat/xlang-matrix (feat(yang): the cross_lang matrix grows the python and clojure legs), no Co-Authored-By. Findings to collab/orch-lane/1794200000000-engineer-xlang-s2.kimi-k3.1-pro.findings.md (uncommitted).