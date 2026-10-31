# Architect Fidelity & Completeness Review: Register-Wasm & Conformance-Kit Design Specifications

- **Review Prompt**: `collab/1792344000000-architect-register-wasm-design-docs-review.gemini-4-argon.prompt.md`
- **Source of Truth (Approved r5 Plan)**: `collab/1792329904305-architect-register-wasm-lower-plan-r5.gpt-6-astra.findings.md`
- **Files Under Review**:
  1. `docs/design/yin.vm.register-wasm.md` (new; 1,235 lines)
  2. `docs/design/yin.vm.conformance-kit.md` (new; 474 lines)
  3. `docs/design/yin.vm.rust-kernel.md` (amended; 1,338 lines)
- **Reviewer**: `gemini-4-argon`
- **Date**: 2026-10-18

---

## 0. Distillation Ground Rules Compliance

| Ground Rule | Status | Evidence |
|---|---|---|
| **Normative content frozen at `r5` / `rust-kernel.md`; no new normative content; no redesign** | **PASS** | `docs/design/yin.vm.register-wasm.md` (`lines 28–1198`) is a faithful, paragraph-for-paragraph distillation of `r5` (`lines 5–512`), reformatted to ~100-column repository design-doc prose. `docs/design/yin.vm.conformance-kit.md` (`lines 13–467`) combines the `K0` specification (`docs/design/yin.vm.rust-kernel.md:1185–1228`) with `r5`'s shared conformance-kit, codec/hash, deterministic traversal, and `W0`–`W6` acceptance sections (`r5:57–74, 299–316, 356–446`). No new normative claims, opcodes, wire tags, or architectural changes were introduced. |
| **Tensions flagged in `Open items`, not resolved** | **PASS** | Both open items in `docs/design/yin.vm.register-wasm.md:1223–1235` (and referenced in `docs/design/yin.vm.conformance-kit.md:468–473`) record genuine wording/notation tensions already present in `r5` (`r5:296` vs. `r5:298`, and `r5:417` vs. `r5:265–266`) while keeping `r5`'s normative text verbatim (`register-wasm.md:674–676, 694–698, 988–994`). |
| **Repository design-doc conventions (`Status:` header, consensus provenance, authorities list, prose/table style)** | **PASS** | Both `docs/design/yin.vm.register-wasm.md:1–25` and `docs/design/yin.vm.conformance-kit.md:1–29` include `Status: design, owner-authorized planning; not implemented.`, dated consensus provenance (`2026-10-18`, `gpt-6-astra` author, `gemini-4-argon` reviewer, five review rounds, `APPROVE` on `r5`, both conversation thread IDs), and explicit `Authorities` / `Scope and authorities` sections matching `docs/design/yin.vm.rust-kernel.md`. |
| **`.cljc` line citations only where `r5` verified them; `rust-kernel.md` citations unchanged** | **PASS** | Exhaustive scan (`rg -n "\.cljc:|rust-kernel\.md:"`) across both new docs confirms that the only `.cljc:line` citations in the prose are `debruijn_register_code.cljc:258–271` and `debruijn_code.cljc:198–203, 224–242, 350–378, 410–465` (`register-wasm.md:196, 199`; `conformance-kit.md:123, 126`), both verified in `r5:67`. All `docs/design/yin.vm.rust-kernel.md` line citations (`:324–392`, `:394–432`, `:553–563`, `:639–713`, `:805–831`, `:991–995`, `:997–1011`, `:1113–1122`, `:1134–1158`, `:1136–1145`, `:1167–1182`, `:1185–1199`, `:1201–1228`, `:1215–1228`, `:1238–1244`, `:1261–1263`, `:1294–1300`, `:1308–1311`) are preserved intact. |
| **`rust-kernel.md` amendment is pointer-only; no `Status:` change, no normative content change** | **PASS** | Verified in Section 3 below: `docs/design/yin.vm.rust-kernel.md` retains its exact `Status: design only, revision 2 (2026-10-03). Not authorized for implementation.` header (`line 3`) and all 1,334 original lines without any line-number shifts. |
| **No `src/` or `test/` changes** | **PASS** | `git status --short -- src test docs` confirms zero modified or untracked files under `src/` or `test/`, and under `docs/` only the unrelated `M docs/agents/delegate-invocation-reference.md` plus untracked design docs. |

---

## 1. Task 1 — Completeness Check (`COMPLETE?`)

Every normative section and requirement from `collab/1792329904305-architect-register-wasm-lower-plan-r5.gpt-6-astra.findings.md` is accounted for across `docs/design/yin.vm.register-wasm.md` and `docs/design/yin.vm.conformance-kit.md`:

