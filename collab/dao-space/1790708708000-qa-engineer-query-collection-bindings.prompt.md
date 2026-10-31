Created-GMT: 2026-09-29 19:05:08 GMT
Created-Local: 2026-09-30 02:05:08 +07 (+0700)
Coding-Agent: claude
Session-ID: dc7eb4bb-63f0-4a22-93c8-972bbef01e47
# Task: dao.space.query — collection and relation binding forms for function-clause results

Role: QA / Query Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-09-30 02:05:08 +07 (+0700) | Status: queued (dispatch after host-fn rendering commits) | Rationale: OWNER INSTRUCTION (verbatim) "queue collection bindings after this"

Work in /Users/sto/workspace/datomworld (main tree, on top of the host-fn rendering commit). Do not stage or commit.
Test first: write the exposing tests, run them on the unmodified source and record the failures, then implement.

Problem (orchestrator-reproduced): in a yin.repl session, an application stores :yin/operands as ONE vector value
(e.g. [21 :yin/operands [23 24]]), so walking operands needs a function-clause binding. A tuple binding works:
  [(identity ?ops) [?lit ?fn]]  => binds positionally
but a collection binding fails:
  [(identity ?ops) [?arg ...]]  => "Unsupported binding form — only a scalar ?out or tuple [?a ?b]"
(src/cljc/dao/space/query.cljc ~1318). :in already supports tuple, collection and relation binding forms (spec,
docs/design/dao.space.query.md ~362-370).

Task: support, for function-clause results, the same binding forms :in already supports, with Datomic semantics:
- collection  [?x ...]       -> one binding per element;
- relation    [[?a ?b]]      -> one binding per tuple;
- tuple       [?a ?b]        (existing; keep behaviour, incl. arity handling);
- scalar      ?x             (existing).
Reuse the :in binding-form machinery rather than a second implementation if the code allows. Follow the owner's
term rule already in the spec (only ?-symbols are variables, _ is blank; bare symbols are constants — a bare symbol in a
result binding stays a constant comparison). Empty collection -> no bindings (the clause filters the row out). A
non-sequential result for a collection/relation form -> clear query error, not a silent match. Update
docs/design/dao.space.query.md to state that function-clause results accept the same binding forms as :in.

Acceptance tests (host, in test/dao/space/query_test.cljc; plus one REPL-level test in test/yin/repl/query_test.cljc):
[(identity ?ops) [?arg ...]] over a yin/def application yields both operands; a relation binding; empty collection;
non-sequential result error; _ inside collection/relation forms; existing tuple/scalar behaviour unchanged; at REPL
level, "all literal operands of yin/def" after (defn inc [i] (+ i 1)) returns inc. Prove key ones by temporary
mutation (revert, grep). Portable CLJC (on CLJD #?(:clj ...) is NOT excluded — #?(:cljd nil :clj ...) with :cljd first).

Allowed files: src/cljc/dao/space/query.cljc, test/dao/space/query_test.cljc, test/yin/repl/query_test.cljc,
docs/design/dao.space.query.md. Anything else: STOP and report.

Verify and report: kondo; cljstyle check (say if blocked); focused JVM dao.space.query-test, yin.repl.query-test,
yin.vm.linker-test; full clj -M:test; bb test:cljs. Not bb test:cljd. Write the report to
collab/1790708708000-qa-engineer-query-collection-bindings.claude-opus-5-5.report.md and give it as your final response,
beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: dc7eb4bb-63f0-4a22-93c8-972bbef01e47
- Status-Event: 2026-09-30 02:54:04 +07 | Model: claude-opus-5-5 | Status: active | Rationale: dispatched from queue after host-fn rendering committed b9665935 (main tree, master b9665935)

## Round 2 (2026-09-30 10:04:20 +07, orchestrator) — OWNER DECISIONS on your two open points
Owner, verbatim selected options:
- nil result: "Empty (no bindings) — nil means 'nothing to bind' — the row is filtered out, like an empty collection.
  Friendlier for fns that return nil."
- maps/sets: "Allow sets — Sets bind one row per element (Datalog results are sets anyway, so order doesn't matter);
  maps still rejected."
Implement both for collection AND relation forms (a set of tuples binds one row per tuple; a nil result filters the
row), keep maps (and other non-sequential, non-set values) as a clear error. Update the exposing tests first (record
the failures on the current code), then the code, then docs/design/dao.space.query.md. Keep ordering irrelevant (the
result is a set); if any test asserted order, make it order-independent. Run IN THE FOREGROUND: kondo; focused JVM
(dao.space.query-test, yin.repl.query-test, yin.vm.linker-test); bb test:cljs; bb test:cljd. Report as report-r2.md.
