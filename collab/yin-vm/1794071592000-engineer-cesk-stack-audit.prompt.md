Created-GMT: 2026-11-08 01:0x +07 (see header fields for exact values)
Coding-Agent: qwen
Session-ID: d4e26585-3e49-4366-9162-c6c3e7feccff

# Task: audit — is every VM variant a CESK machine that cannot throw a native stack exception?

Role: Yin VM Engineer (read-only audit + evidence capture; NO edits)

Implementers:
- Model: qwen3.8-max | Assigned: 2026-11-08 (exact local timestamp below) | Status: active | Rationale: VM-internal invariant question; needs code-level tracing of each vm-type's step model and the host-side helpers around them

## The question (owner-directed, decide with evidence not opinion)

The master nightly's recursion red (yang/python/antlr/safepoint_test.cljc:156,
recursion-error-test, :ast-walker context) shows V8 "Maximum call stack size
exceeded" on node. The owner asserts: yin.vm is a CESK machine (control,
environment, store, kontinuation — store-allocated continuations, explicit
step loop) and therefore cannot blow a native stack; the native recursion must
live in host-side helper code (=, print, serializers, result materialization)
around the machines. The orchestrator half-claimed the :ast-walker variant is
a tree-walking recursive interpreter — the owner corrected: it is also CESK.

AUDIT TASKS (each with a verdict + file:line evidence):

1. Enumerate the vm-types the parity harness runs (grep vm-types in
   test/yang/clojure/stream_eval_test.cljc and safepoint_test.cljc; find each
   implementation). For EACH variant, determine its execution model:
   explicit iterative step loop vs native recursion over the AST/program.
   Key files: src/cljc/yin/vm.cljc (engine), any ast-walker/semantic/stack
   namespaces, src/cljc/yin/semantic/*.cljc.
2. For each variant, locate the 1000-frame guard (RecursionError minting) and
   state whether it is a kont-depth counter (CESK-safe) or a native-depth
   limit.
3. Identify which host-side code could natively recurse over deep structures
   in the failing test's path: the answer comparison (= on deep results), the
   print/materialization of :py/out, the continuation serializers named in
   fix 2's comment (shadow-cljs.edn :prepend comment, branch nightly-reds-fix),
   CBOR encoding of manifests/konts. For each: recursive or iterative? file:line.
4. CONCLUSION: name the exact function(s) that recurse natively and would blow
   V8's stack for a 1000-deep guest recursion — or prove none exists and the
   exception must come from elsewhere. If the JS stack trace is needed, you may
   run ONE node reproduction on shiva (cd ~/ci/datomworld-nightly-node — NOTE:
   another seat's uncommitted edits live there; do not modify anything; the
   bundle is compiled): eval "$(mise env -s bash)" && DATOM_SLOW_TESTS=1 node
   target/node-tests.js --test=yang.python.antlr.safepoint-test/recursion-error-test
   and capture the full JS stack trace of the exception (V8 truncates "Maximum
   call stack size exceeded" traces — if the trace is unhelpful, instrument by
   running a standalone JS reproduction instead: a small script in /tmp that
   mirrors the recursion depth; NOT in the repo).

Constraints: read-only except the one reproduction command above. No commits.
Do not touch other seats' uncommitted state. Begin the final response exactly
with Completed-GMT / Completed-Local and report: the vm-type inventory with
execution models, the guard locations, the native-recursion verdict per
candidate helper, and the named culprit function(s) with file:line evidence.
