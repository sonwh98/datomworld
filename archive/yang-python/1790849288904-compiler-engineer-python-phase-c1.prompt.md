Created-GMT: 2026-10-01 10:08:08 GMT
Created-Local: 2026-10-01 17:08:08 +07 (+0700)
Coding-Agent: claude
Session-ID: 976059c2-629b-4b91-8b63-7c4d9604d7cf (resumed; the spike engineer)
# Task: Python phase C, slice C1 — finally/with, tuples/slices, operators, comprehensions, keyword arguments, gate P3s

Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-01 17:08:08 +07 (+0700) | Status: active | Rationale: author of the spike (phases A, B); continuity

WORK TREE: work ONLY in /Users/sto/workspace/datomworld-py-c1 (branch yang-python-phase-c1 from master 60b60898, which
now contains the spike bf6c5544 and heap reclamation 60b60898). Do not touch other worktrees. Do not stage or commit.

OWNER (verbatim): "go ahead with 4 and 5" (plan item 4 = Python phase C). Owner direction (verbatim, binding):
"integrating antlr should be straight forward ... mapping from antlr AST to it should be a straight forward but tedious
mapping"; "if yin.vm universal AST has continuations, all control flow can be mapped to continuations"; the dynamic
stream pipeline ("any number of interpreters can attach to those dao.stream to do more transformation of its own").
Context (this worktree's collab/): your spike brief and final report; the glm r2 gate findings (5 P3s); the Python
mappability ruling (bucket 1 items and owner decisions); the mutable-objects ruling.

C1 scope (lowering + prelude only; no VM changes; no new AST tag):
1. finally and with: escape wrappers (dynamic-wind style) so EVERY exit — normal, return, break, continue, raise —
   runs pending finally blocks in order and with's __exit__ (with exception info when raised; a truthy __exit__ result
   suppresses). Handler-stack consistency across nested finally/try/loops; tests for each exit kind and nesting.
2. Tuples (immutable, value semantics, hashable as dict keys when elements are) and tuple targets / unpacking
   (a, b = ...; for k, v in ...; nested; star target if simple, else rejected); slices on list/tuple/str
   (start/stop/step, negatives, clamping per Python) and slice assignment on lists if simple (else rejected).
3. Operators: ** (int and float), %, // (Python floor semantics incl. negatives; ZeroDivisionError), bitwise & | ^ ~
   << >> on ints, in / not in (list, tuple, str substring, dict keys, set if present), augmented forms.
4. Comprehensions: list/dict/set comprehensions and generator expressions consumed eagerly where a generator is not
   observable (e.g. as the sole argument to list/len/sum) — otherwise reject generator expressions until C2;
   Python 3 comprehension scope (own scope; the first iterable evaluated in the enclosing scope).
5. Keyword arguments at call sites, **kwargs, keyword-only parameters, default/keyword binding errors as TypeError.
6. The 5 P3 notes from the glm r2 gate: implicit object base and prelude exception classes via the module dict where the
   ruling says so; `global globals; globals()`; out-of-range \U escapes as a qualified diagnostic; reject leading-zero
   decimal literals (0755) per Python 3; repr deviations (UnboundLocalError.args, control characters) where cheap.
Everything else stays rejected with a qualified diagnostic (generators/yield -> C2; big ints, imports, linked prelude,
REPL catalog -> later slices). Keep the "every grammar rule classified" test green and update it.

Tests: e2e programs for every item on all four VMs over the real cell and data modules; lowering goldens where the shape
matters; each new semantics test must fail when its part is reverted (prove by temporary mutation, then restore).
Portability: every reader conditional in .cljc needs a :cljd branch listed FIRST; protocol methods never use duplicate
parameter names (e.g. [_ _x], not [_ _]) — ClojureDart casts the this-parameter.

Verify — EVERY command in the FOREGROUND: kondo (files as separate args); cljstyle check per file (say if blocked);
bb build:yin-repl-node then bb gen:python-antlr (needed by the JVM suite in a fresh worktree); focused JVM; full
clj -M:test; bb test:cljs. NOT bb test:cljd.

