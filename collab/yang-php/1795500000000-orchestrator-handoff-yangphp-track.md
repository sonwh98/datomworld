Created-GMT: 2026-11-07 1x:xx:00 GMT
Created-Local: 2026-11-07 1x:xx:00 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: interactive (ZCode / GLM seat — the outgoing yang.php orchestrator)
Session-ID: not-applicable (interactive seat; the successor session is new)

# Task: yang.php track — the orchestrator seat handoff

Role: Lead Engineering Orchestrator (yang.php track)

Implementers:
- Model: muse-spark-1.3-contributor | Assigned: (standing) | Status: active | Rationale: the owner's implementation lane; laptop rounds deliver, shiva rounds wedge (measured twice)
- Status-Event: 2026-11-05 | Model: qwen3.8-max | Status: available | Rationale: the owner's relay; backup implementation capacity
- Status-Event: 2026-11-05 | Model: gpt-6.1-sol | Status: active | Rationale: the ARCHITECT lane (the owner's ruling: sol reviews every implementation round)
- Status-Event: (current) | Model: kimi CLI | Status: out | Rationale: credits; kimi via the qwen cli is allowed per the owner

Coordinate the yang.php track in /Users/sto/workspace/datomworld.

## Read first

- docs/design/yang.php.md — sections 3 (semantics), 4 (the substrate), 12 (safepoints), 14 (the oracle), 15 (the slice table: P0-P9 landed; P7-b deferred behind safepoint slice 3), 17.3 (oracle gates)
- docs/design/yang.antlr.md — §8.10 (the call convention), §8.10.7 (exception uniformity — the SEPARATE exception track owns it now, collab/exception-track/), §8.11
- docs/agents/roles/orchestrator.md — the workflow, the commit authority, the artifact protocol, the handoff rules
- docs/agents/build-n-test.md — THE BOUNDARY: individual tests local, suite-scale (G1/G2/full/slow/nightly) remote-only on shiva/jupiter; kill remote processes by exact PID never by name pattern; the focus rule (the failing test green IS the fix-branch merge bar); the scratch-clone rules; the 04:00 nightly cron is disabled
- docs/agents/routing-status.md — the delegate availability (muse/agy/qwen/codex per the latest entries; agy quota-walled ~Nov 9-10)
- docs/agents/delegate-invocation-reference.md — the recipes (muse exec --trust-workspace on scratch clones; the headless default-approval trap)
- orchestrator-logs/1793179048-yang-php-yang-typescript-glm-resume.md — the prior seat's log (HISTORICAL CLAIMS ONLY — re-derive everything from git)

## State at handoff (re-derive from git before acting)

- master @ 8d0a4ffb+: ALL section-15 slices landed (P0-P9) with the forward-fix arc complete (the P1s: the exhaustion root escape, exit semantics, buffer drain, budget validation — 8156ded8/1254d24e merges under the architect sign-offs; the retro review collab/orch-lane/1794580000001-...findings.md drove them).
- The B2 arc (the P5 lifecycle) IN FLIGHT: branch feat/p5b2-lifecycle; **B2-r4 (muse) was dispatched DETACHED and is mid-round** — check the b2 worktree /Users/sto/workspace/datomworld-b2 (git log; the findings file collab/orch-lane/1795450000000-...findings.md) for its commit; collect the work. The architect's r2/r3 confirm findings (collab/orch-lane/1795250000000 + 1795400000000) name what remains.
- The P5 oracle recording: branch feat/p5-oracle-recording (the runner extension landed to master at 1254d24e; the 8 fixtures' folds are in the recorded state — the remaining :oracle :pending items need the shiva docker pass: clojure -M:dev -m yang.php.oracle --p5 in a scratch clone with test/ on the classpath).
- Round C pending: the three P9 fixture corrections + the ledger reconciliation per the retro review's P9 section (collab/orch-lane/1794580000001-...findings.md).
- Eval: the compiler-bridge design is DELIVERED (collab/orch-lane/1794680000000-architect-eval-bridge-design...log + the promoted findings) — the implementation round is undispatched.
- P7-b (ticks): implementation-ready per the sp3 design (collab/orch-lane/1794700000000-architect-safepoint-slice3-design...findings.md — the shared :line mechanism EXISTS; PHP integration + the :after timing extension remain).
- The exception track (§8.10.7): a SEPARATE track (collab/exception-track/, the steward is the yang.python seat) — the PHP R1+R2+R3 round is OURS as one coherent round, sequenced after the JS round (the other seat's). The round-0 kit landed (0fa3ff7a); check the steward's folder for the dispatch state. THE CAUTION OF RECORD: the bridge must not land before reception repair (it breaks local typed catches).

## Required workflow

- Follow docs/agents/roles/orchestrator.md#workflow in order. Create a NEW session log at orchestrator-logs/<unix-ts>-<agent>-<model>-yangphp-handoff.md; never append to the prior log.
- Gates: suite-scale (G1/G2/full/slow/nightly) on shiva/jupiter ONLY — never the laptop. Individual namespaces may run locally. The focus rule: for a fix branch, the previously-failing test green IS the merge bar. Kill remote processes by exact PID (pgrep -af to list; never pkill -f). Dispatches via the harness's run_in_background mode (NEVER nohup & — invisible to the owner; this violated twice in the prior session).
- Commit authority: an Architect sign-off (sol gpt-6.1-sol) on reconciled findings, an explicit user instruction, or the trivial-edit exemption. The prior seat landed five merges without step-7 review — the retro review followed; do not repeat that.

## The prior seat's violations (named so the successor does not repeat them)

1. Five feature merges landed without step-7 review (reported; the retro review followed).
2. Dispatches via nohup & after the visibility rule landed (the owner called it out twice).
3. Suite-scale runs on the laptop (the owner called it out; the load hit ~10).
4. Kill-by-name-pattern over ssh (self-killed sessions; the rule landed after).
5. Quiet-vs-stalled judgments made by inference instead of the procedure.
6. Round work committed on a side branch inside the main checkout (the main checkout carries master only; round work in worktrees).

## Standing coordination

- collab/yang-call/ — the relay thread with the yang.clojure/python seats (the callsite-provenance adoption: the design is delivered, the reserved key :yang/call-site v1, the per-frontend equality gate; the adoption brief arrives through this thread).
- collab/exception-track/ — the exception track (the steward's folder; the acceptance ledger).
- The cross-host occurrence-key equality gate is part of EACH frontend's own acceptance (per the clojure seat's ruling 1794980000000) — the php adoption/e2e gates include the JVM+Node coordinate-equality check.
