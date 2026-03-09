# Register/Stack VMs Stream Refactor

## Summary

Refactor RegisterVM and StackVM so their canonical program input follows the
same semantic model as SemanticVM: bounded datom streams
(`{:node root-id :datoms [...]}`).

Bytecode remains a derived projection/cache, not the source of truth.

## Key Changes

- Program model
  - Canonical input for register/stack becomes semantic-style datom program
    maps.
  - Keep backward compatibility for existing bytecode-form `load-program`
    inputs.

- VM loading/eval contract
  - `load-program` supports `{:node :datoms}` as primary form.
  - `eval` explicitly normalizes AST via `vm/ast->datoms`, builds
    `{:node root-id :datoms ...}`, then loads via the same path.

- Projection behavior
  - Register/stack compile from canonical datom stream to asm/bytecode.
  - Appended stream datoms invalidate/rebuild compiled artifacts at safe
    execution boundaries (boundary recompile).

- State model
  - Register/stack VM state stores canonical datom/index metadata required for
    recompilation.
  - No hidden global caches.

## Precise Runtime Boundary Definition

`Boundary recompile` means:

- Recompilation is never done mid-instruction.
- Recompilation is triggered only at explicit dispatch boundaries:
  - before executing a `:call` or `:tailcall` transfer, or
  - when resuming from scheduler/run-queue into a continuation before the next
    instruction dispatch.
- Active frames continue on their current compiled artifact until a boundary is
  reached. No in-frame bytecode patching.

This keeps control flow explicit and avoids implicit mutation of active
continuations.

Boundary recompilation is triggered through a named helper:
`maybe-recompile-at-boundary`.

## Explicit VM State Additions

Register/stack VM state must explicitly store:

- `:program-root-eid` : root entity id for current bounded program.
- `:program-datoms` : canonical `bounded-datom-stream` value.
- `:program-version` : monotonic version for append/recompile lifecycle.
- `:program-index` : derived node-attribute index for compilation.
- `:compiled-by-version` : in-state map of compiled artifacts keyed by version.
- `:active-compiled-version` : version currently used for new control transfers.
- `:compile-dirty?` : flag set when canonical datoms append after last compile.
- `:compiled-cache-limit` : max retained compiled versions (v1 default `8`).

No global singleton cache is allowed. All compilation/projection state is part
of VM value.

## Program Storage and Ownership

- VM execution uses `:program-datoms` from VM state as canonical source.
- v1 representation is a persistent vector snapshot of the bounded stream.
- Snapshot is an implementation detail. The semantic type is bounded stream.
- Appends happen only through explicit VM operations that return a new VM with
  updated `:program-datoms` and `:program-version`.

This preserves determinism and serialization boundaries.

## Invalidation Strategy (v1)

- v1 uses explicit full-artifact rebuild per program version:
  - any append that affects executable `:yin/*` program facts marks
    `:compile-dirty? true`.
  - `:program-index` rebuild is lazy: append marks dirty only. Index rebuild
    happens inside `maybe-recompile-at-boundary`.
  - first eligible dispatch boundary performs compile for new version and stores
    it in `:compiled-by-version`.
- v1 cache retention policy:
  - keep most recent `N` compiled versions (default `N=8`),
  - never evict versions pinned by active frames,
  - evict oldest non-pinned versions when over limit.
- Incremental invalidation/partial rebuild is out of v1 scope.

## Test Plan

- Run all existing VM tests with zero regressions.
- Add register/stack tests for `load-program` with `{:node :datoms}`.
- Add parity tests: semantic/register/stack produce same result from same
  datom program.
- Add boundary-recompile test: append datoms mid-execution, verify in-flight
  frame completes on old artifact and next call uses new artifact.
- Add cache-retention test: enforce N-version cap with active-frame pinning.
- Keep existing bytecode `load-program` tests passing unchanged.

## Acceptance Criteria

- Existing suites pass with no regressions.
- New stream-program, boundary-recompile, and cache-retention behaviors are
  covered and passing.
- Bytecode compatibility path remains intact.
