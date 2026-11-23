Created-GMT: 2026-11-22 23:09:36 GMT
Created-Local: 2026-11-23 06:09:36 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: pending (provider-generated; fresh session — resume-by-state)

# Task: yang.preludes P3′ fix r3 — cross-node cljs regression on merged master (P3-caused)

Role: Engineer

Implementers:
- Model: gpt-6.2-sol | Assigned: 2026-11-23 06:09 +07 | Status: active | Rationale: same lineage; regression diagnosis on the P3′ landing

## Context (resume-by-state)

P3′ is MERGED to master as `5b655b59` (chain: fd148fd9 implementation → f765cd58 gate fix → 2e7ded4a master-reconciliation merging the in-session store-switching landing). The post-merge fresh-clone verification on jupiter caught a repeatable regression the branch gates missed:

- **master `7be4e2da` (pre-P3):** `bb test:sub:cljs dao.stream` = 579 tests / **0 failures** (verified on the jupiter verify clone during this investigation).
- **master `5b655b59` (P3 merged):** same lane = 579 tests / **2 failures**, reproduced THREE consecutive runs:
  - `FAIL in (a-node-dialer-reads-a-jvm-server-over-transit) (dao/stream/ws_project_cross_node_test.cljs:578)` — "the cursor ask never settled" expected `(some? c0)` actual nil; plus "the clj->Node proof stopped early".

This failure was previously seen ONCE during the P2′ verification and dismissed as transient — the new evidence (pre-P3 passes, P3 fails, 3× consecutively) overturns that: it is a P3-caused regression. Work in `/Users/sto/workspace/dw-p3prime` (branch `feat/p3prime-descriptor-cljpy`, tip `2e7ded4a`, tree clean).

## What P3′ touched that could affect this path

The fixture is cross-process: a **JVM** session serves published content; a **Node** dialer reads it. "The cursor ask never settled" means the JVM server never answered a cursor read. P3′'s diff touches the serving session's startup path:
- `src/cljc/yin/repl.cljc` — `create-state`/`store-session` now run `admit-frontend` (the descriptor runtime admission) at startup and on store swaps; the reconciliation with master's store-switching rewired this region.
- `src/cljc/yin/repl/frontends.cljc` — catalog/revision/default-selector changes.
- `src/cljc/yin/vm/linker/publish.cljc` — `module-manifest` extraction (refactor; verify it's content-preserving) and whatever the descriptor flow added.
- `src/cljc/yin/vm/module.cljc`, `engine.cljc` — export-binding, dispatch changes.

Hypotheses to discriminate (read the fixture FIRST — `test/dao/stream/ws_project_cross_node_test.cljs:540+` — to see exactly what the JVM side runs): (a) `admit-frontend`/descriptor validation throws or blocks in the JVM server's session setup under conditions the fixture hits (e.g. no frontends catalog supplied, or a `:lang` the catalog doesn't know), leaving the server unable to serve; (b) the publication manifest content changed (address shift) so the dialer's ask doesn't match; (c) a session-state key rename/collision from the reconciliation (e.g. `:frontend-session` clearing) breaking the served round. Also test whether `bb test:sub:cljs dao.stream` failure reproduces LOCALLY (laptop) — if it's jupiter-environment-specific, note that; the pre/post evidence above was gathered on the same clone so it should reproduce.

## Task

1. Reproduce and diagnose: bisect the cause to a specific commit-hunk (`fd148fd9` implementation vs `f765cd58` fix vs `2e7ded4a` reconciliation) by reading the diffs and, if needed, locally reverting candidate hunks in a scratch worktree (do NOT rewrite master or the branch history — work in a detached scratch checkout, e.g. `git worktree add /tmp/p3-bisect 7be4e2da` and cherry-pick forward).
2. Fix the defect in `dw-p3prime` (commit on the branch, one fix commit; this is authorized — the branch already carries your merge commit).
3. The fix must NOT weaken: descriptor validation, the DEFAULT flip, the store-switching union, the P1′/P2′ invariants, or the fixture's assertions (never touch the fixture unless it encodes the wrong expectation — if so, justify with evidence).
4. Verify (all green): `bb test:sub:cljs dao.stream` (local reproduction if it reproduces locally; otherwise state what you ran), `clj -M:test -n yin.repl.python-antlr-test -n yin.repl-test`, descriptor+catalog+query, clojure suites 160/1677, wedge, `analyze-deps` clean.
5. If the diagnosis shows the root cause is in the RECONCILIATION commit (2e7ded4a), say so explicitly — that changes what the reviewer re-checks.

## Deliverable

Final report: root cause with file:line and the discriminating evidence, the fix, the verification matrix, and whether jupiter-local reproduction was possible. Do NOT push (orchestrator handles landing); the branch commit is your deliverable.
