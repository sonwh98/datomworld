Created-GMT: 2026-11-01 12:25:00 GMT
Created-Local: 2026-11-01 19:25:00 +07 (+0700)
Coding-Agent: codex (fresh session)

# Task: the three remaining yang.python recursion reds (StackOverflowError), after 6d6b7e71

Role: Yang Compiler and Universal AST Engineer
Implementers:
- Model: codex gpt-6-luna | Assigned: 2026-11-01 19:25 +07 | Status: active | Rationale: bounded remaining reds in the Python slow tier

WORKTREE: /Users/sto/workspace/datomworld-py-fix (branch `yangpython-link-reds`, tip 6d6b7e71).
Commit to a new branch `yangpython-recursion-reds` from that tip. You are the only writer.

## State, verified by the orchestrator on BOTH remote hosts (shiva and jupiter, same commit)

The previous round fixed the `yang.call` link refusals. Four namespaces are now green on both hosts:
`siteset-test` 5/71/0, `yin.vm.semantic.derive-test` 1/54/0, `dht-import-test` 1/127/0,
`linked-prelude-test` 8/82/0.

THREE remain red, and they are a DIFFERENT root — a JVM stack overflow, not a link refusal:

| Namespace | Result (shiva AND jupiter, identical) | Identity |
|---|---|---|
| `yang.python.antlr.safepoint-test` | 19 tests, 141 assertions, 0 failures, **1 error** | `recursion-error-test` |
| `yang.python.antlr.linked-safepoint-test` | 10 tests, 51 assertions, 0 failures, **1 error** | `linked-recursion-error-test` |
| `yang.python.antlr.e2e-test` | 42 tests, 714 assertions, **2 failures** | classify (likely filtered-range-comprehension-repl-test + recursion-under-real-hooks-test) |

The error text is identical on both hosts:

```
ERROR in (recursion-error-test) (APersistentVector.java:169)
Uncaught exception, not in assertion.
expected: nil
  actual: java.lang.StackOverflowError: null
 at clojure.lang.APersistentVector.hasheq
    clojure.lang.Murmur3.hashUnordered
    clojure.lang.APersistentMap.hasheq
    clojure.core$hash.invokeStatic
```

Note the stack: the overflow is in **Clojure's own `hasheq` over a deeply nested persistent structure** — the test harness hashing the VM state — not obviously inside the VM's own recursion guard.

**IMPORTANT**: these same tests were ALREADY red in the pre-fix nightly (`safepoint-test` recursion-error-test at
PersistentArrayMap.java:461; `linked-safepoint-test` linked-recursion-error-test at RT.java:1629). So this is
pre-existing, not a regression from 6d6b7e71 — but it may have been *nudged* by the extra `yang.call` install
depth that 6d6b7e71 added. Determine which: run the same three namespaces at the PARENT commit
(`git stash`/temporary checkout of 3ab1c925 or `git worktree add` on it) and compare. If the parent also
overflows, it is pre-existing; if the parent passes, 6d6b7e71 tipped it and the fix must account for the depth.

## What to determine, in order

1. **Is it stack depth or unbounded structure growth?** The test asserts a Python `RecursionError` is raised and
   caught. If the VM's guard fires correctly but the TEST then hashes/logs a structure deep enough to overflow
   the JVM stack, the defect is in how the test inspects or reports the value — fix the test to inspect lazily
   or shallowly (do not weaken the assertion that a RecursionError was raised).
2. **Does the fix belong at the assertion/reporting layer or in the VM's recursion guard?** A StackOverflowError
   escaping as an uncaught exception means no assertion ran; the desired outcome is the RecursionError path.
   Decide with evidence and say which layer, with file:line.
3. **If it is genuine unbounded recursion** (the Python recursion guard not firing at the right depth under the
   linked path), that is a product defect — fix it in `yang/python/**` if it lives there; if it lives in the VM
   or the linker, STOP and report with evidence rather than editing out of scope.

## Constraints

- Scope: `src/cljc/yang/python/**`, `test/yang/python/**`. NOT the VM, NOT the linker, NOT dao.jing.
- Do not weaken or delete assertions. If a test's expectation is wrong, say so explicitly with the reason.
- Do NOT run the full suite or the nightly on this machine. Run the three namespaces only, one per invocation:
  `clojure -M:test -i :slow -n <ns>`.
- Report the parent-commit comparison (pre-existing vs tipped), the layer chosen, and per-namespace counts.

Begin the final response with Completed-GMT / Completed-Local, then the six items above.
