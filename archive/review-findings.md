# Implementation Review Findings: `dao.data` and consumers

**Goal:** Provide an independent, read-only architectural and implementation review of the uncommitted `dao.data` implementation diff based on the frozen `docs/design/dao.data.md` spec.

## Findings List

### 1. `dao.data` Fidelity to Spec
**Severity: Already correct**
**Evidence:** `src/cljc/dao/data.cljc` (Lines 88-105 for containers, 45-49 for leaves, 52-60 for numbers, 63-74 for streams).
- **`counted?` gate & `:count`:** Checked directly (Line 87) and only appended when true (Line 103). The true count is preserved even if the output is truncated, matching the spec.
- **Probe discipline (non-counted):** Depth 0 returns without polling (`(if (zero? depth) ...)` at Line 88). For depths > 0, it calls `(take (inc items))` to verify truncation without fully realizing the sequence (Lines 92-95).
- **Number portability:** Correctly delegates to `transit/portable-value?` (Line 58), keeping valid integers and stringifying overflowing/ratio/non-finite numbers with `:truncated? true`.
- **String/keyword/symbol truncation:** Handled symmetrically in `char-node` (Line 39). Evaluates `> chars` limit correctly across strings, keywords, and symbols, reverting keywords/symbols to cut strings if truncated. 
- **Stream identity:** `stream-node` correctly evaluates `(stream/descriptor x)` and recursively summarizes the `:dao.stream/identity` using the exact same bounds if `:dao.stream/ok` (Lines 63-74). 
- **Type enforcement:** Both `:dao.data/items` and `:dao.data/entries` map reliably to literal vectors (`mapv`) (Line 97-101), preventing silent collapses or map reconstruction.

### 2. `dao.data_test.cljc` Test Coverage
**Severity: Already correct**
**Evidence:** `test/dao/data_test.cljc`
- The test suite rigorously addresses all specified edge cases:
  - Uses `counting-seq` (Line 25), a custom non-chunked `lazy-seq`, to strictly measure the `items + 1` probe claim (Lines 159-173). 
  - Confirms a fully-realized `LazySeq` evaluates to `counted? false` and lacks a `:count` projection (Line 153).
  - Explicitly tests an exhausted-during-probe lazy sequence omitting `:count` (Line 147).
  - Properly asserts portable vs. non-portable ranges (`9007199254740992`, non-finites, JVM ratios) (Lines 119-135).
  - Ensures correct formatting and boolean values on conforming/non-conforming descriptors (Lines 203-224) and string/symbol/keyword truncation (Lines 96-116).

### 3. FFI & Telemetry Wiring
**Severity: Already correct (Confirmed Improvement)**
**Evidence:** `src/cljc/yin/vm/v2/telemetry.cljc` (Lines 57-79, diff), `src/cljc/yin/vm/v2/ffi.cljc` (Line 214)
- Removing `telemetry.cljc`'s local `type-tag` and delegating to `dao.data/tag` is a deliberate, robust enhancement. The previous mock `type-tag` defensively identified all handles as `:opaque` since verifying their surfaces belonged in the final emit pathway. Now, since `data/tag` handles `stream/descriptor?` gracefully (Line 28), distinguishing valid handles prior to map/sequential checks behaves correctly. The downstream output via `ffi.cljc` to `emit-snapshot` safely conveys the more precise `:stream` tag context without breaching any constraints.

### 4. REPL v2 Print-Site Left-Unconverted Wiring
**Severity: Already correct**
**Evidence:** `src/cljc/yin/repl/v2/serve.cljc` (Lines 380-381, Line 405), `src/cljc/yin/repl/v2/connect.cljc`
- **`serve.cljc` "unknown endpoint event kind":** The `lifecycle-transition` event handler takes `kind` directly (Line 381). Tracing the caller logic around Line 405 demonstrates that `kind` originates from `(when (contains? event-kinds kind) kind)`. Consequently, `kind` is strictly bounded to either `nil` or a keyword predefined within the fixed `event-kinds` set. Unbounded memory usage via an uncontrolled value is impossible here.
- **`connect.cljc`'s 3 unconverted sites:** 
  - `(pr-str (:dao.stream/outcome result))` (Line 334): Yields predetermined keywords like `:dao.stream/ok`.
  - `(pr-str (:terminal client))` (Line 442): Relays internal terminal statuses (safe keywords like `:ended`).
  - `(pr-str (:url connection))` (Line 489): Handshakes a URL string inherently constrained at parse-time. 
- **Diagnostic bounds choice:** The selected limit `{:depth 3 :items 8 :chars 200}` hits the sweet spot for operator-driven debugging logic—capturing meaningful insight while defending against run-away output or memory bloat.

### 5. `:opaque`-for-throwing-descriptor Resolution
**Severity: Already correct (Confirmed Architectural Decision)**
**Evidence:** `dao.data.md` spec notes vs. implementation limitations.
- Defending against a malformed protocol descriptor that outright *throws* requires host-specific `catch` expressions (e.g. JVM `Exception`, JS `js/Error`). Utilizing these would violate the strict cross-host, no-reader-conditionals rule of `dao.data`. Bending the rule for an isolated exception introduces systemic brittleness. Treating malformed/throwing handlers comparably to an adversarial infinite lazy sequence appropriately defers guarding responsibilities to the calling environment. The Architect/Orchestrator decision to adapt the design doc stands as sound logic.

---

## Overall Verdict
**Verdict:** 🟢 **Ready to proceed toward Architect sign-off as-is.**

The uncommitted implementation accurately executes against the exact constraints of the `dao.data.md` specification with impressive fidelity. Coverage handles edge cases well, and the integration inside the REPL and FFI workflows correctly restricts unbounded outputs while preserving deliberate unconverted call sites confidently. No functional blocks or gaps were found.
