Created-GMT: 2026-10-03 08:54:00 GMT
Created-Local: 2026-10-03 15:54:00 +07:00
Coding-Agent: claude
Session-ID: 42f03eca-2093-49ef-81ff-649d0a0200d9

# Task: Create a design document to port the De Bruijn Register Kernel to Rust

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-03 15:54:00 +07:00 | Status: active | Rationale: User explicitly requested to use fable as the Architect to author this document.

Create a comprehensive design document at `docs/design/yin.vm.rust-kernel.md` for implementing this Rust port of the De Bruijn Register Kernel. 

Context:
We have been analyzing the Yin-Yang architecture as a distributed compiler pipeline. LLVM can act as an interpreter/downstream worker on `dao.stream` that generates native code.
To efficiently lower Yin.vm code to LLVM IR, `src/cljc/yin/vm/debruijn/register.cljc` is the perfect integration point.

Read first:
- src/cljc/yin/vm/debruijn/register.cljc
- docs/design/yin.vm.code-as-tuples.md
- docs/design/yin.vm.semantic.md

Evaluate foundational invariants, ownership boundaries, explicit state and control flow, concurrency and linearization, dynamic extension, host isolation, and design contradictions while authoring this document. The design must respect the core invariants of datom.world: no hidden global state, no implicit control flow, everything is a stream, explicit state transitions, and values over RPC handles.
Detail the memory layout, the CESK execution loop in Rust, the LLVM bridging strategy (LTO and function calls), and how the Rust node acts as an interpreter on `dao.stream`.

Write the design document to disk.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then report: severity | file:line | invariant/evidence | recommended correction.
Also confirm the requested properties that passed review.
