I’ll add a bounded prior-art note that distinguishes verified Unison runtime facts from analogy and avoids claiming runtime or reference-resolution details that remain unverified.
Completed-GMT: 2026-09-21 18:30:37 GMT  
Completed-Local: 2026-09-22 01:30:37 +07  
Coding-Agent: codex  
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a

## 1. Prior-art assessment

Unison supports architecture B by analogy: its documented runtime pipeline starts
from a full-information term, performs transformations including lambda lifting
and ANF, and reaches a stack-oriented IR using De Bruijn positions. This is
consistent with lowering named datoms into a lossless executable encoding rather
than lowering the lossy identity projection.

It does not establish that Unison executes its exact stored identity form.
Lambda lifting, ANF, and let-rec minimization are deferred here. The existing
named linearizer already provides a deterministic stack-oriented image, and
lambda lifting would require a separate closure and calling-convention
contract.

Unison decompilation is not equivalent to this design's inverse invariant.
Decompilation means producing displayable terms; this design requires exact
recovery of named attributes, scalar spellings, tail flags, and provenance,
modulo explicitly approved tempid and row-order normalization.

## 2. Honest parity statement

- Both designs use De Bruijn positions for local bindings and stack-oriented
  execution artifacts.
- Unison's documented runtime phases support the general architecture, but do
  not prove that its runtime consumes the stored identity form.
- Unison runtime reference resolution, exact MCode/Machine behavior, and input
  artifact are UNVERIFIED.
- No claim is made here about Unison runtime parity, dependency loading, or
  execution of hash-addressed definitions.

## 3. Edit

Added a prior-art note documenting only the verified Unison facts and URLs,
explicitly marking the remaining runtime claims UNVERIFIED. It also states why
lambda lifting, ANF, and let-rec minimization are deferred and distinguishes
decompilation from the executable encoding's invertibility invariant.

## 4. Owner decisions

- Whether lambda lifting or ANF becomes a later optimization phase.
- Whether exact Unison runtime interoperability is a project goal.
- Whether the future linker should target a Unison-like dependency identity
  model or only yin.vm-local hash linking.
- Whether the cited Unison documentation is sufficient as a stable external
  reference.

Sections changed: §7.1 Prior art and §7.2 Future reference-by-hash linking
(numbering adjustment only for the latter).
