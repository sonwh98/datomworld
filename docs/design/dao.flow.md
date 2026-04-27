# Design: dao.flow — Stream-Composed GPU Workflow Substrate

## Context

`dao.flow` is the project's substrate for GPU work — both rendering and compute — built on a single insight: GPU rendering and GPU compute are not separate pipelines. They are different interpretations of the same stream of intent datoms. The producer never decides whether its datoms become pixels or tensor outputs; it just emits intent. Whoever is reading the stream decides what those datoms mean today.

This applies the existing `dao.stream` + `Yin.VM` pattern (one canonical datom representation, multiple interpreters: semantic / register / stack / AST-walker) to the GPU. The Yang compiler's frontends already follow the same shape: many languages → one universal AST → many backends.

`dao.flow` extends the pattern with interpreter composition. Interpreters are either intermediate (stream-to-stream transformers, authored as transducers) or terminal (sinks that produce side effects — pixels, GPU memory writes, audio). They compose two ways: **stream-mediated** (each boundary materialized on a `dao.stream` for inspection / branching / replay) or **transducer-fused** (composed with `comp` into a single transformation, no intermediate buffers). Pipelines are built by chaining stages; any stage can be tapped, replaced, branched, or inserted. v1 ships a 2-stage pipeline; future GPU graphics, GPU compute, optimizers, alternate producers, and alternate terminals slot in without changing existing stages.

The first concrete user (and motivating use case) is a Hiccup-style scene-graph language for declarative 3D/2D graphics — but the scene-graph framing belongs to the producer, not to `dao.flow` itself. A future producer could be Yin.VM code, a network feed, a replay file, or a compute kernel description.

## Architecture

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
3. Writes to a downstream `dao.stream` (unless terminal, in which case it produces side effects — pixels, GPU memory writes, audio samples, etc.).

