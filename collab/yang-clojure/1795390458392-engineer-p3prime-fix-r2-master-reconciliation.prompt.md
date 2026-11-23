Created-GMT: 2026-11-22 19:34:18 GMT
Created-Local: 2026-11-23 02:34:18 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: pending (provider-generated; fresh session — resume-by-state)

# Task: yang.preludes P3′ fix r2 — reconcile the branch with master's in-session store-switching landing

Role: Engineer

Implementers:
- Model: gpt-6.2-sol | Assigned: 2026-11-23 02:34 +07 | Status: active | Rationale: same lineage; this is a semantic merge resolution requiring knowledge of the P3′ descriptor wiring

## Context (resume-by-state)

`/Users/sto/workspace/dw-p3prime` (branch `feat/p3prime-descriptor-cljpy`, tip `f765cd58`, base `b1eb3e40`) carries the complete, review-APPROVED P3′ slice (descriptor validation, clj.py, DEFAULT flip, the gate-repair fix — all verified green). Master has ADVANCED since: `origin/master` is now `7be4e2da`, and a new feature landed in between — `d3eceea0`/`13a94312` "feat(yin.repl): in-session dao.jing store switching — (use-store ...) with quiesce gate and strict open-first swap" — which rewrote ~199 lines of `src/cljc/yin/repl.cljc`, overlapping the P3′ wiring in `create-state` and `replace-vm` (~30 lines changed by P3′).

An orchestrator merge attempt conflicted in exactly two hunks of `src/cljc/yin/repl.cljc` and was ABORTED (master is clean again; do not touch the main checkout `/Users/sto/workspace/datomworld`).

## Task

1. In `dw-p3prime`: `git fetch origin && git merge origin/master` (a merge INTO the feature branch, resolving conflicts there). The two conflict hunks are in `src/cljc/yin/repl.cljc`:
   - `create-state` region: master's side restructured the store/link-source/media setup (the composition link-source derivation, `store/open`, recovery, publication media) while P3′'s side has its own version of that region plus the frontend wiring.
   - `replace-vm` region: master renamed the cond predicate `store/durable?` → `store/retain-on-reset?`; P3′ added `(update :vm admit-frontend (:frontends state) (:lang state))` into the `->` thread before the cond.
   The required union preserves BOTH features: master's store-switching semantics (retain-on-reset gating, carry-over, the new use-store machinery) AND P3′'s descriptor wiring (`admit-frontend` application of the selected revision's descriptor, the default-selector preferred-revision resolution). Read both sides fully (`git log -p` for `13a94312`, and `git diff b1eb3e40..HEAD` on the branch for the P3′ hunks) before resolving — the goal is a semantic union, not a textual pick.
2. If master's new `use-store` flow interacts with the frontend catalog or `:frontend-session` anywhere else, reconcile those too.
3. Verify BOTH feature suites (all must pass):
   - `clj -M:test -n yin.repl.python-antlr-test` → 0 failures (P3′)
   - `clj -M:test -n yang.python.antlr.descriptor-profile-test -n yin.repl.frontend-catalog-test -n yin.repl.query-test` → 0 failures
   - `clj -M:test -n yin.repl-test` (or master's store-switching suite — find it via `git show 13a94312 --stat`) → whatever master requires, 0 failures
   - `clj -M:test -n yang.clojure-test -n yang.clojure-callsite-test -n yang.rt.effect-test` → 160/1677/0
   - `clj -M:test -n d2-wedge-min-repro` → 0 failures
   - `clj ./bin/analyze-deps.clj` clean
4. The merge commit: you may `git commit` the MERGE ITSELF on the feature branch (this is the one exception to the no-commit rule — a merge commit needs to exist to complete the merge; author it as the plain merge commit with the default merge message plus a body noting both features). No other commits.
5. `git add -N` any new files master's landing brought that need it (likely none).

## Deliverable

Final report: how each conflict hunk was resolved (what master contributed, what P3′ contributed), the verification matrix with counts, and confirmation that neither feature regressed (store switching AND descriptor flip both demonstrably work — e.g. run a REPL session that swaps stores AND evaluates under the default clojure-core-v1 revision, if such a path exists).
