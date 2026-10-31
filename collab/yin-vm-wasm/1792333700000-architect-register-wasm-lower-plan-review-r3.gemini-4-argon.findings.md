# Round-3 Independent Second-Architect Review: Register-VM Wasm Lowering Plan (R3)

- **Reviewed Plan**: `collab/1792329904305-architect-register-wasm-lower-plan-r3.gpt-6-astra.findings.md`
- **Previous Plan Baseline**: `collab/1792329904305-architect-register-wasm-lower-plan-r2.gpt-6-astra.findings.md`
- **Reviewer**: `gemini-4-argon` (Architect)

---

## 1. Evaluation of Round-3 Review Questions (a–e)

### (a) Import ABI (`yin.register.host.v1`) Completeness and Freedom from JS Assumptions
- **What passes**:
  - The import surface in §4.1.1 (`collab/1792329904305-architect-register-wasm-lower-plan-r3.gpt-6-astra.findings.md:202-227`) is narrow and runtime-neutral: module `"yin.register.host.v1"` exports `memory` (one non-shared `wasm32` linear memory), `functions` (one original function table), and `exchange(control_ptr: i32) -> i32`, plus immutable `i32` base-offset imports for component modules (line 204).
  - Avoiding `i64`, `externref`, multi-value returns, and host pointers in import/export signatures while placing 64-bit PCs (`max-layout-length` is `4503599627370495` = $2^{52}-1$ at `src/cljc/yin/vm/debruijn/register.cljc:234-236`) and exchange sequence numbers in linear memory eliminates JS `BigInt`/Number coercion hazards and Rust ABI mismatches.
  - Control block layout at bytes `0..255` (`control_ptr = 0` in v1, magic `0x31524e59` at `+0`, major `1` at `+4`, minor `0` at `+8`, header length `64` at `+12`, request ptr/len at `+16/+20`, response ptr/cap at `+24/+28`, written len at `+32`, required len at `+36`, 64-bit sequence at `+40/+44`, transport status at `+48`, flags at `+52`, reserved zero at `+56/+60`, initial request `[256, 32768)` and response `[32768, 65536)`, task allocation from `65536`; line 206) is concrete and implementable in both Rust (`wasmtime`/`wasmer`) and ClojureScript (`Node`).
  - The `NEED_SPACE` (`1`) retry rule (lines 210–212) — where the host executes the transition at most once per `(task, sequence)`, retains the serialized response bytes until acknowledged by the next sequence, writes required length to `+36`, and serves `fetch-response` for the same sequence from retained bytes without re-running the transition — prevents duplicate side effects when a response overflows the initial buffer.
