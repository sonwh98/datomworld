Created-GMT: 2026-11-02 03:05:00 GMT
Created-Local: 2026-11-02 10:05:00 +07 (+0700)
Coding-Agent: codex
Session-ID: pending (provider-generated; capture thread.started.thread_id)

# Task: F12 — the two diagnosis slices (F1 dht-import :importers; F2 vm-rebuild :register/:semantic) plus the compile-contract hardening

Role: Yang Compiler and Universal AST Engineer
Implementers:
- Model: codex gpt-6-luna | Assigned: 2026-11-02 10:05 +07 | Status: active | Rationale: three bounded diagnosis/fix rounds in the Python frontend's own test and fixture surfaces

WORKTREE: /Users/sto/workspace/datomworld-f12 (detached at origin/master; parser built, node_modules
installed). Commit to a new branch `f12-diagnosis-fixes`. You are the only writer.

## Known-gaps rows driving this round (docs/agents/known-gaps.md):

1. **F1 — `dht-import-test` fails on Node and Dart**: the test's own publication differs from the JVM
   artifact in the `:importers` key — JVM passes. Surfaced by the C3 completion run; the C4
   publication area, not a C3 detector.
2. **F2 — `vm-rebuild-is-a-new-process-test` (yin.repl.python-antlr-test) fails on Node and Dart**:
   expected `:register`, got `:semantic` (the VM-type default); JVM passes. Surfaced by the same run.
3. **The compile-contract test hardening** (the closed compile-gap row's follow-up): the contract test
   is a proxy — add a submitted-content assertion and a host-neutral `:incomplete` probe test.

## What to do, in order:

### F1 diagnosis (dht-import :importers)
- Reproduce on Node: `bb test:cljs` (or the scoped Node lane) — capture the actual `:importers` key
  the Node run produces vs the JVM artifact's.
- Read the DHT import path for host-conditional code: does the Node/Dart leg build a different
  importers set (a platform conditional, a set of principals, an ordering difference)? The fixture
  is cljc and shared; the divergence is in the publication path the TEST exercises.
- Diagnose the root cause with file:line. If it is a genuine product defect in the DHT import path
  (the Node/Dart publication is wrong), fix it in `yang/python/**` scope if the fix lives there; if
  it lives in dao.stream/dao.jing or the linker, STOP and report with evidence (out of scope).
- If it is a test-fixture expectation that needs updating (the artifact is correct and the test's
  expected :importers set is stale), fix the expectation and say so explicitly.

### F2 diagnosis (vm-rebuild :register/:semantic)
- Reproduce on Node and Dart: the test expects `:register` as the VM-type after a rebuild; Node/Dart
  get `:semantic`. Read the rebuild path: does the VM-type default differ per host legitimately, or
  is this a genuine defect (a host-conditional default that shouldn't exist)?
- The C4 acceptance required parserless forms on Node and Dart — a `:semantic` default there may be
  CORRECT (parserless forms run on the semantic VM by design; `:register` is a JVM-luxury). Determine
  which, and fix the TEST expectation if the current behavior is correct-by-design; fix the product
  default if the design says all hosts default to :register. Cite the design section.

### The compile-contract hardening
- The contract test (test/yang/python/antlr/e2e_test.cljc or its host — the closed compile-gap row's
  follow-up): add the submitted-content assertion (the compiled artifact contains the submitted
  form's own identifiers) and a host-neutral `:incomplete` probe test (an unclosed paren yields
  `:incomplete`, not a host error) — per the closed row's follow-up note.

## Verification bar (your scope; the orchestrator re-runs remotely)

The focused namespaces for each fix on THIS machine, one at a time (laptop-unload rule: NO suite
runs, no slow tier, nothing that indexes the full prelude). kondo 0/0 + cljstyle on changed files.
The full tri-host lanes are the orchestrator's.

## Constraints

- Scope: `src/cljc/yang/python/**`, `test/yang/python/**`, `test/yin/repl/python_antlr_test.cljc`.
  NOT dao.stream, NOT yin.vm, NOT the linker — a fix that lands there is out of scope; STOP and
  report with evidence.
- Do not weaken or delete tests. If an expectation is stale against correct behavior, say so
  explicitly with the reason.
- Commit to `f12-diagnosis-fixes`. Do NOT push; do NOT touch master.
- Single turn. End with: the F1 root cause and fix (file:line), the F2 root cause and fix (file:line),
  the compile-contract hardening, per-namespace verification counts, and anything open.
