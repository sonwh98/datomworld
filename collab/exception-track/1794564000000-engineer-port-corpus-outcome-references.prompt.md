Created-GMT: 2026-11-12 23:10:00 GMT
Created-Local: 2026-11-13 07:10:00 +07 (+0700)
Coding-Agent: codex
Session-ID: 01a1d1ee-523c-7be2-b321-8eb8aae65cb7 (resume; capture new id from this run's thread.started)
# Task: engineer — PORT: finish the corpus port (5 yin.k/outcome-of errors) + confirm the budget error against cc3's ruling
Role: Engineer
Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-13 07:10 +0700 | Status: active | Rationale: you found these in the re-record run; you own the harness and the corpus.

## Two error classes in `yang.latte-oracle-test` at `691e9bd6`

### Class 1 — FIVE unresolved/unadmitted `yin.k/outcome-of` errors
The port retired the outcome machinery (Phases 2/5/6), yet the LaTTe oracle corpus still hits
`yin.k/outcome-of` — the Phase-3r corpus port was INCOMPLETE. Find every remaining reference
(corpus fixtures, the oracle's own helper tables, anything the harness feeds to the compiler)
and port them to the continuation encoding:
- Where outcome-of was used to "unwrap an outcome for observation": the continuation encoding
  returns plain values — the reference should DECODE to plain evaluation, not be wrapped.
- Where it expressed may-fail control flow: rewrite to `clj/try`/catch (the dialect's finally-
  capable shape) OR drop the construct if the fixture's purpose was the outcome protocol itself.
- Each changed fixture: one-line justification in the commit body; NO test weakened — if a
  fixture's whole subject was the outcome protocol, it becomes a test that the dialect now
  RAISES correctly at that site (same intent, new mechanism) and say so in the pin name.

### Class 2 — ONE machine-data validation budget exhaustion
Read cc3's bounded-run ruling chain (the r2 8MB ceiling, the v2 declaration — the yang.clojure
seat's cc3 arc; find the artifacts in `collab/yang-clojure/` and the cc3 merge commits).
**If this error is the ruled condition** (run preserved FAILED under the v2 bounded-run
declaration): do NOT "fix" it — cite the ruling (artifact + commit) and confirm it reproduces
exactly as ruled. If it is NOT that condition, diagnose and fix as class 1.

## Acceptance

`clj -M:test -n yang.latte-oracle-test` → 0 failures; errors reduced to exactly the ruled class-2
condition (cited), or zero if class 2 turns out fixable. `yang.clojure-test` stays green; the
depth harness unchanged (your re-record stands). kondo 0/0 on changed files. No kit/VM files.
Commit + push `exception-r3-impl`.

End with Completed-GMT/Completed-Local and: **CORPUS PORT COMPLETED: <sha> | class2: <ruled-cite|fixed>**
or **FINDING: <what>**.
