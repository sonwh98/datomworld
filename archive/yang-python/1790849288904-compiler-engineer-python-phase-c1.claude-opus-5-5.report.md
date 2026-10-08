Completed-GMT: 2026-10-01 10:55:07 GMT
Completed-Local: 2026-10-01 17:55:07 +07
Coding-Agent: claude
Session-ID: 976059c2-629b-4b91-8b63-7c4d9604d7cf

# Python phase C, slice C1: report

## Summary

All six C1 items are implemented in the lowering and prelude only. There are no VM changes and no new AST tags.

**Test results.**
- All Python tests pass: 98 tests and 395 assertions, including 13 new end-to-end tests that run on all four VMs over the real `cell` and `data` modules.
- The full JVM suite passes: 2695 tests, 187529 assertions, 0 failures.
- `bb test:cljs` passes with 0 warnings.

**Mutation proof.** I made 9 deliberate breakages, each reverting one implemented piece. Every one turned its tests red. One of them first exposed a coverage gap in the slice tests, which I closed.

**Not done.**
- cljstyle is blocked by permission in this session.
- `bb test:cljd` was not run, per the brief.

Work is in `/Users/sto/workspace/datomworld-py-c1`, on branch `yang-python-phase-c1`. Nothing is staged or committed.

## Supported now

**1. `finally` and `with`.**
- `try`/`finally` and `try`/`except`/`else`/`finally` run the finally block on every exit: normal completion, `return`, `break`, `continue` and `raise`.
- Nested finally blocks run innermost first.
- A `return` inside `finally` overrides the pending return or exception.
- An exception raised inside `finally` replaces the one already in flight.
- `with a as x, b:` follows the language reference's expansion:
  - `__exit__` is looked up before `__enter__` runs;
  - on an exception, `__exit__(type, value, None)` is called, and a truthy result suppresses the exception;
  - every other exit calls `__exit__(None, None, None)`;
  - the `as` target can be any assignment target.

**2. Tuples, unpacking and slices.**
- Tuples have value semantics:
  - `==` compares elements (so `(1, 2) == (1.0, 2)`);
  - they are hashable when their elements are, so `d[1, 2]` and `d[(1.0, 2)]` find the same key;
  - lists, dicts and sets used as keys raise TypeError "unhashable type".
- Tuple and list targets unpack, nested to any depth, including `x, (y, z) = ...`, `[d] = ...`, `for k, v in d.items():` and `with m as (p, q):`.
- One starred target per assignment is allowed (`first, *rest = ...`).
- Unpacking with the wrong count raises ValueError.
- Slices work on lists, tuples and strings, with start, stop and step, negative indexes, and clamping exactly as `slice.indices` does it.
- Slice assignment works on lists for an absent step or a step of 1. An extended slice target is rejected.

**3. Operators.**
- `**` on ints, and on floats with an integral exponent. A negative exponent returns a float, and `0 ** -n` raises ZeroDivisionError.
- `%` and `//` use Python's floor semantics for negative operands, on both ints and floats, and raise ZeroDivisionError on a zero divisor.
- `& | ^ ~ << >>` on ints, using unbounded two's complement as Python does. A negative shift count raises ValueError.
- `in` and `not in` work for lists, tuples, ranges, str (substring), dict keys and sets.
- All the new augmented forms work. `+=` and `*=` on a list mutate it in place, so aliases see the change, matching `list.__iadd__`.
- Also added: list, tuple and str concatenation and repetition.

**4. Comprehensions.**
- List, dict and set comprehensions, with multiple `for` and `if` clauses.
- Each comprehension has its own scope:
  - its targets are cells local to the comprehension;
  - the first iterable is evaluated in the enclosing scope, so a class body can use its own names there;
  - lambdas inside the comprehension share the loop variable's single cell, so `[lambda: i for i in range(3)]` gives 2, 2, 2.
- A generator expression is accepted only as the sole argument of `list`, `tuple`, `set`, `sum`, `any` or `all`, and only when that name resolves to the builtin. There it is lowered as a list, which is not observably different. Any other generator expression is rejected as "generator expression (phase C2)".

**5. Keyword arguments.**
- Call sites accept `name=value`, `*iterable` and `**mapping`.
- Definitions accept keyword-only parameters (after `*name` or a bare `*`), keyword-only defaults, and `**kwargs`.
- All binding failures are Python TypeErrors: too many positional arguments, multiple values for an argument, an unexpected keyword, a missing positional argument, a missing keyword-only argument, and `**` applied to a non-mapping or with non-string keys.
- The static syntax errors are: a repeated keyword, a positional argument after a keyword argument, named arguments missing after a bare `*`, and a non-default argument after a default one.

