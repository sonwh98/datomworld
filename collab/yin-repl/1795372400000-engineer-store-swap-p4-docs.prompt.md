Created-GMT: 2026-11-23 00:20:00 GMT
Created-Local: 2026-11-23 07:20:00 ICT (+07)
Coding-Agent: interactive (ZCode / glm-5.3-flash, orchestrator seat)
Session-ID: a65337a3-0946-411f-abd2-32736aa26803 (resume of your Slice 3 conversation)

# Task: store-swap Slice 4 — user-facing and design documentation

Role: VM / Storage Engineer (documentation)

Implementers:
- Model: gemini-4-argon-high | Assigned: 2026-11-23 07:20 ICT | Status: active | Rationale: docs round on the same track; you hold the full design context

You are in the datomworld feature worktree
/Users/sto/workspace/datomworld-store-swap, branch store-swap-p1. Slices 1-2
(engine quiescence + mem↔file swap; DHT composition swap) are implemented and
review-approved there; Slice 3 (Gambit) lives in the fork and is
review-approved. Implement Slice 4 (documentation) exactly per the design
record's file-spec item 7.

## Deliverables

1. src/cljc/yin/vm/docs/yin.repl.md — update §2 ("Working at the Prompt") to
   document (use-store ...): the quiesce requirement ((reset) before
   switching), the mem ↔ file:<dir> ↔ dht:<dir> workflows, the :peers /
   :listen / :publish / :key / :join options for dht: (NOTE: document
   :trust [...] and :follow [...] with vector syntax — the Slice 2 review's
   P3 note), non-interaction with state.edn, and the Gambit C4 honest refusal
   behavior (mem→mem only; durable targets refuse; session survives).
2. docs/design/yin.repl.dao.space-index.md — replace "There is no runtime
   switching" with the (use-store ...) quiesced open-first switching contract.

Match the existing tone and structure of both documents. Do not invent
behavior beyond the design record and the implemented code (read the diff /
the code where a detail matters: src/cljc/yin/repl.cljc, repl/main.cljc,
shell/cli.cljc).

## Boundaries

- Work ONLY in /Users/sto/workspace/datomworld-store-swap, branch store-swap-p1.
- Files you may change: ONLY the two documents above. Do not commit.
- FOREGROUND only, explicit timeouts; never background a lane.

## Verification

- git diff --check HEAD -- <the two paths> (documentation whitespace/format
  lint; must pass).
- Confirm every claim you write against the code; where you are unsure, read
  the code, do not guess.

## Deliverable

Write your report to
collab/yin-repl/1795372400000-engineer-store-swap-p4-docs.gemini-4-argon-high.findings.md
in this worktree (stage at the worktree root and say so if the sandbox blocks
the write): what you changed, claims verified against code, deviations.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then: files changed, verification outcomes, deviations, findings path.
