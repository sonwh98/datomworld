# SEAT HANDOFF — the yang.php orchestrator seat -> agy (gemini-4-neon)

Created-GMT: 2026-11-20 08:14:27 GMT
Created-Local: 2026-11-20 15:14:27 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: interactive (ZCode / glm-5.3-flash, outgoing yang.php orchestrator)
Session-ID: not-applicable (interactive seat)

# Task: the yang.php track orchestration — handoff to agy

Role: Lead Engineering Orchestrator

Implementers:
- Model: glm-5.3-flash (ZCode seat) | Assigned: 2026-11-13 | Status: superseded (credit exhaustion; this document is the handoff) | Rationale: the outgoing seat's per-session log is orchestrator-logs/1794252276-glm-glm-5.3-flash-yangphp-orchestrator-seat.md — historical claims only; re-derive tree state yourself.

Coordinate the yang.php track in /Users/sto/workspace/datomworld (the main checkout; STAYS ON master per the discipline).

Read first (in this order):
- docs/agents/roles/orchestrator.md — the role; especially Scope of judgment (escalate design to Architect, authorization/scope to the user), Commit authority (Architect sign-off / user / trivial-edit), Shared checkout and branch discipline (per-operation `git branch --show-current`, ff-only syncs, explicit `HEAD:master`), Work log, Failure and handoff (TWO-STRIKES: after two failed rounds on a unit, stop and escalate to the user — the dart leg is AT this boundary right now).
- docs/agents/build-n-test.md — gate ladder, gate-log hygiene (per-run directories; bank sub-*.log before reruns), the NEW tier-naming rule (gate claims must name the tier: fast test:sub EXCLUDES ^:slow), remote hosts (shiva 24-core, jupiter 48-core; check both loads before dispatching; kill by exact PID never pkill), scratch-clone rules (fetch from GitHub, not chained clones — a stale chained clone cost this seat a false "master green" once), dao.stream precondition before every merge, the dart lane rules (fresh caches: rm -rf .clojuredart test/cljd-out lib/cljd-out build/cljd-agg*; mise activation order: trust -> install -> eval env; one dart lane per host).
- docs/agents/delegate-invocation-reference.md — CLI recipes. codex: `-s workspace-write` for implementation, `-s read-only` for consults; capture `thread.started.thread_id` IMMEDIATELY and record it in the prompt artifact; resume = `codex exec resume <id> -m <model> --json -` from the worktree cwd (NEVER -s/-C on resume); stdout to collab/<topic>/ (never /tmp).
- docs/agents/routing-status.md — CLEARED AND RESET 2026-11-19 by the owner: codex + agy are the primary coding agents; qwen unavailable until 2026-12-05.
- docs/agents/team.md — Hydrogen (routine/high-volume) vs Neon (complex multi-file coding/planner) vs Argon/Astra (adjudications/sign-offs) routing on the agy lane; codex models draw one shared pool per host pair (laptop+shiva POOL 1, jupiter POOL 2).
- THIS SEAT'S ARTIFACT CONVENTION: collab/yang-php/<ts>-<role>-<task>.<model>.{prompt,findings}.md; quoted heredoc delimiters when writing artifact bodies containing backticks.

# Track state (all claims re-derivable from git log / origin)

