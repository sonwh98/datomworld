Created-GMT: 2026-09-24 11:38:17 GMT
Created-Local: 2026-09-24 18:38:17 +0700
Coding-Agent: claude
Session-ID: f9328487-72e9-44e8-a216-0fbb81bf6a2e

# Task: Architectural Specification for Universal Code Linker & Module Unification (yin.vm.linker.md)

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-24 18:38:17 +0700 | Status: active | Rationale: Authoring comprehensive architectural specification unifying module loading and the de Bruijn linker across all four Yin VMs over dao.stream

Author the comprehensive, rigorous architectural design document `docs/design/yin.vm.linker.md` in `/Users/sto/workspace/datomworld-universal-linker`.

Read first:
- `docs/design/datom.world.md`
- `docs/design/dao.stream.md`
- `docs/design/dao.jing.md`
- `docs/design/yin.vm.debruijn.linker.md`
- `docs/design/yin.vm.universal-continuation-format.md`
- `src/cljc/yin/vm/module.cljc`
- `src/cljc/yin/vm/debruijn_linker.cljc`

Foundational Critique & Scope to Address:
1. Namespace & Architectural Layering:
   - `yin.vm.debruijn-linker` incorrectly implies the linker is tied only to de Bruijn bytecode.
   - Promote and elevate the linker to `yin.vm.linker`: the universal code linker for ALL `yin.vm` execution backends.
2. Unification of Module Loading and the Linker:
   - Module loading (`(require ...)`) and code linking are conceptually the same operation.
   - Code at rest is a content-addressed tuple/record in `dao.jing`. Code in motion is a stream in `dao.stream`.
   - There should be NO difference between local and remote code loading: all code fetching must travel over `dao.stream` (via in-memory ringbuffer, file medium, WebSocket transport, or DHT).
   - `(require 'foo)` must lower to an effect that invokes the universal linker over `dao.stream`, replacing the static ad-hoc in-memory dictionary lookup in `yin.vm.module`.
3. Support for All Four Yin VM Backends:
   - Define concrete format records for:
     * `:ast-walker`: Universal AST / datom row set (`:yin.ast/code`)
     * `:semantic`: Positional instruction tuple vector / UCF (`:yin.semantic/code`)
     * `:stack`: de Bruijn stack image ($H$) (`:yin.debruijn.code`)
     * `:register`: de Bruijn register image ($R$) (`:yin.debruijn.register`)
   - Specify the universal 6-step `fetch` pipeline across all formats:
     1. Resolve identity to storage address.
     2. Fetch payload over `dao.stream`.
     3. Verify address matches digest (`segment-key`).
     4. Verify format identity hash ($H$, $R$, or semantic image identity).
     5. Validate format-specific structural and liveness invariants.
     6. Verify free-name closure against the receiver environment (`:unresolved-free`, `:shadowed-free`).
4. Migration & Coexistence Strategy:
   - Migration path from `yin.vm.debruijn-linker` to `yin.vm.linker`.
   - How `yin.vm.module` evolves into a module manifest registry pointing to content addresses/identities.
5. Invariants & Code Rules:
   - 100% pure ASCII.
   - Lines strictly <= 80 columns.
   - Clear mermaid diagrams visualizing the unified flow.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, core architectural decisions, and next implementation steps.
