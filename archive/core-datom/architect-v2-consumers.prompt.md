Created-GMT: 2026-09-08 09:23:00 GMT
Created-Local: 2026-09-08 16:23:00 +0700
Coding-Agent: interactive
Session-ID: not-applicable (interactive seat)
# Task: Design Migration Plan for Remaining v1 VM Consumers
Role: Lead System Architect
Implementers:
- Model: pro | Assigned: 2026-09-08 16:23:00 +0700 | Status: active | Rationale: Requires deep architectural reasoning to plan a multi-file migration.

We need a design and implementation plan to migrate the remaining components and consumers of the v1 `yin.vm` to the v2 architecture (`yin.vm`). This migration is necessary to unblock Phase R4 of the `dao.runtime` implementation plan.

Read first:
- docs/design/datom.world.md
- docs/design/yin.vm.divergence-register.md
- docs/design/dao.runtime.implementation-plan.md
- src/cljc/yin/vm/macro.cljc
- src/cljc/yin/vm/space.cljc
- src/cljc/yin/vm/wasm.cljc

Task:
Evaluate how `yin.vm.macro`, `yin.vm.space`, and `yin.vm.wasm` (and any related test suites, such as `test/yang/*`) should be migrated to `yin.vm` and `dao.stream`. 
Output a markdown artifact `docs/design/yin.vm-consumers.implementation-plan.md` containing the detailed implementation plan, divided into phases, with clear criteria for completion.
Also consider if any of these consumers should simply be deleted if they are experimental and obsolete, or how they adapt to the v2 stream-observer and decoupling.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
