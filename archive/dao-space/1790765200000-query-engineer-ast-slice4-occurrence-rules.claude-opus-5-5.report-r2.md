# Report r2: slice 4, fix round 1 (the Dart reader workaround)

Role: Engineer. Model: claude-opus-5-5. Work is on master and not committed.

Source: gpt-6-sol's withheld sign-off, `collab/1790774000000-architect-ast-slice4-signoff.gpt-6-sol.findings.md`. Its findings 1–4 are all in the Dart reader workaround or its docs; the occurrence-rule and `subvec` parts were approved and are unchanged.

**All four lanes are green on the final tree:**

| Lane | Result |
|---|---|
| kondo | 0 errors, 4 warnings, all pre-existing |
| JVM | 2427 tests, 0 failures |
| Node | 2332 tests, 0 failures |
| Dart | `+2294: All tests passed!`, exit 0 |

**Needs a decision:** ClojureDart's reader refuses no duplicates at all, independent of `%`. I preserved that behaviour rather than change the Dart reader for every input. See "Open decision" below.

## Changes

**`src/cljc/yin/repl.cljc`.** Only the `:cljd` reader path changed. JVM and Node are still exactly `read-string` / `cljs.reader/read-string`.

- **Gating (finding 3).** `read-forms` calls `read-percent-forms` only when the input contains `%`; otherwise it reads the input directly. `read-percent-forms` also reads the original input unchanged when the scan finds no bare `%` token.
- **Scanner (finding 1).** `escape-percent-tokens` now walks form starts the way the reader does, instead of guessing from the character before `%`:
  - whitespace and commas;
  - strings, and regexes (`#"…"`);
  - `;` comments and `#!` lines;
  - character literals, as `\` plus the rest of the token;
  - the prefix macros `' @ ^ `` ` `` ~` (and `~@`);
  - opening and closing delimiters;
  - every `#` dispatch: `#(` (opens an anonymous-fn frame), `#{`, `#"`, `#_`, `#'`, `#=`, `#^`, `#?` / `#?@`, `#!`, `##` symbolic values, `#:` namespaced maps, and tags;
  - every other token, which is skipped whole, so a `%` inside a symbol such as `a%` is never touched.
