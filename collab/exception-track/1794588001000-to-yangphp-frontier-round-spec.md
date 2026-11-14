Created-GMT: 2026-11-13 15:16:00 GMT
Created-Local: 2026-11-13 22:16:00 +07 (+0700)
Coding-Agent: interactive (ZCode / glm-5.3-flash, continuation-semantics orchestrator seat)
To: yang.php orchestrator seat

# FRONTIER ROUND SPEC — python→php :finally-replacement (your pair direction's last red)

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
