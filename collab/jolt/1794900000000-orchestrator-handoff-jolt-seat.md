Created-GMT: 2026-11-06 (afternoon)
Created-Local: 2026-11-06 (afternoon) +07
Coding-Agent: interactive (ZCode / glm-5.3, yin.repl jolt orchestrator seat — handing off)

# Seat handoff: the yin.repl-jolt track to the next LLM orchestrator

Read `docs/agents/roles/orchestrator.md` and `docs/agents/delegate-invocation-reference.md` BEFORE any dispatch — they are binding. Also read `docs/agents/build-n-test.md` (the test-boundary rulings) and `docs/agents/team.md` (the roster). This file assumes you have; it covers only what those docs don't.

## Session log

`orchestrator-logs/1793358019-zcode-glm-5.3-flash-jolt-seat-retake.md` is this seat's full history — every ruling, incident, and landing. Read it. The plan-of-record files (plan-v3/v3.1) were lost in a worktree deletion (untracked; the loss is recorded and unrecoverable); their substance survives in the filed rulings (below).

## Where the track stands

**Landed on origin/master (all stable, all pushed):**
- J6a–J6f: the Autobahn policy re-pin, the rfc6455 parser + upgrade handshake, SHA-1 in jolt-crypto, the TCP substrate + owner-thread pump (the A5 fail-connection amendment), the websocket host adapter + yin.repl.host shadow (kimi r8 APPROVE), and the Autobahn launcher routes + testee + ratified policy wording (kimi r8 APPROVE).
- **J6g: the workload W amended protocol PASSES — all 12 gates green** (origin/master 05a596c5; gate j6ggate29 on jupiter): 600-sample prospective cohort, 0 violations, rtt-p99 3.85 ms, scheduled-arrival p99 5.79 ms, A's 4 MiB delivered exactly once over the 120-second pacing, the REPL stepped every owner round (109,813 rounds), the submitted loop evaluated to 5.
- The incremental index materialization (the ruled fix (a) for the index-republication quadratic cost — landed via 1cddaa93/43cd8b92) and the workload W watchdog + guards.

**The design contract (owner-ruled, option 1):** a submitted program owns the thread until it finishes; peer service is suspended during evaluation. The workload W criterion was re-registered by the astra ruling (collab/jolt/1794250000000) — the independently driven B peer at true 10 Hz, the dual p99 bounds, the exact binomial bound q_U ≤ 1%. That re-registered criterion is the governing one.

## What remains

1. **J6h closure/docs/ledger** — the final slice of the J6 wave per plan v3 (the plan file is lost; the substance is in the filed rulings):
   - The claim-to-evidence table is BUILT (in the session log, 2026-11-06 morning entry) — J6b–J6g with landing commits and gate evidence.
   - The row-wise ledger union, the closure inspection, and the archive-first citation sweep (`grep -rn 'collab/' docs/design` empty for touched files) remain.
   - The J6 slice row in yin.repl-jolt-implementation.md §10.1 already reads "landed (J6a-J6g; J6h pending)".
2. **J6f Docker conformance runs** (301 cases × both roles × both platforms) — J6h's qualification evidence. BLOCKED on the owner's coordination of a Linux Docker slot. The launcher (`tools/jolt-autobahn/launch.sh` wrapping harness.py run) and the testee (`src/dev/jolt_autobahn_testee.clj`) are landed and reviewed; only the execution is missing.
3. **The collab filing catch-up** — ~10 artifacts flat-rooted in the shared checkout's collab/ (the mob session logs, coordination notes) + ~9 in the orch worktree need filing into collab/jolt/ per the write-time rule.

## Cross-track obligations (incoming)

- **The yang.clojure seat's call-site provenance generalization** (collab/jolt/1794660000000): jolt inherits automatically through the shadowing; ONE obligation — an applicable load/parity gate on the revised occurrence machinery, specified by the design round (expect it to resemble the J2 load check). The design findings are filed (collab/yang-clojure/1794670000000). No action until the adoption brief arrives.
- **The J2 load check for a6c25106e** (the yang.clojure jolt-conditional require fix): the yang.clojure seat asked for a jolt-side load re-run; it has not been done. A single jolt-lane load of yang.clojure on current master satisfies it.
- **a6c25106e's cljs/dart break**: the yang.clojure seat's top-level conditional broke the cljs compile (shadow's ns* rule). The FIX is the yang.clojure seat's (their note acknowledges); the jolt lane is UNAFFECTED (the :jolt arm is nil). Our dao.stream precondition blocks on the cljs lane until they fix it — coordinate, don't fix it yourself.

## Standing rules this seat learned the hard way

1. **Worktrees**: all master-touching operations in the orch worktree (`/Users/sto/workspace/datomworld-orch`, branch jolt-orch2, push `HEAD:master` — the branch name differs from the remote). Slice work in per-slice worktrees on slice branches. The shared checkout is READ-ONLY (its index must stay empty — the staging incident).
2. **Background discipline** (orchestrator.md 97269e58): dispatches and remote gates wait via tracked background tasks — never foreground sleeps, never bare nohup (invisible to the harness). Remote gates run via `bin/collab-tools/rverify.sh <host> <clone> <ref> <name> <files|-> <cmds>` in tmux; poll on demand or via a background waiter.
3. **Overlay packaging**: build the file list from `git diff origin/master --name-only` (the branch-vs-base diff), NEVER from `git status` (empty after a rebase — this wasted multiple gate attempts).
4. **Tests**: individual tests local; everything suite-scale remote (shiva/jupiter) — the 2026-11-04 owner rulings in build-n-test.md. The dao.stream precondition (`bb test:sub dao.stream`) gates every merge.
5. **Dispatch**: direct CLI per delegate-invocation-reference.md's canonical recipes (dispatch.sh is DELETED). Read each CLI's recipe before dispatching — the approval-mode flags differ (`--approval-mode auto-edit` for qwen, `--permission-mode acceptEdits` for deepseek, `--mode accept-edits` for agy with settings.json permissions.allow, `-s workspace-write` for codex). kimi has no caller ID; capture the session from the resume line.
6. **Terminal-state rule**: two failed rounds on the same unit (two different models) → stop dispatching, escalate to the owner with evidence.
7. **Write-time collab filing**: artifacts are written directly into `collab/jolt/` (the topic subfolder), never flat-rooted. Untracked flat files are the fragile state — the plan-v3 loss is the recorded cost.
8. **The routing board** (`docs/agents/routing-status.md`, untracked, per-checkout): check it before every dispatch. Codex/astra and qwen are the architect lanes; agy flash implements; argon is quota-walled until ~2026-11-09; kimi may be quota-walled (check).

## Open threads with other seats

- yang.clojure: our acknowledgment is filed (1794820000000); their cljs fix is pending on their side.
- yang.python: the F1 round is blocked on the yang.clojure cljs fix; their note is acknowledged.
- TS/PHP: the callsite-generalization surface inventory is filed; no action for jolt yet.

## The judge-of-record artifacts (all in collab/jolt/ on origin/master)

- 1793990000000 — the codex diagnostic (the index-republication root cause)
- 1794006000000 — the codex J6g ruling (the scheduling principle; fix (a) selected)
- 1794250000000 — the astra sample-floor ruling (option d: the re-registered protocol; the statistical bounds)
- The mob session's logs (1794400000000/1794095000000) and the workload W gate results (in the session log)

— the outgoing yin.repl jolt orchestrator seat
