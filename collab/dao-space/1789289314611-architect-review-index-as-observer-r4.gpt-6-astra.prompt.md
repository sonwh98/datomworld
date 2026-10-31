Created-GMT: 2026-09-13 09:33:00 GMT
Created-Local: 2026-09-13 16:33:00 +0700 (+07)
Coding-Agent: codex
Session-ID: 01a099f1-87d6-7811-8a69-b3336b956fac (resumed)

# Task: Round-4 Confirmation — dao.space.index as a dao.stream Observer (R1–R5 folded in)
Role: Lead System Architect (review)

Implementers:
- Model: gpt-6-astra | Assigned: 2026-09-13 16:33:00 +0700 | Status: active | Rationale: Same reviewer confirms its own round-3 findings.

**Read-only review. Print your complete structured findings to stdout; write no files.**
Repository `/Users/sto/workspace/datomworld`, branch `dao.stream-redesign-v2`; run `git log --oneline -1` (one docs-only commit after `3d32eb4`). Target `docs/design/dao.space.index.as-observer.md`; Appendix A's "Architecture round 3" table maps R1–R5 to resolutions, and glm-5.3's round-4 P3s (which overlapped R3/R4/R1) are folded in the same pass.

## What changed
- **R1** — §2.1: `index/checkpoint` and a new `index/coverage` take the whole `{:observer :consumer}` session (the cursor and gap count live in the observer half); `db-value` stays over the consumer and carries no stream; `restore` returns the consumer and the composition re-attaches the observer at `c` — which needs `dao.stream.observer/attach` to accept a kept cursor (it mints `:oldest` only today; `dao.stream.md` already says kept cursors cover repositioning). The dependency direction (`index → observer`, never back) is stated. §4.2's capture paragraph reworked accordingly; §5 defines "live" via `coverage`.
- **R2** — §2.2: a monotonic `:rejected` count, never drained, beside the drainable `:defects` events; carried in coverage and checkpoint; reject → drain → checkpoint → restore test.
- **R3** — §3.1: disjoint partition tempid `<0` / reserved `[0, first-user-id)` / user-positive `≥ first-user-id`; reserved `e` **passes** on `:resolved` (matching `local-datom?`, so Phase 0′ parity is unconditional), stated as a decision not to enforce a reservation the runtime never enforced; §2.2's "reserved passes in every slot" replaced by "per the matrix".
- **R4** — §2.2: an empty admitted batch advances `:batch` only; `:max-t` is `nil` until a row folds; watermark is `0` when `nil` else `max-t + 1`, so an empty checkpoint reopened in-process derives `0`; within-row order `e`, then declared-ref `v`, then `m`.
- **R5** — `:ids` records `:owned`/`:shared`; `restore` refuses `:shared` with your A-then-B example; coordinated recovery of a shared domain is a new §8 open question; test with a sibling allocating after capture.
- Promotion (your notes): walks all four roots, checks every address in the visitor including leaves, through `jing/get` only; "manifest present, one leaf absent" test added; separate obligations (checkpoint record recoverability, suffix retention) stated.

## What to do
1. R1–R5: resolved / partially / not, with section or gap.
2. Two probes: (a) is "`restore` refuses `:shared`" the right boundary, or should a shared-domain session be checkpointable at all before the coordinated design exists? (b) does putting `coverage`/`checkpoint` in `dao.space.index` over the observer's `{:stream :cursor :ingress-gaps}` shape create any coupling you would object to, versus a composition-level helper outside both?
3. Anything new the revision introduced.

If nothing blocking remains, say APPROVE.

## Output Format
Begin your output exactly with:
```text
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a099f1-87d6-7811-8a69-b3336b956fac
Role: Lead System Architect (review) | Model: gpt-6-astra
```
Then the resolution table, any new findings by severity, and an explicit verdict: APPROVE or REQUEST CHANGES.
