# Design: dao.flow.spy — Observability in Fused Workflows

## Context

A core strength of `dao.flow` is the ability to fuse multiple workflow stages
into a single transformation. This eliminates intermediate buffers and provides
maximum performance. However, this fusion introduces a significant
"Observability Gap": because intermediate states are never materialized, they
cannot be inspected, tapped, replayed, branched to additional readers, or
debugged using standard tools.

Commonly, developers revert to `->>` macros and intermediate collections to
debug, then manually convert back to transducers. This is a waste of time and a
source of parity bugs.

`dao.flow.spy` provides a structured way to regain visibility into fused
workflows without changing the underlying logic or abandoning fused execution.

## The Strategy: Toggleable Materialization

Instead of rewriting code to debug, `dao.flow` utilizes the duality of its
composition modes. A workflow is authored once, but the **runner** is
responsible for the execution strategy.

### 1. The "Spy" Transducer (Lightweight)

For simple observation during development, a pass-through transducer is used to emit side effects (like `tap>` or `println`) without altering the reduction.

```clojure
(defn spy
  "A pass-through transducer for observation.
   Calls (f x) for side effects and passes x to the next stage."
  [f]
  (fn [rf]
    (fn
      ([] (rf))
      ([acc] (rf acc))
      ([acc x]
       (f x)
       (rf acc x)))))
```

### 2. The Materialization Toggle (Architectural)

For complex debugging, replay, inspection, branching, or decoupled stage
lifecycle management, the runner can "unroll" the fused workflow into a
stream-mediated one. This materializes stage boundaries onto `dao.stream`
values, making intermediate results storable, queryable, visualizable, and
re-readable by multiple consumers.

```clojure
;; ds = dao.stream
(defn run-workflow
  [intent-stream terminal-rf acc {:keys [materialized? tap-fn]}]
  (if-not materialized?
    ;; Fused Mode: High performance, zero visibility
    (stream-transduce (comp walk-xf optimize-xf)
                      terminal-rf
                      acc
                      intent-stream)

    ;; Mediated Mode: Materialize boundaries for inspection
    (let [op-stream (ds/stream)]
      ;; Stage 1 -> Intermediate Stream
      (stream-transduce walk-xf ds/append-rf op-stream intent-stream)
      ;; Intermediate Stream -> Stage 2 -> Terminal
      (stream-transduce (comp (spy tap-fn) optimize-xf)
                        terminal-rf
                        acc
                        op-stream))))
```

## Stigmergic Debugging

In the `datom.world` philosophy, the debugger should not be a separate "mode"
of the engine, but rather another consumer of the same data.

- **Multi-Cursor Debugging:** Because `dao.stream` supports multiple cursors, a
  debugger can attach to an intermediate stream like `op-stream` and render its
  state in a separate window without affecting the main terminal's timing or
  logic.
- **Time-Travel Debugging:** Since intermediate streams are materialized in
  materialized mode, the developer can pause the workflow and re-run only the
  downstream stages from a historical position on the intermediate stream.
- **Verification by Equivalence:** The fused/mediated equivalence invariant in
  `dao.flow` ensures that switching between fused and materialized execution
  never changes the semantic outcome of the workflow.

## Implementation Guidelines

1.  **Author for Fusion:** Always author stages as pure transducers where
    transducers are the chosen fused implementation technique.
2.  **Instrument the Runner:** Build the materialization toggle into the
    top-level application or system runner, not into the individual stages.
3.  **Tap to UI:** Use `tap>` within `spy` transducers to feed data into
    external visualizers like Portal, Reveal, or a custom `datomworld`
    telemetry viewer.
4.  **Zero-Cost Production:** Ensure that when `materialized?` is false, the
    `spy` transducers and intermediate stream logic are entirely bypassed.

This approach transforms debugging from a reconstruction task into a visibility
configuration, while preserving the broader `dao.flow` rule that materialized
boundaries are an architectural choice, not a special-case debug semantics.
