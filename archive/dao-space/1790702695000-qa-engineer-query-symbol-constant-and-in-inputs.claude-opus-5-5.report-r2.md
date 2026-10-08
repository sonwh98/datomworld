Completed-GMT: 2026-09-29 18:05:29 GMT
Completed-Local: 2026-09-30 01:05:29 +07
Coding-Agent: claude
Session-ID: 5939e397-ce71-4ffa-8bfa-9f5ba06d2a83

# Report r2: bare-symbol CONSTANT semantics (owner decision (a))

**Summary:** Bare-symbol constant semantics are implemented. Only `?`-symbols are variables; `_` is the blank; `$`/`%`
keep their meanings; every other symbol is a constant, in patterns and in fn, predicate, special-form and rule
arguments. All lanes are green. The BUG 2 fix from r1 (implicit `$`) stands unchanged. Nothing is staged or committed.

## 1. Did anything rely on a bare symbol acting as a variable? No, but one caller had the bug

**Runtime check.** I temporarily instrumented the *old* `unify`/`resolve-binding` to print every time a non-`?`,
non-`_` symbol was bound or resolved as a variable, then ran the full `clj -M:test` (2365 tests). Only three symbols
were ever bound this way:
- `foo` and `bump`: my own r1 exposing tests.
- `yin/def`: 100+ times. It was bound to `+`, `*`, `f`, `require`, `yin/def` and others.

**Static check.** A reader-based scan of every `:where` clause and rule set in the files that call `q` (src: yin/vm.cljc,
vm/completion, vm/ledger, vm/linker, dao/space/schema; plus every test file) found only one real bare symbol:
`src/cljc/yin/vm/linker.cljc:334` `[?op :variable yin/def]` in `tree-definition-query`. The other scan hits were
false positives: ordinary `let` bindings such as `[(first r1) r1]`, and query vectors built at runtime with a quoted
`op` in query_numeric_test.

**That caller was written for constant semantics.** Its docstring says "an application of the `yin/def` operator with
exactly two operands, the first a literal". Under the old behaviour `yin/def` was a variable, so **any** two-operand
application whose first operand is a literal counted as a definition. Demonstrated via
`yin.vm.linker/tree-definition-occurrences`:
- old semantics: `(+ 'k 2)` gave `[k]`, a spurious definition of `k`.
- new semantics: `(+ 'k 2)` gives `[]`, and `(yin/def 'k 2)` gives `[k]`.

So nothing relied on the variable behaviour; this caller was quietly wrong under it. I left linker.cljc alone (it is
outside the allowed files and needs no change); it is now correct. No test asserted the spurious result: the full
suite was green both before and after.

## 2. Implementation (src/cljc/dao/space/query.cljc)

Both changes use the existing `query-var-symbol?` classifier.
- **`resolve-binding` (:935):** a `?`-symbol gives its bound value (or `FREE` while unbound); `_` gives `FREE`; any other
  term, a bare symbol included, is returned as the constant it is. This one function serves pattern slots, the
  index-probe arguments, fn and predicate arguments, special-form arguments (`get-else`/`missing?`), rule-call arguments
  and the planner's cost estimate.
- **`unify` (:981):** the constant branch is now `(not (query-var-symbol? sym))`, which was `(not (symbol? sym))`.
  A bare symbol is therefore compared with `cbor/content=` instead of being bound. This covers pattern slots, rule-call
  results and fn result bindings.
