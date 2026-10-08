# Report: $ast slice 4: occurrence rules as pure data, the `subvec` builtin, and the CLJD `%` reader fix

Role: Engineer. Model: claude-opus-5-5. Work is on master and not committed.

Authority:
- fable ruling `collab/1790764371000-architect-repl-free-variable-rules.claude-fable-5-1.findings.md` §1–§4;
- owner decisions on §5 (all four accepted);
- the orchestrator's scope extension of 2026-09-30 20:40, which adds the `yin/repl.cljc` input reader.

**All lanes are green on the final tree:**
- kondo: 0 errors, and no new warnings.
- JVM: 2423 tests, 0 failures.
- Node: 2328 tests, 0 failures.
- Dart: 2290 passed, "All tests passed!", exit 0.

Needs its own Architect sign-off, which must not come from a Claude model alone.

## Changes

| File | Change |
|---|---|
| `src/cljc/dao/space/query.cljc` | New builtin `portable-subvec`, registered as `'subvec`.<br>It has two arities: `(subvec v start)` and `(subvec v start end)`.<br>It refuses with the same text on every host:<br>• a non-vector: "query builtin subvec requires a vector operand";<br>• bad bounds: "query builtin subvec requires integer bounds 0 <= start <= end <= (count v)".<br>It returns the host `subvec` value. No `(into [] …)` was needed: on all three hosts the value is `vector?`, `content=`, and has the same canonical CBOR bytes as a plain vector. |
| `src/cljc/yin/vm.cljc` | `occurrence-rules` becomes the §1 rule: one non-recursive rule over `$ast`/`$occ` that uses only builtins.<br>The docstring covers root scoping, that no `:fns` is needed, the load-bearing `<` guard, and that an unbound `?root` now enumerates (owner decision 4).<br>`free-names` now uses `:in $ast $occ % ?root` and `[$ast ?v :variable ?name]`, with no `:fns`.<br>`occurrence-fns` is deleted. |
| `src/cljc/yin/vm/linker.cljc` | `tree-occurrence-query` now reads `$ast`. `:fns` is dropped. |
| `src/cljc/yin/vm/completion.cljc` | In `segment-scope-rules`, `[(member? …)]` is replaced by `[(identity ?params) [?name ...]]`. `:fns` is dropped. |
| `src/cljc/yin/repl/query.cljc` | Namespace docstring only:<br>• `yin.vm/occurrence-rules` is the opt-in `%` input;<br>• the shell ships no binding for it;<br>• the answer is the raw set, including `yin/def`. |
| `src/cljc/yin/repl.cljc` (scope extension) | Change 1: `read-forms`' `:cljd` branch now pre-escapes `%` tokens and restores them after reading. Two new `:cljd`-only helpers do this: `escape-percent-tokens` and `restore-percent-tokens`. The JVM and Node branches are unchanged. See "The CLJD reader fix". |
| `test/dao/space/query_test.cljc` | `subvec` tests for both arities, the result's type and encoding, and 10 refusals.<br>The production-rule tests now read `$ast` and pass no `:fns`.<br>The unscoped contrast fixture keeps its own private `unscoped-occurrence-fns`.<br>New assertion for owner decision 4 (an unbound `?root` enumerates). |
| `test/yin/repl/ast_query_e2e_test.cljc` | Four positive tests, run on all four VMs from real input lines. |
| `test/yin/repl/query_test.cljc` | The §3 guard `host-functions-cannot-enter-a-query`. It was not present before. |
| `test/yin/repl_test.cljc` (scope extension) | `the-reader-keeps-datalog-symbols-on-every-host`. It checks `%` at the start, middle and end of a vector, `...`, `_`, and `%` next to a list nested inside quoted data. |
| `docs/design/yin.vm.code-as-tuples.md` §4.5, §6.3 | §4.5: the "not root-scoped as written" `p-up`/`occ-anc` text is replaced by the production prefix rule. It now covers what the rule means, why clause order matters, that an unbound `?root` enumerates, and the REPL posture (no binding; raw set with `yin/def`). A stale sentence ("hand-built fixture", "recursive rule") is corrected.<br>§6.3: the `member?`-under-`:fns` text is replaced by the `identity` idiom, and a `subvec` bullet is added. |
| `docs/design/yin.vm.dependency-completion.md` §5.1.1 | The segment rule now uses the `identity` idiom. |

