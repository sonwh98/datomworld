# Round-4 Independent Second-Architect Review: Register-VM Wasm Lowering Plan (R4)

- **Reviewed Plan**: `collab/1792329904305-architect-register-wasm-lower-plan-r4.gpt-6-astra.findings.md`
- **Previous Plan Baseline**: `collab/1792329904305-architect-register-wasm-lower-plan-r3.gpt-6-astra.findings.md`
- **Reviewer**: `gemini-4-argon` (Architect)

---

## 1. Verification of Round-3 Findings in R4

| Round-3 ID | Round-3 Severity | R4 Resolution Status | Verification in R4 Body & Codebase |
|---|---|---|---|
| `MAJOR-1` | MAJOR | **RESOLVED** | §1.1.1 (`collab/1792329904305-architect-register-wasm-lower-plan-r4.gpt-6-astra.findings.md:39-56`) explicitly separates the three encoding domains: (1) §4.1.1 private ABI TLV wire messages, (2) executable register scalar/image encoding (`src/cljc/yin/vm/debruijn_code.cljc:198-203, 224-242, 350-378, 410-465` and `src/cljc/yin/vm/debruijn_register_code.cljc:249-271`) hashed as UTF-8 text via `dao.jing/blake3` (`src/cljc/dao/jing.cljc:128-135`), and (3) Jing canonical CBOR encoding (`src/cljc/dao/jing/cbor.cljc:1-28` and `src/cljc/dao/jing.cljc:160-197`) using the frozen corpus (`test/resources/dao/jing/cbor-v1.json`, `test/dao/jing/cbor_conformance_test.cljc`, `test/dao/jing/hash_registry_contract_test.cljc`, `test/yin/vm/debruijn_code_test.cljc`, `test/yin/vm/debruijn_register_code_test.cljc`). Exact formulas for `:owner` (`src/cljc/yin/vm/values.cljc:123-130`), `:seal` (`src/cljc/yin/vm/engine.cljc:299-302, 316-328`), `:yin.k/profile` (`src/cljc/yin/vm.cljc:338-356`), and module `:address` (`src/cljc/yin/vm/module.cljc:92`) are specified in §1.1.1 (line 53) and gated across W0–W4 (`lines 55, 338, 359, 369, 377, 387, 399`). |
| `MAJOR-2` | MAJOR | **RESOLVED** | §4.1.1 (`collab/1792329904305-architect-register-wasm-lower-plan-r4.gpt-6-astra.findings.md:232-252`) replaces the prose summary with an explicit tag table (`tags 0..13`) and three deterministic rules: (1) **outer-only framing** (`u8 tag \| u32 payload-byte-length (LE) \| payload` with no inner element or code-unit counts, lines 232–246), (2) **ordering independent of interning** by recursively expanding `tag 13` arena references before sorting map keys and set elements by unsigned lexicographic byte comparison (plus pure non-minting structural descriptors for trusted values, lines 247–249), and (3) **float64 / NaN / `-0.0` fidelity** (`00 00 00 00 00 00 f8 7f` LE quiet NaN matching `src/cljc/yin/vm/debruijn_code.cljc:234` and `src/cljc/dao/jing/cbor.cljc:20`, CLJS `cbor/float64?` checked before `yin.vm.integer.host/exact-integer?` at `src/cljc/yin/vm/integer/host.cljc:51-66`, and `tag 4` decoded via `cbor/float64` / `cbor/float64-from-bits` at `src/cljc/dao/jing/cbor.cljc:472-480, 506-513, 530-541`, line 251). |
| `MINOR-1` | MINOR | **RESOLVED** | §4.1.2 (`collab/1792329904305-architect-register-wasm-lower-plan-r4.gpt-6-astra.findings.md:264-282`) defines a shared deterministic traversal prerequisite for both CLJC and the Rust native service at all unordered map/set traversal sites before ID minting or diagnostic selection, while preserving existing explicit source sorts (`(sort-by str ...)` at `src/cljc/yin/vm/engine.cljc:1009, 1072` and `(sort-by key ...)` at `:1106`). Audited in detail in §2 below. |
| `MINOR-2` | MINOR | **RESOLVED** | W0 (`line 361`) now gates `COMPLETE -> NEED_SPACE -> fetch-response`, repeated fetch, sequence/task validation, `BAD_MESSAGE`, `ABI_MISMATCH`, `RESOURCE_LIMIT`, bounds/overlap/overflow, and a counter-only once-only test service on Node and native harnesses. W3 (`line 389`) forces `NEED_SPACE` after real effectful transitions (stream append, FFI request send/retained-writer conversion, `gensym`/id allocation, parked-state update) to prove effects execute once. W4 (`lines 397-399`) explicitly requires Wasmer to pass the full W1–W4 suite and requires bidirectional native↔CLJS module-slice parity (`engine/lift-slice` at `src/cljc/yin/vm/engine.cljc:1054-1087` and `engine/receive-module` at `:1090-1124`). |

