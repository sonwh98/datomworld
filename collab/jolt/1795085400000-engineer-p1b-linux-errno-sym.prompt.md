Created-GMT: 2026-11-19 10:42:35 GMT
Session-ID: 01a1f1f9-2d2b-71a2-b88a-1675b81dd975 (RESUME)
# Task: P1b-linux r3 — platform errno accessor in the errno fixture/gate

Role: VM Runtime Engineer (Linux leg continuation)

Linux leg (shiva, 434dc2f9): one public failure remains. From the log:

*** ERROR IN ... scheme-adapter-runtime.ss@555.71-555.122 -- jolt.ffi: no
entry for "__error" — not in any loaded native and not in the process's
global symbols (gambitffi-abi, Makefile:2157, exit 70)

`__error` is the DARWIN errno accessor symbol; glibc uses `__errno_location`.
The errno-capture tier itself works on Linux (all capture rows pass); the
failing row is the by-name process-global resolution check that hardcodes the
darwin accessor name.

Fix (fixture/gate portability, NOT a tier change): the errno fixture/gate
must resolve the platform errno accessor by platform — `__error` on darwin,
`__errno_location` on linux (returns int*; deref differs: darwin's returns
the int directly... VERIFY the deref semantics per platform from the man
pages/source and adapt the fixture read accordingly) — same selector pattern
as the link flags (Makefile platform selector). Also check any other fixture
or gate row that hardcodes darwin-only symbol names (grep the ffi dir).

macOS regression: make gambitffi must stay PASS byte-identical darwin paths.
Commit on gambit-ffi-p1b; never push; note in p1b-stage1 findings what the
orchestrator reruns.

Return: Completed-* header + what changed + macOS regression line + the exact
Linux expectation for this row (which symbol, what value).
