Created-GMT: 2026-09-02 18:09:39 GMT
Created-Local: 2026-09-03 02:09:39 Asia/Shanghai

# Role: Lead Systems Architecture Reviewer — re-confirm after a scope reduction

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-03 01:53:55 Asia/Shanghai | Status: active | Rationale: Architect seat; sign-off authority
- Round 4 | Assigned: 2026-09-03 02:09:39 Asia/Shanghai | Rationale: sign-off was granted, then the plan changed; re-confirm rather than assume

You granted SIGN-OFF on `docs/design/yin.vm.implementation-plan.md` in round
3. The user then asked to postpone telemetry if it complicated the slice. It
does, and it is cleanly separable, so the plan changed. **A granted sign-off on
an edited document is not a sign-off**, hence this round.

## The change

`emit-snapshot` short-circuits on `(if-not (enabled? state) state …)` and
`enabled?` is `(boolean (get-in state [:telemetry :stream]))`
(`telemetry.cljc:22-24,273-282`). With no stream installed, all ~12 call sites
in `engine`, `ffi` and `ast_walker` are already no-ops.

So `yin.vm.telemetry` becomes a **stub** in the slice: `enabled?` false,
`emit-snapshot` identity, `install`, and a real `type-tag` — real because
`ffi.cljc:118-121` evaluates `(mapv telemetry/type-tag request-args)` as an
argument, so it runs before the no-op check; the stub returns `:opaque` for
handles.

Deferred with it: `append!` outcome reading in the emit path, the
three-surface-before-`map?` ordering, the cursor-ref-without-descent rule, the
`cursor-map?` recognition problem, one stream and its capacity, and the
telemetry tests. New section *Telemetry is a stub*; the census paragraph now
points at it; V3's deliverable is a VM running with telemetry disabled; the
register gains telemetry's absence; the end condition drops the telemetry path.
The consumer plan drops `(telemetry)`, `--telemetry` and `--telemetry-stream`
entirely, making five user-visible changes.

## What to judge

1. **Is the stub sound?** Does anything in the closure reach telemetry outside
   the `enabled?` guard, besides the `type-tag` case already handled?
2. **Does the deferral leave a hole** that a later phase cannot close — any
   decision this slice makes that the real emit path would have to unmake?
3. Does the reduction introduce any inconsistency between the two plans?

Read:
- docs/design/yin.vm.implementation-plan.md
- docs/design/yin.repl.implementation-plan.md
- src/cljc/yin/vm/{telemetry,engine,ffi,ast_walker}.cljc

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with an explicit line:

SIGN-OFF: GRANTED   — implementable as written, or
SIGN-OFF: WITHHELD  — followed by the shortest list of changes that would earn it.

Do not edit files.
