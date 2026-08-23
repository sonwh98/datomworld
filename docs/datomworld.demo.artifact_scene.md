# Implementation Plan: "The Glowing Artifact" Demo

This document is implementation-ready when followed together with the
existing `dao.gui.event` and `dao.postgraphics` contracts. The demo has one
portable state transition layer and two thin host adapters. It does not use
the `dao.gui` compiler.

## Scope and compatibility

The first implementation must render on all existing hosts:

- ClojureDart with `dao.postgraphics.flutter` (which dispatches to `dao.postgraphics.flutter.gpu` when `gpu-available?` is true, otherwise `dao.postgraphics.flutter.canvas` software rendering);
- ClojureScript with `dao.postgraphics.web` (which dispatches to
  `dao.postgraphics.web.gpu` when `gpu-available?` is true, otherwise
  `dao.postgraphics.web.canvas` software rendering).

The required frame is therefore target-free. Reflection targets, HDR targets,
and post-processing are explicitly deferred. Existing Flutter and Canvas2D
backends report `:supports-render-targets? false`, so making them part of the
shared frame would reject the frame. A later GPU-only enhancement may add a
separate frame builder after capability negotiation exists.

The implementation must not use `dao.gui`, `[:gui/view ...]`,
`[:gui/region ...]`, or Hiccup layout in the rendering core. Hiccup is allowed
only in the existing CLJS/Reagent host view because that is the host UI
convention already used by the demos.

## Data flow

```text
native event
  -> host-normalized pointer value
  -> runtime envelope
  -> dao.gui.event binding
  -> :gesture output stream
  -> pure gesture reducer
  -> immutable artifact state
  -> pure frame builder
  -> frame stream
  -> postgraphics widget
```

The hosts own mutable lifecycle resources: atoms, DaoStreams, timers, event
listener registrations, and widget mounting. The CLJC core owns only pure
functions and immutable values.

Shared timing and stream policy. Build every stream with
`(ds/open! {:dao.stream/type :ringbuffer, :capacity n, :eviction-policy p})`:

- tick interval: `16ms` (approximately 60 Hz);
- frame stream: capacity `4`, `:eviction-policy :evict-oldest` (a newer frame
  supersedes an older one);
- runtime-input stream: capacity `1024`, `:eviction-policy :evict-oldest`;
- each event output stream: capacity `64`, `:eviction-policy :evict-oldest`.

Input and event outputs are bounded latest-window streams. If input retention
does gap, the host advances to the current ringbuffer tail, emits a canonical
`:dao.terminal/input-loss` reset, and waits for a fresh `pointerdown` before
recognizing another gesture. A slow host keeps the newest 1024 input values
and newest 64 output values; older values may be discarded.

## File 1: `src/cljc/datomworld/demo/artifact_scene.cljc`

This namespace contains only portable data and pure functions.

### Public contract

```clojure
(def min-zoom 3.0)
(def max-zoom 30.0)
(def pulse-decay-factor 0.9) ; per 16ms tick

(def initial-state
  {:cam-rot-x 0.0
   :cam-rot-y 0.0
   :zoom 10.0
   :pulse 0.0})

(defn build-frame
  [state viewport-size]
  ;; returns a frame data value: a vector of postgraphics operation maps.
  ;; It is not validated here; lower/validate-frame! runs at the host/test
  ;; boundary (see Tests).
  ...)
```

`viewport-size` is `[width height]` in logical pixels. Hosts pass the actual
content size, or `[1.0 1.0]` until the first layout measurement. The frame
must remain valid for positive dimensions.

The frame operation order is:

```clojure
[:frame/clear
 :camera3d/set
 :state/depth-test
 :state/depth-write
 :state/lighting-enable
:light/ambient
:light/point
:draw3d/mesh        ; floor
:draw3d/mesh        ; animated halo
:draw3d/mesh        ; artifact
]
```

Use the same operation shape as the existing scene builders. In particular:

- `:camera3d/set` must include `:camera3d/projection`, `:camera3d/fov`,
  `:camera3d/near`, `:camera3d/far`, `:camera3d/position`, and
  `:camera3d/rotation`;
- `:light/point` must include `:position`, `:color`, and an optional positive
  `:intensity`;
- `:color`, `:material/specular`, and `:material/emissive` are `[r g b]`;
- `:frame/clear`, `:fill`, and per-vertex `:colors` are `[r g b a]`;
- mesh indices are indexed triangle data in the same shape accepted by
  `dao.postgraphics.lowering`; define the artifact and floor mesh builders
  privately in this namespace or in a new shared mesh namespace. Do not make
  the artifact demo depend on `earth_moon_scene.cljc`;
