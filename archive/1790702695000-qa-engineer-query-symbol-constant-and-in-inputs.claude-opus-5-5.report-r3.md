Completed-GMT: 2026-09-29 18:36:19 GMT
Completed-Local: 2026-09-30 01:36:19 +07
Coding-Agent: claude
Session-ID: 5939e397-ce71-4ffa-8bfa-9f5ba06d2a83

# Report r3: gate P1/P2 plus owner decisions (map inputs by :in arity, reject bare symbols in :in/:find)

**Summary:** All three items are done, test-first.
- **P1 (map inputs):** the bridge now splits the caller's arguments by the declared `:in` arity, so a map can be an
  input.
- **Owner decision (bare symbols):** a bare symbol in `:in` or `:find` is refused with a clear error. Through the REPL
  bridge that error is `:yin.repl.query/query-failed`.
- **P2 (linker):** a test assertion now pins that `(+ 'k 2)` produces no definition.

All lanes are green. `linker.cljc` was not edited. Nothing is staged or committed.

## 1. Tests first: failures on the r2 code

Run: `clj -M:test -n dao.space.query-test -n yin.repl.query-test -n yin.vm.linker-test` gave 138 tests, 879 assertions,
**37 failures**, 0 errors.

**test/dao/space/query_test.cljc** `a-bare-symbol-in-in-or-find-is-refused` (:383): 9 failures, one per case. Each
expected a throw matching `#"bare symbol"`; actual was `nil` (no throw). The cases:
- `:in` scalar `foo`
- `:in` tuple `[?v w]`
- `:in` collection `[v ...]`
- `:in` relation `[[?v w]]`
- `:find` variable `foo`
- `:find` scalar `foo .`
- `:find` collection `[foo ...]`
- `:find` aggregate `(count e)`
- `:find` `(pull e [*])`

The same test's positive case already passed on the old code: `:in $ % _ [?v ...]` returns `#{[1]}`.

**test/yin/repl/query_test.cljc** (each on the 4 VMs):
- `a-map-input-is-an-input-and-one-more-map-is-options`:
  - :197, `:in ?m` with `{:k 1}`: expected `"1"`, actual "q options must be {:view :current} or {:view :history},
    got {:k 1} (:yin.repl.query/invalid-input)".
  - :201 and :202, `{:k 1} {:view :current} {:k 2}`: expected query-failed "input arity", actual "q options must be
    …, got {:k 2} (:yin.repl.query/invalid-input)".
  - Already passing: `{:k 1} {:view :current}` (gives `"1"`) and `{:k 1} {:view :sideways}` (gives invalid-input).
- `a-bare-symbol-in-in-or-find-refuses-the-query` :217 and :218 (8 + 8 failures):
  - `:in f` with `'bump`: expected query-failed "bare symbol", actual `#{[nil]}`.
  - `:find op`: expected query-failed "bare symbol", actual `#{}`.

**test/yin/vm/linker_test.cljc** `a-constant-key-yin-def-application-is-a-definition`: the new assertion that
`(+ 'k 2)` gives `[]` *passed* on the r2 code. That is expected: it pins the r2 fix, so its evidence is mutation M3
below.

## 2. P1: map inputs through the bridge (src/cljc/yin/repl/query.cljc)

**Cause:** `split-args` always took a final map as the options map.

**Fix:**
- New `caller-patterns`: the declared `:in` patterns minus the implicit `$`.
- `split-args` now takes the query. It uses exactly that many arguments as inputs (maps included). After them it
  allows at most one more argument, which must be a map and is the options map.
- Anything else is refused with `::query-failed`: "query failed: query input arity must match :in, N :in inputs and an
  optional options map expected, got M arguments".
- `answer` splits first. It then validates the options with `view-of` exactly as before (a bad `:view` is still
  `::invalid-input`), then reads the snapshot, then evaluates `(with-index query)`.
- `with-index` now only rewrites `:in` to put `$` first; the arity check moved into `split-args`.

## 3. Owner "reject now": bare symbols in :in / :find (src/cljc/dao/space/query.cljc)

New `check-declared-symbols`, called in `q` right after `in-patterns`, before any binding or evaluation.

