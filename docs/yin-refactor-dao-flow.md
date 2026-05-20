# Architectural Note: `dao.flow` Independence from `yin.vm`

## The Circular Dependency Problem

In the design of datomworld, two core invariants establish a potential circular
dependency:

1. **`dao.flow` is the universal orchestrator:** It schedules continuations,
   manages wait-sets, and dispatches effects for all major system workloads
   (inference, compilation, agents).
2. **`dao.flow` is hosted:** The design states that the `dao.flow` scheduler
   program runs as a continuation inside `yin.vm`.

If the Yin compiler pipeline (which produces `yin.vm` bytecode) is itself
orchestrated by `dao.flow`, the system cannot bootstrap. It needs `yin.vm` to
run the compiler orchestrator, but it needs the compiler orchestrator to build
`yin.vm`.

## Resolution: Model vs. Runtime

To resolve this, `dao.flow` must be fundamentally decoupled from the `yin.vm`
*runtime* (the bytecode executor) while remaining strictly bound to the
*semantic model* (CESK: Control, Environment, Store, Continuation).

`dao.flow` is not a `yin.vm` dependency; it is a **coordination pattern**.

### 1. `dao.flow` as a Host-Native State Machine

At its core, `dao.flow` is a pure state transition function over data structures.
It manages:

- **Ready Queues:** A list of IDs.
- **Wait Sets:** A map of IDs to conditions.
- **Continuations:** Maps containing state, lineage, and pending effects.

During bootstrap (Stage 0), the `dao.flow` scheduler is implemented entirely in
the **Host Language** (Clojure, Dart, or Rust). It does not require a bytecode
VM to operate. It simply consumes `dao.stream` events, mutates the host-native
state maps, and dispatches effects to host-native functions.

### 2. The Compilation Pipeline (Stage 0)

When orchestrating the compilation pipeline, `dao.flow` runs natively:

```text
Host-Native dao.flow Scheduler
-> dispatches to Host-Native Lexer (Effect)
-> dispatches to Host-Native Parser (Effect)
-> parks on Macro Dependencies (Wait-Set)
-> dispatches to Host-Native Bytecode Emitter (Effect)
-> emits yin.vm bytecode to dao.db
```

Because `dao.flow` relies only on `dao.stream` and `dao.db` (which are also
host-native structures), it can coordinate the entire parallel, incremental
compilation of the `yin.vm` system without needing `yin.vm` to be operational.

### 3. Self-Hosting Transition (Stage 1)

Once the `yin.vm` compiler is built and the VM executor is running, the system
can transition to self-hosting.

Because `dao.flow` is written in the datomworld vocabulary, its scheduler logic
can be compiled into `yin.vm` bytecode. 

At this stage:

1. The host instantiates `yin.vm`.
2. It loads the `dao.flow` bytecode into the VM.
3. The VM executes the scheduler, reading from the exact same `dao.stream`
   transports.

## The Semantic Boundary

This independence clarifies the exact boundary between the components:

- **`yin.vm`** is the execution engine for bytecode.
- **`dao.flow`** is the execution engine for workflow graphs (continuations).

A continuation in `dao.flow` is just a data structure. 
- If that data structure contains a `yin.vm` bytecode pointer, the effect
  registry dispatches it to the `yin.vm` interpreter.
- If that data structure contains a Tensor Op, the effect registry dispatches it
  to a vLLM kernel.
- If that data structure contains Host-Native compilation state, the effect
  registry dispatches it to a host function.

`dao.flow` orchestrates continuations. It does not care if those continuations
are eventually evaluated by `yin.vm`, by a GPU, or by the host CPU.

## Summary

`dao.flow` uses the *theory* of continuations to orchestrate work, but it does
not depend on `yin.vm` to run. By acting as a host-native state machine during
bootstrap, `dao.flow` can safely orchestrate the parallel compilation of
`yin.vm` itself, completely resolving the circular dependency.