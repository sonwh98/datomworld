Created-GMT: 2026-09-27 14:46:00 GMT
Created-Local: 2026-09-27 21:46:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (cross-host socket proofs)

# Task: Slice 3's cross-host socket proofs (clj<->Node, cljd->clj)

Role: QA & Verification (ZCode subagent, GLM-5.3-Flash)

Repository: /Users/sto/workspace/datomworld (branch master @ 83cc8bcd;
slice 4 is concurrently modifying dao.jing files — your files are
disjoint; do not touch anything under src/cljc/dao/jing/ or test/dao/
jing*).

Slice 3 (commit 83cc8bcd) delivered the ws channel composition
(src/cljc/dao/stream/ws_project.cljc) and its proof requires cross-host
socket runs. The implementer's report
(collab/1790485743084-vm-engineer-dao-stream-remote-slice3-r2.glm-flash.report.md,
"What the orchestrator must run") names them: compose
dao.stream.ws-project's acceptor/dial pieces with dao.stream.ws.node and
dao.stream.ws.dart in a per-host peer namespace (the dao.stream.slice-peer
pattern), then run:
1. clj->Node: clj listener serves, Node dials and reads.
2. Node->clj: Node's acceptor serves, clj's dial reflects back over the
   same connection.
3. cljd->clj: clj serves, the Dart peer dials.
(The browser dials-and-reads lane has no harness; skip it, note it.)

Work items:
1. Study the existing dao.stream.slice-peer pattern (test/dao/stream/
   slice_test.* and whatever peer namespaces exist from the yin.repl
   M5 work) and src/cljc/dao/stream/ws_project.cljc's acceptor/dial API.
2. Create the per-host peer test namespaces following that pattern,
   composing ws-project acceptor/dial with the ws node/dart adapters.
3. Run the three proofs. clj->Node and Node->clj need the Node lane
   (bb test:cljs or targeted shadow compile + node); cljd->clj needs
   the Dart lane (bb test:cljd). Run them SEQUENTIALLY, solo.
4. Report exactly what ran, what passed, and any environment limit hit.

Constraints: NEW test namespaces only (plus, if the slice-peer pattern
requires, a small peer source namespace under test support paths —
mirror the existing pattern's file layout). Do NOT modify src/,
docs/, or any existing test. ASCII, <= 80 cols on added lines,
cljstyle/kondo clean on new files, no commit/stage/checkout/reset/
stash, no diagnostics.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