**Other tests that used the deleted names.** Only `test/dao/space/query_test.cljc` used `occurrence-fns`, `p-up` or `occ-anc`, and it was updated. No other test used them.

**Stale mention outside scope, not edited.** `docs/design/yin.vm.code-as-tuples.implementation-plan.md:557` still mentions `p-up`/`occ-anc`. That file is a historical plan.

**Not mine, left untouched:**
- `docs/orchestrator-log.md` (modified in the working tree);
- `collab/slice8-*.log` (untracked).

**Housekeeping.** My scratch files are in `target/slice4/`: the logs, `slice4-mutate.py`, `poll.py`, and `scan_check.clj`. None are in `collab/`.

## The CLJD reader fix

**Defect.** Measured on Dart by the new reader test, red before the fix:
- `(quote [:in $ast $occ % ?root])` printed `[:in '$ast '$occ ' ?root]`. The `%` became a symbol with an empty name.
- Cause: the REPL reads input with `clojure.edn/read-string` on CLJD.
  - That function is a one-argument delegate to `cljd.reader/read-string` (`lib/cljd-out/cljd/edn.dart:147`).
  - `cljd.reader`'s fixed macro table sends `%` to `read-anon-arg` (`reader.dart:1511`).
  - Outside `#()`, `read-anon-arg` interprets the token *after* the `%`.
  - Clojure's `ArgReader` does the opposite: with no arg env bound, it keeps the `%` in the token. So JVM and Node read the plain symbol `%`.

**Fix, `:cljd` branch only:**
- `escape-percent-tokens` replaces each `%`-led token with a placeholder symbol. It skips:
  - strings, comments and character literals;
  - `#(...)` frames, which keep ClojureDart's anon-arg meaning;
  - `%` in the middle of a symbol.
- The placeholder prefix is chosen so it does not occur in the input.
- After `edn/read-string`, `restore-percent-tokens` puts back `(symbol "%")`, `%1`, `%&`, and so on.

**Checked against the JVM reader.** I ran the same scanner on the JVM over these probes:
- `%` at the start and end of a vector;
- `'[% %1 %&]`;
- `%` inside a string and inside a comment;
- `\%`;
- `#(+ % 1) %`;
- `a%`;
- `{:k %}`;
- a collision with the placeholder prefix.

It gave the same forms as `clojure.core/read-string` on every probe. The only difference was gensym numbering inside `#()`.

**Second defect, found on the way.** My first restore used `clojure.walk/postwalk-replace`.
- The reader test turned green, but the five query tests then failed with `dao.jing.cbor refused: unsupported-value (Type)`.
- A temporary debug test, since removed, measured the cause on Dart:
  - the raw read carries no metadata and encodes to 446 bytes;
  - the walked copy fails to encode, even with an empty replacement map;
  - every list rebuilt with `(apply list …)` carries `{:line … :column … :end-line … :end-column … :tag <Type>}` metadata;
  - a plain `mapv`/`apply list` rebuild without resetting the metadata fails the same way;
  - a rebuild that sets each rebuilt value's metadata to the original's, `nil` included, encodes to 446 bytes.
- So `restore-percent-tokens` is an explicit rebuild that always applies `(with-meta rebuilt (meta form))`.
- The docstring records this trap.

**Is there a smaller fix?** Not in this repo:
- `cljd.edn/read-string` has no options arity.
- There is no separate EDN-conformant path on Dart; it is `cljd.reader`.
- The macro table is not configurable.
- `clojure.walk` cannot do the restore, because of the metadata trap above.

The smallest real fix is upstream in ClojureDart's `read-anon-arg`: keep the `%` in the token when no `#()` args env is bound, as Clojure's `ArgReader` does. I recommend filing it, then deleting both helpers once it lands.

## Tests first (red)

