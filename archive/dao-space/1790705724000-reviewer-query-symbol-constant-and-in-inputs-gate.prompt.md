Created-GMT: 2026-09-29 18:15:24 GMT
Created-Local: 2026-09-30 01:15:24 +07 (+0700)
Coding-Agent: codex
Session-ID: 01a0ee61-080c-7012-a2ec-1c2d3431fcd7 (captured)
# Task: Gate — q bare-symbol constants (dao.space.query) + :in inputs through the yin.repl q bridge

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Model: gpt-6-sol | Assigned: 2026-09-30 01:15:24 +07 (+0700) | Status: active | Rationale: standing gate route; Claude-authored (claude-opus-5-5)

Read-only review in /Users/sto/workspace/datomworld (master ba65a4c0, uncommitted). Do not edit. Change under review:
git diff -- src/cljc/dao/space/query.cljc src/cljc/yin/repl/query.cljc test/dao/space/query_test.cljc
test/yin/repl/query_test.cljc docs/design/dao.space.query.md.

OWNER INSTRUCTIONS/DECISIONS (verbatim): "dispatch fixes for both bugs"; "first write a test to expose the bug";
BUG 1 "(a) Constant — Datomic-like: only ?-symbols and _ are variables; `inc` matches the symbol inc. Needs the same
rule decided for function-clause and rule arguments; the spec (dao.space.query.md) gets updated."; BUG 2 "Keep implicit
$ (Recommended) — Matches the design's 'the index is implicit'."
Brief + round 2: collab/1790702695000-qa-engineer-query-symbol-constant-and-in-inputs.prompt.md, ...-r2.prompt.md.
Reports (untrusted): ...claude-opus-5-5.report.md, report-r2.md. Test-first evidence: the exposing tests ran on
untouched src with 25 failures (BUG 1 host+4 VMs; BUG 2 20).

Root causes claimed: BUG 1 — dao.space.query unify (~981) treated every non-_ symbol as a variable and
resolve-binding (~935) made an unbound one a wildcard. BUG 2 — the engine binds the index to $ by default only when :in
is absent (~1613); the bridge passed the index as the first input, so :in ?f bound the index to ?f and the user input
became the options argument; fixed by always prepending $ (with-index / in-patterns) and refusing wrong input arity.

Orchestrator-verified on this exact tree (do not rerun): kondo 0 errors (4 warnings on existing repl/query-* refs in old
tests); cljstyle clean; focused dao.space.query + yin.repl.query + yin.vm.linker 135 / 832 / 0; full JVM
2368 / 184358 / 0; Node 2273 / 50838 / 0; CLJD 2235 all passed.

Check correctness, that ?-variables, _, $-sources, % and fn/rule-name positions are unchanged, spec accuracy, and that
the tests pin both bugs. Rule explicitly on:
Q1. Behaviour change in src/cljc/yin/vm/linker.cljc:334 [?op :variable yin/def]: under the old semantics yin/def was a
    variable, so any two-operand application with a literal first operand counted as a definition ((+ 'k 2) -> [k]); now
    only (yin/def 'k 2). The docstring intended the constant. Correct fix, and does the linker need a regression test
    pinning it (linker.cljc/test were outside the implementer's files)?
Q2. A bare symbol after :in, in :find, or as a result binding (e.g. [(f ?a) out]) now silently does nothing. Should
    those positions refuse bare symbols (and is that required now or a follow-up)? A bare symbol in a rule head now
    throws "head var not bound" — acceptable?
Q3. Implicit $ with user :in patterns: correct and complete (history view + inputs, arity errors)?

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Findings as P0-P3 | file:line | evidence | concrete fix, or "No actionable findings". Answer Q1-Q3; mark owner decisions.
End with Verdict: READY / REQUEST CHANGES and Sign-off: GRANTED / WITHHELD.
