Completed-GMT: 2026-09-29 17:42:41 GMT
Completed-Local: 2026-09-30 00:42:41 +07
Coding-Agent: claude
Session-ID: 5939e397-ce71-4ffa-8bfa-9f5ba06d2a83

# Report: q bare-symbol constants (BUG 1) and :in inputs through the yin.repl bridge (BUG 2)

**Summary:** BUG 2 is fixed in `src/cljc/yin/repl/query.cljc`. BUG 1 is diagnosed but **stopped**: the docs don't say
what a bare symbol in a pattern should mean, so the choice goes to the owner (options below). BUG 1's exposing tests
are kept and still **fail on purpose** (5 assertions per lane) until that choice is made. Nothing is staged or
committed.

## 1. Tests first (before any production edit)

The tests were written and run against unmodified source; the `src/` diff was empty at that point. (`git diff --stat
-- src` needed approval, so I didn't run it at that moment. The only src edits made later are the BUG 2 fix, and the
final `git diff --stat` shows `src/cljc/yin/repl/query.cljc` as the only changed src file.)

Run: `clj -M:test -n dao.space.query-test -n yin.repl.query-test` gave **74 tests, 406 assertions, 25 failures, 0
errors**.

**BUG 1 on the host:** `dao.space.query-test/a-bare-symbol-in-a-pattern-never-matches-everything` (query_test.cljc:344)
- expected: `(contains? #{::refused #{[1]}} (outcome '[:find ?e :where [?e :a foo]]))`
- actual: `#{[2] [1]}`. Every row matches.

**BUG 1 through the REPL:** `yin.repl.query-test/a-bare-symbol-in-a-pattern-never-matches-every-call`
(query_test.cljc:199), on :ast-walker, :semantic, :stack and :register (4 failures)
- expected: `#{[3]}` (the three call sites of `bump`) or a `:yin.repl.query/query-failed` refusal
- actual: `#{[7]}`. Every application is counted.

**BUG 2:** `yin.repl.query-test/scalar-in-inputs-bind-beside-the-implicit-index`, 5 failures on each of the 4 VMs = 20:
- :177 `:in ?f` plus `'bump`: expected `"#{[3]}"`, actual `"Error: FFI call failed: query failed: query options must
  be a map (:yin.repl.query/query-failed)"`.
- :178 the same with `{:view :current}`: the same error.
- :180 `:in ?f` with `{:view :history}` and d5 patterns: the same error.
- :182 and :183 `:in ?f` with **no** input: expected a query-failed "input arity" refusal, actual **`#{}`**. The query
  silently returned nothing, a second symptom of the same cause.
- Passed even before the fix: an explicit `:in $ ?f` (already gave `#{[3]}`), and too many inputs (the engine already
  refused with "query input arity permits at most one options map").

## 2. BUG 1: diagnosis (stopped before choosing semantics)

**Root cause:** `dao.space.query` never checks for the `?` prefix when it binds pattern terms. Any symbol other than
`_` is treated as a variable:
- `unify` (src/cljc/dao/space/query.cljc:981-985): `(not (symbol? sym))` is the only test for a constant, so `foo`
  falls through to `(assoc binding sym val)`.
- `resolve-binding` (:935-937) looks `foo` up in the binding and gets `FREE`. The index scan (`select-by-index`) then
  treats that slot as a wildcard.
- The engine does have a `?` test (`query-var?` :1099, `query-var-symbol?` :424), but it is only used for
  `not`/`or` var sets and `& ?tail` patterns.

So `foo` is effectively a variable named `foo`. It matches every row. And when it appears twice, it joins the two
positions, as a variable would.

**Host vs REPL: there is no divergence.** The orchestrator's host example got `#{}` only because its rows were
`[1 :a 'foo 0 0]`. Under `current`, `m = 0` rows are retractions, so the view is empty even for `?x`. I checked this: the
same query with `?x` also returned `#{}`. With asserted rows (`[1 :a 'foo 1 1] [2 :a 'bar 1 1]`) the host returns
`#{[2] [1]}`, which is the same "matches everything" behaviour the REPL shows (`#{[7]}` / `#{[8]}`).

The bridge doesn't change the query: `answer` passes the quoted vector through the CBOR-portable request unchanged, and
symbols are portable. So nothing in `yin.repl.query` converts the query.

**The docs are silent.**
- docs/design/dao.space.query.md "Datalog surface" (:346-407) lists pattern clauses, negation, `or`, aggregates, fn
  clauses, special forms, rules and find specs. Nothing says what a non-`?`, non-`$`, non-`_` symbol means in a pattern.
- "Datomic syntax" is cited only for rules (:390). The `:in $ $2` handling (:406) covers source symbols only.
- docs/design/dao.space.md: no mention of bare symbols or constants.
- The namespace docstring (`q`, :1604-1610) and the existing tests don't define it either.

