Created-GMT: 2026-10-18 17:33:03 GMT
Created-Local: 2026-10-19 00:33:03 +07

# Task: engine-level host spec + ABI rename (owner ruling)

Owner rulings (2026-10-19 00:3x +07), verbatim intent:
1. The host-independent execution content should live in yin.vm.engine — "since its
   yin.vm.engine then it should be yin.vm.engine.host.v1".
2. Therefore the service ABI is renamed: yin.register.host.v1 → yin.vm.engine.host.v1.
   This is an explicit owner ruling on normative text — record it as a dated owner
   amendment note everywhere the rename lands.

Normative content is otherwise FROZEN (the r5 consensus as decomposed into
yang.wasm.md / yin.vm.register-wasm.md / yin.vm.conformance-kit.md). This task
MOVES the guest-agnostic half of the host content into the engine design document
and renames the ABI. No redesign; tensions go to Open items.

## Deliverables

### 1. AMEND docs/design/yin.vm.engine.md — new design-only extension

Append a clearly-marked extension section: "Extension (2026-10-19, design only):
the engine execution host for guest machines" — placed after the existing content,
with its own status line (design, owner-authorized planning; not implemented) and a
note that the existing document body remains authoritative for landed engine
behavior. The extension holds the GUEST-AGNOSTIC content, moved from
yin.vm.register-wasm.md and generalized at the seams only where the register
instance was stated as an instance:

- The machine-state-as-data boundary: a guest machine's execution state must
  materialize as plain host-readable data at every service boundary; the host never
  reaches into guest-private storage. (State the PATTERN; the register field
  instance stays in the register spec.)
- The value arena and handle discipline: task-owned arena, i32 handles, reserved
  references, generations, no collection while guests hold unreported values,
  region reclamation at materialized exits, snapshot isolation.
- The service protocol: yin.vm.engine.host.v1 — control-block layout, the single
  exchange import shape, status codes, message framing (TLV), expanded-canonical-
  bytes ordering, NaN canonicalization, once-only effect delivery, park/resume
  as plain data. Full tables live here now; other specs reference, not duplicate.
- The embedder seam: CLJS adapter, native service library = rust-kernel crates +
  handle facade, single-engine-port rule (one transition implementation, many
  dispatchers).
- Guest-agnostic acceptance properties: snapshot aliasing/isolation, determinism
  across embedders, engine parity gates (replaced-in-place by reference to
  yin.vm.conformance-kit.md where the kits live).

Also: correct the stale :store-updates prose in the existing body per the already-
verified finding (current ready entries update private :resources through
:resource-updates; yin.vm.engine.md lines with the old wording) as a dated
correction note — this was flagged by both architects and is pre-authorized.

### 2. RENAME the ABI everywhere: yin.register.host.v1 → yin.vm.engine.host.v1

In yang.wasm.md (the target-interface spec now REFERENCES the protocol's tables in
the engine extension rather than owning them — move the ABI tables there), in
yin.vm.register-wasm.md, in yin.vm.conformance-kit.md, in the rust-kernel.md
pointers if they name the ABI. Each file gets a dated owner-ruling note for the
rename. No line-coordinate shifts in rust-kernel.md.

### 3. SLIM docs/design/yin.vm.register-wasm.md — the register instance only

What remains: the register machine's field set (the eight materialized fields),
sparse continuation payload/live sets on wasm, register opcode-coverage gates, the
register-specific W-slice acceptance rows, and the register instance of the sync
barrier (stated as an INSTANCE of the engine extension's pattern, cross-linked).
Everything guest-agnostic moves to the engine extension and is replaced by
cross-references. Update the provenance note (third decomposition step, 2026-10-19).

### 4. REPAIR cross-references

yang.wasm.md (target ABI now lives in the engine spec), conformance-kit.md (gates
cite the engine extension for guest-agnostic gates; register gates cite
register-wasm.md), rust-kernel.md pointers. Relative links must resolve.

## Constraints

Move text, don't rewrite; where generalization requires rewording (e.g. "the eight
fields" → "the guest's declared machine-state fields"), keep the register instance
intact in register-wasm.md so no normative register content is lost. No src/ or
test/ changes. Cite .cljc lines only where the current docs already do. When done,
reply with: files written, the engine extension's section list, every rename site
count, and every cross-reference changed.