- the artifact emissive value is a 3-channel vector derived from a clamped
  pulse and an advancing phase, so the artifact has a visible baseline glow
  that brightens on tap;
- the floor uses an ordinary 4-channel fill and does not sample a target.

Camera semantics are fixed for both hosts. The camera targets the world origin
`[0.0 0.0 0.0]`. `:zoom` is camera distance in world units. With
`pitch = clamp(:cam-rot-x, -1.4835, 1.4835)` and `yaw = :cam-rot-y`, emit:

```clojure
position [(* zoom (cos pitch) (sin yaw))
          (* zoom (sin pitch))
          (* zoom (cos pitch) (cos yaw))]
rotation [(- pitch) yaw 0.0]
```

The implementation must use these signs consistently and test the zero-yaw
and quarter-turn cases. `:zoom` is clamped to `[min-zoom max-zoom]` before
the position is built.

The builder must clamp or sanitize externally supplied numeric state so that a
bad pointer cannot create NaN, negative zoom, or invalid color values.

## File 2: `src/cljc/datomworld/demo/artifact_runner.cljc`

This namespace contains pure state and event functions. It must not create a
global atom, DaoStream, timer, callback, or platform object.

### Pure state contract

```clojure
(def initial-state scene/initial-state)

(def pan-sensitivity 0.005)  ; radians per logical pixel of pan :delta

(def profile-thresholds
  {:motion/slop 18.0
   :tap/max-duration-us 300000
   :multi-tap/max-delay-us 300000
   :multi-tap/slop 100.0
   :long-press/delay-us 500000
   :swipe/min-distance 48.0
   :swipe/max-duration-us 500000
   :swipe/min-velocity 500.0
   :fling/min-velocity 50.0
   :fling/max-velocity 8000.0
   :velocity/window-us 100000
   :edge/width 20.0
   :pressure/start-threshold 0.5
   :pressure/release-threshold 0.5})

(defn input-profile
  [{:keys [generation-id profile-id capabilities]}]
  {:message/kind :dao.terminal/input-profile
   :generation-id generation-id
   :profile-id profile-id
   :capabilities (set capabilities)
   :thresholds profile-thresholds})

(defn reduce-gesture
  [state gesture]
  ;; returns next immutable state
  ...)

(defn tick-state
  [state]
  ;; returns state with pulse multiplied by pulse-decay-factor
  ...)

(defn pointer-runtime-input
  [{:keys [runtime-seq runtime-time-us packet]}]
  {:runtime/seq runtime-seq
   :runtime/time-us runtime-time-us
   :runtime/source :pointer
   :runtime/value packet})
```

`reduce-gesture` handles only the public `:event/kind :gesture` values from
the gesture output stream:

- `:pan`: read `(get-in gesture [:payload :delta])` (frame-to-frame, in
  logical pixels), never the cumulative `[:payload :translation]`, to update
  both camera rotations. Map horizontal delta to `:cam-rot-y` and vertical
  delta to `:cam-rot-x`, each scaled by the `pan-sensitivity` constant
  (radians per logical pixel, e.g. `0.005`);
- `:scale`: read `(get-in gesture [:payload :scale-delta])`, multiply zoom by
  it, then clamp to `[scene/min-zoom scene/max-zoom]`. The transform recognizer
  emits `[:payload :scale]` as a cumulative ratio since recognition start and
  `[:payload :scale-delta]` as the frame-to-frame span ratio; multiplying by
  `:scale` on every update over-accumulates. `:scale-delta` is `1.0` on `:end`,
  so end events are naturally no-ops;
- `:tap`: set pulse to `1.0`;
- all other gesture kinds: return state unchanged.

Missing `:delta` or `:scale-delta` is treated as zero translation or a unit
scale, respectively. Rotation updates clamp pitch to the camera range above.
The profile builder above is production code, not a test-only fallback table;
the same values must be used when booting both hosts.

The exact gesture fields must be read from the existing event tests and kept
in one helper, rather than inferred separately by each host. Add unit tests
for representative `:start`, `:update`, `:end`, and `:recognized` events.

### Event binding transport contract, implemented by the hosts

The binding is not a direct callback API. Each host creates the binding with
DaoStreams:

