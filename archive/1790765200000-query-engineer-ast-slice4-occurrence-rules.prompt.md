# Brief: $ast slice 4 — pure-data occurrence rules + `subvec` builtin

Role: Engineer (docs/agents/roles/). Work in /Users/sto/workspace/datomworld on master. Do not commit.

Authority:
- Architect ruling: collab/1790764371000-architect-repl-free-variable-rules.claude-fable-5-1.findings.md. Implement §1, §2 (docs only), §3 (guard) and §4 exactly.
- Owner accepted all four §5 recommendations: collab/1790765200000-orchestrator-free-variable-rules-owner-decisions.md.

Scope (the files in the ruling's §4 table only):
- src/cljc/dao/space/query.cljc
- src/cljc/yin/vm.cljc
- src/cljc/yin/vm/linker.cljc
- src/cljc/yin/vm/completion.cljc
- src/cljc/yin/repl/query.cljc (docstring only)
- test/dao/space/query_test.cljc
- test/yin/repl/ast_query_e2e_test.cljc
- test/yin/repl/query_test.cljc (the §3 guard, only if it is not already present)
- docs/design/yin.vm.code-as-tuples.md §4.5, §6.3
- docs/design/yin.vm.dependency-completion.md

Also update any other test that uses the deleted `occurrence-fns`, `p-up` or `occ-anc`, and say which ones in your report.

Rules:
- Write tests first and show that they fail.
- CLJC portability:
  - In mixed reader conditionals, put `:cljd` FIRST.
  - No array-map. No cross-namespace #'private.
  - Refusal helpers return the error object; the call site applies ex-message (the shadow-cljs fold trap).
- The `subvec` result must be `vector?`, `content=` a plain vector, and encode as a CBOR vector on every host.
- Refusal text must be identical on all hosts.
- The REPL e2e tests use real input lines on all four VMs, per §4 acceptance item 4. That includes the rule set defined by the user's own `(def …)` line.
- Run every check in the FOREGROUND, per docs/agents/build-n-test.md:
  - `clj -M:kondo --lint` on the changed files
  - focused JVM
  - full `clj -M:test`
  - `bb test:cljs` (confirm "Testing <ns>" appears for the touched namespaces)
  - `bb test:cljd`

Report: collab/1790765200000-query-engineer-ast-slice4-occurrence-rules.<model>.report.md
- Map each acceptance item (§4, 1–6) to its evidence.
- Give at least one mutation per core property and show the test fails.
- Give the lane counts.

## Scope extension (2026-09-30 20:40 +07, orchestrator)
The Dart lane showed that the CLJD REPL reader (`src/cljc/yin/repl.cljc` ~846, `edn/read-string` on CLJD only) drops `%` from `:in $ast $occ % ?root`. Architect acceptance item 4 requires that `%`, `...` and `_` survive the reader on every host, so fixing this is in scope now.
- Add `src/cljc/yin/repl.cljc` (the input reader only) to the allowed files.
- Fix the reader so it produces the same forms as JVM and Node for `%`, `...` and `_`.
- Add a focused reader test that fails on CLJD before the fix. Keep it portable, and put `:cljd` FIRST in any conditional.
Housekeeping: move your scratch files (collab/slice4-*.log, collab/slice4-mutate.py) to target/; collab/ holds only briefs, reports and logs.
Re-run every lane one at a time, and wait for the Dart verdict before reporting. Report: ...claude-opus-5-5.report.md.

## Fix round 1 (2026-09-30 22:10 +07, orchestrator): Architect sign-off findings
gpt-6-sol WITHHELD its sign-off: collab/1790774000000-architect-ast-slice4-signoff.gpt-6-sol.findings.md. The occurrence rule and subvec parts are approved; the findings are all in the Dart reader workaround.
1. HIGH | yin/repl.cljc ~879 | A `%` right after a reader macro (e.g. `#_%`) escapes the scanner. Tokenize reader macros correctly. Add Dart tests for adjacent discard, tagged, regex and anonymous-function forms.
2. HIGH | ~880 | A distinct placeholder per occurrence breaks duplicate detection: `#{% %}` and `{% 1 % 2}` collapse on restore instead of being refused by the reader. Use one placeholder per identical token, so reader duplicate semantics are preserved. Add refusal tests that match the JVM and Node refusal behaviour.
3. MEDIUM | ~899 | The restore rebuilds EVERY map with `into {}`, which changes array-map type and order, even in unrelated maps. Rebuild only the branches that contain placeholders, preserving each collection's type, order and metadata. Gate the whole workaround on the input actually containing a bare `%` token.
4. LOW | docs/design/yin.vm.code-as-tuples.implementation-plan.md:557 | Mark the p-up/occ-anc text as historical (superseded by the fable ruling).
Write each test first and show it FAILS on Dart before the fix. Include a cross-host test that the same input lines read to identical forms on JVM, Node and Dart. Re-run every lane and see each verdict.
Report: ...claude-opus-5-5.report-r2.md

## Fix round 2 (2026-09-30 23:10 +07, orchestrator): r2 sign-off findings
gpt-6-sol WITHHELD its sign-off: collab/1790774000000-architect-ast-slice4-signoff-r2.gpt-6-sol.findings.md. Ruling on duplicates: keep each host reader's own behaviour (the current state), with no reader-wide refusal.
1. HIGH | yin/repl.cljc ~919 | Restore uses only `(name token)`, so a qualified symbol such as `%/foo` loses its namespace. Restore the namespace and the name. Keep syntax-quote qualification only for tokens that were originally unqualified. Test both forms.
2. HIGH | ~914 | Restore never visits metadata, so `^% x` and `^{:tag %} x` keep the placeholder in `x`'s `:tag`. Restore placeholders inside metadata as well as values, preserving the collection type and the reader metadata. Add Dart parity tests.
Before you finish, enumerate systematically every reader position where a symbol can appear:
- values
- metadata
- map keys
- set elements
- tagged-literal forms
- syntax-quote and unquote
- namespaced maps `#:ns{}`
- reader conditionals, if they are enabled
Add a parity test for each position. The Architect keeps finding one position at a time.
Write each test first and show it fails on Dart before its fix. Re-run every lane and see each verdict. Report: ...report-r3.md