| Step | Where | Result before the fix |
|---|---|---|
| New and changed engine and REPL tests | JVM, before any `src/` change (`target/slice4/slice4-red.jvm.log`) | Every `subvec` case and all three production-rule tests failed with `Unknown query fn — pass it via the :fns option`. All four e2e tests failed on all four VMs with `…Unknown query fn… (:yin.repl.query/query-failed)`. |
| The same tests after the engine change | JVM, Node | Green. |
| Full lane | Dart (`target/slice4/cljd-red-reader.log`) | 2284 passed, 6 failed, all from the `%` defect: `the-reader-keeps-datalog-symbols-on-every-host`, the four e2e tests, and the guard, whose rule query also uses `:in %`. |

Before I first went green, the e2e draft had two bugs of its own, both fixed:
- **Reused state.** A `state` used for several lines reads a stale FFI response, because the session streams are live. The query lines are now threaded through one `evaluate`.
- **Name parsing.** The shell prints the symbols in a vector answer quoted (`['+ 'y]`), so names are parsed with a portable regex instead of `edn`.

## Acceptance (§4)

### 1. Builtin

On CLJ, CLJS and CLJD, the following tests are green in all three lanes:
- `subvec-builtin-answers-both-arities`: both arities, plus the empty and full ranges.
- `subvec-builtin-result-is-a-plain-vector-value`:
  - the result is `vector?`;
  - it is `content=` to `[[3 0] 2]`;
  - `cbor/encoded-compare` against the plain vector is zero (same canonical bytes);
  - it decodes as a vector;
  - it unifies with a stored plain vector.
- `subvec-builtin-refuses-bad-operands`: 10 refusals, each checked for the exact `ex-message` and for `(:fn (ex-data e)) = 'subvec`. The helper returns the error object, and the call site applies `ex-message` (the fold trap).

### 2. Equivalence

These three tests pass with no `:fns`, over `$ast`:
- `occurrence-aware-rules-resolve-mixed-free-and-bound-rows`;
- `occurrence-rules-are-root-scoped-across-trees`;
- `occurrence-walk-covers-the-whole-grammar`.

The unscoped contrast assertion still returns `#{}`, so the cross-tree misclassification is still shown.

### 3. Callers

The `rule-r`, `completion` and `linker` suites are green in all three lanes. So is `code-facts-from-tree-and-segment-are-equal`, the §7.7.1 tree-to-segment conformance test. M4–M6 below show these suites catch breakage of the rule.

### 4. REPL end to end, all four VMs, real input lines

Green on JVM, Node and Dart.

| Requirement | Test | Result |
|---|---|---|
| `((fn [x] (+ x y)) 1)` returns exactly `+` and `y`; `((fn [x] x) x)` returns `x` | `occurrence-rules-as-data-answer-free-names-through-q` | `#{+ y}` and `#{x}`, with `vm/occurrence-rules` passed as `(quote …)` data |
| `(fn [x] x)` and `(fn [y] x)` in one session: each root classified by its own binder | `occurrence-rules-classify-each-root-by-its-own-binder` | `#{}` and `#{x}` |
| The round-join form selects the same root as the address form | `the-round-join-selects-the-same-root-as-the-address` | Round 3's root prints as the address root. The round-join answer is text-identical to the address answer. Round 2 answers `#{}`. |
| The user's own `(def …)` line gives the same answer | `a-user-defined-rule-set-gives-the-same-answer` | The rule set is typed as literal source text and gives `#{+ y}` and `#{x}`, text-identical to the quoted-data answers |
| `%`, `...` and `_` survive the reader on every host | `the-reader-keeps-datalog-symbols-on-every-host` | Green on JVM, Node and Dart |

### 5. Guard

`host-functions-cannot-enter-a-query`:
- a rule that names `no-such-fn` refuses with `(:yin.repl.query/query-failed)` and "Unknown query fn";
- `{:fns {}}` as the options map refuses with `(:yin.repl.query/invalid-input)`.

### 6. Lanes

See below.

## Mutations

- M1–M7 were applied by `target/slice4/slice4-mutate.py`, which restores in `finally`. JVM runs.
- M8 was applied by hand from a backup and restored with `cp`. Restoration was checked by grep. Dart run.

