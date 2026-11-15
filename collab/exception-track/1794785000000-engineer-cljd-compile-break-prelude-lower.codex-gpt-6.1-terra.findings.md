# ClojureDart PHP prelude compile failure

Base: `11699dfa88334bbedf8f58ed179c2597720daaa1`. Fix source commit: `795f8d8528599342056f926f46faf33b4778b571`. Isolated checkout on Jupiter; mise trusted after checkout and environment evaluated. `lower.cljc` has no changes in `23de41a9..11699dfa`, but its earlier PHP ticks change entered the ancestry through the P7-b merge.

## Bisect

`git bisect run` used `timeout 300 clojure -M:clojuredart:cljd compile yang.php.antlr.lower`, with mise environment evaluated at each checkout (timeout would be skipped, none occurred). Known endpoints: `a3f7ab8d` good, `11699dfa` bad. Results:

| Commit | rc | Result |
|---|---:|---|
| 8c4bf09c | 1 | bad |
| a64796dd | 1 | bad |
| 5e2efd08 | 1 | bad |
| 411c2b08 | 0 | good |
| e5895380 | 0 | good |
| ba9080d1 | 0 | good |

First bad: `5e2efd08748d02e1b3633de86a6728514a811392` (parent `ba9080d1`), `feat(php): tick sequence green — request counter and completion hooks`.

## Exception and mechanism

At base the entry compile returned rc 1 and identified the entry `ns` as its faulty form. Compiling `yang.php.antlr.lower` directly returned rc 1 and identified `lower-declare`. A diagnostic invocation replaced `cljd.build/print-exception` in memory with `.printStackTrace`; no compiler file was modified. Its root exception, verbatim:

```text
Caused by: java.lang.ClassCastException: class clojure.lang.PersistentList cannot be cast to class clojure.lang.Named (clojure.lang.PersistentList and clojure.lang.Named are in unnamed module of loader 'app')
```

The added ticks parsing reader conditionals contained only `:clj` and `:cljs`. On `:cljd` both the try body and catch class disappear, leaving `(try (catch _ (unsupported! n :ticks)))`; the emitter fails on that malformed form. This is a source portability omission, not an established compiler defect. The fix adds `:cljd (int/parse tick-directive)` and `:cljd Object` to the existing reader conditionals, following existing Dart idioms. JVM and ClojureScript forms remain unchanged.

## Verification

- Jupiter base: `clojure -M:clojuredart:cljd compile yang.php.antlr.prelude-test` rc 1; direct `yang.php.antlr.lower` rc 1.
- Jupiter fix: `clojure -M:clojuredart:cljd compile yang.php.antlr.lower` rc 0, `Compilation succeeded`.
- Jupiter base and fix: `clojure -M:test -n yang.php.antlr.prelude-test` each rc 0, `Ran 33 tests containing 373 assertions. 0 failures, 0 errors.`
- Jupiter fix: `bb test:sub:clj dao.stream` rc 0, 41 namespaces, 589 tests, 0 failures.
- Shiva isolated checkout with the fix: `bb src/dev/cljd_agg.clj --only yang.php.antlr.prelude-test` rc 0; entry compile succeeded and Dart reported `+33: All tests passed!`.
