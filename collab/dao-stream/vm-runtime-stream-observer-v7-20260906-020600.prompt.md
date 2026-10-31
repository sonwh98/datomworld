Created-GMT: 2026-09-06 02:06:00 GMT
Created-Local: 2026-09-06 09:06:00 Asia/Ho_Chi_Minh
Coding-Agent: glm
Session-ID: 9906d51e-be39-4610-8159-88f9acf2ff8b

# Task: Implement Yin VM v2 stream-observer ownership

Role: Yin.VM Runtime Engineer

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-06 09:06:00 Asia/Ho_Chi_Minh | Status: active | Rationale: VM Runtime primary; the migration centers on CESK execution boundaries, VM state, program loading, and REPL coordination.

Implement V7 in `/Users/sto/workspace/datomworld` according to the canonical
contract in `docs/design/yin.vm.implementation-plan.md`, especially
`Program observation and ownership`, `V7`, and `End condition`.

Read first:
- `AGENTS.md`
- `docs/design/datom.world.md`
- `docs/design/dao.stream.md`
- `docs/design/yin.vm.implementation-plan.md`
- `docs/design/yin.vm.divergence-register.md`
- `docs/design/yin.repl.implementation-plan.md`
- `docs/agents/build-n-test.md`
- `src/cljc/dao/stream.cljc`
- `src/cljc/dao/stream/ringbuffer.cljc`
- `src/cljc/yin/vm.cljc`
- `src/cljc/yin/vm/stream_observer.cljc`
- `src/cljc/yin/vm/engine.cljc`
- `src/cljc/yin/vm/ast_walker.cljc`
- `src/cljc/yin/vm/ffi.cljc`
- `src/cljc/yin/repl/core.cljc`
- relevant tests under `test/yin/vm/` and `test/yin/repl/`

The tree is intentionally dirty. The mechanical namespace rename from
`stream-driver` to `stream-observer`, the canonical plan edits, and all existing
`collab/` artifacts belong to this task. The untracked files
`public/chp/blog/datomworld-vs-big-pineapple.blog` and
`public/chp/blog/wici.blog` are unrelated user work: do not touch them. Preserve
all unrelated changes. Do not stage, commit, reset, revert, delete collaboration
artifacts, or modify v1 namespaces.

Authorized edit scope:
- `src/cljc/yin/vm.cljc`
- `src/cljc/yin/vm/stream_observer.cljc`
- `src/cljc/yin/vm/engine.cljc`
- `src/cljc/yin/vm/ast_walker.cljc`
- `src/cljc/yin/repl/core.cljc`
- relevant test files under `test/yin/vm/` and `test/yin/repl/`
- `docs/design/yin.vm.divergence-register.md`
- `docs/design/yin.repl.implementation-plan.md`

Treat `docs/design/yin.vm.implementation-plan.md` as the approved contract;
do not rewrite or mark it complete. If implementation truly requires another
source file, stop and report the exact expansion rather than editing it.

Acceptance criteria:

1. `yin.vm.stream-observer` requires only `dao.stream` and exports exactly
   `attach`, `observe-next`, and `run-on-stream`. Keep helpers private.
2. `attach` receives a unary attach capability and descriptor, calls it once,
   rejects non-ok outcomes while preserving `:dao.stream/outcome`, validates
   `stream/reader?`, reports declared surfaces for assembly failure, mints
   `:dao.stream/oldest` directly, and returns only
   `{:stream handle :cursor cursor :ingress-gaps 0}`. It never creates, appends,
   or closes.
3. `observe-next` owns program handle/cursor/gap state and handles ok, blocked,
   end, gap, terminal, and unexpected outcomes exactly as the plan states.
4. `run-on-stream` carries `{:observer observer :vm vm}` and uses supplied
   `ready?`, `load-program`, and `run-vm`. Preserve readiness gating, suspended
   execution, gap recovery, and poison-batch retry: do not publish the successor
   observer state until loading succeeds.
5. Move `ready-for-ingress?` into `yin.vm.engine` without semantic change.
   Make the existing AST-walker `vm-load-program` public without changing its
   conversion or execution updates. Do not invent `accept-datoms`,
   `ready-for-program?`, a new VM protocol, or an operation registry.
6. Remove `:in-stream`, `:in-cursor`, and `:ingress-gaps` from `ASTWalkerVM` and
   every constructor/copy path. Reject obsolete `:in-stream` before FFI resource
   allocation. Walker `step` and `run` execute loaded work only; idle step is
   identity under the readiness predicate. Keep `eval` as AST conversion,
   `vm-load-program`, and run; it must not drain an independent program stream.
   Preserve scheduler, queues, waits, environment restoration, FFI behavior,
   and `engine/run-loop`.
7. Remove engine observer forwarding and stream-aware VM runners. Update the
   base `yin.vm` protocol/constructor documentation and all stale docstrings.
8. REPL composition owns the program writer, descriptor, resolver/attacher,
   observer, and VM separately. Use existing ring-buffer descriptor/attacher
   mechanisms; do not introduce transport dependencies into the observer.
   Reset and VM selection rebuild them together. Datom literals append through
   the writer and invoke observer coordination; source/AST uses direct eval.
   Preserve output, result history, state-summary shape, and the existing shell
   loss latch: observer recovers the cursor, then a gap-count increase makes the
   REPL report loss and require reset.
9. Migrate stream test helpers to observer sessions; remove ad hoc VM ingress
   associations and forced halted flags. Generic observer tests use a fake unary
   attacher and no transport namespace. Cover the exact API and failure cases,
   independent observers, readiness, loader-failure cursor retention, direct
   eval with queued malformed input, reset then datom evaluation, alternate
   VM-shaped consumer, and existing execution/FFI/REPL behavior.
10. Update the divergence register and REPL implementation plan so they describe
    observer-owned program observation and direct-eval decoupling. Preserve the
    distinction between generic observer gap recovery and the REPL loss policy.

Follow TDD. Run focused JVM tests and lint during implementation. Run affected
CLJS tests if practical. Do not run the CLJD lane; it owns shared generated
output and the orchestrator will run it serially after inspecting your changes.
Inspect the final diff for stale ingress ownership and transport leakage.

Begin the final response exactly with:
Completed-GMT: <actual timestamp GMT>
Completed-Local: <actual timestamp Asia/Ho_Chi_Minh>
Coding-Agent: glm
Session-ID: 9906d51e-be39-4610-8159-88f9acf2ff8b

Then report changed files, exact test/check outcomes and assertion counts,
unresolved concerns, scope expansion requests, and whether implementation is
ready for independent review. Do not claim work or checks that did not occur.
