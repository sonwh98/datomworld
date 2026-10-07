Coding-Agent: claude
Session-ID: 6192f20a-e7f1-4d11-96de-243e8e56d286
# Task: Draft Universal Continuation Format
Role: Lead System Architect
Implementers:
- Model: claude-fable-5.1 | Assigned: 2026-09-14 18:46:00 +07:00 | Status: active | Rationale: Deep systems design and protocol specification.

The Universal Continuation Format (Phase 5) is the core network protocol for datom.world. It dictates how running execution states (CESK) can be paused, serialized, broadcast across networks, and safely resumed on heterogeneous execution engines (JVM, Dart, V8).

Your task is to draft the initial protocol specification in `docs/design/yin.vm.universal-continuation-format.md`.

Read the seed content currently in that file, which lists the 5 blockers (Safepoint Metadata, Portable Encoding, Dependency Context, Ownership Arbitration, Code Identity).
Also review the core philosophies in `docs/design/datom.world.md` and the engine details in `docs/design/yin.vm.semantic.md`.

Expand `docs/design/yin.vm.universal-continuation-format.md` into a full, robust architectural design document. For each of the 5 blockers, propose a strict data-driven protocol solution that aligns with the system's "Code and State are Datoms" axioms. E.g. using `dao.jing` for content-addressing, or stream metadata for ownership locking.

Write your final response confirming completion, and do not remove the warnings from the seed file (they can be resolved in a later implementation phase).
