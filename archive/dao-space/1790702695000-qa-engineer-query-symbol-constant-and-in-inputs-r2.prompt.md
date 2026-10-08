Created-GMT: 2026-09-29 17:45:46 GMT
Created-Local: 2026-09-30 00:45:46 +07 (+0700)
Coding-Agent: claude
Session-ID: 5939e397-ce71-4ffa-8bfa-9f5ba06d2a83 (resumed)
# Task: q bugs round 2 — implement bare-symbol CONSTANT semantics (owner decision)
Role: QA / Query Engineer
Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-09-30 00:45:46 +07 (+0700) | Status: active | Rationale: same session

OWNER DECISIONS (verbatim selected options):
- BUG 1: "(a) Constant — Datomic-like: only ?-symbols and _ are variables; `inc` matches the symbol inc. Needs the same
  rule decided for function-clause and rule arguments; the spec (dao.space.query.md) gets updated."
- BUG 2: "Keep implicit $ (Recommended) — Matches the design's 'the index is implicit'." (your fix stands)

Implement BUG 1 in src/cljc/dao/space/query.cljc at the cause (unify ~981 / resolve-binding ~935 classification):
- In data patterns: only symbols starting with ? are variables; _ is the blank; $-prefixed symbols keep their source
  meaning; every other symbol is a constant matching that symbol value.
- Apply the same rule to ARGUMENTS of function/predicate clauses and rule invocations (bare symbol argument = constant).
  The function-name position and rule-name position keep their current meaning.
- If any existing test, rule set, or caller in the repo relies on a bare symbol acting as a variable (grep src and
  test), STOP and report each instance instead of changing it.
- Your 5 waiting BUG 1 tests must now pass (the refusal alternative in them can be dropped); add tests for a constant in
  a function-clause argument and a rule argument, and a regression that ?-variables and _ behave exactly as before.
- Update docs/design/dao.space.query.md's Datalog surface section (~346-407) to state the rule.
Allowed files this round: src/cljc/dao/space/query.cljc, test/dao/space/query_test.cljc, test/yin/repl/query_test.cljc,
docs/design/dao.space.query.md. Do not stage or commit. Prove by temporary mutation (revert, grep).

Verify and report: kondo; cljstyle check (say if blocked); focused JVM dao.space.query-test, yin.repl.query-test,
yin.repl.index-test, yin.repl-test; the full clj -M:test (other dao.space / yin callers of q must still pass); bb
test:cljs. Not bb test:cljd. Write collab/1790702695000-qa-engineer-query-symbol-constant-and-in-inputs.claude-opus-5-5.report-r2.md
and give it as your final response, beginning with the Completed-GMT / Completed-Local / Coding-Agent / Session-ID lines.
