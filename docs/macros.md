# Unified Compile-Time and Runtime Macros (Stream Model)

## Summary

Implement one macro engine for both compile-time and runtime, operating on
canonical datom streams.

This spec assumes register/stack already follow
`docs/register-stack-vms-stream-refactor.md`.

## Canonical Macro Model (v1)

- Macro representation
  - Macro is a lambda entity with explicit metadata:
    - `:yin/macro? true`
    - `:yin/phase-policy :compile | :runtime | :both`
  - Macro remains `:yin/type :lambda` in structural form.

- Expansion operation naming
  - Expansion op/event name is fixed: `:yin/macro-expand`.
  - This is normative, not an example.

- Argument semantics
  - Macro arguments are AST/datom refs (unevaluated), not evaluated runtime
    values.

## Phase Policy and Security

- Phase semantics
  - `:compile`: expansion allowed only in compile phase.
  - `:runtime`: expansion allowed only in runtime phase.
  - `:both`: expansion allowed in both phases.
  - Invocation outside allowed phase is a hard expansion error datom path.

- Security policy
  - Compile-time expansion: always allowed in trusted build context.
  - Runtime expansion: requires Shibi capability token (TODO: Not implemented yet) scoped to:
    - `macro/expand` authority
    - target stream/program scope
    - allowed phase `:runtime`
    - allowed macro entity/namespace
  - Missing or invalid capability is a hard expansion error.

## Compile and Runtime Flow

- Compile-time flow
  - Yang emits `:yin/macro-expand` datoms into bounded compile stream.
  - Expander runs on bounded stream and may expand recursively.
  - Recursive expansion appends to the same bounded compile stream.
  - Expansion continues until fixpoint or guard-limit failure.
  - Fixpoint is reached when the last pass emits zero new
    `:yin/macro-expand` datoms.

- Runtime flow
  - Semantic VM consumes appended macro datoms directly because it executes
    datoms without bytecode projection.
  - Register/stack consume appended macro datoms via
    `maybe-recompile-at-boundary` (call/tailcall and scheduler resume
    boundaries only, never mid-instruction).

## Provenance, Causality, and Hygiene

- Causality/provenance
  - Expansion creates an expansion-event entity with:
    - `:yin/type :macro-expand-event`
    - `:yin/source-call <call-eid>`
    - `:yin/macro <macro-lambda-eid>`
    - `:yin/phase :compile | :runtime`
    - `:yin/expansion-root <expanded-root-eid>`
    - optional `:yin/error <structured-error>`
    - optional `:yin/capability <shibi-capability-ref>`
  - Expanded output datoms set `m = <expansion-event-eid>`.
  - Original call datoms are immutable and unchanged. Linkage is forward via
    `:yin/source-call` on the expansion-event.
  - Original call datoms and expanded datoms both persist.
  - Query chain must resolve: source-call -> expansion-event -> expansion-root.

- Hygiene (v1)
  - Hygiene is explicit and limited in v1:
    - default policy is non-hygienic expansion,
    - expander provides fresh-id/gensym helpers to avoid accidental capture.
  - Full automatic hygiene is out of scope for v1.

## Guards and Failure Behavior

- Hard guard limits are required in v1:
  - max expansion depth (default `100`),
  - max emitted datom count per expansion transaction (default `10,000`).
- Guard overflow is a hard expansion error with structured metadata.
- Invalid expansion output is a hard error. No silent fallback.

## Runtime Capability Scope (TODO: Not implemented yet)

- Runtime Shibi capability checks scope `macro/expand` authority to
  `:program-root-eid` from VM state.
- Token must authorize target program root scope and allowed macro
  entity/namespace.

## Test Plan

- Compile/runtime expansion parity for same macro/input.
- Phase-policy enforcement tests for `:compile`, `:runtime`, and `:both`.
- Runtime capability tests (allowed vs denied token).
- Provenance tests validating `m` links expansion output to expansion-event.
- Expansion-event shape tests for required attrs and query contract.
- Immutability test: original call datoms unchanged after expansion; provenance
  comes from expansion-event + expanded output `m`.
- Boundary-runtime test: append macro datoms mid-execution, in-flight frame
  completes on old artifact, next boundary transfer uses updated projection.
- Recursive and mutual-recursive macro tests:
  - successful bounded recursion case,
  - depth/datoms guard overflow case.
- Fixpoint test: termination occurs when no new `:yin/macro-expand` datoms are
  emitted in a pass.
- Backend parity tests across semantic/register/stack after expansion.
