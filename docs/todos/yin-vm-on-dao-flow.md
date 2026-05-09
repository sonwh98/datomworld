# Proposal: `yin.vm` Uses `dao.flow`

## Summary

This document records a design realization:

- `yin.vm` is not something to be unified with `dao.flow`
- `yin.vm` already fits the `dao.flow` model
- `yin.vm` should be understood as a client of `dao.flow`, in the same sense
  that `dao.graphics` is a client of `dao.flow`

So the correct framing is not:

- "How do we unify two separate architectures?"

The correct framing is:

- "How does `yin.vm` use the workflow algebra that `dao.flow` already defines?"

## Core Claim

`dao.flow` is the domain-agnostic workflow algebra over `dao.stream`.

`yin.vm` is one domain built on that algebra.

`dao.graphics` is another domain built on that algebra.

So the stack is:

```text
dao.stream
-> dao.flow
   -> yin.vm
   -> dao.graphics
   -> other clients
```

That means `yin.vm` should not be described as merely "similar" to `dao.flow`.
It should be described as using `dao.flow`.

## Why This Matters

The earlier unification framing overcomplicates the relationship.

It makes it sound like:

- `yin.vm` has one architecture
- `dao.flow` has another architecture
- and the project is to merge them later

But the stronger realization is:

- `dao.flow` already names the workflow architecture
- `yin.vm` compilation and execution already fit that architecture
- the right move is to make this usage explicit

This reduces conceptual duplication across the repo.

## `yin.vm` as a `dao.flow` Client

`yin.vm` can be described as a set of workflows over `dao.stream`.

Compilation path:

```text
AST stream
-> lowering interpreters
-> bytecode stream
-> VM machine
```

Execution path:

```text
input stream
-> VM interpreter / machine
-> effect streams or terminal effects
```

Macro expansion, bytecode lowering, FFI bridging, telemetry emission, and
continuation parking can all be viewed as `dao.flow` workflows or workflow
fragments.

That is the same general pattern used elsewhere:

```text
dao.scene
-> lowering
-> dao.graphics
-> graphics VM
```

The semantic domains differ, but the workflow architecture is the same.

## `dao.graphics` as a Parallel Client

`dao.graphics` is another client of `dao.flow`.

Its workflow shape is:

```text
scene stream
-> lowering interpreters
-> dao.graphics stream
-> graphics VM
```

So both `yin.vm` and `dao.graphics` should be seen as peers with respect to
`dao.flow`.

They are not the same domain, but they use the same flow substrate.

## What `dao.flow` Provides to `yin.vm`

`dao.flow` provides:

- explicit stream boundaries
- interpreter composition
- materialized vs fused workflow execution
- branching and replay at workflow boundaries
- runner-level scheduling and lifecycle structure
- a place to treat compilation and execution as workflows rather than hidden
  function chains

Applied to `yin.vm`, this means:

- AST lowering stages can be explicit interpreters
- bytecode generation can be materialized or fused
- VM execution can be embedded in larger workflows
- telemetry and inspection can attach to explicit boundaries
- continuation-aware execution can remain explicit

## What Stays Owned by `yin.vm`

Using `dao.flow` does not mean losing `yin.vm`'s domain ownership.

`yin.vm` still owns:

- universal AST semantics
- macro semantics
- VM bytecode vocabulary
- environment/store/control semantics
- continuation semantics at the language-runtime level
- VM machine behavior

`dao.flow` does not absorb these semantics.

It supplies the workflow model in which they are composed.

## What Changes in Description

The architectural description should change from:

- "`yin.vm` and `dao.flow` can share architecture"

to:

- "`yin.vm` uses `dao.flow`"

And from:

- "maybe `yin.vm` compilation can be modeled like `dao.flow`"

to:

- "`yin.vm` compilation should be described as a `dao.flow` workflow"

This is a simpler and more accurate statement.

## Code Reuse Implication

Once `yin.vm` is recognized as a `dao.flow` client, shared code should be
factored at the `dao.flow` boundary, not at an imagined "unification" layer.

Likely reusable pieces:

- interpreter composition helpers
- fused/materialized runner patterns
- stream-boundary inspection
- continuation-aware waiting integration with `dao.stream`
- telemetry and trace tooling
- executable lowering pipeline helpers

But the code should still remain domain-specific where needed:

- VM bytecode is not graphics bytecode
- VM machine is not graphics VM
- AST is not scene algebra

## Revised Layering

The better conceptual layering is:

```text
dao.stream
  transport substrate

dao.flow
  workflow algebra over streams

yin.vm
  one client domain using dao.flow for compilation and execution workflows

dao.graphics
  one client domain using dao.flow for rendering workflows
```

This is simpler than inventing a separate unification layer above `dao.flow`.

## Open Questions

The remaining questions are not about architectural unification.

They are about how far to push the client model:

- How much of `yin.vm` compilation should be explicitly materialized as
  `dao.flow` stages?
- Which parts of VM execution should expose flow boundaries?
- Which runner helpers belong in `dao.flow` versus `yin.vm`?
- How should continuation-bearing workflows be described when the client is a VM
  rather than a graphics pipeline?

These are adoption questions, not unification questions.

## Accepted Direction

This todo proposes:

- describe `yin.vm` as using `dao.flow`
- describe `dao.graphics` as using `dao.flow`
- stop framing the relationship as a future unification project
- factor shared infrastructure at the `dao.flow` boundary

The key realization is:

`yin.vm` uses `dao.flow` like `dao.graphics` uses `dao.flow`.
