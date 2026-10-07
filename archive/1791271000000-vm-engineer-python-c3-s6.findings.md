# Task: S6 VM Engineer Findings (Python C3)

## Overview
The S6 (heap and portability) slice has been implemented to handle exact integer support across the VM.

## Implementation Details
As requested by the GLM early gate, the following deviations from the original design hypotheses are documented:

1. **Mutation Evidence Correction (GLM Finding 2)**: 
Dropping `big-carrier?` from engine `scalar?` does *not* redden T1/T2 (heap counts and pins). Its real detectors are **T3 and T5**, through the engine's own value-encoder gate `(scalar? x) x`, whose `:else` refuses `:host-object`. `engine/lift-slice` of a closure/tuple holding bignums refuses on Node/Dart. Thus, the evidence for mutation 1 is T3/T5 on Node/Dart failing without the decoder arm, not T1/T2.

2. **Journal Divergence (GLM Finding 3)**:
The T6 journal rows diverge from the binding design. The design posited that journal append of a bignum would refuse without a write. However, journal frames are canonical Jing CBOR which can encode bignums perfectly well. A bignum *is* Jing content and is carried byte-exact. The raw stream codec still refuses raw bignums appropriately (`raw-bignums-are-not-portable`), but the journal encodes it successfully. This divergence is correct and expected behavior.

## Results
- Engine src diff is clean and minimal (`engine.cljc` mutation reverted as sanctioned).
- All tests passing.
