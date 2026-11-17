Created-GMT: 2026-11-15 18:26:26 GMT
Created-Local: 2026-11-16 01:26:26 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: 01a1e067-564b-7383-a67b-660b70c3efbb (promoted after capture from the stdout log's thread.started event, 2026-11-16)
# Task: yang-compilers-dedup-impl-r2
Role: Engineer

Completed-GMT: 2026-11-15 18:26:26 GMT
Completed-Local: 2026-11-16 01:26:26 +07

All five extractions are complete. The changes are uncommitted; fixtures and expectations were preserved.

1. **Siteset store:** Moved the identical 27-line `load-tree` and 33-line `overlay` implementations from each target to [siteset_store.cljc](/tmp/yang-audit-wt/src/cljc/yang/core/siteset_store.cljc:6). Each target now has a three-line traversal wrapper and a one-line overlay alias. The traversal accepts the target’s reader and absence predicate; the target sentinel namespaces remain unchanged. The overlay still creates its buffer and identity sentinel per invocation.

2. **Balanced prelude emitter:** Moved PHP’s 14-line and TypeScript’s 13-line emitters to the nine-line helper in [core/prelude.cljc](/tmp/yang-audit-wt/src/cljc/yang/core/prelude.cljc:180). Each target retains a three-line wrapper. The helper accepts definition pairs and retains the midpoint split. Python’s prelude remains unchanged.

3. **Publication envelope:** Moved `signed-entry`, `entry-datoms`, and `signed-entries` to [siteset_publication.cljc](/tmp/yang-audit-wt/src/cljc/yang/core/siteset_publication.cljc:6). PHP’s original functions totaled 22 lines and Python’s 30; the shared functions total 22 lines, with public forwarding wrappers retaining the target documentation and APIs. The shared operations accept the same arguments as before. Discovery remains target-specific.

4. **Safepoint emitter:** Moved the repeated three-line definition conversion to the three-line helper in [core/prelude.cljc](/tmp/yang-audit-wt/src/cljc/yang/core/prelude.cljc:191). Each target retains its three-line wrapper; the helper accepts definition pairs and uses `u/seq-nodes` unchanged.

5. **Literal helpers:** Moved the two five-line `code-point->str` implementations and two three-line vector builders to [core/lower.cljc](/tmp/yang-audit-wt/src/cljc/yang/core/lower.cljc:102). Each target has a one-line private string-conversion alias and a three-line vector wrapper. The vector helper accepts the target’s `py/vconj` or `js/vconj` symbol; validation and diagnostics remain in the callers.

**Test evidence.** Each row represents the exact command `clojure -M:test -n <namespace>` with the namespace shown. All completed with zero failures and errors.

| Namespace | Tests | Assertions |
|---|---:|---:|
| `yang.php.antlr.siteset-test` | 7 | 30 |
| `yang.php.antlr.prelude-test` | 33 | 373 |
| `yang.php.antlr.safepoint-test` | 5 | 20 |
| `yang.php.antlr.lower-portable-test` | 8 | 1254 |
| `yang.python.antlr.siteset-test` | 17 | 171 |
| `yang.python.antlr.prelude-parity-test` | 35 | 221 |
| `yang.python.antlr.linked-prelude-test` | 14 | 125 |
| `yang.python.antlr.safepoint-test` | 30 | 598 |
| `yang.python.antlr.linked-safepoint-test` | 16 | 113 |
| `yang.python.antlr.lower-test` | 29 | 104 |
| `yang.python.antlr.lower-portable-test` | 5 | 19 |
| `yang.typescript.antlr.prelude-test` | 12 | 460 |
| `yang.typescript.antlr.prelude-module-test` | 3 | 8 |
| `yang.typescript.antlr.class-prelude-test` | 11 | 308 |
| `yang.typescript.antlr.prelude-parity-test` | 9 | 1372 |
| `yang.typescript.antlr.linked-prelude-test` | 10 | 62 |
| `yang.typescript.antlr.safepoint-test` | 9 | 70 |
| `yang.typescript.antlr.lower-test` | 18 | 731 |
| `yang.typescript.antlr.lower-portable-test` | 10 | 46 |

An initial PHP safepoint invocation failed because generated lexer classes were absent. `bb` was unavailable, so I ran `clojure -M:antlr-gen --help`, which generated and compiled the parsers, then reran the focused tests successfully. Two initial test invocations were interrupted and are not counted above. `git diff --check` passed.

The full change-set stat below was obtained with a temporary Git index so it includes the two untracked shared namespaces:

```text
 src/cljc/yang/core/lower.cljc                 | 12 ++++
 src/cljc/yang/core/prelude.cljc               | 16 ++++++
 src/cljc/yang/core/siteset_publication.cljc   | 31 ++++++++++
 src/cljc/yang/core/siteset_store.cljc         | 67 ++++++++++++++++++++++
 src/cljc/yang/php/antlr/prelude.cljc          | 13 +----
 src/cljc/yang/php/antlr/safepoint.cljc        |  3 +-
 src/cljc/yang/php/antlr/siteset.cljc          | 82 +++------------------------
 src/cljc/yang/python/antlr/lower.cljc         |  8 +--
 src/cljc/yang/python/antlr/safepoint.cljc     |  2 +-
 src/cljc/yang/python/antlr/siteset.cljc       | 78 +++----------------------
 src/cljc/yang/typescript/antlr/lower.cljc     |  8 +--
 src/cljc/yang/typescript/antlr/prelude.cljc   | 12 +---
 src/cljc/yang/typescript/antlr/safepoint.cljc |  2 +-
 13 files changed, 152 insertions(+), 182 deletions(-)
```

No additional duplication was factored. The target-specific discovery policies, admission and derivation paths, and lowerer refusal paths remain in place.

VERDICT: DONE — all five extractions are uncommitted and all focused test namespaces passed.
