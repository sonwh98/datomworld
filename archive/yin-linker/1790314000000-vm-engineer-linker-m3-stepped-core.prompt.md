Created-GMT: 2026-09-25 06:30:00 GMT
Created-Local: 2026-09-25 13:30:00 +0700
Coding-Agent: claude (CLI; model to be confirmed by the owner)
Session-ID: pending (generate with uuidgen at dispatch)

# Task: yin.vm.linker Milestone M3 — Stepped Core and Link Runtime

Role: VM Runtime Engineer (claude CLI)

Repository: the worktree /Users/sto/workspace/datomworld-ucf-phase2
(branch ucf-phase2; M1 and M2 are committed; M2's gate verdict will be
in collab/*-architect-linker-m2-gate.gpt-6-sol.findings.md — read it
first and apply any prescribed corrections before starting M3 work).

Implement Milestone M3 exactly as specified in
docs/design/yin.vm.linker.md section 9 (the M3 paragraph, ~line 1880),
reading first: section 6 (Linking is a stream exchange, including the
6.4 local-fetch traffic test), section 4 (the fetch pipeline and format
records as landed in M2), section 2 (invariants), section 10 (file box:
NEW test/yin/vm/linker_step_test.cljc; must-not-change list), section
11 (completion criteria 1-5, the B6 criteria through both fetch and
step).

M3 contract (from section 9):
- Implement `link-state`, `request-link`, `step`, `abandon` over
  `dao.jing.remote.step` (or, for a purely local composition, over
  `dao.stream.rpc` on a ring-buffer pair served by
  `dao.jing.remote/serve-content!`'s handlers).
- Reimplement `fetch` as the blocking driver over a link runtime.
- Export `verify` and `discharge`.
- Acceptance matrix: docs/design/yin.vm.universal-continuation-format.md
  section 7.11 (commit 49790e19) now specifies the blocker-closure test
  contract. M3 lands the code-identity row: the addressed instruction
  stream runs under its stamped contract or is refused before load.
  Implement the M3-tagged rows of the matrix as tests, and retain the
  full streamed refusal and traffic tests the spec already requires.
- Tests, per section 9: the full refusal matrix through `step` over ring
  buffers on all three hosts; the JVM WebSocket path from B6 completion
  criterion 1; a `:pending` sequence where the content server answers
  one part per step; a request carrying a function or a handle is
  `:invalid-request`; the section 6.4 local-fetch traffic test with a
  get-counting server handle and a handle-free linker state; the DHT
  handle behind `serve-content!` answering a link over ring buffers
  with the server driven by the test.

Constraints:
- Pure ASCII, <= 80 columns on every line you add or edit.
- Do NOT commit or stage; do NOT run git checkout/reset/stash.
- JVM tooling under mise (mise exec -- <cmd>).
- The stepped core must preserve the M2 pipeline semantics exactly:
  same admission checks, same refusal vocabulary, no branch on :format
  outside the format records.
- Verify: full JVM suite green (report exact counts; the baseline at the M2 commit is
  2,059 tests / 180,916 assertions / 0 failures, recheck before starting), cljstyle and kondo
  clean on touched files, hygiene on added lines. The orchestrator runs
  the tri-host lanes at the gate; run the JVM lane yourself.
- If the spec's M3 text conflicts with the landed M2 shapes or anything
  is ambiguous, STOP and report BLOCKED with the exact conflict.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