| Normative Element in `r5` | `r5` Location | `yin.vm.register-wasm.md` Location | `yin.vm.conformance-kit.md` Location | Status |
|---|---|---|---|---|
| **Decision, baseline wasm 1.0 profile, and source precedence** | `lines 5–24` | `lines 28–87` | `lines 13–29, 255–263` | **COMPLETE** |
| **Rust-kernel reconciliation** (single engine port, `Value` handle facade, `K0`/`W0` shared kit, `K4` LLVM sibling, `A` vs. `R` identity, `u32` `K-D2b` vs. 64-bit wire PCs, `step` always interprets, Level-2 crates in `W2`–`W4`, owner questions 1–5) | `lines 25–37, 47, 53–55, 73, 131–134, 282–286, 356, 362–365, 395` | `lines 88–116, 139–148, 162–180, 222–237, 351–361, 677–716, 873–881, 887–902, 996–1002` | `lines 13–29, 48–63, 100–106, 149–164, 288–294, 444–466` | **COMPLETE** |
| **Value arena (`i32` `ValueRef`), 3 encoding domains (`yin-scalar`, `yin-image`, `yin-wire`), linear-memory layout (§1.2), mandatory map ↔ memory sync (§1.2.1), ownership/spilling/GC (§1.3)** | `lines 39–113` | `lines 117–318` | `lines 108–176` | **COMPLETE** |
| **Code generation strategy**: resumable body functions + trampoline, LLVM sibling & §7.2 equivalence law, structuring algorithm (`br_table` W1, block coalescing W6, budget yields) | `lines 115–142` | `lines 319–370` | `lines 100–106` | **COMPLETE** |
| **All 23 opcode lowerings** (`:const`, `:load-bound`, `:load-free`, `:closure`, `:move`, `:call`, `:branch-false`, `:jump`, `:return`, `:halt`, `:store-get`, `:store-put`, `:define`, `:gensym`, `:stream-make`, `:stream-put`, `:stream-cursor`, `:stream-next`, `:stream-close`, `:current-continuation`, `:park`, `:resume`, `:ffi-call`) + **§2.4 exact host-function effect completion** | `lines 143–184` | `lines 371–425` (all 23 rows at `lines 378–402`, §2.4 at `lines 404–425`) | Referenced at `lines 347–351` | **COMPLETE** |
| **Continuation capture and resume**: external payload shape (§3.1), 5-step restore algorithm & failure ordering (§3.2), internal `:call` continuation path, host transport & layouts (§3.3) | `lines 185–227` | `lines 426–498` (Part 1 `lines 426–481`, Part 2 `lines 1–28`) | Referenced at `lines 316–335` | **COMPLETE** |
| **Blocking, effects, and FFI protocol**: embedding contract (§4.1), Import ABI `yin.register.host.v1` (§4.1.1: offsets, statuses `0`–`4`, once-only `fetch-response`, TLV tags `0`–`13`, expanded-byte ordering, Float64/NaN rules, single-engine-port rule), deterministic service traversal prerequisite (§4.1.2: 7 sites), stream/park transitions (§4.2), custody gates (§4.2.1), FFI two-step (§4.3) | `lines 229–354` | `lines 499–820` | `lines 166–230` (§3 ABI summary, §4 deterministic traversal table & gates) | **COMPLETE** |
| **Modules and code growth (§5)**: 4-step staged attach transaction, combined-segment payload reads, empty-base `[[empty-R 0 0]]` lifecycle, 65,536-slot preallocated table, `spawn-module`, node-local artifact cache law | `lines 356–396` | `lines 821–882` | Referenced at `lines 372–401` | **COMPLETE** |
| **Implementation slices `W0`–`W6` and acceptance gates (§6)**: shared `K0`/`W0` harness, toolchain pins (`Node 25.6.1`, `Clojure 1.12.4.1602` / `shadow-cljs 3.3.5`, `WABT 1.0.37`, `Wasmtime =38.0.0`, `Wasmer 6.0.1`, `Rust 1.90.0`), `W0`–`W6` gates | `lines 398–488` | `lines 883–1164` | `lines 31–106, 231–443` | **COMPLETE** |
| **Explicit non-goals & Risks and open questions (§7) & Reference conventions** | `lines 490–512, 529` | `lines 1165–1222` (non-goals `1165–1179`, 11 risks `1180–1198`, conventions `1199–1222`) | `lines 444–467` (retains `K1`–`K5` gates & scope bounds) | **COMPLETE** |

