I’ll check the revised outcome rules and validation clauses, then confirm reconstruction and allocation agree with them.
Completed-GMT: 2026-10-09 18:38:19 GMT
Completed-Local: 2026-10-10 01:38:19 Asia/Bangkok

Reconstruction, explicit park classification, conservative allocation overlap, and CFG reachability wording now pass. The revised terminal-outcome model still has three freeze blockers.

| Severity | file:line | Evidence | Correction |
|---|---|---|---|
| High — specification contradiction | `docs/design/yin.vm.semantic-register-vm.md:428` | §3.1 now says resume mints a destination, but §3.3 still says “resume mints nothing”; line 438 likewise excludes resume. Canonical numbering therefore has two incompatible rules. | Update §3.3 to mint a conceptual destination for resume, retaining the existing arm-destination reuse rule. |
| High — outcome/definition contradiction | `docs/design/yin.vm.semantic-register-vm.md:375` | Terminal outcome is equated with a never-defined destination. A tail call still contains a syntactic `rd`; an enclosing call after a terminal operand also contains its destination definition in unreachable layout syntax. Rule 7 and §3.2 do not distinguish syntactic definitions from reachable writes consistently. | Define terminal as **no normal result on that path**, rather than no syntactic definition. Distinguish minted IDs, syntactic destination occurrences, and runtime writes. Make outcome composition conditional on children continuing; explicitly handle terminal tests and mixed result/terminal arms. |
| High — incomplete allocation | `docs/design/yin.vm.semantic-register-vm.md:736` | Every virtual register requires an interval starting at its first definition, but resume’s newly minted destination has no definition. A wholly terminal body can nevertheless reference it in its structural return/halt. | Pin how definition-less IDs are lowered: assign deterministic structural-only slots, or specify another deterministic rewriting policy. Ensure every emitted native operand is in bounds, without treating the slot as a saved runtime value. |

**A.** The recursive structure is finite and can support a well-founded outcome relation. As written, minting, definition validation, and allocation do not yet agree with that relation. Rules 8/10/12 correctly distinguish runtime paths from structural layout.

**B.** §4.3 now matches the act/tail window obligations. Include the segment and suspension-site coordinates from §4.2 in the reconstruction obligation explicitly; their omission from §4.3’s list is editorial.

**C.** No further blocker found in the requested sections. The remaining work is to make terminal expressions consistent across projection, validation, and native lowering. Choosing the independent liveness reference implementation can remain deferred.

Read-only review; no files edited or tests run.

VERDICT: CHANGES_REQUESTED
