I’ll trace the number-wire contract and host encoders, then rule on the representation and the limits for the gated slices, without editing files.


The codec already has a float64 carrier specifically to preserve integral floats on JavaScript. Its bare-number classification conforms to that contract; the defect is upstream, where source float literals reach addressed rows without their kind. I’m checking how to preserve that kind without changing runtime arithmetic or existing canonical bytes.


Python’s existing `:py/float` wrapper does not fix the address divergence: its payload is still a bare host number, which Jing encodes recursively. The fix must cover both Python literals and quoted prelude constants, and include an execution bridge because the VM’s scalar check does not yet recognize the float64 carrier.


Completed-GMT: 2026-10-02 19:21:51 GMT
Completed-Local: 2026-10-03 02:21:51 Asia/Ho_Chi_Minh

**Ruling: preserve Jing’s number-wire contract. Make float kind explicit before row construction, using the existing Jing float64 carrier.** This is a constrained form of (b), requiring no new CBOR tag or row shape. Independent architect review concurs.

1. **Root cause: conforming codec classification, defective producer representation.**

   JavaScript classification occurs at [src/cljc/dao/jing/cbor.cljc:457](/Users/sto/workspace/datomworld/src/cljc/dao/jing/cbor.cljc:457): a native Number satisfying `Number.isInteger`, except negative zero, is classified as an integer. [number-wire:1006](/Users/sto/workspace/datomworld/src/cljc/dao/jing/cbor.cljc:1006) consequently selects `int-wire`; its float branch is at line 1012.

   This **conforms** to [dao.jing.cbor.md:387](/Users/sto/workspace/datomworld/docs/design/dao.jing.cbor.md:387), which expressly requires JavaScript callers to use the float64 carrier for integral floats and reserves ordinary integral Numbers for integers. The constructor already exists at [cbor.cljc:494](/Users/sto/workspace/datomworld/src/cljc/dao/jing/cbor.cljc:494).

   The producer loses the distinction before encoding. The quoted prelude contains bare `1.0`, for example [prelude.cljc:89](/Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/prelude.cljc:89). Python source lowering also wraps a **raw host number** inside `{:py/float …}` at [lower.cljc:279](/Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/lower.cljc:279). That outer map preserves Python type semantics but does not change Jing’s classification of its nested numeric payload.

   Candidate (a), as written, already describes Node’s behavior. Extending integer normalization to JVM/Dart would violate the explicit requirement that `1` and `1.0` have different addresses, despite numeric equality: [dao.jing.cbor.md:281](/Users/sto/workspace/datomworld/docs/design/dao.jing.cbor.md:281).

2. **Required mechanism, contract clarification, and migration.**

   Adopt this additional normative text under Python’s float ruling at [yang.antlr.md:1243](/Users/sto/workspace/datomworld/docs/design/yang.antlr.md:1243), with a cross-reference from Jing’s carrier rule:

   > Every floating-point literal entering canonical rows, including prelude constants and nested Python float payloads, must preserve float64 kind before host representation erases it. Addressed values use Jing’s existing float64 representation. A Python float wrapper alone does not establish the numeric kind of its payload. Row projection and transformation must preserve that kind.

   Python parsing must construct the carrier while literal syntax still identifies a float. Prelude authoring must similarly mark floating constants explicitly **before** JavaScript collapses `1.0` into an indistinguishable Number. A later scan of bare numbers cannot recover the lost distinction.

   Keep Jing’s existing tag-27 `dao.jing/float64` encoding, signed-zero preservation, and NaN normalization unchanged ([dao.jing.cbor.md:392](/Users/sto/workspace/datomworld/docs/design/dao.jing.cbor.md:392)). Keep equal-value/different-kind collection-collision refusals unchanged ([line 177](/Users/sto/workspace/datomworld/docs/design/dao.jing.cbor.md:177)). Do not teach the generic codec Python-specific map semantics.

   **Carrier insertion is not a drop-in runtime fix.** The VM scalar predicate currently admits native numbers and exact-integer carriers, not float64 carriers ([engine.cljc:632](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:632)). Implementation must provide and test an explicit execution bridge, preserving float kind whenever values return to addressed data.

   With identical float bits and the same hash algorithm, corrected rows will have identical cross-host bytes and addresses. Previously misclassified Node rows must be re-minted, along with affected parent rows, A/A′ roots, and address-bearing references. Already-correct JVM/Dart float payload bytes need not change; a prelude/profile change can nevertheless change bundled-unit addresses ([yang.antlr.md:2681](/Users/sto/workspace/datomworld/docs/design/yang.antlr.md:2681)). Rebuild affected artifacts from source; do not alias integer addresses to float addresses. Existing Jing canonical fixtures remain unchanged.

3. **What may proceed before the fix.**

   - Retain slice 1’s float-free golden test. Its tree is explicitly hand-built and contains no numeric literals ([safepoint_test.cljc:35](/Users/sto/workspace/datomworld/test/yang/safepoint_test.cljc:35)); it proves that restricted transformation’s parity.
   - C2-S5 may continue heap, collection, refusal, and restricted determinism work. C3-S2 may continue integer-only literal/boundary byte-and-hash tests. Their stated scopes are at [yang.antlr.md:1858](/Users/sto/workspace/datomworld/docs/design/yang.antlr.md:1858) and [line 2113](/Users/sto/workspace/datomworld/docs/design/yang.antlr.md:2113).
   - **Float-bearing address acceptance remains gated.** “Float-free” must describe the entire addressed payload, including the bundled prelude—not merely the user’s Python source. Host-specific goldens do not establish portability.
   - Release that gate only after JVM/Node/Dart tests establish identical canonical bytes and A/A′ for float-bearing inputs and the full prelude, preservation through projection and decoding, distinct integer/float identities, signed-zero behavior, and working execution across the required evaluators.

Read-only inspection completed; no files changed or test suites run. The ruling is complete; implementation and its acceptance gate remain outstanding.

Status: COMPLETE