---

## 2. Task 2 — Fidelity Spot-Checks (`FAITHFUL?`)

### 2.1 Ten Substantive Spot-Checks Against `r5` and Repository Source

1. **Three Encoding Domains & `R` Preimage Formula (`register-wasm.md:181–201`, `conformance-kit.md:108–128`)**
   - **Check**: `R` is BLAKE3 over UTF-8 bytes of the string concatenation `(str (descriptor-hash version) (encode-register-image image))`, where scalar framing uses 2-char class tag + 8-hex-digit UTF-8 byte length + content (`debruijn_register_code.cljc:258–271`, `debruijn_code.cljc:198–203, 224–242, 350–378, 410–465`), while Jing canonical hashes digest CBOR bytes (`dao/jing.cljc:128–135, 160–197`).
   - **Result**: Matches `r5:57–74` and verified source files exactly.
2. **Native Handle ↔ `Value` Facade (`register-wasm.md:139–148, 162–180`)**
   - **Check**: Wasm uses `i32` `ValueRef` locals (`0 = nil`, `1 = false`, `2 = true`), while the native service uses `yin-value`'s 16-byte `#[repr(C, u8)]` `Value` enum with safe `Rc` ownership (`K-D2`, `rust-kernel.md:324–392, 1238–1244`), `!Send + !Sync` `Machine`, and `Send + Sync` `Arc<Image>` (`rust-kernel.md:394–432, 553–563`).
   - **Result**: Matches `r5:47, 53–55` and `rust-kernel.md` exactly.
3. **Mandatory Map ↔ Memory Synchronization & `:cell/new` In-Service GC (`register-wasm.md:274–291`)**
   - **Check**: All 8 execution fields (`:pc`, `:registers`, `:frames`, `:continuation`, `:store-of`, `:value`, `:halted?`, `:blocked?`) are materialized onto the VM record before every state-consuming `engine/*` call because `:cell/new` can trigger `collect` inside `handle-effect` (`src/cljc/yin/vm/engine.cljc:2587–2614, 2720`) and `module/gc-roots` reads the map (`src/cljc/yin/vm/debruijn/register.cljc:955–957`).
   - **Result**: Matches `r5:96–105` and source exactly.
4. **`:return`, `:halt`, and `:return-result` Restore PC Semantics (`register-wasm.md:388–389, 418, 468`)**
   - **Check**: `:return` with empty `K` and `:halt` preserve `:pc` at the executing instruction (`register.cljc:432, 742–747`), an immediate nonblocked tail effect with empty `K` halts at `site-pc` (`register.cljc:570`), whereas `:return-result` restore first assocs `:pc (:pc entry)` (`inc site-pc`) while leaving `:registers` equal to `(:registers base)` before running `return-transition` (`register.cljc:461–468`).
   - **Result**: Matches `r5:157–158, 180, 218` and `register.cljc` exactly.
5. **Internal `:call` Continuation Precedence (`register-wasm.md:385, 476–481` / offset 46080 `lines 1–5`)**
   - **Check**: `engine/operator-kind` checks ownership first (`{:reason :foreign-value :kind :continuation}`, `engine.cljc:83–90`), `engine/continuation-argument` checks arity second (`:continuation-arity`, `engine.cljc:103–112`), and format/image membership third (`register.cljc:729–733`), skipping external `continuation-defect`/`machine-data?` gates.
   - **Result**: Matches `r5:154, 222` and `register.cljc`/`engine.cljc` exactly.
6. **Import ABI `yin.register.host.v1` Control Layout, Framing, and Determinism Rules (`register-wasm.md:541–660` / offset 46080 `lines 70–175`)**
   - **Check**: Magic `0x31524e59` at `+0`, header length `64`, statuses `0 COMPLETE`, `1 NEED_SPACE`, `2 BAD_MESSAGE`, `3 ABI_MISMATCH`, `4 RESOURCE_LIMIT`; outer-only framing `u8 tag | u32 payload-byte-length (LE) | payload` for tags `0`–`13`; map/set sorting by recursively expanded canonical value bytes before tag-13 reference compression; canonical quiet NaN `0x7ff8000000000000` (`00 00 00 00 00 00 f8 7f` LE) while preserving `-0.0` (`yin.vm.integer.host/exact-integer?`, `host.cljc:51–66`) and integral floats via `Float64` carriers (`dao/jing/cbor.cljc:472–480, 506–541`).
   - **Result**: Matches `r5:246–292` and source exactly.