## LANDED on origin/master (verify with git log, do not trust this list):
- The include SCC fix (d19c57e9 lineage): the ticks hook require goes LAST in the SCC wide application (astra ruling 1794772328000); mutual-include fixtures green.
- The shutdown/permutation fix (fresh-value? array temps acquire a reference; 6abcf47d lineage): shutdown destructor order + the 518-program permutation corpus fixed, one root cause.
- The class-fatal correction (4e2067b2 lineage): duplicate-class = UNCATCHABLE guest-fatal (astra ruling 1795028000000's predecessor 1794798000000-lineage — the exact file is collab/yang-php/1794798000000-architect-class-fatal-ruling.findings.md); the stale test assertion corrected per ruling.
- The dyn producer fix (0c8e67a2): php/make-function completes guest calls before cell/set! applies (sol ruling 1794866000000); dyn fixture rows self-provision.
- The const admission (fd63dad0): bare global constant reads admitted via php/const-get-fq (astra ruling 1794850396000); undefined constant = CATCHABLE Error (NOT guest-fatal); redeclaration warns errno 2; the phpt manifest re-pinned with 9 disclosed migrations (oracle-corroborated on shiva php:8.3).
- The eval-bridge (aa174a11, branch feat/evalb-r17): the stream-service eval path (lower-eval -> php/eval-run -> eval-coordinator/serve, correlated requests), the collision-fatal matrix, and the astra-AUTHORIZED structured refusal payload (ruling 1795028000000 Q1: consumers discriminate via :yang.compile/outcome inside :php/fatal; never stringify; guard code in the ruling file).
- The frontier row (dc184ffa): python->php :finally-replacement — the exception track CLOSED, 12/12 pair matrix, steward acceptance 186/186/0.

## THE ONE OPEN ITEM — the dart leg (at its TWO-STRIKES boundary; the owner has NOT yet decided)
- Unit: cross-host acceptance for the linked fixtures on the dart backend.
- Round history: r1 (codex terra thread 01a1f443-2e10-7390-84fc-ef315cefb3c6) sandbox-blocked before rows ran; produced the harness seam (linked_harness.cljc runtime option + shared dart_linked_test.cljc rows) — UNCOMMITTED in jupiter worktree ~/ci/dw-dartleg.
- r2 (same thread resumed): found the Dart Base64 validator Stack Overflows on the 4M-char php prelude payload (the linker maps that exception to :absent link refusal); fixed with a constant-stack scan (UNCOMMITTED in dw-dartleg: src/cljc/dao/stream/base64.cljc + test/dao/stream/base64_test.cljc).
- Then the seat's own UNSANDBOXED dart lane ran ~3h and HUNG inside dart-linked-test/linked-fixture-rows — dart publication of the 4M payload does not terminate. Evidence: /tmp/dartleg-agg.out and /tmp/dartleg-r2-agg.out on jupiter; the hung flutter was killed by exact PID.
- The escalation is LOGGED (the outgoing log's last entry) and the owner has three options pending: (a) an astra design consult on dart publication termination/perf, (b) defer the dart leg as a recorded known gap, (c) authorize a third round. DO NOT DISPATCH A THIRD ROUND WITHOUT THE OWNER'S WORD — the two-strikes rule is the reason the seat stopped.
- The r2 findings: collab/exception-track/1795106342000-... (dartleg r2 findings — actually in the dw-dartleg worktree AND collab/yang-php/1795099946000 (r1) and 1795106342000 (r2) — verify; the r1/r2 findings files were rescued to origin/master EXCEPT the r2 one may still be worktree-only. CHECK dw-dartleg for untracked findings before any worktree removal.)
- dart-leg findings of record: r1 = collab/yang-php/1795099946000-engineer-dart-leg-r1.gpt-6.1-terra.findings.md? (verify on origin); the compile layer: 9 namespaces compile clean on cljd; the eval coordinator stops at its PHP-parser dependency (no dart bindings — a recorded deferred boundary, NOT this round's problem).

## SIDE STATE (other seats, not yours to touch)
- The DRY seat: round 2 landed (batch-1 dedup: the site-set extraction + prelude-admission split — 35965885/c9dbc991), their batch-2 C6 waits on nothing anymore (the r18 landing note aa174a11 released it); their archive 3a2a9f4a.
- The master-green seat: arc closed (afb6283e + their nightly-arc log).
- The steward: the exception track closed (their acceptance 186/186/0, the merge dc184ffa); the fixture-shape rows (3, long-standing) are THEIRS.
- jupiter worktrees: ONLY dw-dartleg remains of mine (holding the UNCOMMITTED dart-leg work — do not remove it); dw-j7, dw-r2-verify, dw-master-verify-remnant are other seats'/stale.

## ROUTING NOTES FOR YOUR DISPATCHES
- codex terra (gpt-6.1-terra) on jupiter POOL 2 has been the implementation lane; astra (gpt-6-astra, -s read-only) the adjudication lane; the owner wants jupiter codex credit used (the drain directive, 2026-11-16) — superseded only by the routing board reset (codex+agy primary).
- agy lane: Neon for complex multi-file rounds, Hydrogen for routine ones, Argon for top-level adjudications (team.md).
- Known r-series context lives in these threads: dart-leg 01a1f443-2e10-7390-84fc-ef315cefb3c6 (r1+r2); evalb 01a1e56b (r17) + 01a1ef55 (r18); const/dyn/regset chain 01a1e3ef + 01a1e212 + 01a1e24b (astra class-fatal) + 01a1e569 (astra constant); frontier 01a1efed; the dual adjudication 01a1effe. All on their hosts' ~/.codex stores (the jupiter ones on jupiter).

## STANDING RULES FROM THE OWNER (accumulated, verbatim-force)
- "respond only in english".
- do not kill existing dispatches.
- stop assigning tasks to glm-5.3 until further notice (2026-11-16).
- use codex for implementation (2026-11-16), later amended by the routing reset (codex+agy primary) and the jupiter-drain directive (drain the jupiter codex credit — do NOT waste it: preference not waste).
- the main checkout stays on master; worktrees for all branch work (2026-11-14/15 incidents).
- the slow-tier lesson (2026-11-17, filed in build-n-test.md): gate claims NAME THE TIER.
- artifact writes containing backticks need quoted heredoc delimiters.

# Required workflow (for the incoming seat)
- Follow docs/agents/roles/orchestrator.md#workflow in order; create YOUR OWN session log (never append to this file or the outgoing log).
- First actions: (1) re-derive state (git fetch; git log origin/master; git status), (2) ask the owner for the dart-leg decision (a/b/c above) — do NOT dispatch before the answer, (3) verify the dw-dartleg uncommitted findings are intact on jupiter.
- Verification commands: bb gen:antlr (fresh trees first), bb test:sub:clj yang.php (fast tier, ~35-70 min on jupiter), bb test:sub:clj dao.stream (the merge precondition), --slow variants when slow-tagged tests are touched (the tier-naming rule).

Keep scope bounded. Follow the Coordination contract for staging, commits, merges, pushes, and history changes. Inspect direct evidence tied to the tested revision, including remote logs; a delegate summary is not verification.

Begin the final response exactly with:
Completed-GMT: <timestamp>
Completed-Local: <timestamp +07>
Coding-Agent: agy (gemini-4-neon)
Session-ID: <captured after first dispatch or conversation id>

Then report delegated roles/models, prompts and session IDs, verified findings, test outcomes, unresolved risks, and whether the phase is ready to commit. Append the final handoff entry to your own new log before responding, referencing this handoff document path.