- **Gaps requiring specification adjustment (see `MAJOR-1` and `MAJOR-2` below)**:
  1. **Three distinct canonical codecs/hashes in the native Rust service (`MAJOR-1`)**: §4.1.1 (line 214) defines a new private binary wire codec (`u8 tag | u32 byte-length | payload`, tags `0..13`) for ABI messages, and line 225 requires the Rust native service library to port the CLJC transitions. However, guest-observable state in the CLJC reference kernel depends on **two existing repository encodings** that are distinct from the §4.1.1 binary codec:
     - `yin.vm.debruijn-register-code/register-hash` (`src/cljc/yin/vm/debruijn_register_code.cljc:262-271`) over `yin.vm.debruijn-code/encode-scalar` (`src/cljc/yin/vm/debruijn_code.cljc:410-465`, which frames scalars as lowercase hex strings via `framed` at `:198-203`), used at runtime by `install-image` (`src/cljc/yin/vm/debruijn/register.cljc:139, 152`), `attach-image` (`register.cljc:210, 224`), `compose` (`register.cljc:269, 274`), `layout-images` (`register.cljc:289`), `image-identity` / `image-holds?` (`register.cljc:915-916`), and `effects/code-space-defect` / `effects/continuation-defect` (`src/cljc/yin/vm/debruijn_register_effects.cljc:274, 292`).
     - `dao.jing/content-hash` over `dao.jing.cbor/encode` (`src/cljc/dao/jing.cljc:160-179`, `src/cljc/dao/jing/cbor.cljc:1-28`), used at runtime by `values/owner-tag` (`src/cljc/yin/vm/values.cljc:123-130`), `engine/seal-of` (`src/cljc/yin/vm/engine.cljc:299-302`, which produces the `:seal` string on every guest-observable `:stream-ref`, `:cursor-ref`, and `:cell-ref` in `engine/issue-ref` at `:316-328`), `vm/primitive-profile` (`src/cljc/yin/vm.cljc:338-356`), and `jing/segment-key` (`src/cljc/yin/vm/module.cljc:92`, `src/cljc/yin/vm/engine.cljc:1281`).
     If the Rust service uses the §4.1.1 binary codec instead of `debruijn-code/encode-scalar` and `dao.jing.cbor/encode` for those hashes/seals, `:seal` values on stream/cursor/cell refs, `:owner` tags, and `:hash` (`R`) checks in `continuation-defect` and `attach-image` will diverge between Node and Wasmtime/Wasmer.
  2. **ABI binary codec determinism rules (`MAJOR-2`)**:
     - *Map/set key sorting vs. `tag 13` (`checked arena reference`)*: §4.1.1 (line 214) states *"map keys and set elements sort by encoded bytes"* while §1.1 (line 31) says *"Known immutable arena values may be referenced by checked task-local ids in those messages, with definitions transmitted on first use."* If a map key or set element is encoded as `tag 13 (u32 id, u32 generation)`, sorting by the wire node's bytes makes map/set order depend on whether a key was already in the arena (tag `13` vs. value tag) and on arena `id` numbering.
     - *CLJS float64 (`tag 4`) vs. integer (`tag 3`) and NaN canonicalization*: On CLJS, `1.0` and `-0.0` are JS `Number` values for which `(js/Number.isSafeInteger x)` is `true`. As `src/cljc/yin/vm/integer/host.cljc:61-66` (`exact-integer?`) and `src/cljc/yin/vm.cljc:905, 929` (`plain-data?`, `machine-data?`) establish, CLJS requires `dao.jing.cbor`'s `float64` carrier (`cbor/float64?`) to keep integral floats distinct from integers and `(not (and (zero? x) (neg? (/ 1 x))))` to exclude `-0.0` from exact integers. In addition, both `src/cljc/yin/vm/debruijn_code.cljc:233-234` (`"000000000000f87f"`) and `src/cljc/dao/jing/cbor.cljc:20` (`7ff8000000000000`) canonicalize all NaN bit patterns to a single quiet NaN, whereas §4.1.1 line 214 (`4 float64 (eight IEEE bits)`) omits NaN bit canonicalization.
     - *Redundant inner count vs. outer `u32 byte-length`*: Line 214 specifies `u8 tag | u32 byte-length | payload` for all nodes, then says *"Text uses counted little-endian UTF-16 code units"* and *"Collections are length-delimited node sequences"*. W0 must state explicitly whether `payload` for `tag 5` and `tags 8..11` contains only the UTF-16LE bytes / child nodes bounded by the outer `u32 byte-length`, or includes an additional inner `u32` element/code-unit count.

### (b) Consistency of the Core-Wasm-1.0 Feature Profile and WasmGC Non-Goal
- **What passes**:
  - The feature profile (`collab/1792329904305-architect-register-wasm-lower-plan-r3.gpt-6-astra.findings.md:11-15, 93, 271, 293-305`) is internally consistent across all sections.
  - All post-MVP instructions and types (`externref`, `funcref` value manipulation, `call_ref`, `return_call`, WasmGC, `memory.copy`/`memory.fill`, `table.grow`/`table.set`/`table.copy`, multi-value, multi-memory, memory64, SIMD, exceptions, threads, component model, JS string builtins) are excluded from the baseline profile (line 11).
  - §5 (line 271) resolves the r2 table-growth ambiguity without requiring guest `table.grow` or `table.set` instructions: the baseline preallocates table capacity (`65,536` slots in the W0 test profile) and installs new component body functions via the host embedding API (or active element segments), returning a qualified resource refusal on exhaustion.
  - WasmGC is rightly maintained as a non-goal (lines 13, 353): Yin's logical heap cells (`:heap`, `engine/collect` at `src/cljc/yin/vm/engine.cljc:2587-2602`, `module/gc-roots` at `src/cljc/yin/vm/debruijn/register.cljc:955-957`), value equality over immutable snapshots, and Wasmer 6.0.1 engine support all favor the `i32 ValueRef` arena design in §1.1.
  - W0 toolchain pins (lines 293–303) match the repository's existing versions in `mise.toml:6-7` (`clojure = "1.12.4.1602"`, `node = "25.6.1"`) and `deps.edn:40` (`thheller/shadow-cljs {:mvn/version "3.3.5"}`).