**`:in`:**
- A top-level pattern symbol must be a `?`-variable, a `$`-source, `%`, or `_`.
- Every symbol inside a tuple, collection or relation binding form must be a `?`-variable, `_`, or `...`.

**`:find`:** every element the existing `parse-find`/`parse-find-element` extract must be a `?`-variable:
- plain variables in all four find shapes
- aggregate arguments
- `pull` variables (the pull pattern itself, e.g. `[*]`, is not checked)

The error is `ex-info` "A bare symbol in :in is not a query variable: foo (only ?-symbols are variables)", with data
`{:clause :in|:find, :symbol foo}`. The bridge wraps it as `:yin.repl.query/query-failed`.

**Result bindings are unchanged, as the gate described.** A bare symbol in `[(f ?a) out]` is a constant comparison
that binds nothing. This is now stated in the spec.

## 4. P2: linker regression (test/yin/vm/linker_test.cljc)

`a-constant-key-yin-def-application-is-a-definition` now also asserts `[]` for
`((:definitions-fn linker/ast-format) (vm/ast->semantic-bytecode (app (v '+) (lit 'k) (lit 2))))`, with the message
"only the yin/def operator defines …". This pins the `linker.cljc:334` `[?op :variable yin/def]` behaviour.

## 5. Mutation proofs (all reverted; grep confirms)

- **M1 + M2, run together:**
  - M1: `answer` went back to the old inline final-map-is-options split.
  - M2: the `_ (check-declared-symbols in-patterns find)` line was removed.
  - Result: `-n dao.space.query-test -n yin.repl.query-test` gave **41 failures**:
    - the 9 engine refusal cases
    - 8 + 8 bridge refusal assertions
    - 4 + 4 + 4 map-input assertions
    - 4 at `scalar-in-inputs…` :183, where the arity text differs
- **M3:** `resolve-binding` and `unify` went back to the old `symbol?` checks. Result: `-n yin.vm.linker-test` gave
  **1 failure** at linker_test.cljc:1349, with actual `[{:name k, :at [… [[3 2]]], :conditional? false}]`.
- **Revert confirmed by grep:**
  - query.cljc:940 `(cond (query-var-symbol? sym) …`
  - query.cljc:989 `(not (query-var-symbol? sym))`
  - query.cljc:1646 `_ (check-declared-symbols in-patterns find)`
  - yin/repl/query.cljc:440 `split (split-args query more)`

## 6. Spec (docs/design/dao.space.query.md, "Datalog surface", Terms bullet)

Added to the Terms bullet:
- A bare fn result binding is a constant comparison that binds nothing.
- A bare symbol in an `:in` pattern, inside an `:in` binding form, as a `:find` variable, as an aggregate argument, or
  as a `pull` variable is refused as an invalid declaration.
- The list of what `:in` admits: `?`-variables, `_`, `$`-sources, `%`, and `...`.

## 7. Verification

- **kondo** (the 5 changed .cljc files): 0 errors, 4 warnings, all the existing `Unresolved var: repl/query-*`
  references in yin/repl/query_test.cljc (same as r1/r2).
- **cljstyle check:** **blocked**. The permission prompt was not approved; it did not run.
- **Focused JVM** (dao.space.query-test, yin.repl.query-test, yin.vm.linker-test, yin.repl-test): 173 tests, 1067
  assertions, **0 failures, 0 errors**.
- **Full `clj -M:test`:** 2371 tests, 184407 assertions, **0 failures, 0 errors**.
- **`bb test:cljs`:** 2276 tests, 50885 assertions, **0 failures, 0 errors**. "Testing dao.space.query-test",
  "yin.repl.query-test" and "yin.vm.linker-test" all appear.
- `bb test:cljd`: not run, as instructed.

## 8. Notes

- **One behaviour change for callers:** a caller who passes a single map input to a query that declares no caller
  `:in` pattern still has that map read as the options map, so it is validated by `view-of`, as before. Only a declared
  input slot takes a map.
- **Rule heads:** still the r2 behaviour. A bare head symbol throws "Rule head var not bound by rule body". The gate
  accepted this for now.