**6. The five P3 notes from the r2 gate.**
- **(a) Implicit `object` base and the exception classes the prelude raises: left unchanged, because the current behaviour is CPython's.** CPython uses `builtins.object` for an implicit base, and its interpreter-raised errors are `builtins.TypeError` and friends regardless of what the module binds those names to. A test now pins it: after `TypeError = None; object = 1`, a class still defines and an internal TypeError is still caught by `except Exception`.
- **(b) `global globals; globals()` now works.** `globals` is a per-module function object, passed to the module body as `%globals-fn` and read through the same "module key wins, else builtin" path as every other builtin. It can also be used as a value now.
- **(c) An out-of-range `\U` escape is now a qualified syntax diagnostic** ("illegal Unicode character in \U escape"), not a host exception.
- **(d) `0755`: the r2 note does not hold for the pinned grammar.** Its lexer has no token for that literal, so the parser already emits a syntax-error packet; a test now pins that. I added a matching lowering guard in case a grammar ever passes one through.
- **(e) repr fixes:**
  - UnboundLocalError's message is now CPython 3.11's: "cannot access local variable 'x' where it is not associated with a value".
  - repr picks quotes the way Python does: `"it's"` gets double quotes.
  - `\n`, `\r` and `\t` are escaped, and other control characters as `\xNN`.

## Still rejected (each with a qualified diagnostic)

- **For C2:** generator expressions anywhere other than the consuming-builtin position, and `yield`.
- **Imports and statements:** imports, `del`, `assert`, `match`, `async`/`await`, and decorators.
- **Display and argument forms:** `@` and `@=`, `{**d}` in a display, multi-dimensional slicing, extended slice assignment, and multiple inheritance.
- **Literals:** f-strings, bytes, and integers above 2^53 (big ints are a later slice).
- **Annotations:** parameter and return annotations.
- **`**` with a non-integral exponent** (for example `2 ** 0.5`). This is a runtime NotImplementedError, not a static rejection, because the runtime exponent is not known statically and there is no portable `pow`/`exp`.

## Rule → Universal AST changes

The "every grammar rule classified" test passes and has been updated. These rules moved out of the unsupported set:

| Rule | Now | Lowers to |
|---|---|---|
| `with_stmt` | handled | `(py/with mgr (fn [%wv] (assign target %wv) body))`, nested right to left for several items |
| `with_item` | consumed by `with_stmt` | (see `with_stmt`) |
| `star_expr` | handled | splice `py/extend` in displays and in `*` call arguments; `py/unpack-star` in targets; a syntax error anywhere else |
| `sliceop` | consumed by `subscript_` | `(py/slice start stop step)` |
| `comp_for`, `comp_iter`, `comp_if` | consumed by the comprehension | `py/for-each` loops and `if` filters inside the comprehension |
| `test_nocond` | handled | lowered to its single child |
| `lambdef_nocond` | handled | same as `lambdef` |

Existing rules changed as follows:

| Rule | Change |
|---|---|
| `try_stmt` | A `finally` clause becomes `(py/try-finally (fn [] <try/except part>) (fn [%fx] <finally>))`. |
| `testlist`, `testlist_star_expr`, `exprlist` | One element is lowered as before. Several elements, a trailing comma or a star produce `(py/tuple <elements>)`. A lone `*a` is a syntax error. |
| `atom` | `()` is an empty tuple; a parenthesised list with commas is a tuple; `[...]` and `{...}` cover list, set and dict displays and comprehensions. |
| `expr` | Adds `// % ** & \| ^ << >>` and unary `~`. A `**` chain is regrouped to the right, because the grammar's left-recursive alternative groups `a**b**c` as `(a**b)**c`. |
| `comparison` | Adds `in` → `py/in` and `not in` → `py/not-in`. |
| `atom_expr` | The call trailer builds positional args with conj and extend, and kwargs pairs with conj and kw-extend. A call with keywords uses `py/call-kw`. A generator-expression argument becomes a list comprehension when the callee is a consuming builtin. The subscript trailer handles slices and tuple keys. |
| assignment, `for` targets, `with` targets | General `assign-target`: tuple and list targets go through `py/unpack` / `py/unpack-star` and are assigned left to right. A tuple target in augmented assignment is a syntax error. |
| `funcdef`, `lambdef` | `py/make-function name spec defaults kwdefaults code`. `spec` is the static `{:params :star? :kwonly :kwstar?}`. Keyword-only defaults are evaluated at definition time. The code vector follows the written order of the parameters. |
| module | `(py/run-module (fn [%globals %globals-fn] body))` |

## Prelude additions

- **Handler stack and escapes.** The stack is now a chain of depth-numbered frames `[kind payload rest depth]`.
  - `py/frame` and `py/frame-depth` build and read them.
  - `py/raise` pops `:finally` frames, running each thunk with the exception, until it reaches a `:handler`.
  - `py/unwind-to` pops frames above a depth, running finally thunks with None. Every `py/call-ec` escape calls it before jumping, so `return`, `break` and `continue` run pending finally blocks.
  - Also new: `py/try-finally`, `py/with`, `py/type-of`.
