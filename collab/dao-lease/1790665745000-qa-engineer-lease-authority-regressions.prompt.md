Created-GMT: 2026-09-29 07:09:05 GMT
Created-Local: 2026-09-29 14:09:05 +07 (+0700)
Coding-Agent: claude
Session-ID: 067152df-fd04-4aff-859e-8aa524ba524d
# Task: Lease source-authority regression tests (Architect post-epic ruling, item 2)

Role: QA Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-09-29 14:09:05 +07 (+0700) | Status: active | Rationale: test-only follow-up from the Architect's ruling

Work in /Users/sto/workspace/datomworld (master 3cf3c6de). TESTS ONLY: no production code changes anywhere. Do not
stage or commit. Another delegate is concurrently editing yin.repl files and main_test.cljc; do not touch them, and do
not touch docs/orchestrator-log.md.

Ruling (collab/1790665619000-architect-post-epic-queue.gpt-6-sol.findings.md, item 2): DON'T add a blanket dao.lease
refusal of :self-sourced media (a judge may read its own grantor-authored ledger); keep the refusal at the composition
boundary. Its acceptance: "Test that a judge can wire and read its own grantor-authored medium, that remote-serve
refuses an inbound medium with :source equal to ::grantor, and that renewal media remain attributed to their holder."

Task: first check which of these three already exist (the 3b grantor-source refusal test in
test/yin/vm/ffi/remote_serve_test.cljc; grantor-sourced fact media in test/dao/lease_composition_test.cljc ~115; renewal
attribution in the remote-serve tests). Add ONLY what is missing, as focused deftests with names that state the
invariant, in test/dao/lease_composition_test.cljc (or test/dao/lease_test.cljc) and test/yin/vm/ffi/remote_serve_test.cljc.
For each, state in the report whether it was new or already covered (cite the existing test). Prove each new test
bites by a temporary mutation of the production code, then revert and grep (git diff -- src must be empty at the end).

Verify and report: kondo on changed files; focused JVM clj -M:test -n dao.lease-test -n dao.lease-composition-test -n
yin.vm.ffi.remote-serve-test; bb test:cljs. Not bb test:cljd. Write the report to
collab/1790665745000-qa-engineer-lease-authority-regressions.claude-opus-5-5.report.md and give it as your final response,
beginning with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 067152df-fd04-4aff-859e-8aa524ba524d