---

## 2. Audit of the Shared-Ordering Resolution (§4.1.2)

### 2.1 Code-Correctness of the Cited Unsorted Traversal Sites
Direct inspection of `src/cljc/yin/vm/engine.cljc` and `src/cljc/yin/vm/module.cljc` confirms that every helper listed in §4.1.2 (`collab/1792329904305-architect-register-wasm-lower-plan-r4.gpt-6-astra.findings.md:270-278`) currently iterates maps or sets in host iteration order while minting sequential IDs, building vector entries, or picking the first diagnostic:

1. **`engine/encoder` and `engine/encode-store` (`src/cljc/yin/vm/engine.cljc:847-859, 918-925, 1044-1046`)**:
   - `cell-for!` (`:852`) mints `(keyword "yin.k" (str "c-" (count (:cells @found))))` on first encounter of each cursor resource ID.
   - `encoder` (`:918-922`) encodes literal maps into vector `:yin.k/entries (into [] (mapcat (fn [[k v]] [(encode k) (encode v)])) x)` and sets (`:924`) via `(into #{} (map encode) x)` without sorting keys or elements.
   - `encode-store` (`:1044-1046`) iterates `store` entries in host map order while calling `encode` (which triggers `cell-for!`, `:origins`, and `:stores` accumulation).
   - As §4.1.2 (line 272) rightly notes, sorting *after* calling `encode` would be too late because `cell-for!` has already minted `:yin.k/c-N` during the walk; keys/elements must be sorted *before* visiting children.
2. **`engine/markers`, `engine/lower-resources`, and `engine/receive-module` (`src/cljc/yin/vm/engine.cljc:978-982, 1009-1040, 1105-1120`)**:
   - `markers` (`:981-982`) traverses `(tree-seq coll? (fn [n] (if (map? n) (vals n) (seq n))) x)` in unsorted `(vals n)` / `(seq n)` order.
   - `lower-resources` (`:1009-1010`) already explicitly sorts cursor cell IDs via `(sort-by str (distinct (map :yin.k/cell (markers :yin.k/cursor-ref values))))`, **but** at `:1017-1028` it mints `(gensym s "stream")` (`:stream-0`, `:stream-1`, ...) in the encounter order of `(markers :yin.k/stream values)`.
   - `receive-module` (`:1106`) already explicitly sorts attached images via `(sort-by key (:images entry))`, **but** at `:1110-1112` it passes `(concat (vals (:slice entry)) (vals fresh))` in unsorted map order into `lower-resources`, and decodes `(:slice entry)` (`:1114-1116`) and `fresh` (`:1117-1120`) in unsorted map order.
   - Crucially, §4.1.2 (`lines 268, 274-275`) preserves the existing explicit `(sort-by str ...)` (`engine.cljc:1009, 1072`) and `(sort-by key ...)` (`engine.cljc:1106`) orderings rather than replacing them with ABI byte order.
