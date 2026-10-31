Created-GMT: 2026-09-27 15:30:00 GMT
Created-Local: 2026-09-27 22:30:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (dao.stream.remote slice 5)

# Task: dao.stream.remote Implementation — Slice 5 (REPL as a service + the copy-path retirement)

Role: Stream & Network Engineer (ZCode subagent, GLM-5.3-Flash)

Repository: /Users/sto/workspace/datomworld (branch master; slices 0-4
are committed by the time you start — verify with git log).

Implement Slice 5 exactly as the plan defines it. Read first, in order:
- docs/design/dao.stream.remote.implementation-plan.md section 5 (the
  fate of every path — your slice executes the yin.repl.serve,
  dao.stream.serving, dao.stream.rpc.ws, and apply-envelope rows, plus
  the deferred dao.stream.ws accept-frame/served-table retirement from
  slice 3) and the slice-5 row of section 3
- docs/design/dao.stream.remote.md section 5 (the request-and-response
  service convention; :op/eval as payload; the URL becomes a remote
  descriptor for the pair)
- src/cljc/yin/repl/serve.cljc and its test (the module you rewrite),
  src/cljc/dao/stream/serving.cljc + serving_test.cljc (deleted),
  src/cljc/dao/stream/rpc_ws.cljc or rpc/ws (deleted),
  src/cljc/dao/stream/apply.cljc's wire envelope (retired; the AST node
  and FFI bridge keep the name)
- The ws.cljc pointer comments from slice 3 (the :ws/accept frame at
  ~:554-556 and the :served table at ~:410-497): with dao.stream.serving
  and yin.repl.serve's copy path deleted in this slice, their last
  consumers are gone — retire them NOW per the plan (the frame send,
  the disclaim branch, the served table), and update
  test/dao/stream/ws_test.cljc, ws_codec_test.*, ws/*_test to match
  (their assertions on the retired frames/table change with the
  retirement)

Work items:
1. Rewrite yin.repl.serve as an eval service over requests/answers
   streams: :op/eval as payload; connect resolves a
   daostream:ws:// URL to a remote descriptor pair (per the plan's
   convention-over row); the demo REPLs work. The shared-shell
   privilege is the interpreter's policy.
2. Delete dao.stream.serving and dao.stream.rpc.ws; rework or retire
   dao.stream.rpc (the plan allows either: keep its state machine as
   one vocabulary over reflections, or retire — choose per what the
   consumers need, note the choice).
3. Retire the apply wire envelope from dao.stream.apply (the AST node
   and FFI bridge keep the name).
4. Execute the deferred ws retirements (slice 3's debt): the :ws/accept
   frame send, the disclaim branch, the served-path table, updating
   the ws tests that pinned them.
5. Tests: (connect "daostream:ws://...") resolves to a remote
   descriptor pair; the demo REPLs work over the wire; the ws unit
   tests match the retired frames/table.

Constraints:
- This slice deletes modules: no backward-compat shims (standing rule).
  Consumers outside yin.repl/dao.stream were handled by slices 0-4;
  if you find a live consumer of a module you must delete that slices
  0-4 did not migrate, STOP and report BLOCKED with the list.
- Pure ASCII, <= 80 columns on every added/edited line; cljstyle and
  kondo clean; no commit/stage/checkout/reset/stash; no leftover
  diagnostics.
- Verify: JVM full suite green (current baseline 2,272/183,324/0; the
  counts move as modules are deleted — report exact), Node green, Dart
  green. Sequential, solo. Exact counts.
- If the plan and the tree conflict, STOP and report BLOCKED with the
  specific conflict.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
