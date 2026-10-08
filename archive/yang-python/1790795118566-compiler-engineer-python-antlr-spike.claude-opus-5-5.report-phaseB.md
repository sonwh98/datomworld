Completed-GMT: 2026-09-30 20:29:01 GMT
Completed-Local: 2026-10-01 03:29:01 +07
Coding-Agent: claude
Session-ID: 976059c2-629b-4b91-8b63-7c4d9604d7cf

# Python ANTLR spike, phase A: report

## Summary

Phase A is built, in `/Users/sto/workspace/datomworld-py-spike`, branch `yang-python-antlr-spike`. Nothing is staged or committed.

- **Topology:** two interpreters over `dao.stream`.
  - The parser stage (JVM) reads source events and writes CST packets.
  - The lowering stage (portable `.cljc`) reads CST packets and writes Universal AST program batches, plus diagnostics on a separate stream.
- **Lowering:** follows every decided rule in the brief. Scope analysis runs before lowering. Every local and parameter is a cell. Control flow uses continuations. Python semantics live in a Universal AST prelude.
- **Tests:** everything runnable now passes. The 21 end-to-end tests fail, as expected, because cell slice 1 and the `data` module are not in this base.
- **Evidence the e2e tests will pass:** I ran all 21 on all four VMs against temporary atom-backed `cell` and `data` modules. All passed (105 assertions). That harness was scratch only; it is deleted and is not a deliverable. Details are under "Scratch validation" below.

## Changed and new files

Modified:
- `deps.edn`:
  - pinned `org.antlr/antlr4-runtime 4.13.2`;
  - `build/antlr/python3/classes` added to `:paths`;
  - new `:antlr-gen` alias (tool `org.antlr/antlr4 4.13.2`, `src/dev`).
- `bb.edn`: new task `gen:python-antlr`, and `test:clj` now depends on it.

New:
- `antlr/python3/manifest.edn`: grammar pin, digests, patches, license review, generated-file digests.
- `src/dev/yang_antlr_gen.clj`: the generation task (`clj -M:antlr-gen` or `bb gen:python-antlr`), plus `discover` for moving the pin.
- `src/clj/yang/antlr/cst.clj`: generic ANTLR to CST exporter, source-unit assembly, and the parser-stage transform. It contains no Python-specific code.
- `src/clj/yang/python/antlr/parser.clj`: the Python grammar profile (the only namespace that names the generated classes) and the parser stage.
- `src/cljc/yang/antlr/packet.cljc`: portable packet reading and tree validation.
- `src/cljc/yang/python/antlr/stage.cljc`: generic stream-stage driver shared by both interpreters.
- `src/cljc/yang/python/antlr/uast.cljc`: Universal AST constructors, the s-expression notation used for the prelude, and the tail-marking pass.
- `src/cljc/yang/python/antlr/scope.cljc`: binding collection and scope analysis.
- `src/cljc/yang/python/antlr/prelude.cljc`: the Python runtime profile, written as Universal AST.
- `src/cljc/yang/python/antlr/lower.cljc`: the lowering and the lowering stage.
- Tests, all in `test/yang/python/antlr/`: `cst_export_test.clj`, `scope_test.clj`, `lower_test.clj`, `lower_portable_test.cljc`, `prelude_parity_test.cljc`, `e2e_test.clj`.

Not touched: yin.vm engine, module and VM files; legacy `yang.python`; the `yin.repl` dispatcher; other worktrees. The generated and downloaded files live under `build/`, which is gitignored and outside cljstyle and kondo scope.

## Grammar pin and license

**Source:**
- Repository `antlr/grammars-v4`, path `python/python3`.
- Revision `8af0d4c26c796ea27c15c3d85418f2d0f77c3adb`, committed 2026-07-05T17:15:43Z; it was the head of that path when fetched.
- Files are fetched from `https://raw.githubusercontent.com/antlr/grammars-v4/<rev>/python/python3/<file>`.

**Grammar digests (SHA-256, recorded in the manifest):**

| File | SHA-256 |
|---|---|
| `Python3Lexer.g4` | `58c4dfa0b12dfb856b7348b99199bf1a3fdc7901d7fc4575ddf9584aeb831c19` |
| `Python3Parser.g4` | `74fa61c519832b694718c8006741a045a687725a9f0a06645dfdfa4c7ebce65d` |
| `Java/Python3LexerBase.java` | `f8d057a0cbd0fa0d895d1aa27aa8914c3e85a1eaa95f7161da9a6e617c65f5ec` |
| `Java/Python3ParserBase.java` | `2ae0ab2905c50c2675f1b827bc6ba4bc3d01d78cafcd92ad8e0d36f15682b773` |

These digests are trust-on-first-use. I recorded them from my own fetch; I did not check them against a second source.

