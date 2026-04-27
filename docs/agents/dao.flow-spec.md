# dao.flow Specification

`dao.flow` is a stream-composed GPU workflow substrate. It treats GPU rendering and compute not as pipelines of objects, but as different interpretations of a stream of intent datoms.

## Pipeline Architecture

```text
 PRODUCER          STAGE 1            STAGE 2            ...           TERMINAL
 ─────────         ──────────         ──────────                       ─────────
 hiccup author  →  intent-datom       op-datom                         pixels
   (or)            stream             stream                           (or)
 yin.vm code    ─→ ─────────────  ─→  ──────────  ─→  ...  ─→         GPU buffers
   (or)            ↑ walk             ↑ paint                          (or)
 network feed      (interpreter)      (interpreter,                    compute output
                                       terminal here)
```

Each `→` is a `dao.stream`. Each stage is an interpreter that:
1. Reads from an upstream `dao.stream` at its own cursor.
2. Transforms the datoms it sees.
3. Writes to a downstream `dao.stream` (unless terminal).

## Composition Rules

Interpreters expose two equivalent compositions:
1. **Stream-mediated**: Intermediate values are materialized on `dao.stream`. Costly but inspectable.
2. **Transducer-fused**: Stages are transducers, composed with `comp` and run via `stream-transduce`. Zero intermediate buffers.

## Intent-Datom Schema

Entities represent the scene graph.
`[:flow/scene [:geom/rect {:size [10 20] :material {:color [1 0 0 1]}}]]`
Becomes:
```clojure
[-1 :flow/scene-root true]
[-1 :flow/tag        :flow/scene]
[-2 :flow/tag        :geom/rect]
[-2 :flow/parent     -1]
[-2 :flow/sort-key   1000]
[-2 :geom/kind       :rect]
[-2 :geom/size       [10 20]]
[-2 :flow/material   -3]
[-3 :material/color  [1 0 0 1]]
```

## Op-Datom Schema

The contract between `walk` (Stage 1) and `paint` (Stage 2). Backends own how to realize each kind.

Each op is a map containing:
- `:op/kind` - e.g., `:cube`, `:rect`, `:end-frame`
- `:op/world` - 16-double world-from-local matrix
- `:op/projected` - 16-double clip-from-world matrix
- `:op/depth` - sorting metric
- `:op/source-eid` - pointer back to intent entity