```clojure
(event/bind
  {:inputs {:runtime-input runtime-input-stream}
   :outputs {:effects effects-stream
             :trace trace-stream
             :pointer pointer-stream
             :keyboard keyboard-stream
             :gesture gesture-stream
             :dispatch dispatch-stream
             :diagnostic diagnostic-stream}})
```

`artifact_runner.cljc` does not construct these streams or the binding. Each
host owns that code. The host appends runtime envelopes to
`runtime-input-stream`, calls `event/advance` until the input is consumed or
the binding parks, and drains the `:gesture` output stream. A gesture is not
application delivery until the host has also installed a subscription.

The host retains the packet when appending to the input stream reports a full
stream, or when `event/advance` returns `:parked` or `:blocked`. A returned
`:input-gap` is a transport fault, not successful consumption: report it and
do not silently advance the application sequence. Diagnostics must be
retained or logged during development.

Each host advances the binding with the same drain loop per pointer packet:

1. Append one runtime envelope to `runtime-input-stream`. If the append
   reports a full stream, retain the packet and retry after the next drain.
2. Call `event/advance`. A `:blocked` status means the input was not consumed:
   retain the packet and retry later. A `:parked` status means the input was
   consumed but its output is still pending because a destination output
   stream is full.
3. Drain every output stream after every `event/advance` call, including a
   `:parked` one: reading a full stream frees capacity so the next advance can
   flush the pending output. Route `:gesture` through `runner/reduce-gesture`
   and retain or log `:diagnostic`.
4. If the call returned `:parked`, drain outputs and call `event/advance`
   again in the same bounded drain turn until the pending output flushes
   (`:advanced`) or a terminal status is returned. If the call returned
   `:blocked`, do not spin: retain the packet, drain once, and schedule one
   retry on the next host progress opportunity (the next timer tick, native
   input, or output-drain notification). Limit each drain turn to a finite
   number of `event/advance` calls, such as `64`; retain the packet for the
   next turn if the limit is reached.
5. Treat `:input-gap` as a transport fault: report it, do not advance.
6. Treat `:end` and `:closed` as terminal teardown statuses. Do not append
   further input after either status.
7. Publish the next input only after the current input and its pending output
   are fully handled, so output backpressure is never mistaken for input
   acceptance.

Output streams are drained with a non-destructive cursor per stream using
`ds/next`. Hosts retain one cursor for each of the seven output streams and
advance it on each `{:ok value}` result. A `:daostream/gap` is reported as a
transport diagnostic and its cursor is advanced; `:blocked` means there is
currently nothing to drain. The host does not use destructive
`ds/drain-one!` for this binding.

On dispose, both hosts shut down in this order so pending cancellation output
is not stranded:

1. append a `:dao.gui.event/teardown` control input;
2. call `event/advance` until it returns `:closed`, drain remaining output
   values to `:end`, and record diagnostics;
3. remove native listeners and stop the timer;
4. close the runtime-input and frame streams. The binding owns closure of the
   seven output streams after teardown; the host owns their creation and final
   draining.

Closing the streams before the teardown flushes would strand pending gesture
cancellation outputs.

The boot sequence is ordered and shared by both hosts:

1. `:terminal` coordinate-space-change;
2. `:geometry` presented geometry;
3. `:profile` input profile;
4. `:subscription` entries for `:pan`, `:scale`, and `:tap`;
5. pointer inputs.

Every envelope has integer monotonic `:runtime/seq` and integer
`:runtime/time-us`. The timestamp is the host event time in microseconds and
must not regress. Every pointer value has monotonic integer `:input-seq`.
Use one generation id, one coordinate-space id, one profile id, and the
current frame id consistently across boot and pointer packets.

### Canonical geometry and profile

The presented geometry must have this shape:

```clojure
{:message/kind :dao.terminal/presented-geometry
 :generation-id generation-id
 :frame-id frame-id
 :coordinate-space-id coordinate-space-id
 :nodes [{:node-id ::artifact-surface
          :interaction/path
          [{:node-id ::artifact-surface
            :recognizers
            [{:recognizer/id [::artifact-surface ::orbit]
              :gesture/kind :pan
              :machine :dao.gui.event/pan
              :config {:contacts {:min 1 :max 2}
                       :axis :free
                       :start-at :slop
                       :contact-loss :degrade
                       :join-after-accept true}
              :arena {:priority 0 :mode :cooperative
                      :coexistence/group ::artifact-motion}}
             ...]
            :touch-action :none}]
          :touch-action :none
          :regions [{:bounds {:x 0.0 :y 0.0
                              :width width :height height}
                      :paint-order 0}]}]}
```

