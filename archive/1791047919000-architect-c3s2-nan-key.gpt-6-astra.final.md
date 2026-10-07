Completed-GMT: 2026-10-03 17:19:47 GMT
Completed-Local: 2026-10-04 00:19:47 +07

**Verdict: APPROVE one shared NaN key; amend the earlier ruling explicitly and strengthen the observable-behavior tests before sign-off.**

1. **Adopt `[:py.numeric/nan]`.** “Keep today’s behavior” was underspecified where that behavior depended on host boxing or equality. Replace it with a deterministic rule: all NaNs normalize to one dict/set key, including NaNs nested in tuple keys.

   This is a deliberate Python-profile choice consistent with value-based floats and canonical NaN content. Canonical addressing does not itself dictate Python key equality, but it provides no persistent object identity with which to reproduce CPython’s distinct-NaN behavior. Fresh unreachable entries would introduce allocation-dependent identity and break ordinary retrieval. Refusing NaNs would be deterministic but unnecessarily restrict the supported key domain. The engineer’s choice is preferable.

   This ruling concerns **key equivalence only**. It does not make guest numeric NaN equality reflexive, change float encoding, or establish a new guest `hash(nan)` contract.

2. **The documentation is substantially correct; the current tests are insufficient for the complete observable contract.** Add this explicit sentence to §8.5.4:

   > For dict/set key normalization, all NaNs belong to one equivalence class, recursively inside tuple keys; this does not change numeric comparison semantics. Reinsertion retains the first stored key and updates its value.

   Also clarify the remaining “NaN key identity … not claimed by C3” sentence at [yang.antlr.md:2130](/Users/sto/workspace/datomworld-py-c3key1/docs/design/yang.antlr.md:2130): **CPython NaN object-identity fidelity remains unsupported; deterministic NaN key behavior is now specified.** This is an amendment to the earlier ruling, not merely its implementation.

   The [new tests](/Users/sto/workspace/datomworld-py-c3key1/test/yang/python/antlr/prelude_parity_test.cljc:398) check normalized keys, distinct NaN computations, separation from infinity, and dictionary insertion collapsing to one slot. They do not directly pin all three requested behaviors. Require, on all four VMs across JVM, Node, and Dart:

   - Insert `x → 1`; lookup through the same `x` returns `1`.
   - Independently produce `y = NaN`; lookup through `y` returns `1`; assigning `y → 2` leaves one dictionary entry.
   - Insert both into a set; length is `1`, and membership succeeds through either.
   - Tuple keys containing independently produced NaNs also match.

   Use prelude-level NaN construction where source `float('nan')` is unavailable. Include a decoded canonical NaN in lookup coverage; this tests the float-address seam without claiming dictionary-cell serialization.

3. **The reviewed key helpers preserve ruling-6 exactness.** In [prelude.cljc:1278–1338](/Users/sto/workspace/datomworld-py-c3key1/src/cljc/yang/python/antlr/prelude.cljc:1278):

   - `py/num` unwraps Float64 through `data/float-value`; carriers do not enter native arithmetic.
   - Booleans become integer `0`/`1`; both float zeros produce `"0"/"1"`.
   - Integers format directly through the integer module. Thus 2^53 and 2^53+1 remain distinct, while float 2^53 shares the former key.
   - Large finite floats are halved exactly until an integral significand below 2^53 can be constructed, then restored with exact integer shifts.
   - Fractional floats are doubled exactly until integral. For a negative exponent, the first integral significand is odd, so the power-of-two denominator is already reduced. This includes the smallest subnormal, `1/2^1074`.
   - Non-finite classification uses `<= x x`, avoiding boxed-NaN equality. Positive and negative infinity receive distinct signed keys; NaN receives neither.

   I found no rounding or rational-reduction hole in that algorithm. Strengthen boundary fixtures with maximum finite binary64, negative minimum subnormal, and the normal/subnormal transition.

   Two qualifications remain. First, decomposition and decimal formatting consume the explicit integer-module limits: minimum-subnormal normalization needs a 1075-bit denominator. Small configured limits must produce the documented guest failure consistently, never approximate or silently fall back to host keys. Second, repeated interpreted decomposition merits a separate cost check, especially for subnormals; mathematical exactness alone does not establish acceptable runtime cost.

This is an architectural approval of the behavior and a static assessment of the helpers, not implementation sign-off: no suites were run, and Git currently reports unmerged entries in this worktree.