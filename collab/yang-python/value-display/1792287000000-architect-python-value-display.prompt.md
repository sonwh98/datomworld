Created-GMT: 2026-10-16 21:40:00 GMT
Created-Local: 2026-10-17 04:40:00 +07 (+0700)
Coding-Agent: claude-cloud
Session-ID: 87936a9b-d9e2-4041-8282-22b37f92c121

# Task: Architect design — value display for the ANTLR Python frontend at the REPL

Role: Lead System Architect (design authorship; the owner corrected the orchestrator for dispatching
implementation before this design pass)

WORK TREE (read-only context): /Users/sto/workspace/datomworld-c4fix — branch yang-python-value-display
carries an UNCOMMITTED DRAFT implementation of value display by sonnet-5.5 (dispatched before this design
pass — treat it as prior art to rule on, not as a spec). Its report:
/Users/sto/workspace/datomworld/collab/1792278000000-engineer-python-value-display.claude-sonnet-5-5.stdout.log.
The landed base is master 123c12eb (includes the prompt-owed? fix and the cbor hint).

THE QUESTION (owner-reported): at the yin.repl prompt with the ANTLR Python frontend, evaluating
`even_squares` (a bare name, or any expression) prints nothing. CPython's interactive contract: every
expression STATEMENT's value goes through sys.displayhook — repr(value) printed, None suppressed.
Assignments print nothing. The frontend today is deliberately output-only
(:display :output, frontends.cljc: "shows what a submission printed and not the program's value").

DESIGN THE MECHANISM, ruling at minimum:
1. Where the value is captured: lower-time (an interactive mode marking the last expression statement)
   versus session-time (the lower-session captures the evaluation's result) versus display-time. The
   draft implemented lower-time marking + an IN-BAND marker string ("\0display") through the output
   path — rule on in-band vs out-of-band (a side channel carries the value out instead of a sentinel in
   the output stream).
2. The semantics: repr rendering (the render layer's py/repr), None suppression, the last statement vs
   the last expression statement, multi-statement submissions, interaction with explicit print output
   ordering.
3. Who owns it: the frontend binding's :display policy (a new :value+output policy), the lower-session,
   or the render layer.
4. Cross-host: the mechanism must work on JVM, Node and Dart (the ANTLR frontend is becoming
   multi-host — the parallel antlr-multi-target round is adding Node/Dart parser workers).
5. Interaction with the landed prompt-owed? contract (input consumed ⇒ prompt owed — the display is the
   output side, independent).
6. The CPython compatibility line: what CPython does (displayhook, sys.displayhook override, the last-
   expression-only rule, None suppression) and what yang matches vs documents as divergence.

Deliverable: the design (the mechanism, the semantics, the boundaries, the cross-host story), the
acceptance test contracts, and an implementation slice specification for the engineer. Read the draft
implementation in the worktree as prior art.

Begin the final response exactly with:
Completed-GMT / Completed-Local
