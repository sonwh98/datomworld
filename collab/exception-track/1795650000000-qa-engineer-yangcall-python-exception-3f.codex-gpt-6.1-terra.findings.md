# Python exception 3F attribution — STOP, contract decision needed

Base: `a95c4cfd889a5c8d53932ccd1dad38127b584313` (`origin/master`, fetched); host: shiva. No production or test changes. The dispatch identified jupiter, but this worktree runs on shiva.

The three recorded failures are the same `nil` case across VMs. Their immediate cause is a production behavior/expectation mismatch, but classification between (a) stale expectation and (c) behavioral defect is **not established by the cited contract**. Treat as a possible (c) and STOP; do not silently bless either choice. No address appears in these assertions, so there is no evidence of (b) pin drift. A paired order experiment was not run and (d) is not asserted.

| Row | Attribution | Evidence |
| --- | --- | --- |
| `python-malformed-condition-test`, nil, register | Unresolved (a)/(c); possible production defect | `test/yang/call/python_exception_test.clj:118-147` injects `nil`, catches `TypeError`, and expects its args to contain `nil`. `src/cljc/yang/python/antlr/prelude.cljc:1064-1067` passes that value to `py/make-exc`; `:741-750` interprets host `nil` as the no-message sentinel and makes `[]`. |
| Same, semantic | Same | Same code and assertion, independent VM observation. |
| Same, stack | Same | Same code and assertion, independent VM observation. |

`docs/design/yang.antlr.md:3847-3866,3881-3883` specifies fresh thrown conditions and retention of *valid* protocol failures, but does not prescribe the `args` of the fresh error for an invalid, non-map condition. The landed precedent `collab/exception-track/1794483001000-to-yangphp-addendum-nil-guard-precedent-landed.md:9-15` requires a fresh TypeError and no provenance for non-map/nil, without specifying its args. Both `[]` and `[nil]` meet that narrow stated requirement. The test and fallback were introduced together in `7f10befa`; the no-message sentinel predates them (`60cda9b6`). A ruling is needed on whether malformed nil is retained as an argument. If yes, route production repair; if no, explicitly authorize the expectation correction. `:garbage` remains a separate passing case and must remain unchanged.

## Commands and evidence

- `bb gen:antlr`: rc=0 (worktree-local generation).
- `DATOM_SLOW_TESTS=1 clojure -M:test -n yang.call.python-exception-test`: rc=1; Ran 10 tests containing 127 assertions. 3 failures, 0 errors. Full log: `/tmp/yangcall-before.log` on shiva; failures reproduced verbatim below.
- `clojure -M:test -n yang.call.exception-contract-test`: interrupted deliberately, rc=130, no Ran summary; this namespace includes slow cross-frontend tests.
- `clojure -M:test -n yang.call.conformance-test`: rc=0; Ran 3 tests containing 18 assertions. 0 failures, 0 errors.
- No touched Clojure files; clj-kondo not applicable.

## Reproduced failing assertions, verbatim

```text
FAIL in (python-malformed-condition-test) (python_exception_test.clj:146)
 :register
expected: (= {:reason :yang.call/thrown, :payload [malformed], :provenance nil} actual)
  actual: (not (= {:reason :yang.call/thrown, :payload [nil], :provenance nil} {:reason :yang.call/thrown, :payload [], :provenance nil}))

FAIL in (python-malformed-condition-test) (python_exception_test.clj:146)
 :semantic
expected: (= {:reason :yang.call/thrown, :payload [malformed], :provenance nil} actual)
  actual: (not (= {:reason :yang.call/thrown, :payload [nil], :provenance nil} {:reason :yang.call/thrown, :payload [], :provenance nil}))

FAIL in (python-malformed-condition-test) (python_exception_test.clj:146)
 :stack
expected: (= {:reason :yang.call/thrown, :payload [malformed], :provenance nil} actual)
  actual: (not (= {:reason :yang.call/thrown, :payload [nil], :provenance nil} {:reason :yang.call/thrown, :payload [], :provenance nil}))
```