### (c) Falsifiability of Node/Wasmtime/Wasmer Parity Gates and Cross-Runtime Determinism
- **What passes**:
  - Requiring identical wasm binaries to execute under both Node and Wasmtime from W1 onward (and Wasmer by W4/W5), comparing step-by-step guest state and ABI traffic against interpreted `yin.vm.debruijn.register` (`collab/1792329904305-architect-register-wasm-lower-plan-r3.gpt-6-astra.findings.md:283-287`), is falsifiable and prevents native execution from degenerating into final-value-only smoke tests.
  - Logical counters (`:id-counter`, `:yin.k/issued`, `:origins`), liveness (`body-liveness` using `(sorted-set)` at `src/cljc/yin/vm/debruijn_register_code.cljc:349`), and wait-set polling order (`check-wait-set` at `src/cljc/yin/vm/engine.cljc:2126-2201`) are deterministic and clock-free.
- **Gap requiring attention (`MINOR-1`)**:
  - While most of `src/cljc/yin/vm/engine.cljc` sorts sets/maps before sequential side effects (e.g. `(sort-by str ...)` at `engine.cljc:1009, 1072, 1380, 1394, 1416, 1497` and `(sort-by key ...)` at `:1106`), several module-linking and diagnostic paths in `engine.cljc` and `module.cljc` traverse unsorted maps:
    - `engine/encoder` (`src/cljc/yin/vm/engine.cljc:918-922`) and `engine/encode-store` (`:1044-1046`) iterate map entries in host map order while `cell-for!` (`:847-860`) assigns sequential `:yin.k/c-0`, `:yin.k/c-1` IDs via `(count (:cells @found))` and builds vector `:yin.k/entries`.
    - `engine/receive-module` (`src/cljc/yin/vm/engine.cljc:1108-1112`) passes `(concat (vals (:slice entry)) (vals fresh))` into `lower-resources` (`:1000-1041`), which walks `(markers :yin.k/stream values)` (`:978-983`, traversing `(vals n)` of maps) and calls `(gensym s "stream")` (`:1023`), assigning `:stream-0`, `:stream-1`, ... and their `:seal` hashes in host map iteration order.
    - `module/module-entries` (`src/cljc/yin/vm/module.cljc:100-110`) walks `(vals node)` of `:modules` without sorting (consumed by `spawn-child` at `engine.cljc:1323` and `link-install` at `engine.cljc:1452`), `module/list-modules` (`module.cljc:166-169`) returns unsorted `(keys (:modules registry))` in `:available (vec (list-modules (:modules state)))` (`module.cljc:611`), and `engine/resume-from-run-queue` (`engine.cljc:2304-2306`) checks `(some #(when-not (keyword? %) %) (keys updates))` in map iteration order.
    Because CLJS uses `PersistentArrayMap` (insertion order up to 8 keys) and `PersistentHashMap` (Clojure `hasheq` trie order beyond 8 keys), a Rust native service using `BTreeMap` or `IndexMap` will diverge on those paths unless the plan mandates a shared deterministic map-traversal order.

### (d) Slice Scope vs. Acceptance Gate Alignment
- **What passes**:
  - The native Rust service obligations are staged alongside the slices where their transitions first become executable: W0 (ABI/codec/probes), W1 (values, primitives, store, execution state), W2 (continuations, restore, owner/type/payload validation), W3 (scheduler, streams, custody gates, heap GC, FFI two-step), W4 (module admission, module stores, closure/resource transport, Wasmer bring-up), and W5 (release gate across Node, Wasmtime, and Wasmer).
  - None of the r2-approved invariants were weakened or broken.
