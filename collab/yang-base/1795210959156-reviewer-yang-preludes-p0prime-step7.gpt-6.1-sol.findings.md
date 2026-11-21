Created-GMT: 2026-11-20 21:48:09 GMT
Created-Local: 2026-11-21 04:48:09 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: 01a1e592-2d0b-7fe2-9438-fae05bf926d6 (pruned-rollout era; fresh confirmation session used for the sign-off)
# Task: yang-preludes-p0prime-step7-review
Role: Reviewer (step-7)

Completed-GMT: 2026-11-20 21:48:09 GMT
Completed-Local: 2026-11-21 04:48:09 +07

Three P1 findings remain:

1. **Profile collision validation can be bypassed.** [profile-closure, patch:136](/Users/sto/workspace/datomworld/collab/yang-base/1795208263748-dry-p0prime-full.patch.txt:136) checks definitions only in `(:operands ast)`. A linear tree built as `(u/then (u/def! 'x ...) (u/def! 'x ...))` hides the second definition inside the sequencing lambda.

   I reproduced `validate-profile` returning success for this duplicate, including after canonical tree encoding. Check module-level definitions across every accepted emission shape, or reject unsupported shapes explicitly. Preserve literal and nested-function boundaries.

2. **The required boxed comparison export is missing.** [Integer exports, patch:824](/Users/sto/workspace/datomworld/collab/yang-base/1795208263748-dry-p0prime-full.patch.txt:824) adds three conversions but omits `integer/compare-float-boxed`, required by [r8 §7.2](/Users/sto/workspace/datomworld/docs/design/yang.preludes.md:525) and the resolved VM-engineer amendment. Add a boxed-input wrapper over the existing exact comparison kernel, preserving the integer operand exactly and unwrapping the float only inside the host operation. Keep the legacy ABI unchanged.

3. **Float and profile-admission regression suites are absent from the deliverable.** The supplied patch modifies only `data_test.cljc` and `integer_test.cljc`; it ships no tests for the new float module or admission entry points. Include persistent portable tests for the specified float gates and fail-closed profile checks, including the collision regression above. Reported focused runs cannot replace retained coverage.

The remaining boundaries look consistent with the design: sentinel-free key normalization is wired into the listed collection operations; canonical comparison uses encoded bytes; macro consolidation preserves the existing expansion; recipes do not drive emission; and `core.prelude` remains untouched.

Using the reconstructed patch in memory, I independently confirmed the four named JVM float probes: corrected floor division, negative-zero quotient, underflow status, and `"1.0"` formatting. CLJD/Jolt gates remain declared follow-up. No repository files edited.

CHANGES STILL REQUIRED — close the profile-collision bypass, add the required boxed comparison export, and include persistent float/admission regression tests.