Write the report to /Users/sto/workspace/datomworld-py-c1/collab/1790849288904-compiler-engineer-python-phase-c1.claude-opus-5-5.report.md
and give it as your final response, beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 976059c2-629b-4b91-8b63-7c4d9604d7cf
Report: what is supported now vs still rejected; the rule->UAST changes; prelude additions; exact test outcomes; the
mutation proof; which constructs were mechanical and which were not; unresolved concerns.

## Round 2 (orchestrator) — gate REQUEST CHANGES (qwen3.8-max via cmd)
Gate findings (copy in this worktree's collab/): 1790852251711-reviewer-python-phase-c1-gate.qwen3.8-max.findings.md
(cmd session 735ea0f5-f108-41aa-8a10-0f9221aa416e). Orchestrator lanes on your round-1 code: JVM 2695/187529/0, Node
2520/53195/0, CLJD +2475 passed; cljstyle reformatted lower.cljc and prelude.cljc (whitespace, keep).
Required:
1. P1 tuple of classes: except (A, B): and isinstance(x, (A, B)) must match any element (recursively for nested tuples);
   a non-class / non-tuple except expression raises TypeError when matched. e2e on all four VMs.
2. P2 integer bound: guest OverflowError whenever an int result leaves +-2^53 (ipow multiply step, lshift, py/arith's
   int path incl. + - *, unary neg, and any other int-producing op you find), identical on every host; parity cases at
   the boundary (2**53, 2**53+1, 3**40, 1<<52 vs 1<<53, -(2**53)-1) on JVM and Node.
P3 — fix where cheap, otherwise record as a documented deviation (say which, in the report):
 a. break/continue inside finally -> static syntax error (Python 3.8+).
 b. raise of a non-BaseException value -> TypeError "exceptions must derive from BaseException"; `except E as e:`
    unbinds e after the handler.
 c. len(range(...)) and x in range(...) in O(1) closed form.
 d. dict/set iteration that changes size -> RuntimeError (snapshot or size stamp).
 e. float // and % last-ulp deviation: document (or add the fmod correction if simple).
 f. builtins rejecting keyword args; C(1, 2) without __init__ -> TypeError; with-statement dunders looked up on the type.
Mutation-prove each new test. Run EVERY check in the FOREGROUND: kondo (separate args), cljstyle check per file (say if
blocked), bb build:yin-repl-node, bb gen:python-antlr, focused JVM, full clj -M:test, bb test:cljs. Not bb test:cljd.
Append a "Round 2" section; give the full report as your final response (same header).

## Round 3 (orchestrator) — gate r2 REQUEST CHANGES (gpt-6.1-sol, fresh session; cmd paused by owner)
Gate (copy in this worktree's collab/): 1790856811617-reviewer-python-phase-c1-gate-r2.gpt-6.1-sol.findings.md, thread
01a0f762-755f-7913-985d-6782759f2dd9. State: your work is COMMITTED as 7a8493e1 on top of master 6b8502fd (D7 host-typed
closures landed; rebase was clean). Make round-3 changes as ordinary uncommitted edits on top of 7a8493e1.
Required:
1. P1 prelude.cljc:1180 range iteration host divergence: compute elements with exact bounded arithmetic (no oversized
   i*step intermediate); add the reviewer's case to the cross-host parity tests (JVM and Node).
2. P2 prelude.cljc:1192 / :906 range len and membership: split quotient/remainder arithmetic; bound the guest RESULT, not
   internal differences (len(range(-2**53, 2**53, 2**53)) == 2).
3. P2 lower.cljc:639 any/all over a generator expression: short-circuit (lower to early-exit traversal) or reject until
   C2; regressions with exceptions and side effects.
4. P2 prelude.cljc:704 float %: compute the remainder without the bounded quotient (1e20 % 3.0 == 1.0).
5. P2 prelude.cljc:1172 range argument validation: py/int? on all args, booleans via py/num, guest TypeError before
   arithmetic (range('a')).
6. P3 prelude.cljc:451 builtin exception constructors reject keywords (explicit __init__ still binds keywords).
7. P3 lower.cljc:602 allow * after explicit keywords (track keywords vs ** separately); Python's binding then raises the
   duplicate-value TypeError for f(a=1, *[2]).
Mutation-prove each new test. Run EVERY check in the FOREGROUND: kondo (separate args), cljstyle check per file (say if
blocked), bb build:yin-repl-node, bb gen:python-antlr, focused JVM, full clj -M:test, bb test:cljs. Not bb test:cljd.
Append a "Round 3" section; give the full report as your final response (same header).

## Round 4 (orchestrator) — gate r3 REQUEST CHANGES (gpt-6.1-sol)
Gate (copy in this worktree's collab/): 1790856811617-reviewer-python-phase-c1-gate-r2.gpt-6.1-sol.findings-r3.md, thread
01a0f762-755f-7913-985d-6782759f2dd9. All seven r2 findings are resolved. Orchestrator: cljstyle clean, kondo 0/0 on your
round-3 edits; the lane run was stopped for this round. Keep editing uncommitted on top of 7a8493e1.
Fix:
1. P1 prelude.cljc:705, 717-722: float-mod / float-divmod compare with host (= m 0), which differs between float and
   integer zero on the JVM but not Node, so the sign correction misfires: print(4.0 % -2.0, -4.0 % 2.0, 4.0 // -2.0)
   gives "-2.0 0.0 -3.0" on the JVM; Python: "-0.0 0.0 -2.0". Use py/zero? (or an equivalent host-independent test) for
   every zero comparison in the float helpers, including the quotient-zero test. Sweep the prelude for any other host
   (= x 0) / (= x 0.0) comparison on a value that may be either int or float, and fix those too (list them).
2. P3 prelude.cljc:706: signed zero for an infinite divisor: (py/float-mod 0.0 inf) is NaN on Node; CPython 0.0. Choose
   0.0 or -0.0 from the divisor's sign instead of multiplying; same in float-divmod.
Tests: JVM/Node parity cases (prelude_parity_test.cljc) and e2e for exact multiples with both divisor signs (int-valued
floats), x % +-inf and x // +-inf, signed-zero printing. Mutation-prove each. Run EVERY check in the FOREGROUND: kondo
(separate args), cljstyle check per file (say if blocked), bb build:yin-repl-node, bb gen:python-antlr, focused JVM, full
clj -M:test, bb test:cljs. Append a "Round 4" section; give the full report as your final response (same header).

## Round 5 (orchestrator) — CLJD signed-zero failure (gate r4 already READY/GRANTED)
State: the branch was COMMITTED and REBASED by another orchestrator seat (not you, not me): your round-4 edits are commit
fb1c02da on top of 9583809c (C1) on master df7cf1f4 (zcode's linker-over-DHT L2-L5). Make round-5 changes as ordinary
uncommitted edits on top of fb1c02da. Do not commit.
Failure (orchestrator CLJD lane, twice): yang.python.antlr.prelude-parity-test/signed-zero-floats-on-every-host-test on
ClojureDart — expected ["-0.0" "0.0" "-0.0" "-2.0" "-2.0" "-0.0" "0.0" "-0.0" "5.0" "inf" "-inf" "-1.0" "-0.0" "0.0" "0.0" "-0.0"],
got ["0.0" "0.0" "0.0" "-2.0" "-2.0" "0.0" "0.0" "0.0" "5.0" "inf" "-inf" "-1.0" "-0.0" "0.0" "0.0" "0.0"]: every
py/zero-like result loses its sign, while the py/neg-of-0.0 case (index 12) keeps it.
Orchestrator tried (and reverted): building the zeros as (- (* 1.0 0)) / (* 1.0 0) — no change. So the cause is not
simply "integral float literals become ints"; find the real one (literal encoding through the prelude's UAST/datoms on
CLJD? the - primitive on CLJD? render/float-repr on CLJD? how the test calls py/zero-like?).
THIS ROUND YOU MAY RUN THE CLJD LANE: bb test:cljd in this worktree (you own it here; nothing else runs CLJD in this
worktree). Fix the root cause portably, keep JVM/Node green, keep the parity test as the detector (do not weaken it).
Then run EVERY check in the FOREGROUND: kondo (separate args), cljstyle check per file (say if blocked), bb
build:yin-repl-node, bb gen:python-antlr, focused JVM, full clj -M:test (note: yin.repl.dht-process-test from L5 needs the
fresh Node build), bb test:cljs, bb test:cljd. Append a "Round 5" section; give the full report as your final response.
