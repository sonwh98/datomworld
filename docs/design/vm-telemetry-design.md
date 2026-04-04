# VM Telemetry Plan

## Summary

Build a new shared CLJC telemetry module that all current CLJC VMs use to publish live VM state as datoms onto an explicit `dao.stream` sink. The first version is opt-in, disabled by default, and covers `ast-walker`, `semantic`, `stack`, `register`, and `wasm`. It emits full state snapshots at runtime boundaries so downstream JIT and GC interpreters can consume one canonical stream.

## Key Changes

- Add `yin.vm.telemetry` as the shared runtime telemetry layer.
- Extend VM construction opts with a canonical `:telemetry` map:
  - `{:stream <dao.stream> :vm-id <keyword-or-string>}`
  - `:stream` is required when telemetry is enabled.
  - `:vm-id` is optional; default to a generated stable-per-instance ID.
  - Telemetry is disabled when `:telemetry` is absent.
- Extend `yin.vm/empty-state` to carry telemetry runtime state shared by all CLJC VMs:
  - `:telemetry` config
  - `:telemetry-step` monotonic local counter
  - `:telemetry-t` monotonic datom transaction counter
- Define one canonical datom schema for runtime emission, using `:vm/*` attrs.
  - Snapshot root datoms:
    - `:vm/type :vm/snapshot`
    - `:vm/vm-id`
    - `:vm/model`
    - `:vm/step`
    - `:vm/phase` with fixed values: `:init`, `:step`, `:effect`, `:park`, `:resume`, `:blocked`, `:halt`, `:bridge`
    - `:vm/value`
    - `:vm/blocked?`
    - `:vm/halted?`
    - refs to component entities: `:vm/control`, `:vm/environment`, `:vm/store`, `:vm/continuation`
  - Component entities are emitted as child datoms from the snapshot root.
  - Raw host objects are never pushed directly.
    - Stream values become summarized refs by store key / stream ID / cursor position.
    - Functions and opaque host values become tagged summaries such as `:vm.summary/host-fn` or `:vm.summary/opaque`.
- Use full snapshots, not delta-only transitions, at these boundaries:
  - VM creation
  - after every successful step
  - after every handled effect in `yin.vm.engine/handle-effect`
  - after `park-continuation`
  - after `resume-continuation`
  - after bridge dispatch in `yin.vm.host-ffi/bridge-step`
  - when the run loop leaves the VM blocked or halted
- Keep GC support at "allocation and roots only" in v1.
  - Snapshot serialization must preserve enough structure for downstream analyzers to identify:
    - store membership
    - continuation/root references
    - stream and cursor identities
    - parked continuation identities
  - Do not have the VM compute full reachability edges in v1.
- Keep the implementation shared-first.
  - Shared serialization and emission logic lives in `yin.vm.telemetry`.
  - Existing VM namespaces only call shared helpers and provide their model keyword plus their current VM value.
  - Do not duplicate per-VM emission logic beyond the minimal hook points.

## Public Interfaces and Types

- New module namespace: `yin.vm.telemetry`
- New create-vm option accepted by all CLJC VMs:
  - `:telemetry {:stream telemetry-stream :vm-id :optional/id}`
- New internal helper API in `yin.vm.telemetry`:
  - `enabled?`
  - `emit-snapshot`
  - `next-telemetry-state`
  - `snapshot-datoms`
  - `summarize-control`
  - `summarize-environment`
  - `summarize-store`
  - `summarize-continuation`
- `IVMState` remains the source of truth for CESK projection; telemetry reads from it rather than introducing a second VM state interface.

## Test Plan

- Add tests before implementation.
- `test/yin/vm/telemetry_test.cljc`
  - disabled VMs emit nothing by default
  - enabled VM writes datoms to the provided telemetry stream
  - emitted snapshot contains root facts plus control/environment/store/continuation refs
  - telemetry counters are monotonic across multiple steps
- `test/yin/vm/engine_test.cljc`
  - `handle-effect` emits `:effect` snapshots
  - park and resume emit snapshots with correct phase
  - blocked and halt exits from `run-loop` emit terminal snapshots once
- Per-VM smoke tests
  - one test each for `ast_walker`, `semantic`, `stack`, `register`, and `wasm`
  - execute a tiny program and assert telemetry stream contains at least `:init`, `:step`, and `:halt`
- Bridge test
  - `dao.stream.apply` path emits a `:bridge` snapshot and preserves resumed state
- Serialization tests
  - store summaries never embed raw stream instances or host functions directly
  - cursor and stream summaries preserve IDs and positions needed by analyzers

## Assumptions and Defaults

- Scope is CLJC VMs only for this phase; Dart VM is explicitly out of scope.
- Canonical wire format is datoms only.
- Live telemetry is opt-in and must not change VM behavior when disabled.
- Snapshot emission is allowed to add runtime cost because it is an explicit observability mode.
- v1 prioritizes correctness and a stable stream contract over compression; if volume becomes a problem later, delta or checkpoint modes can be added behind the same `:telemetry` surface.
