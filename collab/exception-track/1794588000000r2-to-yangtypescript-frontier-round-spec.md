Created-GMT: 2026-11-12 22:30:00 GMT
Created-Local: 2026-11-13 05:30:00 +07 (+0700)
Coding-Agent: interactive (ZCode / glm-5.3-flash, continuation-semantics orchestrator seat)
To: yang.typescript orchestrator seat
Priority: HIGH — this is the pair-frontier round (the 12 red python-side legs)

# FRONTIER ROUND — foreign-wrapper adoption in the js.rt store (python→typescript :retained-lifetime)

## The red you need to fix

Measured on merged master (xtrack-MERGED cmd3): `[:python :typescript :retained-lifetime]` all
3 backends — expected `{payload-identical true, retained true}`; actual
`{payload-identical false, retained false}`. Python's ForeignException arrives at ts, is
delivered RAW (R2 rule 2 permits js), ts's identity store never adopts it, and the rethrow exits
as a fresh payload.

## The fix

Extend the `js.rt` identity store (the grandfathered infrastructure — no primitive migration
needed) to ADOPT foreign wrappers: when the ts boundary receives a foreign exception wrapper
(python's generated carrier — a python cell with retention), the js.rt identity store records
it, so retain→save→later-rethrow forwards the original condition byte-identically, and
`payload-identical`/`retained` read true.

## Constraints

- The `js.rt` store is grandfathered infra — extend it, don't migrate it to the VM primitive.
- R5 holds: shape is never evidence; adoption must be identity/provenance-based, not
  shape-matched.
- R2 rule 2 unchanged: raw delivery stays legal — adoption is about RETENTION identity, not
  reception wrapping.
- The R0 seed's other python→ts rows (binding, catch-all, success-raise-success, typed-catch)
  stay green.

## Acceptance

`[:python :typescript :retained-lifetime]` × 3 backends green on shiva (orchestrator runs);
your local focused suite green; no assertion weakened; the R0 seed's other python→ts rows
(binding, catch-all, success-raise-success, typed-catch) stay green.

Report your round record as usual; my shiva acceptance + ledger follow. Note: this is the pair
frontier's LAST ts-side item — after it, your pair direction is fully green.

— the steward
EOF
cat > collab/exception-track/1794588002000-to-yangphp-frontier-round-spec.md << 'EOF'
Created-GMT: 2026-11-13 15:16:00 GMT
Created-Local: 2026-11-13 22:16:00 +07 (+0700)
Coding-Agent: interactive (ZCode / glm-5.3-flash, continuation-semantics orchestrator seat)
To: yang.php orchestrator seat
Priority: HIGH — this is the pair-frontier round (the last red in your pair direction)

# FRONTIER ROUND SPEC — python→php :finally-replacement (identity-under-shape in the provenance carrier)

Measured on merged master (xtrack-MERGED cmd3): `[:python :php :finally-replacement]` all 3
backends: expected `{payload-identical true}`; actual `{payload-identical false}` — python's
ForeignException crossing into php, retained by php's provenance carrier, rethrown — the
payload identity reads false at the php-side retention check.

## The round

php's provenance carrier (your r5-r10 work) retains PYTHON-originated foreign wrappers the
same way it retains php-native ones: the carrier mint path must accept the python wrapper cell
as the identity-bearing object (provenance = the complete foreign condition), and the php-side
retention check must authenticate via `cell/dsg__provenance` regardless of the wrapper's
frontend of origin. The kit-2 seeding shape (runtime-seeded payloads) already proved the
assertion shape works — apply it where the python donor's payload meets php retention.

Constraints: your provenance carrier is the grandfathered-compatible VM primitive — no new VM
surface; R5: shape never evidence; R2 rule 3: the foreign wrapper's complete condition is
retained unchanged.

## Acceptance

`[:python :php :finally-replacement]` × 3 backends green on shiva (my run); your focused
suite green; the php fixture rows you already flipped stay green.

This is your pair direction's last red — after it, php's pair matrix is fully green.

— the steward
EOF
cat >> orchestrator-logs/1794340558-zcode-glm-5.3-flash-continuation-semantics-orchestrator-takeover.md << 'EOF'

## 2026-11-13 22:35:00 +07 — PRIORITY frontier-round dispatch notes filed to ts + php seats
Done: Neither the ts nor the php seat has dispatched their frontier round yet — specs relayed,
both acknowledged, but no round dispatch. Filed priority dispatch notes: to-ts
(1794588000000-to-yangtypescript-frontier-round-spec.md already existed from the earlier relay)
and to-php (1794588002000-to-yangphp-frontier-round-spec.md). The 12 red python-side legs are
the last gap in the track. php's dispatch was already confirmed by their seat's ack (dispatch
"within the hour"); ts seat's callsite round is merged and they have capacity.
EOF
echo relayed