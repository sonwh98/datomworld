Created-GMT: 2026-09-10 11:16:07 GMT
Coding-Agent: codex
Session-ID: 01a08afe-639a-7a30-99d1-747e7dc9b3fb
# Task: confirm fix for the P1 (textures created during init permanently lose GPU handle)
Role: Routine Review
Implementers:
- Model: interactive (claude sonnet 5) | Assigned: 2026-09-10 18:16:07 +0700 | Status: active | Rationale: same as r1

Your P1 fixed: `init-gpu!`'s boolean inflight guard replaced with
`gpu-ready-future`, a shared/memoized `Future<bool>` via new function
`ensure-gpu-ready!`. `create-rgba-texture!` now awaits it
(`(.then (ensure-gpu-ready!) (fn [_ready?] ...))`) before deciding whether a
GPU handle is possible, and its return type changed from `^tex/PgTexture`
to `^async/Future` accordingly. Its only two callers updated:
`load-rgba-texture-from-asset!`'s innermost `.then` (relies on Dart's
`Future.then` auto-flattening a returned `Future<PgTexture>` — did not add
an extra `.then` there) and `earth_moon.cljd`'s `ensure-ring-texture!`
(was synchronous try/catch, now `.then`/`.catchError`). `submit-gpu!` is
unchanged — still calls the now-trivial `init-gpu!` (fire-and-forget
kickoff via `ensure-gpu-ready!`, discarding the future) and reads
`gpu-state` synchronously each frame, since it's a paint callback that
cannot await.

Re-read `git diff -- src/cljd/dao/postgraphics/flutter/gpu.cljd
src/cljd/datomworld/demo/earth_moon.cljd
src/cljd/dao/postgraphics/flutter/texture.cljd` (the last is a comment-only
clarification). Re-verified: kondo 0 errors on all three; `clojure -M:cljd
compile datomworld.demo.{earth-moon,solar-system,artifact,voxel}` all clear;
`bb test:cljd` 1179/1179 passed (full clean rebuild not repeated this
round — do not ask for it re-run; trust the reported pass).

Confirm:
1. Does `create-rgba-texture!`'s Future now genuinely wait for GPU
   readiness before building the texture, closing the gap you found (a
   texture built during the load window now sees `gpu-state` as it will be
   once ready, not as it was at kickoff time)?
2. Is the Dart `Future.then` auto-flattening assumption in
   `load-rgba-texture-from-asset!` actually correct, or does returning a
   `Future<PgTexture>` from that innermost `.then` callback produce a
   `Future<Future<PgTexture>>` that needs an explicit extra `.then`/await?
3. Any remaining caller or path this still misses?

Read-only, same as before. Plain verdict: is this fix sufficient to close
your P1, or does something else need to change first?