The profile must be a canonical `:dao.terminal/input-profile` message with a
 numeric `:profile-id`, the same generation id, a capabilities set, and the
 exact `runner/profile-thresholds` map. The surface must be regenerated
when its dimensions or coordinate-space id changes.

On resize, treat it as a coordinate-space change: emit a new
`:dao.terminal/coordinate-space-change` with the new `:viewport` and a fresh
`:coordinate-space-id`. `dao.gui.event` cancels all active arenas and clears
geometry in response (the host does not cancel pointers itself), after which
the host presents new geometry with the new coordinate-space id and an
incremented `:frame-id`. The next pointer packet may use the new ids only
after the geometry presentation has been accepted.

The geometry builder must include complete declarations for all three
recognizers. In particular, `:scale` is declared through
`:dao.gui.event/transform`:

```clojure
{:recognizer/id [::artifact-surface ::zoom]
 :gesture/kind :scale
 :machine :dao.gui.event/transform
 :config {:contacts {:min 2 :max 2}
          :scale-slop 0.02
          :contact-loss :degrade}
 :arena {:priority 0 :mode :cooperative
         :coexistence/group ::artifact-motion}}
```

A nil `:scale-slop` disables the span trigger in the transform's accept test,
leaving only centroid translation to start recognition, so a pure pinch that
spreads without moving the centroid never emits `:scale`. A small positive
`:scale-slop` lets span change alone begin the gesture.

`::orbit` (pan) and `::zoom` (scale) share the same cooperative arena group
`::artifact-motion`. They must not use exclusive arenas: an exclusive pan that
accepts on one contact becomes the arena's sole winner and blocks the
two-contact transform from ever accepting, so pinch zoom would never fire.
Cooperative mode with a shared `:coexistence/group` lets both win together,
and pan's `:join-after-accept true` admits the second contact so `::zoom` can
join the accepted orbit arena. Pan widens to `:max 2` and degrades on contact
loss so the camera keeps orbiting the centroid while both fingers zoom.

The `:tap` declaration uses `:machine :dao.gui.event/tap`; listing only a
gesture kind is not a valid declaration. It must also use:

```clojure
{:config {:count 1
          :max-duration-us 300000
          :slop 18.0
          :max-delay-us 300000
          :multi-tap-slop 100.0
          :contacts {:min 1 :max 1}
          :contact-loss :end}
 :arena {:priority 0 :mode :exclusive
         :coexistence/group nil}}
```

Tap is rejected when orbit movement wins, so it does not fire in the middle
of an orbit. The implementation must subscribe to the node's `:tap` event
kind, not only to its recognizer declaration.

## File 3: `src/cljd/datomworld/demo/artifact.cljd`

This host owns:

- a frame ring-buffer stream with capacity `4` and oldest-frame eviction;
- a non-dropping runtime-input stream with capacity `64`;
- seven output streams, each with capacity `64`;
- a terminal signal stream passed to the postgraphics widget;
- the event binding;
- the mutable current state atom;
- runtime and pointer sequence counters;
- a periodic timer;
- Flutter pointer listeners;
- disposal and stream closure.

It requires `dao.postgraphics.flutter`, which mounts:

```clojure
(pg/postgraphics-widget frame-stream
                        :signal-stream signal-stream
                        :on-error on-error
                        :on-paint-error on-paint-error)
```

`signal-stream` is the host-owned terminal signal stream from the ownership
list above; both error callbacks route to the same visible host diagnostic.

It must not pass `runner/tick!` to the widget. The timer calls a host function
that advances the immutable state with `runner/tick-state`, builds a frame
with `scene/build-frame`, and appends it using
`pg/put-frame!`; `dao.postgraphics.flutter` re-exports
`dao.postgraphics.terminal/put-frame!`.

The timer period is exactly `16ms`. The host must retain input while the
binding is parked or blocked, and must not advance `:runtime/seq` or
`:input-seq` until the packet is accepted.

When the Flutter GPU backend is selected, the application bundle must include
the shader asset required by `dao.postgraphics.flutter.gpu`, including
`assets/shaders/simple_mesh.shaderbundle` in `pubspec.yaml`.

The wrapper (`dao.postgraphics.flutter/postgraphics-widget`) accepts:

- `:backend :auto|:gpu|:software` (default `:auto`); `:auto` selects GPU when
  `gpu-available?` is true and otherwise selects software. An explicit `:gpu`
  request remains GPU even when unavailable, so configuration failures are
  observable; any other keyword throws `ex-info` with the allowed set.
