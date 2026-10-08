Created-GMT: 2026-09-10 11:05:22 GMT
Coding-Agent: codex
Session-ID: pending (provider-generated)
# Task: review async init-gpu! fix for Flutter GPU shader loading
Role: Routine Review
Implementers:
- Model: interactive (claude sonnet 5) | Assigned: 2026-09-10 18:05:22 +0700 | Status: active | Rationale: unrelated bug the user hit while verifying the vm-consumers commit; fixed directly, moderate risk (shared rendering code, async rework), user has visually confirmed it works on a real Android device

## Context

Unrelated to any of today's earlier `yin.vm` work. While testing the
Flutter `dao.gui` REPL demo, the user found the separate Earth/Moon demo's
textures were not loading — flat color, no image. Diagnosis (via a
`.catchError` I added first, then removed guesswork) found two independent,
real issues in `src/cljd/dao/postgraphics/flutter/gpu.cljd`:

1. `assets/shaders/simple_mesh.shaderbundle` was compiled by an older
   `impellerc` than the one shipping with the current Flutter SDK (error:
   "Unsupported shader bundle format version: 1, expected: 2"). Fixed by
   rerunning the repo's own `bin/compile-shaders.sh` (unchanged) against the
   current toolchain — a binary asset regen, no source change, not part of
   this diff for review (nothing to read in a binary).
2. Deeper bug, independent of (1): `flutter_gpu`'s `ShaderLibrary.fromAsset`
   now returns `Future<ShaderLibrary?>` (an SDK API change from whenever
   this code was written, when it was apparently synchronous). The old
   `init-gpu!` treated the return value as already-resolved
   (`^gpu/ShaderLibrary lib`), producing `type 'Future<ShaderLibrary?>' is
   not a subtype of type 'ShaderLibrary' in type cast` on every single
   frame, on every demo using the GPU path (earth-moon, solar-system,
   voxel, artifact — anything through `dao.postgraphics.flutter`'s GPU
   backend).

## The fix

`git diff -- src/cljd/dao/postgraphics/flutter/gpu.cljd
src/cljd/datomworld/demo/earth_moon.cljd` in
/Users/sto/workspace/datomworld is the diff to review. Summary:

- Extracted the post-load setup (pipeline/host-buffer/white-texture
  creation, `gpu-state` population) into a new `install-gpu-state!` helper,
  called from a `.then` callback on the `ShaderLibrary.fromAsset` future
  instead of inline after a synchronous "value."
- Added `gpu-init-inflight?`, a `defonce` atom guarding against firing a new
  asset load every frame while one is already in flight — `init-gpu!` is
  called unconditionally every frame from `submit-gpu!` and
  `create-rgba-texture!`, both of which were and remain synchronous
  (neither's signature changed); a still-nil `:context` in `gpu-state` is
  the existing "not ready" signal both already handle (the former rejects
  the frame with `:unsupported-op`, matching its pre-existing "GPU not
  available" rejection path; the latter's `when context` guard already
  tolerated nil).
- The inflight flag is cleared in both the `.then` success path and the
  `.catchError` failure path, so a transient failure is retried on the next
  frame — matching the old code's actual behavior (it also retried every
  single frame on failure, since only `:context` being set stopped it).
- `earth_moon.cljd`'s `ensure-asset-texture!` gained a `.catchError` with a
  diagnostic print (previously bare `.then`, silently swallowing any
  rejection) — this is what surfaced both bugs above; kept as a permanent
  improvement, mirroring the existing `ensure-ring-texture!`'s try/catch a
  few lines below it in the same file.

## Verification already run (do not rerun; trust and build on it)

- `clojure -M:kondo --lint` on both files: 0 errors (2 pre-existing unused-
  binding warnings elsewhere in `gpu.cljd`, unrelated to this diff).
- `clojure -M:cljd compile datomworld.demo.earth-moon`: compiles clean.
- `bb test:cljd` (full Dart suite, including
  `test/dao/postgraphics/flutter_test.cljd` and `flutter_cljd_test.cljd`):
  1179/1179 passed.
- **User-verified on a real Android device** (not an emulator): before the
  fix, console showed the exact errors described above and a flat-color
  sphere with no texture; after both fixes (shader bundle regen + this
  async rework), the user confirmed "texture loads now."

## What to focus on

- Is the re-entrancy guard (`gpu-init-inflight?`) actually race-safe? Dart
  is single-threaded/cooperatively-scheduled, so there's no true data race,
  but check the guard is set/cleared at points that can't leave it
  permanently `true` (e.g. does every path that sets it to `true` have a
  matching reset, including if `gpu/ShaderLibrary.fromAsset` itself throws
  synchronously before returning a Future, rather than the Future rejecting
  asynchronously)?
- `install-gpu-state!`'s type hint changed from an inline unhinted `lib` to
  `^gpu/ShaderLibrary lib` as a function parameter — does this create the
  same "trusting an unresolved type" risk the original bug had, or is it
  safe because by the time `install-gpu-state!` runs (inside `.then`), `lib`
  really is a resolved `ShaderLibrary` (the `.then` callback's parameter
  type)?
- Any other caller or code path (beyond `submit-gpu!` and
  `create-rgba-texture!`) that assumes `init-gpu!` synchronously populates
  `gpu-state` before returning, which this diff would now silently break?
- Is retrying every frame after a permanent failure (e.g. the shader bundle
  is simply missing from the asset bundle, not just stale) a reasonable
  behavior to preserve, or worth flagging even though it's not a regression
  from this diff (the old code did this too, just via a different failure
  mode each time)?

Read-only: Read, read-only Bash. Do not edit anything. State a plain
verdict.
