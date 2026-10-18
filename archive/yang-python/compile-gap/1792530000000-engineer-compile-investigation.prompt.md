Created-GMT: 2026-10-18 12:00:00 GMT
Created-Local: 2026-10-18 19:00:00 +07 (+0700)
Coding-Agent: claude-sonnet-5-5

# Task: diagnose and fix the (compile ...) defect under the antlr Python frontend

WORK TREE: /Users/sto/workspace/datomworld-compilefix (branch compile-investigation, HEAD = master
05137a1e+). THE KNOWN GAP (docs/agents/known-gaps.md, P2): `(compile "1 + 2")` under
`:yang.python/antlr` — on NODE it returns an EMPTY string; on the JVM it WORKS but prints the WHOLE
prelude (24.8M characters, ~15 s) for a tiny form. Not V8's string limit; the error path would print
"Error: ...". Pre-existing under (lang :yang.python/antlr); the M3 retirement made it reachable via
(lang :python). The cause is unknown.

METHOD (per the owner's standing process — reproduce first, diagnose second, fix third):
1. REPRODUCE: on the JVM and on Node, run the REPL (or a probe driver) with the antlr frontend and
   `(compile "1 + 2")`; capture the exact outputs (the Node empty string; the JVM's 24.8M dump — pipe
   through wc -c / head -c, don't print it all).
2. DIAGNOSE: read the compile path (yang/python/antlr/repl.cljc and wherever (compile) dispatches in
   yin/repl.cljc / the frontends catalog). Hypotheses to check (your job to confirm or refute): does
   the antlr compile path return the lowered MODULE TEXT including the embedded prelude instead of
   the compiled form? Does the Node path return nil/"" because a cljs-only branch fails silently?
   Trace the return value's construction. Label your hypotheses as hypotheses until the trace
   confirms them.
3. FIX: the contract — (compile "expr") should return a compact compiled artifact (or an honest
   error), not the prelude dump, and it must not silently return "" on any host. Match whatever the
   design says the compile command yields (check yin.repl.md / yang.antlr.md for the command's
   contract; if the docs don't define it, define the minimal honest behavior: the compiled artifact
   of the SUBMITTED form, with the prelude implicit — and say so in the report).
4. TEST: a regression test per host (JVM + Node + Dart where the command is reachable): the returned
   value is non-empty and does NOT contain the prelude's body markers; plus the error path test.

VERIFY: the focused namespaces (python_antlr_test, main_test, repl_test), the JVM quick tier, and the
Node lane (bb test:cljs; run Dart only if the command is Dart-reachable). kondo 0/0 + cljstyle on
changed files. The lanes in one worktree run SEQUENTIALLY (the documented race).

CONSTRAINTS: single turn, no approvals. Commit to branch compile-investigation. Do NOT push, do NOT
touch master. collab/ stays untracked. End with: the reproduction captures, the diagnosed cause
(file:line), the fix, the contract statement, the test results, and anything open.