- `:on-error` for frame-validation failures and `:on-paint-error` for
  lowering, GPU-submit, or software-paint failures; route both to a visible
  host diagnostic. If omitted, validation errors are printed with the
  rejection reason.
- `:signal-stream`, the dao.stream receiving canonical terminal signals
  (`:dao.terminal/reset`, `:dao.terminal/rejection`). The wrapper accepts
  `nil`, but this demo requires a host-owned signal stream because it
  participates in `dao.gui.event`; always pass that stream here.

Exact backend capability and failure semantics (from the current
implementation):

- Both backends validate and lower frames with
  `{:supports-render-targets? false, :supports-image? false}` plus
  `:texture-source-valid?` accepting only `gpu/Texture` and
  `dao.postgraphics.flutter.texture/PgTexture` values. The demo frame
  therefore must not use render targets, image ops, or foreign texture
  sources.
- `GPU_RENDERER_POLICY` declares the supported pipelines
  `#{:clear :camera-reset :mesh-3d :draw-3d :line-3d :draw-2d :text}` and
  3D ops `#{:draw3d/mesh :draw3d/triangles :draw3d/lines}`. The demo frame
  (clear, camera, state, lights, `:draw3d/mesh`) is within this set.
- A rejected frame never reaches painting: the binding reports it through
  `:on-error` and the previously presented frame stays rendered.
- Paint failures are caught per paint attempt: `:on-paint-error` receives
  the exception, a diagnostic is printed, and the paint call returns without
  falling back. The widget never swaps backends or drops the current frame
  on error; the host may remount with `:backend :software` when that is the
  desired recovery. When 3D submission succeeds, 2D and text passes are
  painted through the shared `ui/Canvas` on both backends; if 3D submission
  fails, the enclosing paint attempt stops after reporting the error.
- The shader asset must be present in `pubspec.yaml` when GPU mode is used
  (`assets/shaders/simple_mesh.shaderbundle`).

Flutter events are normalized to the same pointer value used by CLJS:

```clojure
{:input/kind :pointer
 :generation-id generation-id
 :frame-id frame-id
 :coordinate-space-id coordinate-space-id
 :profile-id profile-id
 :input-seq input-seq
 :pointer {:id pointer-id
           :type pointer-type
           :primary? primary?            ; host-derived, not a literal
           :buttons buttons              ; host button state
           :modifiers modifiers          ; host modifiers, not #{}
           :pressure pressure}           ; only when supported
 :phase :down                         ; :move, :up, or :cancel
 :samples [{:time-us time-us
            :position {:x x :y y}
            :sample/kind :actual}]}
```

Map pointer fields explicitly, never with fixed literals. Flutter's
`PointerEvent` has no `primary` property. For Flutter, maintain active pointer
ids and mark the lowest active id as primary; browser input may use
`PointerEvent.isPrimary`. `:buttons`, `:modifiers`, and `:pressure` come from
the host event when supported. Use one canonical pointer type vocabulary:
`:touch`, `:mouse`, and `:stylus`. `:type` and `:primary?` must be correct for
every contact, or the scale recognizer and desktop input fidelity break.

The host wraps this value in the runtime envelope described above. Pointer
coordinates and geometry bounds must use the same logical coordinate space.
Normalize Flutter device kinds explicitly: touch to `:touch`, mouse and
trackpad to `:mouse`, and stylus to `:stylus` when supported. Do not hard-code
every device as touch.

Implement `start!`, `stop!`, and `view`. `stop!` submits teardown, drains the
binding, removes listeners, stops the timer, and then closes host-owned
input/frame streams. `view` owns the `Listener` and the postgraphics widget.
Repeated `start!` calls must not create duplicate timers or bindings.

## File 4: `src/cljs/datomworld/demo/artifact.cljs`

This host follows the existing `solar_system.cljs` and `earth_moon.cljs`
pattern. It owns a frame stream, a terminal signal stream, state atom, event
binding, sequence counters, and listener lifecycle. It uses
`dao.postgraphics.web/postgraphics-widget` with the frame stream, the
`:signal-stream`, and `:on-error`. No separate `requestAnimationFrame` loop
is needed: the widget consumes the frame stream.

Use the same exact stream capacities and `16ms` timer policy as the Flutter
host.
The runtime-input stream must not evict old packets. Retain packets while the
binding is parked or blocked.

