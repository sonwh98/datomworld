Created-GMT: 2026-10-19 09:13:40 GMT
Created-Local: 2026-10-19 16:13:40 +07

# Task: final decomposition — the ABI doctrine spec, the wasm stream implementation spec, and the Rust engine doc

Owner-approved restructure (2026-10-19, in-conversation). Current tree state: the
restructured five files (verified PASS by deepseek —
collab/1792393210523-architect-wasm-tree-verification.deepseek-v4-pro.findings.md).
This is the last structural step before commit. Normative content moves as-is;
supersessions get dated notes; single-home rule enforced throughout.

## 1. CREATE docs/design/datom.world.abi.md — THE ABI doctrine spec

Consolidate, from datom.world.md's Streams → Host Boundaries section, the ABI
doctrine that has outgrown it (all of it moves; the master doc keeps a short
Host Boundaries section that states the rule in two lines and points here):
- "The system ABI" paragraph (dao.stream is the system's binary interface...)
- The naming doctrine ("the system ABI" reserved; qualified guest-host names)
- The owner rulings: 2026-10-19 one-ABI ruling ("the boundary is drawn, not
  found...") as amended by the boundary clarification (performance clause
  superseded: no second ABI at all) and the abstraction taxonomy ruling
  (verbatim owner quote: "dao.stream is an abstraction boundary. It is not a
  transport implementation. It can be implemented using a ring-buffer, a
  websocket or UDP").
- The custody-subset consequence (what crosses a trust domain is a strict
  subset; custody gates mark the seam).
- The floor argument (world-facts cannot be computed by shipped code; the seam
  is immovable, the frontier is movable).
- The implementations taxonomy: ring-buffer, websocket (dao.stream.ws.md),
  wasm imports (dao.stream.wasm.md), UDP — implementations of the abstraction,
  never second ABIs.

Split rule versus dao.stream.md, stated in the doc's opening: dao.stream.md
owns WHAT the contract is (operations, outcome maps, batch/observer semantics);
datom.world.abi.md owns WHY that contract is the only boundary (role, rulings,
taxonomy, floor, custody). Status header: design/doctrine; provenance note
(2026-10-19, distilled from the session's owner rulings; five-round wasm
consensus lineage by reference). Style: match the repo's design-doc conventions
(Status, authorities, prose) — authorities: datom.world.md, dao.stream.md,
yin.vm.engine.md, yin.vm.debruijn.register.md.

## 2. CREATE docs/design/dao.stream.wasm.md — the wasm implementation of the dao.stream abstraction

Move engine.md §E.3 (the wasm import-surface implementation: adapter import
signatures over linear memory, the stream operations exposed, outcome-map
encoding as return values, canonical jing CBOR bytes as payload, buffer
management, buffer-full as the stream's full outcome) into this new spec.
- Read docs/design/dao.stream.ws.md first; match its role: an IMPLEMENTATION
  spec of dao.stream, sibling of the ws implementation. Family style follows.
- Title/framing: "the wasm implementation of the dao.stream abstraction" —
  never "the wasm ABI". The import surface exists because wasm's only door is
  imported functions over linear memory; the adapters implement the same
  abstraction as ws/UDP/ring-buffer.
- Language-neutral: the spec binds the abstraction to wasm imports; it does
  not assume a Rust host (CLJS hosts implement it too). The Rust host-side
  implementation belongs to yin.vm.rust-engine.md, cross-referenced.
- The in-memory (ring-buffer) implementation servicing in-instance streams
  with zero host crossings: note as a permitted composition (co-resident
  producers), per the taxonomy ruling.
- dao.stream.md itself: add the wasm implementation pointer alongside its
  existing ws pointer (minimal edit, nothing else in that landed spec moves).

## 3. SLIM docs/design/yin.vm.engine.md

§E.3's moved content is replaced by a pointer to dao.stream.wasm.md (single
home). Retained in §E: the machine-state-as-data discipline, value arena and
handles (in-instance representation), ownership/GC/snapshot rules, the embedder
seam (single-engine-port: the in-instance Rust engine IS the one engine; CLJS
remains the oracle), and the conformance method (§E.5 — lanes now cite
dao.stream.wasm.md for the adapter surface). Update internal numbering and
every cross-reference (register.md §10, yang.wasm.md) to follow.

## 4. RENAME docs/design/yin.vm.rust-kernel.md → docs/design/yin.vm.rust-engine.md and rescope

(git mv not needed — the file is untracked; plain rename.) Rescope it as THE
RUST ENGINE IMPLEMENTATION PLAN, the counterpart of yang.wasm's compiler plan:
- New opening (replacing the old framing): the implementation plan for the
  engine — compiled into wasm instances as the in-guest engine (the crates the
  wasm lane depends on), or run as a native dao.stream peer — with the LLVM
  lowering as the sibling codegen to yang.wasm, AND the Rust host-side
  implementation of dao.stream.wasm.md's import surface.
- Trim the superseded framing (owner rulings R2): the Level-0/1/2 level
  narrative's Level-1 offload role, contradictions C1/C2's justifications for
  the dissolved boundary — replace with a dated supersession note pointing at
  datom.world.abi.md and dao.stream.wasm.md. KEEP the surviving core intact:
  crate workspace, Value representation, ownership/threads, check-then-commit,
  fault containment, unsafe scoping, the LLVM path (translator/worker/tiers/
  equivalence law/artifact cache), the standalone native node, the K-phase
  descriptions (K0 now points at engine.md §E.5 / register.md §10.4 as the
  kit's home).
- NOTE: the earlier "lines 1–1334 unshifted" constraint is LIFTED by this
  owner-approved restructure — but every OTHER file's citation to
  rust-kernel.md (old name AND old line numbers) must be updated to the new
  name and correct sections. Sweep datom.world.abi.md, engine.md, register.md,
  yang.wasm.md for `rust-kernel.md:NNNN` citations and repoint them.

## 5. Cross-reference sweep

All relative links resolve; no references to retired files or to the old
rust-kernel name; single-home checks: ABI doctrine lives ONLY in
datom.world.abi.md (datom.world.md summarizes in ≤4 lines + pointer); the wasm
adapter surface lives ONLY in dao.stream.wasm.md (engine.md points); no src/
or test/ changes; collab/ untouched except your logs.

Reply with: files created/renamed/modified, the section map of each new doc,
every citation repointed (old → new), and confirmation of the single-home
sweep.
