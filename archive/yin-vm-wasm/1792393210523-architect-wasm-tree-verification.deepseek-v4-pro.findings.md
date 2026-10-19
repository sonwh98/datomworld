# Verification: restructured wasm spec tree (deepseek-v4-pro, independent)

Scope: read all five files in full, plus `dao.stream.md` (to check outcome-map
fidelity), `git status`, retired-file references, and `rust-kernel.md` line
coordinates. Verdict below is per checklist item; each ends anchored.

## VERDICT: PASS

The tree is faithful to R1–R3, the dissolved protocol is gone as normative text,
the new engine-in-guest + adapter architecture is structurally coherent, and the
frozen survivors (opcode rows, trampoline law, attach/cache rules, ten-region
table, continuation payload/restore, fixture schema, single-port rule) are
intact. `rust-kernel.md` 1–1334 is unshifted; no src/test changes; links resolve.

---

## A. RULINGS FAITHFUL — PASS

- **R1 (one-ABI)** — `datom.world.md:97-114` "owner ruling, strengthened":
  boundary is `dao.stream`; adapters; no second ABI. The old "sole justification
  … performance" allowance is explicitly superseded (`datom.world.md:107-114`,
  `engine.md:366-367`). No residual "second ABI" as live doctrine.
- **R2 (boundary clarification)** — `datom.world.md:97-105`, `yang.wasm.md:13-24`,
  `engine.md:361-367`, `register.md:1226-1236`. All state engine-in-guest is
  mandatory ("Engine-in-guest is mandatory, not a W6 experiment", `yang.wasm.md:20`),
  imports are only the `dao.stream` implementation, and the former
  `yin.vm.engine.host.v1` vocabulary/TLV/status/refetch machinery is deleted.
- **R3 (taxonomy)** — `datom.world.md:74-89` "one abstraction boundary, many
  implementations (ring-buffer, websocket, UDP, wasm imports)"; verbatim owner
  quote at `datom.world.md:82-84`; echoed at `yang.wasm.md:20-24`,
  `engine.md:554-560`, `register.md:1232-1235`, `rust-kernel.md:1339`. No
  "transport binding of the ABI" wording anywhere.

Residual `yin.vm.engine.host.v1` / `yin.register.host.v1` / TLV / comparator /
NEED_SPACE strings occur **only** in dated supersession or provenance contexts
(`datom.world.md:110-111`, `engine.md:365,548-550`, `register.md:1227-1228`,
`yang.wasm.md:15-16`, `rust-kernel.md:1300,1338`) — all legitimate.

## B. COMPLETE SUPERSESSION — PASS

- Control-block table, TLV tags, expanded-canonical-bytes comparator,
  NEED_SPACE/fetch-response, and the service-status set (`TRANSFER`/`HOST_SERVICE`
  as *serialized* statuses) are gone as normative text. The only comparator
  remnants are prohibition/dissolution statements (`engine.md:550,712,984-985`;
  "no private TLV framing … survives the owner ruling", `engine.md:454`).
- `TRANSFER`/`YIELDED`/`BLOCKED`/`PARKED`/`HALTED`/`ERROR` are retained but
  re-declared as *internal driver control, not serialized service statuses*
  (`yang.wasm.md:228-231`) — correct, not a dangling reference.
- No subsection references a deleted subsection: `§E.3.4` (the dissolved
  "message envelope") is not referenced anywhere; `grep` for `E.3.4`/`E.3.5`/
  "three encoding"/"three domains" returns only the correct "do not invent a
  third codec" prohibition (`engine.md:985`).
- The new `§E.3` has exactly three subsections (`E.3.1` adapter surface,
  `E.3.2` outcome/buffer discipline, `E.3.3` canonical bytes/authority); all
  cross-file references to `§E.3`/`§E.3.1` resolve to these.

## C. STRUCTURAL COHERENCE — PASS

- **Engine-in-guest**: shared Rust crates compile to `wasm32-unknown-unknown`
  and link with bodies via direct calls + shared linear memory; "Park, resume,
  resolve-var, primitive application, state reads, roots, collection and module
  handling have **no imports**" (`engine.md:581-584`, `yang.wasm.md:66-70,392-422`).
- **Adapters** implement `dao.stream` ops with the stream's own outcome maps
  (`engine.md:598-608`). I checked these against `dao.stream.md`: append
  ok/full/invalid-value/closed/refused/transport-error (`dao.stream.md:598-603`),
  next ok/blocked/end/gap/cursor-mismatch/invalid-cursor/refused/transport-error
  (`dao.stream.md:577-584`), close `{ok}` only (`dao.stream.md:629-631`) — all
  match exactly.