- **Gap requiring gate update (`MINOR-2`)**:
  - §4.1.1 (lines 210–212) introduces `NEED_SPACE` (`1`), `fetch-response` retained-response retry, `BAD_MESSAGE` (`2`), `ABI_MISMATCH` (`3`), and `RESOURCE_LIMIT` (`4`), but testing of those transport statuses and `NEED_SPACE` retry idempotence is only listed in **W5's** acceptance gate (line 347) rather than in **W0** (transport framing/retry conformance) and **W3** (verifying that an effectful transition whose response triggers `NEED_SPACE` does not repeat the effect).
  - W4's scope header (line 337) adds *"implement native admission, module stores and resource/closure transport. Bring up Wasmer with the same native service library and run W1–W4 suites before W5"*, but W4's `Acceptance:` paragraph (line 341) was copied unchanged from r2 and omits the Wasmer W1–W4 suite gate and native↔CLJS module-slice (`lift-slice` / `receive-module`) parity check.

### (e) Audit of the "r2 Body Unchanged" Claim
- **Verified**: Line-by-line comparison of `collab/1792329904305-architect-register-wasm-lower-plan-r2.gpt-6-astra.findings.md` (327 lines) and `collab/1792329904305-architect-register-wasm-lower-plan-r3.gpt-6-astra.findings.md` (404 lines) confirms that **every edit in r3 is accounted for in the "Round 3 changes" summary (lines 397–404)**:
  - All 23 opcode rows in §2.3 (r2 lines 99–123 vs. r3 lines 107–131), §2.2 (r2 lines 87–93 vs. r3 lines 95–101), §2.4 (r2 lines 125–135 vs. r3 lines 133–143), §3.1 (r2 lines 139–160 vs. r3 lines 147–168), §3.2 (r2 lines 162–174 vs. r3 lines 170–182), §4.2 (r2 lines 194–198 vs. r3 lines 229–233), §4.2.1 (r2 lines 200–210 vs. r3 lines 235–245), §4.3 (r2 lines 212–219 vs. r3 lines 247–254), §5 steps 1–4 (r2 lines 225–234 vs. r3 lines 260–269), and the Round-1 response table (r2 lines 281–297 vs. r3 lines 368–382) are preserved verbatim.
  - Two minor citation cleanups in existing prose (`engine gc-children` -> `the register kernel’s module/gc-children implementation` at r3 line 75, and `IVM/step` / `IVM/run` -> `the step method of yin.vm/IVM` / `the run method of yin.vm/IVM` at r3 line 198) are explicitly documented in the revision log at r3 lines 391 and 404. No silent edits or regressions were introduced.

---

## 2. Detailed Findings

### MAJOR-1 — Native Rust Service Must Implement Repository Codecs for `register-hash` (`debruijn-code/encode-scalar`) and `jing/content-hash` (`dao.jing.cbor/encode`), Not Only the §4.1.1 ABI Wire Codec
- **Section**: §1.1 (lines 29–35), §3.2 (line 175), §4.1.1 (lines 214, 225), §5 (line 262), §6 (line 287).
- **Evidence**:
  - `src/cljc/yin/vm/debruijn_register_code.cljc:270-271`:
    ```clojure
    [register-image]
    (jing/blake3 (str descriptor-hash (encode-register-image register-image))))
    ```
  - `src/cljc/yin/vm/debruijn_code.cljc:198-203`:
    ```clojure
    (defn- framed
      "One self-delimiting encoded part under this dimension's own class-tag
       table: the class's tag byte, the 8-hex UTF-8 byte length of `content`,
       and `content` itself."
      [class content]
      (str (get class-tag class) (to-hex (utf8-byte-length content) 8) content))
    ```
  - `src/cljc/yin/vm/debruijn_register_effects.cljc:292-296`:
    ```clojure
    (let [expected-h (rcode/register-hash (:segment payload))]
      (when (not= expected-h (:hash payload))
        {:rule :continuation-hash,
         :expected expected-h,
         :actual (:hash payload)}))
    ```
  - `src/cljc/yin/vm/values.cljc:128-130`:
    ```clojure
    [secret]
    (when (some? secret)
      (jing/content-hash [:yin.k/owner secret] {:algorithm :blake3})))
    ```
  - `src/cljc/yin/vm/engine.cljc:299-302, 328`:
    ```clojure
    (defn- seal-of
      "The seal of a `kind` reference to resource `id` under `secret`."
      [secret kind id]
      (jing/content-hash [:yin.k/seal secret kind id] {:algorithm :blake3}))
    ...
    {:type kind, :id id, :seal (seal-of secret kind id)}
    ```
  - `src/cljc/dao/jing.cljc:167-168, 178-179`:
    ```clojure
    [v]
    (cbor/encode v))
    ...
    (let [algo (clojure.core/get opts :algorithm default-hash-algorithm)]
      (digest-bytes algo (canonical-bytes v)))))
    ```
