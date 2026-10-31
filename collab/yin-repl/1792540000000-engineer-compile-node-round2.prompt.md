Created-GMT: 2026-10-18 12:35:00 GMT
Created-Local: 2026-10-18 19:35:00 +07 (+0700)
Coding-Agent: claude-sonnet-5-5

# Task: the (compile ...) Node round — diagnose the empty string (the JVM half is fixed and committed)

WORK TREE: /Users/sto/workspace/datomworld-compilefix (branch compile-investigation, tip b8dbeabf).
STATE: the JVM half of the known gap is FIXED and verified — (compile "1 + 2") no longer dumps the
prelude (python-antlr 21/194 green on the JVM, incl. compile-previews-the-submitted-form-not-the-prelude-test
and compile-error-path-test). THE REMAINING SYMPTOM: on NODE the same test's output is the EMPTY
STRING — three failures reproduced just now (expected AST:/py/run-session/Error:, actual ""). The
generated parser is built in this worktree (bb gen:antlr ran; node_modules installed).

METHOD:
1. REPRODUCE deliberately on Node with a focused driver: drive the compile command through whatever
   the test uses and capture the raw return value at each layer (the command dispatch in the REPL
   loop → compile-command-ast → render-compile-output). Binary-search WHERE it becomes "": does the
   command even reach the compile branch on Node? Does lower-session/parser return nil? Does the
   render drop it?
2. Likely suspects to check (hypotheses — confirm or refute, label as such): a cljs-only branch in
   render-compile-output or in the AST preview printer that returns "" ; the compile branch's regex or
   command parse behaving differently in compiled cljs ; a Node-specific exception swallowed to "" ;
   the :render binding missing so the loop's output path drops the text.
3. FIX the Node cause. The contract stands: (compile "expr") returns the compiled preview of the
   submitted form (starting "AST:", <200k chars, no py/init!) and honest "Error: ..." on the error
   path, on EVERY host.
4. The two existing tests must pass on BOTH hosts (they are already in the tree — do not rewrite
   their assertions to accommodate ""; the assertions are the contract).

VERIFY (lanes SEQUENTIALLY — the documented race): the JVM python-antlr namespace (21/194 must hold),
the Node scoped run (clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge
'{:ns-regexp "python-antlr"}' — must go 21 tests / 0 failures), kondo 0/0 + cljstyle on changed
files. The Dart lane only if the command is Dart-reachable and cheap.

CONSTRAINTS: single turn, no approvals. Commit to branch compile-investigation. Do NOT push, do NOT
touch master. End with: the layer-by-layer trace of where "" appeared, the diagnosed cause (file:line),
the fix, the both-host test results, and anything open.
