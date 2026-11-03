Created-GMT: 2026-11-02 11:20:00 GMT
Created-Local: 2026-11-02 18:20:00 Asia/Ho_Chi_Minh
Coding-Agent: vibe (mistral-4.0); codex CLOSED until further notice per the owner
Session-ID: not-applicable (orchestrator-dispatched, fresh session)
# Task: H3-0 — restore the declared dispositions and make the kernel-gate evidence trustworthy

Role: QA/harness engineer (mistral-4.0 via vibe). NOTE: your implementation will be code-quality reviewed by a kimi round before landing — write for review.
Reviewer/landing: the ZCode glm orchestrator seat; a compiler-engineer reviewer checks the callable-representation part; the architect reviews the disposition guards.

## Governing instructions

The architect's hardening-3 plan APPROVED this slice (plan: `collab/1793770000000-architect-hardening-3-design.gpt-6-astra.prompt.md` + its stdout log — read both). Read `test/yang/latte_oracle/slice9_gate.md`, the refreshed `slice9_evidence.edn` / `slice9_unexercised.edn`, and the harness `test/yang/latte_oracle_test.clj` (the classifier is ~line 1932; the gate test ~2235-2300). Work in `/Users/sto/workspace/datomworld-h30`, branch `hardening3-h3-0`, base `6188b98a6` (master). Push the branch; do NOT merge.

## Headless operating constraints (binding)

You run HEADLESS on the vibe CLI with edit approvals only: file edits are auto-accepted; shell command approvals are NOT available, so any bash call blocks the round forever (measured on another headless agent). Therefore:

- **Do not run shell commands. Not once.** All changes happen through file edits only.
- The verification commands you WOULD run (the focused gate test, the full oracle namespace, kondo) go into your report as exact command lines; the seat runs them.
- Do not attempt git operations — the seat owns staging, review, and landing.
- A previous session created `test/yang/latte_oracle/slice9_declarations.edn` — read it, keep what is correct, continue from it.

## Scope (harness-only — no compiler changes, no yang.call/yin.vm changes)

Writable: `test/yang/latte_oracle_test.clj`, `test/yang/latte_oracle/*` (evidence/gate records and any new declaration file), `docs/agents/known-gaps.md` (only to refresh stale yang.clojure gate narrative rows). Everything else forbidden.

## The two defects to repair

1. **The evidence writer drops declared dispositions.** `write-kernel-gate-evidence!` regenerates `:recorded-portability-boundaries` as `[]` and no fixture row carries `:status :recorded-deviation`, so the gate test's deviations assertion fails. The declared records are recoverable verbatim from `git show ea18c8e4^:test/yang/latte_oracle/slice9_evidence.edn` (see `:recorded-portability-boundaries`): `elab-after-qed` and `check-after-qed`, boundary `:nonportable-host-value`, upstream embeds `clojure.core/meta` in error data, reason verbatim in that history.

2. **The deviation classifier is representation-stale.** The architect's finding: the classifier still requires a bare closure at `:meta` (~line 1932), but the refreshed outcomes contain a §8.10 callable descriptor (the adoption's published representation). Restoring the declarations therefore requires a representation-aware classifier: validate the guest callable using its actual runtime representation — a map containing `{:type :closure}` is insufficient. Do NOT make C-lane callable representations equal merely to obtain agreement; preserve every original outcome and agreement flag.

## Required behavior (the architect's H3-0 acceptance, binding)

- Declarations become explicit, versioned input to classification (a declaration file or clearly-versioned structure in the harness), NOT something generated evidence is the sole source of. Provenance: `ea18c8e4^`.
- Carrying a declaration forward means REVALIDATING the current fixture against its declared boundary: pinned fixture ID and call, completed A/B/C executions, the original exception message, the script value and collection shape, and the precise host-function/guest-callable boundary. For the two after-qed rows specifically.
- `typing-ascription` and `compile-malformed` retain their exact guarded upstream-failure dispositions; all four declared exception departures retain scope and provenance.
- Negative controls (new regressions): a mutated call, an unrelated fixture ID, a changed payload or shape, a forged callable representation, and an incomplete lane must each FAIL classification.
- Writer round-trip tests: declarations, statuses, raw comparisons, and coverage survive a write+read cycle; ordinary tests stay read-only.
- Refresh the stale gate narrative (`slice9_gate.md` and the known-gaps rows): neither "145/165" nor "only 20 remain" describes the baseline — it is 107 credited / 58 unexercised, 100 agreements, 2 deviations, 2 blocked, 2 upstream failures.
- Expected post-slice gate state, with no other changes: 100 agreements, two deviations, two blocked fixtures, two upstream failures; 107 credited, 58 remaining. The final `(= :pass (:gate-status evidence))` assertion stays unchanged and still fails (the gate stays :blocked). Any different result requires explanation in the report.

## Verification

- Focused: the gate test (`test-kernel-differential-gate`) — after H3-0 exactly ONE expected failure remains (the blocked assertion), identified by assertion line.
- The full oracle namespace; the accounting/deviations/blocked assertions each identified; kondo on the touched files; the poisoned-parse/1 and unused-arity regressions preserved.
- Record real command output for every claim.



## Redo requirements (from the kimi review of a REJECTED first attempt — binding)

A previous implementation of this exact task was rejected. Your round must satisfy this checklist:

1. Only reference namespaces that EXIST in this repository — verify by reading the tree before you write a require. The rejected attempt invented yang.runtime, yang.values, yang.sources, yang.kernel and yang.latte-oracle; none exist. The real namespaces you need are already required by the harness's own (ns) form — read it first and do not restructure it. Never splice into the middle of the (ns) form.
2. Preserve the original host `clojure.core/meta` marker guard in the classifier.
3. Validate the real admitted descriptor representation and reject forged, copied, or malformed descriptors — check what the codebase actually produces (read the harness and `yang/clojure` publishing code) rather than inventing a shape.
4. Retarget all existing negative controls to your production classifiers and add mutated-call coverage.
5. Writer write+read tests use a temporary file only — ordinary tests stay read-only.
6. Refresh BOTH narrative locations (`docs/agents/known-gaps.md` row and `test/yang/latte_oracle/slice9_gate.md`) with ONLY the true accounting: 107/165 credited, 58 unexercised, 100 agreements, 2 deviations, 2 blocked fixtures, 2 upstream failures, gate :blocked. Never write the stale 145/165 or 102-agreements numbers, never claim H3-0 is closed — the seat and the reviewers decide that.
7. Write each narrative edit exactly once — the rejected attempt appended the same row multiple times and duplicated the whole patch.
8. End your report with the exact verification commands for the seat (focused gate test, full oracle namespace, kondo) — do not pretend to have run them.

## Report

Findings to `collab/` in the worktree (it may reject a flat file — if so, note the path in the branch commit message and leave the file untracked; the seat archives it). Commit on the branch with `test(latte):` prefix; push; do not merge.
