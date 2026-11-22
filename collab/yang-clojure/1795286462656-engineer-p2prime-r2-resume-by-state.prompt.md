Created-GMT: 2026-11-22 02:21:02 GMT
Created-Local: 2026-11-22 09:21:02 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: pending (provider-generated; fresh session — resume-by-state)

# Task: yang.preludes P2′ r2 — resume by state (r1 stalled on sandbox roots)

Role: Engineer

Implementers:
- Model: gpt-6.2-sol | Assigned: 2026-11-22 09:21 +07 | Status: active | Rationale: fresh session after r1 stalled; dispatched FROM the worktree so the workspace-write sandbox root is the worktree itself

## Context (resume-by-state)

The prior round (r1) worked in THIS worktree `/Users/sto/workspace/dw-p2prime` (branch `feat/p2prime-clojure-core`, base master `f51c3a0d` with landed P1′) and stalled when its sandbox refused writes outside its launch directory. Its partial work IS ON DISK, uncommitted:

- `src/cljc/yang/clojure/core_impl.cljc` — 428 lines (the `clojure.core.impl` module, in progress)
- `src/cljc/yang/clojure/core_module.cljc` — 64 lines (the `clojure.core` facade, skeleton)
- `src/cljc/yang/clojure/prelude.cljc` — 18 lines changed
- `src/cljc/yin/vm/engine.cljc` — 5 lines changed

Treat all of it as an unfinished draft to verify and complete — your work on disk is intact, resume by state. Read the current diff (`git diff`) first, judge what is correct and what is missing, then continue.

## The full task spec

Read your predecessor brief at `/Users/sto/workspace/datomworld/collab/yang-clojure/1795286006293-engineer-p2prime-clojure-core-migration.prompt.md` — it remains the governing task definition (§7.5 two-module `clojure.core` contract, §9 P2′ acceptance gates, out-of-scope fences, pre-existing reds to exclude, P1′ lesson: run FULL namespaces not `-v` selections). All paths and gates there apply here unchanged.

Working tree: `/Users/sto/workspace/dw-p2prime` ONLY (this is your writable root and your cwd — that was r1's failure mode). `git add -N` every NEW file. Do NOT commit.

## Acceptance gates (same as r1's brief — restated)

- Locally-bound-map `:k` → 9 fixture through the new standard library (native data-call dispatch admits locally bound maps).
- Bundled primitive shadowing: bare `get`/`assoc` resolve through the source store before VM primitives.
- Unified `yang.clojure` suite green — FULL namespaces (`clj -M:test -n yang.clojure-test -n yang.clojure-callsite-test -n yang.rt.effect-test`, plus `d2-wedge-min-repro` stays green).
- `yang.clojure` goldens refreshed cleanly; py/js/php goldens byte-identical.
- `clj ./bin/analyze-deps.clj` clean.
- Cross-syntax unwind gates at P3′, not here.

## Deliverable

Final report: what r1's draft got right/wrong (with file:line), what you changed, files added/changed, each gate with exact command and counts, goldens status, ambiguities resolved with §/quotes.
