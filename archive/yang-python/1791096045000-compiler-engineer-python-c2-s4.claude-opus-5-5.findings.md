Completed-GMT: 2026-10-04 06:59:36 GMT
Completed-Local: 2026-10-04 13:59:36 +07 (Indochina Time, +0700)
Coding-Agent: claude
Session-ID: eccc034e-500f-4c51-b2ca-2fdda44289aa

# Python C2 slice S4: generator expressions as anonymous generators

## What changed

### lower.cljc
- A generator expression, whether parenthesized (`lower-atom`) or the
  sole call argument (`call-parts`), is now `(lower-comprehension ctx n :genexp)`:
  `(let [%firstN (py/iter <outermost iterable, enclosing ctx>)]
     (py/make-generator "<genexpr>" (fn [%genN] (<locals cells> (py/for-each ... (py/yield %genN elem))))))`.
  The outermost iterable is evaluated and `iter()`-checked at creation in
  the enclosing scope. Every other `for`/`if` clause, and the element, is
  lowered in the comprehension's own scope and runs only on resume. The
  comprehension's local cells are allocated inside the generator body.
- Removed the C1 inlining: `consuming-builtins`, `builtin?`,
  `callee-builtin`, the `:genexp` call-site branch in `apply-trailer` (and
  its `callee` parameter), the `:sum`/`:any`/`:all` kinds of
  `lower-comprehension`, and both "generator expression (phase C2)"
  refusals. A genexp argument is now an ordinary positional argument, so
  the consumer name is resolved at run time like any other callee.
- `comprehension-names` gains `:genexp "generator expression"`, so `yield`
  inside a genexp (element, inner `for` iterable, or `if`) is the syntax
  diagnostic "'yield' inside generator expression", as in CPython 3.9.6.
  A `yield` in the outermost iterable belongs to the enclosing function,
  as in CPython.
- I updated the docstrings for the namespace, `call-parts` and
  `lower-comprehension`.

### prelude.cljc
- Removed `py/genexp-unsupported`.
- `sum`, `any`, `all` and `set` now consume one element at a time:
  `py/sum-from`, `py/any-of` and `py/all-of` walk `(py/iterable x)` through
  `py/iter-at` instead of a host vector from `py/to-vector`. The new
  `py/set-fill-at` does the same for `set()`. This is required. Without
  the inlining, the old eager builtins would run every element before
  short-circuiting, which breaks CPython's laziness for `any`/`all`. They
  would also break the existing C1 expectations "sum stopped [1, 'a']" and
  "set stopped [1, [2]]". `list` and `tuple` still collect through
  `py/to-vector`, which is observably the same.
- I made no change to the generator machinery: every genexp crossing goes
  through `py/gen-switch`, so the safepoint-slice-2 admission covers
  genexps with no extra code.

