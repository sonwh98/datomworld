Created-GMT: 2026-09-20 08:22:00 GMT
Created-Local: 2026-09-20 15:22:00 +07 (Indochina Time)
Session-ID: 8fafe5c6-77cd-4436-8231-ff0f220ce947 (resumed — your review session)
# Task: adversarial review of dao.stream.waitset Phases W3+W4

W2 (engine integration) is committed (`45bfcc16`). The implementer
(glm-5.3, resumed session) has since built W3+W4: the cadence layer, three
host wake-sources, and the census-driven adoption. The orchestrator
verified the matrix: JVM 1557 tests / 168773 assertions / 0 failures 0
errors; CLJS 1474 / 38636 / 0; CLJD +1437 ALL PASSED including the new
Dart driver tests and the voxel tests (a sibling branch's fixture fix is
merged into this tree — its green is the merged state, not this delta).
One orchestrator edit: two test libspecs corrected to the house string
form (`["dart:core" :as core]`) after the bare form failed to compile;
and the implementer's r2 fixed its own test-side `poll-until` defect (it
exited instantly instead of looping) plus a reversed spacing bound —
`driver.cljd` itself was never changed.

Under review — the delta in this working tree
(`/Users/sto/workspace/worktree-w2`, uncommitted):
- `src/cljc/dao/stream/waitset/cadence.cljc` (new) — the pure layer
- `src/clj|cljs|cljd/dao/stream/waitset/driver.{clj,cljs,cljd}` (new) —
  the three wake sources
- `test/dao/stream/waitset/cadence_test.cljc`,
  `test/dao/stream/waitset/driver_test.cljc(+.cljd)` (new)
- the adoption changes: `src/cljc/yin/repl.cljc` (tick owners → cadence),
  `src/cljc/yin/repl/serve.cljc` (per-session probes),
  `src/cljc/dao/jing/remote.cljc` (daemon sleep! swap), and their test
  files
against the plan's Phase W3 and Phase W4 sections (prescriptive — the
adoption table is authoritative) and the consensus's Dispute A/B
resolutions (no external parks; pure cadence-step; the probe rules and
R4's three qualifications).

Priority checks:
1. The W3 plan list verbatim: the pure layer never calls `check`, reads
   no clock; the host files hold only wake sources (no interpreter
   state); `nudge!` coalesces and never re-enters a tick from inside one.
2. The adoption table row by row: the serve per-session assignment (one
   active waiter; probe retired on terminal; pending responses on host
   cadence), the shell tick owners (25 ms fixed tick deleted;
   `moved?` counts outstanding pending writes), serving unchanged as a
   step, the ws ack sweep and single-stream rows untouched.
3. The deletion rules: every adoption deletes the unconditional poll or
   fixed timer it replaces (grep `setInterval`/`Timer.periodic`/
   `Thread/sleep` in yin.repl — the implementer reports empty).
4. Substrate: `dao.stream` and transports byte-identical (only the four
   new waitset files added under `dao/stream/`).
5. The two flagged judgment calls: `serve/moved?` counting a
   probe-woken round as moved; shutdown drains stepping at the flat base
   interval.

Do not edit. Do not rerun suites. Challenge.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

Report findings as P0-P3 | file:line | evidence | concrete fix. State
explicitly whether W3+W4 is ready for architect sign-off.
