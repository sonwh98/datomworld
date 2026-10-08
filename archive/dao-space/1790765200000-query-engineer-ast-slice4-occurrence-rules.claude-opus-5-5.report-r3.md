# Report r3: slice 4, fix round 2 (restoring qualified tokens and `%` inside metadata)

Role: Engineer. Model: claude-opus-5-5. Work is on master, not committed.

Source: gpt-6-sol's r2 sign-off was withheld (`collab/1790774000000-architect-ast-slice4-signoff-r2.gpt-6-sol.findings.md`). Duplicates follow the ruling: each host reader keeps its own behaviour, and there is no reader-wide refusal.

**All lanes green on the final tree:**
- kondo: 0 errors, 4 warnings (all 4 already in `vm.cljc`).
- JVM: 2430 tests / 0 failures.
- Node: 2335 tests / 0 failures.
- Dart: `+2297: All tests passed!`, exit 0.

## Changes

### `src/cljc/yin/repl.cljc`
The reader workaround still runs on Dart only.

- **`restore-percent-tokens`**
  - **Finding 1, qualified tokens.** A qualified token such as `%/foo` or `%x/y` is now restored whole, namespace and name. An unqualified token (`%`) still takes the namespace its placeholder gained from the reader (syntax quote, `#:ns{}`), which is how the JVM reader qualifies `%` itself.
  - **Finding 2, metadata.** Restore now visits metadata as well as values. Every form's metadata is restored the same recursive way, which covers `^%`, `^{:tag %}`, a `%` metadata key, `%` nested inside metadata, and `^%` on vectors, maps and sets.
  - **Unchanged from r2.** A branch without a placeholder, metadata included, is returned `identical?`. A rebuilt branch keeps its collection type and order (`into (empty form)`). It takes exactly its original metadata after restoring, `nil` included, so the Dart `(apply list …)` `{:tag <Type>}` trap stays covered.
