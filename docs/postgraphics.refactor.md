# Postgraphics Refactor: Phase 5 Evaluation

Evaluation of the Flutter split (`dao.postgraphics.flutter.*`) against the design in
`docs/design/dao.postgraphics.software.md`, as of the commits following
`be7bf425`.

> **Snapshot note.** This is a Phase 5 review. Phase 6 (lighting parity — the GPU
> shaders now mirror `raster/shade-mesh-fragment`) and the later shared-core split
> (the old `software.cljc` became `raster.cljc` for the shade/rasterize core and
> `packing.cljc` for the GPU uniform/buffer contract) landed afterward. Those were
> orthogonal to the gaps below: **all 7 remain open** as last verified against the
> tree. Namespace references here have been updated to the post-split names.

---

## Architecture: Correct

The three-layer separation follows the design doc exactly:

| Layer | File | Role |
|---|---|---|
| Dispatcher + 2D overlay | `flutter.cljd` | `PostgraphicsPainter` picks GPU or software; paints `:draw-2d` and `:text` over either path. |
| GPU submitter | `flutter/gpu.cljd` | Consumes canonical lowered frame, submits via Flutter GPU (Impeller). |
| Software submitter | `flutter/canvas.cljd` | Rasterizes via shared `raster.cljc`, blits once through `decodeImageFromPixels`. |

Both backends consume 16-vec matrices and resolve per-vertex attributes through
the shared `raster/vertex-attrs`. The `PgTexture` dual-handle (`gpu` + `rgba`)
lets the GPU path sample the GPU texture and the software path sample the CPU
bytes without forking scene data.

The pixel-buffer + single-blit perf fix is present: `flutter/canvas.cljd`
allocates one `Uint8List`, runs `raster/render-3d!`, then calls
`decodeImageFromPixels` once. This is the explicit fix for the per-pixel
`drawRect` path that killed the prior attempt.

---

## Gaps

### 1. Global mutable state is multi-widget unsafe

Both backends use `defonce` global atoms:

```clojure
;; flutter/canvas.cljd
(defonce ^:private last-image* (atom nil))

;; flutter/gpu.cljd
(defonce ^:private gpu-state
  (atom {:context nil, :pipeline nil, ...}))
```

`last-image*` is the critical failure: two mounted widgets share one decoded
image. Frame N from widget A becomes frame N for widget B. The GPU state is
mostly shareable (one context, one pipeline), but the canvas-specific image
cache should be widget-scoped, stashed on the painter or passed through the
notifier.

**Fix:** Move `last-image*` into the `PostgraphicsPainter` deftype or the
`ValueNotifier` closure so each widget owns its own blit target.

### 2. Emulator check shells out on every paint frame

`is-emulator?` runs `Process.runSync` for `getprop ro.kernel.qemu` every time
`gpu-available?` is called. `gpu-available?` is invoked inside
`PostgraphicsPainter.paint`, so this is a shell-out per frame.

**Fix:** Cache the emulator result in `gpu-state` (or a separate `defonce`)
once at first probe. The environment does not change mid-session.

### 3. Silent error swallowing in the painter

```clojure
(catch Object e (dart/print (str "Paint failed: " e)))
```

The painter catches everything and prints. A malformed frame, a GPU init
failure, or a shader compilation error produces no visible artifact and no
stream signal. The widget freezes on the last good frame. This violates the
project invariant that side effects must appear as stream emissions; errors
should reach `:on-error` or the `:signal-stream` as
`:dao.terminal/rejection`, not disappear into `stdout`.

**Fix:** Remove the blanket catch, or route exceptions through the terminal's
rejection mechanism before printing.

### 4. Misleading GPU fallback error message

`submit-gpu!` throws:

```
"Flutter GPU is not available; Canvas fallback is disabled"
```

This is false: Canvas fallback is not disabled. The dispatcher already fell
back before reaching this code. This path is only hit when `gpu-available?`
returned `true` (context exists, not emulator) but `init-gpu!` failed
silently, leaving `context` nil in the atom.

**Fix:** Change the message to "Flutter GPU initialization failed". Better,
have the dispatcher handle init failure rather than the submitter.

### 5. No decode failure handling

```clojure
(ui/decodeImageFromPixels rgba w h ui/PixelFormat.rgba8888
  (fn [^ui/Image image] (reset! last-image* image)))
```

If `decodeImageFromPixels` fails (OOM, invalid dimensions, impeller bug), the
callback never fires and `last-image*` keeps the stale image forever. There is
no errback or `try/catch` around the decode.

**Fix:** Provide an error callback that at least clears `last-image*` so the
widget goes black instead of showing an old frame indefinitely.

### 6. Per-frame allocation pressure

```clojure
(let [rgba (type/Uint8List. (* w h 4))
      depth (type/Float64List. (* w h))] ...)
```

Every `paint` call allocates fresh buffers. For 1080p that is ~4 MB color +
~8 MB depth = ~12 MB per frame, GC-bound. Also, `Float64List` for depth is
wasteful: `Float32List` is sufficient and would match the web backend's
`Float32Array`.

**Fix:** Pool `rgba` and `depth` buffers keyed by `[w h]`, reallocating only
on resize. Switch depth to `Float32List`.

### 7. GPU submitter claims to support pipelines it does not render

`gpu-supported-draw?` returns `true` for `:draw-2d` and `:text`, but
`submit-gpu!` only handles `:mesh-3d`, `:line-3d`, and `:draw-3d`. The comment
admits it: `;; TODO: implement other pipelines (clear, text, 2D)`. This works
only because the dispatcher overlays them afterward. A direct call to
`submit-gpu!` would silently drop 2D and text.

**Fix:** Either render them inside `submit-gpu!` (to an offscreen texture with
2D composited), or tighten `gpu-supported-draw?` to reject `:draw-2d` and
`:text` so the contract is honest.

---

## Summary

| Dimension | Grade | Notes |
|---|---|---|
| Correctness (single widget) | B+ | Renders correctly. Async blit latency is one frame, as documented. |
| Architecture alignment | A | Follows the design doc's three-layer split precisely. |
| Multi-widget safety | D | Shared `last-image*` is a real bug. |
| Performance | C | Per-frame allocation + per-frame shell-out. |
| Error handling | D | Silent catch-all; no decode failure path; misleading error text. |
| Contract honesty | C | `gpu-supported-draw?` over-promises. |

Phase 5 **lands the refactor** but needs a follow-up pass for widget-scoped
state, cached probes, buffer pooling, and proper error propagation. Those are
the difference between "compiles and demos" and "production Flutter app."

---

## Recommended follow-up order

1. **Cache emulator probe** — one-line fix, biggest per-frame win.
2. **Widget-scope `last-image*`** — move into painter deftype or notifier state.
3. **Buffer pooling** — pool by `[w h]`, switch depth to `Float32List`.
4. **Error propagation** — remove blanket catch; route through terminal signals.
5. **Honest GPU pipeline support** — tighten `gpu-supported-draw?` or implement
   2D/text inside `submit-gpu!`.
6. **Decode failure callback** — clear stale image on error so widget does not
   lie.