3. **`module/module-entries` and `module/list-modules` (`src/cljc/yin/vm/module.cljc:100-110, 166-169, 609-611`)**:
   - `module-entries` (`:109`) walks `(mapcat walk (vals node))` without sorting keys, and `list-modules` (`:169`) returns `(keys (:modules registry))` in host map order (exposed in `:available (vec (list-modules (:modules state)))` at `module.cljc:611`).
4. **`engine/resume-from-run-queue` (`src/cljc/yin/vm/engine.cljc:2301-2306`)**:
   - Validates `(:resource-updates entry)` via `(when-let [k (some #(when-not (keyword? %) %) (keys updates))] ...)` in host map iteration order.

### 2.2 Explicit Boundary Between Current Kernel Behavior and Planned Prerequisite
- The distinction between current repository behavior and planned implementation work is clean and unambiguous:
  - Opening preamble (`collab/1792329904305-architect-register-wasm-lower-plan-r4.gpt-6-astra.findings.md:3`): *"The shared traversal changes specified below are future implementation prerequisites, not claims that the current kernel already has canonical traversal order."*
  - §4.1.2 (`line 266`): *"This is a planned semantic stabilization of previously host-order-dependent behavior, not an already satisfied property of current source and not a wasm-only patch."*
  - §4.1.2 (`line 280`): *"The helpers named above exist; the ordering policy/helper itself is proposed implementation work. It must land with shared CLJC/native regression tests before the corresponding W3/W4 acceptance gate."*
- Why sorting on both CLJC and Rust is the right architectural resolution: ClojureScript switches map representation from `PersistentArrayMap` (insertion order for $\le 8$ entries) to `PersistentHashMap` (`hasheq` trie order for $> 8$ entries). No static Rust map order (`BTreeMap` or `IndexMap`) can match both CLJS representations across arbitrary insertion histories without re-implementing ClojureScript's internal map types and hash-trie collisions in Rust. Defining a canonical value-key traversal order in both CLJC and Rust makes minted IDs (`:yin.k/c-N`, `:stream-N`) and `:seal` digests a deterministic function of value content rather than host map internals.

### 2.3 Phasing of Parity Gates Around the Shared-Ordering Prerequisite
- **W0 (`line 282, 359`)**: Inventories the unordered observation sites and freezes the order-key comparator fixtures alongside the three codec domains; existing register tests pass unchanged on the reference lane (`line 357`).
- **W1 & W2 (`lines 363-378`)**: Exercise pure register instructions, continuations, `register-restore`, and `park`/`resume`, none of which invoke `resume-from-run-queue` or module slice lifting/lowering (`lift-slice` / `receive-module`). Thus W1 and W2 parity gates do not depend on the W3/W4 traversal changes.
- **W3 (`lines 280-282, 387`)**: Lands the shared `resume-from-run-queue` resource-update key ordering in CLJC and Rust before gating W3 scheduler/resource-update diagnostics and stream/cursor/cell `:seal` parity.
- **W4 (`lines 280-282, 399`)**: Lands the shared module-linking traversal ordering (`encoder`, `encode-store`, `markers`, `lower-resources`, `receive-module`, `module-entries`, `list-modules`) in CLJC and Rust before gating W4 bidirectional native↔CLJS module-slice parity across varying insertion histories and map sizes.

---

## 3. Regression Check Against R2/R3 Approved Properties