- **One placeholder per distinct token (finding 2).** `#{% %}` now reads as `#{P0 P0}`, so each host reader's own duplicate handling applies.
- **Placeholder without dots.** The prefix is now `yin_repl_percent_`. Syntax quote treats a symbol containing `.` as a class name and leaves it unqualified, so the old dotted placeholder restored `` `% `` as a bare `%`. Now it qualifies the placeholder, and the restore maps `ns/P0` back to `ns/%`, as the JVM does.
- **Restore (finding 3).** `restore-percent-tokens` rebuilds only branches that contain a placeholder. Every other value is returned `identical?`.
  - A rebuilt vector, list, map or set keeps its type and order: maps and sets are rebuilt with `(into (empty form) …)`.
  - It takes exactly the original's metadata, `nil` included. This still covers the Dart `(apply list …)` `{:tag <Type>}` metadata trap.
- **Error text.** If a reader error message names a placeholder, `read-percent-forms` rethrows it naming the token instead: `Duplicate key: %`, not `Duplicate key: yin_repl_percent_0`. Any other reader error is rethrown unchanged.

**`test/yin/repl_test.cljc`.**

| Test | What it checks |
|---|---|
| `the-reader-reads-percent-forms-alike-on-every-host` | Cross-host parity: the same lines read to the same printed forms on JVM, Node and Dart. Cases: `[#_% %]`, `[#_#_% % %1]`, `[#_ % %&]`, `["%" #_\% %]`, `{% [%]}`. |
| `a-percent-reads-as-any-other-symbol-does` | On each host, a `%` line answers exactly what the same line with the plain symbol `zz9` answers. Covers maps with `%` values, sets, metadata, nested lists, `#{% %}`, `{% 1 % 2}`, and `[#foo %]` (tag refusal). This pins "the workaround changes nothing but the `%`". |
| `regex-and-anonymous-fn-forms-read-as-on-the-jvm` | `[#_#"%" %]` and `[#_#(+ %1 1) %]` read as `['%]` on Dart and the JVM. Node reads EDN, which has neither form, so it refuses them (a pre-existing Node/JVM difference). |
| `duplicate-percent-keys-are-refused-where-the-reader-refuses-them` | JVM and Node refuse `#{% %}` and `{% 1 % 2}` with their own wording (`Duplicate key: %` / `…contains duplicate key: %`). Dart accepts them, as it does for any symbol. |

**Finding 4.** `docs/design/yin.vm.code-as-tuples.implementation-plan.md` U7 now carries a *Historical* note: the `p-up`/`occ-anc` rule set was superseded by the fable ruling, and it points to the current rule.

**Scratch files** are under `target/slice4/`: logs, `r2_reader_check.clj`, `r2_reader_block.cljc`, `repl.cljc.orig` (round 1), `repl.cljc.r2`, and `poll.py`.

## Tests first: failing on Dart before the fix

**Red run 1** (`target/slice4/r2-red.cljd.log`): first draft of the tests against the round-1 reader. 2290 passed, 3 failed:
- `(quote [#_% %])` gave `Error: Unexpected closing square bracket.` Finding 1 is real: the old scanner missed the `%` after `#_`.
- `(quote #{% %})` gave `#{%}`. Finding 2: the duplicate collapsed after restore.
- `(quote [#_#(+ % 1) %])` gave `Error: arg literal must be %, %& or %integer`.

The Dart run of my first fix then exposed facts about ClojureDart's reader that are independent of `%`. They are listed below, and I rewrote the tests to pin the right invariant.

**Red run 2** (`target/slice4/r2-red2.cljd.log`): the final tests against the round-1 reader, swapped back in from `repl.cljc.orig` and then restored. 2292 passed, 2 failed:
- `the-reader-reads-percent-forms-alike-on-every-host`: `(quote [#_% %])` gave `Error: Unexpected closing square bracket.` (finding 1).
- `a-percent-reads-as-any-other-symbol-does`: `(quote {% 1 % 2})` gave `{'% 1}`, but the plain-symbol line gives `{'zz9 2}`. With distinct placeholders the reader kept both keys, and the old restore's `into {}` then collapsed them first-wins instead of the reader's last-wins (finding 2).

Tests that did not fail on Dart before the fix, stated plainly:
- **Regex and `%1` anonymous-fn cases.** The old scanner happened to handle them. They stay as guards.
- **Finding 3.** Rebuilding every map shows no difference on Dart, because ClojureDart's reader builds every map literal with `(apply hash-map …)`, so both old and new give hash order. No Dart test fails before this fix. It is fixed by construction:
  - the JVM harness (`r2_reader_check.clj`) shows an unrelated map comes back `identical?` (`UNRELATED-IDENTICAL true`), and the no-`%` path skips the scan (`NO-PCT-SKIPS-SCAN true`);
  - the differential test pins that map type and order equal the plain-symbol read on each host.

**JVM harness.** `target/slice4/r2_reader_check.clj` runs the same scanner and restore with `read-string` as the reader over 31 probes, including `#'% @% ~% `` `% `` `^:m %`, `#?`, `##Inf`, `#:a{}`, `#inst`, shebang, a 9-key map, and metadata. Each probe reads the same as `clojure.core/read-string` on the JVM, including refusals. The only differences:
- gensym numbering in `#()`;
- `'x` reading as a `Cons` on the JVM but a `PersistentList` after a rebuild. That only happens in a branch holding a placeholder, and the two print and compare equal.

## ClojureDart reader facts (measured or read from `lib/cljd-out/cljd/reader.dart`)

1. **No duplicate refusal.** Map literals are built with `(apply hash-map kvs)` (`reader.dart` ~1527). The reader contains no "Duplicate" string anywhere. `#{a a}` collapses and `{a 1 a 2}` keeps the last value on Dart, for any symbol.
2. **Map literals are hash maps.** So small maps print in hash order on Dart and in insertion order on the JVM, for any input. The round-1 `into {}` rebuild matched the JVM order only by accident, and only when the input had a `%`.
3. **Bare `%` and `%&` fail inside `#()`.** `read-anon-arg` matches the token *after* the `%` against `"%"`/`"%&"`, so only `%1`, `%2`, … work there. This is the same upstream bug as the bare-`%` one outside `#()`.

## Open decision

The brief asked for "refusal tests that match the JVM and Node refusal behaviour". On Dart the host reader never refuses duplicates, for any symbol.

What I did: preserved each host reader's own semantics, as the finding's fix text asks ("reader duplicate semantics are preserved"). So `%` now behaves exactly like any other symbol on every host, and the refusal test asserts refusal on JVM/Node and pins Dart's collapse.

Making Dart refuse duplicates like JVM/Node would be a change to the Dart reader for every input, not just `%`. It would need either a text-level duplicate check or an upstream ClojureDart fix. Architect or owner call. My recommendation is upstream fixes for all three ClojureDart facts above, after which this whole workaround can be deleted.

## Lanes (final tree, one at a time)

- **kondo** on the 10 changed files: 0 errors, 4 warnings.
  - All 4 warnings are the existing ones in `yin/vm.cljc:1280–1458`.
  - A 5th, a redundant `do` in my test, was fixed by using a `#?@` splice with `:cljd` first, before the final lane runs.
  - kondo does not analyse the `:cljd`-only helpers; the JVM harness and the Dart lane cover them.
- **JVM** `clj -M:test`: **2427 tests, 185098 assertions, 0 failures, 0 errors** (`target/slice4/r2-final.jvm.log`).
- **Node** `bb test:cljs`: **2332 tests, 51544 assertions, 0 failures, 0 errors** (`target/slice4/r2-final.cljs.log`). "Testing <ns>" seen for `dao.space.query-test`, `yin.repl-test`, `yin.repl.ast-query-e2e-test`, `yin.repl.query-test`, `yin.vm.completion-test`, `yin.vm.linker-test` and `yin.vm.rule-r-test`.
- **Dart** `bb test:cljd`: **`+2294: All tests passed!`, exit 0** (`target/slice4/r2-final.cljd.log`).
  - The run is longer than the tool cap, so it was launched detached and its log polled in the foreground until the verdict.
  - Seen by name in the log: `the-reader-keeps-datalog-symbols-on-every-host`, `the-reader-reads-percent-forms-alike-on-every-host`, `a-percent-reads-as-any-other-symbol-does`, `regex-and-anonymous-fn-forms-read-as-on-the-jvm`, `duplicate-percent-keys-are-refused-where-the-reader-refuses-them`.
- **Other Dart runs this round, each to a verdict:**
  - red 1: 2290 passed, 3 failed;
  - my first fix: 2290 passed, 3 failed, which exposed the ClojureDart facts above;
  - red 2: 2292 passed, 2 failed;
  - an intermediate green: 2294 passed.

Not mine, left untouched: `docs/orchestrator-log.md` and the `collab/slice8-*` logs.