### Properties that fall out:
- **Inspectable.** Any intermediate stream can be tapped (multi-cursor) for logging, debugging, replay, or visualization.
- **Composable.** New stages drop in between existing ones without modifying either neighbor (open an extra stream; reroute the consumer's cursor).
- **Branched.** Multiple terminals can read the same op-datom stream — Flutter painter, SVG emitter, and a frame-counter logger can all run side-by-side.
- **Replayable.** Cursor-based reads on `dao.stream` mean any stage can be re-run from any historical position. Free time-travel.
- **Substrate-agnostic at every boundary.** A WebGPU rendering interpreter and a Vulkan compute interpreter both read the same op-datom vocabulary; the difference is downstream.

This is the project's existing model (Plan 9 / Datomic / Lisp lineage, per `CLAUDE.md`) applied to GPU work. It is not TensorFlow's static computation graph; it is TensorFlow's data-as-flow philosophy expressed through the project's existing primitives.

## Composition Modes

Interpreters expose two equivalent compositions, with the runtime choosing whichever fits the moment:

1. **Stream-mediated** — each stage's output is written to a `dao.stream`; the next stage reads from it. Intermediate values are inspectable, branchable (multi-cursor), tappable, replayable. Pay the cost of materializing every boundary.
2. **Transducer-fused** — stages are authored as transducers (reducing-function transformers, exactly like `clojure.core/map`, `filter`, `dedupe`). Composed with `comp`, they collapse into a single fused transformation: input flows from producer directly through every stage into the terminal with no intermediate `dao.stream`. Zero intermediate buffers.

A terminal (e.g., paint) is a reducing function in transducer parlance — the `rf` argument to `transduce`. It accepts an accumulator and an input; for paint, the accumulator is the canvas state and the input is an op-frame, so each "step" issues Canvas draw calls.

Hybrid pipelines are possible: materialize some boundaries (where you want to tap, branch, or replay) and fuse others (hot paths). Same authoring API for the stage; only the runner differs.

### Authoring shape

```clojure
;; Each non-terminal stage is authored as a transducer.
(defn walk-xf
  "Stage 1 as a transducer. Each input is a vector of intent-mutation
   datoms (one transaction). Each output is one op-frame vector."
  [db-atom]
  (fn [rf]
    (fn
      ([]        (rf))
      ([acc]     (rf acc))
      ([acc mutation-datoms]
       (swap! db-atom db/transact mutation-datoms)
       (rf acc (walk-once @db-atom))))))

;; The terminal is a reducing function.
(defn paint-rf
  "Stage 2 terminal. acc is the painter handle; input is one op-frame."
  [acc op-frame]
  (paint! acc op-frame)   ; side effect: Canvas calls
  acc)

;; Two ways to wire them up — same answer, different observability.
;; Fused (no intermediate stream):
(stream-transduce (walk-xf db-atom) paint-rf painter intent-stream)

;; Stream-mediated (op-stream materialized; tap-able, branchable):
(future (stream-transduce (walk-xf db-atom) ds/append-rf op-stream intent-stream))
(future (stream-transduce identity        paint-rf       painter   op-stream))
```

`stream-transduce` is the bridge from `dao.stream` to standard Clojure transducer machinery — it reads inputs from a stream cursor and drives the composed `(xform rf)` step function until the stream ends or signals `reduced?`. Lives in `src/cljc/dao/flow.cljc`.

## v1 Scope

v1 ships one producer + one 2-stage pipeline + one terminal + one demo:

| Role | v1 implementation | Future siblings |
| :--- | :--- | :--- |
| **Producer** | `dao.flow.hiccup` — designer Hiccup → intent-datom stream | Yin.VM code emitter; replay from .flow files; network feed |
| **Stage 1 (walk)** | `dao.flow.walk` — intent → op-datom stream (resolve hierarchy, transforms, projection, depth-sort) | Optimizer; differential frame-diffing |
| **Stage 2 (paint)** | `dao.flow.flutter` — op-datom stream → Flutter Canvas (terminal) | WebGPU rasterizer; Vulkan/Metal terminal; SVG emitter; GPU compute interpreter (reads same ops, produces tensor outputs) |
| **Animation** | `dao.flow.animate` — emits mutation datoms onto the intent stream over time | Yin.VM-driven mutation; physics integrator stage |
| **Demo** | `datomworld.demo.flow_cube` — rotating cube driven end-to-end through the pipeline | |

Datoms are canonical. Hiccup is decomposed into intent datoms (storable in DaoDB, queryable via Datalog). Either form may travel on `dao.stream`; bidirectional `hiccup <-> datoms` projection is required.

## Foundations Reused

| Capability | File | How dao.flow uses it |
| :--- | :--- | :--- |
| **Datom 5-tuple [e a v t m]** | `src/cljc/dao/db.cljc` | All stream payloads are datoms with `:flow/...` attributes |
| **dao.stream (cursor-based)** | `src/cljc/dao/stream.cljc` | The substrate. Every stage boundary is a stream |
| **dao.stream.apply** | `src/cljc/dao/stream/apply.cljc` | (Future) Yin.VM → flow call/response pattern, like equation_plotter |
| **Yin.VM multi-interpreter pattern** | `src/cljc/yin/vm/*.cljc` | Architectural template: same datoms, multiple interpreters |
| **Yang compiler pipeline** | `src/cljc/yang/*.cljc` | Architectural template: many producers, one IR, many consumers |
| **Reagent / ValueNotifier patterns** | `src/cljs/datomworld/demo/equation_plotter.cljs`, `src/cljd/datomworld/demo/repl.cljd` | Bridge from dao.stream into Flutter's reactive world |
| **Vocabulary guide** | `docs/agents/vocabulary.md` | Names: flow, walk, paint, no renderer/engine/util |

## Hiccup Producer Surface

Tag is a namespaced keyword; second element is an optional attribute map; rest are children. Transforms live as the `:transform` attribute (not as wrapper nodes — fewer kinds, uniform meaning). `:flow/group` exists when a designer wants conceptual grouping with its own transform.

```clojure
;; 3D rotating cube
[:flow/scene {:flow/clear-color [0.05 0.06 0.09 1]}
 [:camera/perspective {:fov 60 :near 0.1 :far 100
                       :transform {:translate [0 0 5]}}]
 [:light/ambient {:color [1 1 1] :intensity 0.3}]
 [:light/directional {:color [1 1 1] :intensity 0.9 :direction [-0.3 -1 -0.5]}]
 [:geom/cube {:flow/id :spinner
              :transform {:rotate [0 0 0]}
              :material {:color [0.2 0.6 0.95 1]}}]]

;; 2D widget (button)
[:flow/scene
 [:camera/orthographic {:left 0 :right 320 :top 0 :bottom 240}]
 [:flow/group {:transform {:translate [60 100]}}
  [:geom/rect {:size [200 40] :corner-radius 8
               :material {:color [0.13 0.43 0.92 1]}}]
  [:geom/text {:text "Submit" :font-size 18 :anchor :center
               :transform {:translate [100 20]}
               :material {:color [1 1 1 1]}}]]]

;; 3D viewport spliced into 2D layout
[:flow/scene
 [:camera/orthographic {:left 0 :right 800 :top 0 :bottom 600}]
 [:geom/text {:text "Live Cube" :font-size 24 :transform {:translate [20 20]}}]
 [:flow/viewport {:rect [20 50 400 400]
                  :camera [:camera/perspective {:fov 50}]}
  [:geom/cube {:material {:color [0.95 0.5 0.2 1]}}]]]
```

## Datom Schema (intent layer)

### Attribute namespaces:

| Attribute | Type | Card | Purpose |
| :--- | :--- | :--- | :--- |
| `:flow/scene-root` | boolean | one | root marker |
| `:flow/tag` | keyword | one | e.g. `:geom/cube` |
| `:flow/parent` | ref | one | parent-pointer |
| `:flow/sort-key` | long | one | sibling order, gapped (1000, 2000…) |
| `:flow/id` | keyword | one (unique) | optional designer-supplied stable id |
| `:flow/transform` | ref | one | → transform entity |
| `:flow/material` | ref | one | → material entity |
| `:flow/clear-color` | tuple | one | root only |
| `:transform/translate` `:transform/rotate` `:transform/scale` | tuple | one each | TRS components |
| `:material/color` | tuple | one | `[r g b a]` |
| `:material/opacity` | double | one | |
| `:camera/kind` | keyword | one | `:perspective` \| `:orthographic` |
| `:camera/fov` `:camera/near` `:camera/far` `:camera/left` `:camera/right` `:camera/top` `:camera/bottom` | double | one each | intrinsics |
| `:light/kind` `:light/color` `:light/direction` `:light/intensity` | per-kind | one each | lighting environment |
| `:geom/kind` `:geom/size` `:geom/radius` `:geom/text` `:geom/font-size` `:geom/corner-radius` | per-kind | one each | primitive payload |

### Worked example — `[:flow/scene [:geom/rect {:size [10 20] :material {:color [1 0 0 1]}}]]`:

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

## Op-Datom Schema (walk → paint contract)

The op-datom layer is the public contract between Stage 1 and Stage 2. It is intentionally interpretation-neutral: a graphics interpreter realizes ops as draw commands; a future GPU compute interpreter could realize the same ops as kernel dispatches over the same vertex buffers; an SVG emitter could realize them as `<path>` elements.

Ops are high-level (`:op/kind :cube`), not low-level (`:op/kind :draw/triangle`). Backends own how to realize each kind. Each frame is a vector of op-datoms ending in `:op/end-frame`; the vector is one stream value.

| Attribute | Purpose |
| :--- | :--- |
| `:op/kind` | `:clear` \| `:cube` \| `:sphere` \| `:plane` \| `:rect` \| `:circle` \| `:text` \| `:path` \| `:image` \| `:light` \| `:viewport-push` \| `:viewport-pop` \| `:end-frame` |
| `:op/world` | 16-double world-from-local matrix |
| `:op/projected` | 16-double clip-from-world matrix (per-frame from active camera) |
| `:op/depth` | average clip-z, painter's-algorithm sort key |
| `:op/color`, `:op/size`, `:op/radius`, `:op/text`, `:op/font-size`, `:op/path-data` | per-kind payload |
| `:op/source-eid` | back-pointer to source intent entity (debug, future picking) |

Future stages can introduce additional vocabularies downstream (e.g., a GPU-translator stage emits `:gpu/buffer-write`, `:gpu/draw-indirect`, `:gpu/dispatch`) without changing the `:op/...` schema upstream.

## Files to Create

### Shared (`src/cljc/`)

- `src/cljc/dao/flow.cljc` — public API: `hiccup->datoms`, `datoms->hiccup`, `walk-xf` (transducer), `walk-once` (pure step), `stream-transduce` (bridge from `dao.stream` to standard transducer machinery), attribute keyword catalog.
- `src/cljc/dao/flow/hiccup.cljc` — Hiccup ↔ intent-datom projection (pure).
- `src/cljc/dao/flow/transform.cljc` — TRS math: `identity-mat4`, `translate-mat4`, `rotate-euler-mat4`, `scale-mat4`, `mul-mat4`, `compose-trs`, `perspective-mat4`, `orthographic-mat4`. Vectors of doubles; no host types.
- `src/cljc/dao/flow/walk.cljc` — Stage 1 interpreter: `walk-once` (pure) and `walk-xf` (transducer wrapper). No stream I/O lives here; the bridge is in `dao.flow`.
- `src/cljc/dao/flow/ops.cljc` — op-datom constructors and predicates.
- `src/cljc/dao/flow/animate.cljc` — emit mutation datoms onto an intent stream (`emit-attr-change!`, `rotation-tick`).

### Flutter (`src/cljd/`)

- `src/cljd/dao/flow/flutter.cljd` — Stage 2 terminal: `FlowPainter` extends `CustomPainter` + `mount-flow-stream!` (subscribes op-datom stream → `ValueNotifier<List>` → repaint of painter).
- `src/cljd/datomworld/demo/flow_cube.cljd` — rotating-cube demo app (mirrors `datomworld.demo.repl` structure).

## Tests

- `test/dao/flow/hiccup_test.cljc` — round-trip & shape tests.
- `test/dao/flow/transform_test.cljc` — TRS invariants and projection.
- `test/dao/flow/walk_test.cljc` — op-datom output for known scenes; nested transform composition; depth ordering.
- `test/dao/flow/animate_test.cljc` — mutation stream re-drives walker output at successive cursors.
- `test/dao/flow/compose_test.cljc` — assert two-stage pipeline composes: feed intent stream → walker → op stream → assert ops; prove a third stage can be inserted without changing endpoints.
- `test/dart/flow_smoke_test.dart` — `PictureRecorder`-backed assertion that the painter issues the expected count of Canvas calls.

## Docs

- `docs/agents/flow-spec.md` — spec: pipeline architecture, intent-datom schema, op-datom schema, stage contracts, composition rules.
- One-line pointer added to `CLAUDE.md`.

## Walk Stage Algorithm (pseudocode)

The pure work lives in `walk-once`. `walk-xf` (shown in the Composition Modes section above) is the transducer wrapper. A stream-mediated runner is built by composing `walk-xf` with `dao.stream` append as the reducing function — no separate "walk-the-stream" function is needed because `stream-transduce` provides that bridge.

```clojure
(defn walk-once [db]
  (let [scene-root (find-scene-root db)
        camera     (find-active-camera db scene-root)
        cam-mat    (camera->clip-from-world camera)
        out        (transient [])
        _          (doseq [l (lights-of db scene-root)]
                     (conj! out (op-light l)))
        rec (fn rec [eid parent-world]
              (let [local (entity-local-trs db eid)            ; identity if absent
                    world (mat4-mul parent-world local)
                    tag   (entity-attr db eid :flow/tag)]
                (when (geometry-tag? tag)
                  (conj! out (geom->op db eid tag world cam-mat)))
                (doseq [child (children-sorted db eid)]
                  (rec child world))))]
    (rec scene-root identity-mat4)
    (conj (sort-by :op/depth > (persistent! out)) (op-end-frame))))
```

Cameras are not drawn; pulled out by `find-active-camera`. `:flow/viewport` emits `:op/viewport-push` / `:op/viewport-pop` so terminals can clip and switch projections mid-stream. `walk-once` is pure: takes a db value, returns a vector. The transducer (`walk-xf`) handles the per-input update; `stream-transduce` handles cursor I/O when a stream is involved.

## Paint Stage (Flutter terminal)

- The terminal is `paint-rf`: a reducing function `(fn [painter op-frame] (paint! painter op-frame) painter)`. It can be the `rf` passed to a fully-fused `stream-transduce`, or it can be the consumer end of a stream-mediated path.
- `m/ValueNotifier<List>` named `flow/op-frame` holds the latest op-vector. `paint-rf` writes its input to the notifier; the actual Canvas calls happen inside Flutter's paint loop.
- `mount-flow-stream!` is a convenience: it sets up a `stream-transduce` from a given op-frame stream into `paint-rf`, running on a `dart:async` loop. The fused-pipeline equivalent skips this and passes `paint-rf` directly into the producer's `stream-transduce`.
- `FlowPainter` extends `CustomPainter` takes the notifier as `repaint:`. `paint(canvas, size)` walks the frame; `case :op/kind` dispatches to `canvas.drawRect` / `drawCircle` / `drawParagraph`. v1 cube is wireframe: project 8 corners through `op/world * op/projected`, draw 12 line segments. `:op/viewport-push` saves canvas state and clips; `:op/viewport-pop` restores. `shouldRepaint` returns true whenever the notifier identity changes (Flutter handles this via `repaint:`).

## Animation Pipeline (the v1 demo)

The v1 demo runs the fused composition (no intermediate op-stream materialized) for the hot path, with the intent stream still materialized so Yin.VM and the REPL can observe and inject mutations:

```text
 mutation-emitter ─→ intent-stream ─→ (comp walk-xf paint-rf) ─→ pixels
   (Timer.periodic)    (materialized)         (fused)            (terminal)
```

1. **Mutation emitter** — `Timer.periodic` (~16 ms ≈ 60 Hz). Each tick: compute new angle = `elapsed * 0.001`; call `dao.flow.animate/emit-attr-change!` with the cube's transform entity, `:transform/rotate`, `[0 angle 0]`, next `t`. Writes one mutation datom to the intent stream.
2. **Fused walk + paint** — a single `stream-transduce` reads the intent stream, runs `walk-xf` over each transaction (updating its in-memory `dao.db`, computing the op-frame), and feeds the frame straight into `paint-rf`. No intermediate op-stream.
3. **Painter** — `paint-rf` writes the frame to the `ValueNotifier`; `FlowPainter` redraws when notified.

A debug variant flips a config flag to materialize the op-stream and split the fused pipeline into two `stream-transduce` calls — useful for inspecting frames or running a tap (e.g., a frame-counter or a logger). The user-facing API is the same: `(start! intent-stream painter {:tap? false})`.

The painter does not poll time; the emitter does not call the painter. Stigmergy. Every visible motion has a concrete datom on the intent stream that can be replayed, branched, or observed — even though the op-frame layer is fused away on the hot path.

## TDD Order (per CLAUDE.md)

Write each test first, watch it fail, implement to pass:

1. `hiccup-test/round-trip-empty-scene` — `(= [:flow/scene] (datoms->hiccup (hiccup->datoms [:flow/scene])))`.
2. `hiccup-test/round-trip-cube-with-transform` — full attribute fidelity.
3. `hiccup-test/parent-and-sort-key-assigned` — internal datom shape correctness.
4. `transform-test/identity-and-compose` — TRS algebra; perspective transform of `[0 0 -1]` lands at expected NDC.
5. `walk-test/single-rect-emits-one-op-frame` — input one rect intent, output `[op-rect, op-end-frame]`.
6. `walk-test/nested-group-composes-transforms` — group `[10 0 0]` with rect `[5 0 0]` → world `[15 0 0]`.
7. `walk-test/painter-depth-order` — two cubes at different z produce ops sorted far-to-near.
8. `animate-test/mutation-stream-redrives-walk` — apply rotate mutations; assert walker output rotation evolves at each cursor.
9. `compose-test/insert-passthrough-stage` — assert a no-op middle stage can be inserted between walk and paint with identical output (proves stage composition is honest).
10. `compose-test/fused-equals-streamed` — for the same intent input, the fused composition `(stream-transduce (comp walk-xf passthrough-xf) collect-rf …)` produces the same op-frame sequence as the stream-mediated equivalent that materializes an intermediate stream. This pins the equivalence contract: fusing must never change semantics.
11. (After painter exists) `flow_smoke_test.dart` — canned op-frame produces expected Canvas call counts.

## Verification

```bash
clj -M:test -n dao.flow.hiccup-test          # tight loop while implementing
clj -M:test -n dao.flow.walk-test
clj -M:test                                  # full suite stays green
clj -M:kondo --lint src/cljc/dao/flow.cljc src/cljc/dao/flow/
clj -M:cljd compile                          # Flutter compile clean
flutter run                                  # visual check: rotating cube, smooth motion
```

**Successful run:** green test summary; clean kondo; cljd compile no errors; flutter run opens a window with a smoothly rotating wireframe cube whose motion stops cleanly when the mutation-emitter `Timer` is cancelled (proving the painter is truly stream-driven, not polling).

## Risks & Trade-offs

- **Walk is O(n log n) per frame.** Fine for hundreds of nodes. Mitigation later: memoize subtree world-transforms; dirty-track via the mutation stream (a future "diff" stage).
- **Mat4 math allocates vectors of 16 doubles.** v1 accepts this cost. Later: typed buffers (`Float64List` / `js/Float64Array` / `double-array`) behind the same `dao.flow.transform` API.
- **Hiccup attrs ambiguity.** Standard rule: second element is attrs iff `(map? %)` and not itself a Hiccup vector. Encoded once in `hiccup->datoms`; tested exhaustively.
- **Round-trip is one-way symmetric.** `hiccup->datoms ∘ datoms->hiccup` round-trips for hiccup-produced datoms; hand-written datom sets may need normalization. Tests canonicalize before comparing.
- **CustomPainter is y-down 2D.** Walk emits clip space y-up. Painter applies final `y *= -1` and viewport scale. One easy bug; pinned with a deliberate test.
- **Stream backpressure.** v1's emitter and walker run unbounded; if the painter falls behind, op frames pile up. Mitigation: bounded ringbuffer transport (already in `src/cljc/dao/stream/ringbuffer.cljc`) with overflow policy (`drop-oldest` for animation; block for replay).

## Out of Scope for v1

- GPU rendering interpreter (WebGPU, Vulkan, Metal). v1's terminal is CPU Flutter Canvas.
- GPU compute interpreter. The architecture admits one; v1 doesn't ship it.
- Optimizer stages (transform-folding, draw-call batching, dirty-rectangle).
- Alternate producers (Yin.VM emitter, network feed, replay file).
- PBR, shadows, AO, anti-aliasing beyond Flutter defaults, shaders.
- Multi-camera (except via `:flow/viewport`).
- Datalog queries over scene graph (the schema supports them; no API yet).
- Picking / hit testing.
- Asset loading (textures, glTF, custom fonts).
- Hot-reload of designer Hiccup from disk.
- Widget framework on top of `:geom/rect` + `:geom/text` (events, layout, focus). Primitives ship in v1; widgets are the natural follow-up.