Line-by-line comparison of `collab/1792329904305-architect-register-wasm-lower-plan-r3.gpt-6-astra.findings.md` and `collab/1792329904305-architect-register-wasm-lower-plan-r4.gpt-6-astra.findings.md` confirms that no r2/r3-approved invariants were weakened or altered outside the four Round-3 fixes:
- **All 23 opcode lowerings** in §2.3 (`r4 lines 125-149`) are preserved verbatim from r2/r3 and match `src/cljc/yin/vm/debruijn_register_code.cljc:70-151` and `src/cljc/yin/vm/debruijn/register.cljc:624-823`.
- **Map ↔ linear-memory synchronization** (§1.2.1, `r4 lines 78-87`) and **GC root safety** (§1.3, `r4 lines 89-95`) are unchanged.
- **Host-function call-effect completion** (§2.4, `r4 lines 151-161`) and **continuation capture/restore** (§3.1–3.3, `r4 lines 165-206`, matching `return-transition`, `write-back`, and `register-restore` at `src/cljc/yin/vm/debruijn/register.cljc:403-518`) are unchanged.
- **Stream/park transitions, custody gates, and FFI two-step** (§4.2–4.3, `r4 lines 284-309`, matching `ffi-call` at `src/cljc/yin/vm/debruijn/register.cljc:583-617`) and **module attachment/code growth** (§5, `r4 lines 311-328`) are unchanged.
- **Core-Wasm-1.0 feature profile, WasmGC non-goal, and W0 toolchain pins** (`r4 lines 11-15, 344-352`, matching `mise.toml:6-7` and `deps.edn:40`) are unchanged.

---

## Verdict

APPROVE

### Findings Summary

| ID | Severity | Section | Summary |
|---|---|---|---|
| None | — | — | All Round-1 and Round-3 findings are resolved. No open MAJOR or MINOR findings remain. |

### Properties Passing Review
1. **Three Canonical Encoding & Hash Domains (§1.1.1)**: Private ABI TLV wire messages (§4.1.1), executable register scalar/image encoding (`debruijn-code/encode-scalar` + `rcode/register-hash` at `src/cljc/yin/vm/debruijn_code.cljc:198-203, 410-465` and `src/cljc/yin/vm/debruijn_register_code.cljc:249-271`), and Jing canonical CBOR content hashing (`dao.jing.cbor/encode` + `jing/content-hash` / `jing/segment-key` at `src/cljc/dao/jing/cbor.cljc:1-28` and `src/cljc/dao/jing.cljc:160-197`) are cleanly separated and gated against existing golden corpora so `:hash` (`R`), `:owner`, `:seal`, `:yin.k/profile`, and `:address` are bit-identical across CLJS, Wasmtime, and Wasmer.
2. **Unambiguous & Interning-Invariant ABI Wire Codec (§4.1.1)**: Outer-only `u8 tag | u32 payload-byte-length (LE) | payload` framing, map/set sorting by recursively expanded canonical value bytes independent of `tag 13` arena references, canonical quiet NaN (`00 00 00 00 00 00 f8 7f` LE), and CLJS `dao.jing.cbor` `Float64` carrier + `-0.0` preservation (`src/cljc/yin/vm/integer/host.cljc:51-66`, `src/cljc/dao/jing/cbor.cljc:472-480, 506-541`) eliminate cross-host encoding drift.
3. **Code-Verified & Properly Phased Shared Traversal Order (§4.1.2)**: All unordered map/set traversal sites that mint IDs or choose diagnostics in `src/cljc/yin/vm/engine.cljc` (`encoder`, `encode-store`, `markers`, `lower-resources`, `receive-module`, `resume-from-run-queue`) and `src/cljc/yin/vm/module.cljc` (`module-entries`, `list-modules`) are accurately identified, existing explicit sorts (`engine.cljc:1009, 1072, 1106`) are preserved, the boundary between current kernel behavior and the planned shared prerequisite is explicit, and slice gates in W0, W3, and W4 are properly sequenced around it.
4. **Complete Slice Gates & Preserved R2/R3 Architecture (§1–§7)**: Transport status and `NEED_SPACE` / `fetch-response` once-only retry conformance are gated in W0 and W3, Wasmer W1–W4 full-trace execution and bidirectional native↔CLJS module-slice parity are gated in W4, and all 23 opcode lowerings, map↔memory synchronization, continuation capture/restore, custody gates, FFI two-step, and core-wasm-1.0 portability invariants remain intact.
