Created-GMT: 2026-09-29 18:18:40 GMT
Created-Local: 2026-09-30 01:18:40 +07 (+0700)
Coding-Agent: claude
Session-ID: 5939e397-ce71-4ffa-8bfa-9f5ba06d2a83 (resumed)
# Task: q bugs round 3 — gate P1/P2 and owner decisions (map inputs, reject bare symbols in :in/:find)
Role: QA / Query Engineer
Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-09-30 01:18:40 +07 (+0700) | Status: active | Rationale: same session

Gate (collab/1790705724000-reviewer-query-symbol-constant-and-in-inputs-gate.gpt-6-sol.findings.md) withheld sign-off.
OWNER DECISIONS (verbatim selected options):
- Map inputs: "Support via :in arity (Recommended) — Count declared :in patterns: that many args are inputs (maps
  allowed), one more trailing map is options. Removes the ambiguity; small change in yin/repl/query.cljc."
- Bare symbols: "Reject now (Recommended) — A bare symbol in :in or :find raises a clear query-failed error instead of a
  silently unused input or nil column; add to this fix round and the spec."

Do, test-first as before (write the failing tests on the current code, record the failures, then fix):
1. P1 | src/cljc/yin/repl/query.cljc ~309 split-args: use the declared :in arity (after the implicit $) to split user
   inputs from an optional trailing options map; a declared map input (e.g. :in ?m with {:k 1}) reaches the engine;
   the options map, when present, is validated as before; wrong arity -> :yin.repl.query/query-failed.
2. Owner "reject now": in src/cljc/dao/space/query.cljc, a bare (non-?, non-_, non-$, non-%) symbol in :in or :find is
   an invalid query declaration with a clear error (surfacing through the bridge as :yin.repl.query/query-failed).
   Leave function result bindings as the gate described (a bare symbol there acts as a constant comparison under the
   owner's term rule) and state that behaviour explicitly in the spec. Update docs/design/dao.space.query.md.
3. P2 | test/yin/vm/linker_test.cljc (~1338): add an assertion that (+ 'k 2) produces NO definition, pinning the
   corrected linker.cljc:334 [?op :variable yin/def] behaviour. Test only; do not edit linker.cljc.
Allowed files: src/cljc/dao/space/query.cljc, src/cljc/yin/repl/query.cljc, test/dao/space/query_test.cljc,
test/yin/repl/query_test.cljc, test/yin/vm/linker_test.cljc, docs/design/dao.space.query.md. Do not stage or commit.
Prove by temporary mutation (revert, grep).

Verify and report: kondo; cljstyle check (say if blocked); focused JVM dao.space.query-test, yin.repl.query-test,
yin.vm.linker-test, yin.repl-test; the full clj -M:test; bb test:cljs. Not bb test:cljd. Write
collab/1790702695000-qa-engineer-query-symbol-constant-and-in-inputs.claude-opus-5-5.report-r3.md and give it as your
final response, beginning with the Completed-GMT / Completed-Local / Coding-Agent / Session-ID lines.