- **Problem**: §4.1.1 (line 214) defines a new private tagged binary codec (`u8 tag | u32 byte-length | payload`) for the wasm↔embedder ABI and states it is *"shared by the wasm runtime encoder/decoder, CLJS and native service."* However, the native Rust service library (line 225) must also compute guest-observable hashes and seals that the reference kernel derives from **two other canonical encodings**:
  1. `rcode/register-hash` over `debruijn-code/encode-scalar` (hex-framed strings hashed with BLAKE3) for `install-image` (`register.cljc:139, 152`), `attach-image` (`register.cljc:210, 224`), `compose` (`register.cljc:269, 274`), `layout-images` (`register.cljc:289`), `image-identity` / `image-holds?` (`register.cljc:915-916`), and `effects/code-space-defect` / `effects/continuation-defect` (`debruijn_register_effects.cljc:274, 292`).
  2. `jing/content-hash` over `dao.jing.cbor/encode` (canonical CBOR bytes hashed with BLAKE3) for `values/owner-tag` (`values.cljc:130`), `engine/seal-of` (`engine.cljc:302`, which is embedded in every guest-observable `:stream-ref`, `:cursor-ref`, and `:cell-ref`), `vm/primitive-profile` (`src/cljc/yin/vm.cljc:351`), and `jing/segment-key` (`src/cljc/yin/vm/module.cljc:92`, `src/cljc/yin/vm/engine.cljc:1281`).
- **Required Fix**: Explicitly distinguish the §4.1.1 private ABI transport codec from (1) `debruijn-code/encode-scalar` + `rcode/register-hash` and (2) `dao.jing.cbor/encode` + `jing/content-hash`, and require the native Rust service in W0–W4 to match both existing repository encodings on golden fixtures so `:hash` (`R`), `:owner`, `:seal`, `:yin.k/profile`, and `:address` are bit-identical across Node, Wasmtime, and Wasmer.

### MAJOR-2 — §4.1.1 ABI Binary Codec Needs Explicit Rules for `tag 13` in Map/Set Key Ordering, CLJS `float64` Carrier / `-0.0` / NaN Canonicalization, and Inner vs. Outer Length Framing
- **Section**: §1.1 (line 31), §4.1.1 (line 214), §6 (lines 283, 287, 305).
- **Evidence**:
  - `src/cljc/yin/vm/integer/host.cljc:61-66`:
    ```clojure
    :cljs (or (js-bigint? x)
              (and (number? x)
                   (js/Number.isSafeInteger x)
                   ;; -0 is the one zero whose reciprocal is negative
                   (not (and (zero? x) (neg? (/ 1 x))))))
    ```
  - `src/cljc/yin/vm/debruijn_code.cljc:232-234, 345`:
    ```clojure
    (let [v #?(:cljs (if (number? x) x (.-v ^not-native x)) :default x)]
      (if (not= v v)
        "000000000000f87f"
    ...
    :cljs (or (and (cbor/float64? v) (not (number? v)))
    ```
  - `src/cljc/dao/jing/cbor.cljc:19-20`:
    ```clojure
    * floating point: tag 27 `dao.jing/float64` over eight big-endian bytes,
      float32 widened exactly, every NaN made 7ff8000000000000;
    ```
