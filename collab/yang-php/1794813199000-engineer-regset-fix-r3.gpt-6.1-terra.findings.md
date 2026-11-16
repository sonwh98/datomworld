Created-GMT: 2026-11-16
Coding-Agent: codex (gpt-6.1-terra)
Branch: fix/yangphp-regset, starting at 6abcf47d

Corrected only the first duplicate-class assertion in `class_model_test.cljc`: run the same declarations once and assert `:php/fatal` is the duplicate-class message, `:php/status` is 255, `:php/uncaught` is nil, and `:php/diag` contains errno 64 and the message. Neighboring assertions and `uncaught-message` are unchanged. No runtime changes. The local `origin/master` ref is 8a44b87a and the ruling file was unavailable there; the correction implements the contract and scope explicitly supplied in the r3 dispatch, but cannot claim a byte-for-byte comparison to the ruling's mechanical replacement.

Focused results:
- `declare-and-inherit-test`: 1 test, 8 assertions, 0 failures, 0 errors (`target/regset-r3/class-declare.log`).
- Full class-model namespace: 8 tests, 590 assertions, 0 failures, 0 errors (`target/regset-r3/class-namespace.log`).
- `repeated-entry-does-not-reuse-class-hoist-state` and `function-collision-runs-shutdown-without-unwinding-guest-code`: 2 tests, 7 assertions, 0 failures, 0 errors (`target/regset-r3/eval-collision.log`).

The pre-existing `target/profile/sub-clj.log` was copied to `target/regset-r3/preexisting-sub-clj.log` before the closure gate. `bb test:sub:clj yang.php` passed: 29 namespaces, 181 tests, 5,035 assertions, 0 failures, 0 errors, 7,587 seconds (`target/regset-r3/closure-sub-clj.log`). The php-src quick selection reported 119 pass, 57 unsupported, and 95 recorded known gaps; its manifest assertions passed. As in r2, zz-probe2/zz-probe3 printed informational DIFF diagnostics for include/class-map cases; those probes assert true rather than comparing those observations, so this green gate does not certify those diagnostic cases. No parser generation rerun, no commits or pushes; edits remain in the worktree. JVM only; no Dart or Node verification.
