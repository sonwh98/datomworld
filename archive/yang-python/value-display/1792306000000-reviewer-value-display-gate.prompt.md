Created-GMT: 2026-10-17 12:30:00 GMT
Created-Local: 2026-10-17 19:30:00 +07 (+0700)
Coding-Agent: deepseek
Session-ID: d35df89d-747f-4261-9f84-4ef6362bf36d

# Task: Independent gate — the value-display feature for the ANTLR Python frontend at the REPL

Role: Reviewer (adversarial-lite landing gate; deepseek over the sonnet author per reviewer independence)

WORK TREE (read-only): /Users/sto/workspace/datomworld-c4fix (branch yang-python-value-display; the diff vs
its base: git diff HEAD — src/cljc/yang/python/antlr/lower.cljc, src/cljc/yang/python/antlr/repl.cljc,
test/yin/repl/python_antlr_test.cljc).

CONTEXT: the owner reported that evaluating a bare name at the ANTLR Python prompt prints nothing. CPython
interactive semantics: every expression statement's value goes through sys.displayhook — repr(value)
printed, None suppressed; assignments print nothing. The feature: lower-module-body takes display?; when
true, the last top-level statement is marked if it is a bare expression statement; the marked statement
lowers to a check (None does nothing, otherwise py/print with a marker string followed by the value);
repl.cljc's report renders marker-prefixed entries as repr(value) through the render layer. The
frontend's :display :output stays unchanged (the value arrives through the output path).

Already-run evidence (do not re-run): the engineer's yin.repl.python-antlr-test 11/98 green (the cases:
xs prints [4, 16]; 1+2 prints 3; None prints nothing; an assignment prints nothing; y = 2; y prints 2;
print(7) prints 7; 'a' prints 'a'); kondo 0/0. The orchestrator's step-6 gaps: cljstyle exit 0; the e2e
lanes green (42/714/0, 7/123/0).

REVIEW adversarially:
1. The marker-in-band design: the marker string (the engineer's "\0display"-class sentinel) distinguishes
   display entries from user print output — what happens when a USER print emits the marker string (the
   engineer flagged it as practically unreachable — verify the reachability claim), and is there an
   out-of-band alternative worth the cost at this layer?
2. The semantics vs CPython: only the LAST top-level statement of a submission displays, and only if it
   is a bare expression (the engineer's reading of the ambiguous spec: "y; z = 1" prints nothing — is
   that the right reading?); None suppressed; semicolon-separated statements: the last ;-statement's
   value displays (verify the implementation matches the claimed reading).
3. The prompt-owed? interaction: the value arrives through the output path, so the landed prompt-suppress
   contract (a tick with lines owes the prompt) composes — verify no double-prompt or missing-prompt path
   opens.
4. Cross-host: the mechanism on cljs/cljd (the marked lowering is portable; the render layer's repr) —
   the engineer ran no Node/CLJS lane; assess from the code.
5. Scope: nothing unrelated; the landed prompt-suppress regressions untouched.

Verdict: end with exactly one line "Verdict: READY (sign-off granted)" or "Verdict: REQUEST CHANGES" with
severity-tagged findings and file:line evidence. Begin the final response exactly with:
Completed-GMT / Completed-Local
