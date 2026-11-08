Created-GMT: 2026-11-08 01:1x +07
Coding-Agent: qwen
Session-ID: 83fe3ba5-f28a-4cbb-90d8-ada511e61071

# Task: CESK stack audit r2 — find the native recursion (findings incorporated)

Role: Yin VM Engineer (read-only audit + one authorized reproduction)

Implementers:
- Model: qwen3.8-max | Assigned: 2026-11-08 | Status: active | Rationale: r2 of the audit; orchestrator findings incorporated, scope narrowed

## ESTABLISHED FINDINGS (verified this session; build on them, do not re-derive)

1. ALL FOUR parity runners are CESK machines — source-verified: the ast-walker's
   own docstring says "A CESK machine over raw AST maps with a linked
   continuation" (src/cljc/yin/vm/ast_walker.cljc:1-16); its ASTWalkerVM record
   carries k/control/store/ready-queue (store-allocated, explicitly stepped).
   The runner map: test/yang/python/antlr/safepoint_test.cljc:73-90
   (:ast-walker :semantic :stack :register).
2. The harness catches any throw per-runner into [:thrown message]
   (on-every-vm, safepoint_test.cljc:87-94) — the failure output "[:thrown
   \"Maximum call stack size exceeded\"]" under the :ast-walker key means the
   throw happened INSIDE that runner's (f (run ast prep)) — during run, NOT in
   the comparison after it.
3. The guest program: programs/recursion, expected f(999) completes with :py/out
   ["0" "rec" "1000"], f(1000) raises the guest's 1000-frame RecursionError
   (safepoint_test.cljc:476-483). On shiva/jupiter the V8 throw wins instead.

## YOUR TASK: name the native-recursing function with file:line

Candidates on the failing path, ranked by the orchestrator (verify/refute each):

A. THE HARNESS ITSELF (owner's suspicion): hooked → derived-ast → the runner's
   prep/eval path in safepoint_test.cljc and test utils (tu/create-vm, the
   loader the walker's eval uses, run-on-stream / dao.stream.observer pump,
   vm-load-program). Check whether the walker's eval entry
   (src/cljc/yin/vm/ast_walker.cljc eval / load / run-on-stream) or the stream
   pump recurses per batch/step.
B. render/output (src/cljc/yin/vm/render.cljc or similar): traversal of the
   1000-entry :py/out value — recursive or loop?
C. The output primitive the guest program uses to build :py/out (accumulating
   print): native recursion per call or store-mediated?
D. The serializers fix 2's comment named (continuation serialization in
   shadow-cljs.edn :prepend context / wherever konts get serialized).
E. CLJS core functions on deep data inside run (e.g. = or conj on a deep
   structure built DURING the run — anything comparing/printing inside the
   step loop).

METHOD: read the code paths first. If reading does not settle it, run the ONE
authorized reproduction on shiva (do not modify the clone — another seat's
uncommitted edits live there; the bundle is compiled):
  cd ~/ci/datomworld-nightly-node && eval "$(mise env -s bash)" &&
  DATOM_SLOW_TESTS=1 node target/node-tests.js --test=yang.python.antlr.safepoint-test/recursion-error-test
Capture the FULL JS stack trace (V8 truncates "Maximum call stack size
exceeded" traces — if unhelpful, build a standalone /tmp reproduction instead
of repo edits). The trace NAMES the recursing function — that is the
deliverable.

Constraints: read-only except the reproduction. No commits. Begin the final
response exactly with Completed-GMT / Completed-Local. Report: the named
culprit function(s) with file:line and the trace evidence, each candidate
A-E confirmed/refuted, and (if the culprit is confirmed) a one-paragraph
proposed fix direction (make it iterative: what loop replaces what recursion)
— implementation is a separate round.
