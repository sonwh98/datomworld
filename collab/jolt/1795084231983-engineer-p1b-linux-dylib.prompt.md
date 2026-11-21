Created-GMT: 2026-11-19 10:32:05 GMT
Session-ID: 01a1f1f9-2d2b-71a2-b88a-1675b81dd975 (RESUME)
# Task: P1b-linux r2 — platform-conditional fixture link flags

Role: VM Runtime Engineer (continuation of the Linux leg)

Progress on shiva after be0710cd+f5920f1f: digest verification now passes
(linux-x86_64 manifest recorded and committed); gambitkernel + gambiteval PASS.
The remaining blocker is the fixture dylib build:
make build/gambit-ffi/libjoltffi-fixture.dylib fails on Linux — the Makefile
passes clang's -dynamiclib, which gcc parses char-by-char as unknown -y -n -m
debugging options, then links an executable and dies on undefined main
(Makefile:2108 area; your own findings flagged "Darwin-specific fixture build
flags" for the Linux rerun).

Fix: platform-conditional shared-library link flags in the Makefile
(darwin: -dynamiclib as today; linux: -shared -fPIC; fail loudly on any other
platform rather than guessing). Keep the darwin path byte-identical. Check any
other fixture/gate link lines for the same issue. Then:
- macOS regression: make gambitffi must stay PASS (darwin path unchanged).
- Note in p1b-stage1 findings what the orchestrator reruns on Linux.
Commit on gambit-ffi-p1b; never push; nothing outside the worktree.

Return: Completed-* header + what changed + the macOS regression line.
