Created-GMT: 2026-10-04 06:40:45 GMT
Created-Local: 2026-10-04 13:40:45 +07 (+0700)
Coding-Agent: claude
Session-ID: eccc034e-500f-4c51-b2ca-2fdda44289aa

# Task: Python C2 slice S4: generator expressions as anonymous generators

Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-04 13:41 +07 | Status: active | Rationale: owner asked to close the open engineering items; wave 1 of the sequenced plan

Work in /Users/sto/workspace/datomworld-c2s4 (branch yang-python-c2-s4, master ff1e0195; a NEW worktree; node_modules installed).

## Spec (read first)
docs/design/yang.antlr.md 8.5.3 (generators), especially the slice table row "S4: Generator expressions, with removal of the C1 consuming-builtin inlining", the paragraph beginning "Generator expressions
lower to anonymous generators (ruling 4)", and the cross-ruling acceptance bullets. S1 (generators), S2 (send/throw/close/GeneratorExit/PEP 479) and S3 (yield from, iter, sequence iterators) are LANDED on master;
read their code (src/cljc/yang/python/antlr/lower.cljc, prelude.cljc) and tests (test/yang/python/antlr/e2e_c2_test.clj, prelude_parity_test.cljc, lower_test.clj) so S4 composes with them.

The ruling: a generator expression lowers to an anonymous generator; the OUTERMOST iterable is evaluated and `iter()`-checked at creation in the enclosing scope; every other clause is lazy. The C1 inlining of a generator
expression consumed by `sum`, `any` or `all` (lower.cljc `lower-comprehension` kinds :sum :any :all and the call-site special case, plus `py/genexp-unsupported` and the "generator expression (phase C2)" refusals) was a
placeholder, deviates under PEP 479, and eager materialization can run expressions a lazy consumer never reaches: REMOVE it. Any later optimization belongs to an attached optimizer and must preserve these
observations and runtime rebinding of the consumer name. List, set and dict comprehensions stay eager. `yield` at module or class level, or inside any comprehension or generator expression, is a syntax diagnostic.

## Acceptance (all four evaluators on JVM, Node and Dart; expected output is CPython 3.9.6's)
Prove each with a program (source programs on the JVM through the parser; the portable prelude-level parity form on all hosts, as S1 to S3 did): laziness (`any(x > 2 for x in gen())` stops at the first decisive element and
never evaluates later elements; `all`, `sum`, `next(g)` on a genexp), the outermost iterable evaluated at creation (a non-iterable outermost iterable is a TypeError when the genexp is CREATED, not at first
`next`; later `for`/`if` clauses run lazily), enclosing-scope evaluation of the outermost iterable versus the genexp's own scope for everything else (late binding of free variables), nested generator expressions,
generator expressions as the sole call argument (`f(x for x in y)`) and as a consumer argument with the consumer name REBOUND (`sum = lambda g: ...` still works: no inlining), a StopIteration escaping the genexp body
becoming PEP 479's RuntimeError, `yield` inside a genexp diagnosed, `gi_*`/`close()`/`throw()`/`send()` on a genexp behaving as S2 specifies for generators, and that all crossings still go through `py/gen-switch` (the
recursion-limit admission landed in safepoint slice 2 therefore covers genexps with no extra code; add one boundary test). Existing C1 comprehension tests must keep passing; update only tests that pinned the removed inlining
(say which and why).

## Deliverables
Lowering (lower.cljc) and prelude (prelude.cljc) changes, tests, and the 8.5.3 text: mark S4 landed in the S4 row paragraph only (no commit hashes; the orchestrator adds those). Re-mint the goldens last. Allowed files: lower.cljc,
prelude.cljc, scope.cljc if needed, the Python test files above, test/yang/python/antlr/float_address_test.cljc (goldens only), and docs/design/yang.antlr.md (the S4 row text only). Ask before anything else.
Slow tests: if a new test takes more than about 5 s on any lane, tag it ^:slow (and wrap .cljc ones with dao.test-slow/guard); report the timings.

## Rules (apply to every engineer round)
- You cannot run git write commands (stash, checkout, rebase, reset, commit, stage): do not try; the orchestrator does all git steps, including rebases. Edit files directly. Do not touch collab/ in the main tree.
- Run ONLY focused tests (`clojure -M:test -n <ns>`; add `-e :slow` to skip the slow ones). The orchestrator runs the full JVM, Node and Dart lanes. NEVER run `clojure -A:test -M -e` (the :test alias's runner launches the whole suite).
- Foreground only, single turn: no background processes; chunk anything that could pass 10 minutes. Kill nothing you did not start.
- kondo 0 errors; `mise exec -- cljstyle fix` then `check` on changed files (run directly, not through a piped loop). ASCII; keep added lines <= 80 columns where practical.
- Anything touching the Python prelude moves content-address goldens in test/yang/python/antlr/float_address_test.cljc: re-mint them LAST and only once (run `clojure -M:test -n yang.python.antlr.float-address-test`, write the actual values in the same :segment/blake3-... form; only address-golden lines may change). If master moves before landing, the orchestrator rebases you and you re-mint again.
- Write your findings to the path named below. Begin the final response exactly with: Completed-GMT / Completed-Local (named timezone) / Coding-Agent: claude / Session-ID: <exact id>. Then report changed files, exact test outcomes with counts, deviations, and anything unfinished. Do not claim edits or runs that did not occur.

Findings: /Users/sto/workspace/datomworld-c2s4/collab/1791096045000-compiler-engineer-python-c2-s4.claude-opus-5-5.findings.md