- **Calls.**
  - `py/make-function` now takes `(name spec defaults kwdefaults code)`.
  - `py/call-kw` is new, and `py/call` is now its positional-only form.
  - Binding is done by `py/bind-args` together with `py/bind-keyword(s)`, `py/fill-defaults`, `py/fill-kwdefaults`, `py/pad`, `py/index-of`, `py/lookup-pair` and `py/fn-error`.
  - Call-site splicing uses `py/extend`, `py/kw-extend` and `py/kw-pairs`.
  - `py/instantiate` passes keyword arguments on to `__init__`.
- **Integer operations.** These are exact algorithms built only from `+ - * <`, because no host primitive does them portably. They are valid within 2^53.
  - `py/divmod-pos` divides by doubling the divisor.
  - `py/int-floordiv` and `py/int-mod` apply Python's sign rule on top of it.
  - `py/floor` floors a real by binary descent; it raises OverflowError outside ±2^53.
  - `py/ipow` raises to an integer power by repeated squaring.
  - `py/bit-op` handles unbounded two's complement by recursing until both operands are 0 or -1.
  - Built on these: `py/floordiv`, `py/mod`, `py/pow`, `py/bitand`, `py/bitor`, `py/bitxor`, `py/invert`, `py/lshift`, `py/rshift`.
- **Sequences.**
  - `py/add` gains list and tuple concatenation.
  - `py/mul` and `py/repeat` handle repetition.
  - `py/iadd` and `py/imul` work in place on lists.
  - `py/kind` and `py/sequence?` classify values.
- **Membership and equality.** `py/contains`, `py/in`, `py/not-in` and `py/seq-contains?` are new. `py/eq` now compares tuples element by element and sets by membership.
- **Keys, sets and dicts.**
  - `py/key` now normalizes tuples recursively and refuses unhashable values; `py/keys-of` supports it.
  - `py/dict-has?`.
  - Sets: `py/set-new`, `py/set-add`, `py/set-from`, `py/set-fill`.
  - Dict methods `items`, `keys`, `values` and `get`. `items`, `keys` and `values` return lists, which are snapshots rather than live views.
- **Slices.** `py/slice`, `py/slice-positions` (the `slice.indices` algorithm), `py/slice-bound`, `py/slice-walk`, `py/pick`, `py/slice-of` and `py/set-slice`. `py/getitem` and `py/setitem` now handle slices and tuples.
- **Iteration.**
  - `py/iterable` turns a str into a tuple of its characters, decoding it once.
  - `py/to-vector`, `py/collect`, `py/for-each` and `py/for-each-at`.
  - `py/unpack` and `py/unpack-star`.
  - `py/iter-at` now also walks sets.
- **Builtins.**
  - New: `list`, `tuple`, `set`, `sum`, `any`, `all`, list `append` and set `add`.
  - Every builtin now carries a parameter spec, so arity and keyword errors are TypeErrors.
  - New exception classes: NotImplementedError and OverflowError.
  - Classes now have a `__name__` attribute.
- **Rendering.** The boundary renderer handles sets (with `set()` for an empty set) and implements the Python string repr.

## Test outcomes (all run in the foreground)

| Check | Result |
|---|---|
| clj-kondo on the 10 changed files, as separate arguments | 0 errors, 0 warnings |
| cljstyle | **not run.** `cljstyle check src/cljc/yang/python/antlr/lower.cljc` returned "This command requires approval", so formatting is unverified. |
| `bb build:yin-repl-node`, then `bb gen:python-antlr` | Build completed with 0 warnings. The parser generated with all digests verified. |
| Focused JVM: the seven `yang.python.antlr.*` namespaces | 98 tests, 395 assertions, 0 failures, 0 errors |
| Full `clj -M:test` | 2695 tests, 187529 assertions, 0 failures, 0 errors |
| `bb test:cljs` | 0 warnings; 2520 tests, 53195 assertions, 0 failures; `lower-portable-test` and `prelude-parity-test` ran on Node |
| `bb test:cljd` | not run, per the brief |

**New tests:**
- `test/yang/python/antlr/e2e_c1_test.clj`: 13 deftests, each on all four VMs (ast-walker, semantic, stack, register) over the real modules. They cover every exit through `finally`, finally nesting and override, raise inside finally and handler-stack consistency, `with`, tuples and unpacking, slices, tuple value semantics and unhashable keys, arithmetic operators, membership and augmented assignment, comprehensions, comprehension scope, keyword arguments, and the P3 runtime items.
- `lower_test.clj`, new goldens: try/finally, with, unpacking, comprehension, keyword call, and slice plus `**` regrouping. Refusal and syntax lists were rewritten: 15 refusals and 15 static errors. A parser-level test pins the `0755` rejection.
- `scope_test.clj`: tuple, star and with targets; comprehension scopes, including where a first-iterable lambda belongs and a class-body comprehension.
- `prelude_parity_test.cljc` (also runs on Node): 24 new cases for floor division, modulo, power, bitwise ops, tuple equality and keys, and slice positions on JS numbers.