**Options for the owner** (both mean a bare symbol can no longer silently match everything):
- **(a) Constant, as in Datomic/DataScript.** Use `query-var?` in `unify`/`resolve-binding` so that only `?`-symbols
  (and `_`) are variables, and every other symbol is compared with `cbor/content=`.
  - For: yin code stores names as symbols (`:yin/name inc`), so `[?op :yin/name inc]` reads naturally.
  - Needs deciding: the same rule would then apply to fn-clause arguments and rule arguments, where bare symbols
    also currently resolve through the binding.
- **(b) Refuse.** Throw a clear `ex-info` when a pattern contains a bare symbol, and require an `:in` input or a
  literal of another type.
  - This keeps a single way to name a symbol value.
  - In the REPL the user would see a `:yin.repl.query/query-failed` refusal.

Both tests are written so that either option makes them pass. They are marked "OPEN QUESTION" in comments.

## 3. BUG 2: root cause and fix

**Root cause.** `dao.space.query/q` defaults `:in` to `[$]` **only when `:in` is absent** (query.cljc:1613). The
bridge's `evaluate` always calls `(apply query/q query (view db) inputs)`, i.e. the index is always the first input. The
design says **the index is implicit**, with "portable scalar `:in` inputs before that map"
(archive/collab/1790669186000-architect-repl-q-on-require.gpt-6-sol.findings.md:22). So a user writes `:in ?f`, and then:
- One input: the index is bound to `?f`, and `'inc` is one input too many, so the engine takes it as its options
  argument and reports "query options must be a map".
- No input: the index is bound to `?f` and `$` is unbound, so every pattern clause scans nothing and `#{}` comes back
  silently.

The existing tests had only ever used an explicit `:in $ ?n`, which is why this went unnoticed.

**Fix** (src/cljc/yin/repl/query.cljc, bridge only; the engine is unchanged):
- `in-patterns` reads the `:in` patterns of a vector or map query.
- `with-index` removes any `$` the user wrote and declares `$` first, so the index is always `$`. The user's inputs
  fill the remaining patterns in order. A query with no `:in` is passed through unchanged and takes no inputs.
- If the number of inputs doesn't match the number of non-`$` patterns, the bridge refuses with `::query-failed`:
  "query failed: query input arity must match :in, N :in inputs expected, got M". The engine never sees the call.
- `answer` checks this refusal after `view-of` and before reading the snapshot.

Other `$`-sources such as `$2` are left alone. The bridge has only one database, so an input bound to `$2` is refused
by the engine's database-input check, which gives `query-failed`.

## 4. Tests and mutation proof

**New tests:**
- test/dao/space/query_test.cljc: `a-bare-symbol-in-a-pattern-never-matches-everything` (BUG 1, open question).
- test/yin/repl/query_test.cljc, on all 4 VMs:
  - `scalar-in-inputs-bind-beside-the-implicit-index` (BUG 2) covers:
    - `:in ?f` with an input
    - the same with `{:view :current}`
    - an explicit `:in $ ?f`
    - `{:view :history}` with an input
    - too few inputs and too many inputs, each refused with query-failed and "input arity"
  - `a-bare-symbol-in-a-pattern-never-matches-every-call` (BUG 1, open question).

**After the fix:** `-n dao.space.query-test -n yin.repl.query-test` gave 74 tests, 406 assertions, **5 failures**. All 5
are the BUG 1 tests; every BUG 2 assertion passes.

**Mutation:** I temporarily replaced `bound (with-index query inputs)` with `bound [query inputs]`. Running
`-n yin.repl.query-test` then gave 24 failures: all 20 BUG 2 assertions (lines 177, 178, 180, 182, 183, times 4 VMs)
plus the 4 BUG 1 ones. I reverted it, and grep confirms `src/cljc/yin/repl/query.cljc:436: bound (with-index query
inputs)`.

## 5. Verification

- **kondo** (`clj -M:kondo --lint` on the 3 changed files): 0 errors, 4 warnings, all "Unresolved var:
  repl/query-pair-capacity / query-drive-budget / query-call-limit-text / query-row-limit". These are references in
  existing tests, not in my changes.
- **cljstyle check:** **blocked**. The permission prompt was not approved in this session, so it did not run.
- **Focused JVM** (`-n dao.space.query-test -n yin.repl.query-test -n yin.repl.index-test -n yin.repl-test`): 119 tests,
  692 assertions, **5 failures (all BUG 1)**, 0 errors.
- **Full `clj -M:test`:** 2365 tests, 184346 assertions, **5 failures (all BUG 1)**, 0 errors.
- **`bb test:cljs`:** 2270 tests, 50826 assertions, **5 failures (all BUG 1)**, 0 errors. "Testing dao.space.query-test"
  and "Testing yin.repl.query-test" both appear in the output.
- `bb test:cljd`: not run, as instructed.

## 6. Stopped on

- **BUG 1 semantics:** (a) Datomic-style constant vs (b) refusal. This needs an owner or orchestrator decision. Until
  it is made, the exposing tests stay red on every lane (1 host + 4 REPL assertions).
- **The BUG 2 contract I chose.** The bridge now accepts `:in` with or without `$`, and always makes `$` the implicit
  index. The alternative would have been to require `$` and refuse `:in ?f`. I went with implicit binding because the
  design says "the index is implicit". Please confirm that is the intended contract.