7. **Single-Engine-Port Rule & One-Transition/Two-Dispatchers (`register-wasm.md:677–706` / offset 46080 `lines 207–238`)**
   - **Check**: Native service library is the `yin-value`, `yin-scalar`, `yin-image`, `yin-kernel`, `yin-prims`, `yin-engine`, and `yin-wire` workspace (`rust-kernel.md:1134–1158`), extended with a handle/ABI facade and thin Wasmtime/Wasmer wrappers, applying `rust-kernel.md:991–995, 1308–1311` and `#![forbid(unsafe_code)]` outside the explicit boundary crates (`rust-kernel.md:1154–1158`).
   - **Result**: Matches `r5:282–286` and `rust-kernel.md` exactly.
8. **Deterministic Service Traversal Prerequisite (`register-wasm.md:717–763`, `conformance-kit.md:178–230`)**
   - **Check**: Lists all 7 sites (`encoder`/`encode-store`, `markers`, `lower-resources`, `receive-module`, `module/module-entries`/`module/list-modules`, `resume-from-run-queue`, `lift-slice`/`decode-store`), preserves existing explicit `sort-by str` / `sort-by key` / FIFO queue orders, and clearly marks the policy as a planned shared CLJC/native prerequisite outside the old K-phase restriction (`register-wasm.md:105, 722–725`; `conformance-kit.md:183–186, 225–229`).
   - **Result**: Matches `r5:33, 299–316` and verified `engine.cljc`/`module.cljc` sites.
9. **Custody Gate Semantics & Wait-Entry Oracle Scope (`register-wasm.md:764–808`)**
   - **Check**: Preserves `:yin.k/gate`, `:yin.k/issued` (distinct from `:id-counter`, `engine.cljc:554–559`), and `:yin.k/closes`; notes that running `:stream/close` queues in `:yin.k/closes` (`engine.cljc:744–761`) whereas exporting/ended refuses (`engine.cljc:484–492, 2713–2716`); scopes `effects/wait-entry-defect` to its 4 stream/FFI shapes (`debruijn_register_effects.cljc:371–379`).
   - **Result**: Matches `r5:318–337` and source exactly.
10. **Empty-Base Offset Table & Combined-Segment Payload Reads (`register-wasm.md:835–850`)**
    - **Check**: Empty-image creation seeds `:images [[empty-R 0 0]]`, `:hash nil`, `:pc 0`, `:registers []`, `:halted? true` (`register.cljc:135–146`); attaching a nonempty image produces `[[empty-R 0 0] [R 0 n]]` (`register.cljc:184–227`); boundary payloads read `(nth (:instructions (:segment vm)) (:pc vm))` from the relocated combined segment (`register.cljc:525–530`).
    - **Result**: Matches `r5:369–373` and `register.cljc` exactly.

### 2.2 Verification of `Open items` (Flagged, Not Resolved)

- **Open item 1 (`register-wasm.md:1228–1231`)**: Flags the wording tension in `r5` §4.1.1 between *"Pure body control, register moves and guest closure calls stay compiled"* (`r5:296`, retained verbatim at `register-wasm.md:674–675`) and *"generated service sites never reproduce call/return/restore/effect logic in a wrapper"* (`r5:298`, retained verbatim at `register-wasm.md:695–697`). Neither sentence was altered or resolved.
- **Open item 2 (`register-wasm.md:1232–1234`, `conformance-kit.md:468–473`)**: Flags the `COMPLETE→NEED_SPACE→fetch-response` test-sequence shorthand in `r5` W0 (`r5:417`, retained verbatim at `register-wasm.md:989` and `conformance-kit.md:281`) versus `r5` §4.1.1's definition of `COMPLETE` and `NEED_SPACE` as alternative per-exchange statuses (`r5:265`). The ABI state machine and W0 text remain untouched.

### 2.3 Verification of `docs/design/yin.vm.conformance-kit.md` Against `rust-kernel.md` K0 + Wasm Additions

- `docs/design/yin.vm.conformance-kit.md:33–47` reproduces `docs/design/yin.vm.rust-kernel.md:1189–1199` word-for-word (canonical image data, `R`, B0-normalized result/error, format-level boundary traces with payload/K depth/saved pairs, all 14 validator rules with rule keywords, descriptor hash, scalar-encoding vectors, diagnostic message fixtures, refused-step atomicity fixtures, §7.3 tail-site table fixtures, `>65,535`-register wide-body fixture, `:host-limit` refusal fixtures, and million-iteration tail fixtures).
- `lines 48–106` add `r5`'s three-lane execution model (`R4 CLJS reference, Rust kernel/interpreter, wasm`), Node/Wasmtime/Wasmer differential harness rules, semantic and register corpus requirements, and `K4` LLVM / wasm boundary fallback fixtures (`r5:133, 362–375`).
- `lines 108–230` include `r5`'s three-codec hash determinism obligations (`r5:57–74`) and deterministic traversal prerequisite (`r5:299–316`).
- `lines 231–442` include `r5`'s `W0`–`W6` gates (`r5:377–446`).
- `lines 444–466` retain `rust-kernel.md:1201–1228`'s `K1`–`K5` completion gates verbatim in compact bullet form.