## Mutation proof

Each mutation temporarily broke the code that implements one item. I ran the related tests against it, then restored the code. A final run showed no mutation markers left and all 98 tests green.

| # | Mutation | Tests that turned red |
|---|---|---|
| M1 | `py/unwind-to` stops running finally thunks (escape path) | finally-runs-on-every-exit, finally-nesting-and-override, with-statement: 12 failures across 4 VMs |
| M1b | `py/raise` stops running finally thunks (raise path) | finally-runs-on-every-exit, raise-in-finally-and-handler-stack: 8 failures (`with` correctly unaffected, since its raise path is a handler) |
| M1c | `py/with` ignores `__exit__`'s result | with-statement: 4 failures |
| M2 | `py/key` stops normalizing tuples | tuples-and-unpacking, tuples-are-values-and-unhashable-lists: 8 failures |
| M2b | `py/slice-bound` stops clamping the upper bound | **first run: slices-test still passed**, a real coverage gap. I added `t[1:100]`, `'hello'[-100:2]` and `t[100:0:-1]`, and it then failed 4/4. |
| M3 | integer `//` drops the floor adjustment, and `**` regrouping is disabled | arithmetic-operators on 4 VMs gave `-7//2` → -3, `-7%3` → -1, `2**3**2` → 64, `-6&3` → 0; slice-and-power golden failed: 5 failures |
| M4 | the first iterable is evaluated inside the comprehension scope | comprehension-scope: NameError on `n`, 4 failures |
| M5 | the keyword duplicate-value check is removed | keyword-arguments: 4 failures |
| M6 | the `globals` builtin branch is removed | gate-p3-runtime: NameError on `globals`, 4 failures |
| M7 | repr always uses single quotes | gate-p3-runtime: 4 failures |

## Mechanical versus not

**Mechanical:**
- the new operator and comparison table entries;
- the augmented forms;
- the CST arms for `with`, slices, displays and tuple expressions;
- the new builtins;
- the rule reclassification.

**Not mechanical:**
1. **finally and with.** The handler stack had to become typed, depth-numbered frames so that one data structure serves both `raise`, which unwinds until a handler, and escapes, which unwind to a captured depth. Getting "return in finally overrides" and "raise in finally replaces" right came from popping a frame before running its thunk, not from anything in the grammar. Continuations carry the control flow as the owner said, but the unwinding protocol around them was design work.
2. **Integer arithmetic.** The host offers only `+ - * / <`, and `/` returns a Ratio on the JVM but a double on JS. Floor division, modulo, power, bitwise ops and float floor had to become algorithms that give identical results on all hosts.
3. **Comprehension scope.** Scope analysis needed a new scope kind with a split rule: the first iterable belongs to the enclosing scope, while everything else, including nested lambdas, belongs to the comprehension.
4. **Keyword binding.** CPython's argument-binding algorithm as prelude code, with every failure a TypeError, is the largest single piece.
5. **Grammar mismatches the mapping had to correct.** The grammar groups `**` to the left, so the lowering regroups it. The grammar also accepts a lone `*a` that Python rejects.

## Unresolved concerns

- **Error messages carry no counts.** "g() takes too many positional arguments" stands in for CPython's "takes 1 positional argument but 2 were given", because `data` has no number-to-string primitive.
- **Set iteration and display order is insertion order,** which is deterministic but differs from CPython's hash order (for example, `{1, 0}` versus `{0, 1}`). The comprehension test avoids depending on the difference.
- **`dict.items()`, `keys()` and `values()` return list snapshots,** not live views.
- **`**` with a non-integral exponent raises NotImplementedError,** because there is no portable `pow`/`exp`. JVM long overflow in `**` is still a host error; big ints are a later slice.
- **Escapes now pay an unwinding check.** Every `py/call-ec` escape runs `py/unwind-to`, which costs one depth comparison when no frames lie above the escape's depth. `try`/`finally` allocates one frame vector per entry, and every `py/try` and `py/call-ec` allocates one flag cell, as in phase B. Heap reclamation (60b60898) now collects these.
- **The `(x) = 1` target is still rejected** as an unsupported assignment target. Python accepts it; it is rare.
- **CLJD is verified by reading only.** The one new reader conditional, `code-unit` in `render.cljc`, puts `:cljd` first and uses `.codeUnitAt`. No protocol methods were added.
- **cljstyle has not been run** on these edits.
