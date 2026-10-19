Created-GMT: 2026-10-19 07:00:10 GMT
Created-Local: 2026-10-19 14:00:10 +07

# Task: independent verification of the restructured wasm spec tree

You are the independent verifier (the author was gpt-6-astra/codex; fable is
quota-reserved; argon is nearly out). You know this design from the two ABI
polls — this is the verification of the tree that landed after the owner's
final rulings.

The owner's three rulings, in order (all recorded as dated notes in the tree):

R1 (one-ABI): the boundary is drawn, not found; dao.stream is the only ABI.
R2 (boundary clarification): yang.wasm is the compiler implementation plan
    (register bytecode → wasm); the wasm boundary IS dao.stream; physically it
    is implemented via whatever wasm exposes (imported functions over linear
    memory); the exposed functions are ADAPTERS to dao.stream. Engine-in-guest
    is THE wasm architecture: the Rust engine crates compile into the instance;
    only effects/facts cross, via the adapters; the former service protocol
    (yin.vm.engine.host.v1 vocabulary, TLV codec, comparator, NEED_SPACE) is
    dissolved by supersession.
R3 (abstraction taxonomy): dao.stream is an ABSTRACTION BOUNDARY, not a
    transport; ring-buffer, websocket, UDP, and the wasm import surface are
    IMPLEMENTATIONS of it. Never "transport binding of the ABI."

Files to verify (read all five in full):
1. docs/design/datom.world.md — Streams → Host Boundaries (system ABI para,
   owner ruling, taxonomy note)
2. docs/design/yang.wasm.md — rescoped compiler implementation plan
3. docs/design/yin.vm.engine.md — §E extension (E.3 now the wasm
   implementation of the dao.stream abstraction)
4. docs/design/yin.vm.debruijn.register.md — §10 wasm execution instance
5. docs/design/yin.vm.rust-kernel.md — footer pointers only; lines 1–1334
   must be unshifted (verify)

Verification checklist:
A. RULINGS FAITHFUL: does each file state R1–R3 correctly, in the owner's
   taxonomy, with no residual "second ABI", "transport binding", or
   service-protocol vocabulary presented as live (provenance/supersession
   mentions are legitimate)?
B. COMPLETE SUPERSESSION: the dissolved protocol's machinery (control block,
   TLV tags, expanded-canonical-bytes comparator, NEED_SPACE/fetch-response,
   service-status set) is gone as normative text everywhere, with dated
   supersession notes; nothing dangles (no section references a deleted
   subsection).
C. STRUCTURAL COHERENCE of the new design: engine-in-guest (crates in the
   instance, direct calls/shared linear memory, no host service crossings);
   adapters implement dao.stream operations with the stream's own exhaustive
   outcome maps and jing CBOR bytes; two codecs only (jing CBOR, register
   encoding); the CLJS reference remains the conformance oracle; wasm32
   compilability is an early gate; W0–W6 is the node-lane schedule (K0/oracle
   first; Wasmer by W5; W6 codegen-only).
D. SURVIVOR INTEGRITY: the frozen content that should have survived the
   restructure is intact — all 23 opcode lowering rows, the trampoline/
   structuring codegen law, per-image-row attach and artifact-cache rules,
   the register ten-region memory table, continuation payload/restore
   semantics, fixture schema and conformance method, single-engine-port rule.
E. MECHANICAL: rust-kernel.md lines 1–1334 unshifted; no src/ or test/
   changes (git status); all relative markdown links resolve; no dead links
   to retired files.

Write your verification to:
collab/1792393210523-architect-wasm-tree-verification.deepseek-v4-pro.findings.md
End with PASS (ready to commit) or FAIL with specific, file:line-anchored
corrections. Read-only except that findings file.
