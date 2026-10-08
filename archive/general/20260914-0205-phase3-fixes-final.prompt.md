Created-GMT: 2026-09-13 19:05:00 GMT
Created-Local: 2026-09-14 02:05:00 +07:00

# Task: Final Phase 3 Fixes (Streams, FFI, Observers, Consumers)

Role: VM & Integration Engineer

Implementers:
- Model: claude-opus-5 | Assigned: 2026-09-14 02:05:00 +07:00 | Status: active

The Architect (gpt-6-astra) evaluated your Phase 3 fixes. The original 3 defects are resolved, but it withheld sign-off due to a new P2 architecture violation in the React UI demo:

1. [P2] No callbacks / host boundary separation: In `continuation_stream.cljs`, host callbacks invoke `emit!`, which deposits data but then synchronously calls `handle-event` and `schedule!`. The foundational rules strictly require adapters to append and return without invoking application code.
   - Correction: Separate event deposition from consumption. Give React UI host adapters only the deposit operation. Let an independently driven session interpreter (e.g. a `requestAnimationFrame` loop) consume events from the stream, update state, and emit scheduling effects.

Modify `continuation_stream.cljs` to fix this issue.
