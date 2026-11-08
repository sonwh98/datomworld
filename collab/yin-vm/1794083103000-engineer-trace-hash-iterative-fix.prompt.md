Created-GMT: 2026-11-08 01:2x GMT
Coding-Agent: qwen
Session-ID: eed46c36-b786-431d-9b4c-df16b62f031e

# Task: fix the recursion reds — engine/trace must not structurally hash Continuations

Role: Yin VM Engineer (implementation; the audit is final evidence)

Implementers:
- Model: qwen3.8-max | Assigned: 2026-11-08 | Status: active

## Root cause (audit r2, FINAL evidence — read fully)

collab/yin-vm/1794071929000-engineer-cesk-stack-audit-r2.qwen3.8-max.findings.md

- All four parity runners are CESK; the guest 1000-frame guard fires correctly.
- The V8 "Maximum call stack size exceeded" comes from STRUCTURAL HASHING of
  deep records: values.cljc:71 (Closure -hash) / values.cljc:107 (Continuation
  -hash) → (hash [kind owner payload]) recurses through the whole 1000-link
  kont chain + envs + AST bodies (13,542-frame trace, 100% hash frames).
- The trigger is engine/trace's seen-set (engine.cljc:2516/2537) reached via
  engine/collect from count-allocation on cell/new — telemetry hashing each
  step's continuation to dedupe.

## Fix direction (implement this; escalate ONLY if evidence contradicts)

Make engine/trace's seen-dedup NOT rely on structural hashing of deep VM
records. Preferred: key the seen set on IDENTITY the VM already has — the
id-counter/unique id on each record (ast-walker's id-counter; each variant's
equivalent) or the record object itself via a mutable-identity marker — NOT
(hash deep-structure). If a variant lacks ids on the relevant records, add a
lightweight unique id at construction (the records are created once per step;
an integer field is cheap). Do NOT change the -hash SEMANTICS of Closure/
Continuation for general use in this slice unless the audit's fix-direction
paragraph says otherwise — read it and follow it. Keep telemetry output shape
compatible with its consumers (grep the telemetry stream consumers).

## Where

Work in the git worktree /tmp/nrf-branch-wt (branch nightly-reds-fix @
e0fa8b0a — git pull first). Files in scope: src/cljc/yin/vm/engine.cljc,
src/cljc/yin/vm/values.cljc (ONLY if adding id fields), src/cljc/yin/vm/ast_walker.cljc
and sibling VM namespaces (only for id fields). Nothing else; no commits.

## Verification (scoped, failing tests only)

On shiva (~/ci/datomworld-nightly-node — ANOTHER SEAT'S UNCOMMITTED EDITS LIVE
THERE: shadow-cljs.edn stack-size 7168, dht.cljc repair-slots 40 — leave them;
your diff touches engine/values so recompile):
apply diff, eval "$(mise env -s bash)",
clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge "{:autorun false}",
then DATOM_SLOW_TESTS=1 node target/node-tests.js
--test=yang.python.antlr.safepoint-test/recursion-error-test,yang.python.antlr.safepoint-test/generator-rebase-test,yang.python.antlr.linked-safepoint-test,stream-eval-ns-if-needed
→ all green; also --test=yang.typescript.antlr.string-forms-test (the uncovered 3E family).
Cross-check jupiter once. Report diff --stat + both outputs verbatim. No commits.

Begin the final response exactly with Completed-GMT / Completed-Local.
