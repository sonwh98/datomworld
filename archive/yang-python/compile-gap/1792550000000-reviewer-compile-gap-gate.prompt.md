Created-GMT: 2026-10-18 13:00:00 GMT
Created-Local: 2026-10-18 20:00:00 +07 (+0700)
Coding-Agent: claude-deepseek-flash

# Task: the (compile ...) known-gap landing gate — three commits closing the P2 gap (read-only)

WORK TREE: /Users/sto/workspace/datomworld-compilefix (branch compile-investigation, tip fbfb82b6).
THE DIFF: `git diff origin/master fbfb82b6` — three commits closing the known-gaps P2 row
("(compile ...) under :yang.python/antlr: Node returns an EMPTY string; the JVM prints the whole
prelude"):
1. b8dbeabf (yin/repl.cljc + the tests): compile-command-ast lowered against the shell's
   frontend-session, which is nil on a fresh session, so the AST embedded the whole prelude and the
   preview rendered it four times (the 24.8M-char JVM dump). The fix lowers with :frontend-session
   :primed; the real session untouched.
2. dbe1d14b (cst.cljs): on Node, first-failure's NoViableAltException branch read the FILLED JS token
   buffer's size as the failing token — every such error classified :end, the completeness probe said
   :incomplete, eval-input parked the line and returned "" — the compile command never ran on Node.
   The branch is dropped; the listener's offending token decides, like every other error.
3. fbfb82b6 (cst.cljd): the identical mirror for Dart, applied after the Dart lane failed exactly the
   two compile tests and passed all 4039 after.

VERIFY, adversarially:
1. The priming fix: :frontend-session :primed on the lowered state — does anything downstream read
   :frontend-session in a way that makes :primed lie (e.g. the AST events claiming a primed session)?
   Is the real session genuinely untouched (the modified state not returned)?
2. The error-classifier change: dropping the buffer-length branch — does any OTHER caller depend on
   first-failure returning :end for genuine end-of-input failures (the listener's offending token
   must still yield :end when the failure really is at EOF — check export/failure-kind's contract and
   the tests that assert :end)?
3. The contract: the tests assert the output starts "AST:", is <200k chars, contains no py/init!,
   contains py/run-session, and the error path returns "Error: ...". Is this an honest compile
   preview contract? (yin.repl.md / yang.antlr.md did not define the command's output — the
   orchestrator's fallback ruled: the preview of the submitted form, the prelude implicit.)
4. The cross-host identity: JVM 21/194, Node 21/70, Dart 4039 all passed — the assertion-count
   differences between hosts are pre-existing (per-host langs), confirm nothing in the diff widens
   them.
5. The known-gaps row: confirm the diff closes the gap's substance (both symptoms) and that no
   unrelated behavior changed (git diff --stat vs the three files + the test file).

LANES ALREADY RUN (do not redo): the three-host results above; kondo 0/0 and cljstyle clean on all
changed files.

Verdict: end with exactly one line "Verdict: READY (sign-off granted)" or "Verdict: REQUEST CHANGES"
with findings. Begin the final response exactly with Completed-GMT / Completed-Local.
