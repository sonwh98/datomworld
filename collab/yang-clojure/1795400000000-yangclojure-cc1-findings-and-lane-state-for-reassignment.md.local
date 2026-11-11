Created-GMT: 2026-11-09 ~x:xx GMT
Coding-Agent: interactive (ZCode, yang.clojure orchestrator seat)

# CC1 FINDINGS AND LANE STATE — for the orchestrator taking over the dao.host/yin.repl reds (and a landing handoff option)

## Summary

The cc1 (call-site occurrence contract) unit is at branch `callsite-contract-clojure`, tip `847aa885`, ALL of its own verification green. The shiva full-quick JVM lane surfaced exactly two red shards; both are attributed OUTSIDE cc1's surface and authorship. This note is the findings package for whoever fixes them.

## The two reds (measured, reproducible)

### RED 1 — `dao.host.gate-test`: the H3 file-census pin has drifted behind master (PRE-EXISTING; owner: the H3 gate-census seat)

- Evidence: on a clean shiva clone at master `d8afa459` (baseline clone `~/ci/datomworld-cc1-baseline`): `clojure -M:test -e :slow -n dao.host.gate-test` → **14 tests / 178 assertions / 50 failures**. On the cc1 branch tree at `847aa885` (`~/ci/datomworld-cc1-g2`): the same namespace → **33 failures**. The branch does not worsen master's red — master is worse than the branch.
- Nature: the census pins a file count and categories — `gate_test.clj:1010/1011` expect `168` closure/gate files; `:1024/:1029/:1030/:1034` check list-1 categories/slices/classes. Master's tree has drifted since the pin was recorded (docs/collab commits and other seats' landings added/changed files the census counts).
- What the fix is NOT: a mechanical recount is not automatically the fix — the census exists to detect unreviewed file additions. The owner should diff the census against the actual tree, re-record the count AND verify every new file's category/slice/class entry is legitimate before updating list-1.
- Reproduce: any scratch clone at master, `clojure -M:test -e :slow -n dao.host.gate-test`. The failure set is stable (measured twice, two commits).

### RED 2 — `yin.repl.shell-session-test/local-shell-process-corpus`: deadline waiting for response 14 (LIKELY ENVIRONMENTAL; attribution in flight)

- Evidence: inside the 12-worker sharded run (`target/profile/quick-sharded-1794155569765-92c7378e/yin.repl.log`): `FAIL {:host :jvm, :id :vm-corpus-ast-walker, :status :fail, :message "deadline waiting for response 14"}` and the same for `:vm-corpus-semantic`. An isolated re-run of ONLY this test on the branch clone was dispatched (load-free) at the time of writing — verdict pending. If it passes in isolation, it is worker-starvation environmental and needs no fix (optionally a more generous deadline or a load guard in the test).
- The test is a bounded process-choreography (a JVM shell session answering corpus submissions with deadlines). A deadline failure under 12 concurrent JVMs is the classic signature, but the isolated run decides.

## Context the next orchestrator needs

- The cc1 branch is mid-landing: the owner RULED the occurrence-identity semantics (unitarity; filed draft at `collab/yang-clojure/1795310000000-owner-ruling-occurrence-identity-unitarity.md`, owner sign-off pending). Round 5 (`847aa885`) implements it: construction-threaded occurrence origins, per-occurrence correspondence in the expansion record, the address-set inference deleted. Callsite battery 18/211/0 (laptop, G0), dao.stream merge precondition PASS on shiva (589/0), full-quick JVM wide lane: every yin.vm/yang.clojure shard green.
- The branch is NOT landed. Remaining gates: the yin.repl isolated verdict → Node + Dart changed lanes (Dart in its OWN clone per the overlap rules) → the architect re-check of `847aa885` against the ruling → the codex gate → merge. The landing sequence is fully documented in the session log `orchestrator-logs/1793385006-zcode-glm-5.3-flash-yang-clojure-track.md` (the 2026-11-07/08/09 entries).
- RED 1 does NOT block the cc1 landing (pre-existing, outside the branch's surface; the landing rule is "the branch proves what it touches"). RED 2 is decided by the isolated run.
- After cc1 lands, the yang.clojure seat dispatches the exception track's R3 (brief final at `collab/yang-clojure/1795040000000-engineer-exception-r3.prompt.md`; the round-0 kit is on master at `0fa3ff7a`).

## Operational notes (measured this session)

- The full-quick JVM wide lane as ONE serial JVM ran 30-60 min on shiva; `bb test:quick-sharded --workers 12` is the right shape (but expect ~50 min wall when the long shards — yang.python ~1450s, yin.repl ~1760s — dominate the critical path; the historical 5-min figure was a cold clean-host run).
- Kill remote lane processes by exact PID only; verify cwd before killing (`readlink /proc/<pid>/cwd`).
- The shared laptop checkout `/Users/sto/workspace/datomworld` is currently on the php seat's branch `feat/prelude-tree-balance` with THEIR muse delegate running in it — do not commit there directly; use a temp worktree onto master.
- Three seats were running suite-scale work on the owner's laptop (load 12.9 on 8 cores) — flagged to the owner; the 2026-11-04 ruling puts lane sets on the remote hosts.

— yang.clojure orchestrator seat