Attach DOM pointer listeners to the actual canvas through the widget ref or a
wrapper component. Set the canvas CSS `touch-action` to `none`. On pointer
down, call `setPointerCapture(pointerId)` when available; release capture on
up and cancel, with a window-listener fallback when capture is unavailable.
Convert client coordinates to CSS logical coordinates, preserve pointer ids,
map `pointercancel`, and remove every listener in `dispose!`. Normalize
`pointerType` to `:mouse`, `:touch`, or `:stylus`, and preserve pressure when
present. Without capture, a pointer leaving the canvas mid-gesture can strand
an active arena in the event interpreter.

`dao.postgraphics.web` sizes the canvas backing store in device pixels
(`devicePixelRatio * css-size`) and defaults its viewport to those physical
dimensions, while browser `clientX`/`clientY` are CSS pixels. Use CSS logical
pixels as the demo coordinate space: pass a `:viewport-size` function returning
the CSS size to the widget, keep geometry bounds in CSS pixels, and treat
client coordinates as CSS pixels without DPR scaling. Rendering and
hit-testing must never mix logical and device pixels.

Implement `dispose!` and `main-view`. The timer appends frames with
`dao.postgraphics.terminal/put-frame!`; no separate `requestAnimationFrame`
loop is needed because the widget consumes the frame stream. The view must
provide a measurable
canvas size and regenerate presented geometry on resize. Use the existing
`responsive/canvas-frame-style` convention or an equivalent measured layout.
The geometry bounds must match the canvas CSS logical size. During resize, the
host queues native pointer events until the coordinate-space-change and new
geometry have both been accepted, then forwards queued events with the new
ids or cancels them explicitly. The shared frame
must remain compatible with the Canvas2D fallback by avoiding render targets
and unsupported image operations. Pass the widget's `:on-error` callback to
the visible host diagnostic path.

## Integration

Wire the demo into `src/cljd/datomworld/demo/main.cljd` and
`src/cljs/datomworld/demo.cljs` using the existing demo lifecycle patterns.
The Flutter route must define and pass a valid `context`; the CLJS route must
add the option, hash mappings, disposal branch, and root-shell branch.

## Tests required before integration

Add `test/datomworld/demo/artifact_scene_test.cljc` covering:

- exact operation order;
- valid camera and mesh fields;
- all color vector channel counts;
- emissive response to pulse;
- valid output for initial and clamped states;
- absence of `:target/push`, `:target/pop`, and unsupported image operations.
- camera position/rotation at zero yaw, quarter-turn yaw, and pitch clamp;
- pulse decay by exactly `scene/pulse-decay-factor` per tick.

Use `lower/validate-frame!` with
`{:supports-render-targets? false :supports-image? false}` (the demo uses no
texture sources, so `:texture-source-valid?` is irrelevant) as the backend
compatibility gate, matching the gate the Flutter widget applies at
presentation.

Add `test/datomworld/demo/artifact_runner_test.cljc` covering:

- immutable gesture reduction for pan, scale, and tap;
- zoom and pulse bounds;
- canonical geometry declarations;
- canonical profile and boot input ordering;
- runtime and pointer sequence monotonicity;
- ignored unsupported gesture kinds.
- one-pointer pan followed by a second pointer joining the shared cooperative
  transform arena;
- profile thresholds installed before the first pointer;
- teardown statuses `:closed` and final output draining;
- resize cancellation and new-coordinate-space ordering.

Add an arena-coexistence test modeled on `scenario-cooperative-scale-rotation`
and `scenario-join-contacts-changed-order` in `fixtures_gen.cljc`: one pointer
down, a second pointer added, then a pinch, asserting that `:scale` events are
emitted while the `:pan` orbit remains well-defined.

Add a resize acceptance test asserting the ordered sequence: old geometry →
`coordinate-space-change(old-id, new-id)` → cancellation outputs → new
geometry(new-id, new-frame-id) → a pointer packet using the new ids. No
pointer packet may be sent between the coordinate-space change and the accepted
new geometry.

Host tests must verify listener/timer disposal and that the frame stream
receives frames. Run the normal CLJ/CLJS test commands and the relevant
backend validation tests before declaring the demo complete.

## Explicitly deferred GPU enhancement

After the cross-platform version works, a separate capability-aware builder
may add `:target/push` with `:target/id`, `:target/size`, and a supported format
such as `:rgba8unorm`, followed by `:target/pop`. That enhancement must be
selected only when the mounted backend advertises render-target and image
support. It must not change the required portable frame contract.
