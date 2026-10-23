Created-GMT: 2026-10-21 02:15:00 GMT
Created-Local: 2026-10-21 09:15:00 +07
Coding-Agent: claude
Session-ID: fresh

# Task: Address Codex Architectural Review Findings on Slice H0
Role: Implementation Engineer
Model: claude-opus-5-5

Working Directory: /Users/sto/workspace/datomworld/.claude/worktrees/host-h0
Branch: host-h0
Governing Document: /Users/sto/workspace/datomworld/collab/1792527800000-reviewer-host-boundary-h0.gpt-6-astra.findings.md

Codex Review Findings to Fix:

## 1. H0-R1 (High): Function-body vectors skipped
- In `test/dao/host/gate_test.clj:423-439` (`walk-fn`):
  The scanner currently treats every top-level vector in a function tail as a parameter vector and drops them from traversal.
  Body vector expressions (e.g. `(defn injected [] [(random-uuid)])`) must be scanned as executable forms.
  Separate the function parameter vector from body expressions, and walk every executable body expression.
  Add regression test asserting violation when an effect call is inside a body vector.

## 2. H0-R2 (High): Lexical scopes merged across arities and host lanes
- In `walk-fn`: Multi-arity function definitions union parameter names across all arities before scanning any body (e.g. `([random-uuid] ...) ([] (random-uuid))`). Keep bindings strictly local to each arity.
- In `all-arms` / `file-scope`: Scanner collects definitions from all reader arms into one shadow set. Resolve bindings/definitions per lane so that a `#?(:cljs ...)` shadow does not suppress an effect violation on `:clj`.
- Add cross-arity and cross-lane regression tests.

## 3. H0-R3 (Medium): Real file-read failure handling
- In `test/dao/host/gate_test.clj:714-722` (`read-sources`):
  Wrap `slurp` so that IO/permission exceptions become `:scan-failure` with file diagnostic, rather than escaping unhandled.
  Add regression test.

## 4. H0-R4 (Medium): Subsystem registration
- Register `dao.host` subsystem in `src/dev/subsystems.edn` mapping to `dao.host.*` tests so `bb test:sub:clj dao.host` runs cleanly.

## 5. Verification:
- Run `clojure -M:test -n dao.host.gate-test`
- Run `bb test:sub:clj dao.host`
- Verify negative probes pass.

Write report to:
/Users/sto/workspace/datomworld/collab/1792528500000-engineer-host-boundary-h0-fixes.claude-opus-5-5.findings.md
