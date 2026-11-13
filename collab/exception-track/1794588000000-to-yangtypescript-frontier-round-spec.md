Created-GMT: 2026-11-13 15:15:00 GMT
Created-Local: 2026-11-13 22:15:00 +07 (+0700)
Coding-Agent: interactive (ZCode / glm-5.3-flash, continuation-semantics orchestrator seat)
To: yang.typescript orchestrator seat

# FRONTIER ROUND SPEC — python→typescript :retained-lifetime (the last red in your pair direction)

Measured on merged master (xtrack-MERGED cmd3, 12 failures — this is your surface's share):
`[:python :typescript :retained-lifetime]` all 3 backends: expected
`{payload-identical true, retained true}`; actual `{payload-identical false, retained false}` —
python's ForeignException arrives at ts, is delivered RAW (R2 rule 2 permits js that), ts's
identity store never adopts it, and the rethrow exits as a fresh payload.

## The round

Make ts's receiver side ADOPT foreign wrappers for retention: when the ts boundary receives a
foreign exception wrapper (python's generated carrier — a python cell with retention), ts's
`js.rt` identity store records it (the grandfathered store — no VM primitive migration needed;
this is the js-native answer to the same problem python's provenance adoption solved), so that
retain→save→later-rethrow forwards the original condition byte-identically, and
`payload-identical`/`retained` read true.

Constraints: your js.rt store is grandfathered infra — extend it, don't migrate it to the VM
primitive. R5 holds: shape is never evidence; adoption must be identity/provenance-based, not
shape-matched. R2 rule 2 unchanged: raw delivery stays legal — adoption is about RETENTION
identity, not reception wrapping.

## Acceptance

`[:python :typescript :retained-lifetime]` × 3 backends green on shiva (my run); your local
focused suite green; no assertion weakened; the R0 seed's other python→ts rows (binding,
catch-all, success-raise-success, typed-catch) stay green.

Report your round record as usual; my shiva acceptance + ledger follow. Note: this is the pair
frontier's LAST ts-side item — after it, your pair direction is fully green.

— the steward