- **Problem**:
  1. **Map/set sorting vs. `tag 13` arena references**: §4.1.1 (line 214) states *"map keys and set elements sort by encoded bytes"*, while §1.1 (line 31) permits known immutable arena values to be transmitted as `tag 13 (u32 id, u32 generation)` after first definition. If a map key or set element is encoded as `tag 13`, sorting by the wire node's bytes makes map/set element ordering depend on whether a key was already interned in the arena and on its arena `id` (which even the test harness normalization in §6 line 287 cannot repair if entry order flips).
  2. **CLJS `float64` (`tag 4`) vs. `integer` (`tag 3`) and NaN canonicalization**: On Node/CLJS, plain JS `Number` cannot distinguish `1.0` from `1`, and `(js/Number.isSafeInteger -0.0)` is `true`. Unless the CLJS ABI codec decodes `tag 4` integral floats into `dao.jing.cbor`'s `float64` carrier (`cbor/float64?`, `src/cljc/yin/vm.cljc:905, 929`) and classifies `-0.0` via `integer-host/exact-integer?` (`src/cljc/yin/vm/integer/host.cljc:65-66`), a `tag 4` float round-tripping through CLJS will re-encode as `tag 3` (integer) while staying `tag 4` in Rust. Furthermore, §4.1.1 line 214 specifies `4 float64 (eight IEEE bits)` without canonicalizing NaN payloads to `0x7ff8000000000000` (`00 00 00 00 00 00 f8 7f` LE) as `debruijn_code.cljc:234` and `cbor.cljc:20` do.
  3. **Outer `u32 byte-length` vs. inner count prefix**: Line 214 specifies `u8 tag | u32 byte-length | payload` for every node, then states *"Text uses counted little-endian UTF-16 code units"* and *"Collections are length-delimited node sequences"*. Two independent implementers (Rust vs. CLJS) could disagree on whether `payload` for `tag 5` (string) and `tags 8..11` (collections) has an inner `u32` count prefix in addition to the outer `u32 byte-length`.
- **Required Fix**: In §4.1.1, specify that:
  1. Map keys and set elements always sort by the **canonical encoded bytes of their underlying value** (never by `tag 13` arena-reference bytes, or forbid `tag 13` in map-key/set-element positions).
  2. `tag 4` (`float64`) canonicalizes all NaNs to IEEE-754 quiet NaN `0x7ff8000000000000` (`00 00 00 00 00 00 f8 7f` LE), treats `-0.0` as `tag 4` (per `integer-host/exact-integer?`), and uses the `dao.jing.cbor` float64 carrier on CLJS when needed to preserve integral float tags across ABI round-trips.
  3. State explicitly whether `tag 5` and `tags 8..11` rely solely on the outer `u32 byte-length` header or include an inner `u32` element/code-unit count in `payload`.

### MINOR-1 — Unsorted Map Traversals in Reference Module-Linking and Resume Helpers Can Cause Cross-Runtime Trace Divergence in W3/W4
- **Section**: §4.1.1 (line 225), §5 (line 273), §6 (lines 283, 287, 337–341).
- **Evidence**:
  - `src/cljc/yin/vm/engine.cljc:852, 918-922, 1044-1046`:
    ```clojure
    (let [cid (keyword "yin.k" (str "c-" (count (:cells @found))))
    ...
    {:yin.k/tag :yin.k/literal,
     :yin.k/entries (into []
                          (mapcat (fn [[k v]]
                                    [(encode k) (encode v)]))
                          x)}
    ...
    (defn- encode-store
      [encode store]
      (into {} (map (fn [[k v]] [(encode k) (encode v)])) store))
    ```
  - `src/cljc/yin/vm/engine.cljc:981-982, 1022-1025, 1110-1112`:
    ```clojure
    (filter #(and (map? %) (= tag (:yin.k/tag %)))
            (tree-seq coll? (fn [n] (if (map? n) (vals n) (seq n))) x)))
    ...
    (let [handle (attach-stream! s marker)
          [id s] (gensym s "stream")]
      [(assoc-in s [:resources id] handle)
       (assoc acc ident id)])
    ...
    (lower-resources attached
                     (concat (vals (:slice entry)) (vals fresh))
                     (:cells entry))
    ```
  - `src/cljc/yin/vm/module.cljc:107-109, 168-169, 609-611`:
    ```clojure
    (if (contains? node :manifest)
      [[(:yin.module/name (:manifest node)) node]]
      (mapcat walk (vals node)))
    ...
    (defn list-modules
      "List the top-level module names in a registry value."
      [registry]
      (keys (:modules registry)))
    ...
    (throw (ex-info "Module is not in this VM's registry"
                    {:module module-name,
                     :available (vec (list-modules (:modules state)))}))
    ```
  - `src/cljc/yin/vm/engine.cljc:2304-2306`:
    ```clojure
    _ (when-let [k (some #(when-not (keyword? %) %) (keys updates))]
        (fail "Ready entry resource update carries a non-minted key"
              {:rule :resource-update-key, :key k}))
    ```
