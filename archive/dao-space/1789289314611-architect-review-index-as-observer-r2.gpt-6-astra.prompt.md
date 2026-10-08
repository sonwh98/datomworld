Created-GMT: 2026-09-13 08:48:34 GMT
Created-Local: 2026-09-13 15:48:34 +0700 (+07)
Coding-Agent: codex
Session-ID: 01a099f1-87d6-7811-8a69-b3336b956fac (resumed)

# Task: Architecture & Invariant Review — dao.space.index as a dao.stream Observer (resume after usage-limit failure)
Role: Lead System Architect (review)

Implementers:
- Model: gpt-6-astra | Assigned: 2026-09-13 15:48:34 +0700 | Status: active | Rationale: Resume of the interrupted review in the same thread; the brief and read-list are unchanged.

Your previous turn failed on a provider usage limit after you had reported three leads:
1. the proposed index state is not an accepted `dao.space.query` value;
2. draining recorded content may break B-tree reads after eviction;
3. the retry text does not specify how the unaccepted suffix of a staged publication is retained.

Continue from there and **deliver the complete report** in the format the original brief specified (`collab/1789289033041-architect-review-index-as-observer.gpt-6-astra.prompt.md`): header block, findings by severity with file:line and recommended correction, properties that passed, explicit verdict. Do not restart from scratch; do not write files. Nothing in the repository has changed since your first turn (HEAD `5296ee5`).

## Correction appended 2026-09-13 16:12 +0700, before this brief was consumed
The sentence above claiming nothing changed is superseded. While the usage limit held, the independent runtime reviewer (glm-5.3) ran three rounds and approved; the note took three docs-only commits after `5296ee5`: `280dda2`, `0bd7550`, `e101932` (run `git log --oneline -4 -- docs/design/dao.space.index.as-observer.md`). Review the note **at current HEAD**. The changes: an **Appendix A** ledger of those findings (F1–F5, N1–N3); §2.2 admission reworded (`local-datom?` minus the `e ≥ 0` clause; `v` needs no schema at admission; tx records and `v`-only tempids stated); §2.3 retractions on `t 0` media folded and `current` throws loudly; §3.2 the `:dao.space.index/*` reservation as a contract on media; §4.1 a new paragraph on why draining the recording handle is unsafe if a tree refaults through it, fixed by every index-session tree carrying `:strong` settings — with a resumed session restoring through a session-constructed `:strong` `kv-storage`, never the query read path; §4.2 "a query, not a scan" corrected to O(rows)-but-no-replay; Phase 0′ tests reworked (checkpoint value round-trips, not a live session; deterministic hazard test via the `:test` ref seam). Your three partial leads may already be addressed by these — say so per lead rather than re-deriving them. Everything else in the original brief stands.
