Created-GMT: 2026-10-19 06:41:58 GMT
Created-Local: 2026-10-19 13:41:58 +07

# Task: restructure the wasm spec tree under the owner's boundary clarification

OWNER DIRECTIVE (2026-10-19, verbatim intent — this is a redesign ruling, superseding
parts of the r5 consensus; record supersessions as dated owner-ruling notes):

"I think we need to go back to the drawing board to clarify our boundaries.
I want doc yang.wasm for the implementation plan of yin.vm.debruijn.register
bytecode to wasm. This is basically a compiler implementation plan.
The ABI for datom.world is the dao.stream. The boundary between wasm and
datom.world is a dao.stream but the way it is implemented is via an interface
that isn't a dao.stream. It is whatever wasm exposes to interface to the outside
world. That is the engineering reality. However, the functions that we expose to
wasm are adapters to dao.stream which is the ABI to yin.vm and the whole
datom.world ecosystem."

What this settles (agreed with the owner in-conversation):
1. ONE ABI: dao.stream — for datom.world and for yin.vm. There is no second,
   process-local service-ABI vocabulary. `yin.vm.engine.host.v1` as a *service
   protocol* (its operation vocabulary, TLV codec, expanded-canonical-bytes
   comparator, service-status set) is DISSOLVED by owner ruling — not "justified
   by performance": it was never an ABI; the imports are dao.stream's wasm
   binding.
2. The wasm boundary is dao.stream semantically; physically it is bound to
   whatever wasm exposes (imported functions over linear memory) — the exposed
   functions are ADAPTERS implementing dao.stream operations (append/poll/
   make/close with the stream's exhaustive outcome maps: ok/full/blocked...),
   carrying canonical jing CBOR bytes. The adapter binding is a transport
   binding of the one ABI — the same status as the ws/UDP bindings — never a
   second semantic contract. Keep the two repository codecs only (jing CBOR,
   debruijn register encoding); the private TLV codec is gone.
3. Engine-in-guest is therefore THE wasm architecture: the Rust engine crates
   (yin-value, yin-scalar, yin-image, yin-kernel, yin-prims, yin-engine,
   yin-wire per docs/design/yin.vm.rust-kernel.md) compile into the wasm
   instance (wasm32-unknown-unknown), sharing linear memory with the compiled
   register bodies; resolve-var, primitives, store, GC, continuations, the
   scheduler all execute in-instance; only effects/facts cross, via the
   adapters. Compiled bodies call the in-instance engine directly (shared
   memory/direct calls — internal linkage, not an ABI).
4. yang.wasm.md is scoped as the COMPILER IMPLEMENTATION PLAN: register
   bytecode → wasm. Host-side content is not its subject.

Reference material (read as needed): the four round-2 poll answers in
collab/1792353000000-architect-one-abi-ruling.* (argon's is closest to this
directive — its Option 1 and its revised W0–W6 node-lane schedule; deepseek's
oracle-first argument: the K0 kit and CLJS reference remain the conformance
oracle and are built first; fable's wasm32-compilability-as-gate and its note
that the node lane's memory/table/transport imports are not an ABI). Current
docs: docs/design/yang.wasm.md, yin.vm.engine.md (§E extension),
yin.vm.debruijn.register.md (§10), yin.vm.rust-kernel.md (pointers),
datom.world.md (Streams → Host Boundaries).

## Deliverables

### 1. docs/design/datom.world.md — amend Host Boundaries
Update "The system ABI" paragraph and the 2026-10-19 owner ruling: the ruling is
STRENGTHENED and its "sole justification ... performance" clause is superseded —
there is no second ABI at all; wasm's import surface is dao.stream's binding
(the adapter doctrine, in the owner's words: the boundary is dao.stream; its
implementation is whatever wasm exposes; the exposed functions are adapters to
dao.stream). A binding (ws, UDP, wasm imports) is not an ABI. Keep the
custody-subset consequence. Dated supersession note.

### 2. docs/design/yang.wasm.md — the compiler implementation plan
Rescope/retitle around: implementation plan for lowering register bytecode to
wasm. Retain (frozen, still valid): pipeline position (A→R→module; A the only
identity; artifacts are disposable caches), emitted-module contract (core wasm
1.0 profile), ALL 23 opcode lowerings, trampoline/structuring/tail/live-set
codegen, per-image-row compilation and attach, emission byte-determinism, the
register ten-region execution-memory table, LLVM-sibling note. Replace the
§4 target-interface: the module imports the DAO.STREAM ADAPTER BINDING (defined
in yin.vm.engine.md §E.3, per the single-home rule) and links the in-instance
engine crates; delete the yin.vm.engine.host.v1 service-protocol target, the
service-status vocabulary, and every TLV/NEED_SPACE obligation. Add: how
compiled bodies reach the in-instance engine (direct calls/shared linear
memory; the service-thunk seam fable recommended stays as the one replaceable
site). Revise W0–W6 to the node-lane schedule (blend argon's round-2 W0–W6 with
deepseek's oracle-first guard: K0 kit + CLJS reference oracle first; wasm32
compilability of the crates is a W0/W1 gate; adapters + jing bytes; Wasmer by
W5; W6 = codegen optimization only). Record the supersession.

### 3. docs/design/yin.vm.engine.md — §E extension rewritten
E.1/E.2 (machine-state-as-data pattern, value arena and handles, ownership/
collection, snapshot isolation) SURVIVE as the in-instance representation
discipline (the arena now lives in wasm linear memory owned by the in-instance
engine; the map-sync barrier becomes the internal discipline between compiled
bodies and the engine crates — restate accordingly, keep the GC-root rules).
E.3 becomes: "the dao.stream wasm binding" — the adapter surface (which stream
operations are imported, their signatures over linear memory, canonical jing
CBOR bytes as the payload, the stream's exhaustive outcome maps as return
values, buffer-full handling as the stream's full outcome — replacing the
NEED_SPACE machinery with the stream's own outcome discipline). E.4 embedder
seam: the composition hosts the adapters and nothing else; single-engine-port
rule now means: the in-instance engine IS the one engine (the Rust crates);
the CLJS reference remains the oracle, not a second production engine. E.5
conformance method survives (lanes: CLJS oracle, in-wasm engine under
Node/Wasmtime/Wasmer; fixtures unchanged). Delete the yin.vm.engine.host.v1
protocol tables (control block, TLV tags, statuses) with a dated supersession
note; the ABI rename history stays as provenance.

### 4. docs/design/yin.vm.debruijn.register.md §10 — instance updates
The eight-field materialization/sync content becomes the in-instance discipline
(bodies ↔ engine crates in shared memory); W-slice gates follow the revised
schedule; fixture schema unchanged. Cross-references follow the new §E.3.

### 5. docs/design/yin.vm.rust-kernel.md — pointers only
Footer note: the wasm plan now compiles the K-phase crates to wasm32 as the
in-instance engine (design-only status unchanged; the wasm plan depends on it).
No line-coordinate shifts; keep the file's current name (a rename is a separate
open owner decision — do not rename).

## Constraints
Frozen content that survives moves as-is (opcode rows, codegen law, fixtures,
kit); superseded content is deleted with dated supersession notes naming the
owner ruling — do not leave dead protocol text. No second codec. No src/ or
test/ changes. Cross-references must resolve; rust-kernel coordinates 1–1334
unshifted. When done, reply with: per-file change summary, every supersession
recorded, the revised W0–W6 one-line schedule, and every cross-reference
changed.