### docs/design/yang.antlr.md
- The S4 row of the 8.5.3 slice table now ends "inlining. Landed." That is
  the only doc change. The intro sentence at the top of 8.5.3 ("S4 and S5
  are pending") and section 1 / the status list near line 3568 still say
  S4 is pending. I left them because they were outside the allowed text,
  so the orchestrator should update them along with the hashes.

## Tests

### New (expected output checked against the local CPython 3.9.6, `python3`)
`test/yang/python/antlr/e2e_c2_test.clj`, source programs on the JVM, all
four evaluators:
- `genexp-laziness-test`: `any`/`all` stop at the first decisive element
  (generator log `[1, 2, 3]` / `[1, 2]`), `sum`, `next(g)` one element at
  a time, `sum`/`set` stop at the failing element, `any`/`all` over a
  logging element.
- `genexp-outermost-iterable-at-creation-test`: the outermost iterable runs
  at creation, while `if` and inner `for` run lazily (interleaved log).
  `(x for x in 5)` is a TypeError at creation. A non-iterable inner `for`
  and a failing `if` raise only at `next`.
- `genexp-scopes-test`: rebinding the outermost iterable after creation has
  no effect, free variables bind late (module and function), a class-body
  outermost iterable works, a class-body name inside the element is a
  NameError, and the target does not leak.
- `genexp-nesting-and-call-arguments-test`: nested genexps (in an inner
  `for`, in the element, as the outermost iterable), a genexp as the sole
  argument of a user function, `list`/`tuple`/`set` of a genexp, and the
  consumer rebound three ways (a local `any = lambda`, a module
  `sum = lambda`, `globals()['all'] = ...`). In each case the lambda
  receives the generator.
- `genexp-pep-479-test`: a StopIteration from an inner `next(empty())`, an
  exhausted iterator, a raising call under `any`, or `g.throw(StopIteration)`
  becomes RuntimeError("generator raised StopIteration"), and the genexp is
  then closed.
- `genexp-generator-protocol-test`: `send` (value ignored; non-None first
  send is a TypeError), `throw` at the yield, `throw` into an unstarted
  genexp closes it, `close` is idempotent, `__next__`, `__iter__`,
  `iter(g) is g`, a genexp does not forward `throw` to the generator it
  iterates, and `yield from` over a genexp.

`test/yang/python/antlr/prelude_parity_test.cljc` (portable, uses the
lowering's genexp shape in prelude notation):
- `generator-expression-on-every-host-test`: lazy `any`/`all`/`sum`, a
  creation-time TypeError, a resume-time TypeError, PEP 479 then closed,
  `send`/`close`/state.
- `generator-expression-admission-on-every-host-test`: the recursion-limit
  boundary. Under limit 2, two nested active genexps run and a third is
  refused with RecursionError while the innermost stays `:created`. The
  outer pair keeps working, and limit 3 admits three.

`test/yang/python/antlr/lower_test.clj`:
- `generator-expression-golden-test`: `sum(x for x in y)` lowers to a
  plain `py/call` of the run-time `sum` read with
  `[(let [%first (py/iter y)] (py/make-generator "<genexpr>" ...))]`. No
  inlining.
- Syntax diagnostics: four new cases of "'yield' inside generator
  expression" (module level; element `yield`; `yield from`; in an `if`).
  I verified each message with CPython 3.9.6 `compile`.
- Classification: `return (x for x in y)` makes only `<genexpr>`, and
  `return (x for x in (yield))` makes both `g` and `<genexpr>` (CPython
  accepts this).

### Updated because they pinned the removed inlining
- `lower_test.clj` `unsupported-constructs-are-qualified-test`: I removed
  the two rows `x = (i for i in y)` / `f(i for i in y)` -> "generator
  expression (phase C2)". Those refusals no longer exist.
- `e2e_c1_test.clj` `generator-consumer-rebound-test`: it asserted the C1
  `NotImplementedError` from `py/genexp-unsupported` when `any` was rebound
  through `globals()`. It now asserts the CPython behaviour: the rebound
  lambda receives the generator (`[1, 'mine']`). This file was not in the
  allowed list, but the brief permits updating tests that pinned the
  removed inlining. This is the only change to it.
- No other C1 comprehension test changed. `e2e_c1_test` passes unchanged,
  including the laziness lines (`any(f(x) for x in [1, 0])`,
  "sum stopped", "set stopped").

### Goldens
- `float_address_test.cljc`: I re-minted the five address goldens last,
  once: the bundled prelude, A, A', the derivation record, and the prelude
  subtree. The hook-prelude golden did not move.

## Runs (all in the foreground, focused)
- `clojure -M:test -n yang.python.antlr.lower-test`: 28 tests, 97
  assertions, 0 failures, 0 errors.
- `clojure -M:test -n yang.python.antlr.e2e-c2-test -e :slow`: 28 tests,
  140 assertions, 0 failures, 0 errors (about 50 s wall).
- `clojure -M:test -n yang.python.antlr.e2e-c1-test -e :slow`: 18 tests,
  216 assertions, 0 failures, 0 errors.
- `clojure -M:test -n yang.python.antlr.prelude-parity-test -e :slow`: 21
  tests, 84 assertions, 0 failures, 0 errors.
- Node, focused: `clj -M:cljs -m shadow.cljs.devtools.cli compile test
  --config-merge '{:ns-regexp "prelude-parity-test$" :output-to
  "target/s4-parity-tests.js"}'`: 21 tests, 84 assertions, 0 failures, 0
  errors.
- `clojure -M:test -n yang.python.antlr.float-address-test`, after the
  re-mint: 12 tests, 105 assertions, 0 failures, 0 errors.
- `clj -M:kondo --lint` on all changed .clj/.cljc files: 0 errors, 0
  warnings. `mise exec -- cljstyle fix` then `check`: clean.
- After those runs I made one whitespace-only reflow of three quoted
  prelude forms (`py/any-of`, `py/all-of`, `py/set-fill-at`; the UAST is
  identical), then re-ran kondo, cljstyle and float-address. I did not
  re-run the e2e or parity namespaces after it.

## Timings (new tests, JVM)
Single-var runs (`-v`) took 17.2 to 20.1 s wall, against a 15.9 s baseline
for a trivial S1 test that includes JVM start and load. So each new e2e
test costs roughly 1 to 4 s across four evaluators. The longest is
`genexp-nesting-and-call-arguments-test` at about 4 s; it is near the
threshold but under it, so I left it untagged. The two parity tests are
part of a 21-test namespace run of about 59 s wall that is dominated by
existing tests. The whole Node focused build plus run took 107 s, mostly
compilation. Nothing is tagged ^:slow.

## Deviations / unfinished
- **Dart not run.** `clojure -M:clojuredart:cljd compile ...` needed a
  permission this session could not grant. The parity tests use only the
  constructs the existing S1 to S3 parity tests use, but the Dart lane for
  this round needs to be run by the orchestrator.
- **`gi_*` not covered.** 8.5.3 lists `gi_*` introspection as Deferred, and
  S2 specifies no `gi_*` attributes. On a genexp, `gi_*` therefore behaves
  as on any generator (AttributeError via `py/gen-attr`), which differs
  from CPython. I did not write a source-program test for it because it
  cannot match CPython's output.
- **Close-on-collection.** CPython closes the generator a closed genexp
  was iterating as soon as its refcount drops (ruling 6). This profile does
  not, so I kept that case out of the programs.
- **Build step.** The fresh worktree lacked the generated ANTLR parser. I
  ran `bb gen:python-antlr`, which wrote build/antlr/python3; that path is
  build output and does not appear in `git status`.
- **Scratch files.** I used scratch files under this worktree's collab/
  (CPython probes and a timing script) and deleted them. Nothing outside
  the allowed files changed, apart from the `e2e_c1_test.clj` test
  described above.
