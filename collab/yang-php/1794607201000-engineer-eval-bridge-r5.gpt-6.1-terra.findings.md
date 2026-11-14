# Eval bridge r5: coordinator and PHP-mode source goal

- `src/cljc/yang/php/antlr/cst.cljc:36` constructs an explicitly supplied eval worker; its lexer pushes PHP mode over the default mode, retaining mode exit/reentry and original offsets.
- `src/cljc/yang/php/antlr/eval_coordinator.cljc:15` admits the original carrier before parsing, validates against the decoded source, and runs scope analysis on successful packets. The returned identity is a data key containing the original request, exact carrier, source goal, grammar and UTF-8 profile revision. It is available for the later artifact lookup; this slice does not implement a cache or publish an executable entry. The supplied worker is owned by the composition; no runtime caller state is required.
- `test/yang/php/antlr/eval_coordinator_test.clj:14` exercises the coordinator with nested eval scope recognition, mode reentry, source-aware conformance, byte EOF and line offsets, byte/revision identity differences and correlated malformed-byte refusal.

## Red-first chronology

1. Added the coordinator tests first and ran `clojure -M:test -n yang.php.antlr.eval-coordinator-test`: failed to load the absent coordinator namespace.
2. Added the eval worker and coordinator; the initial 2 tests / 9 assertions passed. Extended byte-location and revision fixtures; 3 tests / 22 assertions passed.
3. Initial cljstyle check found formatting in the two new files. Applied cljstyle fix and reran both lint checks successfully.

## Sequential gates

| Gate | Result |
|---|---|
| coordinator | 3 tests, 22 assertions, pass |
| scope-test | 12 tests, 29 assertions, pass |
| eval-admission-test | 3 tests, 43 assertions, pass |
| cst-test | 10 tests, 196 assertions, pass |
| lower-portable-test, 600-second timeout | 8 tests, 1254 assertions, pass |
| clj-kondo on changed code and fixture | 0 errors, 0 warnings |
| cljstyle on changed code and fixture | pass after formatting |

No Docker or push. Eval-unit emission, linker/entry installation, collision handling and eval execution remain for the next unit. The Node lexer branch is implemented but was not exercised by these JVM gates; worker equivalence remains to be verified. Syntax-error packets and grammar-profile refusals are returned for the next compiler stage to classify; this checkpoint does not claim executable eval support.
