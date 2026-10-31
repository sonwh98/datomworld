Created-GMT: 2026-09-28 12:08:00 GMT
Created-Local: 2026-09-28 19:08:00 +07 (+0700)
Coding-Agent: claude
Session-ID: 7fcb4501-80b2-43a3-8511-32424dd6f0d4
# Task: yin.repl automatic code indexing — dao.space.index observer on program-out, covered indexes published to dao.jing

Role: Yin.VM Runtime Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-09-28 19:08:00 +07 (+0700) | Status: active | Rationale: owner-requested feature with a ruled design; claude CLI is the working implementer route

WORK TREE: /Users/sto/workspace/datomworld-repl-index (branch repl-code-index, from master 1a52b61c). Edit ONLY there.
Do not touch /Users/sto/workspace/datomworld (another slice is in flight there). Do not stage or commit. mise is
trusted and node_modules is installed in the worktree. Read this brief at its absolute path:
/Users/sto/workspace/datomworld/collab/1790597280000-vm-engineer-repl-code-index.prompt.md

OWNER REQUEST (verbatim): "can you add a dao.space.index observer on the dao.stream that the REPL append! semantic
tuples? dao.space.index should create covered indexes of the semantic tuples and save it to dao.jing"

OWNER DECISIONS (verbatim selected options):
- Stream: "program-out (Recommended) — Per the ruled design: index expanded code, i.e. what actually executes. Macro
  calls appear already expanded; a failed expansion indexes nothing."
- Publish: "Every eval round (Recommended) — Transact the program's facts, then transactor/publish! after each
  forwarded evaluation. Simple and always durable; adds a publish to each round."

Read first (in the worktree):
- docs/design/yin.repl.dao.space-index.md — the governing, owner-ruled design (Composition, Indexed facts, Cost)
- docs/design/yin.vm.code-as-tuples.md §6.5 (datom projection) and §7.1
- docs/design/datom.world.md invariants
- src/cljc/yin/repl.cljc: make-session (~523-554), make-attachment (~480), run-expander-stage (~784),
  run-evaluator-stage, eval-program (~1128), reset / VM-selection rebuild
- src/cljc/yin/vm.cljc: ast->datoms (~827), ast->datoms-with-root (~714), datoms->tx-data (~695)
- src/cljc/dao/space/transactor.cljc (create!, transact!, publish!), src/cljc/dao/space/index.cljc (publish-index!)
- existing tests: test/yin/repl*_test.cljc, test/dao/space/transactor_test.cljc (fixtures for store/intake pool)
- docs/agents/build-n-test.md

Scope (the owner's literal request):
IN: a second, independently advanced observer on program-out (:row-stream), built in make-session beside the
evaluator's :row-observer; per forwarded packet, project the expanded tree to [e a v t m] AST facts per §6.5 (e is a
transactor-local id with :yin/address back to the node address — never the node identity), commit the program's facts
as ONE atomic transaction record via the dao.space transactor (transactor allocates t; the shell supplies no clock),
then publish! the covered indexes to dao.jing after each forwarded evaluation round. Provenance per the design: the
shell's stable session token as a fact on a metadata entity, referenced from m (local integer, not a string). Index
code only (no printed output, last-value history, or eval results). Same gap/reset discipline as the evaluator reader:
a lost packet is reported, never called indexed; reset and VM selection rebuild the observer with the session.
The observer consumes packets whether the VM returns, parks, or raises.
OUT (do not implement; name as deferred in your report): the separate $ast row relation / dedicated AST indexer,
binding q on user require, remote/content-service exposure.

Invariants: no hidden global state (transactor/store are session-composition values), no callbacks (the round
drives the observer's step), evaluator and indexer stay peer observers ignorant of each other (neither calls the
other; indexing never sits in a loader, runner, frontend, or the link pair). Portable CLJC for CLJ/CLJS/CLJD (on
CLJD #?(:clj ...) is NOT excluded — use #?(:cljd nil :clj ...) with :cljd first; no cross-ns #'private access).
dao.space.transactor/create! requires a complete-retention transport (dao.stream.memory-log), not a ring buffer.

Allowed files: src/cljc/yin/repl.cljc; new namespace(s) under src/cljc/yin/repl/ if you factor the observer out;
their tests under test/yin/repl/. Any other file (dao.space.*, yin.vm, dao.jing) needs authorization: stop and report.

Acceptance:
1. After evaluating a program, the transactor's history holds exactly one transaction for it, whose datoms equal
   ast->datoms of the expanded tree (modulo local ids) with :yin/address links and the provenance m.
2. After the round, publish! has produced a manifest whose covered indexes, read back from the dao.jing store, contain
   those facts (assert via the index/query read path, not only the manifest's existence).
3. A failed expansion adds no transaction; a program that raises or parks is still indexed.
4. Evaluator results unchanged: existing yin.repl tests pass untouched.
5. A gap on the index reader is reported, not silently indexed; reset rebuilds the observer.

Verify and report exactly (commands + counts), run in the worktree: clj -M:kondo --lint <changed files>; focused JVM
over the yin.repl test namespaces you touched plus dao.space.transactor-test; bb test:cljs if feasible. Do NOT run
bb test:cljd (the orchestrator owns that lane). Known intermittent: yin.repl.main-test cross-process tests may fail
on remote-value nil — report it but do not try to fix it.

Write the report to /Users/sto/workspace/datomworld/collab/1790597280000-vm-engineer-repl-code-index.claude-opus-5-5.report.md
and give it as your final response, beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 7fcb4501-80b2-43a3-8511-32424dd6f0d4
Report changed files, exact test outcomes, design choices where the design left latitude, deferred items, and
unresolved concerns. Do not claim edits or tests that did not occur.