- **Unchanged positions:**
  - The fn-name position (first of the fn clause's list) and the rule-name position.
  - `$`-source symbols: they are stripped by `clause-db-and-pattern` and resolved by `resolve-fact-index`.
  - The `%` rule set.

## 3. Tests

**test/dao/space/query_test.cljc**
- `a-bare-symbol-in-a-pattern-is-a-constant` (replaces the r1 open-question test; the refusal alternative is dropped):
  - `[?e :a foo]` gives `#{[1]}`.
  - A constant in the attribute slot, `[?e foo ?v]`, gives `#{[3 bar]}`.
  - An absent constant gives `#{}`.
  - A repeated constant does not join like a variable.
- `a-bare-symbol-fn-argument-is-a-constant`:
  - `[(= ?v foo)]` filters to `#{[1]}`.
  - `[(str foo) ?s]` gives `#{["foo"]}`.
- `a-bare-symbol-rule-argument-is-a-constant`:
  - `(has ?e foo)` gives `#{[1]}`.
  - `(has ?e ?v)` still binds both rows.
- `variables-and-the-blank-are-unchanged` (regression; passes under both the old and the new semantics):
  - A `?`-variable binds and joins.
  - `_` matches anything in pattern slots.
  - `_` as an fn argument still throws "Unbound variable in fn clause".

**test/yin/repl/query_test.cljc**
- `a-bare-symbol-in-a-pattern-is-the-symbol-constant` (renamed, refusal alternative dropped): on all 4 VMs,
  `[?op :yin/name bump]` gives `#{[3]}`.

**Mutation proof.** I temporarily restored both old checks (`(if (symbol? sym) ...)` in `resolve-binding` and
`(not (symbol? sym))` in `unify`). `-n dao.space.query-test -n yin.repl.query-test` then gave **9 failures and 2
errors**:
- pattern constant: :339, :340, :341
- REPL constant: :196, once per VM (4)
- rule argument: :357
- fn argument: 2 errors, "Unbound variable in fn clause"
- the regression test's join at :372, which then used a bare `foo`

I then made that join line semantics-neutral (`[3 _ ?v]`) so the regression test passes under either semantics. I
reverted the mutation; grep confirms query.cljc:940 `(cond (query-var-symbol? sym) ...` and :989
`(not (query-var-symbol? sym))`.

## 4. Spec

`docs/design/dao.space.query.md`, "Datalog surface": added a **Terms** bullet at the top. It states:
- Datomic-like: only `?`-symbols are variables.
- `_` is the blank, and not an fn argument.
- `$`-symbols name sources, and `%` the rule set.
- Every other symbol is a constant (example `[?op :yin/name inc]`).
- The rule covers pattern positions and the arguments of fn, predicate, special-form and rule-invocation clauses; the
  fn-name and rule-name positions keep naming the fn or rule.

## 5. Verification

- **kondo** (query.cljc and the 2 test files): 0 errors, 4 warnings, all existing `Unresolved var: repl/query-*`
  references in yin/repl/query_test.cljc (the same 4 as r1; not in changed code).
- **cljstyle check:** **blocked**. The permission prompt was not approved; it did not run.
- **Focused JVM** (dao.space.query-test, yin.repl.query-test, yin.repl.index-test, yin.repl-test): 122 tests, 704
  assertions, **0 failures, 0 errors**.
- **Full `clj -M:test`:** 2368 tests, 184358 assertions, **0 failures, 0 errors**. This includes every other dao.space
  and yin caller of `q` (linker, vm requirements, macro, completion, ledger).
- **`bb test:cljs`:** 2273 tests, 50838 assertions, **0 failures, 0 errors**. "Testing dao.space.query-test" and
  "Testing yin.repl.query-test" both appear.
- `bb test:cljd`: not run, as instructed.

## 6. Follow-ups outside this round's scope (not changed)

- **`:in` with a bare symbol:** `classify-in-pattern` still treats a bare `:in` symbol such as `:in $ foo` as a scalar
  binding. Since `foo` in the `:where` clause is now the constant `foo`, that input silently does nothing. The same
  holds for a bare symbol in `:find` (it projects nil) or as a binding form (`[(f ?a) out]` is now a constant check).
  One fix would be to refuse non-`?`, non-`$`, non-`%` symbols in `:in`, `:find` and binding forms.
- **Rule heads:** a bare symbol in a rule head is now a constant, so its head var is never bound and the rule throws
  "Rule head var not bound by rule body". Datomic requires `?` head vars, so this is only a note.
- **`match` (the Linda template):** it compares every slot except `_`/nil literally, including `?x`. It was never part
  of the q term rule and is unchanged.
