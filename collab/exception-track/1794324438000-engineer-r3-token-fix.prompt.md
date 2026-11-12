Created-GMT: 2026-11-10 15:27 GMT
Created-Local: 2026-11-10 22:27 +0700
Coding-Agent: qwen
Session-ID: pending (provider-generated)
# Task: exception R3 — the token-authenticated `:dsg__condition` fix per the argon ruling
Role: Yang Compiler and Universal AST Engineer
Implementers:
- Model: k3.1-pro (via kimi CLI) | Assigned: 2026-11-10 22:27 +0700 | Status: failed | Rationale: provider credit exhaustion mid-round ("credit balance is running low"); partial work uncommitted, resume-by-state. Originally: roster pick for difficult compiler/invariant work; different family from the gemini author; gemini has two strikes on this unit.
- Model: kimi-k3-1-pro (via qwen CLI, --approval-mode auto-edit) | Assigned: 2026-11-10 23:1x +0700 | Status: active | Rationale: same model, different channel — the qwen lane's kimi quota is separate; owner confirmed it is NOT out of credit. Probed OK before dispatch.

You are implementing a SMALL, fully-specified fix on top of an existing verified diff.
The architecture is decided; do not redesign. The spec is an architect ruling — follow
it exactly.

Worktree: `/Users/sto/workspace/datomworld-r3-orch`, branch `exception-r3-impl`,
commit `98519957` (rebased on master `8cd9fc92`). Do NOT create branches or worktrees;
commit on this branch.

## Read first

1. **THE SPEC — the argon ruling** (authoritative, every site pinned with file:line):
   `collab/exception-track/1794320926000-architect-dsg-condition-r4-coupling.gemini-4-argon-high.findings.md`
   Read section 4 ("Minimal Fix Instruction") in full. It rules:
   - `:dsg__condition` is **LOAD-BEARING** (retained-condition provenance vs
     guest-thrown condition-shaped data) — do NOT delete it.
   - Stamp it with the compiler-held closure value **`dsg__call_token`** (NOT literal
     `true`) at writer sites #1–#6 (clojure.cljc:1544, 1548, 1567, 1586, 1636, 2617).
   - Writer site #7 (`invoke-value`, clojure.cljc:2794-2795): restructure per the
     ruling (the library form cannot name `dsg__call_token`; trigger the refusal
     through compiler-generated code instead).
   - Reader/stripper sites (call-entry clojure.cljc:1312-1315; strip-top-outcome
     clojure.cljc:2430-2433): token EQUALITY `(dsg__equal (dsg__get ...) dsg__call_token)`,
     never truthiness.
   - Fix the stale `:tail? true` marks in `compile-program`: replace `u/mark-tails`
     with `yang.tails/remark-tails` at clojure.cljc:2674 (and/or pass
     `(if (outcomes? env) false tail?)` at :2446) so `:semantic` never tail-skips
     `strip-top-outcome`.
2. The existing diff: `git log -p -1` in the worktree (commit 98519957).
3. Ruling v2 §R3/§R5: `collab/exception-track/1794960000000-owner-ruling-exception-uniformity-v2.md`.

## Also fix

- `test/yang/clojure_test.clj` — the R3 test helper references an unbound
  `profile-raise` symbol (ERROR in `test-common-call-rejects-named-arguments`);
  bind it the way the neighboring tests do.

## Acceptance (run ALL, foreground, serially — each command exits before the next starts)

```sh
cd /Users/sto/workspace/datomworld-r3-orch
eval "$(mise env -s bash)"   # mise already trusted
mise install >/dev/null 2>&1 || true
bb gen:antlr 2>&1 | tail -1
clj -M:test -n yang.clojure-test 2>&1 | tail -3
clj -M:test -n yang.call.exception-contract-test -n yang.python.antlr.exception-call-test 2>&1 | tail -3
clj -M:kondo --lint src/cljc/yang/clojure.cljc test/yang/clojure_test.clj 2>&1 | tail -2
```

## Expected numbers

- Pre-fix (measured, same branch, shiva): kit = **144 failures / 39 errors**;
  `yang.clojure-test` = 16 failures / 1 error.
- Post-fix targets: kit ≈ **132–141 failures / 39 errors** (the remaining 3 R3-gated
  rows flip as the token reaches every writer site; report exact numbers, do not tune
  to force a count); `yang.clojure-test` **all green** including
  `test-value-invocation-refusals-are-terminating` (no `:dsg__condition` in any
  guest-visible map) and the fixed fixture; kondo 0/0.
- Guest-visible condition maps leaving `call-entry`/`strip-top-outcome` must be
  strictly `{reason, data}` or `{reason, value}` — `:dsg__condition` stripped,
  never leaked.

## Commit

One commit on `exception-r3-impl`, message:
`fix(yang.clojure): token-authenticate the retained-condition provenance marker (R3; per argon ruling)`
Do NOT push, do NOT merge.

## Rules

- Do NOT redesign the mechanism; the ruling decided it. If you find a site the ruling
  mis-pins (a line number off, a site unreachable), report it — do not improvise.
- Do NOT touch `:clj/escape-token` / `dsg__call_token` authentication logic itself —
  only the marker stamping/readers per the ruling.
- Never background a command; run each test foreground and wait.
- Do not run anything outside `/Users/sto/workspace/datomworld-r3-orch`.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report: per-site confirmation of the ruling's instructions (site → what you changed),
the three test outputs verbatim, kondo, the commit sha, unresolved concerns.

## RESUME ADDENDUM (2026-11-10 23:1x +0700) — state on disk when you start

The prior kimi-CLI round applied MOST of the ruling, then died on provider credit.
Its uncommitted work is in the worktree; VERIFY each item before trusting it:

1. Writer sites #1–#6: `:dsg__condition` now stamped `(u/v 'dsg__call_token)` — appears done.
2. Reader sites (call-entry :1312, strip-top-outcome :2431): `dsg__equal` token equality — appears done.
3. `u/mark-tails` → `tails/remark-tails` at :2676 (+ the `yang.tails` require) — appears done.
4. **`invoke-value` `:else` branch (clojure.cljc:~2799) is WRONG as left**: it is now
   `:else (f)))` — a guest apply that re-enters the dispatcher and loops to
   `Guest instruction budget exhausted` (6 errors in `yang.clojure-test`). The ruling
   demands a *compiler-generated* not-callable refusal — stamp `dsg__call_token` via
   lowered code, do NOT re-apply from the library. Fix per ruling section 4.
5. Fixture fixed: `run-outcome` → `run-profile-outcome` (done).
6. `yang.tails` require added (done). Kondo 0/0 as of the cut.

Current measured state (`yang.clojure-test`, orchestrator re-run): **3 failures / 6 errors
— all one seam**: the 6 errors are the budget-loop from item 4; the 3 failures
(`test-common-call-rejects-named-arguments` :1445, `test-library-failures-flow-through-closures`
:2473-2474) assert the OLD §8.10.7 wire shape (vector `[:yang.call/thrown <map>]`) while the
R3 mechanism produces the condition record `{:reason :thrown, :value <map>}`. The kit's
`:clojure/shape-*` rows pass with the record shape on the rebased branch, so the RECORD is
the R3 wire contract: update those three pre-R3 test expectations to the record shape and
keep the payload/evidence assertions intact.

Finish: fix item 4 properly, update the 3 expectations, re-run the full acceptance block
from the main brief (foreground, serial), commit on `exception-r3-impl`, do NOT push.