**Generation:**
- ANTLR tool and runtime are both 4.13.2.
- Flags: `-Dlanguage=Java -package yang.python.antlr.gen -no-listener -no-visitor`.
- Output is compiled with the host javac at `--release 11`.
- The digests of all four generated or patched `.java` files are recorded, and any drift fails the build.
- Generated text is checked to be ASCII (§4.5).

**Patches to the Java helper, recorded as exact-text replacements in the manifest:**
- A `package` clause is prepended to both helpers.
- **The upstream `Python3LexerBase` gives NEWLINE and DEDENT the wrong source positions, so their spans were wrong.** It builds both tokens with `CommonToken(start, stop)` placed at the end of the newline-plus-indentation text and never sets their text. ANTLR then reads the text back from whatever source sits at that position: a NEWLINE before an INDENT reads a space, and a DEDENT reads the newline before it.
  - Without the patch, a generic exporter cannot report correct spans.
  - The patch puts NEWLINE on its actual newline characters and makes DEDENT zero-width.
  - `cst_export_test` pins the corrected spans.

**License (§4.5):**
- Both `.g4` files carry an MIT header (Copyright (c) 2014 Bart Kiers).
- The ANTLR runtime is BSD-3-Clause.
- **Neither Java helper file carries a license notice; this is recorded as unresolved in the manifest.**
- Nothing is redistributed: sources and output stay in `build/`.

**Known grammar limitation, left in place:** the helper emits an end-of-input NEWLINE only while indentation is open. A top-level statement with no final newline (for example `x = 1` at end of file) is therefore a syntax-error record. I did not normalize source to work around it, and a test pins the behaviour.

## The stream topology as built

```
source events (:yang.source/chunk | :yang.source/seal)  --dao.stream-->
  parser stage   (yang.python.antlr.parser, JVM; generic yang.antlr.cst)
    one CST packet per sealed unit                        --dao.stream-->
  lowering stage (yang.python.antlr.lower, portable)
    port :program     one datom batch per unit (vm/ast->datoms): the batch
                      evaluators already consume        --dao.stream-->  evaluator
    port :diagnostics syntax-error / source-error / unsupported /
                      unhandled-rule / malformed-cst records --dao.stream-->
```

**The stage driver.** Each stage is a value `{:in :cursor :outs :outbox :state :status}` advanced by `stage/step`, and its transform is a pure function `(state, value) -> [state', [[port value]...]]`.
- A `full` append keeps the staged value and yields.
- A `blocked` read keeps the state and cursor.
- A non-empty outbox stops further reading.

**The parser stage:**
- Chunks accumulate by ordinal until the seal.
- It emits a source-error packet for a conflicting duplicate chunk, a seal with missing chunks, or an unknown event.
- One worker owns fresh DFA and prediction-context caches for the lexer and the parser (§4.3), so no parse shares recognizer caches with another worker.
- No ANTLR object leaves `yang.antlr.cst`.