- **Problem**: Unlike `body-liveness` (`debruijn_register_code.cljc:349`) and `link-install` (`engine.cljc:1380, 1394, 1416, 1497`), the paths above iterate over Clojure maps without sorting keys. Because ClojureScript uses `PersistentArrayMap` (insertion order for $\le 8$ entries) and `PersistentHashMap` (`hasheq` trie order for $> 8$ entries), a Rust native service using `BTreeMap` or `IndexMap` will visit multi-entry maps in a different order, minting different `:yin.k/c-N` cursor cell IDs in `lift-slice`, different `:stream-N` resource IDs and `:seal` hashes in `receive-module`, or different `:available` vectors on missing-module errors.
- **Required Fix**: Specify a deterministic map-traversal order (e.g., canonical encoded-byte key order) for `encoder`, `encode-store`, `markers`, `receive-module`, `module-entries`, `list-modules`, and `resume-from-run-queue` resource-update key validation across both the CLJS reference engine and the Rust native service library.

### MINOR-2 — Align W0/W3/W4 Acceptance Gates with `NEED_SPACE` Retry Testing and Wasmer W4 Suite Execution
- **Section**: §4.1.1 (lines 210–212), §6 W0 (lines 305–307), W3 (lines 331–333), W4 (lines 337–341), W5 (line 347).
- **Evidence**:
  - `collab/1792329904305-architect-register-wasm-lower-plan-r3.gpt-6-astra.findings.md:212`:
    > `On NEED_SPACE, resize and issue a transport fetch-response for that same sequence; do not replay the semantic action. Repeat fetch returns the retained bytes. A repeated sequence with different request content is BAD_MESSAGE. An effect is never repeated merely because its encoded response did not fit.`
  - `collab/1792329904305-architect-register-wasm-lower-plan-r3.gpt-6-astra.findings.md:337`:
    > `Required runtime gate: Node + Wasmtime full module/state/ABI traces; implement native admission, module stores and resource/closure transport. Bring up Wasmer with the same native service library and run W1–W4 suites before W5.`
- **Problem**:
  1. `NEED_SPACE` (`1`), `fetch-response`, `BAD_MESSAGE` (`2`), `ABI_MISMATCH` (`3`), and `RESOURCE_LIMIT` (`4`) are defined in §4.1.1 (lines 210–212), but their conformance tests are only listed in **W5's** acceptance gate (line 347) instead of **W0** (transport framing/status/retry) and **W3** (verifying that an effectful transition whose response triggers `NEED_SPACE` does not repeat the effect).
  2. W4's header (line 337) requires bringing up Wasmer on the W1–W4 suites and implementing native module admission, module stores, and closure/resource transport, but W4's `Acceptance:` paragraph (line 341) was copied unchanged from r2 and omits Wasmer W1–W4 suite parity and native↔CLJS module-slice (`lift-slice` / `receive-module`) transport assertions.
- **Required Fix**: Add forced small-capacity `NEED_SPACE` + `fetch-response` / `BAD_MESSAGE` / `ABI_MISMATCH` / `RESOURCE_LIMIT` tests to **W0** (and effect-once `NEED_SPACE` retry to **W3**), and update **W4's** `Acceptance:` paragraph to require Wasmer passing the W1–W4 suites and native↔CLJS module-slice transport parity.

---

## Verdict

APPROVE WITH CHANGES

### Findings Summary

