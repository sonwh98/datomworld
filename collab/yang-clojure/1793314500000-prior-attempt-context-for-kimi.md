# Prior-attempt context: codex's adoption fix round (for the kimi continuation)

Written 2026-10-30 by the GLM orchestrator seat, so the kimi fix round does not
re-derive work that is already committed. Treat as **claims to verify**, not authority.

## What codex actually completed before the pool cut

**Commit `1eb14d06` — "fix(yang.clojure): enforce callable admission and restore tail depth"**
(554 insertions, 4 files: src/cljc/yang/clojure.cljc +291, test/yang/clojure_test.clj +143,
test/yang/latte_oracle_test.clj +192, docs/agents/known-gaps.md +36).

It states it implemented the fixes for the r4 rulings (a)-(e): callable admission
enforcement, tail-depth restoration, plus ledger rows.

## Gates codex reported green against that commit

- Required suites (the brief's four namespaces): green.
- **Broader JVM sharded run: 1,022 tests / 16,300 assertions, 0 failures** across 62 namespaces.
- **Node lane: 953 tests / 45,162 assertions, 0 failures.**
- REPL shard 336 tests; residual shard (including the oracle corpus) 61 tests — green.
- Dart: prerequisite built, 42-namespace lane was RUNNING at the cut; **no Dart result exists**.
- Lint: 0 errors, same 2 pre-existing warnings.

## What is NOT done — the remaining work

1. **One uncommitted follow-up.** Codex found: "the source callable predicate must honor an
   admitted profile's `:yang.call/eligible?` function as well as its admission proof." Its
   edit is in the working tree (13 lines in src/cljc/yang/clojure.cljc at the
   `yang.call/eligible?` compile branch, ~line 1284; it binds the candidate through a
   hygienic `dsg__eligibility_candidate` lambda and tests admitted-first, then eligibility),
   plus a 20-line regression `test-common-call-profile-eligibility` in test/yang/clojure_test.clj.
   It passed lint; **the final JVM regression run after this edit had not completed** at the cut.
   Both files are modified-uncommitted now.
2. **No Dart result** for the post-fix commit.
3. No sign-off yet (that's the astra leg, queued for the codex pool at ~23:24 — deliberately
   NOT kimi, since kimi authored the r4 arbitration).

## What kimi should do

- Verify `1eb14d06` genuinely implements r4's (a)-(d) + the (e) fallback refusal, rather than
  trusting the commit message. The glm review of S1 (collab/1793309500000-…glm-5.3.findings.md)
  lists the exact probes each finding had; re-running those probes is the cheapest check.
- Assess and finish the uncommitted eligibility follow-up (is it correct? does the regression
  prove what it claims?), then run the required suites to completion.
- Do NOT redo work: if a ruling is already satisfied by 1eb14d06, say so with evidence and move on.
- Report the Dart gap as unrun (not green).

## Artifacts

- Commit: `1eb14d06` on branch `yang-common-call` (not pushed)
- Full session trail: `collab/1793311000000-engineer-adopt-fix2.gpt-6.1-sol.stdout.log` (188 items)
- Partial-diff snapshot taken before the commit was discovered (superseded by the commit):
  `collab/1793311000000-engineer-adopt-fix2.PARTIAL.diff`
