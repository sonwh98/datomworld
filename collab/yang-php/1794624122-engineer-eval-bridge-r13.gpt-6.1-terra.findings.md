Created-GMT: 2026-11-14 02:42 UTC
Coding-Agent: codex
Session-ID: pending
Role: Engineer

# Eval redeclaration: partial implementation, not ready to land

The eval entry now binds early functions and classes in source order in the
caller, without lowering-time rejection of duplicate top-level functions.
Function collisions enter the guest-fatal path, with errno 64 for early
function binding and errno 1 for reached runtime declarations. Class collision
also enters that path, with errno 64. Class hoists use per-invocation cells so
reusing an entry cannot mistake an existing class for its own successful hoist.
The existing non-eval compile-time duplicate check remains in place.

Red-first: the new duplicate-function emitter test failed with
`Cannot redeclare F()` during lowering, then passed after the change. The
emitter namespace's original tests and that test passed (6 tests, 19
assertions). Focused direct-entry tests passed on all four VMs for retained
function registration prefix and reusing an entry with a class. A focused
walker lifecycle test checked shutdown visibility, status 255, skipped
catch/finally, suppressed destructor output and compile-fatal errno. The
existing published-eval site-admission test passed. Kondo and cljstyle passed
on the final edited files.

This is **not** the requested 11-fixture matrix. The two direct-entry cases
do not run through service publication and linker admission. The remainder of
the matrix, diagnostic locations, the full existing eval suites and portable
lane have not been verified. The actual PHP eval expression remains explicitly
unsupported; the requested caller-task `php/eval-run` expression integration
is described as the next slice, so neither expression path can currently be
exercised end to end. Do not push or treat this as a green implementation.
No PHP oracle was available locally. A mistaken invocation of the test runner
attempted test discovery and failed on missing generated Python ANTLR classes;
focused tests were subsequently run via a test-path alias.