| ID | Severity | Section | Summary |
|---|---|---|---|
| `MAJOR-1` | MAJOR | §1.1, §3.2, §4.1.1, §5, §6 | Explicitly require the Rust native service to implement `debruijn-code/encode-scalar` + `rcode/register-hash` (`src/cljc/yin/vm/debruijn_register_code.cljc:262-271`) and `dao.jing.cbor/encode` + `jing/content-hash` (`src/cljc/dao/jing.cljc:160-179`) alongside the §4.1.1 ABI binary codec so `:hash` (`R`), `:owner` (`values.cljc:130`), `:seal` (`engine.cljc:302`), `:yin.k/profile` (`vm.cljc:351`), and `:address` (`module.cljc:92`) match CLJS bit-for-bit. |
| `MAJOR-2` | MAJOR | §1.1, §4.1.1, §6 W0 | Specify in §4.1.1 that (1) map keys and set elements sort by canonical encoded value bytes (never by `tag 13` arena-reference bytes), (2) `tag 4` (`float64`) canonicalizes NaNs to `0x7ff8000000000000` (`debruijn_code.cljc:234`, `cbor.cljc:20`) and preserves integral floats / `-0.0` on CLJS via `dao.jing.cbor`'s float64 carrier and `integer-host/exact-integer?` (`integer/host.cljc:65-66`), and (3) string (`tag 5`) and collection (`tags 8..11`) payloads have a single unambiguous framing rule. |
| `MINOR-1` | MINOR | §4.1.1, §5, §6 W3–W4 | Specify a deterministic map-traversal order for `engine/encoder` (`engine.cljc:918-922`), `engine/encode-store` (`:1044-1046`), `engine/markers` / `lower-resources` / `receive-module` (`:978-983, 1017-1028, 1110-1112`), `module/module-entries` / `list-modules` (`module.cljc:100-110, 166-169`), and `engine/resume-from-run-queue` (`engine.cljc:2304-2306`) so CLJS and Rust mint identical `:yin.k/c-N` and `:stream-N` IDs. |
| `MINOR-2` | MINOR | §4.1.1, §6 W0, W3, W4 | Move `NEED_SPACE` + `fetch-response` / `BAD_MESSAGE` / `ABI_MISMATCH` / `RESOURCE_LIMIT` transport tests into W0 and W3 (for effectful once-only retry), and add the Wasmer W1–W4 suite pass and native↔CLJS module-slice parity to W4's `Acceptance:` paragraph. |

### Properties Passing Review
1. **R2 Body Integrity**: All Round-2 approved sections — all 23 opcode lowerings in §2.3 (`src/cljc/yin/vm/debruijn_register_code.cljc:70-151`, `src/cljc/yin/vm/debruijn/register.cljc:624-823`), §1.2.1 map↔memory synchronization, §2.4 host-function effect completion (`run-call-effect` at `register.cljc:564-580`), §3.1–3.2 continuation payload and restore (`register-restore`, `write-back`, `return-transition` at `register.cljc:403-518`), §4.2–4.3 stream/custody/FFI two-step (`ffi-call` at `register.cljc:583-617`), and the Round-1 response table — are retained unchanged without silent edits.
2. **Core-Wasm-1.0 Feature Profile & WasmGC Non-Goal**: The baseline profile (`i32`/`i64`/`f32`/`f64`, structured control, one non-shared `wasm32` memory, one `call_indirect` table, mutable globals, active segments) is consistent throughout, avoids guest `table.grow`/`table.set`/`memory.copy`/`return_call` instructions, and rightly keeps WasmGC and `externref` as non-goals.
3. **Import ABI Framing & `NEED_SPACE` Idempotence**: The `yin.register.host.v1` control-block layout (bytes `0..255`), single `exchange(control_ptr: i32) -> i32` import signature, and sequence-retained `NEED_SPACE` / `fetch-response` protocol cleanly decouple wasm execution from JS/browser assumptions and prevent duplicate effect execution on response buffer growth.
4. **Toolchain Pins & Multi-Runtime Differential Harness**: W0 pins match the repository's `mise.toml:6-7` and `deps.edn:40` versions and require identical wasm binaries to execute with full step and ABI trace comparison across Node and Wasmtime from W1 and Wasmer through W4–W6.
