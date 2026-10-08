Created-GMT: 2026-09-22 07:43:49 GMT
Created-Local: 2026-09-22 14:43:49 +07 (Indochina Time)
Coding-Agent: glm
Session-ID: pending (caller-generated)
# Task: debruijn-b3: independent review of the de Bruijn VM kernel
Role: Adversarial Review (Interpreter / VM)
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-22 14:43:49 +07 | Status: active | Rationale: the code was written by claude-sonnet-5 (Claude family); reviewer must differ; standing rule (docs/agents/roles/orchestrator.md) requires independent review before any commit made while working autonomously

Read-only. Work ONLY in /Users/sto/workspace/worktree-debruijn-b3 (branch
debruijn-b3, HEAD b39f3e8c; the B3 files are UNCOMMITTED in the working
tree). Do NOT edit or create any file and do not run tests (the
orchestrator verified: kondo 0/0, cljstyle clean, full JVM Java 17 1725
tests / 172565 assertions, CLJS 1642 tests / 42410 assertions, CLJD 0
failures). You may read files and run read-only git commands. Give the
complete review now as your final response; do not wait for approval.

## What you are reviewing

src/cljc/yin/vm/debruijn_vm.cljc (about 250 lines) and
test/yin/vm/debruijn_vm_test.cljc (about 300 lines), both new, both
uncommitted (`git status` / `git diff` shows them as untracked).

Read in full first: docs/design/yin.vm.debruijn-vm.md sections 1
(Architecture and invariants) and 4 (VM state and execution) in full, plus
the "B3: de Bruijn VM kernel" phase box. Then read
collab/1790062087560-ref-implementer-b3-report.md (copy this from
collab/1790062087560-yinvm-engineer-debruijn-b3.claude-sonnet-5.stdout.log
if it is not already present under that name -- if you cannot find it,
read the .stdout.log directly) -- the implementer's own report, which you
must verify claim by claim, not trust. Also read
src/cljc/yin/vm/engine.cljc (`resolve-var`, `bind-params`) and
src/cljc/yin/vm.cljc (`IVM`, `IVMState` protocols) to check reuse claims
against the actual source.

## What to judge (concrete; cite file:line)

1. **Frame direction.** The design requires frame 0 for a `:load-bound`
   reference to be the INNERMOST frame, read from the END of the
   `:frames` vector. Trace the implementation's actual indexing arithmetic
   by hand against the report's own worked example (nested closures,
   outer called with 3, inner called with 4, `:frames` becomes `[[3]
   [4]]`, `[:load-bound 1 0]` reaches `x`=3, `[:load-bound 0 0]` reaches
   `y`=4) and at least one case of your own construction. Is the direction
   actually correct, or does the report's example merely happen to work by
   coincidence (e.g. symmetric depths)?
2. **Closure capture and calls.** Does a closure genuinely capture the
   PERSISTENT frame stack at creation time (immutable snapshot), not a
   reference that could see later mutation? Is `bind-positional`
   (`(vec (take arity (concat args (repeat nil))))`) correct for: exact
   arity, under-arity (nil-fill), over-arity (drop extras), and zero
   arity? Does calling a closure push exactly one new frame (the call's
   bound params) while preserving the closure's captured frames beneath
   it, in the right order?
3. **`:load-free` resolution order and reuse.** Confirm `resolve-var` is
   called with the right four arguments in the right order
   (`free-env, store, primitives, module-registry`) and that a
   `:load-bound` operand NEVER falls through to the store (only
   `:load-free` does) -- try to construct a case where a bound reference
   could be mistaken for free and check the code path explicitly forbids
   it.
4. **`IVM`/`IVMState` conformance and scope discipline.** No new protocol
   methods added; `environment` is unimplemented or throws, never returns
   the raw positional frame vector; nothing in `yin.vm.cljc`,
   `yin.vm.engine.cljc`, `yin.vm.semantic.cljc`, `yin.vm.code.cljc`, or the
   merged projection namespace (`yin.vm.debruijn.cljc`,
   `yin.vm.pipeline.cljc`) is touched (confirm via `git status`/`git
   diff`, not by trusting the report). No stream/primitive/FFI/gensym/
   continuation/park/resume opcode is silently no-op'd -- confirm an
   out-of-scope opcode fails loudly (the report names a
   `not-yet-implemented-opcode-test`; verify it actually exercises this).
5. **The `:call`-on-primitive-function deviation.** The implementer added
   handling for a resolved primitive host function value in `:call`
   (beyond `:closure` values), reasoning that without it `:load-free`
   (explicitly in scope) would resolve a callable with no way to invoke
   it. Is this addition genuinely minimal (the pure `apply` case only, no
   effect/module dispatch), does it stay inside B3's stated scope, and is
   the reasoning sound -- or does it quietly reach into B4's territory
   (FFI/primitives) more than the report admits?
6. **Parity tests.** For each named-VM-vs-de-Bruijn-VM comparison
   (literal, bound/free load, nested closures, under/over-arity, both `if`
   arms, store get/put), confirm the named AST and the hand-written
   instruction vector actually encode the SAME program (not just produce
   the same result by coincidence) -- spot-check at least three by
   tracing both sides. Confirm every comparison test builds a FRESH VM
   instance per the design's fresh-initial-environment requirement (no
   reused, previously-run VM).
7. **Store-put sequencing test.** The implementer's own report flags a
   deviation here (a literal `:val`, not an AST node, fixed after a first
   wrong draft). Confirm the final version is correct against
   `yin.vm/ast_walker.cljc`'s actual `:store-put` encoding, and that the
   sequencing (put before get, via nested application operand order) is
   genuinely forced by the Universal AST's lack of an explicit sequencing
   node, not an accidental ordering that happens to pass.

## Deliverable

Final response beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: glm
Session-ID: <your session id if visible, else pending>
then a verdict: READY, READY WITH CHANGES, or NOT READY; findings as P1
(wrong frame addressing, a scope violation, an incorrect parity claim), P2
(significant gap), P3 (minor), each with file:line and the smallest fix;
what you checked and found clean. Findings only; edit no file.
