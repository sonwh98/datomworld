# Round-5 Independent Second-Architect Review: Register-VM Wasm Lowering Plan (R5)

- **Reviewed Plan**: `collab/1792329904305-architect-register-wasm-lower-plan-r5.gpt-6-astra.findings.md`
- **Reconciled Authority**: `docs/design/yin.vm.rust-kernel.md` (revision 2, 2026-10-03)
- **Previous Plan Baseline**: `collab/1792329904305-architect-register-wasm-lower-plan-r4.gpt-6-astra.findings.md`
- **Reviewer**: `gemini-4-argon` (Architect)

---

## 1. Verification of the Six Reconciliation Obligations (1a–1f)

| Obligation | Status | Verification in R5 Body & `docs/design/yin.vm.rust-kernel.md` |
|---|---|---|
| **(a) `rust-kernel.md` cited as authority; DEFERRED "WASM as an artifact target" superseded** | **VERIFIED** | `collab/1792329904305-architect-register-wasm-lower-plan-r5.gpt-6-astra.findings.md:25-30` cites `docs/design/yin.vm.rust-kernel.md` (rev 2, status/subordination at `lines 3-36`, crate layout at `lines 1134-1158`, decisions `K-D1..K-D8` at `lines 1234-1265`), preserves its `design only, not authorized for implementation` status (`rust-kernel.md:3-4`), and explicitly supersedes **only** the `WASM as an artifact target` clause in `DEFERRED` (`rust-kernel.md:1294-1300`) while keeping all other deferred items and the `K3` owner-review gate before `K4` (`rust-kernel.md:1179, 1215-1217`). |
| **(b) Single-engine-port rule (`yin-value`/`yin-scalar`/`yin-image`/`yin-kernel`/`yin-prims`/`yin-engine`/`yin-wire` + facade)** | **VERIFIED** | `r5:73, 238, 282-285, 447, 462` mandate that the native embedder's service library **is** the `yin-value`, `yin-scalar`, `yin-image`, `yin-kernel`, `yin-prims`, `yin-engine`, and `yin-wire` workspace (`docs/design/yin.vm.rust-kernel.md:1136-1145`), extended by a task-local handle/ABI facade and thin Wasmtime/Wasmer runtime wrappers. It applies `rust-kernel.md:991-995, 1308-1311`'s "one transition implementation, two dispatchers" rule so that the Rust interpreter, native wasm facade, and future LLVM out-of-line runtime helpers invoke the same Rust transition functions without any parallel port. |
| **(c) Handle↔`Value` facade over `K-D2`'s 16-byte `Rc` enum and inherited Round-3 determinism rules** | **VERIFIED** | `r5:47, 53-55` bridge `K-D2`'s 16-byte `#[repr(C, u8)] enum Value` with safe-Rust `Rc` payloads (`docs/design/yin.vm.rust-kernel.md:326-392, 1238-1244`) to the wasm guest's `i32` `ValueRef` arena (`0 = Nil`, `1 = Bool(false)`, `2 = Bool(true)`). `r5:53` enforces safe `Clone`/`Drop` retention, generation/bounds checks, no raw pointers or `Rc` counts across the ABI, `!Send + !Sync` `Machine`/arena ownership (`rust-kernel.md:553-564`), and `Send + Sync` `Arc<Image>` holding plain owned `Datum`s (`rust-kernel.md:396-432`). `r5:55` explicitly inherits all three Round-3 determinism rules from §4.1.1: (1) map/set key ordering by recursively expanded canonical encoded value bytes prior to `tag 13` compression, (2) canonical quiet NaN `0x7ff8000000000000` (`00 00 00 00 00 00 f8 7f` LE) with integral-float carrier and `-0.0` preservation, and (3) single outer `u32 payload-byte-length` framing with no inner counts. |
| **(d) W0 consuming/extending `K0` conformance kit with three lanes (R4 CLJS, Rust interpreter, wasm)** | **VERIFIED** | `r5:362-365, 374, 395` unify W0 and `K0` (`docs/design/yin.vm.rust-kernel.md:1170-1171, 1185-1199`) into a single conformance-kit deliverable exported from R4 (`src/cljc/yin/vm/debruijn/register.cljc`), driving three execution lanes (`R4 CLJS reference, Rust kernel/interpreter, wasm`) from one manifest and preserving all `K0` fixtures (14 validator refusal rules, descriptor/scalar vectors, refused-step atomicity, §7.3 tail-site matrix, `>65,535` wide-body registers, `:host-limit`, and million-iteration tail loops). |
| **(e) Wasm and `K4` LLVM as sibling execution strategies under §7.2 equivalence law and node-local cache rules** | **VERIFIED** | `r5:131-134 (§2.1.1), 356 (§5)` place the wasm backend and `K4` LLVM lowering as sibling execution strategies under `docs/design/yin.vm.rust-kernel.md:805-831` (§7.2 equivalence law) and `lines 833-918` (§7.3 function shape/calls), governed by the same node-local artifact-cache rules (`rust-kernel.md:997-1011, 1113-1122, 1261-1263`): artifacts have no `dao.jing` identity, never cross nodes, are installed only at materialized boundaries, and can be dropped at any boundary to resume on the owning host's interpreter (`wasm -> Rust interpreter` and `wasm -> CLJS interpreter` fixtures at `r5:133`). |
| **(f) Implicit answers to `rust-kernel` §11 owner questions noted, not re-litigated** | **VERIFIED** | `r5:37` records alignment with `docs/design/yin.vm.rust-kernel.md:1280-1292`: Question 1 and `C1`/`A3` (`:244-257, 1275-1277, 1282-1284`, kernel-side translation of held register images is an execution strategy, not a Direction A emitter), Question 2 (`:1285-1287`, node-local artifact cache, compile farms out of scope), Question 3 (`:1288-1289`, LLVM worker stream envelope left to LLVM work), Question 4 (`:1290`, `K3` threshold for commissioning `K4` left to owner review), and Question 5 (`:1291-1292`, W0 scalar profile fixes this lane's domain without constraining future Rust profiles). |

---

## 2. Independent Audit of `docs/design/yin.vm.rust-kernel.md` vs. R5

### 2.1 Does R5 Demand Anything `rust-kernel.md` Forbids?
1. **`Value` layout and `Rc` encapsulation (`rust-kernel.md:324-392, 950-958, 1238-1242`)**:
   - `rust-kernel` forbids any code outside safe Rust from reading, writing, or assuming the offset of an `Rc` reference count or dereferencing non-immediate `Value` payloads (`rust-kernel.md:356-367`).
   - `r5` (`lines 47, 53, 286, 447`) keeps all `yin-value::Value` instances inside the safe-Rust task arena indexed by `i32` `ValueRef` handles; wasm linear memory never sees `Value` enum bytes, raw pointers, or `Rc` headers, and cloning/dropping occurs exclusively through safe Rust `Clone`/`Drop` in the facade.
   - `StreamRef`, `CursorRef`, and `CellRef` are ordinary sealed `Map` `Value`s in both documents (`rust-kernel.md:372-375`, `r5:34, 49, 53`, matching `src/cljc/yin/vm/values.cljc:23-24`).
2. **`unsafe` surface boundaries (`rust-kernel.md:226-229, 1154-1158, 1264-1265`)**:
   - `rust-kernel` mandates `#![forbid(unsafe_code)]` across `yin-value`, `yin-scalar`, `yin-image`, `yin-kernel`, `yin-prims`, `yin-engine`, `yin-wire`, `yin-translate`, and `yin-node`.
   - `r5` (`line 286`) preserves `#![forbid(unsafe_code)]` across all semantic crates (`yin-value`, `yin-scalar`, `yin-image`, `yin-kernel`, `yin-prims`, `yin-engine`, `yin-wire`) and the safe handle/ABI facade, isolating any runtime embedding glue to the thin Wasmtime/Wasmer adapter crates.
3. **`Send`/`Sync` and thread-local `Machine` ownership (`rust-kernel.md:138-142, 396-432, 553-564, 1243-1244`)**:
   - `rust-kernel` requires `Machine` (`Rc`-backed `Value`s) to be `!Send + !Sync` and `Image` (`Datum`-backed, no `Rc`) to be `Send + Sync` by compiler derivation (`fn assert_send_sync<T: Send + Sync>()` at `rust-kernel.md:563`), with each `Machine` materializing its own `consts: Vec<Value>` pool (`rust-kernel.md:405-407, 427-432`).
   - `r5` (`line 53`) explicitly matches this split: each task's `Machine` and `Value` arena are single-threaded (`!Send + !Sync`), while admitted `Image`s are shared via `Arc<Image>` and materialize constants per task.
4. **The "`step` always interprets" rule (`rust-kernel.md:677-678`)**:
   - `rust-kernel.md:677-678` specifies that on the Rust kernel, `step` (the `IVM` single-step) always runs interpreted `step1`, while native LLVM code is entered only from `run`.
   - `r5` (`line 34`) explicitly preserves that rule on the Rust interpreter lane while clarifying that the wasm backend's own `IVM/step` (and budget=1 debug stepping in §2.2 step 5, `line 141`) is the wasm adapter's single-instruction dispatcher used by the differential harness to verify per-instruction parity against `step1`.
5. **`u32` index limits (`rust-kernel.md:434-446, 1245-1246`) vs. 64-bit ABI wire PCs (`r5:32, 81, 92`)**:
   - `rust-kernel` `K-D2b` uses `u32` for register indices, register counts, pcs, depths, and positions, refusing any operand `> u32::MAX` or above declared activation limits at load with `:host-limit`.
   - `r5` (`line 32`) keeps 64-bit unsigned wire PC fields in the wasm header/ABI so the wire protocol does not narrow the Clojure layout domain (`max-layout-length` = `4503599627370495` = $2^{52}-1$ at `src/cljc/yin/vm/debruijn/register.cljc:234-236`), while requiring the native Rust facade to check every PC, operand, and attachment against `u32::MAX` and declared host limits before conversion or mutation, returning the same qualified `:host-limit` refusal without widening `Machine` or truncating indices.
6. **No-CLJC-edit rule in `K0–K5` (`rust-kernel.md:93-95, 1164-1165`) vs. §4.1.2 shared traversal stabilization (`r5:3, 33, 290-308`)**:
   - `rust-kernel.md:93-95, 1164-1165` states that `K0–K5` do not edit existing `.cljc` namespaces, whereas `rust-kernel.md:390-392` simultaneously requires that *"no iteration order of a map or set may reach an encoding, a hash, or a diagnostic. Canonical order is by encoded bytes (`:map-set-order :canonical-encoded-bytes`)."*
   - Because Round 3 proved that `src/cljc/yin/vm/engine.cljc:847-859, 918-925, 978-982, 1017-1028, 1044-1046, 1110-1120, 2301-2306` and `src/cljc/yin/vm/module.cljc:100-110, 166-169` currently let unsorted host map iteration reach minted `:yin.k/c-N` / `:stream-N` IDs and `:resource-update-key` diagnostics, `r5:33` cleanly reconciles this by recording §4.1.2 as an explicit, versioned shared-reference stabilization prerequisite before W3/W4 parity gates.

### 2.2 Does `rust-kernel.md` Demand Anything R5's Slices Quietly Drop?
1. **`K2` canonical payload byte parity and `:live` on serialized `ReturnFrame`s (`rust-kernel.md:500-515, 734-746, 1209-1213`)**:
   - `rust-kernel.md:483-490, 500-515` omits `:live` from the in-memory `ReturnFrame` struct (since `regs: Box<[(u32, Value)]>` already stores the sorted live index column) and requires `yin-wire` to emit `:live` from `regs`' indices on encode and validate `:live` via `continuation-defect` (`src/cljc/yin/vm/debruijn_register_effects.cljc:190-194, 280-368`) before constructing structs on decode.
   - `r5` preserves this in full: §1.1.1 (`line 73`) assigns Jing CBOR payload serialization to `yin-wire`, §1.2 (`line 85`) and §3.1 (`line 206`) require `:live` on every return frame, and §6 (`lines 362, 370, 407, 411`) gates bidirectional payload round-trips and byte-identical canonical encodings in W2.
2. **Live-set recomputation at admission (`rust-kernel.md:80-84, 915-918, 1055-1059`)**:
   - `rust-kernel.md:1055-1059` requires `yin-image` to recompute `body-liveness` (`src/cljc/yin/vm/debruijn_register_code.cljc:341-376, 629-646`) on every admitted image under `live-exact-rule` and never trust a received live set.
   - `r5` preserves this in §1.1.1 (`line 73`, assigning image/descriptor/`R` and admission to `yin-image`), §2.2 step 1 (`line 137`, admitting with `register-image-defect` and reusing `body-liveness`/`live-slot-index`), §5 step 1 (`line 343`), and W0 (`lines 368, 389`).
3. **Deterministic heap collector trigger equality (`rust-kernel.md:202-205, 763-765`)**:
   - `rust-kernel.md:202-205, 763-765` requires `yin-engine` to reproduce the exact allocation-triggered heap collector of `src/cljc/yin/vm/engine.cljc:2587-2614, 2720` (`count-allocation` triggering `collect` when `(>= (inc (:since gc)) (:threshold gc))`, resetting `:since 0` and `:threshold (max (:base gc) (* 2 (count heap)))`, and never reusing `:cell-N` IDs) because collection timing is guest-observable (`:dead-or-forged-reference`).
   - `r5` preserves this in §1.2.1 (`line 105`), §1.3 (`line 111`), §4.1.1 (`line 282`), and W3 (`line 387`, which explicitly requires a low-threshold `:cell/new` test forcing collection inside `handle-effect` and verifying retention of live cells in newly materialized registers/frames/`K`).
4. **Crate boundaries (`rust-kernel.md:1134-1158`) vs. R5 embedder layers**:
   - The crate boundaries in `rust-kernel.md:1136-1153` (`yin-value`, `yin-scalar`, `yin-image`, `yin-kernel`, `yin-prims`, `yin-engine`, `yin-wire`) map cleanly onto `r5:73, 282-286` without circular dependencies or `unsafe` leakage into the semantic crates.

---

## 3. Enforceability of the "No Second Port" Rule Across W1–W4

The "no second port" rule is enforceable in `r5` for three concrete reasons:
1. **Shared crate boundary (`r5:73, 282-285`)**: The plan forbids Wasmtime/Wasmer adapters or the handle/ABI facade from implementing their own value semantics, scalar/CBOR codecs, validator, `step1` transitions, scheduler, custody gates, heap GC, or module linking; they must call `yin-value`, `yin-scalar`, `yin-image`, `yin-kernel`, `yin-prims`, `yin-engine`, and `yin-wire`.
2. **Simultaneous three-lane gate (`r5:364, 395, 399, 407, 415, 427`)**: One conformance manifest drives **R4 CLJS, the Rust interpreter (`yin-kernel`/`yin-engine` directly), and the wasm lanes (Node, Wasmtime, Wasmer)** together across W0–W5, and `r5:364` explicitly states that *"Rust interpreter coverage advances with each W slice; it cannot be omitted because native wasm happens to pass."*
3. **Mid-run boundary fallback between wasm and the Rust interpreter (`r5:133, 356, 540`)**: §2.1.1 (`line 133`) requires `wasm -> Rust interpreter` boundary fallback fixtures where the compiled wasm artifact is dropped at a materialized boundary and execution continues directly in the Rust interpreter (`yin-kernel`/`yin-engine`) from the same state to the same result. If the wasm facade maintained a separate state or engine port instead of delegating to `yin-kernel`/`yin-engine`, boundary fallback and shared state inspection would fail immediately.

---

## 4. Regression Check Against R1–R4 Approved Properties

Comparison of `collab/1792329904305-architect-register-wasm-lower-plan-r4.gpt-6-astra.findings.md` and `collab/1792329904305-architect-register-wasm-lower-plan-r5.gpt-6-astra.findings.md` confirms that all previously approved properties remain intact:
- **All 23 opcode lowerings** (§2.3, `r5:147-171`), **host-function effect completion** (§2.4, `r5:173-183`), **map ↔ linear-memory synchronization** (§1.2.1, `r5:96-105`), and **GC root/spilling rules** (§1.3, `r5:107-113`) are unchanged.
- **Three encoding/hash domains** (§1.1.1, `r5:57-73`), **continuation capture/restore** (§3.1–3.3, `r5:187-228`), **ABI v1 framing/ordering/NaN/NEED_SPACE rules** (§4.1.1, `r5:242-288`), and **shared deterministic traversal prerequisite** (§4.1.2, `r5:290-308`) are unchanged.
- **Stream/park transitions, custody gates, FFI two-step, and module growth** (§4.2–§5, `r5:310-356`), **core-wasm-1.0 profile and W0 toolchain pins** (`r5:11-15, 376-384`), and **prior review response tables** (`r5:468-532`) are preserved without regression.

---

## Verdict

APPROVE

### Findings Summary

| ID | Severity | Section | Summary |
|---|---|---|---|
| None | — | — | All six Round-5 reconciliation obligations with `docs/design/yin.vm.rust-kernel.md` (rev 2) and all Round-1 through Round-4 properties are verified and complete. No open MAJOR or MINOR findings remain. |

### Properties Passing Review
1. **Complete Reconciliation with `yin.vm.rust-kernel.md` (Rev 2)**: `r5` (`lines 25-37, 47, 53-55, 73, 131-134, 282-286, 356, 362-365, 395`) accurately cites `docs/design/yin.vm.rust-kernel.md`, preserves its design-only status and `K3` gate before `K4`, supersedes only the `WASM as an artifact target` deferral (`rust-kernel.md:1294-1300`), and cleanly reconciles `A`-identity precedence, `u32` native index limits (`K-D2b`) vs. 64-bit wire PCs, `step`-always-interprets on the Rust interpreter lane, the versioned §4.1.2 shared-traversal prerequisite, and early Level-2 library coverage in `yin-engine`.
2. **Enforceable Single-Engine-Port Architecture**: The native wasm service library is the `yin-value`, `yin-scalar`, `yin-image`, `yin-kernel`, `yin-prims`, `yin-engine`, and `yin-wire` crate workspace (`rust-kernel.md:1136-1145`) behind a safe task-local `i32` handle ↔ 16-byte `#[repr(C, u8)]` `Value` facade (`K-D2`, `K-D4`, `K-D8`), verified by running the Rust interpreter lane alongside Node, Wasmtime, and Wasmer and testing mid-run `wasm -> Rust interpreter` boundary fallback.
3. **Unified `K0`/`W0` Conformance Kit & Preserved `K1`/`K2`/`Level-2` Obligations**: W0 consumes and extends `K0` (`rust-kernel.md:1185-1199`) across all three lanes (`R4 CLJS`, `Rust interpreter`, `wasm`), preserving `live-exact-rule` recomputation at admission (`rust-kernel.md:1055-1059`), `:live` in serialized `ReturnFrame`s (`rust-kernel.md:500-515`), allocation-triggered heap collector parity (`rust-kernel.md:202-205, 763-765`, `src/cljc/yin/vm/engine.cljc:2587-2614, 2720`), refused-step atomicity (`K-D4`), and per-machine fault containment (`rust-kernel.md:688-713`).
4. **Full Retention of R1–R4 Approved Properties**: All 23 opcode lowerings, map ↔ linear-memory synchronization, three canonical encoding/hash domains, interning-invariant ABI wire codec, shared deterministic traversal prerequisite, custody gates, FFI two-step, and core-wasm-1.0 multi-runtime gates remain intact.