| # | Property | Mutation | Result |
|---|---|---|---|
| M1 | Builtin refuses bad bounds | The bounds check becomes `(when-not true` | 15 failures: `subvec-builtin-refuses-bad-operands` |
| M2 | Result is a plain vector and encodes as a CBOR vector | Return `(apply list (subvec …))` | 3 failures: `subvec-builtin-result-is-a-plain-vector-value` |
| M3 | Root scoping | `[$occ ?root …]` → `[$occ _ …]` | 9 failures and 1 error in:<br>• `occurrence-rules-are-root-scoped-across-trees`<br>• `…classify-each-root-by-its-own-binder`<br>• `the-round-join-…` |
| M4 | The `<` guard is load-bearing | Delete `[(< ?n ?m)]` | 8 failures and 15 errors across the query, e2e, linker and completion tests, including `code-facts-from-tree-and-segment-are-equal` |
| M5 | Tree-side params membership | Delete `[(identity ?params) [?name ...]]` | 32 failures in 12 tests: every new e2e test, `…root-scoped-across-trees`, `code-facts-…`, `shadowed-free-name-is-refused` |
| M6 | Segment-side membership | Delete the `identity` clause in `segment-scope-rules` | 12 failures, including `segment-free-names-cases` and `code-facts-…` |
| M7 | Guard: `{:fns …}` options are refused | `view-of` treats a map without `:view` as `:current` | 4 failures: `host-functions-cannot-enter-a-query` |
| M8 (Dart) | Reader restore clears the stray `(apply list …)` metadata | Drop the `(with-meta rebuilt (meta form))` reset | 2284 passed, 6 failed: the reader test (`Error: dao.jing.cbor refused: unsupported-value (Type)` on the nested-list line), the four e2e tests, and the guard |
| (Dart red run) | Reader keeps `%` | No fix | 6 failures, listed in the red section above |

About M7: my first attempt only widened the key-set check. It survived because it was an equivalent mutant (the map still fails the `:view` value check), so I replaced it with the mutation above.

## Lanes (final tree, run one at a time)

**kondo.** `clj -M:kondo --lint` on the 10 changed source and test files: 0 errors, 4 warnings.
- All 4 warnings are in `yin/vm.cljc:1280–1458`, and linting the committed `HEAD` version of `vm.cljc` gives the same 4, so none are new.
- kondo does not see the `:cljd`-only reader helpers. The JVM probe run and the Dart lane cover them.

**Full JVM, `clj -M:test`.** 2423 tests, 185079 assertions, 0 failures, 0 errors (`target/slice4/final.jvm.log`).
- An earlier full run had 1 failure, and my change caused it. `cbor_conformance_test/no-source-file-that-names-the-resource-writes-files` flagged `query_test.cljc` after I required `dao.jing.cbor-fixtures`.
- Fixed by comparing with `cbor/encoded-compare` instead. M2 is still caught.

**Node, `bb test:cljs`.** 2328 tests, 51525 assertions, 0 failures, 0 errors (`target/slice4/final.cljs.log`).
- "Testing <ns>" was seen for `dao.space.query-test`, `yin.repl-test`, `yin.repl.ast-query-e2e-test`, `yin.repl.query-test`, `yin.vm.completion-test`, `yin.vm.linker-test` and `yin.vm.rule-r-test`.

**Dart, `bb test:cljd`.** `+2290: All tests passed!`, exit 0 (`target/slice4/final.cljd.log`).
- Each run takes longer than the 10-minute tool cap, so it was launched detached and polled to its verdict in the foreground.
- The Dart compact reporter prints only some test names. Seen by name in the final log:
  - the three `subvec-builtin-*` tests and the three `occurrence-*` tests in `dao.space.query-test`;
  - `yin.repl-test/the-reader-keeps-datalog-symbols-on-every-host`;
  - three of the four new e2e tests, plus three slice-3 e2e tests;
  - `yin.repl.query-test/host-functions-cannot-enter-a-query`;
  - 44 `yin.vm.rule-r-test` lines.
- The round-join e2e test, `completion-test` and `linker-test` are not printed by name in the final log. They are compiled into `test/cljd-out` and ran; the round-join test was listed as failing in the red run.

**Earlier Dart runs that were cut off, no verdict:**
- the first full Dart run stopped at `+1861 -6` when a turn ended;
- one red run stopped at "Failed to update packages".

I report neither as a result. The complete runs are the red run (2284/−6), the M8 run (2284/−6), two debug runs, and the final green run.