- **`read-forms`** is now public (it was private) and has a docstring. This is a visibility change only. The cross-host tests need to compare the forms each host reads, metadata included, and the REPL's printed answers don't show metadata. A test can't reach a private var in another namespace on Dart (`#'private` fails there), so making it public is the minimal hook.

### `test/yin/repl_test.cljc`
Each positional test gathers all its failing cases into one assertion, because Dart's test runner prints only the first failing assertion per test.

- **`shape`**: forms as host-neutral data. A map becomes `[:map [k v] …]` and a set `[:set …]`, with entries sorted by printed text. A form carrying metadata becomes `[:meta <shape> <form>]`, at every depth.
- **`a-percent-reads-alike-in-every-symbol-position`**: cross-host parity, the same expected text on JVM, Node and Dart. Cases:

| Group | Inputs |
|---|---|
| values | `[% %1 %& (f %) {:k %}]` |
| qualified values | `[%/foo %x/y]` |
| metadata | `^% x`; `^{:tag %} x`; `^{% 1} [x]`; `^{:k [%/foo]} (f)`; `[^% [1] ^% {:a 1} ^% #{1}]` |
| map keys | `{% 1}`, `{%/foo [%]}` |
| set elements | `#{% [%]}`, `#{%/foo}` |
| namespaced maps | `#:a{% 1}` gives `a/%`; `#:a{%/foo 1, _/% 2}`; `#:a{:b %}` |

- **`a-percent-reads-as-a-symbol-reads-under-syntax-quote`**: compares each `%` line against the same line with the plain symbol `zz9`, on the same host. These expansions are host-specific, which is why it can't be a cross-host test. Lines: `['% #'% @% '%/foo]`, `` `% ``, `` `%/foo ``, `` `[% ~% ~@%] ``, `` `(f %/foo ^% x) ``.
- **`tagged-literals-and-reader-conditionals-refuse-alike`**: `[#foo %]`, `[#?(:clj %)]` and `[#?@(:clj [%])]` are refused on every host.
- **Kept from r2**: the REPL-level parity, differential, regex / anonymous-fn and duplicate tests.

## Every position where a symbol can appear

| Position | Test | Result |
|---|---|---|
| values | position test `:value`, `:qualified-value`, plus the r2 REPL tests | same on all 3 hosts |
| metadata | `:meta-tag`, `:meta-map-value`, `:meta-map-key`, `:meta-nested`, `:meta-on-coll` | same on all 3 hosts |
| map keys | `:map-key`, `:map-key-qualified`, plus the r2 map tests | same on all 3 hosts |
| set elements | `:set-element`, `:set-element-qualified` | same on all 3 hosts |
| tagged-literal forms | `tagged-literals-…` | refused on all 3 hosts |
| syntax quote and unquote; `'`, `#'`, `@` | syntax-quote differential test (same-host) | see note 1 |
| namespaced maps `#:ns{}` | `:namespaced-map-key`, `:namespaced-map-qualified-key`, `:namespaced-map-value` | same on all 3 hosts |
| reader conditionals | `tagged-literals-…` | not enabled on any host, all refuse |

**Note 1: syntax quote, `'`, `#'` and `@`.**
- Tagged literals: the only tags the readers know (`#inst`, `#uuid`) take strings, so a symbol inside a tagged form only reaches an unknown tag, which every host refuses.
- Syntax quote refuses on Dart for *every* input, with or without `%`: `No extension of protocol IResolver found for type Null` (no `*resolver*` is bound). Node's EDN reader has no syntax quote, and no `'`, `#'` or `@`. So the syntax-quote qualification rule is only exercised on the JVM:
  - by the harness `target/slice4/r3_reader_check.clj`, which reads 24 lines the same as `read-string`, e.g. `` `% `` gives `(quote ns/%)` and `` `%/foo `` gives `(quote %/foo)`;
  - by the JVM run of the differential test.
- On Dart, the same restore rule ("an unqualified token keeps the namespace its placeholder gained") is exercised by `#:a{% 1}` reading `a/%`, and a qualified token is exercised by `#:a{%/foo 1}` reading `%/foo`.
- `'`, `#'` and `@` read on Dart and are covered by the differential test. On Dart, those reader-built lists carry the `{:line … :tag <Type>}` metadata natively, with or without `%`. That's a pre-existing ClojureDart trap for any `'x` typed at the prompt; the REPL tests spell `(quote …)` out.

The ClojureDart facts are from a temporary probe test, since removed, in the Dart run `target/slice4/r3-green-probe.cljd.log`.

## Tests first: failing on Dart before the fix

**Red run 2** (`target/slice4/r3-red2.cljd.log`): the r2 restore with the final tests. 2295 passed, 2 failed. Every failing position is listed in one assertion:

| Position | Input | Dart result before the fix |
|---|---|---|
| `:qualified-value` | `[%/foo %x/y]` | `[[foo y]]` |
| `:meta-tag` | `^% x` | metadata `{:tag yin_repl_percent_0}` |
| `:meta-map-value` | `^{:tag %} x` | `{:tag yin_repl_percent_0}` |
| `:meta-map-key` | `^{% 1} [x]` | `{yin_repl_percent_0 1}` |
| `:meta-nested` | `^{:k [%/foo]} (f)` | `{:k [yin_repl_percent_0]}` |
| `:meta-on-coll` | `[^% [1] ^% {:a 1} ^% #{1}]` | three `{:tag yin_repl_percent_0}` |
| `:map-key-qualified` | `{%/foo [%]}` | key `foo` |
| `:set-element-qualified` | `#{%/foo}` | `foo` |
| `:namespaced-map-qualified-key` | `#:a{%/foo 1, _/% 2}` | key `a/foo` |
| syntax-quote differential | `['% #'% @% '%/foo]` | `(quote foo)`, not `(quote %/foo)` |

These were already green on Dart before this fix: plain values, `{% 1}`, `#{% [%]}`, `#:a{% 1}`, `#:a{:b %}`, the other syntax-quote lines (they are refused on Dart with or without `%`), and the refusal test.

**Red run 1** (`target/slice4/r3-red.cljd.log`): the first draft, where each case was its own assertion. 2295 passed, 2 failed. It showed only the first failure per test (`:qualified-value` gave `[[foo y]]`). That's why I restructured the tests to report every failing case at once.

## Lanes (final tree, run one at a time)

- **kondo** on the 10 changed files: 0 errors, 4 warnings, all in `yin/vm.cljc:1280–1458` and all 4 already there in the committed file.
- **JVM** `clj -M:test`: **2430 tests, 185103 assertions, 0 failures, 0 errors** (`target/slice4/r3-final.jvm.log`).
- **Node** `bb test:cljs`: **2335 tests, 51549 assertions, 0 failures, 0 errors** (`target/slice4/r3-final.cljs.log`). "Testing <ns>" was seen for all 7 touched namespaces: `dao.space.query-test`, `yin.repl-test`, `yin.repl.ast-query-e2e-test`, `yin.repl.query-test`, `yin.vm.completion-test`, `yin.vm.linker-test`, `yin.vm.rule-r-test`.
- **Dart** `bb test:cljd`: **`+2297: All tests passed!`, exit 0** (`target/slice4/r3-final.cljd.log`).
  - The run is longer than the tool cap, so it was launched detached and its log polled in the foreground until the verdict.
  - All 8 reader tests appear by name in the log: `a-percent-reads-alike-in-every-symbol-position`, `a-percent-reads-as-a-symbol-reads-under-syntax-quote`, `tagged-literals-and-reader-conditionals-refuse-alike`, `a-percent-reads-as-any-other-symbol-does`, `the-reader-reads-percent-forms-alike-on-every-host`, `the-reader-keeps-datalog-symbols-on-every-host`, `regex-and-anonymous-fn-forms-read-as-on-the-jvm`, `duplicate-percent-keys-are-refused-where-the-reader-refuses-them`.
- **Other Dart runs this round, each run to a verdict:**
  - red 1: 2295 passed / 2 failed;
  - red 2: 2295 passed / 2 failed;
  - the fix plus the temporary probe: 2298 passed.

Scratch files are in `target/slice4/`: `r3_make_check.py`, `r3_reader_check.clj`, `r3_fill.py` and the logs.

Not mine, left untouched: `docs/orchestrator-log.md` and the `collab/slice8-*` logs.

**Recommendation, unchanged.** Fix ClojureDart's reader upstream, then delete the workaround. Four issues to raise:
1. `%` outside `#()`, and bare `%`/`%&` inside it.
2. No duplicate refusal.
3. No default syntax-quote resolver.
4. `list` adds `{:line … :tag <Type>}` metadata.