- **Two codecs only**: Jing CBOR + register scalar/image (`engine.md:456-519`,
  `yang.wasm.md:168`). The dissolved private TLV codec is not resurrected; the
  internal structural ordering for noncanonical values is declared "not a third
  serialized codec" (`engine.md:706-712`).
- **CLJS oracle** retained (`engine.md:650`, `yang.wasm.md:70`).
- **wasm32 early gate**: "W0 probes wasm32 compilability … no host service
  fallback" (`engine.md:903-907`, `register.md:1469-1471`).
- **W0–W6 node-lane schedule**: K0/oracle first, Wasmer by W5, W6 codegen-only —
  consistent across `yang.wasm.md:503-513`, `engine.md:827-843`, and
  `register.md:1460-1670` (incl. the W4→W5 Wasmer-gate move, stated identically
  at `register.md:1236` and `yang.wasm.md:508`).

## D. SURVIVOR INTEGRITY — PASS

- **All 23 opcode lowering rows** present in `yang.wasm.md:306-328` (const,
  load-bound, load-free, closure, move, call, branch-false, jump, return, halt,
  store-get, store-put, define, gensym, stream-make, stream-put, stream-cursor,
  stream-next, stream-close, current-continuation, park, resume, ffi-call).
- **Trampoline / structuring law**: `yang.wasm.md:240-292` (compiled-body +
  trampoline, flattening, `return_call` not emitted, LLVM sibling law).
- **Per-image-row attach + artifact-cache**: `yang.wasm.md:424-488`.
- **Ten-region memory table**: `yang.wasm.md:184-195` — all ten regions intact
  (Task header … Exit mailbox).
- **Continuation payload + restore**: `yang.wasm.md:352-388`, `register.md:1317-1396`
  (5-step `register-restore`, failure ordering, cross-layout transport refusal).
- **Fixture schema + conformance method**: `register.md:1424-1456`, `engine.md:805-900`.
- **Single-engine-port rule**: `engine.md:660-692` ("one transition
  implementation, two dispatchers").

Note: "Exit mailbox" survives as a *private memory region* (one of the ten),
explicitly "private implementation state, never a portable continuation"
(`yang.wasm.md:195`) — correctly distinguished from the dissolved *service*
mailbox.

## E. MECHANICAL — PASS

- **rust-kernel.md 1–1334 unshifted**: footer adds only the "Related specs"
  pointer (`:1335`) and dated notes (`:1337-1339`). Line-coordinate spot-checks
  all match what the other docs cite: status/rev at `:3-35`; crate list at
  `:1134-1158`; `forbid(unsafe)` boundary at `:1154-1158`; K0 kit at `:1185-1199`;
  DEFERRED "WASM as an artifact target" at `:1294-1300`. The frozen `:1300`
  still names the historical `yin.vm.engine.host.v1` ABI, but `:1339` explicitly
  supersedes "the historical service-protocol wording in the line-1300 pointer"
  — the correct unshifted-body + footer-supersession pattern.
- **No src/ or test/ changes**: `git status --short -- src test` is empty.
- **Links resolve**: all 13 relative `.md` links across the five files exist on
  disk (no dead links).
- **No dead links to retired files**: `yin.vm.register-wasm.md` and
  `yin.vm.conformance-kit.md` are absent from disk and referenced only as
  backticked provenance (`engine.md:799`, `rust-kernel.md:1338`), never as links.

---

### Non-blocking observations (not defects)

1. `rust-kernel.md:1300` carries the now-stale "`yin.vm.engine.host.v1` ABI"
   pointer inside the frozen body; it is correctly neutralized by the `:1339`
   footer note, but any reader who lands on `:1300` directly will see the
   superseded wording without the note. Acceptable under the "unshifted"
   constraint, worth a one-line inline `(superseded 2026-10-19)` only if the
   unshift rule is later relaxed.
2. `engine.md:587-593` encodes operational stream handles as "a checked,
   task-local i32 slot, encoded as a Jing integer." This is a new *physical
   representation* decision riding inside the Jing-integer carrier. It is
   explicitly scoped as adapter-internal and non-portable, so it does not
   violate the two-codec or one-ABI rule — but it is the single subtlest spot
   in the new tree and is the first thing I would re-scrutinize at
   implementation review.

Neither observation blocks; the tree is ready to commit.
