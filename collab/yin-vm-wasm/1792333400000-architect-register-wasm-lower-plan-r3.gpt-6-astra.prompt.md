Created-GMT: 2026-10-18 14:25:00 GMT
Created-Local: 2026-10-18 21:25:00 +07

Two updates since your r2 revision (which argon has APPROVED — congratulations):

1. NEW OWNER REQUIREMENT (binding, from the owner, 2026-10-18 21:0x +07):
   "lowering to wasm should work also on other wasm runtime not just the browser or
   node. it should work with wasmer or wasmtime."

   Revise the plan so the wasm lane is embedder-agnostic. At minimum:
   - Define the host-service seam as an explicit, versioned IMPORT ABI over linear
     memory (imported functions carrying plain-data request/response messages at
     agreed offsets, status codes returned as values) — so the same binary runs under
     a CLJS adapter (Node/browser) AND native embedders (wasmtime, wasmer), which
     have no JS host. The CLJS engine implementation becomes one conforming embedder,
     not the definition of the boundary.
   - Restrict the feature profile to core wasm 1.0 (+ mutable globals, call_indirect
     tables); every extension behind the W0 probe gate, none assumed. Note that
     reference types/externref are already excluded and WasmGC (finalized in Wasm 3.0,
     2025-09) stays a non-goal — and that uneven native-runtime support for post-MVP
     features is a reason, not a risk, for that cut.
   - Add cross-runtime parity gates to the slices: W1+ differential acceptance runs
     under Node AND at least one native runtime (wasmtime or wasmer), comparing full
     traces, not just final values. Name the pinned toolchain versions in W0.
   - Keep WASI out of the VM core; a WASI-based embedder may layer streams/FFI over
     the import ABI.
   - Note the honest trade-off: host-service calls now cross the wasm boundary as
     serialized linear-memory messages for every embedder; the self-contained
     interpreter-loop variant remains the W6 optimization axis if crossing cost
     dominates.

2. Review-quality instruction (from the orchestrator): argon's r2 review verdict was
   correct but its citations drifted (functions that do not exist, an 18-opcode count,
   NaN-boxing attributed to your §1.1). For r3, make the plan maximally checkable:
   ensure every code reference in the plan names a function/symbol that exists at the
   cited file, and keep the existing correct citations as-is.

Deliverable: write the complete revised plan (all sections, W0–W6, revision log) to:
collab/1792329904305-architect-register-wasm-lower-plan-r3.gpt-6-astra.findings.md
with a "Round 3 changes" section at the end listing exactly what the portability
requirement changed. Do not edit any other file.
