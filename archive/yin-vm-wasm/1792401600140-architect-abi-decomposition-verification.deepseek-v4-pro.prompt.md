Created-GMT: 2026-10-19 09:20:00 GMT
Created-Local: 2026-10-19 16:20:00 +07

# Task: independent verification of the final decomposition (author: gpt-6-astra)

You verified the previous restructure (PASS,
collab/1792393210523-...findings.md). Verify this final owner-approved step
the same way. The owner's ruling set is unchanged (R1 one-ABI, R2 boundary
clarification/engine-in-guest/adapters, R3 abstraction taxonomy: ring-buffer/
websocket/UDP/wasm-imports are IMPLEMENTATIONS of dao.stream, never second
ABIs).

What this step did:
1. CREATED docs/design/datom.world.abi.md — the ABI doctrine spec (system ABI,
   taxonomy, custody, owner rulings, naming/floor, effects/events, adapter
   discipline), consolidated FROM datom.world.md's Host Boundaries.
2. CREATED docs/design/dao.stream.wasm.md — the wasm implementation of the
   dao.stream abstraction (import surface; outcomes/buffers; canonical
   bytes/authority), moved FROM engine.md §E.3. Sibling of dao.stream.ws.md.
3. RENAMED yin.vm.rust-kernel.md → yin.vm.rust-engine.md, rescoped as the
   Rust engine implementation plan (new §6.4: Rust host-adapter
   implementation); superseded Level-1/bridge framing trimmed; K0 points at
   engine §E.5 / register §10.4.
4. MODIFIED datom.world.md (Host Boundaries now 2 lines + link), dao.stream.md
   (wasm pointer added), yin.vm.engine.md (§E.3 now a pointer; E.4/E.5
   retained), yin.vm.debruijn.register.md + yang.wasm.md (citations updated).

Verify:
A. DOCTRINE FIDELITY: datom.world.abi.md contains the owner rulings verbatim
   (one-ABI; boundary-drawn-not-found; the abstraction taxonomy quote),
   the custody-subset consequence, the floor argument, the naming doctrine,
   and the split rule vs dao.stream.md (contract-what vs boundary-why).
   datom.world.md's Host Boundaries remnant is faithful and points correctly.
B. NO CATEGORY ERRORS: dao.stream.wasm.md never calls itself an ABI; it is
   framed as an implementation of the abstraction, language-neutral (no
   Rust-host assumption — Rust host side lives in yin.vm.rust-engine.md §6.4,
   cross-referenced); outcome maps match dao.stream.md; jing CBOR bytes only;
   no TLV/comparator/NEED_SPACE revival.
C. COMPLETENESS OF MOVES: nothing was dropped in the moves (Host Boundaries →
   abi.md; engine §E.3 → dao.stream.wasm.md); engine.md's retained §E content
   (state discipline, arena/GC, embedder seam/single-port, conformance method
   with lanes now citing dao.stream.wasm.md) is intact and coherent.
D. RENAME INTEGRITY: yin.vm.rust-engine.md keeps the surviving core (crates,
   Value, ownership, atomicity, fault containment, unsafe scoping, LLVM path,
   native node, K-phases); superseded framing is trimmed with dated notes, not
   silently; every citation across docs/design now targets the new name and
   correct sections (spot-check ≥10 from the author's repoint table);
   no reference to "rust-kernel" remains anywhere in docs/design.
E. MECHANICAL: dao.stream.md's edit is the pointer only (nothing else in that
   landed spec moved — diff it); no src/ or test/ changes; all relative links
   resolve; no dead links to retired/renamed files. (Four pre-existing broken
   links in telemetry/legacy-UDP docs were reported unchanged — confirm they
   are indeed pre-existing and unrelated.)

Write your verification to:
collab/1792401600140-architect-abi-decomposition-verification.deepseek-v4-pro.findings.md
End with PASS (ready to commit) or FAIL with file:line-anchored corrections.
Read-only except that findings file.