**CST packets:**
- Nodes are flat records in preorder, and each record's id is its index.
- Rule records carry `{id :rule children span}`.
- Token records carry `{id :type text span}` plus `:synthetic true`, with a zero-width span, when the token is not source text (every DEDENT, the helper's end-of-input NEWLINE, and EOF).
- Spans are half-open UTF-8 byte offsets.
- **A syntax error produces a packet with an `:errors` list and no `:nodes` or `:root`. It is never a partial program.**

**Program batches.** Each unit is a self-contained program: the prelude, then `(py/run-module (fn [] body))`. Its value is `{:py/out [...] :py/exception nil-or-{:type :args}}`. I bundled the prelude into every unit so each batch runs standalone on all four VMs, including the three whose loaders take a single image. The cost is that each unit re-defines the prelude and gets its own builtin class identities.

## Rule to Universal AST mapping

Every rule in the pinned grammar is in exactly one of three sets. `every-grammar-rule-is-classified-test` checks that the union equals `Python3Parser/ruleNames` and that the sets are pairwise disjoint.

### Handled: an arm in `lower`

**Statements**

| Rule | Universal AST |
|---|---|
| `file_input`, `stmt`, `simple_stmts`, `simple_stmt`, `compound_stmt`, `flow_stmt` | structural; statements are sequenced by `((fn [%_] next) first)` |
| `expr_stmt` | See below. |
| `pass_stmt`, `global_stmt`, `nonlocal_stmt` | `:py/None`; the declarations are consumed by scope analysis |
| `break_stmt`, `continue_stmt` | invoke the loop's `%brk<id>` / `%cont<id>` escape |
| `return_stmt` | invoke `%ret<id>` |
| `raise_stmt` | `(py/raise (py/as-exception X))`; a bare `raise` re-raises the enclosing except's `%exc<id>` |
| `if_stmt` | nested `:if` on `(py/truthy t)` |
| `while_stmt` | See below. |
| `for_stmt` | See below. |
| `try_stmt` | See below. |
| `funcdef` | See below. |
| `classdef` | `(let [%cls (py/make-class "C" base)] body… (assign C %cls))`; class-body names become `py/setattr` on `%cls` |

`expr_stmt` has four forms:
- expression: evaluated for effect.
- `=` chain: the value is evaluated once into `%v<id>`, then targets are assigned left to right. A name target lowers to `cell/set!`, `yin/def py.g/x`, or a class attribute; `a[i]` to `py/setitem`; `a.f` to `py/setattr`.
- augmented assignment `+= -= *= /=`: the receiver and key are evaluated once.
- annotated assignment: rejected.

`while_stmt` becomes a self-applied lambda `%w<id>`: `(if (py/truthy test) (then body (%w %w)) else)`. The body runs under `py/call-ec` only when it contains `continue`, and the whole loop runs under one only when it contains `break`.

`for_stmt` is the same, indexed through `py/iter-at` until it returns `:py/stop`. It iterates lists, dict keys and ranges; lists are iterated live by index.

`try_stmt` becomes `(py/try (fn [] body) (fn [%exc] clause-dispatch) (fn [] else))`. Each typed clause tests `py/isinstance`, and when no clause matches the exception is re-raised.

`funcdef` becomes `(fn [params] ((fn [params+locals] body) (cell/new p)… (cell/new :py/unbound)…))`. The body runs under `(py/call-ec (fn [%ret] …))` only when it contains a `return`.

**Expressions**

| Rule | Universal AST |
|---|---|
| `lambdef` | like `funcdef`, with an expression body |
| `test` | the ternary becomes `:if` on `py/truthy` |
| `or_test`, `and_test` | `(let [%t a] (if (py/truthy %t) %t rest))`, and its mirror for `and`; short-circuits and returns the operand |
| `not_test` | `py/not` |
| `comparison` | `py/lt/gt/le/ge/eq/ne/is/is-not`; a chain evaluates each operand once through `%c<id>` and stops at the first false |
| `expr` | binary `+ - * /` become `py/add/sub/mul/truediv`; unary `- +` become `py/neg/pos` |
| `atom_expr` | trailers are folded: a call is `py/callN`, a subscript `py/getitem`, an attribute `py/getattr`. `print(...)` and `range(...)` are special-cased only when they resolve to the builtins |
| `atom` | literals: int (decimal, hex, octal, binary, up to 2^53), float, str (`u`/`r` prefixes and escapes), None, True, False; list and dict displays; parenthesised single expressions |
| `name` | by scope resolution: `py/local-get` on a cell; `py.g/x`; the builtin; or `py/getattr %cls` |
| `testlist_star_expr`, `testlist`, `exprlist` | a single element is lowered; more than one is a tuple and is rejected |

### Consumed by a parent arm

`parameters`, `typedargslist`, `tfpdef`, `varargslist`, `vfpdef`, `augassign`, `except_clause`, `block`, `comp_op`, `trailer`, `subscriptlist`, `subscript_`, `arglist`, `argument`, `testlist_comp`, `dictorsetmaker`, `strings`.

If one of these reaches the dispatch on its own, it fails as `unhandled-rule`; a test checks this.

### Rejected: a qualified `:yang.python.antlr/unsupported` naming rule and construct

- The whole match-statement family (`match_stmt` … `keyword_pattern`, 38 rules).
- `import_*`, `dotted_*`, and `with_stmt`/`with_item`.
- `yield_*`, `async_*`, and `decorator(s)`/`decorated`.
- `del_stmt`, `assert_stmt`, and `annassign`.
- `star_expr`, `sliceop`, and `comp_for`/`comp_iter`/`comp_if`.
- `test_nocond`, `lambdef_nocond`, `single_input`, `eval_input`, and `encoding_decl`.

Inside handled rules, these constructs are also rejected with a qualified diagnostic:
- `finally`; `raise from`; a bare `raise` outside `except`.
- Tuples and tuple targets; slices.
- `**`, `%`, `//`, `@`, shifts and bitwise operators; unary `~`.
- `in`, `not in` and `<>`.
- Default, keyword, star and annotated parameters; return annotations.
- Keyword and star arguments; more than 6 call arguments.
- Multiple inheritance.
- Comprehensions and generator expressions.
- bytes and f-strings; imaginary numbers; integers above 2^53; Ellipsis.
- `print` or `range` used as a value.

A rule not in any of the three sets fails with `:yang.python.antlr/unhandled-rule` naming the rule, and a test checks this. `break`, `continue` or `return` outside their construct, and duplicate parameters, are `:yang.python.antlr/syntax` errors.

## Choices I made where the brief left it open

- **Handler stack: a cell, not a hidden parameter.** The stack lives in the store as `py.rt/handlers`, a chain of `[continuation rest]` pairs.
  - A hidden parameter would change the calling convention of every guest function and of every prelude function that calls back into guest code (method dispatch, `__init__`).
  - The cost of the cell is that every escape must restore the stack it saw at capture. `py/call-ec` hands guest code an escape function that does this, never the raw continuation. So leaving a `try` by `return`, `break` or `continue` leaves no stale handler; `escape-restores-handlers-test` checks this.
- **Telling a first pass from a re-entry.** `py/try` and `py/call-ec` do this with `py/kont?`, which is `(= (get r :type) :reified-continuation)`. It is sound only because guest values are never raw continuations.
  - **This depends on today's map representation, the same on all four VMs. If D7 makes continuations a host type, this test must change with it.**
- **Strings are tagged `{:py/str s}`.** The prelude cannot test "is this a number" without a type predicate, and none of the brief's data-module names is one. Tagging makes numbers identifiable by elimination and keeps host strings free for attribute names.
- **Generated names come from CST node ids** (`%brk17`), so there is no counter to thread. Guest locals keep their Python names in the lexical environment, and guest globals live under `py.g/`, so a guest `get` or `conj` cannot shadow a primitive or prelude name.
- **`finally` is rejected, not implemented.** Doing it properly means every escape running pending finalizers between the frames it discards; that is not "simple".

## Prelude contents

About 110 `yin/def` definitions in `yang.python.antlr.prelude`. They are written in a small s-expression notation (`fn`, `if`, `do`, `let`, `quote`, `%capture`), and `uast/sexp->uast` turns that into canonical `:lambda`, `:application`, `:if`, `:literal`, `:variable` and `:vm/current-continuation` nodes. No new node type is introduced.

- **Kinds:** `py/kont?`, `py/cell?`, `py/str?`, `py/str`, `py/tuple`, `py/conj`, `py/numeric?`, `py/num`, `py/zero?`.
- **Exceptions and escapes:** `py/make-class`, `py/make-instance`, `py/make-exc`, `py/raise`, `py/raise-new`, `py/type-error`, `py/as-exception`, `py/try`, `py/call-ec`, `py/subclass?`, `py/isinstance`.
- **Locals:** `py/local-get` (raises UnboundLocalError on `:py/unbound`).
- **Attributes:** `py/class-lookup` (single-inheritance chain), `py/method`, `py/bind`, `py/getattr`, `py/setattr`, `py/attr-error`.
- **Calls:** `py/call0`…`py/call7` dispatch a bound method (prepending self), a class (instantiating), or anything else (applying directly). `py/new0`…`py/new6` run `__init__`, or else store `args`.
- **Numbers:** `py/arith`, `py/add` (numbers, or str with str), `py/sub`, `py/mul`, `py/truediv` (raises ZeroDivisionError; the result is always a float), `py/neg`, `py/pos`, `py/lt/gt/le/ge`.
- **Equality:** `py/eq` (numeric across int, float and bool; element-wise on lists; identity on other objects), `py/eq-objects`, `py/eq-items`, `py/ne`, `py/is`, `py/is-not`.
- **Truthiness:** `py/truthy`, `py/obj-truthy`, `py/not`.
- **Strings:** `py/str-concat`.
- **Lists:** `py/list`, `py/list-append`, `py/index` (negative indexes and IndexError).
- **Dicts:** `py/key` (normalizes 1, 1.0 and True to one key), `py/dict-new`, `py/dict-set`, `py/dict-fill`, `py/dict-from`. Order is kept by the keys vector; no host map is ever iterated.
- **Subscripts:** `py/getitem`, `py/setitem` (KeyError, IndexError, TypeError).
- **Iteration:** `py/range3`, `py/range-at`, `py/range-len`, `py/iter-at`, `py/len`.
- **print:** `py/snapshot`, `py/snapshot-all`, `py/snapshot-pairs`, `py/snapshot-obj`, `py/snapshot-exc`, `py/print`. print is an effect-free collector into the `py.rt/out` cell, holding plain-data snapshots.
- **Module:** `py/run-module`.
- **State:** the `py.rt/handlers` and `py.rt/out` cells, and 14 builtin classes (`py.b/object`, `BaseException`, `Exception`, `ArithmeticError`, `ZeroDivisionError`, `LookupError`, `IndexError`, `KeyError`, `TypeError`, `ValueError`, `AttributeError`, `NameError`, `UnboundLocalError`, `RuntimeError`).

**Host names the prelude uses but does not define:**
- `cell/new`, `cell/get`, `cell/set!`.
- `data/count`, `data/into`.
- **`data/code-points` and `data/from-code-points` are my guesses** at the data module's "code-point string ops". They are used only by `py/str-concat` and `py/len` on strings, and `prelude/assumed-host-names` lists them.
- The e2e composition expects the registry steps `yin.vm.module/register-cell-module` and `yin.vm.module/register-data-module`. They are named in one place, `e2e-test/host-registrars`.

**Adjust these once the parallel slices publish their real names.**

## Test outcomes

### Runnable now: all pass

| Namespace | Host | Result |
|---|---|---|
| `yang.python.antlr.cst-export-test` | JVM | 12 tests, 46 assertions, 0 failures |
| `yang.python.antlr.scope-test` | JVM | 9 tests, 31 assertions, 0 failures |
| `yang.python.antlr.lower-test` | JVM | 17 tests, 51 assertions, 0 failures |
| `yang.python.antlr.lower-portable-test` | JVM and Node | 0 failures (6 tests, 22 assertions together with `prelude-parity-test` on the JVM) |
| `yang.python.antlr.prelude-parity-test` | JVM and Node | 0 failures (counted with `lower-portable-test` above) |

- **CST export:** goldens for rule names and byte spans (a two-byte and a four-byte code point), INDENT/DEDENT/NEWLINE positions, EOF as synthetic, syntax and lexer errors as records with no nodes, determinism, the source protocol (waits for the seal; missing chunk; conflicting duplicate), and chunk invariance across 5 splits including one character per chunk.
- **Scope:** locals and params, module names as globals, `global`, `nonlocal`, forward capture, class scopes (methods skip them), lambdas, and the static errors.
- **Lowering:** goldens for assignment, literals, a function with a return, a function without one, `while`, `for`, `try`, `or` and a comparison chain, a class, and assignment order; program shape; tail marks; 16 unsupported constructs, each qualified with rule and construct; 5 static flow errors; every grammar rule classified; chunk invariance through both stages on datom batches; stage diagnostics.
- **Portable lowering:** a hand-built packet golden, the unknown-rule refusal, the malformed-tree refusals, and transform routing.
- **Prelude parity:** 32 Python-semantics cases on all four VMs, using only the function definitions (loading them allocates nothing). Covers bool arithmetic, truthiness, 1 == 1.0 == True, key normalization, and ranges.

### Awaiting the parallel slices: `yang.python.antlr.e2e-test`, JVM, 21 deftests

All 21 fail today, each with the explicit message "awaiting host modules, absent: [yin.vm.module/register-cell-module yin.vm.module/register-data-module]". Nothing is stubbed.

**Every one of the 21 needs cell slice 1:**
- `arithmetic-and-print-test`, `closures-and-nonlocal-test`, `global-declaration-test`, `forward-capture-test`, `recursion-test`
- `while-break-continue-test`, `for-range-list-else-test`, `lambda-test`, `long-loops-test`
- `try-except-raise-test`, `try-else-and-reraise-test`, `escape-restores-handlers-test`, `unbound-local-test`, `zero-division-test`, `uncaught-exception-test`
- `classes-test`, `class-attributes-and-identity-test`
- `lists-alias-and-index-test`, `dict-order-and-key-normalization-test`, `strings-test`, `boolean-operators-test`

**Four of them also call the data module** (measured by instrumenting the scratch module, not guessed):
- `lists-alias-and-index-test`, `dict-order-and-key-normalization-test`, `boolean-operators-test`: `data/count`.
- `strings-test`: `data/count`, `data/into`, `data/code-points`, `data/from-code-points`.

These four are also listed in `e2e-test/uses-data-module`.

### Scratch validation (no longer on disk)

- **What it was:** a temporary file, `build/scratch/e2e_scratch.clj`, that registered atom-backed `cell` and `data` modules as `:pure` host modules and used `with-redefs` on the e2e composition's registry lookup.
- **Result:** all 21 program tests passed on all four VMs, 105 assertions. The run was repeated on the final code.
- **Bug it caught:** exception `args` were an untagged vector, so `print(e.args)` leaked `{:py/str ...}` values. Tuples are now tagged.
- **Scope of the claim:** the file was deleted before finishing. It proves the lowering and prelude against box semantics, not against the real cell slice 1.

### Verification commands (all run in the foreground)

- **clj-kondo:** `clj -M:kondo --lint` on all 15 changed `.clj`/`.cljc` files plus `deps.edn`, `bb.edn` and `manifest.edn`, as separate arguments. **0 errors, 0 warnings**, after fixing 3 warnings it first reported.
- **cljstyle: not run.** `cljstyle check <files>` returned "This command requires approval" in this session, so **formatting is unverified.**
- **Generation from clean:** `rm -rf build/antlr` then `bb gen:python-antlr`. It fetched at the pin, verified the 4 grammar digests, matched the 4 recorded generated digests, and compiled.
- **Focused JVM:** the six new namespaces ran 65 tests and 171 assertions, with 21 failures, all e2e and all "awaiting host modules".
- **Full `clj -M:test`:** 2487 tests, 185347 assertions, **21 failures (exactly the e2e set), 0 errors**.
- **`bb test:cljs`:** build completed with 0 warnings. 2333 tests, 51504 assertions, **0 failures, 0 errors**. The output shows `Testing yang.python.antlr.lower-portable-test` and `Testing yang.python.antlr.prelude-parity-test`.
- **`bb test:cljd`:** not run, per the brief.

## Known deviations from Python (phase A, documented rather than hidden)

- **Some Python errors surface as host errors, not catchable Python exceptions:**
  - an undefined global ("Unable to resolve symbol" instead of NameError);
  - calling a function with the wrong number of arguments;
  - integer overflow past 64 bits on the JVM;
  - `+` on a builtin function value.
- **`isinstance` covers class instances only.** `isinstance(1, int)` is False; there are no `int` or `str` classes.
- **Equality:** dict `==` is identity. Printing a list that contains itself does not terminate.
- **Instantiation:** a class without `__init__` accepts any arguments and stores them as `args`.
- **Unsupported for now:** functions as distinct-identity objects, `id()`, the `except ... as e` name being deleted after the handler, and `str()`/`int()` conversions.

## Mechanical versus not

The owner's claim holds for most of the grammar and fails at a few specific places.

**Mechanical, near one-line arms:** the expression grammar (`test` through `atom`), the statement plumbing, `if`/`elif`/`else`, literals, calls, subscripts, attributes, list and dict displays, and `pass`/`global`/`nonlocal`. Most of the "tedious" part is the grammar's single-child chains (`test → or_test → … → atom`) and deciding which rules are consumed by a parent. The grammar rules themselves mapped mechanically.

**Not mechanical:**
1. **Scope analysis.** It has to run over the whole block before lowering: forward capture, nonlocal resolution that skips class scopes, and class bodies that are not lexical scopes for their methods. The mapping depends on this rather than being derived from it.
2. **Continuations do carry all control flow, but three things around them were design work:**
   - telling the first pass from a re-entry at the capture point;
   - keeping the handler stack consistent across escapes;
   - loops needing self-application and tail marks, because the Universal AST has no `letrec` and a loop that does not tail-call grows the continuation on the semantic VM.
3. **Semantics that no rule mapping yields: value representation, type dispatch and equality.** Tagged strings, dict key normalization, "numeric by elimination", `==` versus `is`, and the dict layout (index map plus order vector, no host-map iteration) came from Python's rules applied to what the host primitives can distinguish. This is the bulk of the prelude and is where portability risk sits.
4. **Positions from the grammar's helper.** The upstream indentation helper reports wrong token positions. A generic exporter cannot correct them, so a pinned helper patch was needed.
5. **Assignment.** Target shapes, evaluation order (value first, then receiver and key), and augmented assignment evaluating the receiver once are three small, non-uniform cases rather than one arm.

## Open items for the orchestrator or owner (phase A; see Phase B below for status)

- Confirm or rename the assumed host names above once cell slice 1 and the data module land. **Done in Phase B.**
- License of the two unannotated Java helpers: needed before any redistribution. **Still open.**
- `py/kont?` couples to the continuation representation; flag it to the D7 track. **Removed in Phase B.**
- cljstyle needs a run by someone with permission. **Still open.**

---

# Phase B

The base is now local commit `fe8bce4a`: master `5e790683` (D4 plus cell slice 1) plus the `yin.vm.data` commit. Everything runs against the **real** `cell` and `data` modules, with nothing stubbed. Nothing is staged or committed. The phase A text above is left as written; where Phase B supersedes it, this section says so.

**Headline:**
- 26 e2e programs pass on all four VMs against the real modules: the phase A 21, plus 5 new ones for floats, NameError, `globals()`, function objects and arity errors.
- The full JVM suite and the Node suite are green.
- cljstyle is still permission-blocked.

## What changed, per brief item

**a. Real host names.**
- Prelude string operations now call `data/str-concat` and `data/str-length`.
  - These are simpler than going through code points, and they are among the data module's exports.
  - My phase A guesses (`data/code-points`, `data/from-code-points`) are gone.
- The prelude also uses `data/count`, `data/into` and `data/subvec`; the set is listed in `prelude/host-names`.
- `e2e-test/host-registrars` is now `[yin.vm.module/register-cell-module yin.vm.data/register-data-module]`, resolved with `requiring-resolve`.
- The "awaiting host modules" path is removed.

**b. Module namespace as a heap dict (owner decision 1).**
- `py/run-module` allocates the module's namespace dict and passes it to the module body as the parameter `%globals`. Every function in the module closes over it lexically, which is Python's own `__globals__` model; there is no store key for it.
- Reads and writes:
  - global reads are `(py/global-get %globals {:py/str "x"})`, and a miss raises `NameError("name 'x' is not defined")`;
  - global writes are `py/global-set`, which covers module-level assignment, names declared `global`, and module-level `def`/`class`.
- **`yin/def` now appears only in the prelude, for its functions, the two runtime cells and the builtins.** `program-shape-test` checks that the module part contains no `yin/def`.
- `globals()` lowers to `%globals` itself, so `globals()['y'] = 3` then reading `y` works.
  - I added this because it is one line and it demonstrates the ruling.
  - `globals` used as a value is rejected with a qualified diagnostic.
  - `del`, `exec` and module `setattr` remain unsupported syntax; the representation no longer blocks them.
- Builtins are still resolved statically when the name is never bound as a global, as in phase A. They are now `py.b/*` store entries.

**c. `py/kont?` replaced by per-capture flag cells.**
- `py/call-ec` and `py/try` each allocate `(cell/new :first)` before `%capture`.
- On the first pass they set it to `:re-entered`. A later arrival at the capture point reads `:re-entered`, and that tells the passes apart.
- Nothing in the prelude inspects a continuation's representation any more; I grepped for `reified` and `kont` and found no match.
- This relies on the box semantics that cell slice 1 provides: `:cell/set!` is not rolled back by continuation invocation.
- `escapes-on-every-host-test` exercises both paths on all four VMs, on the JVM and on Node.
- The cost is one extra cell per `call-ec` or `try` activation. With no heap reclamation, a long-running task grows by that much per call to a function containing `return`.

**d. Float tagging (owner decision 3).**
- **Encoding:** a float is `{:py/float x}` on every host; ints stay untagged.
  - Float literals lower to the tagged form.
  - `py/float` stores `(* 1.0 x)`, so the payload is a double on the JVM.
- **Arithmetic:** `py/arith` returns a tagged float when either operand is a float, and an int otherwise.
  - `py/truediv` always returns a tagged float.
  - Comparisons (`py/compare`) and equality unwrap through `py/num`, so `1 == 1.0` is True and `2.5 < 2` is False.
  - A float list index is a TypeError.
  - Float arguments to `range` are a TypeError.
- **Dict keys:** `py/key` normalizes every numeric key to `(* 1.0 (py/num k))`, so `1`, `1.0` and `True` are one key, and the first-inserted key object is kept for display.
- **Printing:**
  - `print` still collects plain-data snapshots, and floats stay tagged inside them.
  - The new portable namespace `yang.python.antlr.render` turns snapshots into Python text at the boundary. This is the same boundary-derivation stance as owner decision 2.
  - Float repr matches Python for magnitudes between 1e-4 and 1e16, for integral values, and for `inf`, `nan` and `-0.0`. Outside that range it uses the host's shortest form; on the JVM that differs from Python's exponent notation (for example `1.0E20` against `1e+20`).
- **Node parity:** `printed-floats-on-every-host-test` runs `print(2, 4/2, 1 + 2.0, 0.5)` through `py/run-module` over the real cell and data modules on all four VMs, and it ran on Node in `bb test:cljs`. It expects `"2 2.0 3.0 0.5"` everywhere.
- **Why render outside the guest:** `str()` of a number cannot be built in the prelude, because the `data` module has no number formatting. Rendering printed output therefore happens outside the guest, and guest-visible `str(3.5)` stays unsupported.

**e. Function objects in cells.**
- A `def` or `lambda` evaluates to `(py/make-function name nparams defaults star? code)`. That is a cell holding `{:py/type :function :name :nparams :defaults :star? :code}`, where `code` takes **one argument vector**.
- **Every call lowers to `(py/call f [args...])`.** `py/call` handles each kind of callee:
  - bound methods: prepends the receiver;
  - classes: instantiates, running `__init__` through `py/call`;
  - function objects: `py/bind-args` checks the count, fills trailing defaults and packs `*args` into a tuple;
  - anything else: TypeError "object is not callable".
- **Consequences:**
  - A wrong argument count is a Python `TypeError` ("g() got the wrong number of positional arguments"). It carries no numbers, because there is no number-to-string primitive.
  - The phase A limit of 6 arguments and the `py/call0`…`py/call7` / `py/new0`…`py/new6` families are gone.
  - `f.__name__` works, and lambdas are named `<lambda>`.
  - Identity is the cell: `g is g` is True, and two lambdas with the same body are not identical.
  - Builtins (`len`, `isinstance`, `print`, `range`, and list `append`) are function objects too, so `L = len; L([1, 2])` works, `print` is an ordinary call, and it prints as `<function len>`.
- **Now supported:** default values (evaluated at definition time in the enclosing scope) and `*args`.
- **Still rejected, with a qualified diagnostic:**
  - `**kwargs`, keyword-only parameters, parameter annotations, and keyword arguments at call sites. Each needs keyword binding in `py/bind-args`, which is not simple.
  - `globals` used as a value.
- "non-default argument follows default argument" is a `:yang.python.antlr/syntax` error.
- A tuple used by `*args` gained `len()` and iteration; that gap was found by the first real-module run.

**f. Real-module e2e.** `yang.python.antlr.e2e-test` builds the registry with the real `register-cell-module` and `register-data-module`. It passes the lowering's program stream to the AST walker's observer session, and builds the semantic, stack and register VMs from the same datom batch. **All 26 tests pass on all four VMs; nothing is stubbed.**
- First real-module run: 25 of 26 passed. The one failure was `len()` of a `*args` tuple, which I fixed.

## Files in Phase B

- **New:** `src/cljc/yang/python/antlr/render.cljc`.
- **Changed:**
  - `src/cljc/yang/python/antlr/prelude.cljc`: rewritten around the new encoding and calling convention.
  - `src/cljc/yang/python/antlr/lower.cljc`: globals, calls, `param-spec`/`function-value`, float literals, `globals()`.
  - `test/yang/python/antlr/e2e_test.clj`: real registrars, rendered-text expectations, 5 new tests. The phase A `uses-data-module` list is removed, because every test now runs over the real modules.
  - `test/yang/python/antlr/lower_test.clj`: goldens updated for dict globals, function objects, `py/call` and float literals; a new defaults/`*args` golden; the refusal list updated.
  - `test/yang/python/antlr/lower_portable_test.cljc`: hand-packet golden.
  - `test/yang/python/antlr/prelude_parity_test.cljc`: float cases; real-cell runs for printed floats and flag-cell escapes; renderer checks.
- **Unchanged since phase A:** the parser, the CST exporter, scope analysis, the stage driver, and the grammar pin and manifest.
- Nothing outside `yang.python.antlr.*`, `yang.antlr.*`, the tests, `deps.edn`/`bb.edn` and the manifest was edited.

## Phase B test outcomes (all run in the foreground)

| Check | Result |
|---|---|
| clj-kondo, 16 changed `.clj`/`.cljc` files plus `deps.edn`, `bb.edn` and the manifest, as separate arguments | 0 errors, 0 warnings |
| cljstyle | **not run**: `cljstyle check` (tried on the full list, and on `prelude.cljc` alone) returned "This command requires approval". Formatting is unverified. |
| Generation from clean: `rm -rf build/antlr`, then `bb gen:python-antlr` | fetched at the pin, 4 grammar digests verified, 4 generated digests matched, compiled |
| Focused JVM, the six `yang.python.antlr.*` test namespaces | 74 tests, 299 assertions, 0 failures, 0 errors |
| Full `clj -M:test` | 2635 tests, 186960 assertions, 0 failures, 0 errors |
| `bb test:cljs` | build completed with 0 warnings; 2485 tests, 52830 assertions, 0 failures, 0 errors; `yang.python.antlr.lower-portable-test` and `yang.python.antlr.prelude-parity-test` both ran on Node |
| `bb test:cljd` | not run, per the brief |

**By namespace (JVM):**
- `e2e-test`: 26 tests, 130 assertions, 0 failures.
- `lower-test` together with `lower-portable-test`: 22 tests, 72 assertions, 0 failures.
- `prelude-parity-test`: 5 tests, 20 assertions, 0 failures.
- `cst-export-test` and `scope-test`: pass, unchanged since phase A.

## Known deviations after Phase B

**Fixed, and no longer deviations:**
- An undefined global is now `NameError`.
- A wrong arity is now `TypeError`.
- `4/2` printed `2` on Node; it now prints `2.0` on every host.

**Remaining:**
- Integer overflow past 64 bits is a host error on the JVM.
- `isinstance` covers class instances only.
- Dict `==` is identity.
- Printing a self-containing list does not terminate.
- A class without `__init__` accepts any arguments and stores them as `args`.
- `str()`/`int()` are unsupported, and `repr` exists only at the boundary.
- Float repr outside [1e-4, 1e16) differs on the JVM.
- `except ... as e` does not delete `e` afterwards.
- Every unit bundles its own prelude, so builtin class identity is per unit.
- **Not in the lowering:** safepoints (owner decision 4; they belong in a separately attached interpreter) and guest-visible line numbers (decision 2).

## Mechanical versus not: Phase B addendum

- **The Phase B changes were mostly prelude work, not lowering work.** In the lowering:
  - globals changed two functions;
  - float literals changed one line;
  - calls changed one function;
  - the parameter-list parser is the only new lowering logic.
- **The calling-convention decision (one argument vector) drove the rest.** It removed the per-arity function families, made `*args` and defaults a prelude concern, and turned builtins into ordinary function objects.
- **The flag-cell escape is fully mechanical.** It is two extra operations per capture, and it uses only the box semantics that cells were ruled to have.

## Open items

- The license of the two unannotated Java helpers is still unresolved; it is needed before any redistribution.
- cljstyle still needs a run by someone with permission.
- A number-to-string primitive in `data` would allow a guest-visible `str()`/`repr` and error messages that include counts. That is an owner or Architect call.
- Reclamation pressure from Phase B:
  - one flag cell per `call-ec` or `try` activation;
  - one cell per function object.