---

## 3. Task 3 — `docs/design/yin.vm.rust-kernel.md` Amendment Cleanliness (`AMENDMENT CLEAN?`)

Comparing `docs/design/yin.vm.rust-kernel.md` against the 1,334-line revision-2 baseline verified in Round 5:

1. **Line 3 (`Status:` header)**: Unchanged (`Status: design only, revision 2 (2026-10-03). Not authorized for implementation.`).
2. **Lines 1–1299 and 1301–1334**: Completely unchanged; zero lines shifted.
3. **Line 1300 (in-place supersession note at end of `DEFERRED:` paragraph)**:
   - Was: `client and transports; WASM as an artifact target.`
   - Now: `client and transports; WASM as an artifact target — superseded by [yin.vm.register-wasm.md](./yin.vm.register-wasm.md) (owner-authorized wasm plan, 2026-10-18).`
4. **Lines 1335–1338 (appended at EOF after line 1334)**:
   ```markdown
   Related specs: [yin.vm.register-wasm.md](./yin.vm.register-wasm.md) (wasm execution backend); [yin.vm.conformance-kit.md](./yin.vm.conformance-kit.md) (shared K0/W0 kit).

   2026-10-18: Added the owner-authorized wasm-plan and shared-kit pointers; revision 2 status and all other normative content are unchanged.
   ```

Because the `line 1300` edit is single-line and the `Related specs` + dated note are appended after `line 1334`, every single line coordinate in `docs/design/yin.vm.rust-kernel.md` (`1–1334`) is preserved without shift.

---

## 4. Task 4 — Cross-References and Link Resolution (`CROSS-REFS`)

All relative markdown links in all three files under `docs/design/` were extracted and verified against the filesystem (`docs/design/`):

| Source File & Line | Link Target | Target File on Disk | Resolution |
|---|---|---|---|
| `yin.vm.register-wasm.md:15` | `./datom.world.md` | `docs/design/datom.world.md` | **OK** |
| `yin.vm.register-wasm.md:16` | `./yin.vm.debruijn.register.md` | `docs/design/yin.vm.debruijn.register.md` | **OK** |
| `yin.vm.register-wasm.md:17` | `./yin.vm.semantic.md` | `docs/design/yin.vm.semantic.md` | **OK** |
| `yin.vm.register-wasm.md:18` | `./yin.vm.linker.md` | `docs/design/yin.vm.linker.md` | **OK** |
| `yin.vm.register-wasm.md:19` | `./yin.vm.engine.md` | `docs/design/yin.vm.engine.md` | **OK** |
| `yin.vm.register-wasm.md:20, 90` | `./yin.vm.rust-kernel.md` | `docs/design/yin.vm.rust-kernel.md` | **OK** |
| `yin.vm.register-wasm.md:22` | `./yin.vm.conformance-kit.md` | `docs/design/yin.vm.conformance-kit.md` | **OK** |
| `yin.vm.conformance-kit.md:16` | `./yin.vm.rust-kernel.md` | `docs/design/yin.vm.rust-kernel.md` | **OK** |
| `yin.vm.conformance-kit.md:17` | `./yin.vm.register-wasm.md` | `docs/design/yin.vm.register-wasm.md` | **OK** |
| `yin.vm.rust-kernel.md:17–28` | `./datom.world.md`, `./yin.vm.debruijn.register.md`, `./yin.vm.linker.md`, `./yin.vm.code-as-tuples.md`, `./yin.vm.semantic.md`, `./yin.vm.debruijn.targets.md` | All present in `docs/design/` | **OK** |
| `yin.vm.rust-kernel.md:1300, 1335` | `./yin.vm.register-wasm.md` | `docs/design/yin.vm.register-wasm.md` | **OK** |
| `yin.vm.rust-kernel.md:1335` | `./yin.vm.conformance-kit.md` | `docs/design/yin.vm.conformance-kit.md` | **OK** |

---

## Verdict

**`PASS`** (ready to commit